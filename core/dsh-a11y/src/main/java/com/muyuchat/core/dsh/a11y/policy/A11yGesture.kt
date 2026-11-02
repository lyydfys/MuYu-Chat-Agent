package com.muyuchat.core.dsh.a11y.policy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * One gesture stroke of the official `android_gesture` tool.
 *
 * `finger == -1` means "a fresh, untracked finger" for tap/swipe/hold, matching the reference
 * service where an omitted finger does not join the held-finger state machine. Coordinates stay
 * unresolved until the Android adapter resolves them against the live display geometry.
 */
sealed interface A11yStrokeOp {
    val finger: Int

    data class Down(override val finger: Int, val point: A11yCoord) : A11yStrokeOp
    data class Move(override val finger: Int, val point: A11yCoord, val durationMs: Long) : A11yStrokeOp
    data class Up(override val finger: Int, val point: A11yCoord?, val durationMs: Long) : A11yStrokeOp
    data class Tap(override val finger: Int, val point: A11yCoord, val durationMs: Long) : A11yStrokeOp
    data class Swipe(
        override val finger: Int,
        val from: A11yCoord,
        val to: A11yCoord,
        val durationMs: Long
    ) : A11yStrokeOp

    data class Hold(override val finger: Int, val point: A11yCoord, val durationMs: Long) : A11yStrokeOp
    data class Wait(val durationMs: Long) : A11yStrokeOp {
        override val finger: Int
            get() = -1
    }
}

/** Result of decoding a `/gesture` POST body. */
sealed interface A11yGestureParse {
    data class Parsed(val ops: List<A11yStrokeOp>) : A11yGestureParse
    data class Rejected(val rejection: A11yRejection) : A11yGestureParse
}

/**
 * Decodes the official `/gesture` body: either a bare JSON array or `{"strokes": [...]}`.
 *
 * The codec validates everything that can be decided without the screen: kind, finger range,
 * coordinate representation, every duration and the stroke count. Unknown JSON keys are ignored
 * exactly like the official package's allow-list does, so the official wire keeps working.
 */
object A11yGestureCodec {

    fun parse(bodyJson: String?): A11yGestureParse {
        val body = bodyJson?.trim().orEmpty()
        if (body.isEmpty()) {
            return reject("gesture requires a JSON body with a strokes array")
        }
        val array = try {
            if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                val root = JSONObject(body)
                root.optJSONArray("strokes")
                    ?: return reject("gesture body must be a JSON array or {\"strokes\": [...]}")
            }
        } catch (error: JSONException) {
            return reject("gesture body is not valid JSON")
        }
        if (array.length() == 0) {
            return reject("gesture strokes are empty")
        }
        if (array.length() > A11yLimits.MAX_GESTURE_STROKES) {
            return A11yGestureParse.Rejected(
                A11yRejection.tooLarge(
                    "gesture carries ${array.length()} strokes; at most ${A11yLimits.MAX_GESTURE_STROKES} are accepted"
                )
            )
        }

        val ops = ArrayList<A11yStrokeOp>(array.length())
        for (index in 0 until array.length()) {
            val element = array.optJSONObject(index)
                ?: return reject("gesture stroke #$index must be a JSON object")
            when (val decoded = decodeStroke(element, index)) {
                is StrokeResult.Ok -> ops += decoded.op
                is StrokeResult.Rejected -> return A11yGestureParse.Rejected(decoded.rejection)
            }
        }
        return A11yGestureParse.Parsed(ops)
    }

    private fun decodeStroke(obj: JSONObject, index: Int): StrokeResult {
        val kind = obj.optString("kind", "").trim()
        if (kind.isEmpty()) return rejectStroke("gesture stroke #$index has no kind")

        return when (kind) {
            "down" -> {
                val finger = readFinger(obj, required = true)
                    ?: return rejectStroke("down stroke #$index needs finger 0..${A11yLimits.MAX_FINGERS - 1}")
                val coord = readCoord(obj, "x", "y", "fx", "fy", "down stroke #$index")
                if (coord is CoordResult.Rejected) return StrokeResult.Rejected(coord.rejection)
                val point = (coord as CoordResult.Ok).coord
                    ?: return rejectStroke("down stroke #$index needs x/y or fx/fy")
                StrokeResult.Ok(A11yStrokeOp.Down(finger, point))
            }

            "move" -> {
                val finger = readFinger(obj, required = true)
                    ?: return rejectStroke("move stroke #$index needs finger 0..${A11yLimits.MAX_FINGERS - 1}")
                val coord = readCoord(obj, "x", "y", "fx", "fy", "move stroke #$index")
                if (coord is CoordResult.Rejected) return StrokeResult.Rejected(coord.rejection)
                val point = (coord as CoordResult.Ok).coord
                    ?: return rejectStroke("move stroke #$index needs x/y or fx/fy")
                val duration = readDuration(obj, A11yLimits.DEFAULT_MOVE_MILLIS, "move stroke #$index")
                if (duration is DurationResult.Rejected) return StrokeResult.Rejected(duration.rejection)
                StrokeResult.Ok(
                    A11yStrokeOp.Move(finger, point, (duration as DurationResult.Ok).value)
                )
            }

            "up" -> {
                val finger = readFinger(obj, required = true)
                    ?: return rejectStroke("up stroke #$index needs finger 0..${A11yLimits.MAX_FINGERS - 1}")
                val coord = readCoord(obj, "x", "y", "fx", "fy", "up stroke #$index")
                if (coord is CoordResult.Rejected) return StrokeResult.Rejected(coord.rejection)
                val duration = readDuration(obj, A11yLimits.DEFAULT_UP_MILLIS, "up stroke #$index")
                if (duration is DurationResult.Rejected) return StrokeResult.Rejected(duration.rejection)
                StrokeResult.Ok(
                    A11yStrokeOp.Up(
                        finger,
                        (coord as CoordResult.Ok).coord,
                        (duration as DurationResult.Ok).value
                    )
                )
            }

            "tap" -> {
                val finger = readFinger(obj, required = false)
                    ?: return rejectStroke("tap stroke #$index has an invalid finger")
                val coord = readCoord(obj, "x", "y", "fx", "fy", "tap stroke #$index")
                if (coord is CoordResult.Rejected) return StrokeResult.Rejected(coord.rejection)
                val point = (coord as CoordResult.Ok).coord
                    ?: return rejectStroke("tap stroke #$index needs x/y or fx/fy")
                val duration = readDuration(obj, A11yLimits.DEFAULT_TAP_MILLIS, "tap stroke #$index")
                if (duration is DurationResult.Rejected) return StrokeResult.Rejected(duration.rejection)
                StrokeResult.Ok(
                    A11yStrokeOp.Tap(finger, point, (duration as DurationResult.Ok).value)
                )
            }

            "swipe" -> {
                val finger = readFinger(obj, required = false)
                    ?: return rejectStroke("swipe stroke #$index has an invalid finger")
                val from = readCoord(obj, "x", "y", "fx", "fy", "swipe stroke #$index start")
                if (from is CoordResult.Rejected) return StrokeResult.Rejected(from.rejection)
                val start = (from as CoordResult.Ok).coord
                    ?: return rejectStroke("swipe stroke #$index needs a start (x/y or fx/fy)")
                val to = readCoord(obj, "x2", "y2", "fx2", "fy2", "swipe stroke #$index end")
                if (to is CoordResult.Rejected) return StrokeResult.Rejected(to.rejection)
                val end = (to as CoordResult.Ok).coord
                    ?: return rejectStroke("swipe stroke #$index needs an end (x2/y2 or fx2/fy2)")
                val duration = readDuration(obj, A11yLimits.DEFAULT_SWIPE_MILLIS, "swipe stroke #$index")
                if (duration is DurationResult.Rejected) return StrokeResult.Rejected(duration.rejection)
                StrokeResult.Ok(
                    A11yStrokeOp.Swipe(finger, start, end, (duration as DurationResult.Ok).value)
                )
            }

            "hold" -> {
                val finger = readFinger(obj, required = false)
                    ?: return rejectStroke("hold stroke #$index has an invalid finger")
                val coord = readCoord(obj, "x", "y", "fx", "fy", "hold stroke #$index")
                if (coord is CoordResult.Rejected) return StrokeResult.Rejected(coord.rejection)
                val point = (coord as CoordResult.Ok).coord
                    ?: return rejectStroke("hold stroke #$index needs x/y or fx/fy")
                val duration = readDuration(obj, A11yLimits.DEFAULT_HOLD_MILLIS, "hold stroke #$index")
                if (duration is DurationResult.Rejected) return StrokeResult.Rejected(duration.rejection)
                StrokeResult.Ok(
                    A11yStrokeOp.Hold(finger, point, (duration as DurationResult.Ok).value)
                )
            }

            "wait" -> {
                val raw = when {
                    obj.has("ms") && !obj.isNull("ms") -> obj.optDouble("ms", Double.NaN)
                    obj.has("durationMs") && !obj.isNull("durationMs") -> obj.optDouble("durationMs", Double.NaN)
                    else -> 0.0
                }
                if (!raw.isFinite() || raw < 0.0) {
                    return rejectStroke("wait stroke #$index needs a non-negative ms value")
                }
                if (raw > A11yLimits.MAX_WAIT_MILLIS) {
                    return rejectStroke(
                        "wait stroke #$index is longer than ${A11yLimits.MAX_WAIT_MILLIS} ms; " +
                            "hold a finger instead of waiting"
                    )
                }
                StrokeResult.Ok(A11yStrokeOp.Wait(raw.toLong()))
            }

            else -> rejectStroke("gesture stroke #$index has unknown kind: ${sanitize(kind)}")
        }
    }

    /**
     * Required fingers must be 0..7; optional fingers default to -1 (a fresh untracked finger) and
     * are invalid when present but out of range or non-integral.
     */
    private fun readFinger(obj: JSONObject, required: Boolean): Int? {
        val key = "finger"
        val present = obj.has(key) && !obj.isNull(key)
        if (!present) return if (required) null else -1
        val raw = obj.optDouble(key, Double.NaN)
        if (!raw.isFinite()) return null
        val value = raw.toInt()
        if (value.toDouble() != raw || value !in 0 until A11yLimits.MAX_FINGERS) return null
        return value
    }

    private fun readDuration(obj: JSONObject, default: Long, what: String): DurationResult {
        val present = obj.has("durationMs") && !obj.isNull("durationMs")
        if (!present) return DurationResult.Ok(default)
        val raw = obj.optDouble("durationMs", Double.NaN)
        if (!raw.isFinite() || raw < 0.0) {
            return DurationResult.Rejected(A11yRejection.invalid("$what needs a non-negative durationMs"))
        }
        if (raw > A11yLimits.MAX_GESTURE_DURATION_MILLIS) {
            return DurationResult.Rejected(
                A11yRejection.invalid(
                    "$what durationMs exceeds ${A11yLimits.MAX_GESTURE_DURATION_MILLIS} ms"
                )
            )
        }
        return DurationResult.Ok(if (raw <= 0.0) default else raw.toLong())
    }

    /**
     * Reads a coordinate pair. `fx/fy` wins over `x/y`, like the reference service. A lone half of
     * a pair is a malformed request, not "use zero".
     */
    private fun readCoord(
        obj: JSONObject,
        pxX: String,
        pxY: String,
        fxKey: String,
        fyKey: String,
        what: String
    ): CoordResult {
        val hasFx = obj.has(fxKey) && !obj.isNull(fxKey)
        val hasFy = obj.has(fyKey) && !obj.isNull(fyKey)
        val hasX = obj.has(pxX) && !obj.isNull(pxX)
        val hasY = obj.has(pxY) && !obj.isNull(pxY)

        if (hasFx != hasFy) return CoordResult.Rejected(A11yRejection.invalid("$what needs both $fxKey and $fyKey"))
        if (hasX != hasY) return CoordResult.Rejected(A11yRejection.invalid("$what needs both $pxX and $pxY"))

        if (hasFx && hasFy) {
            val fx = obj.optDouble(fxKey, Double.NaN)
            val fy = obj.optDouble(fyKey, Double.NaN)
            if (!fx.isFinite() || !fy.isFinite() || fx !in 0.0..1.0 || fy !in 0.0..1.0) {
                return CoordResult.Rejected(
                    A11yRejection.invalid("$what fractional coordinates must be finite values in 0..1")
                )
            }
            return CoordResult.Ok(A11yCoord.Fraction(fx, fy))
        }
        if (hasX && hasY) {
            val x = obj.optDouble(pxX, Double.NaN)
            val y = obj.optDouble(pxY, Double.NaN)
            if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0) {
                return CoordResult.Rejected(
                    A11yRejection.invalid("$what pixel coordinates must be finite non-negative values")
                )
            }
            if (x > A11yLimits.MAX_COORDINATE || y > A11yLimits.MAX_COORDINATE) {
                return CoordResult.Rejected(
                    A11yRejection.invalid("$what pixel coordinates exceed ${A11yLimits.MAX_COORDINATE}")
                )
            }
            return CoordResult.Ok(A11yCoord.Pixels(x.toInt(), y.toInt()))
        }
        return CoordResult.Ok(null)
    }

    private fun reject(message: String): A11yGestureParse =
        A11yGestureParse.Rejected(A11yRejection.invalid(message))

    private fun rejectStroke(message: String): StrokeResult =
        StrokeResult.Rejected(A11yRejection.invalid(message))

    private fun sanitize(kind: String): String =
        kind.take(32).filter { it.code in 0x20..0x7E }.ifEmpty { "?" }

    private sealed interface CoordResult {
        data class Ok(val coord: A11yCoord?) : CoordResult
        data class Rejected(val rejection: A11yRejection) : CoordResult
    }

    private sealed interface DurationResult {
        data class Ok(val value: Long) : DurationResult
        data class Rejected(val rejection: A11yRejection) : DurationResult
    }

    private sealed interface StrokeResult {
        data class Ok(val op: A11yStrokeOp) : StrokeResult
        data class Rejected(val rejection: A11yRejection) : StrokeResult
    }
}
