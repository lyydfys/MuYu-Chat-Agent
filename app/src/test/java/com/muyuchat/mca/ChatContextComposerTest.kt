package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextComposerTest {
    @Test
    fun composerBoundsAndCombinesWorldBookBeforeKnowledgeContext() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatContextComposer.kt")
        val compose = functionBody(source, "fun compose(")

        assertTrue(compose.contains("localContextWindowBudget(params.nCtx).promptBudgetTokens"))
        assertTrue(compose.contains("val dynamicBudget = (promptBudget / 5)"))
        assertTrue(compose.contains("val loreBudget = dynamicBudget * 3 / 5"))
        assertTrue(compose.contains("val retrievalBudget = dynamicBudget - loreBudget"))
        assertTrue(compose.contains("books = worldBookStore.load()"))
        assertTrue(compose.contains("tokenBudget = loreBudget"))
        assertTrue(compose.contains("knowledgeBaseStore.retrieve("))
        assertTrue(compose.contains("tokenBudget = retrievalBudget"))
        assertTrue(compose.contains("listOf(lore.context, knowledge.context)"))
        assertTrue(compose.contains(".filter { it.isNotBlank() }"))
        assertTrue(compose.contains(".joinToString(\"\\n\\n\")"))
        assertTrue(compose.contains("runtimeSystemContext = context"))
        assertTrue(compose.contains("tokenBudget = dynamicBudget"))

        val loreSelection = compose.indexOf("val lore = WorldBookResolver.select(")
        val knowledgeRetrieval = compose.indexOf("val knowledge = knowledgeBaseStore.retrieve(")
        val contextAssembly = compose.indexOf("listOf(lore.context, knowledge.context)")
        assertTrue(loreSelection >= 0)
        assertTrue(knowledgeRetrieval > loreSelection)
        assertTrue(contextAssembly > knowledgeRetrieval)
        assertFalse(compose.contains("params.copy("))
    }

    @Test
    fun composerUsesOnlyTheLatestBoundedUserTurnAsKnowledgeQuery() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatContextComposer.kt")
        val compose = functionBody(source, "fun compose(")

        assertTrue(compose.contains("messages.asReversed()"))
        assertTrue(compose.contains("firstOrNull { it.role == Role.USER && it.content.isNotBlank() }"))
        assertTrue(compose.contains("?.content"))
        assertTrue(compose.contains("?.takeLast(MAX_QUERY_CHARS)"))
        assertTrue(source.contains("const val MAX_QUERY_CHARS = 8_192"))
        assertTrue(source.contains("const val MAX_KNOWLEDGE_CHUNKS = 4"))
    }

    @Test
    fun enginePreservesComposedContextWhenAppendingDeviceClockContext() {
        val engineSource = sourceFile(
            "core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceService.kt"
        )
        val mergeStart = engineSource.indexOf("val deviceClockContext =")
        val admissionStart = engineSource.indexOf(
            "val contextAdmission = localContextWindowAdmission(requestWithRuntimeContext)",
            mergeStart
        )
        assertTrue(mergeStart >= 0)
        assertTrue(admissionStart > mergeStart)
        val merge = engineSource.substring(mergeStart, admissionStart)

        assertTrue(merge.contains("val requestWithRuntimeContext = request.copy("))
        assertTrue(merge.contains("listOf(request.runtimeSystemContext, deviceClockContext)"))
        assertTrue(merge.contains(".filter { it.isNotBlank() }"))
        assertTrue(merge.contains(".joinToString(\"\\n\\n\")"))
        val composed = "[World book]\n杭州以西湖闻名。\n\n[Knowledge]\n西湖位于浙江杭州。"
        val clock = "[Device clock]\nLocal date: 2026-08-04"
        val params = GenerationParams(systemPrompt = "固定助手提示")
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "今天介绍一下西湖")),
            params = params,
            runtimeSystemContext = listOf(composed, clock).joinToString("\n\n")
        )

        val system = JSONArray(request.messagesJson()).getJSONObject(0).getString("content")
        assertTrue(system.contains("固定助手提示"))
        assertTrue(system.contains("[World book]"))
        assertTrue(system.contains("[Knowledge]"))
        assertTrue(system.contains("[Device clock]"))
        assertTrue(system.indexOf("[World book]") < system.indexOf("[Knowledge]"))
        assertTrue(system.indexOf("[Knowledge]") < system.indexOf("[Device clock]"))
        assertFalse(params.systemPrompt.contains("[World book]"))
        assertFalse(params.systemPrompt.contains("[Device clock]"))
    }

    @Test
    fun runtimeContextPlanReportsWhetherAnyDynamicContextWasComposed() {
        assertFalse(ChatRuntimeContextPlan().hasContext)
        assertTrue(ChatRuntimeContextPlan(runtimeSystemContext = "[World book]\nentry").hasContext)
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            File(root, relativePath).takeIf(File::isFile)?.let {
                return it.readText(Charsets.UTF_8)
            }
            directory = root.parentFile
        }
        error("Unable to locate $relativePath")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingParenthesis = source.indexOf('(', start)
        require(openingParenthesis >= 0) { "Missing function parameter list: $signature" }
        var parenthesisDepth = 0
        var closingParenthesis = -1
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth == 0) {
                        closingParenthesis = index
                        break
                    }
                }
            }
        }
        require(closingParenthesis >= 0) { "Unterminated function parameter list: $signature" }
        val openingBrace = source.indexOf('{', closingParenthesis)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
