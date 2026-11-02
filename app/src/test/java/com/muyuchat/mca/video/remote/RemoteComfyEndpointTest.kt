package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoRemoteEndpointBinding
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RemoteComfyEndpointTest {

    @Test
    fun normalizesTrailingSlashesAndKeepsHttpOrigin() {
        val endpoint = endpoint("https://example.test:8188///")
        assertEquals("https://example.test:8188", endpoint.baseUrl)
        assertEquals("https://example.test:8188/queue", endpoint.queueRequest().url.toString())
    }

    @Test
    fun keepsBasePathForHttpAndWebSocket() {
        val endpoint = endpoint("https://example.test/comfy/")
        assertEquals("https://example.test/comfy/queue", endpoint.queueRequest().url.toString())
        assertEquals(
            "wss://example.test/comfy/ws?clientId=abc",
            endpoint.webSocketUrl("abc").toString(),
        )
    }

    @Test
    fun usesWsForPlainHttp() {
        val endpoint = endpoint("http://192.168.1.20:8188")
        assertEquals(
            "ws://192.168.1.20:8188/ws?clientId=c1",
            endpoint.webSocketUrl("c1").toString(),
        )
    }

    @Test
    fun historyUsesEncodedPathOnSameOrigin() {
        val request = endpoint("https://example.test:8188").historyRequest("a/b")
        assertEquals("example.test", request.url.host)
        assertEquals(8188, request.url.port)
        assertTrue(request.url.encodedPath.endsWith("/history/a%2Fb"))
    }

    @Test
    fun viewUsesOnlyEncodedQueryParametersOnSameOrigin() {
        val request = endpoint("https://example.test/comfy")
            .viewRequest(RemoteComfyOutputRef("clip 1.mp4", "videos/2026", "9"))
        assertEquals("https", request.url.scheme)
        assertEquals("example.test", request.url.host)
        assertEquals("/comfy/view", request.url.encodedPath)
        assertEquals("clip 1.mp4", request.url.queryParameter("filename"))
        assertEquals("videos/2026", request.url.queryParameter("subfolder"))
        assertEquals("output", request.url.queryParameter("type"))
    }

    @Test
    fun rejectsUserInfoQueryFragmentAndNonHttpSchemes() {
        assertRejected("http://user:pass@example.test:8188")
        assertRejected("http://user@example.test:8188")
        assertRejected("https://example.test:8188/?token=1")
        assertRejected("https://example.test:8188/#fragment")
        assertRejected("ftp://example.test:8188")
        assertRejected("file:///data/local/tmp/workflow.json")
        assertRejected("https://example.test:8188/a b")
        assertRejected("https://")
        assertRejected("")
    }

    @Test
    fun rejectsCredentialControlCharacters() {
        assertRejected("https://example.test:8188", apiKey = "abc\ndef")
        assertRejected("https://example.test:8188", apiKey = "abc\rdef")
    }

    @Test
    fun credentialStaysInAuthorizationHeaderOnly() {
        val endpoint = endpoint("https://example.test:8188", apiKey = "secret-key")
        assertEquals("Bearer secret-key", endpoint.queueRequest().header("Authorization"))
        assertFalse(endpoint.queueRequest().url.toString().contains("secret-key"))
        assertFalse(endpoint.webSocketUrl("client-1").toString().contains("secret-key"))
        assertFalse(endpoint.promptRequest(byteArrayOf(1)).url.toString().contains("secret-key"))
        assertFalse(endpoint.toString().contains("secret-key"))
    }

    @Test
    fun redirectAndTransportPolicyIsExplicit() {
        assertTrue(isRedirectStatus(300))
        assertTrue(isRedirectStatus(302))
        assertTrue(isRedirectStatus(399))
        assertFalse(isRedirectStatus(200))
        assertFalse(isRedirectStatus(400))
    }

    @Test
    fun secureClientNeverFollowsOrRetries() {
        val client = secureClient(OkHttpClient())
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
    }

    @Test
    fun boundedReadFailsInsteadOfTruncating() {
        val payload = ByteArray(32) { 1 }
        assertEquals(32, readBounded(ByteArrayInputStream(payload), 32).size)
        assertThrows(RemoteComfyLimitException::class.java) {
            readBounded(ByteArrayInputStream(payload), 31)
        }
    }

    private fun endpoint(
        url: String,
        apiKey: String? = null,
        allowTargetedCancel: Boolean = false,
    ): RemoteComfyEndpoint = RemoteComfyEndpoint.parse(
        VideoRemoteEndpointBinding(
            endpointId = "ep-1",
            baseUrl = url,
            apiKey = apiKey,
            allowTargetedCancel = allowTargetedCancel,
        ),
    )

    private fun assertRejected(url: String, apiKey: String? = null) {
        assertThrows(RemoteComfyConfigException::class.java) {
            endpoint(url, apiKey)
        }
    }
}
