package com.muyuchat.core.download

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Network-bound catalog operations for ModelScopeClient (P6/M3): repository file listing (ModelScope
 * and Hugging Face endpoints), hub model search and the shared request builder. Bodies are verbatim;
 * instance state arrives through the read-only ModelScopeNetworkPort, and the client class keeps thin
 * delegates (listModelScopeFiles/listHuggingFaceFiles/searchModels) so public behavior and call sites
 * are unchanged.
 */

internal interface ModelScopeNetworkPort {
    val client: OkHttpClient
    val endpoints: List<String>
    val huggingFaceEndpoints: List<String>
}

internal fun listModelScopeFilesVia(
    port: ModelScopeNetworkPort,
    repoId: String,
    revision: String,
    extensions: Set<String>
): List<RemoteModelFile> {
    val client = port.client
    val endpoints = port.endpoints

    val errors = mutableListOf<String>()
    for (endpoint in endpoints) {
        val url = "$endpoint/api/v1/models/$repoId/repo/files?Revision=${revision.urlEncode()}&Recursive=true"
        val request = modelScopeRequest(url)
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
                    extensions = extensions,
                    huggingFaceEndpoints = port.huggingFaceEndpoints
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

internal fun listHuggingFaceFilesVia(
    port: ModelScopeNetworkPort,
    repoId: String,
    revision: String,
    extensions: Set<String>
): List<RemoteModelFile> {
    val client = port.client
    val huggingFaceEndpoints = port.huggingFaceEndpoints

    val safeRevision = revision.ifBlank { "main" }
    val errors = mutableListOf<String>()
    for (endpoint in huggingFaceEndpoints) {
        val url = "$endpoint/api/models/${repoId.urlEncodePath()}/tree/${safeRevision.urlEncode()}?recursive=true"
        val files = runCatching {
            client.newCall(modelScopeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val files = collectGgufFiles(
                    repoId = repoId,
                    revision = safeRevision,
                    endpoint = endpoint,
                    body = response.body?.string().orEmpty(),
                    provider = ModelRepositoryProvider.HUGGING_FACE,
                    extensions = extensions,
                    huggingFaceEndpoints = port.huggingFaceEndpoints
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

internal fun searchModelsVia(
    port: ModelScopeNetworkPort,
    query: String,
    pageNumber: Int,
    pageSize: Int
): ModelScopeModelSearchResult {
    val client = port.client
    val endpoints = port.endpoints

    val safeQuery = query.ifBlank { "Qwen3.5 MNN" }
    val safePage = pageNumber.coerceAtLeast(1)
    val safeSize = pageSize.coerceIn(5, 50)
    val errors = mutableListOf<String>()
    for (endpoint in endpoints) {
        val url = "$endpoint/openapi/v1/models?page_number=$safePage&page_size=$safeSize&search=${safeQuery.urlEncode()}"
        val result = runCatching {
            client.newCall(modelScopeRequest(url)).execute().use { response ->
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

internal fun modelScopeRequest(url: String): Request =

    Request.Builder()
        .url(url)
        .get()
        .header("User-Agent", "MCA/0.1 ModelScopeClient")
        .header("Accept", "application/json,*/*")
        .build()
