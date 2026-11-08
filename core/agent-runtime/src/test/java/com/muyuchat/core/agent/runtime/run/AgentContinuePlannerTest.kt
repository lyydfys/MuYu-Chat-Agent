package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Continue planning: which stopped states may be resumed and what goal the successor carries.
 *
 * The SUCCEEDED case is the anti-stub canary. A planner that answers `canContinue = true` for every
 * input (for example by dropping the PAUSED comparison and returning the seeded goal unconditionally)
 * makes it fail, which is exactly the regression this file exists to catch.
 */
class AgentContinuePlannerTest {

    private val goal = "把 docs/brief.md 里的三点整理成 artifacts/report.md"

    private fun handoff(leftToDo: String = "还差第三步的截图核对") = AgentStopHandoffSummary(
        didWhat = "执行了 3 步 / 调用 3 次工具",
        sawWhat = "已读取 brief",
        leftToDo = leftToDo,
        rendered = "已停止：本轮执行了 3 步 / 调用 3 次工具。"
    )

    @Test
    fun `a paused run with a handoff seeds the goal with the remaining work`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.PAUSED,
            handoff = handoff(leftToDo = "还差第三步的截图核对"),
            originalGoal = goal
        )

        assertTrue(plan.canContinue)
        assertEquals(
            "$goal\n\n${AGENT_CONTINUE_PROGRESS_PREFIX}还差第三步的截图核对",
            plan.seedGoal
        )
        assertTrue(
            "the seed must keep the original goal, not replace it",
            plan.seedGoal.contains(goal)
        )
        assertTrue(plan.reason.isNotBlank())
    }

    @Test
    fun `a paused run without a handoff falls back to the interrupted wording`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.PAUSED,
            handoff = null,
            originalGoal = goal
        )

        assertTrue(plan.canContinue)
        assertEquals("$goal\n\n$AGENT_CONTINUE_INTERRUPTED_SUFFIX", plan.seedGoal)
        assertTrue(plan.seedGoal.contains("上次被中断"))
        assertFalse(
            "no handoff means no invented progress",
            plan.seedGoal.contains(AGENT_CONTINUE_PROGRESS_PREFIX)
        )
    }

    @Test
    fun `a handoff that reports nothing left to do does not fabricate progress`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.PAUSED,
            handoff = handoff(leftToDo = AGENT_STOP_HANDOFF_NONE),
            originalGoal = goal
        )

        assertTrue(plan.canContinue)
        assertEquals("$goal\n\n$AGENT_CONTINUE_INTERRUPTED_SUFFIX", plan.seedGoal)
        assertFalse(plan.seedGoal.contains("上次进度：无"))
    }

    @Test
    fun `a succeeded run is never continued`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.SUCCEEDED,
            handoff = handoff(),
            originalGoal = goal
        )

        assertFalse("a finished task must not be replayed as unfinished", plan.canContinue)
        assertEquals("", plan.seedGoal)
        assertEquals("已完成的任务不需要继续。", plan.reason)
    }

    @Test
    fun `a cancelled run is refused with its own reason`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.CANCELLED,
            handoff = handoff(),
            originalGoal = goal
        )

        assertFalse(plan.canContinue)
        assertEquals("", plan.seedGoal)
        assertTrue(plan.reason.contains("取消"))
    }

    @Test
    fun `a failed run is refused but points at a restart instead of a resume`() {
        val plan = planContinue(
            stoppedRun = AgentRunState.FAILED,
            handoff = handoff(),
            originalGoal = goal
        )

        assertFalse(plan.canContinue)
        assertEquals("", plan.seedGoal)
        assertTrue(plan.reason.contains("失败"))
        assertFalse(
            "a failed run must not be sold as resumable in the reason",
            plan.reason.contains("可以从停止处继续")
        )
    }

    @Test
    fun `a run that still holds the active slot must be stopped first`() {
        listOf(
            AgentRunState.QUEUED,
            AgentRunState.RUNNING,
            AgentRunState.PAUSING,
            AgentRunState.STOPPING,
            AgentRunState.WAITING_APPROVAL,
            AgentRunState.WAITING_USER
        ).forEach { state ->
            val plan = planContinue(state, handoff(), goal)

            assertFalse("$state must not be continuable", plan.canContinue)
            assertEquals("", plan.seedGoal)
            assertTrue("$state reason must be readable", plan.reason.isNotBlank())
        }
    }

    @Test
    fun `review and interrupted states are refused with distinct reasons`() {
        val review = planContinue(AgentRunState.NEEDS_REVIEW, handoff(), goal)
        val interrupted = planContinue(AgentRunState.INTERRUPTED, handoff(), goal)

        assertFalse(review.canContinue)
        assertFalse(interrupted.canContinue)
        assertTrue(
            "each refusal explains itself instead of sharing one generic message",
            review.reason != interrupted.reason
        )
    }

    @Test
    fun `a very long goal with a long handoff is capped`() {
        val longGoal = "目标".repeat(2_000)
        val longHandoff = handoff(leftToDo = "待办".repeat(1_000))

        val plan = planContinue(AgentRunState.PAUSED, longHandoff, longGoal)

        assertTrue(plan.canContinue)
        assertTrue(
            "seedGoal must stay within the budget, was ${plan.seedGoal.length}",
            plan.seedGoal.length <= AGENT_CONTINUE_MAX_SEED_GOAL_CHARS
        )
        assertTrue(
            "the cap must keep the original goal's opening",
            plan.seedGoal.startsWith("目标目标")
        )
        assertTrue("the cut is marked", plan.seedGoal.endsWith("…"))
    }

    @Test
    fun `a seed goal exactly at the budget is not truncated`() {
        val prefix = "$goal\n\n$AGENT_CONTINUE_PROGRESS_PREFIX"
        val exact = prefix + "待".repeat(AGENT_CONTINUE_MAX_SEED_GOAL_CHARS - prefix.length)

        val plan = planContinue(AgentRunState.PAUSED, handoff(leftToDo = exact.removePrefix(prefix)), goal)

        assertEquals(AGENT_CONTINUE_MAX_SEED_GOAL_CHARS, plan.seedGoal.length)
        assertEquals(exact, plan.seedGoal)
        assertFalse(plan.seedGoal.endsWith("…"))
    }

    @Test
    fun `planning is pure and repeatable`() {
        val first = planContinue(AgentRunState.PAUSED, handoff(), goal)
        val second = planContinue(AgentRunState.PAUSED, handoff(), goal)

        assertEquals(first, second)
    }

    @Test
    fun `an empty goal still yields a usable seed`() {
        val withHandoff = planContinue(AgentRunState.PAUSED, handoff(leftToDo = "核对截图"), originalGoal = "")
        val withoutHandoff = planContinue(AgentRunState.PAUSED, null, originalGoal = "   ")

        assertEquals("${AGENT_CONTINUE_PROGRESS_PREFIX}核对截图", withHandoff.seedGoal)
        assertEquals(AGENT_CONTINUE_INTERRUPTED_SUFFIX, withoutHandoff.seedGoal)
        assertFalse(withHandoff.seedGoal.startsWith("\n"))
    }
}
