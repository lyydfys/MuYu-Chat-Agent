package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentBuiltInToolArgumentNormalizer
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentSecurityError
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer

/**
 * Descriptor-aware argument normalizer for `memory.search`.
 *
 * `AgentFrozenCommandFactory` defaults to [AgentBuiltInToolArgumentNormalizer], which owns the
 * four workspace/artifact tools. Production injects this composite normalizer so a
 * `memory.search` proposal can be resolved into canonical arguments *before* the approval is
 * created:
 *
 * - the three schema fields are validated strictly (no coercion, no unknown fields);
 * - `query` is 1..256 chars without ISO control characters;
 * - an optional `spaceId` must be a canonical memory space id; it can only narrow the task's
 *   frozen selection, which [MemorySearchTool] re-checks against the persisted grant;
 * - `maxItems` is defaulted to [AgentMemoryLimits.DEFAULT_MAX_ITEMS] and bounded.
 *
 * Every other tool key delegates to [builtIns], so the existing four tools keep byte-identical
 * normalized arguments and frozen hashes. A frozen memory command carries
 * [AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH] as its resource label because the existing
 * frozen-command/broker contract requires a non-empty logical path list; that label is not a
 * workspace path and never matches an [com.muyuchat.mca.agent.security.AgentWorkspaceReadGrant].
 */
class AgentMemoryToolArgumentNormalizer(
    private val builtIns: AgentToolArgumentNormalizer = AgentBuiltInToolArgumentNormalizer()
) : AgentToolArgumentNormalizer {

    override fun normalize(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String
    ): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentMemoryToolDescriptors.TOOL_KEY &&
            descriptor.toolKey != AgentMemoryToolDescriptors.TOOL_KEY_GET
        ) {
            return builtIns.normalize(descriptor, rawArgumentsJson)
        }
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        return try {
            if (descriptor.toolKey == AgentMemoryToolDescriptors.TOOL_KEY_GET) {
                normalizeGet(fields)
            } else {
                normalizeSearch(fields)
            }
        } catch (failure: FieldFailure) {
            AgentArgumentNormalization.Rejected(failure.error)
        }
    }

    private fun normalizeGet(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_ITEM_ID, FIELD_SPACE_ID, FIELD_CONTENT_HASH), toolKey = "memory.get")
        val itemId = requiredText(fields, FIELD_ITEM_ID, toolKey = "memory.get")
        if (itemId.isEmpty() || itemId.length > AgentMemoryLimits.MAX_MEMORY_ID_CHARS) {
            fail("The 'itemId' argument must contain 1-${AgentMemoryLimits.MAX_MEMORY_ID_CHARS} characters.")
        }
        if (itemId.any { it.isISOControl() }) {
            fail("The 'itemId' argument must not contain control characters.")
        }
        val spaceId = when (val value = fields[FIELD_SPACE_ID]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> value.value
                .takeIf { id -> AgentMemorySelection.isValidSpaceId(id) }
                ?: fail("The 'spaceId' argument is not a valid memory space id.")
            else -> fail("The 'spaceId' argument must be a string or null.")
        }
        val contentHash = when (val value = fields[FIELD_CONTENT_HASH]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> value.value
                .takeIf { hash -> hash.matches(Regex("^[0-9a-f]{64}$")) }
                ?: fail("The 'contentHash' argument must be a lowercase SHA-256 string.")
            else -> fail("The 'contentHash' argument must be a string or null.")
        }
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(
                linkedMapOf(
                    FIELD_ITEM_ID to AgentArgValue.Text(itemId),
                    FIELD_SPACE_ID to (spaceId?.let { AgentArgValue.Text(it) } ?: AgentArgValue.Null),
                    FIELD_CONTENT_HASH to (contentHash?.let { AgentArgValue.Text(it) } ?: AgentArgValue.Null)
                )
            ),
            logicalPaths = listOf(AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH),
            expectedOldHash = null
        )
    }

    private fun normalizeSearch(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_QUERY, FIELD_SPACE_ID, FIELD_MAX_ITEMS))
        val query = requiredText(fields, FIELD_QUERY)
        if (query.isEmpty() || query.length > AgentMemoryLimits.MAX_QUERY_CHARS) {
            fail("The 'query' argument must contain 1-${AgentMemoryLimits.MAX_QUERY_CHARS} characters.")
        }
        if (query.any { it.isISOControl() }) {
            fail("The 'query' argument must not contain control characters.")
        }
        val spaceId = when (val value = fields[FIELD_SPACE_ID]) {
            null, AgentArgValue.Null -> null
            is AgentArgValue.Text -> value.value
                .takeIf { id -> AgentMemorySelection.isValidSpaceId(id) }
                ?: fail("The 'spaceId' argument is not a valid memory space id.")
            else -> fail("The 'spaceId' argument must be a string or null.")
        }
        val maxItems = when (val value = fields[FIELD_MAX_ITEMS]) {
            null -> AgentMemoryLimits.DEFAULT_MAX_ITEMS
            is AgentArgValue.Integer -> value.value
                .takeIf { it in AgentMemoryLimits.MIN_MAX_ITEMS.toLong()..AgentMemoryLimits.MAX_MAX_ITEMS.toLong() }
                ?.toInt()
                ?: fail(
                    "The 'maxItems' argument must be between ${AgentMemoryLimits.MIN_MAX_ITEMS} and " +
                        "${AgentMemoryLimits.MAX_MAX_ITEMS}."
                )
            else -> fail("The 'maxItems' argument must be an integer.")
        }
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(
                linkedMapOf(
                    FIELD_QUERY to AgentArgValue.Text(query),
                    FIELD_SPACE_ID to (spaceId?.let { AgentArgValue.Text(it) } ?: AgentArgValue.Null),
                    FIELD_MAX_ITEMS to AgentArgValue.Integer(maxItems.toLong())
                )
            ),
            logicalPaths = listOf(AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH),
            expectedOldHash = null
        )
    }

    private fun requireOnly(
        fields: Map<String, AgentArgValue>,
        allowed: Set<String>,
        toolKey: String = "memory.search"
    ) {
        if (fields.keys.any { key -> key !in allowed }) {
            // The field name is model-authored; keep the safe message fixed and bounded.
            fail("$toolKey does not accept a field outside its V1 schema.")
        }
    }

    private fun requiredText(
        fields: Map<String, AgentArgValue>,
        key: String,
        toolKey: String = "memory.search"
    ): String =
        when (val value = fields[key]) {
            null -> fail("$toolKey requires a string '$key' argument.")
            is AgentArgValue.Text -> value.value
            else -> fail("The '$key' argument must be a string.")
        }

    private fun fail(detail: String): Nothing = throw FieldFailure(AgentSecurityErrors.invalidArguments(detail))

    private class FieldFailure(val error: AgentSecurityError) : Exception()

    private companion object {
        const val FIELD_QUERY = "query"
        const val FIELD_SPACE_ID = "spaceId"
        const val FIELD_MAX_ITEMS = "maxItems"
        const val FIELD_ITEM_ID = "itemId"
        const val FIELD_CONTENT_HASH = "contentHash"
    }
}
