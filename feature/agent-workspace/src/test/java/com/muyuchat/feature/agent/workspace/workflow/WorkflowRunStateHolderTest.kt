package com.muyuchat.feature.agent.workspace.workflow

import com.muyuchat.feature.agent.workspace.AgentWorkflowAdminOutcome
import com.muyuchat.feature.agent.workspace.AgentWorkflowAdminPort
import com.muyuchat.feature.agent.workspace.AgentWorkflowListResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowRecipeItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM behaviour of [WorkflowRunStateHolder] over fake admin/history/run adapters.
 *
 * The Compose layer is not exercised here (device visual/a11y evidence stays NOT_RUN); these tests
 * pin the page state machine: definition loading/empty/error, selection, terminal receipts, typed
 * failures, replay notices, history refresh and single-flight run protection.
 */
class WorkflowRunStateHolderTest {

    private var now = 1_000_000L

    private class FakeAdmin(
        var listResult: AgentWorkflowListResult = AgentWorkflowListResult.Loaded(emptyList())
    ) : AgentWorkflowAdminPort {
        var listCalls = 0
        var throwOnList = false

        override suspend fun list(): AgentWorkflowListResult {
            listCalls += 1
            if (throwOnList) error("admin down")
            return listResult
        }

        override suspend fun create(name: String, goalTemplate: String): AgentWorkflowAdminOutcome =
            AgentWorkflowAdminOutcome.Rejected("WORKFLOW_ADMIN_UNAVAILABLE", "admin unavailable")
    }

    private fun recipe(
        id: String,
        name: String = "工作流 $id",
        versionLabel: String = "v1"
    ): AgentWorkflowRecipeItem = AgentWorkflowRecipeItem(
        recipeId = id,
        name = name,
        goalTemplate = "总结 $id 的要点",
        versionLabel = versionLabel,
        updatedLabel = "今天 09:12"
    )

    private fun historyEntry(
        runId: String = "run-1",
        definitionId: String = "recipe-1",
        version: Long = 1L,
        trigger: WorkflowRunTrigger = WorkflowRunTrigger.MANUAL,
        status: WorkflowRunStatus = WorkflowRunStatus.COMPLETED,
        startedAtMillis: Long = now - 60_000L,
        errorCode: String? = null
    ): WorkflowRunHistoryEntry = WorkflowRunHistoryEntry(
        runId = runId,
        definitionId = definitionId,
        version = version,
        trigger = trigger,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = startedAtMillis + 1_000L,
        status = status,
        errorCode = errorCode
    )

    private fun receipt(
        definitionId: String = "recipe-1",
        version: Long = 1L,
        status: WorkflowRunReceiptStatus = WorkflowRunReceiptStatus.COMPLETED,
        runId: String? = "run-1",
        replay: Boolean = false,
        errorCode: String? = null,
        safeMessage: String? = null
    ): WorkflowRunReceipt = WorkflowRunReceipt(
        commandId = "cmd-1",
        definitionId = definitionId,
        version = version,
        trigger = WorkflowRunTrigger.MANUAL,
        runId = runId,
        status = status,
        errorCode = errorCode,
        safeMessage = safeMessage,
        completedAtMillis = now,
        idempotentReplay = replay
    )

    private fun holder(
        admin: FakeAdmin,
        history: suspend (Int) -> List<WorkflowRunHistoryEntry> = { emptyList() },
        run: suspend (String, Long) -> WorkflowRunReceipt = { definitionId, version ->
            receipt(definitionId = definitionId, version = version)
        }
    ): WorkflowRunStateHolder = WorkflowRunStateHolder(
        definitions = admin,
        history = history,
        runManual = run,
        clock = { now },
        dispatcher = Dispatchers.Unconfined
    )

    // --- loading --------------------------------------------------------------------------------

    @Test
    fun `refresh loads definitions and history with parsed versions`() = runBlocking {
        val admin = FakeAdmin(
            AgentWorkflowListResult.Loaded(
                listOf(
                    recipe("recipe-1", "每周回顾", "v3"),
                    recipe("recipe-2", "简报整理", "12")
                )
            )
        )
        val limits = mutableListOf<Int>()
        val holder = holder(
            admin = admin,
            history = { limit ->
                limits += limit
                listOf(historyEntry(runId = "run-a"), historyEntry(runId = "run-b", status = WorkflowRunStatus.FAILED))
            }
        )

        holder.refresh()

        val state = holder.state.value
        assertEquals(1, admin.listCalls)
        assertEquals(listOf(WorkflowRunStateHolder.DEFAULT_HISTORY_LIMIT), limits)
        assertEquals(listOf("recipe-1", "recipe-2"), state.definitions.map { it.definitionId })
        assertEquals(listOf(3L, 12L), state.definitions.map { it.version })
        assertEquals(2, state.history.size)
        assertEquals("已完成", state.history[0].statusLabel)
        assertEquals("失败", state.history[1].statusLabel)
        assertFalse(state.loadingDefinitions)
        assertFalse(state.loadingHistory)
        assertNull(state.definitionsError)
        assertNull(state.historyError)
        assertNotNull(state.lastRefreshLabel)
    }

    @Test
    fun `a single runnable definition is selected without an extra tap`() = runBlocking {
        val admin = FakeAdmin(
            AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1", "每周回顾", "v3")))
        )
        val holder = holder(admin = admin)

        holder.refresh()

        val state = holder.state.value
        assertEquals("recipe-1", state.selectedDefinitionId)
        assertTrue(state.canRun)
        assertEquals(3L, state.selectedDefinition?.version)
    }

    @Test
    fun `a rejected definition list is typed and history still loads`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Rejected("WORKFLOW_ADMIN_UNAVAILABLE", "未接入"))
        val holder = holder(admin = admin, history = { listOf(historyEntry()) })

        holder.refresh()

        val state = holder.state.value
        assertEquals("WORKFLOW_ADMIN_UNAVAILABLE", state.definitionsError?.code)
        assertTrue(state.definitions.isEmpty())
        assertEquals(1, state.history.size)
        assertFalse(state.loadingDefinitions)
        assertFalse(state.loadingHistory)
    }

    @Test
    fun `an exception from the definition port maps to the typed failure and history still loads`() = runBlocking {
        val admin = FakeAdmin().apply { throwOnList = true }
        val holder = holder(admin = admin, history = { listOf(historyEntry()) })

        holder.refresh()

        val state = holder.state.value
        assertEquals(WorkflowRunStateHolder.ERROR_DEFINITIONS, state.definitionsError?.code)
        assertEquals(1, state.history.size)
        assertFalse(state.loadingDefinitions)
    }

    @Test
    fun `a rejected history keeps definitions and shows a typed history error`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
        val holder = holder(
            admin = admin,
            history = { throw IllegalStateException("history down") }
        )

        holder.refresh()

        val state = holder.state.value
        assertEquals(1, state.definitions.size)
        assertEquals(WorkflowRunStateHolder.ERROR_HISTORY, state.historyError?.code)
        assertFalse(state.loadingHistory)
    }

    // --- selection / run ------------------------------------------------------------------------

    @Test
    fun `selecting a parsed definition enables an explicit run and refreshes history`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1", versionLabel = "v2"))))
        val runCalls = mutableListOf<Pair<String, Long>>()
        var historyCalls = 0
        val holder = holder(
            admin = admin,
            history = {
                historyCalls += 1
                listOf(historyEntry())
            },
            run = { definitionId, version ->
                runCalls += definitionId to version
                receipt(definitionId = definitionId, version = version, runId = "run-42")
            }
        )
        holder.refresh()
        holder.selectDefinition("recipe-1")

        assertTrue(holder.state.value.canRun)
        holder.runSelected()

        val state = holder.state.value
        assertEquals(listOf("recipe-1" to 2L), runCalls)
        assertEquals(2, historyCalls)
        assertEquals(WorkflowRunReceiptStatus.COMPLETED, state.receipt?.status)
        assertEquals("已完成", state.receipt?.statusLabel)
        assertEquals("run-42", state.receipt?.runId)
        assertFalse(state.running)
        assertNull(state.actionError)
        assertNull(state.notice)
    }

    @Test
    fun `failed and rejected receipts stay visible with their typed codes`() = runBlocking {
        val cases = listOf(
            receipt(status = WorkflowRunReceiptStatus.FAILED, errorCode = "WF_EXECUTION_FAILED"),
            receipt(status = WorkflowRunReceiptStatus.REJECTED, errorCode = "WF_VERSION_NOT_FOUND")
        )
        cases.forEach { expected ->
            val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
            val holder = holder(admin = admin, run = { _, _ -> expected })
            holder.refresh()
            holder.selectDefinition("recipe-1")

            holder.runSelected()

            val state = holder.state.value
            assertEquals(expected.status, state.receipt?.status)
            assertEquals(expected.errorCode, state.receipt?.errorCode)
            assertNull("a receipt failure is not a page error", state.actionError)
            assertFalse(state.running)
        }
    }

    @Test
    fun `repeated clicks are forwarded to the facade and a replay is surfaced as a notice`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
        var runCalls = 0
        val holder = holder(
            admin = admin,
            run = { definitionId, version ->
                runCalls += 1
                receipt(definitionId = definitionId, version = version, replay = true)
            }
        )
        holder.refresh()
        holder.selectDefinition("recipe-1")

        holder.runSelected()
        holder.runSelected()

        assertEquals(2, runCalls)
        assertTrue(holder.state.value.receipt?.replay == true)
        assertEquals(WorkflowRunStateHolder.NOTICE_REPLAY, holder.state.value.notice)
    }

    @Test
    fun `a run exception becomes a typed action error and clears in-flight`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
        val holder = holder(admin = admin, run = { _, _ -> error("facade down") })
        holder.refresh()
        holder.selectDefinition("recipe-1")

        holder.runSelected()

        val state = holder.state.value
        assertEquals(WorkflowRunStateHolder.ERROR_RUN, state.actionError?.code)
        assertFalse(state.running)
        assertNull(state.receipt)
    }

    @Test
    fun `an unparseable version label cannot run and reports the typed reason`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1", versionLabel = "vNext"))))
        var runCalls = 0
        val holder = holder(admin = admin, run = { _, _ -> runCalls += 1; receipt() })
        holder.refresh()

        val definition = holder.state.value.definitions.single()
        assertNull(definition.version)
        assertFalse(definition.runnable)
        assertFalse(holder.state.value.canRun)

        holder.selectDefinition("recipe-1")
        holder.runSelected()

        assertEquals(0, runCalls)
        assertEquals(WorkflowRunStateHolder.ERROR_VERSION, holder.state.value.actionError?.code)
    }

    @Test
    fun `running without a selection is a no-op`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(emptyList()))
        var runCalls = 0
        val holder = holder(admin = admin, run = { _, _ -> runCalls += 1; receipt() })
        holder.refresh()

        holder.runSelected()

        assertEquals(0, runCalls)
        assertNull(holder.state.value.actionError)
    }

    @Test
    fun `a concurrent second click is dropped while the first run is in flight`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runCalls = 0
        val holder = holder(
            admin = admin,
            run = { definitionId, version ->
                runCalls += 1
                entered.complete(Unit)
                release.await()
                receipt(definitionId = definitionId, version = version)
            }
        )
        holder.refresh()
        holder.selectDefinition("recipe-1")

        val first = launch { holder.runSelected() }
        entered.await()
        val second = launch { holder.runSelected() }
        second.join()

        assertEquals("the re-entrant click must not start a run", 1, runCalls)
        release.complete(Unit)
        first.join()

        assertEquals(1, runCalls)
        assertNotNull(holder.state.value.receipt)
    }

    @Test
    fun `history refresh after a run keeps the receipt and surfaces a history failure`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Loaded(listOf(recipe("recipe-1"))))
        var historyCalls = 0
        val holder = holder(
            admin = admin,
            history = {
                historyCalls += 1
                if (historyCalls == 1) emptyList() else error("history down")
            },
            run = { definitionId, version -> receipt(definitionId = definitionId, version = version) }
        )
        holder.refresh()
        holder.selectDefinition("recipe-1")

        holder.runSelected()

        val state = holder.state.value
        assertNotNull(state.receipt)
        assertEquals(WorkflowRunStateHolder.ERROR_HISTORY, state.historyError?.code)
        assertFalse(state.running)
    }

    @Test
    fun `dismiss intents clear only their own slot`() = runBlocking {
        val admin = FakeAdmin(AgentWorkflowListResult.Rejected("WORKFLOW_ADMIN_UNAVAILABLE", "未接入"))
        val holder = holder(admin = admin, history = { error("history down") })
        holder.refresh()

        assertNotNull(holder.state.value.definitionsError)
        assertNotNull(holder.state.value.historyError)

        holder.dismissDefinitionsError()
        assertNull(holder.state.value.definitionsError)
        assertNotNull(holder.state.value.historyError)

        holder.dismissHistoryError()
        assertNull(holder.state.value.historyError)

        holder.dismissActionError()
        holder.dismissNotice()
        assertNull(holder.state.value.actionError)
        assertNull(holder.state.value.notice)
    }

    @Test
    fun `version parsing accepts v-prefixed and plain digits only`() {
        assertEquals(3L, workflowVersionFromLabel("v3"))
        assertEquals(3L, workflowVersionFromLabel("V3"))
        assertEquals(12L, workflowVersionFromLabel(" 12 "))
        assertNull(workflowVersionFromLabel("0"))
        assertNull(workflowVersionFromLabel("v0"))
        assertNull(workflowVersionFromLabel("vNext"))
        assertNull(workflowVersionFromLabel("1.2"))
        assertNull(workflowVersionFromLabel(""))
        assertNull(workflowVersionFromLabel("v123456789012345"))
    }
}
