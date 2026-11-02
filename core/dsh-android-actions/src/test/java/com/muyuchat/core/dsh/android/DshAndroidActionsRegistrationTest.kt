package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshAndroidActionsRegistrationTest {

    private fun actions(
        environmentProvider: () -> DshAndroidEnvironment = { DshAndroidEnvironment() }
    ): DshAndroidActions = DshAndroidActions.create(
        notificationPort = FakeNotificationPort(),
        overlayPort = FakeOverlayPort(),
        usagePort = FakeUsagePort(),
        clipboardPort = FakeClipboardPort(),
        settingsPort = FakeSettingsPort(),
        channels = FakeChannels(),
        consent = FakeConsent(),
        runner = FakeShellRunner(),
        environmentProvider = environmentProvider
    )

    @Test
    fun registers_exactly_the_six_official_paths() {
        val table = actions().registerInto(DshCapabilityRouteTable.builder()).build()

        assertEquals(DshAndroidActions.PATHS.sorted(), table.paths())
        assertEquals(
            listOf("/clipboard", "/notify", "/overlay", "/setting", "/shell", "/usage"),
            table.paths()
        )
        DshAndroidActions.PATHS.forEach { path ->
            assertNotNull("no handler registered for $path", table.handlerFor(path))
        }
        assertNull(table.handlerFor("/not-registered"))
    }

    @Test
    fun a_duplicate_path_in_the_shared_table_fails_loudly() {
        val builder = DshCapabilityRouteTable.builder().route(
            DshAndroidActions.PATH_NOTIFY,
            DshCapabilityRouteHandler { DshCapabilityRouteResponse.Unrouted }
        )

        val error = assertThrowsType<IllegalArgumentException> {
            actions().registerInto(builder)
        }
        assertTrue(error.message.orEmpty().contains("/notify"))
    }

    @Test
    fun the_environment_is_read_through_the_provider_on_every_projection() {
        var calls = 0
        val actions = actions {
            calls += 1
            DshAndroidEnvironment(notificationPost = DshCapabilityState.GRANTED)
        }

        val statuses = actions.capabilityStatuses()
        val usable = actions.usableCapabilities()

        assertEquals(2, calls)
        assertEquals(
            DshCapabilityState.GRANTED,
            statuses.first { it.capability == DshCapability.NOTIFICATION }.state
        )
        assertTrue(usable.contains(DshCapability.NOTIFICATION))
    }
}
