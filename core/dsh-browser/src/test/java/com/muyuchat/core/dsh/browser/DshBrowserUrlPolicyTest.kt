package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DshBrowserUrlPolicyTest {

    private fun blockReason(url: String): DshBrowserBlockReason? {
        val decision = DshBrowserUrlPolicy.evaluate(url)
        return if (decision is DshBrowserUrlDecision.Block) decision.reason else null
    }

    private fun assertAllowed(url: String) {
        val decision = DshBrowserUrlPolicy.evaluate(url)
        assertEquals("expected $url to be allowed, got $decision", DshBrowserUrlDecision.Allow(url), decision)
    }

    @Test
    fun `allows http and https with ports paths and queries`() {
        assertAllowed("https://example.com")
        assertAllowed("http://example.com/")
        assertAllowed("https://example.com:8443/path?q=1#frag")
        assertAllowed("https://sub.domain.example.org/a/b?c=d")
        assertAllowed("https://xn--bcher-kva.example/")
        assertAllowed("https://[2001:db8::1]/")
        assertAllowed("http://8.8.8.8/")
    }

    @Test
    fun `refuses every non http scheme`() {
        listOf(
            "file:///sdcard/secret",
            "content://settings/system",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/uuid",
            "intent://scan/#Intent;scheme=zxing;end",
            "chrome://settings",
            "ftp://example.com"
        ).forEach { url ->
            assertEquals("scheme for $url", DshBrowserBlockReason.SCHEME, blockReason(url))
        }
    }

    @Test
    fun `about blank is reserved for the engine blank page`() {
        assertEquals(DshBrowserBlockReason.ABOUT_BLANK, blockReason("about:blank"))
        val decision = DshBrowserUrlPolicy.evaluate("about:blank", allowInternalBlank = true)
        assertEquals(DshBrowserUrlDecision.Allow("about:blank"), decision)
        assertEquals(true, DshBrowserUrlPolicy.isInternalBlank("about:blank"))
        assertEquals(false, DshBrowserUrlPolicy.isInternalBlank("https://example.com"))
    }

    @Test
    fun `refuses userinfo backslash whitespace and control characters`() {
        assertEquals(DshBrowserBlockReason.USERINFO, blockReason("https://user:pass@example.com/"))
        assertEquals(DshBrowserBlockReason.USERINFO, blockReason("https://user@example.com/"))
        assertEquals(DshBrowserBlockReason.BACKSLASH, blockReason("https://example.com\\@127.0.0.1/"))
        assertEquals(DshBrowserBlockReason.CONTROL_CHARACTER, blockReason("https://exa mple.com/"))
        assertEquals(DshBrowserBlockReason.CONTROL_CHARACTER, blockReason("https://example.com/\u0000"))
        assertEquals(DshBrowserBlockReason.EMPTY, blockReason("   "))
        assertEquals(DshBrowserBlockReason.TOO_LONG, blockReason("https://example.com/" + "a".repeat(3000)))
    }

    @Test
    fun `refuses loopback hosts in every literal spelling`() {
        listOf(
            "http://localhost/",
            "http://localhost:3081/notify",
            "http://localhost./",
            "http://app.localhost/",
            "http://127.0.0.1/",
            "http://127.0.0.1:3081/browser",
            "http://127.1/",
            "http://2130706433/",
            "http://0177.0.0.1/",
            "http://0x7f000001/",
            "http://0x7f.0.0.1/",
            "http://[::1]/",
            "http://[::1]:3081/",
            "http://[0:0:0:0:0:0:0:1]/",
            "http://[::ffff:127.0.0.1]/"
        ).forEach { url ->
            assertEquals("loopback for $url", DshBrowserBlockReason.LOOPBACK, blockReason(url))
        }
    }

    @Test
    fun `refuses private link-local cgnat and reserved address space`() {
        listOf(
            "http://10.1.2.3/",
            "http://172.16.0.1/",
            "http://172.31.255.254/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/",
            "http://198.18.0.1/",
            "http://192.0.0.9/",
            "http://224.0.0.1/",
            "http://255.255.255.255/",
            "http://[fe80::1]/",
            "http://[fc00::1]/",
            "http://[fd12:3456::1]/",
            "http://[ff02::1]/"
        ).forEach { url ->
            assertEquals("private for $url", DshBrowserBlockReason.PRIVATE_ADDRESS, blockReason(url))
        }
        assertAllowed("http://172.32.0.1/")
        assertAllowed("http://100.128.0.1/")
        assertAllowed("http://11.0.0.1/")
    }

    @Test
    fun `refuses single label and local network names`() {
        listOf(
            "http://intranet/",
            "http://printer.local/",
            "http://nas.lan/",
            "http://box.internal/",
            "http://router.home/"
        ).forEach { url ->
            assertEquals("local name for $url", DshBrowserBlockReason.LOCAL_NAME, blockReason(url))
        }
    }

    @Test
    fun `refuses non ascii hosts because only punycode should reach the engine`() {
        assertEquals(DshBrowserBlockReason.NON_ASCII_HOST, blockReason("https://例子.测试/"))
    }

    @Test
    fun `refuses malformed authorities and ports`() {
        assertEquals(DshBrowserBlockReason.AUTHORITY, blockReason("https:///path"))
        assertEquals(DshBrowserBlockReason.AUTHORITY, blockReason("https://:8080/"))
        assertEquals(DshBrowserBlockReason.BAD_PORT, blockReason("https://example.com:0/"))
        assertEquals(DshBrowserBlockReason.BAD_PORT, blockReason("https://example.com:70000/"))
    }

    @Test
    fun `redirects are re-validated with the same admission and projected as a typed failure`() {
        assertEquals(
            DshBrowserUrlDecision.Allow("https://example.com/next"),
            DshBrowserUrlPolicy.evaluateRedirect("https://example.com/next")
        )
        val blocked = DshBrowserUrlPolicy.evaluateRedirect("http://127.0.0.1:3081/browser")
        assertEquals(DshBrowserBlockReason.LOOPBACK, (blocked as DshBrowserUrlDecision.Block).reason)
        val failure = blocked.failureOrNull()
        assertNotNull(failure)
        assertEquals(DshBrowserFailureReason.BLOCKED_URL, failure!!.reason)
        assertNull(DshBrowserUrlPolicy.evaluate("https://example.com").failureOrNull())
    }
}
