package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The offload store is what keeps one oversized character-card JSON from making every
 * `chat_sessions` read fail with `Row too big to fit into CursorWindow`. These tests pin the three
 * properties that matter: small values are untouched, large values round-trip byte for byte, and a
 * missing backing file degrades to an explicit null instead of an exception.
 */
class SessionJsonOffloadTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("mca-session-json", "", File(System.getProperty("java.io.tmpdir")))
        root.delete()
        SessionJsonOffload.configure(root)
    }

    @After
    fun tearDown() {
        SessionJsonOffload.configure(null)
        root.deleteRecursively()
    }

    private fun largeJson(marker: String = "big"): String =
        "{\"name\":\"$marker\",\"lorebook\":\"" + marker.repeat(SessionJsonOffload.MAX_INLINE_JSON_CHARS) + "\"}"

    @Test
    fun `a value inside the inline ceiling is returned untouched`() {
        val small = "{\"name\":\"小预设\"}"
        assertSame(small, SessionJsonOffload.offload("session-1", small))
        assertFalse(SessionJsonOffload.isReference(small))
        assertEquals(small, SessionJsonOffload.resolve(small))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an oversized value becomes a reference and resolves back byte for byte`() {
        val json = largeJson()
        val stored = SessionJsonOffload.offload("session-1", json)
        assertTrue(SessionJsonOffload.isReference(stored))
        assertTrue("the stored reference must be small", stored!!.length < 512)
        assertEquals(json, SessionJsonOffload.resolve(stored))
        assertEquals(1, root.listFiles().orEmpty().size)
    }

    @Test
    fun `repeated offloads of the same content are idempotent`() {
        val json = largeJson("repeat")
        val first = SessionJsonOffload.offload("session-1", json)
        val second = SessionJsonOffload.offload("session-1", json)
        assertEquals(first, second)
        assertEquals(1, root.listFiles().orEmpty().size)

        // Feeding a reference back in must never nest another reference.
        val again = SessionJsonOffload.offload("session-1", first)
        assertEquals(first, again)
        assertEquals(json, SessionJsonOffload.resolve(again))
    }

    @Test
    fun `a missing backing file resolves to null instead of throwing`() {
        val stored = SessionJsonOffload.offload("session-1", largeJson("gone"))
        assertTrue(SessionJsonOffload.isReference(stored))
        root.listFiles().orEmpty().forEach { it.delete() }
        assertNull(SessionJsonOffload.resolve(stored))
    }

    @Test
    fun `different sessions keep different files`() {
        val first = SessionJsonOffload.offload("session-a", largeJson("a"))
        val second = SessionJsonOffload.offload("session-b", largeJson("b"))
        assertTrue(SessionJsonOffload.isReference(first))
        assertTrue(SessionJsonOffload.isReference(second))
        assertEquals(2, root.listFiles().orEmpty().size)
        assertTrue(first != second)
    }

    @Test
    fun `an unconfigured store never rewrites or loses a value`() {
        SessionJsonOffload.configure(null)
        val json = largeJson("unconfigured")
        assertEquals(json, SessionJsonOffload.offload("session-1", json))
        assertFalse(SessionJsonOffload.isReference(SessionJsonOffload.offload("session-1", json)))
        // A reference written by a configured run cannot be resolved without its store, which must
        // read as "missing", never as the reference JSON itself.
        assertNull(SessionJsonOffload.resolve("{\"\$mcaJsonRef\":\"session_json/x.json\"}"))
    }

    @Test
    fun `a reference with a traversal path is refused`() {
        assertNull(SessionJsonOffload.resolve("{\"\$mcaJsonRef\":\"session_json/../../secret.json\"}"))
    }
}
