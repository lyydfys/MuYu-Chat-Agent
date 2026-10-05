package com.muyuchat.mca

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Web-search provider query operations (P7/S15), first intra-class extraction from WebSearchProvider:
 * the provider dispatch and the five per-provider query functions, moved verbatim behind a minimal
 * [WebSearchHttpPort] whose only collaborator is the shared OkHttp client (the rest of their
 * dependencies — baseRequest / executeBodyOrThrow / friendlySearchError / customJsonCandidateUrls —
 * were already top-level, so no other state crosses the boundary). The class keeps a same-signature
 * delegate for searchProviderQuery, so runProviderQueries' call site is unchanged.
 */

internal interface WebSearchHttpPort {
    val client: OkHttpClient
}

internal fun runProviderQuery(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> =
    when (config.provider) {
        WebSearchProviderType.SEARXNG -> searchSearxng(port, query, config)
        WebSearchProviderType.BRAVE -> searchBrave(port, query, config)
        WebSearchProviderType.TAVILY -> searchTavily(port, query, config)
        WebSearchProviderType.JINA -> searchJina(port, query, config)
        WebSearchProviderType.CUSTOM_JSON -> searchCustomJson(port, query, config)
    }

internal fun searchSearxng(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> {
    val base = config.endpointForRequest().trimEnd('/')
    val url = if (base.endsWith("/search")) {
        base
    } else {
        "$base/search"
    }.toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("q", query)
        ?.addQueryParameter("format", "json")
        ?.addQueryParameter("language", "zh-CN")
        ?.addQueryParameter("safesearch", "1")
        ?.build()
        ?: error("SearxNG 地址无效")
    val body = port.client.newCall(baseRequest(url.toString()).build()).executeBodyOrThrow()
    val root = JSONObject(body)
    return root.optJSONArray("results").toSearchDocuments(config.providerLabel)
}

internal fun searchBrave(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> {
    val endpoint = config.endpointForRequest()
    val isLlmContextEndpoint = endpoint.contains("/res/v1/llm/context", ignoreCase = true)
    val url = endpoint
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("q", query)
        ?.addQueryParameter("count", config.sanitizedMaxResults().toString())
        ?.addQueryParameter("search_lang", "zh-hans")
        ?.apply {
            if (isLlmContextEndpoint) {
                addQueryParameter("maximum_number_of_urls", config.sanitizedMaxResults().toString())
                addQueryParameter("maximum_number_of_tokens", if (config.fetchPageContent) "8192" else "4096")
                addQueryParameter("maximum_number_of_snippets", (config.sanitizedMaxResults() * 6).coerceIn(6, 48).toString())
                addQueryParameter("maximum_number_of_snippets_per_url", if (config.fetchPageContent) "6" else "3")
                addQueryParameter("enable_source_metadata", "true")
                addQueryParameter("context_threshold_mode", "balanced")
            } else if (config.fetchPageContent) {
                addQueryParameter("extra_snippets", "true")
            }
        }
        ?.build()
        ?: error("Brave Search 地址无效")
    val request = baseRequest(url.toString())
        .header("X-Subscription-Token", config.apiKey.trim())
        .build()
    val root = JSONObject(port.client.newCall(request).executeBodyOrThrow())
    if (isLlmContextEndpoint) {
        return root.toBraveLlmContextDocuments(config.providerLabel)
            .take(config.sanitizedMaxResults())
    }
    return root.toBraveSearchDocuments(
        provider = config.providerLabel,
        maxResults = config.sanitizedMaxResults()
    )
}

internal fun searchTavily(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> {
    val root = JSONObject()
        .put("query", query)
        .put("max_results", config.sanitizedMaxResults())
        .put("search_depth", if (config.fetchPageContent) "advanced" else "basic")
        .put("include_answer", false)
        .put("include_raw_content", config.fetchPageContent)
    val request = baseRequest(config.endpointForRequest())
        .header("Authorization", "Bearer ${config.apiKey.trim()}")
        .post(root.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    val response = JSONObject(port.client.newCall(request).executeBodyOrThrow())
    return response.optJSONArray("results").toSearchDocuments(config.providerLabel)
}

internal fun searchJina(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> {
    val url = config.endpointForRequest()
        .toHttpUrlOrNull()
        ?.newBuilder()
        ?.addQueryParameter("q", query)
        ?.build()
        ?: error("Jina Search 地址无效")
    val request = baseRequest(url.toString())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer ${config.apiKey.trim()}")
        .build()
    return port.client.newCall(request)
        .executeBodyOrThrow()
        .parseCustomSearchDocuments(config.providerLabel)
        .take(config.sanitizedMaxResults())
}

internal fun searchCustomJson(port: WebSearchHttpPort, query: String, config: WebSearchConfig): List<WebSearchDocument> {
    val urls = customJsonCandidateUrls(query, config)
    val errors = mutableListOf<String>()
    var sawEmptySuccess = false
    urls.forEach { url ->
        val requestBuilder = baseRequest(url)
        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        runCatching {
            port.client.newCall(requestBuilder.build())
                .executeBodyOrThrow()
                .parseCustomSearchDocuments(config.providerLabel)
                .take(config.sanitizedMaxResults())
        }.fold(
            onSuccess = { documents ->
                if (documents.isNotEmpty()) return documents
                sawEmptySuccess = true
            },
            onFailure = { error ->
                errors += error.friendlyWebSearchFailureMessage()
            }
        )
    }
    if (sawEmptySuccess) return emptyList()
    error("自定义搜索全部失败：${errors.distinct().joinToString("；").limitForPrompt(280)}")
}
