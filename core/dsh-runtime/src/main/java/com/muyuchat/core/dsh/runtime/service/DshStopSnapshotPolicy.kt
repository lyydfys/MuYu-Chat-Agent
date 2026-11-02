package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Client-side decision for an explicit `stop()` when the owner's answer is missing, delayed or
 * stale. Kept pure so JVM tests can pin the no-false-STOPPED rule without a Binder/Handler.
 *
 * Rules:
 * - a reply that is not older than the current snapshot (epoch first, then service `changedAt`)
 *   is authoritative and applied verbatim;
 * - an older (stale) reply is never applied; because the current stop outcome is unknown it
 *   becomes FAILED/STOP_TIMEOUT retryable, never STOPPED;
 * - a missing reply (null messenger, request timeout, or Binder loss) is also
 *   FAILED/STOP_TIMEOUT retryable. Binder death is NOT proof of STOPPED: Node is a child of the
 *   service and can outlive it, so only a fresh owner reply can establish STOPPED here.
 */
internal object DshStopSnapshotPolicy {

    fun isFresh(reply: DshRuntimeSnapshot, current: DshRuntimeSnapshot): Boolean =
        reply.epoch > current.epoch ||
            (reply.epoch == current.epoch && reply.changedAtMillis >= current.changedAtMillis)

    fun resolve(
        current: DshRuntimeSnapshot,
        reply: DshRuntimeSnapshot?,
        nowMillis: Long
    ): DshRuntimeSnapshot = when {
        reply != null && isFresh(reply, current) -> reply
        else -> unknown(current, nowMillis)
    }

    private fun unknown(current: DshRuntimeSnapshot, nowMillis: Long): DshRuntimeSnapshot =
        DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = current.epoch,
            origin = null,
            failure = DshRuntimeFailure(
                code = DshFailureCode.STOP_TIMEOUT,
                message = "DSH 停止结果未知：运行时服务未确认停止，请重试",
                retryable = true,
                diagnostics = "stop outcome unknown (timeout, unbound service, or stale reply)"
            ),
            changedAtMillis = nowMillis
        )
}
