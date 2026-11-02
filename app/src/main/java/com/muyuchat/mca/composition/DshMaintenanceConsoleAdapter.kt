package com.muyuchat.mca.composition

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipFile
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.console.DshMaintenanceBackup
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshMaintenanceUpgradePhase
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenancePatchLayer
import com.muyuchat.core.dsh.contract.console.DshMaintenancePatchState
import com.muyuchat.core.dsh.contract.console.DshMaintenanceSession
import com.muyuchat.core.dsh.contract.console.DshMaintenanceThemeState
import com.muyuchat.core.dsh.maintenance.DshBackupCreateResult
import com.muyuchat.core.dsh.maintenance.DshBackupRestoreResult
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshConsolePatchState
import com.muyuchat.core.dsh.maintenance.DshMaintenanceFailure
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRequester
import com.muyuchat.core.dsh.maintenance.DshPatchResult
import com.muyuchat.core.dsh.maintenance.DshQuiescenceEvidence
import com.muyuchat.core.dsh.maintenance.DshRestoreRequest
import com.muyuchat.core.dsh.maintenance.DshSafeModePhase
import com.muyuchat.core.dsh.maintenance.DshSafeModeResult
import com.muyuchat.core.dsh.maintenance.DshSessionRecycleResult
import com.muyuchat.core.dsh.maintenance.DshSessionRepairResult
import com.muyuchat.core.dsh.maintenance.DshSessionStats
import com.muyuchat.core.dsh.maintenance.DshThemeSaveResult
import com.muyuchat.core.dsh.maintenance.DshTrashEntry
import com.muyuchat.core.dsh.runtime.config.DshMcpDeclarationStore
import com.muyuchat.core.dsh.maintenance.DshTrialActiveRecord
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * App-side implementation of the D23 maintenance console port.
 *
 * It is a thin adapter: every effect is delegated to the existing [DshMaintenanceOwner] (engine,
 * backup service, session recycler/repairer, safe-mode controller, patch/theme managers, closure
 * upgrade manager). The console only ever receives bounded metadata (opaque ids, counts,
 * timestamps), never paths or session content.
 *
 * Safety rules enforced here, in addition to the owner's own checks:
 * - destructive intents must carry the explicit `confirmed` flag (the UI dialog sets it);
 * - every mutating intent requires the managed DSH host to be confirmed STOPPED, because this
 *   build has no online mutator barrier (D07m). A running host is refused with BUSY and the UI
 *   message asks the user to stop it first;
 * - patch apply, theme import and managed-file repair carry the user's document or a verified
 *   repair source; without one they are refused instead of applying an implicit payload. They are
 *   advertised only while the host is stopped;
 * - operations whose owner channel is not available in this build (session repair codec before it
 *   proves itself, closure upgrade without a verified candidate) are not advertised and are
 *   refused with UNAVAILABLE/NOT_ELIGIBLE if called anyway. Nothing is faked;
 * - the closure upgrade is advertised as "run" only for a verified staged candidate with no pending
 *   trial/apply decision, and as "resume" only while a pending trial or health step exists. The
 *   isolated trial journal is projected with the upgrade state so a live/unknown trial is never
 *   shown as an idle console.
 *
 * A backup is a `.zip` under `files/dsh/maintenance/backups/`; its opaque id is the file stem. The
 * listing never claims verification it did not perform (`verified = false`).
 */
internal class DshMaintenanceConsoleAdapter(
    private val dataRoot: File,
    private val runtime: DshRuntimeClient,
    private val maintenance: () -> DshMaintenanceOwner,
    private val appVersion: String = "unknown",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Precise managed-file repair (the payload `criticalFiles` inventory). Null keeps the action
     * unadvertised and refused: a console that cannot prove a repair source must not offer one.
     */
    private val managedRepair: DshManagedRepairPort? = null,
    /**
     * Read-only isolated-trial journal (D24). Only its phase and candidate token are projected;
     * the journal never contributes a path, pid or free-form detail to the console contract.
     */
    private val trialJournal: () -> DshTrialActiveRecord? = { null },
    /**
     * Hands an exported diagnostic bundle to the user (system share/save sheet). Returns false when
     * no handler exists; the export itself already succeeded at that point and the message says so.
     */
    private val shareDiagnostics: (File) -> Boolean = { false },
    /**
     * D14 MCP declaration write port. Null keeps the import action unadvertised and refused: a
     * console that cannot validate/publish the one declaration file must not offer an import.
     */
    private val mcpDeclarations: DshMcpDeclarationStore? = null
) : DshMaintenanceConsolePort {

    private val revision = AtomicLong(0L)
    private val backupsDir = File(dataRoot, "maintenance/backups")
    private val upgradeActions = DshMaintenanceUpgradeActions(backupsDir, clock, ::randomSuffix)

    /** Patch apply, theme import and managed-file repair, split out for the file budget. */
    private val customizationActions = DshMaintenanceCustomizationActions(managedRepair)
    @Volatile
    private var lastSelfCheck: com.muyuchat.core.dsh.contract.console.DshSelfCheckSummary? = null

    override suspend fun snapshot(): DshMaintenanceConsoleSnapshot =
        withContext(ioDispatcher) { snapshotNow() }

    private fun snapshotNow(): DshMaintenanceConsoleSnapshot {
        val owner = runCatching(maintenance).getOrNull()
            ?: return unavailableSnapshot()
        val hostStopped = DshMaintenanceQuiescencePolicy.consoleHostQuiescent(runtime.snapshot.value.phase)
        val repairAvailable = owner.repairer != null
        val upgradeState = runCatching { owner.upgrade.state() }.getOrNull()
        val candidate = runCatching { owner.findClosureCandidate() }.getOrNull()
        val trialRecord = runCatching { trialJournal() }.getOrNull()
        // One projection decides both what is rendered and which actions are offered: the console
        // contract has no second upgrade phase, so the adapter and the feature gate must agree on
        // exactly this coarse state (see DshMaintenanceUpgradeProjection).
        val upgrade = DshMaintenanceUpgradeProjection.project(
            upgradePhase = upgradeState?.phase,
            upgradeCandidateId = upgradeState?.candidateId,
            stagedCandidateId = (candidate as? DshClosureCandidateLookup.Available)?.candidate?.candidateId,
            trialPhase = trialRecord?.phase,
            trialCandidateId = trialRecord?.candidateId
        )
        val resumable = upgradeState != null &&
            upgradeState.phase in DshMaintenanceUpgradeProjection.RESUME_RAW_PHASES
        val operations = buildSet {
            add(DshMaintenanceOperationKind.LIST_BACKUPS)
            add(DshMaintenanceOperationKind.LIST_SESSIONS)
            add(DshMaintenanceOperationKind.RUN_SELF_CHECK)
            if (hostStopped) {
                add(DshMaintenanceOperationKind.CREATE_BACKUP)
                add(DshMaintenanceOperationKind.RESTORE_BACKUP)
                add(DshMaintenanceOperationKind.TRASH_SESSION)
                add(DshMaintenanceOperationKind.RESTORE_TRASHED_SESSION)
                add(DshMaintenanceOperationKind.PURGE_TRASHED_SESSION)
                add(DshMaintenanceOperationKind.ENTER_SAFE_MODE)
                add(DshMaintenanceOperationKind.EXIT_SAFE_MODE)
                // Patch/theme inputs are user-selected documents carried by the intent; the console
                // only offers them while the host is stopped, and the owner validates every byte.
                add(DshMaintenanceOperationKind.APPLY_CONSOLE_PATCH)
                add(DshMaintenanceOperationKind.IMPORT_THEME)
                // MCP declarations are read by the seeder at host start. Import is offered while the
                // host is stopped, and the result explicitly says a restart is required.
                if (mcpDeclarations != null) {
                    add(DshMaintenanceOperationKind.IMPORT_MCP_DECLARATION)
                }
                add(DshMaintenanceOperationKind.ROLLBACK_CONSOLE_PATCH)
                add(DshMaintenanceOperationKind.RESET_THEME)
                add(DshMaintenanceOperationKind.EXPORT_DIAGNOSTICS)
                if (repairAvailable) add(DshMaintenanceOperationKind.REPAIR_SESSION)
                // Precise repair of the build's declared managed files is advertised only when the
                // repair inventory and its verified source really exist in this build.
                if (managedRepair?.available == true) {
                    add(DshMaintenanceOperationKind.REPAIR_MANAGED_FILES)
                }
                // Advertised only with a verified staged candidate and no pending trial/apply
                // decision: running it otherwise would have to invent or overwrite a closure.
                if (candidate is DshClosureCandidateLookup.Available &&
                    upgrade.phase in DshMaintenanceUpgradeProjection.RUN_CONSOLE_PHASES
                ) {
                    add(DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE)
                }
                // Resume is advertised only while the owner still owns a pending trial/health step;
                // the same coarse phases gate it in the feature console policy.
                if (resumable && upgrade.phase in DshMaintenanceUpgradeProjection.RESUME_CONSOLE_PHASES) {
                    add(DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE)
                }
                // A recovery-required upgrade is otherwise a permanent boot block with no in-app
                // exit; this rescue never moves the selector, so it is the one safe action here.
                if (upgrade.phase == DshMaintenanceUpgradePhase.RECOVERY_REQUIRED) {
                    add(DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE)
                }
            }
        }

        val backups = listBackups()
        val sessions = runCatching { listSessions(owner) }.getOrDefault(emptyList())
        val trash = runCatching { owner.recycler.listTrash() }.getOrDefault(emptyList())
        val safeModeState = runCatching { owner.safeMode.currentState() }.getOrNull()
        val patchState = runCatching { owner.patches.state() }.getOrNull()
        val theme = runCatching { owner.themes.load() }.getOrNull()

        return DshMaintenanceConsoleSnapshot(
            revision = revision.incrementAndGet(),
            capturedAtMillis = clock(),
            availability = DshMaintenanceConsoleAvailability.AVAILABLE,
            availableOperations = operations,
            backups = backups,
            sessions = sessions + trashSessions(trash),
            trashCount = trash.size,
            safeModeActive = safeModeState?.phase == DshSafeModePhase.ACTIVE,
            patch = patchProjection(patchState),
            theme = DshMaintenanceThemeState(
                themeId = theme?.theme?.id?.takeIf(::isOpaqueId),
                revision = 0,
                customized = theme != null && !theme.usedDefault
            ),
            upgrade = upgrade,
            diagnosticsReady = DshMaintenanceOperationKind.EXPORT_DIAGNOSTICS in operations,
            lastSelfCheck = lastSelfCheck
        )
    }

    override suspend fun execute(intent: DshMaintenanceConsoleIntent): DshMaintenanceConsoleResult =
        withContext(ioDispatcher) { executeNow(intent) }

    private fun executeNow(intent: DshMaintenanceConsoleIntent): DshMaintenanceConsoleResult {
        if (intent.requiresConfirmation && (intent as? com.muyuchat.core.dsh.contract.console.DshConfirmableMaintenanceIntent)?.confirmed != true) {
            return DshMaintenanceConsoleResult.Refused(
                kind = intent.kind,
                reason = DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED,
                message = "该操作会修改用户数据，需要显式确认"
            )
        }
        if (intent is DshMaintenanceConsoleIntent.ListBackups ||
            intent is DshMaintenanceConsoleIntent.ListSessions
        ) {
            return DshMaintenanceConsoleResult.Applied(intent.kind)
        }
        // Self-check is read-only and meaningful while the host is running; it must not be gated
        // behind the stopped-host requirement used by mutations.
        if (intent is DshMaintenanceConsoleIntent.RunSelfCheck) {
            val selfCheckOwner = runCatching(maintenance).getOrNull()
                ?: return refused(intent, DshMaintenanceConsoleRefusal.UNAVAILABLE, "维护接口不可用")
            return runSelfCheck(selfCheckOwner, intent)
        }
        if (runtime.snapshot.value.phase != DshRuntimePhase.STOPPED) {
            return refused(intent, DshMaintenanceConsoleRefusal.BUSY, "请先停止 DSH 运行时再执行维护操作")
        }
        // The MCP declaration has its own owner (the declaration store), not the maintenance owner.
        // Handling it here keeps the import independent of a maintenance transaction and lets the
        // console work even when the maintenance owner is unavailable.
        if (intent is DshMaintenanceConsoleIntent.ImportMcpDeclaration) {
            return importMcpDeclaration(intent)
        }
        val owner = runCatching(maintenance).getOrNull()
            ?: return refused(intent, DshMaintenanceConsoleRefusal.UNAVAILABLE, "维护接口不可用")
        val requester = DshMaintenanceRequester(
            owner = "dsh-console",
            operationId = "console-" + clock(),
            reason = "user-requested console maintenance"
        )
        val quiescence = DshQuiescenceEvidence.hostStopped("DSH host confirmed stopped from the console")
        return try {
            when (intent) {
                is DshMaintenanceConsoleIntent.CreateBackup -> createBackup(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.RestoreBackup -> restoreBackup(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.TrashSession -> trashSession(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.RestoreTrashedSession -> restoreTrashed(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.PurgeTrashedSession -> purgeTrashed(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.EnterSafeMode -> safeMode(owner, intent, requester, quiescence, enter = true)
                is DshMaintenanceConsoleIntent.ExitSafeMode -> safeMode(owner, intent, requester, quiescence, enter = false)
                is DshMaintenanceConsoleIntent.RollbackConsolePatch -> rollbackPatch(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.ResetTheme -> resetTheme(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.ExportDiagnostics ->
                    exportDiagnostics(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.RepairSession ->
                    repairSession(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.RepairManagedFiles ->
                    customizationActions.repairManagedFiles(intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.ApplyConsolePatch ->
                    customizationActions.applyConsolePatch(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.ImportTheme ->
                    customizationActions.importTheme(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.RunClosureUpgrade ->
                    upgradeActions.run(owner, intent, requester, quiescence)
                is DshMaintenanceConsoleIntent.ResumeClosureUpgrade ->
                    upgradeActions.resume(owner, requester, quiescence)
                is DshMaintenanceConsoleIntent.AbandonClosureUpgrade ->
                    upgradeActions.abandon(owner, intent, requester, quiescence)

                else -> refused(intent, DshMaintenanceConsoleRefusal.UNAVAILABLE, "该维护操作尚未接入")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "maintenance console ${intent.kind.name} failed: ${t.javaClass.simpleName}")
            DshMaintenanceConsoleResult.Failed(
                kind = intent.kind,
                code = "INTERNAL_" + t.javaClass.simpleName.uppercase(),
                retryable = true
            )
        }
    }

    // ---------------------------------------------------------------- operations

    private fun createBackup(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.CreateBackup,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        if (!backupsDir.isDirectory && !backupsDir.mkdirs() && !backupsDir.isDirectory) {
            return DshMaintenanceConsoleResult.Failed(intent.kind, "STORAGE_IO", retryable = true)
        }
        val output = File(backupsDir, "backup-" + clock() + "-" + randomSuffix() + ".zip")
        return when (val result = owner.backups.createBackup(
            sources = owner.defaultBackupSources(),
            output = output,
            requester = requester,
            quiescence = quiescence
        )) {
            is DshBackupCreateResult.Created ->
                DshMaintenanceConsoleResult.Applied(intent.kind, "备份已创建（${backupsDir.listFiles()?.size ?: 0} 个）")

            is DshBackupCreateResult.Rejected -> resultFromFailure(intent.kind, result.failure)
        }
    }

    private fun runSelfCheck(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RunSelfCheck
    ): DshMaintenanceConsoleResult {
        val outcome = runCatching { owner.selfCheck() }.getOrNull()
            ?: return refused(intent, DshMaintenanceConsoleRefusal.UNAVAILABLE, "本构建未接入自检探针")
        return when (outcome) {
            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.Healthy -> {
                lastSelfCheck = com.muyuchat.core.dsh.contract.console.DshSelfCheckSummary(
                    degree = com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.HEALTHY,
                    checkedCount = outcome.report.checks.size,
                    issueCount = 0,
                    capturedAtMillis = clock(),
                    headline = "自检通过"
                )
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    "自检通过（" + outcome.report.checks.size + " 项）"
                )
            }

            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.NeedsRepair -> {
                lastSelfCheck = com.muyuchat.core.dsh.contract.console.DshSelfCheckSummary(
                    degree = com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.NEEDS_REPAIR,
                    checkedCount = outcome.report.checks.size,
                    issueCount = outcome.mismatched.size.coerceAtMost(outcome.report.checks.size),
                    capturedAtMillis = clock(),
                    headline = "受管文件需要修复"
                )
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    "自检发现 " + outcome.mismatched.size + " 个受管文件需修复"
                )
            }

            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.RequiresAttention -> {
                val headline = outcome.reasons.firstOrNull() ?: "未通过"
                lastSelfCheck = com.muyuchat.core.dsh.contract.console.DshSelfCheckSummary(
                    degree = com.muyuchat.core.dsh.contract.console.DshSelfCheckDegree.ATTENTION,
                    checkedCount = outcome.report.checks.size,
                    issueCount = outcome.reasons.size.coerceAtMost(outcome.report.checks.size),
                    capturedAtMillis = clock(),
                    headline = headline.take(100)
                )
                val suffix = if (outcome.reasons.size > 1) "（共 ${outcome.reasons.size} 项）" else ""
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    ("自检发现问题：" + headline + suffix).take(180)
                )
            }
        }
    }

    /**
     * Precise session repair (D21). The plan is read-only; only proven bad attachment references
     * are degraded and a structurally torn final frame is truncated exactly like the kernel heal.
     * The engine keeps the original in quarantine and the post-commit probe re-reads every header.
     */
    private fun repairSession(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RepairSession,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val repairer = owner.repairer
            ?: return refused(intent, DshMaintenanceConsoleRefusal.UNAVAILABLE, "本构建未接入官方会话解码器，无法安全修复")
        val session = owner.sessions.find(intent.sessionId)
            ?: return refused(intent, DshMaintenanceConsoleRefusal.NOT_FOUND, "会话不存在")
        val plan = runCatching { repairer.plan(session) }.getOrElse { failure ->
            return DshMaintenanceConsoleResult.Failed(
                intent.kind,
                "SESSION_PLAN_" + failure.javaClass.simpleName.uppercase(),
                retryable = true
            )
        }
        plan.refusal?.let { return resultFromFailure(intent.kind, it) }
        if (!plan.needsRepair) return DshMaintenanceConsoleResult.Applied(intent.kind, "未发现需要修复的内容")
        return when (val result = repairer.apply(session, plan, requester, quiescence)) {
            is DshSessionRepairResult.Applied -> {
                val repaired = if (plan.actions.isEmpty()) "0 处附件引用" else "${plan.actions.size} 处附件引用"
                val truncation = if (plan.tornTailTruncated) "，并截断不完整尾帧" else ""
                DshMaintenanceConsoleResult.Applied(intent.kind, ("已修复 " + repaired + truncation).take(180))
            }

            is DshSessionRepairResult.NoAction -> DshMaintenanceConsoleResult.Applied(intent.kind, result.reason)
            is DshSessionRepairResult.Rejected -> resultFromFailure(intent.kind, result.failure)
            is DshSessionRepairResult.RolledBack ->
                DshMaintenanceConsoleResult.Failed(intent.kind, result.failure.code.name, retryable = true, message = "修复失败，已还原原始会话")

            is DshSessionRepairResult.RecoveryRequired ->
                DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    "RECOVERY_REQUIRED",
                    retryable = false,
                    message = "修复结果需要人工处理，原始数据已保留"
                )

            is DshSessionRepairResult.Planned -> DshMaintenanceConsoleResult.Applied(intent.kind, "修复计划已生成")
        }
    }


    private fun exportDiagnostics(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.ExportDiagnostics,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val directory = File(dataRoot, "maintenance/diagnostics")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return DshMaintenanceConsoleResult.Failed(intent.kind, "STORAGE_IO", retryable = true)
        }
        val output = File(directory, "diagnostics-" + clock() + ".json")
        val snapshot = runtime.snapshot.value
        val selfCheckOutcome = runCatching { owner.selfCheck() }.getOrNull()
        val probes = when (selfCheckOutcome) {
            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.Healthy ->
                selfCheckOutcome.report.checks
            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.NeedsRepair ->
                selfCheckOutcome.report.checks
            is com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome.RequiresAttention ->
                selfCheckOutcome.report.checks
            null -> emptyList()
        }
        val input = com.muyuchat.core.dsh.maintenance.DshDiagnosticInput(
            appVersion = appVersion,
            runtimePhase = snapshot.phase.name,
            hostGeneration = snapshot.epoch,
            selfCheck = probes,
            failures = snapshot.failure?.let {
                listOf(
                    DshMaintenanceFailure(
                        code = com.muyuchat.core.dsh.maintenance.DshMaintenanceError.INTERNAL,
                        message = it.code.name,
                        retryable = it.retryable
                    )
                )
            } ?: emptyList(),
            sessionCount = runCatching { owner.sessions.list().size }.getOrDefault(0),
            recycleBinCount = runCatching { owner.recycler.listTrash().size }.getOrDefault(0)
        )
        return when (val result = owner.diagnostics.export(input, output, requester, quiescence)) {
            is com.muyuchat.core.dsh.maintenance.DshDiagnosticsResult.Exported -> {
                // The bundle is already redacted by the exporter (no secrets, no absolute paths).
                // Sharing hands the user a read-only content URI for that one file; a missing
                // handler is reported instead of pretending the share sheet opened.
                val shared = runCatching { shareDiagnostics(result.file) }.getOrDefault(false)
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    if (shared) {
                        "诊断包已导出并打开系统分享面板（" + result.bytes + " 字节）"
                    } else {
                        "诊断包已导出（" + result.bytes + " 字节），分享面板不可用"
                    }
                )
            }

            is com.muyuchat.core.dsh.maintenance.DshDiagnosticsResult.Rejected ->
                resultFromFailure(intent.kind, result.failure)
        }
    }

    private fun restoreBackup(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RestoreBackup,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val archive = File(backupsDir, intent.backupId + ".zip")
        if (!archive.isFile) return refused(intent, DshMaintenanceConsoleRefusal.NOT_FOUND, "备份不存在")
        val result = owner.backups.restore(
            DshRestoreRequest(
                archive = archive,
                homeTarget = File(dataRoot, "home"),
                workspaceTarget = File(dataRoot, "workspace"),
                targetRoot = dataRoot,
                requester = requester,
                quiescence = quiescence,
                overwriteExisting = false
            )
        )
        return when (result) {
            is DshBackupRestoreResult.Restored ->
                DshMaintenanceConsoleResult.Applied(intent.kind, "已恢复 ${result.restoredEntries} 个条目")

            is DshBackupRestoreResult.Rejected -> resultFromFailure(intent.kind, result.failure)
            is DshBackupRestoreResult.RolledBack -> resultFromFailure(intent.kind, result.failure)
            is DshBackupRestoreResult.RecoveryRequired -> resultFromFailure(intent.kind, result.failure)
        }
    }

    private fun trashSession(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.TrashSession,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val session = owner.sessions.find(intent.sessionId)
            ?: return refused(intent, DshMaintenanceConsoleRefusal.NOT_FOUND, "会话不存在")
        return recycleResult(intent.kind, owner.recycler.trash(session, running = false, requester = requester, quiescence = quiescence))
    }

    private fun restoreTrashed(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RestoreTrashedSession,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val entry = findTrash(owner, intent.sessionId)
            ?: return refused(intent, DshMaintenanceConsoleRefusal.NOT_FOUND, "回收站中不存在该会话")
        return recycleResult(intent.kind, owner.recycler.restore(entry, requester = requester, quiescence = quiescence))
    }

    private fun purgeTrashed(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.PurgeTrashedSession,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val entry = findTrash(owner, intent.sessionId)
            ?: return refused(intent, DshMaintenanceConsoleRefusal.NOT_FOUND, "回收站中不存在该会话")
        return recycleResult(
            intent.kind,
            owner.recycler.purge(entry, permanentConfirmed = intent.confirmed, requester = requester, quiescence = quiescence)
        )
    }

    private fun safeMode(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence,
        enter: Boolean
    ): DshMaintenanceConsoleResult {
        val result = if (enter) owner.safeMode.enter(requester, quiescence) else owner.safeMode.exit(requester, quiescence)
        return when (result) {
            is DshSafeModeResult.Active -> DshMaintenanceConsoleResult.Applied(intent.kind, "安全模式已启用")
            is DshSafeModeResult.Inactive -> DshMaintenanceConsoleResult.Applied(intent.kind, "安全模式已退出")
            is DshSafeModeResult.Rejected -> resultFromFailure(intent.kind, result.failure)
        }
    }

    private fun rollbackPatch(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.RollbackConsolePatch,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult = when (val result = owner.patches.rollback(requester, quiescence)) {
        is DshPatchResult.Applied -> DshMaintenanceConsoleResult.Applied(intent.kind, "控制台补丁已回滚")
        is DshPatchResult.Cleared -> DshMaintenanceConsoleResult.Applied(intent.kind, "控制台补丁已清除")
        is DshPatchResult.RolledBack -> DshMaintenanceConsoleResult.Applied(intent.kind, "控制台补丁已回滚")
        is DshPatchResult.Conflict -> DshMaintenanceConsoleResult.Refused(
            intent.kind,
            DshMaintenanceConsoleRefusal.PATCH_CONFLICT,
            "补丁存在冲突，未回滚"
        )

        is DshPatchResult.Rejected -> resultFromFailure(intent.kind, result.failure)
    }

    private fun resetTheme(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.ResetTheme,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult = when (val result = owner.themes.resetToDefault(requester, quiescence)) {
        is DshThemeSaveResult.Saved -> DshMaintenanceConsoleResult.Applied(intent.kind, "控制台主题已恢复默认")
        is DshThemeSaveResult.Rejected -> resultFromFailure(intent.kind, result.failure)
    }

    // ---------------------------------------------------------------- projections

    private fun listBackups(): List<DshMaintenanceBackup> =
        (backupsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".zip") }
            .sortedByDescending { it.lastModified() }
            .take(20)
            .mapNotNull { file ->
                val id = file.name.removeSuffix(".zip")
                if (!isOpaqueId(id)) return@mapNotNull null
                DshMaintenanceBackup(
                    backupId = id,
                    createdAtMillis = file.lastModified().coerceAtLeast(0L),
                    label = null,
                    sizeBytes = file.length(),
                    entryCount = backupEntryCount(file),
                    verified = false
                )
            }

    /**
     * Reads the entry count from the backup's own manifest instead of guessing. The archive is
     * small (metadata + settings/session files) and listBackups is a bounded, local projection;
     * a malformed archive degrades to 0 rather than failing the whole console.
     */
    private fun backupEntryCount(file: File): Int = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("dsh-backup-manifest.json") ?: return@use 0
            val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            org.json.JSONObject(text).optJSONArray("entries")?.length() ?: 0
        }
    }.getOrDefault(0)

    private fun listSessions(owner: DshMaintenanceOwner): List<DshMaintenanceSession> {
        val refs = owner.sessions.list()
            .sortedByDescending { it.modifiedAtMillis }
            .take(MAX_SESSION_ROWS)
        val stats = sessionStatsFor(owner, refs)
        return refs.mapNotNull { ref ->
            if (!isOpaqueId(ref.sessionId)) return@mapNotNull null
            val stat = stats[ref.sessionId]
            val count = stat?.messageCount
            DshMaintenanceSession(
                sessionId = ref.sessionId,
                updatedAtMillis = ref.modifiedAtMillis.coerceAtLeast(0L),
                messageCount = (count ?: 0).coerceAtLeast(0),
                messageCountKnown = count != null,
                trashed = false,
                label = null
            )
        }
    }

    /**
     * Batch counts through the official reader with a bounded cache keyed by the file identity.
     * A file the codec cannot count stays unknown; it is never reported as an empty session.
     */
    private fun sessionStatsFor(
        owner: DshMaintenanceOwner,
        refs: List<com.muyuchat.core.dsh.maintenance.DshSessionRef>
    ): Map<String, DshSessionStats> {
        if (refs.isEmpty()) return emptyMap()
        val result = HashMap<String, DshSessionStats>()
        val misses = ArrayList<com.muyuchat.core.dsh.maintenance.DshSessionRef>()
        synchronized(statsCache) {
            for (ref in refs) {
                val cached = statsCache[ref.file.absolutePath]
                if (cached != null && cached.length == ref.bytes && cached.modifiedAt == ref.modifiedAtMillis) {
                    result[ref.sessionId] = cached.stats
                } else {
                    misses.add(ref)
                }
            }
        }
        if (misses.isEmpty()) return result
        val statsList = runCatching { owner.sessionStats(misses.map { it.file }) }.getOrDefault(emptyList())
        misses.forEachIndexed { index, ref ->
            val stat = statsList.getOrNull(index) ?: return@forEachIndexed
            result[ref.sessionId] = stat
            synchronized(statsCache) {
                statsCache[ref.file.absolutePath] = StatsCacheEntry(ref.bytes, ref.modifiedAtMillis, stat)
            }
        }
        return result
    }

    private data class StatsCacheEntry(val length: Long, val modifiedAt: Long, val stats: DshSessionStats)

    private val statsCache = object : LinkedHashMap<String, StatsCacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StatsCacheEntry>): Boolean =
            size > MAX_STATS_CACHE_ENTRIES
    }

    private fun trashSessions(entries: List<DshTrashEntry>): List<DshMaintenanceSession> =
        entries.take(MAX_SESSION_ROWS).mapNotNull { entry ->
            val id = entry.entryName.takeIf(::isOpaqueId) ?: entry.slug.takeIf(::isOpaqueId) ?: return@mapNotNull null
            DshMaintenanceSession(
                sessionId = id,
                updatedAtMillis = entry.removedAtMillis.coerceAtLeast(0L),
                messageCount = 0,
                messageCountKnown = false,
                trashed = true,
                label = null
            )
        }

    private fun findTrash(owner: DshMaintenanceOwner, sessionId: String): DshTrashEntry? =
        owner.recycler.listTrash().firstOrNull { it.slug == sessionId || it.entryName == sessionId }

    private fun patchProjection(state: DshConsolePatchState?): DshMaintenancePatchState {
        if (state == null) return DshMaintenancePatchState()
        val userPresent = state.userDiffHash.isNotEmpty()
        return DshMaintenancePatchState(
            activeLayer = when {
                userPresent -> DshMaintenancePatchLayer.USER
                state.activePatchId != null -> DshMaintenancePatchLayer.MANAGED
                else -> null
            },
            userLayerPresent = userPresent,
            managedLayerPresent = state.managedPatchHash.isNotEmpty(),
            conflict = false
        )
    }

    private fun unavailableSnapshot(): DshMaintenanceConsoleSnapshot = DshMaintenanceConsoleSnapshot(
        revision = revision.incrementAndGet(),
        capturedAtMillis = clock(),
        availability = DshMaintenanceConsoleAvailability.UNAVAILABLE,
        availableOperations = emptySet()
    )

    /**
     * Imports one user-selected MCP declaration through the production reader.
     *
     * It never edits the file list entry-by-entry: the whole document is validated and published
     * atomically by [DshMcpDeclarationStore.importDocument]. Success is explicitly restart-pending —
     * the running host keeps its old rows until the next start re-seeds them.
     */
    private fun importMcpDeclaration(
        intent: DshMaintenanceConsoleIntent.ImportMcpDeclaration
    ): DshMaintenanceConsoleResult {
        val store = mcpDeclarations
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
                "当前构建没有接入 MCP 声明写入端口"
            )
        val document = intent.payload?.takeIf { it.isNotBlank() }
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
                "请先选择 MCP 声明文件再导入"
            )
        return when (val outcome = runCatching { store.importDocument(document) }
            .getOrElse { failure ->
                return DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    "MCP_DECLARATION_IO",
                    retryable = true,
                    message = "MCP 声明导入失败：" + failure.javaClass.simpleName
                )
            }) {
            is com.muyuchat.core.dsh.runtime.config.DshMcpWriteOutcome.Imported ->
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    "已导入 ${outcome.serverCount} 个 MCP 服务器；重启 DSH 运行时后生效"
                )

            is com.muyuchat.core.dsh.runtime.config.DshMcpWriteOutcome.Written ->
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    "MCP 声明已更新；重启 DSH 运行时后生效"
                )

            is com.muyuchat.core.dsh.runtime.config.DshMcpWriteOutcome.Refused -> {
                val first = outcome.diagnostics.firstOrNull()
                DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    "MCP_DECLARATION_INVALID",
                    retryable = false,
                    message = "MCP 声明未通过校验：" +
                        (first?.code?.name ?: "SCHEMA") +
                        (first?.field?.let { " ($it)" } ?: "")
                )
            }

            is com.muyuchat.core.dsh.runtime.config.DshMcpWriteOutcome.IoFailure ->
                DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    "MCP_DECLARATION_IO",
                    retryable = true,
                    message = "MCP 声明写入失败：${outcome.detail.take(120)}"
                )
        }
    }

    // ---------------------------------------------------------------- mapping helpers

    private fun recycleResult(
        kind: DshMaintenanceOperationKind,
        result: DshSessionRecycleResult
    ): DshMaintenanceConsoleResult = when (result) {
        is DshSessionRecycleResult.Trashed -> DshMaintenanceConsoleResult.Applied(kind, "会话已移入回收站")
        is DshSessionRecycleResult.Restored -> DshMaintenanceConsoleResult.Applied(kind, "会话已恢复")
        is DshSessionRecycleResult.AlreadyRestored -> DshMaintenanceConsoleResult.Applied(kind, "会话已恢复")
        is DshSessionRecycleResult.Purged -> DshMaintenanceConsoleResult.Applied(kind, "回收站会话已清除")
        is DshSessionRecycleResult.Rejected -> resultFromFailure(kind, result.failure)
        is DshSessionRecycleResult.RolledBack -> resultFromFailure(kind, result.failure)
        is DshSessionRecycleResult.RecoveryRequired -> resultFromFailure(kind, result.failure)
    }

    private fun randomSuffix(): String = java.lang.Long.toHexString(clock() xor System.nanoTime())

    private fun isOpaqueId(value: String): Boolean =
        value.length in 1..128 && value.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
        }

    private companion object {
        const val TAG = "McaDshMaintenanceConsole"
        const val MAX_SESSION_ROWS = 50
        const val MAX_STATS_CACHE_ENTRIES = 256
    }
}
