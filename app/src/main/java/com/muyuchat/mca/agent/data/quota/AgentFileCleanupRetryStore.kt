package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Durable best-effort cleanup hint; never contains user content or credentials. */
data class AgentFileCleanupRetryRecord(
    val taskId: String,
    val workspaceId: String,
    val attempts: Int,
    val firstFailedAtMillis: Long,
    val lastFailedAtMillis: Long,
    val nextAttemptAtMillis: Long,
    val reasonCode: String
) {
    init {
        AgentWorkspacePaths.requireSafeId(taskId, "taskId")
        AgentWorkspacePaths.requireSafeId(workspaceId, "workspaceId")
        require(attempts >= 1) { "attempts must be positive" }
        require(reasonCode.matches(Regex("^[A-Z][A-Z0-9_]{1,63}$"))) { "reason code must be UPPER_SNAKE_CASE" }
    }
}

interface AgentFileCleanupRetryStore {
    suspend fun recordFailure(record: AgentFileCleanupRetryRecord)

    suspend fun clear(taskId: String)

    suspend fun find(taskId: String): AgentFileCleanupRetryRecord?

    suspend fun pending(limit: Int): List<AgentFileCleanupRetryRecord>
}

/**
 * File-backed retry records under `<agentRoot>/maintenance/cleanup-retry/`.
 *
 * The DB is not the place for best-effort filesystem cleanup hints (no migration is allowed for
 * K14); these bounded JSON records are maintenance metadata only and are safe to lose.
 */
class FileAgentFileCleanupRetryStore(
    private val agentRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgentFileCleanupRetryStore {
    override suspend fun recordFailure(record: AgentFileCleanupRetryRecord) = withContext(ioDispatcher) {
        val directory = retryDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            return@withContext
        }
        val target = File(directory, "${record.taskId}.json")
        val temp = File(directory, "${record.taskId}.${UUID.randomUUID()}.part")
        val json = JSONObject()
            .put("taskId", record.taskId)
            .put("workspaceId", record.workspaceId)
            .put("attempts", record.attempts)
            .put("firstFailedAt", record.firstFailedAtMillis)
            .put("lastFailedAt", record.lastFailedAtMillis)
            .put("nextAttemptAt", record.nextAttemptAtMillis)
            .put("reasonCode", record.reasonCode)
            .toString()
        try {
            temp.writeText(json)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override suspend fun clear(taskId: String) = withContext(ioDispatcher) {
        AgentWorkspacePaths.requireSafeId(taskId, "taskId")
        runCatching {
            val file = File(retryDirectory(), "$taskId.json")
            if (file.exists()) file.delete()
        }
        Unit
    }

    override suspend fun find(taskId: String): AgentFileCleanupRetryRecord? = withContext(ioDispatcher) {
        AgentWorkspacePaths.requireSafeId(taskId, "taskId")
        decode(File(retryDirectory(), "$taskId.json"))
    }

    override suspend fun pending(limit: Int): List<AgentFileCleanupRetryRecord> =
        withContext(ioDispatcher) {
            require(limit in 1..1_000) { "limit must be in 1..1000" }
            val files = retryDirectory().listFiles { file -> file.isFile && file.name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
                .take(limit)
            files.mapNotNull(::decode)
        }

    private fun retryDirectory(): File = File(agentRoot, "maintenance/cleanup-retry")

    private fun decode(file: File): AgentFileCleanupRetryRecord? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            AgentFileCleanupRetryRecord(
                taskId = json.getString("taskId"),
                workspaceId = json.getString("workspaceId"),
                attempts = json.getInt("attempts"),
                firstFailedAtMillis = json.getLong("firstFailedAt"),
                lastFailedAtMillis = json.getLong("lastFailedAt"),
                nextAttemptAtMillis = json.getLong("nextAttemptAt"),
                reasonCode = json.getString("reasonCode")
            )
        }.getOrNull()
    }
}
