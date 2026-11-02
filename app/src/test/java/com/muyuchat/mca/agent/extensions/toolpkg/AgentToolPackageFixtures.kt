package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

internal object AgentToolPackageFixtures {
    const val MANIFEST = "mca-tool-package.json"

    fun hostCatalog(): AgentToolPackageHostCatalog =
        BuiltInToolPackageHostCatalog.readOnly(builtInAgentToolCatalog(UnusedWorkspaceFilePort()))

    fun notes(text: String = "# Notes\n\nRead-only package notes.\n"): ByteArray =
        text.toByteArray(Charsets.UTF_8)

    fun sha256(bytes: ByteArray): String = buildString {
        MessageDigest.getInstance("SHA-256").digest(bytes).forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    fun resourceEntry(path: String, mediaType: String, content: ByteArray): String =
        """{"path":"$path","mediaType":"$mediaType","sha256":"${sha256(content)}"}"""

    fun resourceEntries(vararg entries: String): String = entries.joinToString(prefix = "[", postfix = "]")

    fun toolEntry(
        name: String = "read_brief",
        displayName: String = "Read brief",
        description: String = "Read a bounded brief.",
        hostOperation: String = "workspace.read_text",
        preset: String = """{"path":"inputs/brief.txt"}""",
        expose: String = """["offset","limit"]""",
        timeoutMillis: String? = "10000",
        extra: String = ""
    ): String {
        val timeout = if (timeoutMillis == null) "" else ""","timeoutMillis":$timeoutMillis"""
        return """{"name":"$name","displayName":"$displayName","description":"$description",""" +
            """"hostOperation":"$hostOperation","preset":$preset,"expose":$expose$timeout$extra}"""
    }

    fun manifest(
        packageId: String = "research-basics",
        packageVersion: String = "1.0.0",
        displayName: String = "Research basics",
        description: String = "Curated read-only workspace tools.",
        requiresJson: String = "[]",
        toolsJson: String? = null,
        resourcesJson: String = resourceEntries(
            resourceEntry("NOTES.md", "text/markdown", notes())
        ),
        extraRoot: String = "",
        schemaVersion: Int = 1,
        apiVersion: Int = 1
    ): String {
        val tools = toolsJson ?: "[${toolEntry()}]"
        return """{"schemaVersion":$schemaVersion,"packageId":"$packageId",""" +
            """"packageVersion":"$packageVersion","apiVersion":$apiVersion,""" +
            """"displayName":"$displayName","description":"$description",""" +
            """"requires":$requiresJson,"tools":$tools,"resources":$resourcesJson$extraRoot}"""
    }

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun validZip(
        manifestJson: String = manifest(),
        resources: List<Pair<String, ByteArray>> = listOf("NOTES.md" to notes())
    ): ByteArray = zip(listOf(MANIFEST to manifestJson.toByteArray(Charsets.UTF_8)) + resources)

    fun write(temp: File, name: String, bytes: ByteArray): File =
        File(temp, name).also { it.writeBytes(bytes) }

    fun assertPackageFailure(code: AgentToolPackageErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected AgentToolPackageException with $code")
        } catch (failure: AgentToolPackageException) {
            assertEquals("unexpected failure: ${failure.error}", code, failure.error.code)
        }
    }

    // --- custom ZIP writer for attributes/methods the JDK writer hides -------------------------

    data class RawZipEntry(
        val name: String,
        val data: ByteArray = ByteArray(0),
        val method: Int = 0,
        val flags: Int = 0,
        val externalAttributes: Long = 0L,
        val versionMadeBy: Int = (3 shl 8) or 20,
        val declaredUncompressedSize: Long = data.size.toLong(),
        val declaredCompressedSize: Long = data.size.toLong(),
        val compressedData: ByteArray = data
    )

    fun customZip(entries: List<RawZipEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val localOffsets = LongArray(entries.size)
        entries.forEachIndexed { index, entry ->
            localOffsets[index] = output.size().toLong()
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.data) }.value
            output.writeIntLe(0x04034B50L)
            output.writeShortLe(20)
            output.writeShortLe(entry.flags)
            output.writeShortLe(entry.method)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(crc)
            output.writeIntLe(entry.declaredCompressedSize)
            output.writeIntLe(entry.declaredUncompressedSize)
            output.writeShortLe(nameBytes.size)
            output.writeShortLe(0)
            output.write(nameBytes)
            output.write(entry.compressedData)
        }
        val centralOffset = output.size().toLong()
        entries.forEachIndexed { index, entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(entry.data) }.value
            output.writeIntLe(0x02014B50L)
            output.writeShortLe(entry.versionMadeBy)
            output.writeShortLe(20)
            output.writeShortLe(entry.flags)
            output.writeShortLe(entry.method)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(crc)
            output.writeIntLe(entry.declaredCompressedSize)
            output.writeIntLe(entry.declaredUncompressedSize)
            output.writeShortLe(nameBytes.size)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(entry.externalAttributes)
            output.writeIntLe(localOffsets[index])
            output.write(nameBytes)
        }
        val centralSize = output.size().toLong() - centralOffset
        output.writeIntLe(0x06054B50L)
        output.writeShortLe(0)
        output.writeShortLe(0)
        output.writeShortLe(entries.size)
        output.writeShortLe(entries.size)
        output.writeIntLe(centralSize)
        output.writeIntLe(centralOffset)
        output.writeShortLe(0)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Long) {
        write((value and 0xFFL).toInt())
        write(((value ushr 8) and 0xFFL).toInt())
        write(((value ushr 16) and 0xFFL).toInt())
        write(((value ushr 24) and 0xFFL).toInt())
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * Only catalog construction needs an implementation; no executor is ever invoked by these tests.
 */
private class UnusedWorkspaceFilePort : AgentWorkspaceFilePort {
    override suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult> = AgentFileResult.Failure("UNUSED", "not used")

    override suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult> = AgentFileResult.Failure("UNUSED", "not used")

    override suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult> = AgentFileResult.Failure("UNUSED", "not used")

    override suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> = AgentFileResult.Failure("UNUSED", "not used")
}
