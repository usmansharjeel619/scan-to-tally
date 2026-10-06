package com.acme.scantotally.labels

import com.acme.scantotally.scan.BarcodeRegistry
import com.acme.scantotally.scan.MAX_QTY
import com.acme.scantotally.scan.Outcome
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.util.UUID

@Serializable
data class BoxLabel(
    val partNumber: String,
    val description: String,
    val boxNumber: String,
    val quantity: Int,
    val unit: String = "",
) {
    val payload: String get() = "$partNumber|$boxNumber|$quantity|"
}

@Serializable
data class LabelBatch(
    val id: String,
    val company: String,
    val createdAt: Long,
    val labels: List<BoxLabel>,
    // SENDING left by a killed process means uncertain, never auto-retry.
    val lastPrintState: String = "NOT_SENT",
    val lastPrintAt: Long = 0,
    val lastPrintDetail: String = "",
)

object BoxLabels {
    const val MAX_LABELS = 100

    fun generate(company: String, part: String, description: String, quantity: Int,
                 count: Int, unit: String = ""): LabelBatch {
        require(company.isNotBlank()) { "Sync with your Tally company before creating labels." }
        val pid = part.trim()
        require(pid.length in 1..40 && pid.all { it.code in 33..126 && it != '|' }) {
            "Part number must be 1–40 characters, with no spaces or | characters."
        }
        require(description.isNotBlank() && description.trim().length <= 160) {
            "Enter a description of up to 160 characters."
        }
        require(description.none { it.isISOControl() }) { "Description must be on one line." }
        require(quantity in 1..MAX_QTY) { "Quantity per box must be between 1 and $MAX_QTY." }
        require(count in 1..MAX_LABELS) { "Generate between 1 and $MAX_LABELS labels at a time." }
        val labels = List(count) {
            // Full UUID entropy, compact enough for all three existing parsers.
            // ST prevents the parser interpreting this as a manufacturing date.
            val serial = "ST" + BigInteger(UUID.randomUUID().toString().replace("-", ""), 16)
                .toString(32).uppercase().padStart(26, '0')
            BoxLabel(pid, description.trim(), serial, quantity, unit).also { label ->
                check(BarcodeRegistry.default.parse("QR_CODE", label.payload).outcome == Outcome.ACCEPT)
            }
        }
        check(labels.map { it.boxNumber }.distinct().size == count)
        return LabelBatch(UUID.randomUUID().toString(), company, System.currentTimeMillis(), labels)
    }

    /** Includes a four-module quiet zone; printer and preview use this same image. */
    fun qr(label: BoxLabel): BitMatrix = QRCodeWriter().encode(
        label.payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )

    /** Monochrome pixels to ZPL graphic. Text never becomes a printer command. */
    fun graphicZpl(width: Int, height: Int, black: (Int, Int) -> Boolean): String {
        require(width > 0 && height > 0)
        val rowBytes = (width + 7) / 8
        val total = rowBytes * height
        val hex = "0123456789ABCDEF"
        return buildString(total * 2 + 120) {
            append("^XA^PW$width^LL$height^LH0,0^LS0^LT0^PON^PMN^FO0,0^GFA,$total,$total,$rowBytes,")
            for (y in 0 until height) for (b in 0 until rowBytes) {
                var value = 0
                for (bit in 0..7) {
                    val x = b * 8 + bit
                    if (x < width && black(x, y)) value = value or (128 shr bit)
                }
                append(hex[value shr 4]); append(hex[value and 15])
            }
            append("^FS^PQ1^XZ\n")
        }
    }
}
