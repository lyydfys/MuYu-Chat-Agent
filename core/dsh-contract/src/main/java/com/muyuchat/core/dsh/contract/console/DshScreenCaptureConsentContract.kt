package com.muyuchat.core.dsh.contract.console

/**
 * Typed consent lifecycle of the one screen-capture grant the app may hold.
 *
 * The virtual-screen family has two independent gates: its authenticated bridge must be mounted
 * (a generation fact owned by the capability bridge) and the user must have accepted the system
 * `MediaProjection` consent (a user-presence fact owned by the visible host Activity). This type
 * only describes the second gate, so the console can offer the consent entry and later show the
 * truth instead of inferring "granted" from a cached boolean.
 *
 * The projection never carries a projection token, an Intent, a raw URI or a platform object.
 */
enum class DshScreenCaptureConsentPhase {
    /** Nothing was requested in this app process. */
    IDLE,

    /** A console request was recorded and the host Activity has not claimed it yet. */
    REQUESTED,

    /** The capture service is prepared and the host Activity launched the system dialog. */
    AWAITING_USER,

    /** A real `MediaProjection` was accepted for this app process. */
    GRANTED,

    /** The user dismissed the system dialog; nothing was armed. */
    CANCELLED,

    /** The platform or the app refused the grant; [DshScreenCaptureConsentState.code] is typed. */
    REJECTED,

    /** The managed host stopped and the app owner released the grant. */
    RELEASED
}

/**
 * One consent snapshot. [code] is a stable wire-level token (`consent_required` and friends), never
 * a user-facing message; [detail] is a bounded, prompt-free engineering note supplied by the owner.
 */
data class DshScreenCaptureConsentState(
    val phase: DshScreenCaptureConsentPhase = DshScreenCaptureConsentPhase.IDLE,
    /** Monotonic request identity; zero means no request exists. */
    val requestId: Long = 0L,
    val code: String? = null,
    val detail: String? = null
) {
    init {
        require(requestId >= 0L) { "screen-capture request id must be non-negative" }
        require(code == null || isPromptFreeToken(code, DshConsoleLimits.MAX_CODE_CHARS)) {
            "screen-capture consent code must be a short stable token"
        }
        require(detail == null || isPromptFreeLine(detail, DshConsoleLimits.MAX_DETAIL_CHARS)) {
            "screen-capture consent detail must be a bounded prompt-free line"
        }
    }

    /** True while a request still expects the system dialog or the user's answer. */
    val isPending: Boolean
        get() = phase == DshScreenCaptureConsentPhase.REQUESTED ||
            phase == DshScreenCaptureConsentPhase.AWAITING_USER
}

/** Stable codes reported by the app consent owner; the UI maps them, the owner never localises. */
object DshScreenCaptureConsentCodes {
    /** The grant is absent, exactly like `DshVscreenErrorCode.CONSENT_REQUIRED.wire`. */
    const val CONSENT_REQUIRED = "consent_required"

    /** The mediaProjection foreground service could not be started before the dialog. */
    const val FOREGROUND_SERVICE_UNAVAILABLE = "foreground_service_unavailable"

    /** This build/device has no `MediaProjectionManager` intent to launch. */
    const val MEDIA_PROJECTION_UNAVAILABLE = "media_projection_unavailable"

    /** The host Activity could not launch the system consent dialog. */
    const val CONSENT_DIALOG_UNAVAILABLE = "consent_dialog_unavailable"

    /** The managed host stopped and the owner released the grant. */
    const val HOST_STOPPED = "host_stopped"
}
