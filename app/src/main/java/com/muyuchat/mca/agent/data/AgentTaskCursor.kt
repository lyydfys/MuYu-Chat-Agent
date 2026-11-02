package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentTaskPage

/**
 * Task list cursor. Encodes the last `(updatedAt, taskId)` sort key; the sort key is not user text
 * and `|` is not a legal Agent id character, so the encoding is unambiguous. A malformed cursor is
 * rejected instead of silently restarting from the first page.
 */
internal object AgentTaskCursor {
    fun encode(updatedAt: Long, taskId: String): String = "$updatedAt|$taskId"

    fun decode(cursor: String): Pair<Long, String>? {
        if (cursor.length > 256) return null
        val separator = cursor.indexOf('|')
        if (separator <= 0 || separator == cursor.lastIndex) return null
        val updatedAt = cursor.substring(0, separator).toLongOrNull() ?: return null
        val taskId = cursor.substring(separator + 1)
        if (!com.muyuchat.core.agent.contract.AgentContract.isValidId(taskId)) return null
        return updatedAt to taskId
    }

    fun next(tasks: List<com.muyuchat.core.agent.contract.AgentTask>, limit: Int): String? {
        if (tasks.size <= limit) return null
        val last = tasks[limit - 1]
        return encode(last.updatedAtMillis, last.taskId)
    }
}

/** Shared page projection used by both the Room queries object and tests. */
internal fun List<AgentTaskEntity>.toTaskPage(limit: Int): AgentTaskPage {
    val contracts = map(AgentEntityMappers::toContract)
    return AgentTaskPage(
        tasks = contracts.take(limit),
        nextCursor = AgentTaskCursor.next(contracts, limit)
    )
}
