package com.muyuchat.mca

import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageMnnOutputLifecycleTest {
    @Test
    fun publicationRequiresMatchingOuterNativeEffectiveAndOutputItemEvidence() {
        val root = Files.createTempDirectory("mca-mnn-output-evidence").toFile()
        try {
            val output = root.resolve("mnn-diffusion-123e4567-e89b-12d3-a456-426614174000.png")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val sha256 = "a".repeat(64)
            fun fixture(): JSONObject = JSONObject()
                .put("path", output.absolutePath)
                .put("outputPath", output.absolutePath)
                .put("outputBytes", 3)
                .put("outputSha256", sha256)
                .put(
                    "nativeEffective",
                    JSONObject()
                        .put("outputPath", output.absolutePath)
                        .put("outputBytes", 3)
                        .put("outputSha256", sha256)
                )
                .put(
                    "outputs",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("path", output.absolutePath)
                            .put("outputBytes", 3)
                            .put("outputSha256", sha256)
                            .put("mimeType", "image/png")
                    )
                )

            val verified = VerifiedQnnImageOutput(byteArrayOf(1, 2, 3), "image/png", 3)
            verifyMnnPublishedOutputEvidence(fixture(), output, verified)

            val tampered = fixture().apply {
                getJSONObject("nativeEffective").put("outputSha256", "b".repeat(64))
            }
            assertTrue(
                runCatching {
                    verifyMnnPublishedOutputEvidence(tampered, output, verified)
                }.isFailure
            )

            val sanitized = JSONObject(sanitizeNativeExecutionJson(fixture().toString()))
            assertFalse(sanitized.has("path"))
            assertFalse(sanitized.has("outputPath"))
            assertFalse(sanitized.getJSONObject("nativeEffective").has("outputPath"))
            assertEquals(sha256, sanitized.getString("outputSha256"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun requestCleanupRemovesOnlyTheExpectedOutputAndTemporaryFile() {
        val root = Files.createTempDirectory("mca-mnn-output-cleanup").toFile()
        try {
            val output = root.resolve("mnn-diffusion-123e4567-e89b-12d3-a456-426614174000.png")
                .apply { writeBytes(byteArrayOf(1)) }
            val temporary = root.resolve(output.name + ".part").apply { writeBytes(byteArrayOf(2)) }
            val sibling = root.resolve("mnn-diffusion-unrelated.png").apply { writeBytes(byteArrayOf(3)) }

            assertTrue(mnnDiffusionRequestOutputCandidates(output).containsAll(listOf(output, temporary)))
            cleanupMnnDiffusionRequestOutputs(output)

            assertFalse(output.exists())
            assertFalse(temporary.exists())
            assertTrue(sibling.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleSweepUsesClosedNamesAndTheFullAgeBoundary() {
        val root = Files.createTempDirectory("mca-mnn-output-stale").toFile()
        val now = 200_000_000L
        try {
            val staleUuid = root.resolve(
                "mnn-diffusion-123e4567-e89b-12d3-a456-426614174000.png"
            ).apply {
                writeBytes(byteArrayOf(1))
                setLastModified(now - MNN_DIFFUSION_OUTPUT_MAX_AGE_MS)
            }
            val staleLegacy = root.resolve("mnn-diffusion-123456789.png.part").apply {
                writeBytes(byteArrayOf(2))
                setLastModified(now - MNN_DIFFUSION_OUTPUT_MAX_AGE_MS - 1L)
            }
            val fresh = root.resolve(
                "mnn-diffusion-223e4567-e89b-12d3-a456-426614174000.png"
            ).apply {
                writeBytes(byteArrayOf(3))
                setLastModified(now - MNN_DIFFUSION_OUTPUT_MAX_AGE_MS + 1L)
            }
            val unrelated = root.resolve("mnn-diffusion-123456789.png.preview.png").apply {
                writeBytes(byteArrayOf(4))
                setLastModified(now - MNN_DIFFUSION_OUTPUT_MAX_AGE_MS - 1L)
            }

            pruneStaleMnnDiffusionOutputs(root, nowMillis = now)

            assertFalse(staleUuid.exists())
            assertFalse(staleLegacy.exists())
            assertTrue(fresh.exists())
            assertTrue(unrelated.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
