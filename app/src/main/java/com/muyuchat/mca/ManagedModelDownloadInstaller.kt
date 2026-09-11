package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.download.*
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class ManagedDownloadProgress(
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus = DownloadStatus.RUNNING,
    val statusMessage: String = "正在准备下载…"
)

internal data class InstalledManagedDownload(
    val modelId: String? = null,
    val imageId: String? = null,
    val projector: Boolean = false,
    val message: String
)

internal class ManagedModelDownloadInstaller(private val context: Context, private val projectorTargetId: String?) {
    private val modelStore = ModelStoreRepository(context)
    private val localImageModelStore = LocalImageModelStore(context)
    private val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    private val device by lazy { DeviceProfileReader(context).read() }
    private val _uiState = MutableStateFlow(ManagedDownloadProgress())
    val progress get() = _uiState
    private fun busy(message: String) { _uiState.update { it.copy(statusMessage = message) } }
    private fun formatBytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    suspend fun install(request: ManagedDownloadRequest): InstalledManagedDownload {
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
                    return InstalledManagedDownload(modelId = model.id, message = "已下载并导入：${model.displayName}")
                }
                recommendation.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT -> {
                    val model = downloadRecommendedQairtChatBundle(recommendation)
                    return InstalledManagedDownload(modelId = model.id, message = "已下载并导入：${model.displayName}")
                }
                recommendation.visionModelBundle != null -> {
                    return when (val result = downloadRecommendedVisionBundle(recommendation)) {
                        is VisionBundleDownloadResult.ChatModel -> InstalledManagedDownload(modelId = result.model.id,
                            message = "已导入多模态模型并绑定视觉投影器：${result.model.displayName}")
                        is VisionBundleDownloadResult.EngineBundle -> InstalledManagedDownload(message = "已导入：${result.displayName}。${result.report.message}")
                    }
                }
                recommendation.imageEngineBundle != null -> {
                    val image = downloadRecommendedImageBundle(recommendation)
                    return InstalledManagedDownload(imageId = image.id, message = "已下载并导入生图模型：${image.displayName}")
                }
            }
        }
        val remote = request.remote ?: modelScopeClient.recommendedFile(requireNotNull(recommendation))
        return when (val result = download(remote)) {
            is DownloadedModelRegistration.Chat -> InstalledManagedDownload(modelId = result.model.id, message = "已下载并导入：${result.model.displayName}")
            is DownloadedModelRegistration.Image -> InstalledManagedDownload(imageId = result.model.id, message = "已下载并导入生图模型：${result.model.displayName}")
            is DownloadedModelRegistration.VisionProjector -> InstalledManagedDownload(modelId = result.model.id, projector = true, message = "已下载并绑定视觉投影器")
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
                val bundleId = remote.name.removeSuffix(".zip").ifBlank { model.id }
                val bundleDir = modelStore.managedBundleDirFor(bundleId)
                val archiveDir = File(bundleDir.parentFile, ".${bundleDir.name}.archive").also { it.mkdirs() }
                val finalZip = File(archiveDir, remote.name)
                val candidateDir = File(bundleDir.parentFile, ".${bundleDir.name}.candidate")
                val tempFile = File(finalZip.parentFile, ".${managedRemoteIdentity(remote)}.part")
                downloadVerifiedOrResume(remote, tempFile, finalZip) { snapshot ->
                    _uiState.update {
                        it.copy(
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
                extractResumableModelZip(finalZip, candidateDir)
                modelStore.resolveQairtBundleRoot(candidateDir)
                val backup = promoteImageBundleCandidate(candidateDir, bundleDir)
                try {
                    val qairtBundleRoot = modelStore.resolveQairtBundleRoot(bundleDir)
                    val registered = modelStore.registerDownloadedQairtBundle(
                        displayName = model.title, bundleDir = qairtBundleRoot,
                        repoId = model.repoId, revision = model.revision,
                        source = remote.provider.toModelSource(), quant = model.quant,
                        architecture = recommendedQairtArchitecture(model))
                    backup?.deleteRecursively()
                    finalZip.delete()
                    registered
                } catch (error: Exception) {
                    restoreImageBundleBackup(bundleDir, backup)
                    throw error
                }
        }
    }

    private suspend fun downloadRecommendedMnnBundle(model: ModelScopeRecommendedModel): ModelManifest {
        return run {
                val bundle = requireNotNull(model.mnnModelBundle)
                val components = modelScopeClient.recommendedMnnBundleFiles(model)
                val config = components.firstOrNull {
                    it.mnnBundleRole == MnnModelBundleComponentRole.CONFIG
                } ?: error("MNN 模型包缺少 config.json。")
                val bundleDir = modelStore.managedBundleDirFor(bundle.id)
                val installer = ModelBundleInstaller(
                    BundleComponentDownloader { remote, tempFile, stagedFile, onProgress ->
                        downloader.download(remote, tempFile, stagedFile, onProgress)
                    }
                )
                val plan = installer.plan(bundleDir, components)
                val knownTotalBytes = components.sumOf { remote -> remote.sizeBytes ?: 0L }
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
                            downloadFileName = progressPath,
                            downloadedBytes = downloadedBytesByPath.values.sum(),
                            downloadTotalBytes = knownTotalBytes.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                            statusMessage = "正在下载 MNN 组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                        )
                    }
                }
                busy("下载完成，正在校验并自动导入本地模型…")
                require(installer.verifyInstalledBundle(installed.bundleRoot).isVerified) {
                    "MNN 模型包安装后的组件校验失败，请重新下载。"
                }
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
                val bundleDir = modelStore.managedBundleDirFor(bundle.id)
                val targets = components.map { remote ->
                    remote to modelStore.managedBundleFileFor(bundleDir, remote.path)
                }
                val bytesToDownload = targets.sumOf { (remote, _) -> remote.sizeBytes ?: 0L }
                var completedBytes = 0L
                targets.forEachIndexed { index, (remote, finalFile) ->
                    val tempFile = File(finalFile.parentFile, ".${managedRemoteIdentity(remote)}.part")
                    val completedBefore = completedBytes
                    downloadVerifiedOrResume(remote, tempFile, finalFile) { snapshot ->
                        _uiState.update {
                            it.copy(
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
                val primaryFile = targets.firstOrNull { it.first == primary }?.second
                    ?: error("多模态模型包主模型下载目标不存在。")
                writeDownloadedVisionBundleManifest(
                    displayName = model.title,
                    bundleDir = bundleDir,
                    bundle = bundle,
                    targets = targets
                )
                if (bundle.runtime == VisionModelBundleRuntime.GGUF_MMPROJ) {
                    val projectorRemote = projector ?: error("多模态模型包缺少 mmproj / projector。")
                    val projectorFile = targets.firstOrNull { it.first == projectorRemote }?.second
                        ?: error("多模态模型包 projector 下载目标不存在。")
                    val registered = modelStore.registerDownloadedModel(
                        file = primaryFile,
                        repoId = primary.repoId,
                        revision = primary.revision,
                        license = primary.license,
                        source = primary.provider.toModelSource()
                    )
                    VisionBundleDownloadResult.ChatModel(
                        modelStore.attachVisionProjectorFile(registered.id, projectorFile, projectorRemote.name)
                    )
                } else {
                    val report = LiteRtQnnVisionRunner(
                        context = context
                    ).health(
                        device = device,
                        bundleRoot = bundleDir
                    )
                    VisionBundleDownloadResult.EngineBundle(
                        displayName = model.title,
                        bundleDir = bundleDir,
                        report = report
                    )
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
                val bundleDir = localImageModelStore.managedBundleDirFor(bundle.id)
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
                val knownTotalBytes = components.sumOf { it.sizeBytes ?: 0L }
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
                                downloadFileName = progressPath,
                                downloadedBytes = downloadedBytesByPath.values.sum(),
                                downloadTotalBytes = knownTotalBytes.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                                downloadRemainingSeconds = snapshot.remainingSeconds,
                                downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                                statusMessage = "正在下载生图组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                            )
                        }
                    }
                }
                busy("下载完成，正在校验组件并自动导入本地生图模型…")
                var promoted = false
                var previousBundleBackup: File? = null
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
                    previousBundleBackup = promoteImageBundleCandidate(candidateDir, bundleDir)
                    promoted = true
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
                    previousBundleBackup?.deleteRecursively()
                    savedArchive.delete()
                    registered
                } catch (error: Throwable) {
                    if (promoted) {
                        restoreImageBundleBackup(bundleDir, previousBundleBackup)
                    }
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
                if (imageModel) {
                    DownloadedModelRegistration.Image(localImageModelStore.registerDownloadedModel(finalFile, remote))
                } else if (remoteKind == RemoteModelFileKind.PROJECTOR && targetVisionModel != null) {
                    DownloadedModelRegistration.VisionProjector(
                        model = modelStore.attachVisionProjectorFile(targetVisionModel.id, finalFile, remote.name),
                        shouldReload = false
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
            expectedSha == null || expectedSha.equals(audit.sourceSha256, ignoreCase = true)
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

