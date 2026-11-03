package com.muyuchat.feature.dsh.workspace.device

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root channel behaviour with an injected process launcher: no `su` is ever spawned here.
 *
 * The interesting cases are the honest status ladder (never READY without `uid=0`), the policy gate
 * running before a process exists, bounded output, timeouts and process destruction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshRootChannelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private fun channel(
        launcher: DshProcessLauncher,
        probeTimeoutMs: Long = 50L
    ): DshRootChannel = DshRootChannel(launcher, probeTimeoutMs, dispatcher)

    @Test
    fun `a fresh channel is unavailable and does not claim root`() {
        val root = channel(RecordingLauncher { _, _ -> FakeDshProcess() })

        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, root.status())
        assertEquals(DshDeviceChannelKind.ROOT, root.kind)
    }

    @Test
    fun `a device without a su binary is unavailable rather than an error`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> throw IOException("su: not found") }
        val root = channel(launcher)

        val probe = root.probe()

        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, probe.status)
        assertEquals("not_granted", probe.result?.reasonCode)
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, root.status())
        assertEquals(listOf("su", "-c", "id"), launcher.invocations.single())
    }

    @Test
    fun `a denied root prompt means needs grant`() = runTest(dispatcher) {
        val root = channel(
            RecordingLauncher { _, _ ->
                FakeDshProcess(stdoutText = "", stderrText = "su: permission denied", exitCode = 1)
            }
        )

        val probe = root.probe()

        assertEquals(DshDeviceChannelStatus.NEEDS_GRANT, probe.status)
        assertEquals(1, probe.result?.exitCode)
    }

    @Test
    fun `uid zero is the only proof of ready`() = runTest(dispatcher) {
        val root = channel(
            RecordingLauncher { _, _ ->
                FakeDshProcess(stdoutText = "uid=0(root) gid=0(root) groups=0(root)\n")
            }
        )

        assertEquals(DshDeviceChannelStatus.READY, root.probe().status)
        assertEquals(DshDeviceChannelStatus.READY, root.status())
    }

    @Test
    fun `a su that exits zero without uid zero is an error not a success`() = runTest(dispatcher) {
        val root = channel(
            RecordingLauncher { _, _ -> FakeDshProcess(stdoutText = "uid=2000(shell) gid=2000(shell)\n") }
        )

        assertEquals(DshDeviceChannelStatus.ERROR, root.probe().status)
    }

    @Test
    fun `a hung su probe times out as needs grant and is destroyed`() = runTest(dispatcher) {
        var process: FakeDshProcess? = null
        val root = channel(
            launcher = RecordingLauncher { _, _ ->
                FakeDshProcess(exits = false).also { process = it }
            },
            probeTimeoutMs = 20L
        )

        val probe = root.probe()

        assertEquals(DshDeviceChannelStatus.NEEDS_GRANT, probe.status)
        assertEquals("timeout", probe.result?.reasonCode)
        assertTrue("timed out process must be destroyed", process?.destroyed == true)
    }

    @Test
    fun `policy blocked commands never spawn a process`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ ->
            FakeDshProcess(stdoutText = "uid=0(root)\n")
        }
        val root = channel(launcher)

        val blocked = root.exec("rm -rf /")

        assertEquals("policy_blocked", blocked.reasonCode)
        assertTrue("a blocked command must not reach su", launcher.invocations.isEmpty())
        assertTrue(blocked.stderr.startsWith("[POLICY_BLOCKED]"))
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, root.status())
    }

    @Test
    fun `an allowed command probes first and then runs the rendered command`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { index, _ ->
            if (index == 0) {
                FakeDshProcess(stdoutText = "uid=0(root) gid=0(root)\n")
            } else {
                FakeDshProcess(stdoutText = "Linux version 6.1.0\n")
            }
        }
        val root = channel(launcher)

        val result = root.exec("uname -a")

        assertTrue(result.succeeded)
        assertEquals("Linux version 6.1.0\n", result.stdout)
        assertEquals(listOf("su", "-c", "id"), launcher.invocations[0])
        assertEquals(listOf("su", "-c", "uname -a"), launcher.invocations[1])
    }

    @Test
    fun `a channel without root refuses an allowed command without running it`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> FakeDshProcess(exitCode = 1) }
        val root = channel(launcher)

        val result = root.exec("id")

        assertEquals("not_granted", result.reasonCode)
        assertEquals("only the probe may run", 1, launcher.invocations.size)
    }

    @Test
    fun `a timed out command is reported and the child is destroyed`() = runTest(dispatcher) {
        var hung: FakeDshProcess? = null
        val launcher = RecordingLauncher { index, _ ->
            if (index == 0) {
                FakeDshProcess(stdoutText = "uid=0(root)\n")
            } else {
                FakeDshProcess(exits = false).also { hung = it }
            }
        }
        val root = channel(launcher)

        val result = root.exec("id", timeoutMs = 20L)

        assertEquals("timeout", result.reasonCode)
        assertTrue("timed out child must be destroyed", hung?.destroyed == true)
    }

    @Test
    fun `output over the ceiling is truncated and reported`() = runTest(dispatcher) {
        val huge = "x".repeat(DSH_DEVICE_OUTPUT_LIMIT_BYTES + 4096)
        val launcher = RecordingLauncher { index, _ ->
            if (index == 0) FakeDshProcess(stdoutText = "uid=0(root)\n") else FakeDshProcess(stdoutText = huge)
        }
        val root = channel(launcher)

        val result = root.exec("cat /proc/version")

        assertEquals("output_limit", result.reasonCode)
        assertTrue(result.stdout.length <= DSH_DEVICE_OUTPUT_LIMIT_BYTES)
    }

    @Test
    fun `a revoked grant degrades an already ready channel`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { index, _ ->
            if (index == 0) {
                FakeDshProcess(stdoutText = "uid=0(root)\n")
            } else {
                FakeDshProcess(stderrText = "su: access denied", exitCode = 1)
            }
        }
        val root = channel(launcher)

        assertEquals(DshDeviceChannelStatus.READY, root.probe().status)
        val result = root.exec("id")

        assertEquals(1, result.exitCode)
        assertEquals(DshDeviceChannelStatus.NEEDS_GRANT, root.status())
    }

    @Test
    fun `status mapping is pure`() {
        assertEquals(
            DshDeviceChannelStatus.UNAVAILABLE,
            dshRootStatusFor(DshRootProbeOutcome.SuMissing)
        )
        assertEquals(
            DshDeviceChannelStatus.NEEDS_GRANT,
            dshRootStatusFor(DshRootProbeOutcome.TimedOut)
        )
        assertEquals(
            DshDeviceChannelStatus.NEEDS_GRANT,
            dshRootStatusFor(DshRootProbeOutcome.Exited(1, "", "denied"))
        )
        assertEquals(
            DshDeviceChannelStatus.READY,
            dshRootStatusFor(DshRootProbeOutcome.Exited(0, "uid=0(root) gid=0(root)", ""))
        )
        assertEquals(
            DshDeviceChannelStatus.ERROR,
            dshRootStatusFor(DshRootProbeOutcome.Exited(0, "uid=2000(shell)", ""))
        )
        assertEquals(
            DshDeviceChannelStatus.ERROR,
            dshRootStatusFor(DshRootProbeOutcome.Exited(0, "", ""))
        )
    }

    @Test
    fun `denial wording only ever degrades the status`() {
        assertTrue(dshRootDenialFromStderr("su: permission denied"))
        assertTrue(dshRootDenialFromStderr("Permission denied"))
        assertTrue(dshRootDenialFromStderr("request denied by root manager"))
        assertFalse(dshRootDenialFromStderr("uid=0(root) gid=0(root)"))
        assertFalse(dshRootDenialFromStderr(""))
    }

    @Test
    fun `the probe command itself passes the command policy`() {
        // Keeps the built-in probe on the same allowlist the agent's commands must satisfy.
        val decision = DshDeviceCommandPolicy.evaluate("id")
        assertTrue(decision is DshDeviceCommandDecision.Allowed)
    }
}
