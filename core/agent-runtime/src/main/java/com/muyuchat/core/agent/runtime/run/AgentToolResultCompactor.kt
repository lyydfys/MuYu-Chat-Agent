package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

/**
 * Context compaction for the authoritative transcript.
 *
 * Why: every step re-serializes the whole transcript, and a 20+ step task with large tool
 * observations (directory listings, file dumps, search hits) grows without bound until the model call
 * no longer fits the window. Compaction shrinks the *content* of old observations while keeping the
 * transcript structurally identical — the same entries, in the same order, with the same pairing, so
 * every existing serializer, pairing validator and park/resume codec keeps working untouched.
 *
 * Rules:
 *  - the newest [recentEntries] entries are never modified;
 *  - outside that window, a [AgentTranscriptEntry.ToolResult] whose rendered size exceeds
 *    [perEntryMaxBytes] is replaced by its head/tail excerpt;
 *  - `AssistantEnvelope`, `UserAnswer`, `UserInterjection` and `ProtocolError` are never compacted:
 *    the envelope is protocol text the model must still be able to parse, and the other three are
 *    user/system statements, not observations;
 *  - if the whole transcript still exceeds [maxBytes], results are compacted oldest-first until it
 *    fits. Nothing is ever dropped: every entry stays in place.
 *
 * Pure: no clock, no I/O, no randomness. Compaction is idempotent — an already-compacted result is
 * far below the per-entry budget, so a second pass returns the same list.
 */
fun compactTranscript(
    entries: List<AgentTranscriptEntry>,
    maxBytes: Int = AGENT_COMPACT_DEFAULT_MAX_BYTES,
    recentEntries: Int = AGENT_COMPACT_DEFAULT_RECENT_ENTRIES,
    perEntryMaxBytes: Int = AGENT_COMPACT_DEFAULT_PER_ENTRY_MAX_BYTES
): List<AgentTranscriptEntry> {
    if (entries.isEmpty()) return entries
    val budget = maxBytes.coerceAtLeast(1)
    val perEntryBudget = perEntryMaxBytes.coerceAtLeast(1)
    // Everything at or after this index is the protected tail.
    val protectedFrom = (entries.size - recentEntries.coerceAtLeast(0)).coerceAtLeast(0)

    val compacted = entries.toMutableList()
    // An entry that already carries a compaction level keeps it: that is what makes a second pass a
    // no-op instead of re-cutting an excerpt that is already small.
    val levelOf = IntArray(entries.size) { index ->
        (entries[index] as? AgentTranscriptEntry.ToolResult)?.let { existingLevel(it.result) } ?: -1
    }

    fun size(): Int = compacted.sumOf { entryBytes(it) }.coerceAtLeast(0)

    // Phase 1: any old observation over the per-entry budget is cut to the documented 200/200 excerpt.
    for (index in 0 until protectedFrom) {
        val entry = compacted[index]
        if (entry !is AgentTranscriptEntry.ToolResult) continue
        if (entryBytes(entry) <= perEntryBudget) continue
        compacted[index] = compactToolResult(entry, AGENT_COMPACT_HEAD_CHARS, AGENT_COMPACT_TAIL_CHARS, 0)
        levelOf[index] = 0
    }

    // Phase 2: the transcript is still too big. Squeeze oldest-first and, when one pass is not enough,
    // squeeze harder instead of leaving the call over budget. An entry is only rewritten when the new
    // level is tighter than the one it already carries, which is what keeps the whole function
    // idempotent: a second run starts at the final level and changes nothing.
    if (size() > budget) {
        COMPACTION_LEVELS.forEachIndexed { level, (head, tail) ->
            if (size() <= budget) return@forEachIndexed
            for (index in 0 until protectedFrom) {
                if (size() <= budget) break
                val entry = compacted[index]
                if (entry !is AgentTranscriptEntry.ToolResult) continue
                if (levelOf[index] >= level) continue
                compacted[index] = compactToolResult(entry, head, tail, level)
                levelOf[index] = level
            }
        }
    }
    return if (compacted == entries) entries else compacted
}

/** True when [entry] is one this module would ever rewrite (only tool observations). */
fun isCompactableEntry(entry: AgentTranscriptEntry): Boolean =
    entry is AgentTranscriptEntry.ToolResult

/**
 * Replaces one observation with `head…tail（共 X 字）`.
 *
 * [headChars]/[tailChars] come from [COMPACTION_LEVELS]: the first pass keeps 200/200 characters and
 * later passes keep less, so a transcript that is still over budget after one pass can actually be
 * brought under it instead of being compacted into a still-oversized shape.
 *
 * `artifactIds`/`artifactRefs`/`artifacts` are carried over unchanged: they are bounded, machine-read
 * identifiers the rest of the runtime resolves files from, so dropping them would lose data while
 * saving almost nothing.
 *
 * Idempotent: [AGENT_COMPACT_ALREADY_MARKED] marks a compacted result, and a marked result is never
 * compacted again — so a second pass neither re-cuts the excerpt nor leaves a second "（共 … 字）".
 */
internal fun compactToolResult(
    entry: AgentTranscriptEntry.ToolResult,
    headChars: Int = AGENT_COMPACT_HEAD_CHARS,
    tailChars: Int = AGENT_COMPACT_TAIL_CHARS,
    level: Int = 0
): AgentTranscriptEntry.ToolResult {
    val full = renderValue(entry.result)
    if (full.length <= headChars + tailChars) return entry
    val head = full.take(headChars)
    val tail = full.takeLast(tailChars)
    val summary = "$head$AGENT_COMPACT_MARKER$tail（共 ${full.length} 字）"
    val artifactIds = extractArtifactIds(entry.result)
    val fields = ArrayList<Pair<String, AgentJsonValue>>(3)
    fields += "summary" to AgentJsonValue.Str(summary)
    fields += COMPACTED_KEY to AgentJsonValue.Num(level.toString())
    if (artifactIds.isNotEmpty()) {
        fields += "artifactIds" to AgentJsonValue.Arr(artifactIds.map { AgentJsonValue.Str(it) })
    }
    // Identity fields stay byte-identical: pairing, dispatch and the park codec key off them.
    return entry.copy(result = AgentJsonValue.Obj(fields))
}

/** Rendered UTF-8 size of one entry; the same text the serializers will put on the wire. */
internal fun entryBytes(entry: AgentTranscriptEntry): Int = when (entry) {
    is AgentTranscriptEntry.AssistantEnvelope -> utf8(entry.rawEnvelope)
    is AgentTranscriptEntry.ToolResult -> utf8(renderValue(entry.result))
    is AgentTranscriptEntry.UserAnswer -> utf8(entry.text)
    is AgentTranscriptEntry.ProtocolError -> utf8(entry.errorCode) + utf8(entry.schemaHintJson)
    is AgentTranscriptEntry.UserInterjection -> utf8(entry.text)
}

private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

/** Deterministic, codec-free rendering used only for measurement and for the excerpt. */
internal fun renderValue(value: AgentJsonValue): String {
    val builder = StringBuilder()
    appendValue(value, builder, depth = 0)
    return builder.toString()
}

private fun appendValue(value: AgentJsonValue, out: StringBuilder, depth: Int) {
    when (value) {
        is AgentJsonValue.Obj -> {
            out.append('{')
            value.fields.forEachIndexed { index, field ->
                if (index > 0) out.append(',')
                out.append('"').append(field.first).append("\":")
                appendValue(field.second, out, depth + 1)
            }
            out.append('}')
        }

        is AgentJsonValue.Arr -> {
            out.append('[')
            value.items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                appendValue(item, out, depth + 1)
            }
            out.append(']')
        }

        is AgentJsonValue.Str -> {
            out.append('"')
            value.value.forEach { char ->
                when (char) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> out.append(char)
                }
            }
            out.append('"')
        }

        is AgentJsonValue.Num -> out.append(value.literal)
        is AgentJsonValue.Bool -> out.append(if (value.value) "true" else "false")
        AgentJsonValue.Null -> out.append("null")
    }
}

/** The level a previously compacted result carries, or -1 when it is still the original observation. */
private fun existingLevel(result: AgentJsonValue): Int {
    val fields = (result as? AgentJsonValue.Obj)?.fields ?: return -1
    val literal = (fields.firstOrNull { it.first == COMPACTED_KEY }?.second as? AgentJsonValue.Num)?.literal
    return literal?.toIntOrNull() ?: -1
}

/** Bounded identifier list a tool result may carry; duplicates and non-strings are ignored. */
private fun extractArtifactIds(result: AgentJsonValue): List<String> {
    val fields = (result as? AgentJsonValue.Obj)?.fields ?: return emptyList()
    val items = ARTIFACT_KEYS
        .firstNotNullOfOrNull { key -> (fields.firstOrNull { it.first == key }?.second as? AgentJsonValue.Arr) }
        ?.items
        ?: return emptyList()
    return items.asSequence()
        .mapNotNull { (it as? AgentJsonValue.Str)?.value }
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_CARRIED_ARTIFACT_IDS)
        .toList()
}

/** Marker placed between the kept head and the kept tail. */
const val AGENT_COMPACT_MARKER: String = "…"

/** Field that makes a compacted observation recognisable on a later pass. */
internal const val COMPACTED_KEY: String = "compacted"

/**
 * Successive (head, tail) character budgets. The first level is the documented 200/200 excerpt; the
 * later levels exist so a transcript that is still over the budget after one pass can actually be
 * brought under it instead of staying oversized. The last level keeps no excerpt at all, only the
 * "how much was there" fact.
 */
internal val COMPACTION_LEVELS: List<Pair<Int, Int>> = listOf(
    AGENT_COMPACT_HEAD_CHARS to AGENT_COMPACT_TAIL_CHARS,
    64 to 64,
    0 to 0
)

/** Characters kept from the start of a compacted observation. */
const val AGENT_COMPACT_HEAD_CHARS: Int = 200

/** Characters kept from the end of a compacted observation. */
const val AGENT_COMPACT_TAIL_CHARS: Int = 200

/** Whole-transcript ceiling before older observations are squeezed. */
const val AGENT_COMPACT_DEFAULT_MAX_BYTES: Int = 32 * 1024

/** How many trailing entries are always left exactly as written. */
const val AGENT_COMPACT_DEFAULT_RECENT_ENTRIES: Int = 4

/** One observation larger than this is condensed even when the transcript total is fine. */
const val AGENT_COMPACT_DEFAULT_PER_ENTRY_MAX_BYTES: Int = 2 * 1024

private val ARTIFACT_KEYS: List<String> = listOf("artifactIds", "artifactRefs", "artifacts")

private const val MAX_CARRIED_ARTIFACT_IDS: Int = 32
