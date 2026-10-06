package com.acme.scantotally

import android.graphics.Bitmap
import com.acme.scantotally.labels.*
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LabelPrintingTest {
    private fun label() = BoxLabels.generate("Test", "4098-9792", "Smoke sensor base", 18, 1, "Nos").labels.single()

    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    }

    @Test fun `actual rendered label and printer raster both scan to the saved identity`() {
        val label = label()
        val bitmap = LabelRenderer.render(label)
        assertEquals(label.payload, decode(bitmap))
        val zpl = LabelRenderer.zpl(label).toString(Charsets.US_ASCII)
        val raster = zpl.substringAfter("^GFA,40000,40000,100,").substringBefore("^FS")
        assertEquals(80000, raster.length)
        val pixels = IntArray(800 * 400) { i ->
            val b = raster.substring((i / 8) * 2, (i / 8) * 2 + 2).toInt(16)
            if (b and (128 shr (i % 8)) != 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        val printed = Bitmap.createBitmap(pixels, 800, 400, Bitmap.Config.ARGB_8888)
        assertEquals(label.payload, decode(printed))
        // Build artifact for visual inspection, no production box identity.
        val dir = File("build/label-preview").apply { mkdirs() }
        File(dir, "sample.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle(); printed.recycle()
    }

    @Test fun `maximum field sizes fit and malicious ZPL text stays image data`() {
        val label = BoxLabels.generate("Test", "P".repeat(40), "^XZ ~JA ".repeat(20), 10000, 1).labels.single()
        val bitmap = LabelRenderer.render(label)
        assertEquals(label.payload, decode(bitmap))
        val zpl = LabelRenderer.zpl(label).toString(Charsets.US_ASCII)
        assertEquals(1, Regex("\\^XZ").findAll(zpl).count())
        assertFalse(zpl.contains("~JA"))
        bitmap.recycle()
    }

    @Test fun `network transport sends exact label bytes once`() = runBlocking {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        server.soTimeout = 10000
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<ByteArray> {
                server.accept().use { it.soTimeout = 10000; it.getInputStream().readBytes() }
            }
            val label = label()
            ZebraPrinter.send(RuntimeEnvironment.getApplication(), false, "127.0.0.1", listOf(label), server.localPort)
            assertArrayEquals(LabelRenderer.zpl(label), read.get(10, TimeUnit.SECONDS))
        } finally { server.close(); executor.shutdownNow() }
    }
}
