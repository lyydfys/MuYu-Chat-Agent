package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C06 terminal/usage/tool-delta integrity over the real loopback wire.
 *
 * These tests drive the terminal path from an explicit producer script; the gateway may not infer
 * a reason from token counts, content length, or event ordering. They also pin the single-terminal
 * rule, unknown/zero/partial usage semantics, tool identity/argument invariants and SSE/buffered
 * equivalence.
 */
class DshGatewayC06IntegrationTest : DshGatewayServerTestBase() {

    @Test
    fun duplicateAndPostTerminalEventsAreIgnoredAfterTheFirstTerminal() {
        backend.streamFactory = {
            flow {
                emit(DshModelEvent.TextDelta("one"))
                emit(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 2, completionTokens = 1))
                emit(DshModelEvent.Completed(DshFinishReason.LENGTH, promptTokens = 9, completionTokens = 9))
                emit(DshModelEvent.Failed("late_failure", "late", retryable = true))
                emit(DshModelEvent.TextDelta("late"))
                awaitCancellation()
            }
        }

        val buffered = chat(stream = false)
        assertEquals(200, buffered.status)
        val choice = buffered.json().getJSONArray("choices").getJSONObject(0)
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("one", choice.getJSONObject("message").getString("content"))
        assertEquals(2, buffered.json().getJSONObject("usage").getInt("prompt_tokens"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals("[DONE]", events.last())
            assertEquals(listOf("stop"), finishReasons(events))
            assertFalse(events.any { it.contains("late") })
            val frames = events.dropLast(1).map { JSONObject(it) }
            assertEquals(2, frames.last().getJSONObject("usage").getInt("prompt_tokens"))
            assertEquals(1, frames.last().getJSONObject("usage").getInt("completion_tokens"))
        } finally {
            sse.close()
        }
    }

    @Test
    fun firstTerminalErrorWinsOverALaterStopTerminal() {
        backend.streamFactory = {
            flow {
                emit(DshModelEvent.Completed(DshFinishReason.ERROR))
                emit(DshModelEvent.Completed(DshFinishReason.STOP))
                awaitCancellation()
            }
        }

        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals(
            "backend_error_terminal",
            buffered.json().getJSONObject("error").getString("code")
        )

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals(listOf("error"), finishReasons(events))
            val frames = events.dropLast(1).map { JSONObject(it) }
            assertEquals(
                1,
                frames.count { it.optJSONObject("error") != null }
            )
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun producerLengthReasonWithZeroUsageIsPreservedOnBothWireModes() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("cut"),
                DshModelEvent.Completed(DshFinishReason.LENGTH, promptTokens = 0, completionTokens = 0)
            )
        }

        val buffered = chat(stream = false)
        assertEquals(200, buffered.status)
        assertEquals(
            "length",
            buffered.json().getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )
        val bufferedUsage = buffered.json().getJSONObject("usage")
        assertEquals(0, bufferedUsage.getInt("prompt_tokens"))
        assertEquals(0, bufferedUsage.getInt("completion_tokens"))
        assertEquals(0, bufferedUsage.getInt("total_tokens"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals(listOf("length"), finishReasons(events))
            val frames = events.dropLast(1).map { JSONObject(it) }
            val usage = frames.last().getJSONObject("usage")
            assertEquals(0, usage.getInt("prompt_tokens"))
            assertEquals(0, usage.getInt("completion_tokens"))
            assertEquals(0, usage.getInt("total_tokens"))
        } finally {
            sse.close()
        }
    }

    @Test
    fun stopReasonIsNotInferredFromLargeTokenCounts() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("done"),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 32_768, completionTokens = 4_096)
            )
        }

        val buffered = chat(stream = false)
        assertEquals(
            "stop",
            buffered.json().getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )
        assertEquals(32_768, buffered.json().getJSONObject("usage").getInt("prompt_tokens"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            assertEquals(listOf("stop"), finishReasons(sse.readUntilDone()))
        } finally {
            sse.close()
        }
    }

    @Test
    fun unknownUsageIsOmittedAndPartialUsageKeepsItsUnknownSide() {
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = null, completionTokens = null))
        }
        val unknown = chat(stream = false)
        assertEquals(200, unknown.status)
        assertFalse(unknown.json().has("usage"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertFalse(events.dropLast(1).any { it.contains("\"usage\"") })
        } finally {
            sse.close()
        }

        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 7, completionTokens = null))
        }
        val partial = chat(stream = false)
        assertEquals(200, partial.status)
        val usage = partial.json().getJSONObject("usage")
        assertEquals(7, usage.getInt("prompt_tokens"))
        assertTrue(usage.isNull("completion_tokens"))
        assertTrue(usage.isNull("total_tokens"))
    }

    @Test
    fun duplicateToolCallIdsAcrossIndexesFailWithoutAToolCallsTerminal() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_same", "read_file", "{}"),
                DshModelEvent.ToolCallDelta(1, "call_same", "shell", "{}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }

        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals("invalid_tool_call", buffered.json().getJSONObject("error").getString("code"))

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals(listOf("error"), finishReasons(events))
            val frames = events.dropLast(1).map { JSONObject(it) }
            val error = frames.mapNotNull { it.optJSONObject("error") }.single()
            assertEquals("invalid_tool_call", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"tool_calls\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun toolCallIdMutationAfterTheFirstDeltaFailsWithoutAToolCallsTerminal() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", "{}"),
                DshModelEvent.ToolCallDelta(0, "call_b", null, "{}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals(listOf("error"), finishReasons(events))
            val error = events.dropLast(1).map { JSONObject(it) }
                .mapNotNull { it.optJSONObject("error") }
                .single()
            assertEquals("invalid_tool_call", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"tool_calls\"") })
        } finally {
            sse.close()
        }
    }

    @Test
    fun missingArgumentFragmentsFailInsteadOfEmittingExecutableEmptyJson() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", null),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            assertEquals(listOf("error"), finishReasons(events))
            assertFalse(events.any { it.contains("\"arguments\":\"{}\"") })
            assertFalse(events.any { it.contains("\"finish_reason\":\"tool_calls\"") })
        } finally {
            sse.close()
        }

        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", null),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }
        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals("invalid_tool_call", buffered.json().getJSONObject("error").getString("code"))
    }

    @Test
    fun sseAndBufferedAgreeOnTextReasoningToolCallsUsageAndFinish() {
        val script: () -> kotlinx.coroutines.flow.Flow<DshModelEvent> = {
            flowOf(
                DshModelEvent.TextDelta("answer"),
                DshModelEvent.ReasoningDelta("think"),
                DshModelEvent.ToolCallDelta(0, "call_a", "read_file", "{\"path\":"),
                DshModelEvent.ToolCallDelta(0, null, null, "\"a\"}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS, promptTokens = 15, completionTokens = 7)
            )
        }
        backend.streamFactory = { script() }
        val buffered = chat(stream = false)
        assertEquals(200, buffered.status)
        val bufferedChoice = buffered.json().getJSONArray("choices").getJSONObject(0)
        val bufferedMessage = bufferedChoice.getJSONObject("message")
        val bufferedCall = bufferedMessage.getJSONArray("tool_calls").getJSONObject(0)

        backend.streamFactory = { script() }
        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            val frames = events.dropLast(1).map { JSONObject(it) }
            val deltas = frames.flatMap { frame ->
                val choices = frame.optJSONArray("choices") ?: return@flatMap emptyList()
                if (choices.length() == 0) return@flatMap emptyList()
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return@flatMap emptyList()
                val toolCalls = delta.optJSONArray("tool_calls") ?: return@flatMap emptyList()
                (0 until toolCalls.length()).map { toolCalls.getJSONObject(it) }
            }
            val content = frames.joinToString("") { frame ->
                val choices = frame.optJSONArray("choices") ?: return@joinToString ""
                if (choices.length() == 0) return@joinToString ""
                choices.getJSONObject(0).optJSONObject("delta")?.optString("content").orEmpty()
            }
            val reasoning = frames.joinToString("") { frame ->
                val choices = frame.optJSONArray("choices") ?: return@joinToString ""
                if (choices.length() == 0) return@joinToString ""
                choices.getJSONObject(0).optJSONObject("delta")?.optString("reasoning_content").orEmpty()
            }
            val arguments = deltas.joinToString("") { it.getJSONObject("function").optString("arguments") }
            val usage = frames.last().getJSONObject("usage")

            assertEquals(bufferedMessage.getString("content"), content)
            assertEquals("think", reasoning)
            assertEquals("tool_calls", bufferedChoice.getString("finish_reason"))
            assertEquals(listOf("tool_calls"), finishReasons(events))
            assertEquals(bufferedCall.getString("id"), deltas.first().getString("id"))
            assertEquals(bufferedCall.getJSONObject("function").getString("name"), deltas.first().getJSONObject("function").getString("name"))
            assertEquals(bufferedCall.getJSONObject("function").getString("arguments"), arguments)
            assertEquals(buffered.json().getJSONObject("usage").getInt("prompt_tokens"), usage.getInt("prompt_tokens"))
            assertEquals(buffered.json().getJSONObject("usage").getInt("completion_tokens"), usage.getInt("completion_tokens"))
            assertEquals(buffered.json().getJSONObject("usage").getInt("total_tokens"), usage.getInt("total_tokens"))
        } finally {
            sse.close()
        }
    }

    private fun finishReasons(events: List<String>): List<String> =
        events.dropLast(1).map { JSONObject(it) }.mapNotNull { frame ->
            val choices = frame.optJSONArray("choices") ?: return@mapNotNull null
            if (choices.length() == 0) return@mapNotNull null
            val choice = choices.getJSONObject(0)
            if (choice.isNull("finish_reason")) null else choice.getString("finish_reason")
        }
}

/** C06 size-cap behavior under deliberately small configured limits. */
class DshGatewayC06LimitsIntegrationTest : DshGatewayServerTestBase() {

    override fun limits(): DshGatewayLimits = DshGatewayLimits.Default.copy(
        maxResponseChars = 1_024,
        maxToolArgumentChars = 256
    )

    @Test
    fun oversizedToolArgumentsFailWithADistinctCodeAndNoToolCallsTerminal() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "write", "{\"blob\":\""),
                DshModelEvent.ToolCallDelta(0, null, null, "x".repeat(300) + "\"}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            val error = events.dropLast(1).map { JSONObject(it) }
                .mapNotNull { it.optJSONObject("error") }
                .single()
            assertEquals("tool_arguments_too_large", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"tool_calls\"") })
        } finally {
            sse.close()
        }

        backend.streamFactory = {
            flowOf(
                DshModelEvent.ToolCallDelta(0, "call_a", "write", "{\"blob\":\""),
                DshModelEvent.ToolCallDelta(0, null, null, "x".repeat(300) + "\"}"),
                DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)
            )
        }
        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals("tool_arguments_too_large", buffered.json().getJSONObject("error").getString("code"))
    }

    @Test
    fun totalResponseLimitFailsWithResponseTooLargeInsteadOfStop() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("x".repeat(2_000)),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1)
            )
        }

        val sse = DshTestHttp.openSse(endpoint, standardChatBody(stream = true))
        try {
            val events = sse.readUntilDone()
            val error = events.dropLast(1).map { JSONObject(it) }
                .mapNotNull { it.optJSONObject("error") }
                .single()
            assertEquals("response_too_large", error.getString("code"))
            assertFalse(events.any { it.contains("\"finish_reason\":\"stop\"") })
        } finally {
            sse.close()
        }

        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("x".repeat(2_000)),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1)
            )
        }
        val buffered = chat(stream = false)
        assertEquals(502, buffered.status)
        assertEquals("response_too_large", buffered.json().getJSONObject("error").getString("code"))
    }
}
