package com.muyuchat.mca

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class WebSearchProviderType(val label: String, val defaultEndpoint: String, val requiresApiKey: Boolean) {
    SEARXNG("SearxNG", "", false),
    BRAVE("Brave Search", "https://api.search.brave.com/res/v1/web/search", true),
    TAVILY("Tavily Search", "https://api.tavily.com/search", true),
    JINA("Jina Search", "https://s.jina.ai", true),
    CUSTOM_JSON("自定义 JSON", "", false);

    companion object {
        fun from(value: String?): WebSearchProviderType =
            entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) } ?: SEARXNG
    }
}

internal const val WEB_SEARCH_PUBLIC_CHECK_ENDPOINT = "https://hn.algolia.com/api/v1/search"
// P7/S13: shared with WebSearchProviderSupport.kt.
internal const val WEB_SEARCH_PUBLIC_CHECK_LABEL = "公开 JSON 自检源"
// P7/S2: shared with WebSearchDiagnosticConversion.kt.
internal const val WEB_SEARCH_DIRECT_READ_LABEL = "网页直读"
// P7/S13: shared with WebSearchProviderSupport.kt.
internal const val WEB_SEARCH_PUBLIC_CHECK_WARNING =
    "当前使用的是公开 JSON 协议自检源，只适合验证联网链路、上下文注入和来源卡片；它不是通用搜索服务，正式使用请配置 SearxNG、Brave、Tavily、Jina 或可信自建搜索源。"

internal fun isWebSearchPublicCheckSource(provider: WebSearchProviderType, endpoint: String): Boolean =
    provider == WebSearchProviderType.CUSTOM_JSON &&
        endpoint.trim().trimEnd('/') == WEB_SEARCH_PUBLIC_CHECK_ENDPOINT

enum class WebSearchTriggerMode(val label: String, val description: String) {
    MANUAL("手动", "只在输入框本轮开启或助手默认开启时联网"),
    SMART("智能", "遇到实时信息、官网文档、明确搜索或网页链接时自动联网"),
    ALWAYS("始终", "每轮非空问题都会先检索，再交给模型回答");

    companion object {
        fun from(value: String?): WebSearchTriggerMode =
            entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) } ?: SMART
    }
}

enum class WebSearchResearchMode(val label: String, val description: String) {
    AUTO("自动", "命中调研、评测、方案、对比等问题时启用多源研究"),
    OFF("普通", "只做轻量检索，不强制扩展为研究查询"),
    DEEP("深度", "每次关键词检索都尽量扩展为多角度研究查询");

    companion object {
        fun from(value: String?): WebSearchResearchMode =
            entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) } ?: AUTO
    }
}

enum class WebSearchTurnMode(val label: String, val description: String) {
    FOLLOW("智能", "跟随助手和联网检索默认策略"),
    ON("本轮开启", "当前这一轮强制联网检索"),
    OFF("本轮关闭", "当前这一轮不使用联网检索");

    companion object {
        fun from(value: String?): WebSearchTurnMode =
            entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) } ?: FOLLOW
    }
}

data class WebSearchBackupProviderConfig(
    val enabled: Boolean = false,
    val provider: WebSearchProviderType = WebSearchProviderType.SEARXNG,
    val endpoint: String = "",
    val apiKey: String = ""
) {
    val isPublicCheckSource: Boolean
        get() = isWebSearchPublicCheckSource(provider, endpointForRequest())

    val providerLabel: String
        get() = if (isPublicCheckSource) {
            WEB_SEARCH_PUBLIC_CHECK_LABEL
        } else {
            provider.label
        }

    val configured: Boolean
        get() {
            if (!enabled) return false
            val endpointForRequest = endpointForRequest()
            return endpointForRequest.isNotBlank() && (!provider.requiresApiKey || apiKey.isNotBlank())
        }

    val realSearchConfigured: Boolean
        get() = configured && !isPublicCheckSource

    fun rawEndpointForRequest(): String =
        endpoint.trim().ifBlank { provider.defaultEndpoint }.trimEnd('/')

    fun endpointForRequest(): String =
        provider.normalizedEndpointForRequest(rawEndpointForRequest())

    fun toPrimaryConfig(base: WebSearchConfig): WebSearchConfig =
        base.copy(
            provider = provider,
            endpoint = endpoint.trim(),
            apiKey = apiKey.trim(),
            backupProviders = emptyList()
        )
}

data class WebSearchConfig(
    val enabled: Boolean = false,
    val provider: WebSearchProviderType = WebSearchProviderType.SEARXNG,
    val endpoint: String = "",
    val apiKey: String = "",
    val maxResults: Int = 5,
    val fetchPageContent: Boolean = true,
    val triggerMode: WebSearchTriggerMode = WebSearchTriggerMode.SMART,
    val researchMode: WebSearchResearchMode = WebSearchResearchMode.AUTO,
    val backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
) {
    val isPublicCheckSource: Boolean
        get() = isWebSearchPublicCheckSource(provider, endpointForRequest())

    val providerLabel: String
        get() = if (isPublicCheckSource) {
            WEB_SEARCH_PUBLIC_CHECK_LABEL
        } else {
            provider.label
        }

    val primaryConfigured: Boolean
        get() {
            if (!enabled) return false
            val endpointForRequest = endpointForRequest()
            return endpointForRequest.isNotBlank() && (!provider.requiresApiKey || apiKey.isNotBlank())
        }

    val configured: Boolean
        get() = primaryConfigured || configuredBackupProviders().isNotEmpty()

    val primaryRealSearchConfigured: Boolean
        get() = primaryConfigured && !isPublicCheckSource

    val realSearchConfigured: Boolean
        get() = primaryRealSearchConfigured || configuredBackupProviders().any { it.realSearchConfigured }

    val realSearchProviderLabel: String
        get() = configuredSearchProviders()
            .filterNot { it.isPublicCheckSource }
            .map { it.providerLabel }
            .distinct()
            .joinToString(" + ")

    val canReadDirectUrls: Boolean
        get() = enabled

    fun rawEndpointForRequest(): String =
        endpoint.trim().ifBlank { provider.defaultEndpoint }.trimEnd('/')

    fun endpointForRequest(): String =
        provider.normalizedEndpointForRequest(rawEndpointForRequest())

    fun sanitizedMaxResults(): Int = maxResults.coerceIn(1, 8)

    internal val canUseJinaReader: Boolean
        get() = enabled && provider == WebSearchProviderType.JINA && apiKey.isNotBlank()

    internal fun configuredBackupProviders(): List<WebSearchBackupProviderConfig> =
        backupProviders.filter { it.configured }.take(MAX_WEB_SEARCH_BACKUP_PROVIDERS)

    internal fun configuredSearchProviders(): List<WebSearchConfig> =
        buildList {
            if (primaryConfigured) add(copy(backupProviders = emptyList()))
            configuredBackupProviders().forEach { add(it.toPrimaryConfig(this@WebSearchConfig)) }
        }

    internal fun withoutPublicCheckSearchSources(): WebSearchConfig {
        val realProviders = configuredSearchProviders().filterNot { it.isPublicCheckSource }
        if (realProviders.isEmpty()) {
            return copy(
                endpoint = "",
                apiKey = "",
                backupProviders = emptyList()
            )
        }
        val primary = realProviders.first()
        val backups = realProviders.drop(1).map { providerConfig ->
            WebSearchBackupProviderConfig(
                enabled = true,
                provider = providerConfig.provider,
                endpoint = providerConfig.endpoint,
                apiKey = providerConfig.apiKey
            )
        }
        return primary.copy(backupProviders = backups)
    }
}

private fun WebSearchProviderType.normalizedEndpointForRequest(rawEndpoint: String): String {
    val endpoint = rawEndpoint.trim().trimEnd('/')
    if (endpoint.isBlank()) return ""
    val parsed = endpoint.toHttpUrlOrNull() ?: return endpoint
    val normalized = when (this) {
        WebSearchProviderType.BRAVE ->
            parsed.withOfficialPathWhenRoot("api.search.brave.com", "/res/v1/web/search")
        WebSearchProviderType.TAVILY ->
            parsed.withOfficialPathWhenRoot("api.tavily.com", "/search")
        else -> null
    }
    return (normalized ?: parsed).toString().trimEnd('/')
}

private fun okhttp3.HttpUrl.withOfficialPathWhenRoot(
    officialHost: String,
    apiPath: String
): okhttp3.HttpUrl? {
    if (!host.equals(officialHost, ignoreCase = true)) return null
    val path = encodedPath.trimEnd('/').ifBlank { "/" }
    if (path != "/") return null
    return newBuilder()
        .encodedPath(apiPath)
        .build()
}
