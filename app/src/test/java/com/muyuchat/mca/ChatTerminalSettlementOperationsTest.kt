package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the terminal-settlement persistence contract (R1 category 4: "落库").
 *
 * `ChatTerminalSettlementOperations` runs after the stream ended -- exactly where a stale callback
 * is most dangerous. The contract under test here is the one the ViewModel relies on:
 *
 *  - a settlement that changes the conversation persists **the exact updated message list** by
 *    calling [ChatTerminalSettlementPort.upsertSession] once, with the session id, assistant id,
 *    backend binding value and model id read from the same state revision;
 *  - a superseded run (`isRunCurrent` false) performs no writes at all;
 *  - the pending-output discard is run-scoped and happens even for a superseded run -- the buffer
 *    belongs to that run id, so dropping it can never touch a newer run's buffer.
 *
 * `settleWebSearchCitationAudit`'s store-backed body needs an Android Context and is not exercised
 * here; its three pre-store early exits are, and the fake port's store getter throws, so a test that
 * would touch the store fails loudly instead of passing silently.
 */
class ChatTerminalSettlementOperationsTest {

    private class FakeSettlementPort(
        initial: MainUiState,
        private val runIsCurrent: (Long?) -> Boolean = { true }
    ) : ChatTerminalSettlementPort {

        data class UpsertCall(
            val sessionId: String,
            val messages: List<ChatMessage>,
            val assistantId: String?,
            val modelMode: String?,
            val modelId: String?
        )

        val stateFlow = MutableStateFlow(initial)
        val upsertCalls = mutableListOf<UpsertCall>()
        val discardedRuns = mutableListOf<Long?>()
        var chatModelIdForTurn: String? = "model-from-port"
        var supersedingState: MainUiState? = null
        var onSuperseded: () -> Unit = {}

        override fun uiState(): MainUiState = stateFlow.value

        override fun updateUiState(transform: (ConversationSettlementFields) -> ConversationSettlementFields) {
            updateUiStateWithResult { fields -> transform(fields) to Unit }
        }

        override fun <Result> updateUiStateWithResult(
            transform: (ConversationSettlementFields) -> Pair<ConversationSettlementFields, Result>
        ): Result {
            while (true) {
                val snapshot = stateFlow.value
                val (fields, result) = transform(snapshot.toConversationSettlementFields())
                supersedingState?.let { newer ->
                    supersedingState = null
                    stateFlow.value = newer
                    onSuperseded()
                }
                if (stateFlow.compareAndSet(snapshot, fields.mergeInto(snapshot))) return result
            }
        }

        override val webSearchDiagnosticStore: WebSearchDiagnosticStore
            get() = throw AssertionError("this test must not reach the diagnostic store")

        override fun currentChatModelId(state: MainUiState): String? = chatModelIdForTurn

        override fun upsertSession(
            state: MainUiState,
            occurredAt: Long,
            sessions: List<ChatSessionRecord>,
            sessionId: String,
            messages: List<ChatMessage>,
            assistantId: String?,
            modelMode: String?,
            modelId: String?
        ): List<ChatSessionRecord> {
            upsertCalls += UpsertCall(sessionId, messages, assistantId, modelMode, modelId)
            return sessions
        }

        override fun isRunCurrent(runId: Long?): Boolean = runIsCurrent(runId)

        override fun discardPendingOutput(runId: Long?) {
            discardedRuns += runId
        }
    }

    private fun conversationState(
        lastAssistantContent: String,
        sessionId: String? = "session-1"
    ): MainUiState = MainUiState(
        messages = listOf(ChatMessage(Role.USER, "hello"), ChatMessage(Role.ASSISTANT, lastAssistantContent)),
        activeChatSessionId = sessionId
    )

    @Test
    fun visibleReplyFallbackIsPersistedWithExactSessionArguments() {
        val state = conversationState(lastAssistantContent = "")
        val port = FakeSettlementPort(state)

        settleVisibleAssistantReply(port, generationRunId = 7L)

        val after = port.stateFlow.value
        assertEquals(EMPTY_ASSISTANT_COMPLETION_MESSAGE, after.messages.last().content)
        val upsert = port.upsertCalls.single()
        assertEquals("session-1", upsert.sessionId)
        assertEquals(after.messages, upsert.messages)
        assertEquals(state.selectedAssistantId, upsert.assistantId)
        assertEquals(state.selectedChatBackend.bindingValue(), upsert.modelMode)
        assertEquals("model-from-port", upsert.modelId)
    }

    @Test
    fun supersededRunCannotPersistTheVisibleReplyFallback() {
        val state = conversationState(lastAssistantContent = "")
        val port = FakeSettlementPort(state, runIsCurrent = { false })

        settleVisibleAssistantReply(port, generationRunId = 7L)

        assertEquals(state, port.stateFlow.value)
        assertTrue(port.upsertCalls.isEmpty())
    }

    @Test
    fun pendingPlaceholderIsDroppedAndPersistedForTheRun() {
        val state = conversationState(lastAssistantContent = "")
        val port = FakeSettlementPort(state)

        settlePendingAssistantPlaceholder(port, generationRunId = 7L)

        assertEquals(listOf<Long?>(7L), port.discardedRuns)
        val remaining = port.stateFlow.value.messages
        assertEquals(1, remaining.size)
        // Identity, not equality: ChatMessage carries a per-instance id, so the persistence
        // contract here is "the placeholder was removed and the remaining message is untouched".
        assertSame(state.messages.first(), remaining.single())
        val upsert = port.upsertCalls.single()
        assertEquals("session-1", upsert.sessionId)
        assertEquals(port.stateFlow.value.messages, upsert.messages)
    }

    @Test
    fun pendingPlaceholderWithContentKeepsMessagesButStillDiscardsTheRunBuffer() {
        val state = conversationState(lastAssistantContent = "visible")
        val port = FakeSettlementPort(state)

        settlePendingAssistantPlaceholder(port, generationRunId = 7L)

        // The discard is run-scoped and unconditional; the placeholder removal is conditional.
        assertEquals(listOf<Long?>(7L), port.discardedRuns)
        assertEquals(state, port.stateFlow.value)
        assertTrue(port.upsertCalls.isEmpty())
    }

    @Test
    fun supersededRunStillDiscardsOnlyItsOwnRunBufferAndWritesNothing() {
        val state = conversationState(lastAssistantContent = "")
        val port = FakeSettlementPort(state, runIsCurrent = { false })

        settlePendingAssistantPlaceholder(port, generationRunId = 7L)

        assertEquals(listOf<Long?>(7L), port.discardedRuns)
        assertEquals(state, port.stateFlow.value)
        assertTrue(port.upsertCalls.isEmpty())
    }

    @Test
    fun searchEvidenceAttachesToTheLastAssistantReplyAndPersists() {
        val sources = listOf(ChatSourceReference(title = "Example", url = "https://example.com"))
        val state = conversationState(lastAssistantContent = "answer")
        val port = FakeSettlementPort(state)

        settleWebSearchEvidenceAttachment(port, sources = sources, trace = null, generationRunId = 7L)

        val after = port.stateFlow.value
        assertEquals(sources, after.messages.last().sourceReferences)
        assertEquals("answer", after.messages.last().content)
        val upsert = port.upsertCalls.single()
        assertEquals("session-1", upsert.sessionId)
        assertEquals(after.messages, upsert.messages)
    }

    @Test
    fun searchEvidenceWithoutSourcesOrTraceWritesNothing() {
        val state = conversationState(lastAssistantContent = "answer")
        val port = FakeSettlementPort(state)

        settleWebSearchEvidenceAttachment(port, sources = emptyList(), trace = null, generationRunId = 7L)

        assertEquals(state, port.stateFlow.value)
        assertTrue(port.upsertCalls.isEmpty())
    }

    @Test
    fun citationAuditEarlyExitsNeverTouchTheDiagnosticStore() {
        val audit = WebSearchCitationAudit(sourceCount = 0)

        // Blank record id: returns before reading any state.
        val blankIdState = conversationState(lastAssistantContent = "answer")
        val blankIdPort = FakeSettlementPort(blankIdState)
        settleWebSearchCitationAudit(blankIdPort, recordId = " ", audit = audit, generationRunId = 7L)
        assertEquals(blankIdState, blankIdPort.stateFlow.value)

        // Superseded run: returns before reading any state.
        val staleState = conversationState(lastAssistantContent = "answer")
        val stalePort = FakeSettlementPort(staleState, runIsCurrent = { false })
        settleWebSearchCitationAudit(stalePort, recordId = "record-1", audit = audit, generationRunId = 7L)
        assertEquals(staleState, stalePort.stateFlow.value)

        // Current run with no matching diagnostics record: returns before the store is consulted.
        val missingRecordState = conversationState(lastAssistantContent = "answer")
        val missingRecordPort = FakeSettlementPort(missingRecordState)
        settleWebSearchCitationAudit(missingRecordPort, recordId = "record-1", audit = audit, generationRunId = 7L)
        assertEquals(missingRecordState, missingRecordPort.stateFlow.value)
    }
    @Test
    fun answerAuditFromLosingCasAttemptDoesNotEscapeAfterRunIsSuperseded() {
        val sources = listOf(ChatSourceReference(title = "source", url = "https://example.com", snippet = "evidence"))
        val initial = conversationState("answer [99]").let { state ->
            state.copy(messages = state.messages.map { message ->
                if (message.role == Role.ASSISTANT) message.copy(sourceReferences = sources) else message
            })
        }
        var current = true
        val port = FakeSettlementPort(initial, runIsCurrent = { current })
        val replacement = initial.copy(messages = listOf(ChatMessage(Role.USER, "new run")))
        port.supersedingState = replacement
        port.onSuperseded = { current = false }

        assertNull(settleWebSearchAnswerGuards(port, generationRunId = 7L))
        assertSame(replacement, port.stateFlow.value)
    }

    @Test
    fun evidenceUsesOneSessionAndMessageIdentityAcrossCasRetry() {
        val initial = MainUiState()
        val port = FakeSettlementPort(initial)
        port.supersedingState = initial.copy(revision = (initial.revision ?: 0L) + 1L)
        val sources = listOf(ChatSourceReference(title = "source", url = "https://example.com", snippet = "evidence"))

        // Use the result-capable transition as the retrying state writer for ordinary updates too.
        settleWebSearchEvidenceAttachment(port, sources, trace = null)

        assertEquals(2, port.upsertCalls.size)
        assertEquals(port.upsertCalls[0].sessionId, port.upsertCalls[1].sessionId)
        assertEquals(port.upsertCalls[0].messages.single().id, port.upsertCalls[1].messages.single().id)
        assertEquals(port.upsertCalls[1].sessionId, port.stateFlow.value.activeChatSessionId)
    }

}
