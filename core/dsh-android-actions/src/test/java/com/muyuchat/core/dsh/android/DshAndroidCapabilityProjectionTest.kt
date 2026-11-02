package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshAndroidCapabilityProjectionTest {

    @Test
    fun an_empty_environment_denies_every_family_honestly() {
        val environment = DshAndroidEnvironment()
        val statuses = environment.project()

        assertEquals(3, statuses.size)
        statuses.forEach { status ->
            assertEquals(DshCapabilityState.DENIED, status.state)
            assertNotNull(status.detail)
        }
        assertTrue(environment.usableCapabilities().isEmpty())
        assertNull(environment.statusOf(DshCapability.DEVICE_ACTION))
        assertNull(environment.privilegedChannel())
    }

    @Test
    fun a_partial_system_grant_registers_the_family_and_names_the_degraded_actions() {
        val environment = DshAndroidEnvironment(
            notificationPost = DshCapabilityState.GRANTED,
            overlay = DshCapabilityState.GRANTED,
            usageAccess = DshCapabilityState.DENIED,
            settingsWrite = DshCapabilityState.DENIED,
            clipboardRead = DshCapabilityState.GRANTED,
            clipboardWrite = DshCapabilityState.GRANTED
        )

        val system = environment.statusOf(DshCapability.ANDROID_SYSTEM)
        assertNotNull(system)
        assertEquals(DshCapabilityState.GRANTED, system!!.state)
        assertTrue(system.detail.orEmpty().contains("usage=DENIED"))
        assertTrue(system.detail.orEmpty().contains("setting=DENIED"))
        assertEquals(
            setOf(DshCapability.NOTIFICATION, DshCapability.ANDROID_SYSTEM),
            environment.usableCapabilities()
        )
    }

    @Test
    fun not_declared_is_reported_as_not_declared_when_nothing_is_granted() {
        val environment = DshAndroidEnvironment(
            notificationPost = DshCapabilityState.NOT_DECLARED,
            overlay = DshCapabilityState.NOT_DECLARED,
            usageAccess = DshCapabilityState.NOT_DECLARED,
            settingsWrite = DshCapabilityState.NOT_DECLARED,
            clipboardRead = DshCapabilityState.NOT_DECLARED,
            clipboardWrite = DshCapabilityState.NOT_DECLARED
        )

        assertEquals(
            DshCapabilityState.NOT_DECLARED,
            environment.statusOf(DshCapability.NOTIFICATION)?.state
        )
        assertEquals(
            DshCapabilityState.NOT_DECLARED,
            environment.statusOf(DshCapability.ANDROID_SYSTEM)?.state
        )
        assertTrue(environment.usableCapabilities().isEmpty())
    }

    @Test
    fun unsupported_builds_are_reported_as_unsupported() {
        val unsupported = DshCapabilityState.UNSUPPORTED
        val environment = DshAndroidEnvironment(
            overlay = unsupported,
            usageAccess = unsupported,
            settingsWrite = unsupported,
            clipboardRead = unsupported,
            clipboardWrite = unsupported,
            rootShell = unsupported,
            shizukuShell = unsupported
        )

        assertEquals(
            DshCapabilityState.UNSUPPORTED,
            environment.statusOf(DshCapability.ANDROID_SYSTEM)?.state
        )
        assertEquals(
            DshCapabilityState.UNSUPPORTED,
            environment.statusOf(DshCapability.PRIVILEGED_SHELL)?.state
        )
    }

    @Test
    fun privileged_shell_needs_a_channel_and_a_consent_port() {
        val rootGranted = DshAndroidEnvironment(rootShell = DshCapabilityState.GRANTED)
        val shell = rootGranted.statusOf(DshCapability.PRIVILEGED_SHELL)
        assertEquals(DshCapabilityState.GRANTED, shell?.state)
        assertEquals("root(su)", shell?.detail)
        assertEquals(DshPrivilegedChannel.ROOT, rootGranted.privilegedChannel())

        val shizukuGranted = DshAndroidEnvironment(shizukuShell = DshCapabilityState.GRANTED)
        assertEquals(
            DshPrivilegedChannel.SHIZUKU,
            shizukuGranted.privilegedChannel()
        )
        assertEquals("shizuku", shizukuGranted.statusOf(DshCapability.PRIVILEGED_SHELL)?.detail)

        val noConsent = DshAndroidEnvironment(
            rootShell = DshCapabilityState.GRANTED,
            consentAvailable = false
        )
        val refused = noConsent.statusOf(DshCapability.PRIVILEGED_SHELL)
        assertEquals(DshCapabilityState.DENIED, refused?.state)
        assertTrue(refused?.detail.orEmpty().contains("审批端口"))
        assertFalse(refused!!.isUsable)
    }

    @Test
    fun every_status_detail_is_a_prompt_free_single_line() {
        val environment = DshAndroidEnvironment(
            notificationPost = DshCapabilityState.NOT_ENABLED,
            overlay = DshCapabilityState.NOT_ENABLED,
            usageAccess = DshCapabilityState.NOT_ENABLED,
            settingsWrite = DshCapabilityState.NOT_ENABLED,
            clipboardRead = DshCapabilityState.NOT_ENABLED,
            clipboardWrite = DshCapabilityState.NOT_ENABLED,
            rootShell = DshCapabilityState.DENIED,
            shizukuShell = DshCapabilityState.DENIED
        )
        environment.project().forEach { status ->
            val detail = status.detail
            if (detail != null) {
                assertTrue(detail.length <= DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS)
                assertFalse(detail.any(::isForbiddenFailureCharacter))
            }
        }
    }
}
