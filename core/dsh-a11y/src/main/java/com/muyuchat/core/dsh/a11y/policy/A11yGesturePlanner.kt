package com.muyuchat.core.dsh.a11y.policy

/** One finger that is (or should stay) pressed after a request completes. */
data class A11yHeldFingerState(
    val finger: Int,
    val x: Float,
    val y: Float,
    val downAtMillis: Long
) {
    init {
        require(finger in 0 until A11yLimits.MAX_FINGERS) { "held finger is out of range" }
        require(x.isFinite() && y.isFinite()) { "held finger coordinates must be finite" }
        require(downAtMillis >= 0L) { "held finger down time must be non-negative" }
    }
}

/**
 * One concrete stroke for `AccessibilityService.dispatchGesture`.
 *
 * [points] is the path in physical pixels: one point is a tap/hold, two or more is a drag.
 * [startMs] and [durationMs] place the stroke on the shared timeline; [willContinue] keeps the
 * finger down after the stroke so a later request can move it.
 */
data class A11yPlannedStroke(
    val points: List<A11yPoint>,
    val startMs: Long,
    val durationMs: Long,
    val willContinue: Boolean
) {
    init {
        require(points.isNotEmpty()) { "a gesture stroke needs at least one point" }
        require(startMs >= 0L) { "a gesture stroke cannot start before the timeline" }
        require(durationMs > 0L) { "a gesture stroke must have a positive duration" }
    }
}

/** Pure result of planning one gesture request. */
sealed interface A11yPlanResult {
    data class Planned(
        val strokes: List<A11yPlannedStroke>,
        val totalMs: Long,
        val heldAfter: List<A11yHeldFingerState>
    ) : A11yPlanResult

    data class Rejected(val rejection: A11yRejection) : A11yPlanResult
}

/**
 * Pure planner for the reference's multi-finger timeline.
 *
 * The rule set follows `AccessibilityService.executeGesture` of the pinned reference:
 * - one virtual finger state machine is evolved for validation before any real state changes;
 * - a finger held for more than [A11yLimits.HOLD_TIMEOUT_MILLIS] is expired and refused;
 * - `tap`/`swipe`/`hold` on an already held finger becomes "drag to target, then release";
 * - fingers held across requests get a continuation stroke so the system does not lift them;
 * - the whole timeline is bounded by [A11yLimits.MAX_GESTURE_TOTAL_MILLIS].
 *
 * Everything is decided before a stroke is dispatched, so a bad sequence can never leave a
 * half-applied gesture behind.
 */
object A11yGesturePlanner {

    fun plan(
        ops: List<A11yStrokeOp>,
        held: List<A11yHeldFingerState>,
        geometry: A11yScreenGeometry,
        nowMillis: Long
    ): A11yPlanResult {
        if (!geometry.isUsable) {
            return reject("screen geometry is unavailable")
        }
        if (ops.isEmpty()) {
            return reject("gesture has no strokes")
        }
        if (ops.size > A11yLimits.MAX_GESTURE_STROKES) {
            return A11yPlanResult.Rejected(
                A11yRejection.tooLarge("gesture carries too many strokes")
            )
        }

        val heldByFinger = held
            .filter { it.finger in 0 until A11yLimits.MAX_FINGERS }
            .associateBy { it.finger }
        val expired = heldByFinger.values
            .filter { nowMillis - it.downAtMillis > A11yLimits.HOLD_TIMEOUT_MILLIS }
            .map { it.finger }
            .toSet()

        val virtualHeld = heldByFinger.keys.toMutableSet()
        val firstOpStart = LongArray(A11yLimits.MAX_FINGERS) { -1L }
        val resolved = ArrayList<ResolvedOp>(ops.size)
        var timeline = 0L

        for (op in ops) {
            val finger = op.finger
            if (finger !in -1 until A11yLimits.MAX_FINGERS) {
                return reject("finger $finger is out of range 0..${A11yLimits.MAX_FINGERS - 1}")
            }
            var segment = 0L
            var x = -1
            var y = -1
            var x2 = -1
            var y2 = -1

            when (op) {
                is A11yStrokeOp.Wait -> segment = op.durationMs

                is A11yStrokeOp.Down -> {
                    val point = resolve(op.point, geometry)
                        ?: return reject("down point is outside the current screen")
                    if (finger in expired) return expiredFinger(finger)
                    if (finger in virtualHeld && firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                    virtualHeld += finger
                    x = point.x
                    y = point.y
                    segment = A11yLimits.DOWN_SEGMENT_MILLIS
                }

                is A11yStrokeOp.Move -> {
                    if (finger !in virtualHeld) return reject("move finger $finger is not held (send down first)")
                    if (finger in expired) return expiredFinger(finger)
                    val point = resolve(op.point, geometry)
                        ?: return reject("move point is outside the current screen")
                    if (firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                    x = point.x
                    y = point.y
                    segment = op.durationMs
                }

                is A11yStrokeOp.Up -> {
                    if (finger !in virtualHeld) return reject("up finger $finger is not held (send down first)")
                    if (finger in expired) return expiredFinger(finger)
                    if (firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                    op.point?.let { coord ->
                        val point = resolve(coord, geometry)
                            ?: return reject("up point is outside the current screen")
                        x = point.x
                        y = point.y
                    }
                    virtualHeld -= finger
                    segment = op.durationMs
                }

                is A11yStrokeOp.Tap -> {
                    val point = resolve(op.point, geometry)
                        ?: return reject("tap point is outside the current screen")
                    if (finger >= 0 && finger in virtualHeld) {
                        if (finger in expired) return expiredFinger(finger)
                        if (firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                        virtualHeld -= finger
                    }
                    x = point.x
                    y = point.y
                    segment = op.durationMs
                }

                is A11yStrokeOp.Swipe -> {
                    val from = resolve(op.from, geometry)
                        ?: return reject("swipe start is outside the current screen")
                    val to = resolve(op.to, geometry)
                        ?: return reject("swipe end is outside the current screen")
                    if (finger >= 0 && finger in virtualHeld) {
                        if (finger in expired) return expiredFinger(finger)
                        if (firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                        virtualHeld -= finger
                    }
                    x = from.x
                    y = from.y
                    x2 = to.x
                    y2 = to.y
                    segment = op.durationMs
                }

                is A11yStrokeOp.Hold -> {
                    val point = resolve(op.point, geometry)
                        ?: return reject("hold point is outside the current screen")
                    if (finger >= 0 && finger in virtualHeld) {
                        if (finger in expired) return expiredFinger(finger)
                        if (firstOpStart[finger] < 0L) firstOpStart[finger] = timeline
                        virtualHeld -= finger
                    }
                    x = point.x
                    y = point.y
                    segment = op.durationMs
                }
            }

            if (segment < 0L) return reject("gesture stroke has a negative duration")
            if (timeline + segment > A11yLimits.MAX_GESTURE_TOTAL_MILLIS) {
                return A11yPlanResult.Rejected(
                    A11yRejection.tooLarge(
                        "gesture timeline exceeds ${A11yLimits.MAX_GESTURE_TOTAL_MILLIS} ms"
                    )
                )
            }
            resolved += ResolvedOp(op, finger, x, y, x2, y2, timeline, timeline + segment)
            timeline += segment
        }

        if (timeline <= 0L) return reject("gesture timeline is empty")

        val strokes = ArrayList<A11yPlannedStroke>()
        val currentX = HashMap<Int, Float>()
        val currentY = HashMap<Int, Float>()
        heldByFinger.forEach { (finger, state) ->
            currentX[finger] = state.x
            currentY[finger] = state.y
        }
        val currentlyHeld = heldByFinger.keys.toMutableSet()
        // An expired finger is lifted by a release stroke below; it must not stay in the state.
        val heldAfter = heldByFinger.filterKeys { it !in expired }.toMutableMap()

        // Continuation strokes keep fingers that were held before this request pressed.
        for (finger in 0 until A11yLimits.MAX_FINGERS) {
            val state = heldByFinger[finger] ?: continue
            val point = A11yPoint(state.x.toInt(), state.y.toInt())
            when {
                finger in expired -> strokes += A11yPlannedStroke(listOf(point), 0L, 80L, willContinue = false)
                firstOpStart[finger] < 0L -> strokes += A11yPlannedStroke(
                    listOf(point),
                    0L,
                    timeline,
                    willContinue = true
                )

                firstOpStart[finger] > 0L -> strokes += A11yPlannedStroke(
                    listOf(point),
                    0L,
                    firstOpStart[finger],
                    willContinue = true
                )
                // firstOpStart == 0: the op stroke already starts at 0.
            }
        }

        for (resolvedOp in resolved) {
            val finger = resolvedOp.finger
            val start = resolvedOp.start
            val segment = resolvedOp.end - resolvedOp.start
            val target = A11yPoint(resolvedOp.x, resolvedOp.y)
            val cur = if (finger >= 0 && currentX.containsKey(finger)) {
                A11yPoint(currentX[finger]!!.toInt(), currentY[finger]!!.toInt())
            } else {
                null
            }

            when (val op = resolvedOp.op) {
                is A11yStrokeOp.Wait -> Unit

                is A11yStrokeOp.Down -> {
                    if (finger in currentlyHeld) {
                        strokes += A11yPlannedStroke(
                            listOf(cur ?: target, target),
                            start,
                            segment,
                            willContinue = true
                        )
                    } else {
                        strokes += A11yPlannedStroke(
                            listOf(target),
                            start,
                            timeline - start,
                            willContinue = true
                        )
                        currentlyHeld += finger
                    }
                    currentX[finger] = target.x.toFloat()
                    currentY[finger] = target.y.toFloat()
                    heldAfter[finger] = A11yHeldFingerState(
                        finger = finger,
                        x = target.x.toFloat(),
                        y = target.y.toFloat(),
                        downAtMillis = heldAfter[finger]?.downAtMillis ?: nowMillis
                    )
                }

                is A11yStrokeOp.Move -> {
                    strokes += A11yPlannedStroke(
                        listOf(cur ?: target, target),
                        start,
                        segment,
                        willContinue = true
                    )
                    currentX[finger] = target.x.toFloat()
                    currentY[finger] = target.y.toFloat()
                    heldAfter[finger] = A11yHeldFingerState(
                        finger = finger,
                        x = target.x.toFloat(),
                        y = target.y.toFloat(),
                        downAtMillis = heldAfter[finger]?.downAtMillis ?: nowMillis
                    )
                }

                is A11yStrokeOp.Up -> {
                    val path = buildList {
                        cur?.let { add(it) }
                        if (op.point != null) add(target)
                        if (isEmpty()) add(target)
                    }
                    strokes += A11yPlannedStroke(path, start, segment, willContinue = false)
                    currentlyHeld -= finger
                    heldAfter.remove(finger)
                    if (op.point != null) {
                        currentX[finger] = target.x.toFloat()
                        currentY[finger] = target.y.toFloat()
                    }
                }

                is A11yStrokeOp.Tap -> {
                    if (finger >= 0 && finger in currentlyHeld) {
                        strokes += A11yPlannedStroke(
                            listOf(cur ?: target, target),
                            start,
                            segment,
                            willContinue = false
                        )
                        currentlyHeld -= finger
                        heldAfter.remove(finger)
                    } else {
                        strokes += A11yPlannedStroke(listOf(target), start, segment, willContinue = false)
                    }
                    if (finger >= 0) {
                        currentX[finger] = target.x.toFloat()
                        currentY[finger] = target.y.toFloat()
                    }
                }

                is A11yStrokeOp.Swipe -> {
                    val end = A11yPoint(resolvedOp.x2, resolvedOp.y2)
                    val path = if (finger >= 0 && finger in currentlyHeld) {
                        listOf(cur ?: target, end)
                    } else {
                        listOf(target, end)
                    }
                    strokes += A11yPlannedStroke(path, start, segment, willContinue = false)
                    if (finger >= 0) {
                        currentlyHeld -= finger
                        heldAfter.remove(finger)
                        currentX[finger] = end.x.toFloat()
                        currentY[finger] = end.y.toFloat()
                    }
                }

                is A11yStrokeOp.Hold -> {
                    val path = if (finger >= 0 && finger in currentlyHeld) {
                        listOf(cur ?: target, target)
                    } else {
                        listOf(target)
                    }
                    strokes += A11yPlannedStroke(path, start, segment, willContinue = false)
                    if (finger >= 0) {
                        currentlyHeld -= finger
                        heldAfter.remove(finger)
                        currentX[finger] = target.x.toFloat()
                        currentY[finger] = target.y.toFloat()
                    }
                }
            }
        }

        if (strokes.isEmpty()) return reject("gesture has no executable strokes (only waits)")
        if (strokes.size > A11yLimits.MAX_GESTURE_STROKES) {
            return A11yPlanResult.Rejected(
                A11yRejection.tooLarge(
                    "gesture expands to ${strokes.size} strokes; at most ${A11yLimits.MAX_GESTURE_STROKES} are accepted"
                )
            )
        }
        return A11yPlanResult.Planned(
            strokes = strokes,
            totalMs = timeline,
            heldAfter = heldAfter.values.sortedBy { it.finger }
        )
    }

    private fun resolve(coord: A11yCoord, geometry: A11yScreenGeometry): A11yPoint? =
        coord.resolve(geometry)

    private fun reject(message: String): A11yPlanResult =
        A11yPlanResult.Rejected(A11yRejection.invalid(message))

    private fun expiredFinger(finger: Int): A11yPlanResult =
        A11yPlanResult.Rejected(
            A11yRejection.timedOut(
                "finger $finger has been held longer than ${A11yLimits.HOLD_TIMEOUT_MILLIS} ms and was released; send down again"
            )
        )

    private class ResolvedOp(
        val op: A11yStrokeOp,
        val finger: Int,
        val x: Int,
        val y: Int,
        val x2: Int,
        val y2: Int,
        val start: Long,
        val end: Long
    )
}
