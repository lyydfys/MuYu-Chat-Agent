package com.muyuchat.mca

import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class WebSearchCacheEntry(
    val storedAtMillis: Long,
    val result: WebSearchResult
)

class WebSearchProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(28, TimeUnit.SECONDS)
        .build(),
    private val allowPrivateNetworkFetch: Boolean = false,
    private val cacheTtlMillis: Long = DEFAULT_WEB_SEARCH_CACHE_TTL_MS,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock,
    private val dnsResolver: ((String) -> List<InetAddress>)? = null
) {
    private val cacheLock = Any()
    private val searchCache = linkedMapOf<String, WebSearchCacheEntry>()

    suspend fun search(query: String, config: WebSearchConfig): WebSearchResult = withContext(Dispatchers.IO) {
        executeSearch(buildWebSearchPlan(query, config.researchMode), config)
    }

    suspend fun search(plan: WebSearchPlan, config: WebSearchConfig): WebSearchResult = withContext(Dispatchers.IO) {
        executeSearch(plan, config)
    }

    private suspend fun executeSearch(plan: WebSearchPlan, config: WebSearchConfig): WebSearchResult {
        val cleanQuestion = plan.displayQuery.cleanSearchQuery()
        require(cleanQuestion.isNotBlank()) { "搜索问题为空" }
        val providerConfigs = config.configuredSearchProviders()
        val canSearchProvider = providerConfigs.isNotEmpty() && plan.shouldRunSearchProvider(config)
        val canReadDirectUrls = config.canReadDirectUrls && plan.directUrls.isNotEmpty()
        require(providerConfigs.isNotEmpty() || canReadDirectUrls) {
            if (plan.directUrls.isNotEmpty()) {
                "请先在系统设置 > 联网检索 启用联网后再读取网页链接"
            } else {
                "请先在系统设置 > 联网检索 配置搜索服务"
            }
        }
        val started = clock.nowMs()
        val directDocuments = fetchDirectDocuments(plan.directUrls.take(3), config)
            .filter { it.content.isNotBlank() || it.snippet.isNotBlank() }
        val attemptedQueries = if (canSearchProvider) {
            plan.queries.ifEmpty { listOf(cleanQuestion) }.take(plan.providerQueryLimit())
        } else {
            emptyList()
        }
        val cacheKey = buildSearchCacheKey(plan, config, cleanQuestion, attemptedQueries, canSearchProvider)
        cachedSearchResult(cacheKey)?.let { return it }
        var searchCollection = if (canSearchProvider) {
            collectProviderDocuments(
                queries = attemptedQueries,
                providerConfigs = providerConfigs,
                hasDirectDocuments = directDocuments.isNotEmpty()
            )
        } else {
            WebSearchProviderCollection()
        }
        var relevanceSelection = (directDocuments + searchCollection.documents)
            .distinctBy { it.url.normalizedUrlKey() }
            .selectRelevantForQuestion(
                question = cleanQuestion,
                queries = plan.queries,
                directUrls = plan.directUrls,
                maxResults = config.sanitizedMaxResults()
            )
        if (
            canSearchProvider &&
            directDocuments.isEmpty() &&
            searchCollection.documents.isNotEmpty() &&
            (
                relevanceSelection.documents.isEmpty() ||
                    relevanceSelection.warnings.any { it.contains("唯一来源相关性较弱") }
                )
        ) {
            val relevanceFallbackQueries = attemptedQueries
                .fallbackSearchQueries()
                .filterNot { it in searchCollection.searchedQueries }
                .take(2)
            if (relevanceFallbackQueries.isNotEmpty()) {
                val fallbackCollection = collectProviderDocuments(
                    queries = relevanceFallbackQueries,
                    providerConfigs = providerConfigs,
                    hasDirectDocuments = true,
                    stopAfterFirstWithDocuments = false,
                    backupAttemptReason = "主搜索源相关性不足"
                )
                searchCollection = WebSearchProviderCollection(
                    documents = (searchCollection.documents + fallbackCollection.documents)
                        .distinctBy { it.url.normalizedUrlKey() },
                    warnings = (
                        searchCollection.warnings +
                            "相关性过滤后无可用来源，已自动尝试核心检索词：${relevanceFallbackQueries.joinToString(" / ")}" +
                            fallbackCollection.warnings
                        ).distinct(),
                    searchedQueries = (searchCollection.searchedQueries + fallbackCollection.searchedQueries).distinct(),
                    providerLabels = (searchCollection.providerLabels + fallbackCollection.providerLabels).distinct()
                )
                relevanceSelection = (directDocuments + searchCollection.documents)
                    .distinctBy { it.url.normalizedUrlKey() }
                    .selectRelevantForQuestion(
                        question = cleanQuestion,
                        queries = plan.queries + relevanceFallbackQueries,
                        directUrls = plan.directUrls,
                        maxResults = config.sanitizedMaxResults()
                    )
            }
        }
        val baseDocuments = relevanceSelection.documents
        val documents = if (config.fetchPageContent) {
            hydrateReadableContent(baseDocuments, config)
        } else {
            baseDocuments
        }
        val cacheStatus = if (cacheKey == null) {
            WEB_SEARCH_CACHE_NOT_USED
        } else {
            WEB_SEARCH_CACHE_STORED
        }
        val result = WebSearchResult(
            query = cleanQuestion,
            providerLabel = config.providerLabelForResult(
                providerLabels = searchCollection.providerLabels,
                includeDirectRead = directDocuments.isNotEmpty()
            ),
            documents = documents,
            elapsedMs = (clock.nowMs() - started).coerceAtLeast(0L),
            searchedQueries = searchCollection.searchedQueries.ifEmpty { attemptedQueries },
            directUrls = plan.directUrls,
            warnings = (
                searchCollection.warnings +
                    relevanceSelection.warnings +
                    if (canSearchProvider) config.publicCheckSourceWarnings() else emptyList()
                ).distinct(),
            cacheStatus = cacheStatus
        )
        if (cacheKey != null && result.documents.isNotEmpty()) {
            storeSearchResult(cacheKey, result)
        }
        return result
    }

    private fun buildSearchCacheKey(
        plan: WebSearchPlan,
        config: WebSearchConfig,
        cleanQuestion: String,
        attemptedQueries: List<String>,
        canSearchProvider: Boolean
    ): String? {
        if (cacheTtlMillis <= 0L || !canSearchProvider || plan.directUrls.isNotEmpty()) return null
        val providerChain = config.configuredSearchProviders()
        if (providerChain.isEmpty()) return null
        val queries = attemptedQueries.ifEmpty { listOf(cleanQuestion) }
            .map { it.cleanSearchQuery().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
        if (queries.isEmpty()) return null
        return listOf(
            "v4",
            providerChain.joinToString("\u001D") { "${it.provider.name}@${it.endpointForRequest()}" },
            config.sanitizedMaxResults().toString(),
            config.fetchPageContent.toString(),
            queries.joinToString("\u001F")
        ).joinToString("\u001E")
    }

    private fun cachedSearchResult(cacheKey: String?): WebSearchResult? {
        if (cacheKey == null) return null
        val now = nowMillis()
        return synchronized(cacheLock) {
            val entry = searchCache[cacheKey] ?: return@synchronized null
            if (now - entry.storedAtMillis > cacheTtlMillis) {
                searchCache.remove(cacheKey)
                null
            } else {
                entry.result.copy(
                    elapsedMs = 0L,
                    cacheStatus = WEB_SEARCH_CACHE_HIT
                )
            }
        }
    }

    private fun storeSearchResult(cacheKey: String, result: WebSearchResult) {
        val now = nowMillis()
        synchronized(cacheLock) {
            searchCache[cacheKey] = WebSearchCacheEntry(now, result)
            while (searchCache.size > MAX_WEB_SEARCH_CACHE_ENTRIES) {
                val oldestKey = searchCache.keys.firstOrNull() ?: break
                searchCache.remove(oldestKey)
            }
        }
    }

    private suspend fun fetchDirectDocuments(urls: List<String>, config: WebSearchConfig): List<WebSearchDocument> =
        runDirectDocumentFetch(readableFetchPort, urls, config)

    private val collectionPort = object : WebSearchCollectionPort {
        override fun searchProviderQuery(query: String, config: WebSearchConfig): List<WebSearchDocument> =
            this@WebSearchProvider.searchProviderQuery(query, config)

        override fun fetchReadableDocument(url: String, provider: String, config: WebSearchConfig?): WebSearchDocument =
            this@WebSearchProvider.fetchReadableDocument(url, provider, config)
    }

    private suspend fun collectProviderDocuments(
        queries: List<String>,
        providerConfigs: List<WebSearchConfig>,
        hasDirectDocuments: Boolean,
        stopAfterFirstWithDocuments: Boolean = true,
        backupAttemptReason: String = "主搜索源未获得可用来源"
    ): WebSearchProviderCollection =
        runProviderDocumentCollection(collectionPort, queries, providerConfigs, hasDirectDocuments, stopAfterFirstWithDocuments, backupAttemptReason)

    private suspend fun hydrateReadableContent(
        documents: List<WebSearchDocument>,
        config: WebSearchConfig
    ): List<WebSearchDocument> =
        hydrateReadableContentVia(collectionPort, documents, config)

    private val queryOperationsPort = object : WebSearchHttpPort {
        override val client: OkHttpClient get() = this@WebSearchProvider.client
    }

    private fun searchProviderQuery(query: String, config: WebSearchConfig): List<WebSearchDocument> =
        runProviderQuery(queryOperationsPort, query, config)

    private val readableFetchPort = object : WebSearchReadableFetchPort {
        override val client: OkHttpClient get() = this@WebSearchProvider.client
        override val allowPrivateNetworkFetch: Boolean get() = this@WebSearchProvider.allowPrivateNetworkFetch
        override val dnsResolver: ((String) -> List<InetAddress>)? get() = this@WebSearchProvider.dnsResolver ?: { hostname ->
            if (this@WebSearchProvider.client.dns != okhttp3.Dns.SYSTEM) {
                this@WebSearchProvider.client.dns.lookup(hostname)
            } else {
                InetAddress.getAllByName(hostname).toList()
            }
        }
    }

    private fun fetchReadableDocument(url: String, provider: String, config: WebSearchConfig? = null): WebSearchDocument =
        fetchReadableDocumentVia(readableFetchPort, url, provider, config)

}
