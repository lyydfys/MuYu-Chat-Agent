package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceClipboardToolTest {

    private val descriptor = AgentDeviceClipboardTool.DESCRIPTOR
    private val written = mutableListOf<String>()

    private fun command(text: String = "任务产物已准备") = FrozenToolCommand(
        invocationId = "inv-clip",
        runId = "run-clip",
        stepId = "step-clip",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"text":"$text"}""",
        argumentsHash = "args-clip",
        resourceFingerprint = "scope-clip",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = AgentToolContext(
        taskId = "task-clip",
        workspaceId = "workspace-clip",
        runId = "run-clip",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    @Test
    fun writesOneApprovedText() = runBlocking {
        val tool = AgentDeviceClipboardTool(
            sink = DeviceClipboardSink { text -> written += text; true }
        )
        val result = tool.execute(command(), context())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(listOf("任务产物已准备"), written)
        assertTrue(JSONObject(result.dataJson.orEmpty()).getJSONObject("data").getBoolean("written"))
    }

    @Test
    fun aSystemRefusalIsReportedAsFailure() = runBlocking {
        val result = AgentDeviceClipboardTool(sink = DeviceClipboardSink { false })
            .execute(command(), context())
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("DEVICE_CLIPBOARD_WRITE_FAILED", result.errorCode)
    }

    @Test
    fun theNormalizerRequiresExactlyOneNonEmptyText() {
        val normalizer = AgentDeviceClipboardArgumentNormalizer()
        assertTrue(
            normalizer.normalize(descriptor, """{"text":"hello"}""") is
                AgentArgumentNormalization.Normalized
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"text":""}""") is
                AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"text":"a","x":1}""") is
                AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun theClipboardScopeNeverRepeatsTheText() {
        val scope = requireNotNull(
            AgentDeviceScopes.clipboardWrite("""{"text":"secret-value"}""")
        )
        val device = scope.scopes.single() as com.muyuchat.core.agent.contract.AgentResourceScope.Device
        assertEquals("device.clipboard_write", device.operation)
        assertEquals(64, device.selectorHash?.length)
        assertTrue(device.userVisibleSummary.contains("12"))
        assertTrue(device.userVisibleSummary.contains("secret").not())
    }
}
