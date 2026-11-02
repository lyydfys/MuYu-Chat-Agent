package com.muyuchat.mca.dsh

import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.composition.DshCandidateStagingDebug
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Debug-only, opt-in device entry point of the D24 candidate staging source.
 *
 * This test is skipped unless it is invoked with explicit instrumentation arguments, so a routine
 * `connectedAndroidTest` run never mutates the installed app's `files/dsh` tree:
 *
 * ```text
 * adb shell am instrument -w \
 *   -e class com.muyuchat.mca.dsh.DshClosureCandidateDeviceStagingTest \
 *   -e dshCandidateStaging true \
 *   [-e candidateId candidate-<id>] [-e stage true|false] [-e expect available|rejected] \
 *   com.muyuchat.mca.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * It stages from the APK's own `dsh/MANIFEST.json` + `dsh/payload.zip` through
 * [DshCandidateStagingDebug], then verifies the result with the exact production candidate source
 * the console uses. `-e expect rejected` turns the same run into the negative check (for example
 * after the acceptance script tampers with one staged artifact via `run-as`).
 */
@RunWith(AndroidJUnit4::class)
class DshClosureCandidateDeviceStagingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dataRoot = File(context.filesDir, "dsh")

    @Test
    fun candidateFlowFollowsTheRequestedExpectation() {
        assumeTrue(
            "pass -e dshCandidateStaging true to allow staging",
            InstrumentationRegistry.getArguments().getString(ARG_ENABLED) == "true"
        )
        assumeTrue("the packaged payload archive is absent from this build", payloadPackaged())
        assumeTrue(
            "candidate staging requires a debuggable application",
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        val candidateId = candidateId()
        if (argument(ARG_STAGE, "true") == "true") {
            // Debug-only cleanup of *this* acceptance candidate id; no other staged tree is touched.
            val existing = File(dataRoot, "closure/incoming/$candidateId")
            if (existing.isDirectory) {
                assertTrue("cannot clear the previous acceptance candidate", existing.deleteRecursively())
            }
            val schema = schema()
            val outcome = DshCandidateStagingDebug.stage(
                assets = context.assets,
                applicationDebuggable = true,
                filesDir = context.filesDir,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                candidateId = candidateId,
                targetAbi = Build.SUPPORTED_ABIS.first(),
                dataSchemaVersion = schema,
                minReadableDataSchema = (schema - 1).coerceAtLeast(0)
            )
            when (outcome) {
                is DshCandidateStagingDebug.StageOutcome.Staged -> println(
                    "DSH_CANDIDATE_STAGED id=${outcome.candidate.candidateId} " +
                        "fingerprint=${outcome.candidate.fingerprint} " +
                        "components=${outcome.candidate.componentCount} " +
                        "archiveSha256=${outcome.candidate.archiveSha256}"
                )

                is DshCandidateStagingDebug.StageOutcome.Refused -> throw AssertionError(
                    "candidate staging was refused: ${outcome.code}: ${outcome.message}" +
                        (outcome.detail?.let { " ($it)" } ?: "")
                )
            }
        }

        val verification = DshCandidateStagingDebug.verify(context.filesDir, Build.SUPPORTED_ABIS.toList())
        when (val expectation = argument(ARG_EXPECT, EXPECT_AVAILABLE)) {
            EXPECT_AVAILABLE -> {
                assertTrue(
                    "expected exactly one verified candidate, got $verification",
                    verification is DshCandidateStagingDebug.Verification.Available
                )
                val candidate = verification as DshCandidateStagingDebug.Verification.Available
                assertEquals(candidateId, candidate.candidateId)
                assertEquals(DshCandidateStagingDebug.EXPECTED_COMPONENTS, candidate.componentCount)
                assertTrue(candidate.missingComponents.isEmpty())
                assertTrue("declared hashes do not match the staged bytes", candidate.declaredHashesMatch)
                assertEquals(64, candidate.fingerprint.length)
                println(
                    "DSH_CANDIDATE_ACCEPTED id=${candidate.candidateId} " +
                        "fingerprint=${candidate.fingerprint} components=${candidate.componentCount}"
                )
            }

            EXPECT_REJECTED -> {
                assertTrue(
                    "expected a typed candidate rejection, got $verification",
                    verification is DshCandidateStagingDebug.Verification.Rejected
                )
                val rejected = verification as DshCandidateStagingDebug.Verification.Rejected
                println("DSH_CANDIDATE_REJECTED code=${rejected.code} message=${rejected.message}")
            }

            else -> throw AssertionError("unknown -e $ARG_EXPECT=$expectation (use available|rejected)")
        }
    }

    private fun payloadPackaged(): Boolean =
        runCatching { context.assets.list("dsh")?.contains("payload.zip") == true }.getOrDefault(false)

    private fun candidateId(): String {
        InstrumentationRegistry.getArguments().getString(ARG_CANDIDATE_ID)?.let { return it }
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "unknown" }
        return "candidate-device-$versionName"
    }

    /** Same schema as the committed closure; installations without a selector use the D24 value. */
    private fun schema(): Int =
        DshCandidateStagingDebug.currentDataSchema(context.filesDir).takeIf { it > 0 } ?: FALLBACK_DATA_SCHEMA

    private fun argument(name: String, fallback: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: fallback

    private companion object {
        const val ARG_ENABLED = "dshCandidateStaging"
        const val ARG_CANDIDATE_ID = "candidateId"
        const val ARG_STAGE = "stage"
        const val ARG_EXPECT = "expect"
        const val EXPECT_AVAILABLE = "available"
        const val EXPECT_REJECTED = "rejected"
        const val FALLBACK_DATA_SCHEMA = 5
    }
}
