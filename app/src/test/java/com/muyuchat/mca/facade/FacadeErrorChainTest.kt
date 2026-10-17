package com.muyuchat.mca.facade

import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class ErrorEventChatBody(private val event: GenerateEvent) : ChatExecutionBody {
    override fun stream(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent> =
        flowOf(event)

    override suspend fun stopGeneration() = Unit

    override suspend fun stopGenerationIfActive(requestId: String): Boolean = false
}

class FacadeErrorChainTest {

    @Test
    fun `chat error remains queryable in the post-cleanup snapshot`() = runBlocking {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val facade = ChatExecutionFacade(
            supervisor,
            ErrorEventChatBody(GenerateEvent.Error("native graph failed", RuntimeStats(), code = "NATIVE_FAILURE"))
        )
        val requestId = "facade-typed-error"
        val request = ChatRequest(messages = listOf(ChatMessage(Role.USER, "prompt")))

        requireNotNull(facade.streamChat(request, LocalChatExecutionContext(requestId = requestId)))
            .collect { }

        assertEquals("terminal cleanup must evict the live entry", 0, supervisor.requestRegistry.count())
        val outcome = requireNotNull(facade.terminalOutcome(requestId))
        assertEquals(RequestState.Failed, outcome.finalState)
        val terminalError = requireNotNull(outcome.terminalError)
        assertEquals(ErrorCategory.Native, terminalError.category)
        assertEquals(requestId, terminalError.identity?.requestId)
        assertEquals(requestId, terminalError.traceId)

        val snapshot = requireNotNull(facade.snapshot(requestId))
        assertEquals(outcome.terminalError, snapshot.terminalError)
    }
}
