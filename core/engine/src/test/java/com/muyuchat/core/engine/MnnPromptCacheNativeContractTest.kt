package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the MNN text prompt-cache transaction. */
class MnnPromptCacheNativeContractTest {
    @Test
    fun textRuntimeEnablesPromptCacheButMediaRequestsTurnItOff() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val config = functionBody(native, "json build_mnn_config(")

        assertTrue(config.contains("config[\"reuse_kv\"] = true;"))
        assertTrue(config.contains("config[\"prompt_cache\"] = true;"))
        assertTrue(native.contains("const bool textOnlyRequest = !parsed.hasMediaInputs();"))
        assertTrue(native.contains("config[\"reuse_kv\"] = textOnlyRequest;"))
        assertTrue(native.contains("config[\"prompt_cache\"] = textOnlyRequest;"))
        assertTrue(native.contains("mark_mnn_prompt_cache_disabled_locked("))
    }

    @Test
    fun successfulTerminationCommitsAndCancellationRollsBack() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val commit = functionBody(native, "void commit_mnn_text_prompt_cache_locked(")
        val rollback = functionBody(native, "void rollback_mnn_text_prompt_cache_locked(")

        assertTrue(commit.contains("g_llm->syncPromptCache(committed)"))
        assertTrue(commit.contains("g_mnn_prompt_cache.committed = true"))
        assertTrue(rollback.contains("g_llm->eraseHistory(g_mnn_prompt_cache.kv_history_before, 0)"))
        assertTrue(rollback.contains("reset_mnn_native_prompt_cache_locked()"))
        assertTrue(native.contains("settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);"))
        assertTrue(native.contains("rollback_mnn_text_prompt_cache_locked(\"stop_requested\", false);"))
    }

    @Test
    fun nativeEvidenceReportsActualReuseAndPrefillCounts() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val capture = functionBody(native, "bool capture_mnn_text_prefill_locked(")

        assertTrue(capture.contains("mnn_effective_kv_history_locked()"))
        assertTrue(capture.contains("g_mnn_prompt_cache.reused_tokens"))
        assertTrue(capture.contains("g_mnn_prompt_cache.prefilled_tokens"))
        assertTrue(
            native.contains(
                "g_mnn_prompt_cache.reused_tokens + g_mnn_prompt_cache.prefilled_tokens",
            ),
        )
        assertTrue(native.contains("\\\"promptCache\\\""))
        assertTrue(native.contains("\\\"reusedTokens\\\""))
        assertTrue(native.contains("\\\"prefillTokens\\\""))
        assertTrue(native.contains("\\\"promptCacheHit\\\""))
    }

    @Test
    fun prefixChangesAndMultimodalTransitionsInvalidateKnownTranscript() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val disabled = functionBody(native, "void mark_mnn_prompt_cache_disabled_locked(")
        assertTrue(native.contains("mnn_chat_messages_prefix("))
        assertTrue(native.contains("g_mnn_prompt_cache.prefix_extended"))
        assertTrue(native.contains("reset_before_next_text"))
        assertTrue(native.contains("multimodal_request_requires_fresh_visual_prefill"))
        assertTrue(disabled.contains("g_mnn_prompt_cache = MnnPromptCacheRuntimeState()"))
        assertFalse(native.contains("config[\"prompt_cache\"] = false;"))
    }

    @Test
    fun vendorReuseUsesLiveTokenLcpAndBridgeSnapshotsEffectiveKv() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val vendor = sourceFile("third_party/MNN/transformers/llm/engine/src/llm.cpp")
        val prepare = functionBody(native, "void prepare_mnn_text_prompt_cache_locked(")
        val rollback = functionBody(native, "void rollback_mnn_text_prompt_cache_locked(")

        assertTrue(native.contains("getContext()->all_seq_len"))
        assertTrue(prepare.contains("mnn_effective_kv_history_locked()"))
        assertTrue(rollback.contains("mnn_effective_kv_history_locked()"))
        assertTrue(vendor.contains("promptCacheReusableTokenPrefix("))
        assertTrue(vendor.contains("effective_kv_tokens"))
        assertFalse(vendor.contains("size_t prefix_count = tokenizer_encode(\"\").size();"))
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureIndex = source.indexOf(signature)
        check(signatureIndex >= 0) { "Missing native function: $signature" }
        val openingBrace = source.indexOf('{', signatureIndex)
        check(openingBrace >= 0) { "Missing native function body: $signature" }
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
        error("Unterminated native function: $signature")
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
