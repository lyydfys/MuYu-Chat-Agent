package com.muyuchat.feature.chat

/**
 * R05：composer 菜单家族的 1x 基线几何与真实高度预算。
 *
 * The numbers below are the existing rendered values, extracted without changing them: the sheet is
 * 238dp wide, floats 86dp above the composer baseline, its rows are at least 48dp tall with a 34dp
 * icon plate and a 20dp glyph. Keeping them here makes the frozen geometry testable and stops the
 * height cap from being a second, invisible copy of the 86dp placement.
 */
internal object ComposerMenuMetrics {
    const val SHEET_WIDTH_DP = 238
    const val SHEET_HORIZONTAL_PADDING_DP = 10
    const val SHEET_VERTICAL_PADDING_DP = 12
    const val SHEET_ROW_SPACING_DP = 4
    const val SHEET_BOTTOM_OFFSET_DP = 86
    const val ROW_MIN_HEIGHT_DP = 48
    const val ROW_ICON_PLATE_DP = 34
    const val ROW_ICON_GLYPH_DP = 20

    /** Font scale from which long titles/statuses must wrap completely instead of ellipsising. */
    const val LARGE_FONT_SCALE = 1.5f

    const val CAPSULE_WIDTH_DP = 128
    const val CAPSULE_PADDING_DP = 12
    const val CAPSULE_HORIZONTAL_PADDING_DP = 10
    const val CAPSULE_HEADER_VERTICAL_PADDING_DP = 2
    const val CAPSULE_ITEM_SPACING_DP = 7
    const val CAPSULE_CLOSE_GLYPH_DP = 16

    /**
     * Visible glyph stays 16dp; the target is the panel's top-right square.
     *
     * 48dp is not reachable without moving the panel: the first mode pill starts 7dp below the
     * header, so the target is bounded by `panel top padding + header height`. The helper below
     * grows it with the measured header (36dp at 1x, ~48dp at 2x) instead of guessing a fixed size.
     * The exact boundary is asserted in `ChatComposerMenuMetricsTest`; the remaining gap to the
     * 48dp guideline at 1x is reported as a NOT_RUN visual exception.
     */
    const val CAPSULE_CLOSE_TARGET_DP = 36

    /** Visible composer "more" circle; the 48dp exception is reported, not silently laid out. */
    const val COMPOSER_MORE_VISIBLE_DP = 40
    const val COMPOSER_MORE_GLYPH_DP = 24
}

/**
 * Real height of the research capsule close target.
 *
 * It always covers the whole header (the panel's top padding plus the measured header row) and is
 * never smaller than [ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP], so a 16dp glyph that grows with
 * the font scale can never end up outside its own target. It stays above the first pill because the
 * header is followed by [ComposerMenuMetrics.CAPSULE_ITEM_SPACING_DP] of spacing.
 */
internal fun researchCloseTargetHeightDp(headerHeightDp: Int): Int = maxOf(
    ComposerMenuMetrics.CAPSULE_CLOSE_TARGET_DP,
    ComposerMenuMetrics.CAPSULE_PADDING_DP + headerHeightDp
)

/** Available height for a floating menu layer and whether its content has to scroll. */
internal data class ComposerMenuHeightBudget(
    val maxHeightDp: Int,
    val scrollable: Boolean,
)

/**
 * Converts the host's real remaining height into the sheet cap.
 *
 * [hostAvailableHeightDp] is the constraint the host actually delivers: the viewport already less
 * the IME inset and the safe-area/navigation insets (both consumed by the menu's own
 * `navigationBarsPadding`, which is *inside* the measured box), measured with `BoxWithConstraints`.
 * A short screen or an open keyboard therefore shrinks the cap instead of being ignored by a fixed
 * 520dp. [contentHeightDp] is the measured content height; growth is driven only by what the rows
 * really need.
 *
 * The [sheetBottomOffsetDp] is the frozen 1x placement above the composer baseline. It is *not* a
 * cap on the composer's own height: from 1.5x the composer grows taller than 86dp, so at 2x the
 * sheet's bottom padding meets the composer's rounded top edge (measured pixel probe: the sheet's
 * content viewport still ends at the composer's top edge, so no row is covered). Measured on
 * 2304FPN6DC/1440x3200/density560 (artifacts/ui-quality/v9-closeout/font-scale/20261021-041216-411Z):
 * content is ~421dp at 1x and ~645dp at 1.5x (both fully visible without scrolling), and taller than
 * the ~767dp viewport cap at 2x, where the last row is reached through the scroll container. The
 * 12dp inner bottom padding absorbs the remaining edge overlap. If a future device ever hides the
 * last row, the correct fix is to pass the measured composer height into this budget from the host
 * (ChatComposerScreens.kt), not to shrink the rows or the type.
 *
 * An unbounded host (preview/test hosts that measure with infinite height) keeps the content
 * unconstrained instead of inventing a cap.
 */
internal fun composerMenuHeightBudget(
    hostAvailableHeightDp: Int,
    contentHeightDp: Int,
    sheetBottomOffsetDp: Int = ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP,
    minSheetHeightDp: Int = ComposerMenuMetrics.ROW_MIN_HEIGHT_DP,
): ComposerMenuHeightBudget {
    val usableHeightDp = if (hostAvailableHeightDp >= Int.MAX_VALUE) {
        Int.MAX_VALUE
    } else {
        (hostAvailableHeightDp - sheetBottomOffsetDp).coerceAtLeast(minSheetHeightDp)
    }
    return ComposerMenuHeightBudget(
        maxHeightDp = usableHeightDp,
        scrollable = usableHeightDp != Int.MAX_VALUE && contentHeightDp > usableHeightDp,
    )
}
