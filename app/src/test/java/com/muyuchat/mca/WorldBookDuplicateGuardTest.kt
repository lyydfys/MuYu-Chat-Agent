package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the content-based duplicate detection behind batch world-book import.
 *
 * The user-visible promise is "重复文件跳过，并告诉我跳过了几个", so these cases pin what counts as
 * the same book (content, never the file name), what must not (different entries, different flags),
 * and how the result line reads.
 */
class WorldBookDuplicateGuardTest {

    @Test
    fun `the same content fingerprints the same regardless of ids name or scope`() {
        val first = book(name = "世界书.json", scope = WorldBookScope.GLOBAL)
        val second = book(name = "世界书 (1).json", scope = WorldBookScope.ASSISTANT)

        assertEquals(
            WorldBookDuplicateGuard.fingerprint(first),
            WorldBookDuplicateGuard.fingerprint(second)
        )
    }

    @Test
    fun `the fingerprint ignores entry ids but not entry content`() {
        val base = book(name = "a")
        val reIdentified = base.copy(
            entries = base.entries.mapIndexed { index, entry -> entry.copy(id = "generated-$index") }
        )
        assertEquals(WorldBookDuplicateGuard.fingerprint(base), WorldBookDuplicateGuard.fingerprint(reIdentified))

        val changed = base.copy(entries = base.entries.map { it.copy(content = it.content + "（改）") })
        assertNotEquals(WorldBookDuplicateGuard.fingerprint(base), WorldBookDuplicateGuard.fingerprint(changed))
    }

    @Test
    fun `trigger flags and keys are part of the identity`() {
        val base = book(name = "a")
        val constant = base.copy(entries = base.entries.map { it.copy(constant = true) })
        val extraKey = base.copy(entries = base.entries.map { it.copy(keys = it.keys + "雨夜") })

        assertNotEquals(WorldBookDuplicateGuard.fingerprint(base), WorldBookDuplicateGuard.fingerprint(constant))
        assertNotEquals(WorldBookDuplicateGuard.fingerprint(base), WorldBookDuplicateGuard.fingerprint(extraKey))
    }

    @Test
    fun `line endings do not change the fingerprint`() {
        val lf = book(name = "a", content = "第一行\n第二行")
        val crlf = book(name = "a", content = "第一行\r\n第二行")

        assertEquals(WorldBookDuplicateGuard.fingerprint(lf), WorldBookDuplicateGuard.fingerprint(crlf))
    }

    @Test
    fun `the library fingerprint set answers did i import this before`() {
        val existing = book(name = "已存在")
        val incomingSame = existing.copy(name = "另一个文件名")
        // A different book must differ in its entries, not just in its name: the name comes from
        // the file name when the export has none, so it is deliberately not part of the identity.
        val otherBook = book(name = "新书", content = "完全不同的正文：雪原上的旅人缓缓推近。")

        val known = WorldBookDuplicateGuard.fingerprints(listOf(existing))

        assertTrue(
            "same entries under another file name must be recognised as already imported",
            WorldBookDuplicateGuard.fingerprint(incomingSame) in known
        )
        assertFalse(
            "different entries are a different book",
            WorldBookDuplicateGuard.fingerprint(otherBook) in known
        )
    }

    @Test
    fun `a content addressed book id is stable and derived from the fingerprint`() {
        val fingerprint = WorldBookDuplicateGuard.fingerprint(book(name = "a"))
        val otherFingerprint = WorldBookDuplicateGuard.fingerprint(
            book(name = "b", content = "完全不同的正文：雪原上的旅人缓缓推近。")
        )

        val first = WorldBookDuplicateGuard.bookIdFor(fingerprint)
        val second = WorldBookDuplicateGuard.bookIdFor(fingerprint)

        assertEquals("the same content always maps to the same id", first, second)
        assertTrue(first.startsWith("wb-"))
        // "wb-" + the first 24 hex characters of the fingerprint.
        assertEquals("wb-" + fingerprint.take(24), first)
        assertEquals("wb-".length + 24, first.length)
        assertNotEquals(
            "different content must not collide",
            first,
            WorldBookDuplicateGuard.bookIdFor(otherFingerprint)
        )
    }

    @Test
    fun `the summary states every count and keeps the first three failures`() {
        val summary = WorldBookBatchImportSummary(
            imported = 2,
            skippedDuplicates = 3,
            failures = listOf("甲（无法读取文件）", "乙（格式不正确）", "丙（导入失败）", "丁（超过 8 MiB）")
        )

        val message = summary.statusMessage()

        assertTrue(message, message.startsWith("已导入 2 本世界书"))
        assertTrue(message, message.contains("跳过 3 个重复"))
        assertTrue(message, message.contains("4 本失败："))
        assertTrue(message, message.contains("甲（无法读取文件）"))
        assertTrue(message, message.contains("丙（导入失败）"))
        assertFalse("only the first three reasons are listed", message.contains("丁（超过 8 MiB）"))
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun `a clean batch does not mention duplicates or failures`() {
        val message = WorldBookBatchImportSummary(imported = 5, skippedDuplicates = 0, failures = emptyList())
            .statusMessage()

        assertEquals("已导入 5 本世界书", message)
    }

    @Test
    fun `an all duplicate batch says so instead of claiming an import`() {
        val message = WorldBookBatchImportSummary(imported = 0, skippedDuplicates = 4, failures = emptyList())
            .statusMessage()

        assertTrue(message, message.contains("已导入 0 本世界书"))
        assertTrue(message, message.contains("跳过 4 个重复"))
    }

    @Test
    fun `a single successful file stays quiet so its own import line is the feedback`() {
        assertTrue(WorldBookBatchImportSummary(1, 0, emptyList()).isQuietSingleImport(batchSize = 1))
        assertFalse(
            "a duplicate single file must still be reported",
            WorldBookBatchImportSummary(0, 1, emptyList()).isQuietSingleImport(batchSize = 1)
        )
        assertFalse(
            "a failure must still be reported",
            WorldBookBatchImportSummary(0, 0, listOf("甲")).isQuietSingleImport(batchSize = 1)
        )
        assertFalse(
            "a real batch always reports counts",
            WorldBookBatchImportSummary(3, 0, emptyList()).isQuietSingleImport(batchSize = 3)
        )
    }

    private fun book(
        name: String,
        scope: WorldBookScope = WorldBookScope.GLOBAL,
        content: String = "雨夜的旧城街道，霓虹招牌在积水里晃动。"
    ): WorldBookRecord = WorldBookRecord(
        id = "book-$name",
        name = name,
        scope = scope,
        assistantId = if (scope == WorldBookScope.ASSISTANT) "assistant-1" else null,
        entries = listOf(
            WorldBookEntry(content = content, keys = listOf("雨夜", "霓虹"), constant = false),
            WorldBookEntry(content = "海面泛着金色反光", keys = listOf("海岸"), constant = true)
        )
    )
}
