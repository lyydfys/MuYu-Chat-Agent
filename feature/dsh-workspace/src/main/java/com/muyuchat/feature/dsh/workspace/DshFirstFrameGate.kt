package com.muyuchat.feature.dsh.workspace

/**
 * Pure, per-WebView-generation policy behind the single "first visible frame" report.
 *
 * WebView delivers page events in a fixed shape but not a single "the official UI is visible"
 * callback, so the host needs one policy that answers "may this event be the first frame of the
 * page that actually rendered?":
 *
 * - [onPageCommitVisible] is the platform's "content is now visible" signal and the primary
 *   first-frame event;
 * - [onPageFinished] is only a fallback for a clean, same-origin, token-free load, for WebView
 *   implementations that do not deliver commit-visible;
 * - a main-frame network/HTTP/auth failure means the visible surface is an error page, not the
 *   official page, so it must never be reported as the first frame; the next [onPageStarted]
 *   clears that state for a retry or restored load;
 * - at most one event per generation, so navigation after the first frame and renderer
 *   recreation cannot report twice.
 *
 * Epoch and READY validation stay with the workspace owner, which owns the frozen runtime
 * snapshot; this class never decides readiness and holds no Android types, so it is JVM-testable.
 * It is called only from WebView callbacks on the main thread.
 */
internal class DshFirstFrameGate {

    private var reported = false
    private var mainFrameFailed = false

    fun onPageStarted() {
        mainFrameFailed = false
    }

    fun onMainFrameFailed() {
        mainFrameFailed = true
    }

    /**
     * Starts a new host generation on the same composed WebView. A restart reuses the WebView but
     * loads a new origin/entry, so the "already reported" latch must be cleared; otherwise the new
     * generation is never observed as rendered (the console would keep showing 未渲染 forever).
     */
    fun reset() {
        reported = false
        mainFrameFailed = false
    }

    /** Returns true when this committed page is the generation's first visible frame. */
    fun onPageCommitVisible(): Boolean = accept()

    /**
     * Fallback for a load that finished without commit-visible. Only a clean same-origin page
     * without the one-shot launch token qualifies; a token URL finish means the cookie exchange
     * has not produced the official page yet.
     */
    fun onPageFinished(sameOrigin: Boolean, containsLaunchToken: Boolean): Boolean {
        if (!sameOrigin || containsLaunchToken) return false
        return accept()
    }

    private fun accept(): Boolean {
        if (reported || mainFrameFailed) return false
        reported = true
        return true
    }
}
