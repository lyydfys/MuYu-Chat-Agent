package com.muyuchat.mca.composition

import android.content.res.AssetManager
import com.muyuchat.core.dsh.maintenance.DSH_CLOSURE_COMPONENTS
import com.muyuchat.core.dsh.maintenance.DshCandidateStageRequest
import com.muyuchat.core.dsh.maintenance.DshCandidateStageResult
import com.muyuchat.core.dsh.maintenance.DshCandidateStager
import com.muyuchat.core.dsh.maintenance.DshCandidateStagingGate
import com.muyuchat.core.dsh.maintenance.DshCandidateStagingTestOnly
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateLookup
import com.muyuchat.core.dsh.maintenance.DshClosureCandidateSource
import com.muyuchat.core.dsh.maintenance.DshMaintenanceError
import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshPayloadArchiveSpec
import java.io.File
import org.json.JSONObject

/**
 * Debug-variant-only bridge from this APK's own verified payload archive to a staged D24 candidate.
 *
 * It exists **only** in `app/src/debug`: a release variant never compiles this file, and the core
 * builder additionally refuses to run without a [DshCandidateStagingGate] obtained from a
 * debuggable process (or a JVM test). Nothing in the console, Local API, DSH owner or agent tool
 * surface references this object - staging stays a deliberate developer/instrumentation action.
 *
 * The public surface deliberately exposes only app-owned types (`StageOutcome`/`Verification`), so
 * instrumentation code does not depend on the maintenance module's opt-in types directly.
 *
 * The staged artifacts are closure *identity* descriptors, not executable payload bytes: today's
 * trial executes the verified installed payload, so this build exercises the identity/data half of
 * the candidate. Alternate-root payload support remains a separate slice (D24 handoff).
 */
@OptIn(DshCandidateStagingTestOnly::class)
object DshCandidateStagingDebug {

    const val MANIFEST_ASSET = "dsh/MANIFEST.json"
    const val PAYLOAD_ASSET = "dsh/payload.zip"

    /** Number of closure identity components production expects. */
    val EXPECTED_COMPONENTS: Int get() = DSH_CLOSURE_COMPONENTS.size

    data class StagedCandidate(
        val candidateId: String,
        val stagedRoot: String,
        val fingerprint: String,
        val componentCount: Int,
        val archiveSha256: String
    )

    sealed interface StageOutcome {
        data class Staged(val candidate: StagedCandidate) : StageOutcome
        data class Refused(val code: String, val message: String, val detail: String?) : StageOutcome
    }

    sealed interface Verification {
        data object None : Verification

        data class Available(
            val candidateId: String,
            val fingerprint: String,
            val componentCount: Int,
            val missingComponents: List<String>,
            val declaredHashesMatch: Boolean
        ) : Verification

        data class Rejected(val code: String, val message: String, val detail: String?) : Verification
    }

    /**
     * Materialises exactly one candidate below `<filesDir>/dsh/closure/incoming/<candidateId>`.
     * [applicationDebuggable] must be the real `FLAG_DEBUGGABLE` observation of this process.
     */
    fun stage(
        assets: AssetManager,
        applicationDebuggable: Boolean,
        filesDir: File,
        supportedAbis: List<String>,
        candidateId: String,
        targetAbi: String,
        dataSchemaVersion: Int,
        minReadableDataSchema: Int
    ): StageOutcome {
        val gate = DshCandidateStagingGate.debugInstrumentation(applicationDebuggable)
            ?: return refused(
                DshMaintenanceError.ADMISSION_REJECTED,
                "candidate staging is only available in a debuggable application"
            )
        val manifestJson = runCatching {
            assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull() ?: return refused(
            DshMaintenanceError.STAGING_MISSING,
            "the packaged payload manifest is unavailable"
        )
        val dataRoot = File(filesDir, "dsh")
        val stager = DshCandidateStager(
            gate = gate,
            dataRoot = dataRoot,
            supportedAbis = supportedAbis.toSet(),
            protectedRoots = listOf(File(dataRoot, "home"), File(dataRoot, "workspace"))
        )
        val result = stager.stage(
            DshCandidateStageRequest(
                candidateId = candidateId,
                targetAbi = targetAbi,
                dataSchemaVersion = dataSchemaVersion,
                minReadableDataSchema = minReadableDataSchema,
                // The candidate is materialised from this build's own installed payload: no data
                // migration and no native-slot replacement is declared. The pre-packaged native
                // slot is *not* claimed as verified here (`false`); a same-version candidate needs
                // no slot switch, so rollback remains permitted through `nativeLibraryReplaced`.
                migratesData = false,
                nativeLibraryReplaced = false,
                prePackagedSlotVerified = false,
                abiCompatibleWithPrevious = true,
                archive = DshPayloadArchiveSpec(manifestJson) {
                    runCatching { assets.open(PAYLOAD_ASSET) }.getOrNull()
                }
            )
        )
        return when (result) {
            is DshCandidateStageResult.Staged -> StageOutcome.Staged(
                StagedCandidate(
                    candidateId = result.candidateId,
                    stagedRoot = result.stagedRoot.absolutePath,
                    fingerprint = result.fingerprint,
                    componentCount = result.componentHashes.size,
                    archiveSha256 = result.archiveSha256
                )
            )

            is DshCandidateStageResult.Refused -> StageOutcome.Refused(
                code = result.failure.code.name,
                message = result.failure.message,
                detail = result.failure.detail
            )
        }
    }

    /** Read-only verification with the same rules the console uses to advertise RUN. */
    fun verify(filesDir: File, supportedAbis: List<String>): Verification {
        val dataRoot = File(filesDir, "dsh")
        val lookup = DshClosureCandidateSource(
            closureRoot = File(dataRoot, "closure"),
            supportedAbis = supportedAbis.toSet(),
            protectedRoots = listOf(File(dataRoot, "home"), File(dataRoot, "workspace"))
        ).lookup()
        return when (lookup) {
            DshClosureCandidateLookup.None -> Verification.None

            is DshClosureCandidateLookup.Rejected -> Verification.Rejected(
                code = lookup.failure.code.name,
                message = lookup.failure.message,
                detail = lookup.failure.detail
            )

            is DshClosureCandidateLookup.Available -> {
                val candidate = lookup.candidate
                Verification.Available(
                    candidateId = candidate.candidateId,
                    fingerprint = candidate.identity().fingerprint,
                    componentCount = candidate.artifacts.size,
                    missingComponents = candidate.identity().missingComponents(),
                    declaredHashesMatch = declaredHashesMatch(candidate.stagedRoot)
                )
            }
        }
    }

    /** Data schema of the committed closure; 0 when this installation never committed one. */
    fun currentDataSchema(filesDir: File): Int = runCatching {
        JSONObject(File(filesDir, "dsh/closure/selector.json").readText(Charsets.UTF_8))
            .optInt("dataSchemaVersion", 0)
    }.getOrDefault(0)

    /**
     * Re-hashes every staged artifact and compares it with the manifest's own declaration. A missing
     * or unreadable file is a mismatch, never "assume verified".
     */
    private fun declaredHashesMatch(stagedRoot: File): Boolean = runCatching {
        val manifest = JSONObject(DshMaintenanceIo.readText(File(stagedRoot, "candidate.json")))
        val artifacts = manifest.getJSONObject("artifacts")
        val declared = manifest.getJSONObject("hashes")
        for (component in DSH_CLOSURE_COMPONENTS) {
            val file = File(stagedRoot, artifacts.getString(component))
            if (!file.isFile || file.length() <= 0L) return@runCatching false
            if (DshMaintenanceIo.sha256(file) != declared.getString(component)) return@runCatching false
        }
        declared.length() == DSH_CLOSURE_COMPONENTS.size
    }.getOrDefault(false)

    private fun refused(code: DshMaintenanceError, message: String) = StageOutcome.Refused(
        code = code.name,
        message = message,
        detail = null
    )
}
