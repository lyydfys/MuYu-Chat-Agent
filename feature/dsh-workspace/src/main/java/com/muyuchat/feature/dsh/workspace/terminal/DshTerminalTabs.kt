package com.muyuchat.feature.dsh.workspace.terminal

/**
 * Tab strip state of the native terminal page.
 *
 * A tab is declared before it is usable: the owner opens the PTY asynchronously, so the strip shows
 * "打开中" until the session id arrives. A failed open keeps its tab with the reason instead of
 * vanishing, because a disappearing tab looks like a mis-tap.
 *
 * Pure and bounded: the strip never exceeds [maxTabs], ids are opaque, and every transition is a
 * value the page can render directly.
 */
data class DshTerminalTab(
    val id: String,
    val title: String,
    val state: State,
    val sessionId: String? = null,
    val detail: String? = null
) {
    enum class State { OPENING, READY, FAILED, CLOSED }

    val isReady: Boolean get() = state == State.READY && sessionId != null
}

data class DshTerminalTabs(
    val tabs: List<DshTerminalTab> = emptyList(),
    val selectedId: String? = null,
    val nextIndex: Int = 1,
    val maxTabs: Int = DEFAULT_MAX_TABS
) {
    val selected: DshTerminalTab? get() = tabs.firstOrNull { it.id == selectedId }

    val canOpen: Boolean get() = tabs.size < maxTabs

    /** Declares a new tab and selects it; returns the unchanged strip when full. */
    fun open(title: String? = null): DshTerminalTabs {
        if (!canOpen) return this
        val id = "tab-$nextIndex"
        val tab = DshTerminalTab(
            id = id,
            title = title?.takeIf { it.isNotBlank() } ?: "终端 $nextIndex",
            state = DshTerminalTab.State.OPENING
        )
        return copy(tabs = tabs + tab, selectedId = id, nextIndex = nextIndex + 1)
    }

    /** A session id arrived: the tab can now carry input. */
    fun opened(id: String, sessionId: String): DshTerminalTabs {
        require(sessionId.isNotBlank()) { "terminal: session id must not be blank" }
        return map(id) { it.copy(state = DshTerminalTab.State.READY, sessionId = sessionId, detail = null) }
    }

    /** The PTY refused to start; the tab stays visible with its reason. */
    fun failed(id: String, detail: String): DshTerminalTabs =
        map(id) { it.copy(state = DshTerminalTab.State.FAILED, detail = detail.take(MAX_DETAIL)) }

    /**
     * Closes one tab and selects a neighbour.
     *
     * Selection falls back to the previous tab, then to the next one, so closing never leaves an
     * empty selection while tabs remain.
     */
    fun close(id: String): DshTerminalTabs {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.id == id }
        val nextSelected = when {
            remaining.isEmpty() -> null
            selectedId != id -> selectedId?.takeIf { candidate -> remaining.any { it.id == candidate } }
                ?: remaining.first().id
            index - 1 >= 0 -> remaining[(index - 1).coerceAtMost(remaining.size - 1)].id
            else -> remaining.first().id
        }
        return copy(tabs = remaining, selectedId = nextSelected)
    }

    fun select(id: String): DshTerminalTabs =
        if (tabs.any { it.id == id }) copy(selectedId = id) else this

    fun rename(id: String, title: String): DshTerminalTabs =
        map(id) { it.copy(title = title.trim().ifBlank { it.title }.take(MAX_TITLE)) }

    private fun map(id: String, transform: (DshTerminalTab) -> DshTerminalTab): DshTerminalTabs {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(tabs = tabs.toMutableList().also { it[index] = transform(it[index]) })
    }

    companion object {
        const val DEFAULT_MAX_TABS: Int = 6
        const val MAX_TITLE: Int = 32
        const val MAX_DETAIL: Int = 200
    }
}

/**
 * Pinch-to-zoom bounds of the terminal text.
 *
 * Kept out of the composable so the clamp is provable: a gesture can produce any float, and the page
 * must stay readable at both ends.
 */
object DshTerminalZoom {

    const val MIN_SP: Float = 8f
    const val MAX_SP: Float = 32f
    const val DEFAULT_SP: Float = 13f

    /** One pinch step; -1 smaller, +1 larger. */
    const val STEP_SP: Float = 1f

    fun clamp(sp: Float): Float = when {
        sp.isNaN() -> DEFAULT_SP
        sp < MIN_SP -> MIN_SP
        sp > MAX_SP -> MAX_SP
        else -> sp
    }

    /** Applies a multiplicative pinch factor to the current size and clamps the result. */
    fun zoom(current: Float, factor: Float): Float {
        if (factor.isNaN() || factor <= 0f) return clamp(current)
        return clamp(current * factor)
    }

    /** Keyboard/buttons path: one discrete step, clamped. */
    fun step(current: Float, direction: Int): Float =
        if (direction == 0) clamp(current) else clamp(current + direction * STEP_SP)
}
