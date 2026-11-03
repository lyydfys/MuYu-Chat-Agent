package com.muyuchat.feature.modelhub

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor

/**
 * How the model hub spends an unfolded/tablet window.
 *
 * Why a decision type instead of an `if (windowWidth != COMPACT)` inside the screen: the five
 * sections are currently a phone segmented strip, and the foldable/tablet version has to turn them
 * into a permanent rail *without* touching the phone. The rule is therefore a pure function of the
 * shared width ladder ([McaWindowWidth]), so it can be pinned by a JVM test and so the phone branch
 * stays a single explicit "no adaptation" answer instead of a scattered set of conditionals.
 *
 * The breakpoints are NOT redefined here: [mcaContentMaxWidthFor] owns the reading ceilings and
 * [McaAdaptiveTwoPane] owns the rail/content split math.
 */
internal data class ModelHubAdaptiveLayout(
    /** MEDIUM/EXPANDED: the five sections live in a permanent left rail; phone keeps the strip. */
    val twoPane: Boolean,
    /**
     * Ceiling for the rail width, passed straight to `McaAdaptiveTwoPane.railMaxWidth`.
     * Unused (0.dp) when [twoPane] is false.
     */
    val railMaxWidth: Dp,
    /**
     * Reading ceiling of the section column, always taken from the shared ladder. Only applied
     * when [limitsContentWidth] is true, so the phone keeps its exact current layout.
     */
    val contentMaxWidth: Dp,
    /**
     * False on phone: the segmented strip plus the full-width list are already readable at
     * < 600dp, so the phone must not gain an extra centring container.
     */
    val limitsContentWidth: Boolean
)

/**
 * The five 2-character labels fit well under 160dp; the rail stays a rail and the content column
 * keeps the rest of the window. `McaAdaptiveTwoPane` additionally shrinks the rail when the window
 * cannot afford it (it reserves 420dp of content first), so these values are ceilings, not widths.
 */
private val MODEL_HUB_MEDIUM_RAIL_MAX_WIDTH = 200.dp
private val MODEL_HUB_EXPANDED_RAIL_MAX_WIDTH = 240.dp

/** Pure width-class decision for the model hub; see [ModelHubAdaptiveLayout]. */
internal fun modelHubAdaptiveLayoutFor(width: McaWindowWidth): ModelHubAdaptiveLayout =
    when (width) {
        // Phone (and cover display / narrow split pane): byte-for-byte the existing layout.
        McaWindowWidth.COMPACT -> ModelHubAdaptiveLayout(
            twoPane = false,
            railMaxWidth = 0.dp,
            contentMaxWidth = mcaContentMaxWidthFor(width),
            limitsContentWidth = false
        )
        // Unfolded foldable / small tablet: rail + one readable column.
        McaWindowWidth.MEDIUM -> ModelHubAdaptiveLayout(
            twoPane = true,
            railMaxWidth = MODEL_HUB_MEDIUM_RAIL_MAX_WIDTH,
            contentMaxWidth = mcaContentMaxWidthFor(width),
            limitsContentWidth = true
        )
        // Large tablet / desktop window / fully unfolded book foldable.
        McaWindowWidth.EXPANDED -> ModelHubAdaptiveLayout(
            twoPane = true,
            railMaxWidth = MODEL_HUB_EXPANDED_RAIL_MAX_WIDTH,
            contentMaxWidth = mcaContentMaxWidthFor(width),
            limitsContentWidth = true
        )
    }
