package com.muyuchat.mca.agent.extensions.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

internal object AgentSkillFixtures {

    fun validMarkdown(
        name: String = "Test Skill",
        description: String = "A deterministic test skill.",
        allowedTools: List<String> = emptyList(),
        extraFrontmatter: String = "",
        body: String = "# Instructions\n\nDo not run scripts.\n"
    ): ByteArray {
        val tools = if (allowedTools.isEmpty()) "" else
            "allowed-tools:\n" + allowedTools.joinToString("\n") { "  - $it" } + "\n"
        return (
            "---\n" +
                "name: $name\n" +
                "description: $description\n" +
                tools +
                extraFrontmatter +
                "---\n" +
                body
            ).toByteArray(Charsets.UTF_8)
    }

    fun treeFile(
        name: String,
        content: String,
        id: String = "doc-$name",
        size: Long = content.toByteArray(Charsets.UTF_8).size.toLong()
    ): FakeTreeDocument = FakeTreeDocument(
        documentId = id,
        displayName = name,
        type = AgentSkillDocumentType.FILE,
        sizeBytes = size,
        bytes = content.toByteArray(Charsets.UTF_8)
    )

    fun treeDirectory(
        name: String,
        children: List<AgentSkillTreeDocument>,
        id: String = "dir-$name"
    ): FakeTreeDocument = FakeTreeDocument(
        documentId = id,
        displayName = name,
        type = AgentSkillDocumentType.DIRECTORY,
        sizeBytes = 0L,
        childProvider = { children }
    )

    fun treeSkill(
        markdown: ByteArray = validMarkdown(),
        extraRootChildren: List<AgentSkillTreeDocument> = emptyList()
    ): FakeTreeDocument = FakeTreeDocument(
        documentId = "root",
        displayName = "root",
        type = AgentSkillDocumentType.DIRECTORY,
        sizeBytes = 0L,
        childProvider = {
            listOf(
                FakeTreeDocument(
                    documentId = "root-skill-md",
                    displayName = "SKILL.md",
                    type = AgentSkillDocumentType.FILE,
                    sizeBytes = markdown.size.toLong(),
                    bytes = markdown
                )
            ) + extraRootChildren
        }
    )

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

    fun zipSkill(
        markdown: ByteArray = validMarkdown(),
        extraEntries: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray = zip(listOf("SKILL.md" to markdown) + extraEntries)

    fun assertSkillFailure(code: AgentSkillErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected AgentSkillException with $code")
        } catch (failure: AgentSkillException) {
            assertEquals("unexpected failure: ${failure.error}", code, failure.error.code)
        }
    }

    // --- custom ZIP writer for attributes/methods/declared sizes the JDK writer hides ----------

    data class RawZipEntry(
        val name: String,
        val data: ByteArray = ByteArray(0),
        val method: Int = 0,
        val flags: Int = 0,
        val uncompressedSizeOverride: Long? = null,
        val compressedSizeOverride: Long? = null,
        val externalAttributes: Long = 0L,
        val versionMadeBy: Int = (3 shl 8) or 20,
        val compressedData: ByteArray = data
    )

    fun customZip(entries: List<RawZipEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        val localOffsets = LongArray(entries.size)
        entries.forEachIndexed { index, entry ->
            localOffsets[index] = output.size().toLong()
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val uncompressed = entry.uncompressedSizeOverride ?: entry.data.size.toLong()
            val compressed = entry.compressedSizeOverride ?: entry.compressedData.size.toLong()
            val crc = CRC32().apply { update(entry.data) }.value
            output.writeIntLe(0x04034B50L)
            output.writeShortLe(20)
            output.writeShortLe(entry.flags)
            output.writeShortLe(entry.method)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(crc)
            output.writeIntLe(compressed)
            output.writeIntLe(uncompressed)
            output.writeShortLe(nameBytes.size)
            output.writeShortLe(0)
            output.write(nameBytes)
            output.write(entry.compressedData)
        }
        val centralOffset = output.size().toLong()
        entries.forEachIndexed { index, entry ->
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val uncompressed = entry.uncompressedSizeOverride ?: entry.data.size.toLong()
            val compressed = entry.compressedSizeOverride ?: entry.compressedData.size.toLong()
            val crc = CRC32().apply { update(entry.data) }.value
            output.writeIntLe(0x02014B50L)
            output.writeShortLe(entry.versionMadeBy)
            output.writeShortLe(20)
            output.writeShortLe(entry.flags)
            output.writeShortLe(entry.method)
            output.writeShortLe(0)
            output.writeShortLe(0)
            output.writeIntLe(crc)
            output.writeIntLe(compressed)
            output.writeIntLe(uncompressed)
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
}

internal class FakeTreeDocument(
    override val documentId: String,
    override val displayName: String,
    override val type: AgentSkillDocumentType,
    override val sizeBytes: Long = 0L,
    private val bytes: ByteArray = ByteArray(0),
    private val childProvider: () -> List<AgentSkillTreeDocument> = { emptyList() }
) : AgentSkillTreeDocument {
    var openCount: Int = 0
        private set

    override fun children(): List<AgentSkillTreeDocument> = childProvider()

    override fun openStream(): InputStream {
        openCount += 1
        return ByteArrayInputStream(bytes)
    }
}

internal class FakeSnapshotRepository : AgentSkillSnapshotRepository {
    val created = LinkedHashMap<String, AgentSkillStoredSnapshot>()
    val deleted = mutableListOf<String>()

    override fun create(
        skillId: String,
        version: Long,
        files: List<AgentSkillSourceFile>
    ): AgentSkillStoredSnapshot {
        val snapshotId = "$skillId-v$version"
        if (created.containsKey(snapshotId)) {
            skillFailure(AgentSkillErrorCode.VERSION_CONFLICT, "snapshot already exists")
        }
        val (sha, storedFiles) = AgentSkillContentHash.digestWithDetails(files)
        val snapshot = AgentSkillStoredSnapshot(snapshotId, skillId, version, sha, storedFiles)
        created[snapshotId] = snapshot
        return snapshot
    }

    override fun delete(snapshotId: String) {
        deleted += snapshotId
        created.remove(snapshotId)
    }

    override fun exists(snapshotId: String): Boolean = created.containsKey(snapshotId)
}
