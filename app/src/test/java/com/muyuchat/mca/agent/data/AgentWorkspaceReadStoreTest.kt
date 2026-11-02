package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileKind
import com.muyuchat.core.agent.contract.AgentFileResult
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentWorkspaceReadStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var store: AgentWorkspaceReadStore
    private lateinit var taskRoot: File

    private val workspaceId = "workspace-1"
    private val taskId = "task-1"

    @Before
    fun setUp() {
        store = AgentWorkspaceReadStore(temporary.root)
        taskRoot = store.ensureTaskRoots(workspaceId, taskId)
    }

    @Test
    fun `read text returns content eof total bytes and sha256`() = runBlocking {
        val text = "héllo 世界\n"
        write("inputs/hello.txt", text)
        val result = store.readText(workspaceId, taskId, "inputs/hello.txt", 0L, 4096)
            .requireValue()
        assertEquals(text, result.content)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), result.totalBytes)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), result.nextOffsetBytes)
        assertTrue(result.eof)
        assertEquals(AgentCommandFingerprint.sha256Hex(text.toByteArray(Charsets.UTF_8)), result.sha256)
    }

    @Test
    fun `read text enforces utf8 character boundaries and limit too small`() = runBlocking {
        val bytes = "héllo".toByteArray(Charsets.UTF_8)
        writeBytes("inputs/accents.txt", bytes)
        // Byte 2 is the continuation byte of 'é' (0xC3 0xA9): not a character boundary.
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.readText(workspaceId, taskId, "inputs/accents.txt", 2L, 2).requireFailureCode()
        )
        assertEquals(
            "LIMIT_TOO_SMALL",
            store.readText(workspaceId, taskId, "inputs/accents.txt", 1L, 1).requireFailureCode()
        )
        val char = store.readText(workspaceId, taskId, "inputs/accents.txt", 1L, 2).requireValue()
        assertEquals("é", char.content)
        assertEquals(3L, char.nextOffsetBytes)
        assertFalse(char.eof)
        // Alignment: limit ends inside no character -> full requested prefix.
        val ascii = store.readText(workspaceId, taskId, "inputs/accents.txt", 3L, 2).requireValue()
        assertEquals("ll", ascii.content)
        assertEquals(5L, ascii.nextOffsetBytes)
        assertFalse(ascii.eof)
    }

    @Test
    fun `read text trims an incomplete trailing character instead of replacing it`() = runBlocking {
        writeBytes("inputs/tail.txt", "aé".toByteArray(Charsets.UTF_8))
        val first = store.readText(workspaceId, taskId, "inputs/tail.txt", 0L, 2).requireValue()
        assertEquals("a", first.content)
        assertEquals(1L, first.nextOffsetBytes)
        assertFalse(first.eof)
        val second = store.readText(
            workspaceId,
            taskId,
            "inputs/tail.txt",
            first.nextOffsetBytes,
            2
        ).requireValue()
        assertEquals("é", second.content)
        assertTrue(second.eof)
    }

    @Test
    fun `read text handles eof offsets and rejects out of range offsets`() = runBlocking {
        write("inputs/small.txt", "abc")
        val eof = store.readText(workspaceId, taskId, "inputs/small.txt", 3L, 10).requireValue()
        assertEquals("", eof.content)
        assertEquals(3L, eof.nextOffsetBytes)
        assertTrue(eof.eof)
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.readText(workspaceId, taskId, "inputs/small.txt", 4L, 10).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.readText(workspaceId, taskId, "inputs/small.txt", 0L, 0).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.readText(workspaceId, taskId, "inputs/small.txt", 0L, 16_385).requireFailureCode()
        )
    }

    @Test
    fun `read text rejects invalid utf8 and missing files`() = runBlocking {
        writeBytes("inputs/binary.bin", byteArrayOf(0x41, 0xFF.toByte(), 0x42))
        assertEquals(
            AgentDataErrorCodes.INPUT_INVALID,
            store.readText(workspaceId, taskId, "inputs/binary.bin", 0L, 16).requireFailureCode()
        )
        assertEquals(
            AgentDataErrorCodes.NOT_FOUND,
            store.readText(workspaceId, taskId, "inputs/missing.txt", 0L, 16).requireFailureCode()
        )
    }

    @Test
    fun `list is sorted non-recursive and bound to a directory version cursor`() = runBlocking {
        write("inputs/b.txt", "b")
        write("inputs/a.txt", "a")
        File(taskRoot, "inputs/sub").mkdirs()
        File(taskRoot, "inputs/sub/nested.txt").writeText("nested")
        val firstPage = store.list(workspaceId, taskId, "", 10, null).requireValue()
        assertEquals(
            listOf("inputs/a.txt", "inputs/b.txt", "inputs/sub"),
            firstPage.entries.map { it.logicalPath }
        )
        assertEquals(AgentFileKind.DIRECTORY, firstPage.entries.last().kind)
        assertNull(firstPage.nextCursor)

        val single = store.list(workspaceId, taskId, "inputs", 1, null).requireValue()
        assertEquals(listOf("inputs/a.txt"), single.entries.map { it.logicalPath })
        assertNotNull(single.nextCursor)
        val second = store.list(workspaceId, taskId, "inputs", 1, single.nextCursor).requireValue()
        assertEquals(listOf("inputs/b.txt"), second.entries.map { it.logicalPath })

        write("inputs/c.txt", "c")
        assertEquals(
            "CURSOR_STALE",
            store.list(workspaceId, taskId, "inputs", 1, single.nextCursor).requireFailureCode()
        )
    }

    @Test
    fun `list never exposes internal physical artifact versions`() = runBlocking {
        File(taskRoot, "artifacts/.versions").mkdirs()
        File(taskRoot, "artifacts/.versions/artifact_commit.blob").writeText("blob")
        write("artifacts/report.md", "# report")
        val result = store.list(workspaceId, taskId, "artifacts", 20, null).requireValue()
        assertEquals(listOf("artifacts/report.md"), result.entries.map { it.logicalPath })
    }

    @Test
    fun `search is literal case-sensitive ordered and truncation-aware`() = runBlocking {
        write("inputs/a.txt", "one\nTwo two\nthree two\n")
        write("inputs/b.txt", "two at start\n")
        val lower = store.searchText(workspaceId, taskId, "two", "inputs", 100).requireValue()
        assertEquals(
            listOf("inputs/a.txt:2", "inputs/a.txt:3", "inputs/b.txt:1"),
            lower.matches.map { "${it.logicalPath}:${it.lineNumber}" }
        )
        assertEquals(false, lower.truncated)
        val upper = store.searchText(workspaceId, taskId, "Two", "inputs", 100).requireValue()
        assertEquals(listOf("inputs/a.txt:2"), upper.matches.map { "${it.logicalPath}:${it.lineNumber}" })
        val capped = store.searchText(workspaceId, taskId, "two", "inputs", 2).requireValue()
        assertEquals(2, capped.matches.size)
        assertTrue(capped.truncated)
        assertTrue(capped.matches.all { it.snippet.length <= 512 })
    }

    @Test
    fun `search skips binary files during directory scans but fails for an explicit file`() =
        runBlocking {
            writeBytes("inputs/binary.bin", byteArrayOf(0x00, 0xFF.toByte(), 0x00))
            write("inputs/text.txt", "needle here\n")
            val scan = store.searchText(workspaceId, taskId, "needle", "inputs", 10).requireValue()
            assertEquals(listOf("inputs/text.txt"), scan.matches.map { it.logicalPath })
            assertTrue(scan.truncated)
            assertEquals(
                AgentDataErrorCodes.INPUT_INVALID,
                store.searchText(workspaceId, taskId, "needle", "inputs/binary.bin", 10)
                    .requireFailureCode()
            )
        }

    @Test
    fun `search honours the per-file scan cap`() = runBlocking {
        val oversized = File(taskRoot, "inputs/oversized.txt")
        FileOutputStream(oversized).use { output ->
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat((AgentDataLimits.SEARCH_PER_FILE_BYTES / chunk.size).toInt()) {
                output.write(chunk)
            }
            output.write("needle-at-the-end\n".toByteArray())
        }
        val result = store.searchText(workspaceId, taskId, "needle-at-the-end", "inputs", 10)
            .requireValue()
        assertTrue(result.matches.isEmpty())
        assertTrue(result.truncated)
        assertEquals(AgentDataLimits.SEARCH_PER_FILE_BYTES, result.scannedBytes)
    }

    @Test
    fun `task roots are isolated`() = runBlocking {
        val otherWorkspace = "workspace-2"
        val otherTask = "task-2"
        val otherRoot = store.ensureTaskRoots(otherWorkspace, otherTask)
        File(otherRoot, "inputs/other.txt").writeText("secret")
        assertEquals(
            AgentDataErrorCodes.NOT_FOUND,
            store.readText(workspaceId, taskId, "inputs/other.txt", 0L, 16).requireFailureCode()
        )
        val isolatedList = store.list(workspaceId, taskId, "inputs", 10, null).requireValue()
        assertTrue(isolatedList.entries.isEmpty())
    }

    private fun write(relativePath: String, text: String) {
        writeBytes(relativePath, text.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(relativePath: String, bytes: ByteArray) {
        val file = File(taskRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun <T> AgentFileResult<T>.requireValue(): T = when (this) {
        is AgentFileResult.Ok -> value
        is AgentFileResult.Failure -> error("unexpected failure $errorCode: $safeMessage")
    }

    private fun <T> AgentFileResult<T>.requireFailureCode(): String = when (this) {
        is AgentFileResult.Failure -> errorCode
        is AgentFileResult.Ok -> error("expected failure but got $value")
    }
}
