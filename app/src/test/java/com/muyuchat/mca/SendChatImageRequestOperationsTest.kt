package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the extracted chat-image send path ([SendChatImageRequestOperations.sendChatImageRequest]).
 *
 * The port is a hand-written recording fake on purpose: the production collaborator is a 19,600-line
 * `AndroidViewModel` that needs a real `Application`, and this operation is a decision sequence, not
 * a suspend flow. Every test drives one branch and asserts what the port observed -- the status the
 * user sees, the state transaction that was published, the ids that reached admission, and the
 * request rewrite that follows a rejected admission.
 */
class SendChatImageRequestOperationsTest {

    // ── the fake port ───────────────────────────────────────────────────────────────────────────

    private data class UpsertCall(
        val sessions: List<ChatSessionRecord>,
        val sessionId: String,
        val messages: List<ChatMessage>,
        val assistantId: String?,
        val assistantSnapshot: AssistantConversationSnapshot?,
        val modelMode: String?,
        val modelId: String?
    )

    private data class EnqueueCall(
        val prompt: String,
        val inputDraft: LocalImageInputDraft,
        val options: LocalImageGenerationOptions,
        val jobSnapshot: ImageGenerationJobSpec?,
        val requestedJobId: String?,
        val requestedChatSessionId: String?,
        val requestedChatMessageId: String?
    )

    private class RecordingPort(
        var state: MainUiState,
        var writable: Boolean = true,
        var submissionBlockMessage: String? = null,
        var mutationInProgress: Boolean = false,
        var assistantSnapshot: AssistantConversationSnapshot? = null,
        var selectedAssistantRecord: AssistantRecord? = null,
        var localImageModel: LocalImageModelRecord? = null,
        var cloudImageConfig: CloudApiConfig? = null,
        var liveOptions: LocalImageGenerationOptions = LocalImageGenerationOptions(batchCount = 1),
        var optionsFailure: Throwable? = null,
        var chatModelId: String? = "chat-model-1",
        var admitted: Boolean = true,
        var enqueueFailure: Throwable? = null,
        var upserted: List<ChatSessionRecord>? = null,
        var onEnqueue: ((RecordingPort) -> Unit)? = null,
        /** Runs before a transaction transform, so a test can stage a concurrent publication. */
        var onBeforeTransform: ((RecordingPort) -> Unit)? = null,
        /** Derives the answer from the state argument, so the test can tell which state was asked. */
        var modelIdFromState: ((MainUiState) -> String?)? = null
    ) : SendChatImageRequestPort {

        val statuses = mutableListOf<String>()
        val publishedStates = mutableListOf<MainUiState>()
        val upsertCalls = mutableListOf<UpsertCall>()
        val persistCalls = mutableListOf<Pair<List<ChatSessionRecord>?, Pair<String, Set<String>>?>>()
        val enqueueCalls = mutableListOf<EnqueueCall>()
        val requestUpdates = mutableListOf<ChatGeneratedImageRequest>()
        val jobBackfills = mutableListOf<Triple<String, String, String>>()
        val modelIdQueries = mutableListOf<MainUiState>()
        val uiStateReads = mutableListOf<MainUiState>()
        val transformInputs = mutableListOf<MainUiState>()
        var localModelQueries = 0

        override fun uiState(): MainUiState {
            uiStateReads += state
            return state
        }

        override fun updateUiState(transform: (MainUiState) -> MainUiState) {
            onBeforeTransform?.invoke(this)
            val input = state
            transformInputs += input
            state = transform(input)
            publishedStates += state
        }

        override fun showStatus(message: String) {
            statuses += message
        }

        override fun rejectWhileConversationMutationInProgress(): Boolean = mutationInProgress

        override fun chatImageSubmissionBlockMessage(state: MainUiState): String? = submissionBlockMessage

        override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean = writable

        override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
            assistantSnapshot

        override fun selectedAssistant(state: MainUiState): AssistantRecord? = selectedAssistantRecord

        override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? {
            localModelQueries += 1
            return localImageModel
        }

        override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? = cloudImageConfig

        override fun currentChatModelId(state: MainUiState): String? {
            modelIdQueries += state
            return modelIdFromState?.invoke(state) ?: chatModelId
        }

        override fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
            optionsFailure?.let { throw it } ?: liveOptions

        override fun upsertSession(
            sessions: List<ChatSessionRecord>,
            sessionId: String,
            messages: List<ChatMessage>,
            assistantId: String?,
            assistantSnapshot: AssistantConversationSnapshot?,
            modelMode: String?,
            modelId: String?
        ): List<ChatSessionRecord> {
            upsertCalls += UpsertCall(
                sessions = sessions,
                sessionId = sessionId,
                messages = messages,
                assistantId = assistantId,
                assistantSnapshot = assistantSnapshot,
                modelMode = modelMode,
                modelId = modelId
            )
            return upserted ?: sessions
        }

        override fun persistChatSessions(
            sessions: List<ChatSessionRecord>?,
            knowledgeBinding: Pair<String, Set<String>>?
        ) {
            persistCalls += sessions to knowledgeBinding
        }

        override fun enqueueImageGeneration(
            prompt: String,
            inputDraft: LocalImageInputDraft,
            options: LocalImageGenerationOptions,
            jobSnapshot: ImageGenerationJobSpec?,
            requestedJobId: String?,
            requestedChatSessionId: String?,
            requestedChatMessageId: String?
        ): Boolean {
            enqueueCalls += EnqueueCall(
                prompt = prompt,
                inputDraft = inputDraft,
                options = options,
                jobSnapshot = jobSnapshot,
                requestedJobId = requestedJobId,
                requestedChatSessionId = requestedChatSessionId,
                requestedChatMessageId = requestedChatMessageId
            )
            onEnqueue?.invoke(this)
            enqueueFailure?.let { throw it }
            return admitted
        }

        override fun updateChatGeneratedImageRequest(
            chatSessionId: String,
            requestId: String,
            request: ChatGeneratedImageRequest
        ) {
            requestUpdates += request
        }

        override fun updateChatImageRequestFromJob(chatSessionId: String, requestId: String, jobId: String) {
            jobBackfills += Triple(chatSessionId, requestId, jobId)
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────────

    private fun localModel(id: String = "local-1", name: String = "本地模型") = LocalImageModelRecord(
        id = id,
        displayName = name,
        path = "/models/$id.safetensors",
        fileName = "$id.safetensors",
        sizeBytes = 2048L,
        sha256 = "a".repeat(64),
        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP
    )

    private fun snapshot(assistantId: String) = AssistantConversationSnapshot(
        assistantId = assistantId,
        name = "小助手",
        systemPrompt = "你是小助手。",
        memoryEnabled = false,
        webSearchEnabled = false,
        fileContextEnabled = true,
        capturedAt = 1L
    )

    private fun localState(
        activeChatSessionId: String? = "session-1",
        isGenerating: Boolean = false,
        statusMessage: String? = null,
        selectedKnowledgeBaseIds: Set<String> = setOf("kb-1")
    ) = MainUiState(
        activeChatSessionId = activeChatSessionId,
        isGenerating = isGenerating,
        statusMessage = statusMessage,
        selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
        selectedAssistantId = "assistant-3",
        selectedImageBackend = ImageBackend.LOCAL,
        selectedLocalImageModelId = "local-1",
        messages = listOf(ChatMessage(Role.USER, "上一轮问话"), ChatMessage(Role.ASSISTANT, "上一轮回答")),
        chatSessions = listOf(ChatSessionRecord("session-1", "会话", emptyList()))
    )

    private fun operate(
        port: RecordingPort,
        visibleUserText: String = "画一只猫",
        prompt: String = "a cat",
        optionsOverride: LocalImageGenerationOptions? = null,
        promptEnvelope: PromptEnvelope? = null,
        contextTraceJson: String? = null
    ) = SendChatImageRequestOperations.sendChatImageRequest(
        port = port,
        visibleUserText = visibleUserText,
        prompt = prompt,
        optionsOverride = optionsOverride,
        promptEnvelope = promptEnvelope,
        contextTraceJson = contextTraceJson
    )

    private fun assertNothingWasPublished(port: RecordingPort) {
        assertTrue("no state transaction may be published", port.publishedStates.isEmpty())
        assertTrue("no session may be upserted", port.upsertCalls.isEmpty())
        assertTrue("no persistence may be started", port.persistCalls.isEmpty())
        assertTrue("no job may be admitted", port.enqueueCalls.isEmpty())
        assertTrue("no request may be rewritten", port.requestUpdates.isEmpty())
        assertTrue("no job identity may be written back", port.jobBackfills.isEmpty())
    }

    // ── the four guards, in order ───────────────────────────────────────────────────────────────

    @Test
    fun `a read-only session stops before any state change`() {
        val port = RecordingPort(localState(), writable = false)

        operate(port)

        assertEquals(listOf("此会话仅供查看，请先创建角色分支。"), port.statuses)
        assertNothingWasPublished(port)
    }

    @Test
    fun `a turn that is still generating stops before any state change`() {
        val port = RecordingPort(localState(isGenerating = true))

        operate(port)

        assertEquals(listOf("当前聊天仍在生成，请等本轮结束后再发送图片请求。"), port.statuses)
        assertNothingWasPublished(port)
    }

    @Test
    fun `a conversation mutation in progress publishes nothing here and does not look further`() {
        val port = RecordingPort(
            localState(),
            mutationInProgress = true,
            submissionBlockMessage = "must not be reached"
        )

        operate(port)

        // The mutation barrier owns its own status line; this operation only returns.
        assertTrue("the block message must not be read after the barrier rejected the send", port.statuses.isEmpty())
        assertNothingWasPublished(port)
    }

    @Test
    fun `a submission block message is surfaced instead of admitting the job`() {
        val port = RecordingPort(
            localState(),
            submissionBlockMessage = "已有图片任务正在运行，请等它完成或取消后再发送新的生图请求。"
        )

        operate(port)

        assertEquals(
            listOf("已有图片任务正在运行，请等它完成或取消后再发送新的生图请求。"),
            port.statuses
        )
        assertNothingWasPublished(port)
    }

    // ── options resolution and the prompt envelope ──────────────────────────────────────────────

    @Test
    fun `a failing options read restores the draft and shows the error message`() {
        val port = RecordingPort(
            localState(),
            optionsFailure = IllegalStateException("尚未选择可用的本地图像模型")
        )

        operate(port, visibleUserText = "画一只猫")

        assertEquals(1, port.publishedStates.size)
        assertEquals("画一只猫", port.state.input)
        assertEquals("尚未选择可用的本地图像模型", port.state.statusMessage)
        assertTrue(port.enqueueCalls.isEmpty())
        assertTrue(port.persistCalls.isEmpty())
        assertTrue(port.upsertCalls.isEmpty())
    }

    @Test
    fun `a failing options read with no message publishes an empty status`() {
        val port = RecordingPort(localState(), optionsFailure = IllegalStateException())

        operate(port)

        assertEquals("", port.state.statusMessage)
        assertTrue(port.enqueueCalls.isEmpty())
    }

    @Test
    fun `an envelope whose positive text no longer matches the prompt is not sent`() {
        val port = RecordingPort(localState())

        operate(
            port,
            prompt = "a cat",
            promptEnvelope = PromptEnvelope(original = "a cat", positive = "a different cat")
        )

        assertEquals(1, port.publishedStates.size)
        assertEquals("图片描述整理结果已过期，未启动生图。请重试。", port.state.statusMessage)
        assertEquals("画一只猫", port.state.input)
        assertTrue(port.enqueueCalls.isEmpty())
        assertTrue(port.persistCalls.isEmpty())
    }

    @Test
    fun `an envelope whose negative text no longer matches the resolved options is not sent`() {
        val port = RecordingPort(localState())

        operate(
            port,
            prompt = "a cat",
            promptEnvelope = PromptEnvelope(original = "a cat", positive = "a cat", negative = "blurry")
        )

        assertEquals("图片描述整理结果已过期，未启动生图。请重试。", port.state.statusMessage)
        assertTrue(port.enqueueCalls.isEmpty())
    }

    @Test
    fun `a matching envelope is carried into the request as audit json`() {
        val port = RecordingPort(localState())
        val envelope = PromptEnvelope(original = "a cat", positive = "a cat")

        operate(port, prompt = "a cat", promptEnvelope = envelope)

        val request = requireNotNull(port.requestOfTheAssistantTurn())
        val audit = requireNotNull(request.promptEnvelopeJson)
        assertEquals("a cat", JSONObject(audit).getString("positive"))
        assertEquals(1, JSONObject(audit).getInt("schemaVersion"))
    }

    // ── the admitted path ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the turn pair the transaction and the admission are published together`() {
        val port = RecordingPort(localState(), localImageModel = localModel())
        val options = LocalImageGenerationOptions(batchCount = 1, steps = 28)

        operate(port, visibleUserText = "画一只猫", prompt = "a cat", optionsOverride = options)

        // One transaction: the user turn and the assistant placeholder cannot be observed apart.
        assertEquals(1, port.publishedStates.size)
        assertEquals("", port.state.input)
        assertNull(port.state.statusMessage)
        assertEquals("session-1", port.state.activeChatSessionId)
        assertEquals(4, port.state.messages.size)
        assertEquals(Role.USER, port.state.messages[2].role)
        assertEquals("画一只猫", port.state.messages[2].content)
        assertEquals(Role.ASSISTANT, port.state.messages[3].role)
        assertEquals("正在准备图片生成。", port.state.messages[3].content)

        val request = requireNotNull(port.requestOfTheAssistantTurn())
        assertEquals(ChatGeneratedImageStatus.QUEUED, request.status)
        assertEquals("正在准备图片生成。", request.message)
        assertEquals("a cat", request.prompt)
        assertEquals("LOCAL", request.backendId)
        assertEquals("local-1", request.modelId)
        assertEquals("本地模型", request.modelName)
        assertEquals(localModel().chatImageRetryFingerprint(), request.modelFingerprint)
        assertEquals(28, JSONObject(requireNotNull(request.generationOptionsJson)).getInt("steps"))
        assertNull("no envelope was supplied", request.promptEnvelopeJson)

        val upsert = port.upsertCalls.single()
        assertEquals("session-1", upsert.sessionId)
        assertEquals(4, upsert.messages.size)
        assertEquals("assistant-3", upsert.assistantId)
        assertEquals("local", upsert.modelMode)
        assertEquals("chat-model-1", upsert.modelId)

        val enqueue = port.enqueueCalls.single()
        assertEquals("a cat", enqueue.prompt)
        assertEquals(LocalImageInputDraft(), enqueue.inputDraft)
        assertSame("the resolved options must reach admission unchanged", options, enqueue.options)
        assertNull(enqueue.jobSnapshot)
        assertTrue(
            "the UI job id must keep its ui-img- prefix",
            requireNotNull(enqueue.requestedJobId).startsWith("ui-img-")
        )
        assertEquals("session-1", enqueue.requestedChatSessionId)
        assertEquals(request.id, enqueue.requestedChatMessageId)
        assertEquals(request.currentJobId, enqueue.requestedJobId)

        assertEquals(
            Triple("session-1", request.id, requireNotNull(request.currentJobId)),
            port.jobBackfills.single()
        )
        assertTrue("an admitted job must not be rewritten as failed", port.requestUpdates.isEmpty())
    }

    @Test
    fun `the live selection is used when the caller supplies no options override`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            liveOptions = LocalImageGenerationOptions(batchCount = 1, steps = 17)
        )

        operate(port)

        assertSame(port.liveOptions, port.enqueueCalls.single().options)
        assertEquals(
            17,
            JSONObject(requireNotNull(port.requestOfTheAssistantTurn()?.generationOptionsJson)).getInt("steps")
        )
    }

    @Test
    fun `the context assembly trace is attached to the assistant placeholder`() {
        val port = RecordingPort(localState(), localImageModel = localModel())

        operate(port, contextTraceJson = "trace-42")

        assertEquals("trace-42", port.state.messages[3].contextAssemblyTraceJson)
    }

    @Test
    fun `the upsert result is what gets persisted and published`() {
        val upserted = listOf(
            ChatSessionRecord(
                id = "session-1",
                title = "会话",
                messages = listOf(ChatMessage(Role.USER, "画一只猫"), ChatMessage(Role.ASSISTANT, "正在准备图片生成。"))
            )
        )
        val port = RecordingPort(localState(), localImageModel = localModel(), upserted = upserted)

        operate(port)

        assertEquals(upserted, port.state.chatSessions)
        assertEquals(upserted, port.persistCalls.single().first)
        assertEquals("session-1" to setOf("kb-1"), port.persistCalls.single().second)
    }

    @Test
    fun `a missing session id is generated once and reused for binding and admission`() {
        val port = RecordingPort(
            localState(activeChatSessionId = null),
            localImageModel = localModel()
        )

        operate(port)

        val sessionId = requireNotNull(port.state.activeChatSessionId)
        assertTrue("a generated session id must look like a UUID", Regex("[0-9a-f-]{36}").matches(sessionId))
        assertEquals(sessionId, port.persistCalls.single().second?.first)
        assertEquals(sessionId, port.enqueueCalls.single().requestedChatSessionId)
    }

    @Test
    fun `the chat model id is resolved from the state the transaction transforms`() {
        val port = RecordingPort(localState(), localImageModel = localModel())
        // The answer is derived from the state argument, and a concurrent publication replaces the
        // state right before the transform runs: if the operation resolved the id from the initial
        // read instead of from the transaction's own state, this test would see the stale value.
        port.modelIdFromState = { state -> state.loadedModelId }
        port.onBeforeTransform = { it.state = it.state.copy(loadedModelId = "model-live") }

        operate(port)

        assertEquals("model-live", port.upsertCalls.single().modelId)
        assertSame(
            "the model id must be asked of the state the transaction is transforming",
            port.transformInputs.first(),
            port.modelIdQueries.single()
        )
        assertNotSame(
            "the id must not come from the pre-send read",
            port.uiStateReads.first(),
            port.modelIdQueries.single()
        )
    }

    // ── the selected image backend branch ───────────────────────────────────────────────────────

    @Test
    fun `a cloud backend names the request from its image model and never reads the local model`() {
        val config = CloudApiConfig(displayName = "   ", imageModel = "flux-1")
        val port = RecordingPort(
            state = localState().copy(
                selectedImageBackend = ImageBackend.CLOUD,
                selectedCloudImageModelId = "cloud-1"
            ),
            localImageModel = localModel(),
            cloudImageConfig = config
        )

        operate(port)

        val request = requireNotNull(port.requestOfTheAssistantTurn())
        assertEquals("CLOUD", request.backendId)
        assertEquals("cloud-1", request.modelId)
        assertEquals("a blank display name must fall through to the image model", "flux-1", request.modelName)
        assertEquals(config.chatImageRetryFingerprint(), request.modelFingerprint)
        assertEquals("the local model must not be consulted for a cloud backend", 0, port.localModelQueries)
    }

    // ── who the turn is attributed to ───────────────────────────────────────────────────────────

    @Test
    fun `a frozen session snapshot wins over the currently selected assistant`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            assistantSnapshot = snapshot("assistant-9"),
            selectedAssistantRecord = AssistantRecord(id = "assistant-1")
        )

        operate(port)

        val upsert = port.upsertCalls.single()
        assertEquals("assistant-9", upsert.assistantId)
        assertEquals("assistant-9", upsert.assistantSnapshot?.assistantId)
    }

    @Test
    fun `the selected assistant is snapshotted when the session has none`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            selectedAssistantRecord = AssistantRecord(id = "assistant-2", name = "小助手")
        )

        operate(port)

        val upsert = port.upsertCalls.single()
        assertEquals("assistant-2", upsert.assistantId)
        assertEquals("assistant-2", upsert.assistantSnapshot?.assistantId)
    }

    @Test
    fun `the selected assistant id is the last resort for attribution`() {
        val port = RecordingPort(localState(), localImageModel = localModel())

        operate(port)

        val upsert = port.upsertCalls.single()
        assertEquals("assistant-3", upsert.assistantId)
        assertNull(upsert.assistantSnapshot)
    }

    // ── the rejected admission path ─────────────────────────────────────────────────────────────

    @Test
    fun `a rejected admission fails the request with the fallback wording and restores the draft`() {
        val port = RecordingPort(localState(), localImageModel = localModel(), admitted = false)

        operate(port, visibleUserText = "画一只猫")

        val request = requireNotNull(port.requestOfTheAssistantTurn())
        val failed = port.requestUpdates.single()
        assertEquals(request.id, failed.id)
        assertEquals(ChatGeneratedImageStatus.FAILED, failed.status)
        assertEquals("无法开始图片生成，请检查已选模型。", failed.message)
        assertEquals(request.currentJobId, failed.currentJobId)
        assertEquals("local-1", failed.modelId)
        assertTrue("a rejected admission must not write the job back", port.jobBackfills.isEmpty())

        assertEquals("a second transaction restores the draft", 2, port.publishedStates.size)
        assertEquals("画一只猫", port.state.input)
    }

    @Test
    fun `a rejected admission prefers the status the port published while admitting`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            admitted = false,
            onEnqueue = { it.state = it.state.copy(statusMessage = "本地图像模型暂不可用：缺少主模型文件") }
        )

        operate(port)

        assertEquals("本地图像模型暂不可用：缺少主模型文件", port.requestUpdates.single().message)
        assertEquals("画一只猫", port.state.input)
    }

    @Test
    fun `a rejected admission leaves a newer draft in the composer alone`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            admitted = false,
            onEnqueue = { it.state = it.state.copy(input = "用户后来输入的内容") }
        )

        operate(port, visibleUserText = "画一只猫")

        assertEquals("用户后来输入的内容", port.state.input)
        assertEquals(1, port.requestUpdates.size)
    }

    @Test
    fun `a rejected admission never restores the draft into another session`() {
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            admitted = false,
            onEnqueue = {
                it.state = it.state.copy(activeChatSessionId = "session-2", input = "")
            }
        )

        operate(port, visibleUserText = "画一只猫")

        assertEquals("session-2", port.state.activeChatSessionId)
        assertEquals("", port.state.input)
        // The request of this send is still rewritten; only the draft restore is skipped.
        assertEquals(ChatGeneratedImageStatus.FAILED, port.requestUpdates.single().status)
    }

    @Test
    fun `an admission failure propagates instead of being swallowed`() {
        val failure = IllegalStateException("coordinator exploded")
        val port = RecordingPort(
            localState(),
            localImageModel = localModel(),
            enqueueFailure = failure
        )

        val thrown = try {
            operate(port)
            null
        } catch (error: Throwable) {
            error
        }

        assertSame("the original had no try boundary around admission", failure, thrown)
        assertEquals("the turn was already published before the failure", 1, port.publishedStates.size)
        assertTrue("no recovery may be invented here", port.requestUpdates.isEmpty())
        assertTrue(port.jobBackfills.isEmpty())
    }

    // ── the ViewModel-side adapter shape used by the paste block ────────────────────────────────

    /**
     * The delegate in `MainViewModel` builds its port as an object expression inside a private
     * method and routes each member to the private member it replaces. Two things there are easy to
     * get wrong and are pinned here: `this@Host` reaches private members from inside the object
     * expression, and a private member *extension* needs the dispatch receiver in scope, which is
     * what `with(this@Host)` provides.
     */
    private class AdapterShapeHost {
        private var value = MainUiState(input = "host")
        private val statuses = mutableListOf<String>()

        private fun writeValue(transform: (MainUiState) -> MainUiState) {
            value = transform(value)
        }

        private fun publishStatus(message: String) {
            statuses += message
        }

        private fun MainUiState.canWriteToActiveAssistantSession(): Boolean = input == "host"

        fun route(): Pair<MainUiState, List<String>> {
            val port = object : SendChatImageRequestPort {
                override fun uiState(): MainUiState = this@AdapterShapeHost.value

                override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                    this@AdapterShapeHost.writeValue(transform)
                }

                override fun showStatus(message: String) {
                    this@AdapterShapeHost.publishStatus(message)
                }

                override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
                    with(this@AdapterShapeHost) { state.canWriteToActiveAssistantSession() }

                override fun rejectWhileConversationMutationInProgress(): Boolean = error("unused")
                override fun chatImageSubmissionBlockMessage(state: MainUiState): String? = error("unused")
                override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
                    error("unused")

                override fun selectedAssistant(state: MainUiState): AssistantRecord? = error("unused")
                override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? =
                    error("unused")

                override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? = error("unused")
                override fun currentChatModelId(state: MainUiState): String? = error("unused")

                override fun chatImageOptionsForCurrentSelection(
                    state: MainUiState
                ): LocalImageGenerationOptions = error("unused")

                override fun upsertSession(
                    sessions: List<ChatSessionRecord>,
                    sessionId: String,
                    messages: List<ChatMessage>,
                    assistantId: String?,
                    assistantSnapshot: AssistantConversationSnapshot?,
                    modelMode: String?,
                    modelId: String?
                ): List<ChatSessionRecord> = error("unused")

                override fun persistChatSessions(
                    sessions: List<ChatSessionRecord>?,
                    knowledgeBinding: Pair<String, Set<String>>?
                ) = error("unused")

                override fun enqueueImageGeneration(
                    prompt: String,
                    inputDraft: LocalImageInputDraft,
                    options: LocalImageGenerationOptions,
                    jobSnapshot: ImageGenerationJobSpec?,
                    requestedJobId: String?,
                    requestedChatSessionId: String?,
                    requestedChatMessageId: String?
                ): Boolean = error("unused")

                override fun updateChatGeneratedImageRequest(
                    chatSessionId: String,
                    requestId: String,
                    request: ChatGeneratedImageRequest
                ) = error("unused")

                override fun updateChatImageRequestFromJob(
                    chatSessionId: String,
                    requestId: String,
                    jobId: String
                ) = error("unused")
            }

            port.updateUiState { it.copy(input = "written-through") }
            port.showStatus("routed")
            val writable = port.canWriteToActiveAssistantSession(MainUiState(input = "host"))
            port.showStatus(if (writable) "writable" else "read-only")
            return port.uiState() to statuses.toList()
        }
    }

    @Test
    fun `the delegate adapter shape routes to private host members`() {
        val (state, statuses) = AdapterShapeHost().route()

        assertEquals("written-through", state.input)
        assertEquals(listOf("routed", "writable"), statuses)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private fun RecordingPort.requestOfTheAssistantTurn(): ChatGeneratedImageRequest? =
        state.messages.lastOrNull { it.role == Role.ASSISTANT }?.generatedImageRequest
}
