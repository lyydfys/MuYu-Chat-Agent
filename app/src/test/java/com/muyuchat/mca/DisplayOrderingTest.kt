package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the display order of the conversation history, image library and file library, and
 * proves the P2 extraction changed nothing.
 *
 * These orders are visible product behaviour and none of the three had a test. The cases
 * below cover what a refactor would most plausibly break: pinning outranking recency, the
 * direction of each sort, stability for equal keys, and the receiver not being modified.
 */
class DisplayOrderingTest {

    @Test
    fun `the conversation history puts pinned first then most recently updated`() {
        val oldest = session("oldest", pinned = false, updatedAt = 100)
        val middle = session("middle", pinned = false, updatedAt = 200)
        val newest = session("newest", pinned = false, updatedAt = 300)
        val pinnedOld = session("pinned-old", pinned = true, updatedAt = 50)
        val pinnedNew = session("pinned-new", pinned = true, updatedAt = 150)

        val ordered = listOf(oldest, middle, newest, pinnedOld, pinnedNew).sortedForHistory()

        assertEquals(
            listOf("pinned-new", "pinned-old", "newest", "middle", "oldest"),
            ordered.map { it.id }
        )
    }

    @Test
    fun `pinning outranks recency in both directions`() {
        // A pinned conversation older than every unpinned one still sorts above them all.
        val pinnedAncient = session("pinned", pinned = true, updatedAt = 1)
        val unpinnedRecent = session("unpinned", pinned = false, updatedAt = Long.MAX_VALUE)

        assertEquals(
            listOf("pinned", "unpinned"),
            listOf(unpinnedRecent, pinnedAncient).sortedForHistory().map { it.id }
        )
    }

    @Test
    fun `the libraries put the newest entry first`() {
        val old = image("old", createdAt = 100)
        val recent = image("recent", createdAt = 300)
        val middle = image("middle", createdAt = 200)

        assertEquals(
            listOf("recent", "middle", "old"),
            listOf(old, recent, middle).sortedImagesForLibrary().map { it.id }
        )

        val fileOld = file("file-old", createdAt = 100)
        val fileRecent = file("file-recent", createdAt = 300)
        assertEquals(
            listOf("file-recent", "file-old"),
            listOf(fileOld, fileRecent).sortedFilesForLibrary().map { it.id }
        )
    }

    @Test
    fun `all three orders are stable for equal keys`() {
        // Equal keys must keep their input order, or the rendered list reshuffles between
        // recompositions. Stable sorting is what guarantees that.
        val first = image("first", createdAt = 100)
        val second = image("second", createdAt = 100)
        val third = image("third", createdAt = 100)
        assertEquals(
            listOf("first", "second", "third"),
            listOf(first, second, third).sortedImagesForLibrary().map { it.id }
        )

        val a = session("a", pinned = true, updatedAt = 100)
        val b = session("b", pinned = true, updatedAt = 100)
        val c = session("c", pinned = false, updatedAt = 100)
        val d = session("d", pinned = false, updatedAt = 100)
        assertEquals(
            listOf("a", "b", "c", "d"),
            listOf(a, b, c, d).sortedForHistory().map { it.id }
        )
    }

    @Test
    fun `sorting returns a new list and leaves the receiver untouched`() {
        val old = image("old", createdAt = 100)
        val recent = image("recent", createdAt = 300)
        val original = listOf(old, recent)

        val sorted = original.sortedImagesForLibrary()

        assertEquals("the receiver must keep its order", listOf("old", "recent"), original.map { it.id })
        assertEquals("the result must be ordered", listOf("recent", "old"), sorted.map { it.id })
        assertTrue("the result must not be the same instance", original !== sorted)
    }

    @Test
    fun `empty and single element lists are returned as they are`() {
        assertEquals(emptyList<ChatSessionRecord>(), emptyList<ChatSessionRecord>().sortedForHistory())
        assertEquals(emptyList<ImageAssetRecord>(), emptyList<ImageAssetRecord>().sortedImagesForLibrary())
        assertEquals(emptyList<FileAssetRecord>(), emptyList<FileAssetRecord>().sortedFilesForLibrary())

        val only = session("only", pinned = false, updatedAt = 5)
        assertEquals(listOf("only"), listOf(only).sortedForHistory().map { it.id })
    }

    @Test
    fun `the extraction matches the original orderings on every sample`() {
        val sessions = listOf(
            session("s1", pinned = false, updatedAt = 100),
            session("s2", pinned = true, updatedAt = 50),
            session("s3", pinned = true, updatedAt = 300),
            session("s4", pinned = false, updatedAt = 100),
            session("s5", pinned = false, updatedAt = 900)
        )
        assertEquals(
            originalSortedForHistory(sessions).map { it.id },
            sessions.sortedForHistory().map { it.id }
        )

        val images = listOf(
            image("i1", createdAt = 5),
            image("i2", createdAt = 5),
            image("i3", createdAt = 1),
            image("i4", createdAt = 99)
        )
        assertEquals(
            originalSortedImagesForLibrary(images).map { it.id },
            images.sortedImagesForLibrary().map { it.id }
        )

        val files = listOf(
            file("f1", createdAt = 7),
            file("f2", createdAt = 7),
            file("f3", createdAt = 70)
        )
        assertEquals(
            originalSortedFilesForLibrary(files).map { it.id },
            files.sortedFilesForLibrary().map { it.id }
        )
    }

    // ---- verbatim copies of the pre-extraction implementations -----------------------

    private fun originalSortedForHistory(value: List<ChatSessionRecord>): List<ChatSessionRecord> =
        value.sortedWith(
            compareByDescending<ChatSessionRecord> { it.pinned }
                .thenByDescending { it.updatedAt }
        )

    private fun originalSortedImagesForLibrary(value: List<ImageAssetRecord>): List<ImageAssetRecord> =
        value.sortedByDescending { it.createdAt }

    private fun originalSortedFilesForLibrary(value: List<FileAssetRecord>): List<FileAssetRecord> =
        value.sortedByDescending { it.createdAt }

    // ---- fixtures --------------------------------------------------------------------

    private fun session(id: String, pinned: Boolean, updatedAt: Long) = ChatSessionRecord(
        id = id,
        title = id,
        messages = listOf(ChatMessage(Role.USER, "hi")),
        pinned = pinned,
        updatedAt = updatedAt
    )

    private fun image(id: String, createdAt: Long) = ImageAssetRecord(
        id = id,
        name = id,
        uriString = "content://$id",
        source = "test",
        createdAt = createdAt
    )

    private fun file(id: String, createdAt: Long) = FileAssetRecord(
        id = id,
        name = id,
        text = id,
        createdAt = createdAt
    )
}
