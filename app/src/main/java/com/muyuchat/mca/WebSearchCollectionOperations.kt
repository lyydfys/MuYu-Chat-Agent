package com.muyuchat.mca

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Web-search provider-collection operations (P7/S17), third intra-class extraction from
 * WebSearchProvider: backup-provider collection, per-provider collection, query fan-out, provider
 * warnings and readable-content hydration, moved verbatim behind [WebSearchCollectionPort], which
 * re-enters the class through its two existing same-signature delegates (searchProviderQuery /
 * fetchReadableDocument). WebSearchProviderCollection and WebSearchQueryAttempt were widened
 * private->internal in place; same-package resolution keeps every caller unchanged.
 */

internal interface WebSearchCollectionPort {
    fun searchProviderQuery(query: String, config: WebSearchConfig): List<WebSearchDocument>
    fun fetchReadableDocument(url: String, provider: String, config: WebSearchConfig?): WebSearchDocument
}

internal suspend fun runProviderDocumentCollection(
    port: WebSearchCollectionPort,
    queries: List<String>,
    providerConfigs: List<WebSearchConfig>,
    hasDirectDocuments: Boolean,
    stopAfterFirstWithDocuments: Boolean = true,
    backupAttemptReason: String = "主搜索源未获得可用来源"
): WebSearchProviderCollection {
    var merged = WebSearchProviderCollection()
    providerConfigs.forEachIndexed { index, providerConfig ->
        val collection = runCatching {
            collectSingleProviderDocuments(port = port,
                queries = queries,
                config = providerConfig,
                hasDirectDocuments = hasDirectDocuments || merged.documents.isNotEmpty()
            )
        }.getOrElse { error ->
            WebSearchProviderCollection(
                warnings = listOf("${providerConfig.providerLabel}: ${error.friendlyWebSearchFailureMessage()}"),
                providerLabels = listOf(providerConfig.providerLabel)
            )
        }
        val fallbackWarning = if (index > 0) {
            listOf("$backupAttemptReason，已尝试备用搜索源：${providerConfig.providerLabel}")
        } else {
            emptyList()
        }
        merged = WebSearchProviderCollection(
            documents = (merged.documents + collection.documents)
                .distinctBy { it.url.normalizedUrlKey() },
            warnings = (merged.warnings + fallbackWarning + collection.warnings).distinct(),
            searchedQueries = (merged.searchedQueries + collection.searchedQueries).distinct(),
            providerLabels = (merged.providerLabels + collection.providerLabels + providerConfig.providerLabel).distinct()
        )
        if (stopAfterFirstWithDocuments && merged.documents.isNotEmpty()) return merged
    }
    if (merged.documents.isEmpty() && merged.warnings.isNotEmpty() && !hasDirectDocuments) {
        error("搜索服务全部失败：${merged.warnings.joinToString("；").limitForPrompt(280)}")
    }
    return merged
}

internal suspend fun collectSingleProviderDocuments(
    port: WebSearchCollectionPort,
    queries: List<String>,
    config: WebSearchConfig,
    hasDirectDocuments: Boolean
): WebSearchProviderCollection {
    val plannedQueries = queries.map { it.cleanSearchQuery() }.filter { it.isNotBlank() }.distinct().take(MAX_WEB_SEARCH_QUERY_ATTEMPTS)
    val initialResults = runProviderQueries(port, plannedQueries, config)
    val initialDocuments = initialResults.flatMap { it.documents }
    val fallbackQueries = if (initialDocuments.isEmpty()) {
        plannedQueries.fallbackSearchQueries().filterNot { it in plannedQueries }.take(2)
    } else {
        emptyList()
    }
    val fallbackResults = if (fallbackQueries.isNotEmpty()) runProviderQueries(port, fallbackQueries, config) else emptyList()
    val results = initialResults + fallbackResults
    val documents = results.flatMap { it.documents }
    val failures = results.mapNotNull { it.failure }
    val emptyQueries = results
        .filter { it.documents.isEmpty() && it.failure == null }
        .map { it.query }
    if (documents.isEmpty() && failures.isNotEmpty() && !hasDirectDocuments) {
        error("搜索服务全部失败：${failures.joinToString("；").limitForPrompt(280)}")
    }
    val warnings = buildList {
        addAll(failures.take(5))
        if (fallbackQueries.isNotEmpty()) {
            add("初始检索无结果，已自动尝试简化检索词：${fallbackQueries.joinToString(" / ")}")
        }
        emptyQueries.take(3).forEach { query ->
            add("${query.take(36)}：搜索服务返回空结果")
        }
    }
    return WebSearchProviderCollection(
        documents = documents,
        warnings = warnings.distinct(),
        searchedQueries = results.map { it.query }.distinct(),
        providerLabels = if (results.isNotEmpty() || documents.isNotEmpty() || warnings.isNotEmpty()) {
            listOf(config.providerLabel)
        } else {
            emptyList()
        }
    )
}

internal suspend fun runProviderQueries(
    port: WebSearchCollectionPort,
    queries: List<String>,
    config: WebSearchConfig
): List<WebSearchQueryAttempt> =
    coroutineScope {
        queries.map { plannedQuery ->
            async {
                runCatching { port.searchProviderQuery(plannedQuery, config) }
                    .fold(
                        onSuccess = { documents ->
                            WebSearchQueryAttempt(query = plannedQuery, documents = documents)
                        },
                        onFailure = { error ->
                            WebSearchQueryAttempt(
                                query = plannedQuery,
                                failure = "${plannedQuery.take(36)}：${error.friendlyWebSearchFailureMessage()}"
                            )
                        }
                    )
            }
        }.awaitAll()
    }

internal fun List<WebSearchQueryAttempt>.toProviderWarnings(): List<String> {
    val failures = mapNotNull { it.failure }
    val emptyQueries = filter { it.documents.isEmpty() && it.failure == null }.map { it.query }
    return buildList {
        addAll(failures.take(5))
        emptyQueries.take(3).forEach { query ->
            add("${query.take(36)}：搜索服务返回空结果")
        }
    }
}

internal suspend fun hydrateReadableContentVia(
    port: WebSearchCollectionPort,
    documents: List<WebSearchDocument>,
    config: WebSearchConfig
): List<WebSearchDocument> =
    coroutineScope {
        documents.mapIndexed { index, document ->
            async {
                if (index >= 3 || document.content.length > 500) {
                    document
                } else {
                    val fetched = runCatching {
                        port.fetchReadableDocument(document.url, document.provider.ifBlank { config.providerLabel }, config)
                    }.getOrNull()
                    if (fetched == null || fetched.content.isBlank()) {
                        document
                    } else {
                        document.copy(
                            title = document.title.ifBlank { fetched.title },
                            snippet = document.snippet.ifBlank { fetched.snippet },
                            content = fetched.content
                        )
                    }
                }
            }
        }.awaitAll()
    }
