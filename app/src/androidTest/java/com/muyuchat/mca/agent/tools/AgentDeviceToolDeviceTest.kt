package com.muyuchat.mca.agent.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-only proof that the Build/Battery/Connectivity/StatFs sources work on real Android. */
class AgentDeviceToolDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun command(descriptor: com.muyuchat.core.agent.contract.AgentToolDescriptor) = FrozenToolCommand(
        invocationId = "inv-" + descriptor.toolKey,
        runId = "run-device-tools",
        stepId = "step-device-tools",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = "{}",
        argumentsHash = "args-device-tools",
        resourceFingerprint = "scope-device-tools",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun toolContext() = AgentToolContext(
        taskId = "task-device-tools",
        workspaceId = "workspace-device-tools",
        runId = "run-device-tools",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    @Test
    fun buildFactsAreRealOnDevice() = runBlocking {
        val tool = AgentDeviceInfoTool(DeviceInfoSource.androidBuild())
        val result = tool.execute(command(tool.descriptor), toolContext())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertTrue(data.getString("manufacturer").isNotBlank())
        assertTrue(data.getString("model").isNotBlank())
        assertTrue(data.getString("androidRelease").isNotBlank())
        assertTrue(data.getInt("sdkInt") >= 29)
        assertFalse(data.has("serial"))
        assertFalse(data.has("installedPackages"))
        Unit
    }

    @Test
    fun statusFactsAreRealOnDevice() = runBlocking {
        val tool = AgentDeviceStatusTool(DeviceStatusSource.android(context))
        val result = tool.execute(command(tool.descriptor), toolContext())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertTrue(data.getLong("storageTotalBytes") > 0L)
        assertTrue(data.getLong("storageFreeBytes") >= 0L)
        if (!data.isNull("batteryPercent")) {
            val percent = data.getInt("batteryPercent")
            assertTrue("battery percent $percent", percent in 0..100)
        }
        assertFalse(data.has("ssid"))
        assertFalse(data.has("account"))
        Unit
    }
}
