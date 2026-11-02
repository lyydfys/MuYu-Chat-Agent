package com.muyuchat.core.dsh.a11y.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM behaviour tests for the tree caps and redaction policy. Password redaction, text truncation
 * and node lookup are decisions this module makes, so they are asserted here instead of trusting
 * an Android stub.
 */
class A11yTreePolicyTest {

    @Test
    fun passwordFieldsAreRedactedAndCounted() {
        val snapshot = snapshot(
            draft(text = "Search", clickable = true),
            draft(text = "hunter2", desc = "password field", input = true, password = true)
        )

        val search = snapshot.nodes[0]
        assertEquals("Search", search.text)
        assertFalse(search.redacted)

        val password = snapshot.nodes[1]
        assertEquals("", password.text)
        assertEquals("", password.desc)
        assertTrue(password.redacted)
        assertTrue(password.password)
        assertEquals(1, snapshot.redactedCount)
        assertFalse(snapshot.textTruncated)
    }

    @Test
    fun nodeLabelsAreTruncatedAtTheCap() {
        val snapshot = snapshot(draft(text = "a".repeat(200)))
        val text = snapshot.nodes.single().text
        assertEquals(A11yLimits.MAX_NODE_TEXT_CHARS + 1, text.length)
        assertTrue(text.endsWith("\u2026"))
    }

    @Test
    fun nodeCountIsCappedAndReportedAsTruncated() {
        val drafts = (0 until A11yLimits.MAX_NODES + 25).map { draft(text = "node-$it") }
        val snapshot = snapshot(*drafts.toTypedArray())
        assertEquals(A11yLimits.MAX_NODES, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
        assertEquals(A11yLimits.MAX_NODES - 1, snapshot.nodes.last().index)
    }

    @Test
    fun screenTextBudgetStopsTheListingAndFlagsTruncation() {
        val drafts = (0 until 200).map { draft(text = "x".repeat(120)) }
        val snapshot = snapshot(*drafts.toTypedArray())
        val totalChars = snapshot.nodes.sumOf { it.text.length + it.desc.length }
        assertTrue("total readable text must stay under the cap", totalChars <= A11yLimits.MAX_SCREEN_TEXT_CHARS)
        assertTrue(snapshot.textTruncated)
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.nodes.size < 200)
    }

    @Test
    fun nodesDeeperThanTheCapAreDropped() {
        val snapshot = snapshot(
            draft(text = "root", depth = 0),
            draft(text = "too deep", depth = A11yLimits.MAX_DEPTH + 1)
        )
        assertEquals(1, snapshot.nodes.size)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun textSearchPrefersExactClickableThenClickableThenExact() {
        val match = snapshot(
            draft(text = "OK", clickable = false),
            draft(text = "OK button", clickable = true),
            draft(text = "OK", clickable = true)
        )
        val best = A11yTreePolicy.findBestMatch(match, "ok")
        assertEquals(2, best?.index)

        val clickablePartial = snapshot(
            draft(text = "OK", clickable = false),
            draft(text = "OK button", clickable = true)
        )
        assertEquals(1, A11yTreePolicy.findBestMatch(clickablePartial, "ok")?.index)

        val exactOnly = snapshot(draft(text = "OK", clickable = false))
        assertEquals(0, A11yTreePolicy.findBestMatch(exactOnly, "ok")?.index)

        assertNull(A11yTreePolicy.findBestMatch(match, "missing"))
        assertNull(A11yTreePolicy.findBestMatch(match, " "))
    }

    @Test
    fun passwordNodesAreNeverTextMatched() {
        val password = snapshot(draft(text = "secret", password = true))
        assertNull(A11yTreePolicy.findBestMatch(password, "secret"))
    }

    @Test
    fun pointLookupPrefersTheDeepestClickableNode() {
        val snapshot = snapshot(
            draft(text = "row", rect = A11yRect(0, 0, 500, 200), depth = 1, clickable = true),
            draft(text = "label", rect = A11yRect(10, 10, 100, 40), depth = 3),
            draft(text = "outside", rect = A11yRect(900, 900, 100, 100), depth = 5, clickable = true)
        )
        assertEquals(0, A11yTreePolicy.nodeAtPoint(snapshot, A11yPoint(50, 50))?.index)
        assertNull(A11yTreePolicy.nodeAtPoint(snapshot, A11yPoint(700, 700)))
    }

    @Test
    fun deepNonClickableNodeWinsWhenNothingIsClickable() {
        val snapshot = snapshot(
            draft(text = "container", rect = A11yRect(0, 0, 500, 200), depth = 1),
            draft(text = "deep label", rect = A11yRect(10, 10, 100, 40), depth = 3)
        )
        assertEquals(1, A11yTreePolicy.nodeAtPoint(snapshot, A11yPoint(20, 20))?.index)
    }

    @Test
    fun nodeIdentityCheckDetectsAChangedNode() {
        val first = snapshot(draft(text = "Continue", clickable = true)).nodes.single()
        val same = snapshot(draft(text = "Continue", clickable = true)).nodes.single()
        val moved = snapshot(
            draft(text = "Continue", clickable = true, rect = A11yRect(0, 300, 100, 50))
        ).nodes.single()
        assertTrue(A11yTreePolicy.matchesIdentity(first, same))
        assertFalse(A11yTreePolicy.matchesIdentity(first, moved))
    }

    @Test
    fun nodeRefsRoundTripAndRejectMalformedValues() {
        val ref = A11yNodeRef(7L, 12)
        assertEquals("7:12", ref.wire)
        assertEquals(ref, A11yNodeRef.parse("7:12"))
        assertNull(A11yNodeRef.parse("7"))
        assertNull(A11yNodeRef.parse("-1:2"))
        assertNull(A11yNodeRef.parse("7:not-a-number"))
    }

    @Test
    fun emptyTreeKeepsGenerationForRefRejection() {
        val snapshot = A11yTreePolicy.buildSnapshot(9L, "com.example", 3, emptyList(), false)
        assertTrue(snapshot.isEmpty)
        assertFalse(snapshot.truncated)
        assertEquals(9L, snapshot.generation)
        assertEquals(3, snapshot.windowId)
    }

    // ------------------------------------------------------------------ helpers

    private fun draft(
        text: String = "",
        desc: String = "",
        cls: String = "android.widget.TextView",
        rect: A11yRect = A11yRect(0, 0, 100, 50),
        depth: Int = 0,
        clickable: Boolean = false,
        input: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        scrollable: Boolean = false,
        password: Boolean = false
    ): A11yNodeDraft = A11yNodeDraft(
        depth = depth,
        text = text,
        desc = desc,
        cls = cls,
        rect = rect,
        clickable = clickable,
        input = input,
        checked = checked,
        selected = selected,
        scrollable = scrollable,
        password = password
    )

    private fun snapshot(vararg drafts: A11yNodeDraft): A11yTreeSnapshot =
        A11yTreePolicy.buildSnapshot(1L, "com.example", 7, drafts.toList(), false)
}
