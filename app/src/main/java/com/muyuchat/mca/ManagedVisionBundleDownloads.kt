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
 * T2B22-T4（b 工位）拆片：downloadRecommendedVisionBundle 以 ManagedModelDownloadInstaller
 * 扩展承载；自 ManagedModelDownloadInstaller.kt 逐字节平移，仅登记声明行改写。
 */

internal suspend fun ManagedModelDownloadInstaller.downloadRecommendedVisionBundle(model: ModelScopeRecommendedModel): VisionBundleDownloadResult {
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
