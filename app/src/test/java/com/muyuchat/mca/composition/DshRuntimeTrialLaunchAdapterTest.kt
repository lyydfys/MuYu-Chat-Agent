package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHostClient
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.maintenance.DshClosureTrialHost
import com.muyuchat.core.dsh.maintenance.DshHostHealth
import com.muyuchat.core.dsh.maintenance.DshHostLifecyclePort
import com.muyuchat.core.dsh.maintenance.DshHostState
import com.muyuchat.core.dsh.maintenance.DshHostStatus
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchRequest
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchResult
import com.muyuchat.core.dsh.maintenance.DshTrialPhase as MaintenanceTrialPhase
import com.muyuchat.core.dsh.maintenance.DshTrialStopProof
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests of the D24 app-side trial-launch adapter.
 *
 * Two things are pinned here:
 * 1. without the runtime-owner seam the adapter is *honestly* unavailable: it reports the exact
 *    "not connected" failure, never RUNNING, and its trivially-stopped projection keeps a failed
 *    attempt out of `RECOVERY_REQUIRED`;
 * 2. with a typed client, every owner reply is mapped without optimism (STOPPED is a refusal,
 *    UNKNOWN is a failure, a stop is only proven by a real STOPPED reply).
 */
class DshRuntimeTrialLaunchAdapterTest {

    @Test
    fun `unconnected launch reports the exact not connected failure`() {
        val result = DshRuntimeTrialLaunchAdapter(client = null).launch(request())

        assertTrue(result is DshTrialLaunchResult.Failed)
        assertEquals(
            DshRuntimeTrialLaunchAdapter.NOT_CONNECTED_DETAIL,
            (result as DshTrialLaunchResult.Failed).detail
        )
    }

    @Test
    fun `unconnected adapter never reports a ready trial`() {
        val adapter = DshRuntimeTrialLaunchAdapter(client = null)

        val status = adapter.status()
        assertFalse(status.running == true)
        assertEquals(false, status.running)

        val health = adapter.probeHealth()
        assertFalse(health.hostReady)
        assertFalse(health.homeReadable)
        assertFalse(health.sessionReaderReady)
        assertFalse(health.providerConfigured)
        assertFalse(health.authReady)
        assertFalse(health.healthy)
    }

    @Test
    fun `unconnected stop is trivially proven because no trial could start`() {
        assertEquals(
            DshTrialStopProof.Stopped,
            DshRuntimeTrialLaunchAdapter(client = null).stopAndProve(1_000L)
        )
    }

    @Test
    fun `running reply becomes a typed started result with owner identity`() {
        val client = FakeTrialClient(
            startReply = DshTrialSnapshot(
                phase = DshTrialPhase.RUNNING,
                pid = 4_242L,
                generation = 9L,
                detail = "trial generation started"
            )
        )
        val requested = request()

        val result = DshRuntimeTrialLaunchAdapter(client).launch(requested)

        assertTrue(result is DshTrialLaunchResult.Started)
        val started = result as DshTrialLaunchResult.Started
        assertEquals(4_242L, started.pid)
        assertEquals(9L, started.generation)
        // The owner receives the app-private absolute roots, never relative paths.
        assertEquals(requested.candidateRoot.absolutePath, client.lastCandidateRoot)
        assertEquals(requested.homeRoot.absolutePath, client.lastHomeRoot)
        assertEquals(requested.workspaceRoot.absolutePath, client.lastWorkspaceRoot)
        assertEquals(requested.tmpRoot.absolutePath, client.lastTmpRoot)
        assertEquals(requested.candidateFingerprint, client.lastFingerprint)
    }

    @Test
    fun `stopped reply is a refusal not a success`() {
        val client = FakeTrialClient(
            startReply = DshTrialSnapshot(DshTrialPhase.STOPPED, detail = "production host was running")
        )

        val result = DshRuntimeTrialLaunchAdapter(client).launch(request())

        assertTrue(result is DshTrialLaunchResult.Refused)
        assertEquals("production host was running", (result as DshTrialLaunchResult.Refused).reason)
    }

    @Test
    fun `unknown reply is never optimistic`() {
        val client = FakeTrialClient(startReply = DshTrialSnapshot(DshTrialPhase.UNKNOWN))

        val result = DshRuntimeTrialLaunchAdapter(client).launch(request())

        assertTrue(result is DshTrialLaunchResult.Failed)
        assertFalse(result is DshTrialLaunchResult.Started)
    }

    @Test
    fun `failed reply is a typed failure and never started`() {
        val client = FakeTrialClient(
            startReply = DshTrialSnapshot(DshTrialPhase.FAILED, detail = "trial generation exited")
        )

        val result = DshRuntimeTrialLaunchAdapter(client).launch(request())

        assertTrue(result is DshTrialLaunchResult.Failed)
        assertEquals("trial generation exited", (result as DshTrialLaunchResult.Failed).detail)
    }

    @Test
    fun `running without a process id is not a proven trial`() {
        val client = FakeTrialClient(
            startReply = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 0L, generation = 3L)
        )

        val result = DshRuntimeTrialLaunchAdapter(client).launch(request())

        assertTrue(result is DshTrialLaunchResult.Failed)
        assertFalse(result is DshTrialLaunchResult.Started)
        assertTrue((result as DshTrialLaunchResult.Failed).detail.contains("process id"))
    }

    @Test
    fun `a client exception is a typed failure`() {
        val client = FakeTrialClient(throwOnStart = IllegalStateException("binder died"))

        val result = DshRuntimeTrialLaunchAdapter(client).launch(request())

        assertEquals(
            DshTrialLaunchResult.Failed("trial launch failed: IllegalStateException"),
            result
        )
    }

    @Test
    fun `the typed factory stays unconnected for a runtime client without the trial contract`() {
        val port = DshRuntimeTrialLaunchAdapter.fromRuntimeClient(PlainRuntimeClient())

        val result = port.launch(request())

        assertTrue(result is DshTrialLaunchResult.Failed)
        assertEquals(
            DshRuntimeTrialLaunchAdapter.NOT_CONNECTED_DETAIL,
            (result as DshTrialLaunchResult.Failed).detail
        )
        assertFalse("an unconnected port cannot claim a trial is running", port.status().running == true)
    }

    @Test
    fun `the typed factory uses a runtime client that implements the trial contract`() {
        val client = TrialRuntimeClient(
            startReply = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 991L, generation = 4L)
        )

        val port = DshRuntimeTrialLaunchAdapter.fromRuntimeClient(client)
        val result = port.launch(request())

        assertTrue(result is DshTrialLaunchResult.Started)
        assertEquals(991L, (result as DshTrialLaunchResult.Started).pid)
        assertEquals(1, client.startCalls)
    }

    @Test
    fun `a connected port starts health-checks and stops one isolated trial`() {
        val root = File("build/tmp/dsh-trial-connected").absoluteFile
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val candidate = File(root, "closure/incoming/candidate-1").apply {
            mkdirs()
            File(this, "candidate.json").writeText("""{"candidateId":"candidate-1"}""", Charsets.UTF_8)
        }
        val client = TrialRuntimeClient(
            startReply = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 4_321L, generation = 7L),
            healthReply = DshTrialHealthSnapshot(
                hostReady = true,
                homeReadable = true,
                sessionReaderReady = true,
                detail = "trial host ready"
            ),
            stopReply = DshTrialSnapshot(DshTrialPhase.STOPPED)
        )
        val host = DshClosureTrialHost(
            production = FakeProductionHost(),
            trialPort = { DshRuntimeTrialLaunchAdapter.fromRuntimeClient(client) },
            dataRoot = root
        )

        val started = host.startTrial(candidate, "a".repeat(64))

        assertEquals(DshHostState.RUNNING, started.state)
        val journal = host.activeTrial()
        assertEquals(MaintenanceTrialPhase.RUNNING, journal?.phase)
        assertEquals(4_321L, journal?.pid)

        val health = host.probeTrialHealth()
        assertTrue(health.hostReady)
        assertTrue(health.homeReadable)
        assertTrue(health.sessionReaderReady)
        // The isolated trial serves no account; a connected trial is never reported healthy.
        assertFalse(health.providerConfigured)
        assertFalse(health.authReady)
        assertFalse(health.healthy)

        assertEquals(DshHostState.STOPPED, host.stopTrial(1_000L).state)
        assertEquals(MaintenanceTrialPhase.STOPPED, host.activeTrial()?.phase)
        assertEquals(1, client.stopCalls)
    }

    @Test
    fun `a stop is proven only by a real stopped reply`() {
        val adapter = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(stopReply = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 77L))
        )
        assertTrue(adapter.stopAndProve(1_000L) is DshTrialStopProof.Unverified)

        val unverified = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(stopReply = DshTrialSnapshot(DshTrialPhase.UNKNOWN))
        )
        assertTrue(unverified.stopAndProve(1_000L) is DshTrialStopProof.Unverified)

        val stopped = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(stopReply = DshTrialSnapshot(DshTrialPhase.STOPPED))
        )
        assertEquals(DshTrialStopProof.Stopped, stopped.stopAndProve(1_000L))
    }

    @Test
    fun `trial health never claims an account or provider`() {
        val client = FakeTrialClient(
            healthReply = DshTrialHealthSnapshot(
                hostReady = true,
                homeReadable = true,
                sessionReaderReady = true,
                detail = "trial host ready"
            )
        )

        val health = DshRuntimeTrialLaunchAdapter(client).probeHealth()

        assertTrue(health.hostReady)
        assertTrue(health.homeReadable)
        assertTrue(health.sessionReaderReady)
        assertFalse(health.providerConfigured)
        assertFalse(health.authReady)
        assertFalse("an isolated trial has no account, so it is never fully healthy", health.healthy)
    }

    @Test
    fun `a failed status probe is unknown not stopped`() {
        val client = FakeTrialClient(throwOnStatus = IllegalStateException("timeout"))

        val status = DshRuntimeTrialLaunchAdapter(client).status()

        assertNull(status.running)
    }

    @Test
    fun `status maps the owner reply verbatim`() {
        val running = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(statusReply = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 12L))
        ).status()
        assertEquals(true, running.running)
        assertEquals(12L, running.pid)

        val stopped = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(statusReply = DshTrialSnapshot(DshTrialPhase.STOPPED))
        ).status()
        assertEquals(false, stopped.running)

        val unknown = DshRuntimeTrialLaunchAdapter(
            FakeTrialClient(statusReply = DshTrialSnapshot(DshTrialPhase.UNKNOWN))
        ).status()
        assertNull(unknown.running)
    }

    @Test
    fun `closure trial host journals the unconnected failure without faking ready`() {
        val root = File("build/tmp/dsh-trial-adapter-integration").absoluteFile
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val candidate = File(root, "closure/incoming/candidate-1").apply {
            mkdirs()
            File(this, "candidate.json").writeText("""{"candidateId":"candidate-1"}""", Charsets.UTF_8)
        }

        val host = DshClosureTrialHost(
            production = FakeProductionHost(),
            trialPort = { DshRuntimeTrialLaunchAdapter(client = null) },
            dataRoot = root
        )

        val started = host.startTrial(candidate, "a".repeat(64))

        assertEquals(DshHostState.FAILED, started.state)
        assertTrue(started.detail.contains("not connected"))
        val journal = host.activeTrial()
        assertNotNull(journal)
        assertEquals(MaintenanceTrialPhase.FAILED, journal?.phase)
        // The failed attempt is not a live generation; production status is still authoritative.
        assertEquals(DshHostState.STOPPED, host.status().state)
        assertFalse(host.probeTrialHealth().hostReady)

        // Stopping a failed attempt must not invent RECOVERY_REQUIRED for a process that never ran.
        val stopped = host.stopTrial(1_000L)
        assertEquals(DshHostState.STOPPED, stopped.state)
        assertEquals(MaintenanceTrialPhase.STOPPED, host.activeTrial()?.phase)
    }

    private fun request(): DshTrialLaunchRequest {
        val root = File("build/tmp/dsh-trial-adapter-request").absoluteFile
        return DshTrialLaunchRequest(
            candidateRoot = File(root, "candidate"),
            candidateFingerprint = "a".repeat(64),
            homeRoot = File(root, "home"),
            workspaceRoot = File(root, "workspace"),
            tmpRoot = File(root, "tmp"),
            activeJournal = File(root, "active.json")
        )
    }

    private class FakeTrialClient(
        private val startReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.STOPPED),
        private val stopReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.STOPPED),
        private val statusReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.STOPPED),
        private val healthReply: DshTrialHealthSnapshot = DshTrialHealthSnapshot(),
        private val throwOnStart: RuntimeException? = null,
        private val throwOnStatus: RuntimeException? = null
    ) : DshTrialHostClient {

        var lastCandidateRoot: String? = null
        var lastFingerprint: String? = null
        var lastHomeRoot: String? = null
        var lastWorkspaceRoot: String? = null
        var lastTmpRoot: String? = null

        override suspend fun startTrial(
            candidateRoot: String,
            candidateFingerprint: String,
            homeRoot: String,
            workspaceRoot: String,
            tmpRoot: String
        ): DshTrialSnapshot {
            throwOnStart?.let { throw it }
            lastCandidateRoot = candidateRoot
            lastFingerprint = candidateFingerprint
            lastHomeRoot = homeRoot
            lastWorkspaceRoot = workspaceRoot
            lastTmpRoot = tmpRoot
            return startReply
        }

        override suspend fun stopTrial(): DshTrialSnapshot = stopReply

        override suspend fun trialStatus(): DshTrialSnapshot {
            throwOnStatus?.let { throw it }
            return statusReply
        }

        override suspend fun trialHealth(): DshTrialHealthSnapshot = healthReply
    }

    /** The app's runtime client shape without the D24 trial contract (today's build). */
    private class PlainRuntimeClient : DshRuntimeClient {
        private val state = MutableStateFlow(DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED))
        override val snapshot: StateFlow<DshRuntimeSnapshot> = state

        override suspend fun ensureStarted(): DshRuntimeSnapshot = state.value
        override suspend fun restart(): DshRuntimeSnapshot = state.value
        override suspend fun stop() {
            state.value = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED)
        }

        override suspend fun webEntry(): DshWebEntry? = null
    }

    /** A runtime client that also implements the typed trial contract (the owner half lands here). */
    private class TrialRuntimeClient(
        private val startReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.RUNNING, pid = 1L),
        private val stopReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.STOPPED),
        private val statusReply: DshTrialSnapshot = DshTrialSnapshot(DshTrialPhase.STOPPED),
        private val healthReply: DshTrialHealthSnapshot = DshTrialHealthSnapshot()
    ) : DshRuntimeClient, DshTrialHostClient {

        private val state = MutableStateFlow(DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED))
        override val snapshot: StateFlow<DshRuntimeSnapshot> = state

        var startCalls = 0
            private set
        var stopCalls = 0
            private set

        override suspend fun ensureStarted(): DshRuntimeSnapshot = state.value
        override suspend fun restart(): DshRuntimeSnapshot = state.value
        override suspend fun stop() {
            state.value = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED)
        }

        override suspend fun webEntry(): DshWebEntry? = null

        override suspend fun startTrial(
            candidateRoot: String,
            candidateFingerprint: String,
            homeRoot: String,
            workspaceRoot: String,
            tmpRoot: String
        ): DshTrialSnapshot {
            startCalls++
            return startReply
        }

        override suspend fun stopTrial(): DshTrialSnapshot {
            stopCalls++
            return stopReply
        }

        override suspend fun trialStatus(): DshTrialSnapshot = statusReply

        override suspend fun trialHealth(): DshTrialHealthSnapshot = healthReply
    }

    private class FakeProductionHost : DshHostLifecyclePort {
        override fun status(): DshHostStatus = DshHostStatus(DshHostState.STOPPED)

        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
            error("the trial is owned by the wrapper, not the production half")

        override fun probeTrialHealth(): DshHostHealth = DshHostHealth()

        override fun stopTrial(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun probeReadOnlyHealth(): DshHostHealth = DshHostHealth()
    }
}
