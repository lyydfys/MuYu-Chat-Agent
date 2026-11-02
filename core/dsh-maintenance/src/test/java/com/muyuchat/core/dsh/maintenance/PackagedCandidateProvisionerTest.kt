package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The provisioner is production code: these tests build a closure tree the production verifier
 * (`DshClosureCandidateSource`) accepts, so a stale descriptor or a wrong hash shows up as a
 * rejected candidate rather than as a green unit test.
 */
class PackagedCandidateProvisionerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val abi = "arm64-v8a"
    private val payloadSha = "a".repeat(64)

    @Test
    fun identicalPackagedPayloadIsANoOp() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(temporary.newFolder("payload"), kernel = "console.log('v8')\n")
        var calls = 0

        val result = provisioner(closure, active, installed, payloadSha).ensureProvisioned()

        assertTrue("expected NoChange, got $result", result is DshProvisionResult.NoChange)
        assertEquals(1, incomingCandidates(closure).size)
        calls++
        assertEquals(1, calls)
    }

    @Test
    fun aDifferentPackagedPayloadStagesACandidateTheProductionSourceAccepts() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(
            temporary.newFolder("payload"),
            kernel = "console.log('v9 packaged')\n",
            markerSha = "b".repeat(64)
        )
        val newSha = "b".repeat(64)

        val result = provisioner(closure, active, installed, newSha).ensureProvisioned()

        assertTrue("expected Staged, got $result", result is DshProvisionResult.Staged)
        val staged = result as DshProvisionResult.Staged
        val stagedDir = File(closure, "incoming/${staged.candidateId}")
        assertTrue("the candidate directory must exist", stagedDir.isDirectory)

        val descriptor = JSONObject(stagedDir.resolve("candidate.json").readText())
        assertEquals(staged.candidateId, descriptor.getString("candidateId"))
        val payloadBlock = descriptor.getJSONObject("payload")
        assertEquals(newSha, payloadBlock.getString("archiveSha256"))
        assertEquals(7L, payloadBlock.getLong("fileCount"))
        val critical = payloadBlock.getJSONArray("criticalFiles")
        assertEquals(1, critical.length())
        val row = critical.getJSONObject(0)
        assertEquals("bin.js", row.getString("path"))
        assertEquals(
            DshMaintenanceIo.sha256(File(stagedDir, "payload/bin.js")),
            row.getString("sha256")
        )
        assertEquals("console.log('v9 packaged')\n", File(stagedDir, "payload/bin.js").readText())

        val lookup = DshClosureCandidateSource(
            closureRoot = closure,
            supportedAbis = setOf(abi),
            protectedRoots = emptyList(),
            activeRoot = { active }
        ).lookup()
        val available = lookup as? DshClosureCandidateLookup.Available
        assertTrue("production source rejected the candidate: $lookup", available != null)
        val candidate = available!!.candidate
        assertEquals(staged.candidateId, candidate.candidateId)
        // The trial must load the candidate tree, never the installed payload directory.
        assertEquals(staged.candidateId, candidate.stagedRoot.name)
        assertTrue(
            "the payload root must live inside the staged candidate",
            candidate.payloadRoot!!.canonicalPath.startsWith(stagedDir.canonicalPath)
        )
    }

    @Test
    fun anExistingStagedCandidateIsLeftAlone() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(temporary.newFolder("payload"), kernel = "console.log('v9')\n")
        val other = File(closure, "incoming/other")
        other.mkdirs()
        other.resolve("candidate.json").writeText("{}")

        val result = provisioner(closure, active, installed, "b".repeat(64)).ensureProvisioned()

        assertTrue("expected NoChange, got $result", result is DshProvisionResult.NoChange)
        assertTrue(other.isDirectory)
        assertEquals(2, incomingCandidates(closure).size)
    }

    @Test
    fun aMissingKernelEntryRefusesWithoutLeavingATree() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(temporary.newFolder("payload"), kernel = null, markerSha = "b".repeat(64))

        val result = provisioner(closure, active, installed, "b".repeat(64)).ensureProvisioned()

        assertTrue("expected Refused, got $result", result is DshProvisionResult.Refused)
        assertEquals("only the active closure may remain", 1, incomingCandidates(closure).size)
        assertTrue(
            "a refused attempt must not leave a staging tree",
            closure.listFiles().orEmpty().none { it.name.startsWith(".staging-") }
        )
    }

    @Test
    fun noActiveClosureIsANoOp() {
        val closure = temporary.newFolder("closure")
        val installed = installedPayload(temporary.newFolder("payload"), kernel = "console.log('v9')\n")

        val result = provisioner(closure, active = null, installed = installed, "b".repeat(64)).ensureProvisioned()

        assertTrue("expected NoChange, got $result", result is DshProvisionResult.NoChange)
        assertEquals(0, incomingCandidates(closure).size)
    }

    @Test
    fun theSelectorsPreviousClosureDoesNotCountAsAnotherCandidate() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(
            temporary.newFolder("payload"),
            kernel = "console.log('v9 packaged')\n",
            markerSha = "b".repeat(64)
        )
        val previous = File(closure, "incoming/previous-1").apply {
            mkdirs()
            resolve("candidate.json").writeText("{\"candidateId\":\"previous-1\"}")
        }

        // Without the exclusion the previous closure looks like "another staged candidate".
        val blocked = provisioner(closure, active, installed, "b".repeat(64)).ensureProvisioned()
        assertTrue("expected the old NoChange, got $blocked", blocked is DshProvisionResult.NoChange)

        val result = provisioner(closure, active, installed, "b".repeat(64), previous).ensureProvisioned()

        assertTrue("expected Staged, got $result", result is DshProvisionResult.Staged)
        assertTrue(previous.isDirectory)
    }

    @Test
    fun aStaleInstalledMarkerBlocksStaging() {
        val closure = temporary.newFolder("closure")
        val active = activeClosure(closure, archiveSha = payloadSha)
        val installed = installedPayload(temporary.newFolder("payload"), kernel = "console.log('v9')\n", markerSha = payloadSha)

        val result = provisioner(closure, active, installed, "b".repeat(64)).ensureProvisioned()

        assertTrue("a stale installed tree must not be described as the packaged payload", result is DshProvisionResult.NoChange)
        assertEquals(1, incomingCandidates(closure).size)
    }

    // ------------------------------------------------------------------ fixtures

    private fun provisioner(
        closure: File,
        active: File?,
        installed: File,
        packagedSha: String,
        previous: File? = null
    ): DshPackagedCandidateProvisioner = DshPackagedCandidateProvisioner(
        closureRoot = closure,
        activeRoot = { active },
        packagedIdentity = {
            DshPackagedPayloadIdentity(
                payloadVersion = "9.9.9",
                archiveSha256 = packagedSha,
                fileCount = 7L
            )
        },
        installedPayloadDir = { installed },
        previousRoot = { previous },
        clock = { 1_700_000_000_000L }
    )

    private fun incomingCandidates(closure: File): List<File> =
        File(closure, "incoming").listFiles()
            ?.filter { it.isDirectory && File(it, "candidate.json").isFile }
            .orEmpty()

    private fun activeClosure(closure: File, archiveSha: String): File {
        val active = File(closure, "incoming/v8")
        val payload = File(active, "payload")
        payload.mkdirs()
        File(payload, "bin.js").writeText("console.log('v8')\n")
        val artifacts = JSONObject()
        for (component in DSH_CLOSURE_COMPONENTS) {
            val file = File(active, "$component.identity")
            file.writeText("$component=v8")
            artifacts.put(component, "$component.identity")
        }
        val critical = JSONArray().put(
            JSONObject()
                .put("path", "bin.js")
                .put("sha256", DshMaintenanceIo.sha256(File(payload, "bin.js")))
        )
        val descriptor = JSONObject()
            .put("candidateId", "v8")
            .put("targetAbi", abi)
            .put("dataSchemaVersion", 2)
            .put("minReadableDataSchema", 1)
            .put("artifacts", artifacts)
            .put("migratesData", false)
            .put("nativeLibraryReplaced", false)
            .put("prePackagedSlotVerified", true)
            .put("abiCompatibleWithPrevious", true)
            .put(
                "payload",
                JSONObject()
                    .put("root", "payload")
                    .put("kernelEntry", "bin.js")
                    .put("criticalFiles", critical)
                    .put("archiveSha256", archiveSha)
                    .put("fileCount", 3L)
            )
        active.resolve("candidate.json").writeText(descriptor.toString())
        return active
    }

    private fun installedPayload(root: File, kernel: String?, markerSha: String = payloadSha): File {
        File(root, "payload.marker.json").writeText(
            JSONObject()
                .put("manifestVersion", 1)
                .put("payloadVersion", "9.9.9")
                .put("archiveSha256", markerSha)
                .put("fileCount", 7L)
                .put("installedAtMillis", 1L)
                .toString()
        )
        val payload = File(root, "payload")
        payload.mkdirs()
        File(payload, "runtime").mkdirs()
        File(payload, "runtime/node").writeText("node-binary")
        File(payload, "README.txt").writeText("installed payload")
        if (kernel != null) File(payload, "bin.js").writeText(kernel)
        assertTrue(payload.isDirectory)
        assertNotNull(payload.listFiles())
        return payload
    }
}
