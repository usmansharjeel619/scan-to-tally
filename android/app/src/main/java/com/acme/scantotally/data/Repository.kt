package com.acme.scantotally.data

import android.content.Context
import com.acme.scantotally.DeviceConfig
import com.acme.scantotally.feedback.Beep
import com.acme.scantotally.scan.BarcodeRegistry
import com.acme.scantotally.scan.FragmentKind
import com.acme.scantotally.scan.canonicalPid
import com.acme.scantotally.scan.classifyFragment
import com.acme.scantotally.scan.pidVariants
import com.acme.scantotally.scan.Outcome
import com.acme.scantotally.scan.ParsedBox
import com.acme.scantotally.scan.mfgDateFromSerial
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.scan.rejectMessage
import com.acme.scantotally.scan.tailOf
import com.acme.scantotally.scan.wrongBarcodeMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/** What happened when a new product was sent to Tally. */
data class ProposeResult(
    val ok: Boolean = false,
    val queued: Boolean = false,
    val message: String = "",
)

/** The answer to "how much of this do I have". */
data class StockLookup(
    val found: Boolean = false,
    val pid: String = "",
    val stockItemName: String = "",
    val description: String = "",
    val unit: String = "",
    val total: Double = 0.0,
    val godown: String = "",
    val boxes: List<BatchBalanceEntity> = emptyList(),
    val asOf: Long = 0L,
    val scannedBox: String = "",
    val raw: String = "",
    val message: String = "",
    /**
     * A receipt for this product has been sent but the figures predate it, so
     * whatever is shown is already out of date. Not the same as zero.
     */
    val awaitingSync: Boolean = false,
)

private const val EPS = 1e-4

private fun fmt(v: Double): String =
    if (abs(v - v.toLong()) < EPS) v.toLong().toString() else String.format("%.3f", v).trimEnd('0').trimEnd('.')

class Repository(context: Context, private val api: RelayApi?) {

    private val dao = ScanDatabase.get(context).dao()
    private val registry = BarcodeRegistry.default

    /**
     * The Tally company this handset last synced against.
     *
     * Read from the device settings rather than held in memory, because the
     * answer has to survive the app being killed with scans still on it.
     */
    private val config = DeviceConfig(context.applicationContext)

    private suspend fun configCompany(): String = config.company.first()

    /**
     * The company Tally has open, when it is not the one this phone is for.
     *
     * Null when all is well. Read by the home screen, which stops the operator
     * scanning into books that are not theirs.
     */
    var wrongCompany: String? = null
        private set

    /** The company this handset is set up for. */
    suspend fun pinnedCompany(): String = configCompany()

    /** Deliberately moves this handset to another company, and forgets the old. */
    suspend fun repinCompany(name: String) {
        wipeLocalData()
        setConfigCompany(name.trim())
    }
    private suspend fun setConfigCompany(name: String) = config.setCompany(name)

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
            SessionEntity(
                id = id, kind = kind, godown = godown, party = party,
                salesOrder = salesOrder, company = configCompany(),
            ),
        )
        runCatching {
            api?.createSession(
                CreateSessionRequest(id, kind, party, salesOrder, godown,
                    company = configCompany()),
            )
        }
        return id
    }

    fun linesFlow(sessionId: String): Flow<List<SessionLineEntity>> = dao.linesFlow(sessionId)
    fun sessionFlow(sessionId: String): Flow<SessionEntity?> = dao.sessionFlow(sessionId)
    fun recentSessionsFlow(): Flow<List<SessionEntity>> = dao.recentSessionsFlow()
    fun lineSummariesFlow(): Flow<List<SessionSummary>> = dao.lineSummariesFlow()
    fun pendingCountFlow(): Flow<Int> = dao.pendingCountFlow()
    fun failedCountFlow(): Flow<Int> = dao.failedCountFlow()
    fun ordersFlow(): Flow<List<SalesOrderEntity>> = dao.ordersFlow()
    fun orderOutstandingFlow(): Flow<List<OrderOutstanding>> = dao.orderOutstandingFlow()

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
     * fallbacks below settle the obvious cases without asking anyone.
     */
    private suspend fun resolve(pid: String): Resolved? {
        // Both spellings, scanned form first.
        //
        // A carton may print 4098-9792 or 40989792 for the same product, and an
        // item may already sit in Tally under either. The payload is NOT
        // rewritten -- what was scanned is what is stored -- so the reconciling
        // happens here, where a product is looked up.
        for (form in pidVariants(pid)) {
            resolveOne(form)?.let { return it }
        }
        return null
    }

    private suspend fun resolveOne(pid: String): Resolved? {
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

    // --- stock lookup ---

    /**
     * What Tally holds for the product on this label.
     *
     * A different job from a stock take, and deliberately a different screen:
     * this one asks a question and changes nothing. No session is opened, so it
     * leaves no receipt behind, and it answers entirely from the synced figures
     * -- an operator standing in an aisle with no signal still gets an answer,
     * with the time it was last refreshed shown so they can judge it.
     */
    suspend fun lookupStock(godown: String, scan: RawScan): StockLookup {
        // Only the part number matters here. The question is "how much of this
        // product is there", so anything carrying a part number answers it:
        // the bare nnnn-nnnn barcode, or the long combined code, from which the
        // part number is simply taken. Requiring the combined code would have
        // made this unusable on every carton that has no such barcode.
        val parsed = registry.parse(scan.symbology, scan.data)
        val fromCombined = parsed.box
        val fragment = classifyFragment(scan.data)

        val pid = fromCombined?.pid
            ?: fragment.value.takeIf { fragment.kind == FragmentKind.PRODUCT }
            ?: return StockLookup(
                message = "Scan the product barcode (like 4098-9792).", raw = scan.data,
            )

        val resolved = resolve(pid) ?: return StockLookup(
            pid = pid, scannedBox = fromCombined?.boxSerial.orEmpty(), raw = scan.data,
            message = "$pid is not in Tally yet.",
        )

        val boxes = dao.balancesFor(resolved.stockItemName, godown)
        val asOf = boxes.maxOfOrNull { it.syncedAt } ?: 0L

        return StockLookup(
            found = true,
            pid = pid,
            stockItemName = resolved.stockItemName,
            description = resolved.description.ifEmpty { resolved.stockItemName },
            unit = resolved.unit.ifEmpty { boxes.firstOrNull()?.unit.orEmpty() },
            total = boxes.sumOf { it.closingQty },
            godown = godown,
            boxes = boxes,
            asOf = asOf,
            awaitingSync = dao.receivedSince(pid, asOf) > 0,
            scannedBox = fromCombined?.boxSerial.orEmpty(),
            raw = scan.data,
        )
    }

    // --- incoming ---

    /**
     * Incoming is scan-only: quantity comes from the barcode, so one scan is
     * one complete line, and the operator never stops to type.
     */
    suspend fun scanIncoming(
        sessionId: String, scan: RawScan,
    ): ScanDecision {
        val parsed = registry.parse(scan.symbology, scan.data)
        earlyReject(parsed)?.let { return it }
        return decideIncoming(sessionId, parsed.box!!, scan.data, scan.symbology,
            manual = scan.source == RawScan.Source.MANUAL)
    }

    /**
     * Receives a box assembled from separate barcodes.
     *
     * The Tyco, KAC and US/UK Simplex cartons have no combined code, so the
     * product, box id and quantity arrive one at a time -- some scanned, some
     * typed. Once all three are present the box is exactly as real as one that
     * came off a single barcode, so it goes through the IDENTICAL checks:
     * duplicate against this receipt and every other, resolution to a Tally
     * item, and the new-product prompt.
     *
     * It cannot go through the combined parser to get there: that requires a
     * 16-digit serial, and a Tyco box id is six characters. Sharing everything
     * after the parse is the point -- the rules must not fork by label type.
     */
    suspend fun receiveAssembled(
        sessionId: String,
        pid: String,
        boxSerial: String,
        qty: Int,
        raw: String,
        /** What the carton calls it, when the camera could read it. */
        labelDescription: String? = null,
    ): ScanDecision = decideIncoming(
        sessionId,
        ParsedBox(
            pid = pid.trim(),
            boxSerial = boxSerial.trim(),
            qty = qty,
            mfgDate = mfgDateFromSerial(boxSerial.trim()),
        ),
        raw = raw,
        symbology = "ASSEMBLED",
        manual = true,
        labelDescription = labelDescription,
    )

    private suspend fun decideIncoming(
        sessionId: String,
        box: ParsedBox,
        raw: String,
        symbology: String,
        manual: Boolean,
        labelDescription: String? = null,
    ): ScanDecision {
        val scan = RawScan(raw, symbology, RawScan.Source.HARDWARE)

        val flags = mutableListOf<String>()
        if (manual) flags += "MANUAL"

        // 1. Already on this receipt: a hard block, nothing to dismiss.
        dao.lineForBox(sessionId, canonicalPid(box.pid), box.boxSerial)?.let { existing ->
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} is already on this entry (${fmt(existing.qty)}).",
                editLineId = existing.id, raw = scan.data, symbology = scan.symbology,
            )
        }

        // 2. On another receipt that has not been saved yet. A hard block: the
        //    box number identifies one physical carton, so this is the same
        //    pallet being scanned a second time, and there is nothing to
        //    override -- the earlier receipt is still there to be finished.
        val elsewhere = dao.boxInAnotherSession(canonicalPid(box.pid), box.boxSerial, sessionId)
        if (elsewhere != null && elsewhere.state != "POSTED") {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} is already counted (${fmt(elsewhere.qty)}) on " +
                    "an ${elsewhere.state.lowercase()} entry. Finish that one instead.",
                raw = scan.data, symbology = scan.symbology,
            )
        }

        // 3. Received in an earlier session. Returns and reprinted labels are
        //    real, so this one the operator may deliberately override.
        val historical = dao.receivedBox(canonicalPid(box.pid), box.boxSerial)
        // Already received, here or anywhere. A hard refusal with nothing to
        // dismiss.
        //
        // This used to offer "accept again if this is a return", and an
        // override on the one rule that keeps stock honest is an override that
        // gets used -- at the end of a shift, on the box that will not scan,
        // by whoever is in a hurry. (part number, box number) identifies one
        // physical carton: receiving it twice is receiving stock that does not
        // exist. A genuine return is a different transaction and belongs in
        // Tally, not in a dialog at the dock.
        if (historical == null && elsewhere != null) {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} has already been received. " +
                    "It cannot be received twice.",
                raw = scan.data, symbology = scan.symbology,
            )
        }
        if (historical != null) {
            return ScanDecision(
                Outcome2.DUPLICATE, Beep.DUPLICATE,
                "Box ${tailOf(box.boxSerial)} was already received on " +
                    "${historical.receivedAt.take(10)}. It cannot be received twice.",
                raw = scan.data, symbology = scan.symbology,
            )
        }

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
                // Not an instruction: naming it is optional, and the box is
                // counted either way. An unnamed product reaches Tally under
                // its part number rather than not reaching it at all.
                ?: "New product ${box.pid} - ${box.qty} counted, name it if you can",
            // The price list first: it is the business's own naming and is
            // what every other carton of this product will be called. The
            // label is the fallback for a product nobody has listed at all.
            catalogueDescription = cat?.description ?: labelDescription,
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
        return decideOutgoing(sessionId, salesOrder, godown, parsed.box!!, scan.data, scan.symbology)
    }

    /**
     * Despatches a box identified from separate barcodes.
     *
     * A carton received as HFE283 has to be sendable again, and its label has
     * no combined code to scan on the way out either. The checks are the same
     * ones -- they have to be, or a box could leave under rules it could not
     * have arrived under.
     */
    suspend fun despatchAssembled(
        sessionId: String, salesOrder: String, godown: String,
        pid: String, boxSerial: String, raw: String,
    ): ScanDecision = decideOutgoing(
        sessionId, salesOrder, godown,
        ParsedBox(pid = pid.trim(), boxSerial = boxSerial.trim(), qty = 0),
        raw = raw, symbology = "ASSEMBLED",
    )

    private suspend fun decideOutgoing(
        sessionId: String, salesOrder: String, godown: String,
        box: ParsedBox, raw: String, symbology: String,
    ): ScanDecision {
        val scan = RawScan(raw, symbology, RawScan.Source.HARDWARE)

        val resolved = resolve(box.pid) ?: return ScanDecision(
            Outcome2.REJECT, Beep.REJECT,
            "Product ${box.pid} is not in Tally, so it cannot be despatched.",
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

        val existing = dao.lineForBox(sessionId, canonicalPid(box.pid), box.boxSerial)
        val committed = dao.committedForBox(sessionId, canonicalPid(box.pid), box.boxSerial, existing?.id ?: -1)
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
        val sentElsewhere = dao.despatchedElsewhere(salesOrder, resolved.stockItemName, sessionId)
        val orderPending = max(
            0.0,
            orderLine.orderedQty - max(orderLine.deliveredQty, sentElsewhere) - itemCommitted,
        )

        // Nothing left on the order: say so at the scan rather than letting the
        // operator type a quantity that cannot be accepted.
        if (orderPending <= EPS) {
            return ScanDecision(
                Outcome2.REJECT, Beep.REJECT,
                "Order $salesOrder has nothing left outstanding for " +
                    resolved.description.ifEmpty { resolved.stockItemName } + ".",
                available = available, availableAsOf = balance?.syncedAt,
                raw = scan.data, symbology = scan.symbology,
            )
        }

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
        val committed = dao.committedForBox(sessionId, canonicalPid(pid), boxSerial, excludeLineId)
        val available = max(0.0, onHand - committed)

        val orderLine = dao.orderLines(salesOrder).firstOrNull { it.stockItemName == stockItemName }
        val itemCommitted = dao.committedForItem(sessionId, stockItemName, excludeLineId)
        // Two sources for what has already gone out, and the LARGER is used
        // rather than the sum. Tally's figure is authoritative but a sync late;
        // this device's own record is immediate but blind to anything sent
        // elsewhere. Adding them double-counts, taking the larger never
        // under-counts, and under-counting is what lets stock leave twice.
        val sentElsewhere = dao.despatchedElsewhere(salesOrder, stockItemName, sessionId)
        val orderPending = orderLine?.let {
            max(0.0, it.orderedQty - max(it.deliveredQty, sentElsewhere) - itemCommitted)
        } ?: 0.0

        if (qty <= 0) return QtyCheck(false, "Enter a quantity.", available = available, orderPending = orderPending)

        // Ceiling 1: the box. A hard block -- the one that must never pass.
        if (qty - available > EPS) {
            return QtyCheck(
                false, "Only ${fmt(available)} left in box ${tailOf(boxSerial)}.",
                available = available, orderPending = orderPending,
            )
        }
        // Ceiling 3: the order. A hard block, like the box.
        //
        // It was a warning, on the reasoning that over-shipping within
        // tolerance is a real business decision. In practice six went out
        // against an order for four and the screen said it was fine.
        if (qty - orderPending > EPS) {
            return QtyCheck(
                false,
                if (orderPending <= EPS) "Order $salesOrder has nothing left outstanding."
                else "Order $salesOrder has only ${fmt(orderPending)} left outstanding.",
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

        dao.lineForBox(sessionId, canonicalPid(box.pid), box.boxSerial)?.let { existing ->
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
                CreateSessionRequest(s.id, s.kind, s.party, s.salesOrder, s.godown,
                    company = s.company),
            )
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
    ): ProposeResult {
        val name = "$pid $description"
        dao.upsertBindings(listOf(PidBindingEntity(pid, name, description, provisional = true)))
        dao.upsertItems(listOf(StockItemEntity(name = name, baseUnits = unit, hasBatches = true)))
        // Fill in the cartons already scanned for this product, so they stop
        // reading as undescribed the moment the operator has described them.
        dao.fillInProduct(pid, name, unit, description)
        val resp = runCatching {
            api?.proposeItem(
                ProposeItemRequest(
                    pid = pid, description = description, baseUnits = unit,
                    sessionId = sessionId, raw = raw, proposedBy = operator,
                ),
            )
        }.getOrNull()

        // No answer at all is not a failure: the relay will be told when there
        // is signal again, and the carton is already counted either way.
        if (resp == null) return ProposeResult(queued = true)
        if (!resp.ok) {
            // It will not be created, so the guess must not survive -- or the
            // rest of the pallet scans against a product that does not exist.
            dao.deleteBinding(pid)
            dao.unfillProduct(pid)
            return ProposeResult(ok = false, message = resp.message.orEmpty())
        }
        return ProposeResult(ok = true, message = resp.message.orEmpty())
    }

    /**
     * Whether this payload is a whole box on its own.
     *
     * The combined Simplex code carries product, box and quantity in one scan,
     * so it commits immediately and nothing is typed. Everything else has to be
     * assembled field by field, and the screen needs to know which it is
     * holding before it decides what to do with a scan.
     */
    fun parseWholeBox(scan: RawScan): ParsedBox? {
        val parsed = registry.parse(scan.symbology, scan.data)
        return if (parsed.outcome == Outcome.ACCEPT) parsed.box else null
    }

    /** The receipt itself, for callers that need to know which flow it is. */
    suspend fun session(sessionId: String): SessionEntity? = dao.session(sessionId)

    /** What the price list calls a part number, if it knows it. */
    suspend fun catalogueDescription(pid: String): String? = dao.catalogue(pid)?.description

    suspend fun updateLineQty(lineId: Long, qty: Double, flags: String) =
        dao.setLineQty(lineId, qty, flags)

    suspend fun deleteLine(lineId: Long) = dao.deleteLine(lineId)

    /**
     * The unit to create a new stock item with.
     *
     * In order: what this company's existing items already use, then what the
     * relay says it will send to Tally, then a last-resort default. Never a
     * constant compiled into a screen -- Tally refuses a unit the company has
     * not defined, and the symbol differs between sets of books.
     */
    suspend fun defaultUnit(): String =
        dao.commonestUnit()?.takeIf { it.isNotBlank() }
            ?: config.defaultUnit.first().takeIf { it.isNotBlank() }
            ?: FALLBACK_UNIT

    /**
     * Corrects a box already on the receipt.
     *
     * The box number is the box's identity, so changing it is not an edit in
     * place: it has to go through the duplicate and resolution checks as if it
     * had just been scanned. The new line is taken FIRST and the old one
     * removed only if it was accepted, so a correction that turns out to be a
     * duplicate leaves the receipt exactly as it was rather than destroying the
     * line it was trying to fix.
     */
    suspend fun correctLine(
        sessionId: String,
        lineId: Long,
        pid: String,
        boxSerial: String,
        qty: Int,
    ): ScanDecision {
        val existing = dao.line(lineId)

        // Nothing but the quantity has moved: no identity change, no re-check.
        if (existing != null && existing.boxSerial == boxSerial.trim() && existing.pid == pid) {
            dao.setLineQty(lineId, qty.toDouble(), existing.flags)
            runCatching {
                api?.addLine(sessionId, LineRequest(
                    pid = pid, boxSerial = boxSerial.trim(), qty = qty.toDouble(),
                    lineId = lineId, raw = existing.rawPayload, symbology = existing.symbology,
                ))
            }
            return ScanDecision(
                Outcome2.ACCEPT, Beep.ACCEPT,
                "Changed to ${fmt(qty.toDouble())}.",
                pid = pid, boxSerial = boxSerial, labelQty = qty.toDouble(),
            )
        }

        dao.deleteLine(lineId)
        val decision = receiveAssembled(
            sessionId, pid, boxSerial, qty,
            raw = existing?.rawPayload ?: "corrected",
        )
        if (decision.outcome == Outcome2.ACCEPT || decision.outcome == Outcome2.FLAGGED) {
            commitLine(sessionId, decision)
        } else if (existing != null) {
            // Refused, so put back exactly what was there.
            dao.insertLine(existing)
        }
        return decision
    }

    /**
     * Discards a receipt that never reached Tally.
     *
     * Deliberately impossible for one that did. Everything else is fair game:
     * a half-scanned pallet someone abandoned, or a receipt left behind by a
     * version of this app that could not save it, are both just clutter, and
     * clutter in this list is what stops an operator trusting the list.
     *
     * The relay is told too, so the same rubbish does not sit there for ever.
     */
    suspend fun discard(sessionId: String) {
        val s = dao.session(sessionId) ?: return
        if (s.state == "POSTED") return
        dao.deleteLinesFor(sessionId)
        dao.deleteSession(sessionId)
        runCatching { api?.deleteSession(sessionId) }
    }

    /**
     * Empties this handset of everything it has scanned and cached.
     *
     * Not a routine action: it throws away receipts that have not been sent.
     * It exists because the alternative is clearing the app's storage, which
     * also wipes the relay address and token and means provisioning the handset
     * again -- too much ceremony for "start this test from nothing", and a good
     * way to lose a device's settings during a rollout.
     *
     * Master data comes straight back on the next sync, so what is really being
     * discarded is the scan history.
     */
    suspend fun wipeLocalData() {
        dao.clearSessionLines()
        dao.clearSessions()
        dao.clearReceivedBoxes()
        dao.clearBalances()
        dao.clearOrderLines()
        dao.clearOrders()
        dao.clearAllBindings()
        dao.clearAllItems()
        setConfigCompany("")
    }

    /** Clears every receipt that never reached Tally. */
    suspend fun discardAllUnsaved(): Int {
        val ids = dao.unsavedSessionIds()
        for (id in ids) discard(id)
        return ids.size
    }

    /**
     * Closes the session.
     *
     * Marked QUEUED locally FIRST. Whether the relay is reachable is beside the
     * point: the work is durable, the badge shows it pending, and the sync
     * worker delivers it whenever there is signal again.
     */
    suspend fun submit(sessionId: String, scope: String = "PARTIAL"): SubmitResponse? {
        // Counted against one company, posted into another, is stock appearing
        // somewhere it never was. Refused rather than reconciled: nobody can
        // say afterwards which company a box was really on.
        val s = dao.session(sessionId)
        val now = configCompany()
        if (s != null && s.company.isNotBlank() && now.isNotBlank() && s.company != now) {
            dao.setSessionResult(
                sessionId, "DRAFT", "", "BUSINESS",
                "This was counted against ${s.company}, but Tally now has $now open. " +
                    "It cannot be posted into a different company.",
            )
            return SubmitResponse(
                sessionId = sessionId, ok = false,
                message = "Counted against ${s.company}; Tally now has $now open.",
            )
        }

        dao.setSessionState(sessionId, "QUEUED")

        // Never submit ahead of the scans. If the relay is missing even one
        // line, leave the session queued and let the retry deliver the lot --
        // the operator sees it waiting, which is honest, rather than a voucher
        // posting short.
        if (!pushPendingLines(sessionId)) return null

        val resp = runCatching { api?.submit(sessionId, scope) }.getOrNull() ?: return null

        // A refusal goes back to DRAFT with the reason on it. Leaving it QUEUED
        // would be a lie -- nothing is queued, nothing is coming, and the
        // operator would wait for something that is never going to happen.
        if (!resp.ok) {
            dao.setSessionResult(sessionId, "DRAFT", "", "BUSINESS", resp.message)
        }
        return resp
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

    /**
     * Sends a receipt Tally refused, again.
     *
     * A separate call from submit, which treats an already-submitted session as
     * a duplicate and answers without doing anything -- correct for a retry of
     * the SUBMIT, useless for a retry of the POST. Getting these two confused
     * makes the button look like it works while nothing happens.
     */
    suspend fun retry(sessionId: String): Boolean {
        if (!pushPendingLines(sessionId)) return false
        val ok = runCatching { api?.retry(sessionId) == true }.getOrDefault(false)
        if (ok) dao.setSessionState(sessionId, "QUEUED")
        return ok
    }

    suspend fun refreshSessionState(sessionId: String) {
        val local = dao.session(sessionId) ?: return
        val look = runCatching { api?.sessionStateOrGone(sessionId) }.getOrNull() ?: return

        // The relay has no record of it. If every line had already been
        // delivered, it certainly did once and the receipt has been thrown away
        // since, so the copy on this phone is a ghost -- and a list of ghosts is
        // what stops an operator believing anything in it.
        //
        // Only ever when nothing is still waiting to be delivered: a receipt
        // scanned out of range has not reached the relay yet, and "not there"
        // means nothing about it.
        if (look.gone) {
            // Posted receipts are cleared too, when the relay has explicitly
            // forgotten them.
            //
            // They used to be kept as the record of what Tally was told, which
            // is right while Tally still has the voucher. Once the relay has
            // deliberately dropped the receipt there is nothing left for it to
            // be a record OF -- and the box history it carries goes on refusing
            // cartons as duplicates of a receipt that exists nowhere.
            //
            // Only ever on an explicit "no such receipt" with every line
            // already delivered. A receipt scanned out of range has not reached
            // the relay yet, and its silence means nothing.
            if (dao.unsyncedCount(sessionId) == 0) {
                dao.deleteLinesFor(sessionId)
                dao.deleteSession(sessionId)
            }
            return
        }

        val s = look.session ?: return
        dao.setSessionResult(sessionId, s.state, s.tallyVoucherId, s.errorClass, s.errorMessage)
    }

    /**
     * Tally's health, as the connector last reported it.
     *
     * Null when the relay cannot be reached at all, which is a different thing
     * from Tally being down and is shown differently.
     */
    suspend fun connectionStatus(): ConnectorStatus {
        val relay = api ?: return ConnectorStatus(health = "SETUP_REQUIRED")
        return try {
            relay.status().connector
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayHttpException) {
            ConnectorStatus(health = if (e.code == 401 || e.code == 403) "AUTH_REQUIRED" else "RELAY_ERROR",
                lastError = "Relay returned HTTP ${e.code}")
        } catch (e: Exception) {
            ConnectorStatus(health = "RELAY_UNREACHABLE")
        }
    }

    suspend fun tallyHealth(): String = connectionStatus().health

    /**
     * Which company this handset is feeding, and whether that is the right one.
     *
     * [pinned] is the company this phone was set up against. [open] is what the
     * connector is actually posting into. They agree in every ordinary case;
     * when they do not, something has been repointed and the two are shown side
     * by side rather than quietly reconciled.
     */
    data class CompanyCheck(val pinned: String, val open: String) {
        val mismatch: Boolean get() =
            pinned.isNotBlank() && open.isNotBlank() && !pinned.equals(open, ignoreCase = true)
    }

    /**
     * Reads the company situation, preferring the live answer.
     *
     * A refused sync already recorded the offending name, and that is used when
     * the relay cannot be reached, so the warning does not disappear the moment
     * the signal does -- which is exactly when it matters most.
     */
    suspend fun companyCheck(liveCompany: String? = null): CompanyCheck {
        val pinned = configCompany()
        val live = liveCompany ?: runCatching { api?.status()?.connector?.company }.getOrNull()
        return CompanyCheck(pinned, live?.takeIf { it.isNotBlank() } ?: wrongCompany.orEmpty())
    }

    // --- master sync ---

    /** Pulls everything the device needs to keep working without a network. */
    private val masterSyncMutex = Mutex()

    suspend fun syncMasters(): Boolean = masterSyncMutex.withLock { runCatching {
        val s = api?.sync() ?: return false

        // Keep the unit the relay will actually use, so the new-product
        // prompt offers the same symbol rather than a guess of its own.
        config.rememberDefaultUnit(s.defaultUnit)

        // This handset belongs to ONE company, and will not quietly follow
        // Tally to another.
        //
        // Somebody opening the wrong company in Tally is an ordinary slip, and
        // it used to be invisible: the phone adopted whatever was open, cached
        // that company's products, and every carton scanned afterwards went
        // into the wrong books with nothing on screen to suggest it. Stock in
        // two companies, both wrong, and no way to tell which receipts to undo.
        //
        // So the company is pinned the first time it is seen, and a sync from
        // any other company is REFUSED ENTIRELY -- not merged, not partially
        // applied. Nothing about the wrong company reaches this phone.
        val pinned = configCompany()
        if (pinned.isBlank()) {
            if (s.company.isNotBlank()) setConfigCompany(s.company)
        } else if (s.company.isNotBlank() && s.company != pinned) {
            wrongCompany = s.company
            return false
        }
        wrongCompany = null

        dao.applyHistoryRemovals(s.historyRemovals)

        // A product the operator described is bound here the moment they
        // describe it, so the rest of the pallet does not prompt again. Until
        // Tally has actually created it that binding is a guess, and the relay
        // is the only thing that knows how the guess turned out.
        for (p in s.proposals) {
            when (p.state) {
                // Refused by Tally, or refused before it ever got there. Drop
                // the guess so the next carton asks again instead of building
                // receipt after receipt on a product that does not exist.
                "FAILED" -> {
                    dao.deleteBinding(p.pid)
                    // And take the product back off the cartons, so the next
                    // look at that receipt asks for it again rather than
                    // presenting a product Tally has refused as settled.
                    dao.unfillProduct(p.pid)
                }
                // Done: it is a real item now and arrives in the sync proper.
                "CREATED" -> dao.deleteBinding(p.pid)
                // Still in flight; leave the guess alone.
                else -> Unit
            }
        }

        // Anything the relay no longer mentions is a guess it has no record of
        // -- a proposal that was cleared, or one that never arrived. Same
        // conclusion: the phone must stop resolving it.
        val known = s.proposals.map { it.pid }.toSet()
        for (b in dao.provisionalBindings()) if (b.pid !in known) dao.deleteBinding(b.pid)

        // Replaced wholesale, like balances: Tally is the only authority on
        // what exists, and an item it has stopped sending does not.
        dao.clearItemsExceptProvisional()
        dao.clearConfirmedBindings()

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

        // Replaced wholesale, like balances and orders.
        //
        // It was merged in and never cleared, so a box stayed "already
        // received" on this handset long after the receipt that recorded it had
        // gone from Tally and the relay. Clearing the server cleared nothing
        // here, and a carton was refused as a duplicate of a receipt that no
        // longer exists anywhere.
        dao.clearReceivedBoxes()
        dao.upsertReceivedBoxes(
            s.receivedBoxes.map { ReceivedBoxEntity(it.pid, it.boxSerial, it.receivedAt) },
        )
        dao.upsertCatalogue(s.catalogue.map { CatalogueEntity(it.pid, it.description, it.alternates) })
        true
    }.getOrDefault(false) }

    /** Re-asks the relay about everything still in flight. */
    suspend fun refreshPending() {
        // Drafts included, deliberately. They were skipped because a draft has
        // no Tally result worth asking about -- but it is exactly a draft that
        // gets discarded on the relay, and skipping it is why the discarded
        // ones stayed on the phone for ever.
        for (session in dao.sessionsToSync()) refreshSessionState(session.id)
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

/**
 * Used only when a handset has never synced and has no items cached.
 *
 * "Nos" is what the live company uses; it is a last resort, not the answer.
 * The answer comes from the company's own item master or from the relay.
 */
private const val FALLBACK_UNIT = "Nos"
