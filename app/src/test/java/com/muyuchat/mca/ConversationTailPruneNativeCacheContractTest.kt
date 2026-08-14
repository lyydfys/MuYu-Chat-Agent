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
        val deleteTail = functionBody(source, "fun deleteLastConversationTurn()", "private fun cancelGenerationJob()")
        val persistence = functionBody(source, "private fun persistConversationMutation(", "private fun persistKnowledgeBaseBindings(")

        assertFalse(deleteAt.contains("preserveReusableNativePrefix = true"))
        assertTrue(deleteTail.contains("preserveReusableNativePrefix = true"))
        assertTrue(persistence.contains("localConversationContextNeedsInvalidation = !preserveReusableNativePrefix"))
        assertTrue(persistence.contains("if (preserveReusableNativePrefix)"))
        assertTrue(persistence.contains("engine.invalidateConversationContext()"))
    }

    @Test
    fun `gguf runtime validates token prefix and trims only the stale suffix`() {
        val native = sourceFile("core/native/src/main/cpp/native_engine.cpp")
        val prepare = functionBody(native, "size_t prepare_text_prefix_locked(", "int decode_text_prompt_locked(")

        assertTrue(prepare.contains("longestCommonTokenPrefix(tokens, g_context_tokens)"))
        assertTrue(prepare.contains("trim_context_locked(g_context, (llama_pos) reuse"))
        assertTrue(prepare.contains("g_context_tokens.resize(reuse)"))
        assertTrue(prepare.contains("clear_target_context_locked(\"common_prefix_below_threshold\")"))
        assertTrue(prepare.contains("restore_turn_cache_checkpoint_locked(tokens"))
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
        var root = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val candidate = File(root, relativePath)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $relativePath")
    }
}
