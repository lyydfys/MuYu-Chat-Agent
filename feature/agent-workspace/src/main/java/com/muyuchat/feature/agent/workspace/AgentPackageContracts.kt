package com.muyuchat.feature.agent.workspace

import java.util.Locale

/**
 * Feature-local ports for task-package export/import/delete and SAF document I/O.
 *
 * The page never sees an Android `Uri`, `File`, `Activity`, `ContentResolver` or stream. The host
 * (K09) adapts these plain contracts to `ActivityResultContracts.CreateDocument/OpenDocument`,
 * `ContentResolver` streams and the K14 data services.
 */

/** Opaque SAF document reference. The host converts `Uri` <-> this value at the boundary. */
@JvmInline
value class AgentDocumentRef(val value: String) {
    init {
        require(value.isNotBlank()) { "document reference must not be blank" }
    }
}

/**
 * SAF launcher surface owned by the host. Implementations must deliver exactly one callback per
 * launch; `null` means the user canceled the system picker.
 */
interface AgentDocumentLauncher {
    fun createDocument(suggestedFileName: String, onResult: (AgentDocumentRef?) -> Unit)

    fun openDocument(onResult: (AgentDocumentRef?) -> Unit)

    /**
     * Directory-tree picker (SAF OpenDocumentTree). Defaults to "cancelled" so existing host
     * implementations and test fakes keep compiling; the K09 host wires the real picker for the
     * Skills directory import.
     */
    fun openTree(onResult: (AgentDocumentRef?) -> Unit) = onResult(null)

    companion object {
        /** Preview/test fallback only; production wiring must provide a real launcher. */
        val Unavailable: AgentDocumentLauncher = object : AgentDocumentLauncher {
            override fun createDocument(
                suggestedFileName: String,
                onResult: (AgentDocumentRef?) -> Unit
            ) = Unit

            override fun openDocument(onResult: (AgentDocumentRef?) -> Unit) = Unit
        }
    }
}

/**
 * One successful export produces a saved package; a canceled package is a normal outcome so the
 * page can show "已取消" without treating it as an error.
 */
sealed interface AgentExportOutcome {
    data object Saved : AgentExportOutcome

    data object Canceled : AgentExportOutcome
}

/** Result of a validated import. Imported tasks are archived by contract and are not runnable. */
data class AgentImportOutcome(
    val importedTaskId: String,
    val displayTitle: String? = null
)

/** Result of a completed delete request (task tombstoned / files scheduled for cleanup). */
data class AgentDeleteOutcome(val deletedTaskId: String)

/**
 * Typed, already-sanitized failure raised by the host adapter. UI must only display
 * [safeMessage]; the [code] is for diagnostics and policy decisions.
 */
class AgentPackageOperationException(
    val code: String,
    val safeMessage: String
) : Exception(safeMessage) {
    init {
        require(code.isNotBlank()) { "package error code must not be blank" }
        require(safeMessage.isNotBlank()) { "package error message must not be blank" }
    }
}

/** Narrow command surface the Lead wires to K14 export/import/delete services. */
interface AgentPackageActions {
    /**
     * Writes the selected task package to the user-picked SAF destination. The destination is part
     * of the request because SAF CreateDocument returns it only after the picker closes; the data
     * service owns stream/commit/copy-on-success semantics.
     */
    suspend fun export(
        taskId: String,
        destination: AgentDocumentRef
    ): Result<AgentExportOutcome>

    /**
     * Writes the package to the app-chosen public Downloads location without a SAF picker.
     *
     * SAF CreateDocument can return without a usable Uri on some OEM builds; this path keeps export
     * available there. Defaults to a typed failure so existing hosts/fakes keep compiling.
     */
    suspend fun exportToDownloads(taskId: String): Result<AgentExportOutcome> =
        Result.failure(
            AgentPackageOperationException(
                code = "PACKAGE_DOWNLOAD_EXPORT_UNAVAILABLE",
                safeMessage = "当前设备未接入下载目录导出。"
            )
        )

    /** Validates and imports a package from the user-picked SAF source. */
    suspend fun importPackage(source: AgentDocumentRef): Result<AgentImportOutcome>

    /** Stops an active run first when needed, then deletes the task/workspace. */
    suspend fun deleteTask(taskId: String): Result<AgentDeleteOutcome>

    companion object {
        /** Preview/test fallback only; production wiring must provide the real services. */
        val Unavailable: AgentPackageActions = object : AgentPackageActions {
            override suspend fun export(
                taskId: String,
                destination: AgentDocumentRef
            ): Result<AgentExportOutcome> = unavailable()

            override suspend fun importPackage(
                source: AgentDocumentRef
            ): Result<AgentImportOutcome> = unavailable()

            override suspend fun deleteTask(taskId: String): Result<AgentDeleteOutcome> =
                unavailable()

            private fun <T> unavailable(): Result<T> = Result.failure(
                AgentPackageOperationException(
                    code = "PACKAGE_UNAVAILABLE",
                    safeMessage = "任务包功能尚未接入。"
                )
            )
        }
    }
}

internal data class AgentPackageErrorUi(
    val code: String,
    val message: String
)

/** Typed failure mapping. Unknown throwables never leak their message to the user. */
internal fun agentPackageErrorUi(error: Throwable): AgentPackageErrorUi = when (error) {
    is AgentPackageOperationException -> AgentPackageErrorUi(error.code, error.safeMessage)
    else -> AgentPackageErrorUi(
        code = "PACKAGE_OPERATION_FAILED",
        message = "任务包操作未能完成，请稍后重试。"
    )
}

/** SAF display name for an export. Never contains the goal text or user data. */
internal fun exportPackageFileName(taskId: String): String {
    val safe = taskId.map { character ->
        when {
            character.isLetterOrDigit() -> character
            character == '-' || character == '_' || character == '.' -> character
            else -> '-'
        }
    }.joinToString("")
        .trim { it == '-' || it == '.' }
        .take(48)
        .ifBlank { "task" }
    return "agent-$safe.mca-task.zip".lowercase(Locale.ROOT)
}
