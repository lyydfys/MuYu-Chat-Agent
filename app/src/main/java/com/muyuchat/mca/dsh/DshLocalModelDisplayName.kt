package com.muyuchat.mca.dsh

import org.json.JSONObject

/**
 * Authoritative, read-only source of the user-facing local model name.
 *
 * [displayName] may read the existing app model catalog, but it must never load, unload, switch or
 * otherwise mutate a model; a failure is ignored by the adapter and a derived label is used.
 * It is optional so the adapter stays honest when no catalog source is wired.
 */
internal fun interface DshLocalModelNameSource {
    fun displayName(modelId: String, modelPath: String?): String?
}

/**
 * Derives the human label the official DSH model picker shows for the loaded local model.
 *
 * The loaded model identity (`ModelRuntimeIdentity.modelId`) is the MCA manifest **UUID**, so it is
 * never a display name. This helper resolves, in order of trust:
 *  1. the manifest display name (for example `Qwen3.5 0.8B MNN CPU`), supplied through
 *     [DshLocalModelNameSource] (the only source that survives hash-named MNN bundle directories);
 *  2. a readable loaded model path component (GGUF / LiteRT file names, readable bundle dirs) with
 *     the extension and trailing quantization tag removed (`Qwen3.5-0.8B-Q4_K_M.gguf` ->
 *     `Qwen3.5 0.8B`);
 *  3. a native runtime model name when the runner exposes one (GenieX);
 *  4. a readable model id;
 *  5. [FALLBACK] — never the opaque UUID.
 *
 * Pure and side-effect free: it inspects strings only, never the file system, so it cannot leak a
 * full path, a token or any other secret into the catalog. The gateway keeps adding its
 * `[PROMPTED_TOOL_V1]` honesty label independently of this name.
 */
internal object DshLocalModelDisplayName {
    const val FALLBACK: String = "MCA local model"

    private const val MAX_CHARS = 120
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX = Regex("^[0-9a-fA-F]{16,}$")
    private val DIGITS = Regex("^\\d{8,}$")
    private val PACKAGE_LIKE = Regex("^[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+){2,}$")
    private val WHITESPACE = Regex("\\s+")
    private val KNOWN_EXTENSIONS = listOf(
        ".gguf", ".litertlm", ".bin", ".mnn", ".task", ".safetensors", ".zip", ".tar", ".gz"
    )

    /**
     * Trailing quality/backend tags only. Only applied to file/directory-derived labels: a curated
     * manifest name keeps its own wording (`Qwen3.5 0.8B MNN CPU` stays intact).
     */
    private val TRAILING_QUALITY_TAG = Regex(
        "(?i)(?:^|[-_. ])(?:i?q\\d+(?:_[a-z0-9]+)*|bf16|f16|f32|fp16|fp32|w\\d+a\\d+|int[48]|mnn|qairt|litert|gguf)(?=$|[-_. ])"
    )
    /**
     * Embedded identity tokens (a UUID or an 8+ character hex run) are stripped from labels
     * anywhere a word-ish boundary allows it, because they are filesystem/manifest identities, not
     * names. Pure decimal runs (dates, version-like numbers) are deliberately kept.
     */
    private val EMBEDDED_UUID = Regex(
        "(?i)(?<![A-Za-z0-9])[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![A-Za-z0-9])"
    )
    private val EMBEDDED_HEX_RUN = Regex(
        "(?i)(?<![A-Za-z0-9])[0-9a-f]{8,}(?![A-Za-z0-9])"
    )
    private val GENERIC_PATH_SEGMENTS = setOf(
        "models", "model", "files", "file", "cache", "downloads", "download", "tmp",
        "data", "user", "0", "storage", "emulated", "android", "sdcard"
    )

    fun resolve(
        manifestName: String?,
        modelPath: String?,
        nativeModelName: String?,
        modelId: String
    ): String {
        readable(manifestName)?.let { return it }
        fromPath(modelPath)?.let { return it }
        readable(nativeModelName)?.let { return it }
        readable(modelId)?.let { return it }
        return FALLBACK
    }

    /**
     * Returns a display-safe label, or null when the value is blank, opaque or unusable.
     *
     * Path-like input is never returned verbatim: when a manifest/native name contains `/` or `\`
     * only its last non-blank component is used (`/data/.../Qwen3.5 0.8B` and
     * `C:\models\Qwen3.5 0.8B.gguf` both become `Qwen3.5 0.8B`), and a basename that is itself an
     * opaque identity (UUID/hash) is rejected so the next source is tried. A path separator can
     * never appear in the returned label.
     *
     * Identity tokens embedded in an otherwise readable label are removed: a complete UUID or an
     * 8+ character hex run delimited by non-alphanumeric boundaries (for example
     * `Qwen3.5 0.8B mnn 47f681e8 2d00d9d839a3453dbd2d4f75e08b48de` becomes
     * `Qwen3.5 0.8B mnn`). Curated words (`Qwen3.5`, `0.8B`, `MNN CPU`) and pure decimal runs
     * (`20240601`, `12345678`) are never removed; a hex token also has to contain an `a-f` letter
     * to qualify, so version-like numbers survive.
     */
    fun readable(raw: String?, stripQualityTag: Boolean = false): String? {
        var value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.contains('/') || value.contains('\\')) {
            value = value.split('/', '\\').lastOrNull { it.isNotBlank() } ?: return null
        }
        if (looksOpaque(value)) return null
        KNOWN_EXTENSIONS.firstOrNull { value.endsWith(it, ignoreCase = true) }?.let { extension ->
            value = value.dropLast(extension.length)
        }
        if (stripQualityTag) {
            repeat(3) {
                val next = TRAILING_QUALITY_TAG.replace(value, "")
                if (next == value) return@repeat
                value = next
            }
        }
        value = stripOpaqueIdentityTokens(value)
        value = value
            .filter { !it.isISOControl() }
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(WHITESPACE, " ")
            .trim { it.isWhitespace() || it in "-_.:/|" }
        if (value.contains('/') || value.contains('\\')) return null
        if (value.length < 2 || looksOpaque(value)) return null
        return value.take(MAX_CHARS).trimEnd()
    }

    /**
     * Removes embedded UUIDs and 8+ character hex runs delimited by alphanumeric-word boundaries.
     * UUIDs are removed as a whole first; a hex run is removed only when it contains an `a-f`
     * letter, which keeps pure decimal runs such as dates and version numbers intact.
     *
     * Known edge (documented, not handled): a curated word made only of hex characters with an
     * `a-f` letter and at least 8 characters (for example `Cafe2024`) is indistinguishable from a
     * hash and is removed. Distinguishing it would require a word allowlist; the product label
     * normally uses `Qwen3.5`-style names that are unaffected.
     */
    private fun stripOpaqueIdentityTokens(value: String): String {
        if (value.isEmpty()) return value
        var stripped = EMBEDDED_UUID.replace(value, " ")
        stripped = EMBEDDED_HEX_RUN.replace(stripped) { match ->
            if (match.value.any { it in 'a'..'f' || it in 'A'..'F' }) " " else match.value
        }
        return stripped
    }

    /**
     * Derives a readable label from the loaded model path without touching the file system.
     *
     * `.../models/Qwen3.5-0.8B-Q4_K_M.gguf` -> `Qwen3.5 0.8B`, while a hash-named MNN bundle
     * directory (`.../models/<64 hex>`) yields null so the caller can use the manifest name.
     */
    fun fromPath(modelPath: String?): String? {
        val raw = modelPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val components = raw.split('/', '\\').filter { it.isNotBlank() }
        if (components.isEmpty()) return null
        val base = components.last()
        if (!isGenericSegment(base) && !PACKAGE_LIKE.matches(base)) {
            readable(base, stripQualityTag = true)?.let { return it }
        }
        if (components.size >= 2) {
            val parent = components[components.size - 2]
            val outer = components.getOrNull(components.size - 3)
            val parentUsable = !isGenericSegment(parent) &&
                !looksOpaque(parent) &&
                !PACKAGE_LIKE.matches(parent)
            if (parentUsable && (outer == null || isGenericSegment(outer))) {
                readable(parent, stripQualityTag = true)?.let { return it }
            }
        }
        return null
    }

    /** True for values that are identities rather than names (UUIDs, hashes, long digit runs). */
    fun looksOpaque(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isEmpty() ||
            UUID.matches(trimmed) ||
            HEX.matches(trimmed) ||
            DIGITS.matches(trimmed)
    }

    /**
     * Extracts the human model name from the process-global loaded-model document that the Local
     * API projection already publishes (`{"id","name","displayName",...}`).
     *
     * The document's `id` must equal [expectedModelId] so a stale projection after a model switch
     * can never label the wrong model. Pure JSON parsing: callers decide where the document comes
     * from, which keeps the derivation unit-testable without the API module.
     */
    fun loadedModelNameFromJson(json: String?, expectedModelId: String): String? {
        if (json.isNullOrBlank() || expectedModelId.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("id") != expectedModelId) return null
        val name = root.optString("displayName").ifBlank { root.optString("name") }
        return readable(name)
    }

    private fun isGenericSegment(value: String): Boolean =
        value.lowercase() in GENERIC_PATH_SEGMENTS
}
