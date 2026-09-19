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
)

private val ANCHOR_PRODUCT = setOf("pid", "type", "part")
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

private val PRODUCT_RE = Regex("^[0-9]{4}-[0-9]{4}$")
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
    "([0-9]{4}-[0-9]{4})[|Il/\\\\]([0-9]{10,20})[|Il/\\\\]([0-9]{1,5})",
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
    combined(words)?.let { return it }

    return LabelReading(
        product = readProduct(words),
        box = readBox(words),
        qty = readQuantity(words),
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
            product = m.groupValues[1],
            box = serial,
            qty = qty,
        )
    }
    return null
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
    // Named first: "PID: 4098-9788", "Type: 4098-5220".
    for (anchor in anchors(words, ANCHOR_PRODUCT)) {
        valueFor(words, anchor) { PRODUCT_RE.matches(it.clean) }?.let { return it.first.clean }
    }
    // Otherwise the only thing on the label shaped like a part number. KAC
    // prints one barcode and no field name at all.
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
