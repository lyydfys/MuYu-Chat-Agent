package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshBrowserProcessPolicyTest {

    @Test
    fun `an isolated ready build reports the browser process and the session profile rules`() {
        val state = DshBrowserProcessPolicy.decide(
            processDeclared = true,
            overlayGranted = true,
            engineAvailable = true
        )
        assertEquals(DshBrowserProcessMode.ISOLATED, state.mode)
        assertEquals(":browser", state.processName)
        assertTrue(state.processIsolation)
        assertTrue(state.canHostWebView)
        assertTrue(state.overlayAvailable)
        assertNull(state.reason)

        assertEquals(":browser", DshBrowserProcessPolicy.PROCESS_NAME)
        assertEquals("dsh-browser", DshBrowserProcessPolicy.DATA_DIRECTORY_SUFFIX)
        assertEquals(
            "com.muyuchat.core.dsh.browser.DshBrowserService",
            DshBrowserProcessPolicy.SERVICE_CLASS
        )
        assertEquals("android.permission.SYSTEM_ALERT_WINDOW", DshBrowserProcessPolicy.OVERLAY_PERMISSION)
        assertTrue(DshBrowserProcessPolicy.SESSION_COOKIES_ONLY)
        assertFalse(DshBrowserProcessPolicy.ALLOW_THIRD_PARTY_COOKIES)
    }

    @Test
    fun `a declared process without the overlay permission degrades instead of silently sharing`() {
        val state = DshBrowserProcessPolicy.decide(
            processDeclared = true,
            overlayGranted = false,
            engineAvailable = true
        )
        assertEquals(DshBrowserProcessMode.DECLARED_UNAVAILABLE, state.mode)
        assertFalse(state.processIsolation)
        assertFalse(state.canHostWebView)
        assertTrue(state.reason!!.contains("overlay"))
    }

    @Test
    fun `a declared process without a WebView provider degrades with its own reason`() {
        val state = DshBrowserProcessPolicy.decide(
            processDeclared = true,
            overlayGranted = true,
            engineAvailable = false
        )
        assertEquals(DshBrowserProcessMode.DECLARED_UNAVAILABLE, state.mode)
        assertFalse(state.canHostWebView)
        assertTrue(state.reason!!.contains("WebView"))
    }

    @Test
    fun `a build without the process declaration is reported as shared not isolated`() {
        val state = DshBrowserProcessPolicy.decide(
            processDeclared = false,
            overlayGranted = true,
            engineAvailable = true
        )
        assertEquals(DshBrowserProcessMode.SHARED, state.mode)
        assertNull(state.processName)
        assertFalse(state.processIsolation)
        assertFalse(state.canHostWebView)
        assertTrue(state.reason!!.contains("not declared"))
    }
}
