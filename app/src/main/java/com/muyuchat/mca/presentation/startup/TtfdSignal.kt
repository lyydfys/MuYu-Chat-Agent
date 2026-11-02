package com.muyuchat.mca.presentation.startup

/**
 * TTFD (time to first drawn, actionable) model.
 *
 * Definition used by every consumer of this package and by `scripts/measure-ttfd.ps1`:
 *
 *     TTFD = (the moment the chat composer is present in the semantics tree and actionable)
 *          - (the moment this app process started)
 *
 * The origin is `android.os.Process.getStartUptimeMillis()`, i.e. the process start reported by the
 * platform, not the Activity's `onCreate`. It therefore includes Application/ContentProvider
 * initialization and class loading. The measurement is only complete when BOTH observations exist:
 *
 *  - a drawn frame: the window's first frame reached its draw/commit path;
 *  - an actionable composer: the chat editor semantics node exists, is not disabled, has non-empty
 *    layout bounds and exposes the text-entry action.
 *
 * A launch duration (`am start -W` TotalTime) is never a substitute for either half; when one half
 * is missing the sample stays incomplete and `ttfdMs` is `null`.
 *
 * This file is deliberately free of Android and Compose types, so the state machine, the time
 * arithmetic and the "missing signal" behaviour are testable on a plain JVM.
 */

internal object TtfdLog {
    /** Fixed logcat tag parsed by `scripts/measure-ttfd.ps1`. Never rename without the script. */
    const val TAG = "McaTtfd"

    /** The origin label written into every line so a reader knows what the deltas are from. */
    const val ORIGIN = "process-start"

    fun firstFrameLine(firstFrameMs: Long): String =
        "event=first-frame firstFrameMs=$firstFrameMs origin=$ORIGIN"

    /**
     * The one line that proves TTFD. Returns `null` whenever the sample is not "drawn and
     * actionable"; the caller must not invent a value from another timing source.
     */
    fun ttfdLine(sample: TtfdSample): String? {
        val ttfd = sample.ttfdMs ?: return null
        return "event=ttfd ttfdMs=$ttfd firstFrameMs=${sample.firstFrameMs} " +
            "inputReadyMs=${sample.inputReadyMs} drawnAndActionable=true origin=$ORIGIN"
    }

    /** The composer became actionable but no drawn frame was observed: an incomplete sample. */
    fun inputReadyWithoutFrameLine(sample: TtfdSample): String =
        "event=input-ready firstFrameMs=-1 inputReadyMs=${sample.inputReadyMs} " +
            "drawnAndActionable=false origin=$ORIGIN"

    /** The probe gave up: evidence for diagnosis, still not a TTFD. */
    fun probeTimeoutLine(sample: TtfdSample?): String =
        "event=probe-timeout firstFrameMs=${sample?.firstFrameMs ?: -1} " +
            "inputReadyMs=${sample?.inputReadyMs ?: -1} drawnAndActionable=false origin=$ORIGIN"
}

/**
 * One observation of the two moments this signal needs.
 *
 * Both values are elapsed milliseconds from [TtfdMeasurement]'s origin; `null` means "not observed
 * yet". [drawnAndActionable] is the whole point of the signal, and [ttfdMs] is `null` unless that
 * is true, so an incomplete sample can never be read as a short TTFD.
 */
internal data class TtfdSample(
    val firstFrameMs: Long?,
    val inputReadyMs: Long?,
) {
    val drawnAndActionable: Boolean get() = firstFrameMs != null && inputReadyMs != null
    val ttfdMs: Long? get() = if (drawnAndActionable) inputReadyMs else null
}

/**
 * One-shot measurement. Pure time arithmetic: every timestamp is supplied by the caller, so the
 * same state machine can be driven by real uptime in production and by literals in tests.
 *
 * Rules:
 *  - the first accepted mark of each moment wins; later marks never move it;
 *  - a timestamp earlier than the origin is rejected instead of producing a negative delta;
 *  - input readiness is only latched when it is actionable, so an enabled=false composer at an
 *    intermediate state cannot end the measurement.
 */
internal class TtfdMeasurement(val originUptimeMs: Long) {
    init {
        require(originUptimeMs >= 0L) { "origin must be a non-negative uptime, was $originUptimeMs" }
    }

    private var firstFrameMs: Long? = null
    private var inputReadyMs: Long? = null

    /** Returns true only when this call actually recorded the first drawn frame. */
    fun markFirstFrame(atUptimeMs: Long): Boolean {
        if (firstFrameMs != null) return false
        firstFrameMs = relativeOrNull(atUptimeMs) ?: return false
        return true
    }

    /** Returns true only when this call actually recorded an actionable composer. */
    fun markInputReady(actionable: Boolean, atUptimeMs: Long): Boolean {
        if (!actionable || inputReadyMs != null) return false
        inputReadyMs = relativeOrNull(atUptimeMs) ?: return false
        return true
    }

    fun sample(): TtfdSample = TtfdSample(firstFrameMs, inputReadyMs)

    private fun relativeOrNull(atUptimeMs: Long): Long? =
        (atUptimeMs - originUptimeMs).takeIf { it >= 0L }
}

/**
 * Process-wide TTFD signal.
 *
 * [begin] is called once from `MainActivity` (through `TtfdProbe`); afterwards every observation is
 * one-shot. Activity recreation inside the same process therefore cannot rewrite the measurement:
 * a second `install`/`begin` is ignored and repeated frame or composer observations are no-ops.
 *
 * The signal never emits a TTFD without both halves. Outcomes:
 *  - both halves observed -> exactly one `event=ttfd ... drawnAndActionable=true` line;
 *  - composer ready but no drawn frame -> one `event=input-ready ... drawnAndActionable=false` line;
 *  - probe gave up -> one `event=probe-timeout ... drawnAndActionable=false` line.
 * `scripts/measure-ttfd.ps1` records a round as NOT_RUN unless it saw the first outcome.
 */
internal object TtfdSignal {
    private var measurement: TtfdMeasurement? = null
    private var emit: (String) -> Unit = {}
    private var firstFrameLogged = false
    private var inputReadyLogged = false
    private var terminalLogged = false

    /** Idempotent: the first call establishes the origin and the log sink. */
    fun begin(originUptimeMs: Long, log: (String) -> Unit) {
        if (measurement != null) return
        measurement = TtfdMeasurement(originUptimeMs)
        emit = log
    }

    fun noteFirstFrame(atUptimeMs: Long) {
        val current = measurement ?: return
        if (!current.markFirstFrame(atUptimeMs)) return
        val sample = current.sample()
        val firstFrameMs = sample.firstFrameMs ?: return
        if (!firstFrameLogged) {
            firstFrameLogged = true
            emit(TtfdLog.firstFrameLine(firstFrameMs))
        }
        logTerminalIfComplete(sample)
    }

    fun noteInputReady(actionable: Boolean, atUptimeMs: Long) {
        val current = measurement ?: return
        if (!current.markInputReady(actionable, atUptimeMs)) return
        val sample = current.sample()
        if (sample.drawnAndActionable) {
            logTerminalIfComplete(sample)
            return
        }
        if (!inputReadyLogged) {
            inputReadyLogged = true
            emit(TtfdLog.inputReadyWithoutFrameLine(sample))
        }
    }

    fun noteProbeTimeout() {
        val current = measurement ?: return
        if (terminalLogged) return
        terminalLogged = true
        emit(TtfdLog.probeTimeoutLine(current.sample()))
    }

    private fun logTerminalIfComplete(sample: TtfdSample) {
        if (terminalLogged) return
        val line = TtfdLog.ttfdLine(sample) ?: return
        terminalLogged = true
        emit(line)
    }

    fun isComplete(): Boolean = measurement?.sample()?.drawnAndActionable == true

    internal fun sample(): TtfdSample? = measurement?.sample()

    /** Test-only reset. A real process calls [begin] exactly once and never resets. */
    internal fun reset() {
        measurement = null
        emit = {}
        firstFrameLogged = false
        inputReadyLogged = false
        terminalLogged = false
    }
}
