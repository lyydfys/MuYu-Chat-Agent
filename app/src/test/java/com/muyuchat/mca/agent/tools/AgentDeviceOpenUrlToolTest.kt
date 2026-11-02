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

class AgentDeviceOpenUrlToolTest {

    private val descriptor = AgentDeviceOpenUrlTool.DESCRIPTOR
    private val opened = mutableListOf<String>()

    private fun command(url: String = "https://example.com/notes") = FrozenToolCommand(
        invocationId = "inv-open-url",
        runId = "run-open-url",
        stepId = "step-open-url",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"url":"$url"}""",
        argumentsHash = "args-open-url",
        resourceFingerprint = "scope-open-url",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = AgentToolContext(
        taskId = "task-open-url",
        workspaceId = "workspace-open-url",
        runId = "run-open-url",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    @Test
    fun opensOneApprovedWebUrl() = runBlocking {
        val tool = AgentDeviceOpenUrlTool(
            launcher = DeviceUrlLauncher { url -> opened += url; true }
        )
        val result = tool.execute(command(), context())

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(listOf("https://example.com/notes"), opened)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertTrue(data.getBoolean("opened"))
        assertEquals("https://example.com", data.getString("origin"))
    }

    @Test
    fun aLauncherRefusalIsAFailure() = runBlocking {
        val tool = AgentDeviceOpenUrlTool(launcher = DeviceUrlLauncher { false })
        val result = tool.execute(command(), context())
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("DEVICE_URL_LAUNCH_FAILED", result.errorCode)
    }

    @Test
    fun aNonWebSchemeIsDeniedBeforeLaunching() = runBlocking {
        val tool = AgentDeviceOpenUrlTool(launcher = DeviceUrlLauncher { true })
        val result = tool.execute(command("file:///etc/passwd"), context())
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("DEVICE_URL_UNSUPPORTED", result.errorCode)
    }

    @Test
    fun theNormalizerOnlyAcceptsPlainWebUrls() {
        val normalizer = AgentDeviceOpenUrlArgumentNormalizer()
        val accepted = normalizer.normalize(descriptor, """{"url":"https://example.com/a?b=1"}""")
        assertTrue(accepted is AgentArgumentNormalization.Normalized)
        assertTrue(
            normalizer.normalize(descriptor, """{"url":"file:///etc/passwd"}""") is
                AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"url":"javascript:alert(1)"}""") is
                AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"url":"https://user:pass@example.com/"}""") is
                AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"url":"https://example.com/","x":1}""") is
                AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun theUrlScopeBindsTheUrlHashAndShowsOnlyTheOrigin() {
        val scope = requireNotNull(
            AgentDeviceScopes.openUrl("""{"url":"https://example.com/private?token=secret"}""")
        )
        val device = scope.scopes.single() as com.muyuchat.core.agent.contract.AgentResourceScope.Device
        assertEquals("device.open_url", device.operation)
        assertEquals(64, device.selectorHash?.length)
        assertEquals("打开链接：https://example.com", device.userVisibleSummary)
        assertTrue(
            AgentDeviceScopes.openUrl("""{"url":"data:text/html,x"}""") == null
        )
    }
}
