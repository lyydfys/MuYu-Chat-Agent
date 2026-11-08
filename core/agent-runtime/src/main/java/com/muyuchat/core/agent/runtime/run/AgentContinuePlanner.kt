package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRunState

/**
 * Where a "continue" request would start from.
 *
 * @property canContinue true only when the previous run stopped in a state the owner accepts.
 * @property seedGoal the goal text a successor run should carry. It is the original goal, extended
 *   with the stop handoff when one exists, so the model resumes the *same* task instead of restarting.
 * @property reason a user-facing explanation. Always filled in — for a refusal it says why, for an
 *   acceptance it names the state that allowed it, so a UI never has to invent wording.
 */
data class ContinuePlan(
    val canContinue: Boolean,
    val seedGoal: String,
    val reason: String
)

/** Upper bound of [ContinuePlan.seedGoal]; a resumed goal must stay a goal, not a transcript. */
const val AGENT_CONTINUE_MAX_SEED_GOAL_CHARS: Int = 2_000

/** How the previous run's progress is introduced inside [ContinuePlan.seedGoal]. */
const val AGENT_CONTINUE_PROGRESS_PREFIX: String = "上次进度："

/** Appended when the run stopped but produced no usable handoff (stopped before the first step). */
const val AGENT_CONTINUE_INTERRUPTED_SUFFIX: String = "上次被中断，请接着完成原任务。"

/**
 * Decides whether a stopped run may be continued, and what goal the successor starts from.
 *
 * Pure: no clock, no I/O, no state mutation; identical arguments always give an identical plan.
 *
 * [stoppedRun] is the *state* the previous run settled in, because that is what the owner gates on
 * (`ContinueAgentTask` rejects anything but a stopped run). The goal is a separate argument and not
 * part of the run because it lives on the task.
 *
 * Only [AgentRunState.PAUSED] is continuable. A run that already finished must not be replayed as if
 * it were unfinished, and a run that still holds the active slot must be stopped first: both cases are
 * refusals with an explicit reason rather than a silently seeded goal.
 */
fun planContinue(
    stoppedRun: AgentRunState,
    handoff: AgentStopHandoffSummary?,
    originalGoal: String = ""
): ContinuePlan = when (stoppedRun) {
    AgentRunState.PAUSED -> ContinuePlan(
        canContinue = true,
        seedGoal = seedGoalFor(originalGoal, handoff),
        reason = "上一轮已暂停，可以从停止处继续。"
    )

    AgentRunState.SUCCEEDED -> refuse("已完成的任务不需要继续。")

    AgentRunState.FAILED -> refuse("上一轮是失败结束，不能直接续跑，请重新开始一个任务。")

    AgentRunState.CANCELLED -> refuse("任务已被取消，被取消的轮次不会续跑，请重新开始一个任务。")

    AgentRunState.SUPERSEDED -> refuse("该轮运行已被后面的运行取代，请从最新的一轮继续。")

    AgentRunState.NEEDS_REVIEW -> refuse("上一轮结束时有未决的审批或校验，需要先处理它才能继续。")

    AgentRunState.INTERRUPTED -> refuse("上一轮是被系统中断的，恢复流程会把它变成已暂停，恢复完成前不能继续。")

    // Still holding the active slot: queueing a second execution over it is never allowed.
    AgentRunState.QUEUED,
    AgentRunState.RUNNING,
    AgentRunState.WAITING_RESOURCE,
    AgentRunState.WAITING_APPROVAL,
    AgentRunState.WAITING_USER,
    AgentRunState.PAUSING,
    AgentRunState.STOPPING -> refuse("任务还在运行中（${stoppedRun.name}），请先停止它再继续。")
}

/**
 * Builds the successor goal.
 *
 * A usable handoff is one that actually names unfinished work: [buildHandoff] reports
 * [AGENT_STOP_HANDOFF_NONE] when the run stopped on a settled step, and appending "上次进度：无" would
 * only add noise the model has to read past.
 */
private fun seedGoalFor(originalGoal: String, handoff: AgentStopHandoffSummary?): String {
    val goal = originalGoal.trim()
    val progress = handoff?.leftToDo
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != AGENT_STOP_HANDOFF_NONE }
    if (progress == null) {
        return capSeedGoal(
            if (goal.isEmpty()) {
                AGENT_CONTINUE_INTERRUPTED_SUFFIX
            } else {
                "$goal\n\n$AGENT_CONTINUE_INTERRUPTED_SUFFIX"
            }
        )
    }
    val suffix = "$AGENT_CONTINUE_PROGRESS_PREFIX$progress"
    return capSeedGoal(if (goal.isEmpty()) suffix else "$goal\n\n$suffix")
}

/** Hard cap with an ellipsis, so a long goal plus handoff can never exceed the budget. */
private fun capSeedGoal(text: String): String =
    if (text.length <= AGENT_CONTINUE_MAX_SEED_GOAL_CHARS) {
        text
    } else {
        text.take(AGENT_CONTINUE_MAX_SEED_GOAL_CHARS - 1) + "…"
    }

/** A refusal carries no goal: seeding one would look like an offer to run. */
private fun refuse(reason: String): ContinuePlan =
    ContinuePlan(canContinue = false, seedGoal = "", reason = reason)
