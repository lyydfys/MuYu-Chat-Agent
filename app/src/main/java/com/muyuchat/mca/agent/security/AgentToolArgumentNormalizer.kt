package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentToolDescriptor

/**
 * Result of resolving one raw model tool-call argument string into the exact execution arguments.
 *
 * [normalizedArgumentsJson] already contains every default and the canonical logical path. It is
 * produced before the approval is shown and is never recomputed after hashing.
 */
sealed interface AgentArgumentNormalization {
    data class Normalized(
        val normalizedArgumentsJson: String,
        val logicalPaths: List<String>,
        val expectedOldHash: String?
    ) : AgentArgumentNormalization

    data class Rejected(val error: AgentSecurityError) : AgentArgumentNormalization
}

/**
 * Resolves defaults and logical resource scope for one tool descriptor.
 *
 * K08 injects this port when it registers tools. The production default covers exactly the four V1
 * built-in tools; future MCP/extension tools must supply their own descriptor-aware normalizer and
 * still go through the same frozen-command and broker path (no unapproved bypass).
 */
interface AgentToolArgumentNormalizer {
    fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization
}

/**
 * Strict defaults resolver for workspace.list / workspace.read_text / workspace.search_text /
 * artifact.write_text (architecture 9.1). Unknown fields, missing required fields, wrong types and
 * out-of-range values are rejected with a typed error; nothing is silently coerced. Path scope is
 * canonicalized here, before approval, so a later default cannot change what the user approved.
 */
class AgentBuiltInToolArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        val parsed = when (val result = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> result.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(result.error)
        }
        return try {
            when (descriptor.toolKey) {
                LIST -> normalizeList(parsed)
                READ_TEXT -> normalizeReadText(parsed)
                SEARCH_TEXT -> normalizeSearchText(parsed)
                WRITE_TEXT -> normalizeWriteText(parsed)
                else -> AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
            }
        } catch (failure: FieldFailure) {
            AgentArgumentNormalization.Rejected(failure.error)
        }
    }

    private fun normalizeList(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_PATH, FIELD_LIMIT, FIELD_CURSOR))
        val path = readPath(optionalText(fields, FIELD_PATH) ?: AgentLogicalPaths.INPUTS_ROOT)
        val limit = optionalInteger(fields, FIELD_LIMIT, default = 100L, minimum = 1L, maximum = 200L)
        val cursor = optionalTextOrNull(fields, FIELD_CURSOR, maximumChars = 1_024)
        return normalized(
            linkedMapOf(
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_LIMIT to AgentArgValue.Integer(limit),
                FIELD_CURSOR to (cursor?.let(AgentArgValue::Text) ?: AgentArgValue.Null)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = null
        )
    }

    private fun normalizeReadText(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_PATH, FIELD_OFFSET, FIELD_LIMIT))
        val path = readPath(requiredText(fields, FIELD_PATH))
        val offset = optionalInteger(fields, FIELD_OFFSET, default = 0L, minimum = 0L, maximum = Long.MAX_VALUE)
        val limit = optionalInteger(fields, FIELD_LIMIT, default = 4_096L, minimum = 1L, maximum = 16_384L)
        return normalized(
            linkedMapOf(
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_OFFSET to AgentArgValue.Integer(offset),
                FIELD_LIMIT to AgentArgValue.Integer(limit)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = null
        )
    }

    private fun normalizeSearchText(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_QUERY, FIELD_PATH, FIELD_MAX_MATCHES))
        val query = requiredText(fields, FIELD_QUERY)
        if (query.isEmpty() || query.length > 256) {
            fail("Search query must contain between 1 and 256 characters.")
        }
        if (query.indexOf('\u0000') >= 0) fail("Search query must not contain NUL.")
        val path = readPath(optionalText(fields, FIELD_PATH) ?: AgentLogicalPaths.INPUTS_ROOT)
        val maxMatches = optionalInteger(fields, FIELD_MAX_MATCHES, default = 20L, minimum = 1L, maximum = 100L)
        return normalized(
            linkedMapOf(
                FIELD_QUERY to AgentArgValue.Text(query),
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_MAX_MATCHES to AgentArgValue.Integer(maxMatches)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = null
        )
    }

    private fun normalizeWriteText(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_PATH, FIELD_CONTENT, FIELD_EXPECTED_OLD_HASH))
        val path = writePath(requiredText(fields, FIELD_PATH))
        val content = requiredText(fields, FIELD_CONTENT)
        if (content.toByteArray(Charsets.UTF_8).size > MAX_WRITE_CONTENT_BYTES) {
            fail("Artifact content exceeds the 32 KiB UTF-8 limit.")
        }
        if (!fields.containsKey(FIELD_EXPECTED_OLD_HASH)) {
            fail("Artifact writes must state expectedOldHash explicitly (null means create-only).")
        }
        val expectedOldHash = optionalHash(fields, FIELD_EXPECTED_OLD_HASH)
        return normalized(
            linkedMapOf(
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_CONTENT to AgentArgValue.Text(content),
                FIELD_EXPECTED_OLD_HASH to (expectedOldHash?.let(AgentArgValue::Text) ?: AgentArgValue.Null)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = expectedOldHash
        )
    }

    private fun normalized(
        fields: Map<String, AgentArgValue>,
        logicalPaths: List<String>,
        expectedOldHash: String?
    ): AgentArgumentNormalization.Normalized = AgentArgumentNormalization.Normalized(
        normalizedArgumentsJson = AgentCanonicalJson.writeObject(fields),
        logicalPaths = logicalPaths,
        expectedOldHash = expectedOldHash
    )

    private fun requireOnly(fields: Map<String, AgentArgValue>, allowed: Set<String>) {
        fields.keys.forEach { key ->
            if (key !in allowed) fail("Tool arguments contain a field that is not part of the V1 schema.")
        }
    }

    private fun requiredText(fields: Map<String, AgentArgValue>, key: String): String {
        val value = fields[key] ?: fail("A required tool argument is missing.")
        return (value as? AgentArgValue.Text)?.value ?: fail("A required tool argument has the wrong JSON type.")
    }

    private fun optionalText(fields: Map<String, AgentArgValue>, key: String): String? {
        val value = fields[key] ?: return null
        return (value as? AgentArgValue.Text)?.value ?: fail("A tool argument has the wrong JSON type.")
    }

    private fun optionalTextOrNull(fields: Map<String, AgentArgValue>, key: String, maximumChars: Int): String? {
        val value = fields[key] ?: return null
        if (value == AgentArgValue.Null) return null
        val text = (value as? AgentArgValue.Text)?.value ?: fail("A tool argument has the wrong JSON type.")
        if (text.isEmpty() || text.length > maximumChars) fail("A tool argument string is out of range.")
        return text
    }

    private fun optionalInteger(
        fields: Map<String, AgentArgValue>,
        key: String,
        default: Long,
        minimum: Long,
        maximum: Long
    ): Long {
        val value = fields[key] ?: return default
        val integer = (value as? AgentArgValue.Integer)?.value ?: fail("A tool integer argument has the wrong JSON type.")
        if (integer < minimum || integer > maximum) fail("A tool integer argument is out of range.")
        return integer
    }

    private fun optionalHash(fields: Map<String, AgentArgValue>, key: String): String? {
        val value = fields[key] ?: return null
        if (value == AgentArgValue.Null) return null
        val text = (value as? AgentArgValue.Text)?.value ?: fail("expectedOldHash must be a lowercase SHA-256 string or null.")
        if (!SHA256_HEX.matches(text)) fail("expectedOldHash must be a lowercase SHA-256 string or null.")
        return text
    }

    private fun readPath(raw: String): String = when (val result = AgentLogicalPaths.normalizeReadPath(raw)) {
        is AgentPathResult.Valid -> result.path
        is AgentPathResult.Invalid -> throw FieldFailure(result.error)
    }

    private fun writePath(raw: String): String = when (val result = AgentLogicalPaths.normalizeWritePath(raw)) {
        is AgentPathResult.Valid -> result.path
        is AgentPathResult.Invalid -> throw FieldFailure(result.error)
    }

    private fun fail(detail: String): Nothing = throw FieldFailure(AgentSecurityErrors.invalidArguments(detail))

    private class FieldFailure(val error: AgentSecurityError) : Exception()

    companion object {
        const val LIST = "workspace.list"
        const val READ_TEXT = "workspace.read_text"
        const val SEARCH_TEXT = "workspace.search_text"
        const val WRITE_TEXT = "artifact.write_text"
        const val MAX_WRITE_CONTENT_BYTES = 32 * 1024

        private const val FIELD_PATH = "path"
        private const val FIELD_LIMIT = "limit"
        private const val FIELD_OFFSET = "offset"
        private const val FIELD_CURSOR = "cursor"
        private const val FIELD_QUERY = "query"
        private const val FIELD_MAX_MATCHES = "maxMatches"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_EXPECTED_OLD_HASH = "expectedOldHash"
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
