package com.muyuchat.mca.agent.device

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.mca.agent.device.sensors.AgentSensorId
import com.muyuchat.mca.agent.device.sensors.AgentSensorReadMode
import com.muyuchat.mca.agent.device.sensors.AgentSensorReading
import com.muyuchat.mca.agent.device.sensors.AgentSensorResult
import com.muyuchat.mca.agent.device.sensors.AgentTorchState
import com.muyuchat.mca.agent.device.sms.AgentSmsRawMessage
import com.muyuchat.mca.agent.device.sms.AgentSmsRawQueryResult
import com.muyuchat.mca.agent.device.sms.AgentSmsReasonCode
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fixed route surface of the device-access lane: paths, verbs, JSON envelopes, refusal
 * codes and the "confirmation before every SMS query" rule. All collaborators are fakes, so this
 * never touches Android sensors, the camera or the SMS provider.
 */
class AgentDeviceCapabilityRoutesTest {

    private class FakeConsent(
        var sensors: Set<AgentSensorId> = emptySet(),
        var smsEnabled: Boolean = false,
        var skipSms: Boolean = false
    ) : AgentDeviceConsentGate {
        override fun sensorEnabled(id: AgentSensorId): Boolean = id in sensors
        override fun smsReadEnabled(): Boolean = smsEnabled
        override fun smsSkipConfirmationThisSession(): Boolean = skipSms
    }

    private class FakePermissionGate(var granted: Set<String> = emptySet()) : AgentDevicePermissionGate {
        override fun granted(permission: String?): Boolean =
            permission == null || permission in granted
    }

    private class FakeSensors(
        var readResult: AgentSensorResult = AgentSensorResult.Value(
            AgentSensorReading(AgentSensorId.LIGHT, 42.0, "lux", 1_730_000_000_000L)
        ),
        var torchResult: AgentSensorResult = AgentSensorResult.Toggled(AgentTorchState(true))
    ) : AgentSensorSource {
        val reads = mutableListOf<AgentSensorId>()
        var lastTorchRequest: Boolean? = null

        override suspend fun read(id: AgentSensorId): AgentSensorResult {
            reads += id
            return readResult
        }

        override suspend fun setTorch(on: Boolean): AgentSensorResult {
            lastTorchRequest = on
            return torchResult
        }
    }

    private class FakeSms(
        var result: AgentSmsRawQueryResult = AgentSmsRawQueryResult.Rows(
            listOf(
                AgentSmsRawMessage("+8613812345678", "您的验证码是 445566", 3_000L),
                AgentSmsRawMessage("10086", "本月流量提醒", 1_000L)
            )
        )
    ) : AgentSmsSource {
        var lastLimit: Int? = null
        var lastKeyword: String? = null

        override suspend fun query(limit: Int, keyword: String?): AgentSmsRawQueryResult {
            lastLimit = limit
            lastKeyword = keyword
            return result
        }
    }

    private class FakeConfirmation(var decision: Boolean = true) : AgentSmsConfirmationPort {
        var prompts = 0
        var lastDetail: String? = null

        override suspend fun confirm(detail: String): Boolean {
            prompts += 1
            lastDetail = detail
            return decision
        }
    }

    private fun table(
        consent: FakeConsent,
        permissions: FakePermissionGate,
        sensors: FakeSensors = FakeSensors(),
        sms: FakeSms = FakeSms(),
        confirmation: FakeConfirmation = FakeConfirmation()
    ): DshCapabilityRouteTable = AgentDeviceCapabilityRoutes(
        consent = consent,
        permissions = permissions,
        sensors = sensors,
        sms = sms,
        smsConfirmation = confirmation
    ).register(DshCapabilityRouteTable.builder()).build()

    private fun call(
        table: DshCapabilityRouteTable,
        path: String,
        method: DshCapabilityHttpMethod,
        query: Map<String, String> = emptyMap()
    ): JSONObject = runBlocking {
        val handler = requireNotNull(table.handlerFor(path)) { "no handler for $path" }
        val response = handler.handle(
            DshCapabilityRouteRequest(
                path = path,
                method = method,
                query = query,
                bodyJson = null,
                localToken = "dshc_test_token_1234567890",
                generation = 1L,
                receivedAtMillis = 0L
            )
        )
        JSONObject((response as DshCapabilityRouteResponse.Json).bodyJson)
    }

    @Test
    fun `route paths and verbs are fixed`() {
        assertEquals(
            listOf("/sensors", "/sensors/torch", "/sms"),
            AgentDeviceCapabilityRoutes.paths()
        )
        assertEquals(
            setOf(DshCapabilityHttpMethod.GET),
            AgentDeviceCapabilityRoutes.methodsFor("/sensors")
        )
        assertEquals(
            setOf(DshCapabilityHttpMethod.GET),
            AgentDeviceCapabilityRoutes.methodsFor("/sms")
        )
        assertEquals(
            setOf(DshCapabilityHttpMethod.POST),
            AgentDeviceCapabilityRoutes.methodsFor("/sensors/torch")
        )
    }

    @Test
    fun `status lists every capability and the sms switch`() {
        val routes = table(FakeConsent(), FakePermissionGate())
        val body = call(routes, "/sensors", DshCapabilityHttpMethod.GET)

        assertTrue(body.getBoolean("ok"))
        val sensors = body.getJSONArray("sensors")
        assertEquals(8, sensors.length())
        val sms = body.getJSONObject("sms")
        assertFalse(sms.getBoolean("enabled"))
        assertEquals("android.permission.READ_SMS", sms.getString("permission"))
        assertEquals(20, sms.getInt("maxResults"))
    }

    @Test
    fun `sensor read without consent is NOT_GRANTED with a settings hint`() {
        val body = call(
            table(FakeConsent(), FakePermissionGate()),
            "/sensors",
            DshCapabilityHttpMethod.GET,
            mapOf("id" to "light")
        )

        assertFalse(body.getBoolean("ok"))
        assertEquals("NOT_GRANTED", body.getString("errorCode"))
        assertTrue(body.getString("settingsHint").contains("设备传感器与位置"))
    }

    @Test
    fun `sensor read with consent returns one value with its timestamp`() {
        val body = call(
            table(FakeConsent(sensors = setOf(AgentSensorId.LIGHT)), FakePermissionGate()),
            "/sensors",
            DshCapabilityHttpMethod.GET,
            mapOf("id" to "light")
        )

        assertTrue(body.getBoolean("ok"))
        assertEquals("light", body.getString("sensor"))
        assertEquals(42.0, body.getDouble("value"), 0.0001)
        assertEquals("lux", body.getString("unit"))
        assertEquals(1_730_000_000_000L, body.getLong("at"))
    }

    @Test
    fun `unknown sensor id is an invalid request, not an empty success`() {
        val body = call(
            table(FakeConsent(sensors = setOf(AgentSensorId.LIGHT)), FakePermissionGate()),
            "/sensors",
            DshCapabilityHttpMethod.GET,
            mapOf("id" to "battery")
        )

        assertFalse(body.getBoolean("ok"))
        assertEquals("INVALID_REQUEST", body.getString("errorCode"))
    }

    @Test
    fun `missing runtime permission is reported before the platform read`() {
        val sensors = FakeSensors()
        val body = call(
            table(
                FakeConsent(sensors = setOf(AgentSensorId.STEP_COUNTER)),
                FakePermissionGate(granted = emptySet()),
                sensors
            ),
            "/sensors",
            DshCapabilityHttpMethod.GET,
            mapOf("id" to "step_counter")
        )

        assertEquals("PERMISSION_MISSING", body.getString("errorCode"))
        assertTrue("an unauthorised read must not reach the sensor", sensors.reads.isEmpty())
    }

    @Test
    fun `unknown query keys are rejected`() {
        val body = call(
            table(FakeConsent(), FakePermissionGate()),
            "/sensors",
            DshCapabilityHttpMethod.GET,
            mapOf("dump" to "all")
        )

        assertEquals("INVALID_REQUEST", body.getString("errorCode"))
    }

    @Test
    fun `torch is POST only and needs an explicit on flag`() {
        val routes = table(FakeConsent(sensors = setOf(AgentSensorId.TORCH)), FakePermissionGate())

        val wrongVerb = call(routes, "/sensors/torch", DshCapabilityHttpMethod.GET)
        assertFalse(wrongVerb.getBoolean("ok"))

        val missingFlag = call(routes, "/sensors/torch", DshCapabilityHttpMethod.POST)
        assertEquals("INVALID_REQUEST", missingFlag.getString("errorCode"))

        val notGranted = call(
            table(FakeConsent(), FakePermissionGate()),
            "/sensors/torch",
            DshCapabilityHttpMethod.POST,
            mapOf("on" to "1")
        )
        assertEquals("NOT_GRANTED", notGranted.getString("errorCode"))

        val sensors = FakeSensors()
        val toggled = call(
            table(FakeConsent(sensors = setOf(AgentSensorId.TORCH)), FakePermissionGate(), sensors),
            "/sensors/torch",
            DshCapabilityHttpMethod.POST,
            mapOf("on" to "1")
        )
        assertTrue(toggled.getBoolean("ok"))
        assertTrue(toggled.getBoolean("enabled"))
        assertEquals(true, sensors.lastTorchRequest)
    }

    @Test
    fun `sms query needs consent permission and a confirmation`() {
        val offConsent = call(
            table(FakeConsent(), FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION))),
            "/sms",
            DshCapabilityHttpMethod.GET
        )
        assertEquals("NOT_GRANTED", offConsent.getString("errorCode"))

        val noPermission = call(
            table(FakeConsent(smsEnabled = true), FakePermissionGate(granted = emptySet())),
            "/sms",
            DshCapabilityHttpMethod.GET
        )
        assertEquals("PERMISSION_MISSING", noPermission.getString("errorCode"))
        assertTrue(noPermission.getString("settingsHint").contains("短信"))

        val confirmation = FakeConfirmation(decision = false)
        val denied = call(
            table(
                FakeConsent(smsEnabled = true),
                FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION)),
                confirmation = confirmation
            ),
            "/sms",
            DshCapabilityHttpMethod.GET
        )
        assertEquals("CONFIRMATION_DENIED", denied.getString("errorCode"))
        assertEquals(1, confirmation.prompts)
        assertTrue(confirmation.lastDetail!!.contains("120"))
    }

    @Test
    fun `sms success returns masked senders timestamps and previews`() {
        val confirmation = FakeConfirmation(decision = true)
        val body = call(
            table(
                FakeConsent(smsEnabled = true),
                FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION)),
                confirmation = confirmation
            ),
            "/sms",
            DshCapabilityHttpMethod.GET,
            mapOf("limit" to "5", "keyword" to "验证码")
        )

        assertTrue(body.getBoolean("ok"))
        assertEquals(1, body.getInt("count"))
        assertEquals(5, body.getInt("limit"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("****5678", message.getString("sender"))
        assertEquals(3_000L, message.getLong("at"))
        assertTrue(message.getString("preview").contains("445566"))
        assertFalse(message.getString("preview").contains("13812345678"))
    }

    @Test
    fun `sms limit is clamped and an over long keyword is refused`() {
        val sms = FakeSms()
        val body = call(
            table(
                FakeConsent(smsEnabled = true),
                FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION)),
                sms = sms
            ),
            "/sms",
            DshCapabilityHttpMethod.GET,
            mapOf("limit" to "999")
        )
        assertEquals(20, body.getInt("limit"))
        assertEquals(20, sms.lastLimit)

        val refused = call(
            table(
                FakeConsent(smsEnabled = true),
                FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION))
            ),
            "/sms",
            DshCapabilityHttpMethod.GET,
            mapOf("keyword" to "长".repeat(200))
        )
        assertEquals("QUERY_FAILED", refused.getString("errorCode"))
    }

    @Test
    fun `sms provider failure is not an empty inbox`() {
        val body = call(
            table(
                FakeConsent(smsEnabled = true),
                FakePermissionGate(granted = setOf(AgentDeviceCapabilityRoutes.SMS_PERMISSION)),
                sms = FakeSms(
                    result = AgentSmsRawQueryResult.Failed(
                        AgentSmsReasonCode.QUERY_FAILED,
                        "系统短信库不可用"
                    )
                )
            ),
            "/sms",
            DshCapabilityHttpMethod.GET
        )

        assertFalse(body.getBoolean("ok"))
        assertEquals("QUERY_FAILED", body.getString("errorCode"))
    }

    @Test
    fun `console summary counts enabled capabilities without leaking values`() {
        val summary = AgentDeviceCapabilityRoutes.summaryDetail(
            FakeConsent(
                sensors = setOf(AgentSensorId.LIGHT, AgentSensorId.LOCATION),
                smsEnabled = true
            )
        )

        assertTrue(summary.contains("sensors=2/8 on"))
        assertTrue(summary.contains("sms=on"))
        assertFalse(summary.contains("lux"))
    }

    @Test
    fun `the sensor status document marks permission gated entries`() {
        val body = call(
            table(FakeConsent(), FakePermissionGate(granted = setOf("android.permission.ACCESS_FINE_LOCATION"))),
            "/sensors",
            DshCapabilityHttpMethod.GET
        )
        val sensors = body.getJSONArray("sensors")
        for (index in 0 until sensors.length()) {
            val entry = sensors.getJSONObject(index)
            val mode = entry.getString("mode")
            if (mode == AgentSensorReadMode.SAMPLE.name.lowercase()) {
                assertTrue("sample sensors need no permission", entry.isNull("permission"))
                assertTrue(entry.getBoolean("permissionGranted"))
            }
        }
    }
}
