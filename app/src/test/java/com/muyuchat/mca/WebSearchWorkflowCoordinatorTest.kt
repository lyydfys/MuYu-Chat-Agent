package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [WebSearchWorkflowCoordinator] 的生命周期协调逻辑：
 * 配置持久化、模式流转、诊断增删以及终端意图守卫/引用审计/证据挂载的统一调度。
 */
class WebSearchWorkflowCoordinatorTest {

    private class FakeConfigPort(
        initialState: MainUiState,
        private val replayReducers: Boolean = false,
    ) : WebSearchConfigPort {
        val stateFlow = MutableStateFlow(initialState)
        var savedConfig: WebSearchConfig? = null
        val diagnostics = mutableListOf<WebSearchDiagnosticRecord>()

        override val context: Application
            get() = throw UnsupportedOperationException("context not needed for unit test")

        override fun uiState(): MainUiState = stateFlow.value

        override fun updateUiState(transform: (WebSearchFields) -> WebSearchFields) {
            stateFlow.update { snapshot ->
                val fields = snapshot.toWebSearchFields()
                // A CAS retry may re-run a pure reducer; it must not append the journal again.
                if (replayReducers) transform(fields)
                transform(fields).mergeInto(snapshot)
            }
        }

        override fun saveConfig(config: WebSearchConfig) {
            savedConfig = config
        }

        override fun appendDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> {
            diagnostics.add(record)
            return diagnostics.toList()
        }

        override fun clearDiagnostics(): List<WebSearchDiagnosticRecord> {
            diagnostics.clear()
            return emptyList()
        }

        override val provider: WebSearchProvider
            get() = throw UnsupportedOperationException("provider not needed for unit test")
    }

    private class FakeSettlementPort(initialState: MainUiState) : ChatTerminalSettlementPort {
        val stateFlow = MutableStateFlow(initialState)

        override fun uiState(): MainUiState = stateFlow.value

        override fun updateUiState(transform: (ConversationSettlementFields) -> ConversationSettlementFields) {
            stateFlow.update { snapshot -> transform(snapshot.toConversationSettlementFields()).mergeInto(snapshot) }
        }

        override fun <Result> updateUiStateWithResult(
            transform: (ConversationSettlementFields) -> Pair<ConversationSettlementFields, Result>
        ): Result {
            while (true) {
                val snapshot = stateFlow.value
                val (fields, result) = transform(snapshot.toConversationSettlementFields())
                if (stateFlow.compareAndSet(snapshot, fields.mergeInto(snapshot))) return result
            }
        }

        override val webSearchDiagnosticStore: WebSearchDiagnosticStore
            get() = throw UnsupportedOperationException("diagnostic store not needed")

        override fun currentChatModelId(state: MainUiState): String? = "test-model"

        override fun upsertSession(
            state: MainUiState,
            occurredAt: Long,
            sessions: List<ChatSessionRecord>,
            sessionId: String,
            messages: List<ChatMessage>,
            assistantId: String?,
            modelMode: String?,
            modelId: String?
        ): List<ChatSessionRecord> = sessions

        override fun isRunCurrent(runId: Long?): Boolean = true

        override fun discardPendingOutput(runId: Long?) {}
    }

    @Test
    fun saveWebSearchConfigPersistsAndUpdatesState() {
        val port = FakeConfigPort(MainUiState())
        val settlementPort = FakeSettlementPort(MainUiState())
        val coordinator = WebSearchWorkflowCoordinator(
            port = port,
            terminalPort = settlementPort,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        coordinator.saveWebSearchConfig(
            enabled = true,
            provider = "tavily",
            endpoint = "https://api.tavily.com",
            apiKey = "tvly-test",
            maxResults = 5,
            fetchPageContent = true,
            triggerMode = "auto",
            researchMode = "deep"
        )

        assertNotNull(port.savedConfig)
        assertEquals(true, port.savedConfig?.enabled)
        assertEquals("https://api.tavily.com", port.savedConfig?.endpoint)
        assertEquals("tvly-test", port.savedConfig?.apiKey)
        assertEquals(true, port.uiState().webSearchConfig.enabled)
    }

    @Test
    fun toggleWebSearchForNextTurnCyclesTurnModeWhenEnabled() {
        val enabledConfig = WebSearchConfig(enabled = true, apiKey = "key", endpoint = "https://example.com")
        val port = FakeConfigPort(MainUiState(webSearchConfig = enabledConfig, webSearchTurnMode = WebSearchTurnMode.FOLLOW))
        val settlementPort = FakeSettlementPort(MainUiState())
        val coordinator = WebSearchWorkflowCoordinator(
            port = port,
            terminalPort = settlementPort,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        coordinator.toggleWebSearchForNextTurn()
        assertEquals(WebSearchTurnMode.ON, port.uiState().webSearchTurnMode)
        assertTrue(port.uiState().webSearchOneShotEnabled)

        coordinator.toggleWebSearchForNextTurn()
        assertEquals(WebSearchTurnMode.OFF, port.uiState().webSearchTurnMode)

        coordinator.toggleWebSearchForNextTurn()
        assertEquals(WebSearchTurnMode.FOLLOW, port.uiState().webSearchTurnMode)
    }

    @Test
    fun cycleAndSelectResearchModeUpdatesOverrides() {
        val enabledConfig = WebSearchConfig(enabled = true, apiKey = "key", endpoint = "https://example.com")
        val port = FakeConfigPort(MainUiState(webSearchConfig = enabledConfig))
        val settlementPort = FakeSettlementPort(MainUiState())
        val coordinator = WebSearchWorkflowCoordinator(
            port = port,
            terminalPort = settlementPort,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        coordinator.selectWebSearchResearchModeForNextTurn("DEEP")
        assertEquals(WebSearchResearchMode.DEEP, port.uiState().webSearchResearchModeOverride)

        coordinator.selectWebSearchResearchModeForNextTurn("OFF")
        assertEquals(WebSearchResearchMode.OFF, port.uiState().webSearchResearchModeOverride)

        coordinator.selectWebSearchResearchModeForNextTurn("AUTO")
        assertEquals(null, port.uiState().webSearchResearchModeOverride)
    }

    @Test
    fun diagnosticsAppendAndClearWorkCorrectly() {
        val port = FakeConfigPort(MainUiState())
        val settlementPort = FakeSettlementPort(MainUiState())
        val coordinator = WebSearchWorkflowCoordinator(
            port = port,
            terminalPort = settlementPort,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        val record = WebSearchDiagnosticRecord(
            providerLabel = "test",
            triggerModeLabel = "auto",
            query = "hello",
            searchedQueries = listOf("hello"),
            elapsedMs = 100L,
            success = true,
            message = "ok"
        )
        val appended = coordinator.appendDiagnostic(record)
        assertEquals(1, appended.size)

        coordinator.clearWebSearchDiagnostics()
        assertTrue(port.uiState().webSearchDiagnostics.isEmpty())
    }

    @Test
    fun failedConfigTestAppendsOneDiagnosticWhenStateReducerRetries() = runBlocking {
        val config = WebSearchConfig(enabled = true, apiKey = "key", endpoint = "https://example.com")
        val port = FakeConfigPort(MainUiState(webSearchConfig = config), replayReducers = true)

        // The provider seam deliberately fails; this exercises the failure journal and finally path.
        val job = requireNotNull(runWebSearchConfigTest(
            port = port,
            scope = CoroutineScope(Dispatchers.Unconfined),
            query = "test",
            config = config
        ))
        job.join()

        assertEquals(1, port.diagnostics.size)
        assertEquals(1, port.uiState().webSearchDiagnostics.size)
        assertEquals("联网检索测试失败", port.uiState().statusMessage)
        assertEquals(null, port.uiState().settingsOperations.webSearch)
    }

    @Test
    fun settlementDelegationsForwardToTerminalPort() {
        val initialMessages = listOf(
            ChatMessage(role = Role.USER, content = "Question"),
            ChatMessage(role = Role.ASSISTANT, content = "Answer")
        )
        val settlementPort = FakeSettlementPort(MainUiState(messages = initialMessages))
        val port = FakeConfigPort(MainUiState())
        val coordinator = WebSearchWorkflowCoordinator(
            port = port,
            terminalPort = settlementPort,
            scope = CoroutineScope(Dispatchers.Unconfined)
        )

        // 挂载检索证据
        val sources = listOf(ChatSourceReference(title = "Source 1", url = "https://example.com", snippet = "Snippet"))
        coordinator.attachWebSearchEvidenceToPendingAssistant(sources, trace = null)

        val updatedAssistant = settlementPort.uiState().messages.last()
        assertEquals(1, updatedAssistant.sourceReferences.size)
        assertEquals("Source 1", updatedAssistant.sourceReferences.first().title)

        // 意图守卫
        val audit = coordinator.applyWebSearchAnswerGuardsToLastAssistant()
        assertNotNull(audit)
    }
}
