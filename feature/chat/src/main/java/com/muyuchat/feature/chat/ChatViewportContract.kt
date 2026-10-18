package com.muyuchat.feature.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The composer is the only measured source of the chat viewport's bottom overlap.
 *
 * The fallback is used only until the first layout pass and deliberately keeps the previous
 * 118dp first-frame inset (100dp chrome + 18dp reading gap), so measuring the real composer does
 * not shift the list on entry. After measurement, short read-only notices and compact composers
 * reserve only what they actually cover.
 */
internal const val CHAT_COMPOSER_FALLBACK_HEIGHT_DP = 100
internal const val CHAT_LIST_BOTTOM_GAP_DP = 18

internal fun resolveChatComposerOverlapDp(measuredHeight: Dp): Dp =
    measuredHeight.takeIf { it > 0.dp } ?: CHAT_COMPOSER_FALLBACK_HEIGHT_DP.dp

/**
 * Content padding is derived from the same overlap used by overlays. The gap is a reading
 * affordance, not a second independent bottom-chrome approximation.
 */
internal fun resolveChatListBottomPaddingDp(composerOverlap: Dp): Dp =
    resolveChatComposerOverlapDp(composerOverlap) + CHAT_LIST_BOTTOM_GAP_DP.dp
