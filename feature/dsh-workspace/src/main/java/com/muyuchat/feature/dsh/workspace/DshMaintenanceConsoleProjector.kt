package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshMaintenanceBackup
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenancePatchLayer
import com.muyuchat.core.dsh.contract.console.DshMaintenanceSession
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase

/** One backup row as the console renders it; carries no path. */
data class DshMaintenanceBackupUi(
    val backupId: String,
    val label: String,
    val detail: String
)

/** One session row; metadata only, no title content beyond the owner-provided bounded label. */
data class DshMaintenanceSessionUi(
    val sessionId: String,
    val label: String,
    val detail: String,
    val trashed: Boolean
)

/** Read-only rendered maintenance section. Holds no owner state and no write capability. */
data class DshMaintenanceConsoleUiModel(
    val revision: Long,
    val availabilityLabel: String,
    val actionsEnabled: Boolean,
    val sections: List<DshConsoleSection>,
    val backups: List<DshMaintenanceBackupUi>,
    val sessions: List<DshMaintenanceSessionUi>,
    val backupsNote: String?,
    val sessionsNote: String?,
    val safeModeActive: Boolean,
    val upgradePhase: DshMaintenanceUpgradePhase
) {
    init {
        require(revision >= 0L) { "maintenance UI revision must be non-negative" }
    }
}

/**
 * Pure projection of the maintenance console (D23). It maps only the bounded contract snapshot;
 * it never reads files, session bodies, backups or the maintenance owner directly, and never
 * prints a path, URI or credential.
 */
object DshMaintenanceConsoleProjector {

    private const val MAX_LISTED_BACKUPS = 20
    private const val MAX_LISTED_SESSIONS = 50

    fun project(snapshot: DshMaintenanceConsoleSnapshot): DshMaintenanceConsoleUiModel {
        val backups = snapshot.backups
        val sessions = orderedSessions(snapshot.sessions)
        return DshMaintenanceConsoleUiModel(
            revision = snapshot.revision,
            availabilityLabel = availabilityLabel(snapshot.availability),
            actionsEnabled = snapshot.availability == DshMaintenanceConsoleAvailability.AVAILABLE,
            sections = listOf(
                statusSection(snapshot),
                backupsSection(snapshot),
                sessionsSection(snapshot),
                patchAndThemeSection(snapshot),
                upgradeSection(snapshot)
            ),
            backups = backups.take(MAX_LISTED_BACKUPS).map(::backupUi),
            sessions = sessions.take(MAX_LISTED_SESSIONS).map(::sessionUi),
            backupsNote = when {
                backups.isEmpty() -> "尚无备份"
                backups.size > MAX_LISTED_BACKUPS -> "仅显示前 $MAX_LISTED_BACKUPS 条备份"
                else -> null
            },
            sessionsNote = when {
                sessions.isEmpty() -> "暂无会话"
                sessions.size > MAX_LISTED_SESSIONS -> "仅显示前 $MAX_LISTED_SESSIONS 条会话"
                else -> null
            },
            safeModeActive = snapshot.safeModeActive,
            upgradePhase = snapshot.upgrade.phase
        )
    }

    private fun statusSection(snapshot: DshMaintenanceConsoleSnapshot): DshConsoleSection {
        val rows = mutableListOf(
            DshConsoleRow(
                "维护状态",
                availabilityLabel(snapshot.availability),
                availabilityTone(snapshot.availability)
            )
        )
        snapshot.busyOperation?.let { operation ->
            rows += DshConsoleRow(
                "进行中",
                DshMaintenanceConsolePolicy.kindLabel(operation),
                DshConsoleTone.WARNING
            )
        }
        rows += DshConsoleRow(
            "安全模式",
            if (snapshot.safeModeActive) "已启用" else "未启用",
            if (snapshot.safeModeActive) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
        )
        rows += DshConsoleRow("回收站", "${snapshot.trashCount} 个会话")
        if (DshMaintenanceOperationKind.CREATE_BACKUP !in snapshot.availableOperations) {
            rows += DshConsoleRow(
                "写操作前置",
                "请先停止 DSH 运行时",
                DshConsoleTone.WARNING
            )
        }
        rows += DshConsoleRow(
            "诊断导出",
            if (snapshot.diagnosticsReady) "可用" else "不可用",
            if (snapshot.diagnosticsReady) DshConsoleTone.POSITIVE else DshConsoleTone.WARNING
        )
        val selfCheck = snapshot.lastSelfCheck
        rows += if (selfCheck == null) {
            DshConsoleRow("最近自检", "未运行", DshConsoleTone.NEUTRAL)
        } else {
            val label = when (selfCheck.degree) {
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.HEALTHY ->
                    "通过 · ${selfCheck.checkedCount} 项"
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.NEEDS_REPAIR ->
                    "需修复 · ${selfCheck.issueCount}/${selfCheck.checkedCount} 项"
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.ATTENTION ->
                    "需处理 · ${selfCheck.issueCount}/${selfCheck.checkedCount} 项"
            }
            val tone = when (selfCheck.degree) {
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.HEALTHY -> DshConsoleTone.POSITIVE
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.NEEDS_REPAIR -> DshConsoleTone.WARNING
                com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.ATTENTION -> DshConsoleTone.WARNING
            }
            DshConsoleRow("最近自检", label, tone)
        }
        rows += DshConsoleRow("投影版本", snapshot.revision.toString())
        return DshConsoleSection("维护状态", rows)
    }

    private fun backupsSection(snapshot: DshMaintenanceConsoleSnapshot): DshConsoleSection {
        val rows = if (snapshot.backups.isEmpty()) {
            listOf(DshConsoleRow("备份", "尚无备份", DshConsoleTone.NEUTRAL))
        } else {
            snapshot.backups.take(MAX_LISTED_BACKUPS).map(::backupRow)
        }
        return DshConsoleSection("备份", rows)
    }

    private fun sessionsSection(snapshot: DshMaintenanceConsoleSnapshot): DshConsoleSection {
        val sessions = orderedSessions(snapshot.sessions)
        val rows = if (sessions.isEmpty()) {
            listOf(DshConsoleRow("会话", "暂无会话", DshConsoleTone.NEUTRAL))
        } else {
            sessions.take(MAX_LISTED_SESSIONS).map(::sessionRow)
        }
        return DshConsoleSection("会话", rows)
    }

    /**
     * Trashed sessions carry the only restore/purge controls. They must survive the bounded
     * session list even when active sessions already fill it, otherwise the recycle bin is
     * visible in the status row but cannot be acted on.
     */
    private fun orderedSessions(sessions: List<DshMaintenanceSession>): List<DshMaintenanceSession> =
        sessions.filter { it.trashed } + sessions.filterNot { it.trashed }

    private fun patchAndThemeSection(snapshot: DshMaintenanceConsoleSnapshot): DshConsoleSection {
        val patch = snapshot.patch
        val theme = snapshot.theme
        val activeLayer = when (patch.activeLayer) {
            DshMaintenancePatchLayer.MANAGED -> "受管层"
            DshMaintenancePatchLayer.USER -> "用户层"
            null -> "无"
        }
        return DshConsoleSection(
            "补丁与主题",
            listOf(
                DshConsoleRow("活动补丁层", activeLayer),
                DshConsoleRow(
                    "用户补丁",
                    if (patch.userLayerPresent) "存在" else "不存在"
                ),
                DshConsoleRow(
                    "受管补丁",
                    if (patch.managedLayerPresent) "存在" else "不存在"
                ),
                DshConsoleRow(
                    "补丁冲突",
                    if (patch.conflict) "有" else "无",
                    if (patch.conflict) DshConsoleTone.NEGATIVE else DshConsoleTone.NEUTRAL
                ),
                DshConsoleRow("主题", theme.themeId ?: "默认"),
                DshConsoleRow("主题版本", theme.revision.toString()),
                DshConsoleRow(
                    "主题定制",
                    if (theme.customized) "已定制" else "未定制"
                )
            )
        )
    }

    private fun upgradeSection(snapshot: DshMaintenanceConsoleSnapshot): DshConsoleSection {
        val upgrade = snapshot.upgrade
        val rows = mutableListOf(
            DshConsoleRow(
                "升级阶段",
                upgradePhaseLabel(upgrade.phase),
                upgradePhaseTone(upgrade.phase)
            )
        )
        upgrade.candidateVersion?.let { rows += DshConsoleRow("候选版本", it) }
        upgrade.progressPercent?.let { rows += DshConsoleRow("进度", "$it%") }
        // The console only offers resume for a pending trial/health step; say so instead of leaving
        // the user with a disabled button and no explanation. It is shown only when the owner really
        // advertised the resume action, never as a promise the port cannot keep.
        val resumeAdvertised =
            DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE in snapshot.availableOperations
        if (resumeAdvertised &&
            (upgrade.phase == DshMaintenanceUpgradePhase.TRIAL_RUNNING ||
                upgrade.phase == DshMaintenanceUpgradePhase.HEALTH_PENDING)
        ) {
            rows += DshConsoleRow(
                "待处理",
                "恢复升级可继续未完成的隔离试跑/健康确认",
                DshConsoleTone.WARNING
            )
        }
        return DshConsoleSection("整闭包升级", rows)
    }

    private fun backupRow(backup: DshMaintenanceBackup): DshConsoleRow =
        DshConsoleRow(
            label = backup.label ?: shortId(backup.backupId),
            value = backupDetail(backup),
            tone = if (backup.verified) DshConsoleTone.POSITIVE else DshConsoleTone.WARNING
        )

    private fun backupUi(backup: DshMaintenanceBackup): DshMaintenanceBackupUi =
        DshMaintenanceBackupUi(
            backupId = backup.backupId,
            label = backup.label ?: shortId(backup.backupId),
            detail = backupDetail(backup)
        )

    private fun sessionRow(session: DshMaintenanceSession): DshConsoleRow =
        DshConsoleRow(
            label = session.label ?: shortId(session.sessionId),
            value = sessionDetail(session),
            tone = if (session.trashed) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
        )

    private fun sessionUi(session: DshMaintenanceSession): DshMaintenanceSessionUi =
        DshMaintenanceSessionUi(
            sessionId = session.sessionId,
            label = session.label ?: shortId(session.sessionId),
            detail = sessionDetail(session),
            trashed = session.trashed
        )

    private fun backupDetail(backup: DshMaintenanceBackup): String = buildString {
        append(if (backup.verified) "已校验" else "未校验")
        append(" · ").append(backup.entryCount).append(" 项")
        append(" · ").append(formatBytes(backup.sizeBytes))
    }

    private fun sessionDetail(session: DshMaintenanceSession): String = buildString {
        if (session.trashed) append("回收站 · ")
        if (session.messageCountKnown) {
            append(session.messageCount).append(" 条消息")
        } else {
            append("消息数未知")
        }
    }

    private fun shortId(value: String): String =
        if (value.length <= 12) value else value.take(8) + "…" + value.takeLast(3)

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    fun availabilityLabel(availability: DshMaintenanceConsoleAvailability): String =
        when (availability) {
            DshMaintenanceConsoleAvailability.AVAILABLE -> "可用"
            DshMaintenanceConsoleAvailability.BUSY -> "维护操作进行中"
            DshMaintenanceConsoleAvailability.UNAVAILABLE -> "不可用"
            DshMaintenanceConsoleAvailability.USER_PRESENCE_REQUIRED -> "需要可见页面"
        }

    fun availabilityTone(availability: DshMaintenanceConsoleAvailability): DshConsoleTone =
        when (availability) {
            DshMaintenanceConsoleAvailability.AVAILABLE -> DshConsoleTone.POSITIVE
            DshMaintenanceConsoleAvailability.BUSY -> DshConsoleTone.WARNING
            DshMaintenanceConsoleAvailability.UNAVAILABLE -> DshConsoleTone.NEGATIVE
            DshMaintenanceConsoleAvailability.USER_PRESENCE_REQUIRED -> DshConsoleTone.WARNING
        }

    fun upgradePhaseLabel(phase: DshMaintenanceUpgradePhase): String = when (phase) {
        DshMaintenanceUpgradePhase.IDLE -> "空闲"
        DshMaintenanceUpgradePhase.TRIAL_RUNNING -> "隔离 trial 运行中"
        DshMaintenanceUpgradePhase.HEALTH_PENDING -> "等待生产健康确认"
        DshMaintenanceUpgradePhase.COMMITTED -> "已提交"
        DshMaintenanceUpgradePhase.ROLLED_BACK -> "已回滚"
        DshMaintenanceUpgradePhase.RECOVERY_REQUIRED -> "需要恢复"
    }

    /** Tone of one upgrade phase: a pending decision warns, a committed one is positive. */
    fun upgradePhaseTone(phase: DshMaintenanceUpgradePhase): DshConsoleTone = when (phase) {
        DshMaintenanceUpgradePhase.IDLE -> DshConsoleTone.NEUTRAL
        // An upgrade owns a pending trial/apply decision; it is not a failure but it is not done.
        DshMaintenanceUpgradePhase.TRIAL_RUNNING,
        DshMaintenanceUpgradePhase.HEALTH_PENDING -> DshConsoleTone.WARNING
        DshMaintenanceUpgradePhase.COMMITTED -> DshConsoleTone.POSITIVE
        DshMaintenanceUpgradePhase.ROLLED_BACK -> DshConsoleTone.WARNING
        DshMaintenanceUpgradePhase.RECOVERY_REQUIRED -> DshConsoleTone.NEGATIVE
    }
}
