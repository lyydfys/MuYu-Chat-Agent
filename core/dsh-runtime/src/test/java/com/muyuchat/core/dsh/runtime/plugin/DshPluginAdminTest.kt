package com.muyuchat.core.dsh.runtime.plugin

import com.muyuchat.core.dsh.contract.console.DshPluginAdminResult
import com.muyuchat.core.dsh.contract.console.DshPluginOperation
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.process.DshCommandResult
import com.muyuchat.core.dsh.runtime.process.DshProcessRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Behaviour of the in-app plugin administrator without a device: every refusal happens before a
 * child process exists, and the argv it does run is the closure-resolved `node <pnpm.cjs>` shape,
 * never a hand-built shell string.
 */
class DshPluginAdminTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class RecordingRunner(
        private val result: DshCommandResult = DshCommandResult(0, "done", "", false)
    ) : DshProcessRunner {
        val calls = CopyOnWriteArrayList<List<String>>()
        var workingDir: File? = null
        var environment: Map<String, String> = emptyMap()

        override fun run(
            argv: List<String>,
            environment: Map<String, String>,
            workingDir: File?,
            timeoutMillis: Long
        ): DshCommandResult {
            calls += argv
            this.workingDir = workingDir
            this.environment = environment
            return result
        }
    }

    /** Minimal payload + profile tree: the admin only needs the paths to exist. */
    private fun config(): DshRuntimeConfig {
        val root = temp.newFolder("dsh")
        val config = DshRuntimeConfig(dataRoot = root)
        // The profile the official plugin manager owns.
        File(config.homeDir, DshPluginAdmin.PROFILE_RELATIVE).mkdirs()
        // pnpm's payload entry, so the closure resolver can find it.
        File(config.payloadDir, "pnpm/bin").mkdirs()
        File(config.payloadDir, "pnpm/bin/pnpm.cjs").writeText("// pnpm cli")
        return config
    }

    /** A distinct native-library directory per call: TemporaryFolder cannot reuse a name. */
    private fun nativeDir(): File =
        temp.newFolder("lib-" + System.nanoTime()).apply {
            File(this, "libdshnode.so").writeText("node")
        }

    @Test
    fun `refuses while the runtime is live before spawning anything`() = runBlocking {
        val runner = RecordingRunner()
        val admin = DshPluginAdmin(
            config = config(),
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { true },
            runner = runner
        )

        val result = admin.install("dsh-plugin-market")

        assertTrue(result is DshPluginAdminResult.Rejected)
        assertTrue((result as DshPluginAdminResult.Rejected).reason.contains("运行时"))
        assertTrue("no child process may exist", runner.calls.isEmpty())
    }

    @Test
    fun `refuses a name that could be read as an option`() = runBlocking {
        val runner = RecordingRunner()
        val admin = DshPluginAdmin(
            config = config(),
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { false },
            runner = runner
        )

        val result = admin.install("--global")

        assertTrue(result is DshPluginAdminResult.Rejected)
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `refuses when the profile was never seeded`() = runBlocking {
        val root = temp.newFolder("dsh-empty")
        val config = DshRuntimeConfig(dataRoot = root)
        File(config.payloadDir, "pnpm/bin").mkdirs()
        File(config.payloadDir, "pnpm/bin/pnpm.cjs").writeText("// pnpm cli")
        val runner = RecordingRunner()
        val admin = DshPluginAdmin(
            config = config,
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { false },
            runner = runner
        )

        val result = admin.install("dsh-plugin-market")

        assertTrue(result is DshPluginAdminResult.Rejected)
        assertTrue((result as DshPluginAdminResult.Rejected).reason.contains("profiles/web"))
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun `a successful install runs pnpm add in the profile directory`() = runBlocking {
        val runner = RecordingRunner(DshCommandResult(0, "added", "", false))
        val config = config()
        val admin = DshPluginAdmin(
            config = config,
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { false },
            runner = runner
        )

        val result = admin.install("dsh-plugin-market", "1.2.3")

        assertTrue("was $result", result is DshPluginAdminResult.Succeeded)
        assertEquals(DshPluginOperation.INSTALL, (result as DshPluginAdminResult.Succeeded).operation)
        assertEquals(1, runner.calls.size)
        val argv = runner.calls.single()
        assertTrue("argv must run the payload CLI through node: $argv", argv.any { it.endsWith("pnpm.cjs") })
        assertTrue("argv must mention the pinned spec: $argv", argv.contains("dsh-plugin-market@1.2.3"))
        assertTrue("argv must target the workspace root: $argv", argv.contains("-w"))
        assertEquals(
            File(config.homeDir, DshPluginAdmin.PROFILE_RELATIVE).absolutePath,
            runner.workingDir?.absolutePath
        )
        assertFalse("the store/lock must not be under the app's own dir", runner.environment.isEmpty())
    }

    @Test
    fun `a failed CLI run maps to a stable reason code and a sanitized detail`() = runBlocking {
        val config = config()
        val dataRoot = config.dataRoot.absolutePath
        val runner = RecordingRunner(
            DshCommandResult(
                exitCode = 1,
                stdout = "",
                stderr = "ERR_PNPM_FETCH_404 GET https://registry: $dataRoot/profiles/web - 404",
                timedOut = false
            )
        )
        val admin = DshPluginAdmin(
            config = config,
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { false },
            runner = runner
        )

        val result = admin.remove("missing-plugin")

        assertTrue(result is DshPluginAdminResult.Failed)
        val failed = result as DshPluginAdminResult.Failed
        assertEquals("not_found", failed.reasonCode)
        assertTrue("the install layout must be collapsed", failed.detail.contains("<data>"))
        assertFalse("the raw data root must never appear", failed.detail.contains(dataRoot))
        assertTrue("argv must carry the remove verb: ${runner.calls}", runner.calls.single().contains("remove"))
    }

    @Test
    fun `a timeout is reported as its own code`() = runBlocking {
        val runner = RecordingRunner(DshCommandResult(Int.MIN_VALUE, "", "", timedOut = true))
        val admin = DshPluginAdmin(
            config = config(),
            nativeLibraryDir = nativeDir(),
            runtimeIsLive = { false },
            runner = runner
        )

        val result = admin.remove("dsh-plugin-market")

        assertEquals("timeout", (result as DshPluginAdminResult.Failed).reasonCode)
    }

    @Test
    fun `availability requires both the CLI and a seeded profile`() = runBlocking {
        val runner = RecordingRunner()
        val seeded = config()
        assertTrue(
            DshPluginAdmin(seeded, nativeDir(), { false }, runner = runner).isAvailable()
        )

        val unseededRoot = temp.newFolder("dsh-unseeded")
        val unseeded = DshRuntimeConfig(dataRoot = unseededRoot)
        File(unseeded.payloadDir, "pnpm/bin").mkdirs()
        File(unseeded.payloadDir, "pnpm/bin/pnpm.cjs").writeText("// pnpm cli")
        assertFalse(
            DshPluginAdmin(unseeded, nativeDir(), { false }, runner = runner).isAvailable()
        )
    }
}
