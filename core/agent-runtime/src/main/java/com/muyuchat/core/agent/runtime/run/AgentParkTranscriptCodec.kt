package com.muyuchat.core.agent.runtime.run

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonFactoryBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.io.JsonEOFException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.JSON_NUMBER_LITERAL
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Strict, lossless JSON form of the authoritative [AgentLoopTranscript] stored inside a durable
 * park token, so a run parked in WAITING_APPROVAL/WAITING_USER can resume after process death.
 *
 * Exact wire shape (one JSON document, object field order fixed by this writer):
 *
 * ```
 * {"entries":[
 *   {"type":"assistant_envelope","rawEnvelope":"...","providerCallId":"..."|null},
 *   {"type":"tool_result","invocationId":"...","callHash":"...","toolKey":"...",
 *    "result":<AgentJsonValue verbatim>,"providerCallId":"..."|null},
 *   {"type":"user_answer","questionId":"...","text":"..."},
 *   {"type":"protocol_error","errorCode":"...","schemaHintJson":"..."}
 * ],"nextStepIndex":N,"repairsUsed":R}
 * ```
 *
 * Rules:
 *  * values are built as [AgentJsonValue] and written by [StrictAgentJsonCodec]; no string
 *    interpolation and no lenient fallback;
 *  * entry order is preserved and the tool-result AST is stored verbatim (including number literal
 *    text such as `1.50`), so `decode(encode(transcript)) == transcript`;
 *  * decoding is fail-closed: non-object root, missing/extra/duplicate fields, unknown entry type,
 *    wrong JSON type, null where a string is required, negative/non-integer counters and an encoded
 *    document larger than [MAX_TRANSCRIPT_CHARS] are all rejected with
 *    [AgentParkTranscriptRejectedException].
 *
 * The bound counts Java characters (UTF-16 code units) of the encoded document.
 */
object AgentParkTranscriptCodec {

    /**
     * Stored-token budget for one encoded transcript. A park token that would exceed it must fail
     * the park (and settle the run) instead of writing a truncated, unresumable transcript.
     */
    const val MAX_TRANSCRIPT_CHARS: Int = 512 * 1024

    private const val TYPE = "type"
    private const val TYPE_ASSISTANT_ENVELOPE = "assistant_envelope"
    private const val TYPE_TOOL_RESULT = "tool_result"
    private const val TYPE_USER_ANSWER = "user_answer"
    private const val TYPE_PROTOCOL_ERROR = "protocol_error"
    private const val TYPE_USER_INTERJECTION = "user_interjection"

    private const val ENCODE_FAILURE =
        "The transcript cannot be written as strict JSON: a value has a duplicate key, an invalid " +
            "number literal, an unpaired surrogate or exceeds the JSON nesting limit."

    private val ROOT_FIELDS: Set<String> = setOf("entries", "nextStepIndex", "repairsUsed")
    private val ASSISTANT_ENVELOPE_FIELDS: Set<String> = setOf(TYPE, "rawEnvelope", "providerCallId")
    private val TOOL_RESULT_FIELDS: Set<String> =
        setOf(TYPE, "invocationId", "callHash", "toolKey", "result", "providerCallId")
    private val USER_ANSWER_FIELDS: Set<String> = setOf(TYPE, "questionId", "text")
    private val PROTOCOL_ERROR_FIELDS: Set<String> = setOf(TYPE, "errorCode", "schemaHintJson")
    private val USER_INTERJECTION_FIELDS: Set<String> = setOf(TYPE, "text", "receivedAtMillis")

    /** Counters are plain non-negative integers; exponent/fraction spellings are not accepted. */
    private val NON_NEGATIVE_INTEGER: Regex = Regex("(0|[1-9][0-9]*)")

    private val strictJson: StrictJsonCodec = StrictAgentJsonCodec()

    /** Encodes [transcript]; deterministic for the same transcript. */
    fun encode(transcript: AgentLoopTranscript): String {
        val encoded = try {
            strictJson.write(transcriptValue(transcript))
        } catch (failure: IllegalArgumentException) {
            throw AgentParkTranscriptRejectedException(ENCODE_FAILURE)
        } catch (failure: java.io.IOException) {
            throw AgentParkTranscriptRejectedException(ENCODE_FAILURE)
        }
        if (encoded.length > MAX_TRANSCRIPT_CHARS) {
            throw AgentParkTranscriptRejectedException(
                "The encoded transcript is ${encoded.length} characters; the limit is " +
                    "$MAX_TRANSCRIPT_CHARS."
            )
        }
        return encoded
    }

    /** Decodes exactly one stored transcript; any deviation from the fixed shape is rejected. */
    fun decode(encoded: String): AgentLoopTranscript {
        if (encoded.length > MAX_TRANSCRIPT_CHARS) {
            throw AgentParkTranscriptRejectedException(
                "The parked transcript is ${encoded.length} characters; the limit is " +
                    "$MAX_TRANSCRIPT_CHARS."
            )
        }
        val root = objectFields(parseDocument(encoded), "The parked transcript")
        requireExactFields(root, ROOT_FIELDS, "The parked transcript")
        val entries = root.getValue("entries") as? AgentJsonValue.Arr
            ?: reject("The 'entries' field must be a JSON array.")
        val nextStepIndex = counter(root, "nextStepIndex")
        val repairsUsed = counter(root, "repairsUsed")
        val decodedEntries = ArrayList<AgentTranscriptEntry>(entries.items.size)
        for ((index, item) in entries.items.withIndex()) {
            decodedEntries += decodeEntry(index, item)
        }
        return AgentLoopTranscript(
            entries = decodedEntries,
            nextStepIndex = nextStepIndex,
            repairsUsed = repairsUsed
        )
    }

    private fun transcriptValue(transcript: AgentLoopTranscript): AgentJsonValue.Obj {
        val entries = ArrayList<AgentJsonValue>(transcript.entries.size)
        for (entry in transcript.entries) entries += entryValue(entry)
        return AgentJsonValue.Obj(
            listOf(
                "entries" to AgentJsonValue.Arr(entries),
                "nextStepIndex" to AgentJsonValue.Num(transcript.nextStepIndex.toString()),
                "repairsUsed" to AgentJsonValue.Num(transcript.repairsUsed.toString())
            )
        )
    }

    private fun entryValue(entry: AgentTranscriptEntry): AgentJsonValue.Obj = when (entry) {
        is AgentTranscriptEntry.AssistantEnvelope -> AgentJsonValue.Obj(
            listOf(
                TYPE to AgentJsonValue.Str(TYPE_ASSISTANT_ENVELOPE),
                "rawEnvelope" to AgentJsonValue.Str(entry.rawEnvelope),
                "providerCallId" to providerCallIdValue(entry.providerCallId)
            )
        )
        is AgentTranscriptEntry.ToolResult -> AgentJsonValue.Obj(
            listOf(
                TYPE to AgentJsonValue.Str(TYPE_TOOL_RESULT),
                "invocationId" to AgentJsonValue.Str(entry.invocationId),
                "callHash" to AgentJsonValue.Str(entry.callHash),
                "toolKey" to AgentJsonValue.Str(entry.toolKey),
                "result" to entry.result,
                "providerCallId" to providerCallIdValue(entry.providerCallId)
            )
        )
        is AgentTranscriptEntry.UserAnswer -> AgentJsonValue.Obj(
            listOf(
                TYPE to AgentJsonValue.Str(TYPE_USER_ANSWER),
                "questionId" to AgentJsonValue.Str(entry.questionId),
                "text" to AgentJsonValue.Str(entry.text)
            )
        )
        is AgentTranscriptEntry.ProtocolError -> AgentJsonValue.Obj(
            listOf(
                TYPE to AgentJsonValue.Str(TYPE_PROTOCOL_ERROR),
                "errorCode" to AgentJsonValue.Str(entry.errorCode),
                "schemaHintJson" to AgentJsonValue.Str(entry.schemaHintJson)
            )
        )
        is AgentTranscriptEntry.UserInterjection -> AgentJsonValue.Obj(
            listOf(
                TYPE to AgentJsonValue.Str(TYPE_USER_INTERJECTION),
                "text" to AgentJsonValue.Str(entry.text),
                "receivedAtMillis" to AgentJsonValue.Num(entry.receivedAtMillis.toString())
            )
        )
    }

    private fun providerCallIdValue(value: String?): AgentJsonValue =
        if (value == null) AgentJsonValue.Null else AgentJsonValue.Str(value)

    private fun decodeEntry(index: Int, value: AgentJsonValue): AgentTranscriptEntry {
        val label = "Transcript entry #$index"
        val fields = objectFields(value, label)
        val type = stringField(fields, TYPE, label)
        return when (type) {
            TYPE_ASSISTANT_ENVELOPE -> {
                requireExactFields(fields, ASSISTANT_ENVELOPE_FIELDS, label)
                AgentTranscriptEntry.AssistantEnvelope(
                    rawEnvelope = stringField(fields, "rawEnvelope", label),
                    providerCallId = optionalStringField(fields, "providerCallId", label)
                )
            }
            TYPE_TOOL_RESULT -> {
                requireExactFields(fields, TOOL_RESULT_FIELDS, label)
                AgentTranscriptEntry.ToolResult(
                    invocationId = stringField(fields, "invocationId", label),
                    callHash = stringField(fields, "callHash", label),
                    toolKey = stringField(fields, "toolKey", label),
                    result = fields.getValue("result"),
                    providerCallId = optionalStringField(fields, "providerCallId", label)
                )
            }
            TYPE_USER_ANSWER -> {
                requireExactFields(fields, USER_ANSWER_FIELDS, label)
                AgentTranscriptEntry.UserAnswer(
                    questionId = stringField(fields, "questionId", label),
                    text = stringField(fields, "text", label)
                )
            }
            TYPE_PROTOCOL_ERROR -> {
                requireExactFields(fields, PROTOCOL_ERROR_FIELDS, label)
                AgentTranscriptEntry.ProtocolError(
                    errorCode = stringField(fields, "errorCode", label),
                    schemaHintJson = stringField(fields, "schemaHintJson", label)
                )
            }
            TYPE_USER_INTERJECTION -> {
                requireExactFields(fields, USER_INTERJECTION_FIELDS, label)
                AgentTranscriptEntry.UserInterjection(
                    text = stringField(fields, "text", label),
                    receivedAtMillis = longCounter(fields, "receivedAtMillis")
                )
            }
            else -> reject(
                "$label has an unknown 'type'; expected one of $TYPE_ASSISTANT_ENVELOPE, " +
                    "$TYPE_TOOL_RESULT, $TYPE_USER_ANSWER, $TYPE_PROTOCOL_ERROR, " +
                    "$TYPE_USER_INTERJECTION."
            )
        }
    }

    private fun parseDocument(encoded: String): AgentJsonValue {
        if (AgentJsonValues.utf8Size(encoded) <= AgentContract.MAX_CONTROL_RESPONSE_BYTES) {
            return when (val parsed = strictJson.parse(encoded)) {
                is AgentJsonParseResult.Parsed -> parsed.value
                is AgentJsonParseResult.Failed -> reject(
                    "The parked transcript is not strict JSON (${parsed.errorCode})."
                )
            }
        }
        // StrictAgentJsonCodec caps documents at the 64 KiB control-response budget; a park token
        // may legitimately be larger, so the same strict grammar is applied with the park budget.
        return ParkDocumentReader.parse(encoded)
    }

    private fun objectFields(value: AgentJsonValue, what: String): Map<String, AgentJsonValue> {
        val obj = value as? AgentJsonValue.Obj ?: reject("$what must be one JSON object.")
        // Duplicate keys are rejected by the strict readers before an AST reaches this point.
        return obj.fields.toMap()
    }

    private fun requireExactFields(
        fields: Map<String, AgentJsonValue>,
        allowed: Set<String>,
        what: String
    ) {
        for (name in allowed) {
            if (name !in fields) reject("$what is missing the required field '$name'.")
        }
        for (name in fields.keys) {
            if (name !in allowed) reject("$what has an unexpected field '${safeName(name)}'.")
        }
    }

    private fun stringField(
        fields: Map<String, AgentJsonValue>,
        name: String,
        what: String
    ): String {
        val value = fields[name] ?: reject("$what is missing the required field '$name'.")
        return (value as? AgentJsonValue.Str)?.value
            ?: reject("$what field '$name' must be a JSON string.")
    }

    private fun optionalStringField(
        fields: Map<String, AgentJsonValue>,
        name: String,
        what: String
    ): String? {
        val value = fields[name] ?: reject("$what is missing the required field '$name'.")
        return when (value) {
            is AgentJsonValue.Str -> value.value
            AgentJsonValue.Null -> null
            else -> reject("$what field '$name' must be a JSON string or null.")
        }
    }

    /**
 * Same validation as [counter] but keeps the full `Long` range.
 *
 * Wall-clock millis exceed `Int.MAX_VALUE`, so a timestamp read through the Int helper would be
 * rejected as "out of range" for every real timestamp — the entry could be written but never read
 * back. Time fields therefore get their own reader rather than a narrowing cast.
 */
private fun longCounter(fields: Map<String, AgentJsonValue>, name: String): Long {
    val value = fields.getValue(name) as? AgentJsonValue.Num
        ?: reject("The '$name' counter must be a JSON number.")
    if (!NON_NEGATIVE_INTEGER.matches(value.literal)) {
        reject("The '$name' counter must be a non-negative plain integer.")
    }
    return value.literal.toLongOrNull()
        ?: reject("The '$name' counter is out of range.")
}

private fun counter(fields: Map<String, AgentJsonValue>, name: String): Int {
        val value = fields.getValue(name) as? AgentJsonValue.Num
            ?: reject("The '$name' counter must be a JSON number.")
        if (!NON_NEGATIVE_INTEGER.matches(value.literal)) {
            reject("The '$name' counter must be a non-negative plain integer.")
        }
        return value.literal.toIntOrNull()
            ?: reject("The '$name' counter is out of range.")
    }

    /** Keeps diagnostics bounded and free of control characters; no payload is echoed. */
    private fun safeName(name: String): String =
        name.take(48).map { if (it.isISOControl()) ' ' else it }.joinToString("")
}

/** The parked transcript is not the exact strict shape this codec writes. */
class AgentParkTranscriptRejectedException(message: String) : IllegalArgumentException(message)

private fun reject(message: String): Nothing =
    throw AgentParkTranscriptRejectedException(message)

/**
 * Strict reader for park documents that exceed the production codec's control-response budget.
 *
 * It mirrors [StrictAgentJsonCodec]: one root value, no trailing tokens, duplicate keys rejected,
 * no comments/single quotes/trailing commas/NaN/leading zeros or control characters, nesting depth
 * <= [AgentContract.MAX_JSON_DEPTH], and the shared [JSON_NUMBER_LITERAL] grammar. Only the size
 * budget differs. Exit condition: delete this reader once the production codec exposes configurable
 * read limits and use it for every document size.
 */
private object ParkDocumentReader {

    private const val MAX_NAME_CHARS = 256
    private const val MAX_NUMBER_CHARS = 256

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
                .maxStringLength(AgentParkTranscriptCodec.MAX_TRANSCRIPT_CHARS)
                .maxNameLength(MAX_NAME_CHARS)
                .maxNumberLength(MAX_NUMBER_CHARS)
                .maxDocumentLength(AgentParkTranscriptCodec.MAX_TRANSCRIPT_CHARS.toLong())
                .build()
        )
        .build()

    fun parse(text: String): AgentJsonValue {
        if (AgentJsonValues.firstUnpairedSurrogate(text) >= 0) {
            reject("The parked transcript contains an unpaired Unicode surrogate.")
        }
        if (text.startsWith('\uFEFF')) {
            reject("The parked transcript starts with a byte-order mark.")
        }
        if (text.isBlank()) reject("The parked transcript is empty.")
        val parser = factory.createParser(text)
        try {
            val first = parser.nextToken() ?: reject("The parked transcript is empty.")
            val value = readValue(parser, first, 1)
            if (parser.nextToken() != null) {
                reject("The parked transcript must be exactly one JSON document.")
            }
            return value
        } catch (eof: JsonEOFException) {
            reject("The parked transcript is truncated.")
        } catch (constraint: StreamConstraintsException) {
            reject("The parked transcript exceeds a strict JSON read limit.")
        } catch (parse: JsonParseException) {
            reject("The parked transcript is not strict JSON.")
        } catch (io: java.io.IOException) {
            reject("The parked transcript could not be read.")
        } finally {
            parser.close()
        }
    }

    private fun readValue(parser: JsonParser, token: JsonToken, depth: Int): AgentJsonValue {
        if (depth > AgentContract.MAX_JSON_DEPTH) {
            reject("The parked transcript exceeds the JSON nesting depth limit.")
        }
        return when (token) {
            JsonToken.START_OBJECT -> {
                val fields = ArrayList<Pair<String, AgentJsonValue>>()
                while (true) {
                    val next = parser.nextToken()
                        ?: reject("The parked transcript object is incomplete.")
                    if (next == JsonToken.END_OBJECT) break
                    if (next != JsonToken.FIELD_NAME) {
                        reject("A JSON object field name was expected.")
                    }
                    val name = parser.currentName ?: reject("A JSON object field name was expected.")
                    val valueToken = parser.nextToken()
                        ?: reject("The parked transcript object value is missing.")
                    fields += name to readValue(parser, valueToken, depth + 1)
                }
                AgentJsonValue.Obj(fields)
            }
            JsonToken.START_ARRAY -> {
                val items = ArrayList<AgentJsonValue>()
                while (true) {
                    val next = parser.nextToken()
                        ?: reject("The parked transcript array is incomplete.")
                    if (next == JsonToken.END_ARRAY) break
                    items += readValue(parser, next, depth + 1)
                }
                AgentJsonValue.Arr(items)
            }
            JsonToken.VALUE_STRING -> AgentJsonValue.Str(parser.text)
            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> {
                val literal = parser.text
                if (!JSON_NUMBER_LITERAL.matches(literal)) {
                    reject("The parked transcript contains a non-canonical JSON number.")
                }
                AgentJsonValue.Num(literal)
            }
            JsonToken.VALUE_TRUE -> AgentJsonValue.Bool(true)
            JsonToken.VALUE_FALSE -> AgentJsonValue.Bool(false)
            JsonToken.VALUE_NULL -> AgentJsonValue.Null
            else -> reject("The parked transcript contains an unexpected JSON token.")
        }
    }
}
