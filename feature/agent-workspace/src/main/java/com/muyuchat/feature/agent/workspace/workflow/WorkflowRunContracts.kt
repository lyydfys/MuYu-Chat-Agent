package com.muyuchat.feature.agent.workspace.workflow

/**
 * Feature-local projection of one runnable workflow definition.
 *
 * It is derived from the existing [com.muyuchat.feature.agent.workspace.AgentWorkflowRecipeItem]
 * (the admin port is the only source) so the feature never imports app/runtime types. [version] is
 * parsed from the human version label; when it cannot be parsed the row stays visible but not
 * runnable instead of guessing a version.
 */
data class WorkflowRunDefinitionUi(
    val definitionId: String,
    val displayName: String,
    val version: Long?,
    val versionLabel: String,
    val goalPreview: String,
    val updatedLabel: String? = null
) {
    init {
        require(definitionId.isNotBlank()) { "workflow definitionId must not be blank" }
        require(displayName.isNotBlank()) { "workflow displayName must not be blank" }
        require(version == null || version >= 1L) { "workflow version must be positive when present" }
        require(versionLabel.isNotBlank()) { "workflow versionLabel must not be blank" }
    }

    /** True only when the version label resolved to a concrete published version. */
    val runnable: Boolean get() = version != null
}

/** Trigger kind of one run; mirrors the existing entry/history projection without importing it. */
enum class WorkflowRunTrigger { MANUAL, SCHEDULE }

/** Terminal/live status of one history row. */
enum class WorkflowRunStatus { STARTED, COMPLETED, FAILED, REJECTED }

/** Terminal status of one manual-run receipt; a receipt is never STARTED. */
enum class WorkflowRunReceiptStatus { COMPLETED, FAILED, REJECTED, PAUSED }

/**
 * One history row injected by the host.
 *
 * The app adapter maps `WorkflowRunHistoryEntry` to this value; the feature keeps no store and no
 * clock of its own beyond rendering labels.
 */
data class WorkflowRunHistoryEntry(
    val runId: String,
    val definitionId: String,
    val version: Long,
    val trigger: WorkflowRunTrigger,
    val slotMillis: Long? = null,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val status: WorkflowRunStatus = WorkflowRunStatus.STARTED,
    val errorCode: String? = null
) {
    init {
        require(runId.isNotBlank()) { "history runId must not be blank" }
        require(definitionId.isNotBlank()) { "history definitionId must not be blank" }
        require(version >= 1L) { "history version must be positive" }
        require(startedAtMillis > 0L) { "history start time must be positive" }
        require(finishedAtMillis == null || finishedAtMillis >= startedAtMillis) {
            "history finish time must not precede its start"
        }
    }
}

/**
 * Terminal receipt of one manual run injected by the host (the existing trigger facade).
 *
 * [idempotentReplay] is the facade's answer to a repeated command/run id; the feature renders it as
 * a notice and never starts a second run on its own.
 */
data class WorkflowRunReceipt(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val trigger: WorkflowRunTrigger,
    val runId: String?,
    val slotMillis: Long? = null,
    val status: WorkflowRunReceiptStatus,
    val errorCode: String? = null,
    val safeMessage: String? = null,
    val completedAtMillis: Long,
    val idempotentReplay: Boolean = false
) {
    init {
        require(commandId.isNotBlank()) { "receipt commandId must not be blank" }
        require(definitionId.isNotBlank()) { "receipt definitionId must not be blank" }
        require(version >= 1L) { "receipt version must be positive" }
        require(completedAtMillis > 0L) { "receipt completion time must be positive" }
        require(safeMessage == null || safeMessage.length <= 400) {
            "receipt safe message is too long"
        }
    }
}

/**
 * Parses the version number from a display label such as `"v3"` or `"12"`.
 *
 * Returns null for any other shape (prefixes, ranges, suffixes) so the UI disables the run action
 * instead of guessing a version. Non-positive numbers are invalid.
 */
internal fun workflowVersionFromLabel(label: String): Long? {
    val text = label.trim()
    if (text.isEmpty()) return null
    val digits = if (text.startsWith("v", ignoreCase = true)) text.substring(1) else text
    if (digits.isEmpty() || digits.length > MAX_VERSION_DIGITS) return null
    if (!digits.all { it in '0'..'9' }) return null
    return digits.toLongOrNull()?.takeIf { it >= 1L }
}

private const val MAX_VERSION_DIGITS = 12
