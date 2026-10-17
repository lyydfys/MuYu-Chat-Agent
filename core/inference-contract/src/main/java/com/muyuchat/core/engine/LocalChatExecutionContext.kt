package com.muyuchat.core.engine

import org.json.JSONObject
import java.util.UUID

/**
 * Pure boundary handles for engine-owned execution capabilities.
 *
 * The contract module only needs identity and lifecycle behavior here. The
 * concrete authorization and lease implementations remain in core:engine.
 */
interface EngineLoadAuthorizationHandle

interface EngineHotOverrideAuthorizationHandle

interface EngineLifecycleLeaseHandle {
    val leaseId: String

    suspend fun release()
}

data class LocalChatExecutionContext(
    val requestId: String = UUID.randomUUID().toString(),
    val loadAuthorization: EngineLoadAuthorizationHandle? = null,
    /** Kept for source compatibility; a Boolean alone no longer grants trust. */
    val allowTrustedHotOverride: Boolean = false,
    val hotOverrideAuthorization: EngineHotOverrideAuthorizationHandle? = null,
    val pendingProfileDisposition: PendingProfileDisposition = PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS,
    val lifecycleLease: EngineLifecycleLeaseHandle? = null,
    /** Deterministic internal transforms such as prompt translation must not receive wall-clock text. */
    val includeDeviceClockContext: Boolean = true,
    /**
     * Optional request-scoped observer for privacy-reviewed local-vision preparation diagnostics.
     * The engine isolates observer failures from inference and gives this sink its own JSON copy so
     * callers cannot mutate the existing debug diagnostic or another request's evidence.
     */
    val visionDiagnosticSink: ((String, JSONObject) -> Unit)? = null
)

enum class PendingProfileDisposition {
    /** Formal user/apply transaction: commit the exact active candidate before Done. */
    COMMIT_ON_VISIBLE_SUCCESS,

    /** Keep the exact candidate active under an exclusive lease for an external correctness gate. */
    DEFER_TO_LEASE_HOLDER,

    /** Formal candidate evaluation: run under the candidate, then restore the locked rollback target. */
    ROLLBACK_AFTER_REQUEST
}