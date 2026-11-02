package com.muyuchat.core.dsh.maintenance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debug-only candidate staging from the build's own verified payload archive.
 *
 * These tests prove the staging source cannot invent a candidate: the archive must match the
 * manifest declaration, every declared component hash must match the bytes that were written, the
 * result is re-verified by the production candidate source, and staged candidates behave exactly
 * like a real one (including the two-candidate ambiguity and tamper/missing refusals).
 */
@OptIn(DshCandidateStagingTestOnly::class)
class CandidateStagingTest {

    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach(TestSupport::deleteTree)
    }

    private fun newRoot(prefix: String = "dsh-candidate-staging"): File {
        val root = TestSupport.tempRoot(prefix)
        roots.add(root)
        return root
    }

    // ------------------------------------------------------------ fixtures

    private fun archive(
        entries: Map<String, String> = DEFAULT_ENTRIES,
        directories: List<String> = emptyList()
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            directories.forEach { name ->
                zip.putNextEntry(
                    ZipEntry(if (name.endsWith("/")) name else "$name/").apply {
                        time = FIXED_ZIP_ENTRY_TIME_MILLIS
                    }
                )
                zip.closeEntry()
            }
            entries.toSortedMap().forEach { (name, text) ->
                // A packaged payload archive is an immutable artifact: its identity is the
                // SHA-256 of its bytes, and a staged candidate binds that digest. A ZipEntry
                // with an unset time is stamped with the wall clock by ZipOutputStream, so two
                // honest builds of the same payload would otherwise differ across a 2 s DOS-time
                // boundary - and so would their candidate identities. Pin the entry time so the
                // fixture is reproducible and identity assertions compare payload content, not
                // the moment the test happened to run.
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ZIP_ENTRY_TIME_MILLIS })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun manifest(
        archive: ByteArray,
        entries: Map<String, String> = DEFAULT_ENTRIES,
        payloadVersion: String = "dsh-test-1",
        declaredSha: String? = DshMaintenanceIo.sha256(archive),
        declaredSize: Long = archive.size.toLong()
    ): String {
        val archiveJson = JSONObject()
            .put("size", declaredSize)
        if (declaredSha != null) archiveJson.put("sha256", declaredSha)
        return JSONObject()
            .put("manifestVersion", 1)
            .put("payloadVersion", payloadVersion)
            .put("archive", archiveJson)
            .put("node", JSONObject().put("jniLib", "libdshnode.so").put("size", 4_242L).put("sha256", "c".repeat(64)))
            .put("kernelEntry", KERNEL_ENTRY)
            .put("runtimeLibraryDir", "runtime/lib")
            .put("fileCount", entries.size.toLong())
            .put("source", JSONObject().put("officialCommit", "d".repeat(40)))
            .put(
                "criticalFiles",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("path", KERNEL_ENTRY)
                            .put("sha256", sha256Of(entries.getValue(KERNEL_ENTRY)))
                    )
                    .put(
                        JSONObject()
                            .put("path", PROFILE_TEMPLATE)
                            .put("sha256", sha256Of(entries.getValue(PROFILE_TEMPLATE)))
                    )
            )
            .toString()
    }

    private fun sha256Of(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun spec(archiveBytes: ByteArray, manifestJson: String = manifest(archiveBytes)) =
        DshPayloadArchiveSpec(manifestJson = manifestJson, openArchive = { ByteArrayInputStream(archiveBytes) })

    private fun request(
        archiveSpec: DshPayloadArchiveSpec,
        id: String = "candidate-1",
        abi: String = "arm64-v8a",
        schema: Int = 5,
        minReadable: Int = 4,
        migrates: Boolean = false
    ) = DshCandidateStageRequest(
        candidateId = id,
        targetAbi = abi,
        dataSchemaVersion = schema,
        minReadableDataSchema = minReadable,
        migratesData = migrates,
        nativeLibraryReplaced = false,
        prePackagedSlotVerified = true,
        abiCompatibleWithPrevious = true,
        archive = archiveSpec
    )

    private fun stager(root: File, protectedRoots: List<File> = emptyList()) = DshCandidateStager(
        gate = DshCandidateStagingGate.jvmTest(),
        dataRoot = root,
        supportedAbis = setOf("arm64-v8a"),
        protectedRoots = protectedRoots
    )

    private fun source(root: File) = DshClosureCandidateSource(
        closureRoot = File(root, "closure"),
        supportedAbis = setOf("arm64-v8a")
    )

    private fun incomingChildren(root: File): List<File> =
        File(root, "closure/incoming").listFiles()?.toList().orEmpty()

    private fun staged(result: DshCandidateStageResult): DshCandidateStageResult.Staged {
        assertTrue("staging was refused: $result", result is DshCandidateStageResult.Staged)
        return result as DshCandidateStageResult.Staged
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun directoryEntriesInTheArchiveDoNotAbortStaging() {
        // Regression: a real payload zip carries directory entries ("dshroot/", ...). Resolving
        // the raw trailing-slash name was rejected by the safe-path predicate, so staging aborted
        // before writing a single file; the abort was then reported as a 0-file count mismatch.
        val root = newRoot()
        val archiveBytes = archive(directories = listOf("dshroot", "dshroot/lib", "dshhome"))

        val result = staged(stager(root).stage(request(spec(archiveBytes))))

        assertTrue(File(result.stagedRoot, "payload/$KERNEL_ENTRY").isFile)
        assertTrue(File(result.stagedRoot, "payload/$PROFILE_TEMPLATE").isFile)
    }

    @Test
    fun aValidArchiveStagesOneCandidateTheProductionSourceAccepts() {
        val root = newRoot()
        val archiveBytes = archive()

        val result = staged(stager(root).stage(request(spec(archiveBytes))))

        assertEquals("candidate-1", result.candidateId)
        assertEquals(File(root, "closure/incoming/candidate-1"), result.stagedRoot)
        assertEquals(DSH_CLOSURE_COMPONENTS.size, result.componentHashes.size)
        assertEquals(64, result.fingerprint.length)
        assertEquals(DshMaintenanceIo.sha256(archiveBytes), result.archiveSha256)
        assertEquals(archiveBytes.size.toLong(), result.archiveBytes)

        val lookup = source(root).lookup()
        assertTrue(lookup is DshClosureCandidateLookup.Available)
        val candidate = (lookup as DshClosureCandidateLookup.Available).candidate
        assertEquals(result.candidateId, candidate.candidateId)
        assertEquals(DSH_CLOSURE_COMPONENTS.size, candidate.artifacts.size)
        assertEquals(result.fingerprint, candidate.identity().fingerprint)
        assertTrue(candidate.identity().missingComponents().isEmpty())

        // Every declared hash in candidate.json must equal the bytes actually on disk.
        val manifest = JSONObject(DshMaintenanceIo.readText(File(result.stagedRoot, "candidate.json")))
        val artifacts = manifest.getJSONObject("artifacts")
        val declared = manifest.getJSONObject("hashes")
        assertEquals(DSH_CLOSURE_COMPONENTS.size, declared.length())
        for (component in DSH_CLOSURE_COMPONENTS) {
            val file = File(result.stagedRoot, artifacts.getString(component))
            assertTrue(file.isFile)
            assertTrue(file.length() > 0L)
            assertEquals(DshMaintenanceIo.sha256(file), declared.getString(component))
            assertEquals(result.componentHashes.getValue(component), declared.getString(component))
        }
        // Exactly one promoted candidate, no leftover staging directory.
        assertEquals(listOf("candidate-1"), incomingChildren(root).map { it.name })
    }

    @Test
    fun aStagedCandidateStartsASerialTrialAndIsReleasedBeforeProduction() {
        val root = newRoot()
        val staged = staged(stager(root).stage(request(spec(archive()))))
        val production = TestSupport.FakeHost()
        val port = RecordingTrialPort()
        val host = DshClosureTrialHost(production = production, trialPort = { port }, dataRoot = root)

        val started = host.startTrial(staged.stagedRoot, staged.fingerprint)

        assertEquals(DshHostState.RUNNING, started.state)
        assertTrue(port.launched)
        assertEquals(staged.stagedRoot, port.request?.candidateRoot)
        assertEquals(staged.fingerprint, port.request?.candidateFingerprint)
        assertEquals(DshTrialPhase.RUNNING, host.activeTrial()?.phase)

        assertEquals(DshHostState.STOPPED, host.stopTrial(1_000L).state)
        assertEquals(DshTrialPhase.STOPPED, host.activeTrial()?.phase)
        assertEquals(DshHostState.RUNNING, host.startProduction(staged.stagedRoot, staged.fingerprint).state)
    }

    private class RecordingTrialPort : DshTrialLaunchPort {
        var request: DshTrialLaunchRequest? = null
        var launched = false

        override fun launch(request: DshTrialLaunchRequest): DshTrialLaunchResult {
            this.request = request
            launched = true
            return DshTrialLaunchResult.Started(pid = 99L, generation = 2L)
        }

        override fun probeHealth(): DshHostHealth =
            DshHostHealth(hostReady = true, homeReadable = true, sessionReaderReady = true)

        override fun stopAndProve(timeoutMillis: Long): DshTrialStopProof = DshTrialStopProof.Stopped

        override fun status(): DshTrialRuntimeStatus = DshTrialRuntimeStatus(running = false)
    }

    @Test
    fun theSameArchiveAndAbiProduceTheSameIdentityForDifferentCandidateIds() {
        val firstRoot = newRoot("dsh-candidate-staging-a")
        val secondRoot = newRoot("dsh-candidate-staging-b")
        val archiveBytes = archive()

        val first = staged(stager(firstRoot).stage(request(spec(archiveBytes), id = "candidate-a")))
        val second = staged(stager(secondRoot).stage(request(spec(archiveBytes), id = "candidate-b")))

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.componentHashes, second.componentHashes)
    }

    @Test
    fun theFixtureArchiveBytesAreReproducible() {
        // Candidate identity binds the archive SHA-256, so the fixture archive must be a stable
        // artifact: two builds of the same entries may not embed wall-clock zip metadata. Without
        // the pinned entry time this fails whenever the two builds straddle a 2 s DOS-time tick.
        assertArrayEquals(archive(), archive())
    }

    @Test
    fun aDifferentArchiveProducesADifferentIdentity() {
        val firstRoot = newRoot("dsh-candidate-staging-a")
        val secondRoot = newRoot("dsh-candidate-staging-b")
        val firstArchive = archive()
        val secondEntries = DEFAULT_ENTRIES + ("dshroot/lib/extra.js" to "extra")
        val secondArchive = archive(secondEntries)

        val first = staged(stager(firstRoot).stage(request(spec(firstArchive))))
        val second = staged(stager(secondRoot).stage(request(spec(secondArchive, manifest(secondArchive, secondEntries)))))

        assertTrue(first.fingerprint != second.fingerprint)
    }

    // ------------------------------------------------------------ archive refusal

    @Test
    fun aTamperedArchiveIsRefusedAndNothingIsStaged() {
        val root = newRoot()
        val good = archive()
        val manifestJson = manifest(good)
        val tampered = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

        val result = stager(root).stage(request(spec(tampered, manifestJson)))

        assertTrue(result is DshCandidateStageResult.Refused)
        assertEquals(
            DshMaintenanceError.ARCHIVE_INVALID,
            (result as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    @Test
    fun aDeclaredSizeMismatchIsRefused() {
        val root = newRoot()
        val archiveBytes = archive()

        val result = stager(root).stage(request(spec(archiveBytes, manifest(archiveBytes, declaredSize = 1L))))

        assertEquals(
            DshMaintenanceError.ARCHIVE_INVALID,
            (result as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    @Test
    fun aManifestWithoutAnArchiveHashIsRefused() {
        val root = newRoot()
        val archiveBytes = archive()

        val result = stager(root).stage(request(spec(archiveBytes, manifest(archiveBytes, declaredSha = null))))

        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (result as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    @Test
    fun anUnavailableArchiveIsRefused() {
        val root = newRoot()
        val archiveBytes = archive()
        val unavailable = DshPayloadArchiveSpec(manifest(archiveBytes), openArchive = { null })

        val result = stager(root).stage(request(unavailable))

        assertEquals(
            DshMaintenanceError.STAGING_MISSING,
            (result as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    @Test
    fun anUnreadableOrOversizedArchiveIsRefusedWithoutStaging() {
        val root = newRoot()
        val archiveBytes = archive()
        val unreadable = DshPayloadArchiveSpec(
            manifest(archiveBytes),
            openArchive = { throw java.io.IOException("simulated read failure") }
        )
        val unreadableResult = stager(root).stage(request(unreadable))
        assertEquals(
            DshMaintenanceError.IO_FAILURE,
            (unreadableResult as DshCandidateStageResult.Refused).failure.code
        )

        val limited = DshCandidateStager(
            gate = DshCandidateStagingGate.jvmTest(),
            dataRoot = root,
            supportedAbis = setOf("arm64-v8a"),
            maxArchiveBytes = 8L
        )
        val oversized = limited.stage(request(spec(archiveBytes), id = "candidate-oversized"))
        assertEquals(
            DshMaintenanceError.IO_FAILURE,
            (oversized as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    // ------------------------------------------------------------ request refusal

    @Test
    fun anUnsupportedAbiOrIncoherentSchemaIsRefused() {
        val root = newRoot()
        val archiveSpec = spec(archive())

        val wrongAbi = stager(root).stage(request(archiveSpec, id = "candidate-abi", abi = "x86_64"))
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (wrongAbi as DshCandidateStageResult.Refused).failure.code
        )

        val noSchema = stager(root).stage(request(archiveSpec, id = "candidate-schema", schema = 0, minReadable = 0))
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (noSchema as DshCandidateStageResult.Refused).failure.code
        )

        val invertedSchema = stager(root).stage(
            request(archiveSpec, id = "candidate-inverted", schema = 3, minReadable = 4)
        )
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (invertedSchema as DshCandidateStageResult.Refused).failure.code
        )
        assertTrue(incomingChildren(root).isEmpty())
    }

    @Test
    fun stagingIntoLiveUserDataIsRefused() {
        val root = newRoot()
        val home = File(root, "home").apply { mkdirs() }

        val result = stager(home, protectedRoots = listOf(home)).stage(request(spec(archive())))

        assertEquals(
            DshMaintenanceError.PATH_ESCAPE,
            (result as DshCandidateStageResult.Refused).failure.code
        )
        assertFalse(File(home, "closure/incoming/candidate-1").exists())
    }

    @Test
    fun stagingTheSameIdTwiceIsRefusedInsteadOfOverwritten() {
        val root = newRoot()
        val stager = stager(root)
        staged(stager.stage(request(spec(archive()))))

        val second = stager.stage(request(spec(archive())))

        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (second as DshCandidateStageResult.Refused).failure.code
        )
        assertEquals(1, incomingChildren(root).size)
    }

    @Test
    fun aRelativeDataRootIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            DshCandidateStager(
                gate = DshCandidateStagingGate.jvmTest(),
                dataRoot = File("relative/dsh"),
                supportedAbis = setOf("arm64-v8a")
            )
        }
    }

    @Test
    fun aJvmTestGateCannotStageInsideAnAndroidProcess() {
        val androidDataRoot = File(System.getProperty("java.io.tmpdir"), "dsh-candidate-gate")
        val previous = System.getProperty("java.vm.name")
        System.setProperty("java.vm.name", "Dalvik")
        try {
            assertThrows(IllegalStateException::class.java) {
                DshCandidateStager(
                    gate = DshCandidateStagingGate.jvmTest(),
                    dataRoot = androidDataRoot,
                    supportedAbis = setOf("arm64-v8a")
                )
            }
        } finally {
            if (previous == null) {
                System.clearProperty("java.vm.name")
            } else {
                System.setProperty("java.vm.name", previous)
            }
        }
        assertFalse(androidDataRoot.exists())
    }

    // ------------------------------------------------------------ post-staging tamper

    @Test
    fun twoStagedCandidatesStayAmbiguousAndAreRefused() {
        val root = newRoot()
        val first = staged(stager(root).stage(request(spec(archive()), id = "candidate-1")))
        // A second closure staged out of band (as an installer would) must not be guessed between.
        val otherRoot = newRoot("dsh-candidate-staging-second")
        val second = staged(
            stager(otherRoot).stage(request(spec(archive()), id = "candidate-2"))
        )
        val target = File(root, "closure/incoming/candidate-2")
        assertTrue(second.stagedRoot.copyRecursively(target, overwrite = false))
        // Both installers staged the same immutable payload identity; only the staging directory
        // name differs, so there is no basis to guess which one the user meant.
        assertEquals(first.archiveSha256, second.archiveSha256)
        assertEquals(first.componentHashes, second.componentHashes)

        val lookup = source(root).lookup()

        assertTrue(lookup is DshClosureCandidateLookup.Rejected)
        assertEquals(
            DshMaintenanceError.UPGRADE_INCOMPATIBLE,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun aTamperedStagedArtifactIsRejectedByItsDeclaredHash() {
        val root = newRoot()
        val result = staged(stager(root).stage(request(spec(archive()))))
        val artifact = File(result.stagedRoot, "identity/payload.json")
        assertTrue(artifact.isFile)
        artifact.appendText("tampered")

        val lookup = source(root).lookup()

        assertEquals(
            DshMaintenanceError.ARCHIVE_INVALID,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    @Test
    fun aMissingStagedArtifactIsRejected() {
        val root = newRoot()
        val result = staged(stager(root).stage(request(spec(archive()))))
        assertTrue(File(result.stagedRoot, "identity/node.json").delete())

        val lookup = source(root).lookup()

        assertEquals(
            DshMaintenanceError.STAGING_MISSING,
            (lookup as DshClosureCandidateLookup.Rejected).failure.code
        )
    }

    // ------------------------------------------------------------ gating

    @Test
    fun aNonDebuggableApplicationCannotObtainAStagingGate() {
        assertNull(DshCandidateStagingGate.debugInstrumentation(applicationDebuggable = false))
        val gate = DshCandidateStagingGate.debugInstrumentation(applicationDebuggable = true)
        assertNotNull(gate)
        assertEquals(DshCandidateStagingGate.Origin.DEBUG_INSTRUMENTATION, gate!!.origin)
        assertEquals(DshCandidateStagingGate.Origin.JVM_TEST, DshCandidateStagingGate.jvmTest().origin)
    }

    private companion object {
        const val KERNEL_ENTRY = "dshroot/lib/node_modules/@deepseek-ai/dsh/lib/bin.js"
        const val PROFILE_TEMPLATE = "dshhome/profiles/web/package.json"

        /** 2000-01-01T00:00:00Z - a fixed, DOS-range timestamp for reproducible fixture archives. */
        const val FIXED_ZIP_ENTRY_TIME_MILLIS = 946_684_800_000L

        val DEFAULT_ENTRIES = mapOf(
            KERNEL_ENTRY to """{"name":"@deepseek-ai/dsh","version":"test"}""",
            PROFILE_TEMPLATE to """{"name":"web","private":true}""",
            "runtime/bin/node" to "node-bytes",
            "runtime/lib/libz.so.1" to "zlib-bytes"
        )
    }
}
