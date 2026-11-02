package com.muyuchat.core.dsh.browser

/** Engine fault families, mapped to typed failures by the session. */
enum class DshBrowserEngineError {
    /** No engine / no WebView host for this call. */
    UNAVAILABLE,

    /** The page has no viewport or no document yet. */
    PAGE_NOT_READY,

    /** A scripted DOM operation did not return a usable result. */
    SCRIPT_FAILED,

    /** The tab (or the whole engine) was closed under the call. */
    CLOSED,

    /** The engine's own bounded wait for the page elapsed. */
    TIMEOUT,

    /** A main-frame navigation or redirect was refused by the URL policy. */
    NAVIGATION_BLOCKED,

    /** Adapter defect. */
    INTERNAL
}

/** Typed engine fault. [detail] must be prompt-free and must never contain page text. */
class DshBrowserEngineException(
    val kind: DshBrowserEngineError,
    val detail: String
) : Exception(detail)

/** A resolved element target: the live DOM answer for one ref. */
data class DshBrowserTarget(
    val ref: String,
    val found: Boolean,
    val disabled: Boolean,
    val editable: Boolean,
    val inView: Boolean,
    val name: String,
    val role: String,
    val bounds: DshBrowserBounds?
)

/** Outcome of one load awaited by the engine. */
data class DshBrowserLoadOutcome(
    val state: DshBrowserLoadState,
    val url: String,
    val title: String,
    val ready: Boolean,
    val errorDetail: String? = null
)

/** What a click actually dispatched. */
data class DshBrowserClickDispatch(
    val via: String,
    val clickedAt: List<Int>?
)

/** What a type actually did, as the DOM reports it. */
data class DshBrowserTypeDispatch(
    val value: String,
    val via: String,
    val dispatched: Boolean
)

/** Structured field read (`input`/`textarea`/`select` read their `value`, not `textContent`). */
data class DshBrowserTextRead(
    val text: String,
    val isField: Boolean,
    val tag: String,
    val type: String,
    val value: String,
    val placeholder: String,
    val disabled: Boolean
)

/** Result of `back` / `forward` / `reload`. */
data class DshBrowserNavResult(
    val noHistory: Boolean
)

/** Scroll position after a scroll operation. */
data class DshBrowserScrollPosition(
    val scrollY: Int,
    val innerHeight: Int,
    val docHeight: Int
)

/** Honest self-description of the engine behind the port. */
data class DshBrowserEngineCaps(
    val engine: String,
    val userAgent: String?,
    val viewport: DshBrowserViewport?,
    val processIsolation: Boolean,
    val overlayAvailable: Boolean,
    val downloadsPolicy: String
)

/**
 * The typed seam between the protocol/state layer and the platform WebView.
 *
 * Everything above this port is plain Kotlin and unit-tested. The production implementation
 * ([DshBrowserWebViewEngine]) lives in the `:browser` process; tests substitute a fake.
 *
 * Implementations must:
 *  - keep one `WebView` per tab id, never share a document between tabs;
 *  - derive refs as element identity fingerprints (`r` + 7 hex + optional `-N`), stable across
 *    snapshots until navigation;
 *  - re-apply [DshBrowserUrlPolicy] to every main-frame redirect before it loads;
 *  - never expose cookies, the DSH WebView profile or the capability token to the page.
 */
interface DshBrowserEnginePort {
    suspend fun createTab(tabId: String, startUrl: String?)

    suspend fun closeTab(tabId: String)

    suspend fun load(tabId: String, url: String): DshBrowserLoadOutcome

    suspend fun observe(tabId: String, includeText: Boolean): DshBrowserObservation

    suspend fun resolve(tabId: String, ref: String): DshBrowserTarget?

    suspend fun click(tabId: String, target: DshBrowserTarget): DshBrowserClickDispatch

    suspend fun type(
        tabId: String,
        target: DshBrowserTarget,
        text: String,
        replace: Boolean
    ): DshBrowserTypeDispatch

    suspend fun read(tabId: String, ref: String?): DshBrowserTextRead

    suspend fun navigate(tabId: String, op: DshBrowserNavOp): DshBrowserNavResult

    suspend fun scroll(
        tabId: String,
        direction: DshBrowserScrollDirection,
        amount: Int
    ): DshBrowserScrollPosition

    fun capabilities(): DshBrowserEngineCaps

    /** Releases every tab/WebView and the profile session. Idempotent. */
    suspend fun close()
}
