package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradeState
import com.muyuchat.core.dsh.maintenance.DshTrialPhase
import com.muyuchat.core.dsh.maintenance.DshUpgradePhase

/**
 * Pure projection of the D24 closure-upgrade and isolated-trial state into the bounded console
 * contract ([DshMaintenanceUpgradeState]).
 *
 * The maintenance owner distinguishes more phases than the frozen console contract exposes
 * (`VERIFIED`, `TRIAL_RUNNING`, `TRIAL_PASSED`, `APPLYING`) but every one of them means the same
 * thing to the console: an upgrade owns a pending trial/apply decision, so the only offered action
 * is "resume". They therefore fold into [DshMaintenanceUpgradePhase.TRIAL_RUNNING]; they are never
 * presented as an idle console.
 *
 * The isolated trial journal is projected as well. The durable `trial/active.json` can outlive or
 * outrun the upgrade state file (partial journal, crash, unreadable state), and showing `IDLE`
 * while a trial generation may be alive would both mislabel the state and re-offer "run". A live
 * trial phase is therefore surfaced even when the upgrade state says `IDLE`; a terminal trial
 * phase never upgrades a terminal upgrade phase.
 *
 * Nothing here opens a file, talks to the runtime or invents a phase. Paths, pids and detail never
 * leave the owner; only the bounded phase and an opaque candidate token are projected.
 */
internal object DshMaintenanceUpgradeProjection {

    /**
     * Raw upgrade phases that still own a pending trial/apply decision. Resume is the only sound
     * action in all of them, and `DshClosureUpgradeManager.resume` handles each one.
     */
    val RESUME_RAW_PHASES: Set<DshUpgradePhase> = setOf(
        DshUpgradePhase.VERIFIED,
        DshUpgradePhase.TRIAL_RUNNING,
        DshUpgradePhase.TRIAL_PASSED,
        DshUpgradePhase.APPLYING,
        DshUpgradePhase.HEALTH_PENDING
    )

    /**
     * Contract phases in which a new upgrade may be started (no pending decision).
     *
     * `COMMITTED` is included: a commit settles the previous candidate, it does not end the
     * upgrade capability. A new staged candidate (the active closure is excluded by the lookup)
     * is a new decision, so refusing it would make the first upgrade permanent.
     */
    val RUN_CONSOLE_PHASES: Set<DshMaintenanceUpgradePhase> = setOf(
        DshMaintenanceUpgradePhase.IDLE,
        DshMaintenanceUpgradePhase.COMMITTED,
        DshMaintenanceUpgradePhase.ROLLED_BACK
    )

    /** Contract phases in which the pending upgrade can only be resumed. */
    val RESUME_CONSOLE_PHASES: Set<DshMaintenanceUpgradePhase> = setOf(
        DshMaintenanceUpgradePhase.TRIAL_RUNNING,
        DshMaintenanceUpgradePhase.HEALTH_PENDING
    )

    /**
     * Trial journal phases that may still hold a process. `RECOVERY_REQUIRED` is included because
     * it explicitly means "a trial may exist and was not proven stopped".
     */
    private val LIVE_TRIAL_PHASES: Set<DshTrialPhase> = setOf(
        DshTrialPhase.LAUNCHING,
        DshTrialPhase.RUNNING,
        DshTrialPhase.STOPPING,
        DshTrialPhase.RECOVERY_REQUIRED
    )

    /**
     * Projects one read-only view of the upgrade/trial state.
     *
     * @param upgradePhase durable upgrade state phase; `null` when the state could not be read.
     * @param upgradeCandidateId candidate recorded by the upgrade state (the closure being applied).
     * @param stagedCandidateId candidate currently verifiable in the staging tree, if any.
     * @param trialPhase durable isolated-trial journal phase; `null` when no trial was journaled.
     * @param trialCandidateId candidate recorded by the trial journal.
     */
    fun project(
        upgradePhase: DshUpgradePhase?,
        upgradeCandidateId: String?,
        stagedCandidateId: String?,
        trialPhase: DshTrialPhase?,
        trialCandidateId: String?
    ): DshMaintenanceUpgradeState {
        val mapped = contractPhase(upgradePhase)
        val liveTrial = mapped == DshMaintenanceUpgradePhase.IDLE &&
            trialPhase != null &&
            trialPhase in LIVE_TRIAL_PHASES
        val phase = when {
            !liveTrial -> mapped
            // A trial that could not be released is not "running": it needs the rescue surface.
            trialPhase == DshTrialPhase.RECOVERY_REQUIRED -> DshMaintenanceUpgradePhase.RECOVERY_REQUIRED
            else -> DshMaintenanceUpgradePhase.TRIAL_RUNNING
        }
        // The closure the owner is actually trialling/applying is the useful identity; a merely
        // staged candidate is the fallback, so the console still names the pending upgrade.
        val upgradeCandidate =
            if (mapped == DshMaintenanceUpgradePhase.IDLE) null else opaqueToken(upgradeCandidateId)
        val trialCandidate = if (liveTrial) opaqueToken(trialCandidateId) else null
        val candidate = upgradeCandidate ?: trialCandidate ?: opaqueToken(stagedCandidateId)
        return DshMaintenanceUpgradeState(phase = phase, candidateVersion = candidate)
    }

    private fun contractPhase(phase: DshUpgradePhase?): DshMaintenanceUpgradePhase = when (phase) {
        null,
        DshUpgradePhase.IDLE -> DshMaintenanceUpgradePhase.IDLE

        // Every pre-selector phase still owns a pending trial/apply decision.
        DshUpgradePhase.VERIFIED,
        DshUpgradePhase.TRIAL_RUNNING,
        DshUpgradePhase.TRIAL_PASSED,
        DshUpgradePhase.APPLYING -> DshMaintenanceUpgradePhase.TRIAL_RUNNING

        DshUpgradePhase.HEALTH_PENDING -> DshMaintenanceUpgradePhase.HEALTH_PENDING
        DshUpgradePhase.COMMITTED -> DshMaintenanceUpgradePhase.COMMITTED
        DshUpgradePhase.ROLLED_BACK -> DshMaintenanceUpgradePhase.ROLLED_BACK
        DshUpgradePhase.RECOVERY_REQUIRED -> DshMaintenanceUpgradePhase.RECOVERY_REQUIRED
    }

    /** Opaque, prompt-free candidate token; never a path and never session content. */
    private fun opaqueToken(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val bounded = trimmed.take(MAX_CANDIDATE_TOKEN_CHARS)
        val opaque = bounded.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
        }
        return bounded.takeIf { opaque }
    }

    /** Same bound as the console contract's `candidateVersion`. */
    private const val MAX_CANDIDATE_TOKEN_CHARS = 64
}
