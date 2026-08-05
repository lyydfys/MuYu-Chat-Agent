package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatWorkerLoadFailureStatsTest {
    @Test
    fun structuredCodeAndBoundedErrorSurviveNativeRunnerCleanupSnapshot() {
        val modelPath = "/data/user/0/com.muyuchat/files/private/model.gguf"
        val snapshot = JSONObject(
            LocalChatWorkerLoadFailureStats.capture(
                runtime = LocalChatRuntime.LLAMA_CPP,
                nativeLoadResult = -202,
                nativeStatsJson = JSONObject()
                    .put("loadFailureCode", "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION")
                    .put(
                        "lastError",
                        "unsupported tensor type at $modelPath ${"detail ".repeat(1_000)}"
                    )
                    .put("modelPath", modelPath)
                    .put("unboundedNativeField", "x".repeat(20_000))
                    .toString(),
                modelPath = modelPath
            )
        )

        assertEquals("llama_cpp", snapshot.getString("backend"))
        assertFalse(snapshot.getBoolean("loaded"))
        assertTrue(snapshot.getBoolean("runnerReady"))
        assertEquals(-202, snapshot.getInt("nativeLoadResult"))
        assertEquals(
            "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION",
            snapshot.getString("loadFailureCode")
        )
        assertTrue(snapshot.getString("lastError").contains("unsupported tensor type"))
        assertTrue(snapshot.getString("lastError").length <= 2_000)
        assertFalse(snapshot.toString().contains(modelPath))
        assertFalse(snapshot.has("unboundedNativeField"))
    }

    @Test
    fun malformedNativeStatsStillProduceAConcreteBoundedFailure() {
        val snapshot = JSONObject(
            LocalChatWorkerLoadFailureStats.capture(
                runtime = LocalChatRuntime.MNN_CPU,
                nativeLoadResult = -7,
                nativeStatsJson = "not-json",
                modelPath = "/models/broken/config.json"
            )
        )

        assertEquals("mnn_cpu", snapshot.getString("backend"))
        assertEquals(-7, snapshot.getInt("nativeLoadResult"))
        assertEquals("Native loadModel failed with result -7.", snapshot.getString("lastError"))
        assertFalse(snapshot.has("loadFailureCode"))
    }

    @Test
    fun retainedSnapshotStillDrivesTheUiLoadFailureClassification() {
        val snapshot = LocalChatWorkerLoadFailureStats.capture(
            runtime = LocalChatRuntime.LLAMA_CPP,
            nativeLoadResult = -202,
            nativeStatsJson = JSONObject()
                .put("loadFailureCode", "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION")
                .put("lastError", "unsupported tensor type IQ4_XS")
                .toString(),
            modelPath = "/models/quantized/model.gguf"
        )

        val failure = LocalModelLoadFailureClassifier.classify(
            message = "Native loadModel failed: -202",
            nativeStatsJson = snapshot
        )

        assertEquals(
            LocalModelLoadFailureKind.UNSUPPORTED_QUANTIZATION_OR_OPERATION,
            failure.kind
        )
        assertTrue(failure.diagnosticDetail.contains("loadFailureCode="))
    }

    @Test
    fun workerCapturesFailureBeforeUnloadAndPublishesItWithoutAnActiveRunner() {
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt")
        val capture = service.indexOf("LocalChatWorkerLoadFailureStats.capture(")
        val unload = service.indexOf("runCatching { runner.unloadModel() }", capture)
        val detach = service.indexOf("activeRunner = null", unload)
        val publish = service.indexOf("retainedLoadFailureStatsJson = failureStatsJson", detach)

        assertTrue(capture >= 0)
        assertTrue(unload > capture)
        assertTrue(detach > unload)
        assertTrue(publish > detach)
        assertTrue(service.contains("?: retainedLoadFailureStatsJson"))
        assertTrue(service.contains("retainedLoadFailureStatsJson = null\n            prepareLoadDiagnostic"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}
