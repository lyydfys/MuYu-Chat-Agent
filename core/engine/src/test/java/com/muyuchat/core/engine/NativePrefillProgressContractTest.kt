package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePrefillProgressContractTest {
    @Test
    fun bridgeExposesALockFreeExactPrefillSnapshot() {
        val bridge = sourceFile(
            "core/native/src/main/java/com/muyuchat/core/nativebridge/NativeLlamaBridge.kt"
        )
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp")

        assertTrue(bridge.contains("external fun getPrefillProgressJson(): String"))
        assertTrue(bridge.contains("external fun resetPrefillProgress()"))
        assertTrue(
            native.contains(
                "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_getPrefillProgressJson"
            )
        )
        assertTrue(
            native.contains(
                "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_resetPrefillProgress"
            )
        )
        assertTrue(native.contains("g_prefill_progress_completed_tokens"))
        assertTrue(native.contains("g_prefill_progress_total_tokens"))
    }

    @Test
    fun textPromptInitializesTotalAndAdvancesAfterEachDecodedBatch() {
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp")
        val decode = functionBody(native, "int decode_tokens(")
        val begin = functionBody(
            native,
            "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion("
        )

        assertTrue(decode.contains("advance_prefill_progress((size_t) batch_size)"))
        assertTrue(begin.contains("begin_prefill_progress(tokens.size())"))
        assertTrue(begin.contains("report_reused_prefill_tokens(reused)"))
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        check(signatureIndex >= 0) { "Missing function: $signature" }
        val openingBrace = source.indexOf('{', signatureIndex)
        check(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
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
