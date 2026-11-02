package com.muyuchat.mca.dsh

import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudModelRecord
import kotlinx.coroutines.flow.Flow

/**
 * One currently serveable local model.
 *
 * [modelId] is the stable MCA manifest identity (a UUID) used in the gateway model id; it is never
 * a user-facing label. [displayName] is the human label the official DSH picker shows
 * (`Qwen3.5 0.8B MNN CPU`), resolved by [DshLocalModelDisplayName] and never derived from an opaque
 * id.
 *
 * [runtimeValuesJson] is the exact committed execution-profile value set (resolved load-bound +
 * hot execution + model behavior fields). The mapper derives the base [ChatRequest] parameters
 * from it so a DSH call never asks the engine to reload the active model just because a default
 * (`n_ctx`, template mode, ...) differs from what is loaded.
 */
internal data class DshLocalModel(
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
    val maxOutputTokens: Int?,
    val runtimeValuesJson: String,
    /**
     * Envelope the loaded GGUF was trained on, derived from its own chat template. The default is
     * the Qwen2.5/Qwen3 form; Qwen3.5/Coder templates select the Hermes form instead.
     */
    val toolEnvelopeStyle: DshToolEnvelopeStyle = DshToolEnvelopeStyle.QWEN_JSON
)

/**
 * Narrow read surface over the single app engine owner.
 *
 * Returns null when no local model is currently loaded and serveable. It never loads, unloads or
 * switches a model and never constructs an engine; DSH model availability follows the same owner
 * that already serves chat, Local API and the Kotlin Agent.
 */
internal fun interface DshLocalModelAccess {
    fun activeModel(): DshLocalModel?
}

/**
 * Exact-call local execution surface.
 *
 * [stream] must be the production [com.muyuchat.mca.facade.ChatExecutionFacade.streamChat] funnel;
 * [cancel] must target exactly `requestId`, never the global stop. The adapter owns the request id
 * and guarantees it is never reused, so cancelling an old id can never touch a newer call.
 *
 * The three cancellation paths this port participates in are deliberately distinct:
 *  - gateway out-of-band cancel (`DshGatewayBackend.cancel`) calls [cancel] to mark exactly that
 *    request on the supervisor *and* to dispatch the exact engine stop
 *    (`facade.stopGenerationIfActive(requestId)`, the same path the Local API uses for a client
 *    stop), while the gateway also cancels the collecting Flow structurally;
 *  - structural cancellation propagates into the engine flow, and its unwind calls [cancel] for
 *    the same request id as a fallback. Structural cancellation alone is not sufficient: a native
 *    prefill blocked in `beginCompletion` never reaches a cooperative suspension point, which is
 *    why the out-of-band engine stop must exist (device evidence: binder code 4 blocked 245 s);
 *  - an engine-initiated stop (for example unloading the local model, which stops the active
 *    generation first) arrives as a terminal `Error` with a cancellation code and is mapped to
 *    `Completed(CANCELLED)`.
 *
 * [cancel] is suspend because the exact engine-stop dispatch is asynchronous and bounded (the
 * engine's stop window is 500 ms); it must never wait for native settle evidence. The engine's
 * token check (`token.requestId == requestId`) plus the adapter's never-reused request ids mean a
 * late cancel is an exact no-op and can never stop a replacement call. No path uses the global
 * `stopGeneration()`.
 */
internal interface DshLocalChatPort {
    fun stream(request: ChatRequest, requestId: String): Flow<GenerateEvent>

    /**
     * Stops exactly [requestId]. Returns true when the supervisor cancellation and/or the exact
     * engine stop was dispatched for it; false for an unknown or already-settled request.
     */
    suspend fun cancel(requestId: String): Boolean
}

/**
 * Read-only source of the user's configured cloud chat models.
 *
 * The adapter never creates a second account/key store; implementations read the existing
 * `CloudApiStore`. Chat models that are not fully configured must not be returned as serveable.
 */
internal fun interface DshCloudModelAccess {
    suspend fun chatModels(): List<CloudModelRecord>
}

/**
 * Exact-call cloud transport surface. Implementations delegate to the existing
 * `OpenAiCompatibleChatProvider`; structural cancellation of the collected flow cancels exactly
 * that provider call.
 */
internal fun interface DshCloudChatPort {
    fun stream(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent>
}
