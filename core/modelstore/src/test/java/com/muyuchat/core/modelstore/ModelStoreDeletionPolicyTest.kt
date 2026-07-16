package com.muyuchat.core.modelstore

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelStoreDeletionPolicyTest {
    @Test
    fun falseFileDeleteResultKeepsManifestAndReportsPath() {
        val root = tempDirectory()
        try {
            val model = File(root, "model.gguf").apply { writeText("model") }
            var manifestUpdated = false

            val error = assertThrows(IllegalStateException::class.java) {
                deleteModelArtifactsBeforeManifestUpdate(
                    modelPath = model,
                    mainDeletionTarget = model,
                    ownedProjectorPath = null,
                    deleteFile = { false },
                    updateManifest = { manifestUpdated = true }
                )
            }

            assertFalse(manifestUpdated)
            assertTrue(model.exists())
            assertTrue(error.message.orEmpty().contains("delete()"))
            assertTrue(error.message.orEmpty().contains("false"))
            assertTrue(error.message.orEmpty().contains(model.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun successfulReturnWithoutRemovingPathDoesNotUpdateManifest() {
        val root = tempDirectory()
        try {
            val model = File(root, "model.gguf").apply { writeText("model") }
            var manifestUpdated = false

            val error = assertThrows(IllegalStateException::class.java) {
                deleteModelArtifactsBeforeManifestUpdate(
                    modelPath = model,
                    mainDeletionTarget = model,
                    ownedProjectorPath = null,
                    deleteFile = { true },
                    updateManifest = { manifestUpdated = true }
                )
            }

            assertFalse(manifestUpdated)
            assertTrue(model.exists())
            assertTrue(error.message.orEmpty().contains(model.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun falseRecursiveDeleteResultKeepsManifestAndReportsDirectory() {
        val root = tempDirectory()
        try {
            val bundle = File(root, "bundle").apply { mkdirs() }
            File(bundle, "model.mnn").writeText("model")
            var manifestUpdated = false

            val error = assertThrows(IllegalStateException::class.java) {
                deleteModelArtifactsBeforeManifestUpdate(
                    modelPath = bundle,
                    mainDeletionTarget = bundle,
                    ownedProjectorPath = null,
                    deleteRecursively = { false },
                    updateManifest = { manifestUpdated = true }
                )
            }

            assertFalse(manifestUpdated)
            assertTrue(bundle.exists())
            assertTrue(error.message.orEmpty().contains("deleteRecursively()"))
            assertTrue(error.message.orEmpty().contains("false"))
            assertTrue(error.message.orEmpty().contains(bundle.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun projectorFailureLeavesPrimaryModelAndManifestIntact() {
        val root = tempDirectory()
        try {
            val model = File(root, "model.gguf").apply { writeText("model") }
            val projector = File(root, "mmproj.gguf").apply { writeText("projector") }
            var manifestUpdated = false

            val error = assertThrows(IllegalStateException::class.java) {
                deleteModelArtifactsBeforeManifestUpdate(
                    modelPath = model,
                    mainDeletionTarget = model,
                    ownedProjectorPath = projector,
                    deleteFile = { path -> if (path == projector) false else path.delete() },
                    updateManifest = { manifestUpdated = true }
                )
            }

            assertFalse(manifestUpdated)
            assertTrue(model.exists())
            assertTrue(projector.exists())
            assertTrue(error.message.orEmpty().contains(projector.absolutePath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestUpdateRunsOnlyAfterStandaloneArtifactsAreAbsent() {
        val root = tempDirectory()
        try {
            val model = File(root, "model.gguf").apply { writeText("model") }
            val projector = File(root, "mmproj.gguf").apply { writeText("projector") }
            var manifestUpdated = false

            deleteModelArtifactsBeforeManifestUpdate(
                modelPath = model,
                mainDeletionTarget = model,
                ownedProjectorPath = projector
            ) {
                assertFalse(model.exists())
                assertFalse(projector.exists())
                manifestUpdated = true
            }

            assertTrue(manifestUpdated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recursiveMainDeletionCoversProjectorInsideBundle() {
        val root = tempDirectory()
        try {
            val bundle = File(root, "bundle").apply { mkdirs() }
            val model = File(bundle, "model.gguf").apply { writeText("model") }
            val projector = File(bundle, "mmproj.gguf").apply { writeText("projector") }
            var manifestUpdated = false

            deleteModelArtifactsBeforeManifestUpdate(
                modelPath = model,
                mainDeletionTarget = bundle,
                ownedProjectorPath = projector,
                deleteFile = {
                    fail("Projector covered by the recursive bundle delete must not be deleted separately.")
                    false
                },
                updateManifest = {
                    assertFalse(bundle.exists())
                    assertFalse(model.exists())
                    assertFalse(projector.exists())
                    manifestUpdated = true
                }
            )

            assertTrue(manifestUpdated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun alreadyMissingArtifactCanBeRemovedFromManifest() {
        val root = tempDirectory()
        try {
            val missing = File(root, "missing.gguf")
            var manifestUpdated = false

            deleteModelArtifactsBeforeManifestUpdate(
                modelPath = missing,
                mainDeletionTarget = missing,
                ownedProjectorPath = null,
                deleteFile = {
                    fail("A missing path must not invoke delete().")
                    false
                },
                updateManifest = { manifestUpdated = true }
            )

            assertTrue(manifestUpdated)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempDirectory(): File = Files.createTempDirectory("modelstore-delete-").toFile()
}
