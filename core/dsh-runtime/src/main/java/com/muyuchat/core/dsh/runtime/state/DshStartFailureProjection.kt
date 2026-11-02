package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Projects an apply result into what a start caller must report.
 *
 * A failed apply is only a payload refusal when the service refused the requested closure tree:
 * that reply carries no owner failure of its own, so the caller synthesises a non-retryable
 * [DshFailureCode.PAYLOAD_INTEGRITY]. Every other failed apply already carries a typed failure
 * from the owner or from the client's own timeout (for example NODE_START_FAILED after the host
 * process was killed); relabelling those as a corrupt payload would tell the user to reinstall an
 * intact build and would drop the retry affordance.
 */
object DshStartFailureProjection {

    fun resolve(
        result: DshApplyResult,
        closureRequested: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): DshRuntimeSnapshot {
        if (result.status == DshApplyStatus.APPLIED || result.status == DshApplyStatus.UNCHANGED) {
            return result.snapshot
        }
        // Deferred/rolled-back results are the owner's own state; only a FAILED apply needs a
        // projected failure.
        if (result.status != DshApplyStatus.FAILED) return result.snapshot
        val existing = result.snapshot.failure
        if (existing != null) return result.snapshot
        return DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = result.snapshot.epoch,
            origin = null,
            failure = DshRuntimeFailure(
                code = if (closureRequested) {
                    DshFailureCode.PAYLOAD_INTEGRITY
                } else {
                    DshFailureCode.INTERNAL
                },
                message = if (closureRequested) {
                    "the committed closure payload was not admitted"
                } else {
                    "the DSH host did not accept the start request"
                },
                retryable = !closureRequested,
                diagnostics = result.detail?.take(200)
            ),
            changedAtMillis = nowMillis
        )
    }
}
