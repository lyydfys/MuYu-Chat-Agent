package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshSettingActionTest {

    private val allowedKeys = setOf(
        "ok", "error", "namespace", "key", "value", "oldValue", "newValue", "stream", "level", "max"
    )

    private suspend fun handle(
        port: FakeSettingsPort,
        body: String?,
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST
    ): JSONObject = responseJson(
        DshSettingAction(port).handle(routeRequest(DshSettingAction.ROUTE, method, emptyMap(), body))
    )

    @Test
    fun writes_a_system_setting_and_reports_before_and_after() = runTest {
        val port = FakeSettingsPort(outcome = DshSettingWriteOutcome("128", "64"))
        val response = handle(
            port,
            jsonBody("key" to "screen_brightness", "value" to "64")
        )

        assertTrue(response.getBoolean("ok"))
        assertEquals("system", response.getString("namespace"))
        assertEquals("screen_brightness", response.getString("key"))
        assertEquals("128", response.getString("oldValue"))
        assertEquals("64", response.getString("newValue"))
        assertEquals(listOf("screen_brightness" to "64"), port.writes)
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun reports_stream_level_and_max_for_a_volume_key() = runTest {
        val port = FakeSettingsPort(
            outcome = DshSettingWriteOutcome(
                oldValue = "4",
                newValue = "7",
                stream = "volume_music",
                level = 7,
                maxLevel = 15
            )
        )
        val response = handle(port, jsonBody("key" to "volume_music", "value" to "7"))

        assertTrue(response.getBoolean("ok"))
        assertEquals("volume_music", response.getString("stream"))
        assertEquals(7, response.getInt("level"))
        assertEquals(15, response.getInt("max"))
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun rejects_shell_metacharacters_in_the_key() = runTest {
        val port = FakeSettingsPort()
        val response = handle(
            port,
            jsonBody("key" to "screen_brightness; rm -rf /", "value" to "1")
        )

        assertEquals("INVALID_REQUEST", errorCodeOf(response))
        assertTrue(port.writes.isEmpty())
        assertTrue(port.accessQueries.isEmpty())
    }

    @Test
    fun refuses_when_write_settings_is_not_granted() = runTest {
        val port = FakeSettingsPort(accessState = DshCapabilityState.DENIED)
        val response = handle(port, jsonBody("key" to "screen_off_timeout", "value" to "60000"))

        assertFalse(response.getBoolean("ok"))
        assertEquals("PERMISSION_DENIED", errorCodeOf(response))
        assertEquals("system", response.getString("namespace"))
        assertEquals("screen_off_timeout", response.getString("key"))
        assertTrue(port.writes.isEmpty())
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun maps_each_permission_state_to_its_own_wire_code() = runTest {
        assertEquals(
            "PERMISSION_DENIED",
            errorCodeOf(
                handle(
                    FakeSettingsPort(accessState = DshCapabilityState.NOT_DECLARED),
                    jsonBody("key" to "k", "value" to "v")
                )
            )
        )
        assertEquals(
            "NOT_ENABLED",
            errorCodeOf(
                handle(
                    FakeSettingsPort(accessState = DshCapabilityState.NOT_ENABLED),
                    jsonBody("key" to "k", "value" to "v")
                )
            )
        )
        assertEquals(
            "CAPABILITY_UNAVAILABLE",
            errorCodeOf(
                handle(
                    FakeSettingsPort(accessState = DshCapabilityState.UNSUPPORTED),
                    jsonBody("key" to "k", "value" to "v")
                )
            )
        )
    }

    @Test
    fun propagates_a_platform_rejection() = runTest {
        val port = FakeSettingsPort(
            rejection = DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.INTERNAL,
                "系统拒绝了设置写入"
            )
        )
        val response = handle(port, jsonBody("key" to "k", "value" to "v"))

        assertEquals("INTERNAL", errorCodeOf(response))
        assertEquals("k", response.getString("key"))
    }

    @Test
    fun rejects_a_missing_value_and_an_oversized_value() = runTest {
        assertEquals("INVALID_REQUEST", errorCodeOf(handle(FakeSettingsPort(), jsonBody("key" to "k"))))
        assertEquals(
            "TOO_LARGE",
            errorCodeOf(
                handle(
                    FakeSettingsPort(),
                    jsonBody("key" to "k", "value" to "v".repeat(513))
                )
            )
        )
    }

    @Test
    fun refuses_get() = runTest {
        val response = handle(
            FakeSettingsPort(),
            body = null,
            method = DshCapabilityHttpMethod.GET
        )
        assertEquals("METHOD_NOT_ALLOWED", errorCodeOf(response))
    }
}
