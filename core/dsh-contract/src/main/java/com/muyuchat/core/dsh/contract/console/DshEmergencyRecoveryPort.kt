package com.muyuchat.core.dsh.contract.console

/**
 * Emergency recovery seam of the maintenance console.
 *
 * The decision (which repair, in which order) and the execution (stop → move home → reinstall →
 * fall back) are implemented and unit tested outside this contract; the console only renders the
 * advice and asks the owner to run it. `confirmed` carries the user's answer to the "this moves your
 * DSH home aside" question, so the implementation never has to guess.
 */
interface DshEmergencyRecoveryPort {

    /** What this install can do right now. Never null and never silent. */
    fun advice(): DshRecoveryAdvice

    /**
     * Runs the advised plan.
     *
     * @param confirmed true only after the user accepted a data-moving step.
     * @return a one-line, path-free summary for the console.
     */
    suspend fun run(confirmed: Boolean): String
}

/** Advice for the console; every branch carries a human reason. */
sealed interface DshRecoveryAdvice {
    /** [steps] are stable step names; [reason] explains why this plan was chosen. */
    data class Actionable(
        val steps: List<String>,
        val reason: String,
        val needsConfirmation: Boolean
    ) : DshRecoveryAdvice

    /** Nothing systematic is left to do. [reason] is what the user should report. */
    data class Unavailable(val reason: String) : DshRecoveryAdvice
}
