package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshUsageActionTest {

    private val allowedKeys = setOf("ok", "error", "days", "count", "apps")
    private val allowedAppKeys = setOf("pkg", "name", "ms", "min")

    private suspend fun handle(
        port: FakeUsagePort,
        query: Map<String, String> = emptyMap(),
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.GET
    ): JSONObject = responseJson(
        DshUsageAction(port).handle(routeRequest(DshUsageAction.ROUTE, method, query, null))
    )

    @Test
    fun sorts_by_foreground_time_and_computes_minutes() = runTest {
        val port = FakeUsagePort(
            apps = listOf(
                DshUsageApp("com.example.short", "短", 30_000L),
                DshUsageApp("com.example.long", "长", 180_000L)
            )
        )
        val response = handle(port, mapOf("days" to "7"))

        assertTrue(response.getBoolean("ok"))
        assertEquals(7, port.queries.single())
        val days = response.getInt("days")
        assertEquals(7, days)
        assertEquals(2, response.getInt("count"))
        val apps = response.getJSONArray("apps")
        assertEquals("com.example.long", apps.getJSONObject(0).getString("pkg"))
        assertEquals(180_000L, apps.getJSONObject(0).getLong("ms"))
        assertEquals(3L, apps.getJSONObject(0).getLong("min"))
        assertOnlyKeys(response, allowedKeys)
        assertOnlyKeys(apps.getJSONObject(0), allowedAppKeys)
    }

    @Test
    fun defaults_to_one_day() = runTest {
        val port = FakeUsagePort()
        val response = handle(port)

        assertTrue(response.getBoolean("ok"))
        assertEquals(1, port.queries.single())
        assertEquals(0, response.getInt("count"))
        assertEquals(0, response.getJSONArray("apps").length())
    }

    @Test
    fun filters_invalid_packages_and_falls_back_to_the_package_as_label() = runTest {
        val port = FakeUsagePort(
            apps = listOf(
                DshUsageApp("com.example.ok", "  ", 60_000L),
                DshUsageApp("not a package; rm -rf /", "x", 120_000L)
            )
        )
        val response = handle(port)

        assertEquals(1, response.getInt("count"))
        val app = response.getJSONArray("apps").getJSONObject(0)
        assertEquals("com.example.ok", app.getString("pkg"))
        assertEquals("com.example.ok", app.getString("name"))
    }

    @Test
    fun caps_the_app_list() = runTest {
        val apps = (1..150).map { index ->
            DshUsageApp("com.example.app$index", "App $index", index.toLong() * 1_000L)
        }
        val response = handle(FakeUsagePort(apps = apps))

        assertEquals(DshAndroidActionLimits.MAX_USAGE_APPS, response.getInt("count"))
        assertEquals(
            DshAndroidActionLimits.MAX_USAGE_APPS,
            response.getJSONArray("apps").length()
        )
    }

    @Test
    fun rejects_out_of_range_days() = runTest {
        assertEquals("INVALID_REQUEST", errorCodeOf(handle(FakeUsagePort(), mapOf("days" to "31"))))
        assertEquals("INVALID_REQUEST", errorCodeOf(handle(FakeUsagePort(), mapOf("days" to "0"))))
        assertEquals("INVALID_REQUEST", errorCodeOf(handle(FakeUsagePort(), mapOf("days" to "abc"))))
    }

    @Test
    fun reports_missing_usage_access_instead_of_an_empty_list() = runTest {
        val denied = handle(FakeUsagePort(accessState = DshCapabilityState.DENIED))
        assertEquals("PERMISSION_DENIED", errorCodeOf(denied))
        assertFalse(denied.getBoolean("ok"))

        val notDeclared = handle(FakeUsagePort(accessState = DshCapabilityState.NOT_DECLARED))
        assertEquals("PERMISSION_DENIED", errorCodeOf(notDeclared))

        val notEnabled = handle(FakeUsagePort(accessState = DshCapabilityState.NOT_ENABLED))
        assertEquals("NOT_ENABLED", errorCodeOf(notEnabled))

        val unsupported = handle(FakeUsagePort(accessState = DshCapabilityState.UNSUPPORTED))
        assertEquals("CAPABILITY_UNAVAILABLE", errorCodeOf(unsupported))
        assertOnlyKeys(unsupported, allowedKeys)
    }

    @Test
    fun propagates_a_port_failure() = runTest {
        val port = FakeUsagePort(
            failure = DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.INTERNAL,
                "读取失败"
            )
        )
        val response = handle(port)
        assertEquals("INTERNAL", errorCodeOf(response))
        assertFalse(response.getBoolean("ok"))
    }

    @Test
    fun refuses_post_and_unknown_query() = runTest {
        assertEquals(
            "METHOD_NOT_ALLOWED",
            errorCodeOf(handle(FakeUsagePort(), method = DshCapabilityHttpMethod.POST))
        )
        assertEquals(
            "UNEXPECTED_QUERY",
            errorCodeOf(handle(FakeUsagePort(), mapOf("days" to "1", "x" to "1")))
        )
    }
}
