package com.acme.scantotally

import com.acme.scantotally.scan.BoxDraft
import com.acme.scantotally.scan.classifyFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
