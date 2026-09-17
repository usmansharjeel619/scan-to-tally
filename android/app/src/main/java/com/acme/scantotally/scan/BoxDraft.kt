package com.acme.scantotally.scan

/**
 * A box being put together from the separate barcodes on its carton.
 *
 * Most cartons do not carry the combined code. Tyco prints the part number,
 * quantity and box id as three barcodes; the US and UK Simplex labels print no
 * box id at all; KAC prints one barcode and the quantity as text. So a box is
 * assembled from whatever the label offers, scanned or typed, in any order.
 *
 * It commits only when all three fields are present. Nothing is ever assumed:
 * a quantity that was never entered does not quietly become 1, and a missing
 * box id does not silently become blank. That is the whole reason this is a
 * held draft rather than a running guess.
 */
data class BoxDraft(
    val pid: String = "",
    val boxSerial: String = "",
    /** Null until entered. Zero is a real answer to a different question. */
    val qty: Int? = null,

    /** Which fields arrived from a scanner rather than the keypad. */
    val scanned: Set<Slot> = emptySet(),

    /** Every payload that went into this box, kept for the audit trail. */
    val raws: List<String> = emptyList(),
) {
    enum class Slot { PRODUCT, BOX, QUANTITY }

    val isEmpty: Boolean get() = pid.isEmpty() && boxSerial.isEmpty() && qty == null

    val missing: List<Slot>
        get() = buildList {
            if (pid.isEmpty()) add(Slot.PRODUCT)
            if (boxSerial.isEmpty()) add(Slot.BOX)
            if (qty == null || qty <= 0) add(Slot.QUANTITY)
        }

    val isComplete: Boolean get() = missing.isEmpty()

    /** The payload stored against the line, so a disputed box can be traced. */
    val rawTrail: String get() = raws.joinToString(" + ")

    fun withScan(fragment: Fragment): BoxDraft = when (fragment.kind) {
        FragmentKind.PRODUCT -> copy(
            pid = fragment.value,
            scanned = scanned + Slot.PRODUCT,
            raws = raws + fragment.raw,
        )
        FragmentKind.BOX -> copy(
            boxSerial = fragment.value,
            scanned = scanned + Slot.BOX,
            raws = raws + fragment.raw,
        )
        // Never placed. A week number and a quantity are the same shape.
        FragmentKind.NOT_MINE -> this
    }

    fun withTypedProduct(v: String) = copy(pid = v.trim(), scanned = scanned - Slot.PRODUCT)
    fun withTypedBox(v: String) = copy(boxSerial = v.trim(), scanned = scanned - Slot.BOX)
    fun withTypedQty(v: Int) = copy(qty = v, scanned = scanned - Slot.QUANTITY)
}

/**
 * What to tell an operator who scanned a barcode that is on the label but is
 * not one of ours.
 *
 * These exist because the labels are crowded: a part no, a date code, a week
 * number and an issue number all sit next to the ones that matter, and someone
 * will point at them. Saying which is far better than a bare refusal.
 */
fun fragmentRefusal(fragment: Fragment): String = when (fragment.hint) {
    FragmentHint.QTY ->
        "That is a plain number, so it could be the quantity, the week number " +
            "or the issue number. Type the quantity instead."
    FragmentHint.PART_NO ->
        "That looks like the part no or a date code, not the product barcode."
    else -> "That barcode is not the product or the box number."
}
