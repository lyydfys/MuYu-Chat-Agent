package com.muyuchat.core.engine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Immutable identity for a reusable, persisted prompt prefix.
 *
 * Every component is an exact lowercase SHA-256 digest.  Callers should bind
 * the key to every input that can change tokenization or serialized llama.cpp
 * state: model, tokenizer, template, fixed system prompt, runtime/state
 * format, and the exact fixed prefix.
 */
data class PrefixCacheKey(
    val modelFingerprint: String,
    val tokenizerFingerprint: String,
    val templateFingerprint: String,
    val systemPromptFingerprint: String,
    val runtimeFingerprint: String,
    val prefixFingerprint: String
) {
    init {
        require(isSha256Hex(modelFingerprint)) { "modelFingerprint must be a lowercase SHA-256 hex digest." }
        require(isSha256Hex(tokenizerFingerprint)) { "tokenizerFingerprint must be a lowercase SHA-256 hex digest." }
        require(isSha256Hex(templateFingerprint)) { "templateFingerprint must be a lowercase SHA-256 hex digest." }
        require(isSha256Hex(systemPromptFingerprint)) { "systemPromptFingerprint must be a lowercase SHA-256 hex digest." }
        require(isSha256Hex(runtimeFingerprint)) { "runtimeFingerprint must be a lowercase SHA-256 hex digest." }
        require(isSha256Hex(prefixFingerprint)) { "prefixFingerprint must be a lowercase SHA-256 hex digest." }
    }

    /** A path-safe SHA-256 identifier derived from the complete key. */
    val cacheId: String = sha256Utf8(
        listOf(
            KEY_FORMAT_VERSION,
            modelFingerprint,
            tokenizerFingerprint,
            templateFingerprint,
            systemPromptFingerprint,
            runtimeFingerprint,
            prefixFingerprint
        ).joinToString(separator = "\u001f")
    )

    companion object {
        private const val KEY_FORMAT_VERSION = "mca-prefix-cache-key-v1"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /** Returns true only for canonical lowercase, 64-character SHA-256 hex. */
        @JvmStatic
        fun isSha256Hex(value: String): Boolean = SHA256_HEX.matches(value)

        /** Canonical lowercase SHA-256 for UTF-8 content supplied by a caller. */
        @JvmStatic
        fun sha256Utf8(value: CharSequence): String =
            sha256(value.toString().toByteArray(StandardCharsets.UTF_8))

        /** Canonical lowercase SHA-256 for arbitrary bytes supplied by a caller. */
        @JvmStatic
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val chars = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                chars[index * 2] = HEX_DIGITS[value ushr 4]
                chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
            }
            return chars.concatToString()
        }
    }
}
