package com.muyuchat.core.dsh.a11y.policy

/** Prompt-free capability snapshot behind `/status`. */
data class A11yServiceStatus(
    val running: Boolean,
    val packageName: String,
    val nodeCount: Int,
    val apiLevel: Int,
    val canScreenshot: Boolean,
    val windowId: Int
)

/** Result of `/screenshot`. Only the pixels are a side artifact; the metadata is the wire. */
data class A11yScreenshot(
    val path: String,
    val screenW: Int,
    val screenH: Int,
    val imageW: Int,
    val imageH: Int,
    val width: Int,
    val height: Int,
    val scaleX: Double,
    val scaleY: Double,
    val grid: Int,
    val bytes: Long,
    val redactedRegions: Int,
    val hint: String?
)

/** Result of `/tap`; [found] is true only when a click was actually dispatched. */
data class A11yTapResult(
    val found: Boolean,
    val method: String,
    val error: String?
)

/** Result of `/input`; [method] is `set`, `paste` or `none` and is never invented. */
data class A11yInputResult(
    val focused: Boolean,
    val method: String,
    val error: String?
)

/** Result of `/scroll`; [method] is `node` or `none`. */
data class A11yScrollResult(
    val direction: A11yScrollDirection,
    val method: String,
    val error: String?
)

/** Result of `/back` and `/home`. */
data class A11yGlobalActionResult(val ok: Boolean)

/** One currently held virtual finger, as `/touch-status` reports it. */
data class A11yHeldFinger(
    val finger: Int,
    val x: Int,
    val y: Int,
    val fx: Double,
    val fy: Double,
    val elapsedMs: Long
)

/** `/touch-status` document. */
data class A11yTouchReport(
    val screenW: Int,
    val screenH: Int,
    val maxFingers: Int,
    val holdTimeoutMs: Long,
    val held: List<A11yHeldFinger>
)

/** Shared result of `/swipe`, `/hold`, `/touch` and `/gesture`. */
data class A11yTouchDispatchResult(
    val durationMs: Long,
    val held: List<A11yHeldFinger>
)

/**
 * Typed outcome of one command. The route seam encodes it; the Android adapter produces it.
 * Keeping the outcome typed (instead of pre-encoded JSON) is what makes bounds, caps and
 * redaction assertable without a device.
 */
sealed interface A11yOutcome {
    data class Status(val status: A11yServiceStatus) : A11yOutcome
    data class Dump(val snapshot: A11yTreeSnapshot) : A11yOutcome
    data class Tap(val result: A11yTapResult) : A11yOutcome
    data class Input(val result: A11yInputResult) : A11yOutcome
    data class Scroll(val result: A11yScrollResult) : A11yOutcome
    data class GlobalAction(val result: A11yGlobalActionResult) : A11yOutcome
    data class Screenshot(val result: A11yScreenshot) : A11yOutcome
    data class Touch(val result: A11yTouchDispatchResult) : A11yOutcome
    data class TouchStatus(val report: A11yTouchReport) : A11yOutcome
    data class Rejected(val rejection: A11yRejection) : A11yOutcome
}
