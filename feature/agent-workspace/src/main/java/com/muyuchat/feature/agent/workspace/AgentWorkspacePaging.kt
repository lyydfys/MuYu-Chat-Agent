package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskPage

/**
 * Bounded page merging. The page holder stores accumulated pages, never an unbounded DB result:
 * the task list is capped, and timeline events keep the newest [maxEvents] sequence numbers.
 */

internal const val TASK_PAGE_SIZE = 50
internal const val MAX_TASKS = 200
internal const val EVENT_PAGE_SIZE = 100
internal const val MAX_EVENTS = 200

internal fun mergeTaskPage(
    current: List<AgentTask>,
    page: AgentTaskPage,
    maxTasks: Int = MAX_TASKS
): List<AgentTask> {
    if (page.tasks.isEmpty()) return current
    val byId = LinkedHashMap<String, AgentTask>(current.size + page.tasks.size)
    current.forEach { byId[it.taskId] = it }
    page.tasks.forEach { incoming ->
        val existing = byId[incoming.taskId]
        // Same task can be re-emitted by a live page; keep the newest revision only.
        if (existing == null || incoming.revision >= existing.revision) {
            byId[incoming.taskId] = incoming
        }
    }
    return byId.values
        .sortedByDescending { it.updatedAtMillis }
        .take(maxTasks.coerceAtLeast(1))
}

internal data class EventMergeResult(
    val events: List<AgentEvent>,
    val truncated: Boolean
)

internal fun mergeEventPages(
    current: List<AgentEvent>,
    page: AgentEventPage,
    maxEvents: Int = MAX_EVENTS
): EventMergeResult {
    val byId = LinkedHashMap<String, AgentEvent>(current.size + page.events.size)
    current.forEach { byId[it.eventId] = it }
    page.events.forEach { incoming ->
        // Events are immutable; a re-emitted page must not duplicate existing rows.
        byId.putIfAbsent(incoming.eventId, incoming)
    }
    val sorted = byId.values.sortedBy { it.sequence }
    val bounded = if (sorted.size <= maxEvents) sorted else sorted.takeLast(maxEvents)
    return EventMergeResult(events = bounded, truncated = sorted.size > maxEvents)
}

/**
 * Next page cursor for forward event paging. A cursor is only followed when it moves forward and
 * the accumulated timeline is still under the page holder's cap; otherwise the collector stays on
 * the current tail so later appends are observed instead of re-querying the whole run.
 */
internal fun nextEventPageAfter(
    page: AgentEventPage,
    requestedAfter: Long,
    loadedCount: Int,
    maxEvents: Int = MAX_EVENTS
): Long? {
    if (loadedCount >= maxEvents) return null
    val next = page.nextSequence ?: return null
    return next.takeIf { it > requestedAfter }
}

internal fun nextTaskPageCursor(page: AgentTaskPage, loadedCount: Int): String? {
    if (loadedCount >= MAX_TASKS) return null
    return page.nextCursor
}
