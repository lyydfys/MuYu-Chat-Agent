package com.muyuchat.feature.agent.workspace.workflow

/** Typed, content-free failure surfaced by the run page. */
data class WorkflowRunErrorUi(
    val code: String,
    val message: String
) {
    init {
        require(code.isNotBlank()) { "workflow run error code must not be blank" }
        require(message.isNotBlank()) { "workflow run error message must not be blank" }
    }
}

/** One rendered history row; labels are precomputed so the screen stays dumb. */
data class WorkflowRunHistoryRowUi(
    val runId: String,
    val status: WorkflowRunStatus,
    val statusLabel: String,
    val triggerLabel: String,
    val timeLabel: String,
    val detailLabel: String,
    val errorCode: String? = null
) {
    init {
        require(runId.isNotBlank()) { "history row runId must not be blank" }
        require(statusLabel.isNotBlank()) { "history row statusLabel must not be blank" }
        require(triggerLabel.isNotBlank()) { "history row triggerLabel must not be blank" }
        require(timeLabel.isNotBlank()) { "history row timeLabel must not be blank" }
    }
}

/** Rendered terminal receipt of the last manual run. */
data class WorkflowRunReceiptUi(
    val status: WorkflowRunReceiptStatus,
    val statusLabel: String,
    val runId: String?,
    val errorCode: String? = null,
    val safeMessage: String? = null,
    val finishedLabel: String,
    val replay: Boolean = false
) {
    init {
        require(statusLabel.isNotBlank()) { "receipt statusLabel must not be blank" }
        require(finishedLabel.isNotBlank()) { "receipt finishedLabel must not be blank" }
    }
}

/**
 * Complete immutable state of the workflow run surface.
 *
 * Every renderable branch is derivable from this value: definition loading/empty/error, selection,
 * run in-flight, terminal receipt, history loading/empty/error, one-shot notice and dismissible
 * typed errors. [WorkflowRunStateHolder] is the only writer.
 */
data class WorkflowRunState(
    val loadingDefinitions: Boolean = false,
    val definitions: List<WorkflowRunDefinitionUi> = emptyList(),
    val selectedDefinitionId: String? = null,
    val definitionsError: WorkflowRunErrorUi? = null,

    val loadingHistory: Boolean = false,
    val history: List<WorkflowRunHistoryRowUi> = emptyList(),
    val historyError: WorkflowRunErrorUi? = null,

    val running: Boolean = false,
    val receipt: WorkflowRunReceiptUi? = null,
    val actionError: WorkflowRunErrorUi? = null,
    val notice: String? = null,
    val lastRefreshLabel: String? = null
) {
    val selectedDefinition: WorkflowRunDefinitionUi?
        get() = definitions.firstOrNull { it.definitionId == selectedDefinitionId }

    val definitionsEmpty: Boolean
        get() = definitions.isEmpty() && !loadingDefinitions && definitionsError == null

    val historyEmpty: Boolean
        get() = history.isEmpty() && !loadingHistory && historyError == null

    /** The run button is enabled only for a parsed, selected version and while nothing runs. */
    val canRun: Boolean
        get() = selectedDefinition?.runnable == true && !running

    val canRefresh: Boolean
        get() = !loadingDefinitions && !loadingHistory && !running

    val busy: Boolean
        get() = loadingDefinitions || loadingHistory || running

    fun isSelected(definitionId: String): Boolean = selectedDefinitionId == definitionId
}
