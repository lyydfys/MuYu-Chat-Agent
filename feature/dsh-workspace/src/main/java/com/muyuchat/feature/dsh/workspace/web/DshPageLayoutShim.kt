package com.muyuchat.feature.dsh.workspace.web

import android.webkit.WebView

/**
 * Android WebView layout compensation for the official DSH page.
 *
 * On the MCA Compose host the official document can be laid out with a zero-height initial
 * containing block: `window.innerHeight` and `documentElement.clientHeight` report the real
 * viewport, but `100vh`/`100dvh` resolve to 0 and the document's `html, body, #root
 * { height: 100% }` chain collapses. Fixed-position layers still paint, which made a fully
 * mounted page look like a blank main pane. The same state was observed through the WebView
 * DevTools protocol: `html`/`body`/`#root` heights 0, `100vh` 0; an explicit pixel height on
 * `html` immediately restored the official hero/composer, and CSS rules using viewport units
 * (for example the model menu's `max-height: calc(100vh - …)`) still collapsed to 0.
 *
 * This shim therefore does two things, on every page start/finish and on resize/orientation:
 * 1. writes the measured viewport height as an explicit pixel height on `html`;
 * 2. rewrites same-origin stylesheet declarations that use `vh`/`dvh` into pixel values derived
 *    from the measured viewport, keeping the original declaration in a WeakMap so rotation can
 *    recompute from the original rather than from an already-rewritten value.
 *
 * It adds no JS bridge, reads/writes no DSH state, never touches token/cookie data, and only
 * touches same-origin stylesheets (cross-origin access throws and is skipped).
 */
internal object DshPageLayoutShim {

    private const val SCRIPT = """
        (function () {
          function measuredHeight() {
            try {
              var doc = document.documentElement;
              if (!doc) return 0;
              return Math.max(window.innerHeight || 0, doc.clientHeight || 0);
            } catch (e) { return 0; }
          }
          function fixRules(height) {
            try {
              var originals = window.__mcaVhOriginals;
              if (!originals) {
                originals = new WeakMap();
                window.__mcaVhOriginals = originals;
              }
              var sheets = document.styleSheets || [];
              for (var s = 0; s < sheets.length; s++) {
                var rules;
                try { rules = sheets[s].cssRules; } catch (e) { continue; }
                walk(rules, height, originals);
              }
            } catch (e) { /* never break the official page */ }
          }
          function walk(rules, height, originals) {
            if (!rules) return;
            for (var i = 0; i < rules.length; i++) {
              var rule = rules[i];
              try {
                if (rule.style) {
                  var current = rule.style.cssText || '';
                  var original = originals.get(rule.style);
                  if (original === undefined) {
                    if (current.indexOf('vh') === -1) continue;
                    original = current;
                    originals.set(rule.style, original);
                  }
                  if (original.indexOf('vh') === -1) continue;
                  var rewritten = original.replace(/(\d*\.?\d+)(d?vh)/g, function (match, number) {
                    return (parseFloat(number) * height / 100).toFixed(2) + 'px';
                  });
                  if (rewritten !== current) rule.style.cssText = rewritten;
                } else if (rule.cssRules) {
                  walk(rule.cssRules, height, originals);
                }
              } catch (e) { /* skip one unreadable rule */ }
            }
          }
          function apply() {
            var height = measuredHeight();
            if (height <= 0) return;
            try {
              var doc = document.documentElement;
              if (doc) doc.style.height = height + 'px';
            } catch (e) { /* ignore */ }
            fixRules(height);
          }
          apply();
          if (!window.__mcaDshViewportShim) {
            window.__mcaDshViewportShim = true;
            window.addEventListener('resize', apply);
            window.addEventListener('orientationchange', apply);
            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', apply);
            }
            setTimeout(apply, 120);
            setTimeout(apply, 600);
          }
        })();
    """

    fun apply(view: WebView?) {
        view ?: return
        runCatching { view.evaluateJavascript(SCRIPT, null) }
    }
}
