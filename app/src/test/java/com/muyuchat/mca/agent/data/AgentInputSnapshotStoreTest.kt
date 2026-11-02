package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileResult
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentInputSnapshotStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val workspaceId = "workspace-1"
    private val taskId = "task-1"
    private lateinit var snapshots: AgentInputSnapshotStore
    private lateinit var taskRoot: File

    @Before
    fun setUp() {
        val payloads = AgentPayloadStore(temporary.root)
        snapshots = AgentInputSnapshotStore(
            agentRoot = temporary.root,
            payloadStore = payloads,
            clock = AgentTestFixtures.MutableClock(),
            idGenerator = AgentTestFixtures.SequentialIds()
        )
        taskRoot = AgentWorkspacePaths.taskRoot(temporary.root, workspaceId, taskId, create = true)
    }

    @Test
    fun `utf8 input is copied with provenance and a manifest payload`() = runBlocking {
        val bytes = "brief 世界\n".toByteArray(Charsets.UTF_8)
        val imported = snapshots.importInput(
            workspaceId,
            taskId,
            request("content://docs/brief.txt", "brief.txt", bytes)
        ).requireValue()
        assertEquals("inputs/brief.txt", imported.logicalPath)
        assertEquals("UTF-8", imported.sourceEncoding)
        assertEquals(false, imported.convertedToUtf8)
        assertEquals(false, imported.bomRemoved)
        assertEquals(AgentCommandFingerprint.sha256Hex(bytes), imported.sourceSha256)
        assertEquals(AgentCommandFingerprint.sha256Hex(bytes), imported.storedSha256)
        assertEquals("brief 世界\n", File(taskRoot, imported.logicalPath).readText())
        val manifest = AgentPayloadRef.parseOrNull(imported.manifestRef)
        assertTrue(manifest != null)
        assertTrue(AgentPayloadStore(temporary.root).exists(workspaceId, taskId, imported.manifestRef))
        assertEquals(1, snapshots.importedInputs(workspaceId, taskId).size)
        val persisted = snapshots.importedInputs(workspaceId, taskId).single()
        assertEquals(imported.inputId, persisted.inputId)
        assertEquals(imported.sourceUriSha256, persisted.sourceUriSha256)
    }

    @Test
    fun `duplicate display names get a unique logical path`() = runBlocking {
        val first = snapshots.importInput(
            workspaceId, taskId, request("content://a", "brief.txt", "one".toByteArray())
        ).requireValue()
        val second = snapshots.importInput(
            workspaceId, taskId, request("content://b", "brief.txt", "two".toByteArray())
        ).requireValue()
        assertEquals("inputs/brief.txt", first.logicalPath)
        assertEquals("inputs/brief (2).txt", second.logicalPath)
        assertEquals("one", File(taskRoot, first.logicalPath).readText())
        assertEquals("two", File(taskRoot, second.logicalPath).readText())
    }

    @Test
    fun `utf16 bom input is converted to utf8 and records the conversion`() = runBlocking {
        val payload = "hello".toByteArray(Charsets.UTF_16LE)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload
        val imported = snapshots.importInput(
            workspaceId, taskId, request("content://utf16", "utf16.txt", bytes)
        ).requireValue()
        assertEquals("UTF-16LE", imported.sourceEncoding)
        assertTrue(imported.convertedToUtf8)
        assertTrue(imported.bomRemoved)
        assertEquals("hello", File(taskRoot, imported.logicalPath).readText(Charsets.UTF_8))
        assertEquals("hello".toByteArray(Charsets.UTF_8).size.toLong(), imported.storedSizeBytes)
    }

    @Test
    fun `explicit charset is honoured and recorded`() = runBlocking {
        val bytes = "café".toByteArray(Charsets.ISO_8859_1)
        val imported = snapshots.importInput(
            workspaceId,
            taskId,
            request("content://latin1", "latin1.txt", bytes, sourceEncoding = "ISO-8859-1")
        ).requireValue()
        assertEquals("ISO-8859-1", imported.sourceEncoding)
        assertTrue(imported.convertedToUtf8)
        assertEquals("café", File(taskRoot, imported.logicalPath).readText())
    }

    @Test
    fun `unknown encoding and binary inputs fail explicitly without writing a snapshot`() = runBlocking {
        val invalidUtf8 = snapshots.importInput(
            workspaceId,
            taskId,
            request("content://bad", "bad.txt", byteArrayOf(0xC3.toByte(), 0x28))
        ).requireFailure()
        assertEquals("TEXT_ENCODING_UNSUPPORTED", invalidUtf8.errorCode)
        val binary = snapshots.importInput(
            workspaceId,
            taskId,
            request("content://bin", "bin.dat", byteArrayOf(0x41, 0x00, 0x42))
        ).requireFailure()
        assertEquals("BINARY_NOT_SUPPORTED", binary.errorCode)
        val inputs = File(taskRoot, "inputs").listFiles().orEmpty()
        assertTrue("no failed snapshot may be published", inputs.isEmpty())
        val staging = File(taskRoot, "staging").listFiles().orEmpty()
        assertTrue("failed imports must not leave staging files", staging.isEmpty())
    }

    @Test
    fun `binary media type is rejected without an explicit encoding`() = runBlocking {
        val failure = snapshots.importInput(
            workspaceId,
            taskId,
            request(
                "content://image",
                "photo.png",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                mediaType = "image/png"
            )
        ).requireFailure()
        assertEquals("BINARY_NOT_SUPPORTED", failure.errorCode)
    }

    @Test
    fun `manifest payload exposes conversion record`() = runBlocking {
        val imported = snapshots.importInput(
            workspaceId,
            taskId,
            request("content://a", "doc.txt", "text".toByteArray())
        ).requireValue()
        val manifestBytes = AgentPayloadStore(temporary.root)
            .readNullable(workspaceId, taskId, imported.manifestRef)
        assertTrue(manifestBytes != null)
        val json = org.json.JSONObject(String(manifestBytes!!, Charsets.UTF_8))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(imported.logicalPath, json.getString("logicalPath"))
        assertEquals(imported.sourceUriSha256, json.getString("sourceUriSha256"))
        assertEquals(imported.storedSha256, json.getString("storedSha256"))
    }

    private fun request(
        uri: String,
        name: String,
        bytes: ByteArray,
        mediaType: String = "text/plain",
        sourceEncoding: String? = null
    ) = AgentInputImportRequest(
        sourceUri = uri,
        displayName = name,
        mediaType = mediaType,
        sourceEncoding = sourceEncoding,
        openStream = { ByteArrayInputStream(bytes) }
    )

    private fun <T> AgentFileResult<T>.requireValue(): T = when (this) {
        is AgentFileResult.Ok -> value
        is AgentFileResult.Failure -> error("unexpected failure $errorCode: $safeMessage")
    }

    private fun <T> AgentFileResult<T>.requireFailure(): AgentFileResult.Failure = when (this) {
        is AgentFileResult.Failure -> this
        is AgentFileResult.Ok -> error("expected failure but got $value")
    }
}
