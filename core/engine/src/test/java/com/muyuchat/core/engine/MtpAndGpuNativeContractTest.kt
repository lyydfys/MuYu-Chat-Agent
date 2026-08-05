package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtpAndGpuNativeContractTest {
    @Test
    fun nativeMtpUsesMetadataAndStep35GraphSupportInsteadOfModelHashGating() {
        val native = nativeSource()

        assertTrue(native.contains("bool model_supports_requested_mtp("))
        assertTrue(native.contains("mtp_layers == 1"))
        assertTrue(native.contains("lowercase_trimmed_ascii(architecture) == \"step35\""))
        assertFalse(native.contains("g_spec_requested && g_model_mtp_layers != 1"))
    }

    @Test
    fun nativeMtpCancellationAndPrefillFailureClearBothContexts() {
        val native = nativeSource()

        assertTrue(native.contains("stop_speculative_request_if_requested_locked"))
        assertTrue(native.contains("invalidate_speculative_contexts_locked(\"stopped\")"))
        assertTrue(native.contains("invalidate_speculative_contexts_locked(\"prefill_failed\")"))
        assertTrue(native.contains("stop_speculative_request_if_requested_locked();"))
    }

    @Test
    fun gpuLabelRequiresAllocationAndSuccessfulDecodeEvidence() {
        val native = nativeSource()

        assertTrue(native.contains("llama_get_memory_breakdown(g_context)"))
        assertTrue(native.contains("ggml_backend_buft_get_device(buffer_type)"))
        assertTrue(native.contains("g_gpu_offload_model_bytes > 0"))
        assertTrue(native.contains("g_gpu_offload_context_bytes > 0 || g_gpu_offload_compute_bytes > 0"))
        assertTrue(native.contains("record_gpu_decode_execution_locked"))
        assertTrue(native.contains("g_gpu_offload_allocation_observed &&"))
        assertTrue(native.contains("g_gpu_offload_execution_observed"))
        assertTrue(native.contains("g_gpu_offload_layers = -1"))
        assertTrue(native.contains("\\\"gpuOffloadActive\\\""))
        assertTrue(native.contains("stats_json(g_gpu_offload_active ? \"llama.cpp-gpu\" : \"llama.cpp-cpu\")"))
    }

    @Test
    fun gpuAutoModeFallsBackToCpuOnlyForLoadOrContextInitializationFailures() {
        val native = nativeSource()

        assertTrue(native.contains("requested.n_gpu_layers == -1"))
        assertTrue(native.contains("fallback_to_cpu(\"model_load\")"))
        assertTrue(native.contains("fallback_to_cpu(context_rc == 2 ? \"context_create\" : \"mtp_context_create\")"))
        assertTrue(native.contains("effective.n_gpu_layers = 0"))
        assertTrue(native.contains("g_gpu_auto_fallback_applied = true"))
    }

    @Test
    fun runtimeStatsNeverTreatsAllocationAloneAsGpuExecution() {
        assertFalse(
            RuntimeStats(
                gpuOffloadActive = true,
                gpuOffloadAllocationObserved = true,
                gpuOffloadExecutionObserved = false
            ).hasVerifiedGpuExecution
        )
        assertTrue(
            RuntimeStats(
                gpuOffloadActive = true,
                gpuOffloadAllocationObserved = true,
                gpuOffloadExecutionObserved = true
            ).hasVerifiedGpuExecution
        )
    }

    private fun nativeSource(): String = sourceFile("core/native/src/main/cpp/native_engine.cpp")

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
