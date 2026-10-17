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

/**
 * T2B22-T4（b 工位）拆片：downloadRecommendedQairtChatBundle / downloadRecommendedMnnBundle
 * 以 ManagedModelDownloadInstaller 扩展承载；自 ManagedModelDownloadInstaller.kt 逐字节平移，
 * 仅登记声明行改写（private suspend fun -> internal suspend fun <接收者>.）。
 */

internal suspend fun ManagedModelDownloadInstaller.downloadRecommendedQairtChatBundle(model: ModelScopeRecommendedModel): ModelManifest {
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

internal suspend fun ManagedModelDownloadInstaller.downloadRecommendedMnnBundle(model: ModelScopeRecommendedModel): ModelManifest {
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
