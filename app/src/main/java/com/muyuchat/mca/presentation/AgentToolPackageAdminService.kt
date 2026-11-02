package com.muyuchat.mca.presentation

import android.content.Context
import android.net.Uri
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageActionResult
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageItem
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageListResult
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageSupport
import com.muyuchat.feature.agent.workspace.AgentToolPackageAdminPort
import com.muyuchat.feature.agent.workspace.AgentToolPackageItem
import com.muyuchat.feature.agent.workspace.AgentToolPackageListResult
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewItem
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewResult
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewToolItem
import com.muyuchat.mca.agent.composition.AgentDataAssembly
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageErrorCode
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageException
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFileStore
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageIngestResult
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageLimits
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageStoredPackage
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageSummary
import com.muyuchat.mca.agent.extensions.toolpkg.BuiltInToolPackageHostCatalog
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleAction
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleOutcome
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleService
import com.muyuchat.mca.agent.toolpack.ThirdPartyTranslationInstallResult
import com.muyuchat.mca.agent.toolpack.ThirdPartyTranslationResult
import com.muyuchat.mca.agent.toolpack.ToolPackageHostAccess
import com.muyuchat.mca.agent.toolpack.ToolPackageHostApis
import com.muyuchat.mca.agent.toolpack.ExecutableToolPackageManifest
import com.muyuchat.mca.agent.toolpack.ToolPackageImportPreview
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleStatus
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-side adapter for the tool-package settings surface.
 *
 * Two capabilities, one port:
 * - the v1 private preview index ([AgentToolPackageFileStore.summaries]) plus one-ZIP validation
 *   through the host SAF bridge ([preview]). Nothing here can activate a v1 preview;
 * - the executable (format v2) lifecycle surface (R6) delegated to [executableLifecycle]. When that
 *   service is not wired every executable call returns the typed
 *   `TOOL_PACKAGE_EXECUTABLE_NOT_SUPPORTED` answer; it never falls back to the preview store.
 *
 * The P0 manifest validator accepts only read-only host-operation bindings, so the row's effect
 * summary is that real invariant instead of an invented per-package value. A summary carries no
 * source label, so the adapter leaves it null rather than guessing; the preview labels its own
 * source without exposing the raw URI.
 */
class AgentToolPackageAdminService(
    private val store: AgentToolPackageFileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Incremental R4 seam: the executable-package (format v2) lifecycle backend. Null means the
     * settings surface can still preview v1 packages but has no install/enable/disable capability.
     * The composition root owns construction; this adapter never creates a store, engine or process.
     */
    private val executableLifecycle: ToolPackageLifecycleService? = null
) : AgentToolPackageAdminPort {

    private var cacheDir: File? = null
    private var copyArchive: ((AgentDocumentRef, File) -> Unit)? = null

    /**
     * The executable-package lifecycle service when the composition root wired one, else null.
     *
     * A page that renders v2 package actions must call this and show a typed "not available" state
     * when it is null; it must never fall back to the v1 preview store for those actions.
     */
    fun executablePackageLifecycle(): ToolPackageLifecycleService? = executableLifecycle

    /**
     * Test/preview construction: the Android SAF collaborators are supplied explicitly instead of
     * being derived from a Context. Production uses [create].
     */
    internal constructor(
        store: AgentToolPackageFileStore,
        ioDispatcher: CoroutineDispatcher,
        cacheDir: File,
        copyArchive: (AgentDocumentRef, File) -> Unit,
        executableLifecycle: ToolPackageLifecycleService? = null
    ) : this(store, ioDispatcher, executableLifecycle) {
        this.cacheDir = cacheDir
        this.copyArchive = copyArchive
    }

    override suspend fun list(): AgentToolPackageListResult = withContext(ioDispatcher) {
        try {
            AgentToolPackageListResult.Loaded(store.summaries().map(::toItem))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentToolPackageListResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = FAILURE_MESSAGE
            )
        }
    }

    override suspend fun preview(source: AgentDocumentRef): AgentToolPackagePreviewResult =
        withContext(ioDispatcher) {
            val directory = cacheDir
            val copy = copyArchive
            if (directory == null || copy == null) {
                return@withContext AgentToolPackagePreviewResult.Rejected(
                    code = "TOOL_PACKAGE_ADMIN_UNAVAILABLE",
                    safeMessage = "工具包预览尚未接入。"
                )
            }
            val spool = File(directory, "tool-package-preview-${UUID.randomUUID()}.zip")
            try {
                copy(source, spool)
                formatGate(spool)?.let { return@withContext it }
                toPreviewResult(store.preview(source.value, spool))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejection: AgentToolPackageAdminRejection) {
                AgentToolPackagePreviewResult.Rejected(rejection.code, rejection.safeMessage)
            } catch (failure: AgentToolPackageException) {
                AgentToolPackagePreviewResult.Rejected(
                    code = failure.error.code.name,
                    safeMessage = previewFailureMessage(failure.error.code)
                )
            } catch (_: Throwable) {
                AgentToolPackagePreviewResult.Rejected(
                    code = FAILURE_CODE,
                    safeMessage = "工具包预览失败，请确认 ZIP 完整后重试。"
                )
            } finally {
                runCatching { spool.delete() }
            }
        }

    /**
     * Classifies the picked archive through the single import entry before the v1 preview store sees
     * it, so a third-party or executable-v2 package gets its own typed answer instead of the store's
     * "missing mca-tool-package.json". Returns null only for legacy inert v1, which keeps using that
     * store's own read path. Read-only: the classifier and both parsers install and execute nothing.
     */
    private fun formatGate(spool: File): AgentToolPackagePreviewResult? {
        val lifecycle = executableLifecycle ?: return null
        return when (val classified = lifecycle.previewImport(spool.readBytes())) {
            is ToolPackageImportPreview.LegacyInert -> null
            is ToolPackageImportPreview.Executable -> AgentToolPackagePreviewResult.Rejected(
                code = IMPORT_FORMAT_V2_CODE,
                safeMessage = "这是可执行工具包（v2），请使用可执行包的安装操作。",
                details = executableFormatDetails(classified.manifest)
            )
            is ToolPackageImportPreview.ThirdParty -> AgentToolPackagePreviewResult.Rejected(
                code = IMPORT_FORMAT_THIRD_PARTY_CODE,
                safeMessage = thirdpartyImportMessage(classified.translation),
                details = thirdpartyFormatDetails(classified.translation)
            )
            is ToolPackageImportPreview.Rejected -> AgentToolPackagePreviewResult.Rejected(
                code = classified.code.name,
                safeMessage = classified.safeMessage
            )
        }
    }

    private fun thirdpartyImportMessage(translation: ThirdPartyTranslationResult): String =
        when (translation) {
            is ThirdPartyTranslationResult.Rejected ->
                "识别为第三方工具包，但当前不支持其 ${translation.fieldPath}" +
                    "（${translation.code.name}）：不会安装任何内容。"
            is ThirdPartyTranslationResult.Translated ->
                "识别为第三方工具包：仅支持受限 CommonJS main(input)，" +
                    "可用安装操作按该受限契约安装（默认停用）。"
        }

    /**
     * What a v2 archive declares, so the rejection is actionable instead of a format label: the
     * manifest's own host APIs and their access come from the registry (a manifest cannot widen
     * them), and any WRITE API is called out because production still refuses those.
     */
    private fun executableFormatDetails(manifest: ExecutableToolPackageManifest): List<String> {
        val writeApis = manifest.hostApis.filter { api ->
            ToolPackageHostApis.find(api)?.access == ToolPackageHostAccess.WRITE
        }
        return buildList {
            add(
                "格式：可执行工具包 v${manifest.formatVersion} · host API v${manifest.hostApiVersion}" +
                    " · 工具 ${manifest.tools.size} 个"
            )
            add("包 ${manifest.packageId} · 版本 ${manifest.packageVersion}")
            add("声明 host API：${manifest.hostApis.joinToString("、")}")
            if (writeApis.isNotEmpty()) {
                add("写入型 host API：${writeApis.joinToString("、")} · 生产默认拒绝，逐次审批未接线")
            }
            if (manifest.dependencies.isNotEmpty()) {
                add("依赖 ${manifest.dependencies.size} 个")
            }
        }
    }

    /** What this runtime supports of a third-party package, stated as the difference the user faces. */
    private fun thirdpartyFormatDetails(translation: ThirdPartyTranslationResult): List<String> = buildList {
        add("格式：第三方工具包")
        add("支持的执行上下文：受限 CommonJS main(input)")
        add("未支持：UI、WASM、sandbox/provider、main 注册与 hooks")
        when (translation) {
            is ThirdPartyTranslationResult.Rejected ->
                add("拒绝位置：${translation.fieldPath} · 代码 ${translation.code.name}")
            is ThirdPartyTranslationResult.Translated ->
                add("该包可翻译为受限 main(input)；翻译成功不等于生产兼容")
        }
    }

    // --- Executable (format v2) lifecycle (R6) ------------------------------------------------

    override suspend fun listExecutable(): AgentExecutablePackageListResult = withContext(ioDispatcher) {
        val lifecycle = executableLifecycle
            ?: return@withContext executableNotSupportedList()
        try {
            AgentExecutablePackageListResult.Loaded(lifecycle.list().map(::toExecutableItem))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentExecutablePackageListResult.Rejected(
                code = AgentExecutablePackageSupport.LIST_FAILED_CODE,
                safeMessage = AgentExecutablePackageSupport.LIST_FAILED_MESSAGE
            )
        }
    }

    override suspend fun installExecutable(
        source: AgentDocumentRef
    ): AgentExecutablePackageActionResult = withContext(ioDispatcher) {
        val lifecycle = executableLifecycle
            ?: return@withContext executableNotSupportedAction()
        val directory = cacheDir
        val copy = copyArchive
        if (directory == null || copy == null) {
            return@withContext AgentExecutablePackageActionResult.Rejected(
                code = AgentExecutablePackageSupport.INSTALL_UNAVAILABLE_CODE,
                safeMessage = AgentExecutablePackageSupport.INSTALL_UNAVAILABLE_MESSAGE
            )
        }
        val spool = File(directory, "tool-package-executable-${UUID.randomUUID()}.zip")
        try {
            copy(source, spool)
            if (spool.length() > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
                return@withContext AgentExecutablePackageActionResult.Rejected(
                    code = "PACKAGE_TOO_LARGE",
                    safeMessage = "工具包 ZIP 超过大小上限。"
                )
            }
            val bytes = spool.readBytes()
            when (lifecycle.previewImport(bytes)) {
                // A third-party archive has no MCA manifest, so the v2 installer could only answer
                // "missing manifest". It still installs through the same lifecycle/store, disabled
                // until enabled, and every refusal stays the translator's own typed code.
                is ToolPackageImportPreview.ThirdParty -> thirdPartyInstallResult(
                    lifecycle.installThirdPartyArchive(bytes)
                )
                else -> executableResult(
                    outcome = lifecycle.installArchive(bytes),
                    appliedNotice = "已安装并校验；默认未启用。"
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejection: AgentToolPackageAdminRejection) {
            AgentExecutablePackageActionResult.Rejected(rejection.code, rejection.safeMessage)
        } catch (_: Throwable) {
            AgentExecutablePackageActionResult.Rejected(
                code = AgentExecutablePackageSupport.INSTALL_UNAVAILABLE_CODE,
                safeMessage = AgentExecutablePackageSupport.INSTALL_UNAVAILABLE_MESSAGE
            )
        } finally {
            runCatching { spool.delete() }
        }
    }

    override suspend fun enableExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableAction(packageId, "已启用") { it.enable(packageId) }

    override suspend fun disableExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableAction(packageId, "已停用") { it.disable(packageId) }

    override suspend fun rollbackExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableAction(packageId, "已回滚到上一版本") { it.rollback(packageId) }

    override suspend fun revokeExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableAction(packageId, "已撤销；需要手动解除撤销才能再次启用") { it.revoke(packageId) }

    override suspend fun clearExecutableRevocation(
        packageId: String
    ): AgentExecutablePackageActionResult =
        executableAction(packageId, "已解除撤销；包仍保持停用") { it.clearRevocation(packageId) }

    override suspend fun uninstallExecutable(packageId: String): AgentExecutablePackageActionResult =
        executableAction(packageId, "已卸载并清理未引用内容") { it.uninstall(packageId) }

    override suspend fun collectExecutableGarbage(): AgentExecutablePackageActionResult {
        val lifecycle = executableLifecycle ?: return executableNotSupportedAction()
        return withContext(ioDispatcher) {
            val outcome = try {
                lifecycle.collectGarbage()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext AgentExecutablePackageActionResult.Rejected(
                    code = AgentExecutablePackageSupport.ACTION_FAILED_CODE,
                    safeMessage = AgentExecutablePackageSupport.ACTION_FAILED_MESSAGE
                )
            }
            when (outcome) {
                is ToolPackageLifecycleOutcome.Applied -> AgentExecutablePackageActionResult.Applied(
                    packageId = null,
                    notice = "已清理 ${outcome.detail.purgedContentHashes.size} 项未引用内容。"
                )
                is ToolPackageLifecycleOutcome.Rejected -> AgentExecutablePackageActionResult.Rejected(
                    code = outcome.code,
                    safeMessage = outcome.safeMessage
                )
            }
        }
    }

    private suspend fun executableAction(
        packageId: String,
        appliedNotice: String,
        block: suspend (ToolPackageLifecycleService) -> ToolPackageLifecycleOutcome
    ): AgentExecutablePackageActionResult {
        val lifecycle = executableLifecycle ?: return executableNotSupportedAction()
        return withContext(ioDispatcher) {
            val outcome = try {
                block(lifecycle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext AgentExecutablePackageActionResult.Rejected(
                    code = AgentExecutablePackageSupport.ACTION_FAILED_CODE,
                    safeMessage = AgentExecutablePackageSupport.ACTION_FAILED_MESSAGE
                )
            }
            executableResult(outcome, appliedNotice, packageId)
        }
    }

    private fun executableResult(
        outcome: ToolPackageLifecycleOutcome,
        appliedNotice: String,
        fallbackPackageId: String? = null
    ): AgentExecutablePackageActionResult = when (outcome) {
        is ToolPackageLifecycleOutcome.Applied -> AgentExecutablePackageActionResult.Applied(
            packageId = outcome.status?.packageId ?: fallbackPackageId,
            notice = appliedNotice
        )
        is ToolPackageLifecycleOutcome.Rejected -> AgentExecutablePackageActionResult.Rejected(
            code = outcome.code,
            safeMessage = outcome.safeMessage
        )
    }

    /**
     * Maps the translation installer's typed outcome onto the action result this page already
     * renders. An installed translation is disabled exactly like a v2 install; a refusal keeps the
     * translator's own code and field path instead of a generic install failure.
     */
    private fun thirdPartyInstallResult(
        result: ThirdPartyTranslationInstallResult
    ): AgentExecutablePackageActionResult = when (result) {
        is ThirdPartyTranslationInstallResult.Installed -> AgentExecutablePackageActionResult.Applied(
            packageId = result.status?.packageId ?: result.translation.mcaPackageId,
            notice = "已按受限 main(input) 翻译安装；默认未启用。"
        )
        is ThirdPartyTranslationInstallResult.TranslationRejected ->
            AgentExecutablePackageActionResult.Rejected(
                code = result.result.code.name,
                safeMessage = "该第三方工具包不被支持（${result.result.fieldPath}）：" +
                    result.result.safeMessage
            )
        is ThirdPartyTranslationInstallResult.InstallRejected ->
            AgentExecutablePackageActionResult.Rejected(
                code = result.code,
                safeMessage = result.safeMessage
            )
    }

    private fun toExecutableItem(status: ToolPackageLifecycleStatus): AgentExecutablePackageItem =
        AgentExecutablePackageItem(
            packageId = status.packageId,
            displayName = status.displayName ?: status.packageId,
            version = status.packageVersion,
            enabled = status.enabled,
            revoked = status.revoked,
            rollbackAvailable = status.previousContentHash != null,
            toolCount = status.toolCount,
            contentVerified = status.contentVerified,
            problemCode = status.problemCode
        )

    private fun executableNotSupportedList(): AgentExecutablePackageListResult =
        AgentExecutablePackageListResult.Rejected(
            code = AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
            safeMessage = AgentExecutablePackageSupport.NOT_SUPPORTED_MESSAGE
        )

    private fun executableNotSupportedAction(): AgentExecutablePackageActionResult =
        AgentExecutablePackageActionResult.Rejected(
            code = AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
            safeMessage = AgentExecutablePackageSupport.NOT_SUPPORTED_MESSAGE
        )

    private fun toItem(summary: AgentToolPackageSummary): AgentToolPackageItem =
        AgentToolPackageItem(
            packageId = summary.packageId,
            displayName = summary.displayName,
            version = summary.packageVersion,
            toolCount = summary.toolCount,
            sideEffectSummary = READ_ONLY_SIDE_EFFECT_SUMMARY,
            sourceLabel = null
        )

    private fun toPreviewResult(
        result: AgentToolPackageIngestResult
    ): AgentToolPackagePreviewResult = when (result) {
        is AgentToolPackageIngestResult.Previewed ->
            AgentToolPackagePreviewResult.Previewed(toPreviewItem(result.stored))

        is AgentToolPackageIngestResult.Rejected -> AgentToolPackagePreviewResult.Rejected(
            code = result.error.code.name,
            safeMessage = previewFailureMessage(result.error.code)
        )
    }

    private fun toPreviewItem(stored: AgentToolPackageStoredPackage): AgentToolPackagePreviewItem {
        val preview = stored.preview
        val tools = preview.tools.map { tool ->
            AgentToolPackagePreviewToolItem(
                toolName = tool.ref.toolName,
                displayName = tool.displayName,
                hostOperation = tool.hostOperation,
                sideEffectLabel = sideEffectLabel(tool.sideEffect),
                dataClassLabel = dataClassLabel(tool.dataClass),
                exposedParameters = tool.exposedParameters,
                timeoutMillis = tool.timeoutMillis
            )
        }
        val sideEffects = tools.map { it.sideEffectLabel }.distinct().sorted()
        return AgentToolPackagePreviewItem(
            packageId = preview.packageId,
            displayName = preview.displayName,
            version = preview.packageVersion,
            toolCount = tools.size,
            sideEffectSummary = sideEffects.joinToString("、").ifBlank {
                READ_ONLY_SIDE_EFFECT_SUMMARY
            },
            // The source URI stays in the private store record; the page only needs a label.
            sourceLabel = PREVIEW_SOURCE_LABEL,
            tools = tools,
            resourceCount = preview.resources.size,
            totalExpandedBytes = preview.totalExpandedBytes
        )
    }

    private fun sideEffectLabel(sideEffect: AgentToolSideEffect): String = when (sideEffect) {
        AgentToolSideEffect.READ_ONLY -> "只读"
        AgentToolSideEffect.WORKSPACE_WRITE -> "写入工作区"
        AgentToolSideEffect.EXTERNAL_SIDE_EFFECT -> "外部副作用"
        AgentToolSideEffect.NETWORK -> "网络访问"
    }

    private fun dataClassLabel(dataClass: AgentDataClass): String = when (dataClass) {
        AgentDataClass.PUBLIC -> "公开"
        AgentDataClass.PRIVATE -> "私有"
        AgentDataClass.LOCAL_ONLY -> "仅本机"
        AgentDataClass.SECRET -> "机密"
    }

    private fun previewFailureMessage(code: AgentToolPackageErrorCode): String = when (code) {
        AgentToolPackageErrorCode.PACKAGE_TOO_LARGE -> "工具包 ZIP 超过大小上限。"
        AgentToolPackageErrorCode.MISSING_MANIFEST ->
            "ZIP 根目录缺少 ${AgentToolPackageLimits.MANIFEST_FILE_NAME}。"
        AgentToolPackageErrorCode.MANIFEST_RESOURCE_HASH_MISMATCH -> "工具包资源与清单哈希不一致。"
        AgentToolPackageErrorCode.DUPLICATE_PACKAGE_ID -> "已存在同一 ID 且内容不同的预览，未覆盖。"
        AgentToolPackageErrorCode.PACKAGE_CORRUPT -> "工具包预览快照已损坏。"
        AgentToolPackageErrorCode.INVALID_SOURCE -> "所选文件来源无效。"
        AgentToolPackageErrorCode.STORE_FAILURE -> "工具包预览存储失败。"
        else -> "工具包未通过校验（${code.name}）。"
    }

    companion object {
        /**
         * Real P0 invariant: every stored package binds only to code-owned read-only host
         * operations, so no per-package write/network effect can exist to display.
         */
        internal const val READ_ONLY_SIDE_EFFECT_SUMMARY: String = "只读宿主操作"

        /** Same safe copy the feature coordinator uses for a thrown list failure. */
        internal const val FAILURE_CODE: String = "TOOL_PACKAGE_ADMIN_FAILED"
        internal const val FAILURE_MESSAGE: String = "工具包列表暂时无法载入，请稍后重试。"

        /** Label shown for a locally picked ZIP; the raw content URI never reaches the page. */
        internal const val PREVIEW_SOURCE_LABEL: String = "本机 ZIP 预览"

        /** Typed answers for archives this page cannot preview; the message names the right action. */
        internal const val IMPORT_FORMAT_V2_CODE: String = "TOOL_PACKAGE_IMPORT_FORMAT_V2"
        internal const val IMPORT_FORMAT_THIRD_PARTY_CODE: String = "TOOL_PACKAGE_IMPORT_FORMAT_THIRD_PARTY"

        /**
         * Production construction over the private preview store. The built-in host catalog is
         * metadata for manifest validation; [ToolPackageMetadataOnlyFilePort] is never executed.
         * The store root is the no-backup `files/agent/toolpackages` directory. Internal so the
         * view model's one lazy property is the only production construction point.
         *
         * Wiring the R6 executable backend (`executableLifecycle`) is one composition-root line:
         * pass the `ToolPackageLifecycleService` here. Null keeps the executable block inert with a
         * typed `TOOL_PACKAGE_EXECUTABLE_NOT_SUPPORTED` answer; it never falls back to the preview
         * store.
         */
        internal fun create(
            context: Context,
            executableLifecycle: ToolPackageLifecycleService? = null
        ): AgentToolPackageAdminService {
            val application = context.applicationContext
            val root = File(
                application.filesDir,
                AgentDataAssembly.AGENT_ROOT_DIRECTORY + File.separator + "toolpackages"
            )
            val hostCatalog = BuiltInToolPackageHostCatalog.readOnly(
                builtInAgentToolCatalog(ToolPackageMetadataOnlyFilePort)
            )
            return AgentToolPackageAdminService(
                store = AgentToolPackageFileStore(root, hostCatalog),
                ioDispatcher = Dispatchers.IO,
                cacheDir = application.cacheDir,
                copyArchive = { source, destination ->
                    copyContentUri(application, source, destination)
                },
                executableLifecycle = executableLifecycle
            )
        }
    }
}

/**
 * Internal typed rejection raised by the SAF copy step before the store sees any bytes. It carries a
 * content-free message only; provider/path details never cross this boundary.
 */
internal class AgentToolPackageAdminRejection(
    val code: String,
    val safeMessage: String
) : Exception(safeMessage)

private fun copyContentUri(
    context: Context,
    source: AgentDocumentRef,
    destination: File
) {
    val uri = try {
        Uri.parse(source.value)
    } catch (_: Exception) {
        throw AgentToolPackageAdminRejection("INVALID_SOURCE", "所选文件来源无效。")
    }
    val input = try {
        context.contentResolver.openInputStream(uri)
    } catch (_: Exception) {
        null
    } ?: throw AgentToolPackageAdminRejection("INVALID_SOURCE", "无法读取所选 ZIP。")
    try {
        input.use { sourceStream ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = sourceStream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > AgentToolPackageLimits.MAX_ARCHIVE_BYTES) {
                        throw AgentToolPackageAdminRejection(
                            code = "PACKAGE_TOO_LARGE",
                            safeMessage = "工具包 ZIP 超过大小上限。"
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
                output.fd.sync()
            }
        }
    } finally {
        runCatching { input.close() }
    }
}

private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Catalog construction only: the built-in descriptors require an [AgentWorkspaceFilePort] handle,
 * but the settings adapter only parses package manifests. Every operation fails with a typed code so
 * an accidental execution can never be mistaken for real workspace access.
 */
private object ToolPackageMetadataOnlyFilePort : AgentWorkspaceFilePort {
    override suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult> = noExecution()

    override suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult> = noExecution()

    override suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult> = noExecution()

    override suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> = noExecution()

    private fun noExecution(): AgentFileResult.Failure = AgentFileResult.Failure(
        errorCode = "TOOL_PACKAGE_ADMIN_NO_EXECUTION",
        safeMessage = "工具包管理页不执行任何工作区操作。"
    )
}
