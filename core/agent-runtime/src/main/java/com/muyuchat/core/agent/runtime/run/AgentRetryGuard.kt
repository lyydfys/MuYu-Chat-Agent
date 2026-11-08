package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Stops the loop from re-issuing a tool call that has just failed identically.
 *
 * The failure mode this exists for: a tool returns `FAILED` (bad path, missing argument, denied
 * write), and the next model step re-emits the *same* call with the *same* arguments. The result is
 * a second identical failure, more spent tokens and no progress. The transcript already records the
 * failing observation, so the loop can detect the repeat before paying for it.
 *
 * What counts as identical:
 *  * the same [toolKey], and
 *  * the same argument identity. The authoritative transcript stores a call hash, not an argument
 *    hash, so two independent identities are accepted: the canonical arguments recovered from the
 *    paired assistant envelope, and the frozen call hash the runtime persisted. Either one matching
 *    is proof of the same call, because both are derived from the one accepted envelope.
 *
 * Direction of error is deliberate: when the arguments cannot be recovered (a legacy envelope with
 * no parseable arguments) the guard simply does not match. A missed duplicate costs tokens; a wrong
 * match would block a legitimate retry with corrected arguments, which is worse.
 */
const val AGENT_RETRY_GUARD_WINDOW: Int = 5

/**
 * True when an identical call to the same tool already failed within the last
 * [AGENT_RETRY_GUARD_WINDOW] tool calls.
 *
 * Pure and read-only: no clock, no I/O, no mutation of [entries]. A blank tool key or blank hash is
 * never a match, so a malformed proposal can neither be blocked for the wrong reason nor match a
 * previous call by accident.
 */
fun shouldBlockIdenticalRetry(
    entries: List<AgentTranscriptEntry>,
    toolKey: String,
    argumentsHash: String
): Boolean {
    if (toolKey.isBlank() || argumentsHash.isBlank()) return false
    return recentCalls(entries)
        .takeLast(AGENT_RETRY_GUARD_WINDOW)
        .any { call ->
            call.toolKey == toolKey &&
                call.failed &&
                (call.argumentsHash == argumentsHash || call.callHash == argumentsHash)
        }
}

/**
 * One Chinese sentence telling the model what went wrong and what to do instead.
 *
 * Bounded and control-character free: it is fed back as user text, so an unbounded tool message
 * could crowd the context and a newline could fake a second instruction. [failureReason] is a
 * summary, never a payload; a blank reason still yields a usable sentence.
 */
fun retryAdvice(toolKey: String, failureReason: String): String {
    val tool = toolKey.trim().ifEmpty { "该工具" }
    val reason = failureReason
        .filterNot { it.isISOControl() }
        .trim()
        .replace(WHITESPACE, " ")
        .ifEmpty { "原因未知" }
        .let { if (it.length <= MAX_REASON_CHARS) it else it.take(MAX_REASON_CHARS - 1) + "…" }
    return "上次调用 $tool 失败（$reason），请换一种方式而不是原样重试：修正参数、换一个工具，" +
        "或先说明为什么无法继续。"
}

/** One observed tool call, rebuilt from a paired envelope + result. */
private data class PriorToolCall(
    val toolKey: String,
    val callHash: String,
    val argumentsHash: String?,
    val failed: Boolean
)

/**
 * Pairs every tool result with the assistant envelope that proposed it, in transcript order.
 *
 * Only a result with an immediately preceding envelope can be attributed: an interjection, a user
 * answer or a protocol error between them means the envelope's arguments are not recoverable, and
 * the call is skipped rather than guessed.
 */
private fun recentCalls(entries: List<AgentTranscriptEntry>): List<PriorToolCall> {
    val calls = ArrayList<PriorToolCall>()
    var pending: AgentTranscriptEntry.AssistantEnvelope? = null
    for (entry in entries) {
        when (entry) {
            is AgentTranscriptEntry.AssistantEnvelope -> pending = entry
            is AgentTranscriptEntry.ToolResult -> {
                val envelope = pending
                pending = null
                calls += PriorToolCall(
                    toolKey = entry.toolKey,
                    callHash = entry.callHash,
                    argumentsHash = envelope?.let { argumentsHashOf(it.rawEnvelope) },
                    failed = isFailure(entry.result)
                )
            }
            // Any other entry breaks the envelope/result pair; never attribute stale arguments.
            is AgentTranscriptEntry.UserAnswer,
            is AgentTranscriptEntry.ProtocolError,
            is AgentTranscriptEntry.UserInterjection -> pending = null
        }
    }
    return calls
}

/**
 * A result is a failure unless it says it succeeded.
 *
 * Native results are wrapped (`{"record":..,"result":{...}}`), so the inner observation is the one
 * that carries `status`/`ok`. An unreadable or status-less observation counts as a failure: the
 * guard then at worst blocks an identical repeat of something it could not classify, and the caller
 * still gets a typed message telling the model to change approach.
 */
private fun isFailure(observation: AgentJsonValue): Boolean {
    val fields = (unwrap(observation) as? AgentJsonValue.Obj)?.fields?.toMap().orEmpty()
    val status = (fields["status"] as? AgentJsonValue.Str)?.value
    val ok = (fields["ok"] as? AgentJsonValue.Bool)?.value
    return when {
        ok != null -> !ok
        status != null -> status != STATUS_SUCCEEDED
        else -> true
    }
}

/** Strips the transcript-v2 output wrapper so the observation itself is inspected. */
private fun unwrap(observation: AgentJsonValue): AgentJsonValue {
    val fields = (observation as? AgentJsonValue.Obj)?.fields ?: return observation
    val marker = fields.firstOrNull { it.first == "record" }?.second as? AgentJsonValue.Str
    if (marker?.value != V2_RECORD_MARKER) return observation
    return fields.firstOrNull { it.first == "result" }?.second ?: observation
}

/**
 * Canonical hash of the arguments carried by one accepted assistant envelope.
 *
 * Two shapes are readable: the transcript-v2 call record (`arguments`) and the prompted V1 bridge
 * envelope (`call.arguments`). The value is canonicalized and hashed exactly like the validator
 * hashes a proposal, so a caller passing `validation.argumentsHash` matches when the model repeats
 * its arguments verbatim. Anything unreadable yields null instead of a guess.
 */
private fun argumentsHashOf(rawEnvelope: String): String? {
    val root = (codec.parse(rawEnvelope) as? AgentJsonParseResult.Parsed)?.value as? AgentJsonValue.Obj
        ?: return null
    val fields = root.fields.toMap()
    val direct = fields["arguments"]
    val nested = ((fields["call"] as? AgentJsonValue.Obj)?.fields?.toMap())?.get("arguments")
    val arguments = direct ?: nested ?: return null
    if (arguments !is AgentJsonValue.Obj) return null
    val canonical = AgentJsonValues.canonical(arguments)
    return AgentHashes.sha256HexUtf8(codec.write(canonical))
}

private const val STATUS_SUCCEEDED = "SUCCEEDED"
private const val V2_RECORD_MARKER = "mca.agent.transcript.v2"
private const val MAX_REASON_CHARS = 120

private val WHITESPACE = Regex("\\s+")

/** Strict writer only; no lenient parsing is involved in this decision. */
private val codec: StrictJsonCodec = StrictAgentJsonCodec()
