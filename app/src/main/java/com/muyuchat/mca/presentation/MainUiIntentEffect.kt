package com.muyuchat.mca.presentation

import com.muyuchat.mca.AppTab

/**
 * Unidirectional UI Intents representing user actions dispatched from UI Screens to ViewModel.
 */
sealed interface MainUiIntent {
    data class SelectTab(val tab: AppTab) : MainUiIntent
    data class SendChatMessage(val prompt: String, val sessionId: String? = null) : MainUiIntent
    data class CancelGeneration(val requestId: String? = null, val reason: String = "User cancelled") : MainUiIntent
    data class StartImageGeneration(val prompt: String, val width: Int = 512, val height: Int = 512) : MainUiIntent
    data class SelectModel(val modelId: String) : MainUiIntent
    data object ClearCurrentChat : MainUiIntent
    data object CheckAppUpdate : MainUiIntent
    data class ShowStatusMessage(val message: String) : MainUiIntent
}

/**
 * One-time UI Effects emitted from ViewModel to UI for ephemeral events (toasts, dialogs, navigation).
 */
sealed interface MainUiEffect {
    data class ShowToast(val message: String) : MainUiEffect
    data class ShowSnackbar(val message: String, val actionLabel: String? = null) : MainUiEffect
    data class NavigateToTab(val tab: AppTab) : MainUiEffect
    data class ExportComplete(val filePath: String, val mimeType: String = "text/plain") : MainUiEffect
    data class ErrorNotification(val errorCode: String, val message: String) : MainUiEffect
}
