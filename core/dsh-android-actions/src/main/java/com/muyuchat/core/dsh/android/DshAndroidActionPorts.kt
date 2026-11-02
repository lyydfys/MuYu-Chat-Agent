package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityState

/**
 * Ports the six App-local Android routes call.
 *
 * The routes never touch an Android API. Everything the platform owns sits behind these narrow
 * interfaces, which is why the policy (validation, limits, error mapping, availability refusal)
 * is plain JVM code and the unit tests exercise it with fakes instead of Android stubs.
 *
 * Ownership: the app composition creates one adapter instance per application process and keeps
 * it for the lifetime of the capability server. Every method is `suspend` so an adapter can move
 * blocking work (usage query, notification post) off the caller's dispatcher, and cancellation of
 * the calling coroutine is observed by the adapter at the next suspension point.
 */

/** Result of one notification post. The id is deterministic for identical content (see the route). */
data class DshPostedNotification(val notificationId: Int)

sealed interface DshNotificationPostOutcome {
    data class Posted(val notificationId: Int) : DshNotificationPostOutcome

    /** Typed refusal: permission denied, notifications disabled, no launch intent for the app. */
    data class Failed(val failure: DshAndroidActionFailure) : DshNotificationPostOutcome
}

/**
 * `POST /notify` adapter.
 *
 * [state] drives the availability projection; [post] must replace the existing notification with
 * the same [notificationId] instead of stacking a second one, which is what makes a retried
 * request idempotent on the wire. [cancel]/[cancelAll] are the lifecycle half: the runtime owner
 * clears posted notices when the generation stops.
 */
interface DshNotificationPort {
    fun state(): DshCapabilityState

    suspend fun post(
        title: String,
        text: String,
        notificationId: Int
    ): DshNotificationPostOutcome

    suspend fun cancel(notificationId: Int): Boolean

    suspend fun cancelAll(): Int
}

/** One prompt-free snapshot of the app's overlay window and engine state. */
data class DshOverlaySnapshot(
    val running: Boolean,
    val engineUp: Boolean,
    val granted: Boolean,
    val visible: Boolean,
    val message: String? = null
) {
    init {
        require(message == null || message.length <= DshAndroidActionLimits.MAX_STATUS_DETAIL_CHARS) {
            "overlay message exceeds the status detail limit"
        }
        require(message == null || message.none(::isForbiddenFailureCharacter)) {
            "overlay message must be a prompt-free single line"
        }
    }
}

/**
 * `GET /overlay` adapter: the app-owned overlay window controller plus the system
 * `SYSTEM_ALERT_WINDOW` check. `show`/`hide` report the state *after* the request, so the route can
 * derive `changed` from before/after instead of claiming a change it did not observe.
 */
interface DshOverlayPort {
    /** Projection input: overlay permission state (declared / granted / disabled), no window touch. */
    fun state(): DshCapabilityState

    suspend fun snapshot(): DshOverlaySnapshot

    suspend fun show(): DshOverlaySnapshot

    suspend fun hide(): DshOverlaySnapshot
}

/** One app entry in the usage report. [label] is already a display label, never a package dump. */
data class DshUsageApp(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long
)

sealed interface DshUsageQueryOutcome {
    data class Loaded(val apps: List<DshUsageApp>) : DshUsageQueryOutcome

    /** Typed refusal: no usage-access grant, service disabled or the platform threw. */
    data class Unavailable(val failure: DshAndroidActionFailure) : DshUsageQueryOutcome
}

/** `GET /usage` adapter over `UsageStatsManager`. */
interface DshUsageStatsPort {
    /** `GRANTED`, `DENIED`, `NOT_DECLARED`, `NOT_ENABLED` or `UNSUPPORTED`, with the appop reason. */
    fun access(): DshCapabilityState

    suspend fun query(days: Int): DshUsageQueryOutcome
}

sealed interface DshClipboardReadOutcome {
    data class Text(val value: String) : DshClipboardReadOutcome

    /** The clipboard exists but holds no text item. Not an error. */
    data object Empty : DshClipboardReadOutcome

    /** Typed refusal: Android only allows clipboard reads from a focused surface. */
    data class Unavailable(val failure: DshAndroidActionFailure) : DshClipboardReadOutcome
}

/**
 * `POST /clipboard` adapter.
 *
 * [write] sets the sensitive flag so Android 13+ hides the clipboard preview; a non-null return is
 * a typed failure and means the platform refused the write.
 */
interface DshClipboardPort {
    fun state(): DshCapabilityState

    suspend fun read(): DshClipboardReadOutcome

    suspend fun write(text: String, sensitive: Boolean): DshAndroidActionFailure?
}

/** Values the platform reports after a `Settings.System` (or volume stream) write. */
data class DshSettingWriteOutcome(
    val oldValue: String?,
    val newValue: String?,
    val stream: String? = null,
    val level: Int? = null,
    val maxLevel: Int? = null
)

sealed interface DshSettingWriteResult {
    data class Written(val outcome: DshSettingWriteOutcome) : DshSettingWriteResult

    data class Rejected(val failure: DshAndroidActionFailure) : DshSettingWriteResult
}

/**
 * `POST /setting` adapter for the `system` namespace only.
 *
 * The reference App read the old value through its privileged shell and wrote through
 * `WRITE_SETTINGS`; MCA reads and writes `Settings.System` directly, so the before/after check does
 * not depend on root/Shizuku. Global/Secure writes stay on the privileged `/shell` route, which is
 * explicit about its broad power.
 */
interface DshAppSettingsPort {
    /**
     * Whether [key] may be written right now. The adapter decides per key: `Settings.System`
     * writes need `WRITE_SETTINGS`, while the four volume keys the reference maps to audio streams
     * need no such grant, so they must not be refused with a settings permission error.
     */
    fun writeAccess(key: String): DshCapabilityState

    suspend fun read(key: String): String?

    suspend fun write(key: String, value: String): DshSettingWriteResult
}
