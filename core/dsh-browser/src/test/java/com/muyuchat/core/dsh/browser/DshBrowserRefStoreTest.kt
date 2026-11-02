package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshBrowserRefStoreTest {

    private fun node(ref: String, role: String = "button", name: String = "Go") = DshBrowserNode(
        ref = ref,
        role = role,
        name = name,
        bounds = DshBrowserBounds(1, 2, 3, 4),
        inView = true,
        disabled = false
    )

    @Test
    fun `a recorded ref is valid for its navigation generation`() {
        val store = DshBrowserRefStore()
        store.record("t1", 3L, listOf(node("r0000001")))
        val check = store.validate("t1", 3L, "r0000001")
        assertTrue(check is DshBrowserRefCheck.Valid)
        assertEquals("Go", (check as DshBrowserRefCheck.Valid).node.name)
        assertTrue(store.hasSnapshot("t1", 3L))
    }

    @Test
    fun `an unknown ref on a snapshotted page is stale`() {
        val store = DshBrowserRefStore()
        store.record("t1", 1L, listOf(node("r0000001")))
        val failure = (store.validate("t1", 1L, "r00000ee") as DshBrowserRefCheck.Rejected).failure
        assertEquals(DshBrowserFailureReason.STALE_REF, failure.reason)
        assertEquals("stale-ref", failure.reason.wire)
    }

    @Test
    fun `no snapshot for the active generation asks for a snapshot`() {
        val store = DshBrowserRefStore()
        val failure = (store.validate("t1", 0L, "r0000001") as DshBrowserRefCheck.Rejected).failure
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, failure.reason)
        store.record("t1", 0L, listOf(node("r0000001")))
        val staleAfterNavigation =
            (store.validate("t1", 1L, "r0000001") as DshBrowserRefCheck.Rejected).failure
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, staleAfterNavigation.reason)
    }

    @Test
    fun `a ref remembered in another tab is a stale tab not a stale ref`() {
        val store = DshBrowserRefStore()
        store.record("t1", 0L, listOf(node("r0000001")))
        store.record("t2", 0L, listOf(node("r0000002")))
        val failure = (store.validate("t2", 0L, "r0000001") as DshBrowserRefCheck.Rejected).failure
        assertEquals(DshBrowserFailureReason.STALE_TAB, failure.reason)
        assertEquals("stale-tab", failure.reason.wire)
    }

    @Test
    fun `malformed refs are refused before any table lookup`() {
        val store = DshBrowserRefStore()
        listOf("", "r", "r123", "R0000001", "r0000001-", "rzzzzzzz", "r0000001-x", "' onload='x").forEach { ref ->
            val failure = (store.validate("t1", 0L, ref) as DshBrowserRefCheck.Rejected).failure
            assertEquals("ref $ref", DshBrowserFailureReason.INVALID_REF, failure.reason)
        }
    }

    @Test
    fun `release and clear drop remembered identity`() {
        val store = DshBrowserRefStore()
        store.record("t1", 0L, listOf(node("r0000001")))
        store.release("t1")
        assertTrue(store.nodes("t1", 0L) == null)
        store.record("t1", 0L, listOf(node("r0000001")))
        store.clear()
        assertTrue(store.nodes("t1", 0L) == null)
    }

    @Test
    fun `sanitize drops malformed refs and keeps the node cap`() {
        val nodes = mutableListOf<DshBrowserNode>()
        nodes.add(node("bad-ref"))
        repeat(DshBrowserLimits.MAX_NODES + 5) { index ->
            nodes.add(node("r" + index.toString().padStart(7, '0').takeLast(7)))
        }
        val sanitized = DshBrowserRefStore.sanitize(nodes)
        assertEquals(DshBrowserLimits.MAX_NODES, sanitized.size)
        assertTrue(sanitized.none { it.ref == "bad-ref" })
        assertTrue(DshBrowserRefStore.isWellFormedRef("r0000001"))
        assertTrue(DshBrowserRefStore.isWellFormedRef("r0000001-12"))
    }

    @Test
    fun `find scores exact prefix substring role synonyms and visibility`() {
        val nodes = listOf(
            node("r0000001", role = "button", name = "Login"),
            node("r0000002", role = "button", name = "Log in now"),
            node("r0000003", role = "link", name = "Login help"),
            node("r0000004", role = "textbox", name = "Email"),
            node("r0000005", role = "button", name = "Disabled login").copy(disabled = true)
        )
        val exact = DshBrowserFind.search(nodes, "Login", null, 5)
        assertEquals(3, exact.matched)
        assertEquals("r0000001", exact.candidates.first().ref)

        val byRole = DshBrowserFind.search(nodes, "button", null, 5)
        assertEquals("r0000001", byRole.candidates.first().ref)
        assertTrue(byRole.candidates.all { it.role == "button" })

        val filtered = DshBrowserFind.search(nodes, "Login", "link", 5)
        assertEquals(1, filtered.matched)
        assertEquals("r0000003", filtered.candidates.single().ref)

        val none = DshBrowserFind.search(nodes, "zzz", null, 5)
        assertEquals(0, none.matched)
        assertTrue(none.candidates.isEmpty())

        val bounded = DshBrowserFind.search(nodes, "", "button", 1)
        assertEquals(1, bounded.candidates.size)
    }
}
