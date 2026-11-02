package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshMaintenanceBackup
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
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

class DshMaintenanceConsolePolicyTest {

    private val allOperations = DshMaintenanceOperationKind.entries.toSet()

    @Test
    fun absentPortDisablesEveryIntent() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.UNAVAILABLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ListBackups,
                snapshot(),
                portAvailable = false
            )
        )
        assertTrue(
            DshMaintenanceConsolePolicy.availableIntents(snapshot(), portAvailable = false).isEmpty()
        )
    }

    @Test
    fun ownerUnavailableAndBusyStatesAreReflectedHonestly() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.UNAVAILABLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ListBackups,
                snapshot(availability = DshMaintenanceConsoleAvailability.UNAVAILABLE),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.BUSY),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.CreateBackup(),
                snapshot(availability = DshMaintenanceConsoleAvailability.BUSY, busy = true),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.USER_PRESENCE_REQUIRED),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ExportDiagnostics,
                snapshot(availability = DshMaintenanceConsoleAvailability.USER_PRESENCE_REQUIRED),
                portAvailable = true
            )
        )
    }

    @Test
    fun absentCapabilityIsNotEligible() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.CreateBackup(),
                snapshot(operations = setOf(DshMaintenanceOperationKind.LIST_SESSIONS)),
                portAvailable = true
            )
        )
        assertEquals(
            emptySet<DshMaintenanceConsoleIntent>(),
            DshMaintenanceConsolePolicy.availableIntents(
                snapshot(operations = emptySet()),
                portAvailable = true
            )
        )
    }

    @Test
    fun destructiveIntentRequiresConfirmation() {
        val intent = DshMaintenanceConsoleIntent.PurgeTrashedSession("session-trashed")

        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED),
            DshMaintenanceConsolePolicy.gate(intent, snapshot(), portAvailable = true)
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(intent),
                snapshot(),
                portAvailable = true
            )
        )
    }

    @Test
    fun everyDestructiveKindCarriesAConfirmationRequirement() {
        val destructive = listOf(
            DshMaintenanceConsoleIntent.RestoreBackup("backup-1"),
            DshMaintenanceConsoleIntent.PurgeTrashedSession("session-trashed"),
            DshMaintenanceConsoleIntent.RepairSession("session-1"),
            DshMaintenanceConsoleIntent.ApplyConsolePatch(),
            DshMaintenanceConsoleIntent.ImportTheme(),
            DshMaintenanceConsoleIntent.RunClosureUpgrade()
        )

        destructive.forEach { intent ->
            assertTrue("${intent.kind} must require confirmation", intent.requiresConfirmation)
            assertTrue(DshMaintenanceConsolePolicy.confirmationFor(intent) != null)
            assertEquals(
                DshMaintenanceGate.Allowed,
                DshMaintenanceConsolePolicy.gate(
                    DshMaintenanceConsolePolicy.confirmed(intent),
                    snapshot(),
                    portAvailable = true
                )
            )
        }
    }

    @Test
    fun nonDestructiveIntentsDoNotRequireConfirmation() {
        val intents = listOf(
            DshMaintenanceConsoleIntent.ListBackups,
            DshMaintenanceConsoleIntent.ListSessions,
            DshMaintenanceConsoleIntent.CreateBackup(),
            DshMaintenanceConsoleIntent.TrashSession("session-1"),
            DshMaintenanceConsoleIntent.TrashSession("session-trashed"),
            DshMaintenanceConsoleIntent.RestoreTrashedSession("session-trashed"),
            DshMaintenanceConsoleIntent.EnterSafeMode,
            DshMaintenanceConsoleIntent.ExitSafeMode,
            DshMaintenanceConsoleIntent.RollbackConsolePatch,
            DshMaintenanceConsoleIntent.ResetTheme,
            DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
            DshMaintenanceConsoleIntent.ExportDiagnostics
        )

        intents.forEach { intent ->
            assertFalse("${intent.kind} must not require confirmation", intent.requiresConfirmation)
            assertNull(DshMaintenanceConsolePolicy.confirmationFor(intent))
        }
    }

    @Test
    fun missingBackupOrSessionTargetsAreRefused() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_FOUND),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RestoreBackup("backup-x")),
                snapshot(),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_FOUND),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.TrashSession("session-x"),
                snapshot(),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.TRASH_EMPTY),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.PurgeTrashedSession("session-x")),
                snapshot(trashCount = 0),
                portAvailable = true
            )
        )
    }

    @Test
    fun sessionActionsRequireMatchingTrashState() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_FOUND),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.TrashSession("session-trashed"),
                snapshot(),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_FOUND),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.RestoreTrashedSession("session-1"),
                snapshot(),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.RestoreTrashedSession("session-trashed"),
                snapshot(),
                portAvailable = true
            )
        )
    }

    @Test
    fun safeModeToggleIsGatedByCurrentState() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.SAFE_MODE_ACTIVE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.EnterSafeMode,
                snapshot(safeMode = true),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ExitSafeMode,
                snapshot(safeMode = false),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ExitSafeMode,
                snapshot(safeMode = true),
                portAvailable = true
            )
        )
    }

    @Test
    fun patchAndThemeActionsAreGatedByProjection() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.PATCH_CONFLICT),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.ApplyConsolePatch()),
                snapshot(patch = DshMaintenancePatchState(conflict = true)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.RollbackConsolePatch,
                snapshot(patch = DshMaintenancePatchState()),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.RollbackConsolePatch,
                snapshot(patch = DshMaintenancePatchState(activeLayer = DshMaintenancePatchLayer.USER)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResetTheme,
                snapshot(theme = DshMaintenanceThemeState(customized = false)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResetTheme,
                snapshot(theme = DshMaintenanceThemeState(customized = true)),
                portAvailable = true
            )
        )
    }

    @Test
    fun upgradeActionsFollowTheUpgradePhase() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.TRIAL_RUNNING)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.HEALTH_PENDING)),
                portAvailable = true
            )
        )
        // A committed closure is settled, not a refusal of the next staged candidate. Without a
        // projected candidate the port has nothing to run; with one, a new upgrade is allowed.
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.COMMITTED)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(
                    upgrade = DshMaintenanceUpgradeState(
                        phase = DshMaintenanceUpgradePhase.COMMITTED,
                        candidateVersion = "candidate-9"
                    )
                ),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(
                    upgrade = DshMaintenanceUpgradeState(
                        DshMaintenanceUpgradePhase.ROLLED_BACK,
                        candidateVersion = "candidate-1"
                    )
                ),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.HEALTH_PENDING)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.TRIAL_RUNNING)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.IDLE)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.COMMITTED)),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NO_ACTIVE_UPGRADE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ResumeClosureUpgrade,
                snapshot(upgrade = DshMaintenanceUpgradeState(DshMaintenanceUpgradePhase.RECOVERY_REQUIRED)),
                portAvailable = true
            )
        )
    }

    @Test
    fun runClosureUpgradeRequiresAVerifiedStagedCandidate() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(DshMaintenanceConsoleIntent.RunClosureUpgrade()),
                snapshot(upgrade = DshMaintenanceUpgradeState()),
                portAvailable = true
            )
        )
        assertTrue(
            DshMaintenanceConsoleIntent.RunClosureUpgrade() !in
                DshMaintenanceConsolePolicy.availableIntents(
                    snapshot(upgrade = DshMaintenanceUpgradeState()),
                    portAvailable = true
                )
        )
        assertTrue(
            DshMaintenanceConsoleIntent.RunClosureUpgrade() in
                DshMaintenanceConsolePolicy.availableIntents(snapshot(), portAvailable = true)
        )
    }

    @Test
    fun diagnosticsExportRequiresOwnerReadiness() {
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.NOT_ELIGIBLE),
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ExportDiagnostics,
                snapshot(diagnosticsReady = false),
                portAvailable = true
            )
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.ExportDiagnostics,
                snapshot(diagnosticsReady = true),
                portAvailable = true
            )
        )
    }

    @Test
    fun availableIntentsIncludeDestructiveActionsForExistingTargets() {
        val available = DshMaintenanceConsolePolicy.availableIntents(snapshot(), portAvailable = true)

        assertTrue(DshMaintenanceConsoleIntent.CreateBackup() in available)
        assertTrue(DshMaintenanceConsoleIntent.RestoreBackup("backup-1") in available)
        assertTrue(DshMaintenanceConsoleIntent.PurgeTrashedSession("session-trashed") in available)
        assertTrue(DshMaintenanceConsoleIntent.RepairSession("session-1") in available)
        assertTrue(DshMaintenanceConsoleIntent.ApplyConsolePatch() in available)
        assertTrue(DshMaintenanceConsoleIntent.RunClosureUpgrade() in available)
        assertTrue(DshMaintenanceConsoleIntent.RestoreBackup("backup-x") !in available)
    }

    @Test
    fun resultAndRefusalMessagesAreSpecific() {
        assertEquals(
            "维护操作正在进行，请等待完成。",
            DshMaintenanceConsolePolicy.refusalMessage(DshMaintenanceConsoleRefusal.BUSY)
        )
        assertEquals(
            "owner message",
            DshMaintenanceConsolePolicy.refusalMessage(DshMaintenanceConsoleRefusal.BUSY, "owner message")
        )
        assertEquals(
            "done",
            DshMaintenanceConsolePolicy.resultMessage(
                DshMaintenanceConsoleResult.Applied(DshMaintenanceOperationKind.CREATE_BACKUP, "done")
            )
        )
        assertTrue(
            DshMaintenanceConsolePolicy.resultMessage(
                DshMaintenanceConsoleResult.Applied(DshMaintenanceOperationKind.CREATE_BACKUP)
            ).contains("创建备份")
        )
        assertTrue(
            DshMaintenanceConsolePolicy.resultMessage(
                DshMaintenanceConsoleResult.Failed(
                    DshMaintenanceOperationKind.CREATE_BACKUP,
                    "STORAGE_INSUFFICIENT",
                    retryable = true
                )
            ).contains("失败")
        )
        // A typed trial/health failure must stay diagnosable instead of collapsing into "失败".
        val typedFailure = DshMaintenanceConsolePolicy.resultMessage(
            DshMaintenanceConsoleResult.Failed(
                DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE,
                "TRIAL_FAILED",
                retryable = true,
                message = "the isolated trial did not reach a read-only healthy state"
            )
        )
        assertTrue(typedFailure.contains("TRIAL_FAILED"))
        assertTrue(typedFailure.contains("isolated trial"))
    }

    @Test
    fun selfCheckIsReadOnlyAndOfferedWithoutConfirmation() {
        val snap = snapshot()
        assertFalse(DshMaintenanceConsolePolicy.requiresConfirmation(DshMaintenanceConsoleIntent.RunSelfCheck))
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsoleIntent.RunSelfCheck,
                snap,
                portAvailable = true
            )
        )
        assertTrue(
            DshMaintenanceConsoleIntent.RunSelfCheck in
                DshMaintenanceConsolePolicy.availableIntents(snap, portAvailable = true)
        )
    }

    private fun snapshot(
        availability: DshMaintenanceConsoleAvailability = DshMaintenanceConsoleAvailability.AVAILABLE,
        busy: Boolean = false,
        operations: Set<DshMaintenanceOperationKind> = allOperations,
        trashCount: Int = 1,
        safeMode: Boolean = false,
        patch: DshMaintenancePatchState = DshMaintenancePatchState(),
        theme: DshMaintenanceThemeState = DshMaintenanceThemeState(),
        upgrade: DshMaintenanceUpgradeState = DshMaintenanceUpgradeState(
            // A verified staged candidate is what makes "run" an eligible action.
            candidateVersion = "candidate-1"
        ),
        diagnosticsReady: Boolean = true
    ): DshMaintenanceConsoleSnapshot = DshMaintenanceConsoleSnapshot(
        revision = 3,
        capturedAtMillis = 1_000L,
        availability = availability,
        availableOperations = operations,
        busyOperation = if (busy) DshMaintenanceOperationKind.CREATE_BACKUP else null,
        backups = listOf(
            DshMaintenanceBackup(
                backupId = "backup-1",
                createdAtMillis = 900L,
                label = "每日备份",
                sizeBytes = 4_096L,
                entryCount = 3
            )
        ),
        sessions = listOf(
            DshMaintenanceSession("session-1", updatedAtMillis = 950L, messageCount = 4),
            DshMaintenanceSession("session-trashed", updatedAtMillis = 940L, messageCount = 2, trashed = true)
        ),
        trashCount = trashCount,
        safeModeActive = safeMode,
        patch = patch,
        theme = theme,
        upgrade = upgrade,
        diagnosticsReady = diagnosticsReady
    )

    @Test
    fun mcpDeclarationImportIsConfirmedAndLabelled() {
        val snap = snapshot()
        // The console advertises the document action without a payload; the picker attaches it.
        val advertised = DshMaintenanceConsoleIntent.ImportMcpDeclaration()
        val intent = DshMaintenanceConsoleIntent.ImportMcpDeclaration(payload = "{}")
        assertTrue(DshMaintenanceConsolePolicy.requiresConfirmation(intent))
        assertEquals(
            DshMaintenanceGate.Denied(DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED),
            DshMaintenanceConsolePolicy.gate(intent, snap, portAvailable = true)
        )
        assertEquals(
            DshMaintenanceGate.Allowed,
            DshMaintenanceConsolePolicy.gate(
                DshMaintenanceConsolePolicy.confirmed(intent),
                snap,
                portAvailable = true
            )
        )
        val available = DshMaintenanceConsolePolicy.availableIntents(snap, portAvailable = true)
        assertTrue(advertised in available)
        assertEquals("导入 MCP 声明", DshMaintenanceConsolePolicy.kindLabel(intent.kind))
        assertEquals(
            "导入 MCP 声明",
            DshMaintenanceConsolePolicy.confirmationFor(intent)?.title?.removePrefix("确认")
        )
    }

}
