package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPrefixNativeContractTest {
    @Test
    fun bridgeAndNativeExposeThePersistentPrefixJniEntryPoint() {
        val bridge = sourceFile(
            "core/native/src/main/java/com/muyuchat/core/nativebridge/NativeLlamaBridge.kt"
        )
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp")

        assertTrue(bridge.contains("external fun beginCompletionWithPrefixCache("))
        assertTrue(
            native.contains(
                "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletionWithPrefixCache("
            )
        )
        assertTrue(native.contains("g_thread_prefix_cache_request.restore_state_path"))
        assertTrue(native.contains("g_thread_prefix_cache_request.write_state_path"))
        assertTrue(native.contains("g_thread_prefix_cache_request.fixed_system_prompt"))
    }

    @Test
    fun persistentPrefixUsesCompleteSequenceStateFileApis() {
        val body = persistentPrefixPreparationBody()

        assertTrue(
            "Restore must provide the sequence id, token output buffer, capacity, and restored count.",
            Regex(
                """llama_state_seq_load_file\s*\(\s*g_context\s*,\s*request\.restore_state_path\.c_str\(\)\s*,\s*0\s*,\s*restored\.data\(\)\s*,\s*restored\.size\(\)\s*,\s*&restored_count\s*\)"""
            ).containsMatchIn(body)
        )
        assertTrue(
            "Save must persist sequence zero together with its complete prefix token list.",
            Regex(
                """llama_state_seq_save_file\s*\(\s*g_context\s*,\s*request\.write_state_path\.c_str\(\)\s*,\s*0\s*,\s*prefix_tokens\.data\(\)\s*,\s*prefix_tokens\.size\(\)\s*\)"""
            ).containsMatchIn(body)
        )
    }

    @Test
    fun restoredStateRequiresExactTokenCountAndFullPromptPrefixMatch() {
        val body = persistentPrefixPreparationBody()
        val policy = sourceFile("core/native/src/main/cpp/llama_prefix_cache_policy.hpp")

        assertTrue(body.contains("size_t restored_count = 0;"))
        assertTrue(
            Regex("""prefix_tokens\.size\(\)\s*,\s*restored_count\s*,""")
                .containsMatchIn(body)
        )
        assertTrue(body.contains("mca::llama::tokenPrefixMatches(full_tokens, restored)"))
        assertTrue(body.contains("full_prompt_prefix_matches"))
        assertTrue(policy.contains("restoredTokens == expectedTokens"))
        assertTrue(policy.contains("restoredTokensMatch &&"))
        assertTrue(policy.contains("fullPromptPrefixMatches;"))
    }

    @Test
    fun persistentPrefixDerivesAStableTokenBoundaryFromTheCompleteProbePrompt() {
        val body = persistentPrefixPreparationBody()

        assertTrue(body.contains("common_tokenize(g_context, probe_formatted, true, true)"))
        assertTrue(body.contains("mca::llama::longestCommonTokenPrefix("))
        assertTrue(body.contains("full_tokens,"))
        assertTrue(body.contains("probe_tokens"))
    }

    @Test
    fun liveConversationKvIsTriedBeforePersistentPrefixFallback() {
        val beginBody = functionBody(
            source = sourceFile("core/native/src/main/cpp/native_engine.cpp"),
            signature = "Java_com_muyuchat_core_nativebridge_NativeLlamaBridge_beginCompletion("
        )
        val sessionAttempt = beginBody.indexOf("reused = prepare_text_prefix_locked(tokens);")
        val persistentFallback = beginBody.indexOf("shouldAttemptPersistentPrefixFallback(")

        assertTrue("The native request must try the live session KV state.", sessionAttempt >= 0)
        assertTrue(
            "The disk-backed fixed prefix must run only after a session-cache miss.",
            persistentFallback > sessionAttempt
        )
    }

    private fun persistentPrefixPreparationBody(): String = functionBody(
        source = sourceFile("core/native/src/main/cpp/native_engine.cpp"),
        signature = "PersistentPrefixPreparation prepare_persistent_prefix_locked("
    )

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
        error("Unterminated function body: $signature")
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
