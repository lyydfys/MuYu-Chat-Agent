package com.muyuchat.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The single window-width ladder for the whole app.
 *
 * Why one ladder: foldables, tablets and desktop-mode windows were being handled ad hoc inside
 * individual screens, so the same device could read as "wide" on one page and "phone" on the next.
 * The three buckets follow the Material window-size classes; the thresholds are the documented
 * 600dp / 840dp breakpoints, and the values are pinned by `McaAdaptiveWindowWidthTest`.
 *
 * No new dependency is involved: `LocalConfiguration.screenWidthDp` is available everywhere, so a
 * feature module can adopt this without touching its build file. Hinge-aware layouts (an unfolded
 * device whose two halves are separated by a physical hinge) need `androidx.window`
 * `WindowInfoTracker`; that is a separate, deliberate dependency decision and is NOT covered here.
 */
enum class McaWindowWidth {
    /** Phone portrait, split-screen narrow pane, cover display. */
    COMPACT,

    /** Unfolded foldable or small tablet: room for a rail plus one content column. */
    MEDIUM,

    /** Large tablet, desktop-mode window, fully unfolded book-style foldable. */
    EXPANDED
}

/** Pure breakpoint mapping, so the ladder is unit-testable without a composition. */
fun mcaWindowWidthFor(screenWidthDp: Int): McaWindowWidth = when {
    screenWidthDp >= 840 -> McaWindowWidth.EXPANDED
    screenWidthDp >= 600 -> McaWindowWidth.MEDIUM
    else -> McaWindowWidth.COMPACT
}

/**
 * Reading ceiling per width class.
 *
 * A phone column stretched to a 900dp pane is the most common foldable regression: chat bubbles
 * become two-metre lines and a settings row loses its label-to-control relationship. These values
 * keep a comfortable measure (about 60–75 CJK characters) while still using the extra width for
 * navigation and secondary panes.
 */
fun mcaContentMaxWidthFor(width: McaWindowWidth): Dp = when (width) {
    McaWindowWidth.COMPACT -> 720.dp
    McaWindowWidth.MEDIUM -> 780.dp
    McaWindowWidth.EXPANDED -> 940.dp
}

/** True when there is enough width for a navigation rail beside content. */
fun McaWindowWidth.supportsTwoPane(): Boolean = this != McaWindowWidth.COMPACT

/** The width class of the current window, re-evaluated on configuration change. */
@Composable
@ReadOnlyComposable
fun mcaWindowWidth(): McaWindowWidth =
    mcaWindowWidthFor(LocalConfiguration.current.screenWidthDp)

/**
 * Centres content at a readable maximum width without changing its vertical behaviour.
 *
 * Use it around a whole page body (list + empty state), not around a fixed-height bar: bars are
 * usually meant to span the window.
 */
@Composable
fun McaContentWidth(
    modifier: Modifier = Modifier,
    maxWidth: Dp = mcaContentMaxWidthFor(mcaWindowWidth()),
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxHeight()) {
            content()
        }
    }
}

/**
 * Rail + content layout for wide windows, single column otherwise.
 *
 * [rail] is only composed when [twoPane] is true, so screens can keep their existing single-column
 * body as [content] and add a rail as an enhancement instead of a rewrite. When the window narrows
 * the rail disappears; the screen must then expose the same destinations in its own header.
 */
@Composable
fun McaAdaptiveTwoPane(
    rail: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    twoPane: Boolean = mcaWindowWidth().supportsTwoPane(),
    railMaxWidth: Dp = 360.dp,
    minContentWidth: Dp = 420.dp
) {
    if (!twoPane) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Keep the content column usable on a 600dp window before spending width on the rail.
        val railWidth = (maxWidth - minContentWidth).coerceIn(0.dp, railMaxWidth)
        if (railWidth <= 0.dp) {
            content()
        } else {
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Start) {
                Box(modifier = Modifier.widthIn(max = railWidth).fillMaxHeight()) { rail() }
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }
}
