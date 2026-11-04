package com.muyuchat.mca.agent.device

import com.muyuchat.mca.agent.device.sensors.AgentSensorCatalog
import com.muyuchat.mca.agent.device.sensors.AgentSensorId
import com.muyuchat.mca.agent.device.sensors.AgentSensorReadMode
import com.muyuchat.mca.agent.device.sensors.AgentSensorReasonCode
import com.muyuchat.mca.agent.device.sensors.AgentSensorReadings
import com.muyuchat.mca.agent.device.sensors.AgentSensorResult
import com.muyuchat.mca.agent.device.sensors.AgentSensorSample
import com.muyuchat.mca.agent.device.sensors.AgentStepBaseline
import com.muyuchat.mca.agent.device.sensors.AgentStepCounterPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the DSH sensor/location/torch lane without a device.
 *
 * The product rules being fixed here:
 *  - the catalogue is complete (every advertised capability has an id, a name, a unit and a
 *    description) and gated capabilities declare their exact Android permission;
 *  - consent is checked before permission, and permission before the platform sample, so a read
 *    can never silently skip a level;
 *  - a missing sample is a typed refusal with a "go to system settings" hint, never a fake value;
 *  - the step counter is "today" through a persisted baseline and is clamped to a sane range.
 */
class AgentSensorContractTest {

    private val light = AgentSensorCatalog.capability(AgentSensorId.LIGHT)
    private val steps = AgentSensorCatalog.capability(AgentSensorId.STEP_COUNTER)
    private val location = AgentSensorCatalog.capability(AgentSensorId.LOCATION)
    private val torch = AgentSensorCatalog.capability(AgentSensorId.TORCH)

    @Test
    fun `catalogue lists every capability with a name unit and description`() {
        assertEquals(8, AgentSensorCatalog.all.size)
        assertEquals(AgentSensorId.entries.toSet(), AgentSensorCatalog.all.map { it.id }.toSet())
        AgentSensorCatalog.all.forEach { capability ->
            assertTrue("${capability.id} needs a display name", capability.displayName.isNotBlank())
            assertTrue("${capability.id} needs a description", capability.description.isNotBlank())
            if (capability.readMode != AgentSensorReadMode.TOGGLE) {
                assertTrue("${capability.id} needs a unit", capability.unit.isNotBlank())
            }
        }
    }

    @Test
    fun `permission gated capabilities declare the exact android permission`() {
        val gated = AgentSensorCatalog.all.filter { it.permission != null }
        assertEquals(
            setOf(AgentSensorId.STEP_COUNTER, AgentSensorId.LOCATION),
            gated.map { it.id }.toSet()
        )
        assertEquals("android.permission.ACTIVITY_RECOGNITION", steps.permission)
        assertEquals("android.permission.ACCESS_FINE_LOCATION", location.permission)
        assertNull("light needs no runtime permission", light.permission)
        assertNull("torch needs no CAMERA permission", torch.permission)
        assertTrue(gated.all { it.permissionLabel?.isNotBlank() == true })
    }

    @Test
    fun `wire names round trip and unknown names are rejected`() {
        AgentSensorId.entries.forEach { id ->
            assertEquals(id, AgentSensorCatalog.fromWireName(AgentSensorCatalog.wireName(id)))
        }
        assertEquals(AgentSensorId.STEP_COUNTER, AgentSensorCatalog.fromWireName(" Step_Counter "))
        assertNull(AgentSensorCatalog.fromWireName("battery"))
        assertNull(AgentSensorCatalog.fromWireName(null))
    }

    @Test
    fun `consent is checked before the platform sample`() {
        val refused = AgentSensorReadings.evaluate(
            capability = light,
            consentEnabled = false,
            permissionGranted = true,
            sample = AgentSensorSample(value = 100.0, capturedAtMillis = 1_000L)
        )

        assertTrue(refused is AgentSensorResult.Refused)
        val detail = refused as AgentSensorResult.Refused
        assertEquals(AgentSensorReasonCode.NOT_GRANTED, detail.reason)
        assertNotNull("an off switch must tell the user where to turn it on", detail.settingsHint)
        assertTrue(detail.settingsHint!!.contains("设置"))
    }

    @Test
    fun `missing runtime permission is not granted and names the permission`() {
        val refused = AgentSensorReadings.evaluate(
            capability = steps,
            consentEnabled = true,
            permissionGranted = false,
            sample = AgentSensorSample(value = 10.0, capturedAtMillis = 1_000L)
        )

        val detail = refused as AgentSensorResult.Refused
        assertEquals(AgentSensorReasonCode.PERMISSION_MISSING, detail.reason)
        assertTrue(detail.detail.contains("身体活动"))
        assertTrue(detail.settingsHint!!.contains("系统设置"))
    }

    @Test
    fun `single sample keeps the platform timestamp and unit`() {
        val result = AgentSensorReadings.evaluate(
            capability = light,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(value = 321.5, capturedAtMillis = 1_730_000_123_456L)
        )

        val reading = (result as AgentSensorResult.Value).reading
        assertEquals(AgentSensorId.LIGHT, reading.sensorId)
        assertEquals(321.5, reading.value, 0.0001)
        assertEquals("lux", reading.unit)
        assertEquals(1_730_000_123_456L, reading.capturedAtMillis)
    }

    @Test
    fun `missing location fix is unavailable rather than a fake zero`() {
        val refused = AgentSensorReadings.evaluate(
            capability = location,
            consentEnabled = true,
            permissionGranted = true,
            sample = null
        )

        val detail = refused as AgentSensorResult.Refused
        assertEquals(AgentSensorReasonCode.LOCATION_UNAVAILABLE, detail.reason)
        assertTrue(detail.detail.contains("最近已知位置"))
        assertTrue(detail.settingsHint!!.contains("位置"))
    }

    @Test
    fun `location keeps latitude and reports longitude in the detail`() {
        val result = AgentSensorReadings.evaluate(
            capability = location,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(
                value = 31.2304,
                secondValue = 121.4737,
                accuracyMeters = 35f,
                capturedAtMillis = 1_730_000_000_000L
            )
        )

        val reading = (result as AgentSensorResult.Value).reading
        assertEquals(31.2304, reading.value, 0.0001)
        assertTrue(reading.detail!!.contains("121.4737"))
        assertTrue(reading.detail!!.contains("精度约 35 米"))
        assertTrue("must say it is a cached fix", reading.detail!!.contains("最近已知位置"))
    }

    @Test
    fun `out of range coordinates are refused instead of clamped`() {
        val refused = AgentSensorReadings.evaluate(
            capability = location,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(value = 95.0, secondValue = 121.0, capturedAtMillis = 1L)
        )

        assertEquals(
            AgentSensorReasonCode.LOCATION_UNAVAILABLE,
            (refused as AgentSensorResult.Refused).reason
        )
    }

    @Test
    fun `negative step reading is clamped to zero and says so`() {
        val result = AgentSensorReadings.evaluate(
            capability = steps,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(value = -3.0, capturedAtMillis = 1L)
        )

        val reading = (result as AgentSensorResult.Value).reading
        assertEquals(0.0, reading.value, 0.0001)
        assertTrue(reading.detail!!.contains("钳制"))
    }

    @Test
    fun `step counter turns the boot counter into today with a baseline`() {
        val first = AgentStepCounterPolicy.dailySteps(20_000L, counter = 5_000, baseline = null)
        assertEquals(0, first.steps)
        assertEquals(AgentStepBaseline(20_000L, 5_000), first.nextBaseline)
        assertTrue(first.detail.contains("基线"))

        val later = AgentStepCounterPolicy.dailySteps(
            todayEpochDay = 20_000L,
            counter = 5_420,
            baseline = AgentStepBaseline(20_000L, 5_000)
        )
        assertEquals(420, later.steps)
        assertNull("no write is needed while the day and baseline are stable", later.nextBaseline)

        val nextDay = AgentStepCounterPolicy.dailySteps(
            todayEpochDay = 20_001L,
            counter = 6_000,
            baseline = AgentStepBaseline(20_000L, 5_000)
        )
        assertEquals(0, nextDay.steps)
        assertEquals(AgentStepBaseline(20_001L, 6_000), nextDay.nextBaseline)

        val reset = AgentStepCounterPolicy.dailySteps(
            todayEpochDay = 20_000L,
            counter = 12,
            baseline = AgentStepBaseline(20_000L, 9_000)
        )
        assertEquals(0, reset.steps)
        assertEquals(AgentStepBaseline(20_000L, 12), reset.nextBaseline)

        val capped = AgentStepCounterPolicy.dailySteps(
            todayEpochDay = 20_000L,
            counter = 2_000_000,
            baseline = AgentStepBaseline(20_000L, 0)
        )
        assertEquals(AgentSensorReadings.MAX_DAILY_STEPS, capped.steps)

        val missing = AgentStepCounterPolicy.dailySteps(20_000L, counter = null, baseline = null)
        assertEquals(0, missing.steps)
        assertNull(missing.nextBaseline)
    }

    @Test
    fun `torch switch reports the platform state and refuses without consent`() {
        val refused = AgentSensorReadings.evaluateTorch(
            consentEnabled = false,
            requestedOn = true,
            platformState = true
        )
        assertEquals(AgentSensorReasonCode.NOT_GRANTED, (refused as AgentSensorResult.Refused).reason)

        val unavailable = AgentSensorReadings.evaluateTorch(
            consentEnabled = true,
            requestedOn = true,
            platformState = null,
            failureDetail = "闪光灯被占用"
        )
        val unavailableDetail = unavailable as AgentSensorResult.Refused
        assertEquals(AgentSensorReasonCode.TORCH_UNAVAILABLE, unavailableDetail.reason)
        assertEquals("闪光灯被占用", unavailableDetail.detail)

        val toggled = AgentSensorReadings.evaluateTorch(
            consentEnabled = true,
            requestedOn = true,
            platformState = true
        )
        assertEquals(true, (toggled as AgentSensorResult.Toggled).state.enabled)
    }

    @Test
    fun `toggle capability cannot be read as a sample`() {
        val refused = AgentSensorReadings.evaluate(
            capability = torch,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(value = 1.0, capturedAtMillis = 1L)
        )
        assertEquals(
            AgentSensorReasonCode.INVALID_REQUEST,
            (refused as AgentSensorResult.Refused).reason
        )
    }

    @Test
    fun `unavailable sample is typed for every sampling mode`() {
        listOf(light, AgentSensorCatalog.capability(AgentSensorId.BAROMETER)).forEach { capability ->
            val refused = AgentSensorReadings.evaluate(
                capability = capability,
                consentEnabled = true,
                permissionGranted = true,
                sample = null
            )
            assertEquals(
                AgentSensorReasonCode.SENSOR_UNAVAILABLE,
                (refused as AgentSensorResult.Refused).reason
            )
        }
    }
}
