package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.util.UUID

/**
 * The chat-image send path: one turn of "the user asked for an image" from the composer to the
 * admitted image job.
 *
 * ## Where this comes from
 *
 * This is `MainViewModel.sendChatImageRequest` moved out whole. The body below is the original body
 * verbatim; the only edits are the sixteen ViewModel members it reached for, each replaced by the
 * matching [SendChatImageRequestPort] call (`_uiState`, one member, becomes the read/transaction
 * pair above). No condition, no ordering, no wording and no exception
 * type was touched -- the four early returns and their exact status strings, the single
 * `_uiState.update` transaction that appends the user turn and the assistant placeholder together,
 * the `upsertSession`/`persistChatSessions` pair and the admit/reject fan-out all stay where they
 * were and run in the same order.
 *
 * ## Why it is synchronous
 *
 * The original was a plain `fun` with no `withContext` and no `.launch` of its own; the caller that
 * runs it on `Dispatchers.IO` keeps doing that. Making this `suspend` would move a dispatcher choice
 * in here and re-time the publish, which is the change this refactor must not make.
 *
 * ## What the port is for
 *
 * The member is an orchestrator, so the port is exactly the set of collaborators and state
 * derivations it actually used -- one member each, nothing else:
 *
 * * `_uiState` becomes [SendChatImageRequestPort.uiState] + [SendChatImageRequestPort.updateUiState]
 *   (two members, because reading the state and applying the one atomic transaction are different
 *   operations and `update` must keep `MutableStateFlow.update` semantics);
 * * the six decisions the member asked the ViewModel for -- writability, the assistant snapshot,
 *   the selected assistant, the selected local image model, the selected cloud image config, the
 *   current chat model id -- are passed the state they are asked about, so the port stays a
 *   function of its argument instead of a second copy of shell state;
 * * the nine collaborators it drove -- `showStatus`, `rejectWhileConversationMutationInProgress`,
 *   `chatImageSubmissionBlockMessage`, `chatImageOptionsForCurrentSelection`, `List.upsertSession`,
 *   `persistChatSessions`, `enqueueImageGeneration`, `updateChatGeneratedImageRequest`,
 *   `updateChatImageRequestFromJob` -- keep their original signatures, including the optional
 *   parameters the original call site relied on.
 *
 * ## Failure semantics, preserved exactly
 *
 * The original let three different things happen, and so does this:
 *
 * * a failure from [SendChatImageRequestPort.chatImageOptionsForCurrentSelection] is caught by the
 *   same `runCatching`/`getOrElse`, which restores the composer draft and publishes
 *   `error.message.orEmpty()` -- including the empty string for a null message;
 * * a failure from [SendChatImageRequestPort.enqueueImageGeneration] is *not* caught here, exactly
 *   as before: it propagates to the caller, which owns the try boundary;
 * * a rejected admission (`false`) is not an exception: the request is rewritten to `FAILED` with
 *   whatever status the port published while admitting, the fallback wording
 *   `"无法开始图片生成，请检查已选模型。"` when it published none, and the composer draft is restored
 *   only when the session is still the active one and the draft is still blank.
 */
internal interface SendChatImageRequestPort {

    /** The current shell state, always read fresh at the moment of the call. */
    fun uiState(): MainUiState

    /**
     * Applies one atomic state transaction.
     *
     * Must keep `MutableStateFlow.update` semantics: the transform may run more than once under
     * contention, and the returned state is what gets published.
     */
    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The single-writer funnel for the transient status line. */
    fun showStatus(message: String)

    /** True while a previous conversation mutation is still committing. */
    fun rejectWhileConversationMutationInProgress(): Boolean

    /** Why this send must not start, or null when it may. */
    fun chatImageSubmissionBlockMessage(state: MainUiState): String?

    /** Whether the active session belongs to the selected assistant and is not read-only. */
    fun canWriteToActiveAssistantSession(state: MainUiState): Boolean

    /** The active session's frozen assistant snapshot, when it has one. */
    fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot?

    /** The selected assistant, falling back to the first one. */
    fun selectedAssistant(state: MainUiState): AssistantRecord?

    /** The selected local image model, when it exists and is configured. */
    fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord?

    /** The selected cloud image model config, when it exists and is an image model. */
    fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig?

    /** The chat model id the turn is attributed to. */
    fun currentChatModelId(state: MainUiState): String?

    /** The selected image model's default conditioning for a chat-triggered image. */
    fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions

    /** Inserts or replaces the session and returns the resulting list. */
    fun upsertSession(
        sessions: List<ChatSessionRecord>,
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String?,
        assistantSnapshot: AssistantConversationSnapshot?,
        modelMode: String?,
        modelId: String?
    ): List<ChatSessionRecord>

    /** Persists the sessions and binds the knowledge bases in the same transaction. */
    fun persistChatSessions(
        sessions: List<ChatSessionRecord>? = null,
        knowledgeBinding: Pair<String, Set<String>>? = null
    )

    /** Admits the image job. False means "not admitted", not "failed"; it never throws for that. */
    fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        requestedJobId: String? = null,
        requestedChatSessionId: String? = null,
        requestedChatMessageId: String? = null
    ): Boolean

    /** Rewrites the queued request in the chat timeline. */
    fun updateChatGeneratedImageRequest(
        chatSessionId: String,
        requestId: String,
        request: ChatGeneratedImageRequest
    )

    /** Copies the admitted job's identity back onto the queued request. */
    fun updateChatImageRequestFromJob(chatSessionId: String, requestId: String, jobId: String)
}

internal object SendChatImageRequestOperations {

    /**
     * Sends one chat image request: appends the user turn and the assistant placeholder in a single
     * transaction, persists them, admits the job, and fan-outs on the admission result.
     *
     * `visibleUserText` is what stays in the timeline; `prompt` is what the image backend receives.
     * `optionsOverride` skips the live selection read (the translated-prompt path already resolved
     * it), and `promptEnvelope` is only accepted when it still matches `prompt` and the resolved
     * negative prompt -- a stale envelope stops the send instead of generating from stale text.
     */
    fun sendChatImageRequest(
        port: SendChatImageRequestPort,
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions? = null,
        promptEnvelope: PromptEnvelope? = null,
        contextTraceJson: String? = null
    ) {
        val initialState = port.uiState()
        if (!port.canWriteToActiveAssistantSession(initialState)) {
            port.showStatus("此会话仅供查看，请先创建角色分支。")
            return
        }
        if (initialState.isGenerating) {
            port.showStatus("当前聊天仍在生成，请等本轮结束后再发送图片请求。")
            return
        }
        if (port.rejectWhileConversationMutationInProgress()) return
        port.chatImageSubmissionBlockMessage(initialState)?.let { message ->
            port.showStatus(message)
            return
        }

        val assistantSnapshot = port.activeAssistantSnapshot(initialState)
            ?: port.selectedAssistant(initialState)?.toConversationSnapshot()
        val assistantId = assistantSnapshot?.assistantId ?: initialState.selectedAssistantId
        val requestId = UUID.randomUUID().toString()
        val jobId = "ui-img-${UUID.randomUUID()}"
        val initialImageModelId = if (initialState.selectedImageBackend == ImageBackend.LOCAL) {
            initialState.selectedLocalImageModelId
        } else {
            initialState.selectedCloudImageModelId
        }
        val initialImageModelName = if (initialState.selectedImageBackend == ImageBackend.LOCAL) {
            port.selectedLocalImageModel(initialState)?.displayName
        } else {
            port.selectedImageCloudConfig(initialState)?.let { config ->
                config.displayName.takeIf(String::isNotBlank) ?: config.imageModel
            }
        }
        val imageFingerprint = when (initialState.selectedImageBackend) {
            ImageBackend.LOCAL -> port.selectedLocalImageModel(initialState)?.chatImageRetryFingerprint()
            ImageBackend.CLOUD -> port.selectedImageCloudConfig(initialState)?.chatImageRetryFingerprint()
        }
        val imageOptions = optionsOverride ?: runCatching {
            port.chatImageOptionsForCurrentSelection(initialState)
        }.getOrElse { error ->
            port.updateUiState { it.copy(input = visibleUserText, statusMessage = error.message.orEmpty()) }
            return
        }
        promptEnvelope?.let { envelope ->
            if (envelope.positive != prompt || envelope.negative != imageOptions.negativePrompt) {
                port.updateUiState {
                    it.copy(
                        input = visibleUserText,
                        statusMessage = "图片描述整理结果已过期，未启动生图。请重试。"
                    )
                }
                return
            }
        }
        val request = ChatGeneratedImageRequest(
            id = requestId,
            prompt = prompt,
            status = ChatGeneratedImageStatus.QUEUED,
            message = "正在准备图片生成。",
            currentJobId = jobId,
            backendId = initialState.selectedImageBackend.name,
            modelId = initialImageModelId,
            modelName = initialImageModelName,
            modelFingerprint = imageFingerprint,
            generationOptionsJson = imageOptions.toJson().toString(),
            promptEnvelopeJson = promptEnvelope?.let { envelope ->
                ChatImagePromptBridge.run { envelope.toAuditJsonString() }
            }
        )
        val user = ChatMessage(Role.USER, visibleUserText)
        val assistant = ChatMessage(
            role = Role.ASSISTANT,
            content = "正在准备图片生成。",
            generatedImageRequest = request,
            contextAssemblyTraceJson = contextTraceJson
        )
        var ownerSessionId: String? = null
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        var knowledgeBaseIdsForBinding: Set<String> = emptySet()
        port.updateUiState { state ->
            val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
            ownerSessionId = sessionId
            knowledgeBaseIdsForBinding = state.selectedKnowledgeBaseIds
            val messages = state.messages + user + assistant
            sessionsToPersist = port.upsertSession(
                sessions = state.chatSessions,
                sessionId = sessionId,
                messages = messages,
                assistantId = assistantId,
                assistantSnapshot = assistantSnapshot,
                modelMode = state.selectedChatBackend.bindingValue(),
                modelId = port.currentChatModelId(state)
            )
            state.copy(
                input = "",
                messages = messages,
                activeChatSessionId = sessionId,
                chatSessions = sessionsToPersist,
                statusMessage = null
            )
        }
        val chatSessionId = ownerSessionId ?: return
        port.persistChatSessions(
            sessions = sessionsToPersist,
            knowledgeBinding = chatSessionId to knowledgeBaseIdsForBinding
        )
        val admitted = port.enqueueImageGeneration(
            prompt = prompt,
            inputDraft = LocalImageInputDraft(),
            options = imageOptions,
            jobSnapshot = null,
            requestedJobId = jobId,
            requestedChatSessionId = chatSessionId,
            requestedChatMessageId = requestId
        )
        if (!admitted) {
            port.updateChatGeneratedImageRequest(
                chatSessionId = chatSessionId,
                requestId = requestId,
                request = request.copy(
                    status = ChatGeneratedImageStatus.FAILED,
                    message = port.uiState().statusMessage ?: "无法开始图片生成，请检查已选模型。"
                )
            )
            port.updateUiState { state ->
                if (state.activeChatSessionId == chatSessionId && state.input.isBlank()) {
                    state.copy(input = visibleUserText)
                } else {
                    state
                }
            }
        } else {
            port.updateChatImageRequestFromJob(chatSessionId, requestId, jobId)
        }
    }
}
