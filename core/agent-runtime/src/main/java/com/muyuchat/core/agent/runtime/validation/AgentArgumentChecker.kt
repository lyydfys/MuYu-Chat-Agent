package com.muyuchat.core.agent.runtime.validation

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import java.math.BigDecimal

/**
 * Validates parsed tool arguments against the compiled schema subset.
 *
 * The checker receives an already strict-parsed AST and returns `null` when valid, otherwise one
 * bounded human/safe message with the JSON path. It never mutates or fills values: V1 defaults are
 * applied explicitly by [AgentV1ToolRules] before this check.
 */
internal class AgentArgumentChecker(private val codec: StrictJsonCodec) {

    fun check(node: AgentSchemaNode, value: AgentJsonValue, path: String): String? = when (node) {
        is AgentSchemaNode.ObjectNode -> checkObject(node, value, path)
        is AgentSchemaNode.ArrayNode -> checkArray(node, value, path)
        is AgentSchemaNode.StringNode -> checkString(node, value, path)
        is AgentSchemaNode.NumberNode -> checkNumber(node, value, path)
        is AgentSchemaNode.BooleanNode -> checkBoolean(node, value, path)
        AgentSchemaNode.NullNode -> if (value is AgentJsonValue.Null) null else "$path must be null"
    }

    private fun checkObject(
        node: AgentSchemaNode.ObjectNode,
        value: AgentJsonValue,
        path: String
    ): String? {
        val obj = value as? AgentJsonValue.Obj
            ?: return "$path must be a JSON object"
        val fields = obj.fields.toMap()
        for (required in node.required) {
            if (required !in fields) return "$path is missing required argument '$required'"
        }
        for ((name, child) in obj.fields) {
            val schema = node.properties[name]
            if (schema == null) {
                if (!node.additionalProperties) {
                    return "$path contains unexpected argument '${safe(name)}'"
                }
                continue
            }
            val failure = check(schema, child, childPath(path, name))
            if (failure != null) return failure
        }
        return null
    }

    private fun checkArray(
        node: AgentSchemaNode.ArrayNode,
        value: AgentJsonValue,
        path: String
    ): String? {
        val arr = value as? AgentJsonValue.Arr
            ?: return "$path must be a JSON array"
        if (node.minItems != null && arr.items.size < node.minItems) {
            return "$path must contain at least ${node.minItems} items"
        }
        if (node.maxItems != null && arr.items.size > node.maxItems) {
            return "$path must contain at most ${node.maxItems} items"
        }
        val items = node.items ?: return null
        arr.items.forEachIndexed { index, item ->
            val failure = check(items, item, "$path[$index]")
            if (failure != null) return failure
        }
        return null
    }

    private fun checkString(
        node: AgentSchemaNode.StringNode,
        value: AgentJsonValue,
        path: String
    ): String? {
        if (value is AgentJsonValue.Null) {
            return if (node.nullable) null else "$path must be a string"
        }
        val text = (value as? AgentJsonValue.Str)?.value ?: return "$path must be a string"
        val bytes = AgentJsonValues.utf8Size(text)
        if (bytes > AgentContract.MAX_PARAMETER_STRING_BYTES) {
            return "$path exceeds the 32 KiB UTF-8 parameter limit"
        }
        val length = text.codePointCount(0, text.length)
        if (node.minLength != null && length < node.minLength) {
            return "$path must contain at least ${node.minLength} characters"
        }
        if (node.maxLength != null && length > node.maxLength) {
            return "$path must contain at most ${node.maxLength} characters"
        }
        if (node.enumValues != null && text !in node.enumValues) {
            return "$path must be one of the declared enum values"
        }
        return null
    }

    private fun checkNumber(
        node: AgentSchemaNode.NumberNode,
        value: AgentJsonValue,
        path: String
    ): String? {
        if (value is AgentJsonValue.Null) {
            return if (node.nullable) null else "$path must be a finite number"
        }
        val literal = (value as? AgentJsonValue.Num)?.literal
            ?: return "$path must be a finite number"
        val number = try {
            BigDecimal(literal)
        } catch (_: NumberFormatException) {
            return "$path must be a finite number"
        }
        if (node.integerOnly &&
            (literal.contains('.') || literal.contains('e') || literal.contains('E'))
        ) {
            return "$path must be an integer"
        }
        if (node.minimum != null && number < node.minimum) {
            return "$path must be at least ${node.minimum.toPlainString()}"
        }
        if (node.maximum != null && number > node.maximum) {
            return "$path must be at most ${node.maximum.toPlainString()}"
        }
        return null
    }

    private fun checkBoolean(
        node: AgentSchemaNode.BooleanNode,
        value: AgentJsonValue,
        path: String
    ): String? {
        if (value is AgentJsonValue.Null) {
            return if (node.nullable) null else "$path must be a boolean"
        }
        return if (value is AgentJsonValue.Bool) null else "$path must be a boolean"
    }

    private fun childPath(parent: String, name: String): String {
        val candidate = "$parent.$name"
        return if (candidate.length <= MAX_MESSAGE_PATH) candidate else "$parent.<field>"
    }

    private fun safe(name: String): String = name
        .take(64)
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")

    companion object {
        private const val MAX_MESSAGE_PATH = 128
    }
}
