package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Candidate-source verification: only a complete, contained, hash-proven staging tree is usable. */
class ClosureCandidateTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun newRoot(): File {
        val root = TestSupport.tempRoot("dsh-closure-candidate")
        roots.add(root)
        return root
    }

    private fun stage(
        root: File,
        id: String = "candidate-1",
        components: Set<String> = DSH_CLOSURE_COMPONENTS,
        hashes: Boolean = true,
        corruptOneArtifact: Boolean = false,
        targetAbi: String = "arm64-v8a"
    ): File {
        val directory = File(root, "closure/incoming/$id")
        directory.mkdirs()
        val artifacts = JSONObject()
        val declared = JSONObject()
        for (component in DSH_CLOSURE_COMPONENTS) {
            val file = File(directory, "$component.bin")
            TestSupport.writeBytes(file, ("artifact:$component").toByteArray())
            if (component in components) {
                artifacts.put(component, file.name)
                if (hashes) declared.put(component, DshMaintenanceIo.sha256(file))
            }
        }
        if (corruptOneArtifact) {
            TestSupport.writeBytes(File(directory, "payload.bin"), "tampered".toByteArray())
        }
        // A real candidate carries an executable payload tree; identity-only trees are refused.
        val payloadDir = File(directory, DshUpgradeCandidate.PAYLOAD_DIR_NAME)
        payloadDir.mkdirs()
        val kernelEntry = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        val kernel = File(payloadDir, kernelEntry)
        TestSupport.write(kernel, "kernel-$id")
        val critical = JSONArray().put(
            JSONObject().put("path", kernelEntry).put("sha256", DshMaintenanceIo.sha256(kernel))
        )
        val manifest = JSONObject()
            .put("candidateId", id)
            .put("targetAbi", targetAbi)
            .put("dataSchemaVersion", 5)
            .put("minReadableDataSchema", 4)
            .put("migratesData", false)
            .put("nativeLibraryReplaced", false)
            .put("prePackagedSlotVerified", true)
            .put("abiCompatibleWithPrevious", true)
            .put("artifacts", artifacts)
            .put(
                "payload",
                JSONObject()
                    .put("root", DshUpgradeCandidate.PAYLOAD_DIR_NAME)
                    .put("kernelEntry", kernelEntry)
                    .put("archiveSha256", "a".repeat(64))
                    .put("fileCount", 1L)
                    .put("criticalFiles", critical)
            )
        if (hashes) manifest.put("hashes", declared)
        TestSupport.write(File(directory, "candidate.json"), manifest.toString())
        return directory
    }

    private fun source(
        root: File,
        protectedRoots: List<File> = emptyList(),
        activeRoot: File? = null,
        previousRoot: File? = null
    ) = DshClosureCandidateSource(
        closureRoot = File(root, "closure"),
        supportedAbis = setOf("arm64-v8a"),
        protectedRoots = protectedRoots,
        activeRoot = { activeRoot },
        previousRoot = { previousRoot }
    )

    @Test
    fun theActiveClosureIsNotCountedAsASecondStagedCandidate() {
        val root = newRoot()
        val active = stage(root, id = "candidate-active")
        stage(root, id = "candidate-next")

        // Two trees below incoming are ambiguous on their own...
        assertTrue(source(root).lookup() is DshClosureCandidateLookup.Rejected)

        // ...but the tree the selector already serves is the active closure, not a candidate.
        val lookup = source(root, activeRoot = active).lookup()
        assertTrue(lookup is DshClosureCandidateLookup.Available)
        assertEquals(
            "candidate-next",
            (lookup as DshClosureCandidateLookup.Available).candidate.candidateId
        )
    }

    @Test
    fun thePreviousClosureIsNeverOfferedAsANewCandidate() {
        val root = newRoot()
        val previous = stage(root, id = "candidate-previous")
        val active = stage(root, id = "candidate-active")

        // After a commit the previous closure keeps living below `incoming`; on its own it must not
        // be advertised as an upgrade (that would present a downgrade as an upgrade).
        assertTrue(
            source(root, activeRoot = active, previousRoot = previous).lookup() is
                DshClosureCandidateLookup.None
        )

        val fresh = stage(root, id = "candidate-fresh")
        val lookup = source(root, activeRoot = active, previousRoot = previous).lookup()
        assertTrue(lookup is DshClosureCandidateLookup.Available)
        assertEquals(
            "candidate-fresh",
            (lookup as DshClosureCandidateLookup.Available).candidate.candidateId
        )
        assertTrue(fresh.isDirectory)
    }

    @Test
    fun aCompleteStagedCandidateIsAvailableWithAComputedFingerprint() {
        val root = newRoot()
        stage(root)

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Available)
        val candidate = (lookup as DshClosureCandidateLookup.Available).candidate
        assertEquals("candidate-1", candidate.candidateId)
        assertEquals("arm64-v8a", candidate.targetAbi)
        assertEquals(DSH_CLOSURE_COMPONENTS.size, candidate.artifacts.size)
        assertEquals(64, candidate.identity().fingerprint.length)
        assertTrue(candidate.identity().missingComponents().isEmpty())
    }

    @Test
    fun nothingStagedIsAnHonestNone() {
        val root = newRoot()
        assertTrue(source(root).lookup() is DshClosureCandidateLookup.None)
        File(root, "closure/incoming").mkdirs()
        assertTrue(source(root).lookup() is DshClosureCandidateLookup.None)
    }

    @Test
    fun anIncompleteClosureIsRejected() {
        val root = newRoot()
        stage(root, components = DSH_CLOSURE_COMPONENTS - "addons")

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun aTamperedArtifactIsRejectedByItsDeclaredHash() {
        val root = newRoot()
        stage(root, corruptOneArtifact = true)

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
        assertEquals(
            DshMaintenanceError.ARCHIVE_INVALID,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun anAbiThisBuildCannotRunIsRejected() {
        val root = newRoot()
        stage(root, targetAbi = "x86_64")

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun twoStagedCandidatesAreAmbiguousAndRefused() {
        val root = newRoot()
        stage(root, id = "candidate-1")
        stage(root, id = "candidate-2")

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun aCandidateInsideLiveUserDataIsRefused() {
        val root = newRoot()
        val home = File(root, "home")
        home.mkdirs()
        val directory = stage(root)
        // Re-stage the same candidate under the protected home root: the source must refuse it.
        val inside = File(home, "closure/incoming/candidate-1")
        inside.mkdirs()
        File(directory, "candidate.json").copyTo(File(inside, "candidate.json"))
        for (component in DSH_CLOSURE_COMPONENTS) {
            File(directory, "$component.bin").copyTo(File(inside, "$component.bin"))
        }
        TestSupport.deleteTree(File(root, "closure"))

        val lookup = DshClosureCandidateSource(
            closureRoot = File(root, "closure"),
            supportedAbis = setOf("arm64-v8a"),
            protectedRoots = listOf(home)
        ).lookup()

        // The protected root is outside the closure root entirely, so nothing is discovered there.
        assertTrue(lookup is DshClosureCandidateLookup.None)
    }

    @Test
    fun manifestTokensMustBeStable() {
        val root = newRoot()
        val directory = stage(root)
        val manifest = JSONObject(DshMaintenanceIo.readText(File(directory, "candidate.json")))
        manifest.put("candidateId", "../../escape")
        TestSupport.write(File(directory, "candidate.json"), manifest.toString())

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
    }
}
