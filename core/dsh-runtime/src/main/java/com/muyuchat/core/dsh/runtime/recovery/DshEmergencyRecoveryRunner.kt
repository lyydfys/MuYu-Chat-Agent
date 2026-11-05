package com.muyuchat.core.dsh.runtime.recovery

/** Result of one executed recovery step. */
data class DshRecoveryStepResult(
    val step: DshRecoveryStep,
    val ok: Boolean,
    /** Human detail; for [DshRecoveryStep.RESET_HOME] it is the moved-aside name, never a path. */
    val detail: String = ""
)

/** Outcome of a whole plan. */
data class DshRecoveryOutcome(
    val steps: List<DshRecoveryStepResult>,
    /** True only when every planned step ran and succeeded. */
    val succeeded: Boolean,
    /** The step that stopped the plan, when it did not finish. */
    val stoppedAt: DshRecoveryStep? = null
) {
    /** One-line summary the console prints; never contains a path or a token. */
    fun summary(): String = when {
        succeeded -> "应急恢复完成：" + steps.joinToString(" → ") { it.step.name }
        stoppedAt == null -> "应急恢复未执行。"
        else -> "应急恢复在 " + stoppedAt.name + " 停止：" +
            (steps.lastOrNull { it.step == stoppedAt }?.detail.orEmpty())
    }
}

/**
 * Runs a [DshRecoveryPlan] against injected effects.
 *
 * The ordering and the stopping rule live here, not in the app adapter, so they are provable on the
 * JVM: steps run in plan order, the first failure stops the plan (a later step assumes the earlier
 * one succeeded), and a step never runs unless the plan asked for it.
 *
 * Every effect is a lambda owned by the caller. This class touches no file, no process and no
 * runtime: it decides *what ran and whether it worked*, nothing more.
 */
class DshEmergencyRecoveryRunner(
    /** Stops the host and waits for it to settle; returns null on success or the reason it stayed up. */
    private val stopHost: () -> String?,
    /** Moves the poisoned home aside and reseeds; returns the moved-aside name, or null on success. */
    private val resetHome: () -> String?,
    /** Reinstalls the packaged payload from the APK; returns null on success, otherwise the reason. */
    private val reinstallPayload: () -> String?,
    /** Drops the committed closure and serves the packaged payload; null on success. */
    private val fallBackToPackagedPayload: () -> String?
) {

    fun run(plan: DshRecoveryPlan): DshRecoveryOutcome {
        val results = mutableListOf<DshRecoveryStepResult>()

        // Whatever the plan, the host must be down before any of its files are touched.
        if (plan.actionable) {
            val stopFailure = runCatching { stopHost() }.getOrElse { it.message ?: "stop failed" }
            if (stopFailure != null) {
                results += DshRecoveryStepResult(
                    step = DshRecoveryStep.REPORT_ONLY,
                    ok = false,
                    detail = "无法停止运行时：$stopFailure"
                )
                return DshRecoveryOutcome(results, succeeded = false, stoppedAt = DshRecoveryStep.REPORT_ONLY)
            }
        }

        for (step in plan.steps) {
            val outcome: String? = when (step) {
                DshRecoveryStep.RESET_HOME -> runCatching { resetHome() }
                    .getOrElse { it.message ?: "reset failed" }
                DshRecoveryStep.REINSTALL_PAYLOAD -> runCatching { reinstallPayload() }
                    .getOrElse { it.message ?: "reinstall failed" }
                DshRecoveryStep.FALL_BACK_TO_PACKAGED_PAYLOAD -> runCatching { fallBackToPackagedPayload() }
                    .getOrElse { it.message ?: "fallback failed" }
                // Nothing to execute: the plan is a report, and the console already shows the reason.
                DshRecoveryStep.REPORT_ONLY -> null
            }
            val ok = outcome == null
            results += DshRecoveryStepResult(step = step, ok = ok, detail = outcome.orEmpty())
            if (!ok) {
                return DshRecoveryOutcome(results, succeeded = false, stoppedAt = step)
            }
        }

        // A plan that only reports is not a success; it is an honest "nothing was repaired".
        val repaired = plan.steps.any { it != DshRecoveryStep.REPORT_ONLY }
        return DshRecoveryOutcome(steps = results, succeeded = repaired)
    }
}
