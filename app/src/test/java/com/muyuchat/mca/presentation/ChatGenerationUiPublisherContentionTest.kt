package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.mca.AssistantConversationSnapshot
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.ReplyGenerationMetrics
import com.muyuchat.mca.activateContextSummaryCandidate
import com.muyuchat.mca.contextSummaryCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failed CAS attempts must not leak success or repeat terminal side effects. */
class ChatGenerationUiPublisherContentionTest {
    private fun freshRun(ownership: ChatRunOwnership): Long {
        val reservation = requireNotNull(ownership.reserveStart())
        check(ownership.activate(reservation, "test-owner"))
        return reservation.runId
    }

    @Test
    fun browserPublicationReturnsFalseWhenRetryFindsANewerRunEvenWithTheSameTask() {
        val ownership = ChatRunOwnership()
        val runId = freshRun(ownership)
        val state = MutableStateFlow(MainUiState())
        val task = BrowserTaskStateMachine.beginBackgroundSearch("test", null, null, "q")
        val writer = state.generationWriter(beforeCompareAndSet = { attempt ->
            if (attempt == 1) {
                freshRun(ownership)
                state.value = state.value.copy(browserTask = task, statusMessage = "new owner")
            }
        })
        val publisher = ChatGenerationUiPublisher(ownership, runId, writer)

        assertFalse(publisher.publishBrowserSearchTaskStart(task))
        assertSame(task, state.value.browserTask)
        assertEquals("new owner", state.value.statusMessage)
    }

    @Test
    fun browserPublicationReturnsFalseWhenRetryFindsAVisibleWindow() {
        val ownership = ChatRunOwnership()
        val runId = freshRun(ownership)
        val state = MutableStateFlow(MainUiState())
        val task = BrowserTaskStateMachine.beginBackgroundSearch("test", null, null, "q")
        val visible = task.copy(windowVisible = true)
        val writer = state.generationWriter(beforeCompareAndSet = { attempt ->
            if (attempt == 1) state.value = state.value.copy(browserTask = visible)
        })

        assertFalse(ChatGenerationUiPublisher(ownership, runId, writer).publishBrowserSearchTaskStart(task))
        assertSame(visible, state.value.browserTask)
    }

    @Test
    fun browserPublicationReturnsItsOwnSuccessAfterAnotherWriteReplacesTheTask() {
        val ownership = ChatRunOwnership()
        val runId = freshRun(ownership)
        val state = MutableStateFlow(MainUiState())
        val task = BrowserTaskStateMachine.beginBackgroundSearch("test", null, null, "q")
        val replacement = task.copy(taskId = "replacement", windowVisible = true)
        val writer = state.generationWriter(afterCommit = {
            state.value = state.value.copy(browserTask = replacement)
        })

        assertTrue(ChatGenerationUiPublisher(ownership, runId, writer).publishBrowserSearchTaskStart(task))
        assertSame(replacement, state.value.browserTask)
    }

    @Test
    fun summaryActivationReturnsFalseWhenAnotherCommitAlreadyActivatedTheCandidate() {
        val messages = listOf(
            ChatMessage(Role.USER, "I prefer tea.", id = "first", createdAt = 1L),
            ChatMessage(Role.ASSISTANT, "Meeting is on 2026-09-27.", id = "second", createdAt = 2L),
            ChatMessage(Role.USER, "latest question", id = "latest", createdAt = 3L)
        )
        val result = compressChatRequestContext(
            ChatRequest(messages, GenerationParams(nCtx = 4_096)),
            ContextCompressionSettings(keepRecentMessages = 1, minimumMessagesToCompress = 1),
            ContextCompressionTrigger.MANUAL
        )
        val candidate = requireNotNull(contextSummaryCandidate(messages, result, emptyList(), "persona", 100L))
        val persona = AssistantConversationSnapshot(
            assistantId = "assistant", name = "Assistant", systemPrompt = "persona",
            memoryEnabled = false, webSearchEnabled = false, fileContextEnabled = false, capturedAt = 1L
        )
        val session = ChatSessionRecord(
            "session", "Chat", messages, assistantId = "assistant", assistantSnapshot = persona
        )
        val state = MutableStateFlow(MainUiState(
            activeChatSessionId = session.id, messages = messages, chatSessions = listOf(session)
        ))
        val activeRecords = activateContextSummaryCandidate(emptyList(), candidate, messages, "persona")
        assertTrue(activeRecords.single().active)
        val writer = state.generationWriter(beforeCompareAndSet = { attempt ->
            if (attempt == 1) state.value = state.value.copy(
                chatSessions = listOf(session.copy(contextSummaries = activeRecords))
            )
        })
        val ownership = ChatRunOwnership()
        val publisher = ChatGenerationUiPublisher(ownership, freshRun(ownership), writer)

        assertFalse(publisher.publishContextSummaryActivation(
            candidate, "persona", session.id, session.id, "assistant", persona
        ))
        assertSame(activeRecords, state.value.chatSessions.single().contextSummaries)
    }

    @Test
    fun terminalMetricsAreFinishedOnceAcrossARetryAndStayPairedWithTheSession() {
        val ownership = ChatRunOwnership()
        val runId = freshRun(ownership)
        val messages = listOf(ChatMessage(Role.ASSISTANT, "answer"))
        val session = ChatSessionRecord("session", "Chat", messages)
        val state = MutableStateFlow(MainUiState(
            isGenerating = true, activeChatSessionId = session.id,
            messages = messages, chatSessions = listOf(session)
        ))
        val writer = state.generationWriter(beforeCompareAndSet = { attempt ->
            if (attempt == 1) state.value = state.value.copy(input = "concurrent draft")
        })
        val stats = RuntimeStats(loaded = true, completionTokens = 3)
        val metrics = ReplyGenerationMetrics(runId, 0L).apply { record(stats) }.finish(5_000_000L)
        assertNotNull(metrics)
        var finished = 0

        ChatGenerationUiPublisher(ownership, runId, writer).publishFallbackTermination(stats, "done") {
            finished += 1
            metrics
        }

        assertEquals(1, finished)
        assertFalse(state.value.isGenerating)
        assertEquals("concurrent draft", state.value.input)
        assertSame(metrics, state.value.messages.single().generationMetrics)
        assertEquals(state.value.messages, state.value.chatSessions.single().messages)
    }
}
