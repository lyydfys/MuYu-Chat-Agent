package com.muyuchat.mca

import com.muyuchat.core.download.*
import java.io.File
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Downloads the pinned v4 bundle from the public MediaTek Anima derivative repo.
 *
 * The small download.json descriptor is verified against the app's embedded
 * SHA-256, every variant is cross-checked against the pinned spec, and only
 * then is the single bundle ZIP downloaded, verified and imported through the
 * existing local image bundle importer.
 */
internal suspend fun ManagedModelDownloadInstaller.downloadRecommendedRemoteImageBundle(
    model: ModelScopeRecommendedModel,
    imageBundleVariantId: String? = null
): LocalImageModelRecord {
    val spec = requireNotNull(model.remoteImageBundle)

    val descriptorRemote = RemoteModelFile(
        repoId = model.repoId,
        revision = model.revision,
        path = "download.json",
        name = "download.json",
        sizeBytes = spec.descriptorBytes,
        sha256 = spec.descriptorSha256,
        license = spec.license,
        downloadUrl = spec.descriptorUrl,
        provider = ModelRepositoryProvider.MODELSCOPE
    )
    val descriptorFinal = File(context.cacheDir, "anima-${managedRemoteIdentity(descriptorRemote)}.download.json")
    val descriptorPart = File(descriptorFinal.parentFile, ".${descriptorFinal.name}.part")
    busy("正在读取 v4 下载清单与非商业许可声明…")
    downloadVerifiedOrResume(descriptorRemote, descriptorPart, descriptorFinal) { snapshot ->
        _uiState.update {
            it.copy(
                phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                downloadFileName = "download.json",
                downloadedBytes = snapshot.downloadedBytes,
                downloadTotalBytes = spec.descriptorBytes,
                downloadSpeedBytesPerSecond = 0L,
                downloadRemainingSeconds = null,
                downloadStatus = DownloadStatus.RUNNING,
                statusMessage = "正在校验 v4 下载清单…"
            )
        }
    }

    val descriptor = runCatching { JSONObject(descriptorFinal.readText()) }
        .getOrElse { error ->
            descriptorFinal.delete()
            throw IllegalStateException("v4 下载清单无法解析：${error.message}", error)
        }
    require(descriptor.optString("schema") == "mca.anima.npug-dla.download/1") {
        "v4 下载清单 schema 不受支持。"
    }
    require(descriptor.optString("repository") == model.repoId) {
        "v4 下载清单仓库与推荐卡片不一致。"
    }
    val descriptorVariants = descriptor.optJSONArray("variants") ?: error("v4 下载清单缺少 variants。")
    val remoteById = buildMap {
        for (index in 0 until descriptorVariants.length()) {
            val item = descriptorVariants.getJSONObject(index)
            put(item.getString("id"), item)
        }
    }
    spec.variants.forEach { expected ->
        val remote = remoteById[expected.id] ?: error("v4 下载清单缺少变体 ${expected.id}。")
        require(remote.getString("url") == expected.url) { "v4 下载清单 ${expected.id} URL 与应用内 pin 不一致。" }
        require(remote.getLong("bytes") == expected.sizeBytes) { "v4 下载清单 ${expected.id} 大小与应用内 pin 不一致。" }
        require(remote.getString("sha256") == expected.sha256) { "v4 下载清单 ${expected.id} SHA-256 与应用内 pin 不一致。" }
        require(remote.optString("file") == expected.file) { "v4 下载清单 ${expected.id} 文件与应用内 pin 不一致。" }
    }
    descriptorFinal.delete()

    val selected = spec.variant(imageBundleVariantId ?: spec.defaultVariantId)
    val bundleRemote = RemoteModelFile(
        repoId = model.repoId,
        revision = model.revision,
        path = selected.file,
        name = selected.file.substringAfterLast('/'),
        sizeBytes = selected.sizeBytes,
        sha256 = selected.sha256,
        license = spec.license,
        downloadUrl = selected.url,
        provider = ModelRepositoryProvider.MODELSCOPE
    )
    val bundleDir = localImageModelStore.managedBundleDirFor("remote-${managedRemoteIdentity(bundleRemote)}")
    val finalFile = localImageModelStore.managedBundleFileFor(bundleDir, bundleRemote.name)
    val partFile = File(finalFile.parentFile, ".${managedRemoteIdentity(bundleRemote)}.part")
    val totalBytes = selected.sizeBytes
    busy("正在下载 ${model.title}（${selected.id}）…")
    downloadVerifiedOrResume(bundleRemote, partFile, finalFile) { snapshot ->
        _uiState.update {
            it.copy(
                phase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING,
                downloadFileName = snapshot.fileName,
                downloadedBytes = snapshot.downloadedBytes,
                downloadTotalBytes = totalBytes,
                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                downloadRemainingSeconds = snapshot.remainingSeconds,
                downloadStatus = if (snapshot.status == DownloadStatus.DONE) DownloadStatus.RUNNING else snapshot.status,
                statusMessage = snapshot.errorMessage ?: "正在下载 v4 bundle（${selected.id}）"
            )
        }
    }
    busy("下载完成，正在校验并导入本地生图模型…")
    integrityPassed("v4 bundle ${selected.id} 已通过大小和 SHA-256 校验。")
    val installed = localImageModelStore.registerDownloadedModel(finalFile, bundleRemote)
    executionResult("PENDING", "已导入；首次加载时由 Neuron 运行时确认本机兼容性。")
    return installed
}
