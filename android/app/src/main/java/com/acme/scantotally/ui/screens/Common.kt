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
import com.acme.scantotally.ui.theme.AcceptGreen
import com.acme.scantotally.ui.theme.AcceptGreenBg
import com.acme.scantotally.ui.theme.FlagAmber
import com.acme.scantotally.ui.theme.FlagAmberBg
import com.acme.scantotally.ui.theme.RejectRed
import com.acme.scantotally.ui.theme.RejectRedBg
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
fun ConnectionBanner(health: String, pending: Int, failed: Int, modifier: Modifier = Modifier) {
    val (bg, fg, text) = when (health) {
        "ONLINE" -> Triple(AcceptGreenBg, AcceptGreen, "Tally connected")
        "BUSY" -> Triple(FlagAmberBg, FlagAmber, "Tally busy")
        "COMPANY_CLOSED" -> Triple(FlagAmberBg, FlagAmber, "Company not open in Tally")
        "OFFLINE" -> Triple(RejectRedBg, RejectRed, "Tally unreachable")
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
            Text("$failed need review", color = RejectRed, style = MaterialTheme.typography.labelLarge)
        }
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

    val (bg, fg) = when (decision.outcome) {
        Outcome2.ACCEPT -> AcceptGreenBg to AcceptGreen
        Outcome2.FLAGGED -> FlagAmberBg to FlagAmber
        Outcome2.DUPLICATE -> FlagAmberBg to FlagAmber
        Outcome2.WRONG_BARCODE, Outcome2.REJECT -> RejectRedBg to RejectRed
    }

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
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            if (decision.boxSerial.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Box ${tail(decision.boxSerial)}" +
                        if (decision.available != null) "  ·  ${fmtQty(decision.available)} available" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
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

@Composable
fun GroupRow(group: LineGroup, onClick: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.hasFlags) FlagAmberBg else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                group.description.ifEmpty { group.stockItemName.ifEmpty { "Unknown product ${group.pid}" } },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${group.boxCount} ${if (group.boxCount == 1) "box" else "boxes"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${fmtQty(group.totalQty)} ${group.unit}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (group.hasFlags) {
                    Text(
                        "needs review",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FlagAmber,
                    )
                }
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
