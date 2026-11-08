package com.muyuchat.mca.agent.tools

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the production `terminal.exec` sandbox on the parts that are JVM-testable.
 *
 * The real `ProcessBuilder` is only used where the host OS can actually run the binary; every other
 * case drives an injected [spawn]/resolve so the security properties (argv-only, whitelist, timeout
 * kill, output cap) are pinned without depending on a device.
 */
class AndroidAgentTerminalSandboxTest {

    private val workspace = File.createTempFile("mca-terminal", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }

    private fun sandbox(
        whitelist: Set<String> = AgentTerminalWhitelist.DEFAULT,
        resolve: (String) -> String? = { name -> resolveOnDisk(name) },
        spawn: (List<String>, File, Map<String, String>) -> Process = ::neverSpawn,
        probeTimeout: Long = 1_000L
    ) = AndroidAgentTerminalSandbox(
        workspace = workspace,
        whitelist = whitelist,
        resolveExecutable = resolve,
        spawn = spawn,
        probeTimeoutMillis = probeTimeout
    )

    private fun request(
        command: String,
        arguments: List<String> = emptyList(),
        timeoutMillis: Long = 2_000L,
        maxOutputBytes: Int = 64 * 1024
    ) = AgentTerminalRequest(
        invocationId = "inv-1",
        command = command,
        arguments = arguments,
        timeoutMillis = timeoutMillis,
        maxOutputBytes = maxOutputBytes
    )

    // ------------------------------------------------------------------ whitelist

    @Test
    fun `a command outside the whitelist is refused before any process starts`() = runBlocking {
        val spawns = AtomicInteger()
        val tool = sandbox(spawn = { _, _, _ -> spawns.incrementAndGet(); neverSpawn(listOf(), workspace, emptyMap()) })

        val outcome = tool.execute(request("rm", listOf("-rf", "/")))

        val refused = outcome as AgentTerminalOutcome.Refused
        assertEquals("TOOL_COMMAND_NOT_ALLOWED", refused.code)
        assertEquals("nothing may run for a non-whitelisted command", 0, spawns.get())
    }

    @Test
    fun `a path-like or traversal command name is refused even if whitelisted`() = runBlocking {
        listOf("/system/bin/cat", "../../bin/cat", "cat/../sh").forEach { name ->
            val outcome = sandbox(
                whitelist = setOf(name),
                resolve = { "/system/bin/cat" }
            ).execute(request(name))
            assertTrue("$name", outcome is AgentTerminalOutcome.Refused)
        }
    }

    @Test
    fun `a missing binary is reported as not found instead of executed`() = runBlocking {
        val outcome = sandbox(resolve = { null }).execute(request("echo"))

        val refused = outcome as AgentTerminalOutcome.Refused
        assertEquals("TOOL_COMMAND_NOT_FOUND", refused.code)
    }

    @Test
    fun `control characters in an argument are refused`() = runBlocking {
        val outcome = sandbox(resolve = { "/system/bin/echo" })
            .execute(request("echo", listOf("a\u0000b")))

        assertTrue(outcome is AgentTerminalOutcome.Refused)
    }

    // ------------------------------------------------------------------ argv-only

    @Test
    fun `shell metacharacters stay one literal argument and start no second process`() = runBlocking {
        var captured: List<String> = emptyList()
        val tool = sandbox(
            resolve = { "/system/bin/echo" },
            spawn = { argv, _, _ ->
                captured = argv
                fakeProcess(exitCode = 0, stdout = "ok")
            }
        )

        val outcome = tool.execute(request("echo", listOf(";", "rm", "-rf", "/")))

        assertEquals("echo; and 'rm' must arrive as separate argv tokens, never as a command", listOf(
            "/system/bin/echo", ";", "rm", "-rf", "/"
        ), captured)
        val completed = outcome as AgentTerminalOutcome.Completed
        assertEquals(0, completed.exitCode)
        assertEquals("ok", completed.stdout)
    }

    @Test
    fun `a shell pipeline string is never handed to a shell`() = runBlocking {
        var captured: List<String> = emptyList()
        val tool = sandbox(
            resolve = { "/system/bin/echo" },
            spawn = { argv, _, _ -> captured = argv; fakeProcess(0, "ok") }
        )

        tool.execute(request("echo", listOf("a|b", ">", "out")))

        assertEquals("/system/bin/echo", captured.first())
        assertTrue("the pipeline is one literal token", "a|b" in captured)
        // argv = [executable] + 3 argument tokens; ordering is preserved and nothing is prepended, so
        // the "pipeline" can never become two commands.
        assertEquals(
            listOf("/system/bin/echo", "a|b", ">", "out"),
            captured
        )
    }

    // ------------------------------------------------------------------ timeout / kill

    @Test
    fun `a command that exceeds its deadline is killed and reported as timed out`() = runBlocking {
        val process = fakeProcess(exitCode = 0, stdout = "", hangMillis = 5_000L)

        val hanging = sandbox(
            resolve = { "/system/bin/echo" },
            spawn = { _, _, _ -> process }
        ).execute(request("echo", timeoutMillis = 200L))

        assertEquals(AgentTerminalOutcome.TimedOut, hanging)
        assertTrue("the process must be force-killed", process.destroyedForcibly.get())
    }

    // ------------------------------------------------------------------ output budget

    @Test
    fun `oversized output is truncated inside the budget and flagged`() = runBlocking {
        // `cat` is not in DEFAULT (echo/pwd/date); this case is about the output cap, so it must
        // explicitly opt into EXTENDED — the DEFAULT whitelist correctly refuses it.
        val tool = sandbox(
            whitelist = AgentTerminalWhitelist.EXTENDED,
            resolve = { "/system/bin/cat" },
            spawn = { _, _, _ -> fakeProcess(exitCode = 0, stdout = "x".repeat(200_000)) }
        )

        val outcome = tool.execute(request("cat", listOf("-"), maxOutputBytes = 1_024)) as AgentTerminalOutcome.Completed

        assertTrue("must stay within the budget, was ${outcome.stdout.length}", outcome.stdout.length <= 1_024)
        assertTrue(outcome.truncated)
    }

    // ------------------------------------------------------------------ availability

    @Test
    fun `availability is false when the workspace cannot be written`() {
        val missing = File(workspace, "nested/deep").let { dir ->
            // A path whose parent is a *file* can never become a directory.
            File(File(workspace, "blocker").apply { writeText("x") }, "child")
        }
        val tool = AndroidAgentTerminalSandbox(workspace = missing, resolveExecutable = { "/system/bin/echo" })

        assertFalse("a workspace that cannot be created must not claim availability", tool.isAvailable)
        assertFalse(missing.exists() && missing.isDirectory)
    }

    @Test
    fun `availability requires the probe process to exit cleanly`() {
        val ok = sandbox(resolve = { "/system/bin/echo" }, spawn = { _, _, _ -> fakeProcess(0, "x") })
        val failing = sandbox(resolve = { "/system/bin/echo" }, spawn = { _, _, _ -> fakeProcess(1, "") })
        val throwing = sandbox(
            resolve = { "/system/bin/echo" },
            spawn = { _, _, _ -> error("cannot spawn") }
        )

        assertTrue(ok.isAvailable)
        assertFalse("a binary that fails must not be advertised", failing.isAvailable)
        assertFalse("a spawn failure must not be advertised", throwing.isAvailable)
    }

    @Test
    fun `availability is false when no whitelisted binary can be resolved`() {
        assertFalse(sandbox(resolve = { null }).isAvailable)
    }

    // ------------------------------------------------------------------ resolution

    @Test
    fun `resolution rejects unsafe names and finds nothing on an empty directory list`() {
        assertNull(AndroidAgentTerminalSandbox.resolveOnDisk("../sh", emptyList()))
        assertNull(AndroidAgentTerminalSandbox.resolveOnDisk("sh;rm", emptyList()))
        assertNull(AndroidAgentTerminalSandbox.resolveOnDisk("not-on-this-host", AndroidAgentTerminalSandbox.DEFAULT_BIN_DIRS))
    }

    @Test
    fun `the extended whitelist is a superset that stays opt in`() {
        assertTrue(AgentTerminalWhitelist.EXTENDED.containsAll(AgentTerminalWhitelist.DEFAULT))
        assertTrue(AgentTerminalWhitelist.EXTENDED.containsAll(listOf("ls", "cat", "wc", "head", "grep", "find")))
        assertFalse("interpreters must never be added", AgentTerminalWhitelist.EXTENDED.any { it in setOf("sh", "bash", "python", "env") })
        assertEquals(setOf("echo", "pwd", "date"), AgentTerminalWhitelist.DEFAULT)
    }

    // ------------------------------------------------------------------ helpers

    private fun resolveOnDisk(name: String): String? = AndroidAgentTerminalSandbox.resolveOnDisk(name, emptyList())
        ?: "/system/bin/$name".takeIf { AgentTerminalWhitelist.isValidCommandName(name) }

    private fun neverSpawn(argv: List<String>, directory: File, environment: Map<String, String>): Process =
        error("this case must not spawn")

    /** Minimal Process fake: optional hang, bounded stdout, forced-kill accounting. */
    private fun fakeProcess(exitCode: Int, stdout: String, hangMillis: Long = 0L): FakeProcess =
        FakeProcess(exitCode, stdout.toByteArray(Charsets.UTF_8), hangMillis)

    private class FakeProcess(
        private val exitCode: Int,
        private val stdout: ByteArray,
        private val hangMillis: Long
    ) : Process() {
        val destroyedForcibly = java.util.concurrent.atomic.AtomicBoolean(false)
        private val out = java.io.ByteArrayInputStream(stdout)
        private val err = java.io.ByteArrayInputStream(ByteArray(0))
        private var finished = false
        private var startedAt = System.currentTimeMillis()

        override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
        override fun getInputStream(): java.io.InputStream = out
        override fun getErrorStream(): java.io.InputStream = err

        override fun waitFor(): Int {
            while (!finished) Thread.sleep(10)
            return exitCode
        }

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean {
            if (hangMillis > 0L && !destroyedForcibly.get()) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < hangMillis) {
                    Thread.sleep(minOf(timeout, 50L).coerceAtLeast(1L))
                    return false
                }
                finished = true
                return true
            }
            finished = true
            return true
        }

        override fun exitValue(): Int = exitCode
        // Process.destroy() returns Unit; delegating with `=` would declare Process as its return
        // type and fail to override. The body form keeps the signature and still funnels every
        // destroy path into the force-kill counter these tests assert on.
        override fun destroy() {
            destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            destroyedForcibly.set(true)
            finished = true
            return this
        }

        override fun isAlive(): Boolean = !finished
    }
}
