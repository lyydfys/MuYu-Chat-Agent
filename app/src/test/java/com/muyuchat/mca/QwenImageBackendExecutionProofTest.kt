package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.path.writeText

class QwenImageBackendExecutionProofTest {
    @Test
    fun acceptsNativeOpenClProofOnlyWhenEffectiveBackendMatches() {
        verifyQwenNativeBackendExecutionEvidence(
            audit = audit("MNN_OPENCL"),
            expectedBackend = "MNN_OPENCL"
        )
    }

    @Test
    fun acceptsNativeCpuProof() {
        verifyQwenNativeBackendExecutionEvidence(
            audit = audit("MNN_CPU"),
            expectedBackend = "MNN_CPU"
        )
    }

    @Test
    fun rejectsConfiguredOpenClThatActuallyResolvedToCpu() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyQwenNativeBackendExecutionEvidence(
                audit = audit("MNN_CPU"),
                expectedBackend = "MNN_OPENCL"
            )
        }
    }

    @Test
    fun rejectsMissingNativeProofEvenWhenBackendNameMatches() {
        assertThrows(IllegalArgumentException::class.java) {
            verifyQwenNativeBackendExecutionEvidence(
                audit = JSONObject()
                    .put("effectiveBackend", "MNN_OPENCL")
                    .put("nativeRunCompleted", true)
                    .put("backendExecutionConfirmedByNative", false)
                    .put("backendExecutionProof", "mnn_runtime_resolution_after_native_run")
                    .put("nativeGenerationCount", 1),
                expectedBackend = "MNN_OPENCL"
            )
        }
    }

    @Test
    fun bundleFingerprintIgnoresRuntimeCachesAndTempFiles() {
        val root = Files.createTempDirectory("qwen-bundle-").toFile()
        try {
            File(root, "manifest.json").writeText("{}")
            File(root, "dit.mnn").writeText("model")
            val first = qwenImage21BundleFingerprint(root, "pin")
            File(root, ".mnn_cl_cache").mkdirs()
            File(root, ".mnn_cl_cache/program.bin").writeText("compiled")
            File(root, "download.tmp").writeText("partial")
            assertEquals(first, qwenImage21BundleFingerprint(root, "pin"))
            File(root, "dit.mnn").writeText("model changed")
            assertNotEquals(first, qwenImage21BundleFingerprint(root, "pin"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun audit(effectiveBackend: String): JSONObject = JSONObject()
        .put("runtime", "MNN_DIFFUSION")
        .put("nativeExecution", true)
        .put("fallback", false)
        .put("nativeGenerationSequence", 1)
        .put("nativeRunCompleted", true)
        .put("backendExecutionConfirmedByNative", true)
        .put("backendExecutionProof", "mnn_runtime_resolution_after_native_run")
        .put("requestedBackend", effectiveBackend)
        .put("effectiveBackend", effectiveBackend)
        .put("nativeGenerationCount", 1)
        .put(
            "nativeEffective",
            JSONObject()
                .put("runtime", "MNN_DIFFUSION")
                .put("nativeExecution", true)
                .put("fallback", false)
                .put("nativeGenerationSequence", 1)
                .put("nativeRunCompleted", true)
                .put("backendExecutionConfirmedByNative", true)
                .put("effectiveBackend", effectiveBackend)
        )
}
