package com.muyuchat.feature.dsh.workspace.web

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * Read-only DOM observer for the official DSH page.
 *
 * Why: when the page talks to the upstream account directly, MCA never sees the reply, so the
 * "DSH has an answer" bubble could not fire outside the MCA-gateway path. This probe watches the
 * rendered document and reports *text that newly appeared*, so the account path can raise the same
 * bubble as the gateway path.
 *
 * Guarantees:
 *  - it never writes to the page, never calls its APIs, never intercepts network traffic and never
 *    sends a request: the script only observes mutations, reads `document.activeElement` and calls the
 *    single injected bridge method;
 *  - the injected interface exposes exactly one method taking two strings (`text`, `control`), so the
 *    page gains no file/system/JS-execution capability;
 *  - it is installed only when the host passes a callback (the feature switch), and removed when the
 *    WebView is disposed;
 *  - all policy (minimum growth, silence window, truncation, re-arming) lives in [DshReplyDomGate];
 *    the script is a dumb batcher.
 */
internal object DshReplyDomObserver {

    /** Name the page sees: `window.mcaReplyProbe.accept(text, control)`. */
    const val INTERFACE_NAME: String = "mcaReplyProbe"

    /** Batches are flushed at most every 120 ms, so a fast stream is not reported token by token. */
    const val BATCH_DEBOUNCE_MILLIS: Int = 120

    /**
     * The injected script.
     *
     * `control` is `"<jsMillis>|<focus 0|1>|<reset 0|1>"`; the native side parses it defensively and
     * never trusts the page for policy decisions.
     */
    const val SCRIPT: String = """
        (function () {
          var bridge = window.mcaReplyProbe;
          if (!bridge || typeof bridge.accept !== 'function') { return; }
          if (window.__mcaReplyProbeOn) { return; }
          window.__mcaReplyProbeOn = true;
          var pending = '';
          var timer = null;
          function editing() {
            try {
              var el = document.activeElement;
              if (!el) { return false; }
              var tag = String(el.tagName || '').toUpperCase();
              if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') { return true; }
              return el.isContentEditable === true;
            } catch (e) { return false; }
          }
          function push(node) {
            try {
              if (!node) { return; }
              var text = node.nodeType === 3 ? node.nodeValue : node.textContent;
              if (!text) { return; }
              var tag = node.nodeName ? String(node.nodeName).toUpperCase() : '';
              if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') { return; }
              pending = pending + text;
              if (pending.length > 4000) { pending = pending.slice(-4000); }
            } catch (e) { }
          }
          function flush() {
            timer = null;
            if (!pending) { return; }
            var text = pending;
            pending = '';
            try {
              bridge.accept(text, String(Date.now()) + '|' + (editing() ? '1' : '0') + '|0');
            } catch (e) { }
          }
          function schedule() {
            if (timer !== null) { return; }
            timer = setTimeout(flush, 120);
          }
          var observer = new MutationObserver(function (records) {
            try {
              if (editing()) { return; }
              for (var i = 0; i < records.length; i++) {
                var record = records[i];
                if (record.type === 'characterData') {
                  push(record.target);
                } else {
                  for (var j = 0; j < record.addedNodes.length; j++) {
                    push(record.addedNodes[j]);
                  }
                }
              }
              if (pending) { schedule(); }
            } catch (e) { }
          });
          try {
            observer.observe(document.body, { childList: true, characterData: true, subtree: true });
          } catch (e) { return; }
          window.addEventListener('pagehide', function () {
            pending = '';
            if (timer !== null) { clearTimeout(timer); timer = null; }
            try { bridge.accept('', String(Date.now()) + '|0|1'); } catch (e) { }
          });
        })();
    """

    /** Adds the single-interface bridge. Safe to call once per WebView creation. */
    fun install(view: WebView?, onProbe: (text: String, control: String) -> Unit) {
        view ?: return
        runCatching { view.addJavascriptInterface(DshReplyProbeBridge(onProbe), INTERFACE_NAME) }
    }

    /** Removes the bridge before the view is destroyed (idempotent). */
    fun detach(view: WebView?) {
        view ?: return
        runCatching { view.removeJavascriptInterface(INTERFACE_NAME) }
    }

    /** Injects the observer after a clean page finish. */
    fun inject(view: WebView?) {
        view ?: return
        runCatching { view.evaluateJavascript(SCRIPT, null) }
    }
}

/**
 * The only object exposed to the page.
 *
 * Two strings in, nothing out; the callback is invoked on the WebView's background bridge thread, so
 * the host must hop to the main thread before touching UI state.
 */
internal class DshReplyProbeBridge(
    private val onProbe: (text: String, control: String) -> Unit
) {

    @JavascriptInterface
    fun accept(text: String?, control: String?) {
        val safeText = text.orEmpty().take(DshReplyDomLimits.MAX_BATCH_CHARS)
        val safeControl = control.orEmpty().take(64)
        runCatching { onProbe(safeText, safeControl) }
    }
}
