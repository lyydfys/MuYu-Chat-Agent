package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest

/**
 * Narrow bridge between the Local API and the app-owned runtime coordinator.
 *
 * The API module deliberately owns this contract so a coordinator can be attached without
 * introducing a dependency from core runtime modules back to api:local.
 */
interface LocalApiControlPlane {
    fun busyState(): LocalApiBusyState = LocalApiBusyState.IDLE

    fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult =
        LocalApiPreflightResult.Ready

    fun profileJson(): String = "{}"

    fun tuningJson(): String = "{}"

    /**
     * Returns the public, redacted state of one tuning job. A null id means the current job.
     *
     * This is deliberately a synchronous control-plane call. The Local API must not reach into
     * a repository, engine, or tuning worker directly: the app-owned coordinator is the only
     * authority for job state and lifecycle transitions. A current lookup must return a persisted
     * RECOVERING job after process restart. An explicit lookup should include persisted progress,
     * candidate hard-gate outcomes, signature/diff evidence, and a redacted failure classification.
     */
    fun tuningJob(jobId: String?): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_job_query_not_implemented",
            message = "This runtime coordinator does not expose persisted tuning job state yet."
        )

    /**
     * Creates one persisted tuning job through the app-owned coordinator. `standard` is a real
     * mode, not an alias for quick. When `autoApply` is false a successful candidate must remain
     * staged until [applyTuningJob] is called; the create request must not commit it implicitly.
     * The live HTTP server suppresses duplicate dispatch for an Idempotency-Key. The key is still
     * forwarded because the persisted coordinator journal must provide process-restart durability
     * and exact replay beyond the bounded in-memory acceleration cache.
     */
    fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotencyKey: String
    ): LocalApiControlResult = LocalApiControlResult.Rejected(
        httpStatus = 501,
        code = "tuning_job_create_not_implemented",
        message = "This runtime coordinator does not expose tuning job creation yet."
    )

    fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = createTuningJob(request, idempotency.key)

    /** Idempotent pause transition, authorized by the app-owned coordinator. */
    fun pauseTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_control_not_implemented",
            message = "This runtime coordinator does not expose tuning controls yet."
        )

    fun pauseTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = pauseTuningJob(jobId, idempotency.key)

    /** Idempotent resume transition, authorized by the app-owned coordinator. */
    fun resumeTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_control_not_implemented",
            message = "This runtime coordinator does not expose tuning controls yet."
        )

    fun resumeTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = resumeTuningJob(jobId, idempotency.key)

    /** Idempotent cancel transition, authorized by the app-owned coordinator. */
    fun cancelTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_control_not_implemented",
            message = "This runtime coordinator does not expose tuning controls yet."
        )

    fun cancelTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = cancelTuningJob(jobId, idempotency.key)

    /** Applies an already validated staged candidate; it never creates a candidate implicitly. */
    fun applyTuningJob(jobId: String, idempotencyKey: String): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_apply_not_implemented",
            message = "This runtime coordinator does not expose staged candidate application yet."
        )

    fun applyTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = applyTuningJob(jobId, idempotency.key)

    /** Rolls the current model back to its persisted last-known-good profile. */
    fun rollbackTuning(idempotencyKey: String): LocalApiControlResult =
        LocalApiControlResult.Rejected(
            httpStatus = 501,
            code = "tuning_rollback_not_implemented",
            message = "This runtime coordinator does not expose profile rollback yet."
        )

    fun rollbackTuning(idempotency: LocalApiIdempotencyContext): LocalApiControlResult =
        rollbackTuning(idempotency.key)
}

/** Canonical, non-secret identity supplied to the durable coordinator journal. */
data class LocalApiIdempotencyContext(
    val key: String,
    val requestFingerprint: String
)

data class LocalApiTuningJobCreateRequest(
    val modelId: String,
    val mode: String,
    val autoApply: Boolean,
    val performancePreference: String? = null
)

/**
 * Structured result shared by read-only tuning queries and control actions.
 *
 * `json` is supplied by the coordinator so it can apply domain-aware redaction. The Local API
 * validates it and applies a final prompt/session/path/credential denylist before writing it.
 */
sealed interface LocalApiControlResult {
    data class Success(
        val json: String,
        val httpStatus: Int = 200,
        val retryAfterMs: Long = 0L
    ) : LocalApiControlResult

    data class Rejected(
        val httpStatus: Int,
        val code: String,
        val message: String,
        val detailsJson: String = "{}",
        val retryAfterMs: Long = 0L
    ) : LocalApiControlResult
}

data class LocalApiBusyState(
    val busy: Boolean,
    val code: String = "runtime_busy",
    val message: String = "The local model runtime is busy.",
    val retryAfterMs: Long = 0L,
    val detailsJson: String = "{}"
) {
    companion object {
        val IDLE = LocalApiBusyState(
            busy = false,
            code = "idle",
            message = ""
        )
    }
}

data class LocalApiPreflightRequest(
    val route: String,
    val streaming: Boolean,
    val requestedModel: String?,
    val chatRequest: ChatRequest
)

sealed interface LocalApiPreflightResult {
    data object Ready : LocalApiPreflightResult

    data class Rejected(
        val httpStatus: Int = 409,
        val code: String,
        val message: String,
        val retryAfterMs: Long = 0L,
        val detailsJson: String = "{}"
    ) : LocalApiPreflightResult
}
