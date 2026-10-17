package com.muyuchat.core.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the MNN text prompt-cache transaction. */
class MnnPromptCacheNativeContractTest {
    @Test
    fun textRuntimeEnablesPromptCacheButMediaRequestsTurnItOff() {
        val native = nativeSource()
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
        val native = nativeSource()
        val manager = managerSource()
        val commit = functionBody(manager, "void MnnPromptCacheManager::commit_text_cache(")
        val rollback = functionBody(manager, "void MnnPromptCacheManager::rollback_text_cache(")
        val bridgeRollback = functionBody(native, "void rollback_mnn_text_prompt_cache_locked(")

        // The commit path must hand the committed transcript to the backend and latch the committed flag.
        assertTrue(commit.contains("llm->syncPromptCache(committed);"))
        assertTrue(commit.contains("cache.committed = true;"))
        // Rollback must truncate the backend history at the recorded absolute boundary.
        assertTrue(rollback.contains("llm->eraseHistory(cache.kv_history_before, 0);"))
        assertTrue(bridgeRollback.contains("reset_mnn_native_prompt_cache_locked()"))
        assertTrue(native.contains("settle_mnn_text_prompt_cache_locked(g_generation_stop_reason);"))
        assertTrue(native.contains("rollback_mnn_text_prompt_cache_locked(\"stop_requested\", false);"))
    }

    @Test
    fun nativeEvidenceReportsActualReuseAndPrefillCounts() {
        val native = nativeSource()
        val manager = managerSource()
        val capture = functionBody(manager, "bool MnnPromptCacheManager::capture_text_prefill(")

        assertTrue(native.contains("mnn_effective_kv_history_locked()"))
        assertTrue(capture.contains("cache.reused_tokens"))
        assertTrue(capture.contains("cache.prefilled_tokens"))
        assertTrue(
            manager.contains(
                "cache.prefilled_tokens + cache.reused_tokens",
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
        val native = nativeSource()
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
        val native = nativeSource()
        val manager = managerSource()
        val disabled = functionBody(manager, "void MnnPromptCacheManager::mark_disabled(")
        assertTrue(native.contains("mnn_chat_messages_prefix("))
        assertTrue(manager.contains("cache.prefix_extended"))
        assertTrue(manager.contains("reset_before_next_text"))
        assertTrue(native.contains("multimodal_request_requires_fresh_visual_prefill"))
        // Invalidation must drop the whole cached transaction, not just a flag.
        assertTrue(disabled.contains("cache = MnnPromptCacheRuntimeState();"))
        assertFalse(native.contains("config[\"prompt_cache\"] = false;"))
    }

    @Test
    fun recurrentReuseAlignsStateAndRollbackRetainsObservedGenerationEvidence() {
        val native = nativeSource()
        val manager = managerSource()
        val rollback = functionBody(manager, "void MnnPromptCacheManager::rollback_text_cache(")
        assertTrue(rollback.contains("completion_tokens_before_rollback"))
        assertFalse(rollback.contains("cache.hit = false"))
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
        val native = nativeSource()
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
        val native = nativeSource()
        val manager = managerSource()
        val parser = parserSource()
        val prefix = functionBody(parser, "bool mnn_chat_messages_prefix(")
        val prepare = functionBody(manager, "void MnnPromptCacheManager::prepare_text_cache(")

        // The real ZWSP literal ("\xE2\x80\x8B" == U+200B); the bare words "ZERO WIDTH SPACE" only ever
        // existed in a comment, so asserting them proved nothing about the code.  The literal lives in
        // the parser's normalization table (file scope), not inside mnn_chat_messages_prefix.
        assertTrue(parser.contains("\"\\xE2\\x80\\x8B\""))
        assertTrue(prefix.contains("normalize(prefix[index].second)"))
        assertTrue(prepare.contains("llm->syncPromptCache(cache.committed_messages);"))
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

    /**
     * The prompt cache was extracted out of the bridge into mca/mnn modules.  The textual guard reads
     * the bridge PLUS those modules; the previous revision passed only because deleted "// contract:"
     * comments inside the bridge repeated the same strings (see PLAN 9.1 anti-pattern).
     */
    private fun nativeSource(): String =
        listOf(
            "core/native/src/main/cpp/mnn_native_engine.cpp",
            // T2B2-C1: the chat session cluster (state, prompt-cache bookkeeping,
            // build_mnn_config, JNI entries) was relocated out of the monolith.
            "core/native/src/main/cpp/mca/mnn/mnn_chat_session.cpp",
            "core/native/src/main/cpp/mca/mnn/mnn_chat_entries.cpp",
            "core/native/src/main/cpp/mca/mnn/mnn_prompt_cache_manager.cpp",
            "core/native/src/main/cpp/mca/mnn/mnn_template_parser.cpp",
            "core/native/src/main/cpp/mca/mnn/mnn_stats_collector.cpp",
        ).joinToString("\n") { sourceFile(it) }

    private fun managerSource(): String =
        sourceFile("core/native/src/main/cpp/mca/mnn/mnn_prompt_cache_manager.cpp")

    private fun parserSource(): String =
        sourceFile("core/native/src/main/cpp/mca/mnn/mnn_template_parser.cpp")

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
