package com.muyuchat.core.dsh.browser

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshBrowserWireShapeTest {

    private fun request(body: String, method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST) =
        DshCapabilityRouteRequest(
            path = DshBrowserRouteAdapter.ROUTE_PATH,
            method = method,
            query = emptyMap(),
            bodyJson = body,
            localToken = null,
            generation = 1L,
            receivedAtMillis = 0L
        )

    private fun bodyOf(response: DshCapabilityRouteResponse): JSONObject =
        JSONObject((response as DshCapabilityRouteResponse.Json).bodyJson)

    @Test
    fun `caps envelope carries the declared fields and no secret`() = runTest {
        val handler = DshBrowserRequestHandler(DshBrowserSession(FakeBrowserEngine()))
        val json = JSONObject(handler.handleBody("""{"op":"caps"}"""))
        assertTrue(json.getBoolean("ok"))
        assertEquals("browser", json.getString("surface"))
        assertEquals("stable-fingerprint", json.getString("refScheme"))
        assertEquals(DshBrowserLimits.MAX_NODES, json.getInt("maxNodes"))
        assertEquals(DshBrowserLimits.MAX_NAME_CHARS, json.getInt("maxName"))
        assertEquals(DshBrowserLimits.MAX_TEXT_CHARS, json.getInt("maxText"))
        assertEquals(DshBrowserLimits.MAX_TABS, json.getInt("maxTabs"))
        assertTrue(json.getJSONArray("notes").length() > 0)
        assertTrue(json.getBoolean("processIsolation"))
        assertFalse(json.getBoolean("overlayRequired"))
        assertEquals("http-https-only", json.getString("urlPolicy"))
        val flat = json.toString().lowercase()
        assertFalse(json.has("token"))
        assertFalse(json.has("cookie"))
        assertFalse(flat.contains("x-dsh-token"))
        assertFalse(flat.contains("authorization"))
    }

    @Test
    fun `open snapshot and click envelopes use the official field names and types`() = runTest {
        val handler = DshBrowserRequestHandler(DshBrowserSession(FakeBrowserEngine()))

        val opened = JSONObject(
            handler.handleBody("""{"op":"open","args":{"url":"https://example.com"}}""")
        )
        assertTrue(opened.getBoolean("ok"))
        assertEquals("t1", opened.getString("tabId"))
        assertEquals("https://example.com", opened.getString("url"))
        assertEquals("Fixture", opened.getString("title"))
        assertEquals(1, opened.getInt("pageGeneration"))
        assertEquals("ready", opened.getString("loadState"))
        assertTrue(opened.getBoolean("ready"))

        val snapshot = JSONObject(handler.handleBody("""{"op":"snapshot","args":{}}"""))
        assertTrue(snapshot.getBoolean("ok"))
        assertTrue(snapshot.getBoolean("full"))
        assertEquals(4, snapshot.getInt("nodeCount"))
        assertEquals("stable-fingerprint", snapshot.getString("refScheme"))
        assertEquals(1080, snapshot.getJSONObject("viewport").getInt("width"))
        val firstNode = snapshot.getJSONArray("nodes").getJSONObject(0)
        assertEquals("r0000001", firstNode.getString("ref"))
        assertEquals("textbox", firstNode.getString("role"))
        assertEquals("Search", firstNode.getString("name"))
        assertEquals(4, firstNode.getJSONArray("bounds").length())
        assertTrue(firstNode.getBoolean("inView"))
        assertFalse(firstNode.getBoolean("disabled"))
        assertFalse(firstNode.getBoolean("dup"))

        val clicked = JSONObject(
            handler.handleBody("""{"op":"click","args":{"ref":"r0000002"}}""")
        )
        assertTrue(clicked.getBoolean("ok"))
        assertEquals("r0000002", clicked.getString("ref"))
        assertTrue(clicked.getBoolean("changed"))
        assertEquals("js", clicked.getString("via"))
        assertEquals(1, clicked.getInt("fromGeneration"))
        assertEquals(2, clicked.getInt("toGeneration"))
        assertEquals(2, clicked.getInt("pageGeneration"))
        assertEquals("ready", clicked.getString("loadState"))
        assertEquals(2, clicked.getJSONArray("clickedAt").length())
        assertTrue(clicked.getJSONArray("newText").length() > 0)
    }

    @Test
    fun `a diff snapshot exposes full false and the true delta arrays`() = runTest {
        val engine = FakeBrowserEngine()
        val handler = DshBrowserRequestHandler(DshBrowserSession(engine))
        handler.handleBody("""{"op":"open","args":{"url":"https://example.com"}}""")
        val full = JSONObject(handler.handleBody("""{"op":"snapshot","args":{}}"""))
        val generation = full.getInt("pageGeneration")

        engine.page("t1").mutation += 1
        engine.page("t1").nodes.add(FakeBrowserEngine.node("r00000aa", "button", "Fresh"))
        val diff = JSONObject(handler.handleBody("""{"op":"snapshot","args":{"since":$generation}}"""))
        assertFalse(diff.getBoolean("full"))
        assertEquals(1, diff.getJSONArray("added").length())
        assertEquals("r00000aa", diff.getJSONArray("added").getJSONObject(0).getString("ref"))
        assertEquals(0, diff.getJSONArray("changed").length())
        assertEquals(0, diff.getJSONArray("removed").length())
        assertEquals(5, diff.getInt("nodeCount"))
        assertFalse(diff.has("nodes"))

        val unchanged = JSONObject(handler.handleBody("""{"op":"snapshot","args":{"since":1}}"""))
        assertTrue(unchanged.getBoolean("unchanged"))
        assertEquals(0, unchanged.getJSONArray("added").length())
        assertEquals(0, unchanged.getJSONArray("removed").length())
        assertEquals(5, unchanged.getInt("unchangedCount"))
    }

    @Test
    fun `find type read nav and scroll envelopes match the declared schemas`() = runTest {
        val handler = DshBrowserRequestHandler(DshBrowserSession(FakeBrowserEngine()))
        handler.handleBody("""{"op":"open","args":{"url":"https://example.com"}}""")

        val found = JSONObject(handler.handleBody("""{"op":"find","args":{"query":"Go"}}"""))
        assertTrue(found.getBoolean("ok"))
        assertEquals("Go", found.getString("query"))
        assertTrue(found.getInt("matched") >= 1)
        val candidate = found.getJSONArray("candidates").getJSONObject(0)
        assertEquals("r0000002", candidate.getString("ref"))
        assertEquals("button", candidate.getString("role"))

        val typed = JSONObject(
            handler.handleBody("""{"op":"type","args":{"ref":"r0000001","text":"hello"}}""")
        )
        assertEquals("r0000001", typed.getString("ref"))
        assertEquals("hello", typed.getString("value"))
        assertEquals("setter", typed.getString("via"))
        assertTrue(typed.getBoolean("dispatched"))

        val read = JSONObject(handler.handleBody("""{"op":"read"}"""))
        assertTrue(read.getBoolean("ok"))
        assertTrue(read.has("text"))
        assertTrue(read.has("chars"))
        assertFalse(read.getBoolean("truncated"))

        val scrolled = JSONObject(handler.handleBody("""{"op":"scroll","args":{"direction":"down"}}"""))
        assertTrue(scrolled.getBoolean("ok"))
        assertEquals(700, scrolled.getInt("scrollY"))
        assertEquals(1920, scrolled.getInt("innerHeight"))
        assertEquals(4000, scrolled.getInt("docHeight"))

        val nav = JSONObject(handler.handleBody("""{"op":"nav","args":{"op":"close"}}"""))
        assertEquals("close", nav.getString("op"))
        assertTrue(nav.getBoolean("closed"))
    }

    @Test
    fun `failure envelopes keep ok false reason error and bounded hint`() = runTest {
        val handler = DshBrowserRequestHandler(DshBrowserSession(FakeBrowserEngine()))
        val badOp = JSONObject(handler.handleBody("""{"op":"screenshot"}"""))
        assertFalse(badOp.getBoolean("ok"))
        assertEquals("bad-op", badOp.getString("reason"))
        assertTrue(badOp.getString("error").isNotBlank())

        handler.handleBody("""{"op":"open","args":{"url":"https://example.com"}}""")
        val blocked = JSONObject(handler.handleBody("""{"op":"open","args":{"url":"file:///etc/passwd"}}"""))
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("blocked-url", blocked.getString("reason"))
        assertTrue(blocked.getString("hint").contains("http"))

        val stale = JSONObject(handler.handleBody("""{"op":"click","args":{"ref":"r0000002"}}"""))
        assertFalse(stale.getBoolean("ok"))
        assertEquals("snapshot-required", stale.getString("reason"))
    }

    @Test
    fun `route adapter only serves POST on the browser path`() = runTest {
        val adapter = DshBrowserRouteAdapter(DshBrowserInProcessTransport(DshBrowserSession(FakeBrowserEngine())))
        val getResponse = adapter.handle(request("", DshCapabilityHttpMethod.GET))
        assertEquals(405, (getResponse as DshCapabilityRouteResponse.Json).statusCode)
        assertFalse(bodyOf(getResponse).getBoolean("ok"))

        val empty = adapter.handle(request(""))
        assertEquals(200, (empty as DshCapabilityRouteResponse.Json).statusCode)
        assertFalse(bodyOf(empty).getBoolean("ok"))
        assertEquals("bad-args", bodyOf(empty).getString("reason"))

        val wrongPath = adapter.handle(
            DshCapabilityRouteRequest(
                path = "/browser/other",
                method = DshCapabilityHttpMethod.POST,
                query = emptyMap(),
                bodyJson = "{}",
                localToken = null,
                generation = 1L,
                receivedAtMillis = 0L
            )
        )
        assertEquals(DshCapabilityRouteResponse.Unrouted, wrongPath)
    }

    @Test
    fun `route adapter projects a transport failure as a typed browser failure`() = runTest {
        val adapter = DshBrowserRouteAdapter(DshBrowserTransport { throw IllegalStateException("binder died") })
        val response = adapter.handle(request("""{"op":"caps"}"""))
        assertEquals(200, (response as DshCapabilityRouteResponse.Json).statusCode)
        val json = bodyOf(response)
        assertFalse(json.getBoolean("ok"))
        assertEquals("engine-unavailable", json.getString("reason"))
    }

    @Test
    fun `route adapter passes the response through unchanged on success`() = runTest {
        val adapter = DshBrowserRouteAdapter(DshBrowserTransport { """{"ok":true,"surface":"browser"}""" })
        val response = adapter.handle(request("""{"op":"caps"}""")) as DshCapabilityRouteResponse.Json
        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true,"surface":"browser"}""", response.bodyJson)
        assertEquals("/browser", DshBrowserRouteAdapter.ROUTE_PATH)
    }
}
