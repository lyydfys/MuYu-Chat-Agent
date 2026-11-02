package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshBrowserSnapshotDiffTest {

    private fun node(
        ref: String,
        name: String = "Go",
        role: String = "button",
        left: Int = 10,
        inView: Boolean = true,
        disabled: Boolean = false
    ) = DshBrowserNode(
        ref = ref,
        role = role,
        name = name,
        bounds = DshBrowserBounds(left, 20, 100, 30),
        inView = inView,
        disabled = disabled
    )

    @Test
    fun `a moved element keeps its ref and reports a bounds change instead of remove plus add`() {
        val previous = listOf(node("r0000001", left = 10))
        val current = listOf(node("r0000001", left = 40))
        val delta = DshBrowserDomDiff.diff(previous, current, emptyList(), emptyList())
        assertEquals(0, delta.added.size)
        assertEquals(0, delta.removed.size)
        assertEquals(1, delta.changed.size)
        assertEquals(listOf("bounds"), delta.changed.single().fields)
        assertEquals(0, delta.unchangedCount)
        assertTrue(delta.isUnchanged.not())
    }

    @Test
    fun `reports added removed changed and unchanged counts with stable field order`() {
        val previous = listOf(
            node("r0000001"),
            node("r0000002", role = "link", name = "Docs"),
            node("r0000003", name = "Old")
        )
        val current = listOf(
            node("r0000001"),
            node("r0000002", role = "link", name = "Docs", inView = false),
            node("r0000004", name = "New")
        )
        val delta = DshBrowserDomDiff.diff(previous, current, listOf("a", "b"), listOf("a", "b", "c", "d"))
        assertEquals(listOf("r0000004"), delta.added.map { it.ref })
        assertEquals(listOf("r0000003"), delta.removed)
        assertEquals(listOf("r0000002"), delta.changed.map { it.ref })
        assertEquals(listOf("inView"), delta.changed.single().fields)
        assertEquals(1, delta.unchangedCount)
        assertEquals(listOf("c", "d"), delta.newText)
        assertTrue(delta.textChanged)
        assertFalse(delta.isUnchanged)
    }

    @Test
    fun `zero difference is reported as unchanged`() {
        val nodes = listOf(node("r0000001"), node("r0000002"))
        val delta = DshBrowserDomDiff.diff(nodes, nodes, listOf("same"), listOf("same"))
        assertTrue(delta.isUnchanged)
        assertEquals(2, delta.unchangedCount)
        assertTrue(delta.newText.isEmpty())
        assertFalse(delta.textChanged)
    }

    @Test
    fun `multiple differing fields keep a deterministic order`() {
        val old = node("r0000001", name = "A", role = "button", disabled = false)
        val new = node("r0000001", name = "B", role = "link", disabled = true)
        assertEquals(listOf("role", "name", "disabled"), DshBrowserDomDiff.differingFields(old, new))
    }

    @Test
    fun `new text is a bounded set difference`() {
        assertEquals(
            listOf("b"),
            DshBrowserDomDiff.newLines(listOf("a", "a"), listOf("a", "b"))
        )
        assertEquals(
            emptyList<String>(),
            DshBrowserDomDiff.newLines(listOf("a"), listOf("a", "a"))
        )
        val many = (1..20).map { "line$it" }
        assertEquals(8, DshBrowserDomDiff.newLines(emptyList(), many).size)
    }
}
