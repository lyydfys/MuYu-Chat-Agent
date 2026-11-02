package com.muyuchat.feature.agent.workspace.workflow

import com.muyuchat.feature.agent.workspace.AgentWorkflowAdminPort
import com.muyuchat.feature.agent.workspace.AgentWorkflowListResult
import com.muyuchat.feature.agent.workspace.AgentWorkflowRecipeItem
import com.muyuchat.feature.agent.workspace.formatClockTime
import com.muyuchat.feature.agent.workspace.formatRelativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Page state holder for running a published workflow and reading its history.
 *
 * UI-free by construction: it renders no Compose and owns no ViewModel. Every dependency is an
 * injected function over existing feature/admin surfaces, so a JVM test drives the whole page with
 * fakes and the host (Lead) wires the app implementation:
 *
 * - [definitions] is the existing `AgentWorkflowAdminPort` (the only definition source);
 * - [history] returns the app `WorkflowRunHistoryEntry` list mapped to the feature projection;
 * - [runManual] is the host adapter over `WorkflowTriggerFacade.runManual`, already idempotent for a
 *   repeated command/run id. The holder never starts a second concurrent run itself: it drops a
 *   re-entrant click while one run is in flight and always defers dedupe to the facade.
 *
 * Stale loads are impossible: every intent is serialized by one mutex, and the history projection is
 * built only from the value returned by the injected loader.
 */
class WorkflowRunStateHolder(
    private val definitions: AgentWorkflowAdminPort,
    private val history: suspend (Int) -> List<WorkflowRunHistoryEntry>,
    private val runManual: suspend (definitionId: String, version: Long) -> WorkflowRunReceipt,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT
) {
    init {
        require(historyLimit >= 1) { "historyLimit must be positive" }
        require(historyLimit <= MAX_HISTORY_LIMIT) { "historyLimit must be <= $MAX_HISTORY_LIMIT" }
    }

    private val gate = Mutex()

    /**
     * Separate from [gate] so a second click is dropped immediately while the first run is in
     * flight; the facade remains the durable idempotency authority for real repeats.
     */
    private val runGate = Mutex()
    private val mutable = MutableStateFlow(WorkflowRunState())

    val state: StateFlow<WorkflowRunState> = mutable.asStateFlow()

    /** Loads definitions and history together; errors stay per-section instead of failing the page. */
    suspend fun refresh(): Unit = withContext(dispatcher) {
        gate.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        mutable.update {
            it.copy(
                loadingDefinitions = true,
                definitionsError = null
            )
        }
        val listed = try {
            definitions.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (listed) {
            null -> mutable.update {
                it.copy(loadingDefinitions = false, definitionsError = DEFINITIONS_FAILED)
            }

            is AgentWorkflowListResult.Rejected -> mutable.update {
                it.copy(
                    loadingDefinitions = false,
                    definitionsError = WorkflowRunErrorUi(listed.code, listed.safeMessage)
                )
            }

            is AgentWorkflowListResult.Loaded -> {
                val rows = listed.recipes.mapNotNull(::toDefinitionRow)
                val stillSelected = mutable.value.selectedDefinitionId
                    ?.takeIf { selected -> rows.any { it.definitionId == selected } }
                // A single runnable definition needs no extra tap; the run button is usable at once.
                val autoSelected = stillSelected
                    ?: rows.singleOrNull()?.takeIf { it.runnable }?.definitionId
                mutable.update { current ->
                    current.copy(
                        loadingDefinitions = false,
                        definitions = rows,
                        definitionsError = null,
                        selectedDefinitionId = autoSelected,
                        receipt = if (autoSelected == null) null else current.receipt
                    )
                }
            }
        }
        refreshHistoryLocked()
        mutable.update { it.copy(lastRefreshLabel = formatClockTime(clock())) }
    }

    /** Reloads only the history list; used by the page refresh action and after every run. */
    suspend fun refreshHistory(): Unit = withContext(dispatcher) {
        gate.withLock {
            refreshHistoryLocked()
            mutable.update { it.copy(lastRefreshLabel = formatClockTime(clock())) }
        }
    }

    private suspend fun refreshHistoryLocked() {
        mutable.update { it.copy(loadingHistory = true, historyError = null) }
        val entries = try {
            history(historyLimit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (entries == null) {
            mutable.update {
                it.copy(loadingHistory = false, historyError = HISTORY_FAILED)
            }
            return
        }
        val rows = try {
            entries.take(historyLimit).map(::toHistoryRow)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutable.update {
                it.copy(loadingHistory = false, historyError = HISTORY_FAILED)
            }
            return
        }
        mutable.update {
            it.copy(loadingHistory = false, history = rows, historyError = null)
        }
    }

    /** Selects one already-loaded definition. Unknown ids are ignored, not invented. */
    suspend fun selectDefinition(definitionId: String): Unit = withContext(dispatcher) {
        gate.withLock {
            val current = mutable.value
            if (current.selectedDefinitionId == definitionId) return@withLock
            if (current.definitions.none { it.definitionId == definitionId }) return@withLock
            mutable.update {
                it.copy(
                    selectedDefinitionId = definitionId,
                    receipt = null,
                    actionError = null,
                    notice = null
                )
            }
        }
    }

    /**
     * Runs the selected definition through the injected facade adapter.
     *
     * A re-entrant click while a run is in flight is dropped (the button is disabled too). After the
     * terminal receipt the history is refreshed from the injected source, never appended locally.
     */
    suspend fun runSelected(): Unit = withContext(dispatcher) {
        if (!runGate.tryLock()) return@withContext
        try {
            gate.withLock { runLocked() }
        } finally {
            runGate.unlock()
        }
    }

    private suspend fun runLocked() {
        val snapshot = mutable.value
        val selected = snapshot.selectedDefinition ?: return
        if (snapshot.running) return
        val version = selected.version
        if (version == null) {
            mutable.update { it.copy(actionError = VERSION_UNPARSEABLE) }
            return
        }
        mutable.update { it.copy(running = true, actionError = null, notice = null) }
        val receipt = try {
            runManual(selected.definitionId, version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (receipt == null) {
            mutable.update { it.copy(running = false, actionError = RUN_FAILED) }
            return
        }
        val receiptUi = try {
            toReceiptUi(receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (receiptUi == null) {
            mutable.update { it.copy(running = false, actionError = RUN_FAILED) }
            return
        }
        mutable.update {
            it.copy(
                running = false,
                receipt = receiptUi,
                actionError = null,
                notice = if (receipt.idempotentReplay) NOTICE_REPLAY else null
            )
        }
        refreshHistoryLocked()
    }

    fun dismissDefinitionsError() {
        mutable.update { it.copy(definitionsError = null) }
    }

    fun dismissHistoryError() {
        mutable.update { it.copy(historyError = null) }
    }

    fun dismissActionError() {
        mutable.update { it.copy(actionError = null) }
    }

    fun dismissNotice() {
        mutable.update { it.copy(notice = null) }
    }

    // --- mapping --------------------------------------------------------------------------------

    private fun toDefinitionRow(item: AgentWorkflowRecipeItem): WorkflowRunDefinitionUi? = try {
        WorkflowRunDefinitionUi(
            definitionId = item.recipeId,
            displayName = item.name,
            version = workflowVersionFromLabel(item.versionLabel),
            versionLabel = item.versionLabel,
            goalPreview = item.goalTemplate.take(MAX_GOAL_PREVIEW_CHARS),
            updatedLabel = item.updatedLabel
        )
    } catch (_: Throwable) {
        null
    }

    private fun toHistoryRow(entry: WorkflowRunHistoryEntry): WorkflowRunHistoryRowUi =
        WorkflowRunHistoryRowUi(
            runId = entry.runId,
            status = entry.status,
            statusLabel = statusLabel(entry.status),
            triggerLabel = triggerLabel(entry.trigger),
            timeLabel = formatRelativeTime(clock(), entry.finishedAtMillis ?: entry.startedAtMillis),
            detailLabel = "${entry.definitionId} · v${entry.version}",
            errorCode = entry.errorCode
        )

    private fun toReceiptUi(receipt: WorkflowRunReceipt): WorkflowRunReceiptUi = WorkflowRunReceiptUi(
        status = receipt.status,
        statusLabel = receiptStatusLabel(receipt.status),
        runId = receipt.runId,
        errorCode = receipt.errorCode,
        safeMessage = receipt.safeMessage,
        finishedLabel = formatRelativeTime(clock(), receipt.completedAtMillis),
        replay = receipt.idempotentReplay
    )

    private fun statusLabel(status: WorkflowRunStatus): String = when (status) {
        WorkflowRunStatus.STARTED -> "运行中"
        WorkflowRunStatus.COMPLETED -> "已完成"
        WorkflowRunStatus.FAILED -> "失败"
        WorkflowRunStatus.REJECTED -> "已拒绝"
    }

    private fun receiptStatusLabel(status: WorkflowRunReceiptStatus): String = when (status) {
        WorkflowRunReceiptStatus.COMPLETED -> "已完成"
        WorkflowRunReceiptStatus.FAILED -> "失败"
        WorkflowRunReceiptStatus.REJECTED -> "已拒绝"
        WorkflowRunReceiptStatus.PAUSED -> "等待审批"
    }

    private fun triggerLabel(trigger: WorkflowRunTrigger): String = when (trigger) {
        WorkflowRunTrigger.MANUAL -> "手动"
        WorkflowRunTrigger.SCHEDULE -> "定时"
    }

    companion object {
        const val DEFAULT_HISTORY_LIMIT: Int = 50
        const val MAX_HISTORY_LIMIT: Int = 200

        const val ERROR_DEFINITIONS: String = "WORKFLOW_DEFINITIONS_FAILED"
        const val ERROR_HISTORY: String = "WORKFLOW_HISTORY_FAILED"
        const val ERROR_RUN: String = "WORKFLOW_RUN_FAILED"
        const val ERROR_VERSION: String = "WORKFLOW_VERSION_UNPARSEABLE"
        const val NOTICE_REPLAY: String = "重复提交已复用上次运行结果。"

        private const val MAX_GOAL_PREVIEW_CHARS: Int = 160

        val DEFINITIONS_FAILED: WorkflowRunErrorUi = WorkflowRunErrorUi(
            code = ERROR_DEFINITIONS,
            message = "工作流定义暂时无法载入，请稍后重试。"
        )
        val HISTORY_FAILED: WorkflowRunErrorUi = WorkflowRunErrorUi(
            code = ERROR_HISTORY,
            message = "运行历史暂时无法载入，请稍后重试。"
        )
        val RUN_FAILED: WorkflowRunErrorUi = WorkflowRunErrorUi(
            code = ERROR_RUN,
            message = "工作流运行失败，请稍后重试。"
        )
        val VERSION_UNPARSEABLE: WorkflowRunErrorUi = WorkflowRunErrorUi(
            code = ERROR_VERSION,
            message = "该工作流的版本号无法解析，暂不能运行。"
        )
    }
}
