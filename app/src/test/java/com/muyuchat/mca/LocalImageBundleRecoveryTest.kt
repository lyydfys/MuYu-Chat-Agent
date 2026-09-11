package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImageBundleRecoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readyCandidateReplacesLegacyEmptyDestination() {
        val root = temporaryFolder.newFolder("image_models")
        val destination = File(root, "bundle-example").apply { mkdirs() }
        val candidate = File(root, ".bundle-example.candidate").apply { mkdirs() }
        File(candidate, "manifest.json").writeText("ready")
        File(candidate, "model.gguf").writeBytes(byteArrayOf(1, 2, 3))

        val recovered = recoverInterruptedImageBundlePromotions(root) {
            File(it, "manifest.json").isFile && File(it, "model.gguf").length() > 0L
        }

        assertEquals(listOf(destination.canonicalFile), recovered)
        assertFalse(candidate.exists())
        assertTrue(File(destination, "model.gguf").isFile)
    }

    @Test
    fun incompleteCandidateIsRetainedForResume() {
        val root = temporaryFolder.newFolder("image_models")
        val candidate = File(root, ".bundle-example.candidate").apply { mkdirs() }
        File(candidate, "model.gguf.part").writeBytes(byteArrayOf(1))

        val recovered = recoverInterruptedImageBundlePromotions(root) { false }

        assertTrue(recovered.isEmpty())
        assertTrue(candidate.isDirectory)
        assertFalse(File(root, "bundle-example").exists())
    }

    @Test
    fun cleanupRemovesOnlyUnregisteredEmptyBundleDirectory() {
        val root = temporaryFolder.newFolder("image_models")
        val stale = File(root, "bundle-stale").apply { mkdirs() }
        val registered = File(root, "bundle-registered").apply { mkdirs() }
        val populated = File(root, "bundle-populated").apply { mkdirs() }
        File(populated, "model.gguf").writeBytes(byteArrayOf(1))

        removeUnregisteredEmptyImageBundleDirectories(root, setOf(registered.canonicalPath))

        assertFalse(stale.exists())
        assertTrue(registered.isDirectory)
        assertTrue(populated.isDirectory)
    }
}
