package com.muyuchat.core.dsh.vscreen

/**
 * Frame admission policy: rate cap, byte cap and geometry sanity, applied before a frame is
 * persisted or handed to a preview.
 *
 * The rate cap is a *delivery* cap, not a capture cap: when a caller asks for frames faster than
 * the configured rate the owner serves the last admitted frame (bounded by
 * [DshVscreenLimits.MAX_STALE_FRAME_AGE_MILLIS]) instead of re-reading and re-encoding the display.
 */
class DshVscreenFramePolicy(
    val maxFps: Int = DshVscreenLimits.DEFAULT_FPS,
    val maxFrameBytes: Int = DshVscreenLimits.MAX_FRAME_BYTES,
    val maxFrameDimension: Int = DshVscreenLimits.MAX_FRAME_DIMENSION
) {
    init {
        require(maxFps in DshVscreenLimits.MIN_FPS..DshVscreenLimits.MAX_FPS) {
            "maxFps must be ${DshVscreenLimits.MIN_FPS}..${DshVscreenLimits.MAX_FPS}"
        }
        require(maxFrameBytes in 1..DshVscreenLimits.MAX_FRAME_BYTES) {
            "maxFrameBytes must be 1..${DshVscreenLimits.MAX_FRAME_BYTES}"
        }
        require(maxFrameDimension in 1..DshVscreenLimits.MAX_FRAME_DIMENSION) {
            "maxFrameDimension must be 1..${DshVscreenLimits.MAX_FRAME_DIMENSION}"
        }
    }

    /** Ceiling of 1000/maxFps, so 30 fps gives 34 ms rather than silently allowing 33.3 fps. */
    val minIntervalMillis: Long = (1_000L + maxFps - 1L) / maxFps

    fun admit(
        frame: DshVscreenFrame,
        nowMillis: Long,
        lastDeliveredAtMillis: Long?
    ): DshVscreenFrameAdmission {
        if (frame.bytes.isEmpty()) {
            return DshVscreenFrameAdmission.Reject(
                DshVscreenErrorCode.INVALID_FRAME,
                "the frame source returned an empty frame"
            )
        }
        if (frame.width <= 0 || frame.height <= 0 || frame.width > maxFrameDimension || frame.height > maxFrameDimension) {
            return DshVscreenFrameAdmission.Reject(
                DshVscreenErrorCode.INVALID_FRAME,
                "the frame geometry is outside the configured display bounds"
            )
        }
        if (frame.bytes.size > maxFrameBytes) {
            return DshVscreenFrameAdmission.Reject(
                DshVscreenErrorCode.FRAME_TOO_LARGE,
                "the captured frame exceeds the ${maxFrameBytes} byte budget"
            )
        }
        if (lastDeliveredAtMillis != null) {
            val age = nowMillis - lastDeliveredAtMillis
            if (age in 0 until minIntervalMillis) {
                return DshVscreenFrameAdmission.Drop(DshVscreenErrorCode.RATE_LIMITED)
            }
        }
        return DshVscreenFrameAdmission.Deliver
    }
}

/** Decision produced by [DshVscreenFramePolicy.admit]. */
sealed interface DshVscreenFrameAdmission {
    data object Deliver : DshVscreenFrameAdmission

    /** Too soon after the last delivery; the owner may serve the cached frame instead. */
    data class Drop(val reason: DshVscreenErrorCode) : DshVscreenFrameAdmission

    /** The frame itself is unusable; this is a terminal failure for the current call. */
    data class Reject(val code: DshVscreenErrorCode, val message: String) : DshVscreenFrameAdmission {
        init {
            require(message.isNotBlank() && message.length <= DshVscreenLimits.MAX_MESSAGE_CHARS)
            require(message.none { it.code < 0x20 || it.code in 0x7F..0x9F })
        }
    }
}

/** Last admitted frame plus the instant it was admitted. Cleared on every close/failed start. */
class DshVscreenFrameCache {
    private var frame: DshVscreenFrame? = null
    private var admittedAtMillis: Long = 0L

    fun store(frame: DshVscreenFrame, nowMillis: Long) {
        this.frame = frame
        this.admittedAtMillis = nowMillis
    }

    /** Returns the cached frame and its age at [nowMillis], or null when no frame was admitted. */
    fun last(nowMillis: Long): Cached? {
        val current = frame ?: return null
        return Cached(current, (nowMillis - admittedAtMillis).coerceAtLeast(0L))
    }

    fun clear() {
        frame = null
        admittedAtMillis = 0L
    }

    data class Cached(val frame: DshVscreenFrame, val ageMillis: Long)
}
