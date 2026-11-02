package com.muyuchat.mca.agent.extensions.toolpkg

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentToolPackageFileStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `stores a read-only preview snapshot and verifies it idempotently`() {
        val root = temp.newFolder("store")
        val store = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "research.mca",
            AgentToolPackageFixtures.validZip()
        )

        val first = store.preview("content://import/research", archive)
        assertTrue("expected a preview, got $first", first is AgentToolPackageIngestResult.Previewed)
        first as AgentToolPackageIngestResult.Previewed
        assertFalse(first.idempotent)
        val preview = first.stored.preview
        assertEquals("research-basics", preview.packageId)
        assertEquals("1.0.0", preview.packageVersion)
        assertEquals(1, preview.tools.size)
        assertEquals(1, preview.resources.size)
        assertTrue(preview.previewOnly)
        assertEquals(AgentToolSideEffect.READ_ONLY, preview.tools.single().sideEffect)
        assertEquals(AgentDataClass.PRIVATE, preview.tools.single().dataClass)
        assertEquals("workspace.read_text", preview.tools.single().hostOperation)
        assertEquals(preview.contentSha256, preview.bindings.single().packageContentHash)
        assertEquals("pkg.research-basics.read_brief", preview.tools.single().ref.toolKey)

        val snapshot = File(root, "managed/research-basics/${preview.contentSha256}")
        assertTrue(File(snapshot, AgentToolPackageLimits.MANIFEST_FILE_NAME).isFile)
        assertTrue(File(snapshot, AgentToolPackageLimits.RECORD_FILE_NAME).isFile)
        assertEquals(AgentToolPackageFixtures.notes().size.toLong(), File(snapshot, "NOTES.md").length())
        assertFalse("snapshot files must be read-only", File(snapshot, "NOTES.md").canWrite())

        val verified = store.verify("research-basics")
        assertTrue(verified is AgentToolPackageVerificationResult.Valid)
        assertEquals(preview.contentSha256, (verified as AgentToolPackageVerificationResult.Valid).stored.preview.contentSha256)

        val second = store.preview("content://import/research", archive)
        assertTrue(second is AgentToolPackageIngestResult.Previewed)
        second as AgentToolPackageIngestResult.Previewed
        assertTrue(second.idempotent)
        assertEquals(first.stored.snapshotId, second.stored.snapshotId)
        assertEquals(1, store.summaries().size)
        assertNotNull(store.find("research-basics"))
    }

    @Test
    fun `reloads the index and verifies the snapshot after a restart`() {
        val root = temp.newFolder("restart-store")
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "restart.mca",
            AgentToolPackageFixtures.validZip()
        )
        val firstStore = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val stored = (firstStore.preview("content://restart", archive) as AgentToolPackageIngestResult.Previewed)
            .stored

        val reopened = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        assertEquals(1, reopened.summaries().size)
        val verified = reopened.verify("research-basics")
        assertTrue(verified is AgentToolPackageVerificationResult.Valid)
        assertEquals(
            stored.preview.contentSha256,
            (verified as AgentToolPackageVerificationResult.Valid).stored.preview.contentSha256
        )
        val repeat = reopened.preview("content://restart", archive)
        assertTrue(repeat is AgentToolPackageIngestResult.Previewed)
        assertTrue((repeat as AgentToolPackageIngestResult.Previewed).idempotent)
    }

    @Test
    fun `quarantines an invalid archive and creates no managed snapshot`() {
        val root = temp.newFolder("quarantine-store")
        val store = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val wrongHash = AgentToolPackageFixtures.resourceEntries(
            """{"path":"NOTES.md","mediaType":"text/markdown","sha256":"${"a".repeat(64)}"}"""
        )
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "invalid.mca",
            AgentToolPackageFixtures.zip(
                listOf(
                    AgentToolPackageFixtures.MANIFEST to AgentToolPackageFixtures.manifest(resourcesJson = wrongHash)
                        .toByteArray(Charsets.UTF_8),
                    "NOTES.md" to AgentToolPackageFixtures.notes()
                )
            )
        )

        val result = store.preview("content://import/invalid", archive)
        assertTrue(result is AgentToolPackageIngestResult.Rejected)
        result as AgentToolPackageIngestResult.Rejected
        assertEquals(AgentToolPackageErrorCode.MANIFEST_RESOURCE_HASH_MISMATCH, result.error.code)
        assertTrue(result.quarantined)
        val quarantineId = requireNotNull(result.quarantineId)
        val quarantine = File(root, "quarantine/$quarantineId")
        assertTrue(File(quarantine, "archive.bin").isFile)
        val reason = File(quarantine, "reason.json").readText()
        assertTrue(reason.contains("MANIFEST_RESOURCE_HASH_MISMATCH"))
        assertFalse(reason.contains("Read-only package notes."))
        assertTrue(File(root, "managed").listFiles().isNullOrEmpty())
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun `rejects a different content hash for an existing package id`() {
        val root = temp.newFolder("duplicate-store")
        val store = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val firstArchive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "first.mca",
            AgentToolPackageFixtures.validZip()
        )
        val first = store.preview("content://first", firstArchive) as AgentToolPackageIngestResult.Previewed

        val changedNotes = AgentToolPackageFixtures.notes("different notes")
        val secondManifest = AgentToolPackageFixtures.manifest(
            resourcesJson = AgentToolPackageFixtures.resourceEntries(
                AgentToolPackageFixtures.resourceEntry("NOTES.md", "text/markdown", changedNotes)
            )
        )
        val secondArchive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "second.mca",
            AgentToolPackageFixtures.zip(
                listOf(
                    AgentToolPackageFixtures.MANIFEST to secondManifest.toByteArray(Charsets.UTF_8),
                    "NOTES.md" to changedNotes
                )
            )
        )
        val second = store.preview("content://second", secondArchive)
        assertTrue(second is AgentToolPackageIngestResult.Rejected)
        second as AgentToolPackageIngestResult.Rejected
        assertEquals(AgentToolPackageErrorCode.DUPLICATE_PACKAGE_ID, second.error.code)
        assertTrue(second.quarantined)

        val verified = store.verify("research-basics") as AgentToolPackageVerificationResult.Valid
        assertEquals(first.stored.preview.contentSha256, verified.stored.preview.contentSha256)
        assertEquals(1, store.summaries().size)
    }

    @Test
    fun `reports tampered snapshots instead of trusting them`() {
        val root = temp.newFolder("tamper-store")
        val store = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "tamper.mca",
            AgentToolPackageFixtures.validZip()
        )
        val preview = (store.preview("content://tamper", archive) as AgentToolPackageIngestResult.Previewed)
            .stored.preview
        val notes = File(root, "managed/research-basics/${preview.contentSha256}/NOTES.md")
        assertTrue(notes.isFile)
        assertTrue(notes.setWritable(true, false))
        notes.writeText("tampered")

        val verification = store.verify("research-basics")
        assertTrue(verification is AgentToolPackageVerificationResult.Invalid)
        assertEquals(
            AgentToolPackageErrorCode.PACKAGE_CORRUPT,
            (verification as AgentToolPackageVerificationResult.Invalid).error.code
        )
        assertThrows(AgentToolPackageException::class.java) {
            store.find("research-basics")
        }
    }

    @Test
    fun `removes a preview without an enable toggle`() {
        val root = temp.newFolder("remove-store")
        val store = AgentToolPackageFileStore(root, AgentToolPackageFixtures.hostCatalog())
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder(),
            "remove.mca",
            AgentToolPackageFixtures.validZip()
        )
        val stored = (store.preview("content://remove", archive) as AgentToolPackageIngestResult.Previewed).stored
        store.remove("research-basics")

        assertTrue(store.summaries().isEmpty())
        assertTrue(store.verify("research-basics") is AgentToolPackageVerificationResult.Missing)
        assertFalse(File(root, "managed/research-basics/${stored.preview.contentSha256}").exists())
        assertThrows(AgentToolPackageException::class.java) {
            store.remove("research-basics")
        }
    }
}
