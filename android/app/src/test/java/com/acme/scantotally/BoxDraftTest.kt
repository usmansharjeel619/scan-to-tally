package com.acme.scantotally

import com.acme.scantotally.scan.BoxDraft
import com.acme.scantotally.scan.classifyFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assembly rules, walked through with the actual labels photographed at
 * the warehouse.
 */
class BoxDraftTest {

    /** Tyco 4098-5220: Type and Serial No are barcodes, quantity is typed. */
    @Test
    fun `tyco carton assembles from two scans and a typed quantity`() {
        var d = BoxDraft()
        assertTrue(d.isEmpty)

        d = d.withScan(classifyFragment("4098-5220"))
        assertEquals("4098-5220", d.pid)
        assertFalse("still needs a box and a quantity", d.isComplete)

        d = d.withScan(classifyFragment("HFE283"))
        assertEquals("HFE283", d.boxSerial)
        assertFalse("a quantity must never be assumed", d.isComplete)
        assertEquals(listOf(BoxDraft.Slot.QUANTITY), d.missing)

        d = d.withTypedQty(35)
        assertTrue(d.isComplete)
        assertEquals(35, d.qty)
    }

    /** KAC 4099-5208: one barcode, box number and quantity both typed. */
    @Test
    fun `kac carton assembles from one scan and two typed fields`() {
        var d = BoxDraft().withScan(classifyFragment("4099-5208"))
        assertEquals(listOf(BoxDraft.Slot.BOX, BoxDraft.Slot.QUANTITY), d.missing)

        d = d.withTypedBox("1").withTypedQty(100)
        assertTrue(d.isComplete)
        assertEquals("1", d.boxSerial)
        assertEquals(100, d.qty)
    }

    /** Scan order is the operator's business, not ours. */
    @Test
    fun `fields may arrive in any order`() {
        val d = BoxDraft()
            .withScan(classifyFragment("HKT710"))
            .withScan(classifyFragment("4098-5266"))
            .withTypedQty(20)
        assertTrue(d.isComplete)
        assertEquals("4098-5266", d.pid)
        assertEquals("HKT710", d.boxSerial)
    }

    /**
     * The failure this whole design exists to prevent: Week No 17 sits beside
     * Quantity 20 on the Tyco label, and taking one for the other puts stock
     * wrong with nothing on screen.
     */
    @Test
    fun `a stray barcode never fills a slot`() {
        val base = BoxDraft()
            .withScan(classifyFragment("4098-5266"))
            .withScan(classifyFragment("HKT710"))

        for (stray in listOf("17", "20", "5", "26154", "0677104", "742-949")) {
            val after = base.withScan(classifyFragment(stray))
            assertEquals("$stray must change nothing", base, after)
            assertFalse("$stray must not complete the box", after.isComplete)
        }
    }

    /** A quantity of zero is not an answer, and must not complete a box. */
    @Test
    fun `zero and negative quantities leave the box incomplete`() {
        val d = BoxDraft()
            .withScan(classifyFragment("4098-5266"))
            .withScan(classifyFragment("HKT710"))
        assertFalse(d.withTypedQty(0).isComplete)
        assertFalse(d.withTypedQty(-3).isComplete)
        assertTrue(d.withTypedQty(1).isComplete)
    }

    /** Every payload is kept, so a disputed box can be traced months later. */
    @Test
    fun `the audit trail keeps every payload that went into the box`() {
        val d = BoxDraft()
            .withScan(classifyFragment("4098-5220"))
            .withScan(classifyFragment("HFE283"))
        assertEquals("4098-5220 + HFE283", d.rawTrail)
    }

    /**
     * A box number must never survive into the next carton.
     *
     * A KAC carton was received carrying "HFE283" -- the box number printed on
     * a Tyco carton read just before it. Stock recorded against a box that
     * never held it, and nothing on screen to show for it.
     */
    @Test
    fun `a different product clears the previous carton`() {
        val tyco = BoxDraft()
            .withScan(classifyFragment("4098-5220"))
            .withScan(classifyFragment("HFE283"))
            .withTypedQty(35)
        assertTrue(tyco.isComplete)

        val kac = tyco.withScan(classifyFragment("4099-5208"))
        assertEquals("4099-5208", kac.pid)
        assertEquals("the Tyco box must not come with it", "", kac.boxSerial)
        assertNull("nor its quantity", kac.qty)
        assertFalse(kac.isComplete)
    }

    /** Typing the next product clears it just the same. */
    @Test
    fun `typing a different product clears the previous carton`() {
        val first = BoxDraft()
            .withScan(classifyFragment("4098-5220"))
            .withScan(classifyFragment("HFE283"))
        val second = first.withTypedProduct("4099-5208")

        assertEquals("4099-5208", second.pid)
        assertEquals("", second.boxSerial)
    }

    /** Re-reading the SAME product mid-carton keeps what is already there. */
    @Test
    fun `the same product again keeps the rest of the carton`() {
        val d = BoxDraft()
            .withScan(classifyFragment("4098-5220"))
            .withScan(classifyFragment("HFE283"))
            .withScan(classifyFragment("4098-5220"))

        assertEquals("HFE283", d.boxSerial)
        assertEquals("4098-5220", d.pid)
    }

    /**
     * The ordinary case: a pallet of the same product, box after box.
     *
     * Clearing the draft when the product CHANGES must not disturb this --
     * several boxes of one product, each with its own number and its own
     * count, is how a pallet arrives.
     */
    @Test
    fun `several boxes of one product each keep their own number and count`() {
        val received = mutableListOf<Triple<String, String, Int>>()

        // Box one.
        var d = BoxDraft()
            .withScan(classifyFragment("4098-9788"))
            .withScan(classifyFragment("HKT710"))
            .withTypedQty(24)
        assertTrue(d.isComplete)
        received += Triple(d.pid, d.boxSerial, d.qty!!)

        // Committing clears the draft, exactly as the screen does.
        d = BoxDraft()

        // Box two: same product, different number, different count.
        d = d.withScan(classifyFragment("4098-9788"))
            .withScan(classifyFragment("HKT711"))
            .withTypedQty(18)
        assertTrue(d.isComplete)
        received += Triple(d.pid, d.boxSerial, d.qty!!)

        assertEquals(
            listOf(
                Triple("4098-9788", "HKT710", 24),
                Triple("4098-9788", "HKT711", 18),
            ),
            received,
        )
    }

    /** The box may be read before the product; an empty draft carries nothing. */
    @Test
    fun `a box read before its product is kept`() {
        val d = BoxDraft()
            .withScan(classifyFragment("HKT710"))
            .withScan(classifyFragment("4098-9788"))
            .withTypedQty(24)

        assertEquals("HKT710", d.boxSerial)
        assertEquals("4098-9788", d.pid)
        assertTrue(d.isComplete)
    }

    /** Correcting the box number mid-carton replaces it, and nothing else. */
    @Test
    fun `a second box number for the same product replaces the first`() {
        val d = BoxDraft()
            .withScan(classifyFragment("4098-9788"))
            .withScan(classifyFragment("HKT710"))
            .withTypedQty(24)
            .withScan(classifyFragment("HKT711"))

        assertEquals("HKT711", d.boxSerial)
        assertEquals("the quantity is untouched", 24, d.qty)
        assertEquals("4098-9788", d.pid)
    }
}
