package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import android.app.Application
import kotlinx.coroutines.launch

/**
 * The owner seam for the web-search configuration operations.
 *
 * Six members, and none of them is a lock, a job, a scope or the whole ViewModel: the configuration
 * store, the diagnostic journal, the search provider, the context the preflight reads, and the two
 * halves of the single state publish. The coroutine scope is **not** here -- it is a parameter on the
 * functions that need it, because a scope is a caller's resource, not a capability of the owner.
 *
 * The status lines and the turn-mode/one-shot derivations are a user-visible contract and are
 * reproduced verbatim.
 */
internal interface WebSearchConfigPort {

    /** Application context: the preflight reads ConnectivityManager and private-DNS settings. */
    val context: Application

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun saveConfig(config: WebSearchConfig)

    fun appendDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord>

    val provider: WebSearchProvider
}

internal fun persistWebSearchConfig(
    port: WebSearchConfigPort,

        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        val config = webSearchConfigFrom(
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders
        )
        port.saveConfig(config)
        port.updateUiState {
            val nextTurnMode = if (config.enabled) it.webSearchTurnMode else WebSearchTurnMode.FOLLOW
            it.copy(
                webSearchConfig = config,
                webSearchTurnMode = nextTurnMode,
                webSearchResearchModeOverride = it.webSearchResearchModeOverride?.takeIf { config.enabled },
                webSearchOneShotEnabled = nextTurnMode == WebSearchTurnMode.ON && config.enabled,
                webSearchStatusMessage = null,
                statusMessage = if (config.realSearchConfigured) {
                    "联网检索已保存：${config.realSearchProviderLabel.ifBlank { config.providerLabel }}"
                } else if (config.isPublicCheckSource) {
                    "已保存协议自检源：可在设置页测试，聊天页关键词搜索请配置真实搜索源"
                } else if (config.enabled) {
                    "联网检索已启用：可直读网页链接；关键词搜索还需要补齐地址或 Key"
                } else {
                    "联网检索已关闭"
                }
            )
        }
    }

internal fun runWebSearchConfigTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,
query: String) {
        runWebSearchConfigTest(port, scope, query, port.uiState().webSearchConfig)
    }

internal fun runWebSearchConfigTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,

        query: String,
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        runWebSearchConfigTest(port, scope, 
            query = query,
            config = webSearchConfigFrom(
                enabled = enabled,
                provider = provider,
                endpoint = endpoint,
                apiKey = apiKey,
                maxResults = maxResults,
                fetchPageContent = fetchPageContent,
                triggerMode = triggerMode,
                researchMode = researchMode,
                backupProviders = backupProviders
            )
        )
    }

internal fun runWebSearchPreflight(
    port: WebSearchConfigPort,
    scope: CoroutineScope,

        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        val config = webSearchConfigFrom(
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders
        )
        scope.launch {
            port.updateUiState {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在进行联网网络预检...",
                    statusMessage = "正在进行联网网络预检..."
                )
            }
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    buildWebSearchPreflightReport(
                        config = config,
                        environmentChecks = androidWebSearchNetworkChecks(port.context)
                    )
                }
            }
            port.updateUiState { state ->
                val elapsedMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                result.fold(
                    onSuccess = { report ->
                        val warningChecks = report.checks.filter { it.startsWith("需检查") }
                        val diagnostics = port.appendDiagnostic(
                            WebSearchDiagnosticRecord(
                                providerLabel = config.providerLabel,
                                triggerModeLabel = config.triggerMode.label,
                                query = "网络预检",
                                searchedQueries = listOf(config.endpointForRequest()).filter { it.isNotBlank() },
                                elapsedMs = elapsedMs,
                                success = report.ok,
                                message = report.message,
                                healthScore = if (report.ok) 86 else 35,
                                healthLabel = if (report.ok) "健康" else "需检查",
                                healthReasons = report.checks,
                                qualityScore = if (report.ok) 80 else 20,
                                qualityLabel = if (report.ok) "配置可用" else "配置需检查",
                                qualityReasons = report.checks,
                                warnings = warningChecks,
                                closedLoopChecks = report.checks
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = report.message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = if (report.ok) "联网网络预检通过" else "联网网络预检需检查"
                        )
                    },
                    onFailure = { error ->
                        val message = "网络预检失败：${error.message ?: "未知错误"}"
                        val diagnostics = port.appendDiagnostic(
                            WebSearchDiagnosticRecord(
                                providerLabel = config.providerLabel,
                                triggerModeLabel = config.triggerMode.label,
                                query = "网络预检",
                                searchedQueries = listOf(config.endpointForRequest()).filter { it.isNotBlank() },
                                elapsedMs = elapsedMs,
                                success = false,
                                message = message,
                                healthScore = 0,
                                healthLabel = "失败",
                                healthReasons = listOf(message),
                                qualityScore = 0,
                                qualityLabel = "预检失败",
                                qualityReasons = listOf(message),
                                warnings = listOf(message),
                                closedLoopChecks = listOf(message)
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = "联网网络预检失败"
                        )
                    }
                )
            }
        }
    }

internal fun runWebSearchConfigTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,
query: String, config: WebSearchConfig) {
        val cleanQuery = query.trim().ifBlank { "MCA 本地 AI" }
        val plan = buildWebSearchPlan(cleanQuery, config.researchMode)
        if (!config.configured && !(config.canReadDirectUrls && plan.directUrls.isNotEmpty())) {
            port.updateUiState {
                it.copy(
                    webSearchStatusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索，再测试网页链接读取"
                    } else {
                        "请先保存可用的联网检索配置"
                    },
                    statusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索"
                    } else {
                        "请先保存可用的联网检索配置"
                    }
                )
            }
            return
        }
        scope.launch {
            port.updateUiState {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在测试联网检索：$cleanQuery",
                    statusMessage = "正在测试联网检索..."
                )
            }
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatching { port.provider.search(plan, config) }
            port.updateUiState { state ->
                result.fold(
                    onSuccess = { searchResult ->
                        val success = searchResult.documents.isNotEmpty()
                        val message = if (success) {
                            "测试成功：${searchResult.documents.size} 个来源 · ${searchResult.elapsedMs}ms"
                        } else {
                            "测试完成：没有找到可靠来源"
                        }
                        val diagnostics = port.appendDiagnostic(
                            searchResult.toDiagnosticRecord(
                                config = config,
                                success = success,
                                message = message
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = if (success) "联网检索测试成功" else "联网检索无结果"
                        )
                    },
                    onFailure = { error ->
                        val message = "测试失败：${error.message ?: "未知错误"}"
                        val diagnostics = port.appendDiagnostic(
                            plan.toFailedDiagnosticRecord(
                                config = config,
                                elapsedMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
                                message = message
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = "联网检索测试失败"
                        )
                    }
                )
            }
        }
    }

internal fun runWebSearchTurnTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,

        query: String,
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList(),
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ) {
        runWebSearchTurnTest(port, scope, 
            query = query,
            config = webSearchConfigFrom(
                enabled = enabled,
                provider = provider,
                endpoint = endpoint,
                apiKey = apiKey,
                maxResults = maxResults,
                fetchPageContent = fetchPageContent,
                triggerMode = triggerMode,
                researchMode = researchMode,
                backupProviders = backupProviders
            ),
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }

internal fun runWebSearchTurnTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,

        query: String,
        config: WebSearchConfig,
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ) {
        val cleanQuery = query.trim().ifBlank { "MCA 本地 AI 最新说明" }
        val plan = buildWebSearchPlan(cleanQuery, config.researchMode)
        if (!config.configured && !(config.canReadDirectUrls && plan.directUrls.isNotEmpty())) {
            port.updateUiState {
                it.copy(
                    webSearchStatusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索，再进行闭环自检"
                    } else {
                        "请先填写可用搜索服务，再进行闭环自检"
                    },
                    statusMessage = "联网闭环自检未开始"
                )
            }
            return
        }
        scope.launch {
            port.updateUiState {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在闭环自检：$cleanQuery",
                    statusMessage = "正在验证联网检索闭环..."
                )
            }
            val outcome = executeWebSearchForChatTurn(
                messages = listOf(ChatMessage(role = Role.USER, content = cleanQuery)),
                config = config,
                oneShotEnabled = true,
                assistantWebSearchEnabled = false,
                allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest,
                search = { turnPlan, turnConfig -> port.provider.search(turnPlan, turnConfig) }
            )
            val diagnostics = outcome.diagnostic?.let(port::appendDiagnostic)
            val passed = outcome.success &&
                outcome.promptContext.isNotBlank() &&
                outcome.sourceReferences.isNotEmpty()
            val message = when {
                passed -> "闭环自检通过：${outcome.sourceReferences.size} 个来源 · 已生成上下文和来源卡片数据"
                !outcome.webSearchStatusMessage.isNullOrBlank() -> "闭环自检未通过：${outcome.webSearchStatusMessage}"
                else -> "闭环自检未通过：没有生成可用联网来源"
            }
            port.updateUiState {
                it.copy(
                    busy = false,
                    webSearchStatusMessage = message,
                    webSearchDiagnostics = diagnostics ?: it.webSearchDiagnostics,
                    statusMessage = if (passed) "联网闭环自检通过" else "联网闭环自检未通过"
                )
            }
        }
    }

internal fun webSearchConfigFrom(

        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ): WebSearchConfig =
        WebSearchConfig(
            enabled = enabled,
            provider = WebSearchProviderType.from(provider),
            endpoint = endpoint.trim(),
            apiKey = apiKey.trim(),
            maxResults = maxResults.coerceIn(1, 8),
            fetchPageContent = fetchPageContent,
            triggerMode = WebSearchTriggerMode.from(triggerMode),
            researchMode = WebSearchResearchMode.from(researchMode),
            backupProviders = backupProviders.take(3)
        )

