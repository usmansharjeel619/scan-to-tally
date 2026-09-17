package com.acme.scantotally.data

import android.content.Context
import com.acme.scantotally.feedback.Beep
import com.acme.scantotally.scan.BarcodeRegistry
import com.acme.scantotally.scan.Outcome
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.scan.rejectMessage
import com.acme.scantotally.scan.tailOf
import com.acme.scantotally.scan.wrongBarcodeMessage
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * The scan decision, made ON THE DEVICE.
 *
 * The relay makes the same decision again when the line reaches it -- a scan
 * arriving over HTTP is untrusted input. Deciding here as well is what lets the
 * operator keep working through a dead access point, and what makes the beep
 * instant instead of a round trip away.
 */
data class ScanDecision(
    val outcome: Outcome2,
    val beep: Beep,
    val message: String,
    val pid: String = "",
    val boxSerial: String = "",
    val labelQty: Double = 0.0,
    val stockItemName: String = "",
    val description: String = "",
    val unit: String = "",
    val mfgDate: String? = null,
    val available: Double? = null,
    val availableAsOf: Long? = null,
    val orderPending: Double? = null,
    val editLineId: Long? = null,
    val overridable: Boolean = false,
    val flags: List<String> = emptyList(),
    val raw: String = "",
    val symbology: String = "",
    /**
     * What the price list says this is, when Tally has no item for it. The
     * operator confirms rather than types -- they supply only what the
     * catalogue cannot know: unit and batch tracking.
     */
    val catalogueDescription: String? = null,
)

enum class Outcome2 { ACCEPT, FLAGGED, DUPLICATE, WRONG_BARCODE, REJECT }

data class QtyCheck(
    val ok: Boolean,
    val error: String? = null,
    val warning: String? = null,
    val available: Double = 0.0,
    val orderPending: Double = 0.0,
)

private const val EPS = 1e-4

private fun fmt(v: Double): String =
    if (abs(v - v.toLong()) < EPS) v.toLong().toString() else String.format("%.3f", v).trimEnd('0').trimEnd('.')

class Repository(context: Context, private val api: RelayApi?) {

    private val dao = ScanDatabase.get(context).dao()
    private val registry = BarcodeRegistry.default

    // --- sessions ---

    /** The id is minted here so it survives being offline and never changes. */
    /** Picks up a session that is already open, for the queue's Open button. */
    suspend fun resumeSession(sessionId: String): String? =
        dao.session(sessionId)?.takeIf { it.state == "DRAFT" }?.id

    suspend fun openSession(kind: String, godown: String, party: String = "", salesOrder: String = ""): String {
        // Clear out sessions someone opened and backed out of. Five minutes is
        // longer than anyone spends deciding not to scan, and short enough that
        // the queue stays honest.
        runCatching { dao.purgeEmptyDrafts(System.currentTimeMillis() - 5 * 60_000) }

        val id = UUID.randomUUID().toString()
        dao.upsertSession(
            SessionEntity(id = id, kind = kind, godown = godown, party = party, salesOrder = salesOrder),
        )
        runCatching {
            api?.createSession(CreateSessionRequest(id, kind, party, salesOrder, godown))
        }
        return id
    }

    fun linesFlow(sessionId: String): Flow<List<SessionLineEntity>> = dao.linesFlow(sessionId)
    fun sessionFlow(sessionId: String): Flow<SessionEntity?> = dao.sessionFlow(sessionId)
    fun openSessionsFlow(): Flow<List<SessionEntity>> = dao.openSessionsFlow()
    fun lineSummariesFlow(): Flow<List<SessionSummary>> = dao.lineSummariesFlow()
    fun pendingCountFlow(): Flow<Int> = dao.pendingCountFlow()
    fun failedCountFlow(): Flow<Int> = dao.failedCountFlow()
    fun ordersFlow(): Flow<List<SalesOrderEntity>> = dao.ordersFlow()

    suspend fun orderLines(order: String) = dao.orderLines(order)
    suspend fun searchItems(q: String) = dao.searchItems(q)

    // --- resolution ---

    private data class Resolved(
        val stockItemName: String, val unit: String,
        val description: String, val hasBatches: Boolean,
    )

    /**
     * Maps a scanned PID onto a Tally stock item.
     *
     * An explicit binding always wins: it is the one a human confirmed. The
     * fallbacks below save a supervisor from confirming the obvious cases.
     */
    private suspend fun resolve(pid: String): Resolved? {
        dao.binding(pid)?.let { b ->
            val item = dao.item(b.stockItemName)
            return Resolved(b.stockItemName, item?.baseUnits ?: "", b.description, item?.hasBatches ?: false)
        }
        dao.itemByAnyCode(pid)?.let {
            return Resolved(it.name, it.baseUnits, "", it.hasBatches)
        }
        // Many Tally item names begin with the PID ("4098-9792 SSD SENSOR BASE").
        // Requires a UNIQUE match: an ambiguous prefix silently picks the wrong
        // product, which is worse than no match at all.
        val prefix = dao.searchItems(pid).filter { it.name.startsWith("$pid ") }
        if (prefix.size == 1) {
            val it = prefix.first()
            return Resolved(it.name, it.baseUnits, "", it.hasBatches)
        }
        return null
    }

    // --- incoming ---

    /**
     * Incoming is scan-only: quantity comes from the barcode, so one scan is
     * one complete line, and the operator never stops to type.
     */
    suspend fun scanIncoming(
        sessionId: String, scan: RawScan, overrideDuplicate: Boolean = false,
    ): ScanDecision {
        val parsed = registry.parse(scan.symbology, scan.data)
        earlyReject(parsed)?.let { return it }
        val box = parsed.box!!

        val flags = mutableListOf<String>()
        if (scan.source == RawScan.Source.MANUAL) flags += "MANUAL"

        // 1. Already on this receipt: a hard block, nothing to dismiss.
        dao.lineForBox(sessionId, box.pid, box.boxSerial)?.let { existing ->
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} is already on this receipt (${fmt(existing.qty)}).",
                editLineId = existing.id, raw = scan.data, symbology = scan.symbology,
            )
        }

        // 2. On another receipt that has not been saved yet. A hard block: the
        //    box number identifies one physical carton, so this is the same
        //    pallet being scanned a second time, and there is nothing to
        //    override -- the earlier receipt is still there to be finished.
        val elsewhere = dao.boxInAnotherSession(box.pid, box.boxSerial, sessionId)
        if (elsewhere != null && elsewhere.state != "POSTED") {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} is already counted (${fmt(elsewhere.qty)}) on " +
                    "an ${elsewhere.state.lowercase()} receipt. Finish that one instead.",
                raw = scan.data, symbology = scan.symbology,
            )
        }

        // 3. Received in an earlier session. Returns and reprinted labels are
        //    real, so this one the operator may deliberately override.
        val historical = dao.receivedBox(box.pid, box.boxSerial)
        if (historical == null && elsewhere != null && !overrideDuplicate) {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} was already received on this device. " +
                    "Accept again only if this is a return.",
                overridable = true, raw = scan.data, symbology = scan.symbology,
            )
        }
        if (historical != null && !overrideDuplicate) {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} was already received on " +
                    "${historical.receivedAt.take(10)}. Accept again only if this is a return.",
                overridable = true, raw = scan.data, symbology = scan.symbology,
            )
        }
        if (historical != null || elsewhere != null) flags += "DUPLICATE_OVERRIDE"

        // 4. An unknown product does NOT stop the operator. The count is right
        //    the moment it is scanned; the item is created in Tally from what
        //    the operator types next, while they carry on unloading.
        val resolved = resolve(box.pid)
        if (resolved == null) flags += "UNRESOLVED_PID"
        else if (!resolved.hasBatches) flags += "NO_BATCH_SUPPORT"

        // Not in Tally -- but the price list may still know what it is.
        val cat = if (resolved == null) dao.catalogue(box.pid) else null

        val flagged = flags.any { it != "MANUAL" }
        return ScanDecision(
            outcome = if (flagged) Outcome2.FLAGGED else Outcome2.ACCEPT,
            beep = if (flagged) Beep.FLAGGED else Beep.ACCEPT,
            message = resolved?.let { "${it.description.ifEmpty { it.stockItemName }} - ${box.qty}" }
                ?: cat?.let { "${it.description} - ${box.qty} counted, adding to Tally" }
                ?: "New product ${box.pid} - ${box.qty} counted, tell me what it is",
            catalogueDescription = cat?.description,
            pid = box.pid, boxSerial = box.boxSerial, labelQty = box.qty.toDouble(),
            stockItemName = resolved?.stockItemName ?: "",
            description = resolved?.description ?: "",
            unit = resolved?.unit ?: "",
            mfgDate = box.mfgDateString, flags = flags,
            raw = scan.data, symbology = scan.symbology,
        )
    }

    // --- outgoing ---

    /**
     * Outgoing refuses much more than incoming, on purpose: catching a mistake
     * at the dock rather than at the customer is the entire point.
     *
     * This call establishes the CEILING. The employee types the quantity next.
     */
    suspend fun scanOutgoing(sessionId: String, salesOrder: String, godown: String, scan: RawScan): ScanDecision {
        val parsed = registry.parse(scan.symbology, scan.data)
        earlyReject(parsed)?.let { return it }
        val box = parsed.box!!

        val resolved = resolve(box.pid) ?: return ScanDecision(
            Outcome2.REJECT, Beep.REJECT,
            "Product ${box.pid} is not mapped to a Tally item. A supervisor must map it first.",
            raw = scan.data, symbology = scan.symbology,
        )

        val orderLine = dao.orderLines(salesOrder).firstOrNull { it.stockItemName == resolved.stockItemName }
            ?: return ScanDecision(
                Outcome2.REJECT, Beep.REJECT,
                "${resolved.description.ifEmpty { resolved.stockItemName }} is not on order $salesOrder.",
                raw = scan.data, symbology = scan.symbology,
            )

        val balance = dao.balance(resolved.stockItemName, box.boxSerial, godown)
        val onHand = balance?.closingQty ?: 0.0
        if (onHand <= EPS) {
            return ScanDecision(
                Outcome2.REJECT, Beep.REJECT,
                if (balance != null) "Box ${tailOf(box.boxSerial)} is empty in $godown."
                else "Box ${tailOf(box.boxSerial)} is not in stock in $godown.",
                available = 0.0, availableAsOf = balance?.syncedAt,
                raw = scan.data, symbology = scan.symbology,
            )
        }

        val existing = dao.lineForBox(sessionId, box.pid, box.boxSerial)
        val committed = dao.committedForBox(sessionId, box.pid, box.boxSerial, existing?.id ?: -1)
        val available = max(0.0, onHand - committed)

        if (available <= EPS) {
            return ScanDecision(
                Outcome2.REJECT, Beep.REJECT,
                "All ${fmt(onHand)} of box ${tailOf(box.boxSerial)} is already on this despatch.",
                available = 0.0, availableAsOf = balance?.syncedAt, editLineId = existing?.id,
                raw = scan.data, symbology = scan.symbology,
            )
        }

        val itemCommitted = dao.committedForItem(sessionId, resolved.stockItemName, existing?.id ?: -1)
        val orderPending = max(0.0, orderLine.orderedQty - orderLine.deliveredQty - itemCommitted)

        return ScanDecision(
            outcome = Outcome2.ACCEPT, beep = Beep.ACCEPT,
            message = "${resolved.description.ifEmpty { resolved.stockItemName }} - enter quantity",
            pid = box.pid, boxSerial = box.boxSerial, labelQty = box.qty.toDouble(),
            stockItemName = resolved.stockItemName, description = resolved.description,
            unit = resolved.unit.ifEmpty { orderLine.unit },
            mfgDate = box.mfgDateString,
            available = available, availableAsOf = balance?.syncedAt,
            orderPending = orderPending, editLineId = existing?.id,
            raw = scan.data, symbology = scan.symbology,
        )
    }

    /**
     * Checks a typed outgoing quantity against the three ceilings.
     *
     * Called live as the operator types so Confirm can be disabled rather than
     * the number rejected afterwards. There must be no way to submit an invalid
     * quantity.
     */
    suspend fun checkOutgoingQty(
        sessionId: String, salesOrder: String, godown: String,
        pid: String, boxSerial: String, stockItemName: String,
        qty: Double, excludeLineId: Long = -1,
    ): QtyCheck {
        val onHand = dao.balance(stockItemName, boxSerial, godown)?.closingQty ?: 0.0
        val committed = dao.committedForBox(sessionId, pid, boxSerial, excludeLineId)
        val available = max(0.0, onHand - committed)

        val orderLine = dao.orderLines(salesOrder).firstOrNull { it.stockItemName == stockItemName }
        val itemCommitted = dao.committedForItem(sessionId, stockItemName, excludeLineId)
        val orderPending = orderLine?.let {
            max(0.0, it.orderedQty - it.deliveredQty - itemCommitted)
        } ?: 0.0

        if (qty <= 0) return QtyCheck(false, "Enter a quantity.", available = available, orderPending = orderPending)

        // Ceiling 1: the box. A hard block -- the one that must never pass.
        if (qty - available > EPS) {
            return QtyCheck(
                false, "Only ${fmt(available)} left in box ${tailOf(boxSerial)}.",
                available = available, orderPending = orderPending,
            )
        }
        // Ceiling 3: the order. A warning, not a block -- deliberate
        // over-shipping within tolerance is a real business decision.
        if (qty - orderPending > EPS) {
            return QtyCheck(
                true, warning = "This exceeds what order $salesOrder still has outstanding (${fmt(orderPending)}).",
                available = available, orderPending = orderPending,
            )
        }
        return QtyCheck(true, available = available, orderPending = orderPending)
    }

    // --- stock check ---

    /**
     * A scan during a count.
     *
     * Unlike outgoing, an unknown product does not stop anyone: a box on the
     * floor Tally has never heard of is exactly what a stock check exists to
     * find.
     */
    suspend fun scanStockCheck(sessionId: String, godown: String, scan: RawScan, blind: Boolean = true): ScanDecision {
        val parsed = registry.parse(scan.symbology, scan.data)
        earlyReject(parsed)?.let { return it }
        val box = parsed.box!!

        dao.lineForBox(sessionId, box.pid, box.boxSerial)?.let { existing ->
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} is already counted (${fmt(existing.qty)}).",
                editLineId = existing.id, raw = scan.data, symbology = scan.symbology,
            )
        }

        val resolved = resolve(box.pid)
        val flags = buildList {
            if (scan.source == RawScan.Source.MANUAL) add("MANUAL")
            if (resolved == null) add("UNRESOLVED_PID")
        }

        // Only reveal the book figure when blind counting is turned off:
        // showing it anchors the count, and a count that confirms the book is
        // worth nothing.
        val onHand = if (resolved != null && !blind) {
            dao.balance(resolved.stockItemName, box.boxSerial, godown)?.closingQty ?: 0.0
        } else null

        return ScanDecision(
            outcome = if (resolved != null) Outcome2.ACCEPT else Outcome2.FLAGGED,
            beep = if (resolved != null) Beep.ACCEPT else Beep.FLAGGED,
            message = resolved?.let { "${it.description.ifEmpty { it.stockItemName }} - counted ${box.qty}" }
                ?: "Box ${tailOf(box.boxSerial)} of ${box.pid} is not in Tally at all - counted ${box.qty}",
            pid = box.pid, boxSerial = box.boxSerial, labelQty = box.qty.toDouble(),
            stockItemName = resolved?.stockItemName ?: "",
            description = resolved?.description ?: "",
            unit = resolved?.unit ?: "",
            mfgDate = box.mfgDateString, available = onHand, flags = flags,
            raw = scan.data, symbology = scan.symbology,
        )
    }

    // --- writing lines ---

    suspend fun commitLine(sessionId: String, d: ScanDecision, qty: Double = d.labelQty): Long {
        val id = dao.insertLine(
            SessionLineEntity(
                sessionId = sessionId, pid = d.pid, boxSerial = d.boxSerial, qty = qty,
                unit = d.unit, stockItemName = d.stockItemName, description = d.description,
                mfgDate = d.mfgDate, rawPayload = d.raw, symbology = d.symbology,
                flags = d.flags.joinToString(","),
            ),
        )
        // Mirrored to the relay now if there is signal, and marked so that it
        // does not need to be if there is not. The voucher is built from the
        // relay's copy, so a line that never arrives is a carton that silently
        // does not get received -- the outbox below is what prevents that.
        val mirrored = runCatching {
            api?.addLine(sessionId, lineRequest(d, qty)) == true
        }.getOrDefault(false)
        if (mirrored) dao.markLineSynced(id)

        return id
    }

    private fun lineRequest(d: ScanDecision, qty: Double) = LineRequest(
        pid = d.pid, boxSerial = d.boxSerial, qty = qty, raw = d.raw,
        symbology = d.symbology, mfgDate = d.mfgDate,
        manual = d.flags.contains("MANUAL"),
    )

    private fun lineRequest(l: SessionLineEntity) = LineRequest(
        pid = l.pid, boxSerial = l.boxSerial, qty = l.qty, raw = l.rawPayload,
        symbology = l.symbology, mfgDate = l.mfgDate,
        manual = l.flags.contains("MANUAL"),
    )

    /**
     * Delivers every scan the relay has not acknowledged yet.
     *
     * The dock keeps working with no signal, which is the whole point, but it
     * means the relay can be several cartons behind. Nothing may be submitted
     * until it has caught up: a voucher built from a partial set of lines is
     * worse than no voucher, because it looks like a successful receipt.
     *
     * Mirroring is idempotent on (part number, box number), so re-sending a
     * line the relay already has is harmless.
     */
    suspend fun pushPendingLines(sessionId: String): Boolean {
        val pending = dao.unsyncedLines(sessionId)
        if (pending.isEmpty()) return true
        val client = api ?: return false

        // The session itself may never have reached the relay either -- the
        // phone mints the id precisely so that it can be created late. Creating
        // it again is a no-op there, so this is unconditional rather than
        // guessing from local state.
        val s = dao.session(sessionId) ?: return false
        val created = runCatching {
            client.createSession(
                CreateSessionRequest(s.id, s.kind, s.party, s.salesOrder, s.godown),
            )
            true
        }.getOrDefault(false)
        if (!created) return false

        for (l in pending) {
            val ok = runCatching { client.addLine(sessionId, lineRequest(l)) }
                .getOrDefault(false)
            if (!ok) return false
            dao.markLineSynced(l.id)
        }
        return true
    }

    /**
     * Records a new product, and binds it locally straight away.
     *
     * The local binding matters: the next carton of the same product in the
     * SAME session must resolve rather than prompting again. Nobody wants to be
     * asked what a thing is eighteen times while unloading a pallet.
     */
    suspend fun proposeNewItem(
        sessionId: String, pid: String, description: String,
        unit: String, raw: String, operator: String,
    ): Boolean {
        val name = "$pid $description"
        dao.upsertBindings(listOf(PidBindingEntity(pid, name, description)))
        dao.upsertItems(listOf(StockItemEntity(name = name, baseUnits = unit, hasBatches = true)))
        // Backfill the lines already scanned for this PID in this session.
        for (l in dao.lines(sessionId)) {
            if (l.pid == pid && l.stockItemName.isEmpty()) {
                dao.setLineQty(l.id, l.qty, l.flags.replace("UNRESOLVED_PID", "PROPOSED").trim(','))
            }
        }
        return runCatching {
            api?.proposeItem(
                ProposeItemRequest(
                    pid = pid, description = description, baseUnits = unit,
                    sessionId = sessionId, raw = raw, proposedBy = operator,
                ),
            )?.ok ?: false
        }.getOrDefault(false)
    }

    suspend fun updateLineQty(lineId: Long, qty: Double, flags: String) =
        dao.setLineQty(lineId, qty, flags)

    suspend fun deleteLine(lineId: Long) = dao.deleteLine(lineId)

    /**
     * Closes the session.
     *
     * Marked QUEUED locally FIRST. Whether the relay is reachable is beside the
     * point: the work is durable, the badge shows it pending, and the sync
     * worker delivers it whenever there is signal again.
     */
    suspend fun submit(sessionId: String, scope: String = "PARTIAL"): SubmitResponse? {
        dao.setSessionState(sessionId, "QUEUED")

        // Never submit ahead of the scans. If the relay is missing even one
        // line, leave the session queued and let the retry deliver the lot --
        // the operator sees it waiting, which is honest, rather than a voucher
        // posting short.
        if (!pushPendingLines(sessionId)) return null

        return runCatching { api?.submit(sessionId, scope) }.getOrNull()
    }

    /**
     * Pushes anything stranded on the device, for every queued session.
     *
     * Called whenever the app comes back to the foreground, which in practice
     * is when a phone that was out of range at the far end of the warehouse
     * comes back to the office.
     */
    suspend fun drainOutbox(): Int {
        var delivered = 0
        for (s in dao.sessionsInState("QUEUED")) {
            if (!pushPendingLines(s.id)) continue
            runCatching { api?.submit(s.id) }.getOrNull()?.let { delivered++ }
        }
        return delivered
    }

    suspend fun refreshSessionState(sessionId: String) {
        runCatching {
            val s = api?.sessionState(sessionId)?.session ?: return
            dao.setSessionResult(sessionId, s.state, s.tallyVoucherId, s.errorClass, s.errorMessage)
        }
    }

    /**
     * Tally's health, as the connector last reported it.
     *
     * Null when the relay cannot be reached at all, which is a different thing
     * from Tally being down and is shown differently.
     */
    suspend fun tallyHealth(): String? = runCatching {
        api?.status()?.connector?.health
    }.getOrNull()

    // --- master sync ---

    /** Pulls everything the device needs to keep working without a network. */
    suspend fun syncMasters(): Boolean = runCatching {
        val s = api?.sync() ?: return false

        dao.upsertItems(s.items.map {
            StockItemEntity(it.name, it.alias, it.partNo, it.baseUnits, it.hasBatches != 0)
        })
        dao.upsertBindings(s.bindings.map { PidBindingEntity(it.pid, it.stockItemName, it.description) })

        // Balances are replaced wholesale: a batch that has dropped to zero must
        // disappear, not linger at its old value and let someone scan an empty box.
        dao.clearBalances()
        val now = System.currentTimeMillis()
        dao.upsertBalances(s.balances.map {
            BatchBalanceEntity(it.stockItemName, it.batchName, it.godownName, it.closingQty, it.unit, now)
        })

        dao.clearOrderLines()
        dao.clearOrders()
        dao.upsertOrders(s.orders.map { SalesOrderEntity(it.voucherNumber, it.partyName, it.orderDate) })
        dao.upsertOrderLines(
            s.orders.flatMap { o ->
                o.lines.map {
                    SalesOrderLineEntity(o.voucherNumber, it.stockItemName, it.orderedQty, it.deliveredQty, it.unit)
                }
            },
        )

        dao.upsertReceivedBoxes(s.receivedBoxes.map { ReceivedBoxEntity(it.pid, it.boxSerial, it.receivedAt) })
        dao.upsertCatalogue(s.catalogue.map { CatalogueEntity(it.pid, it.description, it.alternates) })
        true
    }.getOrDefault(false)

    /** Re-asks the relay about everything still in flight. */
    suspend fun refreshPending() {
        for (session in dao.sessionsToSync()) {
            if (session.state == "DRAFT") continue
            refreshSessionState(session.id)
        }
    }

    private fun earlyReject(parsed: com.acme.scantotally.scan.ParseResult): ScanDecision? = when {
        parsed.outcome == Outcome.WRONG_BARCODE -> ScanDecision(
            Outcome2.WRONG_BARCODE, Beep.REJECT, wrongBarcodeMessage(parsed.hint),
            raw = parsed.raw, symbology = parsed.symbology,
        )
        parsed.outcome == Outcome.REJECT -> ScanDecision(
            Outcome2.REJECT, Beep.REJECT, rejectMessage(parsed.reason),
            raw = parsed.raw, symbology = parsed.symbology,
        )
        parsed.outcome != Outcome.ACCEPT || parsed.box == null -> ScanDecision(
            Outcome2.REJECT, Beep.REJECT,
            "Label not recognised. Use manual entry if it is damaged.",
            raw = parsed.raw, symbology = parsed.symbology,
        )
        else -> null
    }
}
