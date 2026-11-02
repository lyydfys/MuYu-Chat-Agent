package com.muyuchat.core.dsh.a11y.policy

/** Scroll directions of the official `android_scroll` tool. */
enum class A11yScrollDirection { UP, DOWN, LEFT, RIGHT }

/** Stateful touch actions of the official `android_touch` tool. */
enum class A11yTouchAction { DOWN, MOVE, UP, RELEASE }

/**
 * One validated accessibility request. Parsing (syntax, bounds, caps) happens before this type is
 * created; executing it is the Android adapter's job. Keeping the command free of Android types is
 * what lets the route seam and the whole policy be unit-tested on the JVM.
 */
sealed interface A11yCommand {
    /**
     * Optional foreground-package guard. The official package never sends it, so null means "the
     * current foreground app"; a caller that names one gets `TARGET_CHANGED` when the user moved on.
     */
    val expectedPackage: String?
        get() = null

    data object Status : A11yCommand
    data object Dump : A11yCommand
    data object TouchStatus : A11yCommand
    data object TouchRelease : A11yCommand
    data object Back : A11yCommand
    data object Home : A11yCommand

    data class Tap(
        val text: String?,
        val desc: String?,
        val target: A11yCoord?,
        val ref: A11yNodeRef?,
        override val expectedPackage: String?
    ) : A11yCommand

    data class Input(
        val text: String,
        val paste: Boolean,
        override val expectedPackage: String? = null
    ) : A11yCommand

    data class Scroll(
        val direction: A11yScrollDirection,
        override val expectedPackage: String? = null
    ) : A11yCommand

    data class Screenshot(
        val grid: Int,
        val displayId: Int
    ) : A11yCommand

    data class Swipe(
        val from: A11yCoord,
        val to: A11yCoord,
        val durationMs: Long,
        val finger: Int?,
        override val expectedPackage: String? = null
    ) : A11yCommand

    data class Hold(
        val target: A11yCoord,
        val durationMs: Long,
        val finger: Int?,
        override val expectedPackage: String? = null
    ) : A11yCommand

    data class Touch(
        val action: A11yTouchAction,
        val finger: Int,
        val target: A11yCoord?,
        override val expectedPackage: String? = null
    ) : A11yCommand

    data class Gesture(
        val ops: List<A11yStrokeOp>,
        override val expectedPackage: String? = null
    ) : A11yCommand
}

/** Parsed request or a typed refusal; the route seam never throws for bad input. */
sealed interface A11yParseResult {
    data class Command(val command: A11yCommand) : A11yParseResult
    data class Rejected(val rejection: A11yRejection) : A11yParseResult
}
