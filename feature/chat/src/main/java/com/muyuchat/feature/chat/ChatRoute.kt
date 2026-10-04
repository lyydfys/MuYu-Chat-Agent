package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * no life cycle. It is handed to [ChatViewModel], which holds it for the page, and the app remains
 * its only writer — that is what keeps the single-writer rule intact.
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
 * The chat page's mounting point, and nothing else.
 *
 * It used to forward its three arguments to `ChatScreen` unchanged, which is why the comment here
 * promised that "a future chat-owned view model can be introduced behind this boundary". That view
 * model now exists: [ChatViewModel] owns the page's state and inputs, and exposes the chat page's
 * ninety-six named user intents, which is what `ChatScreen` actually calls. This function is the
 * assembly step between the two — it creates the holder, publishes the app's projection into it,
 * and mounts the screen against it.
 *
 * No decision lives here: no business rule, no defaulting, no re-derivation, no new state. The
 * thirteen inputs are forwarded from the holder rather than from the parameters so that the page
 * has exactly one source for what it renders.
 *
 * ## Why the state is read as a value instead of collected
 *
 * `update` publishes this frame's projection before the value is read below, so reading
 * `state.value` yields exactly the instance this composition was given. Collecting it with
 * `collectAsState()` would deliver an emission one frame later, which would render a stale frame on
 * every keystroke. Preserving the existing frame timing is a hard requirement of this change, so the
 * holder's value is read synchronously; [ChatViewModel.state] remains the reactive surface for any
 * other observer.
 */
@Composable
fun ChatRoute(
    state: ChatUiState,
    inputs: ChatRouteInputs,
    actions: ChatScreenActions,
    modifier: Modifier = Modifier
) {
    val viewModel = remember { ChatViewModel(state, inputs, actions) }
    viewModel.update(state = state, inputs = inputs, capabilities = actions)

    val pageState = viewModel.state.value
    val pageInputs = viewModel.inputs

    ChatScreen(
        state = pageState,
        offlineTranslationStatus = pageInputs.offlineTranslationStatus,
        offlineTranslationInstalled = pageInputs.offlineTranslationInstalled,
        offlineTranslationInstalling = pageInputs.offlineTranslationInstalling,
        offlineTranslationDownloading = pageInputs.offlineTranslationDownloading,
        offlineTranslationDownloadProgress = pageInputs.offlineTranslationDownloadProgress,
        assistantMemories = pageInputs.assistantMemories,
        appMenuOpen = pageInputs.appMenuOpen,
        chatBackground = pageInputs.chatBackground,
        globalChatBackground = pageInputs.globalChatBackground,
        assistantChatBackground = pageInputs.assistantChatBackground,
        sessionChatBackground = pageInputs.sessionChatBackground,
        hasActiveChatSession = pageInputs.hasActiveChatSession,
        chatBackgroundImporting = pageInputs.chatBackgroundImporting,
        modifier = modifier,
        actions = viewModel.screenActions
    )
}
