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
 * Patterns:
 *   - POSIX absolute paths and UNC paths
 *   - file:, content: and android.resource: URIs, case-insensitively
 *   - Windows drive-letter paths such as C:\ or C:/, matched on a boundary (see below)
 *   - the private directories /data/, /storage/, /sdcard/, /mnt/, /cache/, /tmp/,
 *     matched case-insensitively and anywhere in the string
 *
 * The drive-letter pattern used to be the bare `[A-Za-z]:[\\/]`, which matches the "s:/" inside
 * "https://" and the "p:/" inside "http://". Every http(s) URL was therefore classified as a
 * private path. That was not merely a dropped display field: one caller rejects the whole image
 * provider response when this returns true (private_image_input_path_exposed in
 * ImageGenerationApiContract), so a URL in that payload failed the response instead of having a
 * field stripped. The pattern now requires both boundaries described on WINDOWS_DRIVE_PATH.
 */
public fun String.looksLikePrivatePath(): Boolean {
    val value = trim()
    if (value.startsWith("/") ||
        value.startsWith("\\\\") ||
        value.contains("file:", ignoreCase = true) ||
        value.contains("content:", ignoreCase = true) ||
        value.contains("android.resource:", ignoreCase = true)
    ) return true
    if (WINDOWS_DRIVE_PATH.containsMatchIn(value)) return true
    val lowered = value.lowercase()
    return listOf("/data/", "/storage/", "/sdcard/", "/mnt/", "/cache/", "/tmp/")
        .any(lowered::contains)
}

/**
 * A Windows drive-letter path: one letter, a colon, one separator.
 *
 * Two boundaries, both required, and both derived from the shape of the strings involved:
 *
 *   - The letter must not be preceded by a letter or a digit. A URI scheme is letters followed
 *     by a colon, so without this the "s" of "https" and the "p" of "http" look like drive
 *     letters. Requiring a non-alphanumeric before the letter still accepts every real form --
 *     start of string, and a path preceded by a space, quote, bracket, equals sign or colon.
 *
 *   - The separator must not be followed by a second separator. A URI has two slashes after its
 *     scheme; a drive-letter path has one after the colon. The first boundary already handles
 *     every multi-letter scheme, so this one is what stops a single-letter one, and it states
 *     the URI-versus-path distinction rather than leaving it implied.
 *
 * Neither boundary weakens the other checks: absolute POSIX and UNC paths, file:, content: and
 * android.resource: URIs, and the private directory segments are matched by separate tests that
 * this pattern does not participate in.
 */
private val WINDOWS_DRIVE_PATH = Regex("(?<![A-Za-z0-9])[A-Za-z]:[\\\\/](?![\\\\/])")
