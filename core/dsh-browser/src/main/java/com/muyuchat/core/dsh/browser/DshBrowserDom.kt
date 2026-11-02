package com.muyuchat.core.dsh.browser

/** Viewport reported by the page, in CSS pixels. */
data class DshBrowserViewport(
    val width: Int,
    val height: Int,
    val scale: Double = 1.0
) {
    init {
        require(width >= 0 && height >= 0 && width <= DshBrowserLimits.MAX_SCROLL_AMOUNT) {
            "browser viewport is out of range"
        }
        require(scale > 0.0 && scale.isFinite()) { "browser viewport scale must be positive" }
    }

    val isUsable: Boolean
        get() = width > 0 && height > 0

    companion object {
        val UNKNOWN = DshBrowserViewport(0, 0, 1.0)
    }
}

/** Element rectangle in CSS pixels, already rounded by the engine. */
data class DshBrowserBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * One semantic element of the page.
 *
 * [ref] is an element identity fingerprint, not a position: the engine derives it from the
 * element's tag/type/role/name/href/dom-path (same scheme as the pinned reference) plus a `-N`
 * suffix for duplicate fingerprints. It stays stable across snapshots in one page generation and
 * is invalidated by navigation, never renumbered by the host.
 */
data class DshBrowserNode(
    val ref: String,
    val role: String,
    val name: String,
    val bounds: DshBrowserBounds?,
    val inView: Boolean,
    val disabled: Boolean,
    val dup: Boolean = false
) {
    init {
        require(ref.isNotBlank() && ref.length <= DshBrowserLimits.MAX_REF_CHARS) {
            "browser node ref must be 1..${DshBrowserLimits.MAX_REF_CHARS} characters"
        }
        require(role.length <= DshBrowserLimits.MAX_ROLE_CHARS) { "browser node role is too long" }
        require(name.length <= DshBrowserLimits.MAX_NAME_CHARS) { "browser node name is too long" }
        require(role.none { it.code <= 0x1F || it.code in 0x7F..0x9F }) {
            "browser node role must be a single line"
        }
    }
}

/** One raw page observation produced by the engine port. */
data class DshBrowserObservation(
    val url: String,
    val title: String,
    val viewport: DshBrowserViewport,
    val nodes: List<DshBrowserNode>,
    val textLines: List<String>? = null,
    val truncated: Boolean = false,
    /** Monotonic page mutation counter from the DOM observer; 0 means "not supported". */
    val mutationCount: Long = 0L
) {
    init {
        require(title.length <= 300) { "browser page title is too long" }
        require(url.length <= DshBrowserLimits.MAX_URL_CHARS) { "browser page URL is too long" }
        require(nodes.size <= DshBrowserLimits.MAX_NODES) { "browser observation exceeds the node cap" }
        require(textLines == null || textLines.size <= DshBrowserLimits.MAX_TEXT_LINES) {
            "browser observation exceeds the text line cap"
        }
    }
}

/** One entry of the `changed` array: same ref, different visible fields. */
data class DshBrowserNodeChange(
    val ref: String,
    val fields: List<String>,
    val node: DshBrowserNode
)

/** True differential snapshot result. Never "two texts called a diff". */
data class DshBrowserSnapshotDelta(
    val added: List<DshBrowserNode>,
    val removed: List<String>,
    val changed: List<DshBrowserNodeChange>,
    val unchangedCount: Int,
    val newText: List<String>,
    val textChanged: Boolean
) {
    val isUnchanged: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && !textChanged
}

/**
 * Pure snapshot differ.
 *
 * Keys are refs, so an element that merely moves keeps its identity: the diff reports a `bounds`
 * change on the same ref instead of "removed + added". That is what makes a previous ref still
 * usable after a harmless DOM change.
 */
object DshBrowserDomDiff {

    private const val MAX_NEW_TEXT_LINES = 8

    fun diff(
        previous: List<DshBrowserNode>,
        current: List<DshBrowserNode>,
        previousText: List<String>?,
        currentText: List<String>?
    ): DshBrowserSnapshotDelta {
        val previousByRef = LinkedHashMap<String, DshBrowserNode>(previous.size)
        previous.forEach { previousByRef[it.ref] = it }
        val currentByRef = LinkedHashMap<String, DshBrowserNode>(current.size)
        current.forEach { currentByRef[it.ref] = it }

        val added = ArrayList<DshBrowserNode>()
        val changed = ArrayList<DshBrowserNodeChange>()
        for (node in current) {
            val old = previousByRef[node.ref]
            if (old == null) {
                added.add(node)
            } else {
                val fields = differingFields(old, node)
                if (fields.isNotEmpty()) changed.add(DshBrowserNodeChange(node.ref, fields, node))
            }
        }
        val removed = previousByRef.keys.filter { !currentByRef.containsKey(it) }
        val newText = newLines(previousText ?: emptyList(), currentText ?: emptyList())
        return DshBrowserSnapshotDelta(
            added = added,
            removed = removed,
            changed = changed,
            unchangedCount = current.size - added.size - changed.size,
            newText = newText,
            textChanged = newText.isNotEmpty()
        )
    }

    /** Field names in a stable order, matching the wire `changed[].fields`. */
    fun differingFields(old: DshBrowserNode, current: DshBrowserNode): List<String> = buildList {
        if (old.role != current.role) add("role")
        if (old.name != current.name) add("name")
        if (old.bounds != current.bounds) add("bounds")
        if (old.inView != current.inView) add("inView")
        if (old.disabled != current.disabled) add("disabled")
        if (old.dup != current.dup) add("dup")
    }

    /**
     * Text lines visible in [current] that were not visible in [previous], bounded.
     *
     * A line is "new" when it did not appear at all before; a repeated line is not new text, so
     * the comparison is a set difference rather than a multiset count.
     */
    fun newLines(previous: List<String>, current: List<String>): List<String> {
        if (current.isEmpty()) return emptyList()
        val known = previous.toHashSet()
        val fresh = ArrayList<String>()
        for (line in current) {
            if (line in known) continue
            fresh.add(line)
            if (fresh.size >= MAX_NEW_TEXT_LINES) break
        }
        return fresh
    }
}
