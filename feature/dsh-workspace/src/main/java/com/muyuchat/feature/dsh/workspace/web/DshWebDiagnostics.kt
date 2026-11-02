package com.muyuchat.feature.dsh.workspace.web

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import android.webkit.WebView
import com.muyuchat.feature.dsh.workspace.DshUrlRedaction

/**
 * Debug-only visibility into the official DSH page inside the WebView.
 *
 * This exists because the page can render partially while the main pane is blank; without a native
 * hook there is no way to tell whether the SPA failed to mount, threw during a sidebar action, or
 * simply has an empty root. Everything here is gated on
 * `ApplicationInfo.FLAG_DEBUGGABLE`; a release build never enables WebView debugging, never injects
 * the error collector and never evaluates the probe.
 *
 * Secrets: the probe result contains `location.href`; it is passed through [DshUrlRedaction]
 * before it reaches logcat. The token entry is never probed because the host only calls this for a
 * clean same-origin page (no launch token in the URL).
 */
internal class DshWebDiagnostics(private val enabled: Boolean) {

    /** Best-effort document-start hook; runs in the page's main world via WebView.evaluateJavascript. */
    fun installErrorCapture(webView: WebView?) {
        if (!enabled) return
        val target = webView ?: return
        runCatching { target.evaluateJavascript(JS_INSTALL_ERROR_CAPTURE, null) }
    }

    /**
     * Reads a bounded, no-secret summary of the rendered SPA state and logs it at WARN so a
     * diagnostic build surfaces it without adb logcat level filtering.
     */
    fun logProbe(webView: WebView?) {
        if (!enabled) return
        val target = webView ?: return
        runCatching {
            target.evaluateJavascript(JS_PROBE) { result ->
                // `result` is a JSON-encoded JS string and may contain location.href.
                Log.w(TAG, "probe " + DshUrlRedaction.redactText(result))
            }
        }
    }

    companion object {
        const val TAG = "DshWebView"

        fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** `window.__mcaDshErrors` collector for JS errors and unhandled rejections. */
        const val JS_INSTALL_ERROR_CAPTURE =
            "window.__mcaDshErrors=[];" +
                "addEventListener('error',e=>{try{window.__mcaDshErrors.push(String(e.message)+' @ '+String(e.filename)+':'+e.lineno)}catch(_){}});" +
                "addEventListener('unhandledrejection',e=>{try{window.__mcaDshErrors.push('REJ '+String(e.reason))}catch(_){}});"

        /** Minimal DOM readiness probe; no cookies, tokens, storage contents or page text. */
        const val JS_PROBE =
            "(function(){try{var r=document.getElementById('root');" +
                "return JSON.stringify({href:location.href,title:document.title,readyState:document.readyState," +
                "root:!!r,rootChildren:r?r.children.length:-1,rootHTML:r?r.innerHTML.length:-1," +
                "bodyText:(document.body.innerText||'').length,errors:window.__mcaDshErrors||[]});" +
                "}catch(e){return 'PROBE_ERR:'+e}})()"
    }
}
