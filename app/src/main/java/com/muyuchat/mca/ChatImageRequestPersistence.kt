package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import kotlinx.coroutines.flow.update

import android.app.Application
import com.muyuchat.core.engine.ChatMessage

/**
 * The owner seam for persisting a chat image request: the two status updates, the commit snapshot and
 * the final write.
 *
 * Three members. These functions hold no lock, start no coroutine and own no job -- they are the
 * bookkeeping half of the image-request flow, which is why they could move whole. The generation
 * lifecycle itself (`reserveUiGenerationStart` / `cancelGenerationJob`) deliberately stayed behind: it
 * owns job state, and two members that only write records must not be mixed with it.
 */
internal interface ChatImageRequestPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>? = null
    )
}

internal fun ImageGenerationStatusRecord.toChatGeneratedImageStatus(): ChatGeneratedImageStatus =
    when (this) {
        ImageGenerationStatusRecord.QUEUED -> ChatGeneratedImageStatus.QUEUED
        ImageGenerationStatusRecord.GENERATING,
        ImageGenerationStatusRecord.CANCEL_REQUESTED -> ChatGeneratedImageStatus.GENERATING
        ImageGenerationStatusRecord.DONE -> ChatGeneratedImageStatus.DONE
        ImageGenerationStatusRecord.CANCELLED -> ChatGeneratedImageStatus.CANCELLED
        ImageGenerationStatusRecord.FAILED -> ChatGeneratedImageStatus.FAILED
    }

internal data class ChatImageCommitSnapshot(
    val sessions: List<ChatSessionRecord>,
    val activeMessages: List<ChatMessage>?
)

internal fun applyChatImageRequestFromJob(
    port: ChatImageRequestPort,
        chatSessionId: String,
        requestId: String,
        jobId: String
    ) {
        val job = port.uiState().imageJobs.firstOrNull { it.id == jobId } ?: return
        val request = port.uiState().chatSessions.firstOrNull { it.id == chatSessionId }
            ?.messages?.firstOrNull { it.generatedImageRequest?.id == requestId }
            ?.generatedImageRequest
            ?: port.uiState().messages.firstOrNull { it.generatedImageRequest?.id == requestId }
                ?.generatedImageRequest
            ?: return
        val spec = job.spec ?: return
        val fingerprint = when (spec.backend) {
            ImageBackend.LOCAL -> spec.localModelSnapshot?.chatImageRetryFingerprint()
            ImageBackend.CLOUD -> spec.cloudConfigSnapshot?.chatImageRetryFingerprint()
        }
        applyChatGeneratedImageRequest(port, 
            chatSessionId,
            requestId,
            request.copy(
                currentJobId = jobId,
                backendId = spec.backend.name,
                modelId = spec.modelId,
                modelName = spec.modelName,
                modelFingerprint = fingerprint ?: request.modelFingerprint,
                generationOptionsJson = spec.options.toJson().toString()
            )
        )
    }

internal fun applyChatGeneratedImageRequest(
    port: ChatImageRequestPort,
        chatSessionId: String,
        requestId: String,
        request: ChatGeneratedImageRequest
    ) {
        var persistedSessions: List<ChatSessionRecord>? = null
        port.updateUiState { state ->
            val owner = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return@updateUiState state
            if (owner.mixedAssistantHistory || owner.assistantId == null ||
                owner.assistantSnapshot?.assistantId != owner.assistantId) return@updateUiState state
            val sourceMessages = if (state.activeChatSessionId == chatSessionId) {
                state.messages
            } else {
                owner.messages
            }
            var found = false
            val messages = sourceMessages.map { message ->
                if (message.generatedImageRequest?.id != requestId) {
                    message
                } else {
                    found = true
                    message.copy(
                        content = request.assistantTranscriptText(),
                        generatedImageRequest = request
                    )
                }
            }
            if (!found) return@updateUiState state
            val updatedOwner = owner.copy(messages = messages, updatedAt = System.currentTimeMillis())
            val updatedSessions = state.chatSessions
                .map { session -> if (session.id == chatSessionId) updatedOwner else session }
                .sortedForHistory()
            persistedSessions = updatedSessions
            state.copy(
                messages = if (state.activeChatSessionId == chatSessionId) messages else state.messages,
                chatSessions = updatedSessions
            )
        }
        persistedSessions?.let { sessions -> port.persistChatSessions(sessions) }
    }

internal fun chatImageCommitSnapshot(
    port: ChatImageRequestPort,
        job: ImageGenerationJobRecord,
        images: List<ImageAssetRecord>
    ): ChatImageCommitSnapshot? {
        val spec = job.spec ?: return null
        val chatSessionId = spec.chatSessionId ?: return null
        val requestId = spec.chatMessageId ?: return null
        if (images.isEmpty()) return null
        val state = port.uiState()
        val owner = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return null
        if (owner.mixedAssistantHistory || owner.assistantId == null ||
            owner.assistantSnapshot?.assistantId != owner.assistantId) return null
        val sourceMessages = if (state.activeChatSessionId == chatSessionId) state.messages else owner.messages
        val currentRequest = sourceMessages.firstOrNull {
            it.generatedImageRequest?.id == requestId
        }?.generatedImageRequest ?: return null
        if (!currentRequest.currentJobId.isNullOrBlank() && currentRequest.currentJobId != job.id) {
            return null
        }
        val imageIds = images.map(ImageAssetRecord::id)
        val completedRequest = currentRequest.copy(
            status = ChatGeneratedImageStatus.DONE,
            message = "已生成 ${imageIds.size} 张图片。",
            imageAssetIds = imageIds
        )
        var found = false
        val completedMessages = sourceMessages.map { message ->
            if (message.generatedImageRequest?.id != requestId) {
                message
            } else {
                found = true
                message.copy(
                    content = completedRequest.assistantTranscriptText(),
                    generatedImageRequest = completedRequest
                )
            }
        }
        if (!found) return null
        val updatedOwner = owner.copy(messages = completedMessages, updatedAt = System.currentTimeMillis())
        val updatedSessions = state.chatSessions.map { session ->
            if (session.id == chatSessionId) updatedOwner else session
        }.sortedForHistory()
        return ChatImageCommitSnapshot(
            sessions = updatedSessions,
            activeMessages = completedMessages.takeIf { state.activeChatSessionId == chatSessionId }
        )
    }

internal fun persistFinalChatImageRequest(
    port: ChatImageRequestPort,jobId: String) {
        val state = port.uiState()
        val job = state.imageJobs.firstOrNull { it.id == jobId } ?: return
        val spec = job.spec ?: return
        val chatSessionId = spec.chatSessionId ?: return
        val requestId = spec.chatMessageId ?: return
        val existing = state.chatSessions.firstOrNull { it.id == chatSessionId }
            ?.messages
            ?.firstOrNull { it.generatedImageRequest?.id == requestId }
            ?.generatedImageRequest
            ?: state.messages.firstOrNull { it.generatedImageRequest?.id == requestId }
                ?.generatedImageRequest
            ?: return
        if (!existing.currentJobId.isNullOrBlank() && existing.currentJobId != jobId) return
        val terminalStatus = when {
            !job.status.terminal -> ChatGeneratedImageStatus.INTERRUPTED
            else -> job.status.toChatGeneratedImageStatus()
        }
        val request = existing.copy(
            status = terminalStatus,
            message = when (terminalStatus) {
                ChatGeneratedImageStatus.DONE -> "已生成 ${job.imageAssetIds.size.coerceAtLeast(1)} 张图片。"
                ChatGeneratedImageStatus.CANCELLED -> job.message.ifBlank { "已取消图片生成。" }
                ChatGeneratedImageStatus.FAILED -> job.message.ifBlank { "图片生成失败，请检查模型后重试。" }
                ChatGeneratedImageStatus.INTERRUPTED -> "图片任务未能正常结束，可重试。"
                ChatGeneratedImageStatus.QUEUED,
                ChatGeneratedImageStatus.AWAITING_APPROVAL,
                ChatGeneratedImageStatus.GENERATING -> job.message
            },
            imageAssetIds = if (terminalStatus == ChatGeneratedImageStatus.DONE) {
                job.imageAssetIds.ifEmpty { job.imageAssetId?.let(::listOf).orEmpty() }
            } else {
                emptyList()
            }
        )
        applyChatGeneratedImageRequest(port, chatSessionId, requestId, request)
    }
