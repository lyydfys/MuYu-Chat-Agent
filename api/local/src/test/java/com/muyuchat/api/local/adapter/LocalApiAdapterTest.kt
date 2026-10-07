package com.muyuchat.api.local.adapter

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.CallbackEventType
import com.muyuchat.core.engine.contract.CallbackSource
import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.ProgressPayload
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.Retryability
import com.muyuchat.core.engine.contract.TerminalPayload
import com.muyuchat.core.engine.contract.TokenPayload
import com.muyuchat.core.engine.contract.error.StableErrorCode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiAdapterTest {

    @Test
    fun `validateAuth allows permissive mode when no expected token is configured`() {
        val result = LocalApiRequestMapper.validateAuth(null, expectedToken = null)
        assertTrue(result is AuthValidationResult.Success)
    }

    @Test
    fun `validateAuth rejects missing or empty Authorization header`() {
        val result = LocalApiRequestMapper.validateAuth(null, expectedToken = "secret-token-123")
        assertTrue(result is AuthValidationResult.Failure)
        val failure = result as AuthValidationResult.Failure
        assertEquals(401, failure.httpStatus)
        assertEquals("missing_authorization_header", failure.errorCode)
    }

    @Test
    fun `validateAuth rejects non-Bearer scheme`() {
        val result = LocalApiRequestMapper.validateAuth("Basic dXNlcjpwYXNz", expectedToken = "secret-token-123")
        assertTrue(result is AuthValidationResult.Failure)
        val failure = result as AuthValidationResult.Failure
        assertEquals(401, failure.httpStatus)
        assertEquals("invalid_auth_scheme", failure.errorCode)
    }

    @Test
    fun `validateAuth rejects invalid token`() {
        val result = LocalApiRequestMapper.validateAuth("Bearer wrong-token-123", expectedToken = "secret-token-123")
        assertTrue(result is AuthValidationResult.Failure)
        val failure = result as AuthValidationResult.Failure
        assertEquals(403, failure.httpStatus)
        assertEquals("invalid_credentials", failure.errorCode)
    }

    @Test
    fun `validateAuth succeeds on exact matching Bearer token`() {
        val result = LocalApiRequestMapper.validateAuth("Bearer secret-token-123", expectedToken = "secret-token-123")
        assertTrue(result is AuthValidationResult.Success)
        val success = result as AuthValidationResult.Success
        assertEquals("secret-token-123", success.token)
    }

    @Test
    fun `mapChatCompletion parses OpenAI style messages array`() {
        val json = """
            {
                "model": "qwen2.5-7b",
                "messages": [
                    {"role": "user", "content": "Hello MCA"}
                ],
                "temperature": 0.8,
                "max_tokens": 1024,
                "idempotency_key": "idemp-001"
            }
        """.trimIndent()

        val req = LocalApiRequestMapper.mapChatCompletion(json).getOrThrow()
        assertEquals("Hello MCA", req.prompt)
        assertEquals("qwen2.5-7b", req.model)
        assertEquals(0.8f, req.temperature, 0.01f)
        assertEquals(1024, req.maxTokens)
        assertEquals("idemp-001", req.idempotencyKey)
    }

    @Test
    fun `mapChatCompletion rejects empty prompt or malformed payload`() {
        val result = LocalApiRequestMapper.mapChatCompletion("""{"model":"foo"}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `LocalApiEventBridge maps tokens to valid OpenAI SSE chunk wire format`() {
        val bridge = LocalApiEventBridge()
        val identity = RequestIdentity(requestId = "test-req-1", runtimeEpoch = 1L)
        val event = CallbackEvent.token(
            identity = identity,
            sequence = 1L,
            text = "Hello world",
            tokenIndex = 0L
        )

        val frame = bridge.mapEventToSse(event)
        assertNotNull(frame)
        assertEquals("1", frame!!.id)
        assertEquals("token", frame.event)

        val wire = frame.toWire()
        assertTrue(wire.contains("id: 1\n"))
        assertTrue(wire.contains("event: token\n"))
        assertTrue(wire.contains("data: "))

        val root = JSONObject(frame.data)
        assertEquals("test-req-1", root.getString("id"))
        assertEquals("chat.completion.chunk", root.getString("object"))
        val delta = root.getJSONArray("choices").getJSONObject(0).getJSONObject("delta")
        assertEquals("Hello world", delta.getString("content"))
    }

    @Test
    fun `LocalApiEventBridge supports reconnect filtering via resumeAfterSequence`() {
        val bridge = LocalApiEventBridge(resumeAfterSequence = 5L)
        val identity = RequestIdentity(requestId = "test-req-1", runtimeEpoch = 1L)

        // Events 1 through 5 should be ignored
        val eventOld = CallbackEvent.token(identity, sequence = 5L, text = "Old", tokenIndex = 0L)
        assertNull(bridge.mapEventToSse(eventOld))

        // Event 6 should be accepted
        val eventNew = CallbackEvent.token(identity, sequence = 6L, text = "New", tokenIndex = 1L)
        val frameNew = bridge.mapEventToSse(eventNew)
        assertNotNull(frameNew)
        assertEquals("6", frameNew!!.id)
    }

    @Test
    fun `LocalApiEventBridge guarantees exactly-once terminal emission`() {
        val bridge = LocalApiEventBridge()
        val identity = RequestIdentity(requestId = "test-req-1", runtimeEpoch = 1L)

        val terminalEvent1 = CallbackEvent.terminal(identity, sequence = 10L, finalState = RequestState.Succeeded)
        val terminalFrame1 = bridge.mapEventToSse(terminalEvent1)
        assertNotNull(terminalFrame1)
        assertEquals("done", terminalFrame1!!.event)
        assertEquals("[DONE]", terminalFrame1.data)
        assertTrue(bridge.isTerminalEmitted)

        // Subsequent terminal or error events must return null
        val terminalEvent2 = CallbackEvent.terminal(identity, sequence = 11L, finalState = RequestState.Succeeded)
        assertNull(bridge.mapEventToSse(terminalEvent2))

        val errorEvent = CallbackEvent.error(
            identity,
            sequence = 12L,
            error = ErrorEnvelope(
                code = StableErrorCode.RT_CRASHED,
                category = ErrorCategory.Runtime,
                phase = ErrorPhase.Execute,
                retryability = Retryability.Never,
                userMessageKey = "err",
                publicMessage = "Late error"
            )
        )
        assertNull(bridge.mapEventToSse(errorEvent))
    }

    @Test
    fun `LocalApiEventBridge bridgeFlow transforms event flow into SSE frame flow`() = runBlocking {
        val bridge = LocalApiEventBridge()
        val identity = RequestIdentity(requestId = "test-flow", runtimeEpoch = 1L)

        val events = flowOf(
            CallbackEvent.token(identity, sequence = 1L, text = "Part 1", tokenIndex = 0L),
            CallbackEvent.token(identity, sequence = 2L, text = "Part 2", tokenIndex = 1L),
            CallbackEvent.terminal(identity, sequence = 3L, finalState = RequestState.Succeeded)
        )

        val frames = bridge.bridgeFlow(events).toList()
        assertEquals(3, frames.size)
        assertEquals("1", frames[0].id)
        assertEquals("2", frames[1].id)
        assertEquals("[DONE]", frames[2].data)
    }
}
