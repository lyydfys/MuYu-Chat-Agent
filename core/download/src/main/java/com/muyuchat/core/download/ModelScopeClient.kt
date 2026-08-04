package com.muyuchat.core.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
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

    fun listEngineFiles(
        input: String,
        revision: String = "master",
        provider: ModelRepositoryProvider = ModelRepositoryProvider.MODELSCOPE
    ): List<RemoteModelFile> {
        val repoId = parseRepoId(input, provider)
        val extensions = if ("mnn" in repoId.lowercase()) {
            MODEL_FILE_EXTENSIONS + MNN_MODEL_FILE_EXTENSIONS
        } else {
            setOf("gguf")
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

    private fun listModelScopeFiles(repoId: String, revision: String, extensions: Set<String>): List<RemoteModelFile> {
        val errors = mutableListOf<String>()
        for (endpoint in endpoints) {
            val url = "$endpoint/api/v1/models/$repoId/repo/files?Revision=${revision.urlEncode()}&Recursive=true"
            val request = request(url)
            val files = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string().orEmpty()
                    val files = collectGgufFiles(
                        repoId = repoId,
                        revision = revision,
                        endpoint = endpoint,
                        body = body,
                        provider = ModelRepositoryProvider.MODELSCOPE,
                        extensions = extensions
                    )
                    require(files.isNotEmpty()) { "未在该仓库找到可下载模型组件。" }
                    files
                }
            }.onFailure { error ->
                errors += "${endpoint.removePrefix("https://")}: ${error.message}"
            }.getOrNull()
            if (!files.isNullOrEmpty()) return files
        }
        error(
            "ModelScope 文件列表请求失败：repoId=$repoId, revision=$revision。请确认输入的是模型页或 owner/name，" +
                "不要包含 /summary、/files、/resolve 等页面路径。详情：${errors.joinToString("；")}"
        )
    }

    private fun listHuggingFaceFiles(repoId: String, revision: String, extensions: Set<String>): List<RemoteModelFile> {
        val safeRevision = revision.ifBlank { "main" }
        val errors = mutableListOf<String>()
        for (endpoint in huggingFaceEndpoints) {
            val url = "$endpoint/api/models/${repoId.urlEncodePath()}/tree/${safeRevision.urlEncode()}?recursive=true"
            val files = runCatching {
                client.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val files = collectGgufFiles(
                        repoId = repoId,
                        revision = safeRevision,
                        endpoint = endpoint,
                        body = response.body?.string().orEmpty(),
                        provider = ModelRepositoryProvider.HUGGING_FACE,
                        extensions = extensions
                    )
                    require(files.isNotEmpty()) { "未在该 Hugging Face 仓库找到可下载模型组件。" }
                    files
                }
            }.onFailure { error ->
                errors += "${endpoint.removePrefix("https://")}: ${error.message}"
            }.getOrNull()
            if (!files.isNullOrEmpty()) return files
        }
        error("Hugging Face 文件列表请求失败：repoId=$repoId, revision=$safeRevision。详情：${errors.joinToString("；")}")
    }

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
        return files.firstOrNull {
            it.name.equals(model.recommendedFileName, ignoreCase = true)
        } ?: files.firstOrNull {
            val matchesKind = if (model.kind == ModelScopeRecommendedKind.IMAGE) {
                it.isImageModelCandidate()
            } else {
                it.isChatModelCandidate()
            }
            matchesKind && it.name.contains(model.quant, ignoreCase = true)
        } ?: files.firstOrNull {
            if (model.kind == ModelScopeRecommendedKind.IMAGE) {
                it.isImageModelCandidate()
            } else {
                it.isChatModelCandidate()
            }
        } ?: error("推荐仓库 ${model.repoId} 没有找到可下载的主模型 GGUF 文件。")
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
        return resolveImageBundleComponents(bundle.components)
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
    ): ModelScopeModelSearchResult {
        val safeQuery = query.ifBlank { "Qwen3.5 MNN" }
        val safePage = pageNumber.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(5, 50)
        val errors = mutableListOf<String>()
        for (endpoint in endpoints) {
            val url = "$endpoint/openapi/v1/models?page_number=$safePage&page_size=$safeSize&search=${safeQuery.urlEncode()}"
            val result = runCatching {
                client.newCall(request(url)).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    parseModelSearchResult(safeQuery, response.body?.string().orEmpty())
                }
            }.onFailure { error ->
                errors += "${endpoint.removePrefix("https://")}: ${error.message}"
            }.getOrNull()
            if (result != null) return result
        }
        error("ModelScope 模型搜索失败：query=$safeQuery。详情：${errors.joinToString("；")}")
    }

    private fun request(url: String): Request =
        Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "MCA/0.1 ModelScopeClient")
            .header("Accept", "application/json,*/*")
            .build()

    private fun collectGgufFiles(
        repoId: String,
        revision: String,
        endpoint: String,
        body: String,
        provider: ModelRepositoryProvider,
        extensions: Set<String> = setOf("gguf")
    ): List<RemoteModelFile> {
        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root != null) return collectFromJson(repoId, revision, endpoint, provider, root, extensions)
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return collectFromArray(repoId, revision, endpoint, provider, array, extensions)
    }

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
        extensions = setOf("gguf")
    )

    private fun collectFromJson(
        repoId: String,
        revision: String,
        endpoint: String,
        provider: ModelRepositoryProvider,
        json: JSONObject,
        extensions: Set<String>
    ): List<RemoteModelFile> {
        val result = mutableListOf<RemoteModelFile>()
        maybeFile(repoId, revision, endpoint, provider, json, extensions)?.let(result::add)
        json.keys().forEach { key ->
            when (val value = json.opt(key)) {
                is JSONObject -> result += collectFromJson(repoId, revision, endpoint, provider, value, extensions)
                is JSONArray -> result += collectFromArray(repoId, revision, endpoint, provider, value, extensions)
            }
        }
        return result.distinctBy { it.path }
    }

    private fun collectFromArray(
        repoId: String,
        revision: String,
        endpoint: String,
        provider: ModelRepositoryProvider,
        array: JSONArray,
        extensions: Set<String>
    ): List<RemoteModelFile> {
        val result = mutableListOf<RemoteModelFile>()
        for (index in 0 until array.length()) {
            when (val value = array.opt(index)) {
                is JSONObject -> result += collectFromJson(repoId, revision, endpoint, provider, value, extensions)
                is JSONArray -> result += collectFromArray(repoId, revision, endpoint, provider, value, extensions)
            }
        }
        return result
    }

    private fun maybeFile(
        repoId: String,
        revision: String,
        endpoint: String,
        provider: ModelRepositoryProvider,
        json: JSONObject,
        extensions: Set<String>
    ): RemoteModelFile? {
        val path = listOf("Path", "path", "Name", "name", "FileName", "fileName", "rfilename")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            ?: return null
        val extension = path.substringAfterLast('.', "").lowercase()
        if (extension !in extensions) return null
        val name = path.substringAfterLast('/')
        val rawUrl = listOf("DownloadUrl", "downloadUrl", "download_url", "Url", "url")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.startsWith("http") } }
            ?: when (provider) {
                ModelRepositoryProvider.MODELSCOPE ->
                    "$endpoint/models/$repoId/resolve/${revision.urlEncode()}/${path.urlEncodePath()}"
                ModelRepositoryProvider.HUGGING_FACE ->
                    "$endpoint/$repoId/resolve/${revision.urlEncode()}/${path.urlEncodePath()}?download=true"
            }
        val url = if (provider == ModelRepositoryProvider.HUGGING_FACE) {
            preferHuggingFaceDownloadUrl(rawUrl)
        } else {
            rawUrl
        }
        val sha = listOf("Sha256", "sha256", "SHA256")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            ?: json.optJSONObject("lfs")?.firstString("sha256", "oid")
            ?: json.optString("oid").takeIf { it.isNotBlank() }
        val size = listOf("Size", "size", "sizeBytes")
            .firstNotNullOfOrNull { key -> json.optLong(key).takeIf { it > 0 } }
            ?: json.optJSONObject("lfs")?.firstLong("size", "Size")
        return RemoteModelFile(
            repoId = repoId,
            revision = revision,
            path = path,
            name = name,
            sizeBytes = size,
            sha256 = sha,
            downloadUrl = url,
            provider = provider
        )
    }

    private fun parseModelSearchResult(query: String, body: String): ModelScopeModelSearchResult {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: root.optJSONObject("Data") ?: root
        val modelsArray = data.firstArray("models", "Models", "items", "Items", "list", "List", "modelList")
            ?: root.firstArray("data", "Data")
            ?: JSONArray()
        val models = buildList {
            for (index in 0 until modelsArray.length()) {
                val item = modelsArray.optJSONObject(index) ?: continue
                val id = item.firstString("id", "model_id", "modelId", "ModelId", "name", "Name")
                    ?.trim('/')
                    ?.takeIf { it.contains('/') }
                    ?: continue
                add(
                    ModelScopeHubModel(
                        id = id,
                        displayName = item.firstString("display_name", "displayName", "model_name", "modelName", "name", "Name")
                            ?: id.substringAfter('/'),
                        description = item.firstString("description", "Description").orEmpty(),
                        downloads = item.firstLong("downloads", "download_count", "downloadCount", "downloads_count"),
                        likes = item.firstLong("likes", "like_count", "likeCount", "stars"),
                        license = item.firstString("license", "License")
                            ?.takeIf { it.isNotBlank() && it != "null" },
                        tasks = item.firstArray("tasks", "Tasks", "task_tags").toStringList(),
                        fileSizeBytes = item.firstLong("file_size", "fileSize", "size", "Size"),
                        params = item.firstLong("params", "parameter_count", "parameterCount"),
                        tags = item.firstArray("tags", "Tags").toStringList(),
                        private = item.optBoolean("private"),
                        gated = item.optBoolean("gated")
                    )
                )
            }
        }
        return ModelScopeModelSearchResult(
            query = query,
            pageNumber = data.optInt("page_number", 1),
            pageSize = data.optInt("page_size", models.size),
            totalCount = data.optInt("total_count", models.size),
            models = models
        )
    }

    internal fun parseModelSearchResultForTest(query: String, body: String): ModelScopeModelSearchResult =
        parseModelSearchResult(query, body)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = opt(index)
                when (value) {
                    is String -> value.takeIf { it.isNotBlank() }?.let(::add)
                    is JSONObject -> value.firstString("name", "label", "value")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun JSONObject.firstArray(vararg keys: String): JSONArray? =
        keys.firstNotNullOfOrNull { key -> optJSONArray(key) }

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> optString(key).takeIf { it.isNotBlank() } }

    private fun JSONObject.firstLong(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { key -> optLong(key).takeIf { it > 0L } } ?: 0L

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun String.urlEncodePath(): String =
        split('/').joinToString("/") { it.urlEncode() }

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

    private fun preferHuggingFaceDownloadUrl(url: String): String {
        val preferred = huggingFaceEndpoints.firstOrNull {
            !it.contains("huggingface.co", ignoreCase = true)
        } ?: return url
        val normalized = url.trim()
        return when {
            normalized.startsWith("https://huggingface.co/", ignoreCase = true) ->
                normalized.replaceFirst(Regex("""^https://huggingface\.co(?=/)""", RegexOption.IGNORE_CASE), preferred.trimEnd('/'))
            normalized.startsWith("https://www.huggingface.co/", ignoreCase = true) ->
                normalized.replaceFirst(Regex("""^https://www\.huggingface\.co(?=/)""", RegexOption.IGNORE_CASE), preferred.trimEnd('/'))
            normalized.startsWith("https://hf.co/", ignoreCase = true) ->
                normalized.replaceFirst(Regex("""^https://hf\.co(?=/)""", RegexOption.IGNORE_CASE), preferred.trimEnd('/'))
            else -> url
        }
    }

    private fun String.isHuggingFaceHost(): Boolean {
        val host = trim().lowercase().removePrefix("www.")
        return host == "huggingface.co" ||
            host == "hf.co" ||
            huggingFaceEndpoints.any { endpoint ->
                runCatching { URI(endpoint).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("") == host
            }
    }

    private data class QairtReleaseAsset(
        val chipset: String,
        val downloadUrl: String
    )

    private fun JSONObject.selectQnnContextAsset(
        preferredChipsets: List<String>,
        catalogTargetChipset: String? = null
    ): QairtReleaseAsset {
        val chipsetAssets = optJSONObject("precisions")
            ?.optJSONObject("w8a16")
            ?.optJSONObject("chipset_assets")
            ?: error("release_assets.json 缺少 w8a16 chipset_assets。")

        fun assetFor(chipset: String): QairtReleaseAsset? = chipsetAssets.optJSONObject(chipset)
            ?.optJSONObject("qnn_context_binary")
            ?.optString("download_url")
            ?.takeIf { it.startsWith("http") }
            ?.let { QairtReleaseAsset(chipset, it) }

        catalogTargetChipset
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { target ->
                // A catalog entry names one executable context target. Device
                // discovery may rank that entry, but downloading it must not
                // silently substitute a context archive for another chipset.
                val exactTargetFirst = if (target.endsWith("-for-galaxy")) {
                    listOf(target)
                } else {
                    // Legacy callers that name a generic target may still use
                    // the publisher's vendor variant when no generic asset exists.
                    listOf(target, "$target-for-galaxy")
                }
                return exactTargetFirst
                    .firstNotNullOfOrNull(::assetFor)
                    ?: error("release_assets.json 缺少目录声明的 QNN context 目标：$target。")
            }

        val requested = preferredChipsets
            .map { it.trim() }
            .filter { it.isNotBlank() }
            // A chipset match does not imply a vendor-specific device build.
            // Prefer the generic asset and use the Galaxy variant only when
            // the publisher exposes no generic package for that chipset.
            .flatMap { chipset -> listOf(chipset, "$chipset-for-galaxy") }
            .distinct()
        for (chipset in requested) {
            assetFor(chipset)?.let { return it }
        }
        val available = buildList {
            val keys = chipsetAssets.keys()
            while (keys.hasNext()) add(keys.next())
        }
        return available
            .sortedWith(qairtFallbackChipsetComparator())
            .firstNotNullOfOrNull(::assetFor)
            ?: error("release_assets.json 没有可下载的 qnn_context_binary w8a16 芯片包。")
    }

    private fun JSONObject.selectGenieXQairtAsset(preferredChipsets: List<String>): QairtReleaseAsset {
        val chipsetAssets = optJSONObject("precisions")
            ?.optJSONObject("w4a16")
            ?.optJSONObject("chipset_assets")
            ?: error("release_assets.json 缺少 w4a16 chipset_assets。")
        val available = buildList {
            val keys = chipsetAssets.keys()
            while (keys.hasNext()) add(keys.next())
        }
        val chipsetOrder = preferredChipsets
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        for (chipset in chipsetOrder) {
            val asset = chipsetAssets.optJSONObject(chipset)
                ?.optJSONObject("geniex_qairt")
                ?: continue
            val downloadUrl = asset.optString("download_url").takeIf { it.startsWith("http") }
                ?: continue
            return QairtReleaseAsset(chipset, downloadUrl)
        }
        return available
            .sortedWith(qairtFallbackChipsetComparator())
            .firstNotNullOfOrNull { chipset ->
                chipsetAssets.optJSONObject(chipset)
                    ?.optJSONObject("geniex_qairt")
                    ?.optString("download_url")
                    ?.takeIf { it.startsWith("http") }
                    ?.let { QairtReleaseAsset(chipset, it) }
            }
            ?: error("release_assets.json 没有可下载的 geniex_qairt w4a16 芯片包。")
    }

    private fun qairtFallbackChipsetComparator(): Comparator<String> =
        compareBy<String> { chipset ->
            // Generic packages are preferable to vendor-bin variants when no
            // exact device key exists.
            if (chipset.endsWith("-for-galaxy")) 1 else 0
        }.thenBy { chipset ->
            // Prefer the oldest published 8-series target as the broadest
            // forward-compatible baseline; exact requested matches already
            // won above this fallback.
            when {
                "elite-gen5" in chipset -> 2
                "elite" in chipset -> 1
                else -> 0
            }
        }.thenBy { it }

    companion object {
        private const val QNN_SD15_EXECUTION_PROFILE_REVISION = 5
        private const val QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION = 6
        private const val QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION = 6
        private const val QNN_SDXL_EXECUTION_PROFILE_REVISION = 6
        private const val QNN_GEN5_EXECUTION_PROFILE_REVISION = 2
        private val QNN_SD15_CONDITIONING_RUNTIME_ASSETS = listOf(
            "tokenizer.json",
            "token_emb.bin",
            "pos_emb.bin"
        )
        private val QAIRT_IMAGE_RELEASE_ASSET_MODEL_IDS = setOf(
            "qualcomm_sd15_gen5_qnn",
            "qualcomm_sd21_gen5_qnn",
            "qualcomm_controlnet_canny_gen5_qnn"
        )
        private const val QAIRT_GEN5_IMAGE_RELEASE_ASSET_CHIPSET =
            "qualcomm-snapdragon-8-elite-gen5-for-galaxy"
        private val QAIRT_IMAGE_RELEASE_ASSET_SIZE_BYTES = mapOf(
            "qualcomm_sd15_gen5_qnn" to 711_934_104L,
            "qualcomm_sd21_gen5_qnn" to 874_955_354L,
            "qualcomm_controlnet_canny_gen5_qnn" to 950_517_794L
        )
        // SHA-256 of the exact pinned S3 release objects named by each immutable
        // release_assets.json revision. These are download/install contracts,
        // not device admission rules.
        private val QAIRT_IMAGE_RELEASE_ASSET_SHA256 = mapOf(
            "qualcomm_sd15_gen5_qnn" to
                "3716ba4c32d6dcf1af93857d22889e1e95f9c3e4c62983fff2d2a743eeff644e",
            "qualcomm_sd21_gen5_qnn" to
                "3fc5fc8df77e4952776020d932ee5934ec432b397a456515ae7f8ed2af004ae8",
            "qualcomm_controlnet_canny_gen5_qnn" to
                "582b4dee61584cdd2e0f96bdbaff19a6bf919af365c4a7f258ed260be5e1262d"
        )
        private val MODEL_FILE_EXTENSIONS = setOf(
            "gguf",
            "safetensors",
            "sft",
            "ckpt",
            "pth",
            "pt",
            "onnx",
            "mnn",
            "zip",
            "task",
            "tflite",
            "litertlm",
            "bin",
            "ctx",
            "qnn",
            "json"
        )
        private val MNN_MODEL_FILE_EXTENSIONS = setOf("json", "mnn", "weight", "txt", "bin", "mtok")

        private val DEFAULT_ENDPOINTS = listOf(
            "https://www.modelscope.cn",
            "https://modelscope.cn",
            "https://www.modelscope.ai",
            "https://modelscope.ai"
        )

        private val DEFAULT_HUGGING_FACE_ENDPOINTS = listOf(
            "https://hf-mirror.com",
            "https://huggingface.co"
        )

        private const val SANA_EDIT_V2_REVISION = "50adc28b4682161542f893c624048adf6dd027ca"
        private const val SD15_MNN_REVISION = "346de5fcde406781a34368140419ac3f62440916"

        // Keep every recommended MNN package on one immutable repository revision. The
        // installer still validates the per-file SHA-256 returned by the source, but a
        // pinned tree prevents config/tokenizer/model files from drifting independently
        // between a file-list request and a later repair-install.
        private const val QWEN35_2B_MNN_REVISION = "b9ae8c8f3da3fceb4278b558a747286b8a087dbe"
        private const val QWEN35_08B_UNCENSORED_MNN_REVISION = "7ad2802ed360a3066112bd973506a6bb3820df2c"
        private const val QWEN35_4B_UNCENSORED_MNN_REVISION = "cfa553d6b42bd9ed86f17e02da3b78e7093736ed"
        private const val QWEN35_9B_UNCENSORED_MNN_REVISION = "9eadc756519bf42e6b8aed8a6ade5b124c79e564"
        private const val QWEN35_2B_ABLITERATED_GGUF_REVISION = "f36848fead3fdda244cf60195c46993d23183d4c"
        private const val QWEN3_VL_4B_ABLITERATED_GGUF_REVISION = "d6630953b1fe29a678c69f95e02d1f5d17b53b11"
        private const val MINICPM_V46_ABLITERATED_GGUF_REVISION = "df9281a41ec46766218fc1b54d070b2a52668866"
        private const val QWEN25_VL_7B_ABLITERATED_GGUF_REVISION = "dbf78ed7f2020ee497ddaec61b88f153790eedb3"
        private const val GEMMA4_26B_A4B_ABLITERATED_GGUF_REVISION = "5e35628c3cd1f39fdde4d7a16ee6653d42df0e95"
        private const val QWEN36_35B_A3B_ABLITERATED_MNN_REVISION = "bb83b12d83ad64aaaa98f9fab3e86af3806a6e40"
        private const val GEMMA4_E2B_UNCENSORED_GGUF_REVISION = "4345c0c77cde7da43084c94b1deac23c09bccfc1"
        private const val GEMMA4_E4B_UNCENSORED_GGUF_REVISION = "771f130d4c49735ace331f68a80f7ae31387e51c"
        private const val QWEN3_4B_2507_ABLITERATED_GGUF_REVISION = "c9e90669eeb205d5af35c28a3e9983fc9293c2ec"
        private const val QWEN3_8B_ABLITERATED_GGUF_REVISION = "64c8e52a68276ef181b7695813c5883070b783a0"
        private const val QWEN35_35B_A3B_MNN_REVISION = "5e21a599fd2e01d2f1f6ccedac48912439ba22f5"
        private const val GEMMA4_E2B_MNN_REVISION = "ad38122704d7a0cfd207abb75a815a2436ab92e6"
        private const val GEMMA4_E4B_MNN_REVISION = "69a938a0f52bedcffc7e42215932f03de15bfe86"
        private const val GEMMA4_26B_A4B_MNN_REVISION = "2dcaf1402d04cf22c738b937cbab2b8147afc2a0"

        private fun gemmaTextOnlyMnnComponents(): List<MnnModelBundleComponentSpec> = listOf(
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, "tokenizer.mtok"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "ple_embeddings_int4.bin")
        )

        private fun qwen35CommunityMnnComponents(
            tokenizerFileName: String,
            requiresEmbeddingFile: Boolean = false,
            requiresVision: Boolean = false
        ): List<MnnModelBundleComponentSpec> = buildList {
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"))
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"))
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"))
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"))
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, tokenizerFileName))
            add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json"))
            if (requiresEmbeddingFile) {
                add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "embeddings_bf16.bin"))
            }
            if (requiresVision) {
                add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "visual.mnn"))
                add(MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "visual.mnn.weight"))
            }
        }

        /**
         * Community GGUF releases keep the multimodal projector separate from
         * the quantized language model. The installer must therefore always
         * materialize both files as one managed bundle rather than register a
         * text-only main model and leave the card's vision claim dangling.
         */
        private fun communityLowRefusalGgufVisionBundle(
            id: String,
            title: String,
            repoId: String,
            revision: String,
            mainFileName: String,
            projectorFileName: String,
            smokeImageSize: Int = 448,
            smokeTimeoutSeconds: Int = 180
        ): VisionModelBundleSpec = VisionModelBundleSpec(
            id = id,
            title = title,
            runtime = VisionModelBundleRuntime.GGUF_MMPROJ,
            accelerator = VisionModelAccelerator.CPU,
            minDeviceTier = ImageEngineMinDeviceTier.ANY,
            requiresQnnRuntime = false,
            requiresSmokeTest = true,
            downloadProjectorByDefault = true,
            smokeSpec = VisionModelSmokeSpec(
                imageWidth = smokeImageSize,
                imageHeight = smokeImageSize,
                prompt = "请用中文描述这张图片",
                timeoutSeconds = smokeTimeoutSeconds
            ),
            components = listOf(
                VisionModelBundleComponentSpec(
                    role = VisionModelBundleComponentRole.MAIN_MODEL,
                    repoId = repoId,
                    revision = revision,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    fileName = mainFileName
                ),
                VisionModelBundleComponentSpec(
                    role = VisionModelBundleComponentRole.PROJECTOR,
                    repoId = repoId,
                    revision = revision,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    fileName = projectorFileName
                )
            )
        )

        private fun gemmaMnnComponents(): List<MnnModelBundleComponentSpec> = listOf(
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.CONFIG, "config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.MODEL, "llm.mnn"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "llm.mnn.weight"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.TOKENIZER, "tokenizer.mtok"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.LLM_CONFIG, "llm_config.json"),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "llm.mnn.json", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "configuration.json", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "export_args.json", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "visual.mnn.weight", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "audio.mnn", required = false),
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.OPTIONAL, "audio.mnn.weight", required = false),
            // Gemma 4's llm_config.json declares this as `ple_embed_file`.
            // It is not an optional UI modality asset: MNN 3.6 feeds it into
            // the exported graph as the `ple_embeddings` input during every
            // text or multimodal request.  Keeping it optional allowed an
            // incomplete bundle to be registered and fail only at native load.
            MnnModelBundleComponentSpec(MnnModelBundleComponentRole.WEIGHT, "ple_embeddings_int4.bin")
        )

        private fun sanaEditV2MnnComponents(): List<ImageEngineBundleComponentSpec> {
            val repoId = "MNN/MNN-Sana-Edit-V2"
            val revision = SANA_EDIT_V2_REVISION

            fun component(
                role: ImageEngineBundleComponentRole,
                fileName: String,
                expectedSizeBytes: Long,
                sha256: String
            ) = ImageEngineBundleComponentSpec(
                role = role,
                repoId = repoId,
                revision = revision,
                provider = ModelRepositoryProvider.MODELSCOPE,
                fileName = fileName,
                expectedSizeBytes = expectedSizeBytes,
                sha256 = sha256,
                relativePath = fileName
            )

            return listOf(
                component(ImageEngineBundleComponentRole.CONFIG, "config.json", 810L, "9471a0ffd2ac3afb78d70ec8b9d4fdc4696fcdd905ba2a25f1806c3529bf00f2"),
                component(ImageEngineBundleComponentRole.CONFIG, "llm/config.json", 210L, "c4bd25dbbc950feffccc3b154d634fdfbce96fbed453dd738bda4abfc763b73a"),
                component(ImageEngineBundleComponentRole.CONFIG, "llm/llm_config.json", 4_638L, "2e45095efda4d17853d8b565f7f354210d3f14f97ac24b24a87a5ab771f5980a"),
                component(ImageEngineBundleComponentRole.TEXT_ENCODER, "llm/llm.mnn", 504_504L, "a3e32dc50e8988e78d416031023345048f4b6cf152db021da6ee1de921d45096"),
                component(ImageEngineBundleComponentRole.TEXT_ENCODER, "llm/llm.mnn.weight", 373_018_866L, "79db6ac8267ec6a7c9172a363112fa613c0cf17d6f46121d947a75c987ccf49a"),
                component(ImageEngineBundleComponentRole.TOKENIZER, "llm/tokenizer.txt", 3_193_562L, "80e75c6cbf70c75fdd51ac1cd53505ac127dcb0e21ebe4d19751fa772e2868bd"),
                component(ImageEngineBundleComponentRole.CONDITIONING, "llm/meta_queries.mnn", 1_048_824L, "5e80d4e591af78cca31b6e4cf4ee4ead410e9d2f64ee34c73cf5b633def16e0c"),
                component(ImageEngineBundleComponentRole.CONDITIONING, "connector.mnn", 99_096L, "d72239e1c2626cfb8f349b9d6b8c9d85f1cd9def0063d3ea23695aa6b2ef48fd"),
                component(ImageEngineBundleComponentRole.CONDITIONING, "connector.mnn.weight", 76_268_760L, "7128351d2de561932741f7f874b116ea3b4e5979296d8adf41472a93fcb889cd"),
                component(ImageEngineBundleComponentRole.CONDITIONING, "projector.mnn", 2_416L, "6236a92633bab8ee33416b2f84ec41b934885be652aea7f0412a057de817d4c0"),
                component(ImageEngineBundleComponentRole.CONDITIONING, "projector.mnn.weight", 2_387_206L, "34b5afdb0c3b1fc815cdee7f3ed293e8d8f1f377328e5785cad2bd1768a843c3"),
                component(ImageEngineBundleComponentRole.DIFFUSION, "transformer.mnn", 1_454_264L, "092dd75e8b8c12694ffe43476addcbde07fe7227774a1a40b19420f87b217386"),
                component(ImageEngineBundleComponentRole.DIFFUSION, "transformer.mnn.weight", 884_435_680L, "b3bab45fbabc8dabd05840b52ea3cd9bd3e54dd990e153ff6fbecd8b6c17f331"),
                component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn", 751_784L, "9fbe51979b27339b7685cf88f1010a0ff3ab7ff1a7d873fba321eea94b762911"),
                component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn.weight", 162_011_594L, "a6ef7a13ba9af29754adf9b97651cb29a7eaee20b716c16dbe079f500d5eddae"),
                component(ImageEngineBundleComponentRole.VAE_ENCODER, "vae_encoder.mnn", 761_568L, "06da21081f8ee98792bd1838990068e7284351157cafbfa8793282b611eacb24"),
                component(ImageEngineBundleComponentRole.VAE_ENCODER, "vae_encoder.mnn.weight", 155_787_522L, "b44ac00f4683697add9578ef4c0f561fb5753fe24a3f4525e7f492028409d05e")
            )
        }

        private fun stableDiffusion15MnnComponents(): List<ImageEngineBundleComponentSpec> {
            val repoId = "MNN/stable-diffusion-v1-5-mnn-opencl"
            val revision = SD15_MNN_REVISION

            fun component(
                role: ImageEngineBundleComponentRole,
                fileName: String,
                expectedSizeBytes: Long,
                sha256: String,
                required: Boolean = true
            ) = ImageEngineBundleComponentSpec(
                role = role,
                repoId = repoId,
                revision = revision,
                provider = ModelRepositoryProvider.MODELSCOPE,
                fileName = fileName,
                required = required,
                expectedSizeBytes = expectedSizeBytes,
                sha256 = sha256
            )

            return listOf(
                component(ImageEngineBundleComponentRole.TEXT_ENCODER, "text_encoder.mnn", 249_944L, "5713fa5c83aa446b5b9c28a48a90c647a5bababc5ee5f254cf72e7f479551036"),
                component(ImageEngineBundleComponentRole.TEXT_ENCODER, "text_encoder.mnn.weight", 238_120_368L, "c245ac80dd8a72279976414435801d579a8dcf83aba84e121c4e4a0b74bbed3d"),
                component(ImageEngineBundleComponentRole.DIFFUSION, "unet.mnn", 1_248_536L, "0aaad66712a3f86ef7891392517a5d7471327574a6f4c6defcefc10ce5e06fee"),
                component(ImageEngineBundleComponentRole.DIFFUSION, "unet.mnn.weight", 863_262_988L, "67049e0d6ce8cb34ab1cd78e58909427db1b57f9fd048e16f2f9c86c39f7479b"),
                component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn", 128_248L, "fa6f34c9e77fb715f57d27d65c930455c2ba11b42f2b956da8dcadb2b4bf14b2"),
                component(ImageEngineBundleComponentRole.VAE, "vae_decoder.mnn.weight", 49_639_112L, "20db884599922383eb168fd2fd018892a7741bb45f3ad7073d4cfaf7d75f2241"),
                component(ImageEngineBundleComponentRole.TOKENIZER, "vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349"),
                component(ImageEngineBundleComponentRole.TOKENIZER, "merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                ImageEngineBundleComponentSpec(
                    role = ImageEngineBundleComponentRole.TOKENIZER,
                    repoId = "openai/clip-vit-large-patch14",
                    revision = "32bd64288804d66eefd0ccbe215aa642df71cc41",
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    fileName = "tokenizer.json",
                    expectedSizeBytes = 2_224_003L,
                    sha256 = "a83e0809aa4c3af7208b2df632a7a69668c6d48775b3c3fe4e1b1199d1f8b8f4",
                    relativePath = "tokenizer.json"
                ),
                component(ImageEngineBundleComponentRole.OPTIONAL, "configuration.json", 54L, "70da9376517abe492bc35499d5455724da1cbc454126a1dbfad8c0542deace48", required = false),
                component(ImageEngineBundleComponentRole.OPTIONAL, "alphas.txt", 6_999L, "18fe126a911bad616346202b0542adacf59881edcb2edc4a74c5a571ed297f51", required = false)
            )
        }

        private fun imageScheduler(
            algorithm: ImageEngineSchedulerAlgorithm,
            predictionType: ImageEnginePredictionType,
            defaultSteps: Int,
            minSteps: Int,
            maxSteps: Int,
            timestepSpacing: ImageEngineTimestepSpacing = when (algorithm) {
                ImageEngineSchedulerAlgorithm.EULER,
                ImageEngineSchedulerAlgorithm.DPMPP_2M -> ImageEngineTimestepSpacing.LINSPACE
                else -> ImageEngineTimestepSpacing.LEADING
            },
            stepsOffset: Int = 0,
            setAlphaToOne: Boolean = false,
            skipPrkSteps: Boolean = false,
            scaleModelInput: Boolean = false,
            order: Int = 1
        ): ImageEngineSchedulerContractSpec = ImageEngineSchedulerContractSpec(
            algorithm = algorithm,
            predictionType = predictionType,
            noiseSchedule = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) {
                ImageEngineNoiseSchedule.SIGMA
            } else {
                ImageEngineNoiseSchedule.SCALED_LINEAR
            },
            betaStart = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) null else 0.00085,
            betaEnd = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) null else 0.012,
            timestepSpacing = timestepSpacing,
            stepsOffset = stepsOffset,
            setAlphaToOne = setAlphaToOne,
            skipPrkSteps = skipPrkSteps,
            scaleModelInput = scaleModelInput,
            order = order,
            defaultSteps = defaultSteps,
            minSteps = minSteps,
            maxSteps = maxSteps
        )

        private fun clipTokenizer(
            backend: ImageEngineTokenizerBackend,
            maxLength: Int = 77,
            dualClip: Boolean = false,
            padZero: Boolean = false,
            supportsPromptWeighting: Boolean = backend != ImageEngineTokenizerBackend.MNN_MTOK,
            supportsTextualInversion: Boolean = false,
            separateNegativePrompt: Boolean = true
        ): ImageEngineTokenizerContractSpec = ImageEngineTokenizerContractSpec(
            backend = backend,
            bosId = if (backend == ImageEngineTokenizerBackend.MNN_MTOK) null else 49_406,
            eosId = if (backend == ImageEngineTokenizerBackend.MNN_MTOK) null else 49_407,
            padId = when {
                backend == ImageEngineTokenizerBackend.MNN_MTOK -> null
                dualClip || padZero -> 0
                else -> 49_407
            },
            maxLength = maxLength,
            clip1PadRule = when {
                backend == ImageEngineTokenizerBackend.MNN_MTOK -> ImageEngineClipPadRule.MODEL_DECLARED
                padZero -> ImageEngineClipPadRule.ZERO
                else -> ImageEngineClipPadRule.EOS
            },
            clip2PadRule = if (dualClip) ImageEngineClipPadRule.ZERO else null,
            supportsPromptWeighting = supportsPromptWeighting,
            supportsTextualInversion = supportsTextualInversion,
            separateNegativePrompt = separateNegativePrompt
        )

        private fun imageConditioning(
            diskDataType: ImageEngineEmbeddingDataType,
            conversionStrategy: ImageEngineEmbeddingConversionStrategy,
            width: Int,
            maxLength: Int = 77,
            dualEncoder: Boolean = false,
            pooledOutput: Boolean = false,
            separateNegativePrompt: Boolean = true
        ): ImageEngineConditioningContractSpec = ImageEngineConditioningContractSpec(
            diskDataType = diskDataType,
            conversionStrategy = conversionStrategy,
            textEncoderInputShape = listOf(1, maxLength),
            textEncoderOutputShapes = if (dualEncoder) {
                listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
            } else {
                listOf(listOf(1, maxLength, width))
            },
            dualEncoder = dualEncoder,
            pooledOutput = pooledOutput,
            concatenationOrder = when {
                dualEncoder -> listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
                separateNegativePrompt -> listOf("negative", "positive")
                else -> listOf("positive")
            }
        )

        private fun imageVae(
            scalingLocation: ImageEngineVaeScalingLocation,
            scalingFactor: Double,
            size: Int
        ): ImageEngineVaeContractSpec = ImageEngineVaeContractSpec(
            scalingLocation = scalingLocation,
            scalingFactor = scalingFactor,
            inputShape = listOf(1, 4, size / 8, size / 8),
            outputShape = listOf(1, 3, size, size)
        )

        private fun fixedImageCapabilities(
            size: Int,
            schedulers: Set<ImageEngineSchedulerAlgorithm>,
            supportsPromptWeighting: Boolean = true,
            supportsTextualInversion: Boolean = false,
            supportsNegativePrompt: Boolean = true,
            requiresControlImage: Boolean = false,
            requiresInputImage: Boolean = false,
            supportsMask: Boolean = false,
            supportsLivePreview: Boolean = false
        ): ImageEngineGenerationCapabilitiesSpec = ImageEngineGenerationCapabilitiesSpec(
            supportedSchedulers = schedulers,
            minWidth = size,
            maxWidth = size,
            minHeight = size,
            maxHeight = size,
            supportsNegativePrompt = supportsNegativePrompt,
            supportsPromptWeighting = supportsPromptWeighting,
            supportsTextualInversion = supportsTextualInversion,
            requiresControlImage = requiresControlImage,
            requiresInputImage = requiresInputImage,
            supportsMask = supportsMask,
            supportsLivePreview = supportsLivePreview
        )

        private fun stableDiffusionCppCapabilities(
            schedulers: Set<ImageEngineSchedulerAlgorithm>,
            family: ImageEngineModelFamily,
            supportsNegativePrompt: Boolean = true
        ): ImageEngineGenerationCapabilitiesSpec {
            val supportsStableExtensions = family in setOf(
                ImageEngineModelFamily.SD15,
                ImageEngineModelFamily.SD21,
                ImageEngineModelFamily.SDXL,
                ImageEngineModelFamily.SD_TURBO
            )
            val ultraFixMultiple = if (family == ImageEngineModelFamily.SDXL) 32 else 64
            return ImageEngineGenerationCapabilitiesSpec(
                supportedSchedulers = schedulers,
                minWidth = 256,
                maxWidth = 1_536,
                minHeight = 256,
                maxHeight = 1_536,
                widthMultiple = 64,
                heightMultiple = 64,
                supportsNegativePrompt = supportsNegativePrompt,
                supportsPromptWeighting = true,
                supportsTextualInversion = supportsStableExtensions,
                supportsClipSkip = supportsStableExtensions,
                supportsVaeTiling = true,
                supportsUltraFix = supportsStableExtensions,
                ultraFixMinWidth = if (supportsStableExtensions) 128 else 0,
                ultraFixMaxWidth = if (supportsStableExtensions) 8_192 else 0,
                ultraFixMinHeight = if (supportsStableExtensions) 128 else 0,
                ultraFixMaxHeight = if (supportsStableExtensions) 8_192 else 0,
                ultraFixWidthMultiple = if (supportsStableExtensions) ultraFixMultiple else 0,
                ultraFixHeightMultiple = if (supportsStableExtensions) ultraFixMultiple else 0,
                supportsLivePreview = true,
                supportsLora = true,
                maxBatchCount = 8
            )
        }

        private fun stableDiffusionCppSchedulers(
            algorithm: ImageEngineSchedulerAlgorithm
        ): Set<ImageEngineSchedulerAlgorithm> = when (algorithm) {
            // Flow checkpoints are trained against their flow timetable. Do
            // not advertise diffusion-only samplers that the bridge can parse
            // but the selected checkpoint cannot execute faithfully.
            ImageEngineSchedulerAlgorithm.FLOW_MATCH -> setOf(algorithm)
            ImageEngineSchedulerAlgorithm.EULER_A -> setOf(
                ImageEngineSchedulerAlgorithm.EULER_A,
                ImageEngineSchedulerAlgorithm.EULER,
                ImageEngineSchedulerAlgorithm.DPMPP_2M
            )
            else -> setOf(algorithm)
        }

        private fun qnnGraph(
            textEncoder: String,
            unet: String,
            vae: String,
            qnnSdk: String?,
            htpArch: Int?,
            vaeEncoder: String? = null,
            workerStrategy: ImageEngineWorkerStrategy = ImageEngineWorkerStrategy.SHARED_TEXT_UNET_VAE,
            controlNet: String? = null,
            schedulerSidecar: String? = "scheduler/scheduler_config.json",
            tokenizerSidecar: String? = "tokenizer/tokenizer_config.json",
            runtimeAssets: List<String> = emptyList()
        ): ImageEngineGraphContractSpec = ImageEngineGraphContractSpec(
            textEncoder = textEncoder,
            unet = unet,
            vae = vae,
            vaeEncoder = vaeEncoder,
            controlNet = controlNet,
            schedulerSidecar = schedulerSidecar,
            tokenizerSidecar = tokenizerSidecar,
            configSidecars = runtimeAssets,
            qnnSdk = qnnSdk,
            htpArch = htpArch,
            workerStrategy = workerStrategy
        )

        private fun qnnSd15ExecutionProfile(
            profileId: String,
            variant: ImageEngineModelVariant = ImageEngineModelVariant.STANDARD,
            steps: Int = 20,
            cfgScale: Double = 7.0,
            conditioningDataType: ImageEngineEmbeddingDataType = ImageEngineEmbeddingDataType.FP16,
            conversionStrategy: ImageEngineEmbeddingConversionStrategy = ImageEngineEmbeddingConversionStrategy.NONE,
            defaultNegativePrompt: String = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
            profileRevision: Int = QNN_SD15_EXECUTION_PROFILE_REVISION
        ): ImageEngineExecutionProfileSpec = ImageEngineExecutionProfileSpec(
            profileId = profileId,
            profileRevision = profileRevision,
            family = ImageEngineModelFamily.SD15,
            variant = variant,
            tokenizer = clipTokenizer(
                ImageEngineTokenizerBackend.TOKENIZERS_CPP,
                supportsTextualInversion = true
            ),
            conditioning = imageConditioning(conditioningDataType, conversionStrategy, 768),
            scheduler = imageScheduler(
                algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
                predictionType = ImageEnginePredictionType.EPSILON,
                defaultSteps = steps,
                minSteps = if (variant == ImageEngineModelVariant.HYPER) 1 else 10,
                maxSteps = 50,
                timestepSpacing = ImageEngineTimestepSpacing.LEADING,
                order = 2
            ),
            vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
            graph = qnnGraph(
                textEncoder = "clip_v2.mnn",
                unet = "unet.bin",
                vae = "vae_decoder.bin",
                qnnSdk = "2.28",
                htpArch = 68,
                vaeEncoder = "vae_encoder.bin",
                workerStrategy = ImageEngineWorkerStrategy.SHARED_UNET_VAE,
                schedulerSidecar = null,
                tokenizerSidecar = null,
                runtimeAssets = QNN_SD15_CONDITIONING_RUNTIME_ASSETS
            ),
            defaults = ImageEngineGenerationDefaultsSpec(
                width = 512,
                height = 512,
                steps = steps,
                cfgScale = cfgScale,
                useCfg = true,
                defaultNegativePrompt = defaultNegativePrompt
            ),
            capabilities = fixedImageCapabilities(
                512,
                setOf(
                    ImageEngineSchedulerAlgorithm.DPMPP_2M,
                    ImageEngineSchedulerAlgorithm.EULER,
                    ImageEngineSchedulerAlgorithm.PNDM_PLMS
                ),
                supportsTextualInversion = true,
                supportsLivePreview = true
            ).copy(
                supportsUltraFix = true,
                ultraFixMinWidth = 512,
                ultraFixMaxWidth = 2_048,
                ultraFixMinHeight = 512,
                ultraFixMaxHeight = 2_048,
                ultraFixWidthMultiple = 64,
                ultraFixHeightMultiple = 64,
                ultraFixRequiredTileSize = 512
            )
        )

        private fun qnnSdxlExecutionProfile(
            profileId: String,
            variant: ImageEngineModelVariant = ImageEngineModelVariant.SDXL_BASE,
            steps: Int = 30,
            cfgScale: Double = 7.0,
            useCfg: Boolean = true,
            timestepSpacing: ImageEngineTimestepSpacing = ImageEngineTimestepSpacing.TRAILING,
            defaultNegativePrompt: String? = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT,
            supportsNegativePrompt: Boolean = true
        ): ImageEngineExecutionProfileSpec = ImageEngineExecutionProfileSpec(
            profileId = profileId,
            profileRevision = QNN_SDXL_EXECUTION_PROFILE_REVISION,
            family = ImageEngineModelFamily.SDXL,
            variant = variant,
            tokenizer = clipTokenizer(
                ImageEngineTokenizerBackend.TOKENIZERS_CPP,
                dualClip = true,
                supportsTextualInversion = true,
                separateNegativePrompt = supportsNegativePrompt
            ),
            conditioning = imageConditioning(
                ImageEngineEmbeddingDataType.FP16,
                ImageEngineEmbeddingConversionStrategy.NONE,
                2_048,
                dualEncoder = true,
                pooledOutput = true
            ),
            scheduler = imageScheduler(
                algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
                predictionType = ImageEnginePredictionType.EPSILON,
                defaultSteps = steps,
                minSteps = 1,
                maxSteps = 50,
                timestepSpacing = timestepSpacing,
                order = 2
            ),
            vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.13025, 1024),
            graph = qnnGraph(
                textEncoder = "clip.mnn",
                unet = "unet.bin",
                vae = "vae_decoder.bin",
                qnnSdk = "2.28",
                // SDXL packages can mix graph targets (for example V75 UNet
                // with V73 VAE). The package therefore has no single HTP
                // architecture admission value; native context metadata must
                // select transport per graph.
                htpArch = null,
                vaeEncoder = "vae_encoder.bin",
                workerStrategy = ImageEngineWorkerStrategy.SPLIT_UNET_VAE
            ),
            defaults = ImageEngineGenerationDefaultsSpec(
                width = 1024,
                height = 1024,
                steps = steps,
                cfgScale = cfgScale,
                useCfg = useCfg,
                defaultNegativePrompt = defaultNegativePrompt
            ),
            capabilities = fixedImageCapabilities(
                1024,
                setOf(
                    ImageEngineSchedulerAlgorithm.DPMPP_2M,
                    ImageEngineSchedulerAlgorithm.EULER
                ),
                supportsTextualInversion = true,
                supportsNegativePrompt = supportsNegativePrompt,
                // Split SDXL must tear down its UNet process before the VAE
                // phase; it cannot publish an in-process live preview.
                supportsLivePreview = false
            ).copy(
                supportsUltraFix = true,
                ultraFixMinWidth = 1_024,
                ultraFixMaxWidth = 2_048,
                ultraFixMinHeight = 1_024,
                ultraFixMaxHeight = 2_048,
                ultraFixWidthMultiple = 64,
                ultraFixHeightMultiple = 64,
                ultraFixRequiredTileSize = 1_024
            )
        )

        private fun qnnGen5ExecutionProfile(
            profileId: String,
            sd21: Boolean,
            controlNet: Boolean = false
        ): ImageEngineExecutionProfileSpec {
            val task = if (controlNet) ImageEngineTask.CONTROL_IMAGE else ImageEngineTask.TEXT_TO_IMAGE
            return ImageEngineExecutionProfileSpec(
                profileId = profileId,
                profileRevision = QNN_GEN5_EXECUTION_PROFILE_REVISION,
                family = if (sd21) ImageEngineModelFamily.SD21 else ImageEngineModelFamily.SD15,
                variant = when {
                    controlNet -> ImageEngineModelVariant.CONTROLNET_CANNY
                    sd21 -> ImageEngineModelVariant.SD21
                    else -> ImageEngineModelVariant.STANDARD
                },
                task = task,
                tokenizer = clipTokenizer(
                    ImageEngineTokenizerBackend.TOKENIZERS_CPP,
                    padZero = sd21,
                    supportsPromptWeighting = false
                ),
                conditioning = imageConditioning(
                    ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
                    ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
                    if (sd21) 1_024 else 768
                ),
                // These values are the effective pinned sidecar semantics, not
                // a class-name guess from the publisher scheduler JSON.
                scheduler = if (sd21) {
                    imageScheduler(
                        algorithm = ImageEngineSchedulerAlgorithm.DDIM,
                        predictionType = ImageEnginePredictionType.V_PREDICTION,
                        defaultSteps = 20,
                        minSteps = 1,
                        maxSteps = 100,
                        timestepSpacing = ImageEngineTimestepSpacing.LEADING,
                        stepsOffset = 1,
                        setAlphaToOne = false,
                        skipPrkSteps = true
                    )
                } else {
                    imageScheduler(
                        algorithm = ImageEngineSchedulerAlgorithm.EULER,
                        predictionType = ImageEnginePredictionType.EPSILON,
                        defaultSteps = 20,
                        minSteps = 1,
                        maxSteps = 100,
                        timestepSpacing = ImageEngineTimestepSpacing.LINSPACE,
                        stepsOffset = 1,
                        setAlphaToOne = false,
                        skipPrkSteps = true,
                        scaleModelInput = true
                    )
                },
                vae = imageVae(ImageEngineVaeScalingLocation.GRAPH_INTERNAL, 0.18215, 512),
                graph = qnnGraph(
                    textEncoder = "text_encoder.bin",
                    unet = "unet.bin",
                    vae = "vae.bin",
                    qnnSdk = "2.45.0.260326154327",
                    htpArch = 81,
                    controlNet = if (controlNet) "controlnet.bin" else null
                ),
                defaults = ImageEngineGenerationDefaultsSpec(
                    width = 512,
                    height = 512,
                    steps = 20,
                    cfgScale = 7.5,
                    useCfg = true,
                    defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
                ),
                capabilities = fixedImageCapabilities(
                    size = 512,
                    schedulers = setOf(
                        if (sd21) ImageEngineSchedulerAlgorithm.DDIM else ImageEngineSchedulerAlgorithm.EULER
                    ),
                    supportsPromptWeighting = false,
                    requiresControlImage = controlNet,
                    supportsLivePreview = true
                )
            )
        }

        private fun mnnSd15ExecutionProfile(): ImageEngineExecutionProfileSpec =
            ImageEngineExecutionProfileSpec(
                profileId = "mnn.sd15.official.512",
                family = ImageEngineModelFamily.SD15,
                variant = ImageEngineModelVariant.STANDARD,
                tokenizer = clipTokenizer(
                    ImageEngineTokenizerBackend.TOKENIZERS_CPP,
                    supportsPromptWeighting = false
                ),
                conditioning = imageConditioning(
                    ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
                    ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
                    768
                ),
                scheduler = imageScheduler(
                    algorithm = ImageEngineSchedulerAlgorithm.DPMPP_2M,
                    predictionType = ImageEnginePredictionType.EPSILON,
                    defaultSteps = 20,
                    minSteps = 10,
                    maxSteps = 50,
                    timestepSpacing = ImageEngineTimestepSpacing.LEADING,
                    order = 2
                ),
                vae = imageVae(ImageEngineVaeScalingLocation.HOST_BEFORE_GRAPH, 0.18215, 512),
                graph = ImageEngineGraphContractSpec(
                    textEncoder = "text_encoder.mnn",
                    unet = "unet.mnn",
                    vae = "vae_decoder.mnn",
                    workerStrategy = ImageEngineWorkerStrategy.IN_PROCESS
                ),
                defaults = ImageEngineGenerationDefaultsSpec(
                    width = 512,
                    height = 512,
                    steps = 20,
                    cfgScale = 7.0,
                    useCfg = true,
                    defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
                ),
                capabilities = fixedImageCapabilities(
                    512,
                    setOf(
                        ImageEngineSchedulerAlgorithm.DPMPP_2M,
                        ImageEngineSchedulerAlgorithm.EULER,
                        ImageEngineSchedulerAlgorithm.PNDM_PLMS
                    ),
                    supportsPromptWeighting = false
                )
            )

        private fun sanaEditExecutionProfile(): ImageEngineExecutionProfileSpec =
            ImageEngineExecutionProfileSpec(
                profileId = "mnn.sana-edit.v2",
                family = ImageEngineModelFamily.SANA,
                variant = ImageEngineModelVariant.SANA_EDIT,
                task = ImageEngineTask.IMAGE_EDIT,
                tokenizer = clipTokenizer(ImageEngineTokenizerBackend.MNN_MTOK, maxLength = 256),
                conditioning = imageConditioning(
                    ImageEngineEmbeddingDataType.GRAPH_INTERNAL,
                    ImageEngineEmbeddingConversionStrategy.GRAPH_EXECUTION,
                    width = 1,
                    maxLength = 256
                ),
                scheduler = imageScheduler(
                    algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
                    predictionType = ImageEnginePredictionType.FLOW,
                    defaultSteps = 10,
                    minSteps = 2,
                    maxSteps = 50
                ),
                vae = imageVae(ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0, 512),
                graph = ImageEngineGraphContractSpec(
                    textEncoder = "llm/llm.mnn",
                    unet = "transformer.mnn",
                    vae = "vae_decoder.mnn",
                    vaeEncoder = "vae_encoder.mnn",
                    configSidecars = listOf("llm/meta_queries.mnn"),
                    workerStrategy = ImageEngineWorkerStrategy.DEDICATED_WORKER
                ),
                defaults = ImageEngineGenerationDefaultsSpec(
                    width = 512,
                    height = 512,
                    steps = 10,
                    cfgScale = 4.5,
                    useCfg = true,
                    defaultNegativePrompt = RecommendedImageDefaults.EDIT_NEGATIVE_PROMPT
                ),
                capabilities = fixedImageCapabilities(
                    size = 512,
                    schedulers = setOf(ImageEngineSchedulerAlgorithm.FLOW_MATCH),
                    supportsPromptWeighting = false,
                    requiresInputImage = true,
                    supportsMask = false
                )
            )

        private fun stableDiffusionCppExecutionProfile(
            profileId: String,
            family: ImageEngineModelFamily,
            variant: ImageEngineModelVariant,
            steps: Int,
            cfgScale: Double,
            algorithm: ImageEngineSchedulerAlgorithm,
            size: Int = 512,
            defaultNegativePrompt: String? = null,
            supportsNegativePrompt: Boolean = true
        ): ImageEngineExecutionProfileSpec {
            val supportsStableExtensions = family in setOf(
                ImageEngineModelFamily.SD15,
                ImageEngineModelFamily.SD21,
                ImageEngineModelFamily.SDXL,
                ImageEngineModelFamily.SD_TURBO
            )
            return ImageEngineExecutionProfileSpec(
                profileId = profileId,
                profileRevision = if (supportsStableExtensions) 2 else 1,
                family = family,
                variant = variant,
                tokenizer = clipTokenizer(
                    ImageEngineTokenizerBackend.SDCPP_NATIVE,
                    supportsTextualInversion = supportsStableExtensions,
                    separateNegativePrompt = supportsNegativePrompt
                ),
                conditioning = imageConditioning(
                    ImageEngineEmbeddingDataType.RUNTIME_NATIVE,
                    ImageEngineEmbeddingConversionStrategy.RUNTIME_NATIVE,
                    1,
                    separateNegativePrompt = supportsNegativePrompt
                ),
                scheduler = imageScheduler(
                    algorithm = algorithm,
                    predictionType = if (algorithm == ImageEngineSchedulerAlgorithm.FLOW_MATCH) {
                        ImageEnginePredictionType.FLOW
                    } else {
                        ImageEnginePredictionType.EPSILON
                    },
                    defaultSteps = steps,
                    minSteps = 1,
                    maxSteps = 100
                ),
                vae = imageVae(ImageEngineVaeScalingLocation.RUNTIME_NATIVE, 1.0, size),
                graph = ImageEngineGraphContractSpec(workerStrategy = ImageEngineWorkerStrategy.IN_PROCESS),
                defaults = ImageEngineGenerationDefaultsSpec(
                    width = size,
                    height = size,
                    steps = steps,
                    cfgScale = cfgScale,
                    useCfg = stableDiffusionCppUsesCfg(cfgScale),
                    defaultNegativePrompt = defaultNegativePrompt
                ),
                capabilities = stableDiffusionCppCapabilities(
                    stableDiffusionCppSchedulers(algorithm),
                    family = family,
                    supportsNegativePrompt = supportsNegativePrompt
                )
            )
        }

        private fun recommendedImageExecutionProfile(
            recommendationId: String
        ): ImageEngineExecutionProfileSpec = when (recommendationId) {
            "cyberrealistic_sd15_qnn228" -> qnnSd15ExecutionProfile(
                profileId = "community.sd15.qnn228",
                defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
            )
            "dreamshaper_sd15_qnn228" -> qnnSd15ExecutionProfile(
                profileId = "community.sd15.qnn228",
                defaultNegativePrompt = RecommendedImageDefaults.SD15_NEGATIVE_PROMPT,
                profileRevision = QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION
            )
            "realisticvisionhyper_sd15_qnn228" -> qnnSd15ExecutionProfile(
                profileId = "community.sd15.hyper.qnn228",
                variant = ImageEngineModelVariant.HYPER,
                steps = 8,
                cfgScale = 2.0,
                defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
                profileRevision = QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION
            )
            "meinamix_sd15_qnn228" -> qnnSd15ExecutionProfile(
                profileId = "community.sd15.legacy-fp32.qnn228",
                variant = ImageEngineModelVariant.LEGACY_FP32,
                conditioningDataType = ImageEngineEmbeddingDataType.FP32,
                conversionStrategy = ImageEngineEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
                defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
            )
            "sdxl_base_qnn228" -> qnnSdxlExecutionProfile(
                profileId = "community.sdxl.base.qnn228",
                defaultNegativePrompt = RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
            )
            "animagine_xl_v4_qnn228" -> qnnSdxlExecutionProfile(
                profileId = "community.sdxl.base.qnn228",
                steps = RecommendedImageDefaults.ANIMAGINE_XL_STEPS,
                cfgScale = RecommendedImageDefaults.ANIMAGINE_XL_CFG,
                defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
            )
            "cyberrealisticxl_qnn228" -> qnnSdxlExecutionProfile(
                profileId = "community.sdxl.base.qnn228",
                steps = RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS,
                cfgScale = RecommendedImageDefaults.CYBERREALISTIC_XL_CFG,
                defaultNegativePrompt = RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
            )
            "realismsdxl_dmd2_alt_qnn228" -> qnnSdxlExecutionProfile(
                profileId = "community.sdxl.dmd2-alt.qnn228",
                variant = ImageEngineModelVariant.DMD2_ALT,
                steps = 4,
                cfgScale = 1.0,
                useCfg = false,
                timestepSpacing = ImageEngineTimestepSpacing.LINSPACE,
                defaultNegativePrompt = null,
                supportsNegativePrompt = false
            )
            "qualcomm_sd15_gen5_qnn" -> qnnGen5ExecutionProfile(
                profileId = "qualcomm.sd15.gen5.qnn245",
                sd21 = false
            )
            "qualcomm_sd21_gen5_qnn" -> qnnGen5ExecutionProfile(
                profileId = "qualcomm.sd21.gen5.qnn245",
                sd21 = true
            )
            "qualcomm_controlnet_canny_gen5_qnn" -> qnnGen5ExecutionProfile(
                profileId = "qualcomm.controlnet-canny.gen5.qnn245",
                sd21 = false,
                controlNet = true
            )
            "sd15_mnn_512_quality" -> mnnSd15ExecutionProfile()
            "mnn_sana_edit_v2" -> sanaEditExecutionProfile()
            "sd_turbo_512_experimental" -> stableDiffusionCppExecutionProfile(
                profileId = "sdcpp.sd-turbo",
                family = ImageEngineModelFamily.SD_TURBO,
                variant = ImageEngineModelVariant.SD_TURBO,
                steps = 4,
                // A zero native guidance scale selects the unconditional
                // branch. Distilled conditional-only execution uses 1.0 and
                // disables the negative CFG branch explicitly.
                cfgScale = 1.0,
                algorithm = ImageEngineSchedulerAlgorithm.EULER_A,
                supportsNegativePrompt = false
            )
            "z_image_turbo_q4" -> stableDiffusionCppExecutionProfile(
                profileId = "sdcpp.z-image-turbo",
                family = ImageEngineModelFamily.Z_IMAGE,
                variant = ImageEngineModelVariant.Z_IMAGE_TURBO,
                steps = 8,
                cfgScale = 1.0,
                algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
                supportsNegativePrompt = false
            )
            "flux2_klein_4b_q4" -> stableDiffusionCppExecutionProfile(
                profileId = "sdcpp.flux2-klein",
                family = ImageEngineModelFamily.FLUX,
                variant = ImageEngineModelVariant.FLUX2_KLEIN,
                // The recommended file is the distilled Klein checkpoint,
                // not the separately published Klein Base checkpoint.
                steps = 4,
                cfgScale = 1.0,
                algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
                size = 1024,
                supportsNegativePrompt = false
            )
            "qwen_image_2512_q2" -> stableDiffusionCppExecutionProfile(
                profileId = "sdcpp.qwen-image",
                family = ImageEngineModelFamily.QWEN_IMAGE,
                variant = ImageEngineModelVariant.QWEN_IMAGE,
                steps = 40,
                cfgScale = 2.5,
                algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
                size = 1024,
                defaultNegativePrompt = RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT
            )
            "longcat_image_q4" -> stableDiffusionCppExecutionProfile(
                profileId = "sdcpp.longcat-image",
                family = ImageEngineModelFamily.LONGCAT_IMAGE,
                variant = ImageEngineModelVariant.LONGCAT_IMAGE,
                steps = 20,
                cfgScale = 5.0,
                algorithm = ImageEngineSchedulerAlgorithm.FLOW_MATCH,
                size = 1024,
                defaultNegativePrompt = RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT
            )
            else -> error("Unknown catalog image execution profile: $recommendationId")
        }

        private fun qnnZipComponent(
            repoId: String,
            fileName: String,
            revision: String = "main",
            expectedSizeBytes: Long? = null,
            sha256: String? = null
        ): List<ImageEngineBundleComponentSpec> = listOf(
            ImageEngineBundleComponentSpec(
                role = ImageEngineBundleComponentRole.DIFFUSION,
                repoId = repoId,
                revision = revision,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                fileName = fileName,
                expectedSizeBytes = expectedSizeBytes,
                sha256 = sha256
            )
        )

        private data class QnnContextIdentity(
            val sizeBytes: Long,
            val sha256: String
        )

        private val QNN_SD15_VAE_ENCODER_IDENTITIES = mapOf(
            "cyberrealistic_sd15_qnn228" to QnnContextIdentity(
                sizeBytes = 58_870_768L,
                sha256 = "f2a5d073d0c4492361eb49005f03acd6ecdceba652c6fc7ba68eddd2b4d98da7"
            ),
            "realisticvisionhyper_sd15_qnn228" to QnnContextIdentity(
                sizeBytes = 58_862_576L,
                sha256 = "629797a9eb5204a2465fa993e9efa2546c60dce93d9bcd009ba7b06fc62ecf3b"
            ),
            "dreamshaper_sd15_qnn228" to QnnContextIdentity(
                sizeBytes = 58_862_576L,
                sha256 = "6baf4c28749e310404c1b079230cd47296d389fb4037f05267e589a50294bc66"
            ),
            "meinamix_sd15_qnn228" to QnnContextIdentity(
                sizeBytes = 41_438_176L,
                sha256 = "b32367e717c331cbacce7dc3482c7e5668dea90c8cc77396dee3761845d2bdd6"
            )
        )

        private fun sd15QnnSmokeSpecs(
            recommendationId: String
        ): List<ImageEngineQnnSmokeSpec> {
            val encoderIdentity = requireNotNull(
                QNN_SD15_VAE_ENCODER_IDENTITIES[recommendationId]
            ) { "Missing pinned SD1.5 VAE encoder identity for $recommendationId." }
            return listOf(
                ImageEngineQnnSmokeSpec(
                    graphName = "model",
                    contextBinary = "unet.bin",
                    width = 512,
                    height = 512,
                    steps = 1,
                    timeoutSeconds = 180,
                    prompt = "a small ceramic cup on a bright wooden desk",
                    inputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("sample", "uint16", listOf(1, 4, 64, 64)),
                        ImageEngineQnnSmokeTensorSpec("timestamp", "int32", listOf(1)),
                        ImageEngineQnnSmokeTensorSpec("text_embedding", "uint16", listOf(1, 77, 768))
                    ),
                    outputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 4, 64, 64), role = "output")
                    )
                ),
                ImageEngineQnnSmokeSpec(
                    graphName = "model",
                    contextBinary = "vae_decoder.bin",
                    width = 512,
                    height = 512,
                    steps = 1,
                    timeoutSeconds = 180,
                    prompt = "vae decoder smoke",
                    inputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("input", "uint16", listOf(1, 4, 64, 64))
                    ),
                    outputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("output", "uint16", listOf(1, 3, 512, 512), role = "output")
                    )
                ),
                ImageEngineQnnSmokeSpec(
                    graphName = "model",
                    contextBinary = "vae_encoder.bin",
                    expectedContextSizeBytes = encoderIdentity.sizeBytes,
                    expectedContextSha256 = encoderIdentity.sha256,
                    width = 512,
                    height = 512,
                    steps = 1,
                    timeoutSeconds = 180,
                    prompt = "vae encoder smoke",
                    inputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("input", "uint16", listOf(1, 3, 512, 512))
                    ),
                    outputs = listOf(
                        ImageEngineQnnSmokeTensorSpec("mean", "uint16", listOf(1, 4, 64, 64), role = "output"),
                        ImageEngineQnnSmokeTensorSpec("std", "uint16", listOf(1, 4, 64, 64), role = "output")
                    )
                )
            )
        }

        private fun sdxlQnnSmokeSpecs(): List<ImageEngineQnnSmokeSpec> = listOf(
            ImageEngineQnnSmokeSpec(
                graphName = "model",
                contextBinary = "unet.bin",
                width = 1024,
                height = 1024,
                steps = 1,
                timeoutSeconds = 300,
                prompt = "a clean product photo of a ceramic cup on a wooden desk",
                inputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("sample", "float32", listOf(1, 4, 128, 128)),
                    ImageEngineQnnSmokeTensorSpec("encoder_hidden_states", "float32", listOf(1, 77, 2048)),
                    ImageEngineQnnSmokeTensorSpec("timestamp", "int32", listOf(1)),
                    ImageEngineQnnSmokeTensorSpec("time_ids", "float32", listOf(1, 6)),
                    ImageEngineQnnSmokeTensorSpec("text_embeds", "float32", listOf(1, 1280))
                ),
                outputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("output", "float32", listOf(1, 4, 128, 128), role = "output")
                )
            ),
            ImageEngineQnnSmokeSpec(
                graphName = "model",
                contextBinary = "vae_decoder.bin",
                // Published SDXL packages carry a 512 decoder graph. The
                // production SDXL path decodes a 128x128 latent through
                // overlapping 64x64 tiles and merges the 512x512 outputs.
                width = 512,
                height = 512,
                steps = 1,
                timeoutSeconds = 300,
                prompt = "sdxl vae decoder smoke",
                inputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("input", "float32", listOf(1, 4, 64, 64))
                ),
                outputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("output", "float32", listOf(1, 3, 512, 512), role = "output")
                )
            ),
            ImageEngineQnnSmokeSpec(
                graphName = "model",
                contextBinary = "vae_encoder.bin",
                width = 1024,
                height = 1024,
                steps = 1,
                timeoutSeconds = 300,
                prompt = "sdxl vae encoder smoke",
                inputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("input", "float32", listOf(1, 3, 1024, 1024))
                ),
                outputs = listOf(
                    ImageEngineQnnSmokeTensorSpec("mean", "float32", listOf(1, 4, 128, 128), role = "output"),
                    ImageEngineQnnSmokeTensorSpec("std", "float32", listOf(1, 4, 128, 128), role = "output")
                )
            )
        )

        private fun sd15QnnBundle(
            id: String,
            title: String,
            repoId: String,
            fileName: String,
            revision: String = "main",
            expectedSizeBytes: Long? = null,
            sha256: String? = null,
            completeBundleRuntime: Boolean = true,
            minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN1
        ): ImageEngineBundleSpec {
            val profile = recommendedImageExecutionProfile(id)
            return ImageEngineBundleSpec(
                id = id,
                title = title,
                components = qnnZipComponent(repoId, fileName, revision, expectedSizeBytes, sha256),
                recommendationId = id,
                runtime = ImageEngineBundleRuntime.QNN_HTP,
                accelerator = ImageEngineAccelerator.QNN_HTP,
                minDeviceTier = minDeviceTier,
                requiresQnnRuntime = true,
                requiresSmokeTest = true,
                smokeSpec = ImageEngineSmokeSpec(
                    width = profile.defaults.width,
                    height = profile.defaults.height,
                    steps = profile.defaults.steps,
                    timeoutSeconds = 240
                ),
                qnnSmokeSpecs = sd15QnnSmokeSpecs(id),
                requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
                    qnnSdk = "2.28",
                    // The `min` context targets the publisher's broadest hardware
                    // baseline. A self-contained archive may also carry several
                    // physical-device transports; installation resolves the exact
                    // local HTP profile instead of treating this fallback as a
                    // device-admission rule.
                    htpArch = 68,
                    completeBundleRuntime = completeBundleRuntime
                ),
                executionProfile = profile
            )
        }

        private fun sdxlQnnBundle(
            id: String,
            title: String,
            repoId: String,
            fileName: String,
            revision: String = "main",
            expectedSizeBytes: Long? = null,
            sha256: String? = null
        ): ImageEngineBundleSpec {
            val recommendationId = id.removeSuffix("_bundle")
            val profile = recommendedImageExecutionProfile(recommendationId)
            return ImageEngineBundleSpec(
                id = id,
                title = title,
                components = qnnZipComponent(repoId, fileName, revision, expectedSizeBytes, sha256),
                recommendationId = recommendationId,
                runtime = ImageEngineBundleRuntime.QNN_HTP,
                accelerator = ImageEngineAccelerator.QNN_HTP,
                minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN3,
                requiresQnnRuntime = true,
                requiresSmokeTest = true,
                smokeSpec = ImageEngineSmokeSpec(
                    width = profile.defaults.width,
                    height = profile.defaults.height,
                    steps = profile.defaults.steps,
                    timeoutSeconds = 360
                ),
                qnnSmokeSpecs = sdxlQnnSmokeSpecs(),
                executionProfile = profile
            )
        }

        private fun gen5QnnBundle(
            id: String,
            title: String,
            repoId: String,
            fileName: String,
            revision: String,
            useSd21Sidecars: Boolean = false,
            task: ImageEngineTask = ImageEngineTask.TEXT_TO_IMAGE
        ): ImageEngineBundleSpec {
            val recommendationId = id.removeSuffix("_bundle")
            val profile = recommendedImageExecutionProfile(recommendationId)
            return ImageEngineBundleSpec(
                id = id,
                title = title,
                components = qnnZipComponent(
                    repoId = repoId,
                    fileName = fileName,
                    revision = revision,
                    expectedSizeBytes = QAIRT_IMAGE_RELEASE_ASSET_SIZE_BYTES[recommendationId]
                        ?: error("Missing pinned Gen5 QNN archive size for $recommendationId."),
                    sha256 = QAIRT_IMAGE_RELEASE_ASSET_SHA256[recommendationId]
                        ?: error("Missing pinned Gen5 QNN archive SHA-256 for $recommendationId.")
                ) + gen5TokenizerSidecars(useSd21Sidecars),
                recommendationId = recommendationId,
                task = task,
                runtime = ImageEngineBundleRuntime.QNN_HTP,
                accelerator = ImageEngineAccelerator.QNN_HTP,
                minDeviceTier = ImageEngineMinDeviceTier.SNAPDRAGON_8_ELITE,
                requiresQnnRuntime = true,
                requiresSmokeTest = true,
                smokeSpec = ImageEngineSmokeSpec(
                    width = profile.defaults.width,
                    height = profile.defaults.height,
                    steps = profile.defaults.steps,
                    timeoutSeconds = 300
                ),
                requiredRuntimeProfile = ImageEngineQnnRuntimeProfileSpec(
                    qnnSdk = "2.45.0.260326154327",
                    htpArch = 81,
                    completeBundleRuntime = false
                ),
                executionProfile = profile
            )
        }

        private fun gen5TokenizerSidecars(useSd21: Boolean): List<ImageEngineBundleComponentSpec> {
            val repoId = if (useSd21) {
                "sd2-community/stable-diffusion-2-1"
            } else {
                "stable-diffusion-v1-5/stable-diffusion-v1-5"
            }
            val revision = if (useSd21) {
                "bb2154823665391b4fb29b0b9cf82a198964ee05"
            } else {
                "451f4fe16113bff5a5d2269ed5ad43b0592e9a14"
            }
            val metadata = if (useSd21) {
                listOf(
                    Triple("scheduler/scheduler_config.json", 345L, "4cd9b9597ca64549df35016ca02bd3450ecbac70ccd8b0465b018be4ba54fe4b"),
                    Triple("tokenizer/merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                    Triple("tokenizer/special_tokens_map.json", 460L, "f118ab3a983206e4f32583448de6bd6aae4ee21869135cef1f5848a753cdaab6"),
                    Triple("tokenizer/tokenizer_config.json", 824L, "87a3154f0990fd992fd59f9d42c39520155b3d77cd543efe3f2bf011726f379d"),
                    Triple("tokenizer/vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349")
                )
            } else {
                listOf(
                    Triple("scheduler/scheduler_config.json", 308L, "699cce92eb7c122e2eb7dfdea78e6187fda76a5ed4a8e42319b85610e620e091"),
                    Triple("tokenizer/merges.txt", 524_619L, "9fd691f7c8039210e0fced15865466c65820d09b63988b0174bfe25de299051a"),
                    Triple("tokenizer/special_tokens_map.json", 472L, "c4864a9376a8401918425bed71fc14fc0e81f9b59ec45c1cf96cccb2df508eac"),
                    Triple("tokenizer/tokenizer_config.json", 806L, "00439066fcba73de57644cf41e4e3b9f2dbb09d7f3fc2005898ba52399045882"),
                    Triple("tokenizer/vocab.json", 1_059_962L, "e089ad92ba36837a0d31433e555c8f45fe601ab5c221d4f607ded32d9f7a4349")
                )
            }
            val repositorySidecars = metadata.map { (path, size, sha) ->
                ImageEngineBundleComponentSpec(
                    role = if (path.startsWith("tokenizer/")) {
                        ImageEngineBundleComponentRole.TOKENIZER
                    } else {
                        ImageEngineBundleComponentRole.CONFIG
                    },
                    repoId = repoId,
                    revision = revision,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    fileName = path,
                    expectedSizeBytes = size,
                    sha256 = sha,
                    relativePath = path
                )
            }
            // The publisher repositories expose the legacy vocab/merges pair but not a
            // complete tokenizer.json. Pin the canonical CLIP tokenizer contract so
            // Android can execute normalization, pre-tokenization, BPE and post-processing
            // through the standard tokenizer backend instead of a handwritten approximation.
            return repositorySidecars + ImageEngineBundleComponentSpec(
                role = ImageEngineBundleComponentRole.TOKENIZER,
                repoId = "openai/clip-vit-large-patch14",
                revision = "32bd64288804d66eefd0ccbe215aa642df71cc41",
                provider = ModelRepositoryProvider.HUGGING_FACE,
                fileName = "tokenizer.json",
                expectedSizeBytes = 2_224_003L,
                sha256 = "a83e0809aa4c3af7208b2df632a7a69668c6d48775b3c3fe4e1b1199d1f8b8f4",
                relativePath = "tokenizer/tokenizer.json"
            )
        }

        private val QAIRT_MOBILE_CHIPSETS = setOf("SM8750", "SM8750P", "SM8850", "SM8850P")
        private val SD15_QNN_CHIPSETS = setOf(
            "SM8350",
            "SM8450", "SM8475",
            "SM8550", "SM8550P", "QCS8550", "QCM8550",
            "SM8635", "SM8650", "SM8650P",
            "SM8750", "SM8750P",
            "SM8850", "SM8850P"
        )
        private val SDXL_QNN_CHIPSETS = setOf("SM8650", "SM8650P", "SM8750", "SM8750P")
        private val GEN5_QNN_CHIPSETS = setOf("SM8850", "SM8850P")

        private val DEFAULT_RECOMMENDED_MODELS = listOf(
            ModelScopeRecommendedModel(
                id = "qwen35_08b_uncensored_mnn",
                title = "Qwen3.5-0.8B 低拒答实验版 · MNN",
                repoId = "darkmaniac7/Qwen3.5-0.8B-uncensored-MNN",
                revision = QWEN35_08B_UNCENSORED_MNN_REVISION,
                description = "社区 MNN 低拒答实验包；发布者声明其基于 Huihui Qwen3.5 0.8B abliterated 权重转换。完整包含视觉组件，实际图文能力以本机 native load 与 smoke 结果为准。",
                recommendedFileName = "config.json",
                parameterScale = "0.8B",
                quant = "MNN",
                minRamGb = 4,
                tags = listOf("低拒答实验", "低内存", "Qwen3.5", "MNN", "Hugging Face"),
                priority = 0,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "qwen35_08b_uncensored_mnn_bundle",
                    title = "Qwen3.5 0.8B 低拒答实验 MNN",
                    repoId = "darkmaniac7/Qwen3.5-0.8B-uncensored-MNN",
                    revision = QWEN35_08B_UNCENSORED_MNN_REVISION,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    components = qwen35CommunityMnnComponents(
                        tokenizerFileName = "tokenizer.mtok",
                        requiresVision = true
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_2b_q4",
                title = "Qwen3.5-2B MNN",
                repoId = "MNN/Qwen3.5-2B-MNN",
                revision = QWEN35_2B_MNN_REVISION,
                description = "轻量中文多模态聊天进阶档；完整包加载 visual 组件后即可在兼容 ARM64 设备发送图片。",
                recommendedFileName = "config.json",
                parameterScale = "2B",
                quant = "MNN",
                minRamGb = 6,
                tags = listOf("轻量", "纯文本已验证", "Qwen3.5", "MNN", "ModelScope"),
                priority = 1,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.RECOMMENDED,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "qwen35_2b_mnn_bundle",
                    title = "Qwen3.5 2B MNN",
                    repoId = "MNN/Qwen3.5-2B-MNN",
                    revision = QWEN35_2B_MNN_REVISION
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_2b_abliterated_gguf",
                title = "Qwen3.5-2B 低拒答实验版 · GGUF + mmproj",
                repoId = "mradermacher/Huihui-Qwen3.5-2B-abliterated-GGUF",
                revision = QWEN35_2B_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 图文实验包；发布者标记为 uncensored。主模型与匹配 mmproj-f16 会一起安装，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "Huihui-Qwen3.5-2B-abliterated.Q4_K_M.gguf",
                parameterScale = "2B",
                quant = "Q4_K_M",
                minRamGb = 6,
                tags = listOf("低拒答实验", "图文聊天", "Qwen3.5", "GGUF", "Hugging Face"),
                priority = 1,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = communityLowRefusalGgufVisionBundle(
                    id = "qwen35_2b_abliterated_gguf_vision_bundle",
                    title = "Qwen3.5 2B 低拒答实验图文包",
                    repoId = "mradermacher/Huihui-Qwen3.5-2B-abliterated-GGUF",
                    revision = QWEN35_2B_ABLITERATED_GGUF_REVISION,
                    mainFileName = "Huihui-Qwen3.5-2B-abliterated.Q4_K_M.gguf",
                    projectorFileName = "Huihui-Qwen3.5-2B-abliterated.mmproj-f16.gguf"
                )
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e2b_iq4",
                title = "Gemma 4 E2B IT MNN",
                repoId = "MNN/gemma-4-E2B-it-MNN",
                revision = GEMMA4_E2B_MNN_REVISION,
                description = "移动端友好的多语种轻量模型。下载时安装正式文本组件并关闭未兼容的视觉/音频处理器。",
                recommendedFileName = "config.json",
                parameterScale = "E2B",
                quant = "MNN",
                minRamGb = 6,
                tags = listOf("Gemma 4", "多语种", "MNN", "ModelScope"),
                priority = 2,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.RECOMMENDED,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "gemma4_e2b_mnn_bundle",
                    title = "Gemma 4 E2B it MNN",
                    repoId = "MNN/gemma-4-E2B-it-MNN",
                    revision = GEMMA4_E2B_MNN_REVISION,
                    installProfile = MnnModelBundleInstallProfile.TEXT_ONLY,
                    components = gemmaTextOnlyMnnComponents()
                )
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e2b_uncensored_gguf",
                title = "Gemma 4 E2B IT 低拒答实验版 · GGUF",
                repoId = "TrevorJS/gemma-4-E2B-it-uncensored-GGUF",
                revision = GEMMA4_E2B_UNCENSORED_GGUF_REVISION,
                description = "社区 norm-preserving abliterated GGUF 文本实验包；发布者提供低拒答评测信息。仅提供文本聊天，仍须遵守上游 Gemma 许可条款，实际加载以本机 native runtime 为准。",
                recommendedFileName = "gemma-4-E2B-it-uncensored-Q4_K_M.gguf",
                parameterScale = "E2B",
                quant = "Q4_K_M",
                minRamGb = 6,
                tags = listOf("低拒答实验", "Gemma 4", "文本聊天", "GGUF", "Hugging Face"),
                priority = 2,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF
            ),
            ModelScopeRecommendedModel(
                id = "bitcpm4_cann_3b_tq2",
                title = "BitCPM4-CANN 3B TQ2",
                repoId = "OpenBMB/BitCPM-CANN-3B-gguf",
                description = "OpenBMB 侧端取向模型，低精度量化更适合手机内存预算。",
                recommendedFileName = "bitcpm4-3b-tq2_0.gguf",
                parameterScale = "3B",
                quant = "TQ2_0",
                minRamGb = 6,
                tags = listOf("中文", "侧端", "OpenBMB", "ModelScope"),
                priority = 2,
                visibleInRecommendations = false,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "bitcpm4_cann_1b_tq2",
                title = "BitCPM4-CANN 1B TQ2",
                repoId = "OpenBMB/BitCPM-CANN-1B-gguf",
                description = "更小的 BitCPM-CANN 入门档，适合极低内存设备测试本地能力。",
                recommendedFileName = "bitcpm4-1b-tq2_0.gguf",
                parameterScale = "1B",
                quant = "TQ2_0",
                minRamGb = 4,
                tags = listOf("低内存", "中文", "OpenBMB", "ModelScope"),
                priority = 3,
                visibleInRecommendations = false,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_vl_4b_qairt_w4a16",
                title = "Qwen3-VL-4B-Instruct",
                repoId = "qualcomm/Qwen3-VL-4B-Instruct",
                revision = "main",
                description = "旗舰 NPU 图文首选，同一模型直接完成聊天和图片理解。",
                recommendedFileName = "qwen3_vl_4b_instruct-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5.zip",
                parameterScale = "4B",
                quant = "w4a16 QAIRT",
                minRamGb = 12,
                tags = listOf("图文聊天", "QNN", "QAIRT", "骁龙 NPU", "Qualcomm"),
                priority = 0,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.RECOMMENDED,
                supportedChipsetCodes = QAIRT_MOBILE_CHIPSETS,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                chatRuntime = RecommendedChatRuntime.GENIEX_QAIRT
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_vl_4b_abliterated_gguf",
                title = "Qwen3-VL-4B-Instruct 低拒答实验版 · GGUF + mmproj",
                repoId = "mradermacher/Huihui-Qwen3-VL-4B-Instruct-abliterated-GGUF",
                revision = QWEN3_VL_4B_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 图文实验包；发布者标记为 uncensored。主模型与匹配 mmproj-f16 会一起安装，不依赖 QAIRT 或设备白名单，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "Huihui-Qwen3-VL-4B-Instruct-abliterated.Q4_K_M.gguf",
                parameterScale = "4B",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("低拒答实验", "图文聊天", "Qwen3-VL", "GGUF", "Hugging Face"),
                priority = 1,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = communityLowRefusalGgufVisionBundle(
                    id = "qwen3_vl_4b_abliterated_gguf_vision_bundle",
                    title = "Qwen3-VL 4B 低拒答实验图文包",
                    repoId = "mradermacher/Huihui-Qwen3-VL-4B-Instruct-abliterated-GGUF",
                    revision = QWEN3_VL_4B_ABLITERATED_GGUF_REVISION,
                    mainFileName = "Huihui-Qwen3-VL-4B-Instruct-abliterated.Q4_K_M.gguf",
                    projectorFileName = "Huihui-Qwen3-VL-4B-Instruct-abliterated.mmproj-f16.gguf"
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_4b_2507_qairt_w4a16",
                title = "Qwen3-4B-Instruct-2507",
                repoId = "qualcomm/Qwen3-4B-Instruct-2507",
                revision = "main",
                description = "NPU 纯文本速度实验档，不支持图片输入；适合旗舰设备复测稳定性。",
                recommendedFileName = "qwen3_4b_instruct_2507-geniex_qairt-w4a16.zip",
                parameterScale = "4B",
                quant = "w4a16 QAIRT",
                minRamGb = 16,
                tags = listOf("纯文本", "中文", "QNN", "QAIRT", "骁龙 NPU", "Qualcomm"),
                priority = 1,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.RECOMMENDED,
                supportedChipsetCodes = QAIRT_MOBILE_CHIPSETS,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                chatRuntime = RecommendedChatRuntime.GENIEX_QAIRT
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_4b_2507_abliterated_gguf",
                title = "Qwen3-4B-Instruct-2507 低拒答实验版 · GGUF",
                repoId = "mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-GGUF",
                revision = QWEN3_4B_2507_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 文本实验包；发布者标记为 uncensored。该包不含视觉投影器，不进入 QAIRT 路径，实际加载与生成以本机 llama.cpp runtime 为准。",
                recommendedFileName = "Huihui-Qwen3-4B-Instruct-2507-abliterated.Q4_K_M.gguf",
                parameterScale = "4B",
                quant = "Q4_K_M",
                minRamGb = 6,
                tags = listOf("低拒答实验", "文本聊天", "Qwen3", "GGUF", "Hugging Face"),
                priority = 2,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_4b_uncensored_mnn",
                title = "Qwen3.5-4B 低拒答实验版 · MNN",
                repoId = "darkmaniac7/Qwen3.5-4B-uncensored-MNN",
                revision = QWEN35_4B_UNCENSORED_MNN_REVISION,
                description = "社区 MNN 低拒答实验包；发布者声明其基于 Huihui Qwen3.5 4B abliterated 权重转换。该固定包不含视觉图，当前只提供本地文本聊天，实际加载结果以 native runtime 为准。",
                recommendedFileName = "config.json",
                parameterScale = "4B",
                quant = "MNN",
                minRamGb = 8,
                tags = listOf("低拒答实验", "Qwen3.5", "MNN", "Hugging Face"),
                priority = 0,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "qwen35_4b_uncensored_mnn_bundle",
                    title = "Qwen3.5 4B 低拒答实验 MNN",
                    repoId = "darkmaniac7/Qwen3.5-4B-uncensored-MNN",
                    revision = QWEN35_4B_UNCENSORED_MNN_REVISION,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    installProfile = MnnModelBundleInstallProfile.TEXT_ONLY,
                    components = qwen35CommunityMnnComponents(tokenizerFileName = "tokenizer.txt")
                )
            ),
            ModelScopeRecommendedModel(
                id = "bitcpm4_cann_8b_tq2",
                title = "BitCPM4-CANN 8B TQ2",
                repoId = "OpenBMB/BitCPM-CANN-8B-gguf",
                description = "主力中文侧端模型，使用低精度量化降低内存压力，适合中高端手机尝试。",
                recommendedFileName = "bitcpm4-8b-tq2_0.gguf",
                parameterScale = "8B",
                quant = "TQ2_0",
                minRamGb = 8,
                tags = listOf("中文", "侧端", "OpenBMB", "ModelScope"),
                priority = 1,
                visibleInRecommendations = false,
                group = ModelScopeRecommendedGroup.MAIN_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "minicpm_v46_q4",
                title = "MiniCPM-V 4.6 Q4_K_M + mmproj",
                repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                description = "支持文本与图片输入的本地多模态聊天模型；下载主模型和匹配 mmproj 后，可直接在聊天页发送图片进行理解。",
                recommendedFileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
                parameterScale = "V-4.6",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("多模态聊天", "图片输入", "中文", "OpenBMB", "ModelScope"),
                priority = 1,
                visibleInRecommendations = false,
                kind = ModelScopeRecommendedKind.CHAT,
                status = RecommendedModelStatus.RECOMMENDED,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                visionModelBundle = VisionModelBundleSpec(
                    id = "minicpm_v46_q4_vision_bundle",
                    title = "MiniCPM-V 4.6 Q4 多模态聊天包",
                    runtime = VisionModelBundleRuntime.GGUF_MMPROJ,
                    accelerator = VisionModelAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresQnnRuntime = false,
                    requiresSmokeTest = true,
                    smokeSpec = VisionModelSmokeSpec(
                        imageWidth = 448,
                        imageHeight = 448,
                        prompt = "请用中文描述这张图片",
                        timeoutSeconds = 120
                    ),
                    components = listOf(
                        VisionModelBundleComponentSpec(
                            role = VisionModelBundleComponentRole.MAIN_MODEL,
                            repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "MiniCPM-V-4_6-Q4_K_M.gguf"
                        ),
                        VisionModelBundleComponentSpec(
                            role = VisionModelBundleComponentRole.PROJECTOR,
                            repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "mmproj-model-f16.gguf"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "minicpm_v46_abliterated_gguf",
                title = "MiniCPM-V 4.6 低拒答实验版 · GGUF + mmproj",
                repoId = "mradermacher/Huihui-MiniCPM-V-4.6-abliterated-GGUF",
                revision = MINICPM_V46_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 图文实验包；发布者标记为 uncensored。主模型与匹配 mmproj-f16 会作为同一闭包下载，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "Huihui-MiniCPM-V-4.6-abliterated.Q4_K_M.gguf",
                parameterScale = "V-4.6",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("低拒答实验", "图文聊天", "MiniCPM-V", "GGUF", "Hugging Face"),
                priority = 3,
                kind = ModelScopeRecommendedKind.CHAT,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = communityLowRefusalGgufVisionBundle(
                    id = "minicpm_v46_abliterated_gguf_vision_bundle",
                    title = "MiniCPM-V 4.6 低拒答实验图文包",
                    repoId = "mradermacher/Huihui-MiniCPM-V-4.6-abliterated-GGUF",
                    revision = MINICPM_V46_ABLITERATED_GGUF_REVISION,
                    mainFileName = "Huihui-MiniCPM-V-4.6-abliterated.Q4_K_M.gguf",
                    projectorFileName = "Huihui-MiniCPM-V-4.6-abliterated.mmproj-f16.gguf"
                )
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e4b_iq4",
                title = "Gemma 4 E4B IT MNN",
                repoId = "MNN/gemma-4-E4B-it-MNN",
                revision = GEMMA4_E4B_MNN_REVISION,
                description = "Gemma 4 中档多语种模型。下载时安装正式文本组件并关闭未兼容的视觉/音频处理器。",
                recommendedFileName = "config.json",
                parameterScale = "E4B",
                quant = "MNN",
                minRamGb = 8,
                tags = listOf("Gemma 4", "多语种", "MNN", "ModelScope"),
                priority = 2,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.RECOMMENDED,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "gemma4_e4b_mnn_bundle",
                    title = "Gemma 4 E4B it MNN",
                    repoId = "MNN/gemma-4-E4B-it-MNN",
                    revision = GEMMA4_E4B_MNN_REVISION,
                    installProfile = MnnModelBundleInstallProfile.TEXT_ONLY,
                    components = gemmaTextOnlyMnnComponents()
                )
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e4b_uncensored_gguf",
                title = "Gemma 4 E4B IT 低拒答实验版 · GGUF",
                repoId = "TrevorJS/gemma-4-E4B-it-uncensored-GGUF",
                revision = GEMMA4_E4B_UNCENSORED_GGUF_REVISION,
                description = "社区 norm-preserving abliterated GGUF 文本实验包；发布者提供低拒答评测信息。仅提供文本聊天，仍须遵守上游 Gemma 许可条款，实际加载以本机 native runtime 为准。",
                recommendedFileName = "gemma-4-E4B-it-uncensored-Q4_K_M.gguf",
                parameterScale = "E4B",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("低拒答实验", "Gemma 4", "文本聊天", "GGUF", "Hugging Face"),
                priority = 4,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.MAIN_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_9b_uncensored_mnn",
                title = "Qwen3.5-9B 低拒答实验版 · MNN",
                repoId = "darkmaniac7/Qwen3.5-9B-uncensored-MNN",
                revision = QWEN35_9B_UNCENSORED_MNN_REVISION,
                description = "社区 MNN 低拒答实验包；发布者声明其基于 Huihui Qwen3.5 9B abliterated 权重转换。包内必须连同独立 embedding 权重安装，当前只提供本地文本聊天，实际加载结果以 native runtime 为准。",
                recommendedFileName = "config.json",
                parameterScale = "9B",
                quant = "MNN",
                minRamGb = 12,
                tags = listOf("低拒答实验", "高质量", "Qwen3.5", "MNN", "Hugging Face"),
                priority = 0,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "qwen35_9b_uncensored_mnn_bundle",
                    title = "Qwen3.5 9B 低拒答实验 MNN",
                    repoId = "darkmaniac7/Qwen3.5-9B-uncensored-MNN",
                    revision = QWEN35_9B_UNCENSORED_MNN_REVISION,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    installProfile = MnnModelBundleInstallProfile.TEXT_ONLY,
                    components = qwen35CommunityMnnComponents(
                        tokenizerFileName = "tokenizer.txt",
                        requiresEmbeddingFile = true
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_8b_qairt_w4a16",
                title = "Qwen3-8B",
                repoId = "qualcomm/Qwen3-8B",
                revision = "main",
                description = "NPU 纯文本高质量实验档，不支持图片输入；仅建议 24GB 级旗舰验证。",
                recommendedFileName = "qwen3_8b-geniex_qairt-w4a16.zip",
                parameterScale = "8B",
                quant = "w4a16 QAIRT",
                minRamGb = 24,
                tags = listOf("纯文本", "高质量", "中文", "QNN", "QAIRT", "骁龙 NPU", "Qualcomm"),
                priority = 2,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = QAIRT_MOBILE_CHIPSETS,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                chatRuntime = RecommendedChatRuntime.GENIEX_QAIRT
            ),
            ModelScopeRecommendedModel(
                id = "qwen3_8b_abliterated_gguf",
                title = "Qwen3-8B 低拒答实验版 · GGUF",
                repoId = "mradermacher/Huihui-Qwen3-8B-abliterated-v2-GGUF",
                revision = QWEN3_8B_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated v2 GGUF 文本实验包；发布者标记为 uncensored。该包不含视觉投影器，不进入 QAIRT 路径，实际加载与生成以本机 llama.cpp runtime 为准。",
                recommendedFileName = "Huihui-Qwen3-8B-abliterated-v2.Q4_K_M.gguf",
                parameterScale = "8B",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("低拒答实验", "高质量", "文本聊天", "Qwen3", "GGUF", "Hugging Face"),
                priority = 1,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF
            ),

            ModelScopeRecommendedModel(
                id = "qwen25_vl_7b_qairt_w4a16",
                title = "Qwen2.5-VL-7B-Instruct",
                repoId = "qualcomm/Qwen2.5-VL-7B-Instruct",
                revision = "main",
                description = "高内存 NPU 图文实验档，适合 24GB 级旗舰验证。",
                recommendedFileName = "qwen2_5_vl_7b_instruct-geniex_qairt-w4a16-qualcomm_snapdragon_8_elite_gen5.zip",
                parameterScale = "7B",
                quant = "w4a16 QAIRT",
                minRamGb = 24,
                tags = listOf("图文聊天", "高内存", "QNN", "QAIRT", "Qualcomm"),
                priority = 3,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = QAIRT_MOBILE_CHIPSETS,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                chatRuntime = RecommendedChatRuntime.GENIEX_QAIRT
            ),
            ModelScopeRecommendedModel(
                id = "qwen25_vl_7b_abliterated_gguf",
                title = "Qwen2.5-VL-7B-Instruct 低拒答实验版 · GGUF + mmproj",
                repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-abliterated-GGUF",
                revision = QWEN25_VL_7B_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 图文实验包；发布者标记为 uncensored。主模型与匹配 mmproj-f16 会一起安装，不依赖 QAIRT 或设备白名单，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "Qwen2.5-VL-7B-Instruct-abliterated.Q4_K_M.gguf",
                parameterScale = "7B",
                quant = "Q4_K_M",
                minRamGb = 12,
                tags = listOf("低拒答实验", "图文聊天", "Qwen2.5-VL", "GGUF", "Hugging Face"),
                priority = 3,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = communityLowRefusalGgufVisionBundle(
                    id = "qwen25_vl_7b_abliterated_gguf_vision_bundle",
                    title = "Qwen2.5-VL 7B 低拒答实验图文包",
                    repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-abliterated-GGUF",
                    revision = QWEN25_VL_7B_ABLITERATED_GGUF_REVISION,
                    mainFileName = "Qwen2.5-VL-7B-Instruct-abliterated.Q4_K_M.gguf",
                    projectorFileName = "Qwen2.5-VL-7B-Instruct-abliterated.mmproj-f16.gguf",
                    smokeTimeoutSeconds = 240
                )
            ),

            ModelScopeRecommendedModel(
                id = "glm47_flash_tq1",
                title = "GLM-4.7-Flash TQ1",
                repoId = "unsloth/GLM-4.7-Flash-GGUF",
                description = "高质量中文/通用聊天的超低内存实验档，优先保证侧端能加载，质量低于 IQ4/Q4。",
                recommendedFileName = "GLM-4.7-Flash-UD-TQ1_0.gguf",
                parameterScale = "Flash",
                quant = "TQ1_0",
                minRamGb = 10,
                tags = listOf("智谱", "超低内存", "实验", "ModelScope"),
                priority = 2,
                visibleInRecommendations = false,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_35b_a3b_iq2_xxs",
                title = "Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-I-Nano.gguf",
                repoId = "mudler/Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-GGUF",
                revision = "cc768c55deb10d6d08727cf66b856e9950ef0720",
                description = "第三方推理蒸馏 MoE GGUF 实验模型；可直接下载，APEX MTP 加速尚未在当前 llama.cpp 链路验收。",
                recommendedFileName = "Qwen3.6-35B-A3B-Claude-4.7-Opus-Reasoning-Distilled-APEX-MTP-I-Nano.gguf",
                parameterScale = "35B-A3B",
                quant = "APEX MTP I-Nano",
                minRamGb = 12,
                tags = listOf("MoE", "推理蒸馏", "GGUF", "APEX MTP", "第三方"),
                priority = 1,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.MODELSCOPE,
                chatRuntime = RecommendedChatRuntime.GGUF
            ),
            ModelScopeRecommendedModel(
                id = "qwen36_35b_a3b_abliterated_mnn",
                title = "Qwen3.6-35B-A3B 低拒答实验版 · MNN",
                repoId = "darkmaniac7/Qwen3.6-35B-A3B-abliterated-MNN",
                revision = QWEN36_35B_A3B_ABLITERATED_MNN_REVISION,
                description = "社区 abliterated 4-bit HQQ MNN 图文实验包；发布者标记为 uncensored，完整包包含视觉塔与 embedding。该大模型仅作实验下载，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "config.json",
                parameterScale = "35B-A3B",
                quant = "4-bit HQQ MNN",
                minRamGb = 32,
                tags = listOf("低拒答实验", "MoE", "图文聊天", "MNN", "Hugging Face"),
                priority = 2,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.MNN,
                mnnModelBundle = MnnModelBundleSpec(
                    id = "qwen36_35b_a3b_abliterated_mnn_bundle",
                    title = "Qwen3.6 35B-A3B 低拒答实验 MNN 图文包",
                    repoId = "darkmaniac7/Qwen3.6-35B-A3B-abliterated-MNN",
                    revision = QWEN36_35B_A3B_ABLITERATED_MNN_REVISION,
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    components = qwen35CommunityMnnComponents(
                        tokenizerFileName = "tokenizer.mtok",
                        requiresEmbeddingFile = true,
                        requiresVision = true
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "google_gemma4_26b_a4b_iq2_xxs",
                title = "google_gemma-4-26B-A4B-it-IQ2_XXS.gguf",
                repoId = "bartowski/google_gemma-4-26B-A4B-it-GGUF",
                revision = "fabed3e586120477355eea23b92644540a79ce2f",
                description = "Gemma 4 低内存 MoE GGUF 实验模型；主模型可独立下载，图片理解同时安装匹配的 mmproj-F16.gguf。",
                recommendedFileName = "google_gemma-4-26B-A4B-it-IQ2_XXS.gguf",
                parameterScale = "26B-A4B",
                quant = "IQ2_XXS",
                minRamGb = 12,
                tags = listOf("Gemma 4", "MoE", "GGUF", "多模态", "mmproj"),
                priority = 2,
                visibleInRecommendations = false,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = VisionModelBundleSpec(
                    id = "gemma4_26b_a4b_iq2_xxs_vision_bundle",
                    title = "Gemma 4 26B-A4B IQ2_XXS 多模态包",
                    runtime = VisionModelBundleRuntime.GGUF_MMPROJ,
                    accelerator = VisionModelAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresQnnRuntime = false,
                    requiresSmokeTest = true,
                    downloadProjectorByDefault = false,
                    smokeSpec = VisionModelSmokeSpec(
                        imageWidth = 896,
                        imageHeight = 896,
                        prompt = "请用中文描述这张图片",
                        timeoutSeconds = 300
                    ),
                    components = listOf(
                        VisionModelBundleComponentSpec(
                            role = VisionModelBundleComponentRole.MAIN_MODEL,
                            repoId = "bartowski/google_gemma-4-26B-A4B-it-GGUF",
                            revision = "fabed3e586120477355eea23b92644540a79ce2f",
                            provider = ModelRepositoryProvider.HUGGING_FACE,
                            fileName = "google_gemma-4-26B-A4B-it-IQ2_XXS.gguf"
                        ),
                        VisionModelBundleComponentSpec(
                            role = VisionModelBundleComponentRole.PROJECTOR,
                            repoId = "bartowski/google_gemma-4-26B-A4B-it-GGUF",
                            revision = "fabed3e586120477355eea23b92644540a79ce2f",
                            provider = ModelRepositoryProvider.HUGGING_FACE,
                            fileName = "mmproj-google_gemma-4-26B-A4B-it-f16.gguf"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_26b_a4b_abliterated_gguf",
                title = "Gemma 4 26B-A4B 低拒答实验版 · GGUF + mmproj",
                repoId = "mradermacher/Huihui-gemma-4-26B-A4B-it-abliterated-GGUF",
                revision = GEMMA4_26B_A4B_ABLITERATED_GGUF_REVISION,
                description = "社区 abliterated GGUF 图文实验包；主模型与匹配 mmproj-f16 会一起安装。仓库仍受上游 Google Gemma 4 许可条款约束，实际图文兼容性以本机 native load 与 smoke 为准。",
                recommendedFileName = "Huihui-gemma-4-26B-A4B-it-abliterated.Q4_K_M.gguf",
                parameterScale = "26B-A4B",
                quant = "Q4_K_M",
                minRamGb = 24,
                tags = listOf("低拒答实验", "Gemma 4", "MoE", "图文聊天", "GGUF", "Hugging Face"),
                priority = 4,
                status = RecommendedModelStatus.EXPERIMENTAL,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadPolicy = RecommendedModelDownloadPolicy.ALL_DEVICES,
                chatRuntime = RecommendedChatRuntime.GGUF,
                visionModelBundle = communityLowRefusalGgufVisionBundle(
                    id = "gemma4_26b_a4b_abliterated_gguf_vision_bundle",
                    title = "Gemma 4 26B-A4B 低拒答实验图文包",
                    repoId = "mradermacher/Huihui-gemma-4-26B-A4B-it-abliterated-GGUF",
                    revision = GEMMA4_26B_A4B_ABLITERATED_GGUF_REVISION,
                    mainFileName = "Huihui-gemma-4-26B-A4B-it-abliterated.Q4_K_M.gguf",
                    projectorFileName = "Huihui-gemma-4-26B-A4B-it-abliterated.mmproj-f16.gguf",
                    smokeImageSize = 896,
                    smokeTimeoutSeconds = 300
                )
            ),
            ModelScopeRecommendedModel(
                id = "cyberrealistic_sd15_qnn228",
                title = "CyberRealistic SD1.5 QNN 2.28",
                repoId = "Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28",
                revision = "162fe0a46cb3f9017b9e2bc003eb168e8bbf4b04",
                description = "写实方向的 SD1.5 QNN 2.28 包，使用 QNN UNet/VAE 与 MNN clip_v2 conditioning。下载后校验完整展开的运行资源；首次真实 native load 与 graph execution 决定兼容性。",
                recommendedFileName = "cyberrealistic_final_qnn2.28_min.zip",
                parameterScale = "SD1.5",
                quant = "QNN 2.28",
                minRamGb = 8,
                tags = listOf("本地生图", "骁龙 NPU", "QNN UNet/VAE", "MNN clip_v2", "SD1.5"),
                priority = 0,
                kind = ModelScopeRecommendedKind.IMAGE,
                supportedChipsetCodes = SD15_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                localImageEngineTier = LocalImageEngineTier.QUICK,
                imageEngineBundle = sd15QnnBundle(
                    id = "cyberrealistic_sd15_qnn228",
                    title = "CyberRealistic SD1.5 QNN 2.28",
                    repoId = "Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28",
                    fileName = "cyberrealistic_final_qnn2.28_min.zip",
                    revision = "162fe0a46cb3f9017b9e2bc003eb168e8bbf4b04",
                    expectedSizeBytes = 1_007_066_161L,
                    sha256 = "9daf0e4d80d14ae93c774faf5366702c58b0cdb71618d5e5130b54226936bf3f",
                    // This pinned archive contains the portable model graphs
                    // but no QNN host/Skel/Stub files. Runtime discovery must
                    // therefore use the app/OEM generic path and let the real
                    // isolated graph smoke decide compatibility.
                    completeBundleRuntime = false
                )
            ),
            ModelScopeRecommendedModel(
                id = "realisticvisionhyper_sd15_qnn228",
                title = "RealisticVision Hyper SD1.5 QNN 2.28",
                repoId = "Mr-J-369/RealisticVisionHyper-SD1.5-qnn2.28",
                revision = "92a2e40d65a47a6b8aa3ee86ffffdc0ed2b0b66b",
                description = "写实人像和生活摄影方向的 SD1.5 Hyper QNN 2.28 包，默认 8-step、CFG 2.0，使用 QNN UNet/VAE 与 MNN clip_v2 conditioning。下载后校验完整展开的运行资源；首次真实 native load 与 graph execution 决定兼容性。",
                recommendedFileName = "RealisticVisionHyper-qnn2.28-min.zip",
                parameterScale = "SD1.5",
                quant = "QNN 2.28",
                minRamGb = 8,
                tags = listOf("本地生图", "骁龙 NPU", "QNN UNet/VAE", "MNN clip_v2", "写实"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.RECOMMENDED,
                supportedChipsetCodes = SD15_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                localImageEngineTier = LocalImageEngineTier.STANDARD,
                imageEngineBundle = sd15QnnBundle(
                    id = "realisticvisionhyper_sd15_qnn228",
                    title = "RealisticVision Hyper SD1.5 QNN 2.28",
                    repoId = "Mr-J-369/RealisticVisionHyper-SD1.5-qnn2.28",
                    fileName = "RealisticVisionHyper-qnn2.28-min.zip",
                    revision = "92a2e40d65a47a6b8aa3ee86ffffdc0ed2b0b66b",
                    expectedSizeBytes = 1_258_546_529L,
                    sha256 = "7f552ad7f9070f1e482d93d3785ceedd6f3fc1d437db9c5da00d81d9edd34b86"
                )
            ),
            ModelScopeRecommendedModel(
                id = "dreamshaper_sd15_qnn228",
                title = "DreamShaper SD1.5 QNN 2.28",
                repoId = "Mr-J-369/DreamShaper-SD1.5-qnn2.28",
                revision = "2338d013c60981b3bd565ce39d4a731bcf9ebfef",
                description = "通用创意风格的 SD1.5 QNN 2.28 包，覆盖插画、概念图和轻写实场景，使用 QNN UNet/VAE 与 MNN clip_v2 conditioning。下载后校验完整展开的运行资源；首次真实 native load 与 graph execution 决定兼容性。",
                recommendedFileName = "DreamShaperV8-qnn2.28-min.zip",
                parameterScale = "SD1.5",
                quant = "QNN 2.28",
                minRamGb = 8,
                tags = listOf("本地生图", "骁龙 NPU", "QNN UNet/VAE", "MNN clip_v2", "通用创意"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.RECOMMENDED,
                supportedChipsetCodes = SD15_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                localImageEngineTier = LocalImageEngineTier.STANDARD,
                imageEngineBundle = sd15QnnBundle(
                    id = "dreamshaper_sd15_qnn228",
                    title = "DreamShaper SD1.5 QNN 2.28",
                    repoId = "Mr-J-369/DreamShaper-SD1.5-qnn2.28",
                    fileName = "DreamShaperV8-qnn2.28-min.zip",
                    revision = "2338d013c60981b3bd565ce39d4a731bcf9ebfef",
                    expectedSizeBytes = 1_258_568_521L,
                    sha256 = "e4fbd2a28db64b038372d1847d82b66f2f754ed0e95d412a283104b9382ae59c"
                )
            ),
            ModelScopeRecommendedModel(
                id = "meinamix_sd15_qnn228",
                title = "MeinaMix SD1.5 QNN 2.28",
                repoId = "Mr-J-369/MeinaMix-SD1.5-qnn2.28",
                revision = "17d26a779cf2a53acc6caf0345c663767c293c5a",
                description = "动漫与插画方向的 SD1.5 QNN 包。适合作为本地 NPU 生图的风格化补充模型。",
                recommendedFileName = "MeinaMix-qnn2.28-8gen2.zip",
                parameterScale = "SD1.5",
                quant = "QNN 2.28",
                minRamGb = 8,
                tags = listOf("本地生图", "骁龙 NPU", "QNN", "动漫插画", "实验"),
                priority = 4,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = SD15_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                localImageEngineTier = LocalImageEngineTier.COMPACT_QUALITY,
                imageEngineBundle = sd15QnnBundle(
                    id = "meinamix_sd15_qnn228",
                    title = "MeinaMix SD1.5 QNN 2.28",
                    repoId = "Mr-J-369/MeinaMix-SD1.5-qnn2.28",
                    fileName = "MeinaMix-qnn2.28-8gen2.zip",
                    revision = "17d26a779cf2a53acc6caf0345c663767c293c5a",
                    expectedSizeBytes = 1_228_483_146L,
                    sha256 = "120aa73eb843bf24a96fe067baa2c4f97fb3a95620c36caffe080ed7f4503d09"
                ).copy(requiredRuntimeProfile = null)
            ),
            ModelScopeRecommendedModel(
                id = "sdxl_base_qnn228",
                title = "SDXL Base QNN 2.28",
                repoId = "xororz/sdxl-qnn",
                revision = "ead90f4635e21e7412b8200a5efd220b0193beeb",
                description = "通用基础 SDXL QNN 完整 1024×1024 包；双 CLIP、多步 UNet 与分进程 VAE tile 链路已接线，首次运行由真实 native graph 结果确认兼容性。",
                recommendedFileName = "sdxl_base_qnn2.28_8gen3.zip",
                parameterScale = "SDXL",
                quant = "QNN 2.28",
                minRamGb = 12,
                tags = listOf("本地生图", "骁龙 NPU", "QNN", "SDXL", "通用基础"),
                priority = 0,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = SDXL_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = sdxlQnnBundle(
                    id = "sdxl_base_qnn228_bundle",
                    title = "SDXL Base QNN 2.28",
                    repoId = "xororz/sdxl-qnn",
                    fileName = "sdxl_base_qnn2.28_8gen3.zip",
                    revision = "ead90f4635e21e7412b8200a5efd220b0193beeb",
                    expectedSizeBytes = 3_753_226_114L,
                    sha256 = "426e36987fd3b84dd05255cb12bc5463c427c8b55598bd3b2486a72291d6be7f"
                )
            ),
            ModelScopeRecommendedModel(
                id = "realismsdxl_dmd2_alt_qnn228",
                title = "RealismSDXL DMD2 ALT QNN 2.28",
                repoId = "Mr-J-369/RealismByStableYogiV8.0_DMD2_ALT-SDXL-qnn2.28",
                revision = "ab203b4d41e42bd01073e19dcd478d7b231780d2",
                description = "写实方向的 SDXL DMD2 ALT 少步包；默认 4-step、CFG 1.0 单分支，完整 UNet timetable 与 VAE tile 链路已接线。",
                recommendedFileName = "realismSDXLByStable_v80DMD2ALT_qnn2.28_8gen3.zip",
                parameterScale = "SDXL",
                quant = "QNN 2.28 DMD2 ALT",
                minRamGb = 12,
                tags = listOf("本地生图", "骁龙 NPU", "QNN", "SDXL", "写实", "少步生成"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = SDXL_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = sdxlQnnBundle(
                    id = "realismsdxl_dmd2_alt_qnn228_bundle",
                    title = "RealismSDXL DMD2 ALT QNN 2.28",
                    repoId = "Mr-J-369/RealismByStableYogiV8.0_DMD2_ALT-SDXL-qnn2.28",
                    fileName = "realismSDXLByStable_v80DMD2ALT_qnn2.28_8gen3.zip",
                    revision = "ab203b4d41e42bd01073e19dcd478d7b231780d2",
                    expectedSizeBytes = 3_648_575_124L,
                    sha256 = "e95df91391f1f6f6f39416985ada906fec77d65496d3f52f54feb0c3da3744e8"
                )
            ),
            ModelScopeRecommendedModel(
                id = "animagine_xl_v4_qnn228",
                title = "Animagine XL v4 QNN 2.28",
                repoId = "YuuiKurata/animagineXL_qnn2.28",
                revision = "43de36d441380fc9cc34f25c1d01bbf74c8776b7",
                description = "动漫与插画方向的 SDXL QNN 包；默认 1024×1024、28-step、CFG 5.0，并使用动漫模型专属负面提示词。",
                recommendedFileName = "animagineXL40_v4Opt_qnn2.28_8gen3.zip",
                parameterScale = "SDXL",
                quant = "QNN 2.28",
                minRamGb = 12,
                tags = listOf("本地生图", "骁龙 NPU", "QNN", "SDXL", "动漫", "插画"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                supportedChipsetCodes = SDXL_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = sdxlQnnBundle(
                    id = "animagine_xl_v4_qnn228_bundle",
                    title = "Animagine XL v4 QNN 2.28",
                    repoId = "YuuiKurata/animagineXL_qnn2.28",
                    fileName = "animagineXL40_v4Opt_qnn2.28_8gen3.zip",
                    revision = "43de36d441380fc9cc34f25c1d01bbf74c8776b7",
                    expectedSizeBytes = 3_751_928_835L,
                    sha256 = "a08612048ad60e834ae7f5a1b234cfb7edd299e28dc20abab1a4a9be5bf34dfc"
                )
            ),
            ModelScopeRecommendedModel(
                id = "cyberrealisticxl_qnn228",
                title = "CyberRealisticXL SDXL QNN 2.28",
                repoId = "xororz/sdxl-qnn",
                revision = "ead90f4635e21e7412b8200a5efd220b0193beeb",
                description = "写实摄影方向的 1024×1024 SDXL QNN 实验包；历史真机曾在旧的整张 VAE 输入路径暴露 UNet [1,4,128,128] 与 VAE [1,4,64,64] 形状差异。当前 native 已接入 3×3、共 9 次的 64×64 latent 分块解码与重叠融合兼容路径，但此固定 ZIP 尚待生产 MainActivity 与认证 Local API 复验。下载保持开放且不会静默切换模型。",
                recommendedFileName = "cyber_realistic_v10_qnn2.28_8gen3.zip",
                parameterScale = "SDXL",
                quant = "QNN 2.28",
                minRamGb = 12,
                tags = listOf("本地生图", "骁龙 NPU", "QNN", "SDXL", "高端实验"),
                priority = 3,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                supportedChipsetCodes = SDXL_QNN_CHIPSETS,
                downloadPolicy = RecommendedModelDownloadPolicy.ANY_SNAPDRAGON,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = sdxlQnnBundle(
                    id = "cyberrealisticxl_qnn228",
                    title = "CyberRealisticXL QNN 2.28",
                    repoId = "xororz/sdxl-qnn",
                    fileName = "cyber_realistic_v10_qnn2.28_8gen3.zip",
                    revision = "ead90f4635e21e7412b8200a5efd220b0193beeb",
                    expectedSizeBytes = 3_745_235_842L,
                    sha256 = "2af39e9c80629a27406112e91627657981b50f28b477e7adaf9415d886e08ea2"
                )
            ),
            ModelScopeRecommendedModel(
                id = "qualcomm_sd15_gen5_qnn",
                title = "Qualcomm Stable Diffusion 1.5 · 骁龙 8 Elite Gen 5",
                repoId = "qualcomm/Stable-Diffusion-v1.5",
                revision = "1815ed2af65018733338c37efacf62310e74bc94",
                description = "Qualcomm 固定发布的 Gen5 SD1.5 QNN 包；目录已声明 text encoder、UNet、VAE 资产与执行 profile，尚无生产 MainActivity 和认证 Local API 的真机出图证据。下载保持开放，首次运行以真实 native load/graph execution 结果为准。",
                recommendedFileName = "stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                parameterScale = "SD1.5",
                quant = "w8a16 QAIRT 2.45",
                minRamGb = 12,
                tags = listOf("写实", "通用生图", "Gen5", "QNN", "骁龙 NPU", "Qualcomm"),
                priority = 0,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                supportedChipsetCodes = GEN5_QNN_CHIPSETS,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.QUICK,
                imageEngineBundle = gen5QnnBundle(
                    id = "qualcomm_sd15_gen5_qnn_bundle",
                    title = "Qualcomm Stable Diffusion 1.5 Gen5 QNN",
                    repoId = "qualcomm/Stable-Diffusion-v1.5",
                    fileName = "stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                    revision = "1815ed2af65018733338c37efacf62310e74bc94"
                )
            ),
            ModelScopeRecommendedModel(
                id = "qualcomm_sd21_gen5_qnn",
                title = "Qualcomm Stable Diffusion 2.1 · 骁龙 8 Elite Gen 5",
                repoId = "qualcomm/Stable-Diffusion-v2.1",
                revision = "5c79668b496a31d4570b06d5b2919ea393166b36",
                description = "Qualcomm 固定发布的 Gen5 SD2.1 QNN 包；目录已声明 text encoder、UNet、VAE 资产与执行 profile，尚无生产 MainActivity 和认证 Local API 的真机出图证据。下载保持开放，首次运行以真实 native load/graph execution 结果为准。",
                recommendedFileName = "stable_diffusion_v2_1-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                parameterScale = "SD2.1",
                quant = "w8a16 QAIRT 2.45",
                minRamGb = 12,
                tags = listOf("艺术风格", "通用生图", "Gen5", "QNN", "骁龙 NPU", "Qualcomm"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                supportedChipsetCodes = GEN5_QNN_CHIPSETS,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.STANDARD,
                imageEngineBundle = gen5QnnBundle(
                    id = "qualcomm_sd21_gen5_qnn_bundle",
                    title = "Qualcomm Stable Diffusion 2.1 Gen5 QNN",
                    repoId = "qualcomm/Stable-Diffusion-v2.1",
                    fileName = "stable_diffusion_v2_1-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                    revision = "5c79668b496a31d4570b06d5b2919ea393166b36",
                    useSd21Sidecars = true
                )
            ),
            ModelScopeRecommendedModel(
                id = "qualcomm_controlnet_canny_gen5_qnn",
                title = "Qualcomm ControlNet Canny · 骁龙 8 Elite Gen 5",
                repoId = "qualcomm/ControlNet-Canny",
                revision = "2e0b3bb550cad49caf0f2e135d1f67bced02e61e",
                description = "Qualcomm 固定发布的 Gen5 ControlNet Canny QNN 包；目录已声明控制图任务与 graph 组件，Canny 预处理、residual 注入及强度参数的产品输入链尚无生产 UI/API 真机执行证据。下载保持开放，首次运行以真实 native graph 结果为准。",
                recommendedFileName = "controlnet_canny-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                parameterScale = "ControlNet",
                quant = "w8a16 QAIRT 2.45",
                minRamGb = 12,
                tags = listOf("边缘控制", "控制图生图", "Gen5", "QNN", "骁龙 NPU", "Qualcomm"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                supportedChipsetCodes = GEN5_QNN_CHIPSETS,
                provider = ModelRepositoryProvider.HUGGING_FACE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = gen5QnnBundle(
                    id = "qualcomm_controlnet_canny_gen5_qnn_bundle",
                    title = "Qualcomm ControlNet Canny Gen5 QNN",
                    repoId = "qualcomm/ControlNet-Canny",
                    fileName = "controlnet_canny-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip",
                    revision = "2e0b3bb550cad49caf0f2e135d1f67bced02e61e",
                    task = ImageEngineTask.CONTROL_IMAGE
                )
            ),
            ModelScopeRecommendedModel(
                id = "sd15_mnn_512_quality",
                title = "Stable Diffusion 1.5 · MNN 512×512",
                repoId = "MNN/stable-diffusion-v1-5-mnn-opencl",
                revision = SD15_MNN_REVISION,
                description = "真机 direct + OpenCL 512×512、20-step 文生图链路已完成技术闭环；机器人提示词语义通过，车辆提示词仍有颜色和车型偏差。发布仓库不含 VAE encoder，因此该推荐包不宣称 img2img 或 inpaint 能力。允许实验下载和手动选择，但不能设为默认引擎。",
                recommendedFileName = "unet.mnn",
                parameterScale = "SD1.5",
                quant = "MNN",
                minRamGb = 8,
                tags = listOf("本地生图", "MNN", "direct + OpenCL", "512×512", "实验", "module 不推荐", "ModelScope"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                downloadable = true,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "sd15_mnn_bundle",
                    title = "MNN SD1.5 512 实验包",
                    components = stableDiffusion15MnnComponents(),
                    recommendationId = "sd15_mnn_512_quality",
                    runtime = ImageEngineBundleRuntime.MNN_DIFFUSION,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 512, height = 512, steps = 20, timeoutSeconds = 600),
                    executionProfile = recommendedImageExecutionProfile("sd15_mnn_512_quality")
                )
            ),
            ModelScopeRecommendedModel(
                id = "sd_turbo_512_experimental",
                title = "Stable Diffusion Turbo · 512×512",
                repoId = "AI-ModelScope/sd-turbo",
                revision = "dc8a205ed5961a45a1b99c2913a194e616bd284b",
                description = "Stable Diffusion 2.1 蒸馏文生图模型；当前目录默认 512×512、4-step、CFG 1.0、Euler ancestral。现有归档仅证明 debug worker 以 1-step/Euler 产图，尚未证明当前预设在生产 MainActivity 与认证 Local API 的闭环。下载保持开放，不会自动设为默认引擎。",
                recommendedFileName = "sd_turbo.safetensors",
                parameterScale = "SD-Turbo",
                quant = "FP16",
                minRamGb = 8,
                tags = listOf("本地生图", "Stable Diffusion Turbo", "当前 512 四步预设", "CPU", "ModelScope", "实验"),
                priority = 0,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                provider = ModelRepositoryProvider.MODELSCOPE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.STANDARD,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "sd_turbo_512_experimental_bundle",
                    title = "Stable Diffusion Turbo 512 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "AI-ModelScope/sd-turbo",
                            revision = "dc8a205ed5961a45a1b99c2913a194e616bd284b",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "sd_turbo.safetensors",
                            expectedSizeBytes = 5_214_561_328L,
                            sha256 = "3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549",
                            relativePath = "sd_turbo.safetensors"
                        )
                    ),
                    recommendationId = "sd_turbo_512_experimental",
                    runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 512, height = 512, steps = 4, timeoutSeconds = 600),
                    executionProfile = recommendedImageExecutionProfile("sd_turbo_512_experimental"),
                    // This is a single complete checkpoint, not a split bundle
                    // and not a separately verified 384×384 preset.
                    modelFamily = "SD_TURBO"
                )
            ),
            ModelScopeRecommendedModel(
                id = "mnn_sana_edit_v2",
                title = "Sana Edit V2 · MNN",
                repoId = "MNN/MNN-Sana-Edit-V2",
                revision = SANA_EDIT_V2_REVISION,
                description = "ModelScope 官方 MNN Sana 卡通风格图像编辑包；目录声明默认 512×512、10-step、CFG 4.5，并完整下载 llm、VAE encoder、transformer 与 VAE decoder。当前尚无生产 MainActivity 与认证 Local API 的真实编辑证据；下载保持开放，首次运行以真实 native 结果为准。",
                recommendedFileName = "transformer.mnn",
                parameterScale = "Sana Edit V2",
                quant = "MNN",
                minRamGb = 8,
                tags = listOf("本地图像编辑", "Sana", "MNN", "ModelScope", "512x512", "VAE Encoder"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                provider = ModelRepositoryProvider.MODELSCOPE,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "mnn_sana_edit_v2_bundle",
                    title = "MNN Sana Edit V2 图像编辑包",
                    components = sanaEditV2MnnComponents(),
                    recommendationId = "mnn_sana_edit_v2",
                    task = ImageEngineTask.IMAGE_EDIT,
                    runtime = ImageEngineBundleRuntime.MNN_DIFFUSION,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(
                        width = 512,
                        height = 512,
                        steps = 10,
                        timeoutSeconds = 600,
                        prompt = ""
                    ),
                    executionProfile = recommendedImageExecutionProfile("mnn_sana_edit_v2")
                )
            ),
            ModelScopeRecommendedModel(
                id = "z_image_turbo_q4",
                title = "Z-Image Turbo · Q2_K GGUF",
                repoId = "hf/leejet-Z-Image-Turbo-GGUF",
                description = "Z-Image Turbo 完整三组件包，包含 Q2_K diffusion、匹配 VAE 和 Qwen3 文本编码器；按 stable-diffusion.cpp 上游执行 8 次 DiT、CFG 1.0 单分支。",
                recommendedFileName = "z_image_turbo-Q2_K.gguf",
                parameterScale = "6B",
                quant = "Q2_K",
                minRamGb = 8,
                tags = listOf("本地生图", "Z-Image", "Turbo", "备用实验", "ModelScope"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.LARGE_QUALITY,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "z_image_turbo_q2_bundle",
                    title = "Z-Image Turbo Q2_K 引擎包",
                    recommendationId = "z_image_turbo_q4",
                    runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 512, height = 512, steps = 8, timeoutSeconds = 600),
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "hf/leejet-Z-Image-Turbo-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "z_image_turbo-Q2_K.gguf",
                            expectedSizeBytes = 2_592_442_304L,
                            sha256 = "a9cf1b0368e24c2f9d542d2951c01f6f7fc85ed8c9ed39b5b37b15375508d58a"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/z_image_turbo",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/ae.safetensors",
                            expectedSizeBytes = 335_304_388L,
                            sha256 = "afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
                            expectedSizeBytes = 2_497_281_120L,
                            sha256 = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597"
                        )
                    ),
                    executionProfile = recommendedImageExecutionProfile("z_image_turbo_q4"),
                    modelFamily = "Z_IMAGE"
                )
            ),
            ModelScopeRecommendedModel(
                id = "flux2_klein_4b_q4",
                title = "FLUX.2 Klein 4B",
                repoId = "hf/leejet-FLUX.2-klein-4B-GGUF",
                description = "FLUX.2 Klein 4B 蒸馏版完整本地引擎包，一次下载主模型、匹配 VAE 与 Qwen3 文本编码器；默认 1024×1024、4-step、CFG 1.0 单分支。",
                recommendedFileName = "flux-2-klein-4b-Q4_0.gguf",
                parameterScale = "4B",
                quant = "Q4_0",
                minRamGb = 8,
                tags = listOf("本地生图", "FLUX.2", "画质实验", "GGUF", "ModelScope"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                visibleInRecommendations = true,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.COMPACT_QUALITY,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "flux2_klein_4b_q4_bundle",
                    title = "FLUX.2 Klein 4B Q4 引擎包",
                    recommendationId = "flux2_klein_4b_q4",
                    runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 1024, height = 1024, steps = 4, timeoutSeconds = 900),
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "hf/leejet-FLUX.2-klein-4B-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "flux-2-klein-4b-Q4_0.gguf",
                            expectedSizeBytes = 2_460_378_560L,
                            sha256 = "d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/flux2-klein-4B",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/flux2-vae.safetensors",
                            expectedSizeBytes = 336_211_292L,
                            sha256 = "868fe7b343cc8f3a19dbcfcafbc3d5f888802be3f89bd81b65b3621a066ce8f3"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen3-4B-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen3-4B-Q4_K_M.gguf",
                            expectedSizeBytes = 2_497_281_312L,
                            sha256 = "f6f851777709861056efcdad3af01da38b31223a3ba26e61a4f8bf3a2195813a"
                        )
                    ),
                    executionProfile = recommendedImageExecutionProfile("flux2_klein_4b_q4")
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen_image_2512_q2",
                title = "Qwen-Image 2512 · Q2_K GGUF",
                repoId = "unsloth/Qwen-Image-2512-GGUF",
                description = "Qwen-Image 低内存完整三组件包，一次下载 diffusion 主模型、匹配 VAE 和 Qwen2.5-VL 文本编码器；按 2512 的 stable-diffusion.cpp 工作流默认 1024×1024、40-step、CFG 2.5。",
                recommendedFileName = "qwen-image-2512-Q2_K.gguf",
                parameterScale = "Image",
                quant = "Q2_K",
                minRamGb = 12,
                tags = listOf("本地生图", "Qwen-Image", "前沿观察", "ModelScope"),
                priority = 4,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "qwen_image_2512_q2_bundle",
                    title = "Qwen-Image 2512 Q2 引擎包",
                    recommendationId = "qwen_image_2512_q2",
                    runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 1024, height = 1024, steps = 40, timeoutSeconds = 1200),
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "unsloth/Qwen-Image-2512-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "qwen-image-2512-Q2_K.gguf",
                            expectedSizeBytes = 7_333_837_344L,
                            sha256 = "176678f0d4e6c613c5a318014f16d829438b8feec9454bde7b3070a520bf1728"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/Qwen-Image_ComfyUI",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/qwen_image_vae.safetensors",
                            expectedSizeBytes = 253_806_246L,
                            sha256 = "a70580f0213e67967ee9c95f05bb400e8fb08307e017a924bf3441223e023d1f"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen2.5-VL-7B-Instruct-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                            expectedSizeBytes = 4_683_072_384L,
                            sha256 = "d16776dcd9a28d42758c2958ed3a752aabf20a305252cd64ff2be72b4a78c503"
                        )
                    ),
                    executionProfile = recommendedImageExecutionProfile("qwen_image_2512_q2"),
                    modelFamily = "QWEN_IMAGE"
                )
            ),
            ModelScopeRecommendedModel(
                id = "longcat_image_q4",
                title = "LongCat-Image · Q4_0 GGUF",
                repoId = "vantagewithai/LongCat-Image-GGUF",
                description = "LongCat-Image 完整三组件包，一次下载 diffusion 主模型、匹配 FLUX VAE 和 Qwen2.5-VL 文本编码器；默认 1024×1024、20-step、CFG 5.0。",
                recommendedFileName = "LongCat-Image-Q4_0.gguf",
                parameterScale = "Image",
                quant = "Q4_0",
                minRamGb = 12,
                tags = listOf("本地生图", "LongCat", "前沿观察", "GGUF", "ModelScope"),
                priority = 3,
                kind = ModelScopeRecommendedKind.IMAGE,
                status = RecommendedModelStatus.EXPERIMENTAL,
                downloadable = true,
                downloadBlockReason = null,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "longcat_image_q4_bundle",
                    title = "LongCat-Image Q4 引擎包",
                    recommendationId = "longcat_image_q4",
                    runtime = ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP,
                    accelerator = ImageEngineAccelerator.CPU,
                    minDeviceTier = ImageEngineMinDeviceTier.ANY,
                    requiresSmokeTest = true,
                    smokeSpec = ImageEngineSmokeSpec(width = 1024, height = 1024, steps = 20, timeoutSeconds = 1200),
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "vantagewithai/LongCat-Image-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "comfy/LongCat-Image-Q4_0.gguf",
                            expectedSizeBytes = 3_591_090_400L,
                            sha256 = "d494513ea95e82fb7069cdb914738f22dfc940fc770000fbbc8ad0a7a445f601"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/z_image_turbo",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/ae.safetensors",
                            expectedSizeBytes = 335_304_388L,
                            sha256 = "afc8e28272cd15db3919bacdb6918ce9c1ed22e96cb12c4d5ed0fba823529e38"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen2.5-VL-7B-Instruct-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen2.5-VL-7B-Instruct-Q4_K_M.gguf",
                            expectedSizeBytes = 4_683_072_384L,
                            sha256 = "d16776dcd9a28d42758c2958ed3a752aabf20a305252cd64ff2be72b4a78c503"
                        )
                    ),
                    executionProfile = recommendedImageExecutionProfile("longcat_image_q4"),
                    modelFamily = "LONGCAT_IMAGE"
                )
            )
        )
    }
}
