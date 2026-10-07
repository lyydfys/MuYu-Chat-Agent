package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.feature.chat.ChatImageActionKind
import com.muyuchat.mca.composition.McaAppContainer
import kotlinx.coroutines.CoroutineScope

/**
 * R4-S18: 离线提示词翻译、生图准备与助手生图工具权限桥接整族下沉。
 */
internal class OfflinePromptTranslationAndChatImageCoordinator(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val scope: CoroutineScope,
    private val container: McaAppContainer,
    private val installer: OfflinePromptTranslationBundleInstaller,
    private val downloader: OfflinePromptTranslationBundleDownloader,
    private val offlinePromptTranslationServiceProvider: () -> OfflinePromptTranslationService,
    private val chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore,
    private val prepareChatInput: (MainUiState) -> PreparedChatInput,
    private val showStatus: (String) -> Unit,
    private val rejectWhileConversationMutationInProgress: () -> Boolean,
    private val hasUnresolvedAssistantImageToolTurn: (String?) -> Boolean,
    private val sendChatImageRequestWithPromptBridge: (visibleUserText: String, prompt: String, requestedOutputCount: Int?, allowCloudTranslationFallback: Boolean) -> Unit,
    private val sendPreparedMessage: (currentState: MainUiState, preparedInput: PreparedChatInput) -> Unit
) {
    private val imagePromptTranslationPreviewPort: ImagePromptTranslationPreviewPort =
        container.createImagePromptTranslationPreviewPort(
            store,
            offlinePromptTranslationServiceProvider
        )

    suspend fun translateImagePromptPreview(prompt: String): String =
        com.muyuchat.mca.translateImagePromptPreview(port = imagePromptTranslationPreviewPort, prompt = prompt)

    suspend fun translateImageNegativePromptPreview(prompt: String): String =
        com.muyuchat.mca.translateImageNegativePromptPreview(port = imagePromptTranslationPreviewPort, prompt = prompt)

    fun importOfflinePromptTranslationBundle(uri: Uri) {
        importOfflinePromptTranslationBundleFor(
            store = store,
            admission = admission,
            scope = scope,
            installer = installer,
            uri = uri
        )
    }

    fun cancelOfflinePromptTranslationInstall() {
        cancelOfflinePromptTranslationInstallFor(admission)
    }

    fun downloadOfflinePromptTranslationBundle() {
        downloadOfflinePromptTranslationBundleFor(
            store = store,
            admission = admission,
            scope = scope,
            downloader = downloader
        )
    }

    fun cancelOfflinePromptTranslationDownload() {
        cancelOfflinePromptTranslationDownloadFor(admission)
    }

    suspend fun verifiedOfflineTranslationBundle(): VerifiedOfflinePromptTranslationBundle? =
        verifiedOfflineTranslationBundleFor(
            store = store,
            admission = admission,
            downloader = downloader
        )

    suspend fun cacheVerifiedOfflineTranslationBundle(bundle: VerifiedOfflinePromptTranslationBundle) {
        cacheVerifiedOfflineTranslationBundleFor(admission, bundle)
    }

    fun offlineTranslationBundleReadyStatus(bundle: VerifiedOfflinePromptTranslationBundle): String =
        com.muyuchat.mca.offlineTranslationBundleReadyStatus(bundle)

    fun invalidatePendingChatImagePreparation() {
        invalidatePendingChatImagePreparationInto(store, admission)
    }

    fun cancelChatImageTranslationDraft(id: String) {
        if (admission.pendingImageTranslation?.id != id) return
        invalidatePendingChatImagePreparation()
        showStatus("译文草稿已取消，原文已保留。")
    }

    fun resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String) {
        val pending = admission.pendingChatImageAction?.takeIf { it.id == id } ?: return
        if (!pending.stillOwned()) {
            invalidatePendingChatImagePreparation()
            showStatus("会话、模型或原始草稿已变化，请重新发送。")
            return
        }
        val text = editedText.takeUtf8Prefix(MainViewModel.MAX_DIRECT_COMPOSER_UTF8_BYTES).text
        invalidatePendingChatImagePreparation()
        store.update { it.copy(input = text) }
        when (pending.kind) {
            ChatImageActionKind.AMBIGUOUS -> {
                if (primary) {
                    val prompt = parseExplicitChatImageIntent(text)?.sourceText ?: text
                    sendChatImageRequestWithPromptBridge(
                        text,
                        prompt,
                        classifyChatImageIntent(text).requestedOutputCount ?: pending.requestedOutputCount,
                        false
                    )
                } else {
                    val current = store.current
                    sendPreparedMessage(current, prepareChatInput(current))
                }
            }
            ChatImageActionKind.CLOUD_TRANSLATION -> {
                if (primary) {
                    sendChatImageRequestWithPromptBridge(
                        text,
                        parseExplicitChatImageIntent(text)?.sourceText ?: text,
                        classifyChatImageIntent(text).requestedOutputCount ?: pending.requestedOutputCount,
                        true
                    )
                } else {
                    showStatus("已取消云端整理，原始描述仍在输入框。")
                }
            }
        }
    }

    fun sendChatImagePrompt() {
        val state = store.current
        invalidatePendingChatImagePreparation()
        if (rejectWhileConversationMutationInProgress()) return
        if (hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先处理上一张角色请求的图片，再开始新的聊天生图。")
            return
        }
        if (state.isGenerating) {
            store.update {
                it.copy(statusMessage = "当前对话仍在生成，结束或停止本轮后再发送生图请求。")
            }
            return
        }
        val preparedInput = prepareChatInput(state)
        if (preparedInput.imageAttachments.isNotEmpty() ||
            preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
        ) {
            store.update {
                it.copy(statusMessage = "聊天内生图暂不使用上传附件作为参考图。请移除附件后重试，或在图片页使用图生图。")
            }
            return
        }
        val prompt = preparedInput.text.trim()
        if (prompt.isBlank()) {
            showStatus("先写下图片描述，再点击生成图片。")
            return
        }
        val imagePrompt = parseExplicitChatImageIntent(prompt)?.sourceText ?: prompt
        val requestedOutputCount = classifyChatImageIntent(
            prompt,
            ChatImageIntentContext(imageSkillSelected = true)
        ).requestedOutputCount
        sendChatImageRequestWithPromptBridge(
            preparedInput.text,
            imagePrompt,
            requestedOutputCount,
            false
        )
    }

    fun chatImageToolPermissionUiState(): ChatImageToolPermissionUiState {
        return chatImageToolPermissionUiStateFor(store.current, chatImageToolAuthorizationStore)
    }

    fun setAssistantImageToolAutoApproval(enabled: Boolean) {
        setAssistantImageToolAutoApprovalFor(
            store = store,
            scope = scope,
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
            chatImageToolPermissionUiState = ::chatImageToolPermissionUiState,
            showStatus = showStatus,
            enabled = enabled
        )
    }
}

internal fun chatImageToolPermissionUiStateFor(
    state: MainUiState,
    chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore
): ChatImageToolPermissionUiState {
    val cloudModelId = state.selectedCloudChatModelId
    val config = state.selectedChatCloudConfig()?.normalized()
    val imageModelBinding = state.chatImageModelBinding()
    val chatModelIdentity = chatImageToolChatModelIdentity(cloudModelId, config)
    val imageModelIdentity = imageModelBinding?.authorizationIdentity()
    val unavailableReason = when {
        state.selectedChatBackend != ChatBackend.CLOUD ->
            "角色自主生图目前仅支持 OpenAI Responses 云端聊天；本地模型可用输入框的聊天内生图。"
        cloudModelId.isNullOrBlank() || config == null || !config.configured ->
            "请先配置并选择一个云端聊天模型。"
        config.apiFormat != CloudApiFormat.OPENAI_RESPONSES ->
            "当前聊天协议不支持角色生图工具调用，请切换到 OpenAI Responses。"
        !config.supportsTools ->
            "当前 Responses 模型尚未启用工具调用，请到模型管理 > 云端编辑该模型并开启“支持工具调用”。"
        imageModelBinding == null ->
            "请先选择一个可用的本地或云端生图模型。"
        else -> ""
    }
    val sessionId = state.activeChatSessionId
    val authorizationMode = chatImageToolAuthorizationStore.getMode(
        sessionId,
        chatModelIdentity,
        imageModelIdentity
    )
    return ChatImageToolPermissionUiState(
        available = unavailableReason.isBlank(),
        unavailableReason = unavailableReason,
        autoApproval = unavailableReason.isBlank() &&
            authorizationMode == ChatImageToolAuthorizationMode.AUTO_APPROVE,
        canChange = unavailableReason.isBlank() && !sessionId.isNullOrBlank()
    )
}
