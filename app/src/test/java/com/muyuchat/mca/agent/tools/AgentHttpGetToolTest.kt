package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHttpGetToolTest {
    private val client = FakeHttpTextClient()
    private val gate = RecordingHttpGetGate()
    private val tool = AgentHttpGetTool(client, gate)

    private fun command(json: String) = ExtendedToolTestSupport.command(tool.descriptor, json)

    @Test
    fun `authorized https get returns the bounded text and canonical origin`() = runBlocking {
        val result = tool.execute(
            command("""{"url":"https://example.com/notes.txt"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val request = client.requests.single()
        assertEquals("https://example.com/notes.txt", request.url)
        assertEquals("https://example.com", request.origin)
        assertEquals(32 * 1024, request.maxBytes)
        assertEquals(10_000L, request.timeoutMillis)
        assertEquals("https://example.com", gate.requests.single().origin)
        assertEquals("run-1", gate.requests.single().runId)
        assertTrue(result.dataJson!!.contains("\"text\":\"hello\""))
        assertTrue(result.dataJson!!.contains("\"statusCode\":200"))
    }

    @Test
    fun `gate denial is a DENIED result and never opens the transport`() = runBlocking {
        val denied = AgentHttpGetTool(
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
    fun `cleartext and non-https urls are denied before the transport`() = runBlocking {
        listOf("http://example.com/x", "ftp://example.com/x", "file:///data/x").forEach { url ->
            val result = tool.execute(
                command("""{"url":"$url"}"""),
                ExtendedToolTestSupport.context()
            )
            assertEquals("expected $url to be denied", AgentToolResultStatus.DENIED, result.status)
            assertEquals("POLICY_EGRESS_DENIED", result.errorCode)
        }
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `loopback and private destinations are refused`() = runBlocking {
        listOf(
            "https://localhost/x",
            "https://127.0.0.1/x",
            "https://10.1.2.3/x",
            "https://192.168.0.10/x",
            "https://172.20.1.1/x"
        ).forEach { url ->
            val result = tool.execute(
                command("""{"url":"$url"}"""),
                ExtendedToolTestSupport.context()
            )
            assertEquals("expected $url to be denied", AgentToolResultStatus.DENIED, result.status)
        }
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `credentials embedded in the url are refused`() = runBlocking {
        val result = tool.execute(
            command("""{"url":"https://user:pass@example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `transport failure keeps its typed code`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Failure("TOOL_TIMEOUT", "deadline reached")

        val result = tool.execute(
            command("""{"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
    }

    @Test
    fun `truncated responses are marked truncated`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Response(
            statusCode = 200,
            contentType = "text/plain",
            bodyBytes = 4,
            text = "abcd",
            truncated = true
        )

        val result = tool.execute(
            command("""{"url":"https://example.com/x","maxBytes":4}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.truncated)
        assertEquals(4, client.requests.single().maxBytes)
        assertTrue(result.dataJson!!.contains("\"truncated\":true"))
    }

    @Test
    fun `binary payloads are reported without text`() = runBlocking {
        client.outcome = AgentHttpGetOutcome.Response(
            statusCode = 200,
            contentType = "application/octet-stream",
            bodyBytes = 3,
            text = null,
            truncated = false
        )

        val result = tool.execute(
            command("""{"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.dataJson!!.contains("\"textAvailable\":false"))
    }

    @Test
    fun `out of range budgets are rejected`() = runBlocking {
        val tooLarge = tool.execute(
            command("""{"maxBytes":131073,"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )
        assertEquals("TOOL_INPUT_INVALID", tooLarge.errorCode)

        val tooSlow = tool.execute(
            command("""{"timeoutMillis":15001,"url":"https://example.com/x"}"""),
            ExtendedToolTestSupport.context()
        )
        assertEquals("TOOL_INPUT_INVALID", tooSlow.errorCode)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `normalizer freezes the request only with a network typed scope`() {
        val raw = """{"url":"https://example.com/x"}"""
        val normalization = AgentExtendedToolArgumentNormalizer()
            .normalize(tool.descriptor, raw) as AgentArgumentNormalization.Normalized
        assertEquals(
            """{"maxBytes":32768,"timeoutMillis":10000,"url":"https://example.com/x"}""",
            normalization.normalizedArgumentsJson
        )

        val scope = AgentExtendedToolScopes.httpGet(normalization.normalizedArgumentsJson)
        assertEquals(1, scope!!.scopes.size)
        assertEquals(setOf(com.muyuchat.core.agent.contract.AgentEffectKind.NETWORK_READ), scope.effects)

        val frozen = ExtendedToolTestSupport.frozen(tool.descriptor, raw, scope)
        assertTrue(frozen.command.resourcePreconditionsJson.contains("\"kind\":\"network\""))
        assertTrue(frozen.command.resourcePreconditionsJson.contains("https://example.com"))
        assertTrue(frozen.command.networkDestinations.isEmpty())

        ExtendedToolTestSupport.assertRejected(
            ExtendedToolTestSupport.freeze(tool.descriptor, raw),
            "TOOL_INPUT_INVALID"
        )
    }

    @Test
    fun `normalizer refuses cleartext urls`() {
        val rejected = AgentExtendedToolArgumentNormalizer()
            .normalize(tool.descriptor, """{"url":"http://example.com/x"}""")
        assertTrue(rejected is AgentArgumentNormalization.Rejected)
    }

    @Test
    fun `cancellation propagates instead of becoming a result`() = runBlocking {
        val cancelling = object : AgentHttpTextClient {
            override suspend fun get(request: AgentHttpGetRequest): AgentHttpGetOutcome {
                throw CancellationException("stop")
            }
        }
        val cancellingTool = AgentHttpGetTool(cancelling, RecordingHttpGetGate())

        var thrown: CancellationException? = null
        try {
            cancellingTool.execute(
                command("""{"url":"https://example.com/x"}"""),
                ExtendedToolTestSupport.context()
            )
        } catch (cancel: CancellationException) {
            thrown = cancel
        }
        assertTrue(thrown != null)
    }

    @Test
    fun `descriptor declares a network side effect`() {
        assertEquals("http.get", tool.descriptor.toolKey)
        assertEquals(com.muyuchat.core.agent.contract.AgentToolSideEffect.NETWORK, tool.descriptor.sideEffect)
        assertFalse(tool.descriptor.schemaJson.isBlank())
        assertNull(gate.requests.firstOrNull())
    }
}
