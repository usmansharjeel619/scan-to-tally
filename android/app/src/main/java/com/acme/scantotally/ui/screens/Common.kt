package com.acme.scantotally.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acme.scantotally.data.Outcome2
import com.acme.scantotally.data.ScanDecision
import com.acme.scantotally.data.SessionLineEntity
import com.acme.scantotally.ui.theme.LocalSemantics
import kotlin.math.abs

fun fmtQty(v: Double): String =
    if (abs(v - v.toLong()) < 1e-4) v.toLong().toString()
    else String.format("%.3f", v).trimEnd('0').trimEnd('.')

fun tail(serial: String): String = if (serial.length > 7) "…" + serial.takeLast(7) else serial

/**
 * The connector's state, shown honestly and permanently.
 *
 * Operators tolerate delay; they do not tolerate not knowing. A queue that is
 * waiting because a laptop is asleep is fine, as long as it says so.
 */
@Composable
fun ConnectionBanner(
    health: String,
    pending: Int,
    failed: Int,
    modifier: Modifier = Modifier,
    company: String = "",
) {
    val sem = LocalSemantics.current
    val (bg, fg, text) = when (health) {
        "ONLINE" -> Triple(sem.accept.bg, sem.accept.fg, "Tally connected")
        "BUSY" -> Triple(sem.review.bg, sem.review.fg, "Tally busy")
        "COMPANY_CLOSED" -> Triple(sem.review.bg, sem.review.fg, "Company not open in Tally")
        "OFFLINE" -> Triple(sem.reject.bg, sem.reject.fg, "Tally unreachable")
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Checking Tally…",
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(fg))
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        // A silent queue destroys trust faster than a slow one, so the count is
        // always visible rather than tucked behind a screen.
        if (pending > 0) {
            Text("$pending waiting", color = fg, style = MaterialTheme.typography.labelMedium)
        }
        if (failed > 0) {
            Spacer(Modifier.width(12.dp))
            Text("$failed need review", color = sem.reject.fg, style = MaterialTheme.typography.labelLarge)
        }
    }

    // The company is shown on every screen that can write to Tally, even when
    // nothing is wrong.
    //
    // A name that is only displayed when it is wrong is a name nobody learns to
    // read. Keeping it visible all the time is what makes the wrong one
    // noticeable at a glance, rather than something discovered a week later in
    // the ledger.
    if (company.isNotBlank()) {
        Text(
            company,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .padding(start = 36.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

/**
 * Shown when Tally is posting into a company this phone was not set up for.
 *
 * Deliberately not a toast and not a colour change: it takes the whole width,
 * says both names, and the buttons behind it stop working. A warning that can
 * be scanned past is worse than none, because it creates the impression that
 * somebody is checking.
 */
@Composable
fun WrongCompanyBanner(pinned: String, open: String, modifier: Modifier = Modifier) {
    val sem = LocalSemantics.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(sem.reject.bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Wrong company — scanning is stopped",
            color = sem.reject.fg,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "This phone counts for \u201C$pinned\u201D, but Tally is set to " +
                "\u201C$open\u201D. Nothing will be sent until they match.",
            color = sem.reject.fg,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Open the right company in Tally on the office PC, then press Sync now.",
            color = sem.reject.fg,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The last scan, large enough to read at arm's length.
 *
 * Secondary to the beep: the operator looks here only when something sounded
 * wrong, so the colour and the first line have to carry the whole message.
 */
@Composable
fun ScanResultCard(decision: ScanDecision?, modifier: Modifier = Modifier) {
    if (decision == null) {
        Card(
            modifier = modifier.fillMaxWidth().heightIn(min = 108.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Pull the trigger to scan a box",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val sem = LocalSemantics.current
    val tone = when (decision.outcome) {
        Outcome2.ACCEPT -> sem.accept
        Outcome2.FLAGGED, Outcome2.DUPLICATE -> sem.review
        Outcome2.WRONG_BARCODE, Outcome2.REJECT -> sem.reject
    }
    val bg = tone.bg
    val fg = tone.fg

    Card(
        modifier = modifier.fillMaxWidth().heightIn(min = 108.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (decision.outcome) {
                    Outcome2.ACCEPT -> "ACCEPTED"
                    Outcome2.FLAGGED -> "COUNTED · NEEDS REVIEW"
                    Outcome2.DUPLICATE -> "DUPLICATE"
                    Outcome2.WRONG_BARCODE -> "WRONG BARCODE"
                    Outcome2.REJECT -> "SET THIS BOX ASIDE"
                },
                color = fg,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                decision.message,
                // Explicitly the card's own foreground, not the scheme's:
                // the card has its own background, so the surrounding scheme
                // says nothing useful about what is readable on it.
                color = sem.onCard,
                style = MaterialTheme.typography.titleLarge,
            )
            if (decision.boxSerial.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Box ${tail(decision.boxSerial)}" +
                        if (decision.available != null) "  ·  ${fmtQty(decision.available)} in box" else "",
                    color = sem.onCard.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // On a despatch the box figure alone does not say what may be sent:
            // the order is just as hard a limit, and is usually the smaller of
            // the two. Showing only one of them leaves the operator to discover
            // the other by being refused.
            decision.orderPending?.let { pending ->
                Spacer(Modifier.height(2.dp))
                Text(
                    "${fmtQty(pending)} still on the order",
                    color = sem.onCard.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** One part number with its boxes nested beneath: how a pallet is thought about. */
data class LineGroup(
    val stockItemName: String,
    val pid: String,
    val description: String,
    val unit: String,
    val lines: List<SessionLineEntity>,
) {
    val boxCount: Int get() = lines.size
    val totalQty: Double get() = lines.sumOf { it.qty }
    val hasFlags: Boolean get() = lines.any { it.flags.isNotEmpty() }
}

/**
 * Groups scans by part number.
 *
 * Operators think "SSD SENSOR BASE, three boxes, 54 pieces", not in a flat list
 * of eighteen scans -- and it mirrors how the voucher is built, one line per
 * part number with N batch allocations under it.
 */
fun groupLines(lines: List<SessionLineEntity>): List<LineGroup> =
    lines.groupBy { it.stockItemName.ifEmpty { "?${it.pid}" } }
        .map { (_, group) ->
            val first = group.first()
            LineGroup(
                stockItemName = first.stockItemName,
                pid = first.pid,
                description = first.description,
                unit = first.unit,
                lines = group,
            )
        }

/**
 * The boxes of one product on this receipt: each one editable, each removable.
 *
 * A wrong box has to be fixable before the receipt is posted. The camera reads
 * a digit wrongly now and then, and a quantity can be typed wrongly too, and
 * neither is a reason to discard a receipt and scan the whole pallet again.
 */
@Composable
fun BoxesDialog(
    group: LineGroup,
    onEdit: (SessionLineEntity) -> Unit,
    onRemove: (Long) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(group.description.ifEmpty { group.stockItemName }) },
        text = {
            Column {
                Text(
                    "Tap a box to change it. Nothing here has gone to Tally yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                group.lines.forEach { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(line) }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                line.boxSerial,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${fmtQty(line.qty)} ${line.unit}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onRemove(line.id) }) {
                            Text("Remove", color = LocalSemantics.current.reject.fg)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** Changing a box that is already on the receipt. */
@Composable
fun EditBoxDialog(
    line: SessionLineEntity,
    onCancel: () -> Unit,
    onSave: (boxSerial: String, qty: Int) -> Unit,
) {
    var box by remember(line.id) { mutableStateOf(line.boxSerial) }
    var qty by remember(line.id) { mutableStateOf(fmtQty(line.qty)) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(line.description.ifEmpty { line.pid }) },
        text = {
            Column {
                OutlinedTextField(
                    value = box,
                    onValueChange = { box = it.trim().take(32) },
                    label = { Text("Box number") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = qty,
                    onValueChange = { qty = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("Quantity") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(box.trim(), qty.toIntOrNull() ?: 0) },
                enabled = box.isNotBlank() && (qty.toIntOrNull() ?: 0) > 0,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun GroupRow(group: LineGroup, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (group.hasFlags) LocalSemantics.current.review.bg
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                group.description.ifEmpty { group.stockItemName.ifEmpty { "Unknown product ${group.pid}" } },
                style = MaterialTheme.typography.titleMedium,
                color = if (group.hasFlags) LocalSemantics.current.onCard
                else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val gsem = LocalSemantics.current
                val onGroup = if (group.hasFlags) gsem.onCard else MaterialTheme.colorScheme.onSurface
                Text(
                    "${group.boxCount} ${if (group.boxCount == 1) "box" else "boxes"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGroup.copy(alpha = 0.75f),
                )
                Text(
                    "${fmtQty(group.totalQty)} ${group.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = onGroup,
                )
                if (group.hasFlags) {
                    Text("needs review", style = MaterialTheme.typography.bodyMedium, color = gsem.review.fg)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "tap to change",
                    style = MaterialTheme.typography.bodyMedium,
                    color = onGroup.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

/**
 * One scanned box, with everything needed to judge it without opening anything.
 *
 * Part number, box number and quantity are all on the face of the row. The
 * previous list showed a product and a count and made the operator tap into a
 * group to see which boxes were actually on the receipt, which is no use when
 * the question is "did that last one go on right".
 */
@Composable
fun SessionLineRow(
    line: SessionLineEntity,
    /** Marked so the most recent scan is findable at a glance. */
    newest: Boolean = false,
    onEdit: (SessionLineEntity) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val sem = LocalSemantics.current
    val flags = line.flags.split(",").filter { it.isNotBlank() }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                flags.isNotEmpty() -> sem.review.bg
                newest -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                line.description.ifEmpty {
                    line.stockItemName.ifEmpty { "Unknown product" }
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))

            // The three facts, each labelled, because a bare string of digits
            // beside another bare string of digits tells nobody which is which.
            FieldLine("Part", line.pid)
            FieldLine("Box", line.boxSerial)
            FieldLine("Qty", "${fmtQty(line.qty)} ${line.unit}".trim())

            if (flags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    flags.joinToString(" · ") { it.replace('_', ' ').lowercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = sem.review.fg,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onEdit(line) }) { Text("Edit") }
                TextButton(onClick = { onRemove(line.id) }) {
                    Text("Remove", color = sem.reject.fg)
                }
            }
        }
    }
}

/** A labelled value, aligned so the same field lands in the same place. */
@Composable
private fun FieldLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
        )
        Text(
            value.ifEmpty { "—" },
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}
