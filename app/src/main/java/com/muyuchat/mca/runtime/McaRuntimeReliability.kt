package com.muyuchat.mca.runtime

/**
 * Runtime reliability rules: which Web port to bind, which refresh rate to request, and when the
 * watchdog may restart the host.
 *
 * All three are pure decisions with injected facts (port probe, display modes, probe outcome), so the
 * fallback ladder, the frame-rate degradation and the restart throttle are testable without a socket,
 * a display or a coroutine.
 */

/** Port selection outcome; [fellBack] is what the UI must surface instead of silently moving. */
internal sealed interface RuntimePortChoice {
    data class Chosen(
        val port: Int,
        val preferred: Int,
        val attempts: Int
    ) : RuntimePortChoice {
        val fellBack: Boolean get() = port != preferred

        /** "原端口被占用，已回退到 X" when it moved; null when the preferred port was free. */
        val fallbackNotice: String?
            get() = if (fellBack) "原端口 $preferred 被占用，已回退到 $port" else null
    }

    data class Rejected(val reason: String) : RuntimePortChoice
}

internal object RuntimePortPlanner {

    /** Ports below 1024 are system-reserved on Android and never offered to the user. */
    const val MIN_PORT: Int = 1024
    const val MAX_PORT: Int = 65535

    /** Tries the configured port and at most this many ports above it. */
    const val MAX_CANDIDATES: Int = 10

    fun isValidPort(port: Int): Boolean = port in MIN_PORT..MAX_PORT

    /** `[preferred, preferred+1, …]`, clipped to the valid range and to [MAX_CANDIDATES]. */
    fun candidates(preferred: Int): List<Int> {
        if (!isValidPort(preferred)) return emptyList()
        return (preferred..MAX_PORT).take(MAX_CANDIDATES).toList()
    }

    /**
     * Picks the first free port, starting at [preferred].
     *
     * [isAvailable] is the injected bind probe. A reserved/out-of-range preference is refused rather
     * than clamped: silently binding 1024 when the user asked for 80 would be a port they never chose.
     */
    fun choose(preferred: Int, isAvailable: (Int) -> Boolean): RuntimePortChoice {
        if (!isValidPort(preferred)) {
            return RuntimePortChoice.Rejected("端口必须在 $MIN_PORT–$MAX_PORT 之间，当前为 $preferred")
        }
        val candidates = candidates(preferred)
        candidates.forEachIndexed { index, port ->
            if (isAvailable(port)) {
                return RuntimePortChoice.Chosen(port = port, preferred = preferred, attempts = index + 1)
            }
        }
        return RuntimePortChoice.Rejected(
            "端口 $preferred 起的 ${candidates.size} 个候选端口都被占用，请换一个端口或释放占用"
        )
    }
}

/** User-visible refresh-rate ceiling. `null` target means "let the system decide". */
internal enum class FrameRatePreference(val label: String, val targetHz: Int?) {
    FOLLOW_SYSTEM("跟随系统", null),
    HZ_60("60 Hz", 60),
    HZ_90("90 Hz", 90),
    HZ_120("120 Hz", 120);

    companion object {
        fun fromWireName(raw: String?): FrameRatePreference =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: FOLLOW_SYSTEM
    }
}

/** One display mode as the platform reports it. */
internal data class DisplayModeOption(val modeId: Int, val refreshRateHz: Float)

internal sealed interface FrameRateChoice {
    /**
     * Leave the decision to the platform (mode id when known, otherwise the system default).
     * [unsupportedCeilingHz] is set when the user's ceiling cannot be honoured by any mode: the app
     * then does not request a rate above the ceiling and the UI explains the degradation.
     */
    data class FollowSystem(
        val modeId: Int?,
        val unsupportedCeilingHz: Int? = null
    ) : FrameRateChoice

    /** Request exactly this mode; [degradedFromHz] is set when the ceiling was not available. */
    data class Request(val modeId: Int, val hz: Float, val degradedFromHz: Int? = null) : FrameRateChoice

    data object Unavailable : FrameRateChoice
}

internal object FrameRatePlanner {

    /**
     * Chooses the mode for the requested ceiling.
     *
     * "Ceiling" is literal: the highest mode at or below the requested rate wins, so asking for 90 Hz
     * on a 120 Hz panel does not silently run the panel at 120. When no mode fits, the slowest mode is
     * chosen and [FrameRateChoice.Request.degradedFromHz] tells the UI it had to degrade instead of
     * pretending the request was honoured.
     */
    fun choose(
        modes: List<DisplayModeOption>,
        preference: FrameRatePreference,
        systemModeId: Int? = null
    ): FrameRateChoice {
        val usable = modes.filter { it.refreshRateHz.isFinite() && it.refreshRateHz > 0f }
        if (usable.isEmpty()) return FrameRateChoice.Unavailable
        val target = preference.targetHz ?: return FrameRateChoice.FollowSystem(systemModeId)
        val atOrBelow = usable.filter { it.refreshRateHz <= target + 0.5f }.maxByOrNull { it.refreshRateHz }
        if (atOrBelow != null) {
            return FrameRateChoice.Request(
                modeId = atOrBelow.modeId,
                hz = atOrBelow.refreshRateHz,
                degradedFromHz = if (atOrBelow.refreshRateHz + 0.5f < target) target else null
            )
        }
        val slowest = usable.minByOrNull { it.refreshRateHz } ?: return FrameRateChoice.Unavailable
        // Every mode is above the ceiling (a 120 Hz-only panel asked for 90): requesting the slowest
        // mode would still exceed what the user chose, so hand the decision back to the platform.
        return FrameRateChoice.FollowSystem(modeId = systemModeId, unsupportedCeilingHz = target)
    }
}

/** What the watchdog wants to do after one probe. */
internal enum class WatchdogAction { KEEP, RESTART, BACKOFF }

internal data class WatchdogState(
    val failedStreak: Int = 0,
    val restartsInWindow: Int = 0,
    /** 0 means "no window opened yet". */
    val windowStartedAtMillis: Long = 0L
)

internal data class WatchdogDecision(
    val action: WatchdogAction,
    val state: WatchdogState,
    val reason: String,
    /** Log line for a restart/backoff; null for a quiet KEEP. */
    val logLine: String?
)

internal object WatchdogPolicy {

    /** One probe every 15 s. */
    const val PROBE_INTERVAL_MS: Long = 15_000L

    /** Three consecutive failures are required before a restart. */
    const val FAILURE_THRESHOLD: Int = 3

    /** Rolling throttle window. */
    const val RESTART_WINDOW_MS: Long = 5 * 60_000L

    /** At most two restarts inside [RESTART_WINDOW_MS]; further failures back off. */
    const val MAX_RESTARTS_PER_WINDOW: Int = 2

    fun initialState(nowMillis: Long): WatchdogState =
        WatchdogState(failedStreak = 0, restartsInWindow = 0, windowStartedAtMillis = nowMillis)

    /**
     * Folds one probe into the state.
     *
     * Recovered probes reset the failure streak immediately (the next incident needs three fresh
     * failures) but keep the restart counter until its window expires — that is what stops a flapping
     * host from being restarted every 45 seconds.
     */
    fun evaluate(ok: Boolean, state: WatchdogState, nowMillis: Long): WatchdogDecision {
        // Only an opened window can expire. `0` means "no window yet", and in that case the
        // restart counter is kept as-is: it is the evidence that a restart already happened.
        val windowExpired = state.windowStartedAtMillis != 0L &&
            nowMillis - state.windowStartedAtMillis >= RESTART_WINDOW_MS
        // 0 means "no window opened yet": the first probe that needs a window opens one now,
        // otherwise the throttle would count restarts against an ancient zero timestamp.
        val windowStart = when {
            state.windowStartedAtMillis == 0L -> nowMillis
            windowExpired -> nowMillis
            else -> state.windowStartedAtMillis
        }
        val restarts = if (windowExpired) 0 else state.restartsInWindow

        if (ok) {
            return WatchdogDecision(
                action = WatchdogAction.KEEP,
                state = WatchdogState(failedStreak = 0, restartsInWindow = restarts, windowStartedAtMillis = windowStart),
                reason = "运行时正常",
                logLine = null
            )
        }
        val streak = state.failedStreak + 1
        if (streak < FAILURE_THRESHOLD) {
            return WatchdogDecision(
                action = WatchdogAction.KEEP,
                state = WatchdogState(failedStreak = streak, restartsInWindow = restarts, windowStartedAtMillis = windowStart),
                reason = "连续失败 $streak/$FAILURE_THRESHOLD 次，继续观察",
                logLine = null
            )
        }
        if (restarts >= MAX_RESTARTS_PER_WINDOW) {
            return WatchdogDecision(
                action = WatchdogAction.BACKOFF,
                state = WatchdogState(failedStreak = FAILURE_THRESHOLD, restartsInWindow = restarts, windowStartedAtMillis = windowStart),
                reason = "5 分钟内已重启 $restarts 次，暂不自动重启",
                logLine = "watchdog: backoff after $restarts restarts in window, streak=$streak"
            )
        }
        return WatchdogDecision(
            action = WatchdogAction.RESTART,
            state = WatchdogState(
                failedStreak = 0,
                restartsInWindow = restarts + 1,
                windowStartedAtMillis = windowStart
            ),
            reason = "连续失败 $FAILURE_THRESHOLD 次，触发受控重启",
            logLine = "watchdog: restart #${restarts + 1} after $FAILURE_THRESHOLD failed probes"
        )
    }
}
