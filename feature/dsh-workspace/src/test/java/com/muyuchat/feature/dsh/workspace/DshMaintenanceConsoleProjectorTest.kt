package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshMaintenanceBackup
import com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree
import com.muyuchat.core.dsh.contract.console.DshSelfCheckSummary
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenancePatchLayer
import com.muyuchat.core.dsh.contract.console.DshMaintenancePatchState
import com.muyuchat.core.dsh.contract.console.DshMaintenanceSession
import com.muyuchat.core.dsh.contract.console.DshMaintenanceThemeState
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshMaintenanceConsoleProjectorTest {

    @Test
    fun projectsAvailabilityAndStatusRows() {
        val model = DshMaintenanceConsoleProjector.project(snapshot())

        assertEquals("可用", model.availabilityLabel)
        assertTrue(model.actionsEnabled)
        val status = section(model, "维护状态")
        assertEquals("可用", valueOf(status, "维护状态"))
        assertEquals("1 个会话", valueOf(status, "回收站"))
        assertEquals("未启用", valueOf(status, "安全模式"))
        assertEquals("可用", valueOf(status, "诊断导出"))
    }

    @Test
    fun lastSelfCheckIsAPersistentStatusRow() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(
                lastSelfCheck = DshSelfCheckSummary(
                    degree = DshSelfCheckDegree.ATTENTION,
                    checkedCount = 5,
                    issueCount = 1,
                    capturedAtMillis = 1_000L,
                    headline = "SESSION_READER: codec missing"
                )
            )
        )

        val status = section(model, "维护状态")
        assertEquals("需处理 · 1/5 项", valueOf(status, "最近自检"))
        assertEquals(DshConsoleTone.WARNING, rowOf(status, "最近自检").tone)
    }

    @Test
    fun busyProjectionNamesTheRunningOperation() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(
                availability = DshMaintenanceConsoleAvailability.BUSY,
                busy = DshMaintenanceOperationKind.CREATE_BACKUP
            )
        )

        assertFalse(model.actionsEnabled)
        assertEquals("维护操作进行中", model.availabilityLabel)
        assertEquals("创建备份", valueOf(section(model, "维护状态"), "进行中"))
    }

    @Test
    fun emptyBackupsAndSessionsHaveExplicitEmptyStates() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(backups = emptyList(), sessions = emptyList())
        )

        assertEquals("尚无备份", valueOf(section(model, "备份"), "备份"))
        assertEquals("暂无会话", valueOf(section(model, "会话"), "会话"))
        assertEquals("尚无备份", model.backupsNote)
        assertEquals("暂无会话", model.sessionsNote)
        assertTrue(model.backups.isEmpty())
        assertTrue(model.sessions.isEmpty())
    }

    @Test
    fun backupsProjectLabelVerificationSizeAndEntries() {
        val model = DshMaintenanceConsoleProjector.project(snapshot())

        assertEquals("每日备份", rowOf(section(model, "备份"), "每日备份").label)
        assertEquals("已校验 · 3 项 · 4 KB", rowOf(section(model, "备份"), "每日备份").value)
        assertEquals(1, model.backups.size)
        assertEquals("backup-1", model.backups.first().backupId)
        assertEquals("每日备份", model.backups.first().label)
    }

    @Test
    fun unverifiedBackupIsMarkedAsWarning() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(
                backups = listOf(
                    DshMaintenanceBackup("backup-2", createdAtMillis = 1L, verified = false)
                )
            )
        )

        val row = rowOf(section(model, "备份"), "backup-2")
        assertEquals(DshConsoleTone.WARNING, row.tone)
        assertTrue(row.value.startsWith("未校验"))
    }

    @Test
    fun sessionsProjectTrashStateAndMessageCounts() {
        val model = DshMaintenanceConsoleProjector.project(snapshot())
        val sessions = section(model, "会话")

        assertEquals("4 条消息", valueOf(sessions, "主会话"))
        assertEquals("回收站 · 2 条消息", valueOf(sessions, "已删除会话"))
        assertEquals(DshConsoleTone.WARNING, rowOf(sessions, "已删除会话").tone)
        assertEquals(2, model.sessions.size)
        assertTrue(model.sessions.first().trashed)
        assertFalse(model.sessions.last().trashed)
    }

    @Test
    fun trashedSessionsSurviveTheSessionLimit() {
        val sessions = (1..50).map { index ->
            DshMaintenanceSession("session-$index", updatedAtMillis = index.toLong())
        } + DshMaintenanceSession(
            "session-trashed",
            updatedAtMillis = 1_000L,
            messageCount = 1,
            trashed = true,
            label = "已删除会话"
        )

        val snapshot = snapshot(sessions = sessions)
        val model = DshMaintenanceConsoleProjector.project(snapshot)

        assertEquals(50, model.sessions.size)
        assertTrue(model.sessions.first().trashed)
        assertEquals("已删除会话", model.sessions.first().label)
        assertFalse(model.sessions.any { it.sessionId == "session-50" })
        assertTrue(model.sessionsNote.orEmpty().contains("前 50"))
        val available = DshMaintenanceConsolePolicy.availableIntents(snapshot, portAvailable = true)
        assertTrue(available.contains(DshMaintenanceConsoleIntent.RestoreTrashedSession("session-trashed")))
    }

    @Test
    fun patchAndThemeRowsComeFromTheProjectionOnly() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(
                patch = DshMaintenancePatchState(
                    activeLayer = DshMaintenancePatchLayer.USER,
                    userLayerPresent = true,
                    managedLayerPresent = true,
                    conflict = true
                ),
                theme = DshMaintenanceThemeState(themeId = "theme.dark", revision = 5, customized = true)
            )
        )

        val section = section(model, "补丁与主题")
        assertEquals("用户层", valueOf(section, "活动补丁层"))
        assertEquals("存在", valueOf(section, "用户补丁"))
        assertEquals("有", valueOf(section, "补丁冲突"))
        assertEquals(DshConsoleTone.NEGATIVE, rowOf(section, "补丁冲突").tone)
        assertEquals("theme.dark", valueOf(section, "主题"))
        assertEquals("5", valueOf(section, "主题版本"))
        assertEquals("已定制", valueOf(section, "主题定制"))
    }

    @Test
    fun upgradeRowsCoverPhaseCandidateAndProgress() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(
                upgrade = DshMaintenanceUpgradeState(
                    phase = DshMaintenanceUpgradePhase.TRIAL_RUNNING,
                    candidateVersion = "1.2.3",
                    progressPercent = 42
                )
            )
        )

        val section = section(model, "整闭包升级")
        assertEquals("隔离 trial 运行中", valueOf(section, "升级阶段"))
        assertEquals(DshConsoleTone.WARNING, rowOf(section, "升级阶段").tone)
        assertEquals("1.2.3", valueOf(section, "候选版本"))
        assertEquals("42%", valueOf(section, "进度"))
        // A pending trial is resumable, and the console says so instead of only disabling a button.
        assertTrue(rowOf(section, "待处理").value.contains("恢复升级"))
    }

    @Test
    fun upgradePhaseTonesDistinguishIdlePendingCommittedAndRolledBack() {
        fun phaseRow(phase: DshMaintenanceUpgradePhase): DshConsoleRow =
            rowOf(
                section(
                    DshMaintenanceConsoleProjector.project(
                        snapshot(upgrade = DshMaintenanceUpgradeState(phase, candidateVersion = "candidate-1"))
                    ),
                    "整闭包升级"
                ),
                "升级阶段"
            )

        assertEquals(DshConsoleTone.NEUTRAL, phaseRow(DshMaintenanceUpgradePhase.IDLE).tone)
        assertEquals(DshConsoleTone.WARNING, phaseRow(DshMaintenanceUpgradePhase.TRIAL_RUNNING).tone)
        assertEquals(DshConsoleTone.WARNING, phaseRow(DshMaintenanceUpgradePhase.HEALTH_PENDING).tone)
        assertEquals(DshConsoleTone.POSITIVE, phaseRow(DshMaintenanceUpgradePhase.COMMITTED).tone)
        assertEquals(DshConsoleTone.WARNING, phaseRow(DshMaintenanceUpgradePhase.ROLLED_BACK).tone)
        assertEquals(DshConsoleTone.NEGATIVE, phaseRow(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED).tone)
    }

    @Test
    fun recoveryRequiredPhaseIsNegative() {
        val model = DshMaintenanceConsoleProjector.project(
            snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED))
        )

        val row = rowOf(section(model, "整闭包升级"), "升级阶段")
        assertEquals(DshConsoleTone.NEGATIVE, row.tone)
        assertEquals("需要恢复", row.value)
    }

    @Test
    fun longListsAreBoundedWithAnExplicitNote() {
        val backups = (1..21).map { index ->
            DshMaintenanceBackup("backup-$index", createdAtMillis = index.toLong())
        }
        val sessions = (1..51).map { index ->
            DshMaintenanceSession("session-$index", updatedAtMillis = index.toLong())
        }

        val model = DshMaintenanceConsoleProjector.project(
            snapshot(backups = backups, sessions = sessions)
        )

        assertEquals(20, model.backups.size)
        assertEquals(50, model.sessions.size)
        assertTrue(model.backupsNote.orEmpty().contains("前 20"))
        assertTrue(model.sessionsNote.orEmpty().contains("前 50"))
    }

    @Test
    fun projectionIsDeterministicAndPathFree() {
        val snapshot = snapshot()

        val first = DshMaintenanceConsoleProjector.project(snapshot)
        val second = DshMaintenanceConsoleProjector.project(snapshot)

        assertEquals(first, second)
        val text = buildString {
            first.sections.forEach { section ->
                append(section.title)
                section.rows.forEach { append(it.label).append(it.value) }
            }
            first.backups.forEach { append(it.label).append(it.detail) }
            first.sessions.forEach { append(it.label).append(it.detail) }
        }
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/data/"))
        assertFalse(text.contains("file://"))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test
    fun noBackupOrSessionActionIsProjectedForMissingTargets() {
        val available = DshMaintenanceConsolePolicy.availableIntents(
            snapshot(backups = emptyList(), sessions = emptyList()),
            portAvailable = true
        )

        assertTrue(available.none { it is DshMaintenanceConsoleIntent.RestoreBackup })
        assertTrue(available.none { it is DshMaintenanceConsoleIntent.TrashSession })
        assertTrue(available.any { it == DshMaintenanceConsoleIntent.CreateBackup() })
    }

    private fun snapshot(
        availability: DshMaintenanceConsoleAvailability = DshMaintenanceConsoleAvailability.AVAILABLE,
        busy: DshMaintenanceOperationKind? = null,
        backups: List<DshMaintenanceBackup> = listOf(
            DshMaintenanceBackup(
                backupId = "backup-1",
                createdAtMillis = 900L,
                label = "每日备份",
                sizeBytes = 4_096L,
                entryCount = 3
            )
        ),
        sessions: List<DshMaintenanceSession> = listOf(
            DshMaintenanceSession("session-1", updatedAtMillis = 950L, messageCount = 4, label = "主会话"),
            DshMaintenanceSession(
                "session-trashed",
                updatedAtMillis = 940L,
                messageCount = 2,
                trashed = true,
                label = "已删除会话"
            )
        ),
        patch: DshMaintenancePatchState = DshMaintenancePatchState(),
        theme: DshMaintenanceThemeState = DshMaintenanceThemeState(),
        upgrade: DshMaintenanceUpgradeState = DshMaintenanceUpgradeState(),
        lastSelfCheck: DshSelfCheckSummary? = null
    ): DshMaintenanceConsoleSnapshot = DshMaintenanceConsoleSnapshot(
        revision = 2,
        capturedAtMillis = 1_000L,
        availability = availability,
        availableOperations = DshMaintenanceOperationKind.entries.toSet(),
        busyOperation = busy,
        backups = backups,
        sessions = sessions,
        trashCount = sessions.count { it.trashed },
        patch = patch,
        theme = theme,
        upgrade = upgrade,
        diagnosticsReady = true,
        lastSelfCheck = lastSelfCheck
    )

    private fun section(model: DshMaintenanceConsoleUiModel, title: String): DshConsoleSection =
        requireNotNull(model.sections.firstOrNull { it.title == title }) {
            "missing section '$title'; have ${model.sections.map { it.title }}"
        }

    private fun valueOf(section: DshConsoleSection, label: String): String =
        rowOf(section, label).value

    private fun rowOf(section: DshConsoleSection, label: String): DshConsoleRow =
        requireNotNull(section.rows.firstOrNull { it.label == label }) {
            "missing row '$label'; have ${section.rows.map { it.label }}"
        }
}
