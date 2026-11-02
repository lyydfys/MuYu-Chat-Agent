package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityLimits
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteLimits

/**
 * Frozen bounds of the accessibility domain.
 *
 * The values follow the pinned reference service
 * (`woaiys3/deepseek-harness-android-app` @ `98162a23`, `AccessibilityService.java`) where the
 * official tool package depends on them, and tighten a few places where the reference had no
 * bound at all. Every tightening is listed here so the wire behaviour stays explainable:
 *
 * - node count / walk depth / per-node text: same as the reference (250 / 40 / 120);
 * - hold timeout and finger count: same as the reference (30 s / 8);
 * - one gesture stroke is bounded by [DshCapabilityLimits.MAX_GESTURE_DURATION_MILLIS] (10 s); the
 *   reference accepted any `durationMs` and only capped the whole timeline at 120 s. MCA caps the
 *   whole timeline at 60 s so one request cannot hold the main thread for two minutes;
 * - a wait step is bounded by [MAX_WAIT_MILLIS] instead of the reference's unbounded `ms`;
 * - screenshot bytes are bounded by [MAX_SCREENSHOT_BYTES]; a larger PNG is deleted and reported
 *   as `TOO_LARGE` rather than handed to the tool chain;
 * - input text is bounded by the contract's [DshCapabilityLimits.MAX_TYPE_TEXT_CHARS]. The
 *   transport already rejects query values longer than
 *   [DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS]; the policy cap is the semantic limit and is
 *   also reachable through the module-level parser API used by tests.
 */
object A11yLimits {
    const val MAX_NODES = 250
    const val MAX_DEPTH = 40
    const val MAX_NODE_TEXT_CHARS = 120

    const val MAX_SCREEN_TEXT_CHARS = DshCapabilityLimits.MAX_SCREEN_TEXT_CHARS
    const val MAX_INPUT_TEXT_CHARS = DshCapabilityLimits.MAX_TYPE_TEXT_CHARS
    const val MAX_MATCH_TEXT_CHARS = DshCapabilityRouteLimits.MAX_QUERY_VALUE_CHARS
    const val MAX_COORDINATE = DshCapabilityLimits.MAX_COORDINATE
    const val MAX_GESTURE_DURATION_MILLIS = DshCapabilityLimits.MAX_GESTURE_DURATION_MILLIS

    const val MAX_FINGERS = 8
    const val HOLD_TIMEOUT_MILLIS = 30_000L
    const val MAX_GESTURE_TOTAL_MILLIS = 60_000L
    const val MAX_GESTURE_STROKES = 10
    const val MAX_WAIT_MILLIS = 10_000L
    const val DOWN_SEGMENT_MILLIS = 40L

    /** Longest package name accepted as an action target (contract limit). */
    const val MAX_PACKAGE_NAME_CHARS = DshCapabilityLimits.MAX_PACKAGE_NAME_CHARS

    /** Largest PNG this module writes for `/screenshot`; larger captures are discarded. */
    const val MAX_SCREENSHOT_BYTES = 8_388_608L

    /** Bounded status count: enough for a real screen, small enough to stay cheap. */
    const val MAX_STATUS_NODE_COUNT = 5_000

    /** Grid values accepted by `/screenshot`; the official package sends `4` or nothing. */
    val ALLOWED_GRID_VALUES = setOf(0, 4, 8)
    const val MAX_DISPLAY_ID = 100

    const val DEFAULT_TAP_MILLIS = 60L
    const val DEFAULT_SWIPE_MILLIS = 300L
    const val DEFAULT_HOLD_MILLIS = 500L
    const val DEFAULT_MOVE_MILLIS = 100L
    const val DEFAULT_UP_MILLIS = 100L

    const val STATUS_TIMEOUT_MILLIS = 4_000L
    const val DUMP_TIMEOUT_MILLIS = 8_000L
    const val TAP_TIMEOUT_MILLIS = 8_000L
    const val INPUT_TIMEOUT_MILLIS = 5_000L
    const val SCROLL_TIMEOUT_MILLIS = 5_000L
    const val GLOBAL_ACTION_TIMEOUT_MILLIS = 5_000L
    const val SCREENSHOT_TIMEOUT_MILLIS = 8_000L
    const val TOUCH_TIMEOUT_MILLIS = 5_000L
    const val TOUCH_STATUS_TIMEOUT_MILLIS = 2_000L

    /** Extra budget added to the planned gesture timeline before the request is failed. */
    const val GESTURE_SLACK_MILLIS = 5_000L
}
