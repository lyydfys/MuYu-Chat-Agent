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
}
