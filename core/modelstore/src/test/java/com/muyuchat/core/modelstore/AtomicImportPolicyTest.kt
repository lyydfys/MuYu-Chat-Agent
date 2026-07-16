package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicImportPolicyTest {
    @Test
    fun transactionIdCannotEscapeTheManagedImportDirectory() {
        val root = Files.createTempDirectory("atomic-import-token-").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            atomicImportStagingFile(File(root, "model.gguf"), "../escape")
        }
    }

    @Test
    fun cleanupOnlyDeletesTheExpectedTransactionDirectory() {
        val root = Files.createTempDirectory("atomic-import-root-").toFile()
        val target = File(root, "model.gguf")
        val staging = atomicImportStagingFile(target, "tx")
        staging.parentFile?.mkdirs()
        staging.writeText("partial")
        val unrelated = File(root, "keep").apply { mkdirs() }

        assertTrue(cleanupAtomicImportStagingFile(staging, root))
        assertFalse(staging.parentFile!!.exists())
        assertTrue(unrelated.isDirectory)
    }

    @Test
    fun failedCommitNeverReplacesOrDeletesAnExistingFinalPath() {
        val root = Files.createTempDirectory("atomic-import-conflict-").toFile()
        val target = File(root, "model.gguf").apply { writeText("existing") }
        val staging = atomicImportStagingFile(target, "tx")
        staging.parentFile?.mkdirs()
        staging.writeText("new")

        assertFalse(commitAtomicImportPath(staging, target))
        assertEquals("existing", target.readText())
        assertEquals("new", staging.readText())
    }

    @Test
    fun providerReportedSizeNeverRejectsACompleteSafStream() {
        verifyProviderCopyAgainstAdvisorySize(null, copiedSize = 42L)
        verifyProviderCopyAgainstAdvisorySize(0L, copiedSize = 42L)
        verifyProviderCopyAgainstAdvisorySize(-1L, copiedSize = 42L)
        verifyProviderCopyAgainstAdvisorySize(4_096L, copiedSize = 42L)
    }

    @Test
    fun startupCleanupRemovesOnlyStaleManagedImportArtifacts() {
        val root = Files.createTempDirectory("stale-import-root-").toFile()
        val now = 10L * 24L * 60L * 60L * 1000L
        val oldDirectory = File(root, ".importing-00000000-0000-0000-0000-000000000001").apply {
            mkdirs()
            File(this, "model.gguf").writeText("partial")
            setLastModified(now - 2L * 24L * 60L * 60L * 1000L)
        }
        val oldLegacyFile = File(root, ".model.gguf.importing-00000000-0000-0000-0000-000000000002").apply {
            writeText("partial")
            setLastModified(now - 2L * 24L * 60L * 60L * 1000L)
        }
        val recentDirectory = File(root, ".importing-00000000-0000-0000-0000-000000000003").apply {
            mkdirs()
            setLastModified(now)
        }
        val legitimateHiddenModel = File(root, ".importing-release.gguf").apply {
            writeText("model")
            setLastModified(now - 5L * 24L * 60L * 60L * 1000L)
        }
        val misleadingLegacyLikeFile = File(root, ".model.gguf.importing-not-a-transaction").apply {
            writeText("model")
            setLastModified(now - 5L * 24L * 60L * 60L * 1000L)
        }
        val realModel = File(root, "model.gguf").apply {
            writeText("model")
            setLastModified(now - 5L * 24L * 60L * 60L * 1000L)
        }

        assertEquals(2, cleanupStaleAtomicImports(root, nowMillis = now))
        assertFalse(oldDirectory.exists())
        assertFalse(oldLegacyFile.exists())
        assertTrue(recentDirectory.isDirectory)
        assertTrue(legitimateHiddenModel.isFile)
        assertTrue(misleadingLegacyLikeFile.isFile)
        assertTrue(realModel.isFile)
    }
}
