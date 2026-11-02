package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceInfoToolTest {

    private val descriptor = AgentDeviceInfoTool.DESCRIPTOR

    private fun command(
        toolKey: String = descriptor.toolKey,
        targetDescriptor: com.muyuchat.core.agent.contract.AgentToolDescriptor = descriptor
    ) = FrozenToolCommand(
        invocationId = "inv-device",
        runId = "run-device",
        stepId = "step-device",
        callOrdinal = 0,
        toolKey = toolKey,
        toolVersion = targetDescriptor.version,
        schemaHash = targetDescriptor.schemaHash,
        normalizedArgumentsJson = "{}",
        argumentsHash = "args-device",
        resourceFingerprint = "scope-device",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = AgentToolContext(
        taskId = "task-device",
        workspaceId = "workspace-device",
        runId = "run-device",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    private fun tool() = AgentDeviceInfoTool(
        source = DeviceInfoSource {
            DeviceInfoSnapshot(
                manufacturer = "Google",
                model = "Pixel",
                androidRelease = "17",
                sdkInt = 37,
                supportedAbis = listOf("arm64-v8a")
            )
        }
    )

    @Test
    fun readsCoarseDeviceFactsWithoutAContextOrPermission() = runBlocking {
        val result = tool().execute(command(), context())

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertEquals("Google", data.getString("manufacturer"))
        assertEquals("Pixel", data.getString("model"))
        assertEquals("17", data.getString("androidRelease"))
        assertEquals(37, data.getInt("sdkInt"))
        assertEquals("arm64-v8a", data.getJSONArray("supportedAbis").getString(0))
        assertTrue(result.possibleSideEffects.not())
    }

    @Test
    fun aMismatchedFrozenCommandIsNotExecuted() = runBlocking {
        val result = tool().execute(command(toolKey = "workspace.list"), context())
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
    }

    @Test
    fun theNormalizerAcceptsOnlyAnEmptyObject() {
        val normalizer = AgentDeviceInfoArgumentNormalizer()
        val accepted = normalizer.normalize(descriptor, "{}")
        assertTrue(accepted is com.muyuchat.mca.agent.security.AgentArgumentNormalization.Normalized)
        assertEquals(
            "{}",
            (accepted as com.muyuchat.mca.agent.security.AgentArgumentNormalization.Normalized)
                .normalizedArgumentsJson
        )

        assertTrue(
            normalizer.normalize(descriptor, """{"path":"x"}""") is
                com.muyuchat.mca.agent.security.AgentArgumentNormalization.Rejected
        )
    }
    @Test
    fun statusToolReturnsOnlyCoarsePowerNetworkAndStorageFacts() = runBlocking {
        val tool = AgentDeviceStatusTool(
            source = DeviceStatusSource {
                DeviceStatusSnapshot(
                    batteryPercent = 72,
                    charging = true,
                    networkType = "wifi",
                    storageFreeBytes = 1_000L,
                    storageTotalBytes = 4_000L
                )
            }
        )
        val result = tool.execute(
            command(toolKey = AgentDeviceStatusTool.TOOL_KEY, targetDescriptor = AgentDeviceStatusTool.DESCRIPTOR),
            context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertEquals(72, data.getInt("batteryPercent"))
        assertTrue(data.getBoolean("charging"))
        assertEquals("wifi", data.getString("networkType"))
        assertEquals(1_000L, data.getLong("storageFreeBytes"))
        assertTrue(result.possibleSideEffects.not())
    }

    @Test
    fun theNormalizerAcceptsAnEmptyObjectForDeviceStatus() {
        val normalizer = AgentDeviceInfoArgumentNormalizer()
        val accepted = normalizer.normalize(AgentDeviceStatusTool.DESCRIPTOR, "{}")
        assertTrue(accepted is com.muyuchat.mca.agent.security.AgentArgumentNormalization.Normalized)
        assertTrue(
            normalizer.normalize(AgentDeviceStatusTool.DESCRIPTOR, """{"x":1}""") is
                com.muyuchat.mca.agent.security.AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun deviceScopesDeclareTheEffectTheContractRequires() {
        for (precondition in listOf(AgentDeviceScopes.info(), AgentDeviceScopes.status())) {
            assertEquals(
                setOf(com.muyuchat.core.agent.contract.AgentEffectKind.DEVICE_ACTION),
                precondition.effects
            )
            val scope = precondition.scopes.single()
            assertTrue(scope is com.muyuchat.core.agent.contract.AgentResourceScope.Device)
            val summary = (scope as com.muyuchat.core.agent.contract.AgentResourceScope.Device)
                .userVisibleSummary
            assertTrue(summary.isNotBlank())
            assertTrue(
                com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
                    .canonicalJson(precondition)
                    .contains("\"kind\":\"device\"")
            )
        }
    }

}
