package com.muyuchat.api.dsh.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ECO-5: a bound model row without a context window makes official compaction fail closed
 * (`dsh-compaction-basic`: "no context capacity for ...").
 *
 * The gateway cannot invent a window, so it must not emit that row as if it were healthy: every
 * `/v1/models` entry carries a typed `mca_context` sidecar, the listing root a
 * `mca_context_warnings` array, and the host log gets one bounded warning line. The official wire
 * fields (`id`, `name`, `context_window`, `max_output_tokens`, ...) stay exactly as declared.
 */
class DshContextCapacityTest : DshGatewayServerTestBase() {

    @Test
    fun rowsWithoutADeclaredWindowAreTypedAsNotCompactionReady() {
        val missing = DshContextCapacityProbe.of(
            fakeGatewayModel(id = "mca-cloud:abc", contextWindow = null, maxOutputTokens = null)
        )
        assertEquals("mca-cloud:abc", missing.modelId)
        assertNull(missing.contextWindow)
        assertFalse(missing.compactionReady)
        assertEquals(DshContextCapacityGap.NO_CONTEXT_WINDOW, missing.gap)
        assertTrue(missing.warning!!.contains("mca-cloud:abc"))
        assertTrue(missing.warning!!.contains("compaction"))

        val json = DshContextCapacityProbe.toJson(missing)
        assertFalse(json.getBoolean("compaction_ready"))
        assertEquals("no_context_window", json.getString("gap"))
        assertTrue(json.isNull("context_window"))
    }

    @Test
    fun zeroOrNegativeWindowsAreNotCapacities() {
        for (window in listOf(0, -1)) {
            val row = DshContextCapacityProbe.of(fakeGatewayModel(contextWindow = window))
            assertFalse("window=$window must not be treated as declared", row.compactionReady)
            assertEquals(DshContextCapacityGap.NO_CONTEXT_WINDOW, row.gap)
        }
    }

    @Test
    fun aDeclaredWindowIsCompactionReadyAndProducesNoWarning() {
        val healthy = DshContextCapacityProbe.of(fakeGatewayModel(contextWindow = 8_192))
        assertTrue(healthy.compactionReady)
        assertNull(healthy.gap)
        assertNull(healthy.warning)
        assertTrue(DshContextCapacityProbe.warnings(listOf(fakeGatewayModel())).isEmpty())
        assertNull(DshContextCapacityProbe.summary(listOf(fakeGatewayModel())))
    }

    @Test
    fun theSummaryNamesOnlyTheRowsThatCannotBeCompacted() {
        val healthy = fakeGatewayModel(id = "healthy", contextWindow = 8_192)
        val broken = (1..6).map { fakeGatewayModel(id = "gap-$it", contextWindow = null) }
        val summary = DshContextCapacityProbe.summary(listOf(healthy) + broken)!!
        assertTrue(summary.startsWith("6 model row(s) without contextWindow"))
        assertFalse(summary.contains("healthy"))
        assertTrue(summary.contains("gap-1"))
        assertTrue("the summary must stay bounded", summary.contains("(+2 more)"))
    }

    @Test
    fun theModelsListingCarriesTheTypedSidecarAndTheOfficialFieldsStayUnchanged() {
        backend.models = listOf(
            fakeGatewayModel(id = "mca-local:loaded", contextWindow = 8_192),
            fakeGatewayModel(
                id = "mca-cloud:no-window",
                contextWindow = null,
                maxOutputTokens = null
            )
        )

        val listing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, listing.status)
        val body = listing.json()
        assertEquals("list", body.getString("object"))
        val data = body.getJSONArray("data")
        assertEquals(2, data.length())

        val healthy = data.getJSONObject(0)
        assertEquals("mca-local:loaded", healthy.getString("id"))
        assertEquals(8_192, healthy.getInt("context_window"))
        assertEquals(8_192, healthy.getInt("context_length"))
        assertTrue(healthy.getJSONObject("mca_context").getBoolean("compaction_ready"))

        val broken = data.getJSONObject(1)
        assertEquals("mca-cloud:no-window", broken.getString("id"))
        // The gateway must not fabricate the official field for a row that does not declare it.
        assertFalse(broken.has("context_window"))
        assertFalse(broken.has("context_length"))
        val context = broken.getJSONObject("mca_context")
        assertFalse(context.getBoolean("compaction_ready"))
        assertEquals("no_context_window", context.getString("gap"))
        assertTrue(context.isNull("context_window"))

        val warnings = body.getJSONArray("mca_context_warnings")
        assertEquals(1, warnings.length())
        assertTrue(warnings.getString(0).contains("mca-cloud:no-window"))
        assertFalse(listing.body.contains(endpoint.bearerToken))

        // The host log carries the same typed fact once per listing.
        assertTrue(
            "the gateway must log one bounded warning for the unusable row",
            logs.any { it.startsWith("WARN") && it.contains("without contextWindow") }
        )
    }

    @Test
    fun aFullyDeclaredCatalogProducesNoWarningEntry() {
        backend.models = listOf(fakeGatewayModel(id = "mca-local:loaded", contextWindow = 8_192))

        val listing = DshTestHttp.request(endpoint, "GET", "/v1/models")
        assertEquals(200, listing.status)
        val body = JSONObject(listing.body)
        assertEquals(0, body.getJSONArray("mca_context_warnings").length())
        assertTrue(
            body.getJSONArray("data").getJSONObject(0)
                .getJSONObject("mca_context").getBoolean("compaction_ready")
        )
        assertFalse(logs.any { it.contains("without contextWindow") })
    }
}
