package com.acme.scantotally.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.acme.scantotally.ScanToTallyApp
import com.acme.scantotally.data.Outcome2
import com.acme.scantotally.data.Repository
import com.acme.scantotally.data.ScanDecision
import com.acme.scantotally.data.SessionLineEntity
import com.acme.scantotally.feedback.Beep
import com.acme.scantotally.scan.BoxDraft
import com.acme.scantotally.scan.FragmentKind
import com.acme.scantotally.scan.ManualScanSource
import com.acme.scantotally.scan.classifyFragment
import com.acme.scantotally.scan.fragmentRefusal
import com.acme.scantotally.scan.RawScan
import com.acme.scantotally.scan.SuspendScanCapture
import com.acme.scantotally.ui.theme.AcceptGreen
import com.acme.scantotally.ui.theme.FlagAmber
import com.acme.scantotally.ui.theme.LocalSemantics
import com.acme.scantotally.ui.theme.RejectRed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun rememberApp(): ScanToTallyApp =
    LocalContext.current.applicationContext as ScanToTallyApp

// --- incoming ---------------------------------------------------------------

/**
 * Incoming: scan, scan, scan, Done.
 *
 * Quantity comes from the barcode, so one scan is one complete line and the
 * operator never stops to type. Nothing here is modal -- a dialog is a stopped
 * operator holding a box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingScreen(nav: NavController, scans: Flow<RawScan>, resumeId: String? = null) {
    val app = rememberApp()
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<Repository?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<ScanDecision?>(null) }
    var pendingOverride by remember { mutableStateOf<RawScan?>(null) }
    var newProduct by remember { mutableStateOf<ScanDecision?>(null) }
    var draft by remember { mutableStateOf(BoxDraft()) }
    var typing by remember { mutableStateOf<BoxDraft.Slot?>(null) }
    var operator by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = app.repository()
        repo = r
        operator = app.config.operator.first()
        sessionId = resumeId?.let { r.resumeSession(it) }
        // Resuming keeps the scans already on it. A fresh start deliberately
        // opens NOTHING yet: a receipt is created by the first scan, not by
        // looking at the screen. Opening one here left an empty receipt behind
        // every time somebody tapped in and changed their mind, and a list full
        // of those is a list nobody reads.
    }

    val lines by (sessionId?.let { repo?.linesFlow(it) }?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionLineEntity>()) })

    // A receipt reopened from the list may be stuck on a product nobody ever
    // described -- that is exactly why it would not save. Ask again, rather
    // than leaving it unsaveable with no way in.
    //
    // Only ever once per product per visit. Asking is driven off the lines, so
    // without this it re-asks the instant the dialog closes: Later answers
    // nothing, and even Save cannot settle it until the write has landed. The
    // operator ends up in a prompt they cannot get out of.
    val asked = remember { mutableStateListOf<String>() }
    LaunchedEffect(lines, newProduct) {
        if (newProduct != null) return@LaunchedEffect
        val stuck = lines.firstOrNull {
            it.stockItemName.isEmpty() && it.pid !in asked
        } ?: return@LaunchedEffect
        val r = repo ?: return@LaunchedEffect
        asked += stuck.pid
        newProduct = ScanDecision(
            outcome = Outcome2.FLAGGED, beep = Beep.FLAGGED,
            message = "${stuck.pid} still needs its details",
            pid = stuck.pid, boxSerial = stuck.boxSerial, labelQty = stuck.qty,
            catalogueDescription = r.catalogueDescription(stuck.pid),
            raw = stuck.rawPayload, symbology = stuck.symbology,
        )
    }

    // Creates the receipt the first time it is needed, and never before.
    suspend fun ensure(r: Repository): String =
        sessionId ?: r.openSession("INCOMING", app.config.godown.first()).also { sessionId = it }

    // Commits a box however it was put together, and clears the draft.
    //
    // Assembled boxes and whole-barcode boxes end up here alike: the decision
    // has already been through the identical checks by this point, so there is
    // one place that commits and one place that prompts.
    suspend fun accept(r: Repository, sid: String, d: ScanDecision, from: RawScan?) {
        last = d
        app.feedback.play(d.beep)

        if (d.outcome == Outcome2.ACCEPT || d.outcome == Outcome2.FLAGGED) {
            // The box is counted FIRST. Whatever happens next, the count is
            // safe -- the prompt below is only ever about the description.
            r.commitLine(sid, d)
            draft = BoxDraft()

            // Ask right now, while the carton is still in their hands and the
            // description is printed on the label in front of them. By the end
            // of the session the box is on a shelf and they would be recalling
            // rather than reading.
            if (d.flags.contains("UNRESOLVED_PID") && d.pid !in asked) {
                asked += d.pid
                newProduct = d
            }
            return
        }

        // A refused box stays on screen exactly as assembled, so the operator
        // can see what it was rather than starting again from nothing.
        if (d.overridable && from != null) pendingOverride = from
    }

    LaunchedEffect(repo) {
        val r = repo ?: return@LaunchedEffect
        scans.collect { scan ->
            val sid = ensure(r)

            // A combined barcode is a whole box on its own. Unchanged: one
            // pull, one box, nothing typed. Only cartons without one fall
            // through to being assembled a field at a time.
            val whole = r.parseWholeBox(scan)
            if (whole != null) {
                draft = BoxDraft()
                accept(r, sid, r.scanIncoming(sid, scan), scan)
                return@collect
            }

            val fragment = classifyFragment(scan.data)
            if (fragment.kind == FragmentKind.NOT_MINE) {
                // Refused, never guessed into a slot. The labels are crowded
                // with part nos, date codes, week numbers and issue numbers.
                last = ScanDecision(
                    outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                    message = fragmentRefusal(fragment),
                    raw = scan.data, symbology = scan.symbology,
                )
                app.feedback.play(Beep.REJECT)
                return@collect
            }

            draft = draft.withScan(fragment)
            app.feedback.play(Beep.ACCEPT)

            val d = draft
            if (d.isComplete) {
                accept(r, sid, r.receiveAssembled(sid, d.pid, d.boxSerial, d.qty!!, d.rawTrail), null)
            }
        }
    }

    ScanScaffold(
        title = "Incoming",
        subtitle = draft.waitingFor(),
        nav = nav,
        sessionId = sessionId,
        last = last,
        lines = lines,
        submitting = submitting,
        result = result,
        submitLabel = "Done · post receipt",
        onSubmit = {
            scope.launch {
                val r = repo ?: return@launch
                val sid = sessionId ?: return@launch
                submitting = true
                val resp = r.submit(sid)
                submitting = false
                // Only sound the saved chime when it actually saved. A refusal
                // that sounds like success is worse than no sound at all.
                if (resp == null || resp.ok) app.feedback.playSessionPosted()
                result = when {
                    resp == null -> "Saved. It will post to Tally when the connection returns."
                    !resp.ok -> resp.message.ifEmpty { "Tally would not accept this receipt." }
                    resp.unresolvedLines > 0 ->
                        "Saved, but ${resp.unresolvedLines} line(s) are waiting for Tally to create the product."
                    resp.dispatched -> "Sent to Tally."
                    else -> "Saved. Waiting for Tally to come back."
                }
            }
        },
        onManual = {
            scope.launch {
                val r = repo ?: return@launch
                nav.navigate("manual/${ensure(r)}")
            }
        },
        slots = {
            BoxSlots(
                draft = draft,
                onTypeProduct = { typing = BoxDraft.Slot.PRODUCT },
                onTypeBox = { typing = BoxDraft.Slot.BOX },
                onTypeQuantity = { typing = BoxDraft.Slot.QUANTITY },
            )
        },
    )

    typing?.let { slot ->
        SlotEntryDialog(
            slot = slot,
            draft = draft,
            onCancel = { typing = null },
            onConfirm = { value ->
                draft = when (slot) {
                    BoxDraft.Slot.PRODUCT -> draft.withTypedProduct(value)
                    BoxDraft.Slot.BOX -> draft.withTypedBox(value)
                    BoxDraft.Slot.QUANTITY ->
                        draft.withTypedQty(value.toIntOrNull() ?: 0)
                }
                typing = null

                val d = draft
                if (d.isComplete) {
                    scope.launch {
                        val r = repo ?: return@launch
                        val sid = ensure(r)
                        accept(
                            r, sid,
                            r.receiveAssembled(sid, d.pid, d.boxSerial, d.qty!!, d.rawTrail),
                            null,
                        )
                    }
                }
            },
        )
    }

    newProduct?.let { d ->
        NewProductDialog(
            decision = d,
            onSkip = { newProduct = null },
            onSave = { description, unit ->
                scope.launch {
                    val r = repo ?: return@launch
                    val sid = sessionId ?: return@launch
                    // Closed first. Leaving it up while this writes makes the
                    // Save button look dead, and the operator taps it again.
                    newProduct = null
                    val res = r.proposeNewItem(sid, d.pid, description, unit, d.raw, operator)
                    // A product Tally will not create has to be said out loud.
                    // Silently failing here is what left receipts that could
                    // never save with nothing on screen to explain them.
                    if (!res.ok && !res.queued) {
                        result = res.message.ifEmpty { "Tally would not add ${d.pid}." }
                        app.feedback.play(Beep.REJECT)
                        asked -= d.pid
                    }
                }
            },
        )
    }

    pendingOverride?.let { scan ->
        AlertDialog(
            onDismissRequest = { pendingOverride = null },
            title = { Text("Box already received") },
            text = { Text(last?.message ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = repo ?: return@launch
                        val sid = sessionId ?: return@launch
                        val d = r.scanIncoming(sid, scan, overrideDuplicate = true)
                        last = d
                        app.feedback.play(d.beep)
                        r.commitLine(sid, d)
                        pendingOverride = null
                    }
                }) { Text("Accept as a return") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverride = null }) { Text("Set it aside") }
            },
        )
    }
}

// --- stock check ------------------------------------------------------------

/**
 * Stock take: count the shelf, then correct Tally.
 *
 * Distinct from Check stock, which only asks what Tally holds. This one writes
 * a Physical Stock voucher, and because Tally tracks each box as a batch the
 * count has to be per box -- an adjustment has to name the batch it adjusts.
 *
 * Counting is blind by default: showing the operator what Tally expects anchors
 * the count to it, and a count that only ever confirms the book is worth
 * nothing. The variance is revealed at the end, before anything is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCheckScreen(nav: NavController, scans: Flow<RawScan>, resumeId: String? = null) {
    val app = rememberApp()
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<Repository?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var godown by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<ScanDecision?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var showVariance by remember { mutableStateOf(false) }
    var variance by remember { mutableStateOf<com.acme.scantotally.data.VarianceReport?>(null) }
    var scope2 by remember { mutableStateOf("PARTIAL") }

    LaunchedEffect(Unit) {
        val r = app.repository()
        repo = r
        godown = app.config.godown.first()
        // Nothing is opened until the first box is counted. Eleven empty stock
        // takes reached the receipts list from people simply opening this
        // screen, which is what made the list worth ignoring.
        sessionId = resumeId?.let { r.resumeSession(it) }
    }

    val lines by (sessionId?.let { repo?.linesFlow(it) }?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionLineEntity>()) })

    suspend fun ensure(r: Repository): String =
        sessionId ?: r.openSession("STOCKCHECK", godown).also { sessionId = it }

    LaunchedEffect(repo, godown) {
        val r = repo ?: return@LaunchedEffect
        if (godown.isEmpty()) return@LaunchedEffect
        scans.collect { scan ->
            val sid = ensure(r)
            val d = r.scanStockCheck(sid, godown, scan, blind = true)
            last = d
            app.feedback.play(d.beep)
            if (d.outcome == Outcome2.ACCEPT || d.outcome == Outcome2.FLAGGED) {
                r.commitLine(sid, d)
            }
        }
    }

    ScanScaffold(
        title = "Stock take",
        subtitle = "$godown · ${lines.size} counted",
        nav = nav,
        sessionId = sessionId,
        last = last,
        lines = lines,
        submitting = submitting,
        result = result,
        submitLabel = "Done · show variance",
        onSubmit = {
            scope.launch {
                val sid = sessionId ?: return@launch
                submitting = true
                variance = runCatching {
                    app.repository().let { _ ->
                        val api = com.acme.scantotally.data.RelayApi(
                            app.config.relayUrl.first(), app.config.token.first(),
                        )
                        api.variance(sid, scope2)
                    }
                }.getOrNull()
                submitting = false
                showVariance = true
            }
        },
        onManual = {
            scope.launch {
                val r = repo ?: return@launch
                nav.navigate("manual/${ensure(r)}")
            }
        },
    )

    if (showVariance) {
        VarianceDialog(
            report = variance,
            scope = scope2,
            onScopeChange = { scope2 = it },
            onDismiss = { showVariance = false },
            onAdopt = {
                scope.launch {
                    val r = repo ?: return@launch
                    val sid = sessionId ?: return@launch
                    submitting = true
                    val resp = r.submit(sid, scope2)
                    submitting = false
                    showVariance = false
                    if (resp == null || resp.ok) app.feedback.playSessionPosted()
                    result = when {
                        resp == null -> "Saved. It will post when the connection returns."
                        !resp.ok -> resp.message.ifEmpty { "Tally would not accept this count." }
                        resp.noVariance -> "Count matches the book exactly. Nothing to adjust."
                        else -> "Adjustment sent to Tally."
                    }
                }
            },
        )
    }
}

@Composable
private fun VarianceDialog(
    report: com.acme.scantotally.data.VarianceReport?,
    scope: String,
    onScopeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdopt: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Variance") },
        text = {
            Column {
                if (report == null) {
                    Text("Could not reach the relay to work out the variance. Try again when online.")
                    return@Column
                }
                Text(
                    "${report.counted} counted · ${report.matched} match · " +
                        "${report.discrepancies} differ · ${report.notCounted} not counted",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PARTIAL" to "Only what I counted", "FULL" to "Whole godown").forEach { (v, label) ->
                        OutlinedButton(
                            onClick = { onScopeChange(v) },
                            colors = if (scope == v) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            } else ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }

                // The dangerous case, stated plainly rather than buried.
                if (report.willZeroUncounted) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This will set ${report.notCounted} uncounted box(es) to zero in Tally. " +
                            "Only do that if you counted the entire godown.",
                        color = RejectRed,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(report.rows.filter { it.kind != "MATCH" }) { row ->
                        val c = when (row.kind) {
                            "SHORT" -> RejectRed
                            "OVER" -> FlagAmber
                            "NOT_IN_BOOK" -> FlagAmber
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(Modifier.padding(vertical = 5.dp)) {
                            Text(
                                row.stockItemName.ifEmpty { "Unknown (${row.pid})" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${tail(row.boxSerial)} · book ${fmtQty(row.bookQty)} · " +
                                    "counted ${fmtQty(row.countedQty)} · ${row.kind}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = c,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdopt, enabled = report != null) { Text("Adopt the count") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep counting") } },
    )
}

// --- outgoing ---------------------------------------------------------------

/**
 * Outgoing: scan, type the quantity, confirm.
 *
 * More ceremony than incoming, deliberately. Outgoing is where a mistake
 * reaches a customer, and the quantity is entered by the employee rather than
 * defaulted from a label that may no longer be true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutgoingScreen(
    nav: NavController, scans: Flow<RawScan>, salesOrder: String, resumeId: String? = null,
) {
    // Only product and box: a despatch quantity is typed on the keypad, never
    // taken from the label.
    var outDraft by remember { mutableStateOf(BoxDraft()) }
    val app = rememberApp()
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<Repository?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var godown by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<ScanDecision?>(null) }
    var qtyFor by remember { mutableStateOf<ScanDecision?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = app.repository()
        repo = r
        godown = app.config.godown.first()
        sessionId = resumeId?.let { r.resumeSession(it) }
    }

    val lines by (sessionId?.let { repo?.linesFlow(it) }?.collectAsState(emptyList())
        ?: remember { mutableStateOf(emptyList<SessionLineEntity>()) })

    suspend fun ensure(r: Repository): String =
        sessionId ?: r.openSession("OUTGOING", godown, salesOrder = salesOrder)
            .also { sessionId = it }

    LaunchedEffect(repo, godown) {
        val r = repo ?: return@LaunchedEffect
        if (godown.isEmpty()) return@LaunchedEffect
        scans.collect { scan ->
            val sid = ensure(r)

            // A carton received as HFE283 has no combined code on the way out
            // either, so the product and box are assembled here too. The
            // quantity is not part of the draft: on a despatch the employee
            // types it on the keypad that follows, against the box's real
            // remaining stock rather than anything printed on the label.
            val d = if (r.parseWholeBox(scan) != null) {
                outDraft = BoxDraft()
                r.scanOutgoing(sid, salesOrder, godown, scan)
            } else {
                val fragment = classifyFragment(scan.data)
                if (fragment.kind == FragmentKind.NOT_MINE) {
                    last = ScanDecision(
                        outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                        message = fragmentRefusal(fragment),
                        raw = scan.data, symbology = scan.symbology,
                    )
                    app.feedback.play(Beep.REJECT)
                    return@collect
                }

                outDraft = outDraft.withScan(fragment)
                if (outDraft.pid.isEmpty() || outDraft.boxSerial.isEmpty()) {
                    app.feedback.play(Beep.ACCEPT)
                    last = ScanDecision(
                        outcome = Outcome2.FLAGGED, beep = Beep.ACCEPT,
                        message = if (outDraft.pid.isEmpty()) "Now scan the box number"
                        else "Now scan the product barcode",
                        pid = outDraft.pid, boxSerial = outDraft.boxSerial,
                        raw = scan.data, symbology = scan.symbology,
                    )
                    return@collect
                }

                val assembled = r.despatchAssembled(
                    sid, salesOrder, godown, outDraft.pid, outDraft.boxSerial, outDraft.rawTrail,
                )
                outDraft = BoxDraft()
                assembled
            }
            last = d
            app.feedback.play(d.beep)
            // Accepted means "this box is valid" -- the quantity screen opens
            // next. A rejection is only a sound; nothing to dismiss.
            if (d.outcome == Outcome2.ACCEPT) qtyFor = d
        }
    }

    ScanScaffold(
        title = "Outgoing",
        subtitle = "$salesOrder · ${lines.size} ${if (lines.size == 1) "box" else "boxes"}",
        nav = nav,
        sessionId = sessionId,
        last = last,
        lines = lines,
        submitting = submitting,
        result = result,
        submitLabel = "Done · post delivery note",
        onSubmit = {
            scope.launch {
                val r = repo ?: return@launch
                val sid = sessionId ?: return@launch
                submitting = true
                val resp = r.submit(sid)
                submitting = false
                if (resp == null || resp.ok) app.feedback.playSessionPosted()
                result = when {
                    resp == null -> "Saved. It will post when the connection returns."
                    !resp.ok -> resp.message.ifEmpty { "Tally would not accept this despatch." }
                    resp.dispatched -> "Sent to Tally."
                    else -> "Saved. It will post when the connection returns."
                }
            }
        },
        onManual = {
            scope.launch {
                val r = repo ?: return@launch
                nav.navigate("manual/${ensure(r)}")
            }
        },
    )

    qtyFor?.let { d ->
        QuantityKeypad(
            decision = d,
            salesOrder = salesOrder,
            onCancel = { qtyFor = null },
            onConfirm = { qty ->
                scope.launch {
                    val r = repo ?: return@launch
                    val sid = sessionId ?: return@launch
                    if (d.editLineId != null) {
                        r.updateLineQty(d.editLineId, qty, "QTY_EDITED")
                    } else {
                        r.commitLine(sid, d, qty)
                    }
                    qtyFor = null
                }
            },
            check = { qty ->
                val r = repo ?: return@QuantityKeypad null
                val sid = sessionId ?: return@QuantityKeypad null
                r.checkOutgoingQty(
                    sid, salesOrder, godown, d.pid, d.boxSerial, d.stockItemName,
                    qty, d.editLineId ?: -1,
                )
            },
        )
    }
}

/**
 * The quantity screen.
 *
 * The field starts EMPTY -- the employee enters the number, it is never
 * pre-filled. The "all" button is still their deliberate action; it only saves
 * typing on the common full-box case. Validation runs as they type so Confirm
 * simply cannot be pressed on an invalid number.
 */
@Composable
private fun QuantityKeypad(
    decision: ScanDecision,
    salesOrder: String,
    onCancel: () -> Unit,
    onConfirm: (Double) -> Unit,
    check: suspend (Double) -> com.acme.scantotally.data.QtyCheck?,
) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var warning by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val sem = LocalSemantics.current
    val available = decision.available ?: 0.0
    val orderLeft = decision.orderPending ?: 0.0
    // What the operator may actually type: the tighter of the two ceilings.
    val sendable = minOf(available, orderLeft)
    val qty = entry.toDoubleOrNull() ?: 0.0
    val valid = entry.isNotEmpty() && error == null && qty > 0

    LaunchedEffect(entry) {
        if (entry.isEmpty()) { error = null; warning = null; return@LaunchedEffect }
        val c = check(entry.toDoubleOrNull() ?: 0.0)
        error = c?.error
        warning = c?.warning
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(decision.description.ifEmpty { decision.stockItemName })
                Text(
                    "${decision.pid} · box ${tail(decision.boxSerial)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                // BOTH ceilings, and which of them actually binds.
                //
                // The box figure on its own does not say what may be sent: the
                // order is just as hard a limit and is usually the smaller.
                // Showing one and enforcing two leaves the operator to find the
                // other by being refused.
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Label ${fmtQty(decision.labelQty)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "In box ${fmtQty(available)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (available <= orderLeft) sem.review.fg
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (available <= orderLeft) FontWeight.SemiBold
                        else FontWeight.Normal,
                    )
                    Text(
                        "On order ${fmtQty(orderLeft)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (orderLeft < available) sem.review.fg
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (orderLeft < available) FontWeight.SemiBold
                        else FontWeight.Normal,
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "You can send up to ${fmtQty(sendable)}" +
                        if (orderLeft < available) "  (order $salesOrder)" else "  (in this box)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                decision.availableAsOf?.let {
                    val mins = ((System.currentTimeMillis() - it) / 60000).coerceAtLeast(0)
                    Text(
                        "as of ${if (mins < 1) "just now" else "$mins min ago"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        entry.ifEmpty { "—" },
                        fontSize = 30.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (error != null) RejectRed else MaterialTheme.colorScheme.onSurface,
                    )
                }

                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = RejectRed, style = MaterialTheme.typography.bodyMedium)
                }
                warning?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = FlagAmber, style = MaterialTheme.typography.bodyMedium)
                }

                Spacer(Modifier.height(12.dp))
                // A custom keypad, not the system one: the device is operated
                // in gloves and the soft keyboard is far too small.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(listOf("7", "8", "9"), listOf("4", "5", "6"), listOf("1", "2", "3")).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { k -> Key(k, Modifier.weight(1f)) { entry += k } }
                            when (row[0]) {
                                "7" -> Key("All ${fmtQty(available)}", Modifier.weight(1.4f), accent = true) {
                                    entry = fmtQty(available)
                                }
                                "4" -> Key("⌫", Modifier.weight(1.4f)) { entry = entry.dropLast(1) }
                                else -> Key("C", Modifier.weight(1.4f)) { entry = "" }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Key("0", Modifier.weight(2f)) { entry += "0" }
                        Key(".", Modifier.weight(1f)) { if (!entry.contains('.')) entry += "." }
                        Spacer(Modifier.weight(1.4f))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(qty) }, enabled = valid) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun Key(label: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .background(
                if (accent) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (label.length > 3) 13.sp else 20.sp,
            fontFamily = if (label.length <= 3) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Asked the moment an unrecognised product is scanned.
 *
 * The box has ALREADY been counted by the time this appears, so "Later" costs
 * nothing but the description -- the receipt is still correct either way. That
 * is what makes it safe to interrupt at all.
 *
 * What the operator types here creates the stock item in Tally directly. There
 * is no approval step: a carton on the dock is evidence the product exists, and
 * making the dock wait for someone at a desk to agree is the one thing this app
 * must not do. They already have the only facts a second person could add --
 * the description is printed in front of them and the unit is on the carton.
 */
@Composable
private fun NewProductDialog(
    decision: ScanDecision,
    onSkip: () -> Unit,
    onSave: (description: String, unit: String) -> Unit,
) {
    // Prefilled from the price list when it knows this part number, so the
    // operator confirms rather than types. They supply only what the catalogue
    // cannot know.
    val known = decision.catalogueDescription
    var description by remember(decision.pid) { mutableStateOf(known ?: "") }
    var unit by remember(decision.pid) { mutableStateOf("NO") }
    var batchwise by remember(decision.pid) { mutableStateOf(true) }
    SuspendScanCapture()

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("New product") },
        text = {
            Column {
                Text(
                    if (decision.catalogueDescription != null)
                        "The price list knows this part, but Tally has no item for it yet."
                    else
                        "Neither Tally nor the price list has seen this part number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    decision.pid,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${fmtQty(decision.labelQty)} already counted - box ${tail(decision.boxSerial)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AcceptGreen,
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (known != null) "Description" else "What is it?") },
                    placeholder = { Text("SSD SENSOR BASE") },
                    supportingText = {
                        Text(
                            if (known != null) "From the price list - change it only if wrong"
                            else "Copy the DESCRIPTION line from the label",
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it.take(8) },
                    label = { Text("Unit") },
                    supportingText = { Text("NO for pieces, mts for metres, EA for each") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                // Cannot be changed later: Tally will not alter batch tracking
                // on an item once it has transactions.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = batchwise, onCheckedChange = { batchwise = it })
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Track box numbers", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Cannot be changed once the item has movements",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "This is added to Tally straight away, and the rest of the " +
                        "pallet will scan without asking again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(description.trim(), unit.trim().ifEmpty { "NO" }) },
                enabled = description.trim().length >= 3,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Later") } },
    )
}

// --- shared scaffold --------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanScaffold(
    title: String,
    subtitle: String,
    nav: NavController,
    sessionId: String?,
    last: ScanDecision?,
    lines: List<SessionLineEntity>,
    submitting: Boolean,
    result: String?,
    submitLabel: String,
    onSubmit: () -> Unit,
    onManual: () -> Unit,
    /** The box being assembled, for the flows that build one field at a time. */
    slots: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Manual entry is one tap away and always present: the
                    // labels sit under packing tape, and torn ones happen.
                    //
                    // It asks for the receipt rather than reading one, because
                    // there is deliberately none until something is entered.
                    IconButton(onClick = onManual) {
                        Icon(Icons.Default.Keyboard, "Manual entry")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            slots?.let {
                it()
                Spacer(Modifier.height(10.dp))
            }

            ScanResultCard(last)

            result?.let {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.titleMedium)
                }
            }

            SectionLabel("This session")
            Box(Modifier.weight(1f)) {
                if (lines.isEmpty()) {
                    Text(
                        "Nothing scanned yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn {
                        items(groupLines(lines)) { group -> GroupRow(group) }
                    }
                }
            }

            Button(
                onClick = onSubmit,
                enabled = lines.isNotEmpty() && !submitting && result == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            ) {
                Text(if (submitting) "Sending…" else submitLabel, fontSize = 18.sp)
            }
            if (result != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { nav.popBackStack("home", inclusive = false) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Back to start") }
            }
        }
    }
}

// --- manual entry -----------------------------------------------------------

/**
 * Manual entry: a first-class tab, not a hidden escape hatch.
 *
 * It runs through the IDENTICAL parser and validation path as a scan, for
 * whichever flow it was opened from. It is a substitute for the scanner, never
 * a bypass for the checks -- otherwise it quietly becomes the route people take
 * when validation is inconvenient, and stock accuracy dies.
 *
 * It used to treat every typed box as an incoming receipt whatever screen it
 * was opened from, which was exactly the bypass this comment promised it was
 * not: on a despatch it skipped the check that a box cannot give up more than
 * it holds, and on an inventory check it recorded a count as a receipt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(nav: NavController, sessionId: String) {
    SuspendScanCapture()
    val app = rememberApp()
    val scope = rememberCoroutineScope()

    var repo by remember { mutableStateOf<Repository?>(null) }
    var kind by remember { mutableStateOf("") }
    var salesOrder by remember { mutableStateOf("") }
    var godown by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<ScanDecision?>(null) }
    var refusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val r = app.repository()
        repo = r
        godown = app.config.godown.first()
        val s = r.session(sessionId)
        kind = s?.kind ?: "INCOMING"
        salesOrder = s?.salesOrder.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual entry") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when (kind) {
                    "OUTGOING" ->
                        "Use this when a label is torn or unreadable. The quantity is still " +
                            "checked against what the box actually holds."
                    "STOCKCHECK" ->
                        "Use this when a label is torn or unreadable. Count what is in the " +
                            "box in front of you."
                    else ->
                        "Use this when a label is torn or unreadable. Every check that " +
                            "applies to a scan applies here too."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = pid, onValueChange = { pid = it },
                label = { Text("Product code (PID)") },
                placeholder = { Text("4098-9792") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = serial, onValueChange = { serial = it.filter { c -> !c.isWhitespace() } },
                label = { Text("Box number") },
                placeholder = { Text("1124241658336425") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = qty, onValueChange = { qty = it.filter { c -> c.isDigit() } },
                label = {
                    Text(
                        when (kind) {
                            "OUTGOING" -> "Quantity to despatch"
                            "STOCKCHECK" -> "Quantity counted"
                            else -> "Quantity"
                        },
                    )
                },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        val r = repo ?: return@launch
                        refusal = null
                        // Rebuilt into the label's own format so it goes
                        // through exactly the same parser as a real scan.
                        val payload = "$pid|$serial|$qty|"
                        val scan = ManualScanSource.scan(payload)
                        val typed = qty.toDoubleOrNull() ?: 0.0

                        // Whichever flow this was opened from decides the
                        // checks. Anything else would make this the soft route
                        // round them.
                        val d = when (kind) {
                            "OUTGOING" -> r.scanOutgoing(sessionId, salesOrder, godown, scan)
                            "STOCKCHECK" -> r.scanStockCheck(sessionId, godown, scan, blind = true)
                            else -> r.scanIncoming(sessionId, scan)
                        }
                        last = d
                        app.feedback.play(d.beep)

                        if (d.outcome != Outcome2.ACCEPT && d.outcome != Outcome2.FLAGGED) {
                            return@launch
                        }

                        // A despatch quantity is the employee's to type, and it
                        // can never exceed what that box actually holds.
                        if (kind == "OUTGOING") {
                            val check = r.checkOutgoingQty(
                                sessionId, salesOrder, godown,
                                d.pid, d.boxSerial, d.stockItemName, typed,
                            )
                            if (!check.ok) {
                                refusal = check.error
                                app.feedback.play(Beep.REJECT)
                                return@launch
                            }
                            r.commitLine(sessionId, d, typed)
                        } else {
                            r.commitLine(sessionId, d)
                        }
                        pid = ""; serial = ""; qty = ""
                    }
                },
                enabled = pid.isNotBlank() && serial.isNotBlank() && qty.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    when (kind) {
                        "OUTGOING" -> "Add to despatch"
                        "STOCKCHECK" -> "Record count"
                        else -> "Add box"
                    },
                )
            }

            refusal?.let {
                Text(
                    it,
                    color = LocalSemantics.current.reject.fg,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            ScanResultCard(last)
        }
    }
}
