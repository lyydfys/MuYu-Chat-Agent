package com.muyuchat.core.dsh.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RootProcessShellRunnerTest {

    private fun invocation(
        channel: DshPrivilegedChannel = DshPrivilegedChannel.ROOT,
        command: String = "id",
        stdoutLimit: Int = 100,
        stderrLimit: Int = 50
    ) = DshShellInvocation(
        command = command,
        timeoutMillis = 5_000L,
        channel = channel,
        stdoutLimit = stdoutLimit,
        stderrLimit = stderrLimit
    )

    @Test
    fun root_runs_su_with_the_command_as_one_argument() = runTest {
        val starter = RecordingProcessStarter(FakeProcess("uid=0", "", 0))
        val runner = RootProcessShellRunner(
            starter = starter,
            environment = {
                mapOf(
                    "PATH" to "/system/bin",
                    "LD_PRELOAD" to "libevil.so",
                    "APP_LOCAL_TOKEN" to "dshc_secret"
                )
            }
        )

        val result = runner.run(invocation(command = "pm list packages | grep example")) { }

        assertEquals(
            listOf("su", "-c", "pm list packages | grep example"),
            starter.argv
        )
        assertEquals(mapOf("PATH" to "/system/bin"), starter.environment)
        assertTrue(result.ok)
        assertEquals(0, result.exitCode)
        assertEquals("uid=0", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun caps_stdout_and_stderr_at_the_invocation_limits() = runTest {
        val starter = RecordingProcessStarter(
            FakeProcess(
                stdoutText = "a".repeat(200),
                stderrText = "b".repeat(80),
                exitCode = 0
            )
        )
        val result = RootProcessShellRunner(starter = starter).run(
            invocation(stdoutLimit = 100, stderrLimit = 50)
        ) { }

        assertEquals(100, result.stdout.length)
        assertEquals(50, result.stderr.length)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
    }

    @Test
    fun reports_a_non_zero_exit_code_without_inventing_an_error() = runTest {
        val starter = RecordingProcessStarter(FakeProcess("", "denied", 127))
        val result = RootProcessShellRunner(starter = starter).run(invocation()) { }

        assertEquals(127, result.exitCode)
        assertFalse(result.ok)
        assertNull(result.failure)
        assertEquals("denied", result.stderr)
    }

    @Test
    fun a_missing_su_binary_degrades_to_capability_unavailable() = runTest {
        val starter = RecordingProcessStarter(
            FakeProcess(),
            startFailure = IOException("Cannot run program \"su\"")
        )
        val result = RootProcessShellRunner(starter = starter).run(invocation()) { }

        assertEquals(-1, result.exitCode)
        assertFalse(result.ok)
        assertEquals(DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE, result.failure?.code)
    }

    @Test
    fun shizuku_without_a_transport_degrades_honestly() = runTest {
        val starter = RecordingProcessStarter(FakeProcess())
        val result = RootProcessShellRunner(starter = starter).run(
            invocation(channel = DshPrivilegedChannel.SHIZUKU)
        ) { }

        assertEquals(-1, result.exitCode)
        assertEquals(DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE, result.failure?.code)
        assertNull(starter.argv)
    }

    @Test
    fun shizuku_delegates_to_the_injected_transport() = runTest {
        val transport = RecordingShizukuTransport()
        val starter = RecordingProcessStarter(FakeProcess())
        val result = RootProcessShellRunner(starter = starter, shizuku = transport).run(
            invocation(channel = DshPrivilegedChannel.SHIZUKU, command = "echo hi")
        ) { }

        assertEquals("echo hi", transport.seen?.command)
        assertEquals(DshPrivilegedChannel.SHIZUKU, transport.seen?.channel)
        assertEquals(0, result.exitCode)
        assertNull(starter.argv)
    }

    @Test
    fun killing_the_handle_terminates_a_blocked_process() = runTest {
        val process = BlockingProcess()
        val runner = RootProcessShellRunner(starter = RecordingProcessStarter(process))
        val started = CompletableDeferred<DshShellHandle>()
        var captured: DshShellRunResult? = null

        val job = launch {
            captured = runner.run(invocation()) { handle -> started.complete(handle) }
        }
        val handle = started.await()
        assertFalse(process.destroyed)

        handle.kill()
        job.join()

        assertTrue(process.destroyed)
        assertEquals(-1, captured?.exitCode)
    }
}

private class RecordingShizukuTransport : DshShizukuShellTransport {
    var seen: DshShellInvocation? = null

    override suspend fun execute(invocation: DshShellInvocation): DshShellRunResult {
        seen = invocation
        return DshShellRunResult(exitCode = 0, stdout = "shizuku", stderr = "")
    }
}
