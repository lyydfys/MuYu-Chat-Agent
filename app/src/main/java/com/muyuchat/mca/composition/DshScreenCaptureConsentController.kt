package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentCodes
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-owned state machine of the screen-capture consent request (D15 virtual screen).
 *
 * It is deliberately Android-free: the Activity owns the system dialog and the app capability
 * assembly owns the real `MediaProjection`; this controller only remembers which request is live
 * and how it ended. That keeps the transitions unit-testable and prevents a second owner of the
 * projection.
 *
 * [isGrantLive] is a read-only probe of the real app grant (an unused grant expires after a short
 * TTL). It lets [projectedState] and [request] reconcile a stored GRANTED with the platform fact
 * without mutating state on a read, so the console never shows "已授权" for a dead projection and
 * the user can request consent again.
 *
 * Lifecycle contract:
 * - [request] is accepted only from a non-pending, non-granted state and always allocates a new
 *   [DshScreenCaptureConsentState.requestId];
 * - [claim] is exactly-once per request, so an Activity recreation (or a re-run composition
 *   effect) never launches the system dialog twice for the same request;
 * - a pending request can end as [DshScreenCaptureConsentPhase.CANCELLED] or
 *   [DshScreenCaptureConsentPhase.REJECTED] with a typed code; neither arms a projection;
 * - [onCaptureReleased] and [onHostStopped] retire a stored grant after the real owner released it.
 *
 * The controller never logs and never carries an Intent or projection object.
 */
class DshScreenCaptureConsentController(
    /**
     * Read-only probe of the real projection; true only while the platform grant is still live.
     * The default trusts the stored state for a pure/test machine; production always injects the
     * capability owner's real probe.
     */
    private val isGrantLive: () -> Boolean = { true }
) {

    private val lock = Any()
    private val mutableState = MutableStateFlow(DshScreenCaptureConsentState())

    /** Read-only consent projection consumed by the console and the host Activity. */
    val state: StateFlow<DshScreenCaptureConsentState> = mutableState.asStateFlow()

    private var nextRequestId = 0L

    /**
     * Console projection: the stored state reconciled with the live platform grant. A pure read;
     * a dead projection is reported as [DshScreenCaptureConsentPhase.RELEASED] without writing the
     * stored state (the console port must not mutate an owner on `snapshot()`).
     */
    fun projectedState(): DshScreenCaptureConsentState = synchronized(lock) { liveLocked() }

    /** Records one new request; false when a request is pending or a grant already exists. */
    fun request(): Boolean = synchronized(lock) {
        val current = liveLocked()
        if (current.isPending || current.phase == DshScreenCaptureConsentPhase.GRANTED) return false
        nextRequestId += 1L
        mutableState.value = DshScreenCaptureConsentState(
            phase = DshScreenCaptureConsentPhase.REQUESTED,
            requestId = nextRequestId
        )
        true
    }

    /**
     * Marks the request as being shown to the user. Only the first claim of the live request id
     * succeeds, which is what makes the Activity launch idempotent across recreation.
     */
    fun claim(requestId: Long): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.REQUESTED || current.requestId != requestId) {
            return false
        }
        mutableState.value = current.copy(phase = DshScreenCaptureConsentPhase.AWAITING_USER)
        true
    }

    /** Ends a claimed/unclaimed request with a typed refusal code; no projection is armed. */
    fun fail(requestId: Long, code: String, detail: String? = null): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.requestId != requestId || !current.isPending) return false
        mutableState.value = current.copy(
            phase = DshScreenCaptureConsentPhase.REJECTED,
            code = code,
            detail = detail
        )
        true
    }

    /** The platform granted a real projection. */
    fun onGranted(): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.AWAITING_USER) return false
        mutableState.value = current.copy(
            phase = DshScreenCaptureConsentPhase.GRANTED,
            code = null,
            detail = null
        )
        true
    }

    /** The user dismissed the consent dialog; the grant stays absent and typed. */
    fun onCancelled(detail: String? = null): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.AWAITING_USER) return false
        mutableState.value = current.copy(
            phase = DshScreenCaptureConsentPhase.CANCELLED,
            code = DshScreenCaptureConsentCodes.CONSENT_REQUIRED,
            detail = detail
        )
        true
    }

    /** The accepted result did not produce a projection; keep the typed consent code. */
    fun onRejected(code: String, detail: String? = null): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.AWAITING_USER) return false
        mutableState.value = current.copy(
            phase = DshScreenCaptureConsentPhase.REJECTED,
            code = code,
            detail = detail
        )
        true
    }

    /**
     * The capability owner released the real projection and stopped the capture notification.
     *
     * This is the authoritative release fact: it also covers a failed host start/restart where the
     * runtime never publishes STOPPED, so the console cannot keep a stale granted row that would
     * block a new consent request.
     */
    fun onCaptureReleased(): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.GRANTED) return false
        mutableState.value = current.copy(
            phase = DshScreenCaptureConsentPhase.RELEASED,
            code = DshScreenCaptureConsentCodes.HOST_STOPPED,
            detail = "the app capture owner released the projection"
        )
        true
    }

    /**
     * The managed host is confirmed stopped. The capability owner has already released the real
     * projection and stopped the capture notification; this only retires the projection of it.
     */
    fun onHostStopped(): Boolean = synchronized(lock) {
        val current = mutableState.value
        when (current.phase) {
            DshScreenCaptureConsentPhase.GRANTED -> mutableState.value = current.copy(
                phase = DshScreenCaptureConsentPhase.RELEASED,
                code = DshScreenCaptureConsentCodes.HOST_STOPPED,
                detail = "the managed host is confirmed stopped"
            )

            // A dialog that is still open belongs to a generation that no longer exists; nothing
            // may be armed for it, and a late result is ignored by the owner.
            DshScreenCaptureConsentPhase.REQUESTED,
            DshScreenCaptureConsentPhase.AWAITING_USER -> mutableState.value = current.copy(
                phase = DshScreenCaptureConsentPhase.CANCELLED,
                code = DshScreenCaptureConsentCodes.HOST_STOPPED,
                detail = "the managed host stopped before consent completed"
            )

            else -> return false
        }
        true
    }

    /** Stored state reconciled with the live platform grant; never writes. */
    private fun liveLocked(): DshScreenCaptureConsentState {
        val current = mutableState.value
        if (current.phase != DshScreenCaptureConsentPhase.GRANTED) return current
        val live = runCatching { isGrantLive() }.getOrDefault(false)
        return if (live) {
            current
        } else {
            current.copy(
                phase = DshScreenCaptureConsentPhase.RELEASED,
                code = DshScreenCaptureConsentCodes.HOST_STOPPED,
                detail = "the screen-capture projection is no longer live"
            )
        }
    }
}
