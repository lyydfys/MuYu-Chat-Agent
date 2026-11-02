package com.muyuchat.core.dsh.vscreen

/**
 * Stable, secret-free error families of the virtual-screen capability.
 *
 * [wire] is the string the server writes into the JSON envelope. The official package reads
 * `ok`/`error` for every action and additionally distinguishes `no_display`, `not_rendered` and
 * `no_frame` in its `see` render path, so those three strings are frozen.
 */
enum class DshVscreenErrorCode(val wire: String) {
    /** Malformed query string, unknown query parameter or wrong HTTP verb. */
    INVALID_REQUEST("invalid_request"),

    /** `orientation` was present but is not one of the accepted aliases. */
    UNSUPPORTED_ORIENTATION("unsupported_orientation"),

    /** `width`/`height` was not an in-range integer (they only carry the requested direction). */
    INVALID_DIMENSION("invalid_dimension"),

    /** `dpi` was outside the accepted range. */
    INVALID_DPI("invalid_dpi"),

    /** `key` was not one of the accepted names/aliases/keycodes. */
    UNKNOWN_KEY("unknown_key"),

    /** A tap/swipe coordinate was not an integer or lies outside the active display. */
    INVALID_COORDINATE("invalid_coordinate"),

    /** A swipe duration was outside 0..10000 ms. */
    INVALID_DURATION("invalid_duration"),

    /** `app` was empty, too long or contained characters unsafe to hand to `am start`. */
    INVALID_PACKAGE("invalid_package"),

    /** The frame source returned bytes that are empty, oversized or the wrong geometry. */
    INVALID_FRAME("invalid_frame"),

    /** No virtual display is running for this owner generation. */
    NO_DISPLAY("no_display"),

    /** A display already exists (STARTING/RUNNING/CLOSING); MCA keeps exactly one. */
    ALREADY_ACTIVE("already_active"),

    /** A close/cancel lease belongs to an older display generation. */
    STALE_LEASE("stale_lease"),

    /** The display exists but has never produced a first frame. */
    NOT_RENDERED("not_rendered"),

    /** The display produced frames before but none is available now. */
    NO_FRAME("no_frame"),

    /** The owner could not report its own state (kept for wire parity with the package). */
    STATUS_UNAVAILABLE("status_unavailable"),

    /** Neither root nor Shizuku gives a usable privileged shell. */
    PRIVILEGE_UNAVAILABLE("privilege_unavailable"),

    /** The real create path needs a user-granted screen-capture consent that is not present. */
    CONSENT_REQUIRED("consent_required"),

    /** The privileged channel was proven before but its service/dex is now gone; re-probe first. */
    CHANNEL_LOST("channel_lost"),

    /** Privilege exists but the virtual-display path is not verified for that transport. */
    TRANSPORT_UNVERIFIED("transport_unverified"),

    /** Display creation failed for a runtime reason (Binder rejection, missing surface...). */
    START_FAILED("start_failed"),

    /** Display creation exceeded [DshVscreenLimits.START_TIMEOUT_MILLIS]. */
    START_TIMEOUT("start_timeout"),

    /** A captured frame exceeded the byte budget. */
    FRAME_TOO_LARGE("frame_too_large"),

    /** A frame used a format MCA does not deliver. */
    FRAME_FORMAT_UNSUPPORTED("frame_format_unsupported"),

    /** A caller asked for frames faster than the configured frame policy allows. */
    RATE_LIMITED("rate_limited"),

    /** The start was cancelled by a concurrent close; the late handle was released. */
    CANCELLED("cancelled"),

    /** A privileged command exceeded its bounded timeout. */
    TIMEOUT("timeout"),

    /** A privileged command could not be started or exited non-zero. */
    COMMAND_FAILED("command_failed"),

    /** An adapter defect; never carries an exception message that could contain secrets. */
    INTERNAL("internal")
}

/**
 * A typed validation failure raised by the pure protocol parsers.
 *
 * It is deliberately an [IllegalArgumentException] so a caller that forgets to catch it still
 * fails closed, but the wire layer catches it first and maps [code] to the stable envelope.
 */
class DshVscreenValidationException(
    val code: DshVscreenErrorCode,
    message: String
) : IllegalArgumentException(message) {
    init {
        require(message.isNotBlank() && message.length <= DshVscreenLimits.MAX_MESSAGE_CHARS) {
            "validation message must be a bounded non-empty line"
        }
        require(message.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "validation message must be a single safe line"
        }
    }
}

/**
 * Result of one virtual-screen control call. [Failure] is the only error shape the wire layer
 * renders; it never contains a token, a raw URI, a command line or an exception string.
 */
sealed interface DshVscreenResult<out T> {
    data class Success<T>(val value: T) : DshVscreenResult<T>

    data class Failure(
        val code: DshVscreenErrorCode,
        val message: String,
        val displayId: Int? = null,
        val hasFrame: Boolean? = null,
        val waitedMs: Long? = null,
        val attempts: Int? = null,
        val hint: String? = null
    ) : DshVscreenResult<Nothing> {
        init {
            require(message.isNotBlank() && message.length <= DshVscreenLimits.MAX_MESSAGE_CHARS) {
                "failure message must be a bounded non-empty line"
            }
            require(message.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
                "failure message must be a single safe line"
            }
            require(hint == null || hint.length <= DshVscreenLimits.MAX_MESSAGE_CHARS) {
                "failure hint must be bounded"
            }
            require(hint == null || hint.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
                "failure hint must be a single safe line"
            }
        }
    }
}

internal fun failure(
    code: DshVscreenErrorCode,
    message: String,
    displayId: Int? = null,
    hasFrame: Boolean? = null,
    waitedMs: Long? = null,
    attempts: Int? = null,
    hint: String? = null
): DshVscreenResult.Failure = DshVscreenResult.Failure(
    code = code,
    message = message,
    displayId = displayId,
    hasFrame = hasFrame,
    waitedMs = waitedMs,
    attempts = attempts,
    hint = hint
)
