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

internal data class ManagedDownloadProgress(
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.RUNNING,
    val phase: com.muyuchat.feature.modelhub.ModelHubDownloadPhase =
        com.muyuchat.feature.modelhub.ModelHubDownloadPhase.QUEUED,
    val failureSource: com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource? = null,
    val statusMessage: String = "正在准备下载…",
    val integrityStatus: String = "UNKNOWN",
    val integrityMessage: String? = null,
    val executionStatus: String = "UNKNOWN",
    val executionMessage: String? = null
)

internal data class InstalledManagedDownload(
    val modelId: String? = null,
    val imageId: String? = null,
    val projector: Boolean = false,
    val message: String,
    val integrityStatus: String = "PASSED",
    val integrityMessage: String? = null,
    val executionStatus: String = "NOT_APPLICABLE",
    val executionMessage: String? = null
)

internal class ManagedModelDownloadInstaller(private val context: Context, private val projectorTargetId: String?) {
    private val modelStore = ModelStoreRepository(context)
    private val localImageModelStore = LocalImageModelStore(context)
    private val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    private val device by lazy { DeviceProfileReader(context).read() }
    private val _uiState = MutableStateFlow(ManagedDownloadProgress())
    val progress get() = _uiState
    private fun busy(message: String) { _uiState.update { it.copy(
        downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
        statusMessage = message,
        phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INSTALLING
    ) } }
    private fun integrityPassed(message: String = "所有下载组件的大小和 SHA-256 与目录声明一致。") {
        _uiState.update { it.copy(
            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.INTEGRITY_CHECK,
            downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
            integrityStatus = "PASSED", integrityMessage = message
        ) }
    }
    private fun executionResult(status: String, message: String) {
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
    private fun logCommittedCleanupFailure(error: Throwable) {
        android.util.Log.w("McaModelDownload", "安装已提交；清理失败的文件将保留供下次恢复。", error)
    }

    private fun cleanupCommittedDownloadArtifact(file: File) {
        runCatching {
            check(!file.exists() || file.delete()) { "已安装模型的下载暂存文件清理失败：$file" }
        }.exceptionOrNull()?.let(::logCommittedCleanupFailure)
    }

    private fun formatBytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    private fun inspectQnnBundleCompatibility(
        bundleDir: File,
        installedBundle: ImageEngineBundleSpec
    ): QnnBundlePreflight =
        inspectQnnBundleCompatibility(bundleDir, installedBundle, device, context)
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
        return when (val result = download(remote)) {
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
    private suspend fun downloadVerifiedOrResume(remote: RemoteModelFile, part: File, final: File,
        progress: (DownloadTaskSnapshot) -> Unit) {
        val receipt = CompletedDownloadReceipt(final, managedRemoteIdentity(remote))
        if (receipt.matches(remote)) return
        downloader.download(remote, part, final, progress)
        receipt.record()
    }

    private suspend fun downloadRecommendedQairtChatBundle(model: ModelScopeRecommendedModel): ModelManifest {
        return run {
                val remote = modelScopeClient.recommendedQairtChatFile(
                    model = model,
                    preferredChipsets = preferredQairtChipsets(device)
                )
                val bundleId = "qairt-" + remote.sourceIdentity()
                val bundleDir = modelStore.managedBundleDirFor(bundleId)
                val archiveDir = File(bundleDir.parentFile, ".${bundleDir.name}.archive").also { it.mkdirs() }
                val finalZip = File(archiveDir, remote.name)
                val candidateDir = File(bundleDir.parentFile, ".${bundleDir.name}.candidate")
                val tempFile = File(finalZip.parentFile, ".${managedRemoteIdentity(remote)}.part")
                downloadVerifiedOrResume(remote, tempFile, finalZip) { snapshot ->
                    _uiState.update {
                        it.copy(
                            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                            downloadFileName = snapshot.fileName,
                            downloadedBytes = snapshot.downloadedBytes,
                            downloadTotalBytes = snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                            statusMessage = "正在下载 QNN 聊天引擎：${snapshot.fileName}"
                        )
                    }
                }
                busy("下载完成，正在解压并校验 QNN 模型…")
                integrityPassed("QNN 聊天引擎压缩包已通过大小和 SHA-256 校验。")
                extractResumableModelZip(finalZip, candidateDir)
                modelStore.resolveQairtBundleRoot(candidateDir)
                val registered = modelStore.withCatalogTransaction {
                    publishDownloadedBundleCandidate(candidateDir, bundleDir, ::logCommittedCleanupFailure) {
                        val qairtBundleRoot = modelStore.resolveQairtBundleRoot(bundleDir)
                        modelStore.registerDownloadedQairtBundle(
                            displayName = model.title, bundleDir = qairtBundleRoot,
                            repoId = model.repoId, revision = model.revision,
                            source = remote.provider.toModelSource(), quant = model.quant,
                            architecture = recommendedQairtArchitecture(model))
                    }
                }
                cleanupCommittedDownloadArtifact(finalZip)
                registered
        }
    }

    private suspend fun downloadRecommendedMnnBundle(model: ModelScopeRecommendedModel): ModelManifest {
        return run {
                val bundle = requireNotNull(model.mnnModelBundle)
                val components = modelScopeClient.recommendedMnnBundleFiles(model)
                val config = components.firstOrNull {
                    it.mnnBundleRole == MnnModelBundleComponentRole.CONFIG
                } ?: error("MNN 模型包缺少 config.json。")
                val bundleDir = modelStore.managedBundleDirFor(modelBundleSourceIdentity(components, "mnn:" + bundle.installProfile.name))
                val installer = ModelBundleInstaller(
                    BundleComponentDownloader { remote, tempFile, stagedFile, onProgress ->
                        downloader.download(remote, tempFile, stagedFile, onProgress)
                    }
                )
                val plan = installer.plan(bundleDir, components)
                val knownTotalBytes = knownManagedBundleDownloadSize(components)
                val downloadedBytesByPath = mutableMapOf<String, Long>()
                val installed = installer.install(
                    bundleRoot = bundleDir,
                    components = components,
                    stagedTransformer = bundle.installProfile.stagedTransformer()
                ) { snapshot ->
                    val targetIndex = plan.targets.indexOfFirst { target ->
                        target.finalFile.canonicalFile == snapshot.finalFile.canonicalFile
                    }
                    val target = plan.targets.getOrNull(targetIndex)
                    val progressPath = target?.relativePath ?: snapshot.fileName
                    downloadedBytesByPath[progressPath] = snapshot.downloadedBytes
                    _uiState.update {
                        it.copy(
                            phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                            downloadFileName = progressPath,
                            downloadedBytes = downloadedBytesByPath.values.sum(),
                            downloadTotalBytes = knownTotalBytes,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = estimateDownloadRemainingSeconds(
                                knownTotalBytes, downloadedBytesByPath.values.sum(), snapshot.speedBytesPerSecond
                            ),
                            downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                            statusMessage = "正在下载 MNN 组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                        )
                    }
                }
                busy("下载完成，正在校验并自动导入本地模型…")
                require(installer.verifyInstalledBundle(installed.bundleRoot).isVerified) {
                    "MNN 模型包安装后的组件校验失败，请重新下载。"
                }
                integrityPassed("MNN 全部组件已完成大小、SHA-256 和安装审计校验。")
                modelStore.registerDownloadedMnnBundle(
                    displayName = model.title,
                    bundleDir = installed.bundleRoot,
                    repoId = bundle.repoId,
                    revision = bundle.revision,
                    license = config.license,
                    source = bundle.provider.toModelSource(),
                    quant = model.quant,
                    architecture = model.title.substringBefore(' ').lowercase().takeIf { it.isNotBlank() },
                    requiredFiles = bundle.requiredComponents.map { it.relativePath }
                )
        }
    }

    private suspend fun downloadRecommendedVisionBundle(model: ModelScopeRecommendedModel): VisionBundleDownloadResult {
        return run {
                val bundle = requireNotNull(model.visionModelBundle)
                val components = modelScopeClient.recommendedVisionBundleFiles(model)
                val primary = components.firstOrNull {
                    it.visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL
                } ?: error("多模态模型包缺少主模型。")
                val projector = components.firstOrNull {
                    it.visionBundleRole == VisionModelBundleComponentRole.PROJECTOR
                }
                val bundleDir = modelStore.managedBundleDirFor(modelBundleSourceIdentity(components, "vision:" + bundle.runtime.name))
                val candidateDir = imageBundleCandidateDirectory(bundleDir)
                val targets = components.map { remote ->
                    remote to modelStore.managedBundleFileFor(candidateDir, remote.path)
                }
                val bytesToDownload = targets.sumOf { (remote, _) -> remote.sizeBytes ?: 0L }
                var completedBytes = 0L
                targets.forEachIndexed { index, (remote, finalFile) ->
                    val tempFile = File(finalFile.parentFile, ".${managedRemoteIdentity(remote)}.part")
                    val completedBefore = completedBytes
                    downloadVerifiedOrResume(remote, tempFile, finalFile) { snapshot ->
                        _uiState.update {
                            it.copy(
                                phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                            downloadFileName = snapshot.fileName,
                                downloadedBytes = completedBefore + snapshot.downloadedBytes,
                                downloadTotalBytes = bytesToDownload.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                                downloadRemainingSeconds = snapshot.remainingSeconds,
                                downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                                statusMessage = "正在下载多模态组件 ${index + 1}/${targets.size}：${remote.kindLabel()} · ${snapshot.fileName}"
                            )
                        }
                    }
                    completedBytes += finalFile.length()
                }
                integrityPassed("视觉模型和投影器全部通过大小和 SHA-256 校验。")
                check(targets.any { it.first == primary }) { "多模态模型包主模型下载目标不存在。" }
                writeDownloadedVisionBundleManifest(
                    displayName = model.title,
                    bundleDir = candidateDir,
                    bundle = bundle,
                    targets = targets
                )
                val report = if (bundle.runtime == VisionModelBundleRuntime.GGUF_MMPROJ) {
                    null
                } else {
                    LiteRtQnnVisionRunner(context = context).health(
                        device = device,
                        bundleRoot = candidateDir
                    ).also { result ->
                        executionResult(
                            if (result.state == LocalVisionNpuState.BUNDLE_INCOMPLETE ||
                                result.state == LocalVisionNpuState.SMOKE_METADATA_INVALID ||
                                result.state == LocalVisionNpuState.RUNNER_NOT_PACKAGED) "FAILED" else "PREFLIGHT_PASSED",
                            result.message
                        )
                    }
                }
                modelStore.withCatalogTransaction {
                    publishDownloadedBundleCandidate(candidateDir, bundleDir, ::logCommittedCleanupFailure) {
                        val installedTargets = targets.map { (remote, _) ->
                            remote to modelStore.managedBundleFileFor(bundleDir, remote.path).also { installedFile ->
                                check(installedFile.isFile && installedFile.length() > 0L) {
                                    "已提交的多模态组件不完整：${remote.path}"
                                }
                            }
                        }
                        val installedPrimary = installedTargets.firstOrNull { it.first == primary }?.second
                            ?: error("多模态模型包提交后缺少主模型。")
                        val installedResult = if (report == null) {
                            val projectorRemote = projector ?: error("多模态模型包缺少 mmproj / projector。")
                            val projectorFile = installedTargets.firstOrNull { it.first == projectorRemote }?.second
                                ?: error("多模态模型包提交后缺少 projector。")
                            val registered = modelStore.registerDownloadedModel(
                                file = installedPrimary,
                                repoId = primary.repoId,
                                revision = primary.revision,
                                license = primary.license,
                                source = primary.provider.toModelSource()
                            )
                            VisionBundleDownloadResult.ChatModel(
                                modelStore.attachVisionProjectorFile(registered.id, projectorFile, projectorRemote.name)
                            )
                        } else {
                            VisionBundleDownloadResult.EngineBundle(
                                displayName = model.title,
                                bundleDir = bundleDir,
                                report = report
                            )
                        }
                        installedResult
                    }
                }
        }
    }

    private suspend fun downloadRecommendedImageBundle(model: ModelScopeRecommendedModel): LocalImageModelRecord {
        return run {
                val bundle = requireNotNull(model.imageEngineBundle)
                val components = modelScopeClient.recommendedImageBundleFiles(
                    model = model,
                    preferredQairtChipsets = preferredQairtChipsets(device)
                )
                val primary = components.firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
                    ?: error("生图引擎包缺少 diffusion 主模型。")
                val bundleDir = localImageModelStore.managedBundleDirFor(modelBundleSourceIdentity(components, "image:" + bundle.runtime.name))
                val candidateDir = File(bundleDir.parentFile, ".${bundleDir.name}.candidate")
                val installer = ModelBundleInstaller(
                    BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
                        downloader.download(remote, tempFile, finalFile, onProgress)
                    }
                )
                val plan = installer.plan(candidateDir, components)
                val savedArchive = File(bundleDir.parentFile, ".${bundleDir.name}-${managedRemoteIdentity(primary)}.zip")
                val reusableArchive = components.size == 1 && primary.name.endsWith(".zip", true) &&
                    CompletedDownloadReceipt(savedArchive, managedRemoteIdentity(primary)).matches(primary)
                val knownTotalBytes = knownManagedBundleDownloadSize(components)
                if (reusableArchive || isReusableDownloadedImageCandidate(installer, plan)) {
                    _uiState.update {
                        it.copy(
                            downloadFileName = plan.targets.lastOrNull()?.relativePath,
                            downloadedBytes = knownTotalBytes,
                            downloadTotalBytes = knownTotalBytes,
                            downloadSpeedBytesPerSecond = 0L,
                            downloadRemainingSeconds = null,
                            downloadStatus = DownloadStatus.RUNNING,
                            statusMessage = "发现上次已完整下载的生图组件，正在继续校验并自动导入…"
                        )
                    }
                } else {
                    val downloadedBytesByPath = mutableMapOf<String, Long>()
                    installer.install(candidateDir, components) { snapshot ->
                        val targetIndex = plan.targets.indexOfFirst { target ->
                            target.finalFile.canonicalFile == snapshot.finalFile.canonicalFile
                        }
                        val target = plan.targets.getOrNull(targetIndex)
                        val progressPath = target?.relativePath ?: snapshot.fileName
                        downloadedBytesByPath[progressPath] = snapshot.downloadedBytes
                        _uiState.update {
                            it.copy(
                                phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                            downloadFileName = progressPath,
                                downloadedBytes = downloadedBytesByPath.values.sum(),
                                downloadTotalBytes = knownTotalBytes,
                                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                                downloadRemainingSeconds = estimateDownloadRemainingSeconds(
                                knownTotalBytes, downloadedBytesByPath.values.sum(), snapshot.speedBytesPerSecond
                            ),
                                downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                                statusMessage = "正在下载生图组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                            )
                        }
                    }
                }
                busy("下载完成，正在校验组件并自动导入本地生图模型…")
                integrityPassed()
                try {
                    val primaryFile = if (reusableArchive) savedArchive else
                        plan.targets.firstOrNull { it.remote == primary }?.finalFile
                            ?: error("生图引擎包主模型下载目标不存在。")
                    if (primaryFile.extension.equals("zip", ignoreCase = true)) {
                        if (primaryFile != savedArchive) {
                            java.nio.file.Files.move(primaryFile.toPath(), savedArchive.toPath(),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            CompletedDownloadReceipt(savedArchive, managedRemoteIdentity(primary)).record()
                        }
                        val limits = ImageBundleZipExtractionLimits()
                        extractResumableModelZip(savedArchive, candidateDir,
                            maxExpandedBytes = limits.maxTotalBytes,
                            maxEntries = limits.maxEntryCount,
                            maxEntryBytes = limits.maxEntryBytes,
                            shouldSkipTarget = { target ->
                                target == File(candidateDir, "manifest.json").canonicalFile &&
                                    target.isMcaImageBundleManifest()
                            })
                    }
                    val installedBundle = resolveInstalledQnnRuntimeProfile(
                        bundleDir = candidateDir,
                        bundle = bundle,
                        preferredHtpArch = device.let { device ->
                            val chipsetCode = device.accelerationProfile.chipsetCode.ifBlank { device.socModel }
                            DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(chipsetCode)
                                ?: device.accelerationProfile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
                        }
                    )
                    integrityPassed()
                    if (bundle.runtime == ImageEngineBundleRuntime.QNN_HTP) {
                        val preflight = inspectQnnBundleCompatibility(
                            bundleDir = candidateDir,
                            installedBundle = installedBundle
                        )
                        executionResult(preflight.status, preflight.message)
                    } else {
                        executionResult("NOT_APPLICABLE", "该模型不依赖 QNN context，安装后由对应运行时校验。")
                    }
                    preparePinnedQnnRuntimeMetadataIfRequired(candidateDir, installedBundle)
                    prepareMnnDiffusionTokenizerIfPossible(candidateDir)
                    val resolvedPrimary = localImageBundleManifestFromRoot(candidateDir)?.primaryFile
                        ?: findPrimaryImageModel(candidateDir)
                        ?: error("生图引擎包内没有可注册的 diffusion 主模型。")
                    val primarySha256 = resolvedPrimary.sha256ForProfile()
                    val manifestTargets = expandedImageBundleManifestTargets(
                        bundleDir = candidateDir,
                        resolvedPrimary = resolvedPrimary,
                        targets = plan.targets.map { it.remote to it.finalFile }
                    )
                    writeDownloadedImageBundleManifest(
                        displayName = model.title,
                        bundleDir = candidateDir,
                        bundle = installedBundle,
                        targets = manifestTargets,
                        primarySha256 = primarySha256
                    )
                    val installed = localImageModelStore.withCatalogTransaction {
                        publishDownloadedBundleCandidate(candidateDir, bundleDir, ::logCommittedCleanupFailure) {
                            val finalPrimary = File(bundleDir, resolvedPrimary.relativeTo(candidateDir).path)
                            require(finalPrimary.isFile) { "生图引擎主模型在提交后不存在：${resolvedPrimary.name}" }
                            val registered = localImageModelStore.registerDownloadedBundle(
                                displayName = model.title,
                                bundleDir = bundleDir,
                                primaryFile = finalPrimary,
                                primaryRemote = primary,
                                componentCount = components.size,
                                runtimeOverride = bundle.runtime.toLocalImageRuntime(),
                                imageSizeOverride = "${bundle.smokeSpec.width}x${bundle.smokeSpec.height}",
                                primarySha256 = primarySha256
                            )
                            val checks = _uiState.value
                            val annotated = if (checks.executionMessage.orEmpty().isNotBlank()) {
                                localImageModelStore.updateModel(
                                    registered.copy(
                                        verificationStatus = LocalImageVerificationStatus.UNKNOWN,
                                        verificationMessage = checks.executionMessage.orEmpty(),
                                        verifiedAt = 0L
                                    )
                                ).firstOrNull { it.id == registered.id } ?: registered
                            } else registered
                            annotated
                        }
                    }
                    cleanupCommittedDownloadArtifact(savedArchive)
                    installed
                } catch (error: Throwable) {
                    markFailure(error)
                    // Keep the candidate/parts for a resumed install after cancellation or low space.
                    throw error
                }
        }
    }

    private suspend fun download(remote: RemoteModelFile): DownloadedModelRegistration {
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
                            source = remote.provider.toModelSource()
                        )
                    )
                }
                executionResult("PENDING", "文件已导入；本机执行兼容性将在首次加载或模型页校验时确认。")
                registration
        }
    }


    private fun isReusableDownloadedImageCandidate(
        installer: ModelBundleInstaller,
        plan: ModelBundleInstallPlan
    ): Boolean {
        if (!plan.bundleRoot.isDirectory) return false
        val verification = installer.verifyInstalledBundle(plan.bundleRoot)
        if (!verification.isVerified) return false
        val verifiedByPath = verification.components.associateBy { it.audit.relativePath }
        if (verifiedByPath.keys != plan.targets.mapTo(mutableSetOf()) { it.relativePath }) return false
        return plan.targets.all { target ->
            val audit = verifiedByPath[target.relativePath]?.audit ?: return@all false
            val file = target.finalFile
            if (!file.isFile || file.length() <= 0L) return@all false
            val expectedSize = target.remote.sizeBytes
            if (expectedSize != null && expectedSize > 0L && file.length() != expectedSize) return@all false
            val expectedSha = target.remote.sha256?.takeIf(String::isNotBlank)
            (audit.sourceIdentity == target.remote.sourceIdentity() ||
                (expectedSha != null && expectedSha.equals(audit.observedSha256, ignoreCase = true))) &&
                (expectedSha == null || expectedSha.equals(audit.sourceSha256, ignoreCase = true))
        }
    }



}

internal fun preferredQairtChipsets(device: DeviceProfile): List<String> =
        when (device.accelerationProfile.chipsetCode.trim().uppercase(Locale.US)) {
            "SM8850", "SM8850P" -> listOf(
                "qualcomm-snapdragon-8-elite-gen5",
                "qualcomm-snapdragon-8-elite"
            )
            "SM8750", "SM8750P" -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8650", "SM8650P" -> listOf(
                "qualcomm-snapdragon-8gen3",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8550", "SM8550P" -> listOf(
                "qualcomm-snapdragon-8gen2",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            // Unknown/future devices must never produce an empty admission
            // list. The repository client will prefer an exact key when one
            // exists and otherwise choose a deterministic published fallback.
            else -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
        }


/** A partially known component list is not a known total download size. */
internal fun knownManagedBundleDownloadSize(components: List<RemoteModelFile>): Long {
    if (components.isEmpty() || components.any { (it.sizeBytes ?: 0L) <= 0L }) return 0L
    return components.fold(0L) { total, file ->
        val size = requireNotNull(file.sizeBytes)
        if (total > Long.MAX_VALUE - size) return 0L
        total + size
    }
}
