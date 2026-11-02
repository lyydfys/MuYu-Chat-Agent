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

class AgentDeviceNotificationToolTest {

    private val descriptor = AgentDeviceNotificationTool.DESCRIPTOR
    private val posted = mutableListOf<DeviceNotification>()

    private fun command() = FrozenToolCommand(
        invocationId = "inv-notify",
        runId = "run-notify",
        stepId = "step-notify",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = """{"body":"任务已完成","title":"Agent"}""",
        argumentsHash = "args-notify",
        resourceFingerprint = "scope-notify",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = AgentToolContext(
        taskId = "task-notify",
        workspaceId = "workspace-notify",
        runId = "run-notify",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    private fun sink(canPost: Boolean, accepts: Boolean = true) = object : DeviceNotificationSink {
        override fun canPost(): Boolean = canPost
        override fun post(notification: DeviceNotification): Boolean {
            posted += notification
            return accepts
        }
    }

    @Test
    fun postsOneApprovedNotification() = runBlocking {
        val result = AgentDeviceNotificationTool(sink(canPost = true)).execute(command(), context())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(listOf(DeviceNotification("Agent", "任务已完成")), posted)
        val data = JSONObject(result.dataJson.orEmpty()).getJSONObject("data")
        assertTrue(data.getBoolean("posted"))
    }

    @Test
    fun aMissingPermissionIsDeniedWithoutPosting() = runBlocking {
        val result = AgentDeviceNotificationTool(sink(canPost = false)).execute(command(), context())
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals("DEVICE_NOTIFICATION_PERMISSION_DENIED", result.errorCode)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun aSystemRefusalIsReportedAsFailure() = runBlocking {
        val result = AgentDeviceNotificationTool(sink(canPost = true, accepts = false))
            .execute(command(), context())
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals(1, posted.size)
    }

    @Test
    fun theNormalizerRequiresExactlyTitleAndBody() {
        val normalizer = AgentDeviceNotificationArgumentNormalizer()
        val accepted = normalizer.normalize(
            descriptor,
            """{"title":"Agent","body":"任务已完成"}"""
        )
        assertTrue(accepted is AgentArgumentNormalization.Normalized)
        val normalized = accepted as AgentArgumentNormalization.Normalized
        assertTrue(normalized.normalizedArgumentsJson.contains("\"title\""))
        assertTrue(normalized.normalizedArgumentsJson.contains("\"body\""))

        assertTrue(
            normalizer.normalize(descriptor, """{"title":"x"}""") is
                AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(
                descriptor,
                """{"title":"x","body":"y","extra":"z"}"""
            ) is AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"title":"","body":"y"}""") is
                AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun theNotificationScopeBindsTheBodyHash() {
        val scope = requireNotNull(
            AgentDeviceScopes.notification("""{"title":"Agent","body":"任务已完成"}""")
        )
        val device = scope.scopes.single() as com.muyuchat.core.agent.contract.AgentResourceScope.Device
        assertEquals("device.notify", device.operation)
        assertEquals(64, device.selectorHash?.length)
        assertTrue(device.userVisibleSummary.contains("Agent"))
        assertTrue(
            AgentDeviceScopes.notification("""{"title":"Agent"}""") == null
        )
    }
}
