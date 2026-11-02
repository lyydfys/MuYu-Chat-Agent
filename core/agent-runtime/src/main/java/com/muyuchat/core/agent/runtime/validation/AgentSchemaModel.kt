package com.muyuchat.core.agent.runtime.validation

import java.math.BigDecimal

/**
 * Effective schema model for the exact V1 JSON Schema subset (architecture 6.4).
 *
 * Annotations (`title`, `description`, `examples`, `deprecated`, `default`, root `$schema`/`$id`)
 * are retained in the canonical schema JSON but never change validation semantics; they are not
 * represented here. `default` never fills an argument.
 */
internal sealed interface AgentSchemaNode {
    data class ObjectNode(
        val properties: Map<String, AgentSchemaNode>,
        val required: Set<String>,
        val additionalProperties: Boolean
    ) : AgentSchemaNode

    data class ArrayNode(
        val items: AgentSchemaNode?,
        val minItems: Int?,
        val maxItems: Int?
    ) : AgentSchemaNode

    data class StringNode(
        val enumValues: List<String>?,
        val minLength: Int?,
        val maxLength: Int?,
        val nullable: Boolean
    ) : AgentSchemaNode

    data class NumberNode(
        val integerOnly: Boolean,
        val minimum: BigDecimal?,
        val maximum: BigDecimal?,
        val nullable: Boolean
    ) : AgentSchemaNode

    data class BooleanNode(val nullable: Boolean) : AgentSchemaNode

    data object NullNode : AgentSchemaNode
}

internal sealed interface AgentSchemaCompileResult {
    data class Supported(
        val root: AgentSchemaNode,
        val canonicalSchemaJson: String,
        val schemaHash: String
    ) : AgentSchemaCompileResult

    data class Unsupported(
        val errorCode: String,
        val safeMessage: String
    ) : AgentSchemaCompileResult
}
