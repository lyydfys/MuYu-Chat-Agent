package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.json.JSONArray
import org.json.JSONObject

internal data class BuiltInToolSchema(val json: String, val schemaHash: String)

/**
 * The four V1 built-in parameter schemas and the immutable descriptors derived from them.
 *
 * Schemas are built as typed JSON, canonicalised once at construction and validated against the
 * architecture 6.4 supported subset (root object, additionalProperties=false, no remote refs or
 * unsupported keywords). A rejected schema is a construction-time failure, never a silent ignore.
 */
internal object AgentToolSchemas {
    val WORKSPACE_LIST: BuiltInToolSchema = build(listSchema())
    val WORKSPACE_READ_TEXT: BuiltInToolSchema = build(readTextSchema())
    val WORKSPACE_SEARCH_TEXT: BuiltInToolSchema = build(searchTextSchema())
    val ARTIFACT_WRITE_TEXT: BuiltInToolSchema = build(writeTextSchema())

    private fun build(schema: JSONObject): BuiltInToolSchema {
        val json = AgentToolJson.canonical(schema)
        AgentToolSchemaGuard.requireSupported(json)
        return BuiltInToolSchema(json, AgentToolJson.sha256Hex(json))
    }

    private fun listSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "properties",
            JSONObject()
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("limit", integerProperty(minimum = 1, maximum = 200))
                .put("cursor", nullableStringProperty(minLength = 1, maxLength = 1_024))
        )

    private fun readTextSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "required",
            JSONArray().put("path")
        )
        .put(
            "properties",
            JSONObject()
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
                .put("limit", integerProperty(minimum = 1, maximum = 16_384))
        )

    private fun searchTextSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "required",
            JSONArray().put("query")
        )
        .put(
            "properties",
            JSONObject()
                .put("query", stringProperty(minLength = 1, maxLength = 256))
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("maxMatches", integerProperty(minimum = 1, maximum = 100))
        )

    private fun writeTextSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put(
            "required",
            JSONArray().put("content").put("expectedOldHash").put("path")
        )
        .put(
            "properties",
            JSONObject()
                .put("path", stringProperty(minLength = 1, maxLength = 512))
                .put("content", stringProperty(maxLength = 32_768))
                .put("expectedOldHash", nullableStringProperty(minLength = 64, maxLength = 64))
        )

    private fun stringProperty(minLength: Int = 0, maxLength: Int): JSONObject {
        val node = JSONObject().put("type", "string").put("maxLength", maxLength)
        if (minLength > 0) node.put("minLength", minLength)
        return node
    }

    private fun nullableStringProperty(minLength: Int, maxLength: Int): JSONObject = JSONObject()
        .put("type", JSONArray().put("string").put("null"))
        .put("minLength", minLength)
        .put("maxLength", maxLength)

    private fun integerProperty(minimum: Int, maximum: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)
}

/**
 * Construction-time guard for the supported V1 schema subset (architecture 6.4).
 *
 * The runtime schema validator (K02) stays authoritative for model-provided and MCP schemas; this
 * guard only refuses a built-in catalog whose own frozen schema drifts outside the same subset.
 */
internal object AgentToolSchemaGuard {
    const val MAX_SCHEMA_BYTES: Int = 64 * 1024

    private const val MAX_DEPTH = 8
    private const val MAX_PROPERTIES = 64
    private const val MAX_ENUM_VALUES = 64
    private const val MAX_ANNOTATION_CHARS = 512

    private val KEYWORDS = setOf(
        "type", "properties", "required", "additionalProperties",
        "enum", "minLength", "maxLength", "minimum", "maximum",
        "items", "minItems", "maxItems", "title", "description"
    )
    private val TYPES = setOf("object", "string", "number", "integer", "boolean", "null", "array")

    fun requireSupported(schemaJson: String) {
        if (AgentToolJson.utf8Size(schemaJson) > MAX_SCHEMA_BYTES) {
            fail("tool schema exceeds the 64 KiB schema budget")
        }
        val root = try {
            JSONObject(schemaJson)
        } catch (failure: Exception) {
            fail("tool schema is not a JSON object")
        }
        validateNode(root, depth = 0)
        if (root.opt("type") != "object") fail("tool schema root must be an object")
    }

    fun canonicalForm(schemaJson: String): String {
        requireSupported(schemaJson)
        return AgentToolJson.canonical(JSONObject(schemaJson))
    }

    private fun validateNode(node: JSONObject, depth: Int) {
        if (depth > MAX_DEPTH) fail("tool schema nesting exceeds the V1 limit")
        node.keys().asSequence().firstOrNull { it !in KEYWORDS }?.let { keyword ->
            fail("unsupported tool schema keyword: $keyword")
        }
        val types = readTypes(node)
        validateAnnotations(node)
        validateEnum(node)
        validateIntegralKeyword(node, "minLength")
        validateIntegralKeyword(node, "maxLength")
        validateIntegralKeyword(node, "minItems")
        validateIntegralKeyword(node, "maxItems")
        validateNumberKeyword(node, "minimum")
        validateNumberKeyword(node, "maximum")

        if ("object" in types) {
            if (node.opt("additionalProperties") != false) {
                fail("object schema nodes must declare additionalProperties=false")
            }
            val properties = node.opt("properties")
            if (properties !is JSONObject) fail("object schema nodes must declare properties")
            if (properties.length() > MAX_PROPERTIES) fail("tool schema declares too many properties")
            val names = properties.keys().asSequence().toSet()
            val required = node.opt("required")
            if (required != null) {
                if (required !is JSONArray) fail("required must be an array of property names")
                val seen = mutableSetOf<String>()
                for (index in 0 until required.length()) {
                    val name = required.opt(index) as? String
                        ?: fail("required must contain only property names")
                    if (name !in names) fail("required names an undeclared property")
                    if (!seen.add(name)) fail("required repeats a property name")
                }
            }
            for (name in names) {
                val child = properties.opt(name)
                if (child !is JSONObject) fail("property schema must be a JSON object")
                validateNode(child, depth + 1)
            }
        }
        if ("array" in types) {
            val items = node.opt("items")
            if (items !is JSONObject) fail("array schema nodes must declare an object items schema")
            validateNode(items, depth + 1)
        }
    }

    private fun readTypes(node: JSONObject): Set<String> {
        val type = node.opt("type") ?: fail("tool schema node is missing type")
        val values = when (type) {
            is String -> listOf(type)
            is JSONArray -> (0 until type.length()).map { index ->
                type.opt(index) as? String ?: fail("schema type array must contain only strings")
            }
            else -> fail("schema type must be a string or an array of strings")
        }
        if (values.isEmpty()) fail("schema type must not be empty")
        if (values.any { it !in TYPES }) fail("schema type is outside the supported V1 subset")
        if (values.size != values.toSet().size) fail("schema type repeats a value")
        if (values.size > 2) fail("schema type union is limited to one concrete type plus null")
        if (values.size == 2 && "null" !in values) {
            fail("schema type union is limited to one concrete type plus null")
        }
        if ("object" in values && values.size > 1) fail("object schema nodes cannot be nullable")
        return values.toSet()
    }

    private fun validateEnum(node: JSONObject) {
        val enum = node.opt("enum") ?: return
        if (enum !is JSONArray) fail("enum must be an array")
        if (enum.length() == 0 || enum.length() > MAX_ENUM_VALUES) fail("enum size is outside the V1 limit")
        for (index in 0 until enum.length()) {
            if (enum.opt(index) !is String) fail("enum values must be strings")
        }
    }

    private fun validateAnnotations(node: JSONObject) {
        for (key in listOf("title", "description")) {
            val value = node.opt(key) ?: continue
            if (value !is String) fail("$key must be a string")
            if (value.length > MAX_ANNOTATION_CHARS) fail("$key exceeds the annotation budget")
        }
    }

    private fun validateIntegralKeyword(node: JSONObject, keyword: String) {
        val value = node.opt(keyword) ?: return
        val number = value as? Number ?: fail("$keyword must be a number")
        val asLong = number.toLong()
        if (asLong < 0L || asLong.toDouble() != number.toDouble()) {
            fail("$keyword must be a non-negative integer")
        }
    }

    private fun validateNumberKeyword(node: JSONObject, keyword: String) {
        val value = node.opt(keyword) ?: return
        if (value !is Number) fail("$keyword must be a number")
    }

    private fun fail(detail: String): Nothing = throw IllegalArgumentException(detail)
}

internal object AgentToolDescriptors {
    val WORKSPACE_LIST = AgentToolDescriptor(
        toolKey = "workspace.list",
        version = "1",
        displayName = "List Workspace Files",
        description = "List direct children of a task-relative workspace directory. " +
            "Non-recursive; returns logical paths, kind, size and sha256, with cursor pagination.",
        schemaJson = AgentToolSchemas.WORKSPACE_LIST.json,
        schemaHash = AgentToolSchemas.WORKSPACE_LIST.schemaHash,
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    val WORKSPACE_READ_TEXT = AgentToolDescriptor(
        toolKey = "workspace.read_text",
        version = "1",
        displayName = "Read Workspace Text",
        description = "Read a bounded UTF-8 byte range of a workspace text file. " +
            "The offset must fall on a character boundary; returns content, nextOffset, eof, " +
            "totalBytes and sha256. Binary or unknown encodings are unsupported.",
        schemaJson = AgentToolSchemas.WORKSPACE_READ_TEXT.json,
        schemaHash = AgentToolSchemas.WORKSPACE_READ_TEXT.schemaHash,
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    val WORKSPACE_SEARCH_TEXT = AgentToolDescriptor(
        toolKey = "workspace.search_text",
        version = "1",
        displayName = "Search Workspace Text",
        description = "Literal, case-sensitive text search inside a workspace path. " +
            "Returns matches ordered by path then line number with bounded snippets and scan " +
            "progress. Regular expressions are not interpreted.",
        schemaJson = AgentToolSchemas.WORKSPACE_SEARCH_TEXT.json,
        schemaHash = AgentToolSchemas.WORKSPACE_SEARCH_TEXT.schemaHash,
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    val ARTIFACT_WRITE_TEXT = AgentToolDescriptor(
        toolKey = "artifact.write_text",
        version = "1",
        displayName = "Write Workspace Artifact",
        description = "Create or overwrite a UTF-8 text artifact under artifacts/. " +
            "expectedOldHash=null is create-only; overwriting requires the current SHA-256. " +
            "Content is limited to 32 KiB of UTF-8.",
        schemaJson = AgentToolSchemas.ARTIFACT_WRITE_TEXT.json,
        schemaHash = AgentToolSchemas.ARTIFACT_WRITE_TEXT.schemaHash,
        sideEffect = AgentToolSideEffect.WORKSPACE_WRITE,
        dataClass = AgentDataClass.PRIVATE
    )
}
