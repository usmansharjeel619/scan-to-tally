package com.acme.scantotally

import com.acme.scantotally.scan.FragmentKind
import com.acme.scantotally.scan.ScanSlot
import com.acme.scantotally.scan.classifyFragment
import com.acme.scantotally.scan.classifyFragmentFor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The third implementation of the fragment classifier, running the SAME
 * fixtures as the Go and TypeScript ones.
 *
 * Three copies of a rule are a liability unless something forces them to
 * agree; this is that something.
 */
class FragmentVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(): JsonObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("barcode-vectors.json")
            ?: error("barcode-vectors.json not on the test classpath")
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun str(o: JsonObject, k: String): String? = o[k]?.jsonPrimitive?.content

    @Test
    fun `shared fragment vectors`() {
        val fragments = load()["fragments"]!!.jsonArray
        assertTrue("no fragment vectors; the contract is not being read", fragments.isNotEmpty())

        for (element in fragments) {
            val v = element.jsonObject
            val name = str(v, "name").orEmpty()
            val raw = str(v, "raw").orEmpty()
            // A vector that names a slot is a TARGETED scan: the operator said
            // which field they were pointing at before scanning.
            val want = str(v, "want")
            val got = if (want != null) {
                classifyFragmentFor(raw, ScanSlot.valueOf(want))
            } else {
                classifyFragment(raw)
            }

            assertEquals("$name: kind", str(v, "kind"), got.kind.name)
            str(v, "value")?.let { assertEquals("$name: value", it, got.value) }
            if (got.kind != FragmentKind.NOT_MINE) {
                assertTrue("$name: a placed fragment must carry its value", got.value.isNotEmpty())
            }
            str(v, "hint")?.let { assertEquals("$name: hint", it, got.hint?.name) }
        }
    }

    /**
     * A part number and a box id can both mix letters and digits; only the
     * ordering separates them. Getting it wrong invents a batch that no carton
     * physically carries, and the mistake only surfaces at despatch.
     */
    @Test
    fun `a part number is never filed as a box id`() {
        for (partNo in listOf("0677197CN", "0677104", "0746234", "742-949")) {
            assertNotEquals(partNo, FragmentKind.BOX, classifyFragment(partNo).kind)
        }
    }

    /**
     * Week No 17 and Quantity 20 are the same shape. Accepting either would put
     * stock wrong with nothing on screen to show for it, which is exactly why
     * the quantity is typed.
     */
    @Test
    fun `no bare number is ever accepted`() {
        for (n in listOf("1", "5", "17", "20", "24", "35", "100", "2825", "23205", "26154")) {
            assertEquals(n, FragmentKind.NOT_MINE, classifyFragment(n).kind)
        }
    }

    @Test
    fun `every part number and box id seen on a real carton is recognised`() {
        for (pid in listOf("4098-9788", "4100-3206", "4098-5266", "4098-5220",
                           "4098-5209", "4099-5208", "4098-9019")) {
            assertEquals(pid, FragmentKind.PRODUCT, classifyFragment(pid).kind)
            assertEquals(pid, classifyFragment(pid).value)
        }
        for (box in listOf("HFE283", "HKT710", "HJO761", "1120181448458237")) {
            assertEquals(box, FragmentKind.BOX, classifyFragment(box).kind)
            assertEquals(box, classifyFragment(box).value)
        }
    }
}
