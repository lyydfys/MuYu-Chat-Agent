package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshConfirmableMaintenanceIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase

/** Local, read-only gate of one maintenance intent. The port owner remains the authority. */
sealed interface DshMaintenanceGate {
    data object Allowed : DshMaintenanceGate
    data class Denied(val reason: DshMaintenanceConsoleRefusal) : DshMaintenanceGate
}

/** Confirmation text shown before a destructive maintenance intent is submitted. */
data class DshMaintenanceConfirmation(
    val title: String,
    val detail: String,
    val confirmLabel: String
)

/**
 * Pure D23 console policy: availability gating, destructive-confirmation policy and refusal text.
 *
 * It reads only the bounded projection. It never opens a file, touches the maintenance owner or
 * performs any effect; the ViewModel submits allowed intents to the injected port.
 */
object DshMaintenanceConsolePolicy {

    fun requiresConfirmation(intent: DshMaintenanceConsoleIntent): Boolean =
        intent.requiresConfirmation

    /**
     * Returns the confirmed copy of a destructive intent (the only form the owner should accept
     * for operations that mutate user data or executable configuration).
     */
    fun confirmed(intent: DshMaintenanceConsoleIntent): DshMaintenanceConsoleIntent = when (intent) {
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.RestoreBackup ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.PurgeTrashedSession ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.RepairSession ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ApplyConsolePatch ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportTheme ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.RunClosureUpgrade ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.AbandonClosureUpgrade ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.RepairManagedFiles ->
            intent.copy(confirmed = true)
        is com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent.ImportMcpDeclaration ->
            intent.copy(confirmed = true)
        else -> intent
    }

    /** Confirmation prompt for a destructive intent; null for a non-destructive one. */
    fun confirmationFor(
        intent: DshMaintenanceConsoleIntent
    ): DshMaintenanceConfirmation? {
        if (!requiresConfirmation(intent)) return null
        val label = when (intent.kind) {
            DshMaintenanceOperationKind.RESTORE_BACKUP -> "恢复此备份会先保存当前恢复点，再替换当前数据。"
            DshMaintenanceOperationKind.PURGE_TRASHED_SESSION -> "彻底删除后无法从回收站恢复该会话。"
            DshMaintenanceOperationKind.REPAIR_SESSION -> "修复只改写诊断命中的附件引用，并保留备份。"
            DshMaintenanceOperationKind.REPAIR_MANAGED_FILES ->
                "修复只会替换安装包声明且哈希不符的受管文件，原始文件保留在隔离区。"
            DshMaintenanceOperationKind.APPLY_CONSOLE_PATCH -> "应用控制台补丁会改变 DSH 页面的展示与行为。"
            DshMaintenanceOperationKind.IMPORT_THEME -> "导入主题会替换当前控制台主题 token。"
            DshMaintenanceOperationKind.IMPORT_MCP_DECLARATION ->
                "导入 MCP 声明会替换当前 MCP 服务器列表；重启 DSH 运行时后生效。"
            DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE -> "整闭包升级会先运行隔离 trial，再提交为新版本。"
            DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE ->
                "放弃待决升级会解除恢复阻塞并把状态记为已回滚，不会切换版本。"
            DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE ->
                "放弃待决升级只解除恢复阻塞并保留当前闭包，不会切换版本。"
            else -> "该操作会修改用户数据。"
        }
        return DshMaintenanceConfirmation(
            title = "确认${kindLabel(intent.kind)}",
            detail = label,
            confirmLabel = "确认执行"
        )
    }

    fun gate(
        intent: DshMaintenanceConsoleIntent,
        snapshot: DshMaintenanceConsoleSnapshot,
        portAvailable: Boolean,
        requireConfirmation: Boolean = true
    ): DshMaintenanceGate {
        if (!portAvailable) return denied(DshMaintenanceConsoleRefusal.UNAVAILABLE)
        when (snapshot.availability) {
            DshMaintenanceConsoleAvailability.UNAVAILABLE ->
                return denied(DshMaintenanceConsoleRefusal.UNAVAILABLE)

            DshMaintenanceConsoleAvailability.BUSY ->
                return denied(DshMaintenanceConsoleRefusal.BUSY)

            DshMaintenanceConsoleAvailability.USER_PRESENCE_REQUIRED ->
                return denied(DshMaintenanceConsoleRefusal.USER_PRESENCE_REQUIRED)

            DshMaintenanceConsoleAvailability.AVAILABLE -> Unit
        }
        if (intent.kind !in snapshot.availableOperations) {
            return denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)
        }
        if (requireConfirmation && requiresConfirmation(intent) &&
            (intent as? DshConfirmableMaintenanceIntent)?.confirmed != true
        ) {
            return denied(DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED)
        }
        return targetGate(intent, snapshot)
    }

    /** Buttons are enabled when the action could be submitted *after* confirmation. */
    fun isActionAvailable(
        intent: DshMaintenanceConsoleIntent,
        snapshot: DshMaintenanceConsoleSnapshot,
        portAvailable: Boolean
    ): Boolean = gate(intent, snapshot, portAvailable, requireConfirmation = false) ==
        DshMaintenanceGate.Allowed

    /**
     * Every concrete action the current projection can offer, including unconfirmed destructive
     * ones (the UI shows a confirmation dialog before submitting them).
     */
    fun availableIntents(
        snapshot: DshMaintenanceConsoleSnapshot,
        portAvailable: Boolean
    ): Set<DshMaintenanceConsoleIntent> {
        if (!portAvailable || snapshot.availability != DshMaintenanceConsoleAvailability.AVAILABLE) {
            return emptySet()
        }
        val candidates = mutableListOf<DshMaintenanceConsoleIntent>()
        candidates += DshMaintenanceConsoleIntent.ListBackups
        candidates += DshMaintenanceConsoleIntent.ListSessions
        candidates += DshMaintenanceConsoleIntent.CreateBackup()
        candidates += DshMaintenanceConsoleIntent.EnterSafeMode
        candidates += DshMaintenanceConsoleIntent.ExitSafeMode
        candidates += DshMaintenanceConsoleIntent.ApplyConsolePatch()
        candidates += DshMaintenanceConsoleIntent.RollbackConsolePatch
        candidates += DshMaintenanceConsoleIntent.ImportTheme()
        candidates += DshMaintenanceConsoleIntent.ImportMcpDeclaration()
        candidates += DshMaintenanceConsoleIntent.ResetTheme
        candidates += DshMaintenanceConsoleIntent.RunClosureUpgrade()
        candidates += DshMaintenanceConsoleIntent.ResumeClosureUpgrade
        candidates += DshMaintenanceConsoleIntent.AbandonClosureUpgrade()
        candidates += DshMaintenanceConsoleIntent.ExportDiagnostics
        candidates += DshMaintenanceConsoleIntent.RunSelfCheck
        candidates += DshMaintenanceConsoleIntent.RepairManagedFiles()
        snapshot.backups.forEach { backup ->
            candidates += DshMaintenanceConsoleIntent.RestoreBackup(backup.backupId)
        }
        snapshot.sessions.forEach { session ->
            if (session.trashed) {
                candidates += DshMaintenanceConsoleIntent.RestoreTrashedSession(session.sessionId)
                candidates += DshMaintenanceConsoleIntent.PurgeTrashedSession(session.sessionId)
            } else {
                candidates += DshMaintenanceConsoleIntent.TrashSession(session.sessionId)
                candidates += DshMaintenanceConsoleIntent.RepairSession(session.sessionId)
            }
        }
        return candidates.filter { isActionAvailable(it, snapshot, portAvailable) }.toSet()
    }

    /** User-facing fixed text for a refusal; never echoes session content, ids or paths. */
    fun refusalMessage(
        reason: DshMaintenanceConsoleRefusal,
        ownerMessage: String? = null
    ): String = ownerMessage ?: when (reason) {
        DshMaintenanceConsoleRefusal.UNAVAILABLE -> "维护接口当前不可用。"
        DshMaintenanceConsoleRefusal.BUSY -> "维护操作正在进行，请等待完成。"
        DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED -> "该操作需要先确认。"
        DshMaintenanceConsoleRefusal.NOT_FOUND -> "目标已不存在，请刷新列表。"
        DshMaintenanceConsoleRefusal.NOT_ELIGIBLE -> "当前构建或状态下不支持该维护操作。"
        DshMaintenanceConsoleRefusal.SAFE_MODE_ACTIVE -> "安全模式已启用。"
        DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS -> "已有升级正在进行。"
        DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE -> "当前没有可恢复的升级。"
        DshMaintenanceConsoleRefusal.PATCH_CONFLICT -> "控制台补丁存在冲突，需先处理。"
        DshMaintenanceConsoleRefusal.STORAGE_INSUFFICIENT -> "存储空间不足。"
        DshMaintenanceConsoleRefusal.USER_PRESENCE_REQUIRED -> "该操作需要在可见页面完成。"
        DshMaintenanceConsoleRefusal.TRASH_EMPTY -> "回收站为空。"
        DshMaintenanceConsoleRefusal.SESSION_RUNNING -> "会话仍在运行，请先结算或停止。"
    }

    /** Notice text for one port result. [DshMaintenanceConsoleResult.Failed] keeps its typed code. */
    fun resultMessage(result: DshMaintenanceConsoleResult): String = when (result) {
        is DshMaintenanceConsoleResult.Applied ->
            result.message ?: "已${kindLabel(result.kind)}。"

        is DshMaintenanceConsoleResult.Pending ->
            result.message ?: "${kindLabel(result.kind)}已提交，正在等待完成。"

        is DshMaintenanceConsoleResult.Refused ->
            refusalMessage(result.reason, result.message)

        // A failed attempt must stay diagnosable: the bounded owner message is kept and the stable
        // typed code is appended so a trial/health failure is never reduced to generic "失败".
        is DshMaintenanceConsoleResult.Failed -> {
            val headline = result.message ?: "${kindLabel(result.kind)}失败，请重试。"
            if (headline.contains(result.code)) headline else "$headline（${result.code}）"
        }
    }

    fun kindLabel(kind: DshMaintenanceOperationKind): String = when (kind) {
        DshMaintenanceOperationKind.LIST_BACKUPS -> "刷新备份列表"
        DshMaintenanceOperationKind.CREATE_BACKUP -> "创建备份"
        DshMaintenanceOperationKind.RESTORE_BACKUP -> "恢复备份"
        DshMaintenanceOperationKind.LIST_SESSIONS -> "刷新会话列表"
        DshMaintenanceOperationKind.TRASH_SESSION -> "移入回收站"
        DshMaintenanceOperationKind.RESTORE_TRASHED_SESSION -> "恢复会话"
        DshMaintenanceOperationKind.PURGE_TRASHED_SESSION -> "彻底删除会话"
        DshMaintenanceOperationKind.REPAIR_MANAGED_FILES -> "修复托管文件"
        DshMaintenanceOperationKind.REPAIR_SESSION -> "修复会话"
        DshMaintenanceOperationKind.ENTER_SAFE_MODE -> "进入安全模式"
        DshMaintenanceOperationKind.EXIT_SAFE_MODE -> "退出安全模式"
        DshMaintenanceOperationKind.APPLY_CONSOLE_PATCH -> "应用控制台补丁"
        DshMaintenanceOperationKind.ROLLBACK_CONSOLE_PATCH -> "回滚控制台补丁"
        DshMaintenanceOperationKind.IMPORT_THEME -> "导入主题"
        DshMaintenanceOperationKind.IMPORT_MCP_DECLARATION -> "导入 MCP 声明"
        DshMaintenanceOperationKind.RESET_THEME -> "重置主题"
        DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE -> "运行整闭包升级"
        DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE -> "恢复升级"
        DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE -> "放弃待决升级"
        DshMaintenanceOperationKind.EXPORT_DIAGNOSTICS -> "导出诊断"
        DshMaintenanceOperationKind.RUN_SELF_CHECK -> "运行自检"
    }

    private fun targetGate(
        intent: DshMaintenanceConsoleIntent,
        snapshot: DshMaintenanceConsoleSnapshot
    ): DshMaintenanceGate = when (intent) {
        is DshMaintenanceConsoleIntent.RestoreBackup ->
            if (snapshot.backups.any { it.backupId == intent.backupId }) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_FOUND)
            }

        is DshMaintenanceConsoleIntent.TrashSession ->
            if (snapshot.sessions.any { it.sessionId == intent.sessionId && !it.trashed }) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_FOUND)
            }

        is DshMaintenanceConsoleIntent.RestoreTrashedSession ->
            if (snapshot.sessions.any { it.sessionId == intent.sessionId && it.trashed }) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_FOUND)
            }

        is DshMaintenanceConsoleIntent.PurgeTrashedSession -> when {
            snapshot.sessions.any { it.sessionId == intent.sessionId && it.trashed } ->
                DshMaintenanceGate.Allowed
            snapshot.trashCount == 0 -> denied(DshMaintenanceConsoleRefusal.TRASH_EMPTY)
            else -> denied(DshMaintenanceConsoleRefusal.NOT_FOUND)
        }

        is DshMaintenanceConsoleIntent.RepairSession ->
            if (snapshot.sessions.any { it.sessionId == intent.sessionId }) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_FOUND)
            }

        DshMaintenanceConsoleIntent.EnterSafeMode ->
            if (snapshot.safeModeActive) {
                denied(DshMaintenanceConsoleRefusal.SAFE_MODE_ACTIVE)
            } else {
                DshMaintenanceGate.Allowed
            }

        DshMaintenanceConsoleIntent.ExitSafeMode ->
            if (snapshot.safeModeActive) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)
            }

        is DshMaintenanceConsoleIntent.ApplyConsolePatch ->
            if (snapshot.patch.conflict) {
                denied(DshMaintenanceConsoleRefusal.PATCH_CONFLICT)
            } else {
                DshMaintenanceGate.Allowed
            }

        DshMaintenanceConsoleIntent.RollbackConsolePatch ->
            if (snapshot.patch.activeLayer != null) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)
            }

        DshMaintenanceConsoleIntent.ResetTheme ->
            if (snapshot.theme.customized) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)
            }

        is DshMaintenanceConsoleIntent.RunClosureUpgrade -> when {
            // Every non-terminal-with-decision phase owns a pending trial/apply/recovery decision.
            // Running a new upgrade over it would overwrite that decision and its evidence.
            // COMMITTED and ROLLED_BACK are settled: a newly staged candidate is a new decision.
            snapshot.upgrade.phase != DshMaintenanceUpgradePhase.IDLE &&
                snapshot.upgrade.phase != DshMaintenanceUpgradePhase.ROLLED_BACK &&
                snapshot.upgrade.phase != DshMaintenanceUpgradePhase.COMMITTED ->
                denied(DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS)

            // No verified staged candidate was projected: the port could only invent a closure.
            snapshot.upgrade.candidateVersion == null ->
                denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)

            else -> DshMaintenanceGate.Allowed
        }

        DshMaintenanceConsoleIntent.ResumeClosureUpgrade -> when (snapshot.upgrade.phase) {
            DshMaintenanceUpgradePhase.TRIAL_RUNNING,
            DshMaintenanceUpgradePhase.HEALTH_PENDING -> DshMaintenanceGate.Allowed

            else -> denied(DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE)
        }

        // The only in-app way out of RECOVERY_REQUIRED: it never moves the selector, so it is
        // offered exactly where the upgrade is otherwise a permanent boot block.
        is DshMaintenanceConsoleIntent.AbandonClosureUpgrade -> when (snapshot.upgrade.phase) {
            DshMaintenanceUpgradePhase.RECOVERY_REQUIRED -> DshMaintenanceGate.Allowed
            else -> denied(DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE)
        }

        DshMaintenanceConsoleIntent.ExportDiagnostics ->
            if (snapshot.diagnosticsReady) {
                DshMaintenanceGate.Allowed
            } else {
                denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE)
            }

        else -> DshMaintenanceGate.Allowed
    }

    private fun denied(reason: DshMaintenanceConsoleRefusal): DshMaintenanceGate =
        DshMaintenanceGate.Denied(reason)
}
