package com.muyuchat.mca.agent.data.quota

import com.muyuchat.mca.agent.data.AgentDataLimits
import com.muyuchat.mca.agent.data.AgentStorageUsage
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import java.io.File

/** Soft thresholds for Agent-owned storage (architecture 10.5). */
data class AgentWorkspaceQuotaLimits(
    val taskSoftBytes: Long = AgentDataLimits.TASK_SOFT_QUOTA_BYTES,
    val totalSoftBytes: Long = AgentDataLimits.TOTAL_SOFT_QUOTA_BYTES,
    val diskSafetyBytes: Long = AgentDataLimits.DISK_FREE_SAFETY_MARGIN_BYTES
) {
    init {
        require(taskSoftBytes > 0L && totalSoftBytes > 0L && diskSafetyBytes >= 0L)
    }
}

enum class AgentQuotaScope { TASK, TOTAL }

sealed interface AgentQuotaDecision {
    data object Allowed : AgentQuotaDecision

    data class SoftLimitExceeded(
        val scope: AgentQuotaScope,
        val usedBytes: Long,
        val limitBytes: Long,
        val requestedBytes: Long
    ) : AgentQuotaDecision

    data class DiskSpaceInsufficient(
        val usableBytes: Long,
        val requiredBytes: Long
    ) : AgentQuotaDecision
}

/**
 * Read-only quota evaluator. It never deletes or truncates data: callers decide whether to warn or
 * refuse. The disk check uses the real usable space by default and is injectable for deterministic
 * tests. A zero/unknown usable-space reading is not treated as "full" (some virtual filesystems
 * report 0); existing stores use the same convention.
 */
class AgentWorkspaceQuota(
    private val agentRoot: File,
    val limits: AgentWorkspaceQuotaLimits = AgentWorkspaceQuotaLimits(),
    private val diskFreeBytes: (File) -> Long = { it.usableSpace }
) {
    fun evaluate(
        workspaceId: String,
        taskId: String,
        additionalBytes: Long
    ): AgentQuotaDecision {
        require(additionalBytes >= 0L) { "additionalBytes must not be negative" }
        AgentWorkspacePaths.requireSafeId(workspaceId, "workspaceId")
        AgentWorkspacePaths.requireSafeId(taskId, "taskId")
        val taskUsed = AgentStorageUsage.taskBytes(agentRoot, workspaceId, taskId)
        if (exceeds(taskUsed, additionalBytes, limits.taskSoftBytes)) {
            return AgentQuotaDecision.SoftLimitExceeded(
                scope = AgentQuotaScope.TASK,
                usedBytes = taskUsed,
                limitBytes = limits.taskSoftBytes,
                requestedBytes = additionalBytes
            )
        }
        return evaluateTotal(additionalBytes)
    }

    fun evaluateTotal(additionalBytes: Long): AgentQuotaDecision {
        require(additionalBytes >= 0L) { "additionalBytes must not be negative" }
        val totalUsed = AgentStorageUsage.totalBytes(agentRoot)
        if (exceeds(totalUsed, additionalBytes, limits.totalSoftBytes)) {
            return AgentQuotaDecision.SoftLimitExceeded(
                scope = AgentQuotaScope.TOTAL,
                usedBytes = totalUsed,
                limitBytes = limits.totalSoftBytes,
                requestedBytes = additionalBytes
            )
        }
        val usable = diskFreeBytes(agentRoot)
        val required = additionalBytes + limits.diskSafetyBytes
        if (usable > 0L && usable < required) {
            return AgentQuotaDecision.DiskSpaceInsufficient(usableBytes = usable, requiredBytes = required)
        }
        return AgentQuotaDecision.Allowed
    }

    /** Bytes still available under the task soft threshold (never negative). */
    fun remainingTaskBytes(workspaceId: String, taskId: String): Long {
        AgentWorkspacePaths.requireSafeId(workspaceId, "workspaceId")
        AgentWorkspacePaths.requireSafeId(taskId, "taskId")
        val used = AgentStorageUsage.taskBytes(agentRoot, workspaceId, taskId)
        return (limits.taskSoftBytes - used).coerceAtLeast(0L)
    }

    private fun exceeds(used: Long, additional: Long, limit: Long): Boolean {
        if (used >= limit) return additional > 0L
        return additional > limit - used
    }
}
