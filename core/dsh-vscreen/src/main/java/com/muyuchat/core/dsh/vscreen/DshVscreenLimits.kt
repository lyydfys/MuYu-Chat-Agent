package com.muyuchat.core.dsh.vscreen

/**
 * Hard budgets of the DSH virtual-screen capability.
 *
 * The official `dsh-tool-vscreen` package (pinned reference `98162a23`) sends no dimensions of
 * its own except the 9:16 / 16:9 defaults it derives (`1008x1792` portrait, `1792x1008`
 * landscape), caps `waitMs` at 5000 ms and polls every 300 ms. Those values are the wire
 * baseline; the budgets below are MCA's own resource guards (one display, one frame pump, bounded
 * bytes) and are reported honestly rather than hidden.
 */
object DshVscreenLimits {
    /** A virtual display is a full GPU/CPU surface; MCA owns exactly one at a time. */
    const val MAX_DISPLAYS = 1

    /** Smallest accepted requested dimension (only the direction indicator is used). */
    const val MIN_DIMENSION = 240

    /** Largest accepted requested dimension. */
    const val MAX_DIMENSION = 4096

    const val MIN_DPI = 72
    const val MAX_DPI = 960
    const val DEFAULT_DPI = 420

    /** Same 9:16 default the pinned package derives when no orientation is given. */
    const val DEFAULT_PORTRAIT_WIDTH = 1008
    const val DEFAULT_PORTRAIT_HEIGHT = 1792

    /** Same 16:9 default for landscape. */
    const val DEFAULT_LANDSCAPE_WIDTH = 1792
    const val DEFAULT_LANDSCAPE_HEIGHT = 1008

    const val MIN_FPS = 1
    const val MAX_FPS = 120
    const val DEFAULT_FPS = 30

    /** Every captured frame must fit this budget or it is rejected, never silently truncated. */
    const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    /** A frame larger than this in either axis is not an image of the configured display. */
    const val MAX_FRAME_DIMENSION = 4096

    /** A cached frame older than this is not served as if it were current. */
    const val MAX_STALE_FRAME_AGE_MILLIS = 5_000L

    /** `see` waits for a first frame for at most this long (the package clamps to the same cap). */
    const val MAX_WAIT_MS = 5_000L

    /** Retry cadence of `see` while it waits for a frame. */
    const val FRAME_POLL_INTERVAL_MILLIS = 300L

    /** Upper bound of one `latestFrame` attempt, so a stuck frame source cannot hang a request. */
    const val FRAME_READ_TIMEOUT_MILLIS = 300L

    /** Same attempt ceiling the package uses in its own loop. */
    const val MAX_FRAME_ATTEMPTS = 10

    /** No display start may block forever; the package waits 12 s for its server. */
    const val START_TIMEOUT_MILLIS = 12_000L

    const val MAX_PACKAGE_CHARS = 200

    /** Absolute coordinate ceiling before the per-display bounds check runs. */
    const val MAX_COORDINATE = 100_000

    const val MIN_SWIPE_DURATION_MILLIS = 0
    const val DEFAULT_SWIPE_DURATION_MILLIS = 300
    const val MAX_SWIPE_DURATION_MILLIS = 10_000

    /**
     * Preview pump cadence. The preview is a low-rate aid for the user, not a mirror: the display
     * keeps rendering at its own rate, and `see` remains the authoritative capture path.
     */
    const val PREVIEW_PUMP_INTERVAL_MILLIS = 400L

    const val MIN_PREVIEW_PUMP_INTERVAL_MILLIS = 200L
    const val MAX_PREVIEW_PUMP_INTERVAL_MILLIS = 5_000L

    /**
     * A screen-capture consent that is never used expires, so a stale grant cannot be replayed
     * much later. Once a display was created the grant stays valid until it is released.
     */
    const val PROJECTION_GRANT_TTL_MILLIS = 600_000L

    /** Bound of a provisioned `rish` loader dex; the real file is ~200 KiB. */
    const val MAX_PROVISIONED_DEX_BYTES = 8 * 1024 * 1024

    /** Bound of one privileged diagnostic line handed to the wire layer. */
    const val MAX_PRIVILEGED_DIAGNOSTIC_CHARS = 180

    /** Path handed back to the Node tool package; task cache directory paths stay well below this. */
    const val MAX_PATH_CHARS = 240

    /** Human-readable failure/hint ceiling (single line, prompt-free). */
    const val MAX_MESSAGE_CHARS = 300

    const val MIN_BRIDGE_PORT = 1024
    const val MAX_BRIDGE_PORT = 65_535

    const val MAX_COMMAND_ARG_CHARS = 2_048
    const val MAX_STDIN_BYTES = 8_192
    const val MIN_COMMAND_TIMEOUT_MILLIS = 1_000L
    const val MAX_COMMAND_TIMEOUT_MILLIS = 120_000L
    const val DEFAULT_COMMAND_TIMEOUT_MILLIS = 15_000L
    const val MAX_COMMAND_STDOUT_CHARS = 8_000
    const val MAX_COMMAND_STDERR_CHARS = 2_000

    /** Per-generation capability token bounds (matches the shared contract). */
    const val MIN_TOKEN_CHARS = 16
    const val MAX_TOKEN_CHARS = 128

    const val MAX_PRIVILEGED_DETAIL_CHARS = 300
}
