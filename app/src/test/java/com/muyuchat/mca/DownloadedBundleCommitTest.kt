package com.muyuchat.mca

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadedBundleCommitTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun failedCatalogRegistrationRestoresOldModelAndKeepsDownload() {
        val destination = temp.newFolder("bundle-model")
        File(destination, "model").writeText("old")
        val candidate = temp.newFolder(".bundle-model.candidate")
        File(candidate, "model").writeText("new")
        val backup = promoteImageBundleCandidate(candidate, destination)
        restoreImageBundleBackup(destination, backup)
        assertEquals("old", File(destination, "model").readText())
        assertEquals("new", File(candidate, "model").readText())
    }

    @Test fun interruptedRenameDoesNotDeleteTheOnlyPreviousModel() {
        val destination = File(temp.root, "bundle-model")
        val previous = temp.newFolder(".bundle-model.backup")
        File(previous, "model").writeText("previous")
        val candidate = temp.newFolder(".bundle-model.candidate")
        File(candidate, "model").writeText("new")
        val backup = promoteImageBundleCandidate(candidate, destination)
        assertEquals("previous", File(requireNotNull(backup), "model").readText())
        assertEquals("new", File(destination, "model").readText())
    }

    @Test fun stagedVisionBundleStaysPrivateUntilCompleteAndCanRecoverAfterRegistrationFailure() {
        val destination = File(temp.root, "models/vision-bundle")
        val candidate = imageBundleCandidateDirectory(destination)
        assertFalse(destination.exists())

        candidate.mkdirs()
        File(candidate, "model.gguf").writeText("complete main model")
        File(candidate, ".mmproj.gguf.part").writeText("partial projector")
        assertFalse("A partial multi-file download must not expose a public bundle folder", destination.exists())

        File(candidate, "mmproj.gguf").writeText("complete projector")
        File(candidate, ".mmproj.gguf.part").delete()
        File(candidate, "manifest.json").writeText("complete bundle manifest")
        promoteImageBundleCandidate(candidate, destination)

        assertTrue(File(destination, "model.gguf").isFile)
        assertEquals("complete projector", File(destination, "mmproj.gguf").readText())

        // Simulate a failure while registering the main GGUF and binding its
        // projector. Rollback hides the public directory but preserves every
        // completed component so the same install can be retried.
        restoreImageBundleBackup(destination, backup = null)
        assertFalse(destination.exists())
        val recoveredCandidate = imageBundleCandidateDirectory(destination)
        assertEquals("complete main model", File(recoveredCandidate, "model.gguf").readText())
        assertEquals("complete projector", File(recoveredCandidate, "mmproj.gguf").readText())

        promoteImageBundleCandidate(recoveredCandidate, destination)

        assertTrue(File(destination, "model.gguf").isFile)
        assertTrue(File(destination, "mmproj.gguf").isFile)
        assertEquals("complete bundle manifest", File(destination, "manifest.json").readText())
    }

    @Test fun compoundPublishFailureRestoresOldBytesAndPreservesRetryCandidate() {
        val destination = temp.newFolder("publish-bundle")
        File(destination, "model").writeText("old")
        val candidate = temp.newFolder(".publish-bundle.candidate")
        File(candidate, "model").writeText("new")
        val failure = IllegalStateException("projector registration failed")
        val observed = runCatching {
            publishDownloadedBundleCandidate(candidate, destination) { throw failure }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals("old", File(destination, "model").readText())
        assertEquals("new", File(candidate, "model").readText())
    }

    @Test fun successfulPublicationReturnsCatalogEntryAndRemovesOnlyOwnedBackup() {
        val destination = temp.newFolder("committed-bundle")
        File(destination, "model").writeText("old")
        val candidate = temp.newFolder(".committed-bundle.candidate")
        File(candidate, "model").writeText("new")
        val result = publishDownloadedBundleCandidate(candidate, destination) { "catalog-id" }
        assertEquals("catalog-id", result)
        assertEquals("new", File(destination, "model").readText())
        assertFalse(File(temp.root, ".committed-bundle.backup").exists())
        assertFalse(candidate.exists())
    }
}
