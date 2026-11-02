package com.muyuchat.mca.agent.tools

/**
 * Validation shared by `workspace.write_text` and `workspace.edit_text`.
 *
 * Both tools write UTF-8 text through the app file port, which owns staging and the atomic commit.
 * These helpers only enforce what the executor can still prove before handing content over:
 *
 * - the payload is well-formed UTF-8 text (no unpaired surrogates, which would make canonical JSON
 *   quoting ambiguous);
 * - the encoded size is inside the frozen write budget;
 * - a logical path was already canonicalised by the frozen-command normalizer
 *   ([com.muyuchat.mca.agent.security.AgentLogicalPaths]) and still starts below `artifacts/`.
 */
internal object AgentTextWriteSupport {
    /** Frozen per-write content budget (architecture 9.1, same as `artifact.write_text`). */
    const val MAX_CONTENT_BYTES: Int = 32 * 1024

    /** Largest file `workspace.edit_text` will read-modify-write; larger files need a fresh write. */
    const val MAX_EDIT_FILE_BYTES: Long = 32L * 1024

    /** One read window used by the edit tool when it loads the current file content. */
    const val EDIT_READ_WINDOW_BYTES: Int = 16_384

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    fun requireValidText(text: String, field: String) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isHighSurrogate() -> {
                    val next = text.getOrNull(index + 1)
                    if (next == null || !next.isLowSurrogate()) {
                        throw AgentToolInputException("Tool argument '$field' contains an unpaired surrogate.")
                    }
                    index += 2
                }
                char.isLowSurrogate() -> {
                    throw AgentToolInputException("Tool argument '$field' contains an unpaired surrogate.")
                }
                else -> index += 1
            }
        }
    }

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun requireWithinWriteBudget(text: String, field: String) {
        requireValidText(text, field)
        if (utf8Size(text) > MAX_CONTENT_BYTES) {
            throw AgentToolInputException(
                "Tool argument '$field' exceeds the $MAX_CONTENT_BYTES-byte UTF-8 write budget."
            )
        }
    }

    fun requireCanonicalArtifactPath(path: String) {
        if (!path.startsWith("artifacts/")) {
            throw AgentToolInputException("Write targets must be a file below artifacts/.")
        }
    }

    fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)
}
