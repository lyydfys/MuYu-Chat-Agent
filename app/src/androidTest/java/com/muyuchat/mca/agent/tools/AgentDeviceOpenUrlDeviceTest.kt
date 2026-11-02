package com.muyuchat.mca.agent.tools

import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Device-only proof that ACTION_VIEW accepts our approved web URL. */
class AgentDeviceOpenUrlDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun approvedUrlReachesTheSystemViewer() = runBlocking {
        val descriptor = AgentDeviceOpenUrlTool.DESCRIPTOR
        val command = FrozenToolCommand(
            invocationId = "inv-device-url",
            runId = "run-device-url",
            stepId = "step-device-url",
            callOrdinal = 0,
            toolKey = descriptor.toolKey,
            toolVersion = descriptor.version,
            schemaHash = descriptor.schemaHash,
            normalizedArgumentsJson = """{"url":"https://example.com/"}""",
            argumentsHash = "args-device-url",
            resourceFingerprint = "scope-device-url",
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = Long.MAX_VALUE
        )
        val tool = AgentDeviceOpenUrlTool(DeviceUrlLauncher.android(context))
        try {
            val result = tool.execute(
                command,
                AgentToolContext(
                    taskId = "task-device-url",
                    workspaceId = "workspace-device-url",
                    runId = "run-device-url",
                    stepIndex = 0,
                    runEpoch = 1L,
                    policyVersion = 1L,
                    workspaceReadGranted = false
                )
            )
            assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        } finally {
            // Return to the app so the remaining device suite is not left in another task.
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Unit
        }
        Unit
    }
}
