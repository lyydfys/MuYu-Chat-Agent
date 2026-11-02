package com.muyuchat.core.dsh.runtime.trial

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ownership rules of the isolated trial generation: the candidate must be a staged closure
 * candidate and the three data roots must live in exactly one trial transaction below
 * `files/dsh/trial`.
 */
class DshTrialRootsTest {

    private fun fixtures(): DshTrialTestFiles.Fixtures {
        val root = DshTrialTestFiles.newRoot("dsh-trial-roots")
        return DshTrialTestFiles.materialize(root)
    }

    private fun resolve(
        f: DshTrialTestFiles.Fixtures,
        candidate: File = f.candidate,
        home: File = f.trialHome,
        workspace: File = f.trialWorkspace,
        tmp: File = f.trialTmp
    ): DshTrialRoots.Resolution =
        DshTrialRoots.resolve(f.root, candidate, home, workspace, tmp)

    private fun assertRefused(resolution: DshTrialRoots.Resolution) {
        assertTrue("expected a refusal, was $resolution", resolution is DshTrialRoots.Resolution.Refused)
        val reason = (resolution as DshTrialRoots.Resolution.Refused).reason
        assertTrue(reason.isNotBlank())
        // The reason crosses Binder inside a snapshot detail: it never names a path.
        assertFalse(reason.contains("/"))
        assertFalse(reason.contains("\\"))
    }

    @Test
    fun acceptsTheAppDefaultTrialLayout() {
        val f = fixtures()
        val accepted = resolve(f)
        assertTrue(accepted is DshTrialRoots.Resolution.Ok)
        val ok = accepted as DshTrialRoots.Resolution.Ok
        assertEquals(f.candidate.absoluteFile, ok.accepted.candidate)
        assertEquals(f.trialHome.absoluteFile, ok.accepted.home)
    }

    @Test
    fun acceptsAPerTransactionTrialRoot() {
        val f = fixtures()
        val txid = File(f.root, "trial/tx-20261021-01")
        assertTrue(txid.mkdirs())
        val accepted = resolve(
            f,
            home = File(txid, "home"),
            workspace = File(txid, "workspace"),
            tmp = File(txid, "tmp")
        )
        assertTrue(accepted is DshTrialRoots.Resolution.Ok)
    }

    @Test
    fun refusesRootsThatEscapeTheManagedTrialDirectory() {
        val f = fixtures()
        val outside = DshTrialTestFiles.newRoot("dsh-trial-outside")
        assertRefused(resolve(f, tmp = outside))
        assertRefused(
            resolve(
                f,
                home = File(f.root, "home"),
                workspace = File(f.root, "workspace"),
                tmp = File(f.root, "tmp")
            )
        )
    }

    @Test
    fun refusesRootsThatDoNotShareOneTransactionRoot() {
        val f = fixtures()
        val other = File(f.root, "trial/other")
        assertTrue(other.mkdirs())
        assertRefused(resolve(f, tmp = File(other, "tmp")))
    }

    @Test
    fun refusesTheProductionRoots() {
        val f = fixtures()
        val productionTrialRoot = File(f.root, "trial/production")
        assertTrue(productionTrialRoot.mkdirs())
        // The production home is a data root of the real generation, never a trial home.
        assertRefused(
            resolve(
                f,
                home = f.productionHome,
                workspace = File(productionTrialRoot, "workspace"),
                tmp = File(productionTrialRoot, "tmp")
            )
        )
    }

    @Test
    fun refusesACandidateOutsideTheClosureTree() {
        val f = fixtures()
        val outside = DshTrialTestFiles.newRoot("dsh-candidate-outside")
        File(outside, "candidate.json").writeText("{}")
        assertRefused(resolve(f, candidate = outside))
        // ... or a directory inside the closure tree without the staged manifest.
        val empty = File(f.root, "closure/incoming/empty")
        assertTrue(empty.mkdirs())
        assertRefused(resolve(f, candidate = empty))
    }

    @Test
    fun refusesRelativePaths() {
        val f = fixtures()
        val relative = File("trial/home")
        assertRefused(resolve(f, home = relative))
    }

    @Test
    fun refusesDuplicateRoots() {
        val f = fixtures()
        assertRefused(resolve(f, workspace = f.trialHome, tmp = f.trialTmp))
    }
}
