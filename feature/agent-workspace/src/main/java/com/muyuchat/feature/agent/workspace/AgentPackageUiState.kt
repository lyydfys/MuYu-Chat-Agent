package com.muyuchat.feature.agent.workspace

/**
 * Explicit package-operation projection. The page holds one operation at a time; in-flight
 * statuses are the PACKAGE_* values of [AgentWorkspaceStatus]. A failure keeps the operation
 * record (with a safe message) until the user dismisses it or starts another operation.
 */

enum class AgentPackageOperationKind { EXPORT, IMPORT, DELETE }

data class AgentPackageOperationUi(
    val kind: AgentPackageOperationKind,
    val status: AgentWorkspaceStatus,
    val taskId: String? = null,
    val message: String? = null
)

/** Delete confirmation carries the task goal so the dialog can name what is being deleted. */
data class AgentDeleteConfirmationUi(
    val taskId: String,
    val goal: String
)

data class AgentPackageUiState(
    val operation: AgentPackageOperationUi? = null,
    val deleteConfirmation: AgentDeleteConfirmationUi? = null
) {
    val busy: Boolean
        get() = operation?.status == AgentWorkspaceStatus.PACKAGE_EXPORTING ||
            operation?.status == AgentWorkspaceStatus.PACKAGE_IMPORTING ||
            operation?.status == AgentWorkspaceStatus.PACKAGE_DELETING

    val failed: Boolean
        get() = operation?.status == AgentWorkspaceStatus.PACKAGE_FAILED
}
