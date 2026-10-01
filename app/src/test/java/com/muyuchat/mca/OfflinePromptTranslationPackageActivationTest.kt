package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePromptTranslationPackageActivationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun verifiedCompletedDownloadIsPromotedWithAllComponentsAndFreshPaths() {
        val parent = temporary.newFolder()
        val stage = packageDirectory(parent, "offline-prompt-translation.download", "new")
        val active = File(parent, "offline-prompt-translation")
        val verifiedPaths = mutableListOf<File>()
        val result = activateOfflinePromptTranslationPackage(stage, active) { root ->
            verifiedPaths += root
            val value = requireComplete(root)
            root.absolutePath to value
        }
        assertEquals(active.absolutePath to "new", result)
        assertEquals(listOf(stage, active), verifiedPaths)
        assertFalse(stage.exists())
        assertEquals("new", requireComplete(active))
    }

    @Test
    fun partialDownloadCannotReplaceTheInstalledPackage() {
        val parent = temporary.newFolder()
        val active = packageDirectory(parent, "offline-prompt-translation", "installed")
        val stage = File(parent, "offline-prompt-translation.download").also { check(it.mkdir()) }
        assertThrows(IllegalStateException::class.java) {
            activateOfflinePromptTranslationPackage(stage, active, ::requireComplete)
        }
        assertEquals("installed", requireComplete(active))
        assertTrue(stage.isDirectory)
        assertEquals(2, requireNotNull(parent.list()).size)
    }

    @Test
    fun failedPostPromotionVerificationRestoresTheOldInstalledPackage() {
        val parent = temporary.newFolder()
        val active = packageDirectory(parent, "offline-prompt-translation", "installed")
        val stage = packageDirectory(parent, "offline-prompt-translation.download", "candidate")
        val failure = assertThrows(IllegalStateException::class.java) {
            activateOfflinePromptTranslationPackage(stage, active) { root ->
                val contents = requireComplete(root)
                check(root != active) { "post-promotion verification failed" }
                contents
            }
        }
        assertEquals("post-promotion verification failed", failure.message)
        assertEquals("installed", requireComplete(active))
        val recoverable = requireNotNull(parent.listFiles()).single { it.name.contains(".recovery-") }
        assertEquals("candidate", requireComplete(recoverable))
    }

    @Test
    fun differentStorageRootsAreRejectedBeforeEitherDirectoryMoves() {
        val stageParent = temporary.newFolder()
        val activeParent = temporary.newFolder()
        val stage = packageDirectory(stageParent, "stage", "candidate")
        val active = packageDirectory(activeParent, "active", "installed")
        assertThrows(IllegalArgumentException::class.java) {
            activateOfflinePromptTranslationPackage(stage, active, ::requireComplete)
        }
        assertEquals("candidate", requireComplete(stage))
        assertEquals("installed", requireComplete(active))
    }

    private fun packageDirectory(parent: File, name: String, contents: String): File =
        File(parent, name).also { root ->
            check(root.mkdir())
            File(root, "verified-component").writeText(contents)
        }

    private fun requireComplete(root: File): String {
        val file = File(root, "verified-component")
        check(file.isFile) { "partial package" }
        return file.readText()
    }
}
