package com.muyuchat.mca

import org.json.JSONObject

/**
 * QNN text-to-image reports three empty worker path slots as native evidence. Verify the slots
 * before removing them so a Local API response exposes neither private paths nor private path
 * field names. Missing or non-empty slots remain a native contract failure.
 */
internal fun verifyAndSanitizeQnnTextToImagePrivatePaths(
    result: JSONObject,
    options: LocalImageGenerationOptions
) {
    validateQnnNativeDetailStageMask(result)
    require(
        options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
            options.inputImage == null && options.maskImage == null && options.controlImage == null
    ) { "QNN text-to-image path sanitization requires a request without image inputs." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "QNN text-to-image did not report nativeEffective input evidence."
        )
    require(
        result.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName &&
            nativeEffective.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName
    ) { "QNN text-to-image taskMode evidence differs from the request." }
    require(
        result.optInt("batchCount", -1) == options.batchCount &&
            nativeEffective.optInt("batchCount", -1) == options.batchCount
    ) { "QNN text-to-image batchCount evidence differs from the request." }
    listOf("inputImage", "maskImage", "controlImage").forEach { role ->
        val countField = "${role}ExecutionCount"
        val pathField = "${role}Path"
        require(
            result.optInt(countField, -1) == 0 &&
                nativeEffective.optInt(countField, -1) == 0
        ) { "QNN text-to-image reported an unexpected $countField." }
        require(
            result.has(pathField) && !result.isNull(pathField) &&
                nativeEffective.has(pathField) && !nativeEffective.isNull(pathField) &&
                result.getString(pathField).isBlank() &&
                nativeEffective.getString(pathField).isBlank()
        ) { "QNN text-to-image reported invalid $pathField evidence." }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }
}
