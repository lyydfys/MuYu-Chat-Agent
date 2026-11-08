package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `web.fetch`: HTML → readable text on top of the existing `http.get` seams.
 *
 * The tool may not become a second browser engine or a second transport, so these tests pin three
 * things: the projection is honest (no script/style text, titles kept, truncation marked), the
 * destination gate is the same one `http.get` uses, and a denied run never opens a socket.
 */
class AgentWebFetchToolTest {
    private val client = FakeHttpTextClient()
    private val gate = RecordingHttpGetGate()
    private val tool = AgentWebFetchTool(client, gate)

    private fun command(json: String) = ExtendedToolTestSupport.command(tool.descriptor, json)

    // ------------------------------------------------------------------ projection (pure)

    @Test
    fun `scripts and styles are removed but the title and paragraphs stay`() {
        val html = """
            <html><head>
              <title>部署说明</title>
              <style>body { color: red } .x { display:none }</style>
              <script>var token = "secret"; alert(1)</script>
            </head><body>
              <h1>第一章</h1>
              <p>第一段的正文。</p>
              <div>第二段<strong>加粗</b>结尾。</div>
              <noscript>请开启 JavaScript</noscript>
            </body></html>
        """.trimIndent()

        val result = htmlToPlainText(html)

        assertEquals("部署说明", result.title)
        assertTrue(result.text, result.text.startsWith("部署说明"))
        assertTrue(result.text, result.text.contains("第一章"))
        assertTrue(result.text, result.text.contains("第一段的正文。"))
        assertTrue("markup must be gone", result.text.contains("第二段加粗结尾。"))
        assertFalse("script bodies must never reach the model", result.text.contains("alert(1)"))
        assertFalse(result.text.contains("var token"))
        assertFalse(result.text.contains("color: red"))
        assertFalse("noscript is not page prose", result.text.contains("请开启 JavaScript"))
        assertFalse(result.text.contains("<"))
    }

    @Test
    fun `block boundaries become paragraph breaks`() {
        val result = htmlToPlainText("<p>一</p><p>二</p><div>三</div>")

        assertEquals("一\n\n二\n\n三", result.text)
    }

    @Test
    fun `entities are decoded and whitespace collapsed`() {
        val result = htmlToPlainText("<p>A&nbsp;&amp;&nbsp;B &lt;tag&gt; &#48;&#x31; &hellip;</p>")

        assertEquals("A & B <tag> 01 …", result.text)
    }

    @Test
    fun `an empty document yields empty text instead of markup noise`() {
        assertEquals("", htmlToPlainText("").text)
        assertEquals("", htmlToPlainText("<script>x</script>").text)
    }

    @Test
    fun `truncation keeps the budget and adds a visible marker`() {
        val truncated = truncateForModel("x".repeat(100), maxChars = 20)

        assertTrue(truncated.truncated)
        assertTrue(truncated.text, truncated.text.endsWith("…（已截断）"))
        assertTrue("the marker is inside the budget, never appended past it", truncated.text.length <= 20)

        val untouched = truncateForModel("short", maxChars = 20)
        assertFalse(untouched.truncated)
        assertEquals("short", untouched.text)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val truncated = truncateForModel("😀".repeat(20), maxChars = 10)

        assertTrue(truncated.truncated)
        val body = truncated.text.removeSuffix("…（已截断）")
        assertTrue("kept an even number of UTF-16 units", body.length % 2 == 0)
    }

    // ------------------------------------------------------------------ tool behaviour

    @Test
    fun `an authorized fetch returns extracted text with the requested budget`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Response(
            statusCode = 200,
            contentType = "text/html; charset=utf-8",
            bodyBytes = 120,
            text = "<html><title>标题</title><body><p>正文</p><script>x()</script></body></html>",
            truncated = false
        )

        val result = tool.execute(
            command("""{"url":"https://example.com/doc","maxChars":100}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        // `dataJson` is the whole result envelope (schemaVersion/ok/summary/data/...), so the tool's
        // payload must be read from its `data` object rather than from the envelope root.
        val data = JSONObject(result.dataJson!!).getJSONObject("data")
        assertEquals("https://example.com", data.getString("origin"))
        assertEquals("标题", data.getString("title"))
        assertEquals("标题\n\n正文", data.getString("text"))
        assertFalse(data.getBoolean("truncated"))
        // "标题" (2) + the paragraph break (2) + "正文" (2) = 6. The count is of the extracted
        // text the tool returns, which is exactly the `text` asserted above; an off-by-one here
        // would silently pin a wrong character budget on every fetch.
        assertEquals(6, data.getInt("chars"))

        val request = client.requests.single()
        assertEquals("https://example.com/doc", request.url)
        assertEquals("https://example.com", request.origin)
        assertEquals("run-1", gate.requests.single().runId)
    }

    @Test
    fun `maxChars truncates the extracted text and marks it`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Response(
            statusCode = 200,
            contentType = "text/html",
            bodyBytes = 900,
            text = "<p>" + "字".repeat(600) + "</p>",
            truncated = false
        )

        val result = tool.execute(
            command("""{"url":"https://example.com/long","maxChars":50}"""),
            ExtendedToolTestSupport.context()
        )

        // Same envelope shape as above: the payload lives under `data`.
        val data = JSONObject(result.dataJson!!).getJSONObject("data")
        assertEquals(50, data.getInt("chars"))
        assertEquals(600, data.getInt("totalChars"))
        assertTrue(data.getBoolean("truncated"))
        assertTrue(result.truncated)
        assertTrue(data.getString("text").endsWith("…（已截断）"))
    }

    @Test
    fun `cleartext http pages are accepted because reading docs is the point`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Response(200, "text/html", 10, "<p>ok</p>", false)

        val result = tool.execute(
            command("""{"url":"http://example.com/plain"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals("http://example.com", client.requests.single().origin)
    }

    @Test
    fun `non http schemes and private hosts are denied before the transport`() = runBlocking {
        listOf(
            "file:///data/local/tmp/x.html",
            "data:text/html,<b>x</b>",
            "ftp://example.com/x",
            "javascript:alert(1)",
            "https://127.0.0.1/x",
            "https://localhost/x",
            "http://192.168.0.5/router",
            "https://user:pass@example.com/x"
        ).forEach { url ->
            val result = tool.execute(
                command("""{"url":"$url"}"""),
                ExtendedToolTestSupport.context()
            )
            assertEquals("$url must be denied", AgentToolResultStatus.DENIED, result.status)
            assertEquals("POLICY_EGRESS_DENIED", result.errorCode)
        }
        assertTrue("no denied call may reach the transport", client.requests.isEmpty())
    }

    @Test
    fun `a gate denial is a typed failure and never opens the transport`() = runBlocking {
        val denied = AgentWebFetchTool(
            client,
            RecordingHttpGetGate(
                AgentHttpGetGateDecision.Denied("POLICY_EGRESS_DENIED", "No grant for this origin.")
            )
        )

        val result = denied.execute(
            command("""{"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("POLICY_EGRESS_DENIED", result.errorCode)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `a transport failure keeps its typed code and no text is invented`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Failure("TOOL_TIMEOUT", "deadline reached")

        val result = tool.execute(
            command("""{"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
        assertFalse(result.dataJson!!.contains("\"text\""))
    }

    @Test
    fun `out of range budgets are rejected`() = runBlocking {
        listOf("""{"maxChars":0,"url":"https://e.com/"}""", """{"maxChars":20001,"url":"https://e.com/"}""")
            .forEach { json ->
                val result = tool.execute(command(json), ExtendedToolTestSupport.context())
                assertEquals("TOOL_INPUT_INVALID", result.errorCode)
            }
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `the descriptor is a read only private tool`() {
        assertEquals("web.fetch", tool.descriptor.toolKey)
        assertEquals(AgentToolSideEffect.READ_ONLY, tool.descriptor.sideEffect)
        assertEquals(AgentDataClass.PRIVATE, tool.descriptor.dataClass)
        assertEquals(AgentToolJson.sha256Hex(tool.descriptor.schemaJson), tool.descriptor.schemaHash)
        assertTrue(tool.descriptor.schemaJson.contains("\"additionalProperties\":false"))
    }

    @Test
    fun `the normalizer freezes url and default budget before approval`() {
        val normalization = AgentExtendedToolArgumentNormalizer()
            .normalize(tool.descriptor, """{"url":"https://example.com/a"}""") as AgentArgumentNormalization.Normalized

        assertEquals(
            """{"maxChars":4000,"timeoutMillis":10000,"url":"https://example.com/a"}""",
            normalization.normalizedArgumentsJson
        )
        assertTrue(
            "the same rejected destination policy runs at freeze time",
            AgentExtendedToolArgumentNormalizer()
                .normalize(tool.descriptor, """{"url":"https://127.0.0.1/"}""") is AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun `the approved scope is the exact origin as network read`() {
        val scope = AgentExtendedToolScopes.webFetch("""{"maxChars":4000,"timeoutMillis":10000,"url":"http://example.com/doc"}""")

        assertTrue(scope != null)
        assertEquals(
            setOf(com.muyuchat.core.agent.contract.AgentEffectKind.NETWORK_READ),
            scope!!.effects
        )
        assertTrue(scope.scopes.single() is com.muyuchat.core.agent.contract.AgentResourceScope.Network)
    }

    @Test
    fun `registration follows the http client and stays out without one`() {
        val port = FakeAgentWorkspaceFilePort()
        assertTrue(extendedAgentToolCatalog(port).find("web.fetch") == null)
        val withHttp = extendedAgentToolCatalog(port, httpClient = FakeHttpTextClient())
        assertTrue(withHttp.find("web.fetch") != null)
        assertEquals(
            AgentToolSideEffect.READ_ONLY,
            withHttp.find("web.fetch")!!.descriptor.sideEffect
        )
    }

    @Test
    fun `an unsupported schema keyword is still rejected by the guard`() {
        // Sanity: the guard this tool's schema goes through rejects an unsupported keyword.
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSchemaGuard.requireSupported("""{"type":"object","unknownKeyword":true}""")
        }
    }
}
