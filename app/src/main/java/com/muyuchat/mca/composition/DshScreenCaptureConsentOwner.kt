package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentCodes
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase

/**
 * Owns the claim, platform grant and cleanup transaction for the one pending capture request.
 *
 * The Activity launches the system dialog; the capability assembly owns the real projection.
 * This owner only connects their results to the consent state machine. Keeping the payload
 * generic lets the lifecycle and failure contract run without Android platform objects in tests.
 * The injected operations are not invoked during construction, preserving lazy DSH startup.
 */
internal class DshScreenCaptureConsentOwner<T>(
    private val consent: DshScreenCaptureConsentController,
    private val grantCapture: (Int, T?) -> Boolean,
    private val grantedResultCode: Int,
    private val cancelledResultCode: Int,
) {
    /**
     * Claims exactly once. The capture foreground service is started by [grantCapture] after the
     * system result: starting it before the dialog leaves a pending foreground-service start while
     * the dialog owns the foreground, and Android kills the app when that start times out.
     */
    fun begin(requestId: Long): Boolean = consent.claim(requestId)

    /** A missing manager/dialog handler must release the already prepared capture service. */
    fun reject(requestId: Long, code: String) {
        if (!consent.fail(requestId, code)) return
        grantCapture(cancelledResultCode, null)
    }

    /** A late result after host stop must never arm a projection for a retired generation. */
    fun acceptResult(resultCode: Int, data: T?) {
        if (consent.state.value.phase != DshScreenCaptureConsentPhase.AWAITING_USER) return
        if (resultCode == grantedResultCode && data != null) {
            if (grantCapture(resultCode, data)) {
                consent.onGranted()
            } else {
                consent.onRejected(
                    code = DshScreenCaptureConsentCodes.CONSENT_REQUIRED,
                    detail = "the system did not hand over a screen-capture projection"
                )
            }
            return
        }
        // Cancelled / empty result: the real owner releases the prepared foreground service.
        grantCapture(resultCode, null)
        consent.onCancelled(detail = "the user cancelled the screen-capture dialog")
    }

    /** Cancels an unanswered dialog during host teardown, even if platform cleanup throws. */
    fun closePending() {
        if (consent.state.value.phase != DshScreenCaptureConsentPhase.AWAITING_USER) return
        runCatching { grantCapture(cancelledResultCode, null) }
        consent.onCancelled(detail = "the host activity was destroyed before the consent result")
    }
}
