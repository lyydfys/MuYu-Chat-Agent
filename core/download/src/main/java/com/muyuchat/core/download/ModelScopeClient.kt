package com.muyuchat.core.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import kotlin.math.abs

internal fun normalizedRemoteSha256OrNull(value: String?): String? =
    value?.trim()?.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }

internal fun stableDiffusionCppUsesCfg(cfgScale: Double): Boolean =
    abs(cfgScale - 1.0) > 1e-12

class ModelScopeClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val huggingFaceEndpoints: List<String> = DEFAULT_HUGGING_FACE_ENDPOINTS
) {
    fun parseRepoId(input: String): String = parseRepoId(input, ModelRepositoryProvider.MODELSCOPE)

    private fun parseRepoId(input: String, provider: ModelRepositoryProvider): String {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "请输入模型 ID 或模型页 URL。" }
        val rawSegments = if (trimmed.startsWith("http", ignoreCase = true)) {
            val uri = URI(trimmed)
            val pathSegments = uri.rawPath
                .orEmpty()
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() }
            if (provider == ModelRepositoryProvider.HUGGING_FACE || uri.host.orEmpty().isHuggingFaceHost()) {
                pathSegments
            } else {
                val modelsIndex = pathSegments.indexOfFirst { it.equals("models", ignoreCase = true) }
                require(modelsIndex >= 0) { "无法从 URL 解析 ModelScope 模型 ID。" }
                pathSegments.drop(modelsIndex + 1)
            }
        } else {
            trimmed
                .substringBefore("?")
                .substringBefore("#")
                .trim('/')
                .removePrefix("models/")
                .split('/')
                .filter { it.isNotBlank() }
        }
        require(rawSegments.size >= 2) { "模型 ID 应为 owner/name，例如 lmstudio-community/Qwen3.5-4B-GGUF。" }
        return rawSegments
            .take(2)
            .joinToString("/") { URLDecoder.decode(it, "UTF-8") }
    }

    fun listGgufFiles(input: String, revision: String = "master"): List<RemoteModelFile> {
        return listGgufFiles(input, revision, ModelRepositoryProvider.MODELSCOPE)
    }

    fun listGgufFiles(
        input: String,
        revision: String = "master",
        provider: ModelRepositoryProvider
    ): List<RemoteModelFile> {
        return listModelFiles(input, revision, provider, setOf("gguf"))
    }

    /**
     * Lists official LiteRT-LM containers without mixing them with GGUF files.
     * LiteRT-LM is a distinct container/runtime, so callers should not infer
     * compatibility from a repository name or from a `.gguf` fallback.
     */
    fun listLiteRtLmFiles(
        input: String,
        revision: String = "main",
        provider: ModelRepositoryProvider = ModelRepositoryProvider.HUGGING_FACE
    ): List<RemoteModelFile> = listModelFiles(input, revision, provider, setOf("litertlm"))

    fun listEngineFiles(
        input: String,
        revision: String = "master",
        provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE
    ): List<RemoteModelFile> {
        val repoId = parseRepoId(input, provider)
        val extensions = if ("mnn" in repoId.lowercase()) {
            MODEL_FILE_EXTENSIONS + MNN_MODEL_FILE_EXTENSIONS
        } else {
            // Keep the generic repository browser focused on executable chat
            // containers. LiteRT-LM is intentionally listed beside GGUF,
            // rather than hidden behind a device/profile allowlist.
            setOf("gguf", "litertlm")
        }
        return listModelFiles(repoId, revision, provider, extensions)
    }

    fun listModelFiles(
        input: String,
        revision: String = "master",
        provider: ModelRepositoryProvider,
        extensions: Set<String> = MODEL_FILE_EXTENSIONS
    ): List<RemoteModelFile> {
        val repoId = parseRepoId(input, provider)
        return when (provider) {
            ModelRepositoryProvider.MODELSCOPE -> listModelScopeFiles(repoId, revision, extensions)
            ModelRepositoryProvider.HUGGING_FACE -> listHuggingFaceFiles(repoId, revision, extensions)
        }
    }

    private val modelScopeNetworkPort: ModelScopeNetworkPort =
        object : ModelScopeNetworkPort {
            override val client get() = this@ModelScopeClient.client
            override val endpoints get() = this@ModelScopeClient.endpoints
            override val huggingFaceEndpoints get() = this@ModelScopeClient.huggingFaceEndpoints
        }

    private fun listModelScopeFiles(repoId: String, revision: String, extensions: Set<String>): List<RemoteModelFile> =
        listModelScopeFilesVia(modelScopeNetworkPort, repoId, revision, extensions)

    private fun listHuggingFaceFiles(repoId: String, revision: String, extensions: Set<String>): List<RemoteModelFile> =
        listHuggingFaceFilesVia(modelScopeNetworkPort, repoId, revision, extensions)

    fun recommendedModels(): List<ModelScopeRecommendedModel> = DEFAULT_RECOMMENDED_MODELS

    fun userFacingRecommendedModels(): List<ModelScopeRecommendedModel> =
        DEFAULT_RECOMMENDED_MODELS.filter { it.visibleInRecommendations }

    private fun requireDownloadableRecommendation(model: ModelScopeRecommendedModel) {
        require(model.downloadable) {
            model.downloadBlockReason ?: "该推荐模型暂未接入可验证的一键下载链路。"
        }
    }

    fun listRecommendedFiles(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): List<RemoteModelFile> {
        requireDownloadableRecommendation(model)
        model.mnnModelBundle?.let { return recommendedMnnBundleFiles(model) }
        model.visionModelBundle?.let { return recommendedVisionBundleFiles(model) }
        model.imageEngineBundle?.let {
            return recommendedImageBundleFiles(model, preferredQairtChipsets)
        }
        if (model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
            return listOf(recommendedQairtChatFile(model, preferredQairtChipsets))
        }
        if (model.chatRuntime == RecommendedChatRuntime.LITERT_LM) {
            return listModelFiles(
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
        return listGgufFiles(model.repoId, model.revision, model.provider).sortedWith(
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

    fun recommendedFile(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): RemoteModelFile {
        requireDownloadableRecommendation(model)
        model.mnnModelBundle?.let { bundle ->
            return recommendedMnnBundleFiles(model).firstOrNull {
                it.mnnBundleRole == MnnModelBundleComponentRole.CONFIG
            } ?: error("推荐 MNN 包 ${bundle.title} 没有配置 config.json。")
        }
        model.visionModelBundle?.let { bundle ->
            return recommendedVisionBundleFiles(model).firstOrNull {
                it.visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL
            } ?: error("推荐多模态模型包 ${bundle.title} 没有配置主模型。")
        }
        model.imageEngineBundle?.let { bundle ->
            return recommendedImageBundleFiles(model, preferredQairtChipsets)
                .firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
                ?: error("推荐生图引擎 ${bundle.title} 没有配置 diffusion 主模型。")
        }
        if (model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
            return recommendedQairtChatFile(model, preferredQairtChipsets)
        }
        val files = listRecommendedFiles(model, preferredQairtChipsets)
        return selectRecommendedModelFile(model, files)
    }

    fun recommendedMnnBundleFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> {
        val bundle = model.mnnModelBundle ?: error("${model.title} 没有配置 MNN 模型包。")
        val files = listModelFiles(
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

    fun recommendedImageBundleFiles(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): List<RemoteModelFile> {
        requireDownloadableRecommendation(model)
        val bundle = model.imageEngineBundle ?: error("${model.title} 没有配置图像生成引擎包。")
        if (model.id in QAIRT_IMAGE_RELEASE_ASSET_MODEL_IDS) {
            return listOf(recommendedQairtImageFile(model, preferredQairtChipsets)) +
                resolveImageBundleComponents(bundle.components.filterNot {
                    it.role == ImageEngineBundleComponentRole.DIFFUSION
                })
        }
        return resolveImageBundleComponents(bundle.components.filter { it.downloadByDefault })
    }

    private fun resolveImageBundleComponents(
        components: List<ImageEngineBundleComponentSpec>
    ): List<RemoteModelFile> {
        val fileListCache = mutableMapOf<String, List<RemoteModelFile>>()
        return components.map { component ->
            val cacheKey = "${component.provider.name}:${component.repoId}:${component.revision}"
            val files = fileListCache.getOrPut(cacheKey) {
                listModelFiles(
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
        model: ModelScopeRecommendedModel,
        preferredChipsets: List<String>
    ): RemoteModelFile {
        val safeRevision = model.revision.ifBlank { "main" }
        val errors = mutableListOf<String>()
        for (endpoint in huggingFaceEndpoints) {
            val url = "$endpoint/${model.repoId.urlEncodePath()}/resolve/${safeRevision.urlEncode()}/release_assets.json"
            val file = runCatching {
                client.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val root = JSONObject(response.body?.string().orEmpty())
                    val asset = root.selectQnnContextAsset(
                        preferredChipsets = preferredChipsets,
                        catalogTargetChipset = QAIRT_GEN5_IMAGE_RELEASE_ASSET_CHIPSET
                    )
                    val downloadUrl = preferHuggingFaceDownloadUrl(asset.downloadUrl)
                    val name = downloadUrl.substringBefore('?').substringAfterLast('/').ifBlank {
                        model.recommendedFileName.ifBlank { "${model.id}.zip" }
                    }
                    val pinnedIntegrity = pinnedQairtImageReleaseAssetIntegrity(
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

    fun recommendedVisionBundleFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> {
        val bundle = model.visionModelBundle ?: error("${model.title} 没有配置多模态模型包。")
        val fileListCache = mutableMapOf<String, List<RemoteModelFile>>()
        return bundle.components.map { component ->
            val cacheKey = "${component.provider.name}:${component.repoId}:${component.revision}"
            val files = fileListCache.getOrPut(cacheKey) {
                listModelFiles(
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

    fun recommendedQairtChatFile(
        model: ModelScopeRecommendedModel,
        preferredChipsets: List<String> = emptyList()
    ): RemoteModelFile {
        require(model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
            "${model.title} 不是 GenieX QAIRT 聊天引擎推荐。"
        }
        val safeRevision = model.revision.ifBlank { "main" }
        val errors = mutableListOf<String>()
        for (endpoint in huggingFaceEndpoints) {
            val url = "$endpoint/${model.repoId.urlEncodePath()}/resolve/${safeRevision.urlEncode()}/release_assets.json"
            val file = runCatching {
                client.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val root = JSONObject(response.body?.string().orEmpty())
                    val asset = root.selectGenieXQairtAsset(preferredChipsets)
                    val downloadUrl = preferHuggingFaceDownloadUrl(asset.downloadUrl)
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

    fun searchModels(
        query: String = "Qwen3.5 MNN",
        pageNumber: Int = 1,
        pageSize: Int = 20
    ): ModelScopeModelSearchResult =
        searchModelsVia(modelScopeNetworkPort, query, pageNumber, pageSize)
    private fun request(url: String): Request = modelScopeRequest(url)

    internal fun parseGgufFilesForTest(
        repoId: String,
        revision: String,
        endpoint: String,
        body: String,
        provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE
    ): List<RemoteModelFile> = collectGgufFiles(
        repoId = repoId,
        revision = revision,
        endpoint = endpoint,
        body = body,
        provider = provider,
        extensions = setOf("gguf"),
        huggingFaceEndpoints = huggingFaceEndpoints
    )

    internal fun parseLiteRtLmFilesForTest(
        repoId: String,
        revision: String,
        endpoint: String,
        body: String,
        provider: ModelRepositoryProvider = ModelRepositoryProvider.HUGGING_FACE
    ): List<RemoteModelFile> = collectGgufFiles(
        repoId = repoId,
        revision = revision,
        endpoint = endpoint,
        body = body,
        provider = provider,
        extensions = setOf("litertlm"),
        huggingFaceEndpoints = huggingFaceEndpoints
    )

    internal fun parseModelSearchResultForTest(query: String, body: String): ModelScopeModelSearchResult =
        parseModelSearchResult(query, body)


    internal fun preferredHuggingFaceDownloadUrlForTest(url: String): String =
        preferHuggingFaceDownloadUrl(url)

    internal fun selectedQairtChipsetForTest(
        releaseAssetsJson: String,
        preferredChipsets: List<String>
    ): String = JSONObject(releaseAssetsJson).selectGenieXQairtAsset(preferredChipsets).chipset

    internal fun selectedQnnImageChipsetForTest(
        releaseAssetsJson: String,
        preferredChipsets: List<String>,
        catalogTargetChipset: String? = null
    ): String = JSONObject(releaseAssetsJson)
        .selectQnnContextAsset(preferredChipsets, catalogTargetChipset)
        .chipset

    internal fun pinnedQairtImageReleaseAssetIntegrity(
        modelId: String,
        resolvedName: String,
        recommendedName: String
    ): Pair<Long?, String?> {
        val exactNamedArchive = resolvedName.equals(recommendedName, ignoreCase = true)
        return QAIRT_IMAGE_RELEASE_ASSET_SIZE_BYTES[modelId]?.takeIf { exactNamedArchive } to
            QAIRT_IMAGE_RELEASE_ASSET_SHA256[modelId]?.takeIf { exactNamedArchive }
    }

    private fun preferHuggingFaceDownloadUrl(url: String): String =
        preferredHuggingFaceMirrorUrl(url, huggingFaceEndpoints)

    private fun String.isHuggingFaceHost(): Boolean =
        this.isHuggingFaceHost(huggingFaceEndpoints)

}

/** A selected LiteRT container suffix is an artifact choice, not a cosmetic label. */
internal fun selectRecommendedModelFile(
    model: ModelScopeRecommendedModel,
    files: List<RemoteModelFile>
): RemoteModelFile {
    val selectedName = model.recommendedFileName.trim()
    files.firstOrNull {
        it.path.equals(selectedName, ignoreCase = true) || it.name.equals(selectedName, ignoreCase = true)
    }?.let { return it }
    // CPU/GPU/NPU .litertlm files have different runtime graphs. Falling back to
    // the first container silently installs the wrong variant under a chosen card.
    if (model.chatRuntime == RecommendedChatRuntime.LITERT_LM && selectedName.isNotBlank()) {
        error("仓库 ${model.repoId} 缺少所选 LiteRT-LM 文件：$selectedName。未替换为其他后缀文件，请刷新仓库文件列表后选择实际文件。")
    }
    return files.firstOrNull {
        val matchesKind = if (model.kind == ModelScopeRecommendedKind.IMAGE) it.isImageModelCandidate() else it.isChatModelCandidate()
        matchesKind && it.name.contains(model.quant, ignoreCase = true)
    } ?: files.firstOrNull {
        if (model.kind == ModelScopeRecommendedKind.IMAGE) it.isImageModelCandidate() else it.isChatModelCandidate()
    } ?: error("推荐仓库 ${model.repoId} 没有找到可下载的主模型文件。")
}
