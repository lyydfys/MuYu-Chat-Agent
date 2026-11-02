package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog lifecycle against the real loopback listener: the empty catalog is a valid OpenAI
 * listing (never a fake model and never an empty success for a failed read), missing models are
 * rejected before the backend, and a catalog change on the same running gateway is visible to the
 * very next listing and chat call without a restart.
 */
class DshGatewayCatalogIntegrationTest : DshGatewayServerTestBase() {

    @Test
    fun emptyCatalogIsAValidOpenAiListingAndKeepsAuth() {
        backend.models = emptyList()

        val unauthenticated = DshTestHttp.request(endpoint, "GET", "/v1/models", token = null)
        assertEquals(401, unauthenticated.status)
        assertEquals("invalid_api_key", unauthenticated.json().getJSONObject("error").getString("code"))

        val wrongToken = DshTestHttp.request(endpoint, "GET", "/v1/models", token = "dshg_wrong")
        assertEquals(401, wrongToken.status)

        val listing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, listing.status)
        val body = listing.json()
        assertEquals("list", body.getString("object"))
        assertEquals(0, body.getJSONArray("data").length())
        assertTrue(body.getString("mca_catalog_revision").isNotEmpty())
        assertFalse(body.has("error"))
        assertFalse(listing.body.contains(endpoint.bearerToken))
    }

    @Test
    fun emptyCatalogRejectsEveryModelBeforeTheBackendIsTouched() {
        backend.models = emptyList()
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
        }

        val response = chat(stream = false)
        assertEquals(404, response.status)
        val error = response.json().getJSONObject("error")
        assertEquals("model_not_found", error.getString("code"))
        assertEquals("model", error.getString("param"))
        assertEquals("invalid_request_error", error.getString("type"))
        assertTrue(backend.requests.isEmpty())

        val streaming = chat(stream = true)
        assertEquals(404, streaming.status)
        assertEquals("model_not_found", streaming.json().getJSONObject("error").getString("code"))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun catalogChangeOnTheSameGatewayIsVisibleWithoutRestart() {
        val tokenBefore = endpoint.bearerToken
        val baseUrlBefore = endpoint.baseUrl
        backend.models = emptyList()

        val emptyListing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, emptyListing.status)
        assertEquals(0, emptyListing.json().getJSONArray("data").length())
        val emptyRevision = emptyListing.json().getString("mca_catalog_revision")
        assertEquals(emptyRevision, requireNotNull(server.currentEndpoint()).catalogRevision)

        assertEquals(404, chat(stream = false).status)
        assertTrue(backend.requests.isEmpty())

        // Load a local model: chat routing must see it immediately, before any /v1/models refresh.
        backend.models = listOf(fakeGatewayModel())
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("ready"),
                DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 2, completionTokens = 1)
            )
        }
        val completed = chat(stream = false)
        assertEquals(200, completed.status)
        assertEquals(
            "stop",
            completed.json().getJSONArray("choices").getJSONObject(0).getString("finish_reason")
        )
        assertEquals("ready", completed.json().getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
        assertEquals(1, backend.requests.size)
        assertEquals("fake-model", backend.singleRequest().modelId)

        val loadedListing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, loadedListing.status)
        val loadedData = loadedListing.json().getJSONArray("data")
        assertEquals(1, loadedData.length())
        assertEquals("fake-model", loadedData.getJSONObject(0).getString("id"))
        val loadedRevision = loadedListing.json().getString("mca_catalog_revision")
        assertNotEquals(emptyRevision, loadedRevision)
        assertEquals(loadedRevision, requireNotNull(server.currentEndpoint()).catalogRevision)
        assertTrue(server.isRunning)
        assertEquals(tokenBefore, requireNotNull(server.currentEndpoint()).bearerToken)
        assertEquals(baseUrlBefore, requireNotNull(server.currentEndpoint()).baseUrl)

        // Unload it again: routing stops accepting the stale id immediately, still the same
        // server generation and token; the listing follows on its next read.
        backend.models = emptyList()
        val rejected = chat(stream = false)
        assertEquals(404, rejected.status)
        assertEquals("model_not_found", rejected.json().getJSONObject("error").getString("code"))
        assertEquals(1, backend.requests.size)

        val unloadedListing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, unloadedListing.status)
        assertEquals(0, unloadedListing.json().getJSONArray("data").length())
        assertNotEquals(loadedRevision, unloadedListing.json().getString("mca_catalog_revision"))
        assertEquals(tokenBefore, requireNotNull(server.currentEndpoint()).bearerToken)
        assertTrue(server.isRunning)
    }

    @Test
    fun catalogReadFailureIs503AndNeverAnEmptySuccess() {
        backend.catalogFailure = IllegalStateException("model store unavailable")

        val models = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(503, models.status)
        assertEquals("catalog_unavailable", models.json().getJSONObject("error").getString("code"))
        assertFalse(models.body.contains("\"data\""))
        assertFalse(models.body.contains("\"object\":\"list\""))

        val chatResponse = chat(stream = false)
        assertEquals(503, chatResponse.status)
        assertEquals(
            "catalog_unavailable",
            chatResponse.json().getJSONObject("error").getString("code")
        )
        assertTrue(backend.requests.isEmpty())

        // The same running gateway recovers as soon as the catalog read succeeds again.
        backend.catalogFailure = null
        backend.models = listOf(fakeGatewayModel())
        backend.streamFactory = {
            flowOf(DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 1, completionTokens = 1))
        }
        val recovered = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, recovered.status)
        assertEquals(1, recovered.json().getJSONArray("data").length())
        assertEquals(200, chat(stream = false).status)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun healthzReportsTheCurrentCatalogRevisionWithoutLeakingTheToken() {
        backend.models = emptyList()
        assertEquals(200, DshTestHttp.request(endpoint, "GET", "/v1/models").status)
        val emptyHealth = JSONObject(DshTestHttp.request(endpoint, "GET", "/healthz").body)
        assertEquals(0, emptyHealth.getInt("active_calls"))
        val emptyRevision = emptyHealth.getString("catalog_revision")
        assertTrue(emptyRevision.isNotEmpty())

        backend.models = listOf(fakeGatewayModel())
        assertEquals(200, DshTestHttp.request(endpoint, "GET", "/v1/models").status)
        val loadedHealth = JSONObject(DshTestHttp.request(endpoint, "GET", "/healthz").body)
        assertNotEquals(emptyRevision, loadedHealth.getString("catalog_revision"))
        assertFalse(loadedHealth.toString().contains(endpoint.bearerToken))
    }
}
