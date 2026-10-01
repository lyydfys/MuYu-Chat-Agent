package com.muyuchat.mca.presentation

import com.muyuchat.feature.chat.ChatBackgroundScope
import com.muyuchat.feature.chat.ChatBackgroundState
import com.muyuchat.mca.ChatAppearance
import com.muyuchat.mca.ChatAppearanceScope
import com.muyuchat.mca.ChatBackgroundScaleMode
import com.muyuchat.feature.chat.ChatBackgroundScaleMode as UiChatBackgroundScaleMode

// Chat background appearance <-> UI state mapping, moved verbatim from MainActivity during P2.
// These convert between the stored appearance and the screen state object; no field, default
// or branch was edited.

internal fun ChatAppearance.toUiState(): ChatBackgroundState = ChatBackgroundState(
    imageUri = backgroundImagePath,
    scrimAlpha = backgroundAlpha,
    blurRadius = backgroundBlur,
    scaleMode = when (backgroundScaleMode) {
        ChatBackgroundScaleMode.CROP -> UiChatBackgroundScaleMode.CROP
        ChatBackgroundScaleMode.FIT -> UiChatBackgroundScaleMode.FIT
        ChatBackgroundScaleMode.CENTER -> UiChatBackgroundScaleMode.CENTER
    }
)

internal fun ChatBackgroundState.toAppearance(): ChatAppearance = ChatAppearance(
    backgroundImagePath = imageUri,
    backgroundAlpha = scrimAlpha.coerceIn(0f, 1f),
    backgroundBlur = blurRadius.coerceIn(0f, ChatAppearance.MAX_BLUR),
    backgroundScaleMode = when (scaleMode) {
        UiChatBackgroundScaleMode.CROP -> ChatBackgroundScaleMode.CROP
        UiChatBackgroundScaleMode.FIT -> ChatBackgroundScaleMode.FIT
        UiChatBackgroundScaleMode.CENTER -> ChatBackgroundScaleMode.CENTER
    }
)

internal fun ChatBackgroundScope.toAppearanceScope(): ChatAppearanceScope = when (this) {
    ChatBackgroundScope.GLOBAL -> ChatAppearanceScope.GLOBAL
    ChatBackgroundScope.ASSISTANT -> ChatAppearanceScope.ASSISTANT
    ChatBackgroundScope.SESSION -> ChatAppearanceScope.SESSION
}
