package com.acme.scantotally.labels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** 100 x 50 mm at 203 dpi (8 dots/mm). Printed pixels exactly match the preview. */
object LabelRenderer {
    const val WIDTH = 800
    const val HEIGHT = 400

    fun render(label: BoxLabel): Bitmap {
        val image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Color.WHITE)
        fun text(value: String, x: Int, y: Int, width: Int, height: Int, size: Float, bold: Boolean = false) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = size
            }
            var layout: StaticLayout
            do {
                layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                if (layout.height <= height) break
                paint.textSize -= 1f
            } while (paint.textSize >= 14f)
            require(layout.height <= height) { "Description is too long to fit this label. Shorten it." }
            canvas.save(); canvas.translate(x.toFloat(), y.toFloat()); layout.draw(canvas); canvas.restore()
        }
        text(label.partNumber, 24, 16, 752, 74, 36f, true)
        val qr = BoxLabels.qr(label)
        val scale = 248 / qr.width
        require(scale >= 4) { "Part number is too long for this label's barcode." }
        val ink = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        for (y in 0 until qr.height) for (x in 0 until qr.width) if (qr[x, y]) {
            canvas.drawRect((24 + x * scale).toFloat(), (98 + y * scale).toFloat(),
                (24 + (x + 1) * scale).toFloat(), (98 + (y + 1) * scale).toFloat(), ink)
        }
        text(label.description, 292, 102, 484, 150, 27f)
        text("Qty: ${label.quantity} ${label.unit}".trim(), 292, 268, 484, 60, 32f, true)
        text("Box: ${label.boxNumber}", 24, 355, 752, 30, 24f)
        return image
    }

    fun zpl(label: BoxLabel): ByteArray {
        val bitmap = render(label)
        try {
            val pixels = IntArray(WIDTH * HEIGHT)
            bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            return BoxLabels.graphicZpl(WIDTH, HEIGHT) { x, y ->
                Color.red(pixels[y * WIDTH + x]) < 128
            }.toByteArray(Charsets.US_ASCII)
        } finally { bitmap.recycle() }
    }
}
