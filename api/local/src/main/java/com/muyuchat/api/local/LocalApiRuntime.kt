package com.muyuchat.api.local

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

object LocalApiRuntime {
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

    suspend fun generateImage(requestId: String, body: String): String? =
        imageGenerationProvider?.invoke(requestId, body)

    /**
     * Optional app-owned coordinator bridge. Existing provider fields remain valid when this is
     * not attached, which keeps older integrations source and runtime compatible.
     */
    @Volatile
    var controlPlane: LocalApiControlPlane? = null

    fun streamChat(request: ChatRequest): Flow<GenerateEvent>? =
        streamChat(request, LocalChatExecutionContext())

    fun streamChat(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext
    ): Flow<GenerateEvent>? {
        val tracedContext = if (executionContext.requestId.isNotBlank()) {
            val requestId = executionContext.requestId
            synchronized(traceLock) {
                resetRequestTraceLocked(requestId)
            }
            val downstreamSink = executionContext.visionDiagnosticSink
            executionContext.copy(
                visionDiagnosticSink = { stage, details ->
                    runCatching { recordVisionDiagnostic(requestId, stage, details) }
                    downstreamSink?.let { sink ->
                        val snapshot = runCatching { details.toString() }.getOrDefault("{}")
                        runCatching { sink(stage, JSONObject(snapshot)) }
                    }
                }
            )
        } else {
            executionContext
        }
        return streamChatWithContextProvider?.invoke(request, tracedContext)
            ?: streamChatProvider?.invoke(request)
            ?: engine?.streamChat(request, tracedContext)
    }

    suspend fun stopGeneration() {
        val stop = stopGenerationProvider
        if (stop != null) {
            stop()
        } else {
            engine?.stopGeneration()
        }
    }

    fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult {
        val plane = controlPlane ?: return LocalApiPreflightResult.Ready
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

    fun busyState(): LocalApiBusyState = controlPlane?.busyState() ?: LocalApiBusyState.IDLE

    fun profileJson(): String = controlPlane?.profileJson() ?: paramsJsonProvider()

    fun tuningJson(): String = controlPlane?.tuningJson() ?: "{}"

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

    private inline fun invokeControlPlane(
        action: (LocalApiControlPlane) -> LocalApiControlResult
    ): LocalApiControlResult {
        val plane = controlPlane
            ?: return LocalApiControlResult.Rejected(
                httpStatus = 503,
                code = "control_plane_unavailable",
                message = "The app runtime control plane is not attached."
            )
        return runCatching { action(plane) }.getOrElse {
            LocalApiControlResult.Rejected(
                httpStatus = 503,
                code = "control_plane_failed",
                message = "The app runtime control plane could not complete the request."
            )
        }
    }

    /** Correlation plus allowlisted media evidence only; never paths, prompts, keys, or debug text. */
    fun traceJson(): String = requestTraceObject().toString()

    /**
     * Preserves redacted native metrics, adds request correlation, and publishes the coordinator's
     * canonical six-signature snapshot. Absolute paths, prompts, sessions, and credentials are
     * removed at this public API boundary even if a backend accidentally includes them in stats.
     */
    fun metricsJson(): String {
        val stats = nativeMetricsObject()
        requestTraceObject().takeIf { it.length() > 0 }?.let { trace ->
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
    fun modelsJson(): String {
        val supplied = runCatching { modelsJsonProvider() }.getOrDefault("{}")
        val root = supplied.toJsonObjectOrNull()
        val source = root?.optJSONArray("data")
            ?: runCatching { org.json.JSONArray(supplied) }.getOrNull()
            ?: org.json.JSONArray()
        val loaded = runCatching { loadedModelJsonProvider().toJsonObjectOrNull() }.getOrNull()
        val profile = runCatching { profileJson().toJsonObjectOrNull() }.getOrNull()
        val tuning = runCatching { tuningJson().toJsonObjectOrNull() }.getOrNull()
        val perModelStates = runCatching { modelRuntimeStatesJsonProvider() }
            .getOrDefault("{}")
            .toJsonObjectOrNull()
        val activeModelId = profile?.firstString("modelId")
            .orEmpty()
            .ifBlank { loaded?.firstString("id", "modelId").orEmpty() }
        val activeContextLength = activeContextLength(loaded)
        val result = org.json.JSONArray()

        for (index in 0 until source.length()) {
            val raw = source.optJSONObject(index) ?: continue
            val item = raw.publicCopy(stripExecutionState = true)
            val modelId = item.firstString("id", "modelId").orEmpty()
            if (item.isImageGenerationCatalogEntry()) {
                // Image packages have their own worker lifecycle and execution evidence. Do not
                // synthesize chat profile/tuning/loaded fields onto them; retain only the producer's
                // already-sanitized discovery contract.
                result.put(item)
                continue
            }
            val active = modelId.isNotBlank() && modelId == activeModelId
            val persisted = perModelStates.stateForModel(modelId)
            val activeProfile = if (active) profile else null
            val activeTuning = if (
                active || tuning?.firstString("modelId").orEmpty() == modelId
            ) tuning else null

            item.putNullable(
                "profileId",
                (activeProfile?.firstValue("activeProfileId", "profileId")
                    ?: persisted?.firstValue("profileId")).publicScalar()
            )
            item.put(
                "profileRecordState",
                activeProfile?.firstString("profileRecordState", "recordState")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("profileRecordState", "recordState")
                            .orEmpty()
                    }
                    .publicStateOr("none")
            )
            item.put(
                "profileVerificationLevel",
                activeProfile?.firstString("profileVerificationLevel", "verification")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("profileVerificationLevel", "verification")
                            .orEmpty()
                    }
                    .publicStateOr("unknown")
            )
            item.put(
                "reloadRequired",
                activeProfile?.firstBoolean("reloadRequired")
                    ?: persisted?.firstBoolean("reloadRequired")
                    ?: false
            )
            item.put(
                "engineLifecycle",
                activeProfile?.firstString("engineLifecycle", "lifecycle")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("engineLifecycle", "lifecycle").orEmpty()
                    }
                    .publicStateOr(if (active) "unknown" else "unloaded")
            )
            item.put(
                "tuningJobState",
                activeTuning?.firstString("tuningJobState", "state")
                    .orEmpty()
                    .ifBlank {
                        persisted?.firstString("tuningJobState").orEmpty()
                    }
                    .publicStateOr("idle")
            )
            // Short aliases keep the catalog self-describing for clients that consume the
            // coordinator vocabulary, while the profile-prefixed names remain explicit.
            item.put("recordState", item.optString("profileRecordState"))
            item.put("verification", item.optString("profileVerificationLevel"))
            item.put("lifecycle", item.optString("engineLifecycle"))
            val lifecycle = item.optString("engineLifecycle").lowercase()
            val isLoaded = active && lifecycle !in NON_LOADED_LIFECYCLES
            item.put("loaded", isLoaded)
            if (isLoaded && activeContextLength != null) {
                // These aliases intentionally expose the user's logical context window. llama.cpp
                // may pad the native allocation (for example 8190 -> 8192), but replacing the
                // configured value with that transport detail would silently rewrite custom n_ctx.
                item.put("n_ctx", activeContextLength)
                item.put("context_length", activeContextLength)
                item.put("max_context_length", activeContextLength)
            }
            result.put(item)
        }
        return JSONObject()
            .put("object", root?.optString("object").orEmpty().ifBlank { "list" })
            .put("data", result)
            .toString()
    }

    /**
     * Authenticated image-extension inventory. Only fields needed to select an artifact are
     * published; app-private paths and compatibility fingerprints are intentionally discarded.
     */
    fun imageTextualInversionsJson(): String {
        val supplied = runCatching { imageTextualInversionsJsonProvider() }.getOrDefault("[]")
        val root = supplied.toJsonObjectOrNull()
        val source = root?.optJSONArray("data")
            ?: runCatching { org.json.JSONArray(supplied) }.getOrNull()
            ?: org.json.JSONArray()
        val result = org.json.JSONArray()
        val uuidPattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        )
        val triggerPattern = Regex("^[A-Za-z0-9_:#<>|.-]{1,64}$")
        val sha256Pattern = Regex("^[0-9a-f]{64}$")
        val formats = setOf("safetensors", "pytorch", "checkpoint", "binary")

        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: continue
            val id = item.optString("id").trim().lowercase()
            val name = item.optString("name").trim()
            val trigger = item.optString("trigger").trim()
            val format = item.optString("format").trim().lowercase()
            val sha256 = item.optString("sha256").trim().lowercase()
            val sizeBytes = item.optLong("sizeBytes", -1L)
            val importedAt = item.optLong("importedAt", 0L)
            if (!uuidPattern.matches(id) || name.isEmpty() || name.length > 128 ||
                !triggerPattern.matches(trigger) || format !in formats ||
                !sha256Pattern.matches(sha256) || sizeBytes !in 1L..100L * 1024L * 1024L ||
                importedAt < 0L
            ) {
                continue
            }
            result.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "textual_inversion")
                    .put("name", name)
                    .put("trigger", trigger)
                    .put("format", format)
                    .put("sha256", sha256)
                    .put("size_bytes", sizeBytes)
                    .put("created", importedAt / 1_000L)
            )
        }
        return JSONObject()
            .put("object", root?.optString("object").orEmpty().ifBlank { "list" })
            .put("data", result)
            .toString()
    }

    private fun activeContextLength(loaded: JSONObject?): Int? {
        val configured = runCatching { generationParamsProvider().nCtx }
            .getOrNull()
            ?.takeIf { it > 0 }
        if (configured != null) return configured

        return loaded?.firstPositiveInt("n_ctx", "context_length", "max_context_length")
            ?: loaded?.optJSONObject("stats")
                ?.firstPositiveInt("nCtx", "n_ctx", "maxAllTokens", "max_all_tokens")
            ?: nativeStatsObject()
                ?.firstPositiveInt("nCtx", "n_ctx", "maxAllTokens", "max_all_tokens")
    }

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
        if (requestId.isBlank() || generationSequence < 0L) return
        synchronized(traceLock) {
            if (lastDispatchedRequestId == requestId) {
                lastDispatchedGenerationSequence = generationSequence
            }
        }
    }

    internal fun clearRequestTrace() {
        synchronized(traceLock) {
            resetRequestTraceLocked("")
        }
    }

    private val traceLock = Any()
    private var lastDispatchedRequestId: String = ""
    private var lastDispatchedGenerationSequence: Long? = null
    private var lastMediaPreparedCount: Int = 0
    private var lastMediaFailedCount: Int = 0
    private var lastMediaPreprocessingCount: Int = 0
    private val lastMediaPreprocessingCounts = linkedMapOf<String, Int>()
    private val lastMediaInputSha256s = linkedSetOf<String>()

    private fun resetRequestTraceLocked(requestId: String) {
        lastDispatchedRequestId = requestId
        lastDispatchedGenerationSequence = null
        lastMediaPreparedCount = 0
        lastMediaFailedCount = 0
        lastMediaPreprocessingCount = 0
        lastMediaPreprocessingCounts.clear()
        lastMediaInputSha256s.clear()
    }

    private fun recordVisionDiagnostic(requestId: String, stage: String, details: JSONObject) {
        val prepared = stage == VISION_PREPARED_STAGE
        val failed = stage == VISION_FAILED_STAGE
        if (!prepared && !failed) return
        val preprocessing = details.optString("preprocessing")
            .trim()
            .lowercase()
            .takeIf(MEDIA_PREPROCESSING_TOKEN_PATTERN::matches)
            ?: "unknown"
        val inputSha256 = details.optString("inputSha256")
            .trim()
            .lowercase()
            .takeIf(MEDIA_SHA256_PATTERN::matches)

        synchronized(traceLock) {
            if (lastDispatchedRequestId != requestId) return
            if (prepared) {
                lastMediaPreparedCount = lastMediaPreparedCount.safeIncrement()
                if (inputSha256 != null && lastMediaInputSha256s.size < MAX_RECORDED_MEDIA_HASHES) {
                    lastMediaInputSha256s += inputSha256
                }
            } else {
                lastMediaFailedCount = lastMediaFailedCount.safeIncrement()
            }
            lastMediaPreprocessingCount = lastMediaPreprocessingCount.safeIncrement()
            lastMediaPreprocessingCounts[preprocessing] =
                lastMediaPreprocessingCounts.getOrDefault(preprocessing, 0).safeIncrement()
        }
    }

    private fun Int.safeIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private fun mediaTraceObjectLocked(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("preparedCount", lastMediaPreparedCount)
        .put("failedCount", lastMediaFailedCount)
        .put("preprocessingCount", lastMediaPreprocessingCount)
        .put(
            "preprocessingCounts",
            JSONObject().apply {
                lastMediaPreprocessingCounts.toSortedMap().forEach { (name, count) ->
                    put(name, count)
                }
            }
        )
        .put(
            "inputSha256s",
            org.json.JSONArray().apply {
                lastMediaInputSha256s.sorted().forEach { put(it) }
            }
        )
        .apply {
            singleImageSha256Locked()?.let { put("inputSha256", it) }
        }

    private fun singleImageSha256Locked(): String? =
        lastMediaInputSha256s.singleOrNull()
            ?.takeIf { lastMediaPreparedCount == 1 && lastMediaFailedCount == 0 }

    private fun requestTraceObject(): JSONObject = synchronized(traceLock) {
        JSONObject()
            .apply {
                lastDispatchedRequestId.takeIf(String::isNotBlank)?.let { requestId ->
                    put("requestId", requestId)
                    put("mediaTrace", mediaTraceObjectLocked())
                    singleImageSha256Locked()?.let { imageSha256 ->
                        when {
                            requestId.startsWith("ui-") -> put("uiImageSha256", imageSha256)
                            requestId.startsWith("chatcmpl-") -> put("apiImageSha256", imageSha256)
                        }
                    }
                }
                lastDispatchedGenerationSequence?.let { put("generationSequence", it) }
            }
    }

    private fun nativeStatsObject(): JSONObject? = runCatching {
        nativeStatsJsonProvider?.invoke() ?: engine?.nativeStatsJson()
    }.getOrNull()?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }

    private fun nativeMetricsObject(): JSONObject {
        val source = nativeStatsObject() ?: return JSONObject()
        val metrics = JSONObject()
        NATIVE_METRIC_FIELDS.forEach { key ->
            if (!source.has(key) || source.isNull(key)) return@forEach
            when (val value = source.opt(key)) {
                is Number, is Boolean -> metrics.put(key, value)
                is String -> if (
                    key in NATIVE_METRIC_STRING_FIELDS &&
                    value.length <= MAX_PUBLIC_METRIC_STRING_LENGTH &&
                    !value.looksLikeAbsolutePath()
                ) metrics.put(key, value)
            }
        }
        return metrics
    }

    private fun normalizeTuningJobResult(
        requestedJobId: String?,
        result: LocalApiControlResult
    ): LocalApiControlResult {
        if (result !is LocalApiControlResult.Success) return result
        val root = result.json.toJsonObjectOrNull() ?: return result
        // Some legacy coordinators returned the complete global tuning state plus a nested `job`
        // for an explicit id. Expose only that persisted job to avoid mixing unrelated current
        // progress into historical/recovering job queries.
        val normalized = if (requestedJobId != null) {
            root.optJSONObject("job")?.publicCopy()?.also { job ->
                val rootJobId = root.firstString("jobId", "activeJobId")
                if (rootJobId == requestedJobId) {
                    val publicRoot = root.publicCopy()
                    TUNING_JOB_DETAIL_FIELDS.forEach { field ->
                        if (!job.has(field) && publicRoot.has(field)) {
                            job.put(field, publicRoot.opt(field))
                        }
                    }
                }
            } ?: root.publicCopy()
        } else {
            root.publicCopy()
        }
        if (!normalized.has("jobId") && requestedJobId != null) {
            normalized.put("jobId", requestedJobId)
        }
        if (requestedJobId == null && !normalized.has("activeJobId")) {
            normalized.firstValue("jobId")?.let { normalized.put("activeJobId", it) }
        }
        if (!normalized.has("tuningJobState")) {
            normalized.firstValue("state")?.let { normalized.put("tuningJobState", it) }
        }
        if (!normalized.has("state")) {
            normalized.firstValue("tuningJobState")?.let { normalized.put("state", it) }
        }
        return result.copy(json = normalized.toString())
    }

    private fun coordinatorSignaturesObject(): JSONObject {
        val profile = runCatching { profileJson().toJsonObjectOrNull() }.getOrNull()
        val source = profile?.optJSONObject("signatures") ?: profile
        fun signature(vararg names: String): Any {
            val value = source?.firstValue(*names) as? String ?: return JSONObject.NULL
            return value.takeIf(SIGNATURE_TOKEN_PATTERN::matches) ?: JSONObject.NULL
        }
        return JSONObject()
            .put("desired", signature("desired", "desiredSignature"))
            .put("resolved", signature("resolved", "resolvedLoad", "resolvedLoadSignature"))
            .put("active", signature("active", "activeLoaded", "activeLoadedSignature"))
            .put("committed", signature("committed", "committedExecution", "committedExecutionSignature"))
            .put("override", signature("override", "runtimeOverride", "runtimeOverrideSignature"))
            .put("effective", signature("effective", "effectiveExecution", "effectiveExecutionSignature"))
    }

    private fun JSONObject?.stateForModel(modelId: String): JSONObject? {
        if (this == null || modelId.isBlank()) return null
        optJSONObject(modelId)?.let { return it }
        val data = optJSONArray("data") ?: return null
        for (index in 0 until data.length()) {
            val state = data.optJSONObject(index) ?: continue
            if (state.firstString("modelId", "id") == modelId) return state
        }
        return null
    }

    private fun JSONObject.publicCopy(stripExecutionState: Boolean = false): JSONObject {
        val copy = JSONObject()
        keys().asSequence().forEach { key ->
            val normalized = key.lowercase().replace("_", "").replace("-", "")
            if (normalized in PRIVATE_JSON_KEYS) return@forEach
            if (normalized.isSensitiveJsonKey()) return@forEach
            if (stripExecutionState && normalized in CATALOG_EXECUTION_KEYS) return@forEach
            when (val value = opt(key)) {
                is JSONObject -> copy.put(key, value.publicCopy(stripExecutionState))
                is org.json.JSONArray -> copy.put(key, value.publicCopy(stripExecutionState))
                is String -> copy.put(key, if (value.looksLikeAbsolutePath()) REDACTED else value)
                else -> copy.put(key, value)
            }
        }
        return copy
    }

    private fun org.json.JSONArray.publicCopy(stripExecutionState: Boolean): org.json.JSONArray {
        val copy = org.json.JSONArray()
        for (index in 0 until length()) {
            when (val value = opt(index)) {
                is JSONObject -> copy.put(value.publicCopy(stripExecutionState))
                is org.json.JSONArray -> copy.put(value.publicCopy(stripExecutionState))
                is String -> copy.put(if (value.looksLikeAbsolutePath()) REDACTED else value)
                else -> copy.put(value)
            }
        }
        return copy
    }

    private fun JSONObject.firstValue(vararg keys: String): Any? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return opt(key)
        }
        return null
    }

    private fun JSONObject.isImageGenerationCatalogEntry(): Boolean =
        optString("type").equals("image_generation", ignoreCase = true) ||
            optJSONObject("capabilities")?.optBoolean("image_generation", false) == true

    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.asSequence()
            .filter { has(it) && !isNull(it) }
            .map { optString(it).trim() }
            .firstOrNull { it.isNotBlank() }

    private fun JSONObject.firstBoolean(vararg keys: String): Boolean? {
        for (key in keys) {
            if (has(key) && !isNull(key)) return optBoolean(key)
        }
        return null
    }

    private fun JSONObject.firstPositiveInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val value = when (val raw = opt(key)) {
                is Number -> raw.toInt()
                is String -> raw.trim().toIntOrNull()
                else -> null
            }
            if (value != null && value > 0) return value
        }
        return null
    }

    private fun JSONObject.putIfMissing(key: String, value: Any?) {
        if (!has(key) || isNull(key)) putNullable(key, value)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun Any?.publicScalar(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is String -> takeIf(PUBLIC_TOKEN_PATTERN::matches)
        is Number, is Boolean -> this
        else -> null
    }

    private fun String.publicStateOr(fallback: String): String =
        trim().takeIf(PUBLIC_TOKEN_PATTERN::matches) ?: fallback

    private fun String.isSensitiveJsonKey(): Boolean =
        contains("rawoutput") ||
            contains("configjson") ||
            (contains("prompt") && !endsWith("tokens"))

    private fun String.toJsonObjectOrNull(): JSONObject? =
        runCatching { JSONObject(this) }.getOrNull()

    private fun String.looksLikeAbsolutePath(): Boolean =
        WINDOWS_ABSOLUTE_PATH.containsMatchIn(this) ||
            contains("\\\\") ||
            ANDROID_OR_UNIX_ABSOLUTE_PATH.containsMatchIn(this)

    private val PRIVATE_JSON_KEYS = setOf(
        "path",
        "absolutepath",
        "modelpath",
        "localpath",
        "bundlepath",
        "filepath",
        "apikey",
        "authorization",
        "bearer",
        "token",
        "authtoken",
        "accesstoken",
        "refreshtoken",
        "secret",
        "password",
        "credential",
        "credentials",
        "prompt",
        "rawprompt",
        "systemprompt",
        "userprompt",
        "messages",
        "chatmessages",
        "chatrequest",
        "requestbody",
        "responsebody",
        "session",
        "sessionid",
        "usercontent",
        "identityhash",
        "installationscopeid",
        "fingerprint",
        "androidid",
        "deviceid",
        "androidserial",
        "serialnumber",
        "imei",
        "meid"
    )
    private val CATALOG_EXECUTION_KEYS = setOf(
        "contextlength",
        "maxcontextlength",
        "nctx",
        "maxoutputtokens",
        "profileid",
        "profilerecordstate",
        "profileverificationlevel",
        "verification",
        "reloadrequired",
        "enginelifecycle",
        "tuningjobstate"
    )
    private val NON_LOADED_LIFECYCLES = setOf("unloaded", "loading", "stopping", "error")
    private val TUNING_JOB_DETAIL_FIELDS = setOf(
        "phase",
        "progress",
        "etaSeconds",
        "autoApply",
        "candidateProfileId",
        "candidates",
        "hardGates",
        "diff",
        "signatures",
        "failure",
        "cancellationRequested",
        "createdAt",
        "updatedAt",
        "lastHeartbeatAt"
    )
    private val NATIVE_METRIC_STRING_FIELDS = setOf(
        "backend",
        "generationStopReason",
        "mnnModelType",
        "splitMode",
        "cacheTypeK",
        "cacheTypeV",
        "flashAttn",
        "specType"
    )
    private val NATIVE_METRIC_FIELDS = NATIVE_METRIC_STRING_FIELDS + setOf(
        "loaded",
        "runnerReady",
        "modelFileSizeBytes",
        "mmapFallbackAllowed",
        "mmapPrefetchEnabled",
        "mmap",
        "mlock",
        "loadMs",
        "loadGeneration",
        "loadedAtMs",
        "requestCountSinceLoad",
        "lastRequestReset",
        "multimodalSystemPromptSuppressed",
        "multimodalHistorySuppressed",
        "promptTokens",
        "completionTokens",
        "generationSequence",
        "generationActive",
        "stopRequested",
        "contextShifts",
        "ttftMs",
        "prefillMs",
        "decodeMs",
        "decodeTps",
        "generatedSteps",
        "streamedBytes",
        "syncStepping",
        "nThreads",
        "nThreadsBatch",
        "nBatch",
        "nUbatch",
        "nCtx",
        "maxAllTokens",
        "maxNewTokens",
        "nGpuLayers",
        "mainGpu",
        "nCpuMoe",
        "nParallel",
        "cacheReuseThreshold",
        "specDraftNMax",
        "temperature",
        "topK",
        "topP",
        "minP",
        "repeatPenalty",
        "presencePenalty",
        "frequencyPenalty",
        "repeatLastN",
        "useJinja",
        "enableThinking",
        "visionReady",
        "backendReady",
        "backendDeviceCount",
        "mnnDebugTraceEnabled",
        "mnnDebugTraceTruncated",
        "mnnDebugPromptTruncated"
    )
    private const val REDACTED = "[redacted]"
    private const val MAX_PUBLIC_MESSAGE_LENGTH = 1024
    private const val MAX_PUBLIC_METRIC_STRING_LENGTH = 128
    private const val MAX_RECORDED_MEDIA_HASHES = 32
    private const val VISION_PREPARED_STAGE = "local_vision_input_prepared"
    private const val VISION_FAILED_STAGE = "local_vision_input_prepare_failed"
    private val PUBLIC_TOKEN_PATTERN = Regex("[A-Za-z0-9._:-]{1,256}")
    private val SIGNATURE_TOKEN_PATTERN = PUBLIC_TOKEN_PATTERN
    private val MEDIA_SHA256_PATTERN = Regex("[a-f0-9]{64}")
    private val MEDIA_PREPROCESSING_TOKEN_PATTERN = Regex("[a-z0-9_.-]{1,64}")
    private val WINDOWS_ABSOLUTE_PATH = Regex("(?i)(?:^|[^A-Za-z0-9])[A-Z]:[\\\\/]")
    private val ANDROID_OR_UNIX_ABSOLUTE_PATH =
        Regex("(?i)(?:^|[\\s=:\\[])/(?!v\\d+(?:/|$))[A-Za-z0-9._-]+/")
}

