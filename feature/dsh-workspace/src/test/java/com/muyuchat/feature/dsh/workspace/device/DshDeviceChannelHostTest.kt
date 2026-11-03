package com.muyuchat.feature.dsh.workspace.device

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Process-level device host: one instance, one endpoint memory, one policy-gated shell entry point.
 *
 * `of(context)` cannot run on a JVM; [DshDeviceChannelHost.ofStorage] is the same code path with an
 * explicit directory, so the singleton and the gating are testable without Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshDeviceChannelHostTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        DshDeviceChannelHost.resetForTesting()
    }

    @After
    fun tearDown() {
        DshDeviceChannelHost.resetForTesting()
    }

    @Test
    fun `the host is a process singleton`() {
        val directory = temporaryFolder.newFolder("singleton")

        val first = DshDeviceChannelHost.ofStorage(directory)
        val second = DshDeviceChannelHost.ofStorage(directory)

        assertSame("two accesses must return the same host", first, second)
    }

    @Test
    fun `a reset produces a fresh host`() {
        val directory = temporaryFolder.newFolder("reset")
        val first = DshDeviceChannelHost.ofStorage(directory)

        DshDeviceChannelHost.resetForTesting()
        val second = DshDeviceChannelHost.ofStorage(directory)

        assertNotSame(first, second)
    }

    @Test
    fun `the host owns the endpoint memory and restores it after a restart`() {
        val directory = temporaryFolder.newFolder("endpoint")
        val first = DshDeviceChannelHost.ofStorage(directory)
        first.endpointStore.saveEndpoint("192.168.1.7:5037")

        DshDeviceChannelHost.resetForTesting()
        val second = DshDeviceChannelHost.ofStorage(directory)

        assertEquals("192.168.1.7:5037", second.endpointStore.savedEndpoint())
    }

    @Test
    fun `a fresh host reports ADB unavailable`() {
        val host = DshDeviceChannelHost.ofStorage(temporaryFolder.newFolder("status"))

        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, host.adbStatus())
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, host.adb.status())
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, host.root.status())
    }

    @Test
    fun `the provisioner is built once from the same root channel`() {
        val host = DshDeviceChannelHost.ofStorage(temporaryFolder.newFolder("provisioner"))

        assertSame(host.provisioner, host.provisioner)
    }

    @Test
    fun `the shell entry point rejects a dangerous command before any transport`() = runTest {
        val host = DshDeviceChannelHost.ofStorage(temporaryFolder.newFolder("policy"))

        val result = host.adbShell("rm -rf /")

        assertEquals("policy_blocked", result.reasonCode)
        assertTrue(result.stderr.startsWith("[POLICY_BLOCKED]"))
        assertEquals(
            "the transport must not be touched",
            DshDeviceChannelStatus.UNAVAILABLE,
            host.adbStatus()
        )
    }

    @Test
    fun `a safe command on a disconnected host is not granted`() = runTest {
        val host = DshDeviceChannelHost.ofStorage(temporaryFolder.newFolder("disconnected"))

        val result = host.adbShell("id")

        assertEquals("not_granted", result.reasonCode)
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, host.adbStatus())
    }

    @Test
    fun `the caller byte ceiling truncates and flags the result`() {
        val long = "x".repeat(200)

        val bounded = dshBoundDeviceResult(
            DshDeviceCommandResult(exitCode = 0, stdout = long, stderr = ""),
            limitBytes = 64
        )

        assertEquals(DshDeviceCommandFailure.OUTPUT_LIMIT, bounded.reason)
        assertEquals(64, bounded.stdout.length)
    }

    @Test
    fun `a stronger failure is preserved when output is also truncated`() {
        val bounded = dshBoundDeviceResult(
            DshDeviceCommandResult(
                exitCode = -1,
                stdout = "y".repeat(200),
                stderr = "",
                reason = DshDeviceCommandFailure.TIMEOUT
            ),
            limitBytes = 32
        )

        assertEquals(DshDeviceCommandFailure.TIMEOUT, bounded.reason)
        assertEquals(32, bounded.stdout.length)
    }

    @Test
    fun `an unbounded result is returned unchanged`() {
        val original = DshDeviceCommandResult(exitCode = 0, stdout = "ok", stderr = "")

        val bounded = dshBoundDeviceResult(original, limitBytes = DSH_DEVICE_OUTPUT_LIMIT_BYTES)

        assertSame(original, bounded)
    }

    // region channel choice (shared with the app fallback)

    @Test
    fun `a ready privileged channel always wins`() {
        assertEquals(DshShellChannelChoice.PRIVILEGED, dshShellChannelChoice(true, true))
        assertEquals(DshShellChannelChoice.PRIVILEGED, dshShellChannelChoice(true, false))
    }

    @Test
    fun `adb takes over only when the privileged channel is not usable`() {
        assertEquals(DshShellChannelChoice.ADB, dshShellChannelChoice(false, true))
        assertEquals(DshShellChannelChoice.NONE, dshShellChannelChoice(false, false))
    }

    @Test
    fun `the unavailable detail names both missing families`() {
        val detail = dshShellUnavailableDetail("shizuku: not installed")

        assertTrue(detail.contains("root/Shizuku"))
        assertTrue(detail.contains("ADB"))
        assertTrue(detail.contains("not installed"))
    }

    // endregion
}
