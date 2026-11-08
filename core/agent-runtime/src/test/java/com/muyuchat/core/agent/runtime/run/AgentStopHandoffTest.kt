package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.testing.AgentTestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stop-handoff summary: counts, the last observation, the unfinished intention and the render budget.
 *
 * The first test is the anti-stub canary: it asserts the real tool counts, so hard-coding the counter
 * to 0 (or dropping the [AgentTranscriptEntry.ToolResult] scan) makes it fail.
 */
class AgentStopHandoffTest {

    @Test
    fun `a transcript with tool calls reports the exact counts`() {
        val transcript = listOf(
            assistant("先看 inputs/brief.txt"),
            toolResult("read_file", AgentJsonValue.Str("三行摘要：A、B、C")),
            assistant("再写 artifacts/report.md"),
            toolResult("write_file", AgentJsonValue.Obj(listOf("summary" to AgentJsonValue.Str("已写入报告"))))
        )

        val handoff = buildHandoff(run = run(), transcript = transcript)

        assertEquals("执行了 2 步 / 调用 2 次工具", handoff.didWhat)
        assertTrue(
            "didWhat must carry the real tool count, not a hard-coded 0",
            handoff.didWhat.contains("2 次工具")
        )
        assertEquals("已写入报告", handoff.sawWhat)
        assertEquals(AGENT_STOP_HANDOFF_NONE, handoff.leftToDo)
    }

    @Test
    fun `an unfinished assistant intention becomes leftToDo`() {
        val transcript = listOf(
            assistant("先看 inputs/brief.txt"),
            toolResult("read_file", AgentJsonValue.Str("README 三行")),
            assistant("""{"intent":"整理成 artifacts/report.md 的三段结论"}""")
        )

        val handoff = buildHandoff(run = run(), transcript = transcript)

        assertEquals("整理成 artifacts/report.md 的三段结论", handoff.leftToDo)
        assertTrue(handoff.rendered.contains("从这里接着做"))
    }

    @Test
    fun `an empty transcript falls back instead of throwing`() {
        val handoff = buildHandoff(run = run(), transcript = emptyList())

        assertEquals("执行了 0 步 / 调用 0 次工具", handoff.didWhat)
        assertEquals(AGENT_STOP_HANDOFF_NONE, handoff.sawWhat)
        assertEquals(AGENT_STOP_HANDOFF_NONE, handoff.leftToDo)
        assertTrue(handoff.rendered.isNotBlank())
        assertTrue(handoff.rendered.contains("继续"))
    }

    @Test
    fun `rendered never exceeds the 200 character budget`() {
        val huge = "很长的工具输出".repeat(500)
        val transcript = listOf(
            assistant("步骤一"),
            toolResult("run_command", AgentJsonValue.Str(huge)),
            assistant("""{"summary":"${"待完成的下一步".repeat(200)}"}""")
        )

        val handoff = buildHandoff(run = run(), transcript = transcript)

        assertTrue(
            "rendered=${handoff.rendered.length} chars",
            handoff.rendered.length <= AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS
        )
        assertEquals(AGENT_STOP_HANDOFF_MAX_RENDERED_CHARS, handoff.rendered.length)
        assertTrue(handoff.rendered.endsWith("…"))
        assertTrue(handoff.rendered.isNotBlank())
        assertFalse(handoff.rendered.contains("\n"))
    }

    @Test
    fun `the tool preview is bounded and flattened`() {
        val longLine = "x".repeat(400) + "\n\n  y".repeat(40)
        val transcript = listOf(toolResult("read_file", AgentJsonValue.Str(longLine)))

        val handoff = buildHandoff(run = run(), transcript = transcript)

        assertTrue("sawWhat=${handoff.sawWhat.length}", handoff.sawWhat.length <= 120)
        assertFalse(handoff.sawWhat.contains("\n"))
        assertFalse(handoff.sawWhat.contains("  "))
    }

    @Test
    fun `a nested observation is rendered compactly`() {
        val payload = AgentJsonValue.Obj(
            listOf(
                "status" to AgentJsonValue.Str("ok"),
                "nested" to AgentJsonValue.Arr(
                    listOf(AgentJsonValue.Obj(listOf("a" to AgentJsonValue.Num("1"))))
                )
            )
        )

        val handoff = buildHandoff(run = run(), transcript = listOf(toolResult("inspect", payload)))

        assertTrue(handoff.sawWhat.startsWith("{"))
        assertTrue(handoff.sawWhat.contains("status=ok"))
    }

    @Test
    fun `a tool result followed by nothing has no pending work`() {
        val transcript = listOf(
            assistant("run it"),
            toolResult("run_command", AgentJsonValue.Obj(listOf("error" to AgentJsonValue.Str("exit 1"))))
        )

        val handoff = buildHandoff(run = run(), transcript = transcript)

        assertEquals("exit 1", handoff.sawWhat)
        assertEquals(AGENT_STOP_HANDOFF_NONE, handoff.leftToDo)
    }

    @Test
    fun `the same inputs always render the same summary`() {
        val transcript = listOf(
            assistant("a"),
            toolResult("read_file", AgentJsonValue.Str("same"))
        )

        val first = buildHandoff(run = run(), transcript = transcript)
        val second = buildHandoff(run = run(), transcript = transcript)

        assertEquals(first, second)
    }

    private fun assistant(text: String): AgentTranscriptEntry =
        AgentTranscriptEntry.AssistantEnvelope(rawEnvelope = text)

    private fun toolResult(toolKey: String, result: AgentJsonValue): AgentTranscriptEntry =
        AgentTranscriptEntry.ToolResult(
            invocationId = "inv-$toolKey",
            callHash = "hash-$toolKey",
            toolKey = toolKey,
            result = result
        )

    private fun run(): AgentRun = AgentRun(
        runId = "run-handoff",
        taskId = "task-handoff",
        runOrdinal = 1L,
        state = AgentRunState.RUNNING,
        reason = AgentPauseReason.NONE,
        epoch = 1L,
        modelBinding = AgentTestFixtures.binding(),
        policy = AgentRunPolicy(),
        budget = AgentBudget(),
        spentBudget = AgentSpentBudget(),
        lastSequence = 0L,
        revision = 1L,
        createdAtMillis = 1_000L,
        updatedAtMillis = 2_000L
    )
}
