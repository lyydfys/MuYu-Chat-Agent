package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationTailPruneNativeCacheContractTest {
    @Test
    fun `tail prune preserves verified native prefix while arbitrary deletion invalidates`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val deleteAt = functionBody(source, "fun deleteMessageAt(index: Int)", "fun deleteLastConversationTurn()")
        val deleteTail = functionBody(source, "fun deleteLastConversationTurn()", "fun cancelGenerationJob()") +
            sourceFile("app/src/main/java/com/muyuchat/mca/ChatWorkflowCoordinator.kt")
        // P4: the durable boundary moved to ChatSessionPersistenceOperations; the assertions are
        // unchanged -- it is read there now.
        val persistence = sourceFile("app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt")
            .substringAfter("fun runPersistConversationMutation(")

        assertFalse(deleteAt.contains("preserveReusableNativePrefix = true"))
        assertTrue(deleteTail.contains("preserveReusableNativePrefix = true"))
        assertTrue(persistence.contains("localConversationContextNeedsInvalidation = !preserveReusableNativePrefix"))
        assertTrue(persistence.contains("if (preserveReusableNativePrefix)"))
        assertTrue(persistence.contains("engine.invalidateConversationContext()"))
    }

    @Test
    fun `gguf runtime validates token prefix and trims only the stale suffix`() {
        // prepare_text_prefix_locked is now a thin adapter: it builds the LlamaContextEnvironment and
        // delegates to LlamaContextManager::prepare_text_prefix, which lives in mca/llm. The assertions
        // below are unchanged; the prefix-cache reasoning is read from where it now lives.
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp") +
            sourceFile("core/native/src/main/cpp/native_engine_prefix_cache.cpp") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_context_manager.cpp") }.getOrDefault("") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_prefix_cache_policy.hpp") }.getOrDefault("")
        // The prefix-cache reasoning moved into LlamaContextManager and now reaches the llama context
        // through the LlamaContextEnvironment callbacks instead of the g_* globals directly.  The five
        // facts asserted below (common-prefix probe, trim, resize, clear-on-below-threshold, checkpoint
        // restore) are the same facts; only how they are spelled at the new call site changed.
        val prepare = functionBody(native, "size_t prepare_text_prefix_locked(", "int decode_text_prompt_locked(") +
            runCatching { sourceFile("core/native/src/main/cpp/mca/llm/llama_context_manager.cpp") }.getOrDefault("")

        assertTrue(prepare.contains("longestCommonTokenPrefix(tokens, *env.context_tokens)"))
        assertTrue(prepare.contains("env.trim_context((llama_pos) reuse"))
        assertTrue(prepare.contains("env.context_tokens->resize(reuse)"))
        assertTrue(prepare.contains("env.clear_target_context(\"common_prefix_below_threshold\")"))
        assertTrue(prepare.contains("env.restore_turn_checkpoint(tokens"))
        assertTrue(prepare.contains("live_turn_prefix_hit"))
        assertTrue(native.contains("save_turn_cache_checkpoint_locked()"))
        assertTrue(native.contains("turn_checkpoint_hit"))
    }

    @Test
    fun `mnn tail prune uses token lcp instead of clearing the whole cache`() {
        val vendor = sourceFile("third_party/MNN/transformers/llm/engine/src/llm.cpp")
        val response = functionBody(
            vendor,
            "void Llm::response(const ChatMessages& chat_prompts",
            "void Llm::updateCachedPromptText("
        )

        assertTrue(response.contains("promptCacheReusableTokenPrefix("))
        assertTrue(response.contains("eraseHistory(token_split, 0)"))
        assertFalse(response.contains("if (text_common < mCachedPromptText.size())"))
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        require(startIndex >= 0) { "Missing start marker: $start" }
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(endIndex > startIndex) { "Missing end marker: $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun sourceFile(relativePath: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(root, relativePath)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath")
    }
}
