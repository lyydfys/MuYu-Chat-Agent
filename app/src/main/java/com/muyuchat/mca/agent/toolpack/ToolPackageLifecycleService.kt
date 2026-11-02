package com.muyuchat.mca.agent.toolpack

import com.muyuchat.core.agent.contract.AgentToolExecutor
import java.io.File
import kotlinx.coroutines.CancellationException

/** One user-visible lifecycle operation; used to pair every outcome with what was attempted. */
enum class ToolPackageLifecycleAction {
    INSTALL,
    UPDATE,
    ENABLE,
    DISABLE,
    REVOKE,
    CLEAR_REVOCATION,
    ROLLBACK,
    UNINSTALL,
    COLLECT_GARBAGE
}

/** Extra facts an operation can report without widening every outcome type. */
data class ToolPackageLifecycleDetail(
    /** The content bytes were already present (content-addressed reuse). */
    val reusedContent: Boolean = false,
    /** The call replayed an already-applied state (install of identical bytes / enable of enabled). */
    val idempotentReplay: Boolean = false,
    /** Rollback target after an update, when one exists. */
    val previousContentHash: String? = null,
    /** Content directories removed by uninstall/GC in this call. */
    val purgedContentHashes: List<String> = emptyList(),
    /** Content still pinned by a record or by the caller's protected set. */
    val retainedContentHashes: List<String> = emptyList()
)

/** Immutable view of one installed package for the settings surface. */
data class ToolPackageLifecycleStatus(
    val packageId: String,
    val packageVersion: String,
    val contentHash: String,
    val enabled: Boolean,
    val revoked: Boolean,
    val previousContentHash: String?,
    val revision: Long,
    val displayName: String?,
    val toolCount: Int,
    val toolKeys: List<String>,
    /** False when the stored bytes no longer hash to [contentHash]; such a package is never active. */
    val contentVerified: Boolean,
    /** Typed verification problem code when [contentVerified] is false. */
    val problemCode: String?
)

sealed interface ToolPackageLifecycleOutcome {
    data class Applied(
        val action: ToolPackageLifecycleAction,
        /** Null for [ToolPackageLifecycleAction.UNINSTALL] and [ToolPackageLifecycleAction.COLLECT_GARBAGE]. */
        val status: ToolPackageLifecycleStatus?,
        val detail: ToolPackageLifecycleDetail = ToolPackageLifecycleDetail()
    ) : ToolPackageLifecycleOutcome

    data class Rejected(
        val action: ToolPackageLifecycleAction,
        val code: String,
        val safeMessage: String
    ) : ToolPackageLifecycleOutcome
}

/**
 * User-facing lifecycle service for executable tool packages (N09b/R4).
 *
 * Responsibilities are split on purpose:
 * - [ToolPackageInstallStore] is the single writer of the durable active pointer and of immutable
 *   content; it never executes a script;
 * - this service owns package *policy*: manifest/script validation, content re-verification before
 *   activation, refusal to activate write-capable packages, update/rollback ordering, revocation and
 *   staged uninstall;
 * - [enabledToolPackageExecutors] owns the run-catalog projection.
 *
 * Failure semantics:
 * - install/update only ever commit immutable content + a **disabled** state, then verify; an
 *   update whose verification or activation fails is rolled back so the previously active version
 *   keeps contributing tools;
 * - uninstall is staged (`disable` then `remove`), so a failure can leave an inert installed
 *   package but never a half-removed active one; its content is then eligible for reference GC;
 * - revocation is durable and blocks enable until [clearRevocation]; it never deletes content.
 *
 * Lifecycle operations are safe to call from the UI layer: they switch to the IO dispatcher inside
 * the store, never touch Android objects and never spawn a process. The third-party import preview
 * methods are pure CPU and do not switch dispatchers; call the archive overload from an IO/Default
 * context.
 *
 * [onStateChanged] is invoked once after every **applied** transition (never after a rejection) so
 * the composition root can refresh its `enabledToolPackageExecutors` snapshot for the next run
 * without the UI remembering to do it. It must be quick and non-blocking; a throwing listener is
 * ignored and never turns a successful operation into a failure.
 */
class ToolPackageLifecycleService(
    private val store: ToolPackageInstallStore,
    private val runtime: ToolPackageScriptRuntime,
    private val approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onStateChanged: (() -> Unit)? = null,
    private val writeCapabilityPolicy: ToolPackageWriteCapabilityPolicy = ToolPackageWriteCapabilityPolicy.REFUSE
) {
    /** Stateless third-party preview adapter; holds no store, runtime or resource. */
    private val thirdpartyImportAdapter = ThirdPartyToolPackageImportAdapter()

    /** Stateless third-party translator + archive reader (no store/runtime reference). */
    private val thirdpartyTranslationService = ThirdPartyToolPackageTranslationService()

    suspend fun install(
        manifestBytes: ByteArray,
        scripts: Map<String, String>
    ): ToolPackageLifecycleOutcome = installInternal(ToolPackageLifecycleAction.INSTALL, manifestBytes, scripts)

    /** Installs a ZIP archive through [ToolPackageArchive] (strict v2 entry policy). */
    suspend fun installArchive(archiveBytes: ByteArray): ToolPackageLifecycleOutcome =
        installArchiveInternal(ToolPackageLifecycleAction.INSTALL, archiveBytes)

    /** Installs a new version and, when the previous version was active, switches to it. */
    suspend fun update(
        manifestBytes: ByteArray,
        scripts: Map<String, String>
    ): ToolPackageLifecycleOutcome = installInternal(ToolPackageLifecycleAction.UPDATE, manifestBytes, scripts)

    suspend fun updateArchive(archiveBytes: ByteArray): ToolPackageLifecycleOutcome =
        installArchiveInternal(ToolPackageLifecycleAction.UPDATE, archiveBytes)

    suspend fun enable(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.ENABLE) {
            val record = store.record(packageId) ?: return@withRejectionGuard notInstalled(ToolPackageLifecycleAction.ENABLE)
            val verified = when (val result = store.verify(record.contentHash)) {
                is ToolPackageVerifyResult.Valid -> result
                is ToolPackageVerifyResult.Rejected -> return@withRejectionGuard rejected(
                    ToolPackageLifecycleAction.ENABLE,
                    result.code,
                    result.safeMessage
                )
            }
            if (verified.bundle.manifest.declaresWriteCapability &&
                !writeCapabilityPolicy.allowsWriteCapability
            ) {
                return@withRejectionGuard rejected(
                    ToolPackageLifecycleAction.ENABLE,
                    ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
                    "Write-capable packages cannot be enabled before per-call approval is wired."
                )
            }
            applyOrReject(ToolPackageLifecycleAction.ENABLE, ToolPackageStateChange.Enable(packageId))
        }

    suspend fun disable(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.DISABLE) {
            applyOrReject(ToolPackageLifecycleAction.DISABLE, ToolPackageStateChange.Disable(packageId))
        }

    /** Explicit revocation: disables the package and blocks re-enable until [clearRevocation]. */
    suspend fun revoke(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.REVOKE) {
            applyOrReject(
                ToolPackageLifecycleAction.REVOKE,
                ToolPackageStateChange.Revoke(packageId, clockMillis())
            )
        }

    suspend fun clearRevocation(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.CLEAR_REVOCATION) {
            applyOrReject(
                ToolPackageLifecycleAction.CLEAR_REVOCATION,
                ToolPackageStateChange.ClearRevocation(packageId)
            )
        }

    /** Restores the previous version as active content and keeps the newer one as rollback target. */
    suspend fun rollback(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.ROLLBACK) {
            val record = store.record(packageId) ?: return@withRejectionGuard notInstalled(ToolPackageLifecycleAction.ROLLBACK)
            val target = record.previousContentHash ?: return@withRejectionGuard rejected(
                ToolPackageLifecycleAction.ROLLBACK,
                "TOOLPACK_NO_ROLLBACK_TARGET",
                "This package has no previous version to roll back to."
            )
            when (val verified = store.verify(target)) {
                is ToolPackageVerifyResult.Rejected -> return@withRejectionGuard rejected(
                    ToolPackageLifecycleAction.ROLLBACK,
                    verified.code,
                    verified.safeMessage
                )
                is ToolPackageVerifyResult.Valid -> Unit
            }
            val applied = applyOrReject(
                ToolPackageLifecycleAction.ROLLBACK,
                ToolPackageStateChange.Rollback(packageId)
            )
            when (applied) {
                is ToolPackageLifecycleOutcome.Applied -> applied.copy(
                    detail = applied.detail.copy(previousContentHash = record.contentHash)
                )
                is ToolPackageLifecycleOutcome.Rejected -> applied
            }
        }

    /**
     * Staged uninstall: deactivate first, then remove the durable record, then run reference GC so
     * the content of this package (and any orphan) is purged. A failure before removal leaves an
     * inert installed package; nothing is ever half-removed while active.
     */
    suspend fun uninstall(packageId: String): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.UNINSTALL) {
            val record = store.record(packageId) ?: return@withRejectionGuard notInstalled(ToolPackageLifecycleAction.UNINSTALL)
            if (record.enabled) {
                when (val disabled = store.apply(ToolPackageStateChange.Disable(packageId))) {
                    is ToolPackageStateChangeResult.Rejected -> return@withRejectionGuard rejected(
                        ToolPackageLifecycleAction.UNINSTALL,
                        disabled.code,
                        disabled.safeMessage
                    )
                    is ToolPackageStateChangeResult.Applied -> Unit
                }
            }
            when (val removed = store.apply(ToolPackageStateChange.Remove(packageId))) {
                is ToolPackageStateChangeResult.Rejected -> return@withRejectionGuard rejected(
                    ToolPackageLifecycleAction.UNINSTALL,
                    removed.code,
                    "The package was deactivated but its record could not be removed; it stays installed."
                )
                is ToolPackageStateChangeResult.Applied -> Unit
            }
            val gc = store.collectGarbage()
            ToolPackageLifecycleOutcome.Applied(
                action = ToolPackageLifecycleAction.UNINSTALL,
                status = null,
                detail = ToolPackageLifecycleDetail(
                    purgedContentHashes = gc.deletedContentHashes,
                    retainedContentHashes = gc.keptContentHashes
                )
            )
        }

    /** Reference GC with no extra protection; enabled/previous content of other packages is kept. */
    suspend fun collectGarbage(): ToolPackageLifecycleOutcome =
        withRejectionGuard(ToolPackageLifecycleAction.COLLECT_GARBAGE) {
            val gc = store.collectGarbage()
            ToolPackageLifecycleOutcome.Applied(
                action = ToolPackageLifecycleAction.COLLECT_GARBAGE,
                status = null,
                detail = ToolPackageLifecycleDetail(
                    purgedContentHashes = gc.deletedContentHashes,
                    retainedContentHashes = gc.keptContentHashes
                )
            )
        }

    suspend fun status(packageId: String): ToolPackageLifecycleStatus? {
        val record = store.record(packageId) ?: return null
        return statusOf(record)
    }

    suspend fun list(): List<ToolPackageLifecycleStatus> =
        store.scan().records.map { statusOf(it) }

    /**
     * Read-only third-party ToolPkg import preview (design 5.5, PKG-01/PKG-03).
     *
     * Pure CPU: it does not read the store, start the runtime, write a file or install/enable
     * anything. The returned descriptor/report is an additive preview surface; a package whose
     * declared context needs a host capability MCA does not have keeps
     * [ThirdPartyCompatibilityReport.runnable] false. This is the production caller seam for an admin
     * import page; no UI is wired in this slice.
     */
    fun previewThirdPartyImport(
        manifestText: String,
        listing: ThirdPartyPackageListing = ThirdPartyPackageListing(),
        declaredContexts: List<ThirdPartyDeclaredContext> = emptyList()
    ): ThirdPartyToolPackageImportResult =
        thirdpartyImportAdapter.preview(manifestText, listing, declaredContexts)

    /** ZIP overload backed by the existing hardened ZIP reader; it never extracts the archive. */
    fun previewThirdPartyArchive(archiveBytes: ByteArray): ThirdPartyToolPackageImportResult =
        thirdpartyImportAdapter.previewArchive(archiveBytes)

    /**
     * Preview **with translation**: returns the translated MCA executable descriptor and the
     * per-symbol compatibility report, or a typed translation refusal. Pure CPU; does not install.
     *
     * This is the backend seam for an admin import page. A caller must only offer installation when
     * the result is [ThirdPartyTranslationResult.Translated]; every refused context keeps a named
     * symbol/field path. The admin UI wiring is the remaining PKG-23 UI slice.
     */
    fun previewThirdPartyExecutable(
        manifestText: String,
        scripts: Map<String, String>,
        listing: ThirdPartyPackageListing = ThirdPartyPackageListing(
            scripts.keys.map { ThirdPartyListedEntry(it) }
        ),
        declaredContexts: List<ThirdPartyDeclaredContext> = emptyList()
    ): ThirdPartyTranslationResult = thirdpartyTranslationService.translate(
        manifestText = manifestText,
        scripts = scripts,
        listing = listing,
        declaredContexts = declaredContexts
    )

    /** Archive overload of [previewThirdPartyExecutable]; nothing is extracted or installed. */
    fun translateThirdPartyArchive(archiveBytes: ByteArray): ThirdPartyTranslationResult =
        thirdpartyTranslationService.translateArchive(archiveBytes)

    /**
     * The single import entry point: it reads the archive's own format declaration and routes to the
     * parser that owns that format. Pure CPU and read-only — no install, enable, extraction, execution
     * or store write happens here, so a caller may preview any archive before choosing a transition.
     */
    fun previewImport(archiveBytes: ByteArray): ToolPackageImportPreview {
        return when (val classified = ToolPackageFormatClassifier.classify(archiveBytes)) {
            is ToolPackageFormatClassifier.Result.Rejected ->
                ToolPackageImportPreview.Rejected(classified.error.code, classified.error.safeMessage)
            is ToolPackageFormatClassifier.Result.Classified -> when (classified.format) {
                ToolPackageFormatClassifier.Format.INERT_V1 ->
                    ToolPackageImportPreview.LegacyInert(ToolPackageImportPreview.LEGACY_NOTE)
                ToolPackageFormatClassifier.Format.EXECUTABLE_V2 -> executablePreview(archiveBytes)
                ToolPackageFormatClassifier.Format.THIRD_PARTY ->
                    ToolPackageImportPreview.ThirdParty(translateThirdPartyArchive(archiveBytes))
            }
        }
    }

    private fun executablePreview(archiveBytes: ByteArray): ToolPackageImportPreview = try {
        val extracted = ToolPackageArchive.read(archiveBytes)
        ToolPackageImportPreview.Executable(
            manifest = ExecutableToolPackageManifestParser().parse(extracted.manifestBytes),
            manifestBytes = extracted.manifestBytes,
            scripts = extracted.scripts
        )
    } catch (refused: ToolPackageException) {
        ToolPackageImportPreview.Rejected(refused.error.code, refused.error.safeMessage)
    }

    /**
     * Typed install path for a translated third-party package: translate first, install only when the
     * report contains no unsupported requirement. The package is installed **disabled** exactly
     * like a hand-authored MCA package; enabling is a separate explicit transition and still
     * follows [ToolPackageWriteCapabilityPolicy] and the per-call approval port.
     */
    suspend fun installThirdPartyArchive(archiveBytes: ByteArray): ThirdPartyTranslationInstallResult {
        val translated = thirdpartyTranslationService.translateArchive(archiveBytes)
        if (translated is ThirdPartyTranslationResult.Rejected) {
            return ThirdPartyTranslationInstallResult.TranslationRejected(translated)
        }
        val packageBytes = (translated as ThirdPartyTranslationResult.Translated).translated
        return when (
            val outcome = install(
                packageBytes.manifestJson.toByteArray(Charsets.UTF_8),
                packageBytes.scripts
            )
        ) {
            is ToolPackageLifecycleOutcome.Rejected -> ThirdPartyTranslationInstallResult.InstallRejected(
                translation = packageBytes,
                code = outcome.code,
                safeMessage = outcome.safeMessage
            )
            is ToolPackageLifecycleOutcome.Applied -> ThirdPartyTranslationInstallResult.Installed(
                translation = packageBytes,
                status = outcome.status
            )
        }
    }

    /** Executors of enabled + verified packages; the catalog data source for the run owner. */
    suspend fun enabledExecutors(): List<AgentToolExecutor> =
        enabledToolPackageExecutors(
            store = store,
            runtime = runtime,
            approval = approval,
            allowWriteCapability = writeCapabilityPolicy.allowsWriteCapability
        )

    private suspend fun installInternal(
        action: ToolPackageLifecycleAction,
        manifestBytes: ByteArray,
        scripts: Map<String, String>
    ): ToolPackageLifecycleOutcome = withRejectionGuard(action) {
        // Parse once up front so the previous record is known *before* the state pointer moves.
        val incoming = try {
            ExecutableToolPackageLoader().load(manifestBytes, scripts)
        } catch (failure: ToolPackageException) {
            return@withRejectionGuard rejected(action, failure.error.code.agentCode, failure.error.safeMessage)
        }
        val previous = store.record(incoming.packageId)
        if (action == ToolPackageLifecycleAction.INSTALL &&
            previous != null && previous.enabled && previous.contentHash != incoming.contentHash
        ) {
            // Replacing an active version is an explicit update, not an install: installing new
            // bytes would otherwise silently deactivate the running package.
            return@withRejectionGuard rejected(
                action,
                "TOOLPACK_UPDATE_REQUIRED",
                "An active package cannot be replaced by install; use update to switch versions."
            )
        }
        val installed = when (val result = store.install(manifestBytes, scripts)) {
            is ToolPackageInstallResult.Rejected -> return@withRejectionGuard rejected(
                action,
                result.code,
                result.safeMessage
            )
            is ToolPackageInstallResult.Installed -> result
        }
        val record = installed.record
        val verified = when (val result = store.verify(record.contentHash)) {
            is ToolPackageVerifyResult.Valid -> result
            is ToolPackageVerifyResult.Rejected -> {
                restorePrevious(action, previous)
                return@withRejectionGuard rejected(
                    action,
                    result.code,
                    "The installed package did not verify; the previous version was kept."
                )
            }
        }
        val detail = ToolPackageLifecycleDetail(
            reusedContent = installed.reusedContent,
            idempotentReplay = previous?.contentHash == record.contentHash,
            previousContentHash = record.previousContentHash
        )
        if (action == ToolPackageLifecycleAction.UPDATE &&
            previous != null && previous.enabled && previous.contentHash != record.contentHash
        ) {
            if (verified.bundle.manifest.declaresWriteCapability &&
                !writeCapabilityPolicy.allowsWriteCapability
            ) {
                restorePrevious(action, previous)
                return@withRejectionGuard rejected(
                    action,
                    ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
                    "Write-capable packages cannot be activated; the previous version was kept."
                )
            }
            return@withRejectionGuard when (
                val enabled = store.apply(ToolPackageStateChange.Enable(record.packageId))
            ) {
                is ToolPackageStateChangeResult.Rejected -> {
                    restorePrevious(action, previous)
                    rejected(action, enabled.code, "The new version could not be activated; the previous one was kept.")
                }
                is ToolPackageStateChangeResult.Applied -> ToolPackageLifecycleOutcome.Applied(
                    action = action,
                    status = statusOf(requireNotNull(enabled.record)),
                    detail = detail
                )
            }
        }
        ToolPackageLifecycleOutcome.Applied(action, statusOf(record), detail)
    }

    private suspend fun installArchiveInternal(
        action: ToolPackageLifecycleAction,
        archiveBytes: ByteArray
    ): ToolPackageLifecycleOutcome = withRejectionGuard(action) {
        val extracted = try {
            ToolPackageArchive.read(archiveBytes)
        } catch (failure: ToolPackageException) {
            return@withRejectionGuard rejected(action, failure.error.code.agentCode, failure.error.safeMessage)
        }
        installInternal(action, extracted.manifestBytes, extracted.scripts)
    }

    /**
     * Best-effort restoration of the previous version after a failed update: swap the pointer back,
     * then re-enable it because the previous version was active. A failure here leaves the package
     * inert and disabled rather than pretending the old version is running.
     */
    private suspend fun restorePrevious(
        action: ToolPackageLifecycleAction,
        previous: ToolPackageInstalledRecord?
    ) {
        if (previous == null || !previous.enabled) return
        val rolledBack = store.apply(ToolPackageStateChange.Rollback(previous.packageId))
        // Only re-activate after the pointer actually moved back; otherwise enabling would activate
        // the failed new version, which is exactly what this rollback exists to prevent.
        if (rolledBack is ToolPackageStateChangeResult.Applied) {
            store.apply(ToolPackageStateChange.Enable(previous.packageId))
        }
    }

    private suspend fun applyOrReject(
        action: ToolPackageLifecycleAction,
        change: ToolPackageStateChange
    ): ToolPackageLifecycleOutcome {
        val before = store.record(change.packageId)
        val result = store.apply(change)
        return when (result) {
            is ToolPackageStateChangeResult.Rejected -> rejected(action, result.code, result.safeMessage)
            is ToolPackageStateChangeResult.Applied -> {
                val record = result.record ?: return ToolPackageLifecycleOutcome.Applied(
                    action = action,
                    status = null
                )
                ToolPackageLifecycleOutcome.Applied(
                    action = action,
                    status = statusOf(record),
                    detail = ToolPackageLifecycleDetail(
                        idempotentReplay = replayOf(action, before),
                        previousContentHash = record.previousContentHash
                    )
                )
            }
        }
    }

    /** True when the requested transition was already the observed state before this call. */
    private fun replayOf(
        action: ToolPackageLifecycleAction,
        before: ToolPackageInstalledRecord?
    ): Boolean = when (action) {
        ToolPackageLifecycleAction.ENABLE -> before?.enabled == true
        ToolPackageLifecycleAction.DISABLE -> before != null && !before.enabled
        ToolPackageLifecycleAction.REVOKE -> before?.revoked == true
        ToolPackageLifecycleAction.CLEAR_REVOCATION -> before != null && !before.revoked
        else -> false
    }

    private suspend fun statusOf(record: ToolPackageInstalledRecord): ToolPackageLifecycleStatus =
        when (val verified = store.verify(record.contentHash)) {
            is ToolPackageVerifyResult.Valid -> ToolPackageLifecycleStatus(
                packageId = record.packageId,
                packageVersion = verified.bundle.packageVersion,
                contentHash = record.contentHash,
                enabled = record.enabled,
                revoked = record.revoked,
                previousContentHash = record.previousContentHash,
                revision = record.revision,
                displayName = verified.bundle.manifest.displayName,
                toolCount = verified.bundle.manifest.tools.size,
                toolKeys = verified.bundle.manifest.tools
                    .map { verified.bundle.runtimeToolKey(it.name) }
                    .sorted(),
                contentVerified = true,
                problemCode = null
            )
            is ToolPackageVerifyResult.Rejected -> ToolPackageLifecycleStatus(
                packageId = record.packageId,
                packageVersion = record.packageVersion,
                contentHash = record.contentHash,
                enabled = record.enabled,
                revoked = record.revoked,
                previousContentHash = record.previousContentHash,
                revision = record.revision,
                displayName = null,
                toolCount = 0,
                toolKeys = emptyList(),
                contentVerified = false,
                problemCode = verified.code
            )
        }

    private fun notInstalled(action: ToolPackageLifecycleAction): ToolPackageLifecycleOutcome.Rejected = rejected(
        action,
        "TOOLPACK_NOT_INSTALLED",
        "No installed executable package matches the requested id."
    )

    private fun rejected(
        action: ToolPackageLifecycleAction,
        code: String,
        safeMessage: String
    ): ToolPackageLifecycleOutcome.Rejected = ToolPackageLifecycleOutcome.Rejected(
        action = action,
        code = code,
        safeMessage = safeMessage
    )

    /**
     * Cancellation must propagate; no other throwable is allowed to escape the service untyped.
     * Every applied transition notifies [onStateChanged] exactly once (listener faults are ignored).
     */
    private suspend fun withRejectionGuard(
        action: ToolPackageLifecycleAction,
        block: suspend () -> ToolPackageLifecycleOutcome
    ): ToolPackageLifecycleOutcome {
        val outcome = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolPackageException) {
            rejected(action, failure.error.code.agentCode, failure.error.safeMessage)
        } catch (_: Exception) {
            rejected(
                action,
                "TOOLPACK_LIFECYCLE_FAILED",
                "The tool-package operation failed without a safe detail; no activation was changed."
            )
        }
        if (outcome is ToolPackageLifecycleOutcome.Applied) {
            try {
                onStateChanged?.invoke()
            } catch (_: Exception) {
                // A refresh hook must never fail the operation it observes.
            }
        }
        return outcome
    }

    companion object {
        /**
         * Production construction: an app-private store root, the Rhino engine and the caller-owned
         * host operation handler (workspace/memory/artifact bridge). Wiring is performed by the
         * composition root; this class never creates an Android or process owner.
         */
        fun create(
            rootDirectory: File,
            hostHandler: ToolPackageHostOperationHandler,
            approval: ToolPackageHostApprovalPort = ToolPackageHostApprovalPort.DENY_ALL,
            engine: ToolPackageScriptEngine = RhinoToolPackageScriptEngine(),
            limits: ToolPackageRuntimeLimits = ToolPackageRuntimeLimits(),
            onStateChanged: (() -> Unit)? = null
        ): ToolPackageLifecycleService = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(rootDirectory),
            runtime = ToolPackageScriptRuntime(engine, hostHandler, limits),
            approval = approval,
            onStateChanged = onStateChanged
        )
    }
}
