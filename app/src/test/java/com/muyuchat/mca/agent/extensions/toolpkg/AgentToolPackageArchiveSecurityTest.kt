package com.muyuchat.mca.agent.extensions.toolpkg

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolPackageArchiveSecurityTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `rejects malformed and missing-manifest archives`() {
        assertEquals(
            AgentToolPackageErrorCode.MALFORMED_ZIP,
            reject("not-a-zip.mca".toByteArray()).error.code
        )
        assertEquals(
            AgentToolPackageErrorCode.MISSING_MANIFEST,
            reject(AgentToolPackageFixtures.zip(listOf("NOTES.md" to AgentToolPackageFixtures.notes()))).error.code
        )
    }

    @Test
    fun `rejects path traversal and absolute entries`() {
        val traversal = AgentToolPackageFixtures.customZip(
            listOf(
                manifestEntry(),
                AgentToolPackageFixtures.RawZipEntry("NOTES.md", AgentToolPackageFixtures.notes()),
                AgentToolPackageFixtures.RawZipEntry("../escape.md", "escape".toByteArray())
            )
        )
        assertEquals(AgentToolPackageErrorCode.PATH_TRAVERSAL, reject(traversal).error.code)

        val absolute = AgentToolPackageFixtures.customZip(
            listOf(
                manifestEntry(),
                AgentToolPackageFixtures.RawZipEntry("/etc/passwd", "root".toByteArray())
            )
        )
        assertEquals(AgentToolPackageErrorCode.ABSOLUTE_PATH, reject(absolute).error.code)
    }

    @Test
    fun `rejects duplicate normalized entries`() {
        val duplicate = AgentToolPackageFixtures.customZip(
            listOf(
                manifestEntry(),
                AgentToolPackageFixtures.RawZipEntry("NOTES.md", AgentToolPackageFixtures.notes()),
                AgentToolPackageFixtures.RawZipEntry("NOTES.md", AgentToolPackageFixtures.notes())
            )
        )
        assertEquals(AgentToolPackageErrorCode.DUPLICATE_PATH, reject(duplicate).error.code)
    }

    @Test
    fun `rejects symlinks encrypted entries and unsupported compression`() {
        val symlink = AgentToolPackageFixtures.customZip(
            listOf(
                manifestEntry(),
                AgentToolPackageFixtures.RawZipEntry(
                    name = "NOTES.md",
                    externalAttributes = 0xA1FFL shl 16,
                    versionMadeBy = (3 shl 8) or 20
                )
            )
        )
        assertEquals(AgentToolPackageErrorCode.SYMLINK_REJECTED, reject(symlink).error.code)

        val encrypted = AgentToolPackageFixtures.customZip(
            listOf(manifestEntry(flags = 0x0001))
        )
        assertEquals(AgentToolPackageErrorCode.ENCRYPTED_ENTRY, reject(encrypted).error.code)

        val unsupportedCompression = AgentToolPackageFixtures.customZip(
            listOf(manifestEntry(method = 12))
        )
        assertEquals(AgentToolPackageErrorCode.UNSUPPORTED_COMPRESSION, reject(unsupportedCompression).error.code)
    }

    @Test
    fun `rejects entry-count and compression-ratio bombs`() {
        val tooManyEntries = AgentToolPackageFixtures.zip(
            (0 until AgentToolPackageLimits.MAX_ENTRIES + 1).map { index -> "f$index.txt" to ByteArray(0) }
        )
        assertEquals(AgentToolPackageErrorCode.ENTRY_COUNT_EXCEEDED, reject(tooManyEntries).error.code)

        val bomb = ByteArray(512 * 1024)
        val manifest = AgentToolPackageFixtures.manifest(
            resourcesJson = AgentToolPackageFixtures.resourceEntries(
                AgentToolPackageFixtures.resourceEntry("NOTES.md", "text/markdown", bomb)
            )
        )
        val compressedBomb = AgentToolPackageFixtures.zip(
            listOf(
                AgentToolPackageFixtures.MANIFEST to manifest.toByteArray(Charsets.UTF_8),
                "NOTES.md" to bomb
            )
        )
        assertTrue(
            "deflate should compress a zero buffer far beyond the ratio limit",
            compressedBomb.size < bomb.size / 100
        )
        assertEquals(AgentToolPackageErrorCode.COMPRESSION_RATIO_EXCEEDED, reject(compressedBomb).error.code)
    }

    @Test
    fun `rejects unlisted missing and mismatched resources`() {
        val unlisted = AgentToolPackageFixtures.zip(
            listOf(
                AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest(resourcesJson = "[]")
                    .toByteArray(Charsets.UTF_8),
                "NOTES.md" to AgentToolPackageFixtures.notes()
            )
        )
        assertEquals(AgentToolPackageErrorCode.MANIFEST_RESOURCE_UNLISTED, reject(unlisted).error.code)

        val missing = AgentToolPackageFixtures.zip(
            listOf(AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest().toByteArray(Charsets.UTF_8))
        )
        assertEquals(AgentToolPackageErrorCode.MANIFEST_RESOURCE_MISSING, reject(missing).error.code)

        val wrongHash = AgentToolPackageFixtures.resourceEntries(
            """{"path":"NOTES.md","mediaType":"text/markdown","sha256":"${"0".repeat(64)}"}"""
        )
        val mismatched = AgentToolPackageFixtures.zip(
            listOf(
                AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest(resourcesJson = wrongHash)
                    .toByteArray(Charsets.UTF_8),
                "NOTES.md" to AgentToolPackageFixtures.notes()
            )
        )
        assertEquals(AgentToolPackageErrorCode.MANIFEST_RESOURCE_HASH_MISMATCH, reject(mismatched).error.code)
    }

    @Test
    fun `rejects an entry above the per-file limit`() {
        val oversizedEntry = AgentToolPackageFixtures.zip(
            listOf(
                AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest().toByteArray(Charsets.UTF_8),
                "NOTES.md" to ByteArray(AgentToolPackageLimits.MAX_SINGLE_ENTRY_BYTES.toInt() + 1)
            )
        )
        assertEquals(AgentToolPackageErrorCode.ENTRY_SIZE_EXCEEDED, reject(oversizedEntry).error.code)
    }

    @Test
    fun `rejects an executable archive entry as an unlisted file`() {
        val executable = AgentToolPackageFixtures.zip(
            listOf(
                AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest(resourcesJson = "[]")
                    .toByteArray(Charsets.UTF_8),
                "payload.js" to "alert(1)".toByteArray()
            )
        )
        assertEquals(AgentToolPackageErrorCode.MANIFEST_RESOURCE_UNLISTED, reject(executable).error.code)
    }

    @Test
    fun `rejects an oversized archive before copying it`() {
        val storeRoot = temp.newFolder("oversized-store")
        val store = AgentToolPackageFileStore(storeRoot, AgentToolPackageFixtures.hostCatalog())
        val oversized = temp.newFile("oversized.mca")
        RandomAccessFile(oversized, "rw").use { handle ->
            handle.setLength(AgentToolPackageLimits.MAX_ARCHIVE_BYTES + 1)
        }

        val result = store.preview("content://oversized", oversized)
        assertTrue(result is AgentToolPackageIngestResult.Rejected)
        val rejected = result as AgentToolPackageIngestResult.Rejected
        assertEquals(AgentToolPackageErrorCode.PACKAGE_TOO_LARGE, rejected.error.code)
        assertFalse(rejected.quarantined)
        assertTrue(File(storeRoot, "managed").listFiles().isNullOrEmpty())
        assertTrue(File(storeRoot, "quarantine").listFiles().isNullOrEmpty())
    }

    private fun manifestEntry(
        method: Int = 0,
        flags: Int = 0
    ): AgentToolPackageFixtures.RawZipEntry = AgentToolPackageFixtures.RawZipEntry(
        name = AgentToolPackageFixtures.MANIFEST,
        data = AgentToolPackageFixtures.manifest().toByteArray(Charsets.UTF_8),
        method = method,
        flags = flags
    )

    private fun reject(bytes: ByteArray): AgentToolPackageIngestResult.Rejected {
        val store = AgentToolPackageFileStore(temp.newFolder(), AgentToolPackageFixtures.hostCatalog())
        val archive = AgentToolPackageFixtures.write(temp.newFolder(), "package.mca", bytes)
        val result = store.preview("content://test-package", archive)
        assertTrue("expected rejection but got $result", result is AgentToolPackageIngestResult.Rejected)
        return result as AgentToolPackageIngestResult.Rejected
    }
}
