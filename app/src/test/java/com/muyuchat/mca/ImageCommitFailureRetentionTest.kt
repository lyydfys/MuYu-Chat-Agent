package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F2b-1 regression cover: a failed image-library commit must not destroy a successfully produced
 * image.
 *
 * `commitImageLibraryTransaction` hands back any `Throwable` (its `runCatching` catches `Error`s
 * too). The old failure path deleted the local copy and removed the staged journal record for every
 * failure, which left `image_assets` empty, the only file gone, and nothing for the startup
 * recovery path (`ChatImageRetryAndCleanupOperations`) to retry -- permanent data loss. Only a
 * cancellation may roll back now.
 */
class ImageCommitFailureRetentionTest {

    private class Fixture {
        val root: File = Files.createTempDirectory("mca-commit-retention").toFile()
        val assetDirectory: File = File(root, "assets").apply { mkdirs() }
        val journalDirectory: File = File(root, "pending").apply { mkdirs() }
        val assetFile: File = File(assetDirectory, "img-1.png").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        val asset = ImageAssetRecord(
            id = ASSET_ID,
            name = assetFile.name,
            uriString = assetFile.toURI().toString(),
            source = "local",
            prompt = "",
            createdAt = 1L,
            sizeBytes = assetFile.length(),
            width = 8,
            height = 8,
            generationMetadataJson = "",
            favorite = false,
            chatSessionId = SESSION_ID
        )
        val journal = ChatImageCommitJournal(
            ownedRoot = journalDirectory,
            assetDirectory = assetDirectory,
            clock = { 1L }
        )

        init {
            journal.stage(
                ChatImageCommitJournal.Record(
                    jobId = JOURNAL_JOB_ID,
                    chatSessionId = SESSION_ID,
                    requestId = REQUEST_ID,
                    imageAssetMetadataJson = ChatImageCommitJournal.serializeImageAssetRecord(asset)
                )
            )
            check(stagedRecords() == 1) { "fixture must stage exactly one recoverable record" }
        }

        fun stagedRecords(): Int = journal.enumerate().validEntries.size

        fun dispose() {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a non cancellation commit failure keeps the produced copy and its staged record`() {
        val fixture = Fixture()
        try {
            val rolledBack = settleFailedImageCommit(
                commitError = StackOverflowError("stack size 1037KB"),
                cancelRequested = false,
                images = listOf(fixture.asset),
                assetDirectory = fixture.assetDirectory,
                requestId = { REQUEST_ID },
                journalJobId = { JOURNAL_JOB_ID },
                removeJournalEntry = { _, _ ->
                    fail("a non-cancellation failure must not remove the staged record")
                    true
                },
                deleteLocalCopy = { _, _ ->
                    fail("a non-cancellation failure must not delete the produced copy")
                    true
                }
            )

            assertFalse("the commit failure must not be treated as a rollback", rolledBack)
            assertTrue("the produced image must survive the failed commit", fixture.assetFile.isFile)
            assertEquals("the staged record must stay recoverable", 1, fixture.stagedRecords())
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun `a cancellation still rolls back the copy and the staged record`() {
        val fixture = Fixture()
        try {
            val rolledBack = settleFailedImageCommit(
                commitError = CancellationException("Image generation lost ownership before library commit."),
                cancelRequested = false,
                images = listOf(fixture.asset),
                assetDirectory = fixture.assetDirectory,
                requestId = { REQUEST_ID },
                journalJobId = { JOURNAL_JOB_ID },
                removeJournalEntry = { jobId, requestId ->
                    fixture.journal.removeAfterConfirmedAssetRollback(jobId, requestId, rollbackConfirmed = true)
                },
                deleteLocalCopy = { _, _ -> fixture.assetFile.delete() }
            )

            assertTrue("a cancellation is still rolled back", rolledBack)
            assertFalse("the cancelled copy is removed", fixture.assetFile.exists())
            assertEquals("the cancelled staged record is removed", 0, fixture.stagedRecords())
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun `a cancel request rolls back even when the error is not a cancellation`() {
        val fixture = Fixture()
        try {
            val rolledBack = settleFailedImageCommit(
                commitError = IllegalStateException("commit aborted"),
                cancelRequested = true,
                images = listOf(fixture.asset),
                assetDirectory = fixture.assetDirectory,
                requestId = { REQUEST_ID },
                journalJobId = { JOURNAL_JOB_ID },
                removeJournalEntry = { jobId, requestId ->
                    fixture.journal.removeAfterConfirmedAssetRollback(jobId, requestId, rollbackConfirmed = true)
                },
                deleteLocalCopy = { _, _ -> fixture.assetFile.delete() }
            )

            assertTrue("a user cancel still rolls back", rolledBack)
            assertFalse(fixture.assetFile.exists())
            assertEquals(0, fixture.stagedRecords())
        } finally {
            fixture.dispose()
        }
    }

    @Test
    fun `only a cancellation or an explicit cancel request authorises a rollback`() {
        assertFalse(shouldRollBackFailedImageCommit(null, cancelRequested = false))
        assertFalse(shouldRollBackFailedImageCommit(RuntimeException("db"), cancelRequested = false))
        assertFalse(shouldRollBackFailedImageCommit(StackOverflowError("stack size 1037KB"), cancelRequested = false))
        assertFalse(shouldRollBackFailedImageCommit(OutOfMemoryError("failed to allocate"), cancelRequested = false))
        assertTrue(shouldRollBackFailedImageCommit(CancellationException("stopped"), cancelRequested = false))
        assertTrue(shouldRollBackFailedImageCommit(RuntimeException("db"), cancelRequested = true))
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val REQUEST_ID = "request-1"
        const val JOURNAL_JOB_ID = "journal-job-1"
        const val ASSET_ID = "asset-1"
    }
}
