package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Positive-observation hygiene of the trial smoke: the isolated home proof, the official session
 * container proof, and the bounded read-only data-half copy.
 */
class DshTrialHealthProbeTest {

    private fun root(prefix: String): File = DshTrialTestFiles.newRoot(prefix)

    @Test
    fun theHomeProofRequiresARealSeededProfileAndConfig() {
        val home = File(root("dsh-home-proof"), "home")
        home.mkdirs()
        assertFalse(DshTrialHomeProof.prove(home).readable)

        File(home, "profiles/web").mkdirs()
        File(home, "config").mkdirs()
        // The profile file must exist and be readable, not just the directories.
        assertFalse(DshTrialHomeProof.prove(home).readable)

        File(home, "profiles/web/cordis.yml").writeText("plugins: []\n")
        assertTrue(DshTrialHomeProof.prove(home).readable)

        File(home, "profiles/web/cordis.yml").writeText("")
        assertFalse(DshTrialHomeProof.prove(home).readable)
    }

    @Test
    fun theSessionProofRequiresACompleteOfficialContainer() {
        val home = File(root("dsh-session-proof"), "home")
        home.mkdirs()
        assertFalse(DshTrialSessionReader.probe(home).verified)

        val session = DshTrialTestFiles.sessionFile(home, "slug-a", "session-1")
        session.parentFile.mkdirs()
        session.writeBytes(DshTrialTestFiles.tornFrame("{\"type\":\"session\"}"))
        assertFalse("a torn tail with no complete frame is not proof", DshTrialSessionReader.probe(home).verified)

        session.writeBytes(DshTrialTestFiles.zstdRawFrame("{\"type\":\"session\",\"version\":4}\n"))
        val proof = DshTrialSessionReader.probe(home)
        assertTrue(proof.verified)
        assertTrue(proof.detail.contains("container verified"))
        assertFalse("the detail never claims a decoded header", proof.detail.contains("header decoded"))
    }

    @Test
    fun aCompleteFrameFollowedByATornTailStillVerifies() {
        val home = File(root("dsh-session-torn"), "home")
        val session = DshTrialTestFiles.sessionFile(home, "slug-b", "session-2")
        session.parentFile.mkdirs()
        val complete = DshTrialTestFiles.zstdRawFrame("{\"type\":\"session\"}\n")
        val torn = DshTrialTestFiles.tornFrame("{}")
        session.writeBytes(complete + torn)
        val proof = DshTrialSessionReader.probe(home)
        assertTrue(proof.verified)
        assertTrue(proof.detail.contains("torn tail"))
    }

    @Test
    fun anInvalidContainerIsNeverProof() {
        val home = File(root("dsh-session-invalid"), "home")
        val session = DshTrialTestFiles.sessionFile(home, "slug-c", "session-3")
        session.parentFile.mkdirs()
        session.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8))
        assertFalse(DshTrialSessionReader.probe(home).verified)
    }

    @Test
    fun theContainerScanWalksFramesAndRejectsReservedShapes() {
        val one = DshTrialTestFiles.zstdRawFrame("hello")
        val scan = DshZstdFrameContainer.scan(one)
        assertTrue(scan is DshZstdFrameContainer.Result.Scanned)
        assertEquals(1, (scan as DshZstdFrameContainer.Result.Scanned).frames.size)
        assertFalse(scan.tornTail)

        val two = one + one
        assertEquals(2, (DshZstdFrameContainer.scan(two) as DshZstdFrameContainer.Result.Scanned).frames.size)

        // A frame header that stops early is a torn tail, not corruption.
        val truncated = DshZstdFrameContainer.scan(one.copyOf(5))
        assertTrue((truncated as DshZstdFrameContainer.Result.Scanned).frames.isEmpty())
        assertTrue(truncated.tornTail)

        // Reserved frame-header bits are structural corruption.
        val reservedHeader = one.copyOf()
        reservedHeader[4] = 24
        assertTrue(DshZstdFrameContainer.scan(reservedHeader) is DshZstdFrameContainer.Result.Invalid)

        // A reserved block type is structural corruption too.
        val reservedBlock = one.copyOf()
        reservedBlock[6] = (1L or (3L shl 1)).toByte()
        assertTrue(DshZstdFrameContainer.scan(reservedBlock) is DshZstdFrameContainer.Result.Invalid)

        // A bad magic is never split into "probably the next frame".
        val badMagic = one.copyOf()
        badMagic[0] = 0
        assertTrue(DshZstdFrameContainer.scan(badMagic) is DshZstdFrameContainer.Result.Invalid)
    }

    @Test
    fun theDataHalfCopiesOnlySessionLogsAndResetsItsOwnCopies() {
        val root = root("dsh-data-seed")
        val production = File(root, "home")
        val trial = File(root, "trial/home")
        trial.mkdirs()
        DshTrialTestFiles.writeSession(production, "slug-a", "session-1")
        File(production, "sessions/slug-a/session-1/attachments").mkdirs()
        File(production, "sessions/slug-a/session-1/attachments/blob.bin").writeText("payload")
        File(production, ".credentials.yaml").writeText("token: secret\n")
        File(production, "config").mkdirs()

        val first = DshTrialDataSeed.seed(production, trial)
        assertEquals(1, first.copiedFiles)
        assertTrue(first.detail.contains("credentials and attachments are not copied"))
        assertTrue(File(trial, "sessions/slug-a/session-1/session.v4.jsonl.zstd").isFile)
        assertFalse(File(trial, "sessions/slug-a/session-1/attachments/blob.bin").exists())
        assertFalse(File(trial, ".credentials.yaml").exists())
        // Read-only on the source.
        assertTrue(File(production, ".credentials.yaml").isFile)
        assertTrue(File(production, "sessions/slug-a/session-1/session.v4.jsonl.zstd").isFile)

        // A second run replaces its own copy instead of stacking stale ones.
        val second = DshTrialDataSeed.seed(production, trial)
        assertEquals(1, second.copiedFiles)
        assertEquals(1, File(trial, "sessions/slug-a/session-1").listFiles()!!.size)
    }

    @Test
    fun theDataHalfIsBoundedByItsBudget() {
        val root = root("dsh-data-budget")
        val production = File(root, "home")
        val trial = File(root, "trial/home")
        trial.mkdirs()
        DshTrialTestFiles.writeSession(production, "slug-a", "session-1")
        DshTrialTestFiles.writeSession(production, "slug-b", "session-2")

        val bounded = DshTrialDataSeed.seed(production, trial, maxFiles = 1)
        assertEquals(1, bounded.copiedFiles)
        assertEquals(1, bounded.skipped)
        assertTrue(bounded.detail.contains("skipped by the copy budget"))
    }

    @Test
    fun aMissingProductionStoreInitializesAnEmptyNewUserStore() {
        val root = root("dsh-data-empty")
        val trial = File(root, "trial/home")
        trial.mkdirs()
        val result = DshTrialDataSeed.seed(File(root, "home"), trial)
        assertEquals(0, result.sourceLogs)
        assertEquals(0, result.copiedFiles)
        assertTrue(result.detail.contains("new-user profile"))
        assertTrue("the isolated store itself is materialized", File(trial, "sessions").isDirectory)

        // New user: an initialized, readable, empty store is the positive proof.
        val newUserProof = DshTrialSessionReader.probe(trial, expectSessions = false)
        assertTrue(newUserProof.verified)
        assertTrue(newUserProof.storeReady)
        assertEquals(0, newUserProof.sessions)
        assertTrue(newUserProof.detail.contains("empty"))

        // The same empty store is NOT proof when sessions were expected to be migrated.
        val migratedProof = DshTrialSessionReader.probe(trial, expectSessions = true)
        assertFalse(migratedProof.verified)
        assertTrue(migratedProof.storeReady)
    }

    @Test
    fun aCopiedSessionIsRequiredWhenTheProductionHomeHadSessions() {
        val root = root("dsh-data-migrated")
        val production = File(root, "home")
        val trial = File(root, "trial/home")
        trial.mkdirs()
        DshTrialTestFiles.writeSession(production, "slug-a", "session-1")
        val result = DshTrialDataSeed.seed(production, trial)
        assertEquals(1, result.sourceLogs)
        assertEquals(1, result.copiedFiles)
        val proof = DshTrialSessionReader.probe(trial, expectSessions = true)
        assertTrue(proof.verified)
        assertEquals(1, proof.sessions)
    }

    @Test
    fun aNewUserProbeStillRejectsAMissingStore() {
        val home = File(root("dsh-data-no-store"), "home")
        home.mkdirs()
        val proof = DshTrialSessionReader.probe(home, expectSessions = false)
        assertFalse("an absent store is never an initialized store", proof.verified)
        assertFalse(proof.storeReady)
    }
}
