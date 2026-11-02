package com.muyuchat.core.dsh.runtime

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.log.DshOutputRing
import com.muyuchat.core.dsh.runtime.payload.DshPayloadCriticalFile
import com.muyuchat.core.dsh.runtime.payload.DshPayloadInstallResult
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
import com.muyuchat.core.dsh.runtime.trial.DshTrialTestFiles
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D24 owner-side trial generation: the trial runs in the one host slot without publishing READY,
 * production is refused while it runs, and its release needs a real stop proof.
 */
class DshRuntimeOwnerTrialTest {

    private class FakeHost : DshNodeHost {
        override val output = DshOutputRing(10)
        override val isAlive: Boolean get() = alive
        override val exitCode: Int? get() = if (alive) null else 0
        override val processId: Int? get() = 4242
        var alive = false
        var starts = 0
        var stops = 0
        var launch: DshLaunchEntry? = null
        var workingDir: File? = null
        var arguments: List<String> = emptyList()
        var environment: Map<String, String> = emptyMap()
        var nextStopOutcome: DshStopOutcome? = null

        override fun start(
            executable: DshNodeExecutable,
            arguments: List<String>,
            environment: Map<String, String>,
            workingDir: File
        ) {
            starts++
            this.arguments = arguments
            this.environment = environment
            this.workingDir = workingDir
            alive = true
        }

        override fun awaitLaunchEntry(timeoutMillis: Long, shouldAbort: () -> Boolean) = launch

        override fun stop(graceMillis: Long, killMillis: Long): DshStopOutcome {
            stops++
            alive = false
            return nextStopOutcome ?: DshStopOutcome(true, false, false, "test SIGTERM")
        }

        override fun tail(lines: Int) = ""
    }

    private class FakeDeps(private val root: File) : DshRuntimeOwner.Deps {
        val hosts = mutableListOf<FakeHost>()
        val readableHost = FakeHost().apply {
            launch = DshLaunchEntry(
                authenticatedUrl = "http://127.0.0.1:4321/?token=abcdefghijklmnopqrst",
                origin = "http://127.0.0.1:4321",
                port = 4321
            )
        }
        val logs = mutableListOf<String>()
        var linkPreparations = 0
        val linkRoots = mutableListOf<File>()
        var orphanPids: List<Int> = emptyList()
        var orphanScanAvailable = true
        var alivePids: MutableSet<Int> = mutableSetOf()
        var commandLines: MutableMap<Int, String> = mutableMapOf()
        var signals: MutableList<Pair<Int, Int>> = mutableListOf()
        var signalDelivered = true

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
        override val execStrategy = DshNodeExecStrategy(DshProcessRunner { _, _, _, _ ->
            DshCommandResult(0, "v26.4.0\n", "", false)
        })
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
                        "<script>__DSH_BOOT_READY__</script>" +
                            "<script src=\"/plugins/@deepseek-ai/dsh-client-modules/client.js\"></script>",
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
        private val endpoint: DshGatewayEndpoint? = DshGatewayEndpoint(
            baseUrl = "http://127.0.0.1:9999",
            bearerToken = "bearer-secret",
            catalogRevision = "rev-1"
        )

        override suspend fun gatewayEndpoint(): DshGatewayEndpoint? = endpoint
        override suspend fun seedDshHome(endpoint: DshGatewayEndpoint): Boolean = true
        override suspend fun seedDshHomeAccountOnly(): Boolean = true
        override fun nativeLibraryDir(): File = root
        override fun newNodeHost(): DshNodeHost = readableHost.also { hosts += it }
        override fun isProcessAlive(pid: Int): Boolean = pid in alivePids
        override fun findOrphanedHostProcesses(): List<Int>? = if (orphanScanAvailable) orphanPids else null
        override fun prepareRuntimeLinks(executable: DshNodeExecutable, payloadRoot: File) {
            linkPreparations++
            linkRoots += payloadRoot
        }
        override fun log(line: String) {
            logs += line
        }
        override fun processCommandLine(pid: Int): String? = commandLines[pid]
        override fun signalProcess(pid: Int, signal: Int): Boolean {
            signals += pid to signal
            if (!signalDelivered) return false
            alivePids.remove(pid)
            return true
        }
    }

    private fun root(): File = DshTrialTestFiles.newRoot("dsh-owner-trial")

    private fun config(root: File) = DshRuntimeConfig(
        dataRoot = root,
        port = 4321,
        authLineTimeoutMillis = 2_000,
        readinessTimeoutMillis = 2_000,
        nodeExecProbeTimeoutMillis = 2_000,
        stopGraceMillis = 1_000,
        stopKillMillis = 500,
        settlePollMillis = 5
    ).validated()

    private fun fixtures(root: File): DshTrialTestFiles.Fixtures = DshTrialTestFiles.materialize(root)

    @Test
    fun aTrialRunsInTheOneHostSlotWithoutPublishingReady() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)

        val snapshot = owner.startTrial(
            candidateRoot = fixtures.candidate,
            candidateFingerprint = fixtures.fingerprint,
            homeRoot = fixtures.trialHome,
            workspaceRoot = fixtures.trialWorkspace,
            tmpRoot = fixtures.trialTmp
        )

        assertEquals(DshTrialPhase.RUNNING, snapshot.phase)
        assertEquals(4242L, snapshot.pid)
        assertTrue(snapshot.generation > 0L)
        // The production projection is STARTING (so production is refused), never READY.
        assertEquals(DshRuntimePhase.STARTING, owner.snapshot.value.phase)
        assertNull(owner.snapshot.value.origin)
        assertNull(owner.webEntry())

        // Production ensureStarted must not attach to, replace or kill the trial generation.
        val refused = owner.ensureStarted()
        assertEquals(DshRuntimePhase.STARTING, refused.phase)
        assertTrue(deps.readableHost.alive)
        assertEquals(1, deps.hosts.size)
        assertEquals(DshTrialPhase.RUNNING, owner.trialStatus().phase)
    }

    @Test
    fun theTrialUsesItsOwnIsolatedRootsTheVerifiedPayloadAndNoSecret() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)

        assertEquals(
            DshTrialPhase.RUNNING,
            owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp).phase
        )

        val host = deps.readableHost
        assertEquals(fixtures.trialWorkspace.absolutePath, host.workingDir?.absolutePath)
        assertEquals(fixtures.trialHome.absolutePath, host.environment["HOME"])
        assertEquals(fixtures.trialHome.absolutePath, host.environment["DSH_HOME"])
        assertEquals(fixtures.trialTmp.absolutePath, host.environment["TMPDIR"])
        assertEquals(
            File(fixtures.trialHome, "config").absolutePath,
            host.environment["DSH_CONFIG_DIR"]
        )
        // The candidate's own payload tree is executed - never the installed payload, which would
        // make the trial pass regardless of what the candidate contains.
        val launched = host.arguments.firstOrNull {
            it.replace('\\', '/').endsWith("dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        }
        assertNotNull(launched)
        assertEquals(
            fixtures.candidatePayloadKernelEntry.absolutePath.replace('\\', '/'),
            launched!!.replace('\\', '/')
        )
        assertNotEquals(
            fixtures.payloadKernelEntry.absolutePath.replace('\\', '/'),
            launched.replace('\\', '/')
        )
        // --port 0 is forced even though the production configuration names 4321.
        val portIndex = host.arguments.indexOf("--port")
        assertTrue(portIndex >= 0)
        assertEquals("0", host.arguments[portIndex + 1])
        assertEquals("127.0.0.1", host.arguments[host.arguments.indexOf("--host") + 1])
        // No production secret and no capability bridge reach a trial child.
        assertFalse(host.environment.containsKey(DshRuntimeOwner.MCA_DSH_GATEWAY_TOKEN_ENV))
        assertFalse(host.environment.keys.any { it.startsWith("APP_LOCAL_") || it.startsWith("APP_NOTIFY_") })
        // Dependency-link mutation belongs to the verified payload and is skipped for a trial.
        assertEquals(0, deps.linkPreparations)
    }

    @Test
    fun aCommittedClosurePreparesItsOwnLinkFarm() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        val closurePayload = File(fixtures.candidate, "payload")
        // A closure descriptor must carry an executable payload block; otherwise admission refuses
        // it (rightly) before any generation starts.
        val kernel = fixtures.candidatePayloadKernelEntry
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(kernel.readBytes())
            .joinToString("") { "%02x".format(it) }
        File(fixtures.candidate, "candidate.json").writeText(
            """{"candidateId":"candidate-20261021-01","targetAbi":"arm64-v8a","dataSchemaVersion":5,""" +
                """"payload":{"root":"payload","archiveSha256":"${"0".repeat(64)}",""" +
                """"kernelEntry":"dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js","criticalFiles":[""" +
                """{"path":"dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js","sha256":"$sha"}]}}"""
        )

        owner.setActiveClosurePayload(closurePayload)
        val started = owner.ensureStarted()
        assertEquals(started.failure?.toString(), DshRuntimePhase.READY, started.phase)

        // A committed closure is a different tree from the installed payload: its own link farm
        // must be prepared there (a reinstall also dangles every installed-payload link).
        assertEquals(1, deps.linkPreparations)
        val served = deps.linkRoots.single()
        assertTrue(fixtures.candidatePayloadKernelEntry.absolutePath.startsWith(served.absolutePath))
    }

    @Test
    fun aTrialIsRefusedWhileProductionIsReady() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)
        assertEquals(1, deps.linkPreparations)
        // The installed generation prepares its links inside the tree it serves.
        assertTrue(
            fixtures.payloadKernelEntry.absolutePath.startsWith(deps.linkRoots.single().absolutePath)
        )

        val refused = owner.startTrial(
            fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp
        )

        // STOPPED is the app-side port's refusal signal; nothing was started or stopped.
        assertEquals(DshTrialPhase.STOPPED, refused.phase)
        assertEquals(1, deps.readableHost.starts)
        assertEquals(0, deps.readableHost.stops)
        assertEquals(DshRuntimePhase.READY, owner.snapshot.value.phase)
    }

    @Test
    fun aTrialRefusesRootsOutsideTheManagedTrialAndClosureTrees() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)

        val outsideHome = DshTrialTestFiles.newRoot("dsh-trial-outside")
        val candidateOutside = DshTrialTestFiles.newRoot("dsh-candidate-outside")

        val refusals = listOf(
            owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, outsideHome),
            owner.startTrial(candidateOutside, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp),
            // The real home is never a trial home.
            owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.productionHome, fixtures.trialWorkspace, fixtures.trialTmp),
            // A malformed fingerprint is a refusal, not a launch.
            owner.startTrial(fixtures.candidate, "not-a-sha256", fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp)
        )

        for (refusal in refusals) {
            assertEquals(DshTrialPhase.STOPPED, refusal.phase)
            assertTrue(refusal.detail.isNotBlank())
            // Path-free: a detail crosses Binder and must never leak a filesystem path.
            assertFalse(refusal.detail.contains("/"))
            assertFalse(refusal.detail.contains("\\"))
        }
        assertEquals(0, deps.readableHost.starts)
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
    }

    @Test
    fun theTrialHealthIsBuiltOnlyFromPositiveObservations() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        DshTrialTestFiles.writeSession(fixtures.productionHome, "slug-a", "session-1")
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)

        // No trial -> nothing positive is claimed.
        val idle = owner.trialHealth()
        assertFalse(idle.hostReady)
        assertFalse(idle.homeReadable)
        assertFalse(idle.sessionReaderReady)

        assertEquals(
            DshTrialPhase.RUNNING,
            owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp).phase
        )
        val health = owner.trialHealth()
        assertTrue(health.hostReady)
        assertTrue(health.homeReadable)
        // The data half was copied into the isolated home and its official container verified.
        assertTrue(health.sessionReaderReady)
        assertTrue(File(fixtures.trialHome, "sessions/slug-a/session-1/session.v4.jsonl.zstd").isFile)
        // The runtime owner never claims provider/auth readiness for a trial.
        assertFalse(health.detail.contains("provider", ignoreCase = true))
        assertFalse(health.detail.contains("auth", ignoreCase = true))
    }

    @Test
    fun aTrialReleaseRequiresARealStopProof() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp)

        deps.readableHost.nextStopOutcome = DshStopOutcome(true, false, false, "test SIGTERM")
        val stopped = owner.stopTrial()
        assertEquals(DshTrialPhase.STOPPED, stopped.phase)
        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        // The slot is free again: production may start.
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)
    }

    @Test
    fun aTrialReleaseWithSurvivorsIsFailedAndKeepsProductionBlocked() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp)

        deps.readableHost.nextStopOutcome = DshStopOutcome(
            stopped = false,
            escalated = true,
            fenceRequired = true,
            detail = "test SIGKILL did not settle",
            trackedPids = listOf(4242),
            survivingPids = listOf(4242)
        )
        deps.orphanPids = listOf(4242)
        deps.alivePids = mutableSetOf(4242)

        val verdict = owner.stopTrial()
        assertEquals(DshTrialPhase.FAILED, verdict.phase)
        assertTrue(verdict.detail.contains("4242"))
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)

        // A surviving trial tree keeps the shared fence up: production must not start.
        assertEquals(DshRuntimePhase.FAILED, owner.ensureStarted().phase)
        assertEquals(1, deps.readableHost.starts)
    }

    @Test
    fun anUnavailableSurvivorScanIsUnknownNeverStopped() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp)

        deps.orphanScanAvailable = false
        val verdict = owner.stopTrial()

        assertEquals(DshTrialPhase.UNKNOWN, verdict.phase)
        assertEquals(DshRuntimePhase.FAILED, owner.snapshot.value.phase)
        assertEquals(DshFailureCode.STOP_TIMEOUT, owner.snapshot.value.failure?.code)
        // Doubt keeps the shared fence up: production must not start while the scan is unavailable.
        assertEquals(DshRuntimePhase.FAILED, owner.ensureStarted().phase)
        assertEquals(1, deps.readableHost.starts)

        // Once a positive scan is possible again the fence clears and production may start.
        deps.orphanScanAvailable = true
        deps.orphanPids = emptyList()
        assertEquals(DshRuntimePhase.READY, owner.ensureStarted().phase)
    }

    @Test
    fun aFreshOwnerRecoversAJournaledTrialWithCmdlineAttribution() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        DshTrialTestFiles.writeJournal(
            root,
            phase = "RUNNING",
            pid = 777L,
            generation = 3L,
            candidateRoot = fixtures.candidate
        )
        val deps = FakeDeps(root)
        deps.alivePids = mutableSetOf(777)
        deps.commandLines = mutableMapOf(
            777 to "${root.absolutePath}${File.separator}libdshnode.so web --port 0"
        )
        val owner = DshRuntimeOwner(config(root), deps)

        // Fresh status consults the journal + /proc instead of trusting a cached lifecycle value.
        assertEquals(DshTrialPhase.RUNNING, owner.trialStatus().phase)

        val stopped = owner.stopTrial()
        assertEquals(DshTrialPhase.STOPPED, stopped.phase)
        assertEquals(listOf(777 to 15), deps.signals)
        assertFalse(deps.alivePids.contains(777))
    }

    @Test
    fun aJournaledPidWithoutPayloadAttributionIsNeverKilled() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        DshTrialTestFiles.writeJournal(
            root,
            phase = "RUNNING",
            pid = 888L,
            generation = 4L,
            candidateRoot = fixtures.candidate
        )
        val deps = FakeDeps(root)
        deps.alivePids = mutableSetOf(888)
        deps.commandLines = mutableMapOf(888 to "/system/bin/some-other-process")
        val owner = DshRuntimeOwner(config(root), deps)

        assertEquals(DshTrialPhase.UNKNOWN, owner.trialStatus().phase)
        val stopped = owner.stopTrial()
        assertEquals(DshTrialPhase.UNKNOWN, stopped.phase)
        assertTrue(deps.signals.isEmpty())
        assertTrue(deps.alivePids.contains(888))
    }

    @Test
    fun aDeadJournaledPidIsStoppedOnlyWithACleanScan() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        DshTrialTestFiles.writeJournal(
            root,
            phase = "RUNNING",
            pid = 999L,
            generation = 5L,
            candidateRoot = fixtures.candidate
        )
        val deps = FakeDeps(root)
        deps.alivePids = mutableSetOf()
        val owner = DshRuntimeOwner(config(root), deps)
        assertEquals(DshTrialPhase.STOPPED, owner.trialStatus().phase)
        assertEquals(DshTrialPhase.STOPPED, owner.stopTrial().phase)

        deps.orphanScanAvailable = false
        val unknown = owner.trialStatus()
        assertEquals(DshTrialPhase.UNKNOWN, unknown.phase)
    }

    @Test
    fun aStopRequestReleasesTheTrialGenerationToo() = runTest {
        val root = root()
        val fixtures = fixtures(root)
        val deps = FakeDeps(root)
        val owner = DshRuntimeOwner(config(root), deps)
        owner.startTrial(fixtures.candidate, fixtures.fingerprint, fixtures.trialHome, fixtures.trialWorkspace, fixtures.trialTmp)

        owner.stop()

        assertEquals(DshRuntimePhase.STOPPED, owner.snapshot.value.phase)
        assertEquals(DshTrialPhase.STOPPED, owner.trialStatus().phase)
        assertEquals(1, deps.readableHost.stops)
    }
}
