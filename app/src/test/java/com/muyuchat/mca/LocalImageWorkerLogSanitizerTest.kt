package com.muyuchat.mca

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageWorkerLogSanitizerTest {
    @Test
    fun completionLogKeepsOnlyFixedScalarExecutionEvidence() {
        val metadata = JSONObject()
            .put("path", "/private/cache/output.png")
            .put("prompt", "private prompt text")
            .put("contentUri", "content://private/image")
            .put("modelPath", "/private/files/model.bin")
            .put("nativeStageMask", 255L)
            .put(
                "nativeEffective",
                JSONObject()
                    .put("runtime", "/private/runtime")
                    .put("path", "/private/native/output.png")
                    .put("nativeGenerationSequence", 17L)
                    .put("physicalComputeCount", 12L)
                    .put("physicalTileComputeCount", 12L)
                    .put("tiledExecution", true)
            )

        val raw = localImageWorkerCompletionLogSummary(
            requestId = "request-safe",
            workerPid = 1234,
            operation = "ESRGAN_UPSCALE",
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            outputCount = 1,
            outputBytes = 4096L,
            executionMetadataJson = metadata.toString()
        )
        val summary = JSONObject(raw)

        assertEquals("request-safe", summary.getString("requestId"))
        assertEquals("ESRGAN_UPSCALE", summary.getString("operation"))
        assertEquals("STABLE_DIFFUSION_CPP", summary.getString("runtime"))
        assertEquals(17L, summary.getLong("nativeGenerationSequence"))
        assertEquals(255L, summary.getLong("nativeStageMask"))
        assertEquals(12L, summary.getLong("physicalComputeCount"))
        assertEquals(12L, summary.getLong("physicalTileComputeCount"))
        assertTrue(summary.getBoolean("tiledExecution"))
        listOf("path", "prompt", "contentUri", "modelPath", "execution").forEach { field ->
            assertFalse(summary.has(field))
        }
        assertFalse(raw.contains("private", ignoreCase = true))
        assertFalse(raw.contains("content://", ignoreCase = true))
    }

    @Test
    fun workerSuccessLogsNeverEmbedRawExecutionMetadata() {
        val source = serviceSource()

        assertTrue(source.contains("localImageWorkerCompletionLogSummary("))
        assertFalse(source.contains(".put(\"execution\","))
        assertFalse(source.contains("JSONObject(result.executionMetadataJson)"))
        assertFalse(source.contains("Log.i(\"MCA-LocalImage\", result.executionMetadataJson"))
        assertFalse(source.contains("Log.w(\"MCA-LocalImage\", \"Unable to"))
        assertTrue(source.contains("logLocalImageWorkerInternalFailure("))
    }

    private fun serviceSource(): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            listOf(
                File(root, "src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/LocalImageWorkerService.kt")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate LocalImageWorkerService.kt")
    }
}
