package com.muyuchat.mca.dsh.overlay

/**
 * Pure JVM state of the floating overlay owner.
 *
 * The Android service only adds/removes one window and forwards events; every rule — visibility,
 * permission refusal, service death, leaked windows — is a total function here, so it is
 * unit-tested without a device. [DshOverlayController] is the single writer; every terminal
 * transition clears the attached-window and panel flags.
 */

/** Whether the app may add a `TYPE_APPLICATION_OVERLAY` window right now. */
enum class DshOverlayPermissionState { UNKNOWN, GRANTED, DENIED }

/** Lifecycle of the started overlay service, as the controller knows it. */
enum class DshOverlayServiceState { STOPPED, STARTING, RUNNING, FAILED }

enum class DshOverlayFailureCode { PERMISSION_DENIED, SERVICE_START_FAILED, WINDOW_REJECTED, INTERNAL }

/** Typed, prompt-free reason for a refused or failed overlay operation. */
data class DshOverlayFailure(val code: DshOverlayFailureCode, val message: String) {
    init {
        require(message.isNotBlank() && message.length <= MAX_MESSAGE_CHARS) {
            "overlay failure message must be 1..$MAX_MESSAGE_CHARS characters"
        }
        require(message.none(::isForbiddenCharacter)) {
            "overlay failure message must be a single prompt-free line"
        }
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 120

        /** Sanitizes adapter/provider text instead of trusting it in a failure message. */
        fun of(code: DshOverlayFailureCode, rawMessage: String?): DshOverlayFailure =
            DshOverlayFailure(code, sanitize(rawMessage, MAX_MESSAGE_CHARS, defaultMessage(code)))

        private fun defaultMessage(code: DshOverlayFailureCode): String = when (code) {
            DshOverlayFailureCode.PERMISSION_DENIED -> "未授予悬浮窗权限"
            DshOverlayFailureCode.SERVICE_START_FAILED -> "悬浮窗服务启动失败"
            DshOverlayFailureCode.WINDOW_REJECTED -> "系统拒绝了悬浮窗"
            DshOverlayFailureCode.INTERNAL -> "悬浮窗内部错误"
        }
    }
}

/**
 * Runtime facts the panel may display, supplied by a narrow [DshOverlayStatusProvider]. The provider
 * owns formatting; this type only keeps the two lines prompt-free and bounded.
 */
data class DshOverlayStatus(
    val runtimePhase: String,
    val generation: Long,
    val engineOnline: Boolean,
    val capabilitySummary: String
) {
    init {
        require(runtimePhase.isNotBlank() && runtimePhase.length <= MAX_PHASE_CHARS)
        require(generation >= 0L) { "overlay status generation must be non-negative" }
        require(capabilitySummary.length <= MAX_SUMMARY_CHARS)
        require(runtimePhase.none(::isForbiddenCharacter) && capabilitySummary.none(::isForbiddenCharacter))
    }

    val headline: String
        get() = "$runtimePhase · gen $generation"

    companion object {
        const val MAX_PHASE_CHARS = 40
        const val MAX_SUMMARY_CHARS = 200

        /** Before the runtime is connected; the panel says so instead of inventing a state. */
        fun unavailable(): DshOverlayStatus =
            DshOverlayStatus("UNKNOWN", 0L, engineOnline = false, capabilitySummary = "运行时未连接")

        fun of(
            runtimePhase: String?,
            generation: Long,
            engineOnline: Boolean,
            capabilitySummary: String?
        ): DshOverlayStatus = DshOverlayStatus(
            runtimePhase = sanitize(runtimePhase, MAX_PHASE_CHARS, "UNKNOWN"),
            generation = generation.coerceAtLeast(0L),
            engineOnline = engineOnline,
            capabilitySummary = sanitize(capabilitySummary, MAX_SUMMARY_CHARS, "能力状态未知")
        )
    }
}

/** Narrow provider for the two panel lines. Default is "not connected". */
fun interface DshOverlayStatusProvider {
    fun status(): DshOverlayStatus

    companion object {
        val Unavailable: DshOverlayStatusProvider =
            DshOverlayStatusProvider { DshOverlayStatus.unavailable() }
    }
}

/** Single immutable state written only by [DshOverlayStateReducer]. */
data class DshOverlayState(
    val permission: DshOverlayPermissionState = DshOverlayPermissionState.UNKNOWN,
    val service: DshOverlayServiceState = DshOverlayServiceState.STOPPED,
    val windowAttached: Boolean = false,
    val panelOpen: Boolean = false,
    val status: DshOverlayStatus = DshOverlayStatus.unavailable(),
    val failure: DshOverlayFailure? = null
) {
    /** What `isVisible()` reports: a real attached window only. */
    val visible: Boolean
        get() = windowAttached

    /** What `isRunning()` reports: attached window owned by a running service. */
    val running: Boolean
        get() = windowAttached && service == DshOverlayServiceState.RUNNING

    val engineOnline: Boolean
        get() = status.engineOnline
}

/** Everything that may change overlay state; adapters translate platform callbacks into these. */
sealed interface DshOverlayEvent {
    data class PermissionObserved(val permission: DshOverlayPermissionState) : DshOverlayEvent

    data object ShowRequested : DshOverlayEvent

    data class StartFailed(val failure: DshOverlayFailure) : DshOverlayEvent

    data object WindowAttached : DshOverlayEvent

    data object ServiceDestroyed : DshOverlayEvent

    data object HideRequested : DshOverlayEvent

    data object MarkTapped : DshOverlayEvent

    data class StatusUpdated(val status: DshOverlayStatus) : DshOverlayEvent
}

/**
 * The overlay policy as one total function:
 *  - no permission ⇒ show is refused and the service is never left starting (hide is a no-op);
 *  - show is idempotent while a window is attached;
 *  - hide, service death and permission loss clear window + panel (no leaked window);
 *  - the mark tap only toggles the panel while a window is attached.
 */
object DshOverlayStateReducer {

    fun reduce(state: DshOverlayState, event: DshOverlayEvent): DshOverlayState = when (event) {
        is DshOverlayEvent.PermissionObserved ->
            if (event.permission == DshOverlayPermissionState.GRANTED) {
                state.copy(permission = event.permission)
            } else {
                state.copy(
                    permission = event.permission,
                    service = DshOverlayServiceState.STOPPED,
                    windowAttached = false,
                    panelOpen = false,
                    failure = if (event.permission == DshOverlayPermissionState.DENIED) {
                        overlayPermissionFailure()
                    } else {
                        state.failure
                    }
                )
            }

        DshOverlayEvent.ShowRequested -> when {
            state.permission != DshOverlayPermissionState.GRANTED -> state.copy(
                panelOpen = false,
                failure = overlayPermissionFailure()
            )
            state.windowAttached -> state.copy(
                service = DshOverlayServiceState.RUNNING,
                failure = null
            )
            else -> state.copy(service = DshOverlayServiceState.STARTING, failure = null)
        }

        is DshOverlayEvent.StartFailed -> state.copy(
            service = DshOverlayServiceState.FAILED,
            windowAttached = false,
            panelOpen = false,
            failure = event.failure
        )

        DshOverlayEvent.WindowAttached -> state.copy(
            service = DshOverlayServiceState.RUNNING,
            windowAttached = true,
            failure = null
        )

        DshOverlayEvent.ServiceDestroyed -> state.copy(
            service = DshOverlayServiceState.STOPPED,
            windowAttached = false,
            panelOpen = false
        )

        DshOverlayEvent.HideRequested -> state.copy(
            service = DshOverlayServiceState.STOPPED,
            windowAttached = false,
            panelOpen = false
        )

        DshOverlayEvent.MarkTapped ->
            if (state.windowAttached) state.copy(panelOpen = !state.panelOpen) else state

        is DshOverlayEvent.StatusUpdated -> state.copy(status = event.status)
    }
}

private fun isForbiddenCharacter(character: Char): Boolean =
    character.code <= 0x1F || character.code in 0x7F..0x9F

/** Shared typed refusal, so the reducer and the service cannot drift on the message. */
internal fun overlayPermissionFailure(): DshOverlayFailure = DshOverlayFailure.of(
    DshOverlayFailureCode.PERMISSION_DENIED,
    "未授予悬浮窗权限（SYSTEM_ALERT_WINDOW）"
)
/** Collapses control characters/whitespace runs, then caps without splitting a surrogate pair. */
private fun sanitize(raw: String?, limit: Int, fallback: String): String {
    if (raw.isNullOrBlank()) return fallback
    val builder = StringBuilder(minOf(raw.length, limit + 1))
    var pendingSpace = false
    for (character in raw) {
        if (isForbiddenCharacter(character)) {
            pendingSpace = builder.isNotEmpty()
            continue
        }
        if (pendingSpace) {
            builder.append(' ')
            pendingSpace = false
        }
        builder.append(character)
        if (builder.length > limit) break
    }
    var text = builder.toString().trim()
    if (text.length > limit) text = text.take(limit)
    if (text.isNotEmpty() && text.last().isHighSurrogate()) text = text.dropLast(1)
    return text.ifEmpty { fallback }
}
