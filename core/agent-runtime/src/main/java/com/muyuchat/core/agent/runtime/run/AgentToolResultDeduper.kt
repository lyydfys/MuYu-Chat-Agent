package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.security.MessageDigest

/**
 * Collapses repeated tool observations in one run's transcript.
 *
 * A long agent task re-reads the same file, re-lists the same directory and re-runs the same search
 * many times. Every one of those results is a full document, and the loop sends the whole transcript
 * on every model call, so the fifth re-read of a 12 KiB file costs 12 KiB of context to say nothing
 * new. This pass rewrites the *later* copies into a short pointer at the first one:
 *
 * ```
 * {"deduplicated":true,"duplicateOfStep":1,"omittedChars":12158,
 *  "message":"与第 1 步的结果相同（已省略 12158 字）","artifactIds":["artifact-1"]}
 * ```
 *
 * [FIELD_OMITTED_CHARS] is the character count of the omitted canonical JSON document (the same
 * number the pointer's message shows), not a token estimate.
 *
 * Rules, all load-bearing:
 *  - only [AgentTranscriptEntry.ToolResult] entries are candidates; every other entry (assistant
 *    envelopes, user answers, protocol errors) is returned as the same instance;
 *  - the identity of a result is `(toolKey, sha256(canonical JSON of result))`, so the same tool
 *    returning different bytes is *not* a duplicate, and two different tools returning identical
 *    bytes are not collapsed into each other;
 *  - the **first** occurrence is never modified — it is the copy the model actually needs;
 *  - the entry count never changes and no entry is dropped: a deduplicated result still occupies its
 *    position, so pairing with its assistant envelope and the `callHash`/`providerCallId` pairing a
 *    native provider requires are both preserved;
 *  - machine-readable fields survive on the pointer: `invocationId`, `callHash`, `toolKey` and
 *    `providerCallId` live on the entry itself and are untouched, and the artifact references that
 *    [com.muyuchat.core.agent.runtime.context.AgentContextAssembler] reads back out of the result
 *    (`artifactIds` / `artifactRefs` / `artifacts`) are carried onto the replacement;
 *  - already-rewritten entries are passed through and are **not** registered as first occurrences,
 *    which is what makes [dedupe] idempotent: two duplicates of the same result share one pointer
 *    text, and a second pass must not read that shared text as a new duplication.
 *
 * Failure behaviour: a result whose JSON cannot be canonicalised (duplicate keys, invalid number
 * literal) is left untouched rather than guessed at. Losing a dedup opportunity is recoverable;
 * corrupting a transcript the loop replays is not.
 */
object AgentToolResultDeduper {

    /** Marker written on a pointer entry; also the idempotency test used by the second pass. */
    const val FIELD_DEDUPLICATED = "deduplicated"
    const val FIELD_DUPLICATE_OF_STEP = "duplicateOfStep"
    const val FIELD_OMITTED_CHARS = "omittedChars"
    const val FIELD_MESSAGE = "message"

    /** Field names the assembler reads artifact references from; kept in sync with it. */
    private val ARTIFACT_FIELDS = listOf("artifactIds", "artifactRefs", "artifacts")

    /**
     * Result fields that are small, machine-readable and still meaningful on a pointer. Anything not
     * listed (the bulky `data`, the prose `summary`, …) is deliberately dropped: that is the payload
     * the pointer exists to omit.
     */
    private val CARRIED_FIELDS = listOf(
        "ok",
        "status",
        "errorCode",
        "truncated",
        "fullResultRef",
        "fullResultRetained",
        "fullResultSha256",
        "possibleSideEffects",
        "schemaVersion"
    )

    private val codec = StrictAgentJsonCodec()

    /**
     * Returns [entries] with every repeat [AgentTranscriptEntry.ToolResult] replaced by a pointer to
     * the first result with the same tool key and content. Entry count and ordering are unchanged.
     */
    fun dedupe(entries: List<AgentTranscriptEntry>): List<AgentTranscriptEntry> {
        if (entries.size < 2) return entries
        // hash -> (1-based ordinal of the first tool result with that content, carried fields)
        val firstSeen = LinkedHashMap<String, FirstOccurrence>()
        var toolResultOrdinal = 0
        var changed = false
        val result = ArrayList<AgentTranscriptEntry>(entries.size)
        for (entry in entries) {
            val toolResult = entry as? AgentTranscriptEntry.ToolResult
            if (toolResult == null) {
                result += entry
                continue
            }
            toolResultOrdinal += 1
            val canonical = canonicalOrNull(toolResult.result)
            if (canonical == null) {
                // Unhashable result: keep it verbatim and do not let it shadow later copies.
                result += entry
                continue
            }
            if (isPointer(toolResult.result)) {
                // Already rewritten by an earlier pass; never a duplication source or target.
                result += entry
                continue
            }
            val key = toolResult.toolKey + '\u0000' + sha256Hex(canonical)
            val first = firstSeen[key]
            if (first == null) {
                firstSeen[key] = FirstOccurrence(
                    ordinal = toolResultOrdinal,
                    carried = carryOver(toolResult.result)
                )
                result += entry
                continue
            }
            val omitted = canonical.length
            result += toolResult.copy(
                result = pointerResult(
                    ordinal = first.ordinal,
                    omittedChars = omitted,
                    carried = first.carried
                )
            )
            changed = true
        }
        return if (changed) result else entries
    }

    /** True when [result] is a pointer produced by [dedupe]. */
    fun isPointer(result: AgentJsonValue): Boolean {
        val fields = (result as? AgentJsonValue.Obj)?.fields ?: return false
        return fields.any { (name, value) ->
            name == FIELD_DEDUPLICATED && (value as? AgentJsonValue.Bool)?.value == true
        }
    }

    /** Pointer text, exposed so a UI can explain a collapsed row without re-deriving the wording. */
    fun pointerMessage(ordinal: Int, omittedChars: Int): String =
        "与第 $ordinal 步的结果相同（已省略 $omittedChars 字）"

    private fun pointerResult(
        ordinal: Int,
        omittedChars: Int,
        carried: List<Pair<String, AgentJsonValue>>
    ): AgentJsonValue.Obj {
        val fields = ArrayList<Pair<String, AgentJsonValue>>(carried.size + 4)
        fields += FIELD_DEDUPLICATED to AgentJsonValue.Bool(true)
        fields += FIELD_DUPLICATE_OF_STEP to AgentJsonValue.Num(ordinal.toString())
        fields += FIELD_OMITTED_CHARS to AgentJsonValue.Num(omittedChars.toString())
        fields += FIELD_MESSAGE to AgentJsonValue.Str(pointerMessage(ordinal, omittedChars))
        fields += carried
        return AgentJsonValues.obj(fields)
    }

    /** Artifact references plus the small status fields, in a stable order. */
    private fun carryOver(result: AgentJsonValue): List<Pair<String, AgentJsonValue>> {
        val fields = (result as? AgentJsonValue.Obj)?.fields ?: return emptyList()
        val byName = LinkedHashMap<String, AgentJsonValue>(fields.size)
        fields.forEach { (name, value) -> byName[name] = value }
        val carried = ArrayList<Pair<String, AgentJsonValue>>(8)
        ARTIFACT_FIELDS.forEach { name ->
            val value = byName[name] ?: return@forEach
            if (value is AgentJsonValue.Arr && value.items.isNotEmpty()) carried += name to value
        }
        CARRIED_FIELDS.forEach { name ->
            val value = byName[name] ?: return@forEach
            carried += name to value
        }
        return carried
    }

    /** Canonical JSON, or null when the AST is not serialisable (duplicate keys, bad number). */
    private fun canonicalOrNull(result: AgentJsonValue): String? =
        runCatching { AgentJsonValues.encode(codec, result) }.getOrNull()

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(HEX[value ushr 4])
                append(HEX[value and 0x0F])
            }
        }
    }

    private data class FirstOccurrence(
        val ordinal: Int,
        val carried: List<Pair<String, AgentJsonValue>>
    )

    private const val HEX = "0123456789abcdef"
}
