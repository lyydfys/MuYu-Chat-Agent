package com.muyuchat.core.download

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class ModelScopeClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val endpoints: List<String> = DEFAULT_ENDPOINTS
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
            if (provider == ModelRepositoryProvider.HUGGING_FACE || uri.host.orEmpty().contains("huggingface.co", ignoreCase = true)) {
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
        val url = "https://huggingface.co/api/models/${repoId.urlEncodePath()}/tree/${safeRevision.urlEncode()}?recursive=true"
        return client.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) error("Hugging Face 文件列表请求失败：HTTP ${response.code}")
            val files = collectGgufFiles(
                repoId = repoId,
                revision = safeRevision,
                endpoint = "https://huggingface.co",
                body = response.body?.string().orEmpty(),
                provider = ModelRepositoryProvider.HUGGING_FACE,
                extensions = extensions
            )
            require(files.isNotEmpty()) { "未在该 Hugging Face 仓库找到可下载模型组件。" }
            files
        }
    }

    fun recommendedModels(): List<ModelScopeRecommendedModel> = DEFAULT_RECOMMENDED_MODELS

    fun listRecommendedFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> {
        require(model.downloadable) { "该推荐模型暂未接入可验证的一键下载链路。" }
        model.imageEngineBundle?.let { return recommendedImageBundleFiles(model) }
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

    fun recommendedFile(model: ModelScopeRecommendedModel): RemoteModelFile {
        require(model.downloadable) { "该推荐模型暂未接入可验证的一键下载链路。" }
        model.imageEngineBundle?.let { bundle ->
            return recommendedImageBundleFiles(model).firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
                ?: error("推荐生图引擎 ${bundle.title} 没有配置 diffusion 主模型。")
        }
        val files = listRecommendedFiles(model)
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

    fun recommendedImageBundleFiles(model: ModelScopeRecommendedModel): List<RemoteModelFile> {
        val bundle = model.imageEngineBundle ?: error("${model.title} 没有配置图像生成引擎包。")
        return bundle.components.map { component ->
            val files = listModelFiles(
                input = component.repoId,
                revision = component.revision,
                provider = component.provider,
                extensions = MODEL_FILE_EXTENSIONS
            )
            val match = files.firstOrNull { it.path.equals(component.fileName, ignoreCase = true) } ?:
                files.firstOrNull { it.name.equals(component.fileName.substringAfterLast('/'), ignoreCase = true) }
            if (match == null && component.required) {
                error("图像生成引擎包缺少组件：${component.role.label} / ${component.fileName}")
            }
            match?.copy(bundleRole = component.role)
        }.filterNotNull()
    }

    fun searchModels(
        query: String = "Qwen3.5 GGUF Q4_K_M",
        pageNumber: Int = 1,
        pageSize: Int = 20
    ): ModelScopeModelSearchResult {
        val safeQuery = query.ifBlank { "Qwen3.5 GGUF Q4_K_M" }
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
        body: String
    ): List<RemoteModelFile> = collectGgufFiles(
        repoId = repoId,
        revision = revision,
        endpoint = endpoint,
        body = body,
        provider = ModelRepositoryProvider.MODELSCOPE,
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
        val url = listOf("DownloadUrl", "downloadUrl", "download_url", "Url", "url")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.startsWith("http") } }
            ?: when (provider) {
                ModelRepositoryProvider.MODELSCOPE ->
                    "$endpoint/models/$repoId/resolve/${revision.urlEncode()}/${path.urlEncodePath()}"
                ModelRepositoryProvider.HUGGING_FACE ->
                    "$endpoint/$repoId/resolve/${revision.urlEncode()}/${path.urlEncodePath()}?download=true"
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

    companion object {
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "sft", "ckpt", "pth", "pt", "onnx", "zip")

        private val DEFAULT_ENDPOINTS = listOf(
            "https://www.modelscope.cn",
            "https://modelscope.cn",
            "https://www.modelscope.ai",
            "https://modelscope.ai"
        )

        private val DEFAULT_RECOMMENDED_MODELS = listOf(
            ModelScopeRecommendedModel(
                id = "qwen35_08b_q4",
                title = "Qwen3.5 0.8B Q4",
                repoId = "lmstudio-community/Qwen3.5-0.8B-GGUF",
                description = "轻量中文聊天首选，体积小、启动快，适合低内存手机先跑通本地推理闭环。",
                recommendedFileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
                parameterScale = "0.8B",
                quant = "Q4_K_M",
                minRamGb = 4,
                tags = listOf("低内存", "速度优先", "Qwen3.5", "ModelScope"),
                priority = 0,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_2b_q4",
                title = "Qwen3.5 2B Q4",
                repoId = "lmstudio-community/Qwen3.5-2B-GGUF",
                description = "轻量档的均衡选择，中文对话能力比 1B 档更稳，适合 6GB 内存以上设备。",
                recommendedFileName = "Qwen3.5-2B-Q4_K_M.gguf",
                parameterScale = "2B",
                quant = "Q4_K_M",
                minRamGb = 6,
                tags = listOf("均衡", "中文", "Qwen3.5", "ModelScope"),
                priority = 1,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e2b_iq4",
                title = "Gemma 4 E2B it IQ4",
                repoId = "unsloth/gemma-4-E2B-it-GGUF",
                description = "移动端友好的多语种轻量模型，作为 Qwen 之外的英文/多语种补充。",
                recommendedFileName = "gemma-4-E2B-it-IQ4_XS.gguf",
                parameterScale = "E2B",
                quant = "IQ4_XS",
                minRamGb = 6,
                tags = listOf("Gemma 4", "多语种", "ModelScope"),
                priority = 2,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
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
                priority = 3,
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
                priority = 4,
                group = ModelScopeRecommendedGroup.LIGHT_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_4b_q4",
                title = "Qwen3.5 4B Q4",
                repoId = "lmstudio-community/Qwen3.5-4B-GGUF",
                description = "主力聊天首选，中文能力、速度和内存压力比较均衡，建议 8GB 以上设备。",
                recommendedFileName = "Qwen3.5-4B-Q4_K_M.gguf",
                parameterScale = "4B",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("推荐", "中文", "Qwen3.5", "ModelScope"),
                priority = 0,
                group = ModelScopeRecommendedGroup.MAIN_CHAT
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
                group = ModelScopeRecommendedGroup.MAIN_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "minicpm_v46_q4",
                title = "MiniCPM-V 4.6 Q4",
                repoId = "OpenBMB/MiniCPM-V-4.6-gguf",
                description = "OpenBMB 多模态模型，适合后续本地视觉理解扩展；文本聊天也可作为主力候选。",
                recommendedFileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
                parameterScale = "V-4.6",
                quant = "Q4_K_M",
                minRamGb = 8,
                tags = listOf("多模态", "中文", "OpenBMB", "ModelScope"),
                priority = 2,
                group = ModelScopeRecommendedGroup.MAIN_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "gemma4_e4b_iq4",
                title = "Gemma 4 E4B it IQ4",
                repoId = "unsloth/gemma-4-E4B-it-GGUF",
                description = "Gemma 4 中档多语种模型，适合偏英文、多语种、通用助手场景。",
                recommendedFileName = "gemma-4-E4B-it-IQ4_XS.gguf",
                parameterScale = "E4B",
                quant = "IQ4_XS",
                minRamGb = 8,
                tags = listOf("Gemma 4", "多语种", "ModelScope"),
                priority = 3,
                group = ModelScopeRecommendedGroup.MAIN_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_9b_q4",
                title = "Qwen3.5 9B Q4",
                repoId = "lmstudio-community/Qwen3.5-9B-GGUF",
                description = "高质量中文聊天首选，质量明显更强，但发热、耗电和内存压力更高。",
                recommendedFileName = "Qwen3.5-9B-Q4_K_M.gguf",
                parameterScale = "9B",
                quant = "Q4_K_M",
                minRamGb = 12,
                tags = listOf("高质量", "中文", "Qwen3.5", "ModelScope"),
                priority = 0,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT
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
                priority = 1,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "qwen35_35b_a3b_iq2_xxs",
                title = "Qwen3.5 35B-A3B IQ2 XXS",
                repoId = "unsloth/Qwen3.5-35B-A3B-GGUF",
                description = "MoE 大模型超低内存档。Qwen3.6 的 IQ2_XXS 文件未找到可验证仓库，当前使用可下载的 Qwen3.5 对应量化。",
                recommendedFileName = "Qwen3.5-35B-A3B-UD-IQ2_XXS.gguf",
                parameterScale = "35B-A3B",
                quant = "IQ2_XXS",
                minRamGb = 12,
                tags = listOf("MoE", "中文", "超低内存", "ModelScope"),
                priority = 2,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "google_gemma4_26b_a4b_iq2_xxs",
                title = "Gemma 4 26B A4B IQ2 XXS",
                repoId = "bartowski/google_gemma-4-26B-A4B-it-GGUF",
                description = "Gemma 4 MoE 超低内存档，比 Q4 更容易在侧端加载，适合作为高质量组的可运行候选。",
                recommendedFileName = "google_gemma-4-26B-A4B-it-IQ2_XXS.gguf",
                parameterScale = "26B-A4B",
                quant = "IQ2_XXS",
                minRamGb = 12,
                tags = listOf("Gemma 4", "MoE", "超低内存", "ModelScope"),
                priority = 3,
                group = ModelScopeRecommendedGroup.QUALITY_CHAT
            ),
            ModelScopeRecommendedModel(
                id = "sd_turbo_384_fast",
                title = "SD-Turbo 384 极速版",
                repoId = "AI-ModelScope/sd-turbo",
                description = "端侧 CPU 优先的一步生成档。实测 384x384 更适合作为手机默认本地生图入口。",
                recommendedFileName = "sd_turbo.safetensors",
                parameterScale = "SD-Turbo",
                quant = "FP16",
                minRamGb = 6,
                tags = listOf("本地生图", "SD-Turbo", "极速生成", "CPU", "ModelScope"),
                priority = 0,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.QUICK,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "sd_turbo_384_fast_bundle",
                    title = "SD-Turbo 384 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "AI-ModelScope/sd-turbo",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "sd_turbo.safetensors"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "sd_turbo_512_quality",
                title = "SD-Turbo 512 高清版",
                repoId = "AI-ModelScope/sd-turbo",
                description = "端侧 CPU 的高清一步生成档。画面细节比 384 更好，但耗时和发热更高。",
                recommendedFileName = "sd_turbo.safetensors",
                parameterScale = "SD-Turbo",
                quant = "FP16",
                minRamGb = 8,
                tags = listOf("本地生图", "SD-Turbo", "高清生成", "CPU", "ModelScope"),
                priority = 1,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.STANDARD,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "sd_turbo_512_quality_bundle",
                    title = "SD-Turbo 512 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "AI-ModelScope/sd-turbo",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "sd_turbo.safetensors"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "z_image_turbo_q4",
                title = "Z-Image Turbo Q4 备用实验版",
                repoId = "hf/leejet-Z-Image-Turbo-GGUF",
                description = "Z-Image diffusion 主模型。stable-diffusion.cpp 还需要 VAE 和 Qwen3 文本编码器，建议作为组件包导入。",
                recommendedFileName = "z_image_turbo-Q4_K.gguf",
                parameterScale = "6B",
                quant = "Q4_K",
                minRamGb = 8,
                tags = listOf("本地生图", "Z-Image", "Turbo", "备用实验", "ModelScope"),
                priority = 3,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.LARGE_QUALITY,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "z_image_turbo_q4_bundle",
                    title = "Z-Image Turbo Q4 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "hf/leejet-Z-Image-Turbo-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "z_image_turbo-Q4_K.gguf"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/z_image_turbo",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/ae.safetensors"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen3-4B-Instruct-2507-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "flux2_klein_4b_q4",
                title = "FLUX.2 Klein 4B 画质实验版",
                repoId = "hf/leejet-FLUX.2-klein-4B-GGUF",
                description = "FLUX.2 Klein diffusion 主模型。单个 GGUF 不能直接生成，还需 FLUX VAE 和 Qwen3 文本编码器。",
                recommendedFileName = "flux-2-klein-4b-Q4_0.gguf",
                parameterScale = "4B",
                quant = "Q4_0",
                minRamGb = 8,
                tags = listOf("本地生图", "FLUX.2", "画质实验", "GGUF", "ModelScope"),
                priority = 2,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.COMPACT_QUALITY,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "flux2_klein_4b_q4_bundle",
                    title = "FLUX.2 Klein 4B Q4 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "hf/leejet-FLUX.2-klein-4B-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "flux-2-klein-4b-Q4_0.gguf"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/flux2-klein-4B",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/flux2-vae.safetensors"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "unsloth/Qwen3-4B-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "Qwen3-4B-Q4_K_M.gguf"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "qwen_image_2512_q2",
                title = "Qwen-Image 2512 Q2 前沿观察版",
                repoId = "unsloth/Qwen-Image-2512-GGUF",
                description = "Qwen-Image diffusion 主模型低内存版本。生成还需要 Qwen-Image VAE 和 Qwen2.5-VL 文本编码器。",
                recommendedFileName = "qwen-image-2512-Q2_K.gguf",
                parameterScale = "Image",
                quant = "Q2_K",
                minRamGb = 12,
                tags = listOf("本地生图", "Qwen-Image", "前沿观察", "ModelScope"),
                priority = 4,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "qwen_image_2512_q2_bundle",
                    title = "Qwen-Image 2512 Q2 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "unsloth/Qwen-Image-2512-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "qwen-image-2512-Q2_K.gguf"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "Comfy-Org/Qwen-Image_ComfyUI",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "split_files/vae/qwen_image_vae.safetensors"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-GGUF",
                            provider = ModelRepositoryProvider.HUGGING_FACE,
                            fileName = "Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf"
                        )
                    )
                )
            ),
            ModelScopeRecommendedModel(
                id = "longcat_image_q4",
                title = "LongCat-Image Q4 前沿观察版",
                repoId = "vantagewithai/LongCat-Image-GGUF",
                description = "LongCat diffusion 主模型。生成还需要 FLUX VAE 和 Qwen2.5-VL 文本编码器。",
                recommendedFileName = "LongCat-Image-Q4_0.gguf",
                parameterScale = "Image",
                quant = "Q4_0",
                minRamGb = 12,
                tags = listOf("本地生图", "LongCat", "前沿观察", "GGUF", "ModelScope"),
                priority = 5,
                kind = ModelScopeRecommendedKind.IMAGE,
                localImageEngineTier = LocalImageEngineTier.HEAVY_EXPERIMENTAL,
                imageEngineBundle = ImageEngineBundleSpec(
                    id = "longcat_image_q4_bundle",
                    title = "LongCat-Image Q4 引擎包",
                    components = listOf(
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.DIFFUSION,
                            repoId = "vantagewithai/LongCat-Image-GGUF",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "comfy/LongCat-Image-Q4_0.gguf"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.VAE,
                            repoId = "black-forest-labs/FLUX.1-schnell",
                            revision = "master",
                            provider = ModelRepositoryProvider.MODELSCOPE,
                            fileName = "ae.safetensors"
                        ),
                        ImageEngineBundleComponentSpec(
                            role = ImageEngineBundleComponentRole.TEXT_ENCODER,
                            repoId = "mradermacher/Qwen2.5-VL-7B-Instruct-GGUF",
                            provider = ModelRepositoryProvider.HUGGING_FACE,
                            fileName = "Qwen2.5-VL-7B-Instruct.Q4_K_M.gguf"
                        )
                    )
                )
            )
        )
    }
}
