package com.acme.scantotally

import com.acme.scantotally.data.Repository.CompanyCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the phone should shout about the company, and when it must stay quiet.
 *
 * Both failures are bad in different ways. A check that misses the mismatch
 * lets a pallet be counted into another company's books, which nobody finds
 * until the stock does not add up. A check that fires when nothing is wrong is
 * worse in the long run: operators learn to tap past it, and then it is not a
 * safeguard at all, just noise that happens to be red.
 */
class CompanyCheckTest {

    @Test
    fun `different companies are a mismatch`() {
        assertTrue(CompanyCheck("Northwind Trading", "Northwind").mismatch)
    }

    @Test
    fun `the same company is not`() {
        assertFalse(CompanyCheck("Northwind Trading", "Northwind Trading").mismatch)
    }

    /**
     * Tally is not consistent about how it spells a name back, and an operator
     * setting up a second handset types it by hand. Case is not a mismatch --
     * stopping a warehouse over a capital letter is exactly the false alarm
     * that teaches people to ignore the real one.
     */
    @Test
    fun `case does not make a mismatch`() {
        assertFalse(CompanyCheck("NORTHWIND TRADING", "Northwind Trading").mismatch)
    }

    /**
     * A phone that has never synced has nothing pinned yet. It adopts the first
     * company it sees, so there is nothing to disagree with.
     */
    @Test
    fun `an unpinned phone is not a mismatch`() {
        assertFalse(CompanyCheck("", "Northwind Trading").mismatch)
    }

    /**
     * No answer from the relay is not evidence of a wrong company.
     *
     * This is the case that decides whether the warning is trustworthy: out of
     * signal is the normal state at a loading dock, and a phone that shows
     * "wrong company" every time the Wi-Fi drops has cried wolf by lunchtime.
     */
    @Test
    fun `no answer from the relay is not a mismatch`() {
        assertFalse(CompanyCheck("Northwind Trading", "").mismatch)
    }
}
