package com.muyuchat.feature.dsh.workspace.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The injected script is a hardened read-only observer: no network, no evaluation, no page writes.
 */
class DshReplyDomObserverTest {

    @Test
    fun `the script is a mutation observer over new text nodes`() {
        val script = DshReplyDomObserver.SCRIPT

        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("childList: true"))
        assertTrue(script.contains("characterData: true"))
        assertTrue(script.contains("subtree: true"))
        assertTrue(script.contains("document.activeElement"))
    }

    @Test
    fun `the script calls the single injected bridge method`() {
        val script = DshReplyDomObserver.SCRIPT

        assertTrue(script.contains("mcaReplyProbe"))
        assertTrue(script.contains("bridge.accept("))
        assertEquals("mcaReplyProbe", DshReplyDomObserver.INTERFACE_NAME)
    }

    @Test
    fun `the script never performs network or evaluation`() {
        val script = DshReplyDomObserver.SCRIPT

        assertFalse("no fetch", script.contains("fetch("))
        assertFalse("no XHR", script.contains("XMLHttpRequest"))
        assertFalse("no eval", script.contains("eval("))
        assertFalse("no cookies", script.contains("document.cookie"))
        assertFalse("no storage writes", script.contains("localStorage.setItem"))
    }

    @Test
    fun `the script skips non-content elements`() {
        val script = DshReplyDomObserver.SCRIPT

        assertTrue(script.contains("SCRIPT"))
        assertTrue(script.contains("STYLE"))
        assertTrue(script.contains("NOSCRIPT"))
    }

    @Test
    fun `the script is installed at most once per document`() {
        val script = DshReplyDomObserver.SCRIPT

        assertTrue(script.contains("__mcaReplyProbeOn"))
        assertTrue("page unload resets the native accumulator", script.contains("pagehide"))
        assertTrue(script.contains("'|0|1'"))
    }
}
