package com.muyuchat.api.local

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

object LocalApiRuntime {
    private val ownerLock = Any()
    private var ownerToken: Any? = null
    private var ownerReplacementCleanup: (() -> Unit)? = null
    private val requestTraceState = LocalApiRuntimeTraceState()

    /**
     * Installs a process-global runtime owner. Replacing an owner first retires its listener so
     * the new owner can bind the stable port without the old ViewModel later clearing new fields.
     */
    fun claimOwner(token: Any, onReplaced: () -> Unit) {
        val previousCleanup = synchronized(ownerLock) {
            if (ownerToken === token) {
                ownerReplacementCleanup = onReplaced
                null
            } else {
                ownerReplacementCleanup.also {
                    ownerToken = token
                    ownerReplacementCleanup = onReplaced
                }
            }
        }
        previousCleanup?.let { cleanup -> runCatching(cleanup) }
    }

    /**
     * Clears every callback only when [token] still owns this singleton. A stale ViewModel cannot
     * detach providers installed by its replacement.
     */
    fun releaseOwner(token: Any): Boolean = synchronized(ownerLock) {
        if (ownerToken !== token) return@synchronized false
        engine = null
        nativeStatsJsonProvider = null
        streamChatProvider = null
        streamChatWithContextProvider = null
        stopGenerationProvider = null
        stopGenerationIfRequestActiveProvider = null
        loadedModelJsonProvider = { "{}" }
        paramsJsonProvider = { "{}" }
        generationParamsProvider = { GenerationParams() }
        modelsJsonProvider = { "[]" }
        imageTextualInversionsJsonProvider = { "[]" }
        modelRuntimeStatesJsonProvider = { "{}" }
        deviceProfileJsonProvider = { "{}" }
        agentRecommendationJsonProvider = { "{}" }
        benchmarkJsonProvider = { "{}" }
        imageGenerationProvider = null
        controlPlane = null
        // Drop the published set in the same critical section as the field clears, so a call that
        // arrives after release cannot keep using the retired owner's callbacks.
        publishedBindings = null
        clearRequestTrace()
        ownerToken = null
        ownerReplacementCleanup = null
        true
    }

    fun isOwner(token: Any): Boolean = synchronized(ownerLock) {
        ownerToken === token
    }

    @Volatile
    var engine: McaInferenceService? = null

    @Volatile
    internal var nativeStatsJsonProvider: (() -> String)? = null

    @Volatile
    var streamChatProvider: ((ChatRequest) -> Flow<GenerateEvent>)? = null

    /**
     * Context-aware provider used by integrations that need the API-generated request id to reach
     * the runtime coordinator. The one-argument provider remains supported as a legacy fallback.
     */
    @Volatile
    var streamChatWithContextProvider:
        ((ChatRequest, LocalChatExecutionContext) -> Flow<GenerateEvent>)? = null

    @Volatile
    var stopGenerationProvider: (suspend () -> Unit)? = null

    @Volatile
    var stopGenerationIfRequestActiveProvider: (suspend (String) -> Boolean)? = null

    @Volatile
    var loadedModelJsonProvider: () -> String = { "{}" }

    @Volatile
    var paramsJsonProvider: () -> String = { "{}" }

    @Volatile
    var generationParamsProvider: () -> GenerationParams = { GenerationParams() }

    @Volatile
    var modelsJsonProvider: () -> String = { "[]" }

    /** App-owned textual-inversion inventory. The public accessor below applies a strict allowlist. */
    @Volatile
    var imageTextualInversionsJsonProvider: () -> String = { "[]" }

    /**
     * Optional per-model persisted profile summaries keyed by model id. The catalog provider is
     * intentionally kept separate: model discovery must not copy the currently loaded model's
     * execution parameters or profile state onto every catalog entry.
     *
     * Accepted shapes are either `{ "model-id": { ...state... } }` or
     * `{ "data": [{ "modelId": "model-id", ...state... }] }`.
     */
    @Volatile
    var modelRuntimeStatesJsonProvider: () -> String = { "{}" }

    @Volatile
    var deviceProfileJsonProvider: () -> String = { "{}" }

    @Volatile
    var agentRecommendationJsonProvider: (String) -> String = { "{}" }

    @Volatile
    var benchmarkJsonProvider: suspend (String) -> String = { "{}" }

    /** App-owned production image worker bridge used by the authenticated Images API. */
    @Volatile
    var imageGenerationProvider: (suspend (requestId: String, body: String) -> String)? = null

    suspend fun generateImage(requestId: String, body: String): String? {
        val bindings = currentBindings()
        return bindings.imageGenerationProvider?.invoke(requestId, body)
    }

    /**
     * Optional app-owned coordinator bridge. Existing provider fields remain valid when this is
     * not attached, which keeps older integrations source and runtime compatible.
     */
    @Volatile
    var controlPlane: LocalApiControlPlane? = null

    /**
     * Immutable snapshot of every callback one runtime call needs.
     *
     * Before this existed a call read the individual `var` fields one after another. While an owner
     * was being replaced those reads could straddle the swap and observe a half-updated mix (for
     * example the new owner's chat stream together with the old owner's stop handle). The owner now
     * publishes one complete set, and a call captures it once and uses only that value.
     */
    class LocalApiBindings internal constructor(
        val engine: McaInferenceService?,
        val nativeStatsJsonProvider: (() -> String)?,
        val streamChatProvider: ((ChatRequest) -> Flow<GenerateEvent>)?,
        val streamChatWithContextProvider:
            ((ChatRequest, LocalChatExecutionContext) -> Flow<GenerateEvent>)?,
        val stopGenerationProvider: (suspend () -> Unit)?,
        val stopGenerationIfRequestActiveProvider: (suspend (String) -> Boolean)?,
        val loadedModelJsonProvider: () -> String,
        val paramsJsonProvider: () -> String,
        val generationParamsProvider: () -> GenerationParams,
        val modelsJsonProvider: () -> String,
        val imageTextualInversionsJsonProvider: () -> String,
        val modelRuntimeStatesJsonProvider: () -> String,
        val deviceProfileJsonProvider: () -> String,
        val agentRecommendationJsonProvider: (String) -> String,
        val benchmarkJsonProvider: suspend (String) -> String,
        val imageGenerationProvider: (suspend (requestId: String, body: String) -> String)?,
        val controlPlane: LocalApiControlPlane?
    )

    @Volatile
    private var publishedBindings: LocalApiBindings? = null

    /** Reads the current field values as one set. Not atomic by itself; see [publishBindings]. */
    private fun snapshotBindings(): LocalApiBindings = LocalApiBindings(
        engine = engine,
        nativeStatsJsonProvider = nativeStatsJsonProvider,
        streamChatProvider = streamChatProvider,
        streamChatWithContextProvider = streamChatWithContextProvider,
        stopGenerationProvider = stopGenerationProvider,
        stopGenerationIfRequestActiveProvider = stopGenerationIfRequestActiveProvider,
        loadedModelJsonProvider = loadedModelJsonProvider,
        paramsJsonProvider = paramsJsonProvider,
        generationParamsProvider = generationParamsProvider,
        modelsJsonProvider = modelsJsonProvider,
        imageTextualInversionsJsonProvider = imageTextualInversionsJsonProvider,
        modelRuntimeStatesJsonProvider = modelRuntimeStatesJsonProvider,
        deviceProfileJsonProvider = deviceProfileJsonProvider,
        agentRecommendationJsonProvider = agentRecommendationJsonProvider,
        benchmarkJsonProvider = benchmarkJsonProvider,
        imageGenerationProvider = imageGenerationProvider,
        controlPlane = controlPlane
    )

    /**
     * Publishes the currently installed callbacks as one immutable set.
     *
     * The owner calls this exactly once, after it has finished installing every provider, so that no
     * runtime call can observe a half-installed owner. Publishing is a single volatile write, so the
     * swap is atomic with respect to [currentBindings].
     */
    fun publishBindings(): LocalApiBindings {
        val bindings = snapshotBindings()
        publishedBindings = bindings
        return bindings
    }

    /** True once an owner has published a complete set. */
    val hasPublishedBindings: Boolean get() = publishedBindings != null

    /**
     * The set a runtime call must use for its whole lifetime.
     *
     * Falls back to a live read when nothing has been published yet, which keeps integrations that
     * only assign individual providers (older embeddings, focused tests) working unchanged.
     */
    fun currentBindings(): LocalApiBindings = publishedBindings ?: snapshotBindings()

    fun streamChat(request: ChatRequest): Flow<GenerateEvent>? =
        streamChat(request, LocalChatExecutionContext())

    fun streamChat(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext
    ): Flow<GenerateEvent>? {
        val tracedContext = if (executionContext.requestId.isNotBlank()) {
            val requestId = executionContext.requestId
            requestTraceState.reset(requestId)
            val downstreamSink = executionContext.visionDiagnosticSink
            executionContext.copy(
                visionDiagnosticSink = { stage, details ->
                    runCatching { requestTraceState.recordVisionDiagnostic(requestId, stage, details) }
                    downstreamSink?.let { sink ->
                        val snapshot = runCatching { details.toString() }.getOrDefault("{}")
                        runCatching { sink(stage, JSONObject(snapshot)) }
                    }
                }
            )
        } else {
            executionContext
        }
        val bindings = currentBindings()
        return bindings.streamChatWithContextProvider?.invoke(request, tracedContext)
            ?: bindings.streamChatProvider?.invoke(request)
            ?: bindings.engine?.streamChat(request, tracedContext)
    }

    suspend fun stopGeneration() {
        val bindings = currentBindings()
        val stop = bindings.stopGenerationProvider
        if (stop != null) {
            stop()
        } else {
            bindings.engine?.stopGeneration()
        }
    }

    suspend fun stopGenerationIfRequestActive(requestId: String): Boolean {
        if (requestId.isBlank()) return false
        val bindings = currentBindings()
        val conditional = bindings.stopGenerationIfRequestActiveProvider
        if (conditional != null) return conditional(requestId)
        val attached = bindings.engine ?: return false
        val token = attached.activeGenerationStopToken()
            ?.takeIf { it.requestId == requestId }
            ?: return false
        return attached.stopGenerationIfActive(token)
    }

    fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult {
        val plane = currentBindings().controlPlane ?: return LocalApiPreflightResult.Ready
        val busy = plane.busyState()
        if (busy.busy) {
            return LocalApiPreflightResult.Rejected(
                httpStatus = 409,
                code = busy.code.ifBlank { "runtime_busy" },
                message = busy.message.ifBlank { "The local model runtime is busy." },
                retryAfterMs = busy.retryAfterMs.coerceAtLeast(0L),
                detailsJson = busy.detailsJson
            )
        }
        return plane.preflight(request)
    }

    fun busyState(): LocalApiBusyState =
        currentBindings().controlPlane?.busyState() ?: LocalApiBusyState.IDLE

    fun profileJson(): String {
        val bindings = currentBindings()
        return bindings.controlPlane?.profileJson() ?: bindings.paramsJsonProvider()
    }

    fun tuningJson(): String = currentBindings().controlPlane?.tuningJson() ?: "{}"

    fun publicProfileJson(): String = publicJson(profileJson())

    fun publicTuningJson(): String = publicJson(tuningJson())

    /** Redacts a coordinator/native JSON object or array before it crosses an API boundary. */
    fun publicJson(rawJson: String): String =
        rawJson.toJsonObjectOrNull()?.publicCopy()?.toString()
            ?: runCatching { org.json.JSONArray(rawJson).publicCopy(false).toString() }.getOrNull()
            ?: "{}"

    fun publicMessage(message: String): String = when {
        message.looksLikeAbsolutePath() -> "The local runtime rejected the request; private path details were redacted."
        message.length > MAX_PUBLIC_MESSAGE_LENGTH -> message.take(MAX_PUBLIC_MESSAGE_LENGTH)
        else -> message
    }

    /**
     * Read one tuning job through the app-owned control plane. This intentionally does not check
     * `busyState`: job inspection must remain available while loading, validating, or recovering.
     */
    fun tuningJob(jobId: String?): LocalApiControlResult =
        normalizeTuningJobResult(jobId, invokeControlPlane { it.tuningJob(jobId) })

    fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotencyKey: String
    ): LocalApiControlResult = invokeControlPlane { it.createTuningJob(request, idempotencyKey) }

    fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = invokeControlPlane { it.createTuningJob(request, idempotency) }

    /**
     * Tuning controls are synchronous coordinator transitions. They do not share the generation
     * preflight busy gate, so cancel/pause/resume remain callable while the engine is busy.
     */
    fun pauseTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        invokeControlPlane { it.pauseTuningJob(jobId, idempotencyKey) }

    fun pauseTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = invokeControlPlane { it.pauseTuningJob(jobId, idempotency) }

    fun resumeTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        invokeControlPlane { it.resumeTuningJob(jobId, idempotencyKey) }

    fun resumeTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = invokeControlPlane { it.resumeTuningJob(jobId, idempotency) }

    fun cancelTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        invokeControlPlane { it.cancelTuningJob(jobId, idempotencyKey) }

    fun cancelTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = invokeControlPlane { it.cancelTuningJob(jobId, idempotency) }

    fun applyTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        invokeControlPlane { it.applyTuningJob(jobId, idempotencyKey) }

    fun applyTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = invokeControlPlane { it.applyTuningJob(jobId, idempotency) }

    fun rollbackTuning(idempotencyKey: String): LocalApiControlResult =
        invokeControlPlane { it.rollbackTuning(idempotencyKey) }

    fun rollbackTuning(idempotency: LocalApiIdempotencyContext): LocalApiControlResult =
        invokeControlPlane { it.rollbackTuning(idempotency) }

    /**
     * Lifecycle-changing tuning operations use the same coordinator busy state as generation.
     * Pause/resume/cancel deliberately do not call this method so recovery controls remain usable.
     */
    fun lifecycleConflict(): LocalApiControlResult.Rejected? {
        val busy = runCatching { busyState() }.getOrElse {
            return LocalApiControlResult.Rejected(
                httpStatus = 503,
                code = "control_plane_failed",
                message = "The app runtime control plane could not report lifecycle state."
            )
        }
        if (!busy.busy) return null

        val details = busy.detailsJson.toJsonObjectOrNull()?.publicCopy() ?: JSONObject()
        val tuning = runCatching { tuningJson().toJsonObjectOrNull() }.getOrNull()
        val profile = runCatching { profileJson().toJsonObjectOrNull() }.getOrNull()
        details.putIfMissing(
            "activeJobId",
            (details.firstValue("jobId")
                ?: tuning?.firstValue("activeJobId", "jobId")).publicScalar()
        )
        details.putIfMissing(
            "tuningJobState",
            (details.firstValue("state")
                ?: tuning?.firstValue("tuningJobState", "state")).publicScalar()
        )
        details.putIfMissing(
            "engineLifecycle",
            profile?.firstValue("engineLifecycle", "lifecycle").publicScalar()
        )
        details.putIfMissing(
            "activeProfileId",
            profile?.firstValue("activeProfileId", "profileId").publicScalar()
        )
        details.put("retryAfterMs", busy.retryAfterMs.coerceAtLeast(0L))
        return LocalApiControlResult.Rejected(
            httpStatus = 409,
            code = busy.code.ifBlank { "lifecycle_conflict" },
            message = busy.message.ifBlank { "Another model lifecycle operation is active." },
            detailsJson = details.toString(),
            retryAfterMs = busy.retryAfterMs.coerceAtLeast(0L)
        )
    }

    /** Correlation plus allowlisted media evidence only; never paths, prompts, keys, or debug text. */
    fun traceJson(): String = requestTraceState.requestTraceObject().toString()

    /**
     * Preserves redacted native metrics, adds request correlation, and publishes the coordinator's
     * canonical six-signature snapshot. Absolute paths, prompts, sessions, and credentials are
     * removed at this public API boundary even if a backend accidentally includes them in stats.
     */
    fun metricsJson(): String {
        val stats = nativeMetricsObject()
        requestTraceState.requestTraceObject().takeIf { it.length() > 0 }?.let { trace ->
            stats.put("requestTrace", trace)
            trace.optJSONObject("mediaTrace")?.let { mediaTrace ->
                stats.put("mediaTrace", JSONObject(mediaTrace.toString()))
            }
            listOf("uiImageSha256", "apiImageSha256").forEach { field ->
                if (trace.has(field)) stats.put(field, trace.getString(field))
            }
        }
        stats.put("coordinatorSignatures", coordinatorSignaturesObject())
        return stats.toString()
    }

    /** Public model catalog with paths and cross-model execution state removed. */
    fun modelsJson(): String = modelsJsonImpl()

    /**
     * Authenticated image-extension inventory. Only fields needed to select an artifact are
     * published; app-private paths and compatibility fingerprints are intentionally discarded.
     */
    fun imageTextualInversionsJson(): String = imageTextualInversionsJsonImpl()


    fun generationSequence(): Long? {
        val stats = nativeStatsObject() ?: return null
        if (!stats.has("generationSequence") || stats.isNull("generationSequence")) return null
        return stats.optLong("generationSequence").takeIf { it >= 0L }
    }

    /**
     * Records the native sequence for a product-owned request (UI or API).
     * Keeping this at the runtime boundary lets the formal MainActivity path
     * expose the same redacted trace as the authenticated Local API without
     * leaking prompts, keys, or native debug payloads.
     */
    fun recordGenerationSequence(requestId: String, generationSequence: Long) {
        requestTraceState.recordGenerationSequence(requestId, generationSequence)
    }
    internal fun clearRequestTrace() = requestTraceState.clearRequestTrace()

    private const val MAX_PUBLIC_MESSAGE_LENGTH = 1024
}

