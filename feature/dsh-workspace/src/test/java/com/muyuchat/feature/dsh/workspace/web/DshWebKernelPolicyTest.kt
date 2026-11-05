package com.muyuchat.feature.dsh.workspace.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kernel choice must be safe to ship: an unsupported ABI or a build without the GeckoView
 * artifact has to report a reason and fall back, never render an empty page or crash the tab.
 */
class DshWebKernelPolicyTest {

    private val armOnly = listOf("arm64-v8a", "armeabi-v7a")
    private val x86Only = listOf("x86_64", "x86")

    @Test
    fun `the system webview is always usable`() {
        assertNull(
            DshWebKernelPolicy.unavailableReason(
                DshWebKernel.SYSTEM_WEBVIEW,
                supportedAbis = emptyList(),
                geckoViewPresent = false
            )
        )
    }

    @Test
    fun `geckoview is usable on arm64 with the artifact present`() {
        assertNull(
            DshWebKernelPolicy.unavailableReason(
                DshWebKernel.GECKOVIEW,
                supportedAbis = armOnly,
                geckoViewPresent = true
            )
        )
        assertEquals(
            DshWebKernel.GECKOVIEW,
            DshWebKernelPolicy.effectiveKernel(DshWebKernel.GECKOVIEW, armOnly, geckoViewPresent = true)
        )
    }

    @Test
    fun `a build without the artifact reports that and falls back`() {
        val reason = DshWebKernelPolicy.unavailableReason(
            DshWebKernel.GECKOVIEW,
            supportedAbis = armOnly,
            geckoViewPresent = false
        )
        assertNotNull(reason)
        assertTrue("the reason must say the build lacks the dependency", reason!!.contains("没有打入"))
        assertEquals(
            DshWebKernel.SYSTEM_WEBVIEW,
            DshWebKernelPolicy.effectiveKernel(DshWebKernel.GECKOVIEW, armOnly, geckoViewPresent = false)
        )
    }

    @Test
    fun `an unsupported abi reports the abi and falls back`() {
        val reason = DshWebKernelPolicy.unavailableReason(
            DshWebKernel.GECKOVIEW,
            supportedAbis = x86Only,
            geckoViewPresent = true
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains(DshWebKernelPolicy.GECKOVIEW_ABI))
        assertTrue("the reason must name the device ABI", reason.contains("x86_64"))
        assertEquals(
            DshWebKernel.SYSTEM_WEBVIEW,
            DshWebKernelPolicy.effectiveKernel(DshWebKernel.GECKOVIEW, x86Only, geckoViewPresent = true)
        )
    }

    @Test
    fun `unknown preference values resolve to the default`() {
        assertEquals(DshWebKernel.SYSTEM_WEBVIEW, DshWebKernel.fromPref(null))
        assertEquals(DshWebKernel.SYSTEM_WEBVIEW, DshWebKernel.fromPref(""))
        assertEquals(DshWebKernel.SYSTEM_WEBVIEW, DshWebKernel.fromPref("chrome"))
        assertEquals(DshWebKernel.GECKOVIEW, DshWebKernel.fromPref("gecko"))
        assertEquals(DshWebKernel.DEFAULT, DshWebKernel.fromPref("webview"))
    }

    @Test
    fun `preference file name is the one registered in the golden`() {
        // Pinned so a rename cannot silently add an unlisted preferences file on device.
        assertEquals("dsh_web_kernel", DshWebKernelStore.PREFERENCES_NAME)
        assertEquals("kernel", DshWebKernelStore.KEY_KERNEL)
    }
}
