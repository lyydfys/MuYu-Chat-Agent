package com.muyuchat.core.agent.runtime.run

/**
 * How close a run is to its budget ceiling.
 *
 * Ordered from calm to terminal: [NONE] → [ELEVATED] → [CRITICAL] → [EXHAUSTED]. Callers use [ordinal]
 * only through the helper [AgentBudgetAdvisory.isAtLeast]; nothing else should compare enum instances.
 */
enum class AgentBudgetPressure {
    /** Comfortable headroom on both tokens and steps. */
    NONE,

    /** 80% of either dimension consumed: the model is likely to start trimming output. */
    ELEVATED,

    /** 95% consumed: only a short reply still fits. */
    CRITICAL,

    /** The ceiling is reached; further output will be truncated. */
    EXHAUSTED
}

/**
 * Pure budget-pressure projection for a running agent loop.
 *
 * Why this exists: a long task that hits the token ceiling only at the very end produces a hard
 * truncation with no warning. `AgentPromptedLoopCoordinator` already hard-stops via
 * `budgetPolicy.checkGeneratedTokens(...)`; this advisory is the *early* signal that lets the caller
 * tell the user (or the model itself) that the remaining headroom is small, long before that stop.
 *
 * Rules (each dimension independently, the stricter one wins):
 * - any dimension ≥ 100% → EXHAUSTED
 * - any dimension ≥ 95%  → CRITICAL
 * - any dimension ≥ 80%  → ELEVATED
 * - otherwise            → NONE
 *
 * A non-positive [budget] or [maxSteps] means "no ceiling of that kind" and is ignored for that
 * dimension rather than reported as instantly exhausted — a missing limit must not look like a hit one.
 */
object AgentBudgetAdvisory {

    /** Fraction of a dimension at which the caller starts being nudged. */
    const val ELEVATED_RATIO: Double = 0.80

    /** Fraction at which only a short reply still fits. */
    const val CRITICAL_RATIO: Double = 0.95

    /** Fraction at which the ceiling is considered reached. */
    const val EXHAUSTED_RATIO: Double = 1.0

    /**
     * Pressure for the current step.
     *
     * [spentTokens]/[budget] is the token dimension, [stepIndex]/[maxSteps] the step dimension. Both are
     * compared in `Double` and combined with `max` so a run that is fine on tokens but deep in steps is
     * reported exactly as harshly as the reverse.
     */
    fun assess(spentTokens: Long, budget: Long, stepIndex: Int, maxSteps: Int): AgentBudgetPressure {
        val tokenRatio = ratio(spentTokens.toDouble(), budget.toDouble())
        val stepRatio = ratio(stepIndex.toDouble(), maxSteps.toDouble())
        val worst = maxOf(tokenRatio, stepRatio)
        return when {
            worst >= EXHAUSTED_RATIO -> AgentBudgetPressure.EXHAUSTED
            worst >= CRITICAL_RATIO -> AgentBudgetPressure.CRITICAL
            worst >= ELEVATED_RATIO -> AgentBudgetPressure.ELEVATED
            else -> AgentBudgetPressure.NONE
        }
    }

    /**
     * One-sentence Chinese hint for the console, or an empty string for [AgentBudgetPressure.NONE].
     *
     * No numbers are interpolated: the text is a stable string the UI can assert on, and the caller
     * already knows the exact figures.
     */
    fun advisoryText(pressure: AgentBudgetPressure): String = when (pressure) {
        AgentBudgetPressure.NONE -> ""
        AgentBudgetPressure.ELEVATED -> "剩余预算已不足两成，后续回复可能会缩短。"
        AgentBudgetPressure.CRITICAL -> "剩余预算不足一成，本轮很可能被截断，建议先保存结果。"
        AgentBudgetPressure.EXHAUSTED -> "本轮预算已耗尽，输出已被截断，请减少上下文后重试。"
    }

    /** Convenience for callers that only want a yes/no on a severity threshold. */
    fun isAtLeast(pressure: AgentBudgetPressure, threshold: AgentBudgetPressure): Boolean =
        pressure.ordinal >= threshold.ordinal

    /**
     * Consumed fraction of one dimension.
     *
     * A non-positive ceiling is "unlimited" → 0.0 (never a hit). Negative consumption is clamped to 0
     * so a reset counter cannot produce a negative ratio.
     */
    private fun ratio(consumed: Double, ceiling: Double): Double {
        if (ceiling <= 0.0) return 0.0
        return (consumed / ceiling).coerceAtLeast(0.0)
    }
}
