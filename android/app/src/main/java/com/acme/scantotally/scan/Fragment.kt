package com.acme.scantotally.scan

/**
 * A scan that is ONE FIELD of a multi-barcode label rather than a whole box.
 *
 * Mirrors connector/internal/barcode/fragment.go and relay/src/fragment.ts.
 * All three run contracts/barcode-vectors.json; if they disagree, a barcode
 * means two different things in two places and stock drifts.
 *
 * The Tyco, KAC and US/UK Simplex cartons carry the part number, quantity, box
 * id, part no, date code, week number and issue number as separate barcodes.
 * Only two of those can be placed safely by looking at them.
 */
enum class FragmentKind {
    /** The part number: nnnn-nnnn on every label seen so far. */
    PRODUCT,

    /** The carton's own id. */
    BOX,

    /**
     * A barcode that belongs to the label but to none of our fields. Refused,
     * never guessed into a slot.
     *
     * This is why the quantity is typed rather than scanned: on a Tyco label
     * "Week No 17" and "Quantity 20" are both two-digit barcodes, and nothing
     * about the payload says which is which. Taking one for the other puts
     * stock wrong with nothing on screen to show for it.
     */
    NOT_MINE,
}

/** What the operator most likely pointed at, so a refusal can be useful. */
enum class FragmentHint { PART_NO, QTY }

data class Fragment(
    val kind: FragmentKind,
    /** The cleaned payload. Set for PRODUCT and BOX only. */
    val value: String = "",
    val hint: FragmentHint? = null,
    val raw: String = "",
)

private val PRODUCT = Regex("^[0-9]{4}-[0-9]{4}$")
private val SERIAL16 = Regex("^[0-9]{16}$")

/**
 * A Tyco box id: letters THEN digits -- HFE283, HKT710, HJO761.
 *
 * The ordering is load bearing. A Simplex part no also mixes letters and
 * digits ("0677197CN") but digits first, so a looser "contains both" rule
 * would file a part number as a box id and invent a batch no carton carries.
 */
private val BOX_ID = Regex("^[A-Z]{2,4}[0-9]{3,6}$")
private val DIGITS = Regex("^[0-9]+$")

/**
 * Decides which slot a lone barcode belongs in.
 *
 * It never guesses. Anything it cannot place with certainty comes back as
 * NOT_MINE, and the operator types that field instead.
 */
fun classifyFragment(raw: String): Fragment {
    val v = raw.trim().uppercase()

    return when {
        v.isEmpty() -> Fragment(FragmentKind.NOT_MINE, raw = raw)
        PRODUCT.matches(v) -> Fragment(FragmentKind.PRODUCT, value = v, raw = raw)
        SERIAL16.matches(v) -> Fragment(FragmentKind.BOX, value = v, raw = raw)
        BOX_ID.matches(v) -> Fragment(FragmentKind.BOX, value = v, raw = raw)

        // Length only picks the wording of the refusal, never whether to
        // accept: four digits or fewer could plausibly be a carton quantity
        // (and could equally be a week or issue number), more could not be.
        DIGITS.matches(v) -> Fragment(
            FragmentKind.NOT_MINE,
            hint = if (v.length <= 4) FragmentHint.QTY else FragmentHint.PART_NO,
            raw = raw,
        )

        v.any { it.isDigit() } ->
            Fragment(FragmentKind.NOT_MINE, hint = FragmentHint.PART_NO, raw = raw)

        else -> Fragment(FragmentKind.NOT_MINE, raw = raw)
    }
}
