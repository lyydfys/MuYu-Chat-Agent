package com.muyuchat.mca

import java.security.MessageDigest

/** Stable, non-secret identity for deciding whether a chat image retry still targets its source. */
internal fun LocalImageModelRecord.chatImageRetryFingerprint(): String = stableImageModelFingerprint(
    listOf(
        "local",
        id,
        sha256.trim().lowercase(),
        sizeBytes.toString(),
        runtime.name,
        family.name,
        imageSize.trim(),
        componentCount.toString(),
        recommendationId.orEmpty(),
        qnnVerificationStamp.trim()
    )
)

/** API keys are deliberately excluded so a credential rotation does not invalidate a retry. */
internal fun CloudApiConfig.chatImageRetryFingerprint(): String {
    val normalized = normalizedForImageRequest()
    return stableImageModelFingerprint(
        listOf(
            "cloud",
            normalized.apiFormat.name,
            normalized.baseUrl.trimEnd('/'),
            normalized.imageApiFormat.name,
            normalized.imageModel.trim(),
            normalized.imageSize.trim(),
            normalized.imageEndpointPathForRequest().trim('/'),
            normalized.enabled.toString()
        )
    )
}

private fun stableImageModelFingerprint(parts: List<String>): String {
    val canonical = parts.joinToString(separator = "\u0000") { value ->
        "${value.length}:$value"
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
