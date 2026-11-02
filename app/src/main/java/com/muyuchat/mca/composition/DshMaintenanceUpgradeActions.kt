package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.maintenance.DshBackupCreateResult
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshMaintenanceError
import com.muyuchat.core.dsh.maintenance.DshMaintenanceFailure
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRequester
import com.muyuchat.core.dsh.maintenance.DshQuiescenceEvidence
import com.muyuchat.core.dsh.maintenance.DshUpgradePhase
import com.muyuchat.core.dsh.maintenance.DshUpgradeResult
import java.io.File

/**
 * Console actions of the whole-closure upgrade (D24), split from the projection adapter so both
 * files stay inside the maintenance file budget.
 *
 * The backup is its own maintenance transaction and runs **before** the upgrade transaction: the
 * engine owns one lease per transaction, so nesting a backup inside the upgrade body would be a
 * second acquirer (and would deadlock or be refused). It still runs while the host is confirmed
 * stopped and before any trial or selector change.
 */
internal class DshMaintenanceUpgradeActions(
    private val backupsDir: File,
    private val clock: () -> Long,
    private val suffix: () -> String
) {
    fun run(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RunClosureUpgrade,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        // Re-check the durable phase before touching the candidate or the backup: a console whose
        // snapshot is stale must not overwrite a pending trial/apply state, and a recovery-required
        // upgrade must never be re-run over the evidence the rescue surface needs.
        when (owner.upgrade.state().phase) {
            in DshMaintenanceUpgradeProjection.RESUME_RAW_PHASES -> return DshMaintenanceConsoleResult.Refused(
                intent.kind,
                DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS,
                "已有升级正在进行，请先恢复该升级"
            )

            DshUpgradePhase.RECOVERY_REQUIRED -> return DshMaintenanceConsoleResult.Failed(
                intent.kind,
                DshMaintenanceError.RECOVERY_REQUIRED.name,
                retryable = false,
                message = "升级状态需要人工处理，不能重新执行"
            )

            // IDLE, COMMITTED and ROLLED_BACK have no pending trial/apply decision. A committed
            // closure is a stable terminal state of the *previous* candidate, not a refusal of
            // the next one staged below `incoming`.
            else -> Unit
        }
        val candidate = when (val lookup = owner.findClosureCandidate()) {
            is DshClosureCandidateLookup.Available -> lookup.candidate
            DshClosureCandidateLookup.None -> return DshMaintenanceConsoleResult.Refused(
                intent.kind,
                DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
                "没有已暂存的候选闭包，升级不可执行"
            )

            is DshClosureCandidateLookup.Rejected -> return map(intent, lookup.failure)
        }
        val backupFailure = backupRealHome(owner, requester, quiescence)
        if (backupFailure != null) return map(intent, backupFailure)
        return map(intent, owner.upgrade.run(candidate, requester, quiescence))
    }

    fun abandon(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.AbandonClosureUpgrade,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val state = owner.upgrade.state()
        if (state.phase != DshUpgradePhase.RECOVERY_REQUIRED) {
            return DshMaintenanceConsoleResult.Refused(
                intent.kind,
                DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE,
                "升级状态不是 RECOVERY_REQUIRED（当前 ${state.phase.name}），无需放弃"
            )
        }
        return map(intent, owner.upgrade.abandon(requester, quiescence))
    }

    fun resume(
        owner: DshMaintenanceOwner,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val intent = DshMaintenanceConsoleIntent.ResumeClosureUpgrade
        if (owner.upgrade.state().phase !in DshMaintenanceUpgradeProjection.RESUME_RAW_PHASES) {
            return DshMaintenanceConsoleResult.Refused(
                intent.kind,
                DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE,
                "没有需要恢复的闭包升级"
            )
        }
        return map(intent, owner.upgrade.resume(requester, quiescence))
    }

    private fun backupRealHome(
        owner: DshMaintenanceOwner,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceFailure? {
        if (!backupsDir.isDirectory && !backupsDir.mkdirs() && !backupsDir.isDirectory) {
            return DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "备份目录不可用")
        }
        val output = File(backupsDir, "pre-upgrade-" + clock() + "-" + suffix() + ".zip")
        return when (
            val result = owner.backups.createBackup(
                sources = owner.defaultBackupSources(),
                output = output,
                requester = requester,
                quiescence = quiescence
            )
        ) {
            is DshBackupCreateResult.Created -> null
            is DshBackupCreateResult.Rejected -> result.failure
        }
    }

    private fun map(
        intent: DshMaintenanceConsoleIntent,
        result: DshUpgradeResult
    ): DshMaintenanceConsoleResult = when (result) {
        is DshUpgradeResult.Committed ->
            DshMaintenanceConsoleResult.Applied(intent.kind, "闭包升级已提交并通过只读健康检查")

        is DshUpgradeResult.TrialFailed -> DshMaintenanceConsoleResult.Failed(
            intent.kind,
            result.failure.code.name,
            retryable = true,
            message = "隔离试跑未通过：" + result.failure.message.take(120)
        )

        is DshUpgradeResult.Rejected -> map(intent, result.failure)
        is DshUpgradeResult.RolledBack -> DshMaintenanceConsoleResult.Failed(
            intent.kind,
            result.failure.code.name,
            retryable = true,
            message = "升级已回滚：" + result.failure.message.take(120)
        )

        is DshUpgradeResult.RecoveryRequired -> DshMaintenanceConsoleResult.Failed(
            intent.kind,
            "RECOVERY_REQUIRED",
            retryable = false,
            message = "升级中断，需要人工处理：" + result.failure.message.take(100)
        )

        is DshUpgradeResult.NoChange -> DshMaintenanceConsoleResult.Applied(intent.kind, result.reason)
    }

    private fun map(
        intent: DshMaintenanceConsoleIntent,
        failure: DshMaintenanceFailure
    ): DshMaintenanceConsoleResult {
        val refusal = when (failure.code) {
            DshMaintenanceError.ADMISSION_BUSY,
            DshMaintenanceError.ADMISSION_REJECTED,
            DshMaintenanceError.HOST_STILL_RUNNING -> DshMaintenanceConsoleRefusal.BUSY
            DshMaintenanceError.SPACE_INSUFFICIENT -> DshMaintenanceConsoleRefusal.STORAGE_INSUFFICIENT
            // An incompatible/mixed candidate is a typed failure of this attempt, not "busy" or
            // "already upgrading": the console must show the stable code, not a misleading refusal.
            else -> null
        }
        return if (refusal != null) {
            DshMaintenanceConsoleResult.Refused(intent.kind, refusal, failure.message)
        } else {
            DshMaintenanceConsoleResult.Failed(
                kind = intent.kind,
                code = failure.code.name,
                retryable = failure.code != DshMaintenanceError.ARCHIVE_MALICIOUS,
                message = failure.message
            )
        }
    }
}
