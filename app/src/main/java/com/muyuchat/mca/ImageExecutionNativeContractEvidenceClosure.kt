package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: prompt-to-encoder closure and pixel-range evidence; moved verbatim from ImageExecutionProfileNativeContract.kt. */

    /** Compares the native role closure exactly; no path, role, or duplicate may be ignored. */
internal fun JSONObject.matchesPromptToEncoderClosure(
        expected: Map<ImagePromptToEncoderAssetRole, ImageProfileAsset>
    ): Boolean {
        val values = requiredValue("consumedPromptToEncoderAssets") as? org.json.JSONArray
            ?: invalid(
                "consumedPromptToEncoderAssets",
                "Native QNN prompt-to-encoder receipt must be an array."
            )
        if (values.length() != expected.size) return false
        val consumed = linkedMapOf<ImagePromptToEncoderAssetRole, ImageProfileAsset>()
        for (index in 0 until values.length()) {
            val entry = values.optJSONObject(index) ?: invalid(
                "consumedPromptToEncoderAssets[$index]",
                "Native QNN prompt-to-encoder receipt entry must be an object."
            )
            val role = entry.requiredEnum(
                "role",
                ImagePromptToEncoderAssetRole.entries
            )
            val asset = ImageProfileAsset(
                relativePath = entry.nativeContractRequiredString("path"),
                fingerprint = entry.nativeContractRequiredString("sha256"),
                sizeBytes = entry.requiredLong("sizeBytes")
            )
            if (consumed.put(role, asset) != null) return false
        }
        return consumed.all { (role, actual) ->
            val expectedAsset = expected[role] ?: return@all false
            actual.relativePath.replace('\\', '/').trim() ==
                expectedAsset.relativePath.replace('\\', '/').trim() &&
                actual.fingerprint.lowercase() == expectedAsset.fingerprint.lowercase() &&
                actual.sizeBytes == expectedAsset.sizeBytes
        }
    }

    /**
     * A native receipt may claim that an encoder file was consumed, but that alone does not prove
     * its language semantics. Android verifies the publisher signature first and requires native
     * to return the exact resulting payload digest only after conditioning consumed that closure.
     */
internal fun validateNativeMultilingualLanguageProofReceipt(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ) {
        val expected = resolution.profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
            ?: return
        val actual = nativeEffective.nativeContractRequiredString("languageProofSha256").lowercase()
        if (!IMAGE_NATIVE_CONTRACT_SHA256.matches(actual) || actual != expected) {
            invalid(
                "languageProofSha256",
                "Native multilingual receipt did not bind Android's verified text-encoder semantic proof."
            )
        }
    }

internal fun validateQnnPixelRange(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ): ImagePixelRange {
        val expected = resolution.profile.vae.outputRange
        if (expected == ImagePixelRange.RUNTIME_NATIVE) {
            invalid(
                "pixelRange",
                "Resolved QNN image profile must declare an explicit VAE output pixel range."
            )
        }
        val actual = nativeEffective.requiredEnum("pixelRange", ImagePixelRange.entries)
        if (actual == ImagePixelRange.RUNTIME_NATIVE) {
            invalid(
                "pixelRange",
                "Native QNN image execution must report an explicit VAE output pixel range."
            )
        }
        if (actual != expected) {
            val mismatch = ImageExecutionMismatch(
                field = "pixelRange",
                resolved = expected.name,
                nativeEffective = actual.name
            )
            throw ImageNativeExecutionContractException(
                code = EXECUTION_CONTRACT_MISMATCH,
                field = "pixelRange",
                mismatches = listOf(mismatch),
                message = "Native QNN pixel range differs from the resolved VAE contract."
            )
        }
        return actual
    }
