package com.muyuchat.mca

import java.net.InetAddress
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Web-search preflight checks (P7/S1), moved verbatim from WebSearchProvider.kt: the DNS/config
 * preflight report builder and its per-endpoint checks. All declarations were already package-level
 * (internal/private) and stay top-level in the same package, so every caller — including the
 * behavioral WebSearchProviderTest — is unchanged. providerEndpointPreflightChecks and the check
 * record stay file-private because only the report builder consumes them.
 */

private const val WEB_SEARCH_PREFLIGHT_PUBLIC_HOST = "example.com"

internal fun buildWebSearchPreflightReport(
    config: WebSearchConfig,
    dnsResolver: (String) -> List<String> = { host ->
        InetAddress.getAllByName(host).map { it.hostAddress.orEmpty() }
    },
    environmentChecks: List<String> = emptyList()
): WebSearchPreflightReport {
    val checks = mutableListOf<String>()
    var ok = true

    fun addCheck(message: String) {
        if (message.isBlank()) return
        checks += message
        if (message.startsWith("需检查")) {
            ok = false
        }
    }

    fun pass(message: String) {
        addCheck("通过：$message")
    }

    fun warn(message: String) {
        addCheck("需检查：$message")
    }

    if (!config.enabled) {
        warn("联网检索未启用，聊天页不会自动读取网页或搜索关键词。")
    } else {
        pass("联网检索已启用。")
    }

    environmentChecks.forEach(::addCheck)

    runCatching { dnsResolver(WEB_SEARCH_PREFLIGHT_PUBLIC_HOST) }
        .fold(
            onSuccess = { addresses ->
                pass("公网 DNS 可解析 $WEB_SEARCH_PREFLIGHT_PUBLIC_HOST${addresses.firstOrNull()?.let { " -> $it" }.orEmpty()}。")
            },
            onFailure = { error ->
                warn("设备无法解析公网域名 $WEB_SEARCH_PREFLIGHT_PUBLIC_HOST，请检查手机网络、DNS、VPN 或代理设置。${error.message.orEmpty()}")
            }
        )

    val rawEndpoint = config.rawEndpointForRequest()
    val endpoint = config.endpointForRequest()
    val endpointUrl = endpoint.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
    if (endpoint.isBlank()) {
        warn("${config.providerLabel} 搜索接口地址为空；直接 URL 读取仍可用，但关键词搜索不可用。")
    } else if (endpointUrl == null || endpointUrl.scheme !in listOf("http", "https")) {
        warn("${config.providerLabel} 搜索接口地址不是有效 HTTP/HTTPS URL：$endpoint")
    } else {
        pass("${config.providerLabel} 搜索接口格式有效：${endpointUrl.scheme}://${endpointUrl.host}")
        if (rawEndpoint.isNotBlank() && rawEndpoint != endpoint) {
            pass("${config.providerLabel} 已自动补全接口路径：$endpoint")
        }
        config.providerEndpointPreflightChecks(endpointUrl).forEach { check ->
            if (check.ok) pass(check.message) else warn(check.message)
        }
        runCatching { dnsResolver(endpointUrl.host) }
            .fold(
                onSuccess = { addresses ->
                    pass("${config.providerLabel} 域名可解析${addresses.firstOrNull()?.let { " -> $it" }.orEmpty()}。")
                },
                onFailure = { error ->
                    warn("${config.providerLabel} 域名无法解析，请检查 Base URL、网络、DNS、VPN 或代理设置。${error.message.orEmpty()}")
                }
            )
    }

    if (config.provider.requiresApiKey) {
        if (config.apiKey.isBlank()) {
            warn("${config.providerLabel} 需要 API Key，当前未填写。")
        } else {
            pass("${config.providerLabel} API Key 已填写。")
        }
    } else {
        pass("${config.providerLabel} 不强制要求 API Key。")
    }

    config.backupProviders.filter { it.enabled }.take(MAX_WEB_SEARCH_BACKUP_PROVIDERS).forEachIndexed { index, backup ->
        val backupRawEndpoint = backup.rawEndpointForRequest()
        val backupEndpoint = backup.endpointForRequest()
        val backupUrl = backupEndpoint.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        when {
            backupUrl == null -> warn("备用 ${index + 1} ${backup.providerLabel} 地址无效或为空。")
            else -> {
                pass("备用 ${index + 1} ${backup.providerLabel} 地址格式有效：${backupUrl.scheme}://${backupUrl.host}")
                if (backupRawEndpoint.isNotBlank() && backupRawEndpoint != backupEndpoint) {
                    pass("备用 ${index + 1} ${backup.providerLabel} 已自动补全接口路径：$backupEndpoint")
                }
                backup.toPrimaryConfig(config).providerEndpointPreflightChecks(backupUrl).forEach { check ->
                    if (check.ok) pass("备用 ${index + 1} ${check.message}") else warn("备用 ${index + 1} ${check.message}")
                }
                runCatching { dnsResolver(backupUrl.host) }
                    .onFailure { error ->
                        warn("备用 ${index + 1} ${backup.providerLabel} 域名无法解析。${error.message.orEmpty()}")
                    }
            }
        }
        if (backup.provider.requiresApiKey && backup.apiKey.isBlank()) {
            warn("备用 ${index + 1} ${backup.providerLabel} 需要 API Key，当前未填写。")
        }
    }

    val message = if (ok) {
        "网络预检通过：手机网络、DNS、接口地址和必要 Key 初步可用。"
    } else {
        val failedChecks = checks
            .filter { it.startsWith("需检查") }
            .map { it.removePrefix("需检查：") }
        "网络预检需检查：${failedChecks.take(2).joinToString("；")}"
    }
    return WebSearchPreflightReport(ok = ok, checks = checks.distinct(), message = message)
}

private data class WebSearchPreflightCheck(
    val ok: Boolean,
    val message: String
)

private fun WebSearchConfig.providerEndpointPreflightChecks(endpointUrl: okhttp3.HttpUrl): List<WebSearchPreflightCheck> {
    val path = endpointUrl.encodedPath.trimEnd('/').ifBlank { "/" }
    return buildList {
        when (provider) {
            WebSearchProviderType.SEARXNG -> {
                add(
                    WebSearchPreflightCheck(
                        ok = true,
                        message = if (path.endsWith("/search")) {
                            "SearxNG 路径看起来可直接用于 JSON 搜索，MCA 会追加 q、format=json 等参数。"
                        } else {
                            "SearxNG 可填写实例根地址，MCA 会自动追加 /search?format=json。"
                        }
                    )
                )
            }
            WebSearchProviderType.BRAVE -> {
                val looksLikeBraveWebSearch = path == "/res/v1/web/search"
                val looksLikeBraveLlmContext = path == "/res/v1/llm/context"
                add(
                    WebSearchPreflightCheck(
                        ok = looksLikeBraveWebSearch || looksLikeBraveLlmContext,
                        message = if (looksLikeBraveLlmContext) {
                            "Brave LLM Context 路径正确：/res/v1/llm/context，适合 AI grounding 和 RAG 场景。"
                        } else if (looksLikeBraveWebSearch) {
                            "Brave Web Search 路径正确：/res/v1/web/search。"
                        } else {
                            "Brave Search 需要完整 API 路径：https://api.search.brave.com/res/v1/web/search 或 https://api.search.brave.com/res/v1/llm/context；如果使用自建代理，请选择自定义 JSON 或保持兼容路径。"
                        }
                    )
                )
            }
            WebSearchProviderType.TAVILY -> {
                val looksLikeTavilySearch = path.endsWith("/search")
                add(
                    WebSearchPreflightCheck(
                        ok = looksLikeTavilySearch,
                        message = if (looksLikeTavilySearch) {
                            "Tavily Search 路径正确，MCA 会使用 POST JSON 请求。"
                        } else {
                            "Tavily Search 应填写搜索接口地址：https://api.tavily.com/search；不要填写聊天模型或控制台页面地址。"
                        }
                    )
                )
            }
            WebSearchProviderType.JINA -> {
                val host = endpointUrl.host.lowercase(Locale.ROOT)
                val looksLikeJinaSearch = host == "s.jina.ai"
                add(
                    WebSearchPreflightCheck(
                        ok = looksLikeJinaSearch,
                        message = if (looksLikeJinaSearch) {
                            "Jina Search 地址正确；正文不足时可配合 Jina Reader 增强网页读取。"
                        } else {
                            "Jina Search 推荐填写 https://s.jina.ai；如果使用自建网关，请确认它返回兼容 JSON 搜索结果。"
                        }
                    )
                )
            }
            WebSearchProviderType.CUSTOM_JSON -> {
                if (isWebSearchPublicCheckSource(provider, endpointForRequest())) {
                    add(
                        WebSearchPreflightCheck(
                            ok = true,
                            message = "公开 JSON 协议自检源可用于链路验证，但覆盖范围有限。"
                        )
                    )
                } else {
                    add(
                        WebSearchPreflightCheck(
                            ok = true,
                            message = "自定义 JSON 支持 {query}/{max_results} URL 模板，也会尝试 q/query/max_results 等常见参数，并解析 results/items/data/hits/organic_results。"
                        )
                    )
                }
            }
        }
    }
}
