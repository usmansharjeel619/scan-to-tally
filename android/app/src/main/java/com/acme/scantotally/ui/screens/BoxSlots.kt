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
     * The field waiting for a targeted scan, if any.
     *
     * A plain trigger pull takes the long three-part barcode and nothing else.
     * To scan one of the smaller barcodes the operator arms the field first,
     * which is what makes it safe: a quantity and a week number are the same
     * shape, and only saying which one is meant can tell them apart.
     */
    armed: BoxDraft.Slot? = null,
    onArmScan: ((BoxDraft.Slot) -> Unit)? = null,
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
                armed = armed == BoxDraft.Slot.PRODUCT,
                onArm = onArmScan?.let { { it(BoxDraft.Slot.PRODUCT) } },
            )
            Slot(
                label = "BOX",
                value = draft.boxSerial,
                mono = true,
                scanned = BoxDraft.Slot.BOX in draft.scanned,
                onType = onTypeBox,
                armed = armed == BoxDraft.Slot.BOX,
                onArm = onArmScan?.let { { it(BoxDraft.Slot.BOX) } },
            )
            if (showQuantity) Slot(
                // Scannable ONLY when armed. A quantity barcode and a week
                // number are the same shape, so a loose scan can never place
                // one -- but once the operator has said "this is the quantity",
                // there is nothing left to get wrong.
                label = "QUANTITY",
                value = draft.qty?.takeIf { it > 0 }?.toString().orEmpty(),
                mono = false,
                scanned = BoxDraft.Slot.QUANTITY in draft.scanned,
                onType = onTypeQuantity,
                armed = armed == BoxDraft.Slot.QUANTITY,
                onArm = onArmScan?.let { { it(BoxDraft.Slot.QUANTITY) } },
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
    armed: Boolean = false,
    onArm: (() -> Unit)? = null,
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
        } else if (armed) {
            // Said plainly, because the operator has to know the next trigger
            // pull goes somewhere different from usual.
            Text(
                "waiting for a scan…",
                style = MaterialTheme.typography.bodyMedium,
                color = sem.accept.fg,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onArm?.invoke() }) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.weight(1f))
            if (scannable && onArm != null) {
                TextButton(onClick = onArm) {
                    Text("Scan", color = sem.accept.fg, fontWeight = FontWeight.SemiBold)
                }
            }
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

    // An unusual product code is questioned, not refused.
    //
    // Every code seen so far is nnnn-nnnn, so something else is far more often
    // a typo than a real product -- "GGVVCCH" was typed in and sat in a receipt
    // that could never post. But suppliers do differ, and a rule strict enough
    // to block a genuine code would leave the operator with no way to receive
    // the carton in their hands. So it is said out loud and then allowed.
    val unusual = slot == BoxDraft.Slot.PRODUCT &&
        value.isNotBlank() &&
        classifyFragment(value).kind != FragmentKind.PRODUCT

    val valid = when (slot) {
        BoxDraft.Slot.QUANTITY -> (value.toIntOrNull() ?: 0) > 0
        else -> value.isNotBlank()
    }

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
                modifier = Modifier.fillMaxWidth(),
            )
            if (unusual) {
                Spacer(Modifier.height(6.dp))
                Text(
                    // Said plainly, because this is the mistake that actually
                    // happens: the part number is printed inches from the PID
                    // and reads like a product code. Taking it invents a
                    // product and puts real stock on it.
                    if (looksLikeAPartNumber(value))
                        "That looks like the PART NUMBER, not the PID. The PID is " +
                            "printed under PID or Type, like 2084-9009."
                    else
                        "Most product codes look like 4098-9792. Check it, or carry on " +
                            "if this supplier is different.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalSemantics.current.review.fg,
                )
            }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }, enabled = valid) {
                Text(if (unusual) "Use it anyway" else "Done")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * Five to nine bare digits, optionally with a letter tail, and not the
 * eight-digit spelling of a PID.
 *
 * Kept beside the dialog that says it out loud rather than in the classifier:
 * the classifier's job is to refuse a scan, and this one's is to warn about
 * something typed, which is always allowed through.
 */
private fun looksLikeAPartNumber(v: String): Boolean {
    val s = v.trim().uppercase()
    return !Regex("^[0-9]{8}$").matches(s) && Regex("^[0-9]{5,9}[A-Z]{0,4}$").matches(s)
}
