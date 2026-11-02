package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceNotificationSettingsToolTest {

    private val descriptor = AgentDeviceNotificationSettingsTool.DESCRIPTOR
    private var opened = 0

    private fun command() = FrozenToolCommand(
        invocationId = "inv-settings",
        runId = "run-settings",
        stepId = "step-settings",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = "{}",
        argumentsHash = "args-settings",
        resourceFingerprint = "scope-settings",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = AgentToolContext(
        taskId = "task-settings",
        workspaceId = "workspace-settings",
        runId = "run-settings",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    @Test
    fun opensTheSettingsScreen() = runBlocking {
        val tool = AgentDeviceNotificationSettingsTool(
            launcher = DeviceAppSettingsLauncher { opened += 1; true }
        )
        val result = tool.execute(command(), context())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(1, opened)
    }

    @Test
    fun aLauncherRefusalIsAFailure() = runBlocking {
        val result = AgentDeviceNotificationSettingsTool(
            launcher = DeviceAppSettingsLauncher { false }
        ).execute(command(), context())
        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("DEVICE_SETTINGS_LAUNCH_FAILED", result.errorCode)
    }

    @Test
    fun theNormalizerAcceptsAnEmptyObject() {
        val normalizer = AgentDeviceInfoArgumentNormalizer()
        assertTrue(
            normalizer.normalize(descriptor, "{}") is AgentArgumentNormalization.Normalized
        )
        assertTrue(
            normalizer.normalize(descriptor, """{"x":1}""") is AgentArgumentNormalization.Rejected
        )
    }

    @Test
    fun theSettingsScopeIsDeviceAction() {
        val scope = AgentDeviceScopes.notificationSettings()
        assertEquals(
            setOf(com.muyuchat.core.agent.contract.AgentEffectKind.DEVICE_ACTION),
            scope.effects
        )
        val device = scope.scopes.single() as com.muyuchat.core.agent.contract.AgentResourceScope.Device
        assertEquals("device.open_notification_settings", device.operation)
        assertTrue(device.userVisibleSummary.contains("通知设置"))
    }
}
