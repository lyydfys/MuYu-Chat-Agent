package com.muyuchat.core.engine.contract

import com.muyuchat.core.engine.contract.error.StableErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallbackEventTest {

    private val identity = RequestIdentity(
        requestId = "event-test-req",
        attemptId = 0L,
        sessionGeneration = 1L,
        runtimeEpoch = 2L,
        workerIncarnation = 3L,
        nativeSequence = 10L,
        traceId = "tr-10"
    )

    @Test
    fun `token event creation and payload properties`() {
        val event = CallbackEvent.token(
            identity = identity,
            sequence = 10L,
            text = "Hello, MCA",
            tokenIndex = 0L
        )

        assertEquals(CallbackEventType.Token, event.eventType)
        assertFalse(event.isTerminal)
        assertEquals(CallbackSource.Native, event.source)
        val payload = event.payload as TokenPayload
        assertEquals("Hello, MCA", payload.text)
        assertEquals(0L, payload.index)
        assertFalse(payload.isSpecial)
    }

    @Test
    fun `progress event percentage computation`() {
        val event = CallbackEvent.progress(
            identity = identity,
            sequence = 11L,
            current = 25f,
            total = 100f,
            stage = "UnetStep"
        )

        assertEquals(CallbackEventType.Progress, event.eventType)
        assertFalse(event.isTerminal)
        val payload = event.payload as ProgressPayload
        assertEquals(25.0f, payload.percentage, 0.001f)
        assertEquals("UnetStep", payload.stage)
    }

    @Test
    fun `terminal event validation`() {
        val event = CallbackEvent.terminal(
            identity = identity,
            sequence = 50L,
            finalState = RequestState.Succeeded,
            totalTokens = 42L,
            elapsedMs = 1250L
        )

        assertEquals(CallbackEventType.Terminal, event.eventType)
        assertTrue(event.isTerminal)
        val payload = event.payload as TerminalPayload
        assertEquals(RequestState.Succeeded, payload.finalState)
        assertEquals(42L, payload.totalTokens)
        assertEquals(1250L, payload.elapsedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non terminal state in TerminalPayload throws`() {
        TerminalPayload(finalState = RequestState.Running)
    }

    @Test
    fun `isStaleFor delegates to RequestIdentity`() {
        val event = CallbackEvent.token(identity, sequence = 1L, text = "x", tokenIndex = 0L)
        // Current epoch is 2; if active is 3, event is stale
        assertTrue(event.isStaleFor(activeEpoch = 3L, activeGeneration = 1L, activeWorker = 3L))
        assertFalse(event.isStaleFor(activeEpoch = 2L, activeGeneration = 1L, activeWorker = 3L))
    }
}
