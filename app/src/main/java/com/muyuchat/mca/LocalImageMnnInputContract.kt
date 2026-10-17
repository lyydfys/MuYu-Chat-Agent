package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject


/** MNN must echo every product-input field and prove the same count with concrete outputs. */
internal fun verifyAndSanitizeMnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report nativeEffective image input evidence."
        )
    require(result.has("taskMode") && !result.isNull("taskMode") &&
        nativeEffective.has("taskMode") && !nativeEffective.isNull("taskMode")
    ) {
        "MNN-Diffusion is missing required taskMode execution evidence."
    }
    require(result.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion did not execute the requested taskMode."
    }
    require(nativeEffective.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion nativeEffective taskMode differs from the request."
    }

    val outputs = result.optJSONArray("outputs")
        ?: localImageInputReject(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report its concrete outputs array."
        )
    require(outputs.length() == options.batchCount) {
        "MNN-Diffusion output count differs from batchCount."
    }
    val outputPath = result.optString("outputPath")
    val nativeOutputPath = nativeEffective.optString("outputPath")
    val outputBytes = result.optLong("outputBytes", -1L)
    val nativeOutputBytes = nativeEffective.optLong("outputBytes", -1L)
    val outputSha256 = result.optString("outputSha256")
    val nativeOutputSha256 = nativeEffective.optString("outputSha256")
    require(outputPath.isNotBlank() && outputPath == nativeOutputPath &&
        outputBytes > 0L && outputBytes == nativeOutputBytes &&
        outputSha256.matches(Regex("^[0-9a-f]{64}$")) &&
        outputSha256 == nativeOutputSha256
    ) { "MNN-Diffusion output publication proof is missing or inconsistent." }
    for (index in 0 until outputs.length()) {
        val output = outputs.optJSONObject(index)
            ?: error("MNN-Diffusion output item must be an object.")
        require(output.optInt("index", -1) == index) {
            "MNN-Diffusion output indices are not contiguous."
        }
        require(output.optString("path") == outputPath &&
            output.optLong("outputBytes", -1L) == outputBytes &&
            output.optString("outputSha256") == outputSha256
        ) {
            "MNN-Diffusion output item differs from the committed publication proof."
        }
        require(output.optString("mimeType") == "image/png") {
            "MNN-Diffusion output item must be a PNG."
        }
        require(output.has("seed") && output.getLong("seed") == nativeEffective.getLong("seed")) {
            "MNN-Diffusion output seed differs from nativeEffective."
        }
    }
    require(result.has("batchCount") && !result.isNull("batchCount") &&
        nativeEffective.has("batchCount") && !nativeEffective.isNull("batchCount") &&
        result.getInt("batchCount") == options.batchCount &&
        nativeEffective.getInt("batchCount") == options.batchCount
    ) {
        "MNN-Diffusion batchCount execution evidence differs from the request."
    }

    fun verifyRole(
        pathField: String,
        countField: String,
        hashField: String,
        expected: LocalImagePreparedInput?
    ) {
        val expectedCount = if (expected == null) 0 else 1
        require(result.has(countField) && !result.isNull(countField) &&
            nativeEffective.has(countField) && !nativeEffective.isNull(countField)
        ) {
            "MNN-Diffusion is missing required $countField execution evidence."
        }
        require(result.getInt(countField) == expectedCount) {
            "MNN-Diffusion $countField evidence differs from the request."
        }
        require(nativeEffective.getInt(countField) == expectedCount) {
            "MNN-Diffusion nativeEffective $countField differs from the request."
        }
        require(result.has(pathField) && !result.isNull(pathField) &&
            nativeEffective.has(pathField) && !nativeEffective.isNull(pathField)
        ) {
            "MNN-Diffusion is missing required $pathField execution evidence."
        }
        val outerPath = result.getString(pathField)
        val innerPath = nativeEffective.getString(pathField)
        if (expected == null) {
            require(outerPath.isBlank() && innerPath.isBlank()) {
                "MNN-Diffusion reported an unused $pathField."
            }
            require(!result.has(hashField) && !nativeEffective.has(hashField)) {
                "MNN-Diffusion reported unexpected $hashField evidence."
            }
        } else {
            val expectedPath = File(expected.path).canonicalPath
            require(outerPath.isNotBlank() && File(outerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion did not execute the prepared $pathField."
            }
            require(innerPath.isNotBlank() && File(innerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion nativeEffective did not bind the prepared $pathField."
            }
            require(result.has(hashField) && !result.isNull(hashField) &&
                nativeEffective.has(hashField) && !nativeEffective.isNull(hashField) &&
                result.getString(hashField) == expected.sha256 &&
                nativeEffective.getString(hashField) == expected.sha256
            ) {
                "MNN-Diffusion did not report the native-consumed $hashField."
            }
        }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }

    verifyRole("inputImagePath", "inputImageExecutionCount", "inputImageSha256", options.inputImage)
    verifyRole("maskImagePath", "maskImageExecutionCount", "maskImageSha256", options.maskImage)
    verifyRole(
        "controlImagePath",
        "controlImageExecutionCount",
        "controlImageSha256",
        options.controlImage
    )
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
}

