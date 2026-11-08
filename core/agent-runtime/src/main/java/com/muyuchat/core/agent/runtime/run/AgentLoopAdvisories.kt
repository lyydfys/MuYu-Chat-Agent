package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

/**
 * Seams between the loop and the two pure advisories ([AgentBudgetAdvisory], [AgentRetryGuard]).
 *
 * Both decisions are already unit-tested in isolation; this file exists so the coordinator only
 * gains one-line calls instead of a branch, a string budget and a transcript scan of its own (the
 * coordinator is at its 800-line ceiling). Everything here is still pure except the two `append`
 * helpers, which write the transcript entry and event the loop must record for a blocked call.
 */

/** Cap on the advisory text appended to a system prompt. */
private const val MAX_ADVISORY_CHARS: Int = 200

/** Lead-in so the model reads the advisory as environment information, not as the user's goal. */
private const val ADVISORY_PREFIX: String = "\n\n[预算提醒] "

/**
 * Appends the budget advisory to [systemPrompt] when pressure is at least [AgentBudgetPressure.ELEVATED].
 *
 * The fragment goes at the **end** of the prompt: the frozen part of the system prompt is hashed for
 * the prefix cache and recorded by the run, so prepending would invalidate both on every step. The
 * total addition is bounded by [MAX_ADVISORY_CHARS] and an empty pressure yields the input string
 * itself, which keeps the no-pressure path allocation-free and byte-identical to before.
 */
internal fun withBudgetAdvisory(systemPrompt: String, pressure: AgentBudgetPressure): String {
    if (!AgentBudgetAdvisory.isAtLeast(pressure, AgentBudgetPressure.ELEVATED)) return systemPrompt
    val text = AgentBudgetAdvisory.advisoryText(pressure)
    if (text.isEmpty()) return systemPrompt
    val bounded = if (text.length <= MAX_ADVISORY_CHARS) text else text.take(MAX_ADVISORY_CHARS - 1) + "…"
    return systemPrompt + ADVISORY_PREFIX + bounded
}

/**
 * Pressure for the step about to run, from the run's own figures.
 *
 * [AgentBudgetAdvisory] needs a token ceiling and a step ceiling. The run budget only bounds total
 * generated tokens and model attempts, so those are the two dimensions used: they are the ones the
 * loop actually stops on, which means the advisory can never warn about a limit that cannot fire.
 */
internal fun budgetPressureOf(
    spent: AgentSpentBudget,
    run: AgentRun,
    stepIndex: Int
): AgentBudgetPressure = AgentBudgetAdvisory.assess(
    spentTokens = spent.generatedTokens.toLong(),
    budget = run.budget.totalGeneratedTokens.toLong(),
    stepIndex = stepIndex,
    maxSteps = run.budget.modelAttempts
)

/**
 * Decides whether this call repeats a failure already in the transcript.
 *
 * The transcript must be consulted *before* the call is frozen: freezing reserves an invocation id,
 * writes `tool.proposed` and charges the budget, so a block after that point would leave a ledger row
 * for a call that never ran. The two hashes the guard accepts are the frozen [callHash] and the
 * canonical hash of the message's arguments; passing both means a repeat is caught whether the prior
 * envelope carried prompted or native arguments.
 */
internal fun shouldBlockRepeatedToolCall(
    codec: com.muyuchat.core.agent.runtime.StrictJsonCodec,
    entries: List<AgentTranscriptEntry>,
    toolKey: String,
    callHash: String,
    argumentsJson: String
): Boolean {
    if (shouldBlockIdenticalRetry(entries, toolKey, callHash)) return true
    val argumentsHash = canonicalArgumentsHashOrNull(argumentsJson, codec) ?: return false
    return shouldBlockIdenticalRetry(entries, toolKey, argumentsHash)
}

/**
 * Records a blocked call as a failed observation and returns the advice text handed to the model.
 *
 * The model must see the block as a normal tool result: it is one `ToolResult` entry paired with the
 * envelope that proposed the call (so the guard's envelope/result pairing stays intact and the next
 * step can see it), carrying [AgentToolResultStatus.NOT_EXECUTED] and the guard's advice as its
 * summary. Nothing is executed, no invocation is frozen and no budget is charged for it.
 */
internal fun recordBlockedRetry(
    entries: MutableList<AgentTranscriptEntry>,
    invocationId: String,
    callHash: String,
    toolKey: String,
    failureReason: String
): String {
    val advice = retryAdvice(toolKey, failureReason)
    entries += AgentTranscriptEntry.ToolResult(
        invocationId = invocationId,
        callHash = callHash,
        toolKey = toolKey,
        result = blockedObservation(advice)
    )
    return advice
}

/** The failure reason the guard is given: the last status/summary the same tool produced. */
internal fun lastFailureReasonFor(entries: List<AgentTranscriptEntry>, toolKey: String): String {
    val previous = entries.asReversed()
        .filterIsInstance<AgentTranscriptEntry.ToolResult>()
        .firstOrNull { it.toolKey == toolKey }
        ?: return "上次调用未成功"
    val fields = (previous.result as? AgentJsonValue.Obj)?.fields?.toMap().orEmpty()
    val summary = (fields["summary"] as? AgentJsonValue.Str)?.value?.trim().orEmpty()
    if (summary.isNotEmpty()) return summary
    val errorCode = (fields["errorCode"] as? AgentJsonValue.Str)?.value?.trim().orEmpty()
    return errorCode.ifEmpty { "上次调用未成功" }
}

/**
 * Observation for a blocked retry, shaped like [toolObservation] so the serializer and the transcript
 * scanner treat it as an ordinary failed tool result.
 */
private fun blockedObservation(advice: String): AgentJsonValue.Obj {
    val fields = ArrayList<Pair<String, AgentJsonValue>>(8)
    fields += "schemaVersion" to AgentJsonValue.Num("1")
    fields += "status" to AgentJsonValue.Str(AgentToolResultStatus.NOT_EXECUTED.name)
    fields += "ok" to AgentJsonValue.Bool(false)
    fields += "summary" to AgentJsonValue.Str(advice)
    fields += "artifactIds" to AgentJsonValue.Arr(emptyList())
    fields += "truncated" to AgentJsonValue.Bool(false)
    fields += "possibleSideEffects" to AgentJsonValue.Bool(false)
    fields += "errorCode" to AgentJsonValue.Str(BLOCKED_ERROR_CODE)
    return AgentJsonValue.Obj(fields)
}

/**
 * Canonical hash of a proposal's arguments, or null when the text is not a JSON object.
 *
 * Null means "cannot prove identity", and the guard then only matches on the call hash; a wrong
 * match would block a legitimate corrected retry, which is worse than a missed duplicate.
 */
private fun canonicalArgumentsHashOrNull(
    argumentsJson: String,
    codec: com.muyuchat.core.agent.runtime.StrictJsonCodec
): String? {
    val parsed = codec.parse(argumentsJson)
    val value = (parsed as? com.muyuchat.core.agent.runtime.AgentJsonParseResult.Parsed)?.value
        ?: return null
    if (value !is AgentJsonValue.Obj) return null
    val canonical = com.muyuchat.core.agent.runtime.protocol.AgentJsonValues.canonical(value)
    return com.muyuchat.core.agent.runtime.protocol.AgentHashes.sha256HexUtf8(codec.write(canonical))
}

/** Stable error code so a UI can label a blocked call without parsing the advice sentence. */
internal const val BLOCKED_ERROR_CODE: String = "DUPLICATE_TOOL_CALL_BLOCKED"
