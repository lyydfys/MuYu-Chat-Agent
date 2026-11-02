package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    fun updateUiState(transform: (WebSearchFields) -> WebSearchFields)

    fun saveConfig(config: WebSearchConfig)

    fun appendDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord>

    fun clearDiagnostics(): List<WebSearchDiagnosticRecord>

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

private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

private fun startWebSearchOperation(
    port: WebSearchConfigPort,
    scope: CoroutineScope,
    operation: WebSearchOperation,
    progressDetail: String,
    progressStatus: String,
    block: suspend () -> Unit
): Job {
    port.updateUiState {
        it.copy(
            settingsOperations = it.settingsOperations.copy(webSearch = operation),
            webSearchStatusMessage = progressDetail,
            statusMessage = progressStatus
        )
    }
    return scope.launch {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val detail = "测试失败：${error.message ?: "未知错误"}"
            port.updateUiState {
                it.copy(
                    webSearchStatusMessage = detail,
                    statusMessage = when (operation) {
                        WebSearchOperation.PREFLIGHT -> "联网网络预检失败"
                        WebSearchOperation.CONFIG_TEST -> "联网检索测试失败"
                        WebSearchOperation.TURN_TEST -> "联网闭环自检未通过"
                    }
                )
            }
        } finally {
            port.updateUiState { state ->
                if (state.settingsOperations.webSearch == operation) {
                    state.copy(settingsOperations = state.settingsOperations.copy(webSearch = null))
                } else {
                    state
                }
            }
        }
    }
}

internal fun runWebSearchConfigTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,
query: String): Job? {
        return runWebSearchConfigTest(port, scope, query, port.uiState().webSearchConfig)
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
    ): Job? {
        return runWebSearchConfigTest(port, scope,
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
    ): Job {
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
        return startWebSearchOperation(
            port = port,
            scope = scope,
            operation = WebSearchOperation.PREFLIGHT,
            progressDetail = "正在进行联网网络预检...",
            progressStatus = "正在进行联网网络预检..."
        ) {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    buildWebSearchPreflightReport(
                        config = config,
                        environmentChecks = androidWebSearchNetworkChecks(port.context)
                    )
                }
            }
            val elapsedMs = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            val (detail, diagnostics, status) = result.fold(
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
                        Triple(report.message, diagnostics,
                            if (report.ok) "联网网络预检通过" else "联网网络预检需检查")
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
                        Triple(message, diagnostics, "联网网络预检失败")
                    }
                )
            port.updateUiState { state ->
                state.copy(
                    webSearchStatusMessage = detail,
                    webSearchDiagnostics = diagnostics,
                    statusMessage = status
                )
            }
        }
    }

internal fun runWebSearchConfigTest(
    port: WebSearchConfigPort,
    scope: CoroutineScope,
query: String, config: WebSearchConfig): Job? {
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
            return null
        }
        return startWebSearchOperation(
            port = port,
            scope = scope,
            operation = WebSearchOperation.CONFIG_TEST,
            progressDetail = "正在测试联网检索：$cleanQuery",
            progressStatus = "正在测试联网检索..."
        ) {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatchingCancellable { port.provider.search(plan, config) }
            val (detail, diagnostics, status) = result.fold(
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
                        Triple(message, diagnostics,
                            if (success) "联网检索测试成功" else "联网检索无结果")
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
                        Triple(message, diagnostics, "联网检索测试失败")
                    }
                )
            port.updateUiState { state ->
                state.copy(
                    webSearchStatusMessage = detail,
                    webSearchDiagnostics = diagnostics,
                    statusMessage = status
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
    ): Job? {
        return runWebSearchTurnTest(port, scope,
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
    ): Job? {
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
            return null
        }
        return startWebSearchOperation(
            port = port,
            scope = scope,
            operation = WebSearchOperation.TURN_TEST,
            progressDetail = "正在闭环自检：$cleanQuery",
            progressStatus = "正在验证联网检索闭环..."
        ) {
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

internal fun applyToggleWebSearchForNextTurn(port: WebSearchConfigPort) {
    val state = port.uiState()
    val assistantDefault = state.selectedAssistant()?.webSearchEnabled == true
    if (!state.webSearchConfig.enabled) {
        port.updateUiState {
            it.copy(
                webSearchTurnMode = WebSearchTurnMode.FOLLOW,
                webSearchOneShotEnabled = false,
                webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                statusMessage = "请先启用联网检索"
            )
        }
        return
    }
    port.updateUiState {
        val nextMode = when (it.webSearchTurnMode) {
            WebSearchTurnMode.FOLLOW -> if (assistantDefault) WebSearchTurnMode.OFF else WebSearchTurnMode.ON
            WebSearchTurnMode.ON -> WebSearchTurnMode.OFF
            WebSearchTurnMode.OFF -> WebSearchTurnMode.FOLLOW
        }
        it.copy(
            webSearchTurnMode = nextMode,
            webSearchOneShotEnabled = nextMode == WebSearchTurnMode.ON,
            webSearchStatusMessage = when (nextMode) {
                WebSearchTurnMode.ON -> when {
                    it.webSearchConfig.realSearchConfigured -> "本轮将联网检索"
                    it.webSearchConfig.isPublicCheckSource -> "当前是协议自检源；本轮仅可读取网页链接"
                    else -> "本轮仅可读取网页链接"
                }
                WebSearchTurnMode.OFF -> "本轮不使用联网检索"
                WebSearchTurnMode.FOLLOW -> if (assistantDefault) "跟随助手默认联网" else "跟随智能联网策略"
            },
            statusMessage = when (nextMode) {
                WebSearchTurnMode.ON -> "本轮已开启联网检索"
                WebSearchTurnMode.OFF -> "本轮已关闭联网检索"
                WebSearchTurnMode.FOLLOW -> "联网检索跟随默认策略"
            }
        )
    }
}

internal fun applyCycleWebSearchResearchModeForNextTurn(port: WebSearchConfigPort) {
    val state = port.uiState()
    if (!state.webSearchConfig.enabled) {
        port.updateUiState {
            it.copy(
                webSearchResearchModeOverride = null,
                webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                statusMessage = "请先启用联网检索"
            )
        }
        return
    }
    port.updateUiState {
        val current = it.webSearchResearchModeOverride ?: it.webSearchConfig.researchMode
        val next = when (current) {
            WebSearchResearchMode.AUTO -> WebSearchResearchMode.DEEP
            WebSearchResearchMode.DEEP -> WebSearchResearchMode.OFF
            WebSearchResearchMode.OFF -> WebSearchResearchMode.AUTO
        }
        it.copy(
            webSearchResearchModeOverride = next.takeUnless { mode -> mode == it.webSearchConfig.researchMode },
            webSearchStatusMessage = "本轮研究模式：${next.label}",
            statusMessage = "本轮研究模式：${next.label}"
        )
    }
}

internal fun applySelectWebSearchResearchModeForNextTurn(port: WebSearchConfigPort, mode: String) {
    val state = port.uiState()
    if (!state.webSearchConfig.enabled) {
        port.updateUiState {
            it.copy(
                webSearchResearchModeOverride = null,
                webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                statusMessage = "请先启用联网检索"
            )
        }
        return
    }
    val selected = WebSearchResearchMode.from(mode)
    port.updateUiState {
        it.copy(
            webSearchResearchModeOverride = selected.takeUnless { next -> next == it.webSearchConfig.researchMode },
            webSearchStatusMessage = "本轮研究模式：${selected.label}",
            statusMessage = "本轮研究模式：${selected.label}"
        )
    }
}

internal fun applyClearWebSearchDiagnostics(port: WebSearchConfigPort) {
    val records = port.clearDiagnostics()
    port.updateUiState {
        it.copy(
            webSearchDiagnostics = records,
            webSearchStatusMessage = null,
            statusMessage = "已清空联网检索记录"
        )
    }
}


