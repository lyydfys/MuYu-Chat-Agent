package com.muyuchat.core.dsh.vscreen

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Shizuku channel is exercised end to end with a fake child process: argv shape, environment
 * filtering, bounded output, real-uid probe, serialization, timeout, cancellation and rebind.
 *
 * Device-only behavior (a real `app_process` talking to a real Shizuku server) is NOT_RUN here; the
 * test double implements the `Process` contract, not the transport.
 */
class DshShizukuChannelTest {

    private val dexPath = "/data/user/0/com.muyuchat.mca/files/dsh-shizuku/rish_shizuku.dex"

    @Test
    fun rishInvocationMirrorsTheShippedScript() {
        val invocation = DshShizukuRishCommand.build(
            dexPath = "/data/local/tmp/rish_shizuku.dex",
            applicationId = "com.muyuchat.mca",
            shellCommand = "id -u"
        )
        assertEquals(
            listOf(
                "/system/bin/app_process",
                "-Djava.class.path=/data/local/tmp/rish_shizuku.dex",
                "/system/bin",
                "--nice-name=dsh-vscreen",
                "rikka.shizuku.shell.ShizukuShellLoader",
                "-c",
                "id -u"
            ),
            invocation.argv
        )
        assertEquals("com.muyuchat.mca", invocation.environment["RISH_APPLICATION_ID"])
        assertEquals(1, invocation.environment.size)
    }

    @Test
    fun rishInvocationRejectsUnsafeArguments() {
        assertRejected { DshShizukuRishCommand.build(dexPath, "com.muyuchat.mca", " ") }
        assertRejected { DshShizukuRishCommand.build(dexPath, "com.muyuchat.mca", "id -u\nrm -rf /") }
        assertRejected { DshShizukuRishCommand.build("relative/rish.dex", "com.muyuchat.mca", "id -u") }
        assertRejected { DshShizukuRishCommand.build(dexPath, "not a package", "id -u") }
        assertRejected { DshShizukuRishCommand.build(dexPath, "com.muyuchat.mca", "id -u", niceName = "bad name") }
    }

    @Test
    fun onlyTransportSignaturesAreClassifiedAsChannelFailures() {
        assertNull(
            "a command the remote shell rejected says nothing about the channel",
            DshShizukuDiagnostics.classify(outcome(stderr = "input: Invalid display id 9", exitCode = 1))
        )
        assertEquals(
            DshPrivilegedChannelState.PERMISSION_DENIED,
            DshShizukuDiagnostics.classify(
                outcome(stderr = "java.lang.SecurityException: Shizuku permission denied", exitCode = 1)
            )
        )
        assertEquals(
            DshPrivilegedChannelState.SERVER_NOT_RUNNING,
            DshShizukuDiagnostics.classify(outcome(stderr = "Server is not running", exitCode = 1))
        )
        assertEquals(
            DshPrivilegedChannelState.DEX_UNUSABLE,
            DshShizukuDiagnostics.classify(outcome(stderr = "app_process: cannot load writable dex"))
        )
        assertEquals(
            DshPrivilegedChannelState.DEX_UNUSABLE,
            DshShizukuDiagnostics.classify(
                outcome(
                    stderr = "",
                    started = false,
                    startFailure = "java.io.IOException: Cannot run program \"app_process\": error=13, Permission denied"
                )
            )
        )
        assertEquals(
            DshPrivilegedChannelState.LOST,
            DshShizukuDiagnostics.classify(
                outcome(
                    stderr = "",
                    started = false,
                    startFailure = "java.io.IOException: error=2, No such file or directory"
                )
            )
        )
        assertEquals(
            DshPrivilegedChannelState.LOST,
            DshShizukuDiagnostics.classify(outcome(stderr = "", exitCode = -1, timedOut = true))
        )
        assertEquals(DshPrivilegedChannelState.READY, DshShizukuDiagnostics.classify(outcome(stderr = "", exitCode = 0)))
    }

    @Test
    fun diagnosticsStaySingleLineAndBounded() {
        assertEquals("a b c", DshShizukuDiagnostics.sanitize("a\nb\tc"))
        assertNull(DshShizukuDiagnostics.sanitize("   \n  "))
        assertNull(DshShizukuDiagnostics.sanitize(null))
        val long = DshShizukuDiagnostics.sanitize("x".repeat(1_000))
        assertNotNull(long)
        assertEquals(DshVscreenLimits.MAX_PRIVILEGED_DIAGNOSTIC_CHARS, long!!.length)
    }

    @Test
    fun runnerFiltersLoaderAndCredentialEnvironmentAndWritesStdin() = runBlocking {
        var seenEnvironment: Map<String, String>? = null
        var process: FakeProcess? = null
        val runner = runnerWith(environment = {
            mapOf(
                "PATH" to "/system/bin",
                "LD_PRELOAD" to "hook.so",
                "APP_LOCAL_TOKEN" to "dshc_secret",
                "BAD KEY" to "ignored"
            )
        }) { argv, environment ->
            seenEnvironment = environment
            FakeProcess(stdoutText = "2000\n").also { process = it }
        }
        val outcome = runner.run(
            argv = listOf("/system/bin/app_process", "-Djava.class.path=$dexPath", "x"),
            extraEnvironment = mapOf("RISH_APPLICATION_ID" to "com.muyuchat.mca"),
            stdin = "hello",
            timeoutMillis = 1_000L,
            stdoutLimit = 64,
            stderrLimit = 64
        )
        assertTrue(outcome.started)
        assertFalse(outcome.timedOut)
        assertEquals("2000", outcome.stdout.trim())
        assertEquals(setOf("PATH", "RISH_APPLICATION_ID"), seenEnvironment!!.keys)
        assertEquals("hello", process!!.stdinText)
    }

    @Test
    fun probeRequiresTheRealShellUid() = runBlocking {
        val channel = channelWith { _, _ -> FakeProcess(stdoutText = "2000\n") }
        val probe = channel.probe()
        assertTrue(probe.available)
        assertEquals(2000, probe.uid)
        assertEquals(DshPrivilegedChannelState.READY, probe.state)
        assertEquals(DshPrivilegedChannelState.READY, channel.state)

        val wrongUid = channelWith { _, _ -> FakeProcess(stdoutText = "1000\n") }
        val rejected = wrongUid.probe()
        assertFalse(rejected.available)
        assertEquals(1000, rejected.uid)
        assertEquals(DshPrivilegedChannelState.LOST, rejected.state)
        assertTrue(rejected.detail.contains("1000"))
    }

    @Test
    fun aMissingLoaderDexFailsBeforeSpawningAnything() = runBlocking {
        var launches = 0
        val channel = DshShizukuShellChannel(
            runner = runnerWith { _, _ -> launches += 1; FakeProcess() },
            dexPath = { null },
            applicationId = "com.muyuchat.mca"
        )
        val probe = channel.probe()
        assertFalse(probe.available)
        assertEquals(DshPrivilegedChannelState.DEX_UNUSABLE, probe.state)
        assertEquals(0, launches)
        val execution = channel.executeShell("id -u", timeoutMillis = 1_000L)
        assertFalse(execution.started)
        assertEquals(DshPrivilegedChannelState.DEX_UNUSABLE, execution.state)
        assertTrue(execution.channelLost)
    }

    @Test
    fun aDeadChannelIsReprobedInsteadOfTrustingACache() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                FakeProcess(stderrText = "Server is not running", exitCode = 1),
                FakeProcess(stdoutText = "2000\n")
            )
        )
        val channel = channelWith { _, _ -> responses.removeFirst() }
        assertEquals(DshPrivilegedChannelState.SERVER_NOT_RUNNING, channel.probe().state)
        val second = channel.probe()
        assertTrue("the loader re-binds on the next probe", second.available)
        assertEquals(DshPrivilegedChannelState.READY, second.state)
    }

    @Test
    fun concurrentCommandsAreSerializedOntoTheSinglePrivilegedSlot() = runBlocking {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val channel = channelWith { _, _ ->
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet()) { current, next -> maxOf(current, next) }
            FakeProcess(finishDelayMillis = 60L, onFinished = { inFlight.decrementAndGet() })
        }
        coroutineScope {
            repeat(3) { index ->
                launch { channel.executeShell("echo $index", timeoutMillis = 5_000L) }
            }
        }
        assertEquals(1, maxInFlight.get())
    }

    @Test
    fun aCommandTimeoutKillsTheChildAndReportsTimeout() = runBlocking {
        var process: FakeProcess? = null
        val channel = channelWith { _, _ -> FakeProcess(hang = true).also { process = it } }
        val execution = channel.executeShell("input -d 7 tap 1 2", timeoutMillis = 1_000L)
        assertTrue(execution.timedOut)
        assertEquals(-1, execution.exitCode)
        assertTrue(process!!.wasDestroyed)
        assertEquals(DshPrivilegedChannelState.LOST, execution.state)
        assertTrue(execution.channelLost)
    }

    @Test
    fun callerCancellationKillsTheChild() = runBlocking {
        val started = AtomicInteger()
        var process: FakeProcess? = null
        val channel = channelWith { _, _ ->
            started.incrementAndGet()
            FakeProcess(hang = true).also { process = it }
        }
        val job = launch { channel.executeShell("input -d 7 tap 1 2", timeoutMillis = 60_000L) }
        withTimeout(2_000L) { while (started.get() == 0) delay(5L) }
        job.cancelAndJoin()
        assertTrue("cancellation must kill the child", process!!.wasDestroyed)
    }

    @Test
    fun anOrdinaryCommandFailureKeepsTheChannelReady() = runBlocking {
        val responses = ArrayDeque(
            listOf(
                FakeProcess(stdoutText = "2000\n"),
                FakeProcess(stderrText = "input: Invalid display id 9", exitCode = 1)
            )
        )
        val channel = channelWith { _, _ -> responses.removeFirst() }
        assertTrue(channel.probe().available)
        val execution = channel.executeShell("input -d 9 tap 1 2", timeoutMillis = 1_000L)
        assertEquals(1, execution.exitCode)
        assertFalse(execution.channelLost)
        assertEquals(DshPrivilegedChannelState.READY, execution.state)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // Expected branch.
        }
    }

    private fun channelWith(starter: (List<String>, Map<String, String>) -> FakeProcess): DshShizukuShellChannel =
        DshShizukuShellChannel(
            runner = runnerWith(starter = starter),
            dexPath = { dexPath },
            applicationId = "com.muyuchat.mca"
        )

    private fun runnerWith(
        environment: () -> Map<String, String> = { emptyMap() },
        starter: (List<String>, Map<String, String>) -> FakeProcess
    ): DshPrivilegedProcessRunner = DshPrivilegedProcessRunner(
        starter = DshPrivilegedProcessStarter { argv, childEnvironment -> starter(argv, childEnvironment) },
        environment = environment
    )

    private fun outcome(
        stderr: String,
        exitCode: Int = 0,
        started: Boolean = true,
        timedOut: Boolean = false,
        startFailure: String? = null
    ): DshPrivilegedProcessOutcome = DshPrivilegedProcessOutcome(
        started = started,
        exitCode = exitCode,
        stdout = "",
        stderr = stderr,
        timedOut = timedOut,
        stdoutTruncated = false,
        stderrTruncated = false,
        startFailure = startFailure
    )
}

/** Minimal `Process` double: no OS process, no dependency on the host platform. */
internal class FakeProcess(
    private val stdoutText: String = "",
    private val stderrText: String = "",
    private val exitCode: Int = 0,
    private val hang: Boolean = false,
    private val finishDelayMillis: Long = 0L,
    private val onFinished: () -> Unit = {}
) : Process() {

    private val stdinBuffer = ByteArrayOutputStream()
    private val finishedFlag = AtomicBoolean(false)
    private val notified = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val startedAtMillis = System.currentTimeMillis()

    val stdinText: String get() = stdinBuffer.toString("UTF-8")
    val wasDestroyed: Boolean get() = destroyed.get()

    override fun getOutputStream(): OutputStream = stdinBuffer

    override fun getInputStream(): InputStream = ByteArrayInputStream(stdoutText.toByteArray())

    override fun getErrorStream(): InputStream = ByteArrayInputStream(stderrText.toByteArray())

    override fun waitFor(): Int {
        while (!finishedFlag.get() && !destroyed.get()) Thread.sleep(5L)
        return exitCode
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (!hang && finishDelayMillis <= 0L) return finish()
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (!destroyed.get()) {
            if (finishedFlag.get()) return true
            if (!hang && System.currentTimeMillis() - startedAtMillis >= finishDelayMillis) return finish()
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(5L)
        }
        return false
    }

    override fun exitValue(): Int = exitCode

    override fun destroy() {
        destroyed.set(true)
    }

    override fun destroyForcibly(): Process {
        destroyed.set(true)
        return this
    }

    override fun isAlive(): Boolean = !finishedFlag.get() && !destroyed.get()

    private fun finish(): Boolean {
        if (finishedFlag.compareAndSet(false, true) && notified.compareAndSet(false, true)) {
            onFinished()
        }
        return true
    }
}
