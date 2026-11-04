package com.muyuchat.mca

/**
 * Naming rules for generation presets imported from a document picker.
 *
 * The picker returns SAF `content://` URIs, and their `lastPathSegment` is the storage provider's
 * opaque document id rather than a file name: `document:1000156859`,
 * `primary:Download/preset.json`, `raw:/storage/emulated/0/Download/preset.json`. Using it directly
 * as the fallback name leaked provider ids into the preset list, the import status line and the
 * import failure message. *
 * Everything here is pure: no `Uri`, no `ContentResolver`. The caller resolves the real file name
 * (`OpenableColumns.DISPLAY_NAME`) behind an injected `(Uri) -> String?` lambda and passes only
 * plain strings in, so the naming order and normalization are testable on the JVM.
 */
internal object GenerationPresetNaming {
    /** Displayed when a candidate cannot be a human-readable name. */
    const val FALLBACK_NAME: String = "模型预设"

    /** Upper bound for a stored preset name; the binding dialog edits names under the same cap. */
    const val MAX_NAME_CHARS: Int = 64

    /** Prefixes a SAF provider leaves in front of the document id inside `lastPathSegment`. */
    private val SAF_PREFIXES = listOf("document:", "primary:", "raw:", "msf:", "content:", "file:")

    private const val JSON_EXTENSION = ".json"

    /** A full URI that survived the pipeline is a document reference, not a name. */
    private val URI_WITH_AUTHORITY = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*://")

    private val LINE_BREAKS = Regex("[\\r\\n\\t]+")

    /**
     * Import-time fallback name for one picked file, in priority order:
     * the resolver's real file name → the normalized SAF `lastPathSegment` → [FALLBACK_NAME].
     *
     * The preset manager stores exactly this value as the preset name for every dialect (Tavern or
     * MCA). A name declared inside the JSON (`preset_name`) is reported by the adapter but never
     * replaces the file name, so two files sharing a name stay two presets.
     */
    fun fallbackName(displayName: String?, lastPathSegment: String?): String {
        normalizeFileName(displayName)?.let { return it }
        return normalizeFileName(lastPathSegment) ?: FALLBACK_NAME
    }

    /** Strict normalization of one filename/URI candidate; unusable input yields [FALLBACK_NAME]. */
    fun fromFileName(candidate: String?): String = normalizeFileName(candidate) ?: FALLBACK_NAME

    /**
     * Name stored on a [GenerationPresetRecord].
     *
     * The codec already prefers the preset file's own `"name"`, so this only sanitizes: line breaks
     * and the `.json` suffix go away, while authored text is kept verbatim — a file is allowed to
     * declare itself "2024". A value that still carries SAF plumbing (`primary:Download/x.json`,
     * a full `content://` URI) is not authored text, so it runs through the full filename pipeline
     * instead of storing provider syntax on the record.
     */
    fun recordName(rawName: String?): String {
        val collapsed = rawName.orEmpty().replace(LINE_BREAKS, " ").trim()
        val looksLikePlumbing = dropSafPrefixes(collapsed) != collapsed ||
            URI_WITH_AUTHORITY.containsMatchIn(collapsed)
        if (looksLikePlumbing) return fromFileName(collapsed)
        val cleaned = if (collapsed.endsWith(JSON_EXTENSION, ignoreCase = true)) {
            collapsed.dropLast(JSON_EXTENSION.length)
        } else {
            collapsed
        }
        return clamp(cleaned.trim()).ifBlank { FALLBACK_NAME }
    }

    private fun normalizeFileName(candidate: String?): String? {
        val cleaned = sanitizeFileName(candidate) ?: return null
        // A leftover scheme means the candidate was a whole URI, so nothing human is left.
        if (URI_WITH_AUTHORITY.containsMatchIn(cleaned)) return null
        // `1000156859`, `2026-11-03`, `---`: document ids and other text that cannot be a name.
        if (cleaned.none { it.isLetter() }) return null
        return cleaned
    }

    /** Shared filename pipeline: SAF prefix, directory parts and `.json`; null when nothing is left. */
    private fun sanitizeFileName(candidate: String?): String? {
        val raw = candidate?.trim().orEmpty()
        if (raw.isEmpty()) return null
        var value = raw.substringBefore('?').substringBefore('#').trim()
        value = dropSafPrefixes(value).replace('\\', '/').trimEnd('/')
        value = value.substringAfterLast('/')
        if (value.endsWith(JSON_EXTENSION, ignoreCase = true)) {
            value = value.dropLast(JSON_EXTENSION.length)
        }
        return value.trim().takeIf(String::isNotEmpty)?.let(::clamp)
    }

    private fun dropSafPrefixes(raw: String): String {
        var value = raw.trim()
        while (true) {
            val prefix = SAF_PREFIXES.firstOrNull { value.startsWith(it, ignoreCase = true) }
                ?: return value
            value = value.substring(prefix.length).trimStart('/', ' ')
        }
    }

    private fun clamp(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length <= MAX_NAME_CHARS) return trimmed
        val head = trimmed.take(MAX_NAME_CHARS)
        // Never split an emoji/surrogate pair at the cap; the stored name stays a valid string.
        val safe = if (head.lastOrNull()?.isHighSurrogate() == true) head.dropLast(1) else head
        return safe.trimEnd()
    }
}
