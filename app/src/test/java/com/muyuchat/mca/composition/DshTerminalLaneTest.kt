package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.process.DshTerminalSeamInstaller
import com.muyuchat.core.dsh.runtime.process.DshTerminalSeamState
import com.muyuchat.core.dsh.runtime.process.DshTerminalToolRoutes
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generation policy of the terminal lane, exercised on the JVM.
 *
 * The native PTY cannot load here, so a real device run is NOT_RUN. What these tests do prove is
 * the ownership contract: one owner per generation, the previous owner is stopped before a new one
 * exists, stop facts are retained (never collapsed into success), and a missing runtime config
 * yields no routes instead of a half-wired family.
 */
class DshTerminalLaneTest {

    private val root = File(
        System.getProperty("java.io.tmpdir"),
        "dsh-terminal-lane-${System.nanoTime()}"
    ).apply { mkdirs() }

    private val config = DshRuntimeConfig(dataRoot = root).validated()
    private val nativeDir = File(root, "native").apply { mkdirs() }

    @After
    fun cleanUp() {
        root.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun openMountsTheFixedTerminalRoutesAndReportsTheRealTransport() {
        val lane = lane()

        val routes = lane.open(1)
        assertNotNull(routes)

        assertEquals(DshTerminalToolRoutes.PATHS.sorted(), routes!!.paths())
        val facts = lane.facts()
        assertTrue(facts.active)
        assertEquals(1L, facts.generation)
        assertNotNull(facts.transport)
        assertEquals(facts.realPty, facts.transport == "REAL_PTY")
        assertEquals(facts.realPty, facts.providesRealTerminal)
        // No sessions were opened by mounting, and a pipe-only JVM reports its concrete reason.
        assertEquals(0, facts.sessions)
        if (!facts.realPty) {
            assertNotNull(facts.unavailableReason)
            assertTrue(facts.consoleDetail().startsWith("no real pty"))
        }
    }

    @Test
    fun stopRetainsTheRealStopFactsAndIsIdempotent() {
        val lane = lane()
        lane.open(1)

        val shutdown = lane.stop()
        assertNotNull(shutdown)

        assertTrue(shutdown!!.settled)
        assertTrue(shutdown.sessions.isEmpty())
        val facts = lane.facts()
        assertFalse(facts.active)
        assertEquals(true, facts.lastStopSettled)
        assertEquals(1L, facts.lastStopGeneration)
        assertNull(facts.lastStopFailure)
        assertTrue(facts.lastStopSurvivors.isEmpty())
        // A second stop has nothing to stop; it must not fabricate a second stop result.
        assertNull(lane.stop())
    }

    @Test
    fun aNewGenerationStopsThePreviousOwnerFirst() {
        val lane = lane()
        lane.open(1)

        lane.open(2)

        val second = lane.facts()
        assertTrue(second.active)
        assertEquals(2L, second.generation)
        // The previous generation was really stopped before the new owner existed.
        assertEquals(true, second.lastStopSettled)
        assertEquals(1L, second.lastStopGeneration)
    }

    @Test
    fun reopeningTheSameGenerationIsIdempotent() {
        val lane = lane()

        val first = lane.open(3)!!
        val again = lane.open(3)!!

        assertEquals(first.paths(), again.paths())
        assertEquals(3L, lane.facts().generation)
        // Nothing was stopped: the same generation was asked twice.
        assertNull(lane.facts().lastStopSettled)
    }

    @Test
    fun anUnusableRuntimeConfigYieldsNoRoutesAndATypedReason() {
        val lane = DshTerminalLane(
            configProvider = { throw IllegalStateException("dsh root is not usable") },
            nativeLibraryDir = nativeDir,
            seamSource = { TEST_SEAM_SOURCE }
        )

        assertNull(lane.open(1))

        val facts = lane.facts()
        assertFalse(facts.active)
        assertTrue(facts.unavailableReason.orEmpty().startsWith("config: IllegalStateException"))
        assertFalse(facts.providesRealTerminal)
    }

    @Test
    fun aFirstRunHomeLeavesTheSeamPendingInsteadOfWriting() {
        val lane = lane()

        lane.open(1)

        val seam = lane.facts().seam
        assertTrue("expected a pending seam, was $seam", seam is DshTerminalSeamState.Pending)
        assertEquals(
            DshTerminalSeamState.SkipCode.HOME_NOT_SEEDED,
            (seam as DshTerminalSeamState.Pending).code
        )
        assertFalse(config.homeDir.exists())
    }

    @Test
    fun aSeededHomeGetsTheSeamWithinTheSameGeneration() {
        seedHome()
        val lane = lane()

        lane.open(1)

        val seam = lane.facts().seam
        assertTrue("expected an active seam, was $seam", seam is DshTerminalSeamState.Active)
        val overlay = File(config.homeDir, DshTerminalSeamInstaller.OVERLAY_NAME).readText()
        assertTrue(overlay.contains("id: ${DshTerminalSeamInstaller.BLOCK_ID}"))
        assertTrue(overlay.contains("- id: bash-sandbox"))
        assertTrue(
            File(config.homeDir, DshTerminalSeamInstaller.SEAM_DIR_RELATIVE + "/" +
                DshTerminalSeamInstaller.SEAM_FILE_NAME).isFile
        )
        assertTrue(lane.facts().consoleDetail().contains("seam=active"))
    }

    @Test
    fun factsAreReadOnlyBeforeAnyGeneration() {
        val lane = lane()

        val facts = lane.facts()

        assertFalse(facts.active)
        assertEquals(0L, facts.generation)
        assertNull(facts.transport)
        assertNull(facts.seam)
        assertNull(facts.lastStopSettled)
        assertEquals("the terminal owner is not open in this generation", facts.consoleDetail())
    }

    /** Seeds just enough of the home for the seeder's own "complete" gate to be satisfied. */
    private fun seedHome() {
        val profile = File(config.homeDir, DshTerminalSeamInstaller.PROFILE_ROOT_RELATIVE)
        profile.mkdirs()
        File(profile, DshTerminalSeamInstaller.PROFILE_ROOT_FILE).writeText("[]\n")
        File(config.homeDir, DshTerminalSeamInstaller.OVERLAY_NAME).writeText(
            "- id: bash-sandbox\n  disabled: true\n"
        )
    }

    private fun lane(): DshTerminalLane = DshTerminalLane(
        configProvider = { config },
        nativeLibraryDir = nativeDir,
        seamSource = { TEST_SEAM_SOURCE }
    )

    private companion object {
        /** Shaped like the real asset; the installer only requires the two load-bearing markers. */
        val TEST_SEAM_SOURCE = """
            export const inject = ['subprocess']
            export function apply(ctx) {
              return ctx.subprocess.spawnTerminal
            }
            // reads APP_NOTIFY_PORT
        """.trimIndent()
    }
}
