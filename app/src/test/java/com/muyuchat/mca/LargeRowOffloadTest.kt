package com.muyuchat.mca

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The offload store is what keeps one oversized row (a multi-megabyte character card, a runaway
 * model reply, a huge memory turn) from making every read of that table fail with
 * `Row too big to fit into CursorWindow`. These tests pin the three properties that matter: small
 * values are untouched, large values round-trip byte for byte, and a missing backing file degrades
 * to an explicit null instead of an exception.
 *
 * The last test is the maintenance guard: every column the repair may move must have a resolving
 * mapper, otherwise the UI would show raw reference JSON.
 */
class LargeRowOffloadTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("mca-row-text", "", File(System.getProperty("java.io.tmpdir")))
        root.delete()
        LargeRowOffload.configure(root)
    }

    @After
    fun tearDown() {
        LargeRowOffload.configure(null)
        root.deleteRecursively()
    }

    private fun large(marker: String = "big"): String =
        "{\"name\":\"$marker\",\"body\":\"" + marker.repeat(LargeRowOffload.MAX_INLINE_CHARS) + "\"}"

    @Test
    fun `a value inside the inline ceiling is returned untouched`() {
        val small = "{\"name\":\"小预设\"}"
        assertSame(small, LargeRowOffload.offload("row-1", small))
        assertFalse(LargeRowOffload.isReference(small))
        assertEquals(small, LargeRowOffload.resolve(small))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an oversized value becomes a reference and resolves back byte for byte`() {
        val value = large()
        val stored = LargeRowOffload.offload("chat_messages-content", value)
        assertTrue(LargeRowOffload.isReference(stored))
        assertTrue("the reference must stay small", stored!!.length < 512)
        assertEquals(value, LargeRowOffload.resolve(stored))
        assertEquals(1, root.listFiles().orEmpty().size)
    }

    @Test
    fun `repeated offloads of the same content are idempotent`() {
        val value = large("repeat")
        val first = LargeRowOffload.offload("memories-content", value)
        val second = LargeRowOffload.offload("memories-content", value)
        assertEquals(first, second)
        assertEquals(1, root.listFiles().orEmpty().size)

        // Feeding a reference back in must never nest another reference.
        val again = LargeRowOffload.offload("memories-content", first)
        assertEquals(first, again)
        assertEquals(value, LargeRowOffload.resolve(again))
    }

    @Test
    fun `a missing backing file resolves to null instead of throwing`() {
        val stored = LargeRowOffload.offload("assistants-systemPrompt", large("gone"))
        assertTrue(LargeRowOffload.isReference(stored))
        root.listFiles().orEmpty().forEach { it.delete() }
        assertNull(LargeRowOffload.resolve(stored))
        assertEquals("", LargeRowOffload.resolveOrEmpty(stored!!))
    }

    @Test
    fun `different keys keep different values`() {
        val first = LargeRowOffload.offload("a", large("a"))
        val second = LargeRowOffload.offload("b", large("b"))
        assertTrue(LargeRowOffload.isReference(first))
        assertTrue(LargeRowOffload.isReference(second))
        assertEquals(2, root.listFiles().orEmpty().size)
        assertTrue(LargeRowOffload.resolve(first) != LargeRowOffload.resolve(second))
    }

    @Test
    fun `an unconfigured store never rewrites or loses a value`() {
        LargeRowOffload.configure(null)
        val value = large("unconfigured")
        assertEquals(value, LargeRowOffload.offload("row", value))
        assertFalse(LargeRowOffload.isReference(LargeRowOffload.offload("row", value)))
        // A reference written by a configured run cannot be resolved without its store, which must
        // read as "missing", never as the reference JSON itself.
        assertNull(LargeRowOffload.resolve("{\"\$mcaTextRef\":\"row_text/x.json\"}"))
    }

    @Test
    fun `a reference with a traversal path is refused`() {
        assertNull(LargeRowOffload.resolve("{\"\$mcaTextRef\":\"row_text/../../secret.json\"}"))
    }

    @Test
    fun `every repairable column has a resolving mapper`() {
        val mapping = File(
            repositoryRoot(),
            "app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceMapping.kt"
        ).readText(Charsets.UTF_8)

        val unresolvable = LargeRowRepair.COLUMNS.filter { column ->
            !mapping.contains("LargeRowOffload.resolve(${column.column})") &&
                // The session documents are resolved in the DAO's record projection, not here.
                !column.column.startsWith("assistantSnapshot") &&
                column.column != "contextSummariesJson" &&
                column.column != "appearanceJson"
        }
        assertTrue(
            "these columns would surface raw reference JSON in the UI: " +
                unresolvable.joinToString { "${it.table}.${it.column}" },
            unresolvable.isEmpty()
        )
    }

    private fun repositoryRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root")
    }
}
