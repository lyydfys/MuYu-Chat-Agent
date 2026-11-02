package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshOverlayActionTest {

    private val allowedKeys = setOf(
        "ok", "error", "running", "engineUp", "granted", "action", "visible", "changed", "msg"
    )

    private suspend fun handle(
        port: FakeOverlayPort,
        query: Map<String, String> = emptyMap(),
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.GET
    ): JSONObject = responseJson(
        DshOverlayAction(port).handle(
            routeRequest(DshOverlayAction.ROUTE, method, query, body = null)
        )
    )

    @Test
    fun status_is_the_default_action() = runTest {
        val port = FakeOverlayPort(running = true, engineUp = true, visible = true)
        val response = handle(port)

        assertTrue(response.getBoolean("ok"))
        assertEquals("status", response.getString("action"))
        assertTrue(response.getBoolean("running"))
        assertTrue(response.getBoolean("engineUp"))
        assertTrue(response.getBoolean("granted"))
        assertTrue(response.getBoolean("visible"))
        assertFalse(response.getBoolean("changed"))
        assertOnlyKeys(response, allowedKeys)
        assertTrue(port.calls.isEmpty())
    }

    @Test
    fun show_reports_the_observed_change() = runTest {
        val port = FakeOverlayPort(visible = false, visibleAfterShow = true)
        val response = handle(port, mapOf("action" to "show"))

        assertTrue(response.getBoolean("ok"))
        assertEquals("show", response.getString("action"))
        assertTrue(response.getBoolean("changed"))
        assertTrue(response.getBoolean("visible"))
        assertEquals(listOf("show"), port.calls)
    }

    @Test
    fun hide_that_changes_nothing_reports_no_change() = runTest {
        val port = FakeOverlayPort(visible = false)
        val response = handle(port, mapOf("action" to "hide"))

        assertTrue(response.getBoolean("ok"))
        assertFalse(response.getBoolean("changed"))
        assertFalse(response.getBoolean("visible"))
        assertEquals(listOf("hide"), port.calls)
    }

    @Test
    fun rejects_an_unknown_action() = runTest {
        val response = handle(FakeOverlayPort(), mapOf("action" to "toggle"))
        assertEquals("INVALID_REQUEST", errorCodeOf(response))
    }

    @Test
    fun refuses_when_the_overlay_permission_is_missing() = runTest {
        val port = FakeOverlayPort(granted = false, visibleAfterShow = true)
        val response = handle(port, mapOf("action" to "show"))

        assertFalse(response.getBoolean("ok"))
        assertEquals("PERMISSION_DENIED", errorCodeOf(response))
        assertFalse(response.getBoolean("granted"))
        assertFalse(response.getBoolean("visible"))
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun refuses_post() = runTest {
        val response = handle(
            FakeOverlayPort(),
            method = DshCapabilityHttpMethod.POST
        )
        assertEquals("METHOD_NOT_ALLOWED", errorCodeOf(response))
    }

    @Test
    fun rejects_an_unknown_query_parameter() = runTest {
        val response = handle(FakeOverlayPort(), mapOf("action" to "status", "x" to "1"))
        assertEquals("UNEXPECTED_QUERY", errorCodeOf(response))
    }
}
