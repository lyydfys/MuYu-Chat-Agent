package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelLoadFailureClassifierTest {
    @Test
    fun cpuGpuConflictIsNotReportedAsCorruptGguf() {
        val result = LocalModelLoadFailureClassifier.classify(
            "loadModel failed: n_cpu_moe requires a usable GPU offload backend, but none is registered. model.gguf",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG, result.kind)
        assertFalse(result.userMessage.contains("不完整"))
    }

    @Test
    fun gpuDiagnosticExplainsMissingOpenClInsteadOfCallingApkCpuOnly() {
        val result = LocalModelLoadFailureClassifier.classify(
            "n_gpu_layers requests GPU offload, but this APK has no usable non-CPU llama.cpp backend.",
            """
                {
                  "backendReady": true,
                  "backendLoadDiagnostic": "Android OpenCL runtime unavailable: dlopen(libOpenCL.so) failed",
                  "backendCapabilities": {"gpuOffloadSupported": false, "gpuDeviceCount": 0}
                }
            """.trimIndent()
        )

        assertEquals(LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG, result.kind)
        assertTrue(result.userMessage.contains("libOpenCL.so"))
        assertFalse(result.userMessage.contains("CPU-only APK"))
    }

    @Test
    fun gpuDiagnosticExplainsBackendRegistrationFailure() {
        val result = LocalModelLoadFailureClassifier.classify(
            "n_gpu_layers requests GPU offload, but this APK has no usable non-CPU llama.cpp backend.",
            """
                {
                  "backendLoadDiagnostic": "MCA OpenCL backend failed to register",
                  "backendCapabilities": {"gpuOffloadSupported": false, "gpuDeviceCount": 0}
                }
            """.trimIndent()
        )

        assertEquals(LocalModelLoadFailureKind.UNSUPPORTED_RUNTIME_CONFIG, result.kind)
        assertTrue(result.userMessage.contains("注册失败"))
    }

    @Test
    fun explicitContextCreationFailureDoesNotImplyFileCorruption() {
        val result = LocalModelLoadFailureClassifier.classify(
            "GGUF load failed: llama_init_from_model returned null",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.CONTEXT_CREATION_FAILED, result.kind)
    }

    @Test
    fun genericGgufLoaderFailureDoesNotImplyFileCorruption() {
        val result = LocalModelLoadFailureClassifier.classify(
            "GGUF load failed: llama_model_load_from_file returned null",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE, result.kind)
    }

    @Test
    fun explicitInvalidMagicIsFileIntegrityFailure() {
        val result = LocalModelLoadFailureClassifier.classify(
            "invalid magic in GGUF header",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.FILE_INTEGRITY, result.kind)
    }

    @Test
    fun loadBoundMismatchExplainsReload() {
        val result = LocalModelLoadFailureClassifier.classify(
            "Completion config changes load-bound fields; reload the model before generating.",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.LOAD_SIGNATURE_MISMATCH, result.kind)
    }

    @Test
    fun structuredNativeCodesClassifyEveryLoadFailureBranch() {
        val cases = listOf(
            "MCA_LOAD_FILE_UNREADABLE" to LocalModelLoadFailureKind.FILE_UNREADABLE,
            "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED" to LocalModelLoadFailureKind.FILE_INTEGRITY,
            "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID" to
                LocalModelLoadFailureKind.GGUF_METADATA_OR_ARCHITECTURE_INVALID,
            "MCA_LOAD_GGUF_NOT_AUTOREGRESSIVE_CHAT" to
                LocalModelLoadFailureKind.NON_AUTOREGRESSIVE_CHAT,
            "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION" to
                LocalModelLoadFailureKind.UNSUPPORTED_QUANTIZATION_OR_OPERATION,
            "MCA_LOAD_CONTEXT_CREATION_FAILED" to
                LocalModelLoadFailureKind.CONTEXT_CREATION_FAILED,
            "MCA_LOAD_OUT_OF_MEMORY" to LocalModelLoadFailureKind.MEMORY_PRESSURE,
            "MCA_LOAD_SMOKE_EXECUTION_FAILED" to
                LocalModelLoadFailureKind.SMOKE_EXECUTION_FAILED,
            "MCA_LOAD_TOKENIZER_OR_TEMPLATE_INVALID" to
                LocalModelLoadFailureKind.TOKENIZER_OR_TEMPLATE_INVALID,
            "MCA_LOAD_WORKER_TIMEOUT" to LocalModelLoadFailureKind.WORKER_TIMEOUT,
            "MCA_LOAD_WORKER_PROCESS_CRASHED" to LocalModelLoadFailureKind.WORKER_PROCESS_CRASH
        )

        cases.forEach { (code, expected) ->
            val result = LocalModelLoadFailureClassifier.classify(
                message = "invalid magic; unsupported quantization; out of memory",
                nativeStatsJson = """{"loadFailureCode":"$code","lastError":"unrelated legacy text"}"""
            )

            assertEquals(code, expected, result.kind)
            assertTrue(result.diagnosticDetail.contains("loadFailureCode="))
        }
    }

    @Test
    fun stableErrorPrefixIsUsedWhenOlderNativeStatsHaveNoCodeField() {
        val result = LocalModelLoadFailureClassifier.classify(
            "Native loadModel failed: 18 [MCA_LOAD:GGUF_NOT_AUTOREGRESSIVE_CHAT]",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.NON_AUTOREGRESSIVE_CHAT, result.kind)
    }

    @Test
    fun actualCanaryFailureHasItsOwnClassificationWhenNoNativeCodeIsAvailable() {
        val result = LocalModelLoadFailureClassifier.classify(
            "安全基线正确性校准失败：短流式生成没有产生可见正文",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.SMOKE_EXECUTION_FAILED, result.kind)
    }

    @Test
    fun unknownDeviceLanguageNeverBecomesAnAdmissionFailure() {
        val result = LocalModelLoadFailureClassifier.classify(
            "unknown chipset; untested device; no local certification profile",
            "{}"
        )

        assertEquals(LocalModelLoadFailureKind.NATIVE_LOAD_FAILURE, result.kind)
    }

    @Test
    fun tokenizerTemplateAndWorkerFailuresAreStructuredWithoutRawPromptOrPath() {
        val tokenizer = LocalModelLoadFailureClassifier.classify(
            "tokenizer mismatch: prompt=private text path=/data/user/0/com.example/model.gguf",
            "{}"
        )
        assertEquals(LocalModelLoadFailureKind.TOKENIZER_OR_TEMPLATE_INVALID, tokenizer.kind)
        assertFalse(tokenizer.diagnosticDetail.contains("private text"))
        assertFalse(tokenizer.diagnosticDetail.contains("/data/user"))

        val timeout = LocalModelLoadFailureClassifier.classify(
            "worker_watchdog_timeout at smoke",
            "{}"
        )
        assertEquals(LocalModelLoadFailureKind.WORKER_TIMEOUT, timeout.kind)

        val crashed = LocalModelLoadFailureClassifier.classify(
            "worker_process_crashed: SIGSEGV",
            "{}"
        )
        assertEquals(LocalModelLoadFailureKind.WORKER_PROCESS_CRASH, crashed.kind)
    }

    @Test
    fun nativeStatsPublishStableGgufLoadFailureCodeContract() {
        // The GGUF header probe and the llama error -> failure-code mapping were extracted out of
        // native_engine.cpp / llm_stats_collector.cpp into mca/llm; the assertions below are
        // unchanged, only the set of files they are read from follows the move.
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llm_stats_collector.cpp") }.getOrDefault("") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_model_loader.cpp") }.getOrDefault("") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_model_loader.hpp") }.getOrDefault("") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_runtime_config.cpp") }.getOrDefault("") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llm_runtime_config.cpp") }.getOrDefault("") +
            // The llama error -> failure-code mapping is defined in this header (mca::llama) and called
            // from llama_model_loader.cpp; it used to be a same-named forwarding shell inside
            // native_engine.cpp.  The assertions below are unchanged in meaning.
            runCatching { sourceFile("core/native/src/main/cpp/llama_load_failure_policy.hpp") }.getOrDefault("")

        assertTrue(native.contains("\\\"loadFailureCode\\\""))
        assertTrue(native.contains("probe_gguf_header"))
        // Renamed by the mca/llm extraction: the reference repository's snake_case symbol was itself
        // a zero-logic forwarding shell to this same implementation, and both repositories' policy
        // headers are byte-identical, so this follows the name without changing what is asserted.
        assertTrue(native.contains("loadFailureCodeFromLlamaError"))
        assertTrue(native.contains("NativeLlamaBridge_invalidateTextContext"))
        // The JNI invalidation path (native_engine.cpp, NativeLlamaBridge_invalidateTextContext) still
        // clears the target context and invalidates the speculative contexts, but it now reports the
        // reason as "context_invalidated_by_caller" instead of the old conversation-edit label.  The
        // behaviour under test -- a conversation mutation must not reuse the old token tail -- is the
        // same; only the diagnostic label moved.
        assertTrue(native.contains("context_invalidated_by_caller"))
        listOf(
            "MCA_LOAD_FILE_UNREADABLE",
            "MCA_LOAD_GGUF_CORRUPT_OR_TRUNCATED",
            "MCA_LOAD_GGUF_METADATA_OR_ARCHITECTURE_INVALID",
            "MCA_LOAD_GGUF_NOT_AUTOREGRESSIVE_CHAT",
            "MCA_LOAD_BUILD_UNSUPPORTED_QUANTIZATION_OR_OPERATION",
            "MCA_LOAD_CONTEXT_CREATION_FAILED",
            "MCA_LOAD_OUT_OF_MEMORY"
        ).forEach { code -> assertTrue("Missing native code: $code", native.contains(code)) }
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
