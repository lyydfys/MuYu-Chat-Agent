package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Everything the chat page needs besides its state and its actions.
 *
 * `ChatScreen` took these as thirteen separate optional parameters, which made the call site the
 * longest piece of wiring in `MainActivity` (238 lines, 95 action lambdas) and made "constructing
 * the page" a 16-argument proposition. They travel together — every one of them is derived from the
 * same appearance/session snapshot — so they are one value now.
 *
 * This is the app-side mapping's output type, not a feature state owner: it holds no behaviour and
 * no life cycle. The route still receives its state and actions from the caller, which keeps the
 * single-writer rule intact while the feature grows its own view model.
 */
data class ChatRouteInputs(
    val offlineTranslationStatus: String = "未安装",
    val offlineTranslationInstalled: Boolean = false,
    val offlineTranslationInstalling: Boolean = false,
    val offlineTranslationDownloading: Boolean = false,
    val offlineTranslationDownloadProgress: Float? = null,
    val assistantMemories: List<AssistantMemoryUiItem> = emptyList(),
    val appMenuOpen: Boolean = false,
    val chatBackground: ChatBackgroundState = ChatBackgroundState(),
    val globalChatBackground: ChatBackgroundState = ChatBackgroundState(),
    val assistantChatBackground: ChatBackgroundState? = null,
    val sessionChatBackground: ChatBackgroundState? = null,
    val hasActiveChatSession: Boolean = false,
    val chatBackgroundImporting: Boolean = false
)

/**
 * The chat page's mounting point.
 *
 * Callers hand it one [ChatUiState], one [ChatRouteInputs] and one [ChatScreenActions]; it forwards
 * them to [ChatScreen] unchanged. The point is not to add indirection for its own sake but to give
 * the chat page a single place to be wired from, so the composition root stops spelling out
 * thirteen derived values inline and so a future chat-owned view model can be introduced behind
 * this boundary without touching the call site again.
 *
 * Defaults here match [ChatScreen]'s defaults exactly; nothing is re-derived.
 */
@Composable
fun ChatRoute(
    state: ChatUiState,
    inputs: ChatRouteInputs,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier
) {
    ChatScreen(
        state = state,
        offlineTranslationStatus = inputs.offlineTranslationStatus,
        offlineTranslationInstalled = inputs.offlineTranslationInstalled,
        offlineTranslationInstalling = inputs.offlineTranslationInstalling,
        offlineTranslationDownloading = inputs.offlineTranslationDownloading,
        offlineTranslationDownloadProgress = inputs.offlineTranslationDownloadProgress,
        assistantMemories = inputs.assistantMemories,
        appMenuOpen = inputs.appMenuOpen,
        chatBackground = inputs.chatBackground,
        globalChatBackground = inputs.globalChatBackground,
        assistantChatBackground = inputs.assistantChatBackground,
        sessionChatBackground = inputs.sessionChatBackground,
        hasActiveChatSession = inputs.hasActiveChatSession,
        chatBackgroundImporting = inputs.chatBackgroundImporting,
        modifier = modifier,
        actions = actions
    )
}
