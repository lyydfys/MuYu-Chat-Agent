package com.muyuchat.mca

import kotlinx.coroutines.Job

/**
 * Decides which job a generation must wait for before it dispatches to the isolated worker.
 *
 * The local send path runs immediately after the update worker may have armed a foreground
 * recovery probe; the send must not overtake that probe and observe the transient unloaded
 * projection. Only the recovery job may be awaited, and never the caller's own job: a job that
 * joins itself suspends forever (no timeout, no error, and the foreground lease is only released
 * when the job completes).
 *
 * Extracted from `ChatExecutionOperations.runChatGeneration` (R1 stop/start path) so the decision is
 * unit-testable without the full orchestration port. The upstream original expressed the same rule
 * inline as `foregroundRecoveryJob?.takeUnless { it === generationJob }?.join()`.
 */
internal fun recoveryJobToAwait(foregroundRecoveryJob: Job?, selfJob: Job?): Job? {
    if (foregroundRecoveryJob == null) return null
    if (selfJob != null && foregroundRecoveryJob === selfJob) return null
    return foregroundRecoveryJob
}
