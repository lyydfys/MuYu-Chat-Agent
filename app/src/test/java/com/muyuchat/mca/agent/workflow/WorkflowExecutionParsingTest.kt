package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.mca.agent.memory.AgentMemoryLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These two parsers decide which array a `foreach` iterates and which memory spaces an agent task may
 * select. They used to be private helpers inside `WorkflowExecutionService` with no direct coverage.
 */
class WorkflowExecutionParsingTest {

    // ---- foreach path resolution ----

    @Test
    fun wholeValueRequiresAnArray() {
        val arr = AgentJsonValue.Arr(listOf(AgentJsonValue.Str("a"), AgentJsonValue.Str("b")))
        assertEquals(2, resolveForeachPathItems(arr, "\$")?.size)
        assertNull(resolveForeachPathItems(AgentJsonValue.Str("a"), "\$"))
        assertNull(resolveForeachPathItems(obj("k" to AgentJsonValue.Str("v")), "\$"))
    }

    @Test
    fun dottedPathWalksObjectsAndIndices() {
        val items = AgentJsonValue.Arr(listOf(AgentJsonValue.Str("x")))
        val root = obj(
            "outer" to obj(
                "list" to items,
                "nested" to AgentJsonValue.Arr(listOf(items))
            )
        )
        assertEquals(1, resolveForeachPathItems(root, "outer.list")?.size)
        assertEquals(1, resolveForeachPathItems(root, "outer.nested.0")?.size)
    }

    @Test
    fun malformedOrMissingPathsResolveToNull() {
        val root = obj("outer" to AgentJsonValue.Arr(listOf(AgentJsonValue.Str("x"))))
        assertNull(resolveForeachPathItems(root, "missing"))
        assertNull(resolveForeachPathItems(root, "outer.9"))
        assertNull(resolveForeachPathItems(root, "outer.notAnIndex"))
        assertNull(resolveForeachPathItems(root, "outer..list"))
        assertNull(resolveForeachPathItems(root, " "))
        assertNull(resolveForeachPathItems(root, "outer.0.1"))
    }

    @Test
    fun oversizedPathsAreRefused() {
        val root = obj("k" to AgentJsonValue.Arr(emptyList()))
        assertNull(resolveForeachPathItems(root, "a".repeat(MAX_PATH_SEGMENT_CHARS + 1)))
        assertNull(resolveForeachPathItems(root, List(MAX_PATH_SEGMENTS + 1) { "a" }.joinToString(".")))
        // Exactly at the limits is still accepted when the path exists.
        val deep = (1 until MAX_PATH_SEGMENTS).fold<Int, AgentJsonValue>(AgentJsonValue.Arr(emptyList())) { acc, _ ->
            obj("a" to acc)
        }
        assertTrue(resolveForeachPathItems(deep, List(MAX_PATH_SEGMENTS - 1) { "a" }.joinToString(".")) != null)
    }

    // ---- agent-task memory selection ----

    @Test
    fun absentSelectionInheritsTheRunPlan() {
        assertEquals(emptySet<String>(), parseAgentTaskMemorySpaceSelection(null))
        assertEquals(emptySet<String>(), parseAgentTaskMemorySpaceSelection(AgentJsonValue.Null))
    }

    @Test
    fun validSelectionIsTrimmedAndDeduplicated() {
        val value = AgentJsonValue.Arr(
            listOf(AgentJsonValue.Str(" space-a "), AgentJsonValue.Str("space-b"))
        )
        assertEquals(setOf("space-a", "space-b"), parseAgentTaskMemorySpaceSelection(value))
    }

    @Test
    fun invalidSelectionsFailClosed() {
        assertNull(parseAgentTaskMemorySpaceSelection(AgentJsonValue.Str("space-a")))
        assertNull(parseAgentTaskMemorySpaceSelection(AgentJsonValue.Arr(emptyList())))
        assertNull(
            parseAgentTaskMemorySpaceSelection(
                AgentJsonValue.Arr(listOf(AgentJsonValue.Str("a"), AgentJsonValue.Str(" a ")))
            )
        )
        assertNull(parseAgentTaskMemorySpaceSelection(AgentJsonValue.Arr(listOf(AgentJsonValue.Num("1")))))
        assertNull(parseAgentTaskMemorySpaceSelection(AgentJsonValue.Arr(listOf(AgentJsonValue.Str("  ")))))
        assertNull(
            parseAgentTaskMemorySpaceSelection(
                AgentJsonValue.Arr(listOf(AgentJsonValue.Str("a".repeat(AgentMemoryLimits.MAX_SPACE_ID_CHARS + 1))))
            )
        )
        assertNull(
            parseAgentTaskMemorySpaceSelection(
                AgentJsonValue.Arr(listOf(AgentJsonValue.Str("bad\u0000id")))
            )
        )
    }

    @Test
    fun selectionIsBoundedByTheSpaceLimit() {
        val tooMany = AgentJsonValue.Arr(
            (0..AgentMemoryLimits.MAX_SELECTED_SPACES).map { AgentJsonValue.Str("space-$it") }
        )
        assertNull(parseAgentTaskMemorySpaceSelection(tooMany))
        val atLimit = AgentJsonValue.Arr(
            (0 until AgentMemoryLimits.MAX_SELECTED_SPACES).map { AgentJsonValue.Str("space-$it") }
        )
        assertEquals(AgentMemoryLimits.MAX_SELECTED_SPACES, parseAgentTaskMemorySpaceSelection(atLimit)?.size)
    }

    private fun obj(vararg fields: Pair<String, AgentJsonValue>): AgentJsonValue =
        AgentJsonValue.Obj(fields.toList())
}
