package com.muyuchat.api.local

import java.nio.ByteBuffer
import java.security.MessageDigest

fun imagePromptExecutionSha256(prompt: String, negativePrompt: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String?) {
        if (value == null) {
            digest.update(0.toByte())
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(1.toByte())
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    update(prompt)
    update(negativePrompt)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun imageTextualInversionBindingFingerprint(
    id: String,
    sha256: String,
    trigger: String,
    modelFingerprint: String,
    tokenizerFingerprint: String,
    profileId: String,
    profileRevision: Long,
    runtime: String
): String = sha256Utf8(
    listOf(
        "textual-inversion-binding-v1",
        id,
        sha256,
        trigger.lowercase(),
        modelFingerprint.lowercase(),
        tokenizerFingerprint.lowercase(),
        profileId,
        profileRevision.toString(),
        runtime
    ).joinToString("\u001f")
)

internal fun imageTextualInversionSelectionFingerprint(
    bindings: List<Pair<String, String>>
): String = sha256Utf8(
    (
        listOf("textual-inversion-selection-v1") +
            bindings.sortedBy { (trigger, _) -> trigger.lowercase() }.map { (_, fingerprint) ->
                fingerprint
            }
        ).joinToString("\u001f")
)

internal data class ImageTextualInversionExecutionAsset(
    val label: String,
    val sizeBytes: Long,
    val sha256: String
)

internal fun imageTextualInversionExecutionAssetsSha256(
    runtime: String,
    profilePromptFingerprint: String,
    assets: List<ImageTextualInversionExecutionAsset>
): String = sha256Utf8(
    (
        listOf(
            "textual-inversion-execution-assets-v1",
            runtime,
            profilePromptFingerprint
        ) + assets.flatMap { asset ->
            listOf(asset.label, asset.sizeBytes.toString(), asset.sha256)
        }
        ).joinToString("\u001f")
)

internal fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun ultrafixNoiseSeedFingerprint(seed: Long, denoiseStepCount: Long): String {
    require(denoiseStepCount > 0L)
    return sha256Utf8(
        "mca-ultrafix-quality-noise-v1|seed=${seed.toULong()}|steps=$denoiseStepCount"
    )
}

/**
 * Portable consistency checksum for app-produced prompt-processing metadata. This is intentionally
 * not a signature or remote attestation; the live app provider is the trust boundary, and persisted
 * translated history must be reverified before it can become executable again.
 */
fun imagePromptTranslationProofFingerprint(
    contractVersion: Int,
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String,
    negativePromptSource: String,
    translationPlanSha256: String,
    verificationReceiptSha256: String,
    translationPhaseSystemPromptSha256: String,
    verificationPhaseSystemPromptSha256: String,
    translatorRuntime: String,
    translatorModelSha256: String,
    promptLanguageBindingFingerprint: String
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String?) {
        if (value == null) {
            digest.update(0.toByte())
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(1.toByte())
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    update("mca-local-image-prompt-translation-proof-v4")
    update(contractVersion.toString())
    update(originalPrompt)
    update(effectivePrompt)
    update(originalNegativePrompt)
    update(if (negativePromptSource == "USER") effectiveNegativePrompt else "")
    update(negativePromptSource)
    update(translationPlanSha256)
    update(verificationReceiptSha256)
    update(translationPhaseSystemPromptSha256)
    update(verificationPhaseSystemPromptSha256)
    update(translatorRuntime)
    update(translatorModelSha256)
    update(promptLanguageBindingFingerprint)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
