package com.muyuchat.mca.agent.security

import com.muyuchat.mca.agent.tools.AgentDeviceInfoTool
import com.muyuchat.mca.agent.tools.AgentToolNormalizerRoutes
import com.muyuchat.mca.agent.tools.AgentDeviceStatusTool
import com.muyuchat.mca.agent.tools.DeviceStatusSource
import com.muyuchat.mca.agent.tools.DeviceStatusSnapshot
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.extendedAgentToolCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every catalog tool must have a dispatch route.
 *
 * A tool that is advertised but has no normalizer route fails closed at freeze time with
 * TOOL_NOT_REGISTERED, which surfaces to the user as an unexplained unusable tool. This test pins
 * the route table against the catalog surface, so adding a tool without wiring its normalizer breaks
 * CI instead of production.
 */
class AgentToolNormalizerCoverageTest {

    private val normalizer = AgentToolNormalizerRoutes.production()

    @Test
    fun everyExtendedCatalogToolHasANormalizerRoute() {
        val catalog = extendedAgentToolCatalog(
            filePort = FakeAgentWorkspaceFilePort(),
            deviceStatusSource = DeviceStatusSource {
                DeviceStatusSnapshot(50, false, "wifi", 1L, 2L)
            },
            deviceNotificationSink = object : com.muyuchat.mca.agent.tools.DeviceNotificationSink {
                override fun canPost(): Boolean = true
                override fun post(notification: com.muyuchat.mca.agent.tools.DeviceNotification): Boolean = true
            },
            deviceUrlLauncher = com.muyuchat.mca.agent.tools.DeviceUrlLauncher { true },
            deviceClipboardSink = com.muyuchat.mca.agent.tools.DeviceClipboardSink { true },
            deviceSettingsLauncher = com.muyuchat.mca.agent.tools.DeviceAppSettingsLauncher { true }
        )
        val descriptors = catalog.snapshot()
        assertTrue(descriptors.map { it.toolKey }.contains(AgentDeviceStatusTool.TOOL_KEY))
        assertTrue(descriptors.map { it.toolKey }.contains("device.notify"))
        assertTrue(descriptors.map { it.toolKey }.contains("device.open_url"))
        assertTrue(descriptors.map { it.toolKey }.contains("device.clipboard_write"))
        assertTrue(
            descriptors.map { it.toolKey }.contains("device.open_notification_settings")
        )
        for (descriptor in descriptors) {
            val result = normalizer.normalize(descriptor, "{}")
            val registered = when (result) {
                is AgentArgumentNormalization.Normalized -> true
                is AgentArgumentNormalization.Rejected -> result.error.code != "TOOL_NOT_REGISTERED"
            }
            assertTrue("${descriptor.toolKey} has no normalizer route: $result", registered)
        }
    }
}
