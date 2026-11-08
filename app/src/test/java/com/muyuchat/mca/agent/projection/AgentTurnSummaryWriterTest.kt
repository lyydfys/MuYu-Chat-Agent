package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.agentContextProjection
import com.muyuchat.mca.agent.data.AgentTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the Agent-terminal → chat-summary writer.
 *
 * The point of the whole feature is "only the final summary of a *successful* run may re-enter the
 * model context", so these cases pin the three ways that promise can break: writing for a failed
 * run, writing an empty message, and writing something the projection would then drop or keep
 * verbatim.
 */
class AgentTurnSummaryWriterTest {

    @Test
    fun `a succeeded run with terminal text produces one summary assistant message`() {
        val run = succeededRun()
        val message = requireNotNull(
            AgentTurnSummaryWriter.messageFor(
                run = run,
                events = listOf(terminalEvent(summary = "已整理 notes.md 的要点并写入 summary.md。")),
                chatSessionId = SESSION_ID,
                createdAtMillis = 5_000L
            )
        )

        assertEquals(Role.ASSISTANT, message.role)
        assertEquals("已整理 notes.md 的要点并写入 summary.md。", message.content)
        assertEquals(5_000L, message.createdAt)
        val trace = requireNotNull(message.agentTrace)
        assertEquals(run.runId, trace.turnId)
        assertEquals(AgentTurnTrace.SUMMARY, trace.kind)
        assertEquals(AgentTurnSummaryWriter.SUMMARY_KIND, trace.kind)
        assertEquals(message.content, trace.finalSummary)
        assertTrue(trace.carriesContextSummary)
    }

    @Test
    fun `a failed run never produces a summary message`() {
        val run = succeededRun().copy(state = AgentRunState.FAILED)

        assertNull(
            AgentTurnSummaryWriter.messageFor(
                run = run,
                // Even a non-blank terminal summary must not be replayed after a failure.
                events = listOf(terminalEvent(summary = "写到一半就失败了")),
                chatSessionId = SESSION_ID
            )
        )
    }

    @Test
    fun `every non-success terminal state stays silent`() {
        val terminalStates = AgentRunState.entries.filter { it.isTerminal }

        terminalStates.forEach { state ->
            val produced = AgentTurnSummaryWriter.messageFor(
                run = succeededRun().copy(state = state),
                events = listOf(terminalEvent(summary = "看起来像结论文本")),
                chatSessionId = SESSION_ID
            )
            if (state == AgentRunState.SUCCEEDED) {
                assertNotNull("$state must produce a summary", produced)
            } else {
                assertNull("$state must never write a summary", produced)
            }
        }
    }

    @Test
    fun `a blank or missing summary produces no message`() {
        val run = succeededRun()

        assertNull(
            AgentTurnSummaryWriter.messageFor(
                run = run,
                events = listOf(terminalEvent(summary = "   \n\t ")),
                chatSessionId = SESSION_ID
            )
        )
        assertNull(
            AgentTurnSummaryWriter.messageFor(
                run = run,
                events = listOf(terminalEvent(summary = null)),
                chatSessionId = SESSION_ID
            )
        )
        assertNull(
            AgentTurnSummaryWriter.messageFor(run = run, events = emptyList(), chatSessionId = SESSION_ID)
        )
    }

    @Test
    fun `a message without a chat session is refused instead of orphaned`() {
        val events = listOf(terminalEvent(summary = "结论"))

        assertNull(AgentTurnSummaryWriter.messageFor(succeededRun(), events, chatSessionId = null))
        assertNull(AgentTurnSummaryWriter.messageFor(succeededRun(), events, chatSessionId = "  "))
    }

    @Test
    fun `only this run's terminal event is read`() {
        val run = succeededRun()
        val foreign = terminalEvent(summary = "别的 run 的结论", runId = "run-other", sequence = 9L)

        assertNull(AgentTurnSummaryWriter.messageFor(run, listOf(foreign), SESSION_ID))

        val mixed = listOf(foreign, terminalEvent(summary = "本 run 的结论", sequence = 10L))
        assertEquals("本 run 的结论", AgentTurnSummaryWriter.boundedSummary(run, mixed))
    }

    @Test
    fun `the newest terminal summary wins over an older one`() {
        val run = succeededRun()
        val events = listOf(
            terminalEvent(summary = "旧结论", sequence = 3L),
            terminalEvent(summary = "新结论", sequence = 8L)
        )

        assertEquals("新结论", AgentTurnSummaryWriter.boundedSummary(run, events))
    }

    @Test
    fun `the produced message survives the context projection with summary as its content`() {
        val run = succeededRun()
        val summary = "整理完成：3 条结论。"
        val produced = requireNotNull(
            AgentTurnSummaryWriter.messageFor(
                run = run,
                events = listOf(terminalEvent(summary = summary)),
                chatSessionId = SESSION_ID
            )
        )
        val otherTurn = com.muyuchat.core.engine.ChatMessage(
            role = Role.ASSISTANT,
            content = "原始 agent 步骤内容",
            agentTrace = AgentTurnTrace(turnId = "run-other", kind = "step")
        )

        val projected = listOf(otherTurn, produced).agentContextProjection()

        assertEquals(1, projected.size)
        assertSame(produced, projected.single())
        assertEquals(summary, projected.single().content)
        assertEquals(AgentTurnTrace.SUMMARY, projected.single().agentTrace?.kind)
    }

    // ---------------------------------------------------------------- fixtures

    private fun succeededRun(): AgentRun =
        AgentTestFixtures.run(state = AgentRunState.SUCCEEDED)

    private fun terminalEvent(
        summary: String?,
        runId: String = AgentTestFixtures.RUN_ID,
        sequence: Long = 7L
    ): AgentEvent = AgentEvent(
        eventId = "event-$sequence-$runId",
        runId = runId,
        sequence = sequence,
        type = AgentEventTypes.RUN_TERMINAL,
        createdAtMillis = 1_000L + sequence,
        summary = summary
    )

    private companion object {
        const val SESSION_ID = "chat-session-1"
    }
}
