package com.muyuchat.core.dsh.a11y.policy

/**
 * Thread-safe owner of the virtual finger state.
 *
 * The reference service keeps `fingerDown[]`/`fingerX[]`/`fingerY[]` inside the service and guards
 * them with a monitor. This tracker is the same state without Android dependencies: the planner is
 * pure and commits a new state only after a plan was accepted, so a rejected request cannot move a
 * finger or leave one pressed.
 */
class A11yTouchTracker(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var held: Map<Int, A11yHeldFingerState> = emptyMap()

    /** Plans [ops] against the current state and commits the new state when the plan is accepted. */
    fun planAndApply(ops: List<A11yStrokeOp>, geometry: A11yScreenGeometry): A11yPlanResult {
        synchronized(lock) {
            val result = A11yGesturePlanner.plan(
                ops = ops,
                held = held.values.sortedBy { it.finger },
                geometry = geometry,
                nowMillis = clock()
            )
            if (result is A11yPlanResult.Planned) {
                held = result.heldAfter.associateBy { it.finger }
            }
            return result
        }
    }

    /** `/touch-status`: the report is taken against the current clock, not the last request. */
    fun report(geometry: A11yScreenGeometry): A11yTouchReport = synchronized(lock) {
        val now = clock()
        val width = geometry.widthPx
        val height = geometry.heightPx
        A11yTouchReport(
            screenW = width,
            screenH = height,
            maxFingers = A11yLimits.MAX_FINGERS,
            holdTimeoutMs = A11yLimits.HOLD_TIMEOUT_MILLIS,
            held = held.values.sortedBy { it.finger }.map { state ->
                A11yHeldFinger(
                    finger = state.finger,
                    x = state.x.toInt(),
                    y = state.y.toInt(),
                    fx = if (width > 0) (state.x / width).toDouble() else 0.0,
                    fy = if (height > 0) (state.y / height).toDouble() else 0.0,
                    elapsedMs = (now - state.downAtMillis).coerceAtLeast(0L)
                )
            }
        )
    }

    /** Drops every held finger, returning the states that the caller still has to lift. */
    fun releaseAll(): List<A11yHeldFingerState> = synchronized(lock) {
        val released = held.values.sortedBy { it.finger }
        held = emptyMap()
        released
    }

    /** Drops fingers that exceeded the hold timeout, returning those that must be lifted. */
    fun releaseExpired(): List<A11yHeldFingerState> = synchronized(lock) {
        val now = clock()
        val (expired, kept) = held.values.partition {
            now - it.downAtMillis > A11yLimits.HOLD_TIMEOUT_MILLIS
        }
        held = kept.associateBy { it.finger }
        expired.sortedBy { it.finger }
    }

    /** Snapshot without mutating state; used by the adapter to lift fingers on cancellation. */
    fun current(): List<A11yHeldFingerState> = synchronized(lock) {
        held.values.sortedBy { it.finger }
    }
}
