package com.muyuchat.core.agent.contract

/**
 * Capability boundary between the Agent file tools and the app's private workspace implementation.
 *
 * Every path is a logical task-relative path (`inputs/...` or `artifacts/...`); implementations
 * resolve it under the task root and reject escapes. Implementations own encoding, hashing,
 * staging and commit journaling; executors never touch absolute paths.
 */
enum class AgentFileKind { FILE, DIRECTORY }

data class AgentFileEntry(
    val logicalPath: String,
    val kind: AgentFileKind,
    val sizeBytes: Long,
    val sha256: String? = null
)

data class AgentListResult(
    val entries: List<AgentFileEntry>,
    val nextCursor: String? = null
)

data class AgentReadResult(
    val content: String,
    val offsetBytes: Long,
    val nextOffsetBytes: Long,
    val eof: Boolean,
    val totalBytes: Long,
    val sha256: String
)

data class AgentSearchMatch(
    val logicalPath: String,
    val lineNumber: Int,
    val snippet: String
)

data class AgentSearchResult(
    val matches: List<AgentSearchMatch>,
    val truncated: Boolean,
    val scannedBytes: Long
)

data class AgentWriteResult(
    val artifactId: String,
    val logicalPath: String,
    val sizeBytes: Long,
    val sha256: String
)

sealed interface AgentFileResult<out T> {
    data class Ok<T>(val value: T) : AgentFileResult<T>

    data class Failure(
        val errorCode: String,
        val safeMessage: String,
        val possibleSideEffects: Boolean = false
    ) : AgentFileResult<Nothing>
}

/** Read scope is granted at task creation; write still requires the normal ASK approval path. */
interface AgentWorkspaceFilePort {
    suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult>

    suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult>

    suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult>

    suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult>
}
