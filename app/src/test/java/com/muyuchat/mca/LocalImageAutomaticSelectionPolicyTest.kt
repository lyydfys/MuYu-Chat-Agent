package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageAutomaticSelectionPolicyTest {
    @Test
    fun `unknown and historical failed structurally complete models remain selectable`() {
        val root = Files.createTempDirectory("image-auto-selection").toFile()
        try {
            val unknown = model(root, "unknown", LocalImageVerificationStatus.UNKNOWN)
            val failed = model(root, "failed", LocalImageVerificationStatus.FAILED).copy(
                verificationMessage = "A previous native attempt failed.",
                verifiedAt = 123L
            )
            val passed = model(root, "passed", LocalImageVerificationStatus.PASSED)

            assertTrue(unknown.isReadyForLocalImageGeneration())
            assertTrue(failed.isReadyForLocalImageGeneration())

            assertEquals(
                failed.id,
                selectStructurallyReadyLocalImageModelId(
                    models = listOf(unknown, failed, passed),
                    preferredId = failed.id
                )
            )
            assertEquals(
                unknown.id,
                selectStructurallyReadyLocalImageModelId(
                    models = listOf(unknown, failed, passed),
                    preferredId = null
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `automatic selection skips only concrete structural failures`() {
        val root = Files.createTempDirectory("image-auto-selection-invalid").toFile()
        try {
            val missing = LocalImageModelRecord(
                id = "missing",
                displayName = "Missing",
                path = File(root, "missing.safetensors").path,
                fileName = "missing.safetensors",
                sizeBytes = 0L,
                sha256 = "missing",
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD_TURBO
            )
            val failedButComplete = model(root, "retry", LocalImageVerificationStatus.FAILED)

            assertEquals(
                failedButComplete.id,
                selectStructurallyReadyLocalImageModelId(
                    models = listOf(missing, failedButComplete),
                    preferredId = missing.id
                )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun model(
        root: File,
        id: String,
        status: LocalImageVerificationStatus
    ): LocalImageModelRecord {
        val file = File(root, "$id.safetensors").apply { writeText("model") }
        return LocalImageModelRecord(
            id = id,
            displayName = id,
            path = file.path,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = id,
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.SD_TURBO,
            verificationStatus = status
        )
    }
}
