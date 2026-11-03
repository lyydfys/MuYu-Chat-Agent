package com.muyuchat.feature.dsh.workspace.web

import android.webkit.WebView
import com.muyuchat.feature.dsh.workspace.ui.dshWideWindowMinDp

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
 * This shim therefore does three things, on every page start/finish and on resize/orientation:
 * 1. writes the measured viewport height as an explicit pixel height on `html`;
 * 2. rewrites same-origin stylesheet declarations that use `vh`/`dvh` into pixel values derived
 *    from the measured viewport, keeping the original declaration in a WeakMap so rotation can
 *    recompute from the original rather than from an already-rewritten value;
 * 3. on **wide** windows only (canvas/screen width >= [dshWideWindowMinDp], the app-wide two-pane
 *    threshold), normalises a `<meta name="viewport">` that pins a phone pixel width
 *    (`width=390`, `width=412`, …) or omits `width` to `width=device-width`, so an unfolded
 *    foldable gets the real layout viewport instead of a 390 dp phone column. Other
 *    declarations (`initial-scale`, `maximum-scale`, `viewport-fit`, order) are preserved and
 *    no `user-scalable` patch is added, so pinch zoom stays available exactly as before. On
 *    COMPACT windows the official declaration is not touched at all: the phone sees the page
 *    the official server served. The gate reads `max(window.innerWidth, screen.width)` because a
 *    pinned declaration makes `innerWidth` report the pinned width instead of the canvas.
 *
 * Known limitation: rewriting the declaration after the document has laid out does not re-run
 * layout in Chromium; the normalised value applies to the next load. The official page already
 * declares `width=device-width`, so this only repairs a future/forked declaration that pins a
 * phone width — it does not enlarge the current live document.
 *
 * It adds no JS bridge, reads/writes no DSH state, never touches token/cookie data, and only
 * touches same-origin stylesheets (cross-origin access throws and is skipped).
 */
internal object DshPageLayoutShim {

    private const val WIDE_MIN_PLACEHOLDER = "__MCA_WIDE_MIN_DP__"

    private const val SCRIPT_TEMPLATE = """
        (function () {
          var WIDE_MIN = __MCA_WIDE_MIN_DP__;
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
          function rewriteViewportWidth(content) {
            var tokens = [];
            var parts = String(content || '').split(',');
            for (var i = 0; i < parts.length; i++) {
              var token = parts[i].trim();
              if (token) tokens.push(token);
            }
            if (tokens.length === 0) return 'width=device-width';
            var index = -1;
            for (var j = 0; j < tokens.length; j++) {
              var key = tokens[j].split('=')[0].trim().toLowerCase();
              if (key === 'width') { index = j; break; }
            }
            if (index < 0) return 'width=device-width, ' + tokens.join(', ');
            var value = tokens[index].indexOf('=') >= 0
              ? tokens[index].substring(tokens[index].indexOf('=') + 1).trim()
              : '';
            if (value.toLowerCase() === 'device-width') return null;
            tokens[index] = 'width=device-width';
            return tokens.join(', ');
          }
          function normalizeViewport() {
            try {
              // A viewport meta that already pins a pixel width makes window.innerWidth report that
              // pinned width, not the canvas MCA gave the WebView. screen.width is the device CSS
              // width and is not rewritten by the declaration, so the gate takes the larger of the
              // two: a pinned 390 cannot mask an unfolded 840 canvas, and a real phone (both values
              // in the 360-430 range) is still classified as narrow.
              var layoutWidth = window.innerWidth || 0;
              var screenWidth = (window.screen && window.screen.width) || 0;
              var width = Math.max(layoutWidth, screenWidth);
              if (!(width >= WIDE_MIN)) return;
              var metas = document.getElementsByTagName('meta');
              for (var i = 0; i < metas.length; i++) {
                var meta = metas[i];
                var name = (meta.getAttribute('name') || '').toLowerCase();
                if (name !== 'viewport') continue;
                var content = meta.getAttribute('content') || '';
                var next = rewriteViewportWidth(content);
                if (next !== null && next !== content) meta.setAttribute('content', next);
              }
            } catch (e) { /* never break the official page */ }
          }
          function apply() {
            var height = measuredHeight();
            if (height <= 0) return;
            try {
              var doc = document.documentElement;
              if (doc) doc.style.height = height + 'px';
            } catch (e) { /* ignore */ }
            fixRules(height);
            normalizeViewport();
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

    /** Threshold injected from the app-wide breakpoint ladder, never a second `600` literal. */
    private val SCRIPT: String =
        SCRIPT_TEMPLATE.replace(WIDE_MIN_PLACEHOLDER, dshWideWindowMinDp.toString())

    fun apply(view: WebView?) {
        view ?: return
        runCatching { view.evaluateJavascript(SCRIPT, null) }
    }
}
