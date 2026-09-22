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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.acme.scantotally.scan.ScanSlot
import com.acme.scantotally.scan.classifyFragmentFor
import com.acme.scantotally.scan.refusedForSlot
import com.acme.scantotally.scan.classifyFragment
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
    var newProduct by remember { mutableStateOf<ScanDecision?>(null) }
    var draft by remember { mutableStateOf(BoxDraft()) }
    var typing by remember { mutableStateOf<BoxDraft.Slot?>(null) }
    /** A quantity read off a barcode, waiting to be confirmed against the carton. */
    /** The camera, off unless asked for. Scanning is unchanged and still first. */
    var reading by remember { mutableStateOf(false) }
    /** What became of the last carton the camera added, shown until the next. */
    var cameraResult by remember { mutableStateOf<String?>(null) }
    /** The field waiting for a targeted scan, or null for the long barcode. */
    var armed by remember { mutableStateOf<BoxDraft.Slot?>(null) }
    /** The camera, opened from the new-product prompt to read just the name. */
    var readingName by remember { mutableStateOf(false) }
    var capturedName by remember { mutableStateOf<String?>(null) }
    var openGroup by remember { mutableStateOf<LineGroup?>(null) }
    var editing by remember { mutableStateOf<SessionLineEntity?>(null) }
    var operator by remember { mutableStateOf("") }
    /** What this company creates new stock items with, learned on sync. */
    var defaultUnit by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    /** Products on this receipt that nobody has named. Asked about once. */
    var unnamedPrompt by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(Unit) {
        val r = app.repository()
        repo = r
        defaultUnit = r.defaultUnit()
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
    suspend fun accept(r: Repository, sid: String, d: ScanDecision) {
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
    }

    LaunchedEffect(repo) {
        val r = repo ?: return@LaunchedEffect
        scans.collect { scan ->
            // No receipt is opened yet. A scan that turns out to be a part no,
            // or one refused as a duplicate, must not leave an empty receipt
            // behind -- five of them appeared in a single afternoon that way.
            // The receipt is created by the first box that commits.

            // A combined barcode is a whole box on its own. Unchanged: one
            // pull, one box, nothing typed. Only cartons without one fall
            // through to being assembled a field at a time.
            val whole = r.parseWholeBox(scan)
            if (whole != null) {
                draft = BoxDraft()
                val sid = ensure(r)
                accept(r, sid, r.scanIncoming(sid, scan))
                return@collect
            }

            // Past here the scan was NOT the long barcode.
            //
            // A plain trigger pull accepts that code and nothing else. The
            // smaller barcodes on a carton cannot be told apart by looking at
            // them -- a quantity and a week number are the same shape, a part
            // number without its dash and a date code are the same shape -- so
            // placing one on a guess is how stock goes wrong with nothing on
            // screen. To scan one, the operator arms its field first and then
            // only the shape has to fit.
            val target = armed
            if (target == null) {
                last = ScanDecision(
                    outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                    message = "Scan the long barcode with three parts. " +
                        "For a single field, tap Scan beside it first.",
                    raw = scan.data, symbology = scan.symbology,
                )
                app.feedback.play(Beep.REJECT)
                return@collect
            }

            val fragment = classifyFragmentFor(scan.data, target.toScanSlot())
            if (fragment.kind == FragmentKind.NOT_MINE) {
                // Armed, but the barcode cannot be that field. Refused rather
                // than coerced: a box serial must not become a quantity.
                last = ScanDecision(
                    outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                    message = refusedForSlot(target.toScanSlot()),
                    raw = scan.data, symbology = scan.symbology,
                )
                app.feedback.play(Beep.REJECT)
                return@collect
            }

            draft = draft.withScan(fragment)
            armed = null
            app.feedback.play(Beep.ACCEPT)

            val d = draft
            if (d.isComplete) {
                val sid = ensure(r)
                accept(
                    r, sid,
                    r.receiveAssembled(
                        sid, d.pid, d.boxSerial, d.qty!!, d.rawTrail,
                        labelDescription = d.description,
                    ),
                )
            }
        }
    }

    // One place that posts, called from the Done button and from the answer to
    // the unnamed-products prompt.
    val submitNow: () -> Unit = {
        scope.launch {
            val r = repo
            val sid = sessionId
            if (r != null && sid != null) {
                submitting = true
                val resp = r.submit(sid)
                submitting = false
                // Only sound the saved chime when it actually saved. A refusal
                // that sounds like success is worse than no sound at all.
                if (resp == null || resp.ok) app.feedback.playSessionPosted()
                result = when {
                    resp == null -> "Saved. It will post to Tally when the connection returns."
                    !resp.ok -> resp.message.ifEmpty { "Tally would not accept this stock entry." }
                    // The relay's own words: it has just created the missing
                    // products itself, so this is a short wait for Tally to
                    // answer, not a job left for somebody.
                    resp.unresolvedLines > 0 ->
                        resp.message.ifEmpty {
                            "Saved. Adding the new product to Tally - this posts by itself in a moment."
                        }
                    resp.dispatched -> "Sent to Tally."
                    else -> "Saved. Waiting for Tally to come back."
                }
            }
        }
        Unit
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
        submitLabel = "Done · post stock entry",
        onSubmit = {
            // A receipt does not post while a product on it has no name.
            //
            // It used to post anyway, creating "0635484" in Tally with no
            // description -- which was not a new product at all but the part
            // number read where the PID was meant, so real stock landed on a
            // product that does not exist. Two of those reached the day book.
            //
            // The relay enforces this as well; it has to, because the phone is
            // not the only thing that can submit. This is here so the operator
            // finds out while the carton is still in their hand.
            val unnamed = lines.filter { it.flags.contains("UNRESOLVED_PID") }
                .map { it.pid }.distinct()
            if (unnamed.isNotEmpty()) unnamedPrompt = unnamed else submitNow()
        },
        slots = {
            BoxSlots(
                draft = draft,
                onTypeProduct = { armed = null; typing = BoxDraft.Slot.PRODUCT },
                onTypeBox = { armed = null; typing = BoxDraft.Slot.BOX },
                onTypeQuantity = { armed = null; typing = BoxDraft.Slot.QUANTITY },
                armed = armed,
                // Tapping the armed field again cancels it, so a mis-tap is
                // undone the same way it was made.
                onArmScan = { slot -> armed = if (armed == slot) null else slot },
            )
        },
        onReadLabel = { reading = true },
        onOpenGroup = { openGroup = it },
        onEditLine = { editing = it },
        onRemoveLine = { id -> scope.launch { repo?.deleteLine(id) } },
    )

    if (reading) {
        LabelCameraSheet(
            onClose = { reading = false; cameraResult = null },
            lastResult = cameraResult,
            added = lines.size,
            onCorrect = { product, box, qty, description ->
                // Out of the camera and into the slots, where each field can be
                // edited. Nothing is committed on the way.
                reading = false
                cameraResult = null
                var next = draft
                product?.let { next = next.withTypedProduct(it) }
                box?.let { next = next.withTypedBox(it) }
                qty?.let { next = next.withTypedQty(it) }
                draft = next.withDescription(description)
            },
            onAdd = { product, box, qty, description ->
                var next = draft
                product?.let { next = next.withTypedProduct(it) }
                box?.let { next = next.withTypedBox(it) }
                qty?.let { next = next.withTypedQty(it) }
                next = next.withDescription(description)
                draft = next

                if (!next.isComplete) {
                    // Not a whole carton: what was read goes to the slots and
                    // the camera steps out of the way so the rest can be typed.
                    reading = false
                    cameraResult = null
                } else {
                    // A whole carton is added to the receipt, exactly as a
                    // complete scan is -- and nothing is posted to Tally. The
                    // receipt is sent when the operator says so, from the
                    // button on the scan screen, never from here.
                    scope.launch {
                        val r = repo ?: return@launch
                        val sid = ensure(r)
                        val d = r.receiveAssembled(
                            sid, next.pid, next.boxSerial, next.qty!!, next.rawTrail,
                            labelDescription = next.description,
                        )
                        accept(r, sid, d)
                        cameraResult = d.message
                    }
                }
            },
        )
        return
    }

    openGroup?.let { group ->
        // Re-read from the session each time, so a removal is reflected without
        // reopening, and the dialog closes once the last box has gone.
        val current = groupLines(lines).firstOrNull { it.pid == group.pid }
        if (current == null) {
            openGroup = null
        } else {
            BoxesDialog(
                group = current,
                onEdit = { editing = it },
                onRemove = { id -> scope.launch { repo?.deleteLine(id) } },
                onClose = { openGroup = null },
            )
        }
    }

    editing?.let { line ->
        EditBoxDialog(
            line = line,
            onCancel = { editing = null },
            onSave = { boxSerial, qty ->
                val target = line
                editing = null
                scope.launch {
                    val r = repo ?: return@launch
                    val sid = sessionId ?: return@launch
                    // Through the same checks as a scan: changing a box number
                    // changes which box it is, and a corrected box must not be
                    // able to duplicate one already on the receipt.
                    val d = r.correctLine(sid, target.id, target.pid, boxSerial, qty)
                    last = d
                    app.feedback.play(d.beep)
                }
            },
        )
    }

    unnamedPrompt?.let { pids ->
        UnnamedProductsDialog(
            pids = pids,
            onName = {
                // Straight to the prompt for the first one, with the carton's
                // own line to hand. Naming it clears the flag, so the next tap
                // on Done asks about whatever is still left.
                unnamedPrompt = null
                val line = lines.firstOrNull { it.pid == pids.first() }
                if (line != null) {
                    newProduct = ScanDecision(
                        outcome = Outcome2.FLAGGED, beep = Beep.FLAGGED,
                        message = "New product ${line.pid}",
                        pid = line.pid, boxSerial = line.boxSerial,
                        labelQty = line.qty, flags = listOf("UNRESOLVED_PID"),
                    )
                }
            },
            onCancel = { unnamedPrompt = null },
        )
    }

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
                        )
                    }
                }
            },
        )
    }

    if (readingName) {
        // Shown INSTEAD of the prompt, not over it. newProduct stays set, so
        // closing this comes straight back to the prompt with whatever was
        // read already in the box.
        LabelCameraSheet(
            descriptionOnly = true,
            onClose = { readingName = false },
            lastResult = null,
            added = 0,
            onCorrect = { _, _, _, description ->
                capturedName = description
                readingName = false
            },
            onAdd = { _, _, _, description ->
                capturedName = description
                readingName = false
            },
        )
        return
    }

    newProduct?.let { d ->
        NewProductDialog(
            decision = d,
            defaultUnit = defaultUnit,
            capturedDescription = capturedName,
            onReadDescription = { readingName = true },
            onSkip = { newProduct = null; capturedName = null },
            onSave = { description, unit ->
                scope.launch {
                    val r = repo ?: return@launch
                    val sid = sessionId ?: return@launch
                    // Closed first. Leaving it up while this writes makes the
                    // Save button look dead, and the operator taps it again.
                    newProduct = null
                    capturedName = null
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
    /** The field waiting for a targeted scan on a despatch. */
    var outArmed by remember { mutableStateOf<BoxDraft.Slot?>(null) }
    var outTyping by remember { mutableStateOf<BoxDraft.Slot?>(null) }
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
                // Same rule as a receipt: a plain trigger pull takes the long
                // three-part barcode and nothing else. The flows must not
                // differ on this -- the same carton and the same scanner
                // behaving one way inbound and another outbound is how an
                // operator learns to stop reading the screen.
                val target = outArmed
                if (target == null) {
                    last = ScanDecision(
                        outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                        message = "Scan the long barcode with three parts. " +
                            "For a single field, tap Scan beside it first.",
                        raw = scan.data, symbology = scan.symbology,
                    )
                    app.feedback.play(Beep.REJECT)
                    return@collect
                }

                val fragment = classifyFragmentFor(scan.data, target.toScanSlot())
                if (fragment.kind == FragmentKind.NOT_MINE) {
                    last = ScanDecision(
                        outcome = Outcome2.WRONG_BARCODE, beep = Beep.REJECT,
                        message = refusedForSlot(target.toScanSlot()),
                        raw = scan.data, symbology = scan.symbology,
                    )
                    app.feedback.play(Beep.REJECT)
                    return@collect
                }
                outArmed = null

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
        slots = {
            BoxSlots(
                draft = outDraft,
                showQuantity = false,
                onTypeProduct = { outArmed = null; outTyping = BoxDraft.Slot.PRODUCT },
                onTypeBox = { outArmed = null; outTyping = BoxDraft.Slot.BOX },
                onTypeQuantity = {},
                armed = outArmed,
                onArmScan = { slot -> outArmed = if (outArmed == slot) null else slot },
            )
        },
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
    )

    outTyping?.let { slot ->
        SlotEntryDialog(
            slot = slot,
            draft = outDraft,
            onCancel = { outTyping = null },
            onConfirm = { value ->
                outDraft = when (slot) {
                    BoxDraft.Slot.PRODUCT -> outDraft.withTypedProduct(value)
                    BoxDraft.Slot.BOX -> outDraft.withTypedBox(value)
                    BoxDraft.Slot.QUANTITY -> outDraft
                }
                outTyping = null

                // A typed field completes a despatch exactly as a scanned one
                // does: same checks, same keypad, same ceilings.
                val d = outDraft
                if (d.pid.isNotEmpty() && d.boxSerial.isNotEmpty()) {
                    scope.launch {
                        val r = repo ?: return@launch
                        val sid = ensure(r)
                        val decision = r.despatchAssembled(
                            sid, salesOrder, godown, d.pid, d.boxSerial, d.rawTrail,
                        )
                        outDraft = BoxDraft()
                        last = decision
                        app.feedback.play(decision.beep)
                        if (decision.outcome == Outcome2.ACCEPT) qtyFor = decision
                    }
                }
            },
        )
    }

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
    /**
     * The unit this company creates items with, resolved by the caller.
     *
     * Passed in rather than looked up here: Tally refuses a unit the company
     * has not defined, and the right symbol is a property of the books, not of
     * this dialog.
     */
    defaultUnit: String,
    /**
     * A name read off the carton, when the operator asked the camera for one.
     *
     * The long barcode carries a part number and a count but never the
     * product's name, so for a product in neither Tally nor the price list
     * there is nowhere for that name to come from except the carton itself.
     */
    capturedDescription: String? = null,
    /** Opens the camera to read ONLY the printed name. */
    onReadDescription: (() -> Unit)? = null,
    onSkip: () -> Unit,
    onSave: (description: String, unit: String) -> Unit,
) {
    // Prefilled from the price list when it knows this part number, so the
    // operator confirms rather than types. They supply only what the catalogue
    // cannot know.
    val known = decision.catalogueDescription
    var description by remember(decision.pid) { mutableStateOf(known ?: "") }
    // The unit is LEARNED, never hardcoded: Tally refuses a unit the company
    // has not defined, and the symbol differs between sets of books. Comes from
    // this company's own items, or from what the relay says it will send.
    var unit by remember(decision.pid) { mutableStateOf(defaultUnit) }
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
                // A name the camera read replaces what is in the box, because
                // the operator asked for it deliberately.
                LaunchedEffect(capturedDescription) {
                    capturedDescription?.takeIf { it.isNotBlank() }?.let { description = it }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (known != null) "Description" else "What is it?") },
                    placeholder = { Text("SSD SENSOR BASE") },
                    trailingIcon = onReadDescription?.let {
                        {
                            IconButton(onClick = it) {
                                Icon(Icons.Default.PhotoCamera, "Read the name off the carton")
                            }
                        }
                    },
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
                    supportingText = {
                        Text("As Tally spells it. This company uses \"$defaultUnit\".")
                    },
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
                        "pallet will scan without asking again. Skip it and the " +
                        "box still posts - the product goes in under its part " +
                        "number, and can be named in Tally later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(description.trim(), unit.trim()) },
                enabled = description.trim().length >= 3,
            ) { Text("Save") }
        },
        // Skipping costs nothing now: the product is created from the part
        // number alone if nobody names it, so the box is never held back.
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
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
    /** The box being assembled, for the flows that build one field at a time. */
    slots: (@Composable () -> Unit)? = null,
    /** Offered only where reading a printed label makes sense. */
    onReadLabel: (() -> Unit)? = null,
    /** Opens a product's boxes, so a wrong one can be taken off the receipt. */
    onOpenGroup: ((LineGroup) -> Unit)? = null,
    /** Edits one box straight from the session list, without opening a group. */
    onEditLine: ((SessionLineEntity) -> Unit)? = null,
    onRemoveLine: ((Long) -> Unit)? = null,
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
                    onReadLabel?.let {
                        IconButton(onClick = it) {
                            Icon(Icons.Default.PhotoCamera, "Read the label")
                        }
                    }
                },
            )
        },
    ) { pad ->
        // Which company this count is going into, kept on screen throughout.
        //
        // The home screen refuses to open this flow against the wrong company
        // and the receipt is refused again on submit, but neither of those
        // helps somebody already standing at the dock halfway through a pallet.
        // Naming it here is what lets them notice before the pallet is done
        // rather than after.
        val app = LocalContext.current.applicationContext as ScanToTallyApp
        var company by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { company = app.config.company.first() }

        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            if (company.isNotBlank()) {
                Text(
                    company,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            // Two tabs rather than one cramped column.
            //
            // The session list used to live in whatever height was left between
            // the scan card and the Done button, which on a handset is a couple
            // of rows. Checking what had been scanned meant scrolling a window
            // too small to see it through. Scanning and reviewing are different
            // jobs and now get a screen each.
            var tab by rememberSaveable { mutableStateOf(0) }

            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Scan") },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        Text(
                            if (lines.isEmpty()) "This session"
                            else "This session · ${lines.size}",
                        )
                    },
                )
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize()) {
                        slots?.let {
                            it()
                            Spacer(Modifier.height(10.dp))
                        }

                        ScanResultCard(last)

                        result?.let {
                            Spacer(Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            ) {
                                Text(
                                    it, Modifier.padding(14.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }

                        if (lines.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionLabel("Last scanned")
                            // The newest box, in full, without changing tab.
                            // A scan that went on wrong is worth catching on
                            // the next carton, not at the end of the pallet.
                            SessionLineRow(
                                line = lines.last(),
                                newest = true,
                                onEdit = { l -> onEditLine?.invoke(l) },
                                onRemove = { id -> onRemoveLine?.invoke(id) },
                            )
                        }
                    }

                    else -> {
                        if (lines.isEmpty()) {
                            Text(
                                "Nothing scanned yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                SectionLabel(
                                    "${lines.size} " +
                                        (if (lines.size == 1) "box" else "boxes") +
                                        " · ${fmtQty(lines.sumOf { it.qty })} total",
                                )
                                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                    // Newest first: the scan just made is the
                                    // one being checked, and it was previously
                                    // at the bottom of a list that had to be
                                    // scrolled to reach.
                                    items(
                                        items = lines.reversed(),
                                        key = { it.id },
                                    ) { line ->
                                        SessionLineRow(
                                            line = line,
                                            newest = line.id == lines.last().id,
                                            onEdit = { l -> onEditLine?.invoke(l) },
                                            onRemove = { id -> onRemoveLine?.invoke(id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

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

/**
 * The draft's slots and the classifier's are separate types on purpose -- one
 * is "what does this box still need", the other "what is the scanner pointed
 * at" -- so the crossing is written once, here.
 */
private fun BoxDraft.Slot.toScanSlot(): ScanSlot = when (this) {
    BoxDraft.Slot.PRODUCT -> ScanSlot.PRODUCT
    BoxDraft.Slot.BOX -> ScanSlot.BOX
    BoxDraft.Slot.QUANTITY -> ScanSlot.QUANTITY
}

/**
 * A receipt cannot post while a product on it has no description.
 *
 * This DOES block, and that is the point. An unnamed product is nearly always
 * the part-number barcode read where the PID was meant -- 0635484 instead of
 * 2084-9009 -- so posting it puts real stock on a product that does not exist,
 * under a name nobody can search for. Two of those reached the live day book.
 *
 * Nothing is lost by stopping here. Every carton stays counted on the receipt,
 * and it posts by itself the moment a name is given.
 */
@Composable
private fun UnnamedProductsDialog(
    pids: List<String>,
    onName: () -> Unit,
    onCancel: () -> Unit,
) {
    SuspendScanCapture()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (pids.size == 1) "One product needs a name" else "${pids.size} products need a name") },
        text = {
            Column {
                Text(
                    "This cannot post until these have a description:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                for (pid in pids) {
                    Text(
                        pid,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "If a number here is the PART NUMBER rather than the PID, remove " +
                        "that box and scan it again: the PID is the code printed " +
                        "under PID or Type. Nothing is lost -- every carton stays " +
                        "counted, and this posts by itself once they have names.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSemantics.current.review.fg,
                )
            }
        },
        confirmButton = { Button(onClick = onName) { Text("Name them") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Back") } },
    )
}
