package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.mca.agent.memory.AgentMemoryLimits

/**
 * Pure parsing/validation used by the workflow executor.
 *
 * These were private helpers inside `WorkflowExecutionService` and had no direct tests; they decide
 * two security-relevant inputs (which array a `foreach` iterates, and which memory spaces an agent
 * task may select), so they live here with their own coverage instead of hiding in the orchestrator.
 */

internal const val FOREACH_WHOLE_VALUE = "\$"
internal const val MAX_PATH_SEGMENTS = 8
internal const val MAX_PATH_SEGMENT_CHARS = 64

/**
 * Resolves the array a `foreach` node iterates: either the whole node value (`$`) or a dotted path of
 * object keys / array indices. Returns null for any malformed or out-of-bounds path so the caller can
 * fail the node instead of iterating something unintended.
 */
internal fun resolveForeachPathItems(root: AgentJsonValue, path: String): List<AgentJsonValue>? {
    if (path == FOREACH_WHOLE_VALUE) {
        return (root as? AgentJsonValue.Arr)?.items
    }
    val segments = path.split('.')
    if (segments.any { it.isBlank() || it.length > MAX_PATH_SEGMENT_CHARS } ||
        segments.size > MAX_PATH_SEGMENTS
    ) {
        return null
    }
    var current: AgentJsonValue = root
    for (segment in segments) {
        current = when (current) {
            is AgentJsonValue.Obj -> current.fields.firstOrNull { it.first == segment }?.second
                ?: return null
            is AgentJsonValue.Arr -> {
                val index = segment.toIntOrNull() ?: return null
                current.items.getOrNull(index) ?: return null
            }
            else -> return null
        }
    }
    return (current as? AgentJsonValue.Arr)?.items
}

/**
 * Optional AGENT_TASK memory selection. Absent/null/empty means "inherit the run plan".
 * A present array must contain 1..MAX_SELECTED_SPACES unique, bounded, control-free space ids;
 * anything else returns null so the node fails closed rather than silently narrowing or widening.
 */
internal fun parseAgentTaskMemorySpaceSelection(value: AgentJsonValue?): Set<String>? {
    if (value == null || value is AgentJsonValue.Null) return emptySet()
    val array = value as? AgentJsonValue.Arr ?: return null
    if (array.items.isEmpty() || array.items.size > AgentMemoryLimits.MAX_SELECTED_SPACES) return null
    val ids = LinkedHashSet<String>(array.items.size)
    for (item in array.items) {
        val raw = (item as? AgentJsonValue.Str)?.value ?: return null
        val id = raw.trim()
        if (id.isEmpty() ||
            id.length > AgentMemoryLimits.MAX_SPACE_ID_CHARS ||
            id.any { it.isISOControl() }
        ) {
            return null
        }
        if (!ids.add(id)) return null
    }
    return ids.toSet()
}
