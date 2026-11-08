package com.muyuchat.mca

import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * R4-S24: 网络检索配置、测试与意图守卫整族下沉协调器。
 *
 * 承载网络检索配置持久化、配置预检、单项配置测试、轮次检索闭环测试、
 * 轮次开关切换、研究模式选择/循环、诊断记录追加与清空，
 * 以及检索意图守卫、引用审计与证据挂载等完整生命周期操作。
 */
internal class WebSearchWorkflowCoordinator(
    private val port: WebSearchConfigPort,
    private val terminalPort: ChatTerminalSettlementPort,
    private val scope: CoroutineScope
) {
    private val operationLock = Any()
    private var activeOperationJob: Job? = null

    private fun launchExclusive(start: () -> Job?): Job? = synchronized(operationLock) {
        if (activeOperationJob?.isActive == true) return null
        val job = start() ?: return null
        activeOperationJob = job
        job.invokeOnCompletion {
            synchronized(operationLock) {
                if (activeOperationJob === job) activeOperationJob = null
            }
        }
        job
    }

    /** 委托持久化联网检索配置。 */
    fun saveWebSearchConfig(
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
        persistWebSearchConfig(
            port = port,
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
    }

    /** 委托执行联网检索配置测试（使用当前存储配置）。 */
    fun testWebSearchConfig(query: String): Job? = launchExclusive {
        runWebSearchConfigTest(port = port, scope = scope, query = query)
    }

    /** 委托执行联网检索配置测试（使用传入参数）。 */
    fun testWebSearchConfig(
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
    ): Job? = launchExclusive {
        runWebSearchConfigTest(
            port = port,
            scope = scope,
            query = query,
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
    }

    /** 委托执行联网检索配置测试（使用指定配置）。 */
    fun testWebSearchConfig(query: String, config: WebSearchConfig): Job? = launchExclusive {
        runWebSearchConfigTest(
            port = port,
            scope = scope,
            query = query,
            config = config
        )
    }

    /** 委托执行联网检索网络与环境预检。 */
    fun preflightWebSearchConfig(
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ): Job? = launchExclusive {
        runWebSearchPreflight(
            port = port,
            scope = scope,
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
    }

    /** 委托执行单轮闭环自检测试。 */
    fun testWebSearchTurn(
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
    ): Job? = launchExclusive {
        runWebSearchTurnTest(
            port = port,
            scope = scope,
            query = query,
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders,
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }

    /** 委托执行单轮闭环自检测试（使用指定配置）。 */
    fun testWebSearchTurn(
        query: String,
        config: WebSearchConfig,
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ): Job? = launchExclusive {
        runWebSearchTurnTest(
            port = port,
            scope = scope,
            query = query,
            config = config,
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }

    /** 切换下一轮检索开关。 */
    fun toggleWebSearchForNextTurn() = applyToggleWebSearchForNextTurn(port)

    /** `+` menu Agent mode: mutually exclusive with image-prompt/research (ChatComposerModes). */
    fun changeAgentMode(enabled: Boolean) = applyChangeAgentMode(port, enabled)

    /** Drops the next-turn research override without touching the saved default. */
    fun clearWebSearchResearchModeForNextTurn() = applyClearWebSearchResearchModeForNextTurn(port)

    /** 循环切换研究模式。 */
    fun cycleWebSearchResearchModeForNextTurn() = applyCycleWebSearchResearchModeForNextTurn(port)

    /** 指定下一轮研究模式。 */
    fun selectWebSearchResearchModeForNextTurn(mode: String) = applySelectWebSearchResearchModeForNextTurn(port, mode)

    /** 清空联网检索诊断记录。 */
    fun clearWebSearchDiagnostics() = applyClearWebSearchDiagnostics(port)

    /** 追加单条检索诊断记录。 */
    fun appendDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> =
        port.appendDiagnostic(record)

    /** 委托终端结算应用联网检索意图守卫。 */
    fun applyWebSearchAnswerGuardsToLastAssistant(
        generationRunId: Long? = null
    ): WebSearchCitationAudit? = settleWebSearchAnswerGuards(port = terminalPort, generationRunId = generationRunId)

    /** 委托终端结算记录联网检索引用审计。 */
    fun recordWebSearchCitationAudit(
        recordId: String?,
        audit: WebSearchCitationAudit,
        generationRunId: Long? = null
    ) {
        settleWebSearchCitationAudit(port = terminalPort, recordId = recordId, audit = audit, generationRunId = generationRunId)
    }

    /** 委托终端结算挂载联网检索证据。 */
    fun attachWebSearchEvidenceToPendingAssistant(
        sources: List<ChatSourceReference>,
        trace: ChatWebSearchTrace?,
        generationRunId: Long? = null
    ) {
        settleWebSearchEvidenceAttachment(port = terminalPort, sources = sources, trace = trace, generationRunId = generationRunId)
    }
}
