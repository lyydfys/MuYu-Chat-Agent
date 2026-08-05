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
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp")

        assertTrue(native.contains("\\\"loadFailureCode\\\""))
        assertTrue(native.contains("probe_gguf_header"))
        assertTrue(native.contains("load_failure_code_from_llama_error"))
        assertTrue(native.contains("NativeLlamaBridge_invalidateTextContext"))
        assertTrue(native.contains("invalidated_by_conversation_edit"))
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
