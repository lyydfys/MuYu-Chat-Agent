package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PTY session outlives the capability request that opened it, so it must hold a maintenance
 * lease for its whole life. These tests pin that lifetime: acquire before the child exists, hold
 * while open, release exactly once when the session ends, and refuse instead of spawning while an
 * exclusive maintenance write owns the data.
 */
class DshTerminalSessionAdmissionTest {

    private val workingDirectory = File(System.getProperty("java.io.tmpdir")).absolutePath
    private val model = dshTestModel()

    private class CountingAdmission(var refuse: Boolean = false) : DshTerminalSessionAdmission {
        val acquires = AtomicInteger()
        val releases = AtomicInteger()
        val keys = ArrayList<String>()

        override fun acquire(ownerKey: String, sessionId: String): (() -> Unit)? {
            if (refuse) return null
            acquires.incrementAndGet()
            keys.add("$ownerKey/$sessionId")
            return { releases.incrementAndGet() }
        }
    }

    private fun host(
        admission: DshTerminalSessionAdmission,
        port: ScriptedTerminalPort = ScriptedTerminalPort()
    ): DshTerminalHost = DshTerminalHost(
        factory = fakeFactory(port),
        modelProvider = { model },
        environmentProvider = { mapOf("PATH" to "/payload/bin:/system/bin") },
        workingDirectoryProvider = { workingDirectory },
        maxSessions = 4,
        maxSessionsPerOwner = 4,
        initialOutputMillis = 20L,
        autoPump = false,
        sessionAdmission = admission
    )

    @Test
    fun anOpenSessionHoldsItsLeaseUntilItIsClosed() {
        val admission = CountingAdmission()
        val host = host(admission)

        val opened = host.open("dsh")
        assertTrue(opened is DshTerminalOpenOutcome.Opened)
        assertEquals(1, admission.acquires.get())
        assertEquals(0, admission.releases.get())
        val sessionId = (opened as DshTerminalOpenOutcome.Opened).snapshot.sessionId
        assertTrue(admission.keys.single().endsWith("/$sessionId"))

        host.close("dsh", sessionId)

        assertEquals(1, admission.releases.get())

        // Closing again must not release a second time.
        host.close("dsh", sessionId)
        assertEquals(1, admission.releases.get())
    }

    @Test
    fun aRefusedAdmissionNeverSpawnsTheChild() {
        val admission = CountingAdmission(refuse = true)
        val host = host(admission)

        val outcome = host.open("dsh")

        assertTrue(outcome is DshTerminalOpenOutcome.Refused)
        assertEquals(
            DshTerminalRefusal.MAINTENANCE_ACTIVE,
            (outcome as DshTerminalOpenOutcome.Refused).reason
        )
        assertEquals(0, admission.acquires.get())
        assertEquals(0, admission.releases.get())
    }

    @Test
    fun closeAllReleasesEverySessionLeaseOnce() {
        val admission = CountingAdmission()
        val host = host(admission)
        host.open("dsh")
        host.open("dsh")

        assertEquals(2, admission.acquires.get())
        host.closeAll()
        assertEquals(2, admission.releases.get())
    }

    @Test
    fun aSessionWithoutAdmissionStaysUnmanaged() {
        val host = DshTerminalHost(
            factory = fakeFactory(ScriptedTerminalPort()),
            modelProvider = { model },
            environmentProvider = { mapOf("PATH" to "/payload/bin:/system/bin") },
            workingDirectoryProvider = { workingDirectory },
            initialOutputMillis = 20L,
            autoPump = false
        )

        val opened = host.open("dsh")
        assertTrue(opened is DshTerminalOpenOutcome.Opened)
        host.close("dsh", (opened as DshTerminalOpenOutcome.Opened).snapshot.sessionId)
    }
}
