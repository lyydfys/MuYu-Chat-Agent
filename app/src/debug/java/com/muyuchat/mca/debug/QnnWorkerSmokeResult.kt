package com.muyuchat.mca.debug

import java.security.MessageDigest
import org.json.JSONObject

/** Binds smoke evidence to the worker's final image, never to the prerequisite run. */
internal fun qnnWorkerSmokeResult(
    executionMetadataJson: String,
    outputBytes: ByteArray,
    requestedSteps: Int?,
    mainProcessVerification: JSONObject
): JSONObject {
    require(executionMetadataJson.isNotBlank()) {
        "QNN worker result is missing final execution metadata."
    }
    val result = JSONObject(executionMetadataJson)
    val nativeEffective = requireNotNull(result.optJSONObject("nativeEffective")) {
        "QNN worker result is missing final nativeEffective evidence."
    }
    fun exactLong(json: JSONObject, field: String): Long {
        val number = json.opt(field) as? Number
            ?: error("QNN worker $field evidence must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "QNN worker $field evidence must be an exact integer."
        }
        return value
    }
    // Keep scheduler-specific counts as reported; PNDM and CFG need not run one
    // graph per requested step. The production execution contract validates them.
    listOf("steps", "timetableCount", "unetExecutionCount").forEach { field ->
        val actual = exactLong(result, field)
        require(actual > 0 && actual == exactLong(nativeEffective, field)) {
            "QNN worker $field conflicts with final nativeEffective evidence."
        }
    }
    requestedSteps?.let { requested ->
        require(exactLong(result, "steps") == requested.toLong()) {
            "QNN worker native steps=${result.get("steps")} differ from requested steps=$requested."
        }
    }
    val npuExecutionProven = listOf("npuActive", "qnnGraphExecution", "nativeExecution")
        .all { result.opt(it) == true } && result.opt("fallback") == false
    require(npuExecutionProven) {
        "QNN worker result does not prove final native NPU execution without fallback."
    }
    require(outputBytes.isNotEmpty() && exactLong(result, "outputBytes") == outputBytes.size.toLong()) {
        "QNN worker output byte count does not match the final image."
    }
    val outputSha256 = MessageDigest.getInstance("SHA-256").digest(outputBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(result.optString("outputSha256").equals(outputSha256, ignoreCase = true)) {
        "QNN worker output SHA-256 does not match the final image."
    }
    return result
        .put("ok", true)
        .put("npuExecutionProven", npuExecutionProven)
        // The semantic smoke contract names this terminal readiness flag;
        // derive it from the final worker evidence rather than preflight.
        .put("semanticReady", npuExecutionProven)
        .put("executionEvidenceSource", "worker_execution_metadata")
        .put("requestedSteps", requestedSteps ?: JSONObject.NULL)
        .put("mainProcessVerification", JSONObject(mainProcessVerification.toString()))
}
