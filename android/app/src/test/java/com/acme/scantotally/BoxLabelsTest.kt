package com.acme.scantotally

import com.acme.scantotally.labels.*
import com.acme.scantotally.scan.BarcodeRegistry
import com.acme.scantotally.scan.Outcome
import com.google.zxing.qrcode.decoder.Decoder
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class BoxLabelsTest {
    private fun batch(part: String = "4098-9792", qty: Int = 18, count: Int = 3) =
        BoxLabels.generate("Test company", part, "Smoke sensor base", qty, count, "Nos")

    @Test fun `every QR decodes to a complete box with no invented manufacture date`() {
        for (b in listOf(batch(), batch("P".repeat(40), 10000, 100))) {
            for (label in b.labels) {
                val qr = BoxLabels.qr(label)
                val box = qr.enclosingRectangle
                // Decoder takes the module matrix without the quiet zone.
                val modules = com.google.zxing.common.BitMatrix(box[2], box[3])
                for (y in 0 until box[3]) for (x in 0 until box[2]) if (qr[x + box[0], y + box[1]]) modules.set(x, y)
                val decoded = Decoder().decode(modules).text
                assertEquals(label.payload, decoded)
                val result = BarcodeRegistry.default.parse("QR_CODE", decoded)
                assertEquals(Outcome.ACCEPT, result.outcome)
                assertEquals(label.partNumber, result.box!!.pid)
                assertEquals(label.boxNumber, result.box!!.boxSerial)
                assertEquals(label.quantity, result.box!!.qty)
                assertNull(result.box!!.mfgDate)
                assertTrue(248 / qr.width >= 4)
            }
        }
    }

    @Test fun `each generated box has its own identity across batches`() {
        val labels = (1..20).flatMap { batch(count = 100).labels }
        assertEquals(labels.size, labels.map { it.boxNumber }.toSet().size)
        assertTrue(labels.all { it.boxNumber.length == 28 && it.boxNumber.startsWith("ST") })
    }

    @Test fun `invalid quantities counts and field separators cannot create labels`() {
        for (pid in listOf("", "A|B", "A\nB", "A B", "A".repeat(41), "é")) {
            assertThrows(IllegalArgumentException::class.java) { batch(pid) }
        }
        for (qty in listOf(-1, 0, 10001)) assertThrows(IllegalArgumentException::class.java) { batch(qty = qty) }
        for (count in listOf(0, -1, 101)) assertThrows(IllegalArgumentException::class.java) { batch(count = count) }
        assertThrows(IllegalArgumentException::class.java) { BoxLabels.generate("", "A", "Item", 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { BoxLabels.generate("C", "A", "", 1, 1) }
    }

    @Test fun `ZPL packs rows with white padding and exactly one copy`() {
        val zpl = BoxLabels.graphicZpl(9, 2) { x, y -> (y == 0 && x in listOf(0, 7, 8)) || (y == 1 && x == 1) }
        assertTrue(zpl.contains("^GFA,4,4,2,81804000^FS"))
        assertTrue(zpl.endsWith("^PQ1^XZ\n"))
    }

    @Test fun `saved labels survive reopening status changes and remain company scoped`() {
        val dir = Files.createTempDirectory("box-labels").toFile()
        try {
            val original = batch()
            LabelStore(dir).save(original)
            assertEquals(original, LabelStore(dir).recent("Test company").single())
            val sent = original.copy(lastPrintState = "SENT", lastPrintDetail = "1 label")
            LabelStore(dir).save(sent)
            val reopened = LabelStore(dir).recent("Test company").single()
            assertEquals(original.labels, reopened.labels)
            assertEquals("SENT", reopened.lastPrintState)
            assertTrue(LabelStore(dir).recent("Other company").isEmpty())
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
