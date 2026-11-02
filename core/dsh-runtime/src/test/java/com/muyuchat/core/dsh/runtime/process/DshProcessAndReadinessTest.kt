package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.readiness.DshHttpProbe
import com.muyuchat.core.dsh.runtime.readiness.DshHttpResponse
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.readiness.DshSetCookie
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshNodeCommandTest {

    @Test
    fun buildsTheOfficialWebProfileInvocation() {
        val kernel = File("/tmp/dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        val executable = DshNodeExecutable(listOf("/native/libdshnode.so"), DshNodeExecStrategy.Mode.NATIVE_LIBRARY)
        val args = DshNodeCommand.arguments(executable, kernel, "127.0.0.1", 0)
        assertEquals(
            listOf(
                "/native/libdshnode.so", "--expose-internals", kernel.absolutePath,
                "web", "--no-open", "--host", "127.0.0.1", "--port", "0"
            ),
            args
        )
    }

    @Test
    fun environmentIsIsolatedAndComplete() {
        val root = File("/tmp/dsh-test-env")
        val env = DshNodeCommand.environment(
            homeDir = File(root, "home"),
            configDir = File(root, "home/config"),
            workspaceDir = File(root, "workspace"),
            tmpDir = File(root, "tmp"),
            payloadDir = File(root, "payload"),
            runtimeLibraryDir = File(root, "payload/runtime/lib"),
            payloadBinDir = File(root, "payload/bin")
        )
        assertEquals(File(root, "home").absolutePath, env["HOME"])
        assertEquals(File(root, "home").absolutePath, env["DSH_HOME"])
        assertEquals(File(root, "home/config").absolutePath, env["DSH_CONFIG_DIR"])
        assertEquals(File(root, "workspace").absolutePath, env["DSH_WORKSPACE"])
        assertEquals(File(root, "tmp").absolutePath, env["TMPDIR"])
        assertEquals(File(root, "payload/runtime/lib").absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals("/system/bin/sh", env["SHELL"])
        assertTrue(env.getValue("PATH").startsWith(File(root, "payload/bin").absolutePath))
        // The official filesystem skill provider reads its only bundled root from this variable.
        // It must point at the MCA-managed home, never at the replaceable payload tree.
        assertEquals(
            File(File(root, "home"), "bundled-skills").absolutePath,
            env["DSH_BUNDLED_SKILL_DIR"]
        )
        assertFalse(env.getValue("DSH_BUNDLED_SKILL_DIR").startsWith(File(root, "payload").absolutePath))
    }
}

class FakeProcessRunner(
    private val direct: Boolean,
    private val linker: Boolean
) : DshProcessRunner {
    override fun run(
        argv: List<String>,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long
    ): DshCommandResult = when {
        argv.firstOrNull()?.endsWith("libdshnode.so") == true && direct -> DshCommandResult(0, "v26.4.0\n", "", false)
        argv.firstOrNull() == "/system/bin/linker64" && linker -> DshCommandResult(0, "v26.4.0\n", "", false)
        else -> DshCommandResult(-1, "", "execve: EACCES", false)
    }
}

class DshNodeExecStrategyTest {

    /**
     * `resolve` deliberately refuses to probe a path that does not exist, so the strategy tests
     * need a real file even though the runner itself is faked. The file keeps the production
     * `libdshnode.so` name because the fake runner classifies the strategy by that name.
     */
    private fun node(): File {
        val dir = java.nio.file.Files.createTempDirectory("dsh-exec").toFile()
        val file = File(dir, "libdshnode.so")
        file.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        file.deleteOnExit()
        dir.deleteOnExit()
        return file
    }

    private val env = emptyMap<String, String>()

    @Test
    fun prefersDirectNativeLibraryExecution() {
        val node = node()
        val choice = DshNodeExecStrategy(FakeProcessRunner(direct = true, linker = true))
            .resolve(node, env, null, 1000)
        assertNotNull(choice)
        assertEquals(DshNodeExecStrategy.Mode.NATIVE_LIBRARY, choice!!.executable.strategy)
        assertEquals("v26.4.0", choice.nodeVersion)
    }

    @Test
    fun fallsBackToSystemLinkerWhenDirectExecIsDenied() {
        val node = node()
        val choice = DshNodeExecStrategy(FakeProcessRunner(direct = false, linker = true))
            .resolve(node, env, null, 1000)
        assertNotNull(choice)
        assertEquals(DshNodeExecStrategy.Mode.LINKER64, choice!!.executable.strategy)
        assertEquals(listOf("/system/bin/linker64", node.absolutePath), choice.executable.argvPrefix)
    }

    @Test
    fun returnsNullWhenNeitherStrategyExecutes() {
        val node = node()
        assertNull(DshNodeExecStrategy(FakeProcessRunner(direct = false, linker = false)).resolve(node, env, null, 1000))
        assertNull(DshNodeExecStrategy(FakeProcessRunner(true, true)).resolve(File("/missing.so"), env, null, 1000))
    }

    @Test
    fun probesRunInTheProvidedWorkspaceDirectory() {
        val node = node()
        val workspace = File("/dsh/workspace")
        val seen = mutableListOf<File?>()
        val runner = object : DshProcessRunner {
            override fun run(
                argv: List<String>,
                environment: Map<String, String>,
                workingDir: File?,
                timeoutMillis: Long
            ): DshCommandResult {
                seen += workingDir
                return DshCommandResult(0, "v26.4.0\n", "", false)
            }
        }

        val choice = DshNodeExecStrategy(runner).resolve(node, env, workspace, 1000)

        assertNotNull(choice)
        assertTrue("the exec probe must run at least once", seen.isNotEmpty())
        assertTrue(
            "the exec probe must inherit the isolated workspace, never the payload tree",
            seen.all { it == workspace }
        )
    }
}

class DshProcStatTest {

    @Test
    fun parsesPpidAfterTheLastParenthesis() {
        assertEquals(42, DshProcStat.parsePpid("1234 (my proc) S 42 1 1 0 -1"))
        assertEquals(7, DshProcStat.parsePpid("9 (a) b) S 7 8"))
        assertNull(DshProcStat.parsePpid("garbage"))
    }

    @Test
    fun collectsDescendantsBreadthFirst() {
        val stats = mapOf(
            1 to "1 (init) S 0 0",
            10 to "10 (node) S 1 0",
            11 to "11 (sh) S 10 0",
            12 to "12 (git) S 11 0",
            20 to "20 (other) S 1 0"
        )
        assertEquals(setOf(10, 11, 12, 20), DshProcStat.childPids(stats, 1).toSet())
        assertEquals(setOf(11, 12), DshProcStat.childPids(stats, 10).toSet())
    }
}

class DshReadinessGateTest {

    private val entry = DshLaunchEntry("http://127.0.0.1:4321/?token=abcdefghijklmnopqrst", "http://127.0.0.1:4321", 4321)
    private val html = "<html><script>globalThis.__DSH_BOOT_READY__ ??= {};</script>" +
        "<script src=\"/plugins/@deepseek-ai/dsh-client-modules/client.js\"></script></html>"

    private fun probe(
        exchangeCode: Int = 303,
        withCookie: Boolean = true,
        httpOnly: Boolean = true,
        indexCode: Int = 200,
        includeBootMarker: Boolean = true,
        pluginCode: Int = 200,
        unauthCode: Int = 401,
        indexHtml: String = html
    ): DshHttpProbe = DshHttpProbe { url, cookie ->
        when {
            url.contains("?token=") -> {
                val cookieAttributes = if (httpOnly) {
                    "Max-Age=60; Path=/; HttpOnly; SameSite=Strict"
                } else {
                    "Max-Age=60; Path=/; SameSite=Strict"
                }
                val headers = if (withCookie) {
                    mapOf("Set-Cookie" to listOf("dsh-auth-abc=v1.payload.sig; $cookieAttributes"), "Location" to listOf("./"))
                } else {
                    mapOf("Location" to listOf("./"))
                }
                DshHttpResponse(exchangeCode, "", null, headers)
            }
            url.contains("/plugins/") && url.contains("client.js") ->
                DshHttpResponse(pluginCode, "export const x = 1\n".repeat(100), "text/javascript", emptyMap())
            url.endsWith("/") && cookie == null -> DshHttpResponse(unauthCode, "auth required", "text/plain", emptyMap())
            url.endsWith("/") -> DshHttpResponse(
                indexCode,
                if (includeBootMarker) indexHtml else "<html></html>",
                "text/html; charset=utf-8",
                emptyMap()
            )
            else -> DshHttpResponse(404, "", null, emptyMap())
        }
    }

    private fun gate(probe: DshHttpProbe, timeoutMillis: Long = 1_000) = DshReadinessGate(
        probe = probe,
        stabilityDelayMillis = 0,
        readinessTimeoutMillis = timeoutMillis,
        retryDelayMillis = 5,
        sleeper = { _ -> }
    )

    @Test
    fun readyOnlyAfterOfficialSurfaceIsStable() {
        val outcome = gate(probe()).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Ready)
        assertEquals("http://127.0.0.1:4321", (outcome as DshReadinessGate.Outcome.Ready).origin)
        assertTrue(outcome.cookie.startsWith("dsh-auth-"))
    }

    @Test
    fun rejectsWhenTokenExchangeNeverSucceeds() {
        val outcome = gate(probe(exchangeCode = 401, withCookie = true), timeoutMillis = 30).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(DshFailureCode.LOADER_NOT_READY, (outcome as DshReadinessGate.Outcome.Failed).code)
    }

    @Test
    fun rejectsIndexWithoutOfficialBootMarker() {
        val outcome = gate(probe(includeBootMarker = false), timeoutMillis = 30).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
    }

    @Test
    fun rejectsWhenAuthFenceDoesNotRefuse() {
        val outcome = gate(probe(unauthCode = 200), timeoutMillis = 30).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
    }

    @Test
    fun abortsImmediatelyWhenTheProcessExits() {
        val outcome = gate(probe()).awaitReady(entry) { true }
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(DshFailureCode.NODE_START_FAILED, (outcome as DshReadinessGate.Outcome.Failed).code)
    }

    @Test
    fun cookieReaderKeepsOnlyTheSessionPair() {
        val cookie = DshSetCookie.firstCookie("dsh-auth-abc=v1.x.y; Max-Age=60; HttpOnly; SameSite=Strict")
        assertEquals("dsh-auth-abc=v1.x.y", cookie)
        assertTrue(DshSetCookie.hasAttribute("dsh-auth-abc=v1; HttpOnly", "HttpOnly"))
        assertFalse(DshSetCookie.hasAttribute("dsh-auth-abc=v1", "HttpOnly"))
    }

    @Test
    fun readyForTheOfficialDocumentRelativeComboBootstrapUrl() {
        // The official renderer strips the leading slash (comboReference), so the real index
        // carries `plugins/??...` without a leading slash.
        val comboHtml = "<html><script>globalThis.__DSH_BOOT_READY__ ??= {};</script>" +
            "<link rel=\"preload\" as=\"script\" " +
            "href=\"plugins/??@deepseek-ai/dsh-client-modules/client.js,&amp;rev=abc123\">" +
            "</html>"
        val outcome = gate(probe(indexHtml = comboHtml)).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Ready)
        val ready = outcome as DshReadinessGate.Outcome.Ready
        assertTrue(ready.pluginAsset.startsWith("/plugins/??"))
        assertFalse(ready.pluginAsset.contains("&amp;"))
    }

    @Test
    fun readyForTheAbsoluteComboBootstrapUrl() {
        val comboHtml = "<html><script>globalThis.__DSH_BOOT_READY__ ??= {};</script>" +
            "<script src=\"/plugins/??@deepseek-ai/dsh-client-modules/client.js&amp;rev=abc123\"></script>" +
            "</html>"
        val outcome = gate(probe(indexHtml = comboHtml)).awaitReady(entry)
        assertTrue(outcome is DshReadinessGate.Outcome.Ready)
        assertTrue((outcome as DshReadinessGate.Outcome.Ready).pluginAsset.startsWith("/plugins/??"))
    }

    @Test
    fun reportsTheVerifiedStartupBoundariesAndNeverTheFirstFrame() {
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(probe()).awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Ready)
        assertEquals(
            listOf(
                DshStartupStage.PORT_LISTENING,
                DshStartupStage.HTTP_AUTHENTICATED,
                DshStartupStage.PLUGINS_LOADED
            ),
            stages
        )
    }

    @Test
    fun anOpenPortWithoutTheOfficialSurfaceIsNeverReady() {
        val stages = mutableListOf<DshStartupStage>()
        // Every request answers 200 text/plain: a listener exists, but no launch-token exchange.
        val plain = DshHttpProbe { _, _ -> DshHttpResponse(200, "ok", "text/plain", emptyMap()) }
        val outcome = gate(plain, timeoutMillis = 30).awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(DshFailureCode.LOADER_NOT_READY, (outcome as DshReadinessGate.Outcome.Failed).code)
        assertEquals(listOf(DshStartupStage.PORT_LISTENING), stages.distinct())
    }

    @Test
    fun theCookieMustAlsoServeASecondAuthenticatedPageRequest() {
        var authenticatedIndexRequests = 0
        val flaky = DshHttpProbe { url, cookie ->
            when {
                url.contains("?token=") -> {
                    authenticatedIndexRequests = 0
                    DshHttpResponse(
                        303, "", null,
                        mapOf(
                            "Set-Cookie" to listOf(
                                "dsh-auth-abc=v1; Max-Age=60; Path=/; HttpOnly; SameSite=Strict"
                            ),
                            "Location" to listOf("./")
                        )
                    )
                }
                url.contains("/plugins/") ->
                    DshHttpResponse(200, "export const x = 1\n".repeat(100), "text/javascript", emptyMap())
                cookie == null -> DshHttpResponse(401, "auth required", "text/plain", emptyMap())
                else -> {
                    authenticatedIndexRequests += 1
                    if (authenticatedIndexRequests <= 1) {
                        DshHttpResponse(200, html, "text/html; charset=utf-8", emptyMap())
                    } else {
                        DshHttpResponse(401, "auth required", "text/plain", emptyMap())
                    }
                }
            }
        }
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(flaky, timeoutMillis = 30).awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(DshFailureCode.LOADER_NOT_READY, (outcome as DshReadinessGate.Outcome.Failed).code)
        assertTrue(
            "the gate must make a second authenticated page request",
            authenticatedIndexRequests >= 2
        )
        assertFalse(stages.contains(DshStartupStage.FIRST_FRAME_RENDERED))
    }

    @Test
    fun aClientPluginFailureStopsTheTimelineAtHttpAuthenticated() {
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(probe(pluginCode = 404), timeoutMillis = 30)
            .awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(
            listOf(DshStartupStage.PORT_LISTENING, DshStartupStage.HTTP_AUTHENTICATED),
            stages.distinct()
        )
        assertFalse(stages.contains(DshStartupStage.PLUGINS_LOADED))
    }

    @Test
    fun aBootMarkerlessIndexIsAuthenticatedButNotPluginLoaded() {
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(probe(includeBootMarker = false), timeoutMillis = 30)
            .awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(
            listOf(DshStartupStage.PORT_LISTENING, DshStartupStage.HTTP_AUTHENTICATED),
            stages.distinct()
        )
    }

    @Test
    fun aCookieWithoutHttpOnlyNeverCountsAsAuthenticated() {
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(probe(httpOnly = false), timeoutMillis = 30)
            .awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(listOf(DshStartupStage.PORT_LISTENING), stages.distinct())
    }

    @Test
    fun aCookieThatTheIndexStillRejectsIsNotAuthenticated() {
        val stages = mutableListOf<DshStartupStage>()
        val outcome = gate(probe(indexCode = 401), timeoutMillis = 30)
            .awaitReady(entry, onStage = { stage -> stages += stage })
        assertTrue(outcome is DshReadinessGate.Outcome.Failed)
        assertEquals(
            DshFailureCode.LOADER_NOT_READY,
            (outcome as DshReadinessGate.Outcome.Failed).code
        )
        assertEquals(listOf(DshStartupStage.PORT_LISTENING), stages.distinct())
    }
}

/**
 * DSHA G1: the managed Node process is launched with the user workspace as `ProcessBuilder.directory`,
 * while HOME/DSH_WORKSPACE and the payload tool paths stay in their isolated roots.
 */
class AndroidDshNodeHostTest {

    @Test
    fun processStartsInTheWorkspaceWithTheIsolatedEnvironment() {
        val root = java.nio.file.Files.createTempDirectory("dsh-host-workspace").toFile()
        try {
            val workspace = File(root, "workspace").apply { assertTrue(mkdirs() || isDirectory) }
            val environment = mapOf(
                "HOME" to File(root, "home").absolutePath,
                "DSH_HOME" to File(root, "home").absolutePath,
                "DSH_WORKSPACE" to workspace.absolutePath,
                "LD_LIBRARY_PATH" to File(root, "payload/runtime/lib").absolutePath,
                "PATH" to File(root, "payload/bin").absolutePath
            )

            val builder = AndroidDshNodeHost().newProcessBuilder(
                arguments = listOf("/native/libdshnode.so", "--version"),
                environment = environment,
                workingDir = workspace
            )

            assertEquals(
                "the process cwd must be the user workspace, never the replaceable payload tree",
                workspace.canonicalFile,
                builder.directory()?.canonicalFile
            )
            assertTrue("merged stdout/stderr is required for readiness parsing", builder.redirectErrorStream())
            for ((key, value) in environment) {
                assertEquals("environment $key must reach the child", value, builder.environment()[key])
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
