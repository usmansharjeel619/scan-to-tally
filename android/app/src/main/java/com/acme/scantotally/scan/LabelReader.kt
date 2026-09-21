package com.acme.scantotally.scan

/**
 * Reading a carton label with the camera.
 *
 * The camera exists to see the one thing a barcode scanner cannot: the WORDS
 * next to the numbers. A scanner reads "17" and "35" off a Tyco label and has
 * no way to know which is the week number and which is the quantity. A camera
 * reads "Week No.: 17" and "Quantity: 35", and the association is the answer.
 *
 * So this is not OCR plus a guess. Every field is taken from the text that
 * NAMES it, and a number that belongs to a different name is actively refused
 * rather than merely not preferred.
 *
 * Nothing here is ever committed. Measured against the real cartons, OCR read
 * "4098-9783" for "4098-9788" -- a single digit wrong and still shaped exactly
 * like a valid part number. A reading that looks right and is not is the whole
 * risk, so a reading is only ever OFFERED, and only once several frames agree.
 */

/** One word the camera found, and where on the label it found it. */
data class TextWord(
    val text: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = left + width
    val centreY: Int get() = top + height / 2
    val clean: String get() = text.trim().trim(':', '#', '.', ',')
}

/** What one frame appeared to say. */
data class LabelReading(
    val product: String? = null,
    val box: String? = null,
    val qty: Int? = null,
    /**
     * What the carton calls the product, for one nobody has met before.
     *
     * A product that is in neither Tally nor the price list has to be named by
     * hand, and the name is printed on the label the camera is already reading.
     * Advisory only: it fills the prompt in so the operator confirms a name
     * rather than composing one.
     */
    val description: String? = null,
)

/**
 * Names that mean "the number beside me IS the product", in order of authority.
 *
 * A carton often carries BOTH a PID and a part number, and on these labels both
 * are 8 digits -- so nothing about the value tells them apart and only the word
 * beside it can. "PART" is therefore the LAST resort, used only when the label
 * names no PID at all: reading a part number as the product files stock against
 * a code Tally does not know.
 */
private val ANCHOR_PRODUCT_STRONG = setOf("pid", "type")
private val ANCHOR_PRODUCT_WEAK = setOf("part", "partno", "part-no")
private val ANCHOR_PRODUCT = ANCHOR_PRODUCT_STRONG + ANCHOR_PRODUCT_WEAK
private val ANCHOR_QTY = setOf("qty", "quantity")
private val ANCHOR_BOX = setOf("box", "serial", "boxid")

/**
 * Names that mean "this number is NOT a quantity".
 *
 * On one Tyco carton "Week No. 17" sits directly beside "Quantity 20", and on
 * another "Issue No. 5" beside "Quantity 35". Without these the nearest number
 * to the word "Quantity" is sometimes the wrong one, and a carton of 20 is
 * received as a carton of 17 with nothing on screen to say so.
 */
private val ANCHOR_NOT_QTY = setOf("week", "issue", "date", "code", "mfd", "exp")

private val ANCHOR_DESCRIPTION = setOf("description", "desc")

/**
 * The words that end a description.
 *
 * A description runs to the end of its line or two, and then the next field
 * begins. Without knowing where to stop, "2 WIRE BASE W/REMOTE LED" acquires
 * "PART NO 0677104" and becomes a name nobody would recognise.
 */
private val DESCRIPTION_ENDS = setOf(
    "part", "pid", "type", "qty", "quantity", "date", "code", "issue", "week",
    "serial", "box", "tlnr", "software", "made", "coo", "no", "no.",
)

/**
 * A dashed part number, letters allowed.
 *
 * Kept in step with the scanner's own pattern: a carton whose PID the camera
 * refuses but the scanner accepts (or the other way round) is a carton that
 * behaves differently depending on which button was pressed.
 */
private val PRODUCT_RE = Regex("^[0-9]{4,6}-[0-9]{2,5}[A-Z]{0,4}$")

/** The same part number with the dash dropped, and its lettered forms. Only
 *  ever read under an anchor, where the label says which field it is -- read
 *  blind, these are indistinguishable from half a dozen other numbers OCR
 *  finds on a carton. */
private val PRODUCT_NODASH_RE = Regex("^[0-9]{6,8}[A-Z]{0,4}$")
private val BOXID_RE = Regex("^[A-Z]{2,4}[0-9]{3,6}$")
private val SERIAL16_RE = Regex("^[0-9]{16}$")
private val NUMBER_RE = Regex("^[0-9]{1,6}$")

/**
 * "4100-3206|1120181448458237|1", as OCR tends to return it.
 *
 * The separator allows for a printed pipe coming back as I, l, / or a
 * backslash. It deliberately does NOT allow "1": a digit separator cannot be
 * told apart from the digits either side of it, and a serial read one
 * character short is far worse than one not read at all -- it would name a
 * box that does not exist and pass every check afterwards.
 *
 * The serial is digits only, which is what these actually are, so a letter
 * separator cannot be absorbed into it.
 */
private val COMBINED_RE = Regex(
    "([0-9]{4}-[0-9]{4}|[0-9]{8})[|Il/\\\\]([0-9]{10,20})[|Il/\\\\]([0-9]{1,5})",
    RegexOption.IGNORE_CASE,
)

/**
 * Reads one frame.
 *
 * Fields are found by their name first and by their shape second, because the
 * name is the only evidence that cannot be confused with another field.
 */
fun readLabel(words: List<TextWord>): LabelReading {
    if (words.isEmpty()) return LabelReading()

    // The combined line first, where there is one.
    //
    // On the Simplex cartons the box number is printed nowhere else: it lives
    // inside "4100-3206|1120181448458237|1", between the part number and the
    // quantity, and there is no separate Box or Serial field to read. Looking
    // only for a standalone box number finds nothing on those labels.
    combined(words)?.let { return it.copy(description = readDescription(words)) }

    return LabelReading(
        product = readProduct(words),
        box = readBox(words),
        qty = readQuantity(words),
        description = readDescription(words),
    )
}

/**
 * The whole box, printed as one line under SERIAL#.
 *
 * Read from the TEXT rather than the barcode, because that is all a camera
 * sees. OCR breaks a long line into several words and is unreliable about the
 * separator -- a pipe comes back as I, l, 1 or a space depending on the
 * printing -- so the line is reassembled and the separators are treated
 * loosely. The fields themselves are not: a part number must still look like
 * one and a quantity must still be a number, or nothing is returned.
 */
private fun combined(words: List<TextWord>): LabelReading? {
    for (line in lines(words)) {
        val joined = line.joinToString("") { it.text }.replace(" ", "")
        val m = COMBINED_RE.find(joined) ?: continue

        val serial = m.groupValues[2]
        val qty = m.groupValues[3].toIntOrNull() ?: continue
        if (qty <= 0) continue

        return LabelReading(
            // Positional inside the long code, so a missing dash is unambiguous.
            product = m.groupValues[1],
            box = serial,
            qty = qty,
        )
    }
    return null
}

/**
 * The product's name, as printed under "Description".
 *
 * Taken from the line or two beneath the word, stopping at whatever field comes
 * next. It is never used to decide anything -- it only fills in the prompt for
 * a product nobody has named yet, and the operator confirms or replaces it.
 */
private fun readDescription(words: List<TextWord>): String? {
    val anchor = anchors(words, ANCHOR_DESCRIPTION).firstOrNull() ?: return null
    val rows = lines(words)

    val collected = mutableListOf<String>()
    for (row in rows) {
        val top = row.minOf { it.top }
        // The line the word sits on (the name may follow it) and the two below.
        if (top < anchor.top - anchor.height / 2) continue
        if (top > anchor.top + anchor.height * 4) break

        for (word in row.sortedBy { it.left }) {
            if (word === anchor) continue
            val lower = word.clean.lowercase()
            if (lower in ANCHOR_DESCRIPTION) continue
            // The next field has started, so the description has finished.
            if (lower in DESCRIPTION_ENDS) {
                return collected.takeIf { it.isNotEmpty() }?.joinToString(" ")
            }
            if (word.clean.isNotEmpty()) collected += word.clean
        }
    }

    return collected
        .joinToString(" ")
        .trim()
        .take(48)
        .takeIf { it.length >= 3 }
}

/** Words grouped into the lines they were printed on. */
private fun lines(words: List<TextWord>): List<List<TextWord>> {
    if (words.isEmpty()) return emptyList()
    val height = words.map { it.height }.sorted()[words.size / 2]
    return words
        .sortedWith(compareBy({ it.centreY / (height.coerceAtLeast(1)) }, { it.left }))
        .groupBy { it.centreY / (height.coerceAtLeast(1)) }
        .values
        .toList()
}

private fun anchors(words: List<TextWord>, names: Set<String>): List<TextWord> =
    words.filter { it.clean.lowercase() in names }

/**
 * The value a field label points at.
 *
 * Every one of these labels puts the value to the right of its name or on the
 * line below it, so only those two directions count. Anything further away
 * than a few line heights belongs to a different field.
 */
private fun valueFor(
    words: List<TextWord>,
    anchor: TextWord,
    accept: (TextWord) -> Boolean,
): Pair<TextWord, Int>? {
    var best: TextWord? = null
    var bestDistance = Int.MAX_VALUE

    for (w in words) {
        if (w === anchor || !accept(w)) continue

        val sameLine = kotlin.math.abs(w.centreY - anchor.centreY) < anchor.height
        val below = (w.top - anchor.top) in 1..(anchor.height * 4)

        val distance = when {
            sameLine && w.left >= anchor.right -> w.left - anchor.right
            below && kotlin.math.abs(w.left - anchor.left) < anchor.width * 4 ->
                (w.top - anchor.top) + kotlin.math.abs(w.left - anchor.left) / 2
            else -> continue
        }
        if (distance < bestDistance) {
            best = w
            bestDistance = distance
        }
    }
    return best?.let { it to bestDistance }
}

private fun readProduct(words: List<TextWord>): String? {
    val looksLikeProduct = { w: TextWord ->
        PRODUCT_RE.matches(w.clean) || PRODUCT_NODASH_RE.matches(w.clean)
    }

    // "PID: 4098-9788", "Type: 4098-5220", "PID: 40989792".
    //
    // Under an anchor the label itself says which field this is, so a part
    // number printed WITHOUT its dash can be taken here.
    for (anchor in anchors(words, ANCHOR_PRODUCT_STRONG)) {
        valueFor(words, anchor, looksLikeProduct)?.let { return it.first.clean }
    }

    // Only now "PART NO". A carton carrying both prints them in the same shape,
    // so taking the part number while a PID is on the label would file stock
    // against a code Tally has never heard of -- and it would do it silently,
    // because the value looks perfectly valid.
    if (anchors(words, ANCHOR_PRODUCT_STRONG).isEmpty()) {
        for (anchor in anchors(words, ANCHOR_PRODUCT_WEAK)) {
            valueFor(words, anchor, looksLikeProduct)?.let { return it.first.clean }
        }
    }
    // Otherwise the only thing on the label shaped like a part number. KAC
    // prints one barcode and no field name at all.
    //
    // Deliberately STRICTER than the anchored branch: with nothing naming the
    // field, 8 bare digits is equally the shape of a date code, and taking one
    // for the other would invent a product that is not on the carton.
    return words.map { it.clean }.singleOrNull { PRODUCT_RE.matches(it) }
}

private fun readBox(words: List<TextWord>): String? {
    for (anchor in anchors(words, ANCHOR_BOX)) {
        valueFor(words, anchor) {
            val v = it.clean.uppercase()
            BOXID_RE.matches(v) || SERIAL16_RE.matches(v)
        }?.let { return it.first.clean.uppercase() }
    }
    return words.map { it.clean.uppercase() }
        .firstOrNull { BOXID_RE.matches(it) || SERIAL16_RE.matches(it) }
}

/**
 * The quantity, and only the quantity.
 *
 * Read from the word that names it, and refused if a different name is closer
 * to the same number. That second half is the point: a week number and a
 * quantity are identical as values, so the only defence is which word they sit
 * beside.
 */
private fun readQuantity(words: List<TextWord>): Int? {
    val decoys = anchors(words, ANCHOR_NOT_QTY)

    for (anchor in anchors(words, ANCHOR_QTY)) {
        val hit = valueFor(words, anchor) { NUMBER_RE.matches(it.clean) } ?: continue
        val (value, distance) = hit

        // Is this number actually somebody else's? If a "Week" or "Issue"
        // label sits closer to it than "Quantity" does, it is theirs.
        val closerDecoy = decoys.any { decoy ->
            val theirs = valueFor(words, decoy) { it === value }
            theirs != null && theirs.second < distance
        }
        if (closerDecoy) continue

        return value.clean.toIntOrNull()?.takeIf { it > 0 }
    }
    return null
}

/**
 * Agreement across frames, which is the confidence.
 *
 * A camera sees many frames of the same label, and measured against the real
 * cartons every value that was read identically six times out of six was
 * correct, while the ones that disagreed with themselves included a part
 * number with a digit wrong. So a value is offered only once enough frames
 * have said the same thing, and how many said it is shown to the operator.
 */
class LabelConsensus(private val required: Int = 3) {

    private val products = mutableMapOf<String, Int>()
    private val boxes = mutableMapOf<String, Int>()
    private val quantities = mutableMapOf<Int, Int>()
    var frames: Int = 0
        private set

    fun offer(reading: LabelReading) {
        frames++
        reading.product?.let { products[it] = (products[it] ?: 0) + 1 }
        reading.box?.let { boxes[it] = (boxes[it] ?: 0) + 1 }
        reading.qty?.let { quantities[it] = (quantities[it] ?: 0) + 1 }
    }

    fun reset() {
        products.clear(); boxes.clear(); quantities.clear(); frames = 0
    }

    /** A value and how many frames said it, once enough of them agree. */
    data class Agreed<T>(val value: T, val votes: Int)

    private fun <T> settled(counts: Map<T, Int>): Agreed<T>? =
        counts.maxByOrNull { it.value }
            ?.takeIf { it.value >= required }
            ?.let { Agreed(it.key, it.value) }

    val product: Agreed<String>? get() = settled(products)
    val box: Agreed<String>? get() = settled(boxes)
    val qty: Agreed<Int>? get() = settled(quantities)

    /** Nothing more is worth reading: every field has settled. */
    val complete: Boolean get() = product != null && box != null && qty != null
}
