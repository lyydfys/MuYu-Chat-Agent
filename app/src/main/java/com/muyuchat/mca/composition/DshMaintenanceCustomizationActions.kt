package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.maintenance.DshConsolePatchState
import com.muyuchat.core.dsh.maintenance.DshMaintenanceError
import com.muyuchat.core.dsh.maintenance.DshMaintenanceFailure
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOutcome
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRequester
import com.muyuchat.core.dsh.maintenance.DshPatchResult
import com.muyuchat.core.dsh.maintenance.DshQuiescenceEvidence
import com.muyuchat.core.dsh.maintenance.DshSelfCheckOutcome
import com.muyuchat.core.dsh.maintenance.DshThemeDecodeResult
import com.muyuchat.core.dsh.maintenance.DshThemeSaveResult
import java.io.File

/**
 * Precise managed-file repair port (payload `criticalFiles`).
 *
 * It is a port rather than a direct call so the console adapter stays testable without a real
 * maintenance assembly: a build that cannot declare managed files, or cannot prove a repair source,
 * passes null and the console then refuses the action instead of improvising one.
 */
internal interface DshManagedRepairPort {
    /** True only when this build declares managed files and can run the bounded repair. */
    val available: Boolean

    /** Fresh read-only self-check; null when no probe port is wired. */
    fun selfCheck(): DshSelfCheckOutcome?

    /** Extracts and hash-verifies the repair source for exactly [relativePaths]. */
    fun prepareSource(relativePaths: List<String>): File?

    /** Bounded repair transaction through the maintenance engine. */
    fun repair(
        mismatched: List<String>,
        source: File,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceOutcome?
}

/**
 * Console customization actions that were previously refused as "not wired": console patch apply,
 * theme import and precise managed-file repair.
 *
 * Split from [DshMaintenanceConsoleAdapter] so both files stay inside the composition file budget.
 * Every method delegates to the existing owner (`patches`, `themes`, self-check/repair transaction)
 * and only adds the strict decoding of the user's document and the typed console result. Nothing is
 * faked: a missing payload is a refusal, a malformed document is a non-retryable failure, and a
 * repair whose verified source cannot be prepared changes no file.
 */
internal class DshMaintenanceCustomizationActions(
    private val managedRepair: DshManagedRepairPort?
) {

    /**
     * Applies a user-selected console patch document.
     *
     * The owner performs the exact per-file conflict check, staging, quarantine and health probe; a
     * document without a payload is refused instead of silently applying a bundled or previous one.
     */
    fun applyConsolePatch(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.ApplyConsolePatch,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val payload = intent.payload?.takeIf { it.isNotBlank() }
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
                "请先选择补丁文件再应用"
            )
        val current = runCatching { owner.patches.state() }.getOrDefault(DshConsolePatchState())
        val decoded = DshConsolePatchPayload.decode(payload, current)
        if (decoded is DshConsolePatchPayload.Decoded.Invalid) {
            return DshMaintenanceConsoleResult.Failed(
                intent.kind,
                "PATCH_INVALID",
                retryable = false,
                message = decoded.message
            )
        }
        val proposal = (decoded as DshConsolePatchPayload.Decoded.Valid).proposal
        return when (val result = owner.patches.apply(proposal, requester, quiescence)) {
            is DshPatchResult.Applied ->
                DshMaintenanceConsoleResult.Applied(intent.kind, "已应用 ${result.files} 个控制台文件")

            is DshPatchResult.Cleared ->
                DshMaintenanceConsoleResult.Applied(intent.kind, "控制台补丁已清除")

            is DshPatchResult.RolledBack ->
                DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    "PATCH_ROLLED_BACK",
                    retryable = true,
                    message = "补丁应用失败，已恢复原文件"
                )

            is DshPatchResult.Conflict -> DshMaintenanceConsoleResult.Refused(
                intent.kind,
                DshMaintenanceConsoleRefusal.PATCH_CONFLICT,
                "补丁与当前文件不一致，未写入任何内容"
            )

            is DshPatchResult.Rejected -> resultFromFailure(intent.kind, result.failure)
        }
    }

    /** Imports a user-selected theme document through the strict schema codec, then saves it. */
    fun importTheme(
        owner: DshMaintenanceOwner,
        intent: DshMaintenanceConsoleIntent.ImportTheme,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val payload = intent.payload?.takeIf { it.isNotBlank() }
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.NOT_ELIGIBLE,
                "请先选择主题文件再导入"
            )
        return when (val decoded = owner.themes.importTheme(payload)) {
            is DshThemeDecodeResult.Invalid ->
                DshMaintenanceConsoleResult.Failed(
                    intent.kind,
                    decoded.failure.code.name,
                    retryable = false,
                    message = decoded.failure.message.take(120)
                )

            is DshThemeDecodeResult.Valid ->
                when (val saved = owner.themes.save(decoded.theme, requester, quiescence)) {
                    is DshThemeSaveResult.Saved ->
                        DshMaintenanceConsoleResult.Applied(intent.kind, "控制台主题已导入")

                    is DshThemeSaveResult.Rejected -> resultFromFailure(intent.kind, saved.failure)
                }
        }
    }

    /**
     * Precise repair of the managed payload files.
     *
     * Three facts are required before a byte is replaced: a fresh self-check that reports only
     * managed-file mismatches (never a runtime probe failure), a verified repair source extracted
     * from this APK's payload archive, and the owner's own bounded transaction. Anything missing is
     * a typed refusal/failure — the console never "repairs" by copying whatever is on disk.
     */
    fun repairManagedFiles(
        intent: DshMaintenanceConsoleIntent.RepairManagedFiles,
        requester: DshMaintenanceRequester,
        quiescence: DshQuiescenceEvidence
    ): DshMaintenanceConsoleResult {
        val repair = managedRepair?.takeIf { it.available }
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.UNAVAILABLE,
                "本构建未声明可修复的受管文件"
            )
        val outcome = runCatching { repair.selfCheck() }.getOrNull()
            ?: return refused(
                intent,
                DshMaintenanceConsoleRefusal.UNAVAILABLE,
                "本构建未接入自检探针，无法确定需要修复的文件"
            )
        val mismatched = when (outcome) {
            is DshSelfCheckOutcome.Healthy ->
                return DshMaintenanceConsoleResult.Applied(intent.kind, "受管文件校验通过，无需修复")

            is DshSelfCheckOutcome.RequiresAttention -> return DshMaintenanceConsoleResult.Failed(
                intent.kind,
                "SELF_CHECK_ATTENTION",
                retryable = false,
                message = "运行时检查未通过，文件替换无法解决：" +
                    (outcome.reasons.firstOrNull() ?: "未提供原因").take(100)
            )

            is DshSelfCheckOutcome.NeedsRepair -> outcome.mismatched
        }
        if (mismatched.isEmpty()) {
            return DshMaintenanceConsoleResult.Applied(intent.kind, "受管文件校验通过，无需修复")
        }
        val source = repair.prepareSource(mismatched)
            ?: return DshMaintenanceConsoleResult.Failed(
                intent.kind,
                "REPAIR_SOURCE_UNAVAILABLE",
                retryable = true,
                message = "无法从安装包准备可校验的修复源，未改动任何文件"
            )
        val transaction = try {
            repair.repair(mismatched, source, requester, quiescence)
        } finally {
            runCatching { source.deleteRecursively() }
        } ?: return refused(
            intent,
            DshMaintenanceConsoleRefusal.UNAVAILABLE,
            "本构建未接入受管文件修复"
        )
        return when (transaction) {
            is DshMaintenanceOutcome.Committed ->
                DshMaintenanceConsoleResult.Applied(
                    intent.kind,
                    // Each installed file was hash-verified against the build manifest by the
                    // maintenance engine's own step contract before the transaction committed.
                    "已修复 ${mismatched.size} 个受管文件（每步均按构建清单校验）"
                )

            is DshMaintenanceOutcome.RolledBack -> DshMaintenanceConsoleResult.Failed(
                intent.kind,
                transaction.failure.code.name,
                retryable = true,
                message = "修复失败，已还原原始文件"
            )

            is DshMaintenanceOutcome.Rejected ->
                resultFromFailure(intent.kind, transaction.failure)

            is DshMaintenanceOutcome.RecoveryRequired -> DshMaintenanceConsoleResult.Failed(
                intent.kind,
                "RECOVERY_REQUIRED",
                retryable = false,
                message = "修复结果需要人工处理，原始文件已保留"
            )
        }
    }
}

/** Console refusal with fixed copy; shared by the adapter and the customization actions. */
internal fun refused(
    intent: DshMaintenanceConsoleIntent,
    reason: DshMaintenanceConsoleRefusal,
    message: String
): DshMaintenanceConsoleResult =
    DshMaintenanceConsoleResult.Refused(intent.kind, reason, message)

/**
 * Maps one owner failure to a refusal or a failure. Only well-known admission/space/state conflicts
 * become a refusal; everything else is a failure with the owner's typed code, so a broken operation
 * is never presented as a normal precondition.
 */
internal fun resultFromFailure(
    kind: DshMaintenanceOperationKind,
    failure: DshMaintenanceFailure
): DshMaintenanceConsoleResult {
    val refusal = when (failure.code) {
        DshMaintenanceError.ADMISSION_BUSY,
        DshMaintenanceError.ADMISSION_REJECTED,
        DshMaintenanceError.HOST_STILL_RUNNING -> DshMaintenanceConsoleRefusal.BUSY
        DshMaintenanceError.SPACE_INSUFFICIENT -> DshMaintenanceConsoleRefusal.STORAGE_INSUFFICIENT
        DshMaintenanceError.SESSION_RUNNING -> DshMaintenanceConsoleRefusal.SESSION_RUNNING
        DshMaintenanceError.SESSION_NOT_FOUND, DshMaintenanceError.STAGING_MISSING ->
            DshMaintenanceConsoleRefusal.NOT_FOUND
        DshMaintenanceError.PATCH_CONFLICT, DshMaintenanceError.PATCH_FORBIDDEN ->
            DshMaintenanceConsoleRefusal.PATCH_CONFLICT
        DshMaintenanceError.UPGRADE_INCOMPATIBLE -> DshMaintenanceConsoleRefusal.UPGRADE_IN_PROGRESS
        DshMaintenanceError.PURGE_CONFIRMATION_REQUIRED -> DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED
        else -> null
    }
    return if (refusal != null) {
        DshMaintenanceConsoleResult.Refused(kind, refusal, failure.message)
    } else {
        DshMaintenanceConsoleResult.Failed(
            kind = kind,
            code = failure.code.name,
            retryable = failure.code != DshMaintenanceError.ARCHIVE_MALICIOUS,
            message = failure.message
        )
    }
}
