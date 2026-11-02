package com.muyuchat.mca.agent.tools

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Device-only proof that an approved notification is actually posted to the notification shade. */
class AgentDeviceNotificationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        manager.cancelAll()
    }

    @After
    fun cleanUp() {
        manager.cancelAll()
    }

    private fun command() = FrozenToolCommand(
        invocationId = "inv-device-notify",
        runId = "run-device-notify",
        stepId = "step-device-notify",
        callOrdinal = 0,
        toolKey = AgentDeviceNotificationTool.DESCRIPTOR.toolKey,
        toolVersion = AgentDeviceNotificationTool.DESCRIPTOR.version,
        schemaHash = AgentDeviceNotificationTool.DESCRIPTOR.schemaHash,
        normalizedArgumentsJson = """{"body":"设备通知测试正文","title":"MCA 设备通知"}""",
        argumentsHash = "args-device-notify",
        resourceFingerprint = "scope-device-notify",
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun toolContext() = AgentToolContext(
        taskId = "task-device-notify",
        workspaceId = "workspace-device-notify",
        runId = "run-device-notify",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    @Test
    fun approvedNotificationReachesTheNotificationShade() = runBlocking {
        val sink = DeviceNotificationSink.android(context)
        assumeTrue("notification permission was not granted", sink.canPost())
        assumeTrue(
            "notifications are disabled on this device",
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        )

        val tool = AgentDeviceNotificationTool(sink)
        val result = tool.execute(command(), toolContext())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)

        val active = manager.activeNotifications
        assertTrue("no active notification was posted", active.isNotEmpty())
        val texts = active.mapNotNull { it.notification.extras.getCharSequence("android.title")?.toString() }
        assertTrue("posted titles: $texts", texts.any { it.contains("MCA 设备通知") })
        Unit
    }
}
