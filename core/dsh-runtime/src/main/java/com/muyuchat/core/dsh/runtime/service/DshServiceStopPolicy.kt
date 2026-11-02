package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Service-side decision for ending the foreground `:dsh` service.
 *
 * Only a confirmed [DshRuntimePhase.STOPPED] snapshot permits removing the foreground
 * notification and calling `stopSelf()`. FAILED (including STOP_TIMEOUT), STARTING, READY and
 * STOPPING all keep the service and the stop fence observable so the user can retry.
 *
 * Kept pure so JVM tests can pin the rule without a Binder/Handler or Android runtime.
 */
internal object DshServiceStopPolicy {

    fun canStopService(snapshot: DshRuntimeSnapshot): Boolean =
        snapshot.phase == DshRuntimePhase.STOPPED
}
