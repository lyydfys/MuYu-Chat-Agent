package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.download.*
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.QnnRuntimeProfileSelector
import com.muyuchat.core.nativebridge.NativeQnnBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

internal class ManagedModelDownloadInstaller(internal val context: Context, private val projectorTargetId: String?) {
    internal val modelStore = ModelStoreRepository(context)
    internal val localImageModelStore = LocalImageModelStore(context)
    internal val modelScopeClient = ModelScopeClient()
    internal val downloader = ResumableDownloader()
    internal val device by lazy { DeviceProfileReader(context).read() }
    internal val _uiState = MutableStateFlow(ManagedDownloadProgress())
    val progress get() = _uiState
    internal fun busy(message: String) { _uiState.update { it.copy(
        downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
        statusMessage = message,
        phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INSTALLING
    ) } }
    internal fun integrityPassed(message: String = "所有下载组件的大小和 SHA-256 与目录声明一致。") {
        _uiState.update { it.copy(
            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INTEGRITY_CHECK,
            downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
            integrityStatus = "PASSED", integrityMessage = message
        ) }
    }
    internal fun executionResult(status: String, message: String) {
        _uiState.update { it.copy(
            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.EXECUTION_CHECK,
            downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
            executionStatus = status, executionMessage = message
        ) }
    }
    /** Publish a terminal installer failure without hiding a previously proven file check. */
    internal fun markFailure(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank) ?: "下载或安装失败。"
        _uiState.update {
            val source = when (it.phase) {
                com.muyuchat.feature.modelhub.ModelHubDownloadPhase.QUEUED,
                com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING ->
                    com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.DOWNLOAD
                com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INSTALLING ->
                    com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.INSTALL
                com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INTEGRITY_CHECK ->
                    com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.INTEGRITY
                else -> com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.EXECUTION
            }
            it.copy(
                failureSource = source,
                integrityStatus = if (source == com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.INTEGRITY) "FAILED" else it.integrityStatus,
                integrityMessage = if (source == com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.INTEGRITY) message else it.integrityMessage,
                executionStatus = if (source == com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.EXECUTION) "FAILED" else it.executionStatus,
                executionMessage = if (source == com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.EXECUTION) message else it.executionMessage,
                statusMessage = "下载或安装失败：$message"
            )
        }
    }
    internal fun logCommittedCleanupFailure(error: Throwable) {
        android.util.Log.w("McaModelDownload", "安装已提交；清理失败的文件将保留供下次恢复。", error)
    }

    internal fun cleanupCommittedDownloadArtifact(file: File) {
        runCatching {
            check(!file.exists() || file.delete()) { "已安装模型的下载暂存文件清理失败：$file" }
        }.exceptionOrNull()?.let(::logCommittedCleanupFailure)
    }

    private fun formatBytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    internal fun inspectQnnBundleCompatibility(
        bundleDir: File,
        installedBundle: ImageEngineBundleSpec
    ): QnnBundlePreflight =
        inspectQnnBundleCompatibility(bundleDir, installedBundle, device) { root ->
            org.json.JSONArray(qnnRuntimeDirectoriesFor(context, root)).toString()
        }
    suspend fun install(request: ManagedDownloadRequest): InstalledManagedDownload {
        _uiState.value = ManagedDownloadProgress(
            downloadStatus = DownloadStatus.RUNNING,
            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
            statusMessage = "正在准备下载…",
            integrityStatus = "RUNNING",
            executionStatus = "PENDING"
        )
        val recommendation = request.recommendationId?.let { id ->
            modelScopeClient.recommendedModels().firstOrNull { it.id == id }
                ?: error("推荐模型已更新，请刷新推荐页后重新下载。")
        }
        if (recommendation != null) {
            check(request.catalogFingerprint == managedCatalogFingerprint(recommendation)) {
                "推荐模型的组件配置已更新，已保留旧进度。请刷新推荐页后重新下载。"
            }
            when {
                recommendation.mnnModelBundle != null -> {
                    val model = downloadRecommendedMnnBundle(recommendation)
                    integrityPassed("MNN 全部组件已完成大小、SHA-256 和安装审计校验。")
                    executionResult("PREFLIGHT_PASSED", "MNN 组件结构和配置已通过预检；首次加载时确认本机后端兼容性。")
                    val checks = _uiState.value
                    return InstalledManagedDownload(modelId = model.id, message = "已下载并导入：${model.displayName}",
                        integrityStatus = checks.integrityStatus, integrityMessage = checks.integrityMessage,
                        executionStatus = checks.executionStatus, executionMessage = checks.executionMessage)
                }
                recommendation.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT -> {
                    val model = downloadRecommendedQairtChatBundle(recommendation)
                    integrityPassed("QNN 聊天引擎压缩包和安装内容已通过完整性校验。")
                    executionResult("PREFLIGHT_PASSED", "QNN 聊天引擎已完成结构预检；首次加载时确认本机执行兼容性。")
                    val checks = _uiState.value
                    return InstalledManagedDownload(modelId = model.id, message = "已下载并导入：${model.displayName}",
                        integrityStatus = checks.integrityStatus, integrityMessage = checks.integrityMessage,
                        executionStatus = checks.executionStatus, executionMessage = checks.executionMessage)
                }
                recommendation.visionModelBundle != null -> {
                    return when (val result = downloadRecommendedVisionBundle(recommendation)) {
                        is VisionBundleDownloadResult.ChatModel -> InstalledManagedDownload(modelId = result.model.id,
                            message = "已导入多模态模型并绑定视觉投影器：${result.model.displayName}",
                            integrityStatus = _uiState.value.integrityStatus,
                            integrityMessage = _uiState.value.integrityMessage,
                            executionStatus = "PREFLIGHT_PASSED",
                            executionMessage = "模型和视觉投影器已完成结构预检；首次加载时确认本机兼容性")
                        is VisionBundleDownloadResult.EngineBundle -> InstalledManagedDownload(
                            message = "已导入：${result.displayName}。${result.report.message}",
                            integrityStatus = _uiState.value.integrityStatus,
                            integrityMessage = _uiState.value.integrityMessage,
                            executionStatus = _uiState.value.executionStatus,
                            executionMessage = _uiState.value.executionMessage)
                    }
                }
                recommendation.remoteImageBundle != null -> {
                    val image = downloadRecommendedRemoteImageBundle(
                        recommendation,
                        request.imageBundleVariantId
                    )
                    val checks = _uiState.value
                    return InstalledManagedDownload(
                        imageId = image.id,
                        message = "已下载并导入生图模型：${image.displayName}",
                        integrityStatus = checks.integrityStatus,
                        integrityMessage = checks.integrityMessage,
                        executionStatus = checks.executionStatus,
                        executionMessage = checks.executionMessage
                    )
                }
                recommendation.imageEngineBundle != null -> {
                    val image = downloadRecommendedImageBundle(recommendation)
                    val checks = _uiState.value
                    return InstalledManagedDownload(
                        imageId = image.id,
                        message = "已下载并导入生图模型：${image.displayName}",
                        integrityStatus = checks.integrityStatus,
                        integrityMessage = checks.integrityMessage,
                        executionStatus = checks.executionStatus,
                        executionMessage = checks.executionMessage
                    )
                }
            }
        }
        val remote = request.remote ?: modelScopeClient.recommendedFile(requireNotNull(recommendation))
        return when (val result = download(remote, recommendation?.chatRuntime)) {
            is DownloadedModelRegistration.Chat -> InstalledManagedDownload(modelId = result.model.id, message = "已下载并导入：${result.model.displayName}",
                integrityStatus = _uiState.value.integrityStatus, integrityMessage = _uiState.value.integrityMessage,
                executionStatus = _uiState.value.executionStatus, executionMessage = _uiState.value.executionMessage)
            is DownloadedModelRegistration.Image -> InstalledManagedDownload(imageId = result.model.id, message = "已下载并导入生图模型：${result.model.displayName}",
                integrityStatus = _uiState.value.integrityStatus, integrityMessage = _uiState.value.integrityMessage,
                executionStatus = _uiState.value.executionStatus, executionMessage = _uiState.value.executionMessage)
            is DownloadedModelRegistration.VisionProjector -> InstalledManagedDownload(modelId = result.model.id, projector = true, message = "已下载并绑定视觉投影器",
                integrityStatus = _uiState.value.integrityStatus, integrityMessage = _uiState.value.integrityMessage,
                executionStatus = _uiState.value.executionStatus, executionMessage = _uiState.value.executionMessage)
        }
    }
    internal suspend fun downloadVerifiedOrResume(remote: RemoteModelFile, part: File, final: File,
        progress: (DownloadTaskSnapshot) -> Unit) {
        val receipt = CompletedDownloadReceipt(final, managedRemoteIdentity(remote))
        if (receipt.matches(remote)) return
        downloader.download(remote, part, final, progress)
        receipt.record()
    }

    private suspend fun download(
        remote: RemoteModelFile,
        chatRuntime: RecommendedChatRuntime? = null
    ): DownloadedModelRegistration {
        return run {
                val remoteKind = remote.fileKind()
                if (remoteKind == RemoteModelFileKind.MNN_COMPONENT) {
                    error("MNN 组件需要作为完整高速引擎包下载或多选导入。请回到推荐卡片点击“下载 MNN”，或一次选择完整组件。")
                }
                val imageModel = remote.isImageModelCandidate()
                val targetVisionModel = if (remoteKind == RemoteModelFileKind.PROJECTOR) {
                    modelStore.getModel(requireNotNull(projectorTargetId) { "请先选择要绑定视觉投影器的主模型。" })
                        ?: error("要绑定投影器的主模型已不存在，请重新选择。")
                } else {
                    null
                }
                val downloadRoot = if (imageModel) localImageModelStore.managedBundleDirFor("single-${managedRemoteIdentity(remote)}")
                    else modelStore.managedBundleDirFor("single-${managedRemoteIdentity(remote)}")
                val finalFile = if (imageModel) localImageModelStore.managedBundleFileFor(downloadRoot, remote.name)
                    else modelStore.managedBundleFileFor(downloadRoot, remote.name)
                val tempFile = File(finalFile.parentFile, ".${managedRemoteIdentity(remote)}.part")
                downloadVerifiedOrResume(remote, tempFile, finalFile) { snapshot ->
                    _uiState.update {
                        it.copy(
                            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                            downloadFileName = snapshot.fileName,
                            downloadedBytes = snapshot.downloadedBytes,
                            downloadTotalBytes = snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                            statusMessage = snapshot.errorMessage ?: "正在下载 ${snapshot.fileName}"
                        )
                    }
                }
                busy("下载完成，正在校验并自动导入本地模型…")
                integrityPassed("文件已通过大小和 SHA-256 校验。")
                val registration = if (imageModel) {
                    DownloadedModelRegistration.Image(localImageModelStore.registerDownloadedModel(finalFile, remote))
                } else if (remoteKind == RemoteModelFileKind.PROJECTOR && targetVisionModel != null) {
                    DownloadedModelRegistration.VisionProjector(
                        model = modelStore.attachVisionProjectorFile(targetVisionModel.id, finalFile, remote.name),
                        // The observer reloads the active model when this target is
                        // currently loaded.  Keep the registration explicit so a
                        // future worker/UI path cannot silently leave a stale text
                        // session after binding the projector.
                        shouldReload = true
                    )
                } else {
                    DownloadedModelRegistration.Chat(
                        modelStore.registerDownloadedModel(
                            file = finalFile,
                            repoId = remote.repoId,
                            revision = remote.revision,
                            license = remote.license,
                            source = remote.provider.toModelSource(),
                            runtime = if (chatRuntime == RecommendedChatRuntime.GENIEX_LLAMA_CPP) {
                                com.muyuchat.core.modelstore.ChatModelRuntime.GENIEX_LLAMA_CPP
                            } else {
                                com.muyuchat.core.modelstore.ChatModelRuntime.LLAMA_CPP
                            }
                        )
                    )
                }
                executionResult("PENDING", "文件已导入；本机执行兼容性将在首次加载或模型页校验时确认。")
                registration
        }
    }
}
