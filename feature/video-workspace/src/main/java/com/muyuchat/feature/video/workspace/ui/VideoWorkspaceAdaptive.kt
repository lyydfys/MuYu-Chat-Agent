package com.muyuchat.feature.video.workspace.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaAdaptiveTwoPane
import com.muyuchat.core.designsystem.McaContentWidth
import com.muyuchat.core.designsystem.McaWindowWidth
import com.muyuchat.core.designsystem.mcaContentMaxWidthFor

/**
 * How the video workspace spends an unfolded/tablet window.
 *
 * The page is a long vertical form (mode chips, engine/decode card, model assets, guide, example
 * prompts) followed by the workflow surface (canvas or simplified chain) and the run/output
 * preview. On a phone that order is the page; on a wide window the settings half and the workflow
 * half can sit side by side, and the canvas must keep the width it gains instead of being squeezed
 * into a reading column. Both facts are decided here, as a pure function of the shared width ladder
 * ([McaWindowWidth]), so a JVM test can pin them and so the phone answer stays a single explicit
 * "no adaptation" case.
 *
 * Breakpoints and reading ceilings are NOT redefined here: [mcaContentMaxWidthFor] owns the
 * ceilings and [McaAdaptiveTwoPane] owns the rail/content split math.
 */
internal data class VideoWorkspaceAdaptiveLayout(
    /** EXPANDED only: settings column on the left, canvas/output column on the right. */
    val twoPane: Boolean,
    /**
     * Reading ceiling for the settings/form column, always from the shared ladder. Only applied
     * when [limitsFormWidth] is true.
     */
    val formMaxWidth: Dp,
    /**
     * MEDIUM only: the vertical page caps the settings/form cards but lets the workflow canvas use
     * the full window width, so a foldable does not lose canvas width to a text measure.
     */
    val limitsFormWidth: Boolean,
    /**
     * Ceiling for the settings column in the two-pane layout, passed to
     * [McaAdaptiveTwoPane.railMaxWidth]. Unused (0.dp) outside EXPANDED.
     */
    val settingsRailMaxWidth: Dp,
    /**
     * Smallest width the canvas/output pane must keep before the rail gives up width, passed to
     * [McaAdaptiveTwoPane.minContentWidth]. Unused (0.dp) outside EXPANDED.
     */
    val canvasMinWidth: Dp,
    /**
     * EXPANDED only: the guide and the example prompts start expanded because the settings column
     * has the room and the onboarding copy is what a first-time user needs. The phone default
     * (collapsed, two 56dp headers above the fold) is unchanged.
     */
    val expandUsageCardsByDefault: Boolean
)

/**
 * Settings column ceiling in the two-pane layout. Wide enough for the model-asset rows and the two
 * engine switches without stretching their label-to-control relationship.
 */
private val VIDEO_SETTINGS_RAIL_MAX_WIDTH = 360.dp

/**
 * Canvas/output floor. Applied as `McaAdaptiveTwoPane.minContentWidth`, so an 840dp unfolded window
 * still gives the canvas 520dp and only a genuinely wide window lets the rail reach 360dp.
 */
private val VIDEO_CANVAS_MIN_WIDTH = 520.dp

/** Pure width-class decision for the video workspace; see [VideoWorkspaceAdaptiveLayout]. */
internal fun videoWorkspaceAdaptiveLayoutFor(width: McaWindowWidth): VideoWorkspaceAdaptiveLayout =
    when (width) {
        // Phone (and cover display / narrow split pane): the existing single scrolling column.
        McaWindowWidth.COMPACT -> VideoWorkspaceAdaptiveLayout(
            twoPane = false,
            formMaxWidth = mcaContentMaxWidthFor(width),
            limitsFormWidth = false,
            settingsRailMaxWidth = 0.dp,
            canvasMinWidth = 0.dp,
            expandUsageCardsByDefault = false
        )
        // Unfolded foldable / small tablet: still one column, but the forms stop stretching and the
        // guide/examples keep their phone default (collapsed).
        McaWindowWidth.MEDIUM -> VideoWorkspaceAdaptiveLayout(
            twoPane = false,
            formMaxWidth = mcaContentMaxWidthFor(width),
            limitsFormWidth = true,
            settingsRailMaxWidth = 0.dp,
            canvasMinWidth = 0.dp,
            expandUsageCardsByDefault = false
        )
        // Large tablet / desktop window / fully unfolded book foldable: settings beside the
        // workflow surface. The rail bounds the forms, so no extra content ceiling is needed on the
        // canvas side — capping it there would waste exactly the width this layout exists to use.
        McaWindowWidth.EXPANDED -> VideoWorkspaceAdaptiveLayout(
            twoPane = true,
            formMaxWidth = mcaContentMaxWidthFor(width),
            limitsFormWidth = false,
            settingsRailMaxWidth = VIDEO_SETTINGS_RAIL_MAX_WIDTH,
            canvasMinWidth = VIDEO_CANVAS_MIN_WIDTH,
            expandUsageCardsByDefault = true
        )
    }
