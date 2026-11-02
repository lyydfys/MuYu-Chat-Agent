package com.muyuchat.core.agent.runtime.protocol

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.io.JsonEOFException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import java.io.StringWriter

/**
 * Strict JSON codec based on Jackson Core's streaming parser only (no databind).
 *
 * Hard rules (architecture 6.3):
 * - exactly one root value, no trailing tokens;
 * - duplicate object keys rejected (`STRICT_DUPLICATE_DETECTION`, decoded as a typed error);
 * - no comments, single quotes, unquoted names, trailing commas, missing values, non-numeric
 *   numbers (NaN/Infinity), leading zeros/plus, or unescaped control characters;
 * - nesting depth <= [AgentContract.MAX_JSON_DEPTH] and UTF-8 input <= 64 KiB;
 * - unpaired surrogates (not representable as UTF-8) and a leading BOM are rejected;
 * - parse failures are returned as typed [AgentJsonParseResult.Failed], never silent fallback.
 *
 * [write] may throw [IllegalArgumentException] for a host-side invalid AST (duplicate keys,
 * invalid number literal, out-of-range depth); that is a programming error, not model input.
 */
class StrictAgentJsonCodec : StrictJsonCodec {

    private val factory: JsonFactory = JsonFactoryBuilder()
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
        .disable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
        .disable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
        .disable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
        .disable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(AgentContract.MAX_JSON_DEPTH)
                .maxStringLength(AgentContract.MAX_CONTROL_RESPONSE_BYTES)
                .maxNameLength(256)
                .maxNumberLength(256)
                .maxDocumentLength(AgentContract.MAX_CONTROL_RESPONSE_BYTES.toLong())
                .build()
        )
        .build()

    override fun parse(text: String): AgentJsonParseResult {
        if (text.length > AgentContract.MAX_CONTROL_RESPONSE_BYTES) {
            return tooLarge()
        }
        if (AgentJsonValues.firstUnpairedSurrogate(text) >= 0) {
            return AgentJsonParseResult.Failed(
                AgentProtocolErrorCodes.UTF8_INVALID,
                "The response contains an unpaired Unicode surrogate and is not valid UTF-8."
            )
        }
        if (text.startsWith('\uFEFF')) {
            return AgentJsonParseResult.Failed(
                AgentProtocolErrorCodes.INVALID_JSON,
                "A leading byte-order mark is not allowed; send plain UTF-8 JSON."
            )
        }
        if (text.isBlank()) {
            return AgentJsonParseResult.Failed(
                AgentProtocolErrorCodes.EMPTY,
                "The response is empty; a complete JSON object is required."
            )
        }
        if (text.toByteArray(Charsets.UTF_8).size > AgentContract.MAX_CONTROL_RESPONSE_BYTES) {
            return tooLarge()
        }
        val parser = factory.createParser(text)
        try {
            val first = parser.nextToken()
                ?: return AgentJsonParseResult.Failed(
                    AgentProtocolErrorCodes.EMPTY,
                    "The response is empty; a complete JSON object is required."
                )
            val value = try {
                readValue(parser, first, 1)
            } catch (failure: StrictJsonFailure) {
                return AgentJsonParseResult.Failed(failure.errorCode, failure.safeMessage)
            }
            val trailing = parser.nextToken()
            if (trailing != null) {
                return AgentJsonParseResult.Failed(
                    AgentProtocolErrorCodes.MULTIPLE_ROOTS,
                    "Only one complete JSON value is allowed; a second root or trailing token was found."
                )
            }
            return AgentJsonParseResult.Parsed(value)
        } catch (eof: JsonEOFException) {
            return AgentJsonParseResult.Failed(
                AgentProtocolErrorCodes.TRUNCATED,
                "The JSON value is incomplete or truncated."
            )
        } catch (constraint: StreamConstraintsException) {
            return AgentJsonParseResult.Failed(
                constraint.errorCode(),
                "The JSON value exceeds a strict size limit (${constraint.message?.take(120)})."
            )
        } catch (parse: JsonParseException) {
            return AgentJsonParseResult.Failed(
                parse.errorCode(),
                parse.safeMessage()
            )
        } catch (io: java.io.IOException) {
            return AgentJsonParseResult.Failed(
                AgentProtocolErrorCodes.INVALID_JSON,
                "The JSON value could not be parsed."
            )
        } finally {
            parser.close()
        }
    }

    override fun write(value: AgentJsonValue): String {
        val out = StringWriter()
        val generator = factory.createGenerator(out)
        try {
            writeValue(generator, value, 1)
        } finally {
            generator.close()
        }
        return out.toString()
    }

    private fun readValue(parser: JsonParser, token: JsonToken, depth: Int): AgentJsonValue {
        if (depth > AgentContract.MAX_JSON_DEPTH) throw StrictJsonFailure(
            AgentProtocolErrorCodes.DEPTH_EXCEEDED,
            "JSON nesting depth exceeds the maximum of ${AgentContract.MAX_JSON_DEPTH}."
        )
        return when (token) {
            JsonToken.START_OBJECT -> {
                val fields = ArrayList<Pair<String, AgentJsonValue>>()
                val names = HashSet<String>()
                while (true) {
                    val next = parser.nextToken() ?: throw StrictJsonFailure(
                        AgentProtocolErrorCodes.TRUNCATED,
                        "The JSON object is incomplete."
                    )
                    if (next == JsonToken.END_OBJECT) break
                    if (next != JsonToken.FIELD_NAME) throw StrictJsonFailure(
                        AgentProtocolErrorCodes.INVALID_JSON,
                        "A JSON object field name was expected."
                    )
                    val name = parser.currentName ?: throw StrictJsonFailure(
                        AgentProtocolErrorCodes.INVALID_JSON,
                        "A JSON object field name was expected."
                    )
                    if (!names.add(name)) throw StrictJsonFailure(
                        AgentProtocolErrorCodes.DUPLICATE_KEY,
                        "Duplicate JSON object key '${name.take(128)}'; each key must appear once."
                    )
                    val valueToken = parser.nextToken() ?: throw StrictJsonFailure(
                        AgentProtocolErrorCodes.TRUNCATED,
                        "The JSON object value is missing."
                    )
                    fields += name to readValue(parser, valueToken, depth + 1)
                }
                AgentJsonValue.Obj(fields)
            }
            JsonToken.START_ARRAY -> {
                val items = ArrayList<AgentJsonValue>()
                while (true) {
                    val next = parser.nextToken() ?: throw StrictJsonFailure(
                        AgentProtocolErrorCodes.TRUNCATED,
                        "The JSON array is incomplete."
                    )
                    if (next == JsonToken.END_ARRAY) break
                    items += readValue(parser, next, depth + 1)
                }
                AgentJsonValue.Arr(items)
            }
            JsonToken.VALUE_STRING -> AgentJsonValue.Str(parser.text)
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> {
                val literal = parser.text
                if (!JSON_NUMBER_LITERAL.matches(literal)) throw StrictJsonFailure(
                    AgentProtocolErrorCodes.INVALID_JSON,
                    "The JSON number is not a plain finite decimal literal."
                )
                AgentJsonValue.Num(literal)
            }
            JsonToken.VALUE_TRUE -> AgentJsonValue.Bool(true)
            JsonToken.VALUE_FALSE -> AgentJsonValue.Bool(false)
            JsonToken.VALUE_NULL -> AgentJsonValue.Null
            else -> throw StrictJsonFailure(
                AgentProtocolErrorCodes.INVALID_JSON,
                "Unexpected JSON token."
            )
        }
    }

    private fun writeValue(generator: JsonGenerator, value: AgentJsonValue, depth: Int) {
        require(depth <= AgentContract.MAX_JSON_DEPTH) {
            "JSON value nesting depth exceeds ${AgentContract.MAX_JSON_DEPTH}"
        }
        when (value) {
            is AgentJsonValue.Obj -> {
                val names = HashSet<String>()
                generator.writeStartObject()
                for ((name, child) in value.fields) {
                    require(names.add(name)) { "JSON object contains duplicate key '$name'" }
                    generator.writeFieldName(name)
                    writeValue(generator, child, depth + 1)
                }
                generator.writeEndObject()
            }
            is AgentJsonValue.Arr -> {
                generator.writeStartArray()
                for (item in value.items) writeValue(generator, item, depth + 1)
                generator.writeEndArray()
            }
            is AgentJsonValue.Str -> {
                require(AgentJsonValues.firstUnpairedSurrogate(value.value) < 0) {
                    "JSON string contains an unpaired surrogate"
                }
                generator.writeString(value.value)
            }
            is AgentJsonValue.Num -> {
                require(JSON_NUMBER_LITERAL.matches(value.literal)) {
                    "Not a valid JSON number literal: '${value.literal}'"
                }
                generator.writeNumber(value.literal)
            }
            is AgentJsonValue.Bool -> generator.writeBoolean(value.value)
            AgentJsonValue.Null -> generator.writeNull()
        }
    }

    private fun tooLarge(): AgentJsonParseResult = AgentJsonParseResult.Failed(
        AgentProtocolErrorCodes.TOO_LARGE,
        "The control response exceeds the 64 KiB UTF-8 limit."
    )

    private class StrictJsonFailure(
        val errorCode: String,
        val safeMessage: String
    ) : RuntimeException(safeMessage)

    private fun StreamConstraintsException.errorCode(): String {
        val text = message.orEmpty().lowercase()
        return when {
            "nesting depth" in text -> AgentProtocolErrorCodes.DEPTH_EXCEEDED
            "string value length" in text || "document length" in text ||
                "token count" in text -> AgentProtocolErrorCodes.TOO_LARGE
            else -> AgentProtocolErrorCodes.INVALID_JSON
        }
    }

    private fun JsonParseException.errorCode(): String {
        val text = message.orEmpty()
        return when {
            text.contains("Duplicate field", ignoreCase = true) ->
                AgentProtocolErrorCodes.DUPLICATE_KEY
            text.contains("Unexpected end-of-input", ignoreCase = true) ->
                AgentProtocolErrorCodes.TRUNCATED
            else -> AgentProtocolErrorCodes.INVALID_JSON
        }
    }

    private fun JsonParseException.safeMessage(): String {
        val text = message.orEmpty()
        return when {
            text.contains("Duplicate field", ignoreCase = true) ->
                "Duplicate JSON object key; each key must appear once."
            text.contains("Unexpected end-of-input", ignoreCase = true) ->
                "The JSON value is incomplete or truncated."
            text.contains("Unexpected character", ignoreCase = true) ->
                "The response is not strict JSON (unexpected character)."
            else -> "The response is not strict JSON."
        }
    }
}
