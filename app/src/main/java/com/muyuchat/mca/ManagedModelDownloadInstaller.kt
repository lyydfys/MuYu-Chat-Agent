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
    private fun busy(message: String) { _uiState.update { it.copy(statusMessage = message) } }
    private fun integrityPassed(message: String = "所有下载组件的大小和 SHA-256 与目录声明一致。") {
        _uiState.update { it.copy(integrityStatus = "PASSED", integrityMessage = message) }
    }
    private fun executionResult(status: String, message: String) {
        _uiState.update { it.copy(executionStatus = status, executionMessage = message) }
    }
    /** Publish a terminal installer failure without hiding a previously proven file check. */
    internal fun markFailure(error: Throwable) {
        val message = error.message?.takeIf(String::isNotBlank) ?: "下载或安装失败。"
        _uiState.update {
            it.copy(
                integrityStatus = when (it.integrityStatus) {
                    "PASSED" -> "PASSED"
                    else -> "FAILED"
                },
                integrityMessage = when (it.integrityStatus) {
                    "PASSED" -> it.integrityMessage
                    else -> message
                },
                executionStatus = "FAILED",
                executionMessage = "安装阶段未完成：$message",
                statusMessage = "下载或安装失败：$message"
            )
        }
    }
    private fun formatBytes(bytes: Long) = "%.1f MB".format(bytes / 1048576.0)

    private data class QnnBundlePreflight(val status: String, val message: String)

    private data class QnnPackageMetadata(
        val socModel: Int? = null,
        val socVersion: String? = null,
        val htpArch: Int? = null,
        val qnnSdk: String? = null,
        val source: String = ""
    )

    private data class QnnContextDiagnostic(
        val text: String,
        val metadataParsed: Boolean,
        val packageMetadata: QnnPackageMetadata? = null,
        val target: QnnContextTargetIdentity? = null
    )

    private fun readQnnPackageMetadata(bundleDir: File): QnnPackageMetadata? {
        val candidates = bundleDir.walkTopDown()
            .filter { file ->
                file.isFile && file.name.lowercase() in setOf(
                    "metadata.json", "qnn_metadata.json", "context.json",
                    "qnn_context.json", "runtime.json", "release_assets.json"
                )
            }
            .take(24)
            .toList()
        for (file in candidates) {
            val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
            val objects = buildList {
                fun collect(value: Any?, depth: Int) {
                    if (depth > 4) return
                    when (value) {
                        is JSONObject -> {
                            add(value)
                            val keys = value.keys()
                            while (keys.hasNext()) collect(value.opt(keys.next()), depth + 1)
                        }
                        is org.json.JSONArray -> {
                            for (index in 0 until value.length()) collect(value.opt(index), depth + 1)
                        }
                    }
                }
                collect(root, 0)
            }
            fun firstInt(vararg keys: String): Int? = objects.asSequence()
                .flatMap { objectValue -> keys.asSequence().map { key -> objectValue.optInt(key, 0) } }
                .firstOrNull { it > 0 }
            fun firstText(vararg keys: String): String? = objects.asSequence()
                .flatMap { objectValue -> keys.asSequence().map { key -> objectValue.optString(key) } }
                .map { it.trim() }
                .firstOrNull(String::isNotBlank)
            val socModel = firstInt("soc_model", "socModel", "target_soc", "targetSoc")
            val htpArch = firstInt("htp_version", "htpVersion", "htp_arch", "htpArch", "htp_arch_version")
            val qnnSdk = firstText("qnnSdk", "qnn_sdk", "qairt", "qnn", "sdkVersion", "sdk")
            val socVersion = firstText("socVersion", "soc_version", "marketing_name", "chipset", "name")
            if (socModel != null || htpArch != null || qnnSdk != null || socVersion != null) {
                return QnnPackageMetadata(
                    socModel = socModel,
                    socVersion = socVersion,
                    htpArch = htpArch,
                    qnnSdk = qnnSdk,
                    source = file.relativeTo(bundleDir).invariantSeparatorsPath
                )
            }
        }
        return null
    }

    /** Metadata-only check: it never creates a QNN device, context, or graph. */
    private fun inspectQnnBundleCompatibility(
        bundleDir: File,
        installedBundle: ImageEngineBundleSpec
    ): QnnBundlePreflight {
        val profile = device.accelerationProfile
        val currentChipset = profile.chipsetCode.ifBlank { device.socModel }
        val currentSocModel = DeviceAccelerationAnalyzer.expectedQnnSocModelForChipsetCode(currentChipset)
        val deviceArch = profile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
            ?: DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(
                currentChipset
            )
        val expectedProfile = installedBundle.requiredRuntimeProfile
        val contextSpecs = installedBundle.qnnSmokeSpecs
        val runtimeStatus = profile.qnnRuntime
        val runtimeIssues = buildList {
            if (!runtimeStatus.qnnSystemLibraryPresent) add("缺少 libQnnSystem.so")
            if (!runtimeStatus.qnnHtpLibraryPresent) add("缺少 libQnnHtp.so")
            if (!runtimeStatus.htpSkelLibraryPresent) {
                val arch = deviceArch?.toString()?.let { "V${it}" } ?: "V*"
                add("缺少 libQnnHtp${arch}Skel.so")
            }
            if (!runtimeStatus.htpStubLibraryPresent) {
                add("缺少 QNN HTP stub transport（libQnnHtpV*Stub.so）")
            }
            if (runtimeStatus.probeState.name == "LOAD_FAILED") {
                runtimeStatus.probeMessage.takeIf { it.isNotBlank() }
                    ?.let { add("QNN runtime 加载失败：$it") }
            }
            if (runtimeStatus.ready && !runtimeStatus.exactArchMatch) {
                add("QNN runtime HTP V${runtimeStatus.htpArchVersion} 与设备要求 HTP V${runtimeStatus.preferredHtpArchVersion} 不同")
            }
        }
        if (contextSpecs.isEmpty()) {
            android.util.Log.i("McaModelDownload", "QNN metadata unavailable: ${installedBundle.id} has no context specifications")
            return QnnBundlePreflight(
                "EXPERIMENTAL",
                buildString {
                    append("包未声明可检查的 QNN context/目标 SoC/HTP 信息；安装不会被设备型号拦截，首次真实加载决定结果。")
                    expectedProfile?.let { append(" 声明 SDK=${it.qnnSdk ?: "未知"}、HTP V${it.htpArch ?: "未知"}。") }
                    if (runtimeIssues.isNotEmpty()) append(" 本机运行时：${runtimeIssues.joinToString("；")}。")
                }
            )
        }
        val packageMetadata = readQnnPackageMetadata(bundleDir)
        val observedContextArchs = mutableSetOf<Int>()
        val observedContextSocModels = mutableSetOf<Int>()
        val observedContextSocVersions = mutableSetOf<String>()
        val observedContextSdkVersions = mutableSetOf<String>()
        packageMetadata?.socModel?.let(observedContextSocModels::add)
        packageMetadata?.htpArch?.let(observedContextArchs::add)
        packageMetadata?.socVersion?.let(observedContextSocVersions::add)
        packageMetadata?.qnnSdk?.let(observedContextSdkVersions::add)

        val diagnostics = contextSpecs.map { spec ->
            val relative = resolvedDownloadedQnnContextPath(spec.contextBinary, emptyList())
            val direct = File(bundleDir, relative).canonicalFile.takeIf {
                it.path.startsWith(bundleDir.canonicalPath + File.separator)
            }
            val file = direct?.takeIf(File::isFile) ?: bundleDir.walkTopDown()
                .firstOrNull { it.isFile && it.name.equals(spec.contextBinary.substringAfterLast('/'), ignoreCase = true) }
                ?: return@map QnnContextDiagnostic(
                    text = "${spec.contextBinary}: context 文件缺失",
                    metadataParsed = false
                )
            if (file.length() <= 0L) return@map QnnContextDiagnostic(
                text = "${spec.contextBinary}: context 文件为空",
                metadataParsed = false
            )
            val json = runCatching {
                NativeQnnBridge().inspectContextMetadata(
                    file.absolutePath,
                    org.json.JSONArray(qnnRuntimeDirectoriesFor(context, bundleDir)).toString()
                )
            }.getOrElse { error -> return@map QnnContextDiagnostic(
                text = "${spec.contextBinary}: 预检不可用（${error.message ?: "native bridge unavailable"}）",
                metadataParsed = false
            ) }
            val root = JSONObject(json)
            val runtime = root.optJSONObject("runtime")
            val metadata = root.optJSONObject("binaryMetadata")
            val parsed = metadata?.optBoolean("parsed", false) == true
            val socModel = metadata?.optInt("socModel", 0)?.takeIf { it > 0 }
            val socVersion = metadata?.optString("socVersion").orEmpty().trim().takeIf { it.isNotBlank() }
            val build = metadata?.optString("buildId").orEmpty()
            val runtimeArch = runtime?.optInt("htpArchVersion", 0)?.takeIf { it > 0 }
            val arch = socModel?.let(QnnRuntimeProfileSelector::htpArchVersionForSocModel) ?: runtimeArch
            val sdk = qnnSdkVersionFromContextBuildId(build)
            socModel?.let(observedContextSocModels::add)
            socVersion?.let(observedContextSocVersions::add)
            arch?.let(observedContextArchs::add)
            sdk?.let(observedContextSdkVersions::add)
            val target = if (parsed) {
                qnnContextTargetIdentity(
                    socModel = socModel ?: 0,
                    socVersion = socVersion.orEmpty(),
                    buildId = build,
                    htpArch = arch
                )
            } else null
            QnnContextDiagnostic(
                text = buildString {
                append(spec.contextBinary).append(": ")
                if (!parsed) {
                    append("context 元数据无法读取：").append(metadata?.optString("message").orEmpty())
                } else {
                    append("目标 SoC=").append(socModel ?: "未知")
                    arch?.let { append("/HTP V").append(it) }
                    sdk?.let { append("，context SDK=").append(it) }
                    metadata.optString("coreApiVersion").takeIf { it.isNotBlank() }?.let { append("，QNN core API=").append(it) }
                    metadata.optString("backendApiVersion").takeIf { it.isNotBlank() }?.let { append("，backend API=").append(it) }
                    runtime?.optJSONObject("compile")?.let { compile ->
                        append("，APK SDK headers=").append(if (compile.optBoolean("sdkHeadersPresent", false)) "有" else "无")
                    }
                    runtimeArch?.let { append("，context/runtime HTP V").append(it) }
                    if (deviceArch != null && arch != null && deviceArch != arch) {
                        append("；当前设备 HTP V").append(deviceArch).append(" 与 context 不同")
                    }
                    if (build.isNotBlank()) append("，buildId=").append(build)
                }
                },
                metadataParsed = parsed,
                packageMetadata = packageMetadata,
                target = target
            )
        }
        val text = diagnostics.joinToString("；")
        val targetSocMismatch = currentSocModel != null && observedContextSocModels.any { it != currentSocModel }
        val targetArchMismatch = deviceArch != null && when {
            observedContextArchs.isNotEmpty() -> observedContextArchs.any { it != deviceArch }
            else -> expectedProfile?.htpArch?.let { it != deviceArch } == true
        }
        val currentSocLabel = currentSocModel?.toString() ?: "未知"
        val deviceArchLabel = deviceArch?.toString() ?: "未知"
        val declaredSdkMismatch = expectedProfile?.qnnSdk?.let { declared ->
            observedContextSdkVersions.any { observed ->
                qnnContextSdkMatchesDeclared(observed, declared) == false
            }
        } == true
        val metadataUnavailable = diagnostics.any { diagnostic ->
            !diagnostic.metadataParsed ||
                (diagnostic.target?.socModel == null && packageMetadata?.socModel == null) ||
                (diagnostic.target?.sdkVersion == null && packageMetadata?.qnnSdk.isNullOrBlank())
        } || (packageMetadata == null && diagnostics.isNotEmpty())
        val missingContextFiles = diagnostics.any { diagnostic ->
            diagnostic.text.contains("文件缺失") || diagnostic.text.contains("文件为空")
        }
        val preflightUnavailable = diagnostics.any { diagnostic ->
            diagnostic.text.contains("预检不可用") || diagnostic.text.contains("元数据无法读取")
        }
        val mismatchReasons = buildList {
            if (targetSocMismatch) {
                add("目标 SoC ${observedContextSocModels.joinToString()} 与当前 SoC $currentSocLabel 不同")
            }
            if (targetArchMismatch) {
                add("目标 HTP V${observedContextArchs.joinToString("/V")} 与当前 HTP V$deviceArchLabel 不同")
            }
            if (declaredSdkMismatch) {
                add("context SDK ${observedContextSdkVersions.joinToString()} 与声明 SDK ${expectedProfile.qnnSdk} 不同")
            }
        }
        val reasons = mismatchReasons + listOfNotNull(
            "QNN context 的 SoC/HTP/SDK 元数据不完整".takeIf { metadataUnavailable }
        )
        return if (missingContextFiles || preflightUnavailable || reasons.isNotEmpty() || runtimeIssues.isNotEmpty()) {
            val profileText = expectedProfile?.let { "包声明 QNN ${it.qnnSdk} / HTP V${it.htpArch}" } ?: "包未声明 QNN SDK/HTP"
            val reasonText = reasons.joinToString("；").ifBlank { "无法完整读取 context 元数据" }
            val runtimeText = runtimeIssues.takeIf { it.isNotEmpty() }?.joinToString("；")
            android.util.Log.i("McaModelDownload", "QNN preflight ${installedBundle.id}: $profileText; $reasonText; runtime=${runtimeText ?: "ok"}; $text")
            val userMessage = buildString {
                when {
                    missingContextFiles -> append("QNN context 文件缺失或为空（文件完整性与运行包兼容性分开报告），请重新校验或重新下载。")
                    mismatchReasons.isNotEmpty() -> append(mismatchReasons.joinToString("；")).append("。")
                    preflightUnavailable -> append("本机暂时无法读取 QNN context 元数据；这不是文件损坏，首次真实加载仍会给出最终结果。")
                    metadataUnavailable -> append("QNN context 的目标 SoC/HTP/SDK 元数据不完整，暂不能确认精确兼容性。")
                }
                runtimeText?.let {
                    if (isNotEmpty()) append(" ")
                    append("本机 QNN 运行时：").append(it).append("。")
                }
                if (isNotEmpty() && text.isNotBlank()) append(" 诊断：").append(text)
                if (isEmpty()) append("安装后由真实 QNN graph smoke 决定能否执行；未知设备仍保留通用加载路径。")
            }
            QnnBundlePreflight(
                if (missingContextFiles) "FAILED" else "EXPERIMENTAL",
                userMessage
            )
        } else {
            val deviceText = deviceArch?.let { "当前设备 HTP V$it" } ?: "当前设备 HTP 未知"
            android.util.Log.i("McaModelDownload", "QNN preflight ${installedBundle.id}: $deviceText; $text")
            QnnBundlePreflight(
                "PREFLIGHT_PASSED",
                "运行包信息检查完成，可在本地页选择模型。"
            )
        }
    }

    suspend fun install(request: ManagedDownloadRequest): InstalledManagedDownload {
        _uiState.value = ManagedDownloadProgress(
            downloadStatus = DownloadStatus.RUNNING,
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
                integrityPassed("QNN 聊天引擎压缩包已通过大小和 SHA-256 校验。")
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
                val bundleDir = modelStore.managedBundleDirFor(bundle.id)
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
                val backup = promoteImageBundleCandidate(candidateDir, bundleDir)
                try {
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
                    backup?.deleteRecursively()
                    installedResult
                } catch (error: Throwable) {
                    restoreImageBundleBackup(bundleDir, backup)
                    throw error
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
                integrityPassed()
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
                    previousBundleBackup?.deleteRecursively()
                    savedArchive.delete()
                    annotated
                } catch (error: Throwable) {
                    markFailure(error)
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

