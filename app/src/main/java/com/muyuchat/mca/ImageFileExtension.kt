package com.muyuchat.mca

/**
 * File extension chosen for an image asset.
 *
 * Two paths name image files, and they start from different inputs: an import knows the
 * user's file name, while a generated image only knows the MIME type its provider reported.
 * Both must land on an extension the gallery and the decoder can handle, so both restrict
 * themselves to an allow-list and fall back to [FALLBACK_EXTENSION] for anything else.
 *
 * ## The two paths disagree on JPEG
 *
 * [imageExtension] keeps a recognised extension as-is, so an imported `photo.jpeg` is stored
 * as `.jpeg`, while [imageExtensionForMime] maps `image/jpeg` to `.jpg`. The same format can
 * therefore be stored under either extension depending on how it arrived. Both are valid
 * JPEG extensions and the bytes are unchanged, so this is a naming inconsistency rather than
 * a data problem; it is preserved and pinned rather than normalised, because renaming
 * existing assets is a storage change and does not belong to a refactor.
 *
 * Extracted verbatim from `MainViewModel` during P2; no allow-list entry or fallback was
 * changed.
 */

/** Extension used when a name or MIME type is unrecognised. */
internal const val FALLBACK_EXTENSION = "png"

/**
 * Extension taken from a file name, or [FALLBACK_EXTENSION] when it is not recognised.
 *
 * Only the last dot-separated segment is considered, lower-cased. A name without a dot, a
 * trailing dot, or an unknown extension all fall back.
 */
internal fun imageExtension(name: String): String {
    val extension = name.substringAfterLast('.', FALLBACK_EXTENSION).lowercase()
    return when (extension) {
        "jpg", "jpeg", "png", "webp", "heic", "heif" -> extension
        else -> FALLBACK_EXTENSION
    }
}

/**
 * Extension for a MIME type, or [FALLBACK_EXTENSION] when it is not recognised.
 *
 * Case is ignored and any `;`-separated parameters are dropped, so `Image/JPEG; charset=x`
 * resolves like `image/jpeg`. `image/jpg` is accepted as an alias for `image/jpeg`.
 */
internal fun imageExtensionForMime(mimeType: String): String =
    when (mimeType.lowercase().substringBefore(";").trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> FALLBACK_EXTENSION
    }
