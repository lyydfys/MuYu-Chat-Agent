package com.muyuchat.mca.agent.projection

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two properties that make the stop handoff safe to show in a chat:
 *
 * 1. it appears only for a *paused* run (the one state the continue planner accepts), and
 * 2. it can never become part of a later prompt, because its trace kind is `step` and its
 *    `finalSummary` is blank — the projection keeps only `summary` traces with a non-blank summary.
 *
 * The rendered line is also checked for the two affordances the user needs: how far it got, and the
 * explicit "说「继续」" offer. Without those the bubble is noise.
 */
class AgentStopHandoffWriterTest {

    private fun event(
        runId: String = RUN_ID,
        type: String,
        summary: String? = null,
        sequence: Long = 1L
    ): AgentEvent = AgentEvent(
        eventId = "evt-$sequence",
        runId = runId,
        sequence = sequence,
        type = type,
        createdAtMillis = 1_000L,
        summary = summary
    )

    private fun run(state: AgentRunState): AgentRun =
        com.muyuchat.mca.agent.data.AgentTestFixtures.run(runId = RUN_ID, state = state)

    private val busyEvents = listOf(
        event(type = AgentEventTypes.MODEL_ATTEMPT_SETTLED, summary = "我先看看工作区", sequence = 1L),
        event(type = AgentEventTypes.TOOL_RESULT, summary = "三个文件", sequence = 2L),
        event(type = AgentEventTypes.MODEL_ATTEMPT_SETTLED, summary = "准备改写报告", sequence = 3L)
    )

    @Test
    fun `a paused run produces a visible handoff`() {
        val message = AgentStopHandoffWriter.messageFor(
            run = run(AgentRunState.PAUSED),
            events = busyEvents,
            chatSessionId = SESSION_ID,
            createdAtMillis = 3_000L
        )
        requireNotNull(message)
        assertEquals(Role.ASSISTANT, message.role)
        assertEquals(3_000L, message.createdAt)
        val text = message.content
        assertTrue("must say how far it got: $text", text.contains("2 步"))
        assertTrue("must say how many tools: $text", text.contains("1 次工具"))
        assertTrue("must offer the continue affordance: $text", text.contains("继续"))
        assertTrue("must stay one short line, was ${text.length}", text.length <= 200)
    }

    @Test
    fun `the handoff can never enter a later prompt`() {
        val message = AgentStopHandoffWriter.messageFor(
            run = run(AgentRunState.PAUSED),
            events = busyEvents,
            chatSessionId = SESSION_ID
        )
        requireNotNull(message)
        val trace = requireNotNull(message.agentTrace)
        assertEquals("step", trace.kind)
        assertEquals("", trace.finalSummary)
        // The one predicate the projection actually consults.
        assertTrue("a handoff must not be summarisable", !trace.carriesContextSummary)
    }

    @Test
    fun `cancelled runs get no handoff because continue would be refused`() {
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.CANCELLED),
                events = busyEvents,
                chatSessionId = SESSION_ID
            )
        )
    }

    @Test
    fun `failed and interrupted runs get no handoff`() {
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.FAILED),
                events = busyEvents,
                chatSessionId = SESSION_ID
            )
        )
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.INTERRUPTED),
                events = busyEvents,
                chatSessionId = SESSION_ID
            )
        )
    }

    @Test
    fun `a paused run with no visible activity stays silent`() {
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.PAUSED),
                events = listOf(event(type = AgentEventTypes.RUN_QUEUED)),
                chatSessionId = SESSION_ID
            )
        )
    }

    @Test
    fun `events of another run never describe this run`() {
        val foreign = listOf(
            event(runId = "other", type = AgentEventTypes.MODEL_ATTEMPT_SETTLED, summary = "别人的活", sequence = 1L)
        )
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.PAUSED),
                events = foreign,
                chatSessionId = SESSION_ID
            )
        )
    }

    @Test
    fun `a blank session has nowhere to write`() {
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.PAUSED),
                events = busyEvents,
                chatSessionId = null
            )
        )
        assertNull(
            AgentStopHandoffWriter.messageFor(
                run = run(AgentRunState.PAUSED),
                events = busyEvents,
                chatSessionId = "   "
            )
        )
    }

    @Test
    fun `facts are bounded so one huge result cannot crowd out the rest`() {
        val huge = "x".repeat(500)
        val message = AgentStopHandoffWriter.messageFor(
            run = run(AgentRunState.PAUSED),
            events = listOf(
                event(type = AgentEventTypes.MODEL_ATTEMPT_SETTLED, summary = huge, sequence = 1L),
                event(type = AgentEventTypes.TOOL_RESULT, summary = huge, sequence = 2L)
            ),
            chatSessionId = SESSION_ID
        )
        requireNotNull(message)
        assertTrue("the bubble must stay bounded, was ${message.content.length}", message.content.length <= 200)
        assertTrue("a cut fact says so", message.content.contains("…"))
    }

    private companion object {
        const val RUN_ID = "run-1"
        const val SESSION_ID = "session-1"
    }
}
