package com.muyuchat.core.dsh.browser

import kotlinx.coroutines.delay

/**
 * Deterministic engine used by the JVM tests.
 *
 * It models exactly the observable behaviour the session depends on (documents, mutation counters,
 * URL changes, editable/disabled targets) and nothing else, so a session test that passes proves
 * the policy/state layer, not an Android stub.
 */
class FakeBrowserEngine : DshBrowserEnginePort {

    class Page(
        var url: String = "about:blank",
        var title: String = "",
        var nodes: MutableList<DshBrowserNode> = mutableListOf(),
        var text: List<String> = emptyList(),
        var mutation: Long = 0L,
        var viewport: DshBrowserViewport = DshBrowserViewport(1080, 1920)
    )

    val pages = LinkedHashMap<String, Page>()
    var closed = false
        private set
    val closeTabCalls = mutableListOf<String>()
    val loads = mutableListOf<Pair<String, String>>()

    /** Non-null makes every load return a typed error with this detail. */
    var loadFailureDetail: String? = null

    /** Non-null is the post-redirect URL the load resolves to. */
    var redirectTo: String? = null

    /** Non-null is the URL observation reports instead of the page URL (simulated redirect trick). */
    var observeUrlOverride: String? = null

    var observeDelayMillis: Long = 0L
    var clickDelayMillis: Long = 0L
    var typeDelayMillis: Long = 0L
    var clickThrows: DshBrowserEngineException? = null
    var clickNavigatesTo: String? = null
    var clickMutates: Boolean = true
    var clickAddsNode: Boolean = true
    var clickMutatesWithoutChange: Boolean = false
    var typeValue: String = ""
    var readText: String = "fixture body"
    var readLong = false
    var resolveReturnsNull = false
    val disabledRefs = mutableSetOf<String>()
    val editableRefs = mutableSetOf<String>()
    var navNoHistory = false
    var scrollY = 0
    var docHeight = 4000

    fun page(tabId: String): Page = pages.getValue(tabId)

    override suspend fun createTab(tabId: String, startUrl: String?) {
        pages[tabId] = Page(url = startUrl ?: DshBrowserTabs.ABOUT_BLANK)
    }

    override suspend fun closeTab(tabId: String) {
        closeTabCalls.add(tabId)
        pages.remove(tabId)
    }

    override suspend fun load(tabId: String, url: String): DshBrowserLoadOutcome {
        loads.add(tabId to url)
        val failure = loadFailureDetail
        if (failure != null) {
            return DshBrowserLoadOutcome(
                state = DshBrowserLoadState.ERROR,
                url = url,
                title = "",
                ready = false,
                errorDetail = failure
            )
        }
        val page = pages.getValue(tabId)
        page.url = redirectTo ?: url
        page.title = "Fixture"
        page.nodes = seedNodes().toMutableList()
        page.text = listOf("line one", "line two")
        page.mutation = 0L
        return DshBrowserLoadOutcome(
            state = DshBrowserLoadState.READY,
            url = page.url,
            title = page.title,
            ready = true
        )
    }

    override suspend fun observe(tabId: String, includeText: Boolean): DshBrowserObservation {
        if (observeDelayMillis > 0) delay(observeDelayMillis)
        val page = pages.getValue(tabId)
        return DshBrowserObservation(
            url = observeUrlOverride ?: page.url,
            title = page.title,
            viewport = page.viewport,
            nodes = page.nodes.toList(),
            textLines = if (includeText) page.text else null,
            truncated = false,
            mutationCount = page.mutation
        )
    }

    override suspend fun resolve(tabId: String, ref: String): DshBrowserTarget? {
        if (resolveReturnsNull) return null
        val page = pages.getValue(tabId)
        val node = page.nodes.firstOrNull { it.ref == ref }
            ?: return DshBrowserTarget(ref, false, false, false, false, "", "", null)
        return DshBrowserTarget(
            ref = ref,
            found = true,
            disabled = node.disabled,
            editable = ref in editableRefs || node.role == "textbox",
            inView = node.inView,
            name = node.name,
            role = node.role,
            bounds = node.bounds
        )
    }

    override suspend fun click(tabId: String, target: DshBrowserTarget): DshBrowserClickDispatch {
        clickThrows?.let { throw it }
        if (clickDelayMillis > 0) delay(clickDelayMillis)
        val page = pages.getValue(tabId)
        val navigates = clickNavigatesTo
        if (navigates != null) {
            page.url = navigates
            page.title = "Next page"
            page.nodes = mutableListOf()
            page.text = emptyList()
            page.mutation += 1
        } else {
            if (clickMutates && !clickMutatesWithoutChange) page.mutation += 1
            if (clickAddsNode) page.nodes.add(node("r00000ff", "button", "After click"))
            if (clickMutatesWithoutChange) page.text = page.text
            else page.text = page.text + "after click"
        }
        return DshBrowserClickDispatch(via = "js", clickedAt = listOf(50, 60))
    }

    override suspend fun type(
        tabId: String,
        target: DshBrowserTarget,
        text: String,
        replace: Boolean
    ): DshBrowserTypeDispatch {
        if (typeDelayMillis > 0) delay(typeDelayMillis)
        val page = pages.getValue(tabId)
        page.mutation += 1
        typeValue = if (replace) text else typeValue + text
        return DshBrowserTypeDispatch(value = typeValue, via = "setter", dispatched = true)
    }

    override suspend fun read(tabId: String, ref: String?): DshBrowserTextRead {
        val text = if (readLong) "x".repeat(9_000) else readText
        val isField = ref == "r0000001"
        return DshBrowserTextRead(
            text = if (isField) "" else text,
            isField = isField,
            tag = if (isField) "input" else "div",
            type = if (isField) "text" else "",
            value = if (isField) "typed value" else "",
            placeholder = if (isField) "Search" else "",
            disabled = false
        )
    }

    override suspend fun navigate(tabId: String, op: DshBrowserNavOp): DshBrowserNavResult {
        if (navNoHistory) return DshBrowserNavResult(noHistory = true)
        val page = pages.getValue(tabId)
        if (op == DshBrowserNavOp.RELOAD) page.mutation += 1
        return DshBrowserNavResult(noHistory = false)
    }

    override suspend fun scroll(
        tabId: String,
        direction: DshBrowserScrollDirection,
        amount: Int
    ): DshBrowserScrollPosition {
        scrollY = when (direction) {
            DshBrowserScrollDirection.TOP -> 0
            DshBrowserScrollDirection.BOTTOM -> docHeight
            DshBrowserScrollDirection.DOWN -> scrollY + amount
            DshBrowserScrollDirection.UP -> (scrollY - amount).coerceAtLeast(0)
        }
        return DshBrowserScrollPosition(scrollY, innerHeight = 1920, docHeight = docHeight)
    }

    override fun capabilities(): DshBrowserEngineCaps = DshBrowserEngineCaps(
        engine = "fake-webview",
        userAgent = "FakeAgent/1.0",
        viewport = DshBrowserViewport(1080, 1920),
        processIsolation = true,
        overlayAvailable = true,
        downloadsPolicy = "reject"
    )

    override suspend fun close() {
        closed = true
        pages.clear()
    }

    companion object {
        fun seedNodes(): List<DshBrowserNode> = listOf(
            node("r0000001", "textbox", "Search"),
            node("r0000002", "button", "Go"),
            node("r0000003", "link", "Docs"),
            node("r0000004", "button", "Disabled", disabled = true)
        )

        fun node(
            ref: String,
            role: String,
            name: String,
            disabled: Boolean = false,
            inView: Boolean = true
        ): DshBrowserNode = DshBrowserNode(
            ref = ref,
            role = role,
            name = name,
            bounds = DshBrowserBounds(10, 20, 100, 30),
            inView = inView,
            disabled = disabled,
            dup = false
        )
    }
}

