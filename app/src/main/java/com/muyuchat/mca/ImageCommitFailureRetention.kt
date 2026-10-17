package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Rollback policy for a failed image-library commit.
 *
 * A cancellation is the one failure that legitimately rolls the run back: the user stopped it, so
 * the produced copy and the staged journal entry may go. Every other failure must keep both.
 *
 * Why this matters (F2b): `commitImageLibraryTransaction` returns any `Throwable` -- `runCatching`
 * catches `Error`s too -- so a failure inside the commit used to delete the only copy of a
 * successfully generated image *and* remove the staged record that the startup recovery path
 * (`ChatImageRetryAndCleanupOperations`) needs in order to retry the commit or preserve the file in
 * the gallery. That turned a recoverable commit failure into permanent data loss with no trace.
 */
internal fun shouldRollBackFailedImageCommit(
    commitError: Throwable?,
    cancelRequested: Boolean
): Boolean = cancelRequested || commitError is CancellationException

/**
 * Applies [shouldRollBackFailedImageCommit] to the produced images.
 *
 * Returns true when the run was rolled back (local copies deleted and staged journal entries
 * removed); false when the artifacts and their staged records were retained for recovery.
 */
internal fun settleFailedImageCommit(
    commitError: Throwable?,
    cancelRequested: Boolean,
    images: List<ImageAssetRecord>,
    assetDirectory: File,
    requestId: () -> String?,
    journalJobId: (ImageAssetRecord) -> String?,
    removeJournalEntry: (String, String) -> Boolean,
    deleteLocalCopy: (ImageAssetRecord, File) -> Boolean = { image, root ->
        image.deleteLocalCopy(root)
    }
): Boolean {
    if (!shouldRollBackFailedImageCommit(commitError, cancelRequested)) return false
    val request = requestId()
    for (image in images) {
        val rolledBack = deleteLocalCopy(image, assetDirectory)
        val journalJob = journalJobId(image)
        if (rolledBack && !request.isNullOrBlank() && !journalJob.isNullOrBlank()) {
            runCatching { removeJournalEntry(journalJob, request) }
        }
    }
    return true
}
