package com.muyuchat.mca

import com.muyuchat.mca.debug.qnnWorkerSmokeResult
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QnnWorkerSmokeResultTest {
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val preflight = JSONObject().put("semantic", JSONObject()
        .put("ok", true).put("npuActive", true).put("semanticReady", true)
        .put("steps", 1).put("unetExecutionCount", 2).put("seed", 1234))

    private fun metadata(): JSONObject {
        val native = JSONObject().put("steps", 28).put("timetableCount", 28)
            .put("unetExecutionCount", 56).put("seed", 123)
            .put("sdxlPhaseProof", JSONObject().put("unetWorkerPid", 901)
                .put("vaeWorkerPid", 902))
        return JSONObject(native.toString()).put("nativeEffective", native)
            .put("npuActive", true).put("qnnGraphExecution", true)
            .put("nativeExecution", true).put("fallback", false)
            .put("effectiveDenoiseSteps", 28)
            .put("timesteps", JSONArray((1..28).toList()))
            .put("sigmas", JSONArray(listOf(1.0, 0.0)))
            .put("outputBytes", bytes.size)
            .put("outputSha256", MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun build(json: JSONObject = metadata(), requested: Int? = 28) =
        qnnWorkerSmokeResult(json.toString(), bytes, requested, preflight)

    @Test fun preservesFinal28StepsAndPhaseProofInsteadOfOneStepPreflight() {
        val result = build()
        assertEquals(28, result.getInt("steps"))
        assertEquals(28, result.getInt("effectiveDenoiseSteps"))
        assertEquals(56, result.getInt("unetExecutionCount"))
        assertEquals(123, result.getInt("seed"))
        assertEquals(28, result.getJSONArray("timesteps").length())
        assertEquals(2, result.getJSONArray("sigmas").length())
        assertEquals(901, result.getJSONObject("nativeEffective")
            .getJSONObject("sdxlPhaseProof").getInt("unetWorkerPid"))
        assertEquals(1, result.getJSONObject("mainProcessVerification")
            .getJSONObject("semantic").getInt("steps"))
        assertEquals("worker_execution_metadata", result.getString("executionEvidenceSource"))
        assertTrue(result.getBoolean("npuExecutionProven"))
        assertTrue(result.getBoolean("semanticReady"))
        assertEquals(metadata().getString("outputSha256"), result.getString("outputSha256"))
    }

    @Test fun rejectsMissingWorkerMetadataDespiteSuccessfulPreflight() {
        assertThrows(IllegalArgumentException::class.java) {
            qnnWorkerSmokeResult("", bytes, 28, preflight)
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(metadata().apply { remove("nativeEffective") })
        }
    }

    @Test fun rejectsActualStepMismatchInsteadOfCopyingRequest() {
        assertThrows(IllegalArgumentException::class.java) { build(requested = 30) }
    }

    @Test fun rejectsConflictingOrFractionalNativeCounts() {
        for (field in listOf("steps", "timetableCount", "unetExecutionCount")) {
            assertThrows(IllegalArgumentException::class.java) {
                build(metadata().apply { getJSONObject("nativeEffective").put(field, 1) })
            }
            assertThrows(IllegalArgumentException::class.java) {
                build(metadata().put(field, 28.5))
            }
        }
    }

    @Test fun preflightCannotOverwriteFailedWorkerExecutionOrFallback() {
        for (field in listOf("npuActive", "qnnGraphExecution", "nativeExecution", "fallback")) {
            assertThrows(IllegalArgumentException::class.java) {
                build(metadata().put(field, field == "fallback"))
            }
        }
    }

    @Test fun rejectsMismatchedOrMissingOutputDigest() {
        assertThrows(IllegalArgumentException::class.java) {
            build(metadata().put("outputSha256", "0".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            build(metadata().apply { remove("outputSha256") })
        }
    }

    @Test fun rejectsWrongOutputSizeAndEmptyImage() {
        assertThrows(IllegalArgumentException::class.java) {
            build(metadata().put("outputBytes", bytes.size + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            qnnWorkerSmokeResult(metadata().put("outputBytes", 0).toString(), byteArrayOf(), 28, preflight)
        }
    }

    @Test fun absentRequestDoesNotInventRequestedSteps() {
        val result = build(requested = null)
        assertTrue(result.isNull("requestedSteps"))
        assertEquals(28, result.getInt("steps"))
    }

    @Test fun preservesSchedulerSpecificCountsWithoutAssumingTwoGraphsPerStep() {
        val json = metadata().put("timetableCount", 37).put("unetExecutionCount", 37)
        json.getJSONObject("nativeEffective").put("timetableCount", 37).put("unetExecutionCount", 37)
        assertEquals(37, build(json).getInt("unetExecutionCount"))
    }
}
