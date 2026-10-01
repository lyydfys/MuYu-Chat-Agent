package com.muyuchat.api.local

/**
 * Detects strings that look like filesystem or content-resolver paths.
 *
 * Not a general sanitiser: it answers one question, whether a value is the kind of path that
 * must not be echoed into stored image history or into a Local API response. Callers use it to
 * drop such values, never to rewrite them.
 *
 * One implementation, in one place. The same function existed twice, body for body, as
 * looksLikePrivatePath in app/ImageGenerationHistoryMetadata.kt and as
 * looksLikePrivateExecutionPath in this module's ImageGenerationApiContract.kt. A leak filter
 * duplicated across a storage path and an API path is how one of the two ends up missing a
 * scheme the other catches.
 *
 * Patterns, unchanged from both copies:
 *   - POSIX absolute paths and UNC paths
 *   - file:, content: and android.resource: URIs, case-insensitively
 *   - Windows drive-letter paths such as C:\ or C:/
 *   - the private directories /data/, /storage/, /sdcard/, /mnt/, /cache/, /tmp/,
 *     matched case-insensitively and anywhere in the string
 */
public fun String.looksLikePrivatePath(): Boolean {
    val value = trim()
    if (value.startsWith("/") ||
        value.startsWith("\\\\") ||
        value.contains("file:", ignoreCase = true) ||
        value.contains("content:", ignoreCase = true) ||
        value.contains("android.resource:", ignoreCase = true)
    ) return true
    if (Regex("[A-Za-z]:[\\\\/]").containsMatchIn(value)) return true
    val lowered = value.lowercase()
    return listOf("/data/", "/storage/", "/sdcard/", "/mnt/", "/cache/", "/tmp/")
        .any(lowered::contains)
}
