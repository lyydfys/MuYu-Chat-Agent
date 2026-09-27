package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatImageCommitJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stageEnumerateAndRemoveAfterDurableCommitRoundTripsMetadata() {
        val fixture = fixture()
        val journal = fixture.journal()
        val staged = journal.stage(fixture.record())

        assertTrue(staged.stagedAtEpochMs > 0L)
        val file = fixture.journalRoot.listFiles().orEmpty().single()
        assertTrue(file.name.matches(Regex("pending-[0-9a-f]{64}\\.json")))
        assertFalse(file.name.contains("job-1"))
        assertFalse(file.readText().contains("apiKey"))

        val scan = journal.enumerate()
        assertEquals(listOf(staged), scan.validEntries)
        assertTrue(scan.rejectedEntries.isEmpty())
        assertEquals(fixture.asset(), scan.validEntries.single().imageAssetRecord())

        assertFalse(journal.removeAfterDurableCommit("job-1", "request-1", durableCommitConfirmed = false))
        assertTrue(file.exists())
        assertFalse(journal.removeAfterDurableCommit("job-1", "stale-request", durableCommitConfirmed = true))
        assertTrue(file.exists())
        assertTrue(journal.removeAfterDurableCommit("job-1", "request-1", durableCommitConfirmed = true))
        assertFalse(file.exists())
        assertTrue(journal.enumerate().validEntries.isEmpty())
    }

    @Test
    fun rollbackRemovalRequiresCallerConfirmationAndDoesNotNeedAssetToRemain() {
        val fixture = fixture()
        val journal = fixture.journal()
        journal.stage(fixture.record())
        File(fixture.assets, "generated-image.png").delete()

        assertFalse(
            journal.removeAfterConfirmedAssetRollback(
                jobId = "job-1",
                requestId = "request-1",
                rollbackConfirmed = false
            )
        )
        assertTrue(fixture.journalRoot.listFiles().orEmpty().any { it.name.endsWith(".json") })
        assertTrue(
            journal.removeAfterConfirmedAssetRollback(
                jobId = "job-1",
                requestId = "request-1",
                rollbackConfirmed = true
            )
        )
        assertTrue(fixture.journalRoot.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test
    fun corruptJournalIsReportedWithoutAbortingEnumeration() {
        val fixture = fixture()
        val journal = fixture.journal()
        journal.stage(fixture.record())
        val pending = fixture.journalRoot.listFiles().orEmpty().single()
        pending.writeText("{ broken json")

        val scan = journal.enumerate()

        assertTrue(scan.validEntries.isEmpty())
        assertEquals(1, scan.rejectedEntries.size)
        assertEquals(pending.name, scan.rejectedEntries.single().fileName)
        assertTrue(scan.rejectedEntries.single().reason.isNotBlank())
        assertTrue(pending.exists())
    }

    @Test
    fun stageRejectsAssetOutsideOwnedDirectoryAndSizeMismatch() {
        val fixture = fixture()
        val journal = fixture.journal()
        val outside = File(temporaryFolder.root, "outside.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val escapedAsset = fixture.asset().copy(uriString = outside.toURI().toString())

        assertIllegalArgument {
            journal.stage(fixture.record(asset = escapedAsset, jobId = "job-outside"))
        }

        val wrongSize = fixture.asset().copy(sizeBytes = fixture.asset().sizeBytes + 1)
        assertIllegalArgument {
            journal.stage(fixture.record(asset = wrongSize, jobId = "job-size"))
        }
        assertTrue(fixture.journalRoot.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    @Test
    fun stageRejectsUnsafeIdentifiersAndCredentialBearingMetadata() {
        val fixture = fixture()
        val journal = fixture.journal()
        assertIllegalArgument { journal.stage(fixture.record(jobId = "../escape")) }

        val credentialMetadata = JSONObject().put("apiKey", "do-not-persist").toString()
        val asset = fixture.asset().copy(generationMetadataJson = credentialMetadata)
        assertIllegalArgument { journal.stage(fixture.record(asset = asset, jobId = "job-secret")) }

        val allWrittenText = fixture.journalRoot.walkTopDown()
            .filter(File::isFile)
            .joinToString("") { it.readText() }
        assertFalse(allWrittenText.contains("do-not-persist"))
        assertTrue(fixture.journalRoot.listFiles().orEmpty().none { it.name.endsWith(".json") })
    }

    private fun fixture(): Fixture {
        val base = temporaryFolder.newFolder("fixture-${System.nanoTime()}")
        val journalRoot = File(base, "pending-chat-image-commits").apply { mkdirs() }
        val assets = File(base, "image-assets").apply { mkdirs() }
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        File(assets, "generated-image.png").writeBytes(bytes)
        return Fixture(journalRoot, assets, bytes.size.toLong())
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation rejection.
        }
    }

    private data class Fixture(
        val journalRoot: File,
        val assets: File,
        val imageSize: Long
    ) {
        fun journal(): ChatImageCommitJournal = ChatImageCommitJournal(journalRoot, assets) { 1234L }

        fun asset(): ImageAssetRecord = ImageAssetRecord(
            id = "image-1",
            name = "generated-image.png",
            uriString = File(assets, "generated-image.png").toURI().toString(),
            source = "local_generation",
            prompt = "blue ceramic cup",
            createdAt = 123L,
            sizeBytes = imageSize,
            width = 512,
            height = 512,
            generationMetadataJson = "",
            favorite = false,
            chatSessionId = "session-1",
            projectId = null
        )

        fun record(
            asset: ImageAssetRecord = asset(),
            jobId: String = "job-1"
        ): ChatImageCommitJournal.Record = ChatImageCommitJournal.Record(
            jobId = jobId,
            chatSessionId = "session-1",
            requestId = "request-1",
            imageAssetMetadataJson = ChatImageCommitJournal.serializeImageAssetRecord(asset)
        )
    }
}
