package com.muyuchat.core.download

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catalog collection + search parsing for the ModelScope client (P6/M2), moved verbatim from
 * ModelScopeClient's private members. huggingFaceEndpoints is threaded explicitly so the helpers
 * stay stateless; the class keeps 1-arg/0-arg member delegates so every existing call site and
 * *ForTest wrapper resolves unchanged. collectFromJson/collectFromArray/maybeFile stay file-private;
 * collectGgufFiles and parseModelSearchResult are internal because the class still calls them.
 */

internal fun collectGgufFiles(
    repoId: String,
    revision: String,
    endpoint: String,
    body: String,
    provider: ModelRepositoryProvider,
    extensions: Set<String> = setOf("gguf"),
    huggingFaceEndpoints: List<String>
): List<RemoteModelFile> {
    val root = runCatching { JSONObject(body) }.getOrNull()
    if (root != null) return collectFromJson(repoId, revision, endpoint, provider, root, extensions, huggingFaceEndpoints)
    val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
    return collectFromArray(repoId, revision, endpoint, provider, array, extensions, huggingFaceEndpoints)
}

private fun collectFromJson(
    repoId: String,
    revision: String,
    endpoint: String,
    provider: ModelRepositoryProvider,
    json: JSONObject,
    extensions: Set<String>,
    huggingFaceEndpoints: List<String>
): List<RemoteModelFile> {
    val result = mutableListOf<RemoteModelFile>()
    maybeFile(repoId, revision, endpoint, provider, json, extensions, huggingFaceEndpoints)?.let(result::add)
    json.keys().forEach { key ->
        when (val value = json.opt(key)) {
            is JSONObject -> result += collectFromJson(repoId, revision, endpoint, provider, value, extensions, huggingFaceEndpoints)
            is JSONArray -> result += collectFromArray(repoId, revision, endpoint, provider, value, extensions, huggingFaceEndpoints)
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
    extensions: Set<String>,
    huggingFaceEndpoints: List<String>
): List<RemoteModelFile> {
    val result = mutableListOf<RemoteModelFile>()
    for (index in 0 until array.length()) {
        when (val value = array.opt(index)) {
            is JSONObject -> result += collectFromJson(repoId, revision, endpoint, provider, value, extensions, huggingFaceEndpoints)
            is JSONArray -> result += collectFromArray(repoId, revision, endpoint, provider, value, extensions, huggingFaceEndpoints)
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
    extensions: Set<String>,
    huggingFaceEndpoints: List<String>
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
        preferredHuggingFaceMirrorUrl(rawUrl, huggingFaceEndpoints)
    } else {
        rawUrl
    }
    // Hugging Face's ordinary Git tree entries expose `oid` as a
    // 40-character Git blob SHA-1.  The downloader compares this field
    // with a content SHA-256, so carrying it as `RemoteModelFile.sha256`
    // makes complete files fail verification.  Keep only an actual
    // 64-hex SHA-256 here.  LFS `oid` values are SHA-256 and therefore
    // still pass this normalization; unknown/malformed digests remain
    // unavailable instead of being used as a false integrity contract.
    val sha = listOf("Sha256", "sha256", "SHA256")
        .asSequence()
        .mapNotNull { key -> normalizedRemoteSha256OrNull(json.optString(key)) }
        .firstOrNull()
        ?: json.optJSONObject("lfs")?.let { lfs ->
            listOf("sha256", "oid")
                .asSequence()
                .mapNotNull { key -> normalizedRemoteSha256OrNull(lfs.optString(key)) }
                .firstOrNull()
        }
        ?: normalizedRemoteSha256OrNull(json.optString("oid"))
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

internal fun parseModelSearchResult(query: String, body: String): ModelScopeModelSearchResult {
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

internal fun preferredHuggingFaceMirrorUrl(url: String, huggingFaceEndpoints: List<String>): String {
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

internal fun String.isHuggingFaceHost(huggingFaceEndpoints: List<String>): Boolean {
    val host = trim().lowercase().removePrefix("www.")
    return host == "huggingface.co" ||
        host == "hf.co" ||
        huggingFaceEndpoints.any { endpoint ->
            runCatching { URI(endpoint).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("") == host
        }
}
