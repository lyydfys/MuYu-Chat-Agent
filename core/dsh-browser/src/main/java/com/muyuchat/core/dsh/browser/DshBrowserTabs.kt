package com.muyuchat.core.dsh.browser

/** Load state of one tab, using the pinned reference's wire words. */
enum class DshBrowserLoadState(val wire: String) {
    IDLE("idle"),
    LOADING("loading"),
    READY("ready"),
    ERROR("error")
}

/** Immutable projection of one tab, safe to hand to a response encoder. */
data class DshBrowserTab(
    val id: String,
    val pageGeneration: Long,
    val navigationGeneration: Long,
    val url: String,
    val title: String,
    val loadState: DshBrowserLoadState,
    val hasSnapshot: Boolean
)

/**
 * Bounded multi-tab model.
 *
 * Each tab owns its own page generation, navigation generation, URL, load state and snapshot
 * flag. Refs are *not* stored here: [DshBrowserRefStore] owns the per-tab, per-generation identity
 * table so a tab switch can never silently validate another tab's refs.
 *
 * Page generation advances on navigation and on an observed DOM mutation. A ref-consuming action
 * is bound to the navigation generation, which only advances on navigation; a DOM-only change
 * therefore keeps the previous refs usable (that is the whole point of identity fingerprints),
 * while a navigation invalidates them.
 *
 * This class is not thread-safe: [DshBrowserSession] serialises every mutation.
 */
class DshBrowserTabs(private val maxTabs: Int = DshBrowserLimits.MAX_TABS) {

    private class MutableTab(
        val id: String,
        var pageGeneration: Long = 0L,
        var navigationGeneration: Long = 0L,
        var url: String = ABOUT_BLANK,
        var title: String = "",
        var loadState: DshBrowserLoadState = DshBrowserLoadState.IDLE,
        var hasSnapshot: Boolean = false
    ) {
        fun view(): DshBrowserTab = DshBrowserTab(
            id = id,
            pageGeneration = pageGeneration,
            navigationGeneration = navigationGeneration,
            url = url,
            title = title,
            loadState = loadState,
            hasSnapshot = hasSnapshot
        )
    }

    private val tabs = LinkedHashMap<String, MutableTab>()
    private var sequence = 0L

    var activeTabId: String = ""
        private set

    val size: Int
        get() = tabs.size

    val isAtCapacity: Boolean
        get() = tabs.size >= maxTabs

    val capacity: Int
        get() = maxTabs

    fun isEmpty(): Boolean = tabs.isEmpty()

    /** Allocates the next stable id. Only meaningful together with a successful engine creation. */
    fun allocateId(): String {
        sequence += 1
        return "t$sequence"
    }

    /**
     * Registers an engine-created tab. Fails closed when the bound would be exceeded.
     *
     * The first tab becomes active implicitly; any later tab only becomes active through an
     * explicit [switchTo], so `tabs.new{front:false}` cannot silently steal the active page.
     */
    fun add(tabId: String): Boolean {
        if (isAtCapacity) return false
        if (tabs.containsKey(tabId)) return false
        tabs[tabId] = MutableTab(id = tabId)
        if (activeTabId.isEmpty()) activeTabId = tabId
        return true
    }

    fun find(tabId: String?): DshBrowserTab? = tabId?.let { tabs[it]?.view() }

    /** The active tab, or null when the session has no tab at all. */
    fun active(): DshBrowserTab? = tabs[activeTabId]?.view()

    fun list(): List<DshBrowserTab> = tabs.values.map { it.view() }

    fun isActive(tabId: String?): Boolean = tabId != null && tabId == activeTabId

    fun switchTo(tabId: String): DshBrowserTab? {
        val tab = tabs[tabId] ?: return null
        activeTabId = tabId
        return tab.view()
    }

    /** Removes one tab. Returns the removed projection, or null when the id is unknown. */
    fun remove(tabId: String): DshBrowserTab? {
        val removed = tabs.remove(tabId) ?: return null
        if (activeTabId == tabId) {
            activeTabId = tabs.keys.lastOrNull().orEmpty()
        }
        return removed.view()
    }

    /** Navigation admission: refs die, both generations advance, the old snapshot is dropped. */
    fun beginNavigation(tabId: String, targetUrl: String): DshBrowserTab? {
        val tab = tabs[tabId] ?: return null
        tab.pageGeneration += 1
        tab.navigationGeneration += 1
        tab.hasSnapshot = false
        tab.loadState = DshBrowserLoadState.LOADING
        tab.url = targetUrl
        tab.title = ""
        return tab.view()
    }

    fun finishLoad(
        tabId: String,
        url: String?,
        title: String?,
        state: DshBrowserLoadState
    ): DshBrowserTab? {
        val tab = tabs[tabId] ?: return null
        if (url != null && url.isNotEmpty()) tab.url = url.take(DshBrowserLimits.MAX_URL_CHARS)
        if (title != null) tab.title = title.take(300)
        tab.loadState = state
        return tab.view()
    }

    /** DOM-only change: page generation advances but navigation generation (ref validity) does not. */
    fun noteDomMutation(tabId: String): Long? {
        val tab = tabs[tabId] ?: return null
        tab.pageGeneration += 1
        return tab.pageGeneration
    }

    fun markSnapshot(tabId: String): DshBrowserTab? {
        val tab = tabs[tabId] ?: return null
        tab.hasSnapshot = true
        tab.loadState = if (tab.loadState == DshBrowserLoadState.IDLE) {
            DshBrowserLoadState.READY
        } else {
            tab.loadState
        }
        return tab.view()
    }

    /** Releases the whole model. The caller must have released the engine tabs first. */
    fun clear() {
        tabs.clear()
        activeTabId = ""
    }

    companion object {
        /** Engine-owned blank page; never reachable through the URL policy. */
        const val ABOUT_BLANK = "about:blank"
    }
}
