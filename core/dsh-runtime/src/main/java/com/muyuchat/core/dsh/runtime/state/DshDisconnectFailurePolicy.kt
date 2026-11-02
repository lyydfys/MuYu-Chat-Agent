package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * A service disconnect is transport evidence ("the process is gone"), not a diagnosis.
 *
 * When the owner already published a specific terminal failure for this snapshot (for example a
 * refused closure payload), that failure is what the user has to see; overwriting it with
 * PROCESS_EXITED would hide the real reason and turn a non-retryable integrity refusal into a
 * generic crash with a retry button.
 */
object DshDisconnectFailurePolicy {

    fun resolve(
        current: DshRuntimeSnapshot,
        message: String,
        nowMillis: Long = System.currentTimeMillis()
    ): DshRuntimeSnapshot {
        val failure = current.failure
        if (current.phase == DshRuntimePhase.FAILED &&
            failure != null &&
            failure.code != DshFailureCode.PROCESS_EXITED
        ) {
            return current
        }
        return DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = current.epoch,
            origin = null,
            failure = DshRuntimeFailure(
                code = DshFailureCode.PROCESS_EXITED,
                message = message,
                retryable = true
            ),
            changedAtMillis = nowMillis
        )
    }
}
