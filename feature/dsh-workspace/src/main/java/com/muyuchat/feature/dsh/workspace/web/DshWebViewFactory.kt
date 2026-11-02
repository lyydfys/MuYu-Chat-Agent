package com.muyuchat.feature.dsh.workspace.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Production settings for the official DSH page.
 *
 * Everything here is the minimum the official Web profile needs. Each switch that grants a
 * capability is called out:
 *
 * - JavaScript and DOM storage are required by the official frontend (SPA + local client state).
 * - File/content access, mixed content, file:// universal access, popups and geolocation are off.
 * - Third-party cookies are off; the DSH HttpOnly cookie is first-party for `127.0.0.1`.
 * - Password saving is off; the DSH auth is a cookie minted by the runtime, not a form login.
 * - `textZoom` is deliberately *not* set. Chromium scales WebView text by the system font scale by
 *   default; calling setTextZoom would override 2.0x accessibility font sizes for the page.
 * - Dark mode is not forced. The app ships `values-night` theme variants, and WebView's
 *   `prefers-color-scheme` follows the activity theme; forcing algorithmic darkening would invert
 *   the official page's own colour design.
 * - The user agent is left at the WebView default so the official responsive layout sees a normal
 *   mobile Chrome-family UA.
 */
internal object DshWebViewFactory {

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(
        context: Context,
        backgroundColor: Int,
        webViewClient: WebViewClient,
        webChromeClient: WebChromeClient,
        downloadListener: DownloadListener
    ): WebView {
        // Diagnostic APKs only: expose this WebView to chrome://inspect so the blank-pane issue can
        // be inspected live. Release builds keep the platform default (debugging off).
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        val view = WebView(context)
        view.setBackgroundColor(backgroundColor)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            // The official page uses window.open / target="_blank" for external links. Android
            // WebView silently drops those navigations unless multiple windows are enabled AND
            // onCreateWindow is implemented; DshWebChromeClient intercepts each popup as one URL
            // through the origin fence and never displays a second window.
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            // The official page carries a device-width viewport meta; overview mode keeps the
            // layout viewport tied to the WebView's measured size. With it disabled, this Compose
            // host resolved 100vh against a zero-height initial containing block while still
            // reporting the real innerHeight (verified through the WebView DevTools protocol).
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            defaultTextEncodingName = "UTF-8"
            @Suppress("DEPRECATION")
            savePassword = false
        }
        // Only the page's own origin may set first-party cookies; never third-party ones.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webViewClient = webViewClient
        view.webChromeClient = webChromeClient
        view.setDownloadListener(downloadListener)
        return view
    }
}
