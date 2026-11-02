package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshToolCallAssemblerTest {

    private fun assembler(
        maxCalls: Int = 8,
        maxArgumentChars: Int = 1_000_000
    ): DshToolCallAssembler =
        DshToolCallAssembler(
            maxCalls = maxCalls,
            callId = "dsh:7:3",
            maxArgumentChars = maxArgumentChars
        )

    @Test
    fun holdsArgumentsUntilTheNameIsKnownThenEmitsOneCompleteFirstDelta() {
        val assembler = assembler()
        assertTrue(
            assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", null, "{\"path\":")).isEmpty()
        )
        val emitted = assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, "read_file", "\"a\"}"))
        assertEquals(1, emitted.size)
        assertEquals(0, emitted[0].index)
        assertEquals("call_1", emitted[0].toolCallId)
        assertEquals("read_file", emitted[0].name)
        assertEquals("{\"path\":\"a\"}", emitted[0].argumentsFragment)

        val continuation = assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, null, " "))
        assertEquals(1, continuation.size)
        assertEquals(null, continuation[0].toolCallId)
        assertEquals(null, continuation[0].name)
        assertEquals(" ", continuation[0].argumentsFragment)
    }

    @Test
    fun interleavedIndicesKeepStableIdentityAndOrder() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_a", "read_file", "{\"p\":"))
        assembler.onDelta(DshModelEvent.ToolCallDelta(1, "call_b", "shell", "{\"cmd\":"))
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, null, "\"x\"}"))
        assembler.onDelta(DshModelEvent.ToolCallDelta(1, null, null, "\"ls\"}"))
        val outcome = assembler.terminate(
            DshModelEvent.Completed(DshFinishReason.TOOL_CALLS, promptTokens = 4, completionTokens = 2)
        )
        assertTrue(outcome is DshStreamOutcome.Completed)
        val completed = outcome as DshStreamOutcome.Completed
        assertEquals(DshFinishReason.TOOL_CALLS, completed.finishReason)
        assertEquals(listOf(0, 1), completed.toolCalls.map { it.index })
        assertEquals(listOf("call_a", "call_b"), completed.toolCalls.map { it.id })
        assertEquals(listOf("read_file", "shell"), completed.toolCalls.map { it.name })
        assertEquals("""{"p":"x"}""", completed.toolCalls[0].argumentsJson)
        assertEquals("""{"cmd":"ls"}""", completed.toolCalls[1].argumentsJson)
    }

    @Test
    fun missingNameIsAProtocolFailureNotAToolCallsTerminal() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", null, "{}"))
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertTrue(outcome is DshStreamOutcome.Failed)
        assertEquals("invalid_tool_call", (outcome as DshStreamOutcome.Failed).code)
    }

    @Test
    fun toolCallsFinishWithoutAnyToolCallIsAProtocolFailure() {
        val outcome = assembler().terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertTrue(outcome is DshStreamOutcome.Failed)
        assertEquals("invalid_tool_calls", (outcome as DshStreamOutcome.Failed).code)
    }

    @Test
    fun invalidCompleteArgumentsFailTheTerminal() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{\"unterminated\":"))
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertTrue(outcome is DshStreamOutcome.Failed)
        assertEquals("invalid_tool_call", (outcome as DshStreamOutcome.Failed).code)
    }

    @Test
    fun missingArgumentFragmentsAreNeverSynthesizedIntoExecutableArguments() {
        val assembler = assembler()
        val emitted = assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", null))
        assertEquals(1, emitted.size)
        assertEquals("call_1", emitted.single().toolCallId)
        assertEquals("fn", emitted.single().name)
        assertEquals(null, emitted.single().argumentsFragment)
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertTrue(outcome is DshStreamOutcome.Failed)
        assertEquals("invalid_tool_call", (outcome as DshStreamOutcome.Failed).code)
        assertTrue(outcome.message.contains("without any argument fragments"))
    }

    @Test
    fun emptyStringFragmentsDoNotCountAsObservedArguments() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", ""))
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, null, ""))
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertTrue(outcome is DshStreamOutcome.Failed)
        assertEquals("invalid_tool_call", (outcome as DshStreamOutcome.Failed).code)
    }

    @Test
    fun argumentSizeCapIsEnforcedBeforeTerminal() {
        val assembler = assembler(maxArgumentChars = 32)
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{\"a\":"))
        val failure = runCatching {
            assembler.onDelta(
                DshModelEvent.ToolCallDelta(0, null, null, "\"" + "x".repeat(64) + "\"}")
            )
        }
        val error = failure.exceptionOrNull()
        assertTrue(error is DshToolCallProtocolException)
        assertEquals("tool_arguments_too_large", (error as DshToolCallProtocolException).code)
    }

    @Test
    fun idMutationOnTheSameIndexIsRejectedBeforeAndAfterFlush() {
        val beforeFlush = assembler()
        beforeFlush.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", null, "{\"a\":"))
        val beforeFailure = runCatching {
            beforeFlush.onDelta(DshModelEvent.ToolCallDelta(0, "call_2", "fn", "1}"))
        }
        assertTrue(beforeFailure.exceptionOrNull() is DshToolCallProtocolException)

        val afterFlush = assembler()
        afterFlush.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{}"))
        val afterFailure = runCatching {
            afterFlush.onDelta(DshModelEvent.ToolCallDelta(0, "call_2", null, "{}"))
        }
        assertTrue(afterFailure.exceptionOrNull() is DshToolCallProtocolException)
    }

    @Test
    fun repeatingTheSameIdAndNameIsAccepted() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{"))
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", null, "\"a\":"))
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "1}"))
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertEquals("""{"a":1}""", (outcome as DshStreamOutcome.Completed).toolCalls.single().argumentsJson)
    }

    @Test
    fun crossIndexDuplicateIdsAreRejected() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_dup", "a", "{}"))
        val failure = runCatching {
            assembler.onDelta(DshModelEvent.ToolCallDelta(1, "call_dup", "b", "{}"))
        }
        val error = failure.exceptionOrNull()
        assertTrue(error is DshToolCallProtocolException)
        assertEquals("invalid_tool_call", (error as DshToolCallProtocolException).code)
    }

    @Test
    fun missingIdIsMintedDeterministically() {
        val assembler = assembler()
        val emitted = assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, "fn", "{}"))
        assertEquals("call_dsh_7_3_0", emitted.single().toolCallId)
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS))
        assertEquals(
            "call_dsh_7_3_0",
            (outcome as DshStreamOutcome.Completed).toolCalls.single().id
        )
    }

    @Test
    fun stopTerminalMayCarryToolCallsAndKeepsTheStopReason() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{}"))
        val outcome = assembler.terminate(DshModelEvent.Completed(DshFinishReason.STOP))
        assertEquals(DshFinishReason.STOP, (outcome as DshStreamOutcome.Completed).finishReason)
        assertEquals(1, outcome.toolCalls.size)
    }

    @Test
    fun cancelledAndErrorTerminalsAreNotRewrittenToStop() {
        val cancelled = assembler().terminate(DshModelEvent.Completed(DshFinishReason.CANCELLED))
        assertTrue(cancelled is DshStreamOutcome.Cancelled)
        val error = assembler().terminate(DshModelEvent.Completed(DshFinishReason.ERROR))
        assertTrue(error is DshStreamOutcome.Failed)
        assertEquals("backend_error_terminal", (error as DshStreamOutcome.Failed).code)
    }

    @Test
    fun cancelledOrErrorTerminalBeatsAnIncompleteHeldToolEnvelope() {
        val cancelled = assembler()
        cancelled.onDelta(DshModelEvent.ToolCallDelta(0, "call_a", "fn", null))
        assertTrue(
            cancelled.terminate(DshModelEvent.Completed(DshFinishReason.CANCELLED))
                is DshStreamOutcome.Cancelled
        )

        val errored = assembler()
        errored.onDelta(DshModelEvent.ToolCallDelta(0, "call_a", "fn", null))
        val outcome = errored.terminate(DshModelEvent.Completed(DshFinishReason.ERROR))
        assertEquals("backend_error_terminal", (outcome as DshStreamOutcome.Failed).code)
    }

    @Test
    fun nameChangeAndCallOverflowAreProtocolFailures() {
        val assembler = assembler()
        assembler.onDelta(DshModelEvent.ToolCallDelta(0, "call_1", "fn", "{}"))
        val nameChange = runCatching {
            assembler.onDelta(DshModelEvent.ToolCallDelta(0, null, "other", "{}"))
        }
        assertTrue(nameChange.exceptionOrNull() is DshToolCallProtocolException)

        val bounded = assembler(maxCalls = 1)
        bounded.onDelta(DshModelEvent.ToolCallDelta(0, "a", "fn", "{}"))
        val overflow = runCatching {
            bounded.onDelta(DshModelEvent.ToolCallDelta(1, "b", "fn", "{}"))
        }
        assertTrue(overflow.exceptionOrNull() is DshToolCallProtocolException)
    }
}
