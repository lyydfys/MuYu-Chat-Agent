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
 * T2B22-T4（b 工位）拆片：downloadRecommendedImageBundle 以 ManagedModelDownloadInstaller
 * 扩展承载；自 ManagedModelDownloadInstaller.kt 逐字节平移，仅登记声明行改写。
 */

internal suspend fun ManagedModelDownloadInstaller.downloadRecommendedImageBundle(model: ModelScopeRecommendedModel): LocalImageModelRecord {
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
