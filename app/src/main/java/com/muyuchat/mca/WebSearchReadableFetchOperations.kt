package com.muyuchat.mca

import java.net.InetAddress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

/**
 * Web-search readable-document fetch operations (P7/S16), second intra-class extraction from
 * WebSearchProvider: the readable/direct/Jina fetch chain and the direct-URL fan-out, moved verbatim
 * behind a [WebSearchReadableFetchPort] whose state is exactly client + allowPrivateNetworkFetch (the
 * remaining dependencies — validateReadableWebSearchUrl / jinaReaderUrlFor / html-to-text helpers —
 * are already top-level). The class keeps same-signature delegates, so existing call sites are
 * unchanged.
 */

internal interface WebSearchReadableFetchPort {
    val client: OkHttpClient
    val allowPrivateNetworkFetch: Boolean
    val dnsResolver: ((String) -> List<InetAddress>)? get() = null
}

internal fun fetchReadableDocumentVia(port: WebSearchReadableFetchPort, url: String, provider: String, config: WebSearchConfig? = null): WebSearchDocument {
    val httpUrl = url.toHttpUrlOrNull()
    if (httpUrl == null || (httpUrl.scheme != "http" && httpUrl.scheme != "https")) {
        return WebSearchDocument(title = url, url = url, provider = provider)
    }
    if (!port.allowPrivateNetworkFetch) {
        validateReadableWebSearchUrl(url, port.dnsResolver)
    }
    val direct = runCatching { fetchReadableDocumentDirect(port, url, provider) }.getOrNull()
    if (direct != null && direct.content.length >= 280) {
        return direct
    }
    if (config?.canUseJinaReader == true) {
        val jina = runCatching { fetchJinaReaderDocument(port, url, provider, config) }.getOrNull()
        if (jina != null && (jina.content.isNotBlank() || jina.snippet.isNotBlank())) {
            return jina
        }
    }
    if (direct != null) {
        return direct
    }
    return WebSearchDocument(title = url, url = url, provider = provider)
}

private fun fetchReadableDocumentDirect(port: WebSearchReadableFetchPort, url: String, provider: String): WebSearchDocument {
    val response = port.client.newCall(baseRequest(url).build()).execute()
    response.use {
        if (!it.isSuccessful) return WebSearchDocument(title = url, url = url, provider = provider)
        val contentType = it.header("content-type").orEmpty().lowercase()
        if (contentType.isNotBlank() && "text" !in contentType && "html" !in contentType && "json" !in contentType) {
            return WebSearchDocument(title = url, url = url, provider = provider)
        }
        val raw = it.body?.string().orEmpty()
        val readable = raw.htmlToReadableText()
        return WebSearchDocument(
            title = raw.extractHtmlTitle().ifBlank { url },
            url = url,
            snippet = readable.lineSequence().firstOrNull().orEmpty().limitForPrompt(360),
            content = readable.limitForPrompt(4_000),
            provider = provider
        )
    }
}

private fun fetchJinaReaderDocument(port: WebSearchReadableFetchPort, url: String, provider: String, config: WebSearchConfig): WebSearchDocument {
    val readerUrl = jinaReaderUrlFor(url)
    val requestBuilder = baseRequest(readerUrl)
        .header("Accept", "text/plain,text/markdown;q=0.9,*/*;q=0.2")
    if (config.apiKey.isNotBlank()) {
        requestBuilder.header("Authorization", "Bearer ${config.apiKey.trim()}")
    }
    val raw = port.client.newCall(requestBuilder.build()).executeBodyOrThrow()
    val readable = raw.jinaReaderToReadableText()
    return WebSearchDocument(
        title = raw.extractJinaReaderTitle().ifBlank { url },
        url = url,
        snippet = readable.lineSequence().firstOrNull().orEmpty().limitForPrompt(360),
        content = readable.limitForPrompt(4_000),
        provider = provider
    )
}
internal suspend fun runDirectDocumentFetch(port: WebSearchReadableFetchPort, urls: List<String>, config: WebSearchConfig): List<WebSearchDocument> =
    coroutineScope {
        urls.map { url ->
            async {
                runCatching { fetchReadableDocumentVia(port, url, WEB_SEARCH_DIRECT_READ_LABEL, config) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            if (error is BlockedWebSearchUrlException) {
                                WebSearchDocument(
                                    title = "已阻止读取受限地址",
                                    url = url,
                                    snippet = error.message.orEmpty(),
                                    provider = "安全拦截"
                                )
                            } else {
                                null
                            }
                        }
                    )
            }
        }.awaitAll().filterNotNull()
    }
