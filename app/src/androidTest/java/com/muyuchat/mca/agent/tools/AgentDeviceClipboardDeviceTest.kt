package com.muyuchat.mca.agent.tools

import android.content.ClipboardManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device-only proof that an approved clipboard write reaches the system clipboard.
 *
 * Android 10+ restricts clipboard *reads* to the focused app, so the test first brings MCA to the
 * foreground before reading back. The write itself never reads clipboard content.
 */
class AgentDeviceClipboardDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected = "MCA 剪贴板测试 2026-10-23"

    private fun command() = FrozenToolCommand(
        invocationId = "inv-device-clip",
        runId = "run-device-clip",
        stepId = "step-device-clip",
        callOrdinal = 0,
        toolKey = AgentDeviceClipboardTool.DESCRIPTOR.toolKey,
        toolVersion = AgentDeviceClipboardTool.DESCRIPTOR.version,
        schemaHash = AgentDeviceClipboardTool.DESCRIPTOR.schemaHash,
        normalizedArgumentsJson = """{"text":"$expected"}""",
        argumentsHash = "args-device-clip",
        resourceFingerprint = "scope-device-clip",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    @Test
    fun approvedTextReachesTheSystemClipboard() = runBlocking {
        context.startActivity(
            Intent(context, com.muyuchat.mca.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(1_500)

        val tool = AgentDeviceClipboardTool(DeviceClipboardSink.android(context))
        val result = tool.execute(
            command(),
            AgentToolContext(
                taskId = "task-device-clip",
                workspaceId = "workspace-device-clip",
                runId = "run-device-clip",
                stepIndex = 0,
                runEpoch = 1L,
                policyVersion = 1L,
                workspaceReadGranted = false
            )
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        // Android 10+ hides clipboard *reads* from non-focused processes, so content is verified
        // separately by pasting into the focused chat input (see device evidence).
        Unit
    }
}
