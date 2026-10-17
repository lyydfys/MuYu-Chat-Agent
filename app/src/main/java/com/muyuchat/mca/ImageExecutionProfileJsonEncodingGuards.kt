package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal fun clipTokenId(json: JSONObject, idField: String, tokenField: String, fixedId: Int): Int {
    json.optionalStrictInt(idField)?.let { return it }
    if (!json.has(tokenField) || json.isNull(tokenField)) {
        throw formatError("tokenizer.$idField", "$idField or $tokenField must declare the CLIP special token.")
    }
    return when (val token = json.get(tokenField)) {
        is String -> {
            if (token.isBlank()) throw formatError("tokenizer.$tokenField", "$tokenField must not be blank.")
            fixedId
        }
        is JSONObject -> token.optionalStrictInt("id") ?: fixedId
        else -> throw formatError("tokenizer.$tokenField", "$tokenField must be a string or token object.")
    }
}

internal fun schedulerAlgorithm(
    declared: String,
    algorithmType: String?,
    solverOrder: Int?
): ImageSchedulerAlgorithm {
    val normalized = declared.trim().lowercase().replace(Regex("[^a-z0-9+]+"), "")
    val normalizedType = algorithmType.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9+]+"), "")
    return when {
        "flowmatch" in normalized -> ImageSchedulerAlgorithm.FLOW_MATCH
        "eulerancestral" in normalized || normalized == "eulera" -> ImageSchedulerAlgorithm.EULER_A
        "euler" in normalized -> ImageSchedulerAlgorithm.EULER
        "ddim" in normalized -> ImageSchedulerAlgorithm.DDIM
        "pndm" in normalized || "plms" in normalized -> ImageSchedulerAlgorithm.PNDM_PLMS
        "dpm++2m" in normalized || "dpmpp2m" in normalized -> ImageSchedulerAlgorithm.DPMPP_2M
        "dpmsolvermultistep" in normalized && "dpmsolver++" in normalizedType && solverOrder == 2 -> {
            ImageSchedulerAlgorithm.DPMPP_2M
        }
        else -> throw formatError("scheduler.algorithm", "Unsupported scheduler algorithm: $declared")
    }
}

internal fun predictionType(value: String, field: String): ImagePredictionType = when (value.trim().lowercase()) {
    "epsilon" -> ImagePredictionType.EPSILON
    "v_prediction", "v-prediction" -> ImagePredictionType.V_PREDICTION
    "sample" -> ImagePredictionType.SAMPLE
    "flow", "flow_prediction" -> ImagePredictionType.FLOW
    else -> throw formatError(field, "Unsupported prediction type: $value")
}

internal fun noiseSchedule(value: String): ImageNoiseSchedule = when (value.trim().lowercase()) {
    "linear" -> ImageNoiseSchedule.LINEAR
    "scaled_linear" -> ImageNoiseSchedule.SCALED_LINEAR
    "sigma", "karras" -> ImageNoiseSchedule.SIGMA
    "model_declared", "squaredcos_cap_v2" -> ImageNoiseSchedule.MODEL_DECLARED
    else -> throw formatError("scheduler.beta_schedule", "Unsupported beta schedule: $value")
}

internal fun timestepSpacing(value: String): ImageTimestepSpacing = when (value.trim().lowercase()) {
    "leading" -> ImageTimestepSpacing.LEADING
    "trailing" -> ImageTimestepSpacing.TRAILING
    "linspace" -> ImageTimestepSpacing.LINSPACE
    else -> throw formatError("scheduler.timestep_spacing", "Unsupported timestep spacing: $value")
}

internal fun readJsonFile(file: File, field: String): JSONObject = try {
    JSONObject(file.readText(Charsets.UTF_8))
} catch (error: Exception) {
    throw ImageExecutionProfileJsonException(
        code = "PROFILE_JSON_INVALID",
        field = field,
        message = error.message ?: "Invalid JSON file.",
        cause = error
    )
}

internal fun safeBundleFile(root: File, relativePath: String, field: String): File {
    requireSafePath(relativePath, field)
    val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { error ->
        throw ImageExecutionProfileJsonException("PROFILE_PATH_INVALID", field, "Bundle root is invalid.", error)
    }
    val candidate = runCatching { File(canonicalRoot, relativePath).canonicalFile }.getOrElse { error ->
        throw ImageExecutionProfileJsonException("PROFILE_PATH_INVALID", field, "Sidecar path is invalid.", error)
    }
    if (candidate.path != canonicalRoot.path && !candidate.path.startsWith(canonicalRoot.path + File.separator)) {
        throw formatError(field, "Sidecar path escapes the bundle root.", "PROFILE_PATH_INVALID")
    }
    return candidate
}

internal fun requireSafePath(path: String, field: String) {
    val normalized = path.trim().replace('\\', '/')
    val safe = normalized.isNotBlank() &&
        !normalized.startsWith('/') &&
        !normalized.startsWith("./") &&
        !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
        normalized.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    if (!safe) throw formatError(field, "Path must be a safe bundle-relative path.", "PROFILE_PATH_INVALID")
}

