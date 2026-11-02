package com.muyuchat.feature.dsh.workspace.web

import android.webkit.CookieManager
import com.muyuchat.feature.dsh.workspace.DshCookiePurge
import com.muyuchat.feature.dsh.workspace.DshOrigin

/**
 * WebView session hygiene across runtime generations.
 *
 * A DSH generation owns a unique random port, so its origin (and therefore its WebView cookie
 * jar/storage partition) differs after every restart. On an epoch change the old origin's cookies
 * are explicitly expired. DOM storage/IndexedDB for the old origin is unreachable by the new
 * origin; this feature deliberately does not call `WebStorage.deleteAllData()` because that would
 * also clear unrelated cookie/storage state used by the chat internal browser, which is not this
 * feature's data to erase.
 */
internal object DshWebStorage {

    fun expireOriginCookies(origin: DshOrigin) {
        val cookieManager = CookieManager.getInstance()
        val header = runCatching { cookieManager.getCookie(origin.value) }.getOrNull()
        DshCookiePurge.expiryHeaders(header).forEach { expiry ->
            runCatching { cookieManager.setCookie(origin.value, expiry) }
        }
        runCatching { cookieManager.flush() }
    }
}
