package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import org.json.JSONObject

/**
 * The owner seam for the chat image retry/cleanup bookkeeping: deleting a cloud model,
 * confirming a translation draft, recovering interrupted commits and building a retry snapshot.
 *
 * Ten members. None of them is a lock, a job or a scope; the pending-translation slot is reached
 * through a pair of accessors because it is a `var` the ViewModel still owns.
 */
internal data class PendingImageTranslation(
        val id: String,
        val visibleUserText: String,
        val handoff: ChatImagePromptBridge.ChatImagePromptBridgeHandoff,
        val contextTraceJson: String?,
        val stillOwned: () -> Boolean
    )

/**
 * The narrow port for commit recovery: the only two objects [recoverPendingImageCommits] reads.
 *
 * Extracted so the recovery path does not need the full retry port -- whose `sendChatImageRequest`
 * leads back into the chat request flow and the image owner, closing a construction circle
 * (retry port -> owner -> startup reconciliation -> recovery -> retry port). With this seam the
 * container can build a recovery-only adapter and the circle is broken.
 */
internal interface ChatImageCommitRecoveryPort {

    val chatImageCommitJournal: ChatImageCommitJournal

    val chatSessionStore: ChatSessionStore
}

internal interface ChatImageRetryPort : ChatImageCommitRecoveryPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val cloudApiStore: CloudApiStore

    val localImageModelStore: LocalImageModelStore

    override val chatImageCommitJournal: ChatImageCommitJournal

    override val chatSessionStore: ChatSessionStore

    fun showStatus(message: String?)

    fun invalidatePendingChatImagePreparation()

    fun sendChatImageRequest(
visibleUserText: String,
prompt: String,
optionsOverride: LocalImageGenerationOptions? = null,
promptEnvelope: PromptEnvelope? = null,
contextTraceJson: String? = null
)

    fun pendingImageTranslation(): PendingImageTranslation?

    fun setPendingImageTranslation(value: PendingImageTranslation?)
}

    internal suspend fun recoverPendingImageCommits(
    port: ChatImageCommitRecoveryPort,
        sessions: List<ChatSessionRecord>,
        images: List<ImageAssetRecord>
    ): Pair<List<ChatSessionRecord>, List<ImageAssetRecord>> {
        val journal = runCatching { port.chatImageCommitJournal }.getOrNull() ?: return sessions to images
        val entries = runCatching { journal.enumerate().validEntries }.getOrNull().orEmpty()
        if (entries.isEmpty()) return sessions to images
        var recoveredSessions = sessions
        val recoveredImages = images.toMutableList()
        for (entry in entries) {
            val asset = runCatching { entry.imageAssetRecord() }.getOrNull() ?: continue
            val session = recoveredSessions.firstOrNull { it.id == entry.chatSessionId }
            val request = session?.messages
                ?.firstOrNull { it.generatedImageRequest?.id == entry.requestId }
                ?.generatedImageRequest
            val jobMatches = request != null && (
                request.currentJobId == entry.jobId ||
                    request.currentJobId?.let { entry.jobId.startsWith("$it.") } == true
                )
            val databaseAlreadyContains = recoveredImages.any { it.id == asset.id }
            val safelyAssociate = session != null && request != null && jobMatches
            if (safelyAssociate && session != null && request != null) {
                val imageIds = (request.imageAssetIds + asset.id).distinct()
                val completedRequest = request.copy(
                    status = ChatGeneratedImageStatus.DONE,
                    message = if (imageIds.size == 1) "已生成 1 张图片。" else "已生成 ${imageIds.size} 张图片。",
                    imageAssetIds = imageIds
                )
                val updatedMessages = session.messages.map { message ->
                    if (message.generatedImageRequest?.id == entry.requestId) {
                        message.copy(
                            content = completedRequest.assistantTranscriptText(),
                            generatedImageRequest = completedRequest
                        )
                    } else message
                }
                val updatedSessions = recoveredSessions.map { current ->
                    if (current.id == session.id) {
                        current.copy(messages = updatedMessages, updatedAt = System.currentTimeMillis())
                    } else current
                }.sortedForHistory()
                val committed = runCatching {
                    port.chatSessionStore.saveGeneratedImageResult(updatedSessions, listOf(asset))
                }.getOrDefault(false)
                if (committed) {
                    recoveredSessions = updatedSessions
                    if (!databaseAlreadyContains) recoveredImages += asset
                    journal.removeAfterDurableCommit(entry.jobId, entry.requestId, true)
                    continue
                }
            }

            // A stale or deleted chat must not strand a valid image file. Preserve it in the
            // gallery; only associate it with a conversation when the request/job identity matches.
            val galleryAsset = if (safelyAssociate) asset else asset.copy(chatSessionId = null)
            val durable = if (databaseAlreadyContains) true else runCatching {
                port.chatSessionStore.upsertImages(listOf(galleryAsset))
                true
            }.getOrDefault(false)
            if (durable) {
                if (!databaseAlreadyContains) recoveredImages += galleryAsset
                journal.removeAfterDurableCommit(entry.jobId, entry.requestId, true)
            }
        }
        return recoveredSessions to recoveredImages.sortedImagesForLibrary()
    }

    internal fun deleteCloudModelRecord(
    port: ChatImageRetryPort,modelId: String) {
        val current = port.uiState()
        val removed = current.cloudModels.firstOrNull { it.id == modelId }
        if (removed == null) {
            port.showStatus("未找到要删除的云端模型")
            return
        }
        val remaining = current.cloudModels.filterNot { it.id == modelId }
        val deletedSelectedChat = removed.kind == CloudModelKind.CHAT && current.selectedCloudChatModelId == modelId
        val deletedSelectedImage = removed.kind == CloudModelKind.IMAGE && current.selectedCloudImageModelId == modelId
        val nextChat = if (deletedSelectedChat) {
            remaining.firstOrNull { it.kind == CloudModelKind.CHAT }
        } else {
            remaining.firstOrNull { it.id == current.selectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
        }
        val nextImage = if (deletedSelectedImage) {
            remaining.firstOrNull { it.kind == CloudModelKind.IMAGE }
        } else {
            remaining.firstOrNull { it.id == current.selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
        }
        val nextChatBackend = if (deletedSelectedChat && nextChat == null) ChatBackend.LOCAL else current.selectedChatBackend
        val nextImageBackend = if (deletedSelectedImage && nextImage == null) ImageBackend.LOCAL else current.selectedImageBackend
        val nextConfig = when {
            deletedSelectedChat && nextChat != null -> nextChat.toChatConfig().normalized()
            deletedSelectedImage && nextImage != null -> nextImage.toImageConfig().normalized()
            current.editingCloudModelId == modelId -> CloudApiConfig()
            else -> current.cloudApiConfig
        }

        port.cloudApiStore.saveModels(remaining)
        port.cloudApiStore.saveSelectedCloudChatModelId(nextChat?.id)
        port.cloudApiStore.saveSelectedCloudImageModelId(nextImage?.id)
        if (deletedSelectedChat) port.cloudApiStore.saveSelectedBackend(nextChatBackend)
        if (deletedSelectedImage) port.localImageModelStore.saveSelectedBackend(nextImageBackend)
        if (deletedSelectedChat || deletedSelectedImage || current.editingCloudModelId == modelId) {
            port.cloudApiStore.save(nextConfig)
        }

        port.updateUiState { state ->
            state.copy(
                cloudModels = remaining,
                selectedCloudChatModelId = nextChat?.id,
                selectedCloudImageModelId = nextImage?.id,
                selectedChatBackend = nextChatBackend,
                selectedImageBackend = nextImageBackend,
                cloudApiConfig = nextConfig,
                editingCloudModelId = state.editingCloudModelId.takeUnless { it == modelId },
                statusMessage = "已删除云端模型：${removed.displayName}"
            )
        }
    }

    internal fun applyChatImageTranslationDraft(
    port: ChatImageRetryPort,id: String, positive: String, negative: String?) {
        val pending = port.pendingImageTranslation()?.takeIf { it.id == id } ?: return
        if (!pending.stillOwned()) {
            port.invalidatePendingChatImagePreparation()
            port.showStatus("会话或模型已变化，请重新整理图片描述。")
            return
        }
        val currentOwner = port.uiState()
        if (currentOwner.input != pending.visibleUserText) {
            port.invalidatePendingChatImagePreparation()
            port.showStatus("原始描述已修改，请重新整理图片提示词。")
            return
        }
        if (currentOwner.isGenerating) return
        val prepared = ChatImagePromptBridge.Result.Prepared(
            originalPrompt = pending.handoff.envelope.original,
            effectivePrompt = positive,
            effectiveNegativePrompt = negative,
            translated = true
        )
        val handoff = runCatching {
            require(positive.isNotBlank() && positive.length <= ChatImagePromptBridge.MAX_OUTPUT_CHARS)
            require(negative.orEmpty().length <= ChatImagePromptBridge.MAX_OUTPUT_CHARS)
            require(!positive.containsHanScript() && !negative.orEmpty().containsHanScript()) {
                "当前图片编码器需要英文描述，请编辑译文后重试。"
            }
            require(ChatImagePromptBridge.protectedPromptSpansPreserved(
                pending.handoff.envelope.protectedSpans,
                positive,
                negative
            )) { "译文草稿移动、重复或丢失了受保护的权重或 LoRA 标签。" }
            pending.handoff.copy(
                prompt = prepared.effectivePrompt,
                options = pending.handoff.options.copy(negativePrompt = negative?.takeIf(String::isNotBlank)),
                envelope = pending.handoff.envelope.copy(positive = positive, negative = negative)
            )
        }.getOrElse { error ->
            port.showStatus(error.message ?: "译文草稿无效。")
            return
        }
        port.setPendingImageTranslation(null)
        port.updateUiState { it.copy(pendingChatImageTranslationDraft = null) }
        port.sendChatImageRequest(
            visibleUserText = pending.visibleUserText,
            prompt = handoff.prompt,
            optionsOverride = handoff.options,
            promptEnvelope = handoff.envelope,
            contextTraceJson = pending.contextTraceJson
        )
    }

    internal fun chatImageRetrySnapshot(
    port: ChatImageRetryPort,
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? {
        val state = port.uiState()
        val options = request.generationOptionsJson?.let { encoded ->
            runCatching { LocalImageGenerationOptions.fromHistoryJson(JSONObject(encoded)) }.getOrNull()
        } ?: return null
        val backend = request.backendId?.let { encoded ->
            runCatching { ImageBackend.valueOf(encoded) }.getOrNull()
        } ?: return null
        val modelId = request.modelId ?: return null
        return when (backend) {
            ImageBackend.LOCAL -> {
                val model = state.localImageModels.firstOrNull {
                    it.id == modelId && it.configured
                } ?: return null
                if (request.modelFingerprint != null &&
                    model.chatImageRetryFingerprint() != request.modelFingerprint
                ) return null
                ImageGenerationJobSpec(
                    prompt = request.prompt,
                    backend = backend,
                    localModelSnapshot = model,
                    modelId = model.id,
                    modelName = request.modelName?.takeIf(String::isNotBlank) ?: model.displayName,
                    inputDraft = LocalImageInputDraft(),
                    options = options.copy(
                        inputImage = null,
                        maskImage = null,
                        controlImage = null,
                        taskMode = LocalImageTaskMode.TEXT_TO_IMAGE
                    ),
                    chatSessionId = chatSessionId,
                    chatMessageId = request.id
                )
            }
            ImageBackend.CLOUD -> {
                val config = state.cloudModels.firstOrNull {
                    it.id == modelId && it.kind == CloudModelKind.IMAGE && it.configured
                }?.toImageConfig()?.normalized() ?: return null
                if (request.modelFingerprint != null &&
                    config.chatImageRetryFingerprint() != request.modelFingerprint
                ) return null
                ImageGenerationJobSpec(
                    prompt = request.prompt,
                    backend = backend,
                    cloudConfigSnapshot = config,
                    modelId = modelId,
                    modelName = request.modelName?.takeIf(String::isNotBlank)
                        ?: config.displayName.ifBlank { config.imageModel },
                    inputDraft = LocalImageInputDraft(),
                    options = options.copy(
                        inputImage = null,
                        maskImage = null,
                        controlImage = null,
                        taskMode = LocalImageTaskMode.TEXT_TO_IMAGE
                    ),
                    chatSessionId = chatSessionId,
                    chatMessageId = request.id
                )
            }
        }
    }
