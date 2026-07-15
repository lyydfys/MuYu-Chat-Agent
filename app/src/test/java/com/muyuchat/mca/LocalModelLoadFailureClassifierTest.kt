package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun genericGgufFailureDoesNotImplyFileCorruption() {
        val result = LocalModelLoadFailureClassifier.classify(
            "GGUF load failed: llama_init_from_model returned null",
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
}
