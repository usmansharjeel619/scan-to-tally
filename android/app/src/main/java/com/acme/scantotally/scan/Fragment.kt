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

    /**
     * A quantity the operator explicitly asked to scan.
     *
     * Never produced by a blind scan, and that asymmetry is the point: a
     * quantity cannot be told apart from a week number by looking at it, but it
     * can be placed once the operator has said which field they are pointing at.
     */
    QUANTITY,
}

/**
 * The field the operator asked for BEFORE scanning.
 *
 * Blind scanning has to place a barcode by shape alone, which is why so much of
 * a label is refused. When the operator taps "scan the product number" first,
 * the slot stops being a guess and only the shape has to fit.
 *
 * Deliberately NOT BoxDraft.Slot. That one answers "which fields does this
 * draft still need"; this one answers "which field is the scanner pointed at".
 * They line up today and would drift the moment either grew a member.
 */
enum class ScanSlot { PRODUCT, BOX, QUANTITY }

/** What the operator most likely pointed at, so a refusal can be useful. */
enum class FragmentHint { PART_NO, QTY }

data class Fragment(
    val kind: FragmentKind,
    /** The cleaned payload. Set for PRODUCT and BOX only. */
    val value: String = "",
    val hint: FragmentHint? = null,
    val raw: String = "",
)

/**
 * A dashed part number.
 *
 * Simplex's own are nnnn-nnnn, but the same shelves carry JCI, Tyco, Apollo
 * and KAC cartons, and theirs carry LETTERS: a revision suffix (4090-9001B),
 * Apollo's 55000-390APO. A pattern that accepts only nnnn-nnnn refuses those
 * outright, and a part number the app refuses is a carton that does not get
 * counted -- which is the one outcome worth avoiding.
 *
 * Still only the DASHED form here, because this is the blind path, which never
 * guesses. Undashed codes stay ambiguous against a box id until the operator
 * says which field they are filling. A three-digit head stays out too: the
 * Simplex Mexico supplier ref "742-949" is nnn-nnn and is not a part number.
 */
private val PRODUCT = Regex("^[0-9]{4,6}-[0-9]{2,5}[A-Z]{0,4}$")
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
 * The part number, which is NOT the PID and must never be taken for one.
 *
 * A Simplex carton prints both: PID 2084-9009 and part no 0635484, inches
 * apart, and only the PID identifies the product in Tally. Reading the part
 * number instead creates a second product called "0635484" with no
 * description, holding stock that belongs to the real one -- which is exactly
 * what happened on the dock.
 *
 * The two are told apart by SHAPE. A PID is four digits, a dash and four
 * (2084-9009), or the same eight digits with the dash dropped. A part number
 * is five to nine digits with no dash, sometimes with a letter tail
 * (0677197CN). Eight bare digits are the one genuine ambiguity, and those go
 * to the PID: that spelling is on real cartons and is what resolves.
 */
private val DASHLESS_PID = Regex("^[0-9]{8}$")
private val PART_NUMBER = Regex("^[0-9]{5,9}[A-Z]{0,4}$")

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

/** A scanned carton count beyond this is a misread or a serial. */
private const val MAX_SCANNED_QTY = 9999

/**
 * Places a barcode into a slot the operator named.
 *
 * A payload that does not fit is still refused rather than coerced: scanning a
 * box serial into the quantity slot must fail, not become a quantity of
 * 1124241658336425.
 */
fun classifyFragmentFor(raw: String, want: ScanSlot): Fragment {
    val v = raw.trim().uppercase()
    if (v.isEmpty()) return Fragment(FragmentKind.NOT_MINE, raw = raw)

    return when (want) {
        ScanSlot.PRODUCT ->
            // The operator has SAID this is the part number, so it is taken as
            // one. Whatever a supplier prints -- dashed, undashed, lettered,
            // a code no pattern here has ever seen -- is a part number if that
            // is the field being filled.
            //
            // Only the three things that certainly are NOT one are refused,
            // and they are refused because each is a barcode printed inches
            // away on the same label: the 16-digit box serial, the small bare
            // number under QTY, and the two-letter country of origin.
            when {
                // The eight-digit spelling of a PID, before anything else can
                // mistake it for a part number.
                DASHLESS_PID.matches(v) ->
                    Fragment(FragmentKind.PRODUCT, value = v, raw = raw)

                // The barcode printed beside the one they wanted. Redirected
                // rather than accepted: taking it would invent a product.
                PART_NUMBER.matches(v) ->
                    Fragment(FragmentKind.NOT_MINE, hint = FragmentHint.PART_NO, raw = raw)

                // The three things certainly printed inches away on the same
                // label: the box serial, the number under QTY, and the
                // two-letter country of origin.
                SERIAL16.matches(v) ||
                    (DIGITS.matches(v) && v.length <= 4) ||
                    v.length < 3 || v.length > MAX_SERIAL_LEN ->
                    Fragment(FragmentKind.NOT_MINE, hint = FragmentHint.PART_NO, raw = raw)

                // Anything else the operator has aimed at the product field is
                // a part number, whatever a supplier chose to print.
                // Stored as scanned; reconciling the dash is the lookup's job.
                else -> Fragment(FragmentKind.PRODUCT, value = v, raw = raw)
            }

        ScanSlot.BOX ->
            if (SERIAL16.matches(v) || BOX_ID.matches(v)) {
                Fragment(FragmentKind.BOX, value = v, raw = raw)
            } else {
                Fragment(FragmentKind.NOT_MINE, raw = raw)
            }

        ScanSlot.QUANTITY -> {
            // Deliberately narrow: a quantity is a small positive number, and
            // anything longer is a serial or a date pointed at by mistake.
            val n = if (DIGITS.matches(v) && v.length <= 4) v.toIntOrNull() else null
            if (n == null || n <= 0 || n > MAX_SCANNED_QTY) {
                Fragment(FragmentKind.NOT_MINE, hint = FragmentHint.QTY, raw = raw)
            } else {
                Fragment(FragmentKind.QUANTITY, value = v, raw = raw)
            }
        }
    }
}

/** Why a targeted scan did not fit the field the operator asked for. */
fun refusedForSlot(want: ScanSlot): String = when (want) {
    ScanSlot.PRODUCT -> "That is the part number, not the PID. Scan the code printed under PID or Type."
    ScanSlot.BOX -> "That is not a box number. Scan the long serial, or the box id."
    ScanSlot.QUANTITY -> "That is not a quantity. Scan the number printed under QTY, or type it."
}
