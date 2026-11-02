package com.muyuchat.core.dsh.browser

/** Result of validating one ref against the identity table of the active tab. */
sealed interface DshBrowserRefCheck {
    data class Valid(val node: DshBrowserNode) : DshBrowserRefCheck

    data class Rejected(val failure: DshBrowserFailure) : DshBrowserRefCheck
}

/**
 * Per-tab, per-navigation-generation identity table.
 *
 * A snapshot (or find) records the whole observed node set for the active tab and its current
 * navigation generation. A ref-consuming action is valid only when the ref is present in that
 * table *and* the tab's navigation generation still matches: the table is released on navigation.
 * A DOM-only change therefore keeps refs alive, which is exactly the stability property the
 * protocol promises (and why `pageGeneration` alone is not used as the ref epoch).
 */
class DshBrowserRefStore {

    private class Entry(
        val navigationGeneration: Long,
        val nodes: Map<String, DshBrowserNode>
    )

    private val entries = HashMap<String, Entry>()

    fun record(tabId: String, navigationGeneration: Long, nodes: List<DshBrowserNode>) {
        val index = LinkedHashMap<String, DshBrowserNode>(nodes.size)
        nodes.forEach { node -> if (isWellFormedRef(node.ref)) index[node.ref] = node }
        entries[tabId] = Entry(navigationGeneration, index)
    }

    fun hasSnapshot(tabId: String, navigationGeneration: Long): Boolean {
        val entry = entries[tabId] ?: return false
        return entry.navigationGeneration == navigationGeneration
    }

    fun nodes(tabId: String, navigationGeneration: Long): List<DshBrowserNode>? {
        val entry = entries[tabId] ?: return null
        if (entry.navigationGeneration != navigationGeneration) return null
        return entry.nodes.values.toList()
    }

    fun validate(tabId: String, navigationGeneration: Long, ref: String): DshBrowserRefCheck {
        if (!isWellFormedRef(ref)) {
            return DshBrowserRefCheck.Rejected(
                browserFailure(
                    DshBrowserFailureReason.INVALID_REF,
                    "The ref is not an element identity fingerprint",
                    "refs look like r0123abc or r0123abc-2; take one from browser_snapshot or browser_find"
                )
            )
        }
        val entry = entries[tabId]
        if (entry == null || entry.navigationGeneration != navigationGeneration) {
            if (refKnownInAnotherTab(tabId, ref)) return staleTab()
            return DshBrowserRefCheck.Rejected(
                browserFailure(
                    DshBrowserFailureReason.SNAPSHOT_REQUIRED,
                    "No snapshot is registered for the active page generation",
                    "call browser_snapshot or browser_find first; the host never guesses coordinates"
                )
            )
        }
        val node = entry.nodes[ref]
        if (node != null) return DshBrowserRefCheck.Valid(node)
        if (refKnownInAnotherTab(tabId, ref)) return staleTab()
        return DshBrowserRefCheck.Rejected(
            browserFailure(
                DshBrowserFailureReason.STALE_REF,
                "The ref no longer identifies an element on this page",
                "the page changed or the element disappeared; call browser_snapshot or browser_find again"
            )
        )
    }

    fun release(tabId: String) {
        entries.remove(tabId)
    }

    fun clear() {
        entries.clear()
    }

    private fun refKnownInAnotherTab(activeTabId: String, ref: String): Boolean =
        entries.any { (tabId, entry) -> tabId != activeTabId && entry.nodes.containsKey(ref) }

    private fun staleTab(): DshBrowserRefCheck.Rejected = DshBrowserRefCheck.Rejected(
        browserFailure(
            DshBrowserFailureReason.STALE_TAB,
            "The ref belongs to a different tab than the active one",
            "switch to the tab that produced the ref (browser_tabs op=switch) and retry"
        )
    )

    companion object {
        private val REF_PATTERN = Regex("^r[0-9a-f]{7}(-\\d{1,3})?$")

        /** `r` + 7 lowercase hex fingerprint chars + optional duplicate suffix, as the engine emits. */
        fun isWellFormedRef(ref: String): Boolean =
            ref.length <= DshBrowserLimits.MAX_REF_CHARS && REF_PATTERN.matches(ref)

        /** Drops anything the engine should never produce, so page text cannot forge a ref table. */
        fun sanitize(nodes: List<DshBrowserNode>): List<DshBrowserNode> =
            nodes.filter { isWellFormedRef(it.ref) }.take(DshBrowserLimits.MAX_NODES)
    }
}
