package com.muyuchat.mca

import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the agent-turn filter of context composition.
 *
 * The rule under test: an agent turn's intermediate stages are transcript, so they never reach the
 * model; only a `summary` stage with a non-blank [AgentTurnTrace.finalSummary] survives, and it
 * survives *as its summary*. A message without a trace must come through untouched — the role-chat
 * path is not allowed to change at all.
 */
class ChatContextComposerAgentTraceTest {

    private class FakeSources : ChatContextSources {
        var lastQuery: String? = null
            private set
        var loadCount = 0
            private set

        override fun loadWorldBooks(): List<WorldBookRecord> {
            loadCount += 1
            return emptyList()
        }

        override fun retrieveKnowledge(
            knowledgeBaseIds: Set<String>,
            query: String,
            maxChunks: Int,
            tokenBudget: Int
        ): KnowledgeRetrieval {
            lastQuery = query
            return KnowledgeRetrieval()
        }
    }

    private fun user(content: String) = ChatMessage(role = Role.USER, content = content)

    private fun assistant(content: String, trace: AgentTurnTrace?) = ChatMessage(
        role = Role.ASSISTANT,
        content = content,
        agentTrace = trace
    )

    private fun projection(messages: List<ChatMessage>) = messages.agentContextProjection()

    @Test
    fun `a message carrying an agent trace never reaches the composed context`() {
        val trace = AgentTurnTrace(turnId = "turn-1", kind = "step")
        val messages = listOf(
            user("请帮我统计这个目录"),
            assistant("正在读取文件…", trace),
            assistant("还需要一步：确认是否覆盖", trace),
            user("继续")
        )

        val kept = projection(messages)

        assertEquals("only the two plain chat turns may remain", 2, kept.size)
        assertEquals(listOf("请帮我统计这个目录", "继续"), kept.map { it.content })
        assertTrue("no kept message may still be an agent turn", kept.none { it.agentTrace != null })
    }

    @Test
    fun `the composer's knowledge query skips an agent turn`() {
        val sources = FakeSources()
        val trace = AgentTurnTrace(turnId = "turn-1", kind = "step")
        ChatContextComposer(sources).compose(
            messages = listOf(
                user("真正的用户问题"),
                assistant("internal agent step text", trace)
            ),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertEquals("the dropped step must not become the retrieval query", "真正的用户问题", sources.lastQuery)
    }

    @Test
    fun `a summary with a final summary is kept as the summary text`() {
        val trace = AgentTurnTrace(
            turnId = "turn-2",
            kind = AgentTurnTrace.SUMMARY,
            finalSummary = "已整理 3 个文件，未做修改。"
        )
        val messages = listOf(
            user("帮我整理"),
            assistant("=== raw transcript of every tool call ===", trace)
        )

        val kept = projection(messages)

        assertEquals(2, kept.size)
        assertEquals("已整理 3 个文件，未做修改。", kept[1].content)
        assertEquals("the trace must stay attached so the message is still recognisable", trace, kept[1].agentTrace)
        assertEquals(Role.ASSISTANT, kept[1].role)
    }

    @Test
    fun `a summary without a final summary is dropped like any other agent stage`() {
        val blank = AgentTurnTrace(turnId = "turn-3", kind = AgentTurnTrace.SUMMARY, finalSummary = "")
        val spaces = AgentTurnTrace(turnId = "turn-4", kind = AgentTurnTrace.SUMMARY, finalSummary = "   \n ")

        val kept = projection(
            listOf(user("问题"), assistant("raw", blank), assistant("raw", spaces))
        )

        assertEquals(1, kept.size)
        assertEquals("问题", kept.single().content)
    }

    @Test
    fun `unknown or non summary kinds are dropped rather than trusted`() {
        val kinds = AgentTurnTrace.KNOWN_KINDS.filter { it != AgentTurnTrace.SUMMARY } +
            "browser-step" // a future stage the contract does not know yet

        kinds.forEach { kind ->
            val trace = AgentTurnTrace(turnId = "turn-$kind", kind = kind, finalSummary = "even with a summary")
            val kept = projection(listOf(user("q"), assistant("raw", trace)))
            assertEquals("kind=$kind must not enter the context", 1, kept.size)
        }
    }

    @Test
    fun `a transcript without traces is returned as the same list untouched`() {
        val messages = listOf(
            user("第一句"),
            ChatMessage(role = Role.ASSISTANT, content = "回答"),
            ChatMessage(role = Role.USER, content = "带附件的消息", id = "fixed-id"),
            ChatMessage(role = Role.ASSISTANT, content = "好")
        )

        val kept = projection(messages)

        assertSame("a pure chat transcript must not be rebuilt", messages, kept)
        assertEquals(messages.map { it.id }, kept.map { it.id })
        assertEquals(messages.map { it.content }, kept.map { it.content })
    }

    @Test
    fun `untraced messages keep their identity even when other messages carry traces`() {
        val plainUser = user("保留这条")
        val plainAssistant = ChatMessage(role = Role.ASSISTANT, content = "也保留")
        val kept = projection(
            listOf(
                plainUser,
                assistant("step", AgentTurnTrace(turnId = "t", kind = "approval")),
                plainAssistant
            )
        )

        assertEquals(2, kept.size)
        assertSame(plainUser, kept[0])
        assertSame(plainAssistant, kept[1])
    }

    @Test
    fun `the composed plan for a pure chat transcript is unchanged by the filter`() {
        val messages = listOf(user("今天天气如何"), ChatMessage(role = Role.ASSISTANT, content = "晴"))

        val withFilter = ChatContextComposer(FakeSources()).compose(
            messages = messages,
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = emptySet()
        )

        // Without any trace the projection is the identity, so the plan must equal what the same
        // messages produce when the filter is bypassed entirely (the raw list).
        assertEquals("", withFilter.runtimeSystemContext)
        assertEquals(0, withFilter.trace?.selectedWorldBookSources?.size)
        assertEquals(0, withFilter.trace?.selectedKnowledgeSources?.size)
    }
}
