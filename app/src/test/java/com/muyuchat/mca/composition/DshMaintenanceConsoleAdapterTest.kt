package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.runtime.config.DshMcpDeclarationStore
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshHostHealth
import com.muyuchat.core.dsh.maintenance.DshHostLifecyclePort
import com.muyuchat.core.dsh.maintenance.DshHostState
import com.muyuchat.core.dsh.maintenance.DshHostStatus
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionOutcome
import com.muyuchat.core.dsh.maintenance.DshMaintenanceAdmissionPort
import com.muyuchat.core.dsh.maintenance.DshMaintenanceLease
import com.muyuchat.core.dsh.maintenance.DshMaintenanceOwner
import com.muyuchat.core.dsh.maintenance.DshMaintenanceProbeResult
import com.muyuchat.core.dsh.maintenance.DshMaintenanceRecoveryProbe
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.feature.dsh.workspace.DshMaintenanceConsolePolicy
import com.muyuchat.feature.dsh.workspace.DshMaintenanceConsoleProjector
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety-path tests of the D23 console adapter. They deliberately avoid constructing the real
 * maintenance owner: the rules under test (confirmation, stopped-host gate, honest unavailable
 * projection) must hold before any owner is touched.
 */
class DshMaintenanceConsoleAdapterTest {

    private class FakeRuntime(initial: DshRuntimePhase) : DshRuntimeClient {
        val state = MutableStateFlow(DshRuntimeSnapshot(phase = initial))
        override val snapshot: StateFlow<DshRuntimeSnapshot> = state
        override suspend fun ensureStarted(): DshRuntimeSnapshot = state.value
        override suspend fun restart(): DshRuntimeSnapshot = state.value
        override suspend fun stop() {
            state.value = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED)
        }

        override suspend fun webEntry(): DshWebEntry? = null
    }

    private fun adapter(
        phase: DshRuntimePhase,
        owner: () -> Nothing = { error("owner must not be constructed") }
    ) = DshMaintenanceConsoleAdapter(
        dataRoot = File("build/tmp/d23-console-test"),
        runtime = FakeRuntime(phase),
        maintenance = owner,
        clock = { 1_000L }
    )

    @Test
    fun `snapshot is honestly unavailable when the owner cannot be built`() = runTest {
        val snapshot = adapter(DshRuntimePhase.STOPPED).snapshot()
        assertEquals(DshMaintenanceConsoleAvailability.UNAVAILABLE, snapshot.availability)
        assertTrue(snapshot.availableOperations.isEmpty())
        assertTrue(snapshot.backups.isEmpty())
    }

    @Test
    fun `destructive intent without confirmation is refused before any owner work`() = runTest {
        val result = adapter(DshRuntimePhase.STOPPED).execute(
            DshMaintenanceConsoleIntent.RestoreBackup(backupId = "backup-1", confirmed = false)
        )
        assertTrue(result is DshMaintenanceConsoleResult.Refused)
        assertEquals(
            DshMaintenanceConsoleRefusal.CONFIRMATION_REQUIRED,
            (result as DshMaintenanceConsoleResult.Refused).reason
        )
    }

    @Test
    fun `mutation while the host is ready is refused as busy`() = runTest {
        val result = adapter(DshRuntimePhase.READY).execute(
            DshMaintenanceConsoleIntent.CreateBackup(label = null)
        )
        assertTrue(result is DshMaintenanceConsoleResult.Refused)
        assertEquals(DshMaintenanceConsoleRefusal.BUSY, (result as DshMaintenanceConsoleResult.Refused).reason)
    }

    @Test
    fun `trash session while the host is ready is refused rather than touching data`() = runTest {
        val result = adapter(DshRuntimePhase.READY).execute(
            DshMaintenanceConsoleIntent.TrashSession(sessionId = "session-1")
        )
        assertTrue(result is DshMaintenanceConsoleResult.Refused)
    }

    @Test
    fun `self-check is not gated as busy and fails honestly without an owner`() = runTest {
        val result = adapter(DshRuntimePhase.READY).execute(DshMaintenanceConsoleIntent.RunSelfCheck)
        assertTrue(result is DshMaintenanceConsoleResult.Refused)
        assertEquals(
            DshMaintenanceConsoleRefusal.UNAVAILABLE,
            (result as DshMaintenanceConsoleResult.Refused).reason
        )
    }

    @Test
    fun `read-only listing stays available without constructing the owner`() = runTest {
        val result = adapter(DshRuntimePhase.READY).execute(DshMaintenanceConsoleIntent.ListSessions)
        assertTrue(result is DshMaintenanceConsoleResult.Applied)
    }

    /**
     * A session moved to the recycle bin must stay in the projected session list with
     * `trashed = true` while the host is stopped, so the existing per-row restore/purge controls
     * are both rendered and enabled. The same actions must stay unadvertised while the host runs:
     * the console has no online mutator barrier (D07m).
     */
    @Test
    fun `a trashed session is projected with restore and purge actions while the host is stopped`() = runTest {
        val root = File("build/tmp/d23-console-trash-${System.nanoTime()}").absoluteFile
            .also { it.mkdirs() }
        try {
            val entryName = "261027-120000-261027-112233-72d"
            File(root, "home/sessions-deleted/$entryName").also { it.mkdirs() }
                .let { entry ->
                    File(entry, ".origin").writeText(
                        """{"slug":"261027-112233-72d","dirName":"261027-112233-72d",""" +
                            """"at":"2026-10-27T12:00:00Z","sessionId":"261027-112233-72d"}"""
                    )
                }
            val owner = owner(root)

            val stopped = console(root, owner, DshRuntimePhase.STOPPED).snapshot()

            assertEquals(1, stopped.trashCount)
            assertTrue(DshMaintenanceOperationKind.RESTORE_TRASHED_SESSION in stopped.availableOperations)
            assertTrue(DshMaintenanceOperationKind.PURGE_TRASHED_SESSION in stopped.availableOperations)
            val projected = stopped.sessions.singleOrNull { it.trashed }
            assertTrue("the trashed session must be part of the projected session list", projected != null)
            val trashed = requireNotNull(projected)
            assertEquals(entryName, trashed.sessionId)
            assertFalse(trashed.messageCountKnown)

            // Projection: the row the section renders carries the trash flag and the trash detail.
            val model = DshMaintenanceConsoleProjector.project(stopped)
            val row = model.sessions.singleOrNull { it.sessionId == trashed.sessionId }
            assertTrue("the rendered session list must contain the trashed row", row != null)
            assertTrue(requireNotNull(row).trashed)
            assertTrue(requireNotNull(row).detail.startsWith("回收站"))

            // Both per-row actions are offered (the UI enables each button when its intent is here).
            val available = DshMaintenanceConsolePolicy.availableIntents(stopped, portAvailable = true)
            assertTrue(
                DshMaintenanceConsoleIntent.RestoreTrashedSession(trashed.sessionId) in available
            )
            assertTrue(
                DshMaintenanceConsoleIntent.PurgeTrashedSession(trashed.sessionId) in available
            )

            // Running host: the row is still visible, but no mutation is advertised.
            val running = console(root, owner, DshRuntimePhase.READY).snapshot()
            assertEquals(1, running.trashCount)
            assertTrue(running.sessions.any { it.trashed })
            assertFalse(DshMaintenanceOperationKind.RESTORE_TRASHED_SESSION in running.availableOperations)
            assertFalse(DshMaintenanceOperationKind.PURGE_TRASHED_SESSION in running.availableOperations)
            val runningAvailable = DshMaintenanceConsolePolicy.availableIntents(running, portAvailable = true)
            assertFalse(
                DshMaintenanceConsoleIntent.RestoreTrashedSession(trashed.sessionId) in runningAvailable
            )
            assertFalse(
                DshMaintenanceConsoleIntent.PurgeTrashedSession(trashed.sessionId) in runningAvailable
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun console(
        root: File,
        owner: DshMaintenanceOwner,
        phase: DshRuntimePhase
    ): DshMaintenanceConsoleAdapter = DshMaintenanceConsoleAdapter(
        dataRoot = root,
        runtime = FakeRuntime(phase),
        maintenance = { owner },
        clock = { 1_000L }
    )

    private fun owner(root: File): DshMaintenanceOwner = DshMaintenanceOwner(
        dataRoot = root,
        admission = DshMaintenanceAdmissionPort {
            DshMaintenanceAdmissionOutcome.Granted(
                object : DshMaintenanceLease {
                    override val leaseId: String = "lease-1"
                    override val owner: String = "console-test"
                    override fun release() = Unit
                }
            )
        },
        probe = DshMaintenanceRecoveryProbe { _, _, _ -> DshMaintenanceProbeResult.HEALTHY },
        host = NoTrialHost(),
        supportedAbis = setOf("arm64-v8a"),
        candidateLookup = { DshClosureCandidateLookup.None }
    )

    /** The production half only; the trial half is owned by the DshClosureTrialHost wrapper. */
    private class NoTrialHost : DshHostLifecyclePort {
        override fun status(): DshHostStatus = DshHostStatus(DshHostState.STOPPED)

        override fun stopAndConfirm(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startTrial(candidateRoot: File, candidateFingerprint: String): DshHostStatus =
            DshHostStatus(DshHostState.FAILED, detail = "no trial generation")

        override fun probeTrialHealth(): DshHostHealth = DshHostHealth(detail = "no trial generation")

        override fun stopTrial(timeoutMillis: Long): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun startProduction(activeRoot: File, signature: String): DshHostStatus =
            DshHostStatus(DshHostState.STOPPED)

        override fun probeReadOnlyHealth(): DshHostHealth = DshHostHealth()
    }

    @Test
    fun `mcp declaration import validates and publishes without the maintenance owner`() = runTest {
        val root = File("build/tmp/d23-mcp-import-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val home = File(root, "home").also { it.mkdirs() }
            val store = DshMcpDeclarationStore(home)
            val console = DshMaintenanceConsoleAdapter(
                dataRoot = root,
                runtime = FakeRuntime(DshRuntimePhase.STOPPED),
                maintenance = { error("owner must not be constructed for an MCP import") },
                clock = { 1_000L },
                mcpDeclarations = store
            )
            val document =
                """{"version":1,"servers":[{"transport":"stdio","serverName":"alpha",""" +
                    """"command":"node","args":["a.js"],"env":{}}]}"""
            val applied = console.execute(
                DshMaintenanceConsoleIntent.ImportMcpDeclaration(payload = document, confirmed = true)
            )
            assertTrue(applied is DshMaintenanceConsoleResult.Applied)
            assertEquals(1, store.read().servers.size)

            val missing = console.execute(
                DshMaintenanceConsoleIntent.ImportMcpDeclaration(payload = null, confirmed = true)
            )
            assertTrue(missing is DshMaintenanceConsoleResult.Refused)

            val invalid = console.execute(
                DshMaintenanceConsoleIntent.ImportMcpDeclaration(payload = "{not json", confirmed = true)
            )
            assertTrue(invalid is DshMaintenanceConsoleResult.Failed)
            assertEquals("MCP_DECLARATION_INVALID", (invalid as DshMaintenanceConsoleResult.Failed).code)
            assertTrue(store.declarationFile.readText(Charsets.UTF_8).contains("alpha"))
        } finally {
            root.deleteRecursively()
        }
    }

}
