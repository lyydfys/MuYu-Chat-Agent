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
        assertTrue(native.contains("computed_prefill_tokens * 1000.0 / prefill_ms"))
        assertTrue(native.contains("\\\"effectivePromptTps\\\""))
        assertTrue(native.contains("\\\"promptCacheHit\\\""))
    }

    @Test
    fun emptyVisibleResponseRollsBackBeforeCommitAndHistoryUsesAbsoluteBoundary() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val settle = functionBody(native, "void settle_mnn_text_prompt_cache_locked(")
        assertTrue(settle.contains("has_non_whitespace(mnn_prompt_cache_assistant_text_locked())"))
        assertTrue(settle.contains("rollback_mnn_text_prompt_cache_locked(\"empty_visible_response\", true)"))
        assertTrue(settle.indexOf("empty_visible_response") < settle.indexOf("commit_mnn_text_prompt_cache_locked(reason)"))
        val vendor = sourceFile("third_party/MNN/transformers/llm/engine/src/llm.cpp")
        val erase = functionBody(vendor, "void Llm::eraseHistory(")
        assertTrue(erase.contains("trimPromptTokenHistory(mContext->history_tokens, begin)"))
        assertTrue(erase.contains("mMeta->n_reserve = 0"))
        assertTrue(erase.contains("mMeta->reserve = nullptr"))
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
    fun recurrentReuseAlignsStateAndRollbackRetainsObservedGenerationEvidence() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val rollback = functionBody(native, "void rollback_mnn_text_prompt_cache_locked(")
        assertTrue(rollback.contains("completion_tokens_before_rollback"))
        assertFalse(rollback.contains("g_mnn_prompt_cache.hit = false"))
        val vendor = sourceFile("third_party/MNN/transformers/llm/engine/src/llm.cpp")
        assertTrue(vendor.contains("token_split = recurrentReusablePrefix("))
        assertTrue(vendor.contains("mMeta->recurrentCheckpoint"))
        assertTrue(vendor.contains("checkpointPrefillChunks(input_ids.size(), mBlockSize)"))
        val activity = sourceFile("app/src/debug/java/com/muyuchat/mca/debug/LocalChatSmokeActivity.kt")
        val compare = activity.substringAfter("\"mnn_cache_ab\" -> {").substringBefore("val cacheAbSucceeded")
        assertTrue(compare.contains("requestMessages = secondMessages, allowGenerationFailure = true"))
        assertTrue(compare.contains("timedUnload(activeEngine)"))
        assertTrue(compare.contains("cacheAbResult.put(\"coldControl\", coldControl)"))
    }

    @Test
    fun vendorReuseUsesLiveTokenLcpAndBridgeSnapshotsEffectiveKv() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val vendor = sourceFile("third_party/MNN/transformers/llm/engine/src/llm.cpp")
        val prepare = functionBody(native, "void prepare_mnn_text_prompt_cache_locked(")
        val rollback = functionBody(native, "void rollback_mnn_text_prompt_cache_locked(")

        assertTrue(native.contains("g_llm->getCurrentHistory()"))
        assertTrue(prepare.contains("mnn_effective_kv_history_locked()"))
        assertTrue(rollback.contains("mnn_effective_kv_history_locked()"))
        assertTrue(vendor.contains("promptCacheReusableTokenPrefix("))
        assertTrue(vendor.contains("effective_kv_tokens"))
        assertFalse(vendor.contains("size_t prefix_count = tokenizer_encode(\"\").size();"))
    }

    @Test
    fun persistedTranscriptNormalizationStillDefersReuseAmountToTokenLcp() {
        val native = sourceFile("core/native/src/main/cpp/mnn_native_engine.cpp")
        val prefix = functionBody(native, "bool mnn_chat_messages_prefix(")
        val prepare = functionBody(native, "void prepare_mnn_text_prompt_cache_locked(")

        assertTrue(prefix.contains("ZERO WIDTH SPACE"))
        assertTrue(prefix.contains("normalize(prefix[index].second)"))
        assertTrue(prepare.contains("g_llm->syncPromptCache(g_mnn_prompt_cache.committed_messages)"))
        assertTrue(prepare.contains("extendsCommittedTranscript"))
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
