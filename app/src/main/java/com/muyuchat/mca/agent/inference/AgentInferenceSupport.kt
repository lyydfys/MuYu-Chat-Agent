package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentModelUsage
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import org.json.JSONObject

/** Monotonic-ish wall clock boundary so tests never depend on real time. */
internal fun interface AgentInferenceClock {
    fun nowMillis(): Long
}

internal object SystemAgentInferenceClock : AgentInferenceClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/**
 * Stable Agent attempt namespace from architecture 5.2: `agent:<runId>:<stepIndex>:<ordinal>`.
 * The ordinals are unique per run, so this id must never be reused for a second dispatch.
 */
internal fun agentRequestId(attempt: AgentModelAttemptRef): String =
    "agent:${attempt.runId}:${attempt.stepIndex}:${attempt.ordinal}"

/** Adapter-local stable error codes in the existing contract families. */
internal object AgentInferenceErrorCodes {
    /** The engine produced an Error event that has no narrower Agent V1 code. */
    const val MODEL_GENERATION_FAILED = "MODEL_GENERATION_FAILED"
}

internal object AgentInferenceErrors {
    fun of(
        code: String,
        category: AgentErrorCategory,
        safeMessage: String,
        attempt: AgentModelAttemptRef,
        retryability: AgentRetryability = AgentRetryability.AFTER_USER_ACTION,
        causeRef: String? = null,
        possibleSideEffects: Boolean = false
    ): AgentError = AgentError(
        code = code,
        category = category,
        retryability = retryability,
        safeMessage = safeMessage,
        correlationId = "agent-attempt:${attempt.attemptId}",
        causeRef = causeRef,
        possibleSideEffects = possibleSideEffects
    )

    fun failure(
        code: String,
        category: AgentErrorCategory,
        safeMessage: String,
        attempt: AgentModelAttemptRef,
        retryability: AgentRetryability = AgentRetryability.AFTER_USER_ACTION,
        causeRef: String? = null
    ): AgentModelResult.Failure = AgentModelResult.Failure(
        of(code, category, safeMessage, attempt, retryability, causeRef)
    )

    fun staleAttempt(attempt: AgentModelAttemptRef): AgentError = of(
        code = AgentErrorCodes.RESOURCE_LEASE_LOST,
        category = AgentErrorCategory.RESOURCE,
        safeMessage = "本次模型尝试已不再属于当前运行，结果未提交。",
        attempt = attempt,
        retryability = AgentRetryability.AFTER_USER_ACTION
    )
}

/** Set of already-dispatched request ids; the same attempt can never run twice. */
internal class AgentRequestIdLedger {
    private val lock = Any()
    private val used = HashSet<String>()

    /** Returns the request id exactly once for [attempt]; null means this attempt already ran. */
    fun claim(attempt: AgentModelAttemptRef): String? {
        val id = agentRequestId(attempt)
        synchronized(lock) {
            if (!used.add(id)) return null
        }
        return id
    }
}

/**
 * Translation of the frozen Agent transcript into the local chat request shape.
 *
 * Deliberately explicit: exactly one SYSTEM message from [AgentModelRequest.systemPrompt], then
 * USER/ASSISTANT turns only. Tools, tool exchanges and every persistent cache identity stay empty;
 * request-scoped runtime context stays empty because the Agent SYSTEM is already explicit.
 */
internal fun mapAgentRequestToChatRequest(
    request: AgentModelRequest,
    sampling: GenerationParams,
    nPredict: Int = request.maxOutputTokens.coerceAtLeast(1),
    responseFormatJson: String? = null
): ChatRequest {
    val messages = ArrayList<ChatMessage>(request.messages.size + 1)
    messages += ChatMessage(
        role = Role.SYSTEM,
        content = request.systemPrompt,
        id = "agent-system:${request.attempt.attemptId}"
    )
    request.conversationalMessages.forEach { message ->
        messages += ChatMessage(
            role = message.role.toEngineRole(),
            content = message.content,
            id = message.messageId
        )
    }
    return ChatRequest(
        messages = messages,
        params = sampling.copy(
            nPredict = nPredict.coerceAtLeast(1),
            systemPrompt = "",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        ),
        runtimeSystemContext = "",
        persistentPrefixSystemPrompt = null,
        persistentSessionId = null,
        persistentRoleId = null,
        persistentRoleCardVersion = null,
        persistentMemoryVersion = null,
        protectedMessageIds = emptySet(),
        tools = emptyList(),
        toolExchanges = emptyList(),
        conversationContextRevision = null
    )
}

private fun com.muyuchat.core.agent.contract.AgentTranscriptRole.toEngineRole(): Role =
    when (this) {
        com.muyuchat.core.agent.contract.AgentTranscriptRole.SYSTEM -> Role.SYSTEM
        com.muyuchat.core.agent.contract.AgentTranscriptRole.USER -> Role.USER
        com.muyuchat.core.agent.contract.AgentTranscriptRole.ASSISTANT -> Role.ASSISTANT
    }

/** Load-bound fields must never be smuggled in through per-attempt sampling JSON. */
private val loadBoundParameterKeys = setOf(
    "n_ctx",
    "max_num_tokens",
    "maxNumTokens",
    "n_threads",
    "n_threads_batch",
    "mmap",
    "mlock",
    "backend",
    "mmproj_path",
    "geniex_compute_unit"
)

internal sealed interface AgentSamplingParse {
    data class Accepted(val params: GenerationParams) : AgentSamplingParse
    data class Rejected(val field: String) : AgentSamplingParse
}

/**
 * Parses the frozen `samplingJson`. The per-request output cap always wins and reasoning is off
 * because Agent V1 only consumes visible text (the other channel is ignored by contract).
 */
/**
 * Builds the generation defaults from the ACTIVE profile's full parameter set.
 *
 * Using only `hotExecutionValues` leaves load-bound fields (`n_ctx`, `n_threads`, ...) at the
 * deserialized defaults; `GenerationParams.toJson()` then submits those defaults with the request
 * and the engine preflight rejects it as `model_reload_required`. Merging the resolved load-bound
 * and behavior values keeps the request identical to the loaded profile so a plain Agent turn can
 * run without a reload.
 */
internal fun activeAgentSampling(profile: ModelExecutionProfile): GenerationParams {
    val merged = JSONObject()
    listOf(
        profile.resolvedLoadBoundValues,
        profile.hotExecutionValues,
        profile.modelBehaviorValues
    ).forEach { values ->
        val json = values.toJsonObject()
        json.keys().forEach { key -> merged.put(key, json.get(key)) }
    }
    return GenerationParams.fromJson(merged, GenerationParams())
}

internal fun parseAgentSampling(
    samplingJson: String,
    maxOutputTokens: Int,
    activeDefaults: GenerationParams = GenerationParams()
): AgentSamplingParse {
    val root = runCatching { JSONObject(samplingJson.ifBlank { "{}" }) }.getOrNull()
        ?: return AgentSamplingParse.Rejected("samplingJson")
    val offending = loadBoundParameterKeys.firstOrNull { key -> root.has(key) && !root.isNull(key) }
    if (offending != null) return AgentSamplingParse.Rejected(offending)
    // Local Agent runs must not silently override the model's committed hot-execution profile:
    // ordinary chat requests are forbidden from doing so, and a frozen Agent sampling snapshot may
    // differ from the values the engine actually loaded. The active profile is the single source of
    // truth for local generation parameters; only the max-output budget is enforced by the loop.
    val params = runCatching {
        GenerationParams.fromJson(root, activeDefaults)
    }.getOrDefault(activeDefaults)
    return AgentSamplingParse.Accepted(
        params.copy(
            nPredict = activeDefaults.nPredict.takeIf { it > 0 }
                ?: maxOutputTokens.coerceAtLeast(1),
            systemPrompt = "",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
    )
}

/** Builds a bounded, redacted error for an engine or transport failure. */
internal fun agentEngineFailure(
    attempt: AgentModelAttemptRef,
    code: String?,
    message: String?
): AgentModelResult.Failure {
    val normalized = code?.trim()?.uppercase().orEmpty()
    return when (normalized) {
        "CONTEXT_LENGTH_EXCEEDED" -> AgentInferenceErrors.failure(
            code = AgentErrorCodes.MODEL_CONTEXT_LIMIT,
            category = AgentErrorCategory.MODEL,
            safeMessage = "请求超出本地模型上下文，请缩小资料或更换模型。",
            attempt = attempt
        )

        "MODEL_NOT_LOADED" -> AgentInferenceErrors.failure(
            code = AgentErrorCodes.MODEL_NOT_LOADED,
            category = AgentErrorCategory.MODEL,
            safeMessage = "本地模型尚未加载。",
            attempt = attempt
        )

        else -> AgentInferenceErrors.failure(
            code = AgentInferenceErrorCodes.MODEL_GENERATION_FAILED,
            category = AgentErrorCategory.MODEL,
            safeMessage = "本地模型生成失败：" + (message ?: "未知错误").take(160),
            attempt = attempt,
            causeRef = code?.take(64)
        )
    }
}

internal fun usageFromStats(stats: RuntimeStats?): AgentModelUsage? {
    if (stats == null) return null
    val estimated = stats.promptTokensEstimated || stats.completionTokensEstimated
    return AgentModelUsage(
        inputTokens = stats.promptTokens.takeIf { it > 0 },
        outputTokens = stats.completionTokens.takeIf { it > 0 },
        estimated = estimated
    )
}
