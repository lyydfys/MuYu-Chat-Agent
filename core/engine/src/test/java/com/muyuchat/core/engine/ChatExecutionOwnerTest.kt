package com.muyuchat.core.engine

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.Retryability
import com.muyuchat.core.engine.contract.error.StableErrorCode
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeChatBody(
    private val events: () -> Flow<GenerateEvent> = { flowOf() }
) : ChatExecutionBody {
    var stopAllCalls = 0
    var stopIfActiveResult = true
    val stoppedRequestIds = mutableListOf<String>()

    override fun stream(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent> =
        events()

    override suspend fun stopGeneration() {
        stopAllCalls++
    }

    override suspend fun stopGenerationIfActive(requestId: String): Boolean {
        stoppedRequestIds += requestId
        return stopIfActiveResult
    }
}

class ChatExecutionOwnerTest {

    private fun request() = ChatRequest(messages = listOf(ChatMessage(Role.USER, "hi")))

    private fun context(id: String) = LocalChatExecutionContext(requestId = id)

    @Test
    fun successStreamPassesEventsSettlesAndArchivesTheOutcome() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody {
            flowOf(
                GenerateEvent.Chunk("a", RuntimeStats()),
                GenerateEvent.Chunk("b", RuntimeStats()),
                GenerateEvent.Done(RuntimeStats())
            )
        }
        val owner = ChatExecutionOwner(supervisor, body)
        val seen = mutableListOf<GenerateEvent>()
        val statesDuring = mutableListOf<RequestState>()

        owner.stream(request(), context("t-success"))
            .onEach { statesDuring += supervisor.requestRegistry.snapshot("t-success")!!.state }
            .collect { seen += it }

        assertEquals(3, seen.size)
        assertTrue("every delivered event belongs to a Running attempt", statesDuring.all { it == RequestState.Running })
        assertFalse(supervisor.requestRegistry.isActive("t-success"))
        val record = supervisor.callbackRouter.terminalRecord("t-success")
        assertNotNull(record)
        assertEquals(RequestState.Succeeded, record!!.finalState)
    }

    @Test
    fun thrownBodyFailureSettlesFailedAndPropagates() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody { flow { throw IllegalStateException("native exploded") } }
        val owner = ChatExecutionOwner(supervisor, body)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.stream(request(), context("t-fail")).collect { } }
        }
        assertEquals("native exploded", failure.message)
        assertFalse(supervisor.requestRegistry.isActive("t-fail"))
        val record = supervisor.callbackRouter.terminalRecord("t-fail")
        assertEquals(RequestState.Failed, record?.finalState)
        val terminalError = requireNotNull(record?.terminalError)
        assertEquals(StableErrorCode.INT_UNEXPECTED_EXCEPTION, terminalError.code)
        assertEquals("t-fail", terminalError.identity?.requestId)
        assertEquals("t-fail", terminalError.traceId)
    }

    @Test
    fun errorEventWithoutCancellationCodeSettlesFailed() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody {
            flowOf(GenerateEvent.Error("boom", RuntimeStats(), code = "NATIVE_FAILURE"))
        }
        val owner = ChatExecutionOwner(supervisor, body)

        owner.stream(request(), context("t-error")).collect { }

        val record = requireNotNull(supervisor.callbackRouter.terminalRecord("t-error"))
        assertEquals(RequestState.Failed, record.finalState)
        val terminalError = requireNotNull(record.terminalError)
        assertEquals(StableErrorCode.NAT_EXECUTION_FAULT, terminalError.code)
        assertEquals(ErrorCategory.Native, terminalError.category)
        assertEquals("boom", terminalError.publicMessage)
        assertEquals("t-error", terminalError.identity?.requestId)
        assertEquals("t-error", terminalError.traceId)
    }

    @Test
    fun typedErrorEventKeepsEnvelopeCodesIdentityAndTraceThroughCleanup() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val eventEnvelope = ErrorEnvelope(
            code = StableErrorCode.MDL_LOAD_FAILED,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.model.load_failed",
            publicMessage = "The model could not be loaded.",
            causeChain = listOf("native graph rejected the bundle"),
            nativeCode = 203,
            httpCode = 503,
            traceId = "trace-event-42"
        )
        val body = FakeChatBody {
            flowOf(
                GenerateEvent.Error(
                    message = "The model could not be loaded.",
                    stats = RuntimeStats(),
                    code = StableErrorCode.MDL_LOAD_FAILED.name,
                    errorEnvelope = eventEnvelope
                )
            )
        }
        val owner = ChatExecutionOwner(supervisor, body)

        owner.stream(request(), context("t-typed-error")).collect { }

        assertNull("terminal cleanup must evict the live registry entry", supervisor.requestRegistry.snapshot("t-typed-error"))
        val record = requireNotNull(supervisor.callbackRouter.terminalRecord("t-typed-error"))
        assertEquals(RequestState.Failed, record.finalState)
        val terminalError = requireNotNull(record.terminalError)
        assertEquals(record.identity, terminalError.identity)
        assertEquals("trace-event-42", terminalError.traceId)
        assertEquals(203, terminalError.nativeCode)
        assertEquals(503, terminalError.httpCode)
        assertEquals(eventEnvelope.copy(identity = record.identity), terminalError)
    }

    @Test
    fun cancellationErrorEventSettlesCancelled() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody {
            flowOf(GenerateEvent.Error("stopped", RuntimeStats(), code = "REQUEST_CANCELLED"))
        }
        val owner = ChatExecutionOwner(supervisor, body)

        owner.stream(request(), context("t-cancel-event")).collect { }

        val record = requireNotNull(supervisor.callbackRouter.terminalRecord("t-cancel-event"))
        assertEquals(RequestState.Cancelled, record.finalState)
        val terminalError = requireNotNull(record.terminalError)
        assertEquals(ErrorCategory.Cancel, terminalError.category)
        assertEquals(StableErrorCode.CNC_USER_CANCELLED, terminalError.code)
    }

    @Test
    fun collectorCancellationSettlesCancelled() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val firstEvent = CompletableDeferred<Unit>()
        val body = FakeChatBody {
            flow {
                emit(GenerateEvent.Chunk("partial", RuntimeStats()))
                awaitCancellation()
            }
        }
        val owner = ChatExecutionOwner(supervisor, body)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch {
                owner.stream(request(), context("t-collector-cancel"))
                    .onEach { firstEvent.complete(Unit) }
                    .collect { }
            }
            withTimeout(5_000) { firstEvent.await() }
            job.cancel()
            job.join()
        } finally {
            scope.cancel()
        }

        assertFalse(supervisor.requestRegistry.isActive("t-collector-cancel"))
        assertEquals(
            RequestState.Cancelled,
            supervisor.callbackRouter.terminalRecord("t-collector-cancel")?.finalState
        )
        assertEquals(
            ErrorCategory.Cancel,
            supervisor.callbackRouter.terminalRecord("t-collector-cancel")?.terminalError?.category
        )
    }

    @Test
    fun duplicateLiveRequestIdIsAnExplicitConflict() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val firstEvent = CompletableDeferred<Unit>()
        val body = FakeChatBody {
            flow {
                emit(GenerateEvent.Chunk("a", RuntimeStats()))
                awaitCancellation()
            }
        }
        val owner = ChatExecutionOwner(supervisor, body)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch {
                owner.stream(request(), context("t-dup")).onEach { firstEvent.complete(Unit) }.collect { }
            }
            withTimeout(5_000) { firstEvent.await() }

            assertThrows(IllegalStateException::class.java) {
                owner.stream(request(), context("t-dup"))
            }
            job.cancel()
            job.join()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutDownSupervisorRejectsNewTurns() {
        val supervisor = RuntimeSupervisor()
        val owner = ChatExecutionOwner(supervisor, FakeChatBody())
        supervisor.shutdownProcess()

        assertThrows(IllegalStateException::class.java) {
            owner.stream(request(), context("t-shutdown"))
        }
    }

    @Test
    fun stopGenerationIfActiveRoutesToSupervisorAndBody() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody()
        val owner = ChatExecutionOwner(supervisor, body)
        // A live attempt is required for the supervisor-side bookkeeping to have a target.
        supervisor.admitRequest("t-stop")
        supervisor.requestRegistry.transitionState(
            supervisor.requestRegistry.getEntry("t-stop")!!.identity,
            RequestState.Preparing
        )

        assertTrue(owner.stopGenerationIfActive("t-stop"))
        assertEquals(listOf("t-stop"), body.stoppedRequestIds)
        assertTrue(supervisor.cancellationController.isCancellationRequested("t-stop"))

        body.stopIfActiveResult = false
        assertFalse(owner.stopGenerationIfActive("t-stop"))

        assertFalse("blank ids never reach the body", owner.stopGenerationIfActive("  "))
        assertEquals(listOf("t-stop", "t-stop"), body.stoppedRequestIds)
        assertNull(supervisor.requestRegistry.snapshot("nonexistent"))
    }

    @Test
    fun stopGenerationCancelsAllLiveAttemptsAndStopsTheBody() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val body = FakeChatBody()
        val owner = ChatExecutionOwner(supervisor, body)
        supervisor.admitRequest("t-live-1")
        supervisor.admitRequest("t-live-2")

        owner.stopGeneration()

        assertEquals(1, body.stopAllCalls)
        assertTrue(supervisor.cancellationController.isCancellationRequested("t-live-1"))
        assertTrue(supervisor.cancellationController.isCancellationRequested("t-live-2"))
    }
}
