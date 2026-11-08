package com.muyuchat.mca

import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContextComposerTest {
    @Test
    fun storedTraceBoundsLargeSkippedSourcesAndRetainsFullExcerptHash() {
        val excerpt = "quoted \"line\"\n".repeat(2_000)
        val sources = List(200) { index ->
            WorldBookSource("book-$index", "entry-$index", WorldBookScope.GLOBAL, excerpt, 12_000, "budget")
        }
        val trace = ContextAssemblyTrace("a".repeat(64), emptyList(), sources, emptyList(), emptyList(), 256, "b".repeat(64))
        val raw = trace.toJsonString()
        val json = JSONObject(raw)
        val stored = json.getJSONArray("skippedWorldBookSources")

        assertTrue(raw.toByteArray(Charsets.UTF_8).size <= 128 * 1024)
        assertTrue(stored.length() in 1..64)
        assertEquals(200 - stored.length(), json.getInt("omittedSourceCount"))
        assertTrue(stored.getJSONObject(0).getBoolean("excerptTruncated"))
        assertEquals(excerpt.length, stored.getJSONObject(0).getInt("excerptLength"))
        assertEquals(contextSummaryDigest(excerpt), stored.getJSONObject(0).getString("excerptSha256"))
    }

    @Test
    fun composerBoundsAndCombinesWorldBookBeforeKnowledgeContext() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatContextComposer.kt")
        val compose = functionBody(source, "fun compose(")

        assertTrue(compose.contains("localContextWindowBudget(params.nCtx).promptBudgetTokens"))
        assertTrue(compose.contains("val dynamicBudget = (promptBudget / 5)"))
        assertTrue(compose.contains("val loreBudget = dynamicBudget * 3 / 5"))
        assertTrue(compose.contains("val retrievalBudget = dynamicBudget - loreBudget"))
        assertTrue(compose.contains("books = sources.loadWorldBooks()"))
        assertTrue(compose.contains("tokenBudget = loreBudget"))
        assertTrue(compose.contains("sources.retrieveKnowledge("))
        assertTrue(compose.contains("fileContextEnabled: Boolean = true"))
        assertTrue(compose.contains("if (fileContextEnabled)"))
        assertTrue(compose.contains("KnowledgeRetrieval()"))
        assertTrue(compose.contains("tokenBudget = retrievalBudget"))
        assertTrue(compose.contains("listOf(lore.context, knowledge.context)"))
        assertTrue(compose.contains(".filter { it.isNotBlank() }"))
        assertTrue(compose.contains(".joinToString(\"\\n\\n\")"))
        assertTrue(compose.contains("runtimeSystemContext = context"))
        assertTrue(compose.contains("tokenBudget = dynamicBudget"))

        val loreSelection = compose.indexOf("val lore = WorldBookResolver.select(")
        val knowledgeRetrieval = compose.indexOf("sources.retrieveKnowledge(")
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

        assertTrue(compose.contains("contextMessages.asReversed()"))
        assertTrue(compose.contains("firstOrNull { it.role == Role.USER && it.content.isNotBlank() }"))
        assertTrue(compose.contains("?.content"))
        assertTrue(compose.contains("?.let(::boundedQuery)"))
        assertTrue(source.contains("const val MAX_QUERY_CHARS = 8_192"))
        assertTrue(source.contains("const val MAX_KNOWLEDGE_CHUNKS = 4"))
        assertTrue(source.contains("value.take(half) + \"\\n…\\n\" + value.takeLast(half)"))
    }

    @Test
    fun enginePreservesComposedContextWhenAppendingDeviceClockContext() {
        val engineSource = sourceFiles(
            "core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceService.kt",
            "core/engine/src/main/java/com/muyuchat/core/engine/McaInferenceStreamChat.kt"
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

    @Test
    fun assistantFileContextSettingIsPassedToPreflightAndGenerationComposition() {
        // P3 step 1 moved the generation composition to ChatExecutionOperations.runChatGeneration;
        // the assertions are unchanged -- the generation half is read where it now lives.
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatWorkflowCoordinator.kt")
        val execution = sourceFile("app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt")

        listOf(
            functionBody(source, "fun sendPreparedMessageAsync"),
            functionBody(execution, "fun ChatGenerationExecutionPort.runChatGeneration")
        ).forEach { body ->
            assertTrue(body.contains("fileContextEnabled = assistantSnapshot?.fileContextEnabled"))
        }
    }

    @Test
    fun sendMessageCompressesAndRebuildsRuntimeContextBeforeFinalAdmission() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatWorkflowCoordinator.kt")
        // The public sendMessage entry point performs the asynchronous attachment preflight;
        // the request admission/compression and persistence live in the synchronous handoff it
        // calls so the final request uses the exact deduplicated attachment snapshot.
        val sendMessage = functionBody(source, "fun sendMessage")
        val sendPreparedMessage = functionBody(source, "fun sendPreparedMessageAsync")
        val request = sendPreparedMessage.indexOf("val preflightRequest = ChatRequest(")
        val initialAdmission = sendPreparedMessage.indexOf("val initialAdmission = localContextWindowAdmission(", request)
        val compression = sendPreparedMessage.indexOf("val preflightCompression = compressChatRequestContext(", initialAdmission)
        val rebuiltContext = sendPreparedMessage.indexOf("chatContextComposer.compose(", compression)
        val finalRequest = sendPreparedMessage.indexOf("val finalPreflightRequest = preflightCompression.request.copy(", rebuiltContext)
        val finalAdmission = sendPreparedMessage.indexOf("val admission = localContextWindowAdmission(finalPreflightRequest)", finalRequest)
        val persistedMessages = sendPreparedMessage.indexOf("val messages = it.messages + user + assistant", finalAdmission)

        assertTrue(request >= 0)
        assertTrue(initialAdmission > request)
        assertTrue(compression > initialAdmission)
        assertTrue(rebuiltContext > compression)
        assertTrue(finalRequest > rebuiltContext)
        assertTrue(finalAdmission > finalRequest)
        assertTrue(persistedMessages > finalAdmission)
        assertTrue(sendPreparedMessage.contains("trigger = if (initialAdmission.isAccepted)"))
        assertTrue(sendPreparedMessage.contains("ContextCompressionTrigger.MANUAL"))
        assertTrue(sendPreparedMessage.contains("listOf(rebuiltPreflightContext.runtimeSystemContext, characterMemoryContext)"))
        // R1/S6: sendMessage 的 vision 去重改直接调用 owner（deduplicatedVisionAttachmentsForSend），
        // 旧 VM wrapper（deduplicateVisionAttachmentsForSend）已删除；断言跟踪到 owner 名。
        assertTrue(sendMessage.contains("deduplicatedVisionAttachmentsForSend"))
    }

    // ---------------------------------------------------------------- agent projection (multi-turn)

    @Test
    fun tenAgentTurnsCollapseToTheirSummariesWithoutLeakingRawSteps() {
        val messages = (1..10).flatMap { turn ->
            val traceId = "run-$turn"
            listOf(
                agentStep(traceId, 1, "步骤一：读取 $traceId 的原始工具输出，内容很长且不应进入模型上下文"),
                agentStep(traceId, 2, "步骤二：$traceId approval 请求原文"),
                agentStep(traceId, 3, "步骤三：$traceId question 原文"),
                agentSummary(traceId, "第 $turn 轮总结：完成了 $turn 项操作。")
            )
        }

        val projected = messages.agentContextProjection()

        assertEquals("10 轮任务只应留下 10 条 summary", 10, projected.size)
        assertTrue("任何原始 step 都不能泄漏", projected.none { it.content.contains("原始工具输出") })
        assertTrue(projected.none { it.content.contains("approval 请求原文") })
        assertTrue(projected.none { it.content.contains("question 原文") })
        assertEquals(
            (1..10).map { "第 $it 轮总结：完成了 $it 项操作。" },
            projected.map { it.content }
        )
        assertEquals(
            (1..10).map { "run-$it" },
            projected.map { it.agentTrace?.turnId }
        )
    }

    @Test
    fun everyProjectedAgentMessageCarriesItsFinalSummaryAsContent() {
        val summary = "整理完成：3 条结论，写入 summary.md。"
        val raw = "非常长的原始 transcript ".repeat(200)
        val message = ChatMessage(
            role = Role.ASSISTANT,
            content = raw,
            agentTrace = AgentTurnTrace(
                turnId = "run-1",
                kind = AgentTurnTrace.SUMMARY,
                finalSummary = summary
            )
        )

        val projected = message.let { listOf(it) }.agentContextProjection()

        assertEquals(1, projected.size)
        assertEquals("投影后的正文必须是 finalSummary，而不是原始长文本", summary, projected.single().content)
        assertFalse(projected.single().content.contains("原始 transcript"))
        assertEquals(message.agentTrace, projected.single().agentTrace)
    }

    @Test
    fun aConversationWithNoAgentTraceReturnsTheVerySameInstance() {
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "你好"),
            ChatMessage(role = Role.ASSISTANT, content = "你好，有什么可以帮你？")
        )

        val projected = messages.agentContextProjection()

        assertSame("普通对话必须零改动，连实例都不应重建", messages, projected)
    }

    @Test
    fun mixedConversationKeepsPlainTurnsVerbatimAndKeepsOnlyAgentSummaries() {
        val plainUser = ChatMessage(role = Role.USER, content = "  帮我看看这张图\n（注意前导空格）")
        val plainAssistant = ChatMessage(role = Role.ASSISTANT, content = "好的，我看看。")
        val messages = listOf(
            plainUser,
            agentSummary("run-1", "总结一"),
            plainAssistant,
            agentStep("run-2", 1, "步骤原文"),
            agentSummary("run-2", "总结二"),
            agentStep("run-3", 1, "另一段步骤原文"),
            agentSummary("run-3", "总结三")
        )

        val projected = messages.agentContextProjection()

        assertEquals("2 条普通对话 + 3 条 summary", 5, projected.size)
        assertEquals(plainUser.content, projected[0].content)
        assertEquals(plainUser.role, projected[0].role)
        assertEquals(plainAssistant.content, projected[2].content)
        assertEquals("总结一", projected[1].content)
        assertEquals("总结二", projected[3].content)
        assertEquals("总结三", projected[4].content)
        assertTrue(projected.none { it.content.contains("步骤原文") })
    }

    @Test
    fun aSummaryMessageWithBlankFinalSummaryIsDropped() {
        val messages = listOf(
            ChatMessage(
                role = Role.ASSISTANT,
                content = "看起来像结论的正文",
                agentTrace = AgentTurnTrace(turnId = "run-1", kind = AgentTurnTrace.SUMMARY, finalSummary = "")
            ),
            ChatMessage(
                role = Role.ASSISTANT,
                content = "空白结论的正文",
                agentTrace = AgentTurnTrace(turnId = "run-2", kind = AgentTurnTrace.SUMMARY, finalSummary = "   ")
            ),
            agentSummary("run-3", "真正的结论")
        )

        val projected = messages.agentContextProjection()

        assertEquals(1, projected.size)
        assertEquals("真正的结论", projected.single().content)
        assertEquals("run-3", projected.single().agentTrace?.turnId)
    }

    private fun agentStep(turnId: String, index: Int, content: String) = ChatMessage(
        role = Role.ASSISTANT,
        content = content,
        agentTrace = AgentTurnTrace(turnId = turnId, kind = "step")
    )

    private fun agentSummary(turnId: String, summary: String) = ChatMessage(
        role = Role.ASSISTANT,
        content = summary,
        agentTrace = AgentTurnTrace(
            turnId = turnId,
            kind = AgentTurnTrace.SUMMARY,
            finalSummary = summary
        )
    )

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

    private fun sourceFiles(vararg relativePaths: String): String =
        relativePaths.joinToString("\n") { sourceFile(it) }

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
