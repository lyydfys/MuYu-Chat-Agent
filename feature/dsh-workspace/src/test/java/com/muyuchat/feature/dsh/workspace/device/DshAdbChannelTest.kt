package com.muyuchat.feature.dsh.workspace.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fake transport: the seam a future pairing implementation plugs into. */
private class FakeAdbTransport(
    private var current: DshAdbTransportState,
    private val stdout: String = "",
    private val stderr: String = "",
    private val exitCode: Int = 0,
    private val delayMs: Long = 0L
) : AdbTransport {

    val commands: MutableList<String> = mutableListOf()

    override fun state(): DshAdbTransportState = current

    override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
        current = DshAdbTransportState.CONNECTED
        return true
    }

    override suspend fun shell(command: String): DshAdbShellResult {
        commands += command
        if (delayMs > 0) delay(delayMs)
        return DshAdbShellResult(exitCode = exitCode, stdout = stdout, stderr = stderr)
    }

    override fun close() {
        current = DshAdbTransportState.NOT_CONFIGURED
    }
}

/**
 * The ADB channel is a skeleton in this generation: it must report "not integrated" instead of a
 * fake READY, and it must keep sharing the same command policy as the root channel.
 */
class DshAdbChannelTest {

    @Test
    fun `transport states map to truthful channel statuses`() {
        assertEquals(
            DshDeviceChannelStatus.UNAVAILABLE,
            dshAdbStatusFor(DshAdbTransportState.NOT_CONFIGURED)
        )
        assertEquals(
            DshDeviceChannelStatus.NEEDS_GRANT,
            dshAdbStatusFor(DshAdbTransportState.AWAITING_PAIRING)
        )
        assertEquals(
            DshDeviceChannelStatus.READY,
            dshAdbStatusFor(DshAdbTransportState.CONNECTED)
        )
        assertEquals(
            DshDeviceChannelStatus.ERROR,
            dshAdbStatusFor(DshAdbTransportState.FAILED)
        )
    }

    @Test
    fun `without a transport the channel is unavailable and says not integrated`() = runTest {
        val channel = DshAdbChannel()

        assertEquals(DshDeviceChannelKind.ADB, channel.kind)
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, channel.status())

        val probe = channel.probe()
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, probe.status)
        assertEquals("not_integrated", probe.result?.reasonCode)

        val command = channel.exec("id")
        assertEquals("not_integrated", command.reasonCode)
        assertFalse(command.succeeded)
    }

    @Test
    fun `the policy gate runs before the transport gate`() = runTest {
        val channel = DshAdbChannel()

        val blocked = channel.exec("rm -rf /")

        assertEquals("policy_blocked", blocked.reasonCode)
        assertTrue(blocked.stderr.startsWith("[POLICY_BLOCKED]"))
    }

    @Test
    fun `a connected transport runs a whitelisted command`() = runTest {
        val transport = FakeAdbTransport(
            current = DshAdbTransportState.CONNECTED,
            stdout = "sdk_gphone64_arm64\n"
        )
        val channel = DshAdbChannel(transport)

        assertEquals(DshDeviceChannelStatus.READY, channel.status())
        val result = channel.exec("getprop ro.product.model")

        assertTrue(result.succeeded)
        assertEquals("sdk_gphone64_arm64\n", result.stdout)
        assertEquals(listOf("getprop ro.product.model"), transport.commands)
    }

    @Test
    fun `an unpaired transport refuses commands without touching it`() = runTest {
        val transport = FakeAdbTransport(current = DshAdbTransportState.AWAITING_PAIRING)
        val channel = DshAdbChannel(transport)

        assertEquals(DshDeviceChannelStatus.NEEDS_GRANT, channel.status())
        assertEquals("not_granted", channel.exec("id").reasonCode)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun `a failed transport is an error`() = runTest {
        val channel = DshAdbChannel(FakeAdbTransport(current = DshAdbTransportState.FAILED))

        assertEquals(DshDeviceChannelStatus.ERROR, channel.status())
        assertEquals("not_granted", channel.exec("id").reasonCode)
    }

    @Test
    fun `an adb shell timeout is reported as a timeout`() = runTest {
        val transport = FakeAdbTransport(
            current = DshAdbTransportState.CONNECTED,
            delayMs = 5_000L
        )
        val channel = DshAdbChannel(transport)

        val result = channel.exec("id", timeoutMs = 50L)

        assertEquals("timeout", result.reasonCode)
    }

    @Test
    fun `adb output over the ceiling is truncated and reported`() = runTest {
        val transport = FakeAdbTransport(
            current = DshAdbTransportState.CONNECTED,
            stdout = "y".repeat(DSH_DEVICE_OUTPUT_LIMIT_BYTES + 1)
        )
        val channel = DshAdbChannel(transport)

        val result = channel.exec("id")

        assertEquals("output_limit", result.reasonCode)
        assertTrue(result.stdout.length <= DSH_DEVICE_OUTPUT_LIMIT_BYTES)
    }

    @Test
    fun `probing a ready transport runs the read-only probe through the policy`() = runTest {
        val transport = FakeAdbTransport(
            current = DshAdbTransportState.CONNECTED,
            stdout = "sdk_gphone64_arm64\n"
        )
        val channel = DshAdbChannel(transport)

        val probe = channel.probe()

        assertEquals(DshDeviceChannelStatus.READY, probe.status)
        assertEquals(0, probe.result?.exitCode)
        assertEquals(listOf("getprop ro.product.model"), transport.commands)
    }

    @Test
    fun `an endpoint must carry a usable port and host`() {
        assertEquals(5555, DshAdbEndpoint("127.0.0.1", 5555).port)
        try {
            DshAdbEndpoint("127.0.0.1", 0)
            fail("port 0 must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("port"))
        }
        try {
            DshAdbEndpoint("  ", 5555)
            fail("blank host must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("host"))
        }
    }
}
