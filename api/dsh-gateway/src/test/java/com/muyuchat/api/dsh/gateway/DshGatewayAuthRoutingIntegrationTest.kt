package com.muyuchat.api.dsh.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class DshGatewayAuthRoutingIntegrationTest : DshGatewayServerTestBase() {

    @Test
    fun healthzRequiresTheBearerTokenAndNeverEchoesIt() {
        val missing = DshTestHttp.request(endpoint, "GET", "/healthz", token = null)
        assertEquals(401, missing.status)
        assertEquals("invalid_api_key", missing.json().getJSONObject("error").getString("code"))
        assertEquals("Bearer", missing.headers["www-authenticate"])

        val wrong = DshTestHttp.request(endpoint, "GET", "/healthz", token = "dshg_wrong")
        assertEquals(401, wrong.status)

        val ok = DshTestHttp.request(endpoint, "GET", "/healthz")
        assertEquals(200, ok.status)
        val body = ok.json()
        assertEquals("ok", body.getString("status"))
        assertEquals("mca-dsh-gateway", body.getString("service"))
        assertEquals(0, body.getInt("active_calls"))
        assertFalse(ok.body.contains(endpoint.bearerToken))
    }

    @Test
    fun modelsRequiresTheBearerTokenAndUsesTheDshDiscoveryShape() {
        val missing = DshTestHttp.request(endpoint, "GET", "/v1/models", token = null)
        assertEquals(401, missing.status)

        val ok = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, ok.status)
        val body = ok.json()
        assertEquals("list", body.getString("object"))
        val model = body.getJSONArray("data").getJSONObject(0)
        assertEquals("fake-model", model.getString("id"))
        assertEquals("Fake Model", model.getString("name"))
        assertEquals(8_192, model.getInt("context_window"))
        assertEquals(1_024, model.getInt("max_output_tokens"))
        assertTrue(model.getBoolean("supports_tools"))
        assertTrue(model.getBoolean("native_tool_calling"))
        assertTrue(body.getString("mca_catalog_revision").isNotEmpty())
        assertFalse(ok.body.contains(endpoint.bearerToken))
    }

    @Test
    fun methodsAndUnknownRoutesAreRejectedWithOpenAiErrors() {
        val wrongMethod = DshTestHttp.request(endpoint, "GET", "/v1/chat/completions")
        assertEquals(405, wrongMethod.status)
        assertEquals("POST", wrongMethod.headers["allow"])
        assertEquals("method_not_allowed", wrongMethod.json().getJSONObject("error").getString("code"))

        val modelsPost = DshTestHttp.request(endpoint, "POST", "/v1/models", body = "{}")
        assertEquals(405, modelsPost.status)
        assertEquals("GET", modelsPost.headers["allow"])

        val unknown = DshTestHttp.request(endpoint, "GET", "/v1/unknown")
        assertEquals(404, unknown.status)
        assertEquals("not_found", unknown.json().getJSONObject("error").getString("code"))
    }

    @Test
    fun browserOriginAndForeignHostAreRejected() {
        val origin = DshTestHttp.request(
            endpoint, "GET", "/healthz",
            extraHeaders = mapOf("Origin" to "http://evil.example")
        )
        assertEquals(403, origin.status)
        assertEquals("origin_not_allowed", origin.json().getJSONObject("error").getString("code"))

        val host = DshTestHttp.request(
            endpoint, "GET", "/healthz",
            hostHeader = "evil.example:${DshTestHttp.port(endpoint)}"
        )
        assertEquals(403, host.status)
        assertEquals("invalid_host", host.json().getJSONObject("error").getString("code"))
    }

    @Test
    fun malformedJsonIsRejectedAsABadRequest() {
        val malformed = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions", body = "{"
        )
        assertEquals(400, malformed.status)
        assertEquals("invalid_request", malformed.json().getJSONObject("error").getString("code"))

        val missingModel = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions",
            body = """{"messages":[{"role":"user","content":"hi"}]}"""
        )
        assertEquals(400, missingModel.status)
        assertEquals("invalid_request", missingModel.json().getJSONObject("error").getString("code"))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun oversizedDeclaredBodyIsRejectedBeforeItIsRead() {
        val limits = DshGatewayLimits.Default
        val response = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions",
            body = null,
            contentLengthOverride = limits.maxRequestBytes.toLong() + 1L
        )
        assertEquals(413, response.status)
        assertEquals("request_too_large", response.json().getJSONObject("error").getString("code"))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun missingTokenNeverReachesTheBackend() {
        val response = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions",
            body = standardChatBody(stream = false),
            token = null
        )
        assertEquals(401, response.status)
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun catalogFailureMapsToServiceUnavailable() {
        backend.catalogFailure = IllegalStateException("catalog offline")
        val models = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(503, models.status)
        assertEquals("catalog_unavailable", models.json().getJSONObject("error").getString("code"))

        val chat = chat(stream = false)
        assertEquals(503, chat.status)
        assertEquals("catalog_unavailable", chat.json().getJSONObject("error").getString("code"))
    }

    @Test
    fun repeatedStartIsIdempotentForOneRun() {
        val again = kotlinx.coroutines.runBlocking { server.start() }
        assertEquals(endpoint.bearerToken, again.bearerToken)
        assertEquals(endpoint.baseUrl, again.baseUrl)
        assertEquals(endpoint.catalogRevision, again.catalogRevision)
    }

    @Test
    fun catalogRevisionTracksTheServedListing() {
        val before = requireNotNull(server.currentEndpoint()).catalogRevision
        backend.models = listOf(fakeGatewayModel(id = "second-model", displayName = "Second"))
        val listing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, listing.status)
        val after = requireNotNull(server.currentEndpoint()).catalogRevision
        assertNotEquals(before, after)
        assertEquals(after, listing.json().getString("mca_catalog_revision"))
    }

    @Test
    fun theListenerIsBoundToLoopbackOnly() {
        assertTrue(endpoint.baseUrl.startsWith("http://127.0.0.1:"))
        assertTrue(DshTestHttp.port(endpoint) > 0)

        val foreignAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { address ->
                address is Inet4Address &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
            }
        assumeNotNull(foreignAddress)
        val address = requireNotNull(foreignAddress)
        val failure = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, DshTestHttp.port(endpoint)), 750)
            }
        }
        assertTrue(
            "Connecting through a non-loopback interface must fail",
            failure.exceptionOrNull() is IOException
        )
    }

    @Test
    fun theBearerTokenNeverAppearsInLogsOrErrorBodies() {
        backend.streamFactory = {
            kotlinx.coroutines.flow.flow {
                emit(
                    com.muyuchat.core.dsh.contract.DshModelEvent.Completed(
                        com.muyuchat.core.dsh.contract.DshFinishReason.STOP,
                        promptTokens = 1,
                        completionTokens = 1
                    )
                )
            }
        }
        DshTestHttp.request(endpoint, "GET", "/healthz", token = "dshg_wrong")
        DshTestHttp.request(endpoint, "POST", "/v1/chat/completions", body = "{")
        DshTestHttp.request(endpoint, "GET", "/v1/unknown")
        val success = DshTestHttp.request(
            endpoint, "POST", "/v1/chat/completions",
            body = standardChatBody(stream = false)
        )
        assertEquals(200, success.status)

        val token = endpoint.bearerToken
        logs.forEach { line ->
            assertFalse("Token leaked into a log line: $line", line.contains(token))
        }
        assertFalse(success.body.contains(token))
        assertFalse(DshTestHttp.request(endpoint, "GET", "/healthz").body.contains(token))
        assertTrue(logs.isNotEmpty())
    }
}
