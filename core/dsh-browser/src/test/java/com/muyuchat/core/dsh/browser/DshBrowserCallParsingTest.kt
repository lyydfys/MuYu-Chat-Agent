package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DshBrowserCallParsingTest {

    private fun parsed(body: String): DshBrowserCall = when (val result = DshBrowserCallParser.parse(body)) {
        is DshBrowserCallResult.Parsed -> result.call
        is DshBrowserCallResult.Invalid -> {
            fail("expected a parsed call, got failure ${result.failure.reason}")
            error("unreachable")
        }
    }

    private fun failure(body: String): DshBrowserFailure = when (val result = DshBrowserCallParser.parse(body)) {
        is DshBrowserCallResult.Invalid -> result.failure
        is DshBrowserCallResult.Parsed -> {
            fail("expected a rejection, got ${result.call.op}")
            error("unreachable")
        }
    }

    @Test
    fun `parses every wire operation into its typed arguments`() {
        val caps = parsed("""{"op":"caps"}""")
        assertEquals(DshBrowserOp.CAPS, caps.op)
        assertTrue(caps.args is DshBrowserArgs.Caps)

        val open = parsed("""{"op":"open","args":{"url":"https://example.com","newTab":true}}""")
        assertEquals(DshBrowserArgs.Open("https://example.com", true), open.args)

        assertTrue(parsed("""{"op":"tabs.list"}""").args is DshBrowserArgs.TabsList)

        val tabsNew = parsed("""{"op":"tabs.new","args":{"url":"https://a.example","front":false}}""")
        assertEquals(DshBrowserArgs.TabsNew("https://a.example", false, null), tabsNew.args)

        val switch = parsed("""{"op":"tabs.switch","args":{"tabId":"t2"}}""")
        assertEquals(DshBrowserArgs.TabsSwitch("t2"), switch.args)

        assertEquals(DshBrowserArgs.TabsClose(null), parsed("""{"op":"tabs.close"}""").args)

        val snapshot = parsed("""{"op":"snapshot","args":{"since":3,"includeText":true}}""")
        assertEquals(DshBrowserArgs.Snapshot(3L, true), snapshot.args)

        val find = parsed("""{"op":"find","args":{"query":"Go","role":"button","limit":7}}""")
        assertEquals(DshBrowserArgs.Find("Go", "button", 7), find.args)

        val click = parsed("""{"op":"click","args":{"ref":"r0000002","tabId":"t1"}}""")
        assertEquals(DshBrowserArgs.Click("r0000002", "t1"), click.args)

        val type = parsed("""{"op":"type","args":{"ref":"r0000001","text":"hello"}}""")
        assertEquals(DshBrowserArgs.TypeAction("r0000001", "hello", true, null), type.args)

        val read = parsed("""{"op":"read"}""")
        assertEquals(DshBrowserArgs.Read(null, null), read.args)

        val nav = parsed("""{"op":"nav","args":{"op":"back"}}""")
        assertEquals(DshBrowserArgs.Nav(DshBrowserNavOp.BACK), nav.args)

        val scroll = parsed("""{"op":"scroll","args":{"direction":"bottom"}}""")
        assertEquals(
            DshBrowserArgs.Scroll(DshBrowserScrollDirection.BOTTOM, DshBrowserLimits.DEFAULT_SCROLL_AMOUNT),
            scroll.args
        )
    }

    @Test
    fun `uses the official package timeout budget when none is sent`() {
        assertEquals(40_000L, parsed("""{"op":"open","args":{"url":"https://example.com"}}""").timeoutMillis)
        assertEquals(8_000L, parsed("""{"op":"caps"}""").timeoutMillis)
        assertEquals(10_000L, parsed("""{"op":"scroll","args":{"direction":"down"}}""").timeoutMillis)
    }

    @Test
    fun `accepts an explicit timeout inside the contract range and null as absent`() {
        assertEquals(15_000L, parsed("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":15000}""").timeoutMillis)
        assertEquals(40_000L, parsed("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":null}""").timeoutMillis)
    }

    @Test
    fun `rejects an out of range or fractional timeout`() {
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":10}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":999999}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":1500.5}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":"https://example.com"},"timeout_ms":"1000"}""").reason.wire)
    }

    @Test
    fun `rejects an unknown operation with the official bad-op reason`() {
        val result = failure("""{"op":"screenshot"}""")
        assertEquals(DshBrowserFailureReason.BAD_OP, result.reason)
        assertEquals("bad-op", result.reason.wire)
    }

    @Test
    fun `rejects an unknown envelope field`() {
        assertEquals("bad-args", failure("""{"op":"caps","url":"https://example.com"}""").reason.wire)
    }

    @Test
    fun `rejects an undeclared per operation argument`() {
        val result = failure("""{"op":"open","args":{"url":"https://example.com","tabId":"t1"}}""")
        assertEquals("bad-args", result.reason.wire)
        assertTrue(result.hint!!.contains("args.tabId"))
    }

    @Test
    fun `rejects wrong JSON types and blank required strings`() {
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":42}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"open","args":{"url":"https://example.com","newTab":"yes"}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"find","args":{"query":"   "}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"click","args":{"ref":""}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"nav","args":{"op":"jump"}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"scroll","args":{"direction":"left"}}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"snapshot","args":{"since":-1}}""").reason.wire)
    }

    @Test
    fun `requires a JSON object body`() {
        assertEquals("bad-args", failure("").reason.wire)
        assertEquals("bad-args", failure("not json").reason.wire)
        assertEquals("bad-args", failure("[]").reason.wire)
        assertEquals("too-large", failure("{\"op\":\"" + "x".repeat(DshBrowserLimits.MAX_REQUEST_BYTES) + "\"}").reason.wire)
    }

    @Test
    fun `accepts empty text because it means clear the field`() {
        val call = parsed("""{"op":"type","args":{"ref":"r0000001","text":"","replace":false}}""")
        assertEquals(DshBrowserArgs.TypeAction("r0000001", "", false, null), call.args)
    }

    @Test
    fun `bounds find limit and scroll amount instead of forwarding unbounded numbers`() {
        val find = parsed("""{"op":"find","args":{"query":"Go","limit":999}}""").args as DshBrowserArgs.Find
        assertEquals(DshBrowserLimits.MAX_FIND_LIMIT, find.limit)
        val findZero = parsed("""{"op":"find","args":{"query":"Go","limit":0}}""").args as DshBrowserArgs.Find
        assertEquals(1, findZero.limit)
        val scroll = parsed("""{"op":"scroll","args":{"direction":"down","amount":999999}}""").args
            as DshBrowserArgs.Scroll
        assertEquals(DshBrowserLimits.MAX_SCROLL_AMOUNT, scroll.amount)
    }

    @Test
    fun `tolerates the tabId the official package forwards to every tabs sub op`() {
        val newTab = parsed("""{"op":"tabs.new","args":{"url":"https://a.example","tabId":"t1"}}""").args
            as DshBrowserArgs.TabsNew
        assertEquals("t1", newTab.tabId)
        assertEquals("https://a.example", newTab.url)
    }

    @Test
    fun `validates the optional request id shape`() {
        assertEquals(DshBrowserOp.CAPS, parsed("""{"op":"caps","request_id":"req-123"}""").op)
        assertEquals("bad-args", failure("""{"op":"caps","request_id":7}""").reason.wire)
        assertEquals("bad-args", failure("""{"op":"caps","request_id":""}""").reason.wire)
    }

    @Test
    fun `does not echo caller controlled values in a malformed request`() {
        val result = failure("{\"op\":\"click\",\"args\":{\"ref\":\"" + "ALERT".repeat(10) + "\"}}")
        assertTrue(result.message.length <= 200)
        assertTrue(!result.message.contains("ALERT"))
        assertTrue(!result.hint.orEmpty().contains("ALERT"))
    }
}
