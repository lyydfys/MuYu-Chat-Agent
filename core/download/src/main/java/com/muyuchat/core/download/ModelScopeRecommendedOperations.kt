package com.muyuchat.core.download

import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Recommended-model catalog operations for ModelScopeClient (P6/M5), moved verbatim from its class
 * body: recommended file/bundle resolution for chat, MNN, vision, LiteRT and QAIRT catalog entries.
 * Instance state arrives through the read-only ModelScopeRecommendedCatalogPort (client,
 * huggingFaceEndpoints, listModelFiles/listGgufFiles, pinned integrity); the class keeps thin
 * delegates with the original public signatures, and pinnedQairtImageReleaseAssetIntegrity stays a
 * class member because tests call it on the instance.
 */

internal interface ModelScopeRecommendedCatalogPort {
    val client: OkHttpClient
    val huggingFaceEndpoints: List<String>

    fun listModelFiles(
        input: String,
        revision: String,
        provider: ModelRepositoryProvider,
        extensions: Set<String>
    ): List<RemoteModelFile>

    fun listGgufFiles(
        input: String,
        revision: String,
        provider: ModelRepositoryProvider
    ): List<RemoteModelFile>

    fun pinnedQairtImageReleaseAssetIntegrity(
        modelId: String,
        resolvedName: String,
        recommendedName: String
    ): Pair<Long?, String?>
}

private fun requireDownloadableRecommendation(model: ModelScopeRecommendedModel) {
    require(model.downloadable) {
        model.downloadBlockReason ?: "该推荐模型暂未接入可验证的一键下载链路。"
    }
}

internal fun listRecommendedFilesVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel,
    preferredQairtChipsets: List<String>
): List<RemoteModelFile> {
    requireDownloadableRecommendation(model)
    model.mnnModelBundle?.let { return recommendedMnnBundleFilesVia(port, model) }
    model.visionModelBundle?.let { return recommendedVisionBundleFilesVia(port, model) }
    model.imageEngineBundle?.let {
        return recommendedImageBundleFilesVia(port, model, preferredQairtChipsets)
    }
    if (model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
        return listOf(recommendedQairtChatFileVia(port, model, preferredQairtChipsets))
    }
    if (model.chatRuntime == RecommendedChatRuntime.LITERT_LM) {
        return port.listModelFiles(
            input = model.repoId,
            revision = model.revision,
            provider = model.provider,
            extensions = setOf("litertlm")
        ).sortedWith(
            compareByDescending<RemoteModelFile> {
                it.name.equals(model.recommendedFileName, ignoreCase = true)
            }.thenBy {
                it.sizeBytes ?: Long.MAX_VALUE
            }
        )
    }
    return port.listGgufFiles(model.repoId, model.revision, model.provider).sortedWith(
        compareByDescending<RemoteModelFile> {
            it.name.equals(model.recommendedFileName, ignoreCase = true)
        }.thenByDescending {
            if (model.kind == ModelScopeRecommendedKind.IMAGE) {
                it.isImageModelCandidate()
            } else {
                it.isChatModelCandidate()
            }
        }.thenByDescending {
            it.name.contains(model.quant, ignoreCase = true)
        }.thenBy {
            it.sizeBytes ?: Long.MAX_VALUE
        }
    )
}

internal fun recommendedFileVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel,
    preferredQairtChipsets: List<String>
): RemoteModelFile {
    requireDownloadableRecommendation(model)
    model.mnnModelBundle?.let { bundle ->
        return recommendedMnnBundleFilesVia(port, model).firstOrNull {
            it.mnnBundleRole == MnnModelBundleComponentRole.CONFIG
        } ?: error("推荐 MNN 包 ${bundle.title} 没有配置 config.json。")
    }
    model.visionModelBundle?.let { bundle ->
        return recommendedVisionBundleFilesVia(port, model).firstOrNull {
            it.visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL
        } ?: error("推荐多模态模型包 ${bundle.title} 没有配置主模型。")
    }
    model.imageEngineBundle?.let { bundle ->
        return recommendedImageBundleFilesVia(port, model, preferredQairtChipsets)
            .firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
            ?: error("推荐生图引擎 ${bundle.title} 没有配置 diffusion 主模型。")
    }
    if (model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
        return recommendedQairtChatFileVia(port, model, preferredQairtChipsets)
    }
    val files = listRecommendedFilesVia(port, model, preferredQairtChipsets)
    return selectRecommendedModelFile(model, files)
}

internal fun recommendedMnnBundleFilesVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel
): List<RemoteModelFile> {
    val bundle = model.mnnModelBundle ?: error("${model.title} 没有配置 MNN 模型包。")
    val files = port.listModelFiles(
        input = bundle.repoId,
        revision = bundle.revision,
        provider = bundle.provider,
        extensions = MNN_MODEL_FILE_EXTENSIONS
    )
    return bundle.components.map { component ->
        val match = files.firstOrNull { it.path.equals(component.fileName, ignoreCase = true) } ?:
            files.firstOrNull { it.name.equals(component.fileName.substringAfterLast('/'), ignoreCase = true) }
        if (match == null && component.required) {
            error("MNN 模型包缺少组件：${component.role.label} / ${component.fileName}")
        }
        match?.copy(
            mnnBundleRole = component.role,
            relativePath = component.relativePath
        )
    }.filterNotNull()
}

internal fun recommendedImageBundleFilesVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel,
    preferredQairtChipsets: List<String>
): List<RemoteModelFile> {
    requireDownloadableRecommendation(model)
    val bundle = model.imageEngineBundle ?: error("${model.title} 没有配置图像生成引擎包。")
    if (model.id in QAIRT_IMAGE_RELEASE_ASSET_MODEL_IDS) {
        return listOf(recommendedQairtImageFile(port, model, preferredQairtChipsets)) +
            resolveImageBundleComponents(port, bundle.components.filterNot {
                it.role == ImageEngineBundleComponentRole.DIFFUSION
            })
    }
    return resolveImageBundleComponents(port, bundle.components.filter { it.downloadByDefault })
}

private fun resolveImageBundleComponents(
    port: ModelScopeRecommendedCatalogPort,
    components: List<ImageEngineBundleComponentSpec>
): List<RemoteModelFile> {
    val fileListCache = mutableMapOf<String, List<RemoteModelFile>>()
    return components.map { component ->
        val cacheKey = "${component.provider.name}:${component.repoId}:${component.revision}"
        val files = fileListCache.getOrPut(cacheKey) {
            port.listModelFiles(
                input = component.repoId,
                revision = component.revision,
                provider = component.provider,
                extensions = MODEL_FILE_EXTENSIONS + MNN_MODEL_FILE_EXTENSIONS
            )
        }
        val match = files.firstOrNull { it.path.equals(component.fileName, ignoreCase = true) } ?:
            files.firstOrNull {
                '/' !in component.fileName &&
                    it.name.equals(component.fileName, ignoreCase = true)
            }
        if (match == null && component.required) {
            error("图像生成引擎包缺少组件：${component.role.label} / ${component.fileName}")
        }
        match?.let { remote ->
            component.expectedSizeBytes?.let { expected ->
                remote.sizeBytes?.let { actual ->
                    require(actual == expected) {
                        "图像生成引擎组件大小不匹配：${component.fileName}，期望 $expected，实际 $actual"
                    }
                }
            }
            // Hugging Face returns a 40-character Git blob SHA-1 for
            // ordinary (non-LFS) files.  It is not comparable with the
            // repository-owned SHA-256 contract below.  Only accept a
            // real 64-hex publisher digest as remote SHA-256; otherwise
            // retain the pinned component SHA-256 as the install check.
            val remoteSha256 = normalizedRemoteSha256OrNull(remote.sha256)
            component.sha256?.let { expected ->
                remoteSha256?.let { actual ->
                    require(actual.equals(expected, ignoreCase = true)) {
                        "图像生成引擎组件 SHA-256 不匹配：${component.fileName}，期望 $expected，实际 $actual"
                    }
                }
            }
            remote.copy(
                sizeBytes = remote.sizeBytes ?: component.expectedSizeBytes,
                sha256 = remoteSha256 ?: component.sha256,
                bundleRole = component.role,
                relativePath = component.relativePath
            )
        }
    }.filterNotNull()
}

private fun recommendedQairtImageFile(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel,
    preferredChipsets: List<String>
): RemoteModelFile {
    val huggingFaceEndpoints = port.huggingFaceEndpoints
    val client = port.client
    val safeRevision = model.revision.ifBlank { "main" }
    val errors = mutableListOf<String>()
    for (endpoint in huggingFaceEndpoints) {
        val url = "$endpoint/${model.repoId.urlEncodePath()}/resolve/${safeRevision.urlEncode()}/release_assets.json"
        val file = runCatching {
            client.newCall(modelScopeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val asset = root.selectQnnContextAsset(
                    preferredChipsets = preferredChipsets,
                    catalogTargetChipset = QAIRT_GEN5_IMAGE_RELEASE_ASSET_CHIPSET
                )
                val downloadUrl = preferredHuggingFaceMirrorUrl(asset.downloadUrl, huggingFaceEndpoints)
                val name = downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank {
                    model.recommendedFileName.ifBlank { "${model.id}.zip" }
                }
                val pinnedIntegrity = port.pinnedQairtImageReleaseAssetIntegrity(
                    modelId = model.id,
                    resolvedName = name,
                    recommendedName = model.recommendedFileName
                )
                RemoteModelFile(
                    repoId = model.repoId,
                    revision = safeRevision,
                    path = name,
                    name = name,
                    // The pinned size belongs to the catalog's exact named
                    // archive. A generic/vendor variant of the same target
                    // can differ in length, so do not attach a false size.
                    sizeBytes = pinnedIntegrity.first,
                    sha256 = pinnedIntegrity.second,
                    downloadUrl = downloadUrl,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    bundleRole = ImageEngineBundleComponentRole.DIFFUSION,
                    relativePath = name
                )
            }
        }.onFailure { error ->
            errors += "${endpoint.removePrefix("https://")}: ${error.message}"
        }.getOrNull()
        if (file != null) return file
    }
    error("Qualcomm QNN 生图 release assets 读取失败：${model.repoId}。详情：${errors.joinToString("；")}")
}

internal fun recommendedVisionBundleFilesVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel
): List<RemoteModelFile> {
    val bundle = model.visionModelBundle ?: error("${model.title} 没有配置多模态模型包。")
    val fileListCache = mutableMapOf<String, List<RemoteModelFile>>()
    return bundle.components.map { component ->
        val cacheKey = "${component.provider.name}:${component.repoId}:${component.revision}"
        val files = fileListCache.getOrPut(cacheKey) {
            port.listModelFiles(
                input = component.repoId,
                revision = component.revision,
                provider = component.provider,
                extensions = MODEL_FILE_EXTENSIONS
            )
        }
        val match = files.firstOrNull { it.path.equals(component.fileName, ignoreCase = true) } ?:
            files.firstOrNull { it.name.equals(component.fileName.substringAfterLast('/'), ignoreCase = true) }
        if (match == null && component.required) {
            error("多模态模型包缺少组件：${component.role.label} / ${component.fileName}")
        }
        match?.copy(
            visionBundleRole = component.role,
            relativePath = component.relativePath
        )
    }.filterNotNull()
}

internal fun recommendedQairtChatFileVia(
    port: ModelScopeRecommendedCatalogPort,
    model: ModelScopeRecommendedModel,
    preferredChipsets: List<String>
): RemoteModelFile {
    val huggingFaceEndpoints = port.huggingFaceEndpoints
    val client = port.client
    require(model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
        "${model.title} 不是 GenieX QAIRT 聊天引擎推荐。"
    }
    val safeRevision = model.revision.ifBlank { "main" }
    val errors = mutableListOf<String>()
    for (endpoint in huggingFaceEndpoints) {
        val url = "$endpoint/${model.repoId.urlEncodePath()}/resolve/${safeRevision.urlEncode()}/release_assets.json"
        val file = runCatching {
            client.newCall(modelScopeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val root = JSONObject(response.body?.string().orEmpty())
                val asset = root.selectGenieXQairtAsset(preferredChipsets)
                val downloadUrl = preferredHuggingFaceMirrorUrl(asset.downloadUrl, huggingFaceEndpoints)
                val name = downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank {
                    model.recommendedFileName.ifBlank { "${model.id}.zip" }
                }
                RemoteModelFile(
                    repoId = model.repoId,
                    revision = safeRevision,
                    path = name,
                    name = name,
                    sizeBytes = null,
                    sha256 = null,
                    downloadUrl = downloadUrl,
                    provider = ModelRepositoryProvider.HUGGING_FACE
                )
            }
        }.onFailure { error ->
            errors += "${endpoint.removePrefix("https://")}: ${error.message}"
        }.getOrNull()
        if (file != null) return file
    }
    error("Qualcomm QAIRT release assets 读取失败：${model.repoId}。详情：${errors.joinToString("；")}")
}
