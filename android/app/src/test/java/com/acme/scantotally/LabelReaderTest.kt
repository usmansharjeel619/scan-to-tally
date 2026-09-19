package com.acme.scantotally

import com.acme.scantotally.scan.LabelConsensus
import com.acme.scantotally.scan.LabelReading
import com.acme.scantotally.scan.TextWord
import com.acme.scantotally.scan.readLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label layouts, as they are actually printed on the cartons.
 *
 * Positions are taken from the photographs: what matters is which word sits
 * beside which number, because that association is the only thing the camera
 * can see that a barcode scanner cannot.
 */
class LabelReaderTest {

    /** A word at a position, sized roughly as printed text is. */
    private fun w(text: String, x: Int, y: Int, width: Int = text.length * 12) =
        TextWord(text, x, y, width, 20)

    /**
     * Tyco: "Week No. 17" sits directly beside "Quantity 20".
     *
     * This is the failure the camera exists to prevent. A scanner sees two
     * two-digit barcodes and cannot choose; the printed words can.
     */
    @Test
    fun `a week number is never read as the quantity`() {
        val label = listOf(
            w("Type:", 20, 20), w("4098-5266", 120, 20),
            w("Quantity:", 20, 80), w("20", 160, 80),
            w("Box:", 300, 80), w("HKT710", 380, 80),
            w("Week", 20, 140), w("No.:", 90, 140), w("17", 160, 140),
        )

        val read = readLabel(label)
        assertEquals("4098-5266", read.product)
        assertEquals("the number beside Quantity, not the one beside Week", 20, read.qty)
        assertEquals("HKT710", read.box)
    }

    /** The same trap with an issue number, on a differently laid out carton. */
    @Test
    fun `an issue number is never read as the quantity`() {
        val label = listOf(
            w("Type:", 20, 20), w("4098-5209", 120, 20),
            w("Date", 20, 60), w("code:", 80, 60), w("26154", 160, 60),
            w("Issue", 20, 100), w("No.", 90, 100), w("5", 150, 100),
            w("Quantity:", 20, 140), w("35", 160, 140),
            w("Box", 240, 140), w("ID:", 300, 140), w("HJO761", 360, 140),
        )

        val read = readLabel(label)
        assertEquals("4098-5209", read.product)
        assertEquals(35, read.qty)
        assertEquals("HJO761", read.box)
    }

    /** Simplex US: quantity sits BELOW its label, and there is no box number. */
    @Test
    fun `a value below its label is found`() {
        val label = listOf(
            w("PID:", 20, 20), w("4098-9788", 140, 20),
            w("PART", 20, 200), w("NO.", 80, 200), w("0677104", 180, 200),
            w("QTY", 600, 160), w("24", 700, 160),
        )

        val read = readLabel(label)
        assertEquals("4098-9788", read.product)
        assertEquals(24, read.qty)
        assertNull("nothing on this carton is a box number", read.box)
    }

    /** KAC: one part number, no field name against it at all. */
    @Test
    fun `an unnamed part number is still recognised`() {
        val label = listOf(
            w("KAC", 20, 10),
            w("This", 100, 60), w("carton", 150, 60), w("contains", 220, 60),
            w("4099-5208", 500, 60),
            w("Quantity", 100, 200), w("100", 300, 200),
        )

        val read = readLabel(label)
        assertEquals("4099-5208", read.product)
        assertEquals(100, read.qty)
    }

    /** A part no is not a part number, however much it looks like one. */
    @Test
    fun `the part no is not mistaken for the product`() {
        val label = listOf(
            w("PID:", 20, 20), w("4100-3206", 140, 20),
            w("PART", 20, 100), w("NO.", 80, 100), w("742-949", 180, 100),
            w("QTY:", 500, 100), w("1", 560, 100),
        )

        val read = readLabel(label)
        assertEquals("4100-3206", read.product)
        assertEquals(1, read.qty)
    }

    /** A 16-digit Simplex serial is a box number too. */
    @Test
    fun `a long serial is read as the box`() {
        val label = listOf(
            w("PID:", 20, 20), w("4100-3206", 140, 20),
            w("SERIAL#:", 20, 100), w("1120181448458237", 160, 100),
            w("QTY:", 500, 20), w("1", 560, 20),
        )
        assertEquals("1120181448458237", readLabel(label).box)
    }

    @Test
    fun `an empty frame says nothing rather than guessing`() {
        val read = readLabel(emptyList())
        assertNull(read.product)
        assertNull(read.box)
        assertNull(read.qty)
    }

    /**
     * Measured against the real cartons, OCR read "4098-9783" for "4098-9788"
     * -- one digit wrong, still shaped exactly like a valid part number. Every
     * value that six passes agreed on was correct; the ones that disagreed
     * with themselves were not. So agreement is the confidence.
     */
    @Test
    fun `a value is offered only when enough frames agree`() {
        val c = LabelConsensus(required = 3)

        c.offer(LabelReading(product = "4098-9788"))
        c.offer(LabelReading(product = "4098-9783"))  // the misread
        assertNull("two frames disagreeing settles nothing", c.product)

        c.offer(LabelReading(product = "4098-9788"))
        c.offer(LabelReading(product = "4098-9788"))
        assertEquals("4098-9788", c.product?.value)
        assertEquals("and says how sure it is", 3, c.product?.votes)
    }

    @Test
    fun `agreement is counted per field, not per frame`() {
        val c = LabelConsensus(required = 2)
        c.offer(LabelReading(product = "4098-5220", qty = 35))
        c.offer(LabelReading(product = "4098-5220"))
        c.offer(LabelReading(qty = 35, box = "HFE283"))

        assertEquals("4098-5220", c.product?.value)
        assertEquals(35, c.qty?.value)
        assertNull("one sighting of the box is not enough", c.box)
        assertEquals(3, c.frames)
    }

    @Test
    fun `a settled reading knows when it is done`() {
        val c = LabelConsensus(required = 2)
        repeat(2) { c.offer(LabelReading("4098-5220", "HFE283", 35)) }
        assertTrue(c.complete)
        assertNotNull(c.product)

        c.reset()
        assertEquals(0, c.frames)
        assertNull(c.product)
    }

    /**
     * Simplex: the box number exists NOWHERE else on the carton.
     *
     * It sits inside "4100-3206|1120181448458237|1", printed under SERIAL#,
     * between the part number and the quantity. There is no Box field to read,
     * so a reader that only looks for a standalone box number finds nothing --
     * which is exactly what happened on the real cartons.
     */
    @Test
    fun `the box number is taken out of the long serial line`() {
        val label = listOf(
            w("PID:", 20, 20), w("4100-3206", 140, 20),
            w("PART", 20, 100), w("NO.", 80, 100), w("742-949", 180, 100),
            w("QTY:1", 500, 100),
            w("SERIAL#:", 20, 180),
            w("4100-3206|1120181448458237|1", 20, 220, width = 340),
        )

        val read = readLabel(label)
        assertEquals("4100-3206", read.product)
        assertEquals("1120181448458237", read.box)
        assertEquals(1, read.qty)
    }

    /** OCR splits a long line into pieces; it is still one line. */
    @Test
    fun `a serial line broken across words is reassembled`() {
        val label = listOf(
            w("SERIAL#:", 20, 180),
            w("4100-3206", 20, 220), w("|", 130, 220, width = 8),
            w("1120181448458237", 145, 220, width = 190),
            w("|", 340, 220, width = 8), w("1", 355, 220),
        )

        val read = readLabel(label)
        assertEquals("1120181448458237", read.box)
        assertEquals(1, read.qty)
    }

    /**
     * A printed pipe comes back as I, l or a slash depending on the label, and
     * those are allowed for.
     *
     * "1" deliberately is not: a digit separator cannot be told apart from the
     * digits either side of it, and a serial read one character short would
     * name a box that does not exist and then pass every check after it.
     */
    @Test
    fun `a misread separator still yields the box number`() {
        for (sep in listOf("|", "I", "l", "/")) {
            val label = listOf(
                w("SERIAL#:", 20, 180),
                w("4098-9792${sep}1124241658336425${sep}18", 20, 220, width = 340),
            )
            val read = readLabel(label)
            assertEquals("separator $sep", "1124241658336425", read.box)
            assertEquals("separator $sep", 18, read.qty)
            assertEquals("separator $sep", "4098-9792", read.product)
        }
    }

    /** A digit separator is ambiguous, so nothing is claimed rather than guessed. */
    @Test
    fun `a digit separator is not guessed at`() {
        val label = listOf(
            w("SERIAL#:", 20, 180),
            w("4098-9792" + "1" + "1124241658336425" + "1" + "18", 20, 220, width = 340),
        )
        assertNull("better nothing than a serial one character out", readLabel(label).box)
    }

    /** Nonsense between the pipes is not a box number. */
    @Test
    fun `a line that only looks like a serial is not accepted`() {
        val label = listOf(
            w("Made in Czech Republic 17", 20, 20, width = 300),
            w("Quantity:", 20, 80), w("35", 160, 80),
        )
        val read = readLabel(label)
        assertNull(read.box)
        assertEquals(35, read.qty)
    }

    /**
     * A product in neither Tally nor the price list has to be named by hand,
     * and its name is printed on the carton the camera is already reading.
     */
    @Test
    fun `the printed description is read`() {
        val label = listOf(
            w("PID:", 20, 20), w("4098-9788", 140, 20),
            w("DESCRIPTION:", 20, 90),
            w("2", 20, 130), w("WIRE", 45, 130), w("BASE", 110, 130),
            w("W/REMOTE", 180, 130), w("LED", 290, 130),
            w("PART", 20, 200), w("NO.", 80, 200), w("0677104", 180, 200),
            w("QTY", 600, 160), w("24", 700, 160),
        )

        val read = readLabel(label)
        assertEquals("2 WIRE BASE W/REMOTE LED", read.description)
        assertEquals("4098-9788", read.product)
        assertEquals(24, read.qty)
    }

    /** It must stop where the description does, or it swallows the next field. */
    @Test
    fun `the description stops at the next field`() {
        val label = listOf(
            w("Description:", 20, 20),
            w("LP", 20, 60), w("SOUNDER", 50, 60), w("BASE", 160, 60),
            w("Date", 20, 110), w("code:", 80, 110), w("26154", 160, 110),
            w("Quantity:", 20, 160), w("35", 160, 160),
        )

        val read = readLabel(label)
        assertEquals("LP SOUNDER BASE", read.description)
        assertEquals("the quantity is still its own", 35, read.qty)
    }

    /** A carton with no description printed offers none. */
    @Test
    fun `no description is invented`() {
        val label = listOf(
            w("Type:", 20, 20), w("4098-5266", 120, 20),
            w("Quantity:", 20, 80), w("20", 160, 80),
        )
        assertNull(readLabel(label).description)
    }
}
