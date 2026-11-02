package com.muyuchat.core.agent.runtime.validation

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.runtime.AgentSchemaValidation
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues

/**
 * Frozen schemas and code-level defaults for the four built-in V1 tools (architecture 9.1).
 *
 * The schemas use only the supported subset and always set root `additionalProperties=false`.
 * Defaults are applied by the validator as explicit host rules, never by a schema `default`
 * keyword. K08 builds its immutable catalog from [descriptor]/[descriptors] so the schema hash is
 * always the canonical hash produced by [SupportedToolSchemaValidator].
 */
object AgentV1ToolSpecs {
    const val WORKSPACE_LIST = "workspace.list"
    const val WORKSPACE_READ_TEXT = "workspace.read_text"
    const val WORKSPACE_SEARCH_TEXT = "workspace.search_text"
    const val ARTIFACT_WRITE_TEXT = "artifact.write_text"

    const val TOOL_VERSION = "1"
    const val TOOL_TIMEOUT_MILLIS = 30_000L

    val TOOL_KEYS: List<String> = listOf(
        WORKSPACE_LIST,
        WORKSPACE_READ_TEXT,
        WORKSPACE_SEARCH_TEXT,
        ARTIFACT_WRITE_TEXT
    )

    fun schemaJson(toolKey: String): String = schemaJsonOrNull(toolKey)
        ?: throw IllegalArgumentException("Not a built-in V1 tool: '$toolKey'")

    fun schemaJsonOrNull(toolKey: String): String? = when (toolKey) {
        WORKSPACE_LIST -> WORKSPACE_LIST_SCHEMA
        WORKSPACE_READ_TEXT -> WORKSPACE_READ_TEXT_SCHEMA
        WORKSPACE_SEARCH_TEXT -> WORKSPACE_SEARCH_TEXT_SCHEMA
        ARTIFACT_WRITE_TEXT -> ARTIFACT_WRITE_TEXT_SCHEMA
        else -> null
    }

    fun displayName(toolKey: String): String = when (toolKey) {
        WORKSPACE_LIST -> "List workspace files"
        WORKSPACE_READ_TEXT -> "Read workspace text"
        WORKSPACE_SEARCH_TEXT -> "Search workspace text"
        ARTIFACT_WRITE_TEXT -> "Write artifact text"
        else -> toolKey
    }

    fun description(toolKey: String): String = when (toolKey) {
        WORKSPACE_LIST -> "List a bounded, non-recursive directory view of the task workspace."
        WORKSPACE_READ_TEXT -> "Read a bounded UTF-8 byte range of one workspace text file."
        WORKSPACE_SEARCH_TEXT -> "Literal, case-sensitive text search over the task workspace."
        ARTIFACT_WRITE_TEXT -> "Atomically write a text artifact into the task's artifacts/ area."
        else -> ""
    }

    fun sideEffect(toolKey: String): AgentToolSideEffect = when (toolKey) {
        ARTIFACT_WRITE_TEXT -> AgentToolSideEffect.WORKSPACE_WRITE
        else -> AgentToolSideEffect.READ_ONLY
    }

    /** Builds one descriptor with the canonical schema hash checked by [validator]. */
    fun descriptor(
        toolKey: String,
        validator: AgentToolSchemaValidator
    ): AgentToolDescriptor {
        val schema = schemaJson(toolKey)
        val validation = validator.validateToolSchema(schema)
        require(validation is AgentSchemaValidation.Supported) {
            "Built-in tool '$toolKey' schema must be supported: " +
                (validation as AgentSchemaValidation.Unsupported).safeMessage
        }
        return AgentToolDescriptor(
            toolKey = toolKey,
            version = TOOL_VERSION,
            displayName = displayName(toolKey),
            description = description(toolKey),
            schemaJson = schema,
            schemaHash = validation.schemaHash,
            sideEffect = sideEffect(toolKey),
            dataClass = AgentDataClass.PRIVATE,
            timeoutMillis = TOOL_TIMEOUT_MILLIS
        )
    }

    fun descriptors(validator: AgentToolSchemaValidator): List<AgentToolDescriptor> =
        TOOL_KEYS.map { descriptor(it, validator) }

    private val WORKSPACE_LIST_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "path": { "type": "string", "minLength": 1, "maxLength": 512 },
            "limit": { "type": "integer", "minimum": 1, "maximum": 200 },
            "cursor": { "type": ["string", "null"], "maxLength": 512 }
          }
        }
    """.trimIndent()

    private val WORKSPACE_READ_TEXT_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "path": { "type": "string", "minLength": 1, "maxLength": 512 },
            "offset": { "type": "integer", "minimum": 0 },
            "limit": { "type": "integer", "minimum": 1, "maximum": 16384 }
          },
          "required": ["path"]
        }
    """.trimIndent()

    private val WORKSPACE_SEARCH_TEXT_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "query": { "type": "string", "minLength": 1, "maxLength": 256 },
            "path": { "type": "string", "minLength": 1, "maxLength": 512 },
            "maxMatches": { "type": "integer", "minimum": 1, "maximum": 100 }
          },
          "required": ["query"]
        }
    """.trimIndent()

    private val ARTIFACT_WRITE_TEXT_SCHEMA = """
        {
          "type": "object",
          "additionalProperties": false,
          "properties": {
            "path": { "type": "string", "minLength": 1, "maxLength": 512 },
            "content": { "type": "string", "maxLength": 32768 },
            "expectedOldHash": {
              "type": ["string", "null"],
              "minLength": 64,
              "maxLength": 64
            }
          },
          "required": ["path", "content", "expectedOldHash"]
        }
    """.trimIndent()
}

/**
 * Code-level defaults and semantic rules that cannot be expressed by the supported schema subset
 * (UTF-8 byte budgets, logical paths, hash format). Applied before schema validation for absent
 * optional fields only; an explicitly supplied `null` is never replaced by a default.
 */
internal object AgentV1ToolRules {
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    private const val MAX_ARTIFACT_CONTENT_BYTES = 32 * 1024

    fun applyDefaults(toolKey: String, fields: MutableMap<String, AgentJsonValue>) {
        when (toolKey) {
            AgentV1ToolSpecs.WORKSPACE_LIST -> {
                putDefault(fields, "path", AgentJsonValue.Str("inputs/"))
                putDefault(fields, "limit", AgentJsonValue.Num("100"))
                putDefault(fields, "cursor", AgentJsonValue.Null)
            }
            AgentV1ToolSpecs.WORKSPACE_READ_TEXT -> {
                putDefault(fields, "offset", AgentJsonValue.Num("0"))
                putDefault(fields, "limit", AgentJsonValue.Num("4096"))
            }
            AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT -> {
                putDefault(fields, "path", AgentJsonValue.Str("inputs/"))
                putDefault(fields, "maxMatches", AgentJsonValue.Num("20"))
            }
            else -> Unit
        }
    }

    fun validate(toolKey: String, fields: Map<String, AgentJsonValue>): String? = when (toolKey) {
        AgentV1ToolSpecs.WORKSPACE_LIST -> validateList(fields)
        AgentV1ToolSpecs.WORKSPACE_READ_TEXT -> validateRead(fields)
        AgentV1ToolSpecs.WORKSPACE_SEARCH_TEXT -> validateSearch(fields)
        AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT -> validateWrite(fields)
        else -> null
    }

    private fun validateList(fields: Map<String, AgentJsonValue>): String? {
        val path = (fields["path"] as? AgentJsonValue.Str)?.value
            ?: return "path must be a string"
        AgentLogicalPaths.validateDirectory(path)?.let { return it }
        val limit = intValue(fields, "limit") ?: return "limit must be an integer"
        if (limit !in 1..200) return "limit must be between 1 and 200"
        return when (val cursor = fields["cursor"]) {
            null, AgentJsonValue.Null -> null
            is AgentJsonValue.Str ->
                if (cursor.value.length <= 512) null else "cursor must be at most 512 characters"
            else -> "cursor must be a string or null"
        }
    }

    private fun validateRead(fields: Map<String, AgentJsonValue>): String? {
        val path = (fields["path"] as? AgentJsonValue.Str)?.value
            ?: return "path must be a string"
        AgentLogicalPaths.validateFile(path)?.let { return it }
        val offset = intValue(fields, "offset") ?: return "offset must be a byte offset integer"
        if (offset < 0) return "offset must not be negative"
        val limit = intValue(fields, "limit") ?: return "limit must be an integer"
        if (limit !in 1..16384) return "limit must be between 1 and 16384 bytes"
        return null
    }

    private fun validateSearch(fields: Map<String, AgentJsonValue>): String? {
        val query = (fields["query"] as? AgentJsonValue.Str)?.value
            ?: return "query must be a string"
        val queryChars = query.codePointCount(0, query.length)
        if (queryChars !in 1..256) return "query must contain between 1 and 256 characters"
        val path = (fields["path"] as? AgentJsonValue.Str)?.value
            ?: return "path must be a string"
        AgentLogicalPaths.validateDirectory(path)?.let { return it }
        val maxMatches = intValue(fields, "maxMatches") ?: return "maxMatches must be an integer"
        if (maxMatches !in 1..100) return "maxMatches must be between 1 and 100"
        return null
    }

    private fun validateWrite(fields: Map<String, AgentJsonValue>): String? {
        val path = (fields["path"] as? AgentJsonValue.Str)?.value
            ?: return "path must be a string"
        AgentLogicalPaths.validateArtifactTarget(path)?.let { return it }
        val content = (fields["content"] as? AgentJsonValue.Str)?.value
            ?: return "content must be a string"
        if (AgentJsonValues.utf8Size(content) > MAX_ARTIFACT_CONTENT_BYTES) {
            return "content must be at most 32 KiB of UTF-8 text"
        }
        return when (val expected = fields["expectedOldHash"]) {
            null -> "expectedOldHash is required (use null to create a new artifact)"
            AgentJsonValue.Null -> null
            is AgentJsonValue.Str ->
                if (SHA256_HEX.matches(expected.value)) {
                    null
                } else {
                    "expectedOldHash must be a lowercase SHA-256 hex string or null"
                }
            else -> "expectedOldHash must be a string or null"
        }
    }

    private fun putDefault(
        fields: MutableMap<String, AgentJsonValue>,
        key: String,
        value: AgentJsonValue
    ) {
        if (key !in fields) fields[key] = value
    }

    private fun intValue(fields: Map<String, AgentJsonValue>, key: String): Int? {
        val literal = (fields[key] as? AgentJsonValue.Num)?.literal ?: return null
        if (literal.contains('.') || literal.contains('e') || literal.contains('E')) return null
        return literal.toIntOrNull()
    }
}
