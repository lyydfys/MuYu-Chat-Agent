package com.muyuchat.core.agent.runtime.validation

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import java.math.BigDecimal

/**
 * Compiles the exact supported JSON Schema subset into [AgentSchemaNode].
 *
 * Anything outside the subset returns a typed Unsupported result; nothing is silently ignored.
 * `$ref` (local or remote) is always rejected, which also makes recursive/cyclic schemas
 * impossible. A bounded node/depth budget protects the host from adversarial schemas.
 */
internal class SupportedSchemaCompiler(private val codec: StrictJsonCodec) {

    fun compile(schemaJson: String): AgentSchemaCompileResult {
        val parsed = codec.parse(schemaJson)
        if (parsed is AgentJsonParseResult.Failed) {
            return unsupported("The tool schema is not strict JSON (${parsed.errorCode}).")
        }
        val rootAst = (parsed as AgentJsonParseResult.Parsed).value as? AgentJsonValue.Obj
            ?: return unsupported("The tool schema root must be a JSON object.")
        val canonicalJson = codec.write(AgentJsonValues.canonical(rootAst))
        return try {
            val state = CompileState()
            val root = compileNode(rootAst, "$", isRoot = true, depth = 1, state = state)
            if (root !is AgentSchemaNode.ObjectNode) {
                return unsupported("The tool schema root must have type 'object'.")
            }
            if (state.rootAdditionalPropertiesRestricted != true) {
                return unsupported("The tool schema root object must set additionalProperties=false.")
            }
            AgentSchemaCompileResult.Supported(
                root = root,
                canonicalSchemaJson = canonicalJson,
                schemaHash = AgentHashes.sha256HexUtf8(canonicalJson)
            )
        } catch (failure: SchemaCompileFailure) {
            unsupported(failure.safeMessage)
        }
    }

    private fun compileNode(
        ast: AgentJsonValue,
        path: String,
        isRoot: Boolean,
        depth: Int,
        state: CompileState
    ): AgentSchemaNode {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw SchemaCompileFailure("The tool schema nesting exceeds $MAX_SCHEMA_DEPTH levels.")
        }
        state.nodeCount += 1
        if (state.nodeCount > MAX_SCHEMA_NODES) {
            throw SchemaCompileFailure("The tool schema is too large (more than $MAX_SCHEMA_NODES nodes).")
        }
        val obj = ast as? AgentJsonValue.Obj
            ?: throw SchemaCompileFailure("A schema at $path must be an object.")
        val fields = obj.fields.toMap()
        for (key in fields.keys) {
            UNSUPPORTED_KEYWORDS[key]?.let { reason ->
                throw SchemaCompileFailure("Unsupported schema keyword '$key' at $path: $reason.")
            }
        }
        validateAnnotations(fields, path, isRoot)
        val typeField = fields["type"]
            ?: throw SchemaCompileFailure("A schema at $path must declare 'type'.")
        val types = parseTypes(typeField)
            ?: throw SchemaCompileFailure(
                "The schema 'type' at $path must be one scalar type or a nullable pair."
            )
        val nullable = "null" in types && types.size == 2
        val primary = types.firstOrNull { it != "null" }
            ?: run {
                if (types != listOf("null")) {
                    throw SchemaCompileFailure("The schema 'type' at $path is invalid.")
                }
                ensureOnly(fields, path, BASE_KEYS)
                return AgentSchemaNode.NullNode
            }
        if (types.size == 2 && primary in NON_NULLABLE_TYPES) {
            throw SchemaCompileFailure("Only scalar types may be nullable at $path.")
        }
        return when (primary) {
            "object" -> {
                if (nullable) throw SchemaCompileFailure("Object schemas cannot be nullable in V1 at $path.")
                compileObject(fields, path, isRoot, depth, state)
            }
            "array" -> {
                if (nullable) throw SchemaCompileFailure("Array schemas cannot be nullable in V1 at $path.")
                compileArray(fields, path, depth, state)
            }
            "string" -> compileString(fields, path, nullable)
            "number" -> compileNumber(fields, path, nullable, integerOnly = false)
            "integer" -> compileNumber(fields, path, nullable, integerOnly = true)
            "boolean" -> {
                ensureOnly(fields, path, BASE_KEYS)
                AgentSchemaNode.BooleanNode(nullable)
            }
            else -> throw SchemaCompileFailure("Unsupported schema type '$primary' at $path.")
        }
    }

    private fun compileObject(
        fields: Map<String, AgentJsonValue>,
        path: String,
        isRoot: Boolean,
        depth: Int,
        state: CompileState
    ): AgentSchemaNode {
        ensureOnly(fields, path, OBJECT_KEYS)
        val additional = when (val value = fields["additionalProperties"]) {
            null -> if (isRoot) {
                throw SchemaCompileFailure("The root object must set additionalProperties=false at $path.")
            } else {
                true
            }
            is AgentJsonValue.Bool -> value.value
            else -> throw SchemaCompileFailure(
                "additionalProperties must be a boolean at $path; schema-valued forms are unsupported."
            )
        }
        if (isRoot && additional) {
            throw SchemaCompileFailure("The root object must set additionalProperties=false at $path.")
        }
        if (isRoot) state.rootAdditionalPropertiesRestricted = !additional
        val propertyNodes = LinkedHashMap<String, AgentSchemaNode>()
        val propertiesAst = fields["properties"]
        if (propertiesAst != null) {
            val properties = propertiesAst as? AgentJsonValue.Obj
                ?: throw SchemaCompileFailure("'properties' must be an object at $path.")
            for ((name, childAst) in properties.fields) {
                if (name.isBlank() || name.length > 128) {
                    throw SchemaCompileFailure(
                        "Property names must be 1..128 non-blank characters at $path."
                    )
                }
                propertyNodes[name] = compileNode(
                    childAst,
                    "$path.properties.$name",
                    isRoot = false,
                    depth = depth + 1,
                    state = state
                )
            }
        }
        val required = LinkedHashSet<String>()
        val requiredAst = fields["required"]
        if (requiredAst != null) {
            val requiredArray = requiredAst as? AgentJsonValue.Arr
                ?: throw SchemaCompileFailure("'required' must be an array at $path.")
            for (item in requiredArray.items) {
                val name = (item as? AgentJsonValue.Str)?.value
                    ?: throw SchemaCompileFailure("'required' must contain only strings at $path.")
                if (!required.add(name)) {
                    throw SchemaCompileFailure("'required' contains duplicate '$name' at $path.")
                }
                if (name !in propertyNodes) {
                    throw SchemaCompileFailure(
                        "'required' names '$name' which is not declared in properties at $path."
                    )
                }
            }
        }
        return AgentSchemaNode.ObjectNode(propertyNodes, required, additional)
    }

    private fun compileArray(
        fields: Map<String, AgentJsonValue>,
        path: String,
        depth: Int,
        state: CompileState
    ): AgentSchemaNode {
        ensureOnly(fields, path, ARRAY_KEYS)
        val items = fields["items"]
        val itemNode = if (items == null) {
            null
        } else {
            compileNode(items, "$path.items", isRoot = false, depth = depth + 1, state = state)
        }
        val minItems = intKeyword(fields, "minItems", path)
        val maxItems = intKeyword(fields, "maxItems", path)
        if (minItems != null && minItems < 0) {
            throw SchemaCompileFailure("'minItems' must not be negative at $path.")
        }
        if (maxItems != null && maxItems < 0) {
            throw SchemaCompileFailure("'maxItems' must not be negative at $path.")
        }
        if (minItems != null && maxItems != null && minItems > maxItems) {
            throw SchemaCompileFailure("'minItems' must not exceed 'maxItems' at $path.")
        }
        return AgentSchemaNode.ArrayNode(itemNode, minItems, maxItems)
    }

    private fun compileString(
        fields: Map<String, AgentJsonValue>,
        path: String,
        nullable: Boolean
    ): AgentSchemaNode {
        ensureOnly(fields, path, STRING_KEYS)
        val minLength = intKeyword(fields, "minLength", path)
        val maxLength = intKeyword(fields, "maxLength", path)
        if (minLength != null && minLength < 0) {
            throw SchemaCompileFailure("'minLength' must not be negative at $path.")
        }
        if (maxLength != null && maxLength < 0) {
            throw SchemaCompileFailure("'maxLength' must not be negative at $path.")
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw SchemaCompileFailure("'minLength' must not exceed 'maxLength' at $path.")
        }
        val enumValues = when (val enumAst = fields["enum"]) {
            null -> null
            is AgentJsonValue.Arr -> {
                if (enumAst.items.isEmpty() || enumAst.items.size > 64) {
                    throw SchemaCompileFailure("'enum' must contain 1..64 values at $path.")
                }
                val values = ArrayList<String>(enumAst.items.size)
                for (item in enumAst.items) {
                    val value = (item as? AgentJsonValue.Str)?.value
                        ?: throw SchemaCompileFailure("V1 'enum' supports string values only at $path.")
                    if (value in values) {
                        throw SchemaCompileFailure("'enum' values must be unique at $path.")
                    }
                    values += value
                }
                values
            }
            else -> throw SchemaCompileFailure("'enum' must be an array at $path.")
        }
        return AgentSchemaNode.StringNode(enumValues, minLength, maxLength, nullable)
    }

    private fun compileNumber(
        fields: Map<String, AgentJsonValue>,
        path: String,
        nullable: Boolean,
        integerOnly: Boolean
    ): AgentSchemaNode {
        ensureOnly(fields, path, NUMBER_KEYS)
        val minimum = decimalKeyword(fields, "minimum", path)
        val maximum = decimalKeyword(fields, "maximum", path)
        if (minimum != null && maximum != null && minimum > maximum) {
            throw SchemaCompileFailure("'minimum' must not exceed 'maximum' at $path.")
        }
        return AgentSchemaNode.NumberNode(integerOnly, minimum, maximum, nullable)
    }

    private fun validateAnnotations(
        fields: Map<String, AgentJsonValue>,
        path: String,
        isRoot: Boolean
    ) {
        fields["title"]?.let {
            if (it !is AgentJsonValue.Str || it.value.length > 256) {
                throw SchemaCompileFailure("'title' must be a string of at most 256 characters at $path.")
            }
        }
        fields["description"]?.let {
            if (it !is AgentJsonValue.Str || it.value.length > 2048) {
                throw SchemaCompileFailure(
                    "'description' must be a string of at most 2048 characters at $path."
                )
            }
        }
        fields["examples"]?.let {
            if (it !is AgentJsonValue.Arr || it.items.size > 8) {
                throw SchemaCompileFailure("'examples' must be an array of at most 8 values at $path.")
            }
        }
        fields["deprecated"]?.let {
            if (it !is AgentJsonValue.Bool) {
                throw SchemaCompileFailure("'deprecated' must be a boolean at $path.")
            }
        }
        for (rootOnly in ROOT_ONLY_KEYS) {
            if (!isRoot && rootOnly in fields) {
                throw SchemaCompileFailure("'$rootOnly' is only allowed on the schema root at $path.")
            }
            if (isRoot) {
                fields[rootOnly]?.let {
                    if (it !is AgentJsonValue.Str || it.value.length > 256) {
                        throw SchemaCompileFailure(
                            "'$rootOnly' must be a string of at most 256 characters at $path."
                        )
                    }
                }
            }
        }
    }

    private fun ensureOnly(
        fields: Map<String, AgentJsonValue>,
        path: String,
        allowed: Set<String>
    ) {
        val extra = fields.keys.firstOrNull { it !in allowed } ?: return
        throw SchemaCompileFailure("Unsupported schema keyword '$extra' at $path.")
    }

    private fun parseTypes(value: AgentJsonValue): List<String>? = when (value) {
        is AgentJsonValue.Str -> listOf(value.value)
        is AgentJsonValue.Arr -> {
            if (value.items.size != 2) return null
            val names = value.items.map { (it as? AgentJsonValue.Str)?.value ?: return null }
            if (names.toSet().size != 2) return null
            if ("null" !in names) return null
            val other = names.first { it != "null" }
            if (other !in SCALAR_TYPES) return null
            names
        }
        else -> null
    }

    private fun intKeyword(
        fields: Map<String, AgentJsonValue>,
        key: String,
        path: String
    ): Int? {
        if (key !in fields) return null
        val literal = (fields[key] as? AgentJsonValue.Num)?.literal
            ?: throw SchemaCompileFailure("'$key' must be a non-negative integer at $path.")
        if (literal.contains('.') || literal.contains('e') || literal.contains('E')) {
            throw SchemaCompileFailure("'$key' must be an integer at $path.")
        }
        return literal.toIntOrNull()
            ?: throw SchemaCompileFailure("'$key' is out of range at $path.")
    }

    private fun decimalKeyword(
        fields: Map<String, AgentJsonValue>,
        key: String,
        path: String
    ): BigDecimal? {
        if (key !in fields) return null
        val literal = (fields[key] as? AgentJsonValue.Num)?.literal
            ?: throw SchemaCompileFailure("'$key' must be a number at $path.")
        return try {
            BigDecimal(literal)
        } catch (_: NumberFormatException) {
            throw SchemaCompileFailure("'$key' must be a finite number at $path.")
        }
    }

    private fun unsupported(message: String): AgentSchemaCompileResult.Unsupported =
        AgentSchemaCompileResult.Unsupported(AgentErrorCodes.POLICY_SCHEMA_UNSUPPORTED, message)

    private class CompileState {
        var nodeCount: Int = 0
        var rootAdditionalPropertiesRestricted: Boolean? = null
    }

    private class SchemaCompileFailure(val safeMessage: String) : RuntimeException(safeMessage)

    companion object {
        const val MAX_SCHEMA_DEPTH: Int = 12
        const val MAX_SCHEMA_NODES: Int = 256

        private val SCALAR_TYPES = setOf("string", "number", "integer", "boolean")
        private val NON_NULLABLE_TYPES = setOf("object", "array")
        private val ROOT_ONLY_KEYS = setOf("\$schema", "\$id")
        private val BASE_KEYS = setOf(
            "type", "title", "description", "examples", "deprecated", "default"
        ) + ROOT_ONLY_KEYS
        private val OBJECT_KEYS = BASE_KEYS + setOf("properties", "required", "additionalProperties")
        private val ARRAY_KEYS = BASE_KEYS + setOf("items", "minItems", "maxItems")
        private val STRING_KEYS = BASE_KEYS + setOf("enum", "minLength", "maxLength")
        private val NUMBER_KEYS = BASE_KEYS + setOf("minimum", "maximum")

        private val UNSUPPORTED_KEYWORDS: Map<String, String> = mapOf(
            "\$ref" to "remote and local references are not supported; recursive schemas are rejected",
            "\$dynamicRef" to "dynamic references are not supported",
            "\$defs" to "reusable definitions are not supported",
            "definitions" to "reusable definitions are not supported",
            "allOf" to "combinators are not supported",
            "anyOf" to "combinators are not supported",
            "oneOf" to "combinators are not supported",
            "not" to "combinators are not supported",
            "if" to "conditional schemas are not supported",
            "then" to "conditional schemas are not supported",
            "else" to "conditional schemas are not supported",
            "patternProperties" to "pattern properties are not supported",
            "propertyNames" to "propertyNames is not supported",
            "dependentSchemas" to "dependent schemas are not supported",
            "dependentRequired" to "dependent required is not supported",
            "const" to "const is not supported; use enum",
            "pattern" to "regular expressions are not supported in V1",
            "format" to "formats are not validated in V1",
            "uniqueItems" to "uniqueItems is not supported",
            "contains" to "contains is not supported",
            "prefixItems" to "tuple arrays are not supported",
            "exclusiveMinimum" to "exclusive bounds are not supported",
            "exclusiveMaximum" to "exclusive bounds are not supported",
            "multipleOf" to "multipleOf is not supported",
            "minProperties" to "minProperties is not supported",
            "maxProperties" to "maxProperties is not supported",
            "contentEncoding" to "content keywords are not supported",
            "contentMediaType" to "content keywords are not supported",
            "unevaluatedProperties" to "unevaluatedProperties is not supported",
            "unevaluatedItems" to "unevaluatedItems is not supported",
            "\$anchor" to "anchors are not supported",
            "\$comment" to "comments are not part of the executable subset"
        )
    }
}
