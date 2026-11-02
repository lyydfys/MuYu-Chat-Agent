package com.muyuchat.mca.agent.extensions.mcp

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.io.JsonEOFException
import com.fasterxml.jackson.core.json.JsonReadFeature
import java.io.IOException
import java.io.StringWriter

/** Minimal ordered JSON tree for MCP wire messages (host-controlled shape, untrusted contents). */
sealed interface McpJson {
    data class Obj(val fields: Map<String, McpJson>) : McpJson
    data class Arr(val items: List<McpJson>) : McpJson
    data class Str(val value: String) : McpJson
    data class Num(val literal: String) : McpJson
    data class Bool(val value: Boolean) : McpJson
    data object Null : McpJson

    companion object {
        fun obj(vararg fields: Pair<String, McpJson>): Obj = Obj(linkedMapOf(*fields))
        fun str(value: String): Str = Str(value)
        fun num(value: Long): Num = Num(value.toString())
    }
}

/** Extension helpers; callers must still validate every expected field. */
fun McpJson.asObj(): McpJson.Obj? = this as? McpJson.Obj
fun McpJson.Obj.string(name: String): String? = (fields[name] as? McpJson.Str)?.value
fun McpJson.Obj.objectOrNull(name: String): McpJson.Obj? = fields[name] as? McpJson.Obj
fun McpJson.Obj.arrayOrNull(name: String): McpJson.Arr? = fields[name] as? McpJson.Arr
fun McpJson.Obj.numberOrNull(name: String): McpJson.Num? = fields[name] as? McpJson.Num
fun McpJson.Obj.boolOrNull(name: String): Boolean? = (fields[name] as? McpJson.Bool)?.value

sealed interface McpJsonParseResult {
    data class Parsed(val value: McpJson) : McpJsonParseResult
    data class Failed(val code: String, val message: String) : McpJsonParseResult
}

/**
 * Strict JSON codec for MCP wire messages.
 *
 * This is a protocol boundary with its own bounds (256 KiB message, depth 32) because MCP
 * `tools/list` pages legitimately exceed the 64 KiB control-response cap. It uses Jackson Core
 * streaming only: no databind, no reflection, duplicate keys rejected, one root only, and no
 * lenient feature is enabled. It never returns a partially parsed value.
 */
class McpJsonCodec(
    private val maxBytes: Int = McpProtocol.MAX_MESSAGE_BYTES,
    private val maxDepth: Int = McpProtocol.MAX_JSON_DEPTH,
    private val maxStringLength: Int = McpProtocol.MAX_MESSAGE_BYTES
) {
    private val factory: JsonFactory = JsonFactoryBuilderHolder.create(maxDepth, maxStringLength)

    fun parse(text: String): McpJsonParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            return McpJsonParseResult.Failed("mcp_message_too_large", "The MCP message exceeds the byte limit.")
        }
        if (text.isBlank()) {
            return McpJsonParseResult.Failed("mcp_json_invalid", "The MCP message is empty.")
        }
        if (text.startsWith('\uFEFF')) {
            return McpJsonParseResult.Failed("mcp_json_invalid", "A leading byte-order mark is not allowed.")
        }
        return try {
            factory.createParser(text).use { parser ->
                if (parser.nextToken() == null) {
                    return McpJsonParseResult.Failed("mcp_json_incomplete", "The MCP message has no JSON value.")
                }
                val value = readValue(parser, 0)
                if (parser.nextToken() != null) {
                    McpJsonParseResult.Failed("mcp_json_trailing", "The MCP message contains trailing content.")
                } else {
                    McpJsonParseResult.Parsed(value)
                }
            }
        } catch (constraint: StreamConstraintsException) {
            McpJsonParseResult.Failed("mcp_json_limits", "The MCP message exceeds the parser limits.")
        } catch (eof: JsonEOFException) {
            McpJsonParseResult.Failed("mcp_json_incomplete", "The MCP message ended before the JSON value was complete.")
        } catch (parse: JsonParseException) {
            val message = parse.message.orEmpty()
            val code = if (message.contains("Duplicate", ignoreCase = true)) {
                "mcp_json_duplicate_key"
            } else {
                "mcp_json_invalid"
            }
            McpJsonParseResult.Failed(code, "The MCP message is not valid strict JSON.")
        } catch (failure: IOException) {
            McpJsonParseResult.Failed("mcp_json_invalid", "The MCP message could not be parsed.")
        } catch (failure: RuntimeException) {
            McpJsonParseResult.Failed("mcp_json_invalid", "The MCP message could not be parsed.")
        }
    }

    fun write(value: McpJson): String {
        val out = StringWriter()
        factory.createGenerator(out).use { generator -> writeValue(generator, value) }
        return out.toString()
    }

    private fun readValue(parser: JsonParser, depth: Int): McpJson {
        if (depth > maxDepth) throw StreamConstraintsException("MCP JSON nesting exceeds $maxDepth")
        return when (parser.currentToken()) {
            JsonToken.START_OBJECT -> {
                val fields = linkedMapOf<String, McpJson>()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) {
                        throw JsonParseException(parser, "Expected an object field name.")
                    }
                    val name = parser.currentName()
                    parser.nextToken()
                    if (fields.put(name, readValue(parser, depth + 1)) != null) {
                        throw JsonParseException(parser, "Duplicate field '$name'.")
                    }
                }
                McpJson.Obj(fields)
            }
            JsonToken.START_ARRAY -> {
                val items = ArrayList<McpJson>()
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    items += readValue(parser, depth + 1)
                }
                McpJson.Arr(items)
            }
            JsonToken.VALUE_STRING -> McpJson.Str(parser.text)
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> McpJson.Num(parser.text)
            JsonToken.VALUE_TRUE -> McpJson.Bool(true)
            JsonToken.VALUE_FALSE -> McpJson.Bool(false)
            JsonToken.VALUE_NULL -> McpJson.Null
            else -> throw JsonParseException(parser, "Unsupported JSON token: ${parser.currentToken()}.")
        }
    }

    private fun writeValue(generator: JsonGenerator, value: McpJson) {
        when (value) {
            is McpJson.Obj -> {
                generator.writeStartObject()
                value.fields.forEach { (key, child) ->
                    generator.writeFieldName(key)
                    writeValue(generator, child)
                }
                generator.writeEndObject()
            }
            is McpJson.Arr -> {
                generator.writeStartArray()
                value.items.forEach { writeValue(generator, it) }
                generator.writeEndArray()
            }
            is McpJson.Str -> generator.writeString(value.value)
            is McpJson.Num -> generator.writeNumber(value.literal)
            is McpJson.Bool -> generator.writeBoolean(value.value)
            McpJson.Null -> generator.writeNull()
        }
    }

    /** The parser factory is immutable and thread-safe; constructing it once per codec is enough. */
    private object JsonFactoryBuilderHolder {
        fun create(maxDepth: Int, maxStringLength: Int): JsonFactory =
            com.fasterxml.jackson.core.JsonFactoryBuilder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .disable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonReadFeature.ALLOW_MISSING_VALUES)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .disable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .disable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .streamReadConstraints(
                    StreamReadConstraints.builder()
                        .maxNestingDepth(maxDepth)
                        .maxStringLength(maxStringLength)
                        .maxNameLength(McpProtocol.MAX_NAME_LENGTH)
                        .maxNumberLength(256)
                        .maxDocumentLength(McpProtocol.MAX_MESSAGE_BYTES.toLong())
                        .build()
                )
                .build()
    }
}
