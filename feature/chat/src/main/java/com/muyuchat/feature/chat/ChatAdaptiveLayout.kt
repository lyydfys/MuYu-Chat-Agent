package com.muyuchat.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaAdaptiveTwoPane
import com.muyuchat.core.designsystem.McaContentWidth
import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor
import com.muyuchat.core.designsystem.mcaWindowWidth

/**
 * How the chat shell spends an unfolded/tablet window.
 *
 * The rule exists as a value instead of a scatter of `if (wide)` checks inside the screen for the
 * same reason the model hub has one: the phone answer has to stay a single explicit "no
 * adaptation" answer, so a future edit cannot accidentally push a centring container or a rail
 * into the COMPACT path. Every field is derived from the shared ladder in `core:designsystem`;
 * this file adds no breakpoint of its own.
 *
 * COMPACT is the frozen path: [twoPane] is false, [railMaxWidth] is unused and
 * [readingMaxWidth] is null, which means no extra layout node is inserted anywhere below.
 */
internal data class ChatAdaptiveLayout(
    /** MEDIUM/EXPANDED: history is a permanent left column; phone keeps the modal drawer. */
    val twoPane: Boolean,
    /** Ceiling handed to `McaAdaptiveTwoPane.railMaxWidth`; 0.dp when there is no rail. */
    val railMaxWidth: Dp,
    /**
     * Reading ceiling for the message column, the composer shell and the image prompt bar,
     * taken from [mcaContentMaxWidthFor]. Null on COMPACT: nothing is constrained or centred.
     */
    val readingMaxWidth: Dp?
)

/**
 * The history panel keeps its phone drawer width budget as the rail's ceiling: the rows are a
 * title, four entries and the recent list, so 300dp stays readable while the content column keeps
 * the rest of the window (`McaAdaptiveTwoPane` shrinks the rail further if it must).
 */
private val CHAT_HISTORY_RAIL_MAX_WIDTH = 300.dp

/**
 * Chat stays readable at phone width, so the two-pane split only has to leave a phone-wide column
 * on the right. The shared default reserves 420dp; asking for 360dp keeps the rail from collapsing
 * to ~180dp on a just-unfolded 600dp window, where the drawer header row would start clipping.
 */
private val CHAT_RAIL_MIN_CONTENT_WIDTH = 360.dp

/** Pure width-class decision for the chat shell; see [ChatAdaptiveLayout]. */
internal fun chatAdaptiveLayoutFor(width: McaWindowWidth): ChatAdaptiveLayout = when (width) {
    // Phone (and cover display / narrow split pane): byte-for-byte the existing layout.
    McaWindowWidth.COMPACT -> ChatAdaptiveLayout(
        twoPane = false,
        railMaxWidth = 0.dp,
        readingMaxWidth = null
    )
    // Unfolded foldable, small tablet, desktop-mode window: rail plus one readable column.
    McaWindowWidth.MEDIUM,
    McaWindowWidth.EXPANDED -> ChatAdaptiveLayout(
        twoPane = true,
        railMaxWidth = CHAT_HISTORY_RAIL_MAX_WIDTH,
        readingMaxWidth = mcaContentMaxWidthFor(width)
    )
}

/** Current window's chat decision, re-evaluated on configuration change. */
@Composable
@ReadOnlyComposable
internal fun chatAdaptiveLayout(): ChatAdaptiveLayout = chatAdaptiveLayoutFor(mcaWindowWidth())

/**
 * Rail + conversation host. With [ChatAdaptiveLayout.twoPane] false it composes [content] alone:
 * the phone composition has no extra wrapper, rail or divider.
 */
@Composable
internal fun ChatWideRailHost(
    layout: ChatAdaptiveLayout,
    rail: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    if (!layout.twoPane) {
        content()
        return
    }
    McaAdaptiveTwoPane(
        rail = { ChatHistoryRail(rail) },
        content = content,
        twoPane = true,
        railMaxWidth = layout.railMaxWidth,
        minContentWidth = CHAT_RAIL_MIN_CONTENT_WIDTH
    )
}

/** A permanent column reads as its own pane: app background plus one hairline against the chat. */
@Composable
private fun ChatHistoryRail(rail: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { rail() }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        )
    }
}

/**
 * History container: the unchanged modal drawer sheet on a phone, a plain pane when [rail] is true
 * (the rail column and its background are already provided by [ChatWideRailHost]).
 */
@Composable
internal fun ChatHistorySurface(
    rail: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (rail) {
        content()
    } else {
        ModalDrawerSheet(
            modifier = modifier,
            drawerContainerColor = MaterialTheme.colorScheme.background
        ) {
            content()
        }
    }
}

/**
 * Centres [content] at the shared reading ceiling on wide windows and composes it untouched on a
 * phone. Use around a body column, not around a bar that is meant to span the window.
 */
@Composable
internal fun ChatReadingWidth(layout: ChatAdaptiveLayout, content: @Composable () -> Unit) {
    val maxWidth = layout.readingMaxWidth
    if (maxWidth == null) {
        content()
        return
    }
    McaContentWidth(maxWidth = maxWidth) { content() }
}

/**
 * The reading ceiling as a plain modifier for hosts that align their own child (the image prompt
 * bar sits at `BottomCenter` of its page). COMPACT returns the receiver unchanged, so the phone
 * modifier chain is identical to the pre-adaptive one.
 */
internal fun Modifier.chatReadingCeiling(layout: ChatAdaptiveLayout): Modifier =
    layout.readingMaxWidth
        ?.let { maxWidth -> widthIn(max = maxWidth) }
        ?: this
