package com.acme.scantotally.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.acme.scantotally.scan.BoxDraft
import com.acme.scantotally.scan.FragmentKind
import com.acme.scantotally.scan.classifyFragment
import com.acme.scantotally.scan.SuspendScanCapture
import com.acme.scantotally.ui.theme.LocalSemantics
import com.acme.scantotally.ui.theme.TouchTarget

/**
 * The box being put together, as three slots.
 *
 * The same three every time, whatever the carton. The operator scans whatever
 * barcodes the label has and each drops into its own slot; whatever is still
 * blank is tapped and typed. There is nothing to choose and no format to
 * declare -- an empty slot IS the instruction.
 *
 * Deliberately no timers and no defaults. A slot stays visibly empty until
 * someone fills it, which is what stops a missing quantity quietly becoming 1.
 */
@Composable
fun BoxSlots(
    draft: BoxDraft,
    onTypeProduct: () -> Unit,
    onTypeBox: () -> Unit,
    onTypeQuantity: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Off for a despatch, where the quantity is typed on the keypad that
     * follows -- checked against the box's real remaining stock rather than
     * anything printed on the carton.
     */
    showQuantity: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (if (showQuantity) draft.isComplete
            else draft.pid.isNotEmpty() && draft.boxSerial.isNotEmpty())
            LocalSemantics.current.accept.bg
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Slot(
                label = "PRODUCT",
                value = draft.pid,
                mono = true,
                scanned = BoxDraft.Slot.PRODUCT in draft.scanned,
                onType = onTypeProduct,
            )
            Slot(
                label = "BOX",
                value = draft.boxSerial,
                mono = true,
                scanned = BoxDraft.Slot.BOX in draft.scanned,
                onType = onTypeBox,
            )
            if (showQuantity) Slot(
                // Never scannable on these labels: a quantity barcode and a
                // week number are the same shape, so this one is always typed.
                label = "QUANTITY",
                value = draft.qty?.takeIf { it > 0 }?.toString().orEmpty(),
                mono = false,
                scanned = false,
                scannable = false,
                onType = onTypeQuantity,
            )
        }
    }
}

@Composable
private fun Slot(
    label: String,
    value: String,
    mono: Boolean,
    scanned: Boolean,
    onType: () -> Unit,
    scannable: Boolean = true,
) {
    val filled = value.isNotEmpty()
    val sem = LocalSemantics.current

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onType)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )

        if (filled) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // Says where the value came from, so a typed box number is never
            // mistaken for one that was read off the carton.
            Text(
                if (scanned) "scanned" else "typed",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                if (scannable) "scan it, or" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onType) {
                Text("Type", color = sem.review.fg, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** What is still needed, in the words the operator would use. */
fun BoxDraft.waitingFor(): String = when {
    isEmpty -> "Scan the carton"
    isComplete -> "Ready"
    else -> "Still need " + missing.joinToString(" and ") {
        when (it) {
            BoxDraft.Slot.PRODUCT -> "the product"
            BoxDraft.Slot.BOX -> "the box number"
            BoxDraft.Slot.QUANTITY -> "the quantity"
        }
    }
}

/**
 * Typing one field of the box.
 *
 * A number pad for the quantity, a keyboard for the other two. Deliberately
 * bare: the operator is holding a carton and reading one figure off it, and a
 * paragraph of explanation above the box is something to scroll past, not
 * something anyone reads at a dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotEntryDialog(
    slot: BoxDraft.Slot,
    draft: BoxDraft,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
    /** A figure read off a barcode, filled in for the operator to check. */
    offered: Int? = null,
) {
    SuspendScanCapture()

    val initial = when (slot) {
        BoxDraft.Slot.PRODUCT -> draft.pid
        BoxDraft.Slot.BOX -> draft.boxSerial
        BoxDraft.Slot.QUANTITY ->
            offered?.toString() ?: draft.qty?.takeIf { it > 0 }?.toString().orEmpty()
    }
    var value by remember(slot) { mutableStateOf(initial) }

    val numeric = slot == BoxDraft.Slot.QUANTITY

    // A typed field is held to the same standard as a scanned one.
    //
    // Typing skipped the check that scanning applies, so "GGVVCCH" went in as a
    // product code and sat in a receipt that could never post. A rule that
    // applies to the scanner and not the keyboard is not a rule.
    val problem = when (slot) {
        BoxDraft.Slot.QUANTITY ->
            if ((value.toIntOrNull() ?: 0) > 0) null else ""
        BoxDraft.Slot.PRODUCT ->
            if (value.isBlank()) ""
            else if (classifyFragment(value).kind == FragmentKind.PRODUCT) null
            else "Product codes look like 4098-9792."
        BoxDraft.Slot.BOX -> if (value.isBlank()) "" else null
    }
    val valid = problem == null

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when (slot) {
                    BoxDraft.Slot.PRODUCT -> "Product code"
                    BoxDraft.Slot.BOX -> "Box number"
                    BoxDraft.Slot.QUANTITY ->
                        if (offered != null) "Is this the quantity?" else "Quantity"
                },
            )
        },
        text = {
            Column {
            OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    value = if (numeric) v.filter { it.isDigit() }.take(6) else v.trim().take(32)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    capitalization = if (numeric) KeyboardCapitalization.None
                    else KeyboardCapitalization.Characters,
                ),
                isError = !problem.isNullOrEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!problem.isNullOrEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSemantics.current.reject.fg,
                )
            }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }, enabled = valid) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
