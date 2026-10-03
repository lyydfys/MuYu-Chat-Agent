package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the assistant image-tool turn now that it lives outside `MainViewModel`.
 *
 * The guards that matter here are the ones a mechanical move would quietly break: every
 * user-visible failure wording, the order of the bridges and the pending-turn/timeline writes, the
 * single live-state read the auto-approval gate compares, and the fact that a port exception is
 * still an exception rather than a "图片请求未执行" line.
 *
 * The fake is hand written on purpose: no mocking framework is used in this repository, and the
 * assertions below are about a call order, which a stub framework would hide.
 */
private const val SINGLE_IMAGE_FAILURE = "一次助手回复最多只能请求生成一张图片。"
private const val UNKNOWN_TOOL_TURN_TARGET_FAILURE = "无法确定本次生图请求所属的对话或云端模型。"
private const val MISSING_IMAGE_MODEL_FAILURE = "请先在模型管理中选择一个可用的生图模型，再发送本轮消息。"
private const val UNAVAILABLE_IMAGE_MODEL_FAILURE = "所选生图模型已不可用，请先恢复模型后重新发送本轮消息。"
private const val UNUSABLE_ARGUMENT_NEGATIVE_FAILURE = "聊天模型未返回可用的英文负面提示词，未启动生图。请重试角色回复。"
private const val WRONG_TOOL_FAILURE = "当前只允许调用生图工具。"

private fun prepared(positive: String = "a red bird", negative: String? = null): ChatImagePromptBridge.Result =
    ChatImagePromptBridge.Result.Prepared(
        originalPrompt = "一只红鸟",
        effectivePrompt = positive,
        translated = true,
        effectiveNegativePrompt = negative
    )

private fun failed(message: String): ChatImagePromptBridge.Result =
    ChatImagePromptBridge.Result.Failed(ChatImagePromptBridge.Code.MODEL_ERROR, message)

private fun snapshotSpec(options: LocalImageGenerationOptions): ImageGenerationJobSpec =
    ImageGenerationJobSpec(
        prompt = "a red bird",
        backend = ImageBackend.CLOUD,
        cloudConfigSnapshot = CloudApiConfig(),
        modelId = "image-model-1",
        modelName = "Image Model",
        inputDraft = LocalImageInputDraft(),
        options = options
    )

private fun optionsJson(options: LocalImageGenerationOptions): String = options.toJson().toString()

/** The snapshot the port returns is deliberately different from the request's own options. */
private val SNAPSHOT_OPTIONS = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "snapshot-frozen")

private fun toolCall(argumentsJson: String = """{"prompt":"一只红鸟"}"""): ChatToolCall =
    ChatToolCall(id = "call-1", name = CHAT_IMAGE_TOOL_NAME, argumentsJson = argumentsJson)

class AssistantImageToolTurnOperationsTest {

    private class FakePort : AssistantImageToolTurnPort {
        var positive: ChatImagePromptBridge.Result = prepared()
        var negative: ChatImagePromptBridge.Result =
            prepared(positive = "neutral image", negative = "watermark")
        var snapshot: ImageGenerationJobSpec? = snapshotSpec(SNAPSHOT_OPTIONS)
        var selection = AssistantImageToolTurnSelection(
            activeChatSessionId = "session-1",
            selectedCloudChatModelId = "cloud-model-1",
            chatModelIdentity = "chat-identity",
            imageAuthorizationIdentity = "image-identity"
        )
        var mode: ChatImageToolAuthorizationMode = ChatImageToolAuthorizationMode.ASK_EVERY_TIME
        var stateSessionId: String? = null
        var topologySupportsNegative: Boolean = true
        var throwOnPositiveBridge: Throwable? = null
        var throwOnArgumentNegativeBridge: Throwable? = null
        var throwOnAttach: Throwable? = null

        /** Proves the CLOUD short-circuit: this must never be asked for a cloud backend. */
        var failQuestioningTopology: Boolean = false

        val timeline = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val approved = mutableListOf<String>()
        val registered = mutableListOf<AssistantImageToolPendingTurn>()
        val negativeBridgeCalls = mutableListOf<Pair<String, Boolean>>()
        var attached: Pair<String, ChatGeneratedImageRequest>? = null
        var snapshotRequest: ChatGeneratedImageRequest? = null
        var authorizationCall: Triple<String?, String?, String?>? = null

        override fun activeChatSessionId(): String? {
            timeline += "activeSessionId"
            return stateSessionId
        }

        override fun chatImageNegativePromptSupported(): Boolean {
            timeline += "topology"
            check(!failQuestioningTopology) {
                "the negative-prompt topology must not be questioned for a cloud backend"
            }
            return topologySupportsNegative
        }

        override suspend fun prepareAssistantImagePrompt(
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>
        ): ChatImagePromptBridge.Result {
            timeline += "positiveBridge"
            throwOnPositiveBridge?.let { throw it }
            return positive
        }

        override suspend fun prepareAssistantImageNegativePrompt(
            prompt: String,
            chatConfig: CloudApiConfig?,
            params: GenerationParams,
            conversationContext: List<ChatMessage>,
            preserveAuthoredNegative: Boolean
        ): ChatImagePromptBridge.Result {
            timeline += "negativeBridge"
            negativeBridgeCalls += prompt to preserveAuthoredNegative
            throwOnArgumentNegativeBridge?.let { throw it }
            return negative
        }

        override fun setPendingAssistantToolFailure(message: String) {
            timeline += "failure"
            failures += message
        }

        override fun buildChatImageRetrySnapshot(
            request: ChatGeneratedImageRequest,
            chatSessionId: String
        ): ImageGenerationJobSpec? {
            timeline += "snapshot"
            snapshotRequest = request
            return snapshot
        }

        override fun registerPendingAssistantImageToolTurn(turn: AssistantImageToolPendingTurn) {
            timeline += "register"
            registered += turn
        }

        override fun attachAssistantImageRequestToTimeline(
            chatSessionId: String,
            request: ChatGeneratedImageRequest
        ) {
            timeline += "attach"
            throwOnAttach?.let { throw it }
            attached = chatSessionId to request
        }

        override fun currentTurnSelectionSnapshot(): AssistantImageToolTurnSelection {
            timeline += "selection"
            return selection
        }

        override fun chatImageToolAuthorizationMode(
            chatSessionId: String?,
            chatModelIdentity: String?,
            imageModelIdentity: String?
        ): ChatImageToolAuthorizationMode {
            timeline += "mode"
            authorizationCall = Triple(chatSessionId, chatModelIdentity, imageModelIdentity)
            // Mirrors ChatImageToolAuthorizationStore.getMode: a missing identity revokes the
            // stored grant and falls back to the safe default, so a null here must not approve.
            if (chatSessionId == null || chatModelIdentity == null || imageModelIdentity == null) {
                return ChatImageToolAuthorizationMode.ASK_EVERY_TIME
            }
            return mode
        }

        override fun approveChatImageRequest(requestId: String) {
            timeline += "approve"
            approved += requestId
        }
    }

    private fun binding(
        backend: ImageBackend = ImageBackend.CLOUD,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(batchCount = 1)
    ) = AssistantImageToolModelBinding(
        backend = backend,
        modelId = "image-model-1",
        modelName = "Image Model",
        options = options,
        fingerprint = "fingerprint-1",
        authorizationIdentity = { "image-identity" }
    )

    private fun invoke(
        port: FakePort,
        calls: List<ChatToolCall> = listOf(toolCall()),
        messages: List<ChatMessage> = listOf(ChatMessage(Role.USER, "画一只红鸟")),
        params: GenerationParams = GenerationParams(),
        chatSessionId: String? = "session-1",
        cloudModelId: String? = "cloud-model-1",
        chatModelIdentity: String? = "chat-identity",
        imageModelBinding: AssistantImageToolModelBinding? = binding(),
        chatConfig: CloudApiConfig? = CloudApiConfig()
    ): Boolean = runBlocking {
        handleAssistantImageToolTurn(
            port = port,
            calls = calls,
            originalRequestMessages = messages,
            paramsBeforeAssistant = params,
            chatSessionId = chatSessionId,
            cloudModelId = cloudModelId,
            chatModelIdentity = chatModelIdentity,
            imageModelBinding = imageModelBinding,
            chatConfig = chatConfig
        )
    }

    private fun optionsOf(port: FakePort): JSONObject =
        JSONObject(requireNotNull(port.snapshotRequest).generationOptionsJson)

    private fun attachedOptionsOf(port: FakePort): JSONObject =
        JSONObject(requireNotNull(port.attached).second.generationOptionsJson)

    // ---- the early guards ---------------------------------------------------------------------

    @Test
    fun `no tool calls are not a handled turn and nothing is touched`() {
        val port = FakePort()
        assertTrue(!invoke(port, calls = emptyList()))
        assertEquals(emptyList<String>(), port.timeline)
    }

    @Test
    fun `more than one call is refused with the single-image message`() {
        val port = FakePort()
        assertTrue(invoke(port, calls = listOf(toolCall(), toolCall())))
        assertEquals(listOf(SINGLE_IMAGE_FAILURE), port.failures)
        assertEquals(listOf("failure"), port.timeline)
    }

    @Test
    fun `a call to another tool is refused with the validator reason`() {
        val port = FakePort()
        val other = ChatToolCall(id = "call-1", name = "delete_everything", argumentsJson = "{}")
        assertTrue(invoke(port, calls = listOf(other)))
        assertEquals(listOf(WRONG_TOOL_FAILURE), port.failures)
        assertEquals(listOf("failure"), port.timeline)
    }

    @Test
    fun `a blank live session id is reported before any bridge runs`() {
        val port = FakePort()
        port.stateSessionId = "   "
        assertTrue(invoke(port, chatSessionId = null))
        assertEquals(listOf(UNKNOWN_TOOL_TURN_TARGET_FAILURE), port.failures)
        assertEquals(listOf("activeSessionId", "failure"), port.timeline)
    }

    @Test
    fun `a missing cloud model id is reported even when the session resolves`() {
        val port = FakePort()
        assertTrue(invoke(port, cloudModelId = null))
        assertEquals(listOf(UNKNOWN_TOOL_TURN_TARGET_FAILURE), port.failures)
        assertEquals(listOf("failure"), port.timeline)
    }

    @Test
    fun `a missing image model binding is reported before the bridge runs`() {
        val port = FakePort()
        assertTrue(invoke(port, imageModelBinding = null))
        assertEquals(listOf(MISSING_IMAGE_MODEL_FAILURE), port.failures)
        assertEquals(listOf("failure"), port.timeline)
    }

    // ---- the prompt bridges -------------------------------------------------------------------

    @Test
    fun `a failed positive bridge reports the bridge reason and starts nothing`() {
        val port = FakePort()
        port.positive = failed("bridge down")
        assertTrue(invoke(port))
        assertEquals(
            listOf("聊天模型未能把角色的图片描述整理成可执行提示词，未启动生图。请重试角色回复。原因：bridge down"),
            port.failures
        )
        assertEquals(listOf("positiveBridge", "failure"), port.timeline)
        assertEquals(emptyList<AssistantImageToolPendingTurn>(), port.registered)
        assertNull(port.attached)
    }

    @Test
    fun `a blank translated argument negative is refused instead of generating`() {
        val port = FakePort()
        port.negative = prepared(positive = "neutral image", negative = "   ")
        assertTrue(invoke(port, calls = listOf(toolCall("""{"prompt":"一只红鸟","negative_prompt":"不要文字"}"""))))
        assertEquals(listOf(UNUSABLE_ARGUMENT_NEGATIVE_FAILURE), port.failures)
        assertEquals(listOf("positiveBridge", "negativeBridge", "failure"), port.timeline)
        assertEquals(listOf("不要文字" to true), port.negativeBridgeCalls)
        assertNull(port.attached)
    }

    @Test
    fun `a failed argument negative bridge reports its own reason`() {
        val port = FakePort()
        port.negative = failed("no bridge")
        assertTrue(invoke(port, calls = listOf(toolCall("""{"prompt":"一只红鸟","negative_prompt":"不要文字"}"""))))
        assertEquals(
            listOf("聊天模型未能把角色请求中的负面提示词整理成英文，未启动生图。请重试角色回复。原因：no bridge"),
            port.failures
        )
        assertEquals(listOf("positiveBridge", "negativeBridge", "failure"), port.timeline)
    }

    @Test
    fun `a failed model default negative bridge reports its own reason`() {
        val port = FakePort()
        port.negative = failed("default bridge down")
        assertTrue(
            invoke(
                port,
                imageModelBinding = binding(
                    options = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "不要文字")
                )
            )
        )
        assertEquals(
            listOf("聊天模型未能整理生图模型的默认负面提示词，未启动生图。请重试角色回复。原因：default bridge down"),
            port.failures
        )
        assertEquals(listOf("positiveBridge", "negativeBridge", "failure"), port.timeline)
        // A model default is optional conditioning, so it is translated without the fail-closed
        // authored-negative rule.
        assertEquals(listOf("不要文字" to false), port.negativeBridgeCalls)
    }

    @Test
    fun `an ascii model default negative is passed through without a bridge`() {
        val port = FakePort()
        assertTrue(
            invoke(
                port,
                imageModelBinding = binding(
                    options = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "lowres, blurry")
                )
            )
        )
        assertEquals("lowres, blurry", optionsOf(port).getString("negativePrompt"))
        assertEquals(emptyList<Pair<String, Boolean>>(), port.negativeBridgeCalls)
    }

    @Test
    fun `a translated han model default negative replaces the authored default`() {
        val port = FakePort()
        port.negative = prepared(positive = "neutral image", negative = "no text")
        assertTrue(
            invoke(
                port,
                imageModelBinding = binding(
                    options = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "不要文字")
                )
            )
        )
        assertEquals("no text", optionsOf(port).getString("negativePrompt"))
        assertEquals(listOf("不要文字" to false), port.negativeBridgeCalls)
    }

    @Test
    fun `the argument and inline negatives are merged in order without duplicates`() {
        val port = FakePort()
        port.positive = prepared(negative = "lowres, watermark")
        port.negative = prepared(positive = "neutral image", negative = "watermark")
        assertTrue(invoke(port, calls = listOf(toolCall("""{"prompt":"一只红鸟","negative_prompt":"watermark"}"""))))
        // Authored argument clause first, then the inline branch, with the duplicate dropped.
        assertEquals("watermark, lowres", optionsOf(port).getString("negativePrompt"))
    }

    // ---- the negative-branch topology gate ----------------------------------------------------

    @Test
    fun `a cloud backend never asks whether the topology encodes negatives`() {
        val port = FakePort()
        port.failQuestioningTopology = true
        assertTrue(invoke(port))
        assertTrue("topology" !in port.timeline)
        assertEquals("snapshot-frozen", attachedOptionsOf(port).getString("negativePrompt"))
    }

    @Test
    fun `a local backend without negative support drops the branch from the request`() {
        val port = FakePort()
        port.topologySupportsNegative = false
        assertTrue(
            invoke(
                port,
                imageModelBinding = binding(
                    backend = ImageBackend.LOCAL,
                    options = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "lowres")
                )
            )
        )
        assertEquals(listOf("positiveBridge", "topology", "snapshot", "register", "attach", "selection", "mode"), port.timeline)
        assertTrue(!optionsOf(port).has("negativePrompt"))
    }

    @Test
    fun `a local backend with negative support keeps the branch`() {
        val port = FakePort()
        port.topologySupportsNegative = true
        assertTrue(
            invoke(
                port,
                imageModelBinding = binding(
                    backend = ImageBackend.LOCAL,
                    options = LocalImageGenerationOptions(batchCount = 1, negativePrompt = "lowres")
                )
            )
        )
        assertEquals("lowres", optionsOf(port).getString("negativePrompt"))
    }

    // ---- the request, the pending turn and the timeline ---------------------------------------

    @Test
    fun `an unavailable retry snapshot is reported and registers nothing`() {
        val port = FakePort()
        port.snapshot = null
        assertTrue(invoke(port))
        assertEquals(listOf(UNAVAILABLE_IMAGE_MODEL_FAILURE), port.failures)
        assertEquals(listOf("positiveBridge", "snapshot", "failure"), port.timeline)
        assertEquals(emptyList<AssistantImageToolPendingTurn>(), port.registered)
        assertNull(port.attached)
    }

    @Test
    fun `the happy path records the pending turn, the card and the auto approval`() {
        val port = FakePort()
        port.mode = ChatImageToolAuthorizationMode.AUTO_APPROVE
        val messages = listOf(ChatMessage(Role.USER, "画一只红鸟"))
        val params = GenerationParams()
        val call = toolCall()

        assertTrue(
            invoke(
                port,
                calls = listOf(call),
                messages = messages,
                params = params
            )
        )

        assertEquals(emptyList<String>(), port.failures)
        assertEquals(
            listOf("positiveBridge", "snapshot", "register", "attach", "selection", "mode", "approve"),
            port.timeline
        )

        val pending = port.registered.single()
        assertEquals("session-1", pending.chatSessionId)
        assertEquals("cloud-model-1", pending.cloudModelId)
        assertEquals(listOf(call), pending.toolCalls)
        assertEquals("call-1", pending.primaryToolCallId)
        assertEquals(emptyMap<String, String>(), pending.initialToolOutputs)
        assertSame(messages, pending.originalRequestMessages)
        assertSame(params, pending.params)

        val (attachedSessionId, request) = requireNotNull(port.attached)
        assertEquals("session-1", attachedSessionId)
        assertEquals(pending.requestId, request.id)
        assertEquals("a red bird", request.prompt)
        assertEquals(ChatGeneratedImageStatus.AWAITING_APPROVAL, request.status)
        assertEquals("角色请求生成一张图片。", request.message)
        assertEquals(ChatGeneratedImageOrigin.ASSISTANT_TOOL, request.origin)
        assertEquals("CLOUD", request.backendId)
        assertEquals("image-model-1", request.modelId)
        assertEquals("Image Model", request.modelName)
        assertEquals("fingerprint-1", request.modelFingerprint)
        assertEquals("call-1", request.toolCallId)
        assertEquals(call.argumentsJson, request.toolArgumentsJson)
        assertEquals("cloud-model-1", request.toolChatModelId)
        assertEquals(params.toJson(), request.toolParamsJson)
        assertEquals(1, request.toolRequestMessageCount)
        assertEquals(chatImageToolContextFingerprint(messages), request.toolRequestMessagesFingerprint)

        // The card carries the frozen snapshot options, while the request the snapshot was built
        // from still carried the effective options.
        assertEquals(optionsJson(SNAPSHOT_OPTIONS), request.generationOptionsJson)
        assertEquals(
            optionsJson(LocalImageGenerationOptions(batchCount = 1)),
            requireNotNull(port.snapshotRequest).generationOptionsJson
        )

        assertEquals(listOf(pending.requestId), port.approved)
        assertEquals(Triple("session-1", "chat-identity", "image-identity"), port.authorizationCall)
    }

    @Test
    fun `ask every time skips the approval but keeps everything else identical`() {
        val port = FakePort()
        port.mode = ChatImageToolAuthorizationMode.ASK_EVERY_TIME
        assertTrue(invoke(port))
        assertEquals(
            listOf("positiveBridge", "snapshot", "register", "attach", "selection", "mode"),
            port.timeline
        )
        assertEquals(emptyList<String>(), port.approved)
        assertEquals(1, port.registered.size)
        assertTrue(port.attached != null)
    }

    @Test
    fun `a null chat session id falls back to the live state session`() {
        val port = FakePort()
        port.stateSessionId = "session-1"
        port.mode = ChatImageToolAuthorizationMode.AUTO_APPROVE
        assertTrue(invoke(port, chatSessionId = null))
        assertEquals("activeSessionId", port.timeline.first())
        assertEquals("session-1", port.registered.single().chatSessionId)
        assertEquals("session-1", requireNotNull(port.attached).first)
        assertEquals(listOf(port.registered.single().requestId), port.approved)
    }

    @Test
    fun `a changed live selection revokes the approval instead of approving a stale turn`() {
        val scenarios = linkedMapOf(
            "session" to AssistantImageToolTurnSelection("session-2", "cloud-model-1", "chat-identity", "image-identity"),
            "cloud model" to AssistantImageToolTurnSelection("session-1", "cloud-model-2", "chat-identity", "image-identity"),
            "chat identity" to AssistantImageToolTurnSelection("session-1", "cloud-model-1", "other-chat", "image-identity"),
            "image identity" to AssistantImageToolTurnSelection("session-1", "cloud-model-1", "chat-identity", "other-image")
        )
        scenarios.forEach { (label, selection) ->
            val port = FakePort()
            port.mode = ChatImageToolAuthorizationMode.AUTO_APPROVE
            port.selection = selection
            assertTrue(label, invoke(port))
            assertEquals(
                "$label: a stale identity must reach the store as null so the grant is revoked",
                Triple<String?, String?, String?>("session-1", null, null),
                port.authorizationCall
            )
            assertEquals("$label: no approval may be issued", emptyList<String>(), port.approved)
            assertEquals("$label: the turn itself is still consumed", 1, port.registered.size)
        }
    }

    // ---- failures are not swallowed -----------------------------------------------------------

    @Test
    fun `a port failure propagates instead of becoming a visible failure line`() {
        val port = FakePort()
        port.throwOnPositiveBridge = IllegalStateException("bridge exploded")
        val thrown = assertThrows(IllegalStateException::class.java) { invoke(port) }
        assertEquals("bridge exploded", thrown.message)
        assertEquals(emptyList<String>(), port.failures)
        assertEquals(emptyList<AssistantImageToolPendingTurn>(), port.registered)
    }

    @Test
    fun `a cancellation from the port stays a cancellation`() {
        val port = FakePort()
        port.throwOnArgumentNegativeBridge = CancellationException("cancelled")
        var rethrown = false
        try {
            invoke(port, calls = listOf(toolCall("""{"prompt":"一只红鸟","negative_prompt":"不要文字"}""")))
        } catch (expected: CancellationException) {
            rethrown = true
        }
        assertTrue("a cancelled turn must not be reported as 图片请求未执行", rethrown)
        assertEquals(emptyList<String>(), port.failures)
    }

    @Test
    fun `a failing timeline write leaves the pending turn registered`() {
        val port = FakePort()
        port.throwOnAttach = IllegalStateException("timeline down")
        assertThrows(IllegalStateException::class.java) { invoke(port) }
        assertEquals(
            listOf("positiveBridge", "snapshot", "register", "attach"),
            port.timeline
        )
        assertEquals(1, port.registered.size)
        assertEquals(emptyList<String>(), port.approved)
    }
}
