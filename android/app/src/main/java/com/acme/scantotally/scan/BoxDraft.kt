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
        // A different product means a different carton, so whatever was on the
        // draft belonged to the last one.
        //
        // Without this a box number survives into the next box: a KAC carton
        // was received carrying "HFE283", which is the box number printed on a
        // Tyco carton scanned before it. The quantity would carry over the same
        // way, and a receipt built from two cartons is stock recorded against a
        // box that never held it.
        FragmentKind.PRODUCT -> forProduct(fragment.value).copy(
            scanned = scanned + Slot.PRODUCT - carriedOver(fragment.value),
            raws = if (isDifferentProduct(fragment.value)) listOf(fragment.raw)
            else raws + fragment.raw,
        )
        FragmentKind.BOX -> copy(
            boxSerial = fragment.value,
            scanned = scanned + Slot.BOX,
            raws = raws + fragment.raw,
        )
        // Never placed. A week number and a quantity are the same shape.
        FragmentKind.NOT_MINE -> this
    }

    fun withTypedProduct(v: String) =
        forProduct(v.trim()).copy(scanned = scanned - Slot.PRODUCT - carriedOver(v.trim()))
    fun withTypedBox(v: String) = copy(boxSerial = v.trim(), scanned = scanned - Slot.BOX)

    private fun isDifferentProduct(next: String) = pid.isNotEmpty() && pid != next

    /** Which fields a change of product invalidates. */
    private fun carriedOver(next: String): Set<Slot> =
        if (isDifferentProduct(next)) setOf(Slot.BOX, Slot.QUANTITY) else emptySet()

    /**
     * The draft as it should be for this product: unchanged when it is the same
     * one, emptied of the previous carton's box and quantity when it is not.
     */
    private fun forProduct(next: String): BoxDraft =
        if (isDifferentProduct(next)) BoxDraft(pid = next)
        else copy(pid = next)
    fun withTypedQty(v: Int) = copy(qty = v, scanned = scanned - Slot.QUANTITY)
}

/**
 * What to tell an operator who scanned a barcode that is none of our fields.
 *
 * Says what to do, not why. The reasoning behind refusing a bare number --
 * that a week number and a quantity are the same shape -- is true but useless
 * to someone holding a carton, and on a label with no week number it reads as
 * nonsense.
 */
fun fragmentRefusal(fragment: Fragment): String = when (fragment.hint) {
    FragmentHint.PART_NO ->
        "That is not the product or the box barcode."
    else -> "That barcode is not the product or the box number."
}

/**
 * A scanned number offered as the quantity, for the operator to confirm.
 *
 * Refusing it outright was obstructive: with the quantity the only thing
 * missing, someone pointing a scanner at a number is plainly pointing at the
 * quantity. But it still cannot be taken on trust, because on a Tyco label the
 * week number and the issue number are the same shape and sit beside it.
 *
 * So it is offered rather than accepted or refused: the figure lands in the
 * box already filled in, and a human checks it against the carton before it
 * becomes stock. That is the same guarantee as typing, with almost none of the
 * work.
 */
fun BoxDraft.offeredQuantity(fragment: Fragment): Int? =
    if (fragment.hint == FragmentHint.QTY && qty == null) {
        fragment.raw.trim().toIntOrNull()?.takeIf { it > 0 }
    } else {
        null
    }
