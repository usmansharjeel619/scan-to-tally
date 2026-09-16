package com.acme.scantotally.scan

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Barcode parsing, mirrored from connector/internal/barcode (Go) and
 * relay/src/barcode.ts (TypeScript).
 *
 * All three run the SAME fixture file, contracts/barcode-vectors.json. If they
 * ever disagree, a label means two different things in two places and stock
 * drifts without anyone noticing.
 *
 * The device parses locally so that scanning, duplicate detection and the
 * "wrong barcode" hint all work with no signal. The relay parses again on
 * arrival, because a scan line coming over HTTP is untrusted input and the
 * quantity on it becomes stock.
 */

enum class Outcome { ACCEPT, WRONG_BARCODE, REJECT, UNKNOWN }

/** Which of the label's other barcodes was scanned, so the UI can redirect. */
enum class Hint { PID, PART_NO, COO, QTY }

enum class Reason {
    QTY_NOT_A_POSITIVE_INTEGER,
    QTY_OUT_OF_RANGE,
    EMPTY_SERIAL,
    EMPTY_PID,
    SERIAL_LENGTH,
    FIELD_COUNT,
}

data class ParsedBox(
    /** Join key to a Tally stock item, resolved through the item master. */
    val pid: String,
    /** Becomes the Tally batch name verbatim -- no transform, ever. */
    val boxSerial: String,
    val qty: Int,
    val firmware: String? = null,
    /** Derived from the serial prefix when it is a real date. Advisory. */
    val mfgDate: LocalDate? = null,
) {
    /**
     * The duplicate key is (product, box), never the serial alone.
     *
     * Tally scopes a batch under a stock item, so this pair is its natural key
     * too: the same box number under a different product is a different box,
     * and the same part across different boxes is the normal case.
     */
    val key: String get() = "$pid$boxSerial"

    val mfgDateString: String? get() = mfgDate?.format(DateTimeFormatter.ISO_LOCAL_DATE)
}

data class ParseResult(
    val outcome: Outcome,
    /** The untouched payload. Stored on every scan line, forever. */
    val raw: String,
    val symbology: String = "",
    val parser: String? = null,
    val box: ParsedBox? = null,
    val hint: Hint? = null,
    val reason: Reason? = null,
    val confidence: Double = 0.0,
)

/** Sanity bounds against a misread, not business rules. */
const val MIN_SERIAL_LEN = 8
const val MAX_SERIAL_LEN = 32
const val MAX_QTY = 10_000

interface BarcodeParser {
    val name: String
    /** 0 = unrecognised; otherwise a confidence in (0,1]. Must never throw. */
    fun probe(symbology: String, raw: String): Double
    fun parse(symbology: String, raw: String): ParseResult
}

/**
 * The long SERIAL# code on a Simplex carton:
 *
 *     4098-9792|1124241658336425|18|
 *        PID          SERIAL      QTY  FIRMWARE (empty on passive devices)
 *
 * Validates STRUCTURE, not the PID's shape. "4098-9792" happens to look like
 * \d{4}-\d{4}, but other Simplex and JCI product lines do not all match that,
 * and an over-strict pattern rejects good boxes at the dock.
 */
object SimplexPipeParser : BarcodeParser {
    override val name = "simplex-pipe"

    override fun probe(symbology: String, raw: String): Double =
        if (raw.contains('|')) 0.9 else 0.0

    override fun parse(symbology: String, raw: String): ParseResult {
        fun reject(reason: Reason) = ParseResult(
            Outcome.REJECT, raw, symbology, name, reason = reason, confidence = 0.9,
        )

        val fields = raw.trim().split('|').map { it.trim() }

        // Three fields (no trailing delimiter) or four (with it, firmware
        // possibly empty). Anything else is a dialect we do not know, and
        // guessing at a quantity is the one mistake we must never make.
        if (fields.size < 3 || fields.size > 4) return reject(Reason.FIELD_COUNT)

        val pid = fields[0]
        val serial = fields[1]
        val qtyStr = fields[2]

        if (pid.isEmpty()) return reject(Reason.EMPTY_PID)
        if (serial.isEmpty()) return reject(Reason.EMPTY_SERIAL)
        if (serial.length < MIN_SERIAL_LEN || serial.length > MAX_SERIAL_LEN) {
            return reject(Reason.SERIAL_LENGTH)
        }

        // Require a plain positive integer: "1.5" and "-5" are not box counts.
        if (!qtyStr.all { it.isDigit() } || qtyStr.isEmpty()) {
            return reject(Reason.QTY_NOT_A_POSITIVE_INTEGER)
        }
        val qty = qtyStr.toIntOrNull() ?: return reject(Reason.QTY_NOT_A_POSITIVE_INTEGER)
        if (qty <= 0) return reject(Reason.QTY_NOT_A_POSITIVE_INTEGER)
        if (qty > MAX_QTY) return reject(Reason.QTY_OUT_OF_RANGE)

        val firmware = fields.getOrNull(3)?.takeIf { it.isNotEmpty() }

        return ParseResult(
            outcome = Outcome.ACCEPT, raw = raw, symbology = symbology, parser = name,
            confidence = 0.9,
            box = ParsedBox(pid, serial, qty, firmware, mfgDateFromSerial(serial)),
        )
    }
}

/**
 * Reads the serial's leading MMDDYY as a manufacturing date.
 *
 * On the reference label the serial 1124241658336425 begins 112424 = 24 Nov
 * 2024, which the same label independently prints as the Julian "24 329". Two
 * fields agreeing is why the structure is trustworthy -- but only as far as
 * returning null the moment it is not a real date. A wrong date is worse than
 * no date, so anything questionable yields nothing at all.
 */
fun mfgDateFromSerial(serial: String): LocalDate? {
    if (serial.length < 6) return null
    val head = serial.substring(0, 6)
    if (!head.all { it.isDigit() }) return null

    val mm = head.substring(0, 2).toInt()
    val dd = head.substring(2, 4).toInt()
    val yy = head.substring(4, 6).toInt()
    if (mm !in 1..12 || dd !in 1..31) return null

    // LocalDate.of throws rather than silently normalising 31 Feb, which is
    // exactly the behaviour wanted here.
    return runCatching { LocalDate.of(2000 + yy, mm, dd) }.getOrNull()
}

private val RE_PID = Regex("""^\d{4}-\d{4}$""")
private val RE_PART_NO = Regex("""^\d{6,8}[A-Z]{2}$""")
private val RE_COO = Regex("""^[A-Z]{2}$""")
private val RE_QTY = Regex("""^\d{1,4}$""")

private fun hintFor(s: String): Hint? = when {
    RE_PID.matches(s) -> Hint.PID
    RE_PART_NO.matches(s) -> Hint.PART_NO
    RE_COO.matches(s) -> Hint.COO
    RE_QTY.matches(s) -> Hint.QTY
    else -> null
}

/**
 * Recognises the PID, part-number, country-of-origin and quantity barcodes
 * printed alongside the long one.
 *
 * It never produces a box. Its whole job is to let the app say "that's the
 * product barcode -- scan the long serial barcode at the bottom" rather than
 * failing generically. There are five codes on that label and operators will
 * sometimes hit the wrong one.
 */
object SimplexShortParser : BarcodeParser {
    override val name = "simplex-short"

    override fun probe(symbology: String, raw: String): Double =
        if (hintFor(raw.trim()) == null) 0.0 else 0.5

    override fun parse(symbology: String, raw: String) = ParseResult(
        outcome = Outcome.WRONG_BARCODE, raw = raw, symbology = symbology, parser = name,
        hint = hintFor(raw.trim()), confidence = 0.5,
    )
}

/**
 * Picks the parser that recognises a payload most confidently.
 *
 * Adding a supplier means registering a parser; no call site changes.
 */
class BarcodeRegistry(
    private val parsers: MutableList<BarcodeParser> = mutableListOf(SimplexPipeParser, SimplexShortParser),
) {
    fun register(p: BarcodeParser) { parsers += p }

    fun parse(symbology: String, raw: String): ParseResult {
        // Scanners routinely append CR/LF; manual entry brings its own spaces.
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return ParseResult(Outcome.UNKNOWN, raw, symbology)

        var best: BarcodeParser? = null
        var bestScore = 0.0
        for (p in parsers) {
            val s = runCatching { p.probe(symbology, cleaned) }.getOrDefault(0.0)
            if (s > bestScore) { best = p; bestScore = s }
        }
        val chosen = best ?: return ParseResult(Outcome.UNKNOWN, raw, symbology)

        // raw is replaced with the untouched payload, always.
        return chosen.parse(symbology, cleaned).copy(raw = raw)
    }

    companion object { val default = BarcodeRegistry() }
}

/** Operator-facing text for a wrong or unreadable label. */
fun wrongBarcodeMessage(hint: Hint?): String = when (hint) {
    Hint.PID -> "That is the product barcode. Scan the long serial barcode at the bottom."
    Hint.PART_NO -> "That is the part-number barcode. Scan the long serial barcode at the bottom."
    Hint.QTY -> "That is the quantity barcode. Scan the long serial barcode at the bottom."
    Hint.COO -> "That is the country-of-origin barcode. Scan the long serial barcode at the bottom."
    null -> "Wrong barcode. Scan the long serial barcode at the bottom of the label."
}

fun rejectMessage(reason: Reason?): String = when (reason) {
    Reason.QTY_NOT_A_POSITIVE_INTEGER,
    Reason.QTY_OUT_OF_RANGE -> "The quantity on this label could not be read. Use manual entry."
    Reason.EMPTY_SERIAL,
    Reason.SERIAL_LENGTH -> "The box number on this label could not be read. Use manual entry."
    Reason.EMPTY_PID -> "The product code on this label could not be read. Use manual entry."
    Reason.FIELD_COUNT -> "This label is in an unfamiliar format. Use manual entry and report it."
    null -> "Label not recognised. Use manual entry if it is damaged."
}

/** Box serials are 16 digits; operators read and say the last few. */
fun tailOf(serial: String): String =
    if (serial.length > 7) "…" + serial.takeLast(7) else serial
