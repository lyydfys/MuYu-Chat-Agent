package com.muyuchat.core.dsh.runtime

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import com.muyuchat.core.dsh.runtime.payload.DshPayloadCriticalFile
import com.muyuchat.core.dsh.runtime.payload.DshPayloadInstallResult
import com.muyuchat.core.dsh.runtime.payload.DshPayloadLicense
import com.muyuchat.core.dsh.runtime.payload.DshPayloadManifest
import com.muyuchat.core.dsh.runtime.payload.DshPayloadProvisioner
import com.muyuchat.core.dsh.runtime.payload.DshPayloadSourceInfo
import com.muyuchat.core.dsh.runtime.process.DshCommandResult
import com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy
import com.muyuchat.core.dsh.runtime.process.DshNodeExecutable
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.process.DshProcessRunner
import com.muyuchat.core.dsh.runtime.process.DshStopOutcome
import com.muyuchat.core.dsh.runtime.readiness.DshHttpProbe
import com.muyuchat.core.dsh.runtime.readiness.DshHttpResponse
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the two generation-safety rules the app integration depends on:
 * no model gateway means no Node launch, and a successful start yields exactly one READY
 * generation with an in-memory entry that repeated ensureStarted calls reuse.
 */
class DshRuntimeOwnerTest {

    private val sha = "a".repeat(64)
    private val manifest = DshPayloadManifest(
        manifestVersion = 1,
        payloadVersion = "dsh-test",
        archiveAsset = "dsh/payload.zip",
        archiveSize = 1,
        archiveSha256 = sha,
        nodeLibraryName = "libdshnode.so",
        nodeSize = 1,
        nodeSha256 = "b".repeat(64),
        kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
        runtimeLibraryDir = "runtime/lib",
        fileCount = 1,
        criticalFiles = listOf(DshPayloadCriticalFile("x", 1, sha)),
        source = DshPayloadSourceInfo(null, null, null),
        licenses = listOf(DshPayloadLicense("DSH", "MIT"))
    )

    private class FakeHost : DshNodeHost {
        override val output = DshOutputRing(10)
        override val isAlive: Boolean get() = alive
        override val exitCode: Int? get() = null
        override val processId: Int? get() = 4242
        var alive = false
        var starts = 0
        var stops = 0
        var launch: DshLaunchEntry? = null
        var startedIn: File? = null
        var nextStopOutcome: DshStopOutcome? = null

        override fun start(executable: DshNodeExecutable, arguments: List<String>, environment: Map<String, String>, workingDir: File) {
            starts++
            startedIn = workingDir
            alive = true
        }

        override fun awaitLaunchEntry(timeoutMillis: Long, shouldAbort: () -> Boolean) = launch

        override fun stop(graceMillis: Long, killMillis: Long): DshStopOutcome {
            stops++
            alive = false
            return nextStopOutcome ?: DshStopOutcome(true, false, false, "test")
        }

        override fun tail(lines: Int) = ""
    }

    private class FakeDeps(
        val root: File,
        private val endpoint: DshGatewayEndpoint?,
        private val seeded: Boolean = true
    ) : DshRuntimeOwner.Deps {
        var hostStarts = 0
        var seedCalls = 0
        val host = FakeHost().apply {
            launch = DshLaunchEntry(
                authenticatedUrl = "http://127.0.0.1:4321/?token=abcdefghijklmnopqrst",
                origin = "http://127.0.0.1:4321",
                port = 4321
            )
        }
        val logs = mutableListOf<String>()

        override val payload = DshPayloadProvisioner {
            DshPayloadInstallResult.Installed(
                manifest = DshPayloadManifest(
                    manifestVersion = 1,
                    payloadVersion = "dsh-test",
                    archiveAsset = "dsh/payload.zip",
                    archiveSize = 1,
                    archiveSha256 = "a".repeat(64),
                    nodeLibraryName = "libdshnode.so",
                    nodeSize = 1,
                    nodeSha256 = "b".repeat(64),
                    kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                    runtimeLibraryDir = "runtime/lib",
                    fileCount = 1,
                    criticalFiles = listOf(DshPayloadCriticalFile("x", 1, "a".repeat(64))),
                    source = DshPayloadSourceInfo(null, null, null),
                    licenses = emptyList()
                ),
                archiveSha256 = "a".repeat(64),
                reused = true
            )
        }
        override val execStrategy = DshNodeExecStrategy(FakeRunner())
        override val readinessGate = DshReadinessGate(
            probe = DshHttpProbe { url, cookie ->
                when {
                    url.contains("?token=") -> DshHttpResponse(
                        303, "", null,
                        mapOf(
                            "Set-Cookie" to listOf("dsh-auth-x=v1; Max-Age=1; HttpOnly; SameSite=Strict"),
                            "Location" to listOf("./")
                        )
                    )
                    url.contains("/plugins/") -> DshHttpResponse(200, "x".repeat(600), "text/javascript", emptyMap())
                    cookie == null -> DshHttpResponse(401, "no", "text/plain", emptyMap())
                    else -> DshHttpResponse(
                        200,
                        "<script>__DSH_BOOT_READY__</script><script src=\"/plugins/@deepseek-ai/dsh-client-modules/client.js\"></script>",
                        "text/html",
                        emptyMap()
                    )
                }
            },
            stabilityDelayMillis = 0,
            readinessTimeoutMillis = 500,
            retryDelayMillis = 5,
            sleeper = { _ -> }
        )
        override val supportedAbis = listOf("arm64-v8a")
        override val packagedAbis = listOf("arm64-v8a")

        override suspend fun gatewayEndpoint(): DshGatewayEndpoint? = endpoint

        override suspend fun seedDshHome(endpoint: DshGatewayEndpoint): Boolean {
            seedCalls++
            return seeded
        }

        override fun nativeLibraryDir(): File = root
        override fun newNodeHost(): DshNodeHost {
            hostStarts++
            return host
        }

        var alivePids: Set<Int> = emptySet()
        var orphanPids: List<Int> = emptyList()
        var orphanScanAvailable: Boolean = true

        override fun isProcessAlive(pid: Int) = pid in alivePids
        override fun findOrphanedHostProcesses(): List<Int>? =
            if (orphanScanAvailable) orphanPids else null
        override fun prepareRuntimeLinks(executable: DshNodeExecutable, payloadRoot: File) {}
        override fun log(line: String) {
            logs += line
        }
    }

    private class FakeRunner : DshProcessRunner {
        override fun run(argv: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): DshCommandResult =
            DshCommandResult(0, "v26.4.0\n", "", false)
    }

    private fun config(root: File) = DshRuntimeConfig(
        dataRoot = root,
        authLineTimeoutMillis = 2_000,
        readinessTimeoutMillis = 2_000,
        nodeExecProbeTimeoutMillis = 2_000,
        stopGraceMillis = 1_000,
        stopKillMillis = 500
    ).validated()

    @Test
    fun missingGatewayFailsClosedBeforeAnyProcessIsCreated() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = null)
        val owner = DshRuntimeOwner(config(root), deps)
        val snapshot = owner.ensureStarted()
        assertEquals(DshRuntimePhase.FAILED, snapshot.phase)
        assertEquals(DshFailureCode.GATEWAY_UNAVAILABLE, snapshot.failure?.code)
        assertEquals(0, deps.hostStarts)
        assertEquals(0, deps.seedCalls)
        assertNull(owner.webEntry())
        // The generation timeline exists, but no process/HTTP/plugin stage was observed.
        assertEquals(snapshot.epoch, owner.startupProgress.value.epoch)
        assertNull(owner.startupProgress.value.stage)
        assertTrue(owner.startupProgress.value.events.isEmpty())
    }

    @Test
    fun gatewaySeedingFailureAlsoBlocksLaunch() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "t", "rev"), seeded = false)
        val snapshot = DshRuntimeOwner(config(root), deps).ensureStarted()
        assertEquals(DshFailureCode.GATEWAY_UNAVAILABLE, snapshot.failure?.code)
        assertEquals(0, deps.hostStarts)
    }

    @Test
    fun readyGenerationIsAttachedNotRestartedAndStopsCleanly() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val cfg = config(root)
        val owner = DshRuntimeOwner(cfg, deps)
        val first = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, first.phase)
        assertEquals(1L, first.epoch)
        // Node and its tools must run inside the isolated workspace, never inside the
        // hash-verified payload tree (fs/sandbox roots derive from process.cwd()).
        assertEquals(cfg.workspaceDir.absolutePath, deps.host.startedIn?.absolutePath)
        assertEquals("http://127.0.0.1:4321", first.origin)
        val entry = owner.webEntry()
        assertNotNull(entry)
        assertEquals(1L, entry!!.epoch)
        val second = owner.ensureStarted()
        assertEquals(first.epoch, second.epoch)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)
        owner.stop()
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertTrue(deps.host.stops >= 1)
        assertNull(owner.webEntry())
    }

    @Test
    fun readyPublishesTheTypedStagesButNeverClaimsARenderedPage() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        val snapshot = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, snapshot.phase)

        val progress = owner.startupProgress.value
        assertEquals(snapshot.epoch, progress.epoch)
        assertEquals(DshStartupStage.PLUGINS_LOADED, progress.stage)
        assertEquals(
            listOf(
                DshStartupStage.NODE_PROCESS_ALIVE,
                DshStartupStage.PORT_LISTENING,
                DshStartupStage.HTTP_AUTHENTICATED,
                DshStartupStage.PLUGINS_LOADED
            ),
            progress.reachedStages
        )
        assertFalse(
            "DshRuntimePhase.READY is the HTTP surface, not a WebView first frame",
            progress.hasReached(DshStartupStage.FIRST_FRAME_RENDERED)
        )
        // Each accepted boundary is also visible in the engineering log, not only in the flow.
        assertTrue(deps.logs.any { it.contains("startup stage=NODE_PROCESS_ALIVE") })
        assertTrue(deps.logs.any { it.contains("startup stage=PLUGINS_LOADED") })
    }

    @Test
    fun eachGenerationGetsItsOwnStartupTimeline() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(1L, owner.ensureStarted().epoch)
        assertEquals(1L, owner.startupProgress.value.epoch)
        assertEquals(4, owner.startupProgress.value.events.size)

        owner.stop()
        val second = owner.ensureStarted()
        assertEquals(2L, second.epoch)
        assertEquals(2L, owner.startupProgress.value.epoch)
        assertEquals(4, owner.startupProgress.value.events.size)
        assertEquals(DshStartupStage.PLUGINS_LOADED, owner.startupProgress.value.stage)
        assertFalse(owner.startupProgress.value.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
    }

    @Test
    fun firstFrameReportAdvancesTheTimelineWithoutTouchingTheSnapshot() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        val ready = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, ready.phase)

        // No page exists yet and a stale/future generation is not accepted.
        assertFalse(owner.reportFirstFrameRendered(0L))
        assertFalse(owner.reportFirstFrameRendered(ready.epoch + 1L))
        assertFalse(owner.startupProgress.value.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
        assertEquals(DshRuntimePhase.READY, owner.snapshot.value.phase)

        assertTrue(owner.reportFirstFrameRendered(ready.epoch))
        val progress = owner.startupProgress.value
        assertEquals(DshStartupStage.FIRST_FRAME_RENDERED, progress.stage)
        assertTrue(progress.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
        assertEquals(5, progress.events.size)
        assertTrue(deps.logs.any { it.contains("startup stage=FIRST_FRAME_RENDERED") })

        // Additive observability: the frozen snapshot is unchanged and duplicates are rejected.
        assertEquals(ready, owner.snapshot.value)
        assertFalse(
            "a duplicate report must not append a second event",
            owner.reportFirstFrameRendered(ready.epoch)
        )
        assertEquals(5, owner.startupProgress.value.events.size)
    }

    @Test
    fun firstFrameOfAStoppedOrPreviousGenerationIsRejected() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(1L, owner.ensureStarted().epoch)
        owner.stop()
        assertFalse(owner.reportFirstFrameRendered(1L))
        assertFalse(owner.startupProgress.value.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))

        assertEquals(2L, owner.ensureStarted().epoch)
        assertFalse(owner.reportFirstFrameRendered(1L))
        assertFalse(owner.startupProgress.value.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
        assertTrue(owner.reportFirstFrameRendered(2L))
        assertTrue(owner.startupProgress.value.hasReached(DshStartupStage.FIRST_FRAME_RENDERED))
    }
    @Test
    fun unsettledStopReportsSurvivorsAndBlocksTheNextHost() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        val survivors = listOf(4242, 5151)
        deps.alivePids = survivors.toSet()
        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "SIGKILL did not settle pids=$survivors",
            trackedPids = survivors,
            survivingPids = survivors
        )

        owner.stop()
        val fenced = owner.snapshot.value
        assertEquals(DshRuntimePhase.FAILED, fenced.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, fenced.failure?.code)
        assertTrue(fenced.failure?.diagnostics?.contains("4242") == true)
        assertTrue(fenced.failure?.diagnostics?.contains("5151") == true)
        assertNull(owner.webEntry())

        val startsBefore = deps.hostStarts
        val seedsBefore = deps.seedCalls
        val blocked = owner.ensureStarted()
        assertEquals(DshRuntimePhase.FAILED, blocked.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertEquals(startsBefore, deps.hostStarts)
        assertEquals(seedsBefore, deps.seedCalls)
    }

    @Test
    fun orphanPayloadProcessKeepsTheFenceUntilItDisappears() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        val fencedPid = 4242
        deps.alivePids = setOf(fencedPid)
        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "SIGKILL did not settle pids=[$fencedPid]",
            trackedPids = listOf(fencedPid),
            survivingPids = listOf(fencedPid)
        )
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)

        deps.alivePids = emptySet()
        deps.orphanPids = listOf(9090)
        val startsBefore = deps.hostStarts
        val seedsBefore = deps.seedCalls
        val blocked = owner.ensureStarted()
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertEquals(startsBefore, deps.hostStarts)
        assertEquals(seedsBefore, deps.seedCalls)

        deps.orphanPids = emptyList()
        val next = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, next.phase)
        assertEquals(2L, next.epoch)
        assertEquals(startsBefore + 1, deps.hostStarts)
        assertEquals(seedsBefore + 1, deps.seedCalls)
    }

    @Test
    fun cleanStopReportsStoppedWithTrackedPidsAndClearsTheFence() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = true,
            escalated = false,
            fenceRequired = false,
            detail = "SIGTERM",
            trackedPids = listOf(4242),
            survivingPids = emptyList()
        )
        owner.stop()
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertNull(owner.snapshot.value.failure)

        val second = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, second.phase)
        assertEquals(2L, second.epoch)
    }
    @Test
    fun deadExistingHostWithLiveOrphanBlocksStart() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)
        val startsBefore = deps.hostStarts
        val seedsBefore = deps.seedCalls

        // Crash shape: the tracked root is dead but a reparented same-UID payload child lives.
        deps.host.alive = false
        deps.orphanPids = listOf(7777)
        val blocked = owner.ensureStarted()
        assertEquals(DshRuntimePhase.FAILED, blocked.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertTrue(blocked.failure?.diagnostics?.contains("7777") == true)
        assertEquals(startsBefore, deps.hostStarts)
        assertEquals(seedsBefore, deps.seedCalls)
    }

    @Test
    fun freshOwnerWithLiveOrphanBlocksStartThenStartsWhenClean() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)

        // A fresh service instance has no in-memory fence; the start-time scan must block.
        deps.orphanPids = listOf(8888)
        val blocked = owner.ensureStarted()
        assertEquals(DshRuntimePhase.FAILED, blocked.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertTrue(blocked.failure?.diagnostics?.contains("8888") == true)
        assertEquals(0, deps.hostStarts)
        assertEquals(0, deps.seedCalls)

        deps.orphanPids = emptyList()
        val started = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, started.phase)
        assertEquals(1L, started.epoch)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)
    }

    @Test
    fun fenceWithoutKnownPidsIsGuardedByStartScan() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        // Root pid unavailable: fenceRequired with no surviving PID names.
        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "pid unavailable; surviving set unknown",
            trackedPids = emptyList(),
            survivingPids = emptyList()
        )
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)

        val startsBefore = deps.hostStarts
        deps.orphanPids = listOf(1234)
        val blocked = owner.ensureStarted()
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertTrue(blocked.failure?.diagnostics?.contains("1234") == true)
        assertEquals(startsBefore, deps.hostStarts)

        deps.orphanPids = emptyList()
        val next = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, next.phase)
        assertEquals(2L, next.epoch)
        assertEquals(startsBefore + 1, deps.hostStarts)
    }

    @Test
    fun noOrphanStartsNormallyOnAFreshOwner() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)

        val ready = owner.ensureStarted()
        assertEquals(DshRuntimePhase.READY, ready.phase)
        assertEquals(1L, ready.epoch)
        assertEquals(1, deps.hostStarts)
        assertEquals(1, deps.seedCalls)
        assertNull(ready.failure)
    }
    @Test
    fun scanUnavailableFailsClosedOnStartAndFirstStop() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)

        deps.orphanScanAvailable = false
        val blocked = owner.ensureStarted()
        assertEquals(DshRuntimePhase.FAILED, blocked.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, blocked.failure?.code)
        assertTrue(blocked.failure?.diagnostics?.contains("unavailable") == true)
        assertEquals(0, deps.hostStarts)
        assertEquals(0, deps.seedCalls)

        deps.orphanScanAvailable = true
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        // Clean host reply but the verification scan is unavailable: STOPPED is forbidden.
        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = true,
            escalated = false,
            fenceRequired = false,
            detail = "SIGTERM",
            trackedPids = listOf(4242)
        )
        deps.orphanScanAvailable = false
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)

        deps.orphanScanAvailable = true
        owner.stop()
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertNull(owner.snapshot.value.failure)
    }

    @Test
    fun secondStopAfterFenceStaysBlockedUntilScanProvesClean() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)

        deps.alivePids = setOf(4242)
        deps.host.nextStopOutcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "SIGKILL did not settle pids=[4242]",
            trackedPids = listOf(4242),
            survivingPids = listOf(4242)
        )
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        deps.alivePids = emptySet()

        // Host==null with a raised fence: an unavailable scan must not clear it.
        deps.orphanScanAvailable = false
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)
        assertTrue(owner.snapshot.value.failure?.diagnostics?.contains("unavailable") == true)

        // Scan available but a reparented payload process lives: still fenced.
        deps.orphanScanAvailable = true
        deps.orphanPids = listOf(9090)
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)
        assertTrue(owner.snapshot.value.failure?.diagnostics?.contains("9090") == true)

        // Scan available and clean: the second-stop path may now publish STOPPED.
        deps.orphanPids = emptyList()
        owner.stop()
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertNull(owner.snapshot.value.failure)
    }
    @Test
    fun freshOwnerStopScansBeforeStopped() = runTest {
        val root = java.nio.file.Files.createTempDirectory("dsh-owner").toFile()
        File(root, "libdshnode.so").writeBytes(byteArrayOf(1))
        val deps = FakeDeps(root, endpoint = DshGatewayEndpoint("http://127.0.0.1:9/v1", "token", "rev1"))
        val owner = DshRuntimeOwner(config(root), deps)

        // No host and no in-memory fence, but the scan cannot prove the absence of a reparented
        // Node process: STOPPED must not be published.
        deps.orphanScanAvailable = false
        owner.stop()
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)
        assertTrue(owner.snapshot.value.failure?.diagnostics?.contains("unavailable") == true)
        assertEquals(0, deps.hostStarts)

        // Available and empty scan is the only proof that allows STOPPED.
        deps.orphanScanAvailable = true
        owner.stop()
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertNull(owner.snapshot.value.failure)
        assertEquals(0, deps.hostStarts)
    }
}
