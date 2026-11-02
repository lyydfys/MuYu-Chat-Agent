package com.muyuchat.core.agent.runtime.context

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentPromptedTranscriptContext
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.AgentTranscriptSerialization
import com.muyuchat.core.agent.runtime.AgentTranscriptSerializer
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Input for one complete PROMPTED_V1 model request. Only the Agent transcript and the frozen
 * system prompt are accepted: there is no chat-history, other-task or page-state channel.
 */
data class AgentContextAssemblyRequest(
    val systemPrompt: String,
    val userGoal: String,
    val entries: List<AgentTranscriptEntry>,
    val contextWindowTokens: Int?,
    val reservedOutputTokens: Int,
    val toolResultToModelBytes: Int = AgentContract.MAX_TOOL_RESULT_TO_MODEL_BYTES,
    /** NATIVE selects the provider-item serializer (N05c); prompted modes keep the V1 mapping. */
    val protocolMode: com.muyuchat.core.agent.contract.AgentProtocolMode =
        com.muyuchat.core.agent.contract.AgentProtocolMode.PROMPTED_V1
) {
    init {
        require(systemPrompt.isNotBlank()) { "systemPrompt must not be blank" }
        require(userGoal.isNotBlank()) { "userGoal must not be blank" }
        require(reservedOutputTokens >= 0) { "reservedOutputTokens must not be negative" }
        require(toolResultToModelBytes in 1..AgentContract.MAX_TOOL_RESULT_PERSIST_BYTES)
    }
}

sealed interface AgentContextAssemblyResult {
    data class Ready(
        val context: AgentPromptedTranscriptContext,
        val serialization: AgentTranscriptSerialization,
        val estimatedTokens: Int,
        val truncatedToolResults: Int
    ) : AgentContextAssemblyResult

    data class Overflow(
        val error: AgentError,
        val estimatedTokens: Int
    ) : AgentContextAssemblyResult

    data class InvalidHistory(
        val error: AgentError,
        val safeMessage: String
    ) : AgentContextAssemblyResult
}

/**
 * Builds the full paired transcript for the next model call.
 *
 * - The transcript must be paired before serialization: an accepted assistant envelope needs
 *   exactly one tool result or user answer, tool invocation ids are unique, and an unpaired turn
 *   is an invariant failure, never silently dropped.
 * - Tool results larger than the 16 KiB to-model budget are replaced with a bounded excerpt plus
 *   `truncated=true`, the full-result hash/size and any artifact references. Nothing is silently
 *   removed from history.
 * - When the estimated token count cannot fit the frozen context window after reserving output,
 *   the result is `Overflow` and the caller must pause with CONTEXT_LIMIT; no compression happens
 *   here.
 */
class AgentContextAssembler(
    private val serializer: AgentTranscriptSerializer,
    private val nativeSerializer: AgentTranscriptSerializer? = null,
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) {

    fun assemble(request: AgentContextAssemblyRequest): AgentContextAssemblyResult {
        validatePairing(request.entries)?.let { reason ->
            return AgentContextAssemblyResult.InvalidHistory(
                error = integrityError(reason),
                safeMessage = reason
            )
        }
        val activeSerializer = if (request.protocolMode ==
            com.muyuchat.core.agent.contract.AgentProtocolMode.NATIVE
        ) {
            nativeSerializer ?: return AgentContextAssemblyResult.InvalidHistory(
                error = integrityError("native transcript serializer is not configured"),
                safeMessage = "The native transcript serializer is not configured."
            )
        } else {
            serializer
        }
        var truncated = 0
        val projected = request.entries.map { entry ->
            if (entry is AgentTranscriptEntry.ToolResult) {
                val resultJson = codec.write(AgentJsonValues.canonical(entry.result))
                val totalBytes = resultJson.toByteArray(Charsets.UTF_8).size
                if (totalBytes > request.toolResultToModelBytes) {
                    truncated += 1
                    entry.copy(
                        result = truncatedResult(
                            resultJson = resultJson,
                            totalBytes = totalBytes,
                            limitBytes = request.toolResultToModelBytes
                        )
                    )
                } else {
                    entry
                }
            } else {
                entry
            }
        }
        val context = AgentPromptedTranscriptContext(
            systemPrompt = request.systemPrompt,
            userGoal = request.userGoal,
            entries = projected
        )
        val serialization = activeSerializer.serialize(context)
        val estimatedTokens = estimateTokens(request.systemPrompt, serialization)
        val window = request.contextWindowTokens
        if (window != null && estimatedTokens + request.reservedOutputTokens > window) {
            return AgentContextAssemblyResult.Overflow(
                error = AgentError(
                    code = AgentErrorCodes.MODEL_CONTEXT_LIMIT,
                    category = AgentErrorCategory.MODEL,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "The paired transcript does not fit the model context window.",
                    correlationId = CORRELATION_ID
                ),
                estimatedTokens = estimatedTokens
            )
        }
        return AgentContextAssemblyResult.Ready(
            context = context,
            serialization = serialization,
            estimatedTokens = estimatedTokens,
            truncatedToolResults = truncated
        )
    }

    /** Returns a safe reason when the transcript is not perfectly paired, otherwise null. */
    private fun validatePairing(entries: List<AgentTranscriptEntry>): String? {
        var pendingAssistant = false
        val invocationIds = HashSet<String>()
        for (entry in entries) {
            when (entry) {
                is AgentTranscriptEntry.AssistantEnvelope -> {
                    if (pendingAssistant) return "Two assistant envelopes without a paired observation."
                    pendingAssistant = true
                }
                is AgentTranscriptEntry.ToolResult -> {
                    if (!pendingAssistant) return "A tool result has no preceding assistant envelope."
                    if (!AgentContract.isValidId(entry.invocationId)) {
                        return "A tool result has an invalid invocation id."
                    }
                    if (!invocationIds.add(entry.invocationId)) {
                        return "Two tool results share the invocation id '${entry.invocationId}'."
                    }
                    if (entry.callHash.isBlank()) return "A tool result is missing its call hash."
                    pendingAssistant = false
                }
                is AgentTranscriptEntry.UserAnswer -> {
                    if (!pendingAssistant) return "A user answer has no preceding assistant question."
                    if (!AgentContract.isValidId(entry.questionId)) {
                        return "A user answer has an invalid question id."
                    }
                    pendingAssistant = false
                }
                is AgentTranscriptEntry.ProtocolError -> {
                    if (pendingAssistant) {
                        return "A protocol error interrupted an accepted envelope before its result."
                    }
                    if (entry.errorCode.isBlank()) return "A protocol error record is missing its code."
                }
            }
        }
        if (pendingAssistant) return "The transcript ends with an unpaired assistant envelope."
        return null
    }

    private fun truncatedResult(
        resultJson: String,
        totalBytes: Int,
        limitBytes: Int
    ): AgentJsonValue {
        val refs = extractArtifactRefs(resultJson)
        val fullHash = com.muyuchat.core.agent.runtime.protocol.AgentHashes.sha256HexUtf8(resultJson)
        fun skeleton(excerpt: String): AgentJsonValue.Obj = AgentJsonValue.Obj(
            listOf(
                "truncated" to AgentJsonValue.Bool(true),
                "totalBytes" to AgentJsonValue.Num(totalBytes.toString()),
                "fullResultSha256" to AgentJsonValue.Str(fullHash),
                "artifactRefs" to AgentJsonValue.Arr(refs.map { AgentJsonValue.Str(it) }),
                "excerpt" to AgentJsonValue.Str(excerpt)
            )
        )
        val overhead = codec.write(AgentJsonValues.canonical(skeleton(""))).toByteArray(Charsets.UTF_8).size
        val excerptBudget = (limitBytes - overhead - ENVELOPE_RESERVE_BYTES).coerceAtLeast(0)
        return skeleton(utf8Prefix(resultJson, excerptBudget))
    }

    private fun extractArtifactRefs(resultJson: String): List<String> {
        val parsed = codec.parse(resultJson)
        val root = (parsed as? com.muyuchat.core.agent.runtime.AgentJsonParseResult.Parsed)
            ?.value as? AgentJsonValue.Obj ?: return emptyList()
        val fields = root.fields.toMap()
        val candidates = listOf("artifactIds", "artifactRefs", "artifacts")
            .firstNotNullOfOrNull { key -> (fields[key] as? AgentJsonValue.Arr)?.items }
            ?: return emptyList()
        return candidates.mapNotNull { (it as? AgentJsonValue.Str)?.value }
            .filter { AgentContract.isValidId(it) }
            .distinct()
            .take(AgentContract.MAX_FINAL_ARTIFACTS)
    }

    private fun estimateTokens(
        systemPrompt: String,
        serialization: AgentTranscriptSerialization
    ): Int {
        var tokens = estimateTextTokens(systemPrompt)
        for (message in serialization.messages) {
            tokens += estimateTextTokens(message.content) + MESSAGE_OVERHEAD_TOKENS
        }
        return tokens
    }

    /**
     * Conservative estimate without a model tokenizer: ceil(UTF-8 bytes / 3) + 1, which
     * overestimates English and roughly matches CJK text. Callers surface it as estimated.
     */
    private fun estimateTextTokens(text: String): Int =
        (text.toByteArray(Charsets.UTF_8).size + 2) / 3 + 1

    private fun utf8Prefix(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var used = 0
        var index = 0
        val builder = StringBuilder()
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val charCount = Character.charCount(codePoint)
            val byteCount = utf8Length(codePoint)
            if (used + byteCount > maxBytes) break
            builder.appendCodePoint(codePoint)
            used += byteCount
            index += charCount
        }
        return builder.toString()
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private fun integrityError(reason: String): AgentError = AgentError(
        code = AgentErrorCodes.INTERNAL_INVARIANT,
        category = AgentErrorCategory.INTERNAL,
        retryability = AgentRetryability.NEVER,
        safeMessage = reason.take(2 * 1024),
        correlationId = CORRELATION_ID
    )

    companion object {
        const val CORRELATION_ID: String = "agent-context-assembler"
        private const val MESSAGE_OVERHEAD_TOKENS = 4
        private const val ENVELOPE_RESERVE_BYTES = 64
    }
}
