package com.muyuchat.mca

import android.net.Uri

fun MainViewModel.confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) =
    chatImageWorkflowCoordinator.confirmChatImageTranslationDraft(id, positive, negative)

fun MainViewModel.resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String) =
    offlinePromptTranslationAndChatImageCoordinator.resolveChatImageActionDraft(id, primary, editedText)

fun MainViewModel.importOfflinePromptTranslationBundle(uri: Uri) =
    offlinePromptTranslationAndChatImageCoordinator.importOfflinePromptTranslationBundle(uri)

fun MainViewModel.cancelOfflinePromptTranslationInstall() =
    offlinePromptTranslationAndChatImageCoordinator.cancelOfflinePromptTranslationInstall()

fun MainViewModel.downloadOfflinePromptTranslationBundle() =
    offlinePromptTranslationAndChatImageCoordinator.downloadOfflinePromptTranslationBundle()

fun MainViewModel.cancelOfflinePromptTranslationDownload() =
    offlinePromptTranslationAndChatImageCoordinator.cancelOfflinePromptTranslationDownload()

fun MainViewModel.cancelChatImageTranslationDraft(id: String) =
    offlinePromptTranslationAndChatImageCoordinator.cancelChatImageTranslationDraft(id)

fun MainViewModel.sendChatImagePrompt() =
    offlinePromptTranslationAndChatImageCoordinator.sendChatImagePrompt()

fun MainViewModel.setAssistantImageToolAutoApproval(enabled: Boolean) =
    offlinePromptTranslationAndChatImageCoordinator.setAssistantImageToolAutoApproval(enabled)

fun MainViewModel.retryChatImageGeneration(requestId: String) =
    assistantImageContinuationCoordinator.retryChatImageGeneration(requestId)

fun MainViewModel.approveChatImageRequest(requestId: String) =
    assistantImageContinuationCoordinator.approveChatImageRequest(requestId)

fun MainViewModel.rejectChatImageRequest(requestId: String) =
    assistantImageContinuationCoordinator.rejectChatImageRequest(requestId)

fun MainViewModel.continueAssistantImageTurn(requestId: String) =
    assistantImageContinuationCoordinator.continueAssistantImageTurn(requestId)

fun MainViewModel.cancelChatImageGeneration(requestId: String) =
    assistantImageContinuationCoordinator.cancelChatImageGeneration(requestId)

