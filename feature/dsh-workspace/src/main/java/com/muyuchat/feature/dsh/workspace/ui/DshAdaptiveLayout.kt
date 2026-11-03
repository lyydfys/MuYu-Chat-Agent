package com.muyuchat.feature.dsh.workspace.ui

import androidx.compose.runtime.Composable
import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import com.muyuchat.core.designsystem.mcaWindowWidth
import com.muyuchat.core.designsystem.mcaWindowWidthFor
import com.muyuchat.core.designsystem.supportsTwoPane
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foldable / large-screen policy of the DSH workspace, expressed as pure functions.
 *
 * The breakpoint ladder is **not** re-declared here: it is the app-wide
 * [McaWindowWidth] ladder from `:core:designsystem` (`< 600dp` COMPACT, `600..839dp`
 * MEDIUM, `>= 840dp` EXPANDED). [DshWindowWidth] is an alias so DSH call sites read
 * naturally while there is exactly one source of the 600/840 numbers.
 *
 * Dependency note: no `androidx.window` / `material3-adaptive` is involved. Everything
 * below is arithmetic over `screenWidthDp`/dp/px values, so it is JVM-testable
 * (`DshAdaptiveLayoutTest`). Hinge-aware layouts (dual-pane posture, hinge occlusion)
 * need `WindowInfoTracker` and are deliberately out of scope for this module.
 *
 * COMPACT contract: every function returns the value that reproduces today's phone
 * behaviour (no rail, no width cap, the original floating-ball metrics, the official
 * viewport declaration untouched).
 */
internal typealias DshWindowWidth = McaWindowWidth

/** Window-width class of the DSH surface; thin wrapper over the app-wide ladder. */
internal fun dshWindowWidthFor(screenWidthDp: Int): DshWindowWidth = mcaWindowWidthFor(screenWidthDp)

/** The width class of the current window (recomputed on configuration change). */
@Composable
internal fun dshWindowWidth(): DshWindowWidth = mcaWindowWidth()

/** Same rule as [McaWindowWidth.supportsTwoPane], named for the DSH surface. */
internal fun dshSupportsRail(width: DshWindowWidth): Boolean = width.supportsTwoPane()

// Rail geometry mirrors the app-wide two-pane defaults (`McaAdaptiveTwoPane`):
// rail at most 360dp, content column at least 420dp.
internal const val DSH_RAIL_MAX_WIDTH_DP: Int = 360
internal const val DSH_MIN_WEB_PANE_WIDTH_DP: Int = 420

// Text-heavy host surfaces (starting/failure shells, dialogs) are capped on wide windows.
// 560dp is a comfortable measure for centred body text and matches the console/dialog scale;
// 640dp on a fully unfolded book-style foldable still leaves visible margin.
private const val DSH_TEXT_SURFACE_MAX_WIDTH_MEDIUM_DP: Int = 560
private const val DSH_TEXT_SURFACE_MAX_WIDTH_EXPANDED_DP: Int = 640

/**
 * The narrowest window that counts as a wide DSH surface, derived from the ladder instead of
 * a second `600` literal. The WebView shim uses it as its CSS-pixel threshold.
 */
internal val dshWideWindowMinDp: Int =
    (0..DSH_WIDE_WINDOW_SEARCH_LIMIT_DP).first { dshSupportsRail(dshWindowWidthFor(it)) }

/** Upper bound of the threshold search; far beyond any real window in dp. */
private const val DSH_WIDE_WINDOW_SEARCH_LIMIT_DP: Int = 10_000

/**
 * Width of the left rail in the wide two-pane layout.
 *
 * `0` means "no rail": COMPACT keeps the single-column page, and a window that cannot keep
 * [DSH_MIN_WEB_PANE_WIDTH_DP] for the WebView never loses width to a rail.
 */
internal fun dshWorkspaceRailWidthFor(availableWidthDp: Int, twoPane: Boolean): Int {
    if (!twoPane) return 0
    val spare = availableWidthDp - DSH_MIN_WEB_PANE_WIDTH_DP
    if (spare <= 0) return 0
    return min(spare, DSH_RAIL_MAX_WIDTH_DP)
}

/** Reading ceiling of the console body: the app-wide content ceiling for this width class. */
internal fun dshContentMaxWidthFor(width: DshWindowWidth): Int =
    mcaContentMaxWidthFor(width).value.roundToInt()

/**
 * Width cap for centred host text (starting/failure shell), or `null` to keep the current
 * full-width phone layout. COMPACT must return `null`: the phone shell is unchanged.
 */
internal fun dshShellMaxWidthFor(width: DshWindowWidth): Int? = when (width) {
    McaWindowWidth.COMPACT -> null
    McaWindowWidth.MEDIUM -> DSH_TEXT_SURFACE_MAX_WIDTH_MEDIUM_DP
    McaWindowWidth.EXPANDED -> DSH_TEXT_SURFACE_MAX_WIDTH_EXPANDED_DP
}

/**
 * Width cap for the DSH dialogs on wide windows, or `null` to keep the platform default
 * dialog width (COMPACT). A dialog stretched across an unfolded foldable is the regression
 * this prevents.
 */
internal fun dshDialogMaxWidthFor(width: DshWindowWidth): Int? = when (width) {
    McaWindowWidth.COMPACT -> null
    McaWindowWidth.MEDIUM -> DSH_TEXT_SURFACE_MAX_WIDTH_MEDIUM_DP
    McaWindowWidth.EXPANDED -> DSH_TEXT_SURFACE_MAX_WIDTH_EXPANDED_DP
}

/**
 * Floating-ball metrics per width class.
 *
 * COMPACT (12dp edge / 48dp ball / uncapped travel) is the existing phone ball, untouched.
 * Wide windows push the ball off the physical edge and grow the target slightly (it is now
 * an overlay on a 600dp+ canvas), and [maxHorizontalTravelDp] bounds the drag band so the
 * ball cannot smear across the whole unfolded display. `0` means "no cap".
 */
internal data class DshBallMetrics(
    val edgeMarginDp: Int,
    val ballSizeDp: Int,
    val maxHorizontalTravelDp: Int
)

internal fun dshBallMetricsFor(width: DshWindowWidth): DshBallMetrics = when (width) {
    McaWindowWidth.COMPACT -> DshBallMetrics(edgeMarginDp = 12, ballSizeDp = 48, maxHorizontalTravelDp = 0)
    McaWindowWidth.MEDIUM -> DshBallMetrics(edgeMarginDp = 20, ballSizeDp = 52, maxHorizontalTravelDp = 320)
    McaWindowWidth.EXPANDED -> DshBallMetrics(edgeMarginDp = 24, ballSizeDp = 56, maxHorizontalTravelDp = 420)
}

/** Travel cap in pixels; `0`/invalid cap means "uncapped" (the COMPACT phone contract). */
internal fun dshBallTravelCapPx(maxTravelDp: Int, density: Float): Float =
    if (maxTravelDp <= 0 || !density.isFinite() || density <= 0f) {
        Float.POSITIVE_INFINITY
    } else {
        maxTravelDp * density
    }

/**
 * Effective horizontal drag range: the real pane travel, capped on wide windows.
 *
 * Non-finite or non-positive values never produce a negative/NaN range: a broken measure
 * pins the ball instead of sending it outside the page.
 */
internal fun dshBallCappedTravelPx(travelPx: Float, capPx: Float): Float {
    if (!travelPx.isFinite() || travelPx <= 0f) return 0f
    if (!capPx.isFinite() || capPx <= 0f) return travelPx
    return min(travelPx, capPx)
}

/**
 * Horizontal position of the ball for a persisted fraction, anchored to the centre of its usable
 * band on the page.
 *
 * With the uncapped phone values (`startInsetPx == endInsetPx == edgePx`, `travelPx == full travel`)
 * this is algebraically the previous `edge + travel * fraction` formula, so the COMPACT placement
 * does not move. [startInsetPx] carries the wide-window rail so the ball can never park on top of
 * the rail's buttons, and a capped [travelPx] keeps the drag band centred instead of stretched
 * across an unfolded display (no edge-to-edge drifting).
 */
internal fun dshBallXOffsetPx(
    fraction: Float,
    availableWidthPx: Float,
    ballSizePx: Float,
    startInsetPx: Float,
    endInsetPx: Float,
    travelPx: Float
): Float {
    if (!availableWidthPx.isFinite() || !ballSizePx.isFinite() || !travelPx.isFinite()) return 0f
    if (!startInsetPx.isFinite() || !endInsetPx.isFinite()) return 0f
    val safeFraction = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0.5f
    val usableStart = startInsetPx.coerceAtLeast(0f)
    val usableRight = availableWidthPx - ballSizePx - endInsetPx.coerceAtLeast(0f)
    val fullTravel = (usableRight - usableStart).coerceAtLeast(0f)
    val effectiveTravel = travelPx.coerceIn(0f, fullTravel)
    // Fraction 0 parks the ball at the usable start, 1 at the usable end (exactly as before).
    return usableStart + fullTravel * 0.5f + effectiveTravel * (safeFraction - 0.5f)
}

/**
 * Left inset of the ball's drag band: the wide-window rail (0 on a phone) plus the safe margin,
 * so the ball never parks on the rail's buttons. Returns the plain margin on COMPACT, which is the
 * existing phone behaviour.
 */
internal fun dshBallStartInsetDp(railWidthDp: Int, edgeMarginDp: Int): Int =
    railWidthDp.coerceAtLeast(0) + edgeMarginDp.coerceAtLeast(0)

/**
 * `true` when the WebView viewport may be normalised to `width=device-width`.
 *
 * COMPACT returns `false`: the official page's own mobile declaration is left exactly as
 * served, so a normal phone cannot see a viewport change from this module.
 */
internal fun dshNormalizesViewportWidth(width: DshWindowWidth): Boolean = dshSupportsRail(width)

/**
 * The `width` token this shim installs on a wide window.
 *
 * The official DSH document declares `width=device-width`; this only rewrites declarations
 * that pin a phone pixel width (`width=390`, `width=412`, …) or omit `width` entirely.
 */
internal const val DSH_WIDE_VIEWPORT_CONTENT: String = "width=device-width"

private const val DSH_VIEWPORT_WIDTH_TOKEN = "width"
private const val DSH_VIEWPORT_DEVICE_WIDTH = "device-width"

/**
 * Rewritten `content` of the page's viewport `<meta>`, or `null` when it must stay as served.
 *
 * Pure mirror of the rule injected by `DshPageLayoutShim`: other tokens
 * (`initial-scale`, `maximum-scale`, `viewport-fit`, …) and their order are preserved, so
 * pinch zoom and the official mobile behaviour are not re-specified here. On COMPACT — and
 * for an already-correct `width=device-width` declaration on any width — this returns
 * `null` and the page is untouched.
 */
internal fun dshViewportContentFor(declaredContent: String?, width: DshWindowWidth): String? {
    if (!dshNormalizesViewportWidth(width)) return null
    val tokens = declaredContent.orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return DSH_WIDE_VIEWPORT_CONTENT
    val widthIndex = tokens.indexOfFirst {
        it.substringBefore('=').trim().equals(DSH_VIEWPORT_WIDTH_TOKEN, ignoreCase = true)
    }
    if (widthIndex < 0) {
        // No width token at all: Chromium would fall back to a desktop-width layout.
        return DSH_WIDE_VIEWPORT_CONTENT + ", " + tokens.joinToString(", ")
    }
    val declaredWidth = tokens[widthIndex].substringAfter('=', "").trim()
    if (declaredWidth.equals(DSH_VIEWPORT_DEVICE_WIDTH, ignoreCase = true)) return null
    val rewritten = tokens.toMutableList()
    rewritten[widthIndex] = "$DSH_VIEWPORT_WIDTH_TOKEN=$DSH_VIEWPORT_DEVICE_WIDTH"
    return rewritten.joinToString(", ")
}
