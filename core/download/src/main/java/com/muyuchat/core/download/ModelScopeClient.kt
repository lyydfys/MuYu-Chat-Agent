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

    private val modelScopeRecommendedPort: ModelScopeRecommendedCatalogPort =
        object : ModelScopeRecommendedCatalogPort {
            override val client get() = this@ModelScopeClient.client
            override val huggingFaceEndpoints get() = this@ModelScopeClient.huggingFaceEndpoints

            override fun listModelFiles(
                input: String,
                revision: String,
                provider: ModelRepositoryProvider,
                extensions: Set<String>
            ): List<RemoteModelFile> =
                this@ModelScopeClient.listModelFiles(input, revision, provider, extensions)

            override fun listGgufFiles(
                input: String,
                revision: String,
                provider: ModelRepositoryProvider
            ): List<RemoteModelFile> =
                this@ModelScopeClient.listGgufFiles(input, revision, provider)

            override fun pinnedQairtImageReleaseAssetIntegrity(
                modelId: String,
                resolvedName: String,
                recommendedName: String
            ): Pair<Long?, String?> =
                this@ModelScopeClient.pinnedQairtImageReleaseAssetIntegrity(modelId, resolvedName, recommendedName)
        }

    fun listRecommendedFiles(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): List<RemoteModelFile> =
        listRecommendedFilesVia(modelScopeRecommendedPort, model, preferredQairtChipsets)

    fun recommendedFile(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): RemoteModelFile =
        recommendedFileVia(modelScopeRecommendedPort, model, preferredQairtChipsets)

    fun recommendedMnnBundleFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> =
        recommendedMnnBundleFilesVia(modelScopeRecommendedPort, model)

    fun recommendedImageBundleFiles(
        model: ModelScopeRecommendedModel,
        preferredQairtChipsets: List<String> = emptyList()
    ): List<RemoteModelFile> =
        recommendedImageBundleFilesVia(modelScopeRecommendedPort, model, preferredQairtChipsets)

    fun recommendedVisionBundleFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> =
        recommendedVisionBundleFilesVia(modelScopeRecommendedPort, model)

    fun recommendedQairtChatFile(
        model: ModelScopeRecommendedModel,
        preferredChipsets: List<String> = emptyList()
    ): RemoteModelFile =
        recommendedQairtChatFileVia(modelScopeRecommendedPort, model, preferredChipsets)


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
