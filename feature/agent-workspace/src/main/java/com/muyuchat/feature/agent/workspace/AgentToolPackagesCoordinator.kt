package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local tool-package coordinator.
 *
 * It owns two blocks:
 * - the v1 read-only preview list ([refreshNow]/[previewNow]); it can validate one user-picked ZIP
 *   into the private preview store and nothing more;
 * - the executable (format v2) lifecycle list added by R6: list, ZIP install, enable, disable,
 *   rollback, revoke, clear revocation, uninstall and reference GC. Every destructive action goes
 *   through [requestExecutableAction] → confirmation → [confirmExecutableAction], so a single tap
 *   can never uninstall or revoke a package.
 *
 * The coordinator never constructs an executor, never touches a run catalog and never hides a
 * backend rejection: a port that answers `TOOL_PACKAGE_EXECUTABLE_NOT_SUPPORTED` flips the block to
 * an inert "not wired" state instead of showing fake toggles.
 */
internal class AgentToolPackagesCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentToolPackageAdminPort,
    private val documents: AgentDocumentLauncher
) {
    private val mutable = MutableStateFlow(AgentToolPackagesUiState())
    val state: StateFlow<AgentToolPackagesUiState> = mutable.asStateFlow()

    /** Raw executable rows kept so a transient list failure does not erase the visible rows. */
    private var lastExecutablePackages: List<AgentExecutablePackageItem> = emptyList()

    fun open() {
        if (mutable.value.visible) return
        val shouldLoad = mutable.value.items.isEmpty() && !mutable.value.loading
        mutable.update {
            it.copy(visible = true, loading = shouldLoad || it.loading, notice = null)
        }
        if (shouldLoad) scope.launch { refreshNow() }
        if (mutable.value.executable.items.isEmpty() && !mutable.value.executable.loading) {
            scope.launch { refreshExecutableNow() }
        }
    }

    fun close() {
        mutable.update { it.copy(visible = false, executable = it.executable.copy(confirmation = null)) }
    }

    fun refresh() {
        if (mutable.value.loading || mutable.value.previewing) return
        mutable.update { it.copy(loading = true, error = null, notice = null) }
        scope.launch { refreshNow() }
        if (!mutable.value.executable.busy) scope.launch { refreshExecutableNow() }
    }

    /**
     * Opens the system ZIP picker and previews the selected document. No enable, import or install
     * action exists on this v1 path; a canceled picker leaves the page unchanged.
     */
    fun requestPreview() {
        if (mutable.value.previewing) return
        documents.openDocument { source ->
            if (source != null) startPreview(source)
        }
    }

    /**
     * Importing a v1 preview into the run catalog is still closed by explicit policy; this intent
     * only explains the gate and never reaches the port.
     */
    fun requestImport() {
        mutable.update {
            it.copy(
                notice = AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY,
                error = null
            )
        }
    }

    fun dismissStatus() {
        mutable.update {
            it.copy(
                error = null,
                notice = null,
                executable = it.executable.copy(error = null, notice = null)
            )
        }
    }

    // --- Executable (format v2) lifecycle -----------------------------------------------------

    /** Re-reads the executable list; also used after every applied lifecycle action. */
    fun refreshExecutable() {
        if (mutable.value.executable.busy) return
        scope.launch { refreshExecutableNow() }
    }

    /** Opens the ZIP picker for an executable package; the result is installed but stays disabled. */
    fun installExecutablePackage() {
        val current = mutable.value.executable
        if (!current.supported || current.busy) return
        documents.openDocument { source ->
            if (source != null) startExecutableInstall(source)
        }
    }

    /**
     * Requests one row action. Destructive actions open a confirmation first; clearing a revocation
     * is safe and runs immediately.
     */
    fun requestExecutableAction(packageId: String, action: AgentExecutablePackageAction) {
        val current = mutable.value.executable
        if (!current.supported || current.busy) return
        val item = current.items.firstOrNull { it.packageId == packageId }
            ?: AgentExecutablePackageUiItem(
                packageId = packageId,
                displayName = packageId,
                versionLabel = "",
                statusLabel = "",
                problemLabel = null,
                enabled = false,
                revoked = false,
                rollbackAvailable = false,
                contentVerified = false,
                toolCountLabel = "",
                busy = false
            )
        val confirmation = executablePackageConfirmation(item.packageId, item.displayName, action)
        if (confirmation == null) {
            startExecutableAction(packageId, action)
        } else {
            mutable.update { it.copy(executable = it.executable.copy(confirmation = confirmation)) }
        }
    }

    fun confirmExecutableAction() {
        val confirmation = mutable.value.executable.confirmation ?: return
        mutable.update { it.copy(executable = it.executable.copy(confirmation = null)) }
        startExecutableAction(confirmation.packageId, confirmation.action)
    }

    fun cancelExecutableAction() {
        mutable.update { it.copy(executable = it.executable.copy(confirmation = null)) }
    }

    // --- Internals ----------------------------------------------------------------------------

    internal suspend fun refreshNow() {
        mutable.update { it.copy(loading = true, error = null) }
        val result = try {
            port.list()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentToolPackageListResult.Rejected(
                code = "TOOL_PACKAGE_ADMIN_FAILED",
                safeMessage = "工具包列表暂时无法载入，请稍后重试。"
            )
        }
        when (result) {
            is AgentToolPackageListResult.Loaded -> {
                val projected = projectToolPackagesState(
                    AgentToolPackagesProjectionInput(
                        visible = mutable.value.visible,
                        loading = false,
                        previewing = mutable.value.previewing,
                        packages = result.packages,
                        preview = mutable.value.preview,
                        executable = mutable.value.executable
                    )
                )
                mutable.update {
                    it.copy(items = projected.items, loading = false, error = null)
                }
            }

            is AgentToolPackageListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    error = AgentToolPackageErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun refreshExecutableNow(clearStatus: Boolean = true) {
        mutable.update {
            it.copy(
                executable = it.executable.copy(
                    loading = true,
                    error = if (clearStatus) null else it.executable.error,
                    notice = if (clearStatus) null else it.executable.notice
                )
            )
        }
        val result = try {
            port.listExecutable()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentExecutablePackageListResult.Rejected(
                code = AgentExecutablePackageSupport.LIST_FAILED_CODE,
                safeMessage = AgentExecutablePackageSupport.LIST_FAILED_MESSAGE
            )
        }
        when (result) {
            is AgentExecutablePackageListResult.Loaded -> {
                lastExecutablePackages = result.packages
                mutable.update { current ->
                    current.copy(
                        executable = projectExecutablePackagesState(
                            AgentExecutablePackagesProjectionInput(
                                packages = result.packages,
                                supported = true,
                                // A post-action refresh must not erase the action's own outcome.
                                error = current.executable.error,
                                notice = current.executable.notice
                            )
                        )
                    )
                }
            }

            is AgentExecutablePackageListResult.Rejected -> {
                val notSupported =
                    result.code == AgentExecutablePackageSupport.NOT_SUPPORTED_CODE
                mutable.update { current ->
                    current.copy(
                        executable = projectExecutablePackagesState(
                            AgentExecutablePackagesProjectionInput(
                                // "not supported" is a wiring fact, not a scary error: keep the block
                                // inert and silent. Any other rejection keeps the last known rows and
                                // shows the typed message.
                                packages = if (notSupported) {
                                    emptyList()
                                } else {
                                    lastExecutablePackages
                                },
                                loading = false,
                                supported = !notSupported,
                                error = when {
                                    notSupported -> null
                                    current.executable.error != null -> current.executable.error
                                    else -> AgentToolPackageErrorUi(result.code, result.safeMessage)
                                },
                                notice = current.executable.notice
                            )
                        )
                    )
                }
            }
        }
    }

    private fun startPreview(source: AgentDocumentRef) {
        mutable.update {
            it.copy(previewing = true, preview = null, error = null, notice = null)
        }
        scope.launch { previewNow(source) }
    }

    internal suspend fun previewNow(source: AgentDocumentRef) {
        mutable.update { it.copy(previewing = true, error = null, notice = null) }
        val result = try {
            port.preview(source)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentToolPackagePreviewResult.Rejected(
                code = "TOOL_PACKAGE_ADMIN_FAILED",
                safeMessage = "工具包预览失败，请重试。"
            )
        }
        when (result) {
            is AgentToolPackagePreviewResult.Previewed -> {
                mutable.update {
                    it.copy(
                        previewing = false,
                        preview = projectToolPackagePreview(result.preview),
                        notice = "预览已载入：仅用于检查，不会启用，也不会加入运行目录。",
                        error = null
                    )
                }
                // The preview became one installed snapshot, so reload the authoritative list.
                refreshNow()
            }

            is AgentToolPackagePreviewResult.Rejected -> mutable.update {
                it.copy(
                    previewing = false,
                    preview = null,
                    error = AgentToolPackageErrorUi(result.code, result.safeMessage, result.details)
                )
            }
        }
    }

    private fun startExecutableInstall(source: AgentDocumentRef) {
        mutable.update {
            it.copy(
                executable = it.executable.copy(
                    installing = true,
                    error = null,
                    notice = null,
                    confirmation = null
                )
            )
        }
        scope.launch { installExecutableNow(source) }
    }

    internal suspend fun installExecutableNow(source: AgentDocumentRef) {
        val result = try {
            port.installExecutable(source)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentExecutablePackageActionResult.Rejected(
                code = AgentExecutablePackageSupport.ACTION_FAILED_CODE,
                safeMessage = AgentExecutablePackageSupport.ACTION_FAILED_MESSAGE
            )
        }
        mutable.update {
            it.copy(
                executable = it.executable.copy(
                    installing = false,
                    notice = (result as? AgentExecutablePackageActionResult.Applied)?.notice,
                    error = (result as? AgentExecutablePackageActionResult.Rejected)?.let { rejected ->
                        AgentToolPackageErrorUi(rejected.code, rejected.safeMessage)
                    }
                )
            )
        }
        refreshExecutableNow(clearStatus = false)
    }

    private fun startExecutableAction(packageId: String, action: AgentExecutablePackageAction) {
        mutable.update {
            it.copy(
                executable = it.executable.copy(
                    busyPackageId = packageId,
                    error = null,
                    notice = null,
                    confirmation = null
                )
            )
        }
        scope.launch { performExecutableAction(packageId, action) }
    }

    internal suspend fun performExecutableAction(
        packageId: String,
        action: AgentExecutablePackageAction
    ) {
        val result = try {
            when (action) {
                AgentExecutablePackageAction.ENABLE -> port.enableExecutable(packageId)
                AgentExecutablePackageAction.DISABLE -> port.disableExecutable(packageId)
                AgentExecutablePackageAction.ROLLBACK -> port.rollbackExecutable(packageId)
                AgentExecutablePackageAction.REVOKE -> port.revokeExecutable(packageId)
                AgentExecutablePackageAction.CLEAR_REVOCATION -> port.clearExecutableRevocation(packageId)
                AgentExecutablePackageAction.UNINSTALL -> port.uninstallExecutable(packageId)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentExecutablePackageActionResult.Rejected(
                code = AgentExecutablePackageSupport.ACTION_FAILED_CODE,
                safeMessage = AgentExecutablePackageSupport.ACTION_FAILED_MESSAGE
            )
        }
        mutable.update {
            it.copy(
                executable = it.executable.copy(
                    busyPackageId = null,
                    notice = (result as? AgentExecutablePackageActionResult.Applied)?.notice,
                    error = (result as? AgentExecutablePackageActionResult.Rejected)?.let { rejected ->
                        AgentToolPackageErrorUi(rejected.code, rejected.safeMessage)
                    }
                )
            )
        }
        refreshExecutableNow(clearStatus = false)
    }
}
