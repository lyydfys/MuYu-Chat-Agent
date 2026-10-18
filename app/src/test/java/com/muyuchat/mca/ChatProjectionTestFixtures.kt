package com.muyuchat.mca

import com.muyuchat.core.deviceprofile.DeviceAccelerationProfile
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.QnnRuntimeProbeState
import com.muyuchat.core.deviceprofile.QnnRuntimeStatus
import com.muyuchat.core.deviceprofile.ThermalStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.ChatUiState
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import java.util.Locale

/**
 * P02 projection fixtures shared by the reuse / invalidation / equivalence tests.
 *
 * Every cached domain (assistants, local models, images, files, image jobs, world books, knowledge
 * bases, LoRAs, textual inversions, upscalers, context summaries) is populated with a small but
 * representative record so a cache hit cannot be confused with an empty projection.
 */
internal data class ChatProjectionFixture(
    val state: MainUiState,
    val locale: Locale = Locale.US,
    val imageGenerationHistoryById: Map<String, ImageGenerationHistoryMetadata?> = emptyMap(),
    /** Per-model results of the injected `generationParamsForChatModel` function. */
    val modelParams: Map<String, GenerationParams> = emptyMap(),
    val preparedImageUi: Map<String, PreparedLocalImageUi> = emptyMap(),
    val sessionPresentations: Map<String, AssistantSessionPresentation> = emptyMap()
) {
    fun project(cache: ChatUiProjectionCache): ChatUiState = buildChatUiState(
        state = state,
        sessionPresentations = sessionPresentations,
        timestampLocale = locale,
        imageGenerationHistoryById = imageGenerationHistoryById,
        assistantImageToolPermission = ChatProjectionFixtures.permission,
        preparedImageUi = preparedImageUi,
        generationParamsForChatModel = { modelId ->
            // A fresh lambda on every call: the local-model cache may only depend on the returned
            // values, never on the function identity itself.
            modelParams[modelId] ?: ChatProjectionFixtures.defaultModelParams
        },
        projectionCache = cache
    )
}

internal object ChatProjectionFixtures {
    val permission = ChatImageToolPermissionUiState(
        available = false,
        unavailableReason = "测试用不可用原因",
        autoApproval = false,
        canChange = true
    )

    val defaultModelParams: GenerationParams = GenerationParams(
        temperature = 0.5f,
        advancedJson = "{}"
    )

    const val LOADED_MODEL_ID = "model-loaded"
    const val IDLE_MODEL_ID = "model-idle"
    const val LITERT_MODEL_ID = "model-litert"
    const val ASSISTANT_ID = "assistant-a"
    const val SESSION_ID = "session-a"
    const val IMAGE_ID = "image-1"
    const val LORA_ID = "lora-1"
    const val TEXTUAL_INVERSION_ID = "11111111-1111-4111-8111-111111111111"
    const val UPSCALER_ID = "upscaler-1"

    fun fixture(): ChatProjectionFixture {
        val loadedModel = model(LOADED_MODEL_ID, "本地已加载", ChatModelRuntime.LLAMA_CPP, lastLoadedAt = 1L)
        val idleModel = model(IDLE_MODEL_ID, "本地待加载", ChatModelRuntime.MNN, lastLoadedAt = null)
        val litertModel = model(LITERT_MODEL_ID, "LiteRT 模型", ChatModelRuntime.LITERT_LM, lastLoadedAt = null)
        val localModels = listOf(loadedModel, idleModel, litertModel)

        val cloudChat = cloudModel(
            id = "cloud-chat",
            kind = CloudModelKind.CHAT,
            displayName = "云端聊天",
            modelName = "gpt-test",
            updatedAt = 2L
        )
        val cloudImage = cloudModel(
            id = "cloud-image",
            kind = CloudModelKind.IMAGE,
            displayName = "云端生图",
            modelName = "image-test",
            updatedAt = 1L
        )

        val assistantA = AssistantRecord(
            id = ASSISTANT_ID,
            name = "角色甲",
            avatar = "avatar-a",
            tag = "标签",
            defaultModelMode = "local",
            defaultModelId = LOADED_MODEL_ID,
            paramsJson = GenerationParams(temperature = 0.8f).toAssistantGenerationJson(),
            characterCardJson = "{\"name\":\"角色甲\"}",
            memoryEnabled = true,
            webSearchEnabled = true
        )
        val assistantB = AssistantRecord(
            id = "assistant-b",
            name = "角色乙",
            defaultModelMode = "cloud",
            defaultModelId = "cloud-chat",
            paramsJson = GenerationParams(topK = 40).toAssistantGenerationJson()
        )

        val sessionA = ChatSessionRecord(
            id = SESSION_ID,
            title = "会话 A",
            messages = listOf(ChatMessage(Role.USER, "你好"), ChatMessage(Role.ASSISTANT, "你好呀")),
            assistantId = ASSISTANT_ID,
            updatedAt = 1_704_067_200_000L,
            contextSummaries = listOf(contextSummary())
        )
        val sessionB = ChatSessionRecord(
            id = "session-b",
            title = "会话 B",
            messages = emptyList(),
            updatedAt = 1_704_067_260_000L
        )

        val imageHistory = mapOf<String, ImageGenerationHistoryMetadata?>(
            IMAGE_ID to ImageGenerationHistoryMetadata(
                backend = ImageBackend.CLOUD,
                modelId = "cloud-image",
                modelName = "云端生图",
                requestPrompt = "一只猫",
                options = LocalImageGenerationOptions(
                    width = 1024,
                    height = 1024,
                    steps = 20,
                    cfgScale = 7.0,
                    seed = 42,
                    sampleMethod = "euler",
                    batchCount = 1
                ),
                inputDraft = LocalImageInputDraft()
            ),
            "image-2" to null
        )

        val state = MainUiState(
            messages = listOf(ChatMessage(Role.USER, "你好")),
            chatSessions = listOf(sessionA, sessionB),
            activeChatSessionId = SESSION_ID,
            images = listOf(
                ImageAssetRecord(
                    id = IMAGE_ID,
                    name = "图片一.png",
                    uriString = "content://images/1",
                    source = "generated",
                    prompt = "一只猫",
                    createdAt = 1_704_067_200_000L,
                    sizeBytes = 2_048L,
                    width = 1024,
                    height = 1024,
                    favorite = true
                ),
                ImageAssetRecord(
                    id = "image-2",
                    name = "图片二.png",
                    uriString = "content://images/2",
                    source = "uploaded",
                    createdAt = 1_704_067_260_000L
                )
            ),
            files = listOf(
                FileAssetRecord(
                    id = "file-1",
                    name = "笔记.txt",
                    text = "记事",
                    preview = "记事",
                    createdAt = 1_704_067_200_000L,
                    sizeBytes = 2_048L
                )
            ),
            imageJobs = listOf(imageJob()),
            assistants = listOf(assistantA, assistantB),
            selectedAssistantId = ASSISTANT_ID,
            models = localModels,
            loadedModelId = LOADED_MODEL_ID,
            loadedModelName = loadedModel.displayName,
            cloudModels = listOf(cloudChat, cloudImage),
            selectedChatBackend = ChatBackend.LOCAL,
            selectedCloudChatModelId = cloudChat.id,
            selectedImageBackend = ImageBackend.LOCAL,
            selectedCloudImageModelId = cloudImage.id,
            params = GenerationParams(
                temperature = 0.7f,
                topP = 0.9f,
                advancedJson = "{}"
            ),
            stats = RuntimeStats(
                loaded = true,
                backend = "llama_cpp",
                backendDevices = "[]",
                gpuOffloadSupported = false
            ),
            engineLifecycle = AgentEngineLifecycle.READY,
            deviceProfile = deviceProfile(),
            worldBooks = listOf(
                WorldBookRecord(
                    id = "world-global",
                    name = "全局世界书",
                    scope = WorldBookScope.GLOBAL,
                    entries = listOf(
                        WorldBookEntry(content = "全局设定", constant = true),
                        WorldBookEntry(content = "关键词设定", keys = listOf("关键词"))
                    )
                ),
                WorldBookRecord(
                    id = "world-assistant",
                    name = "角色世界书",
                    scope = WorldBookScope.ASSISTANT,
                    assistantId = ASSISTANT_ID,
                    entries = listOf(WorldBookEntry(content = "角色设定"))
                ),
                WorldBookRecord(
                    id = "world-chat",
                    name = "会话世界书",
                    scope = WorldBookScope.CHAT,
                    chatSessionId = SESSION_ID,
                    entries = listOf(WorldBookEntry(content = "会话设定"))
                )
            ),
            knowledgeBases = listOf(
                KnowledgeBaseRecord(id = "kb-1", name = "知识库一", description = "说明一"),
                KnowledgeBaseRecord(id = "kb-2", name = "知识库二")
            ),
            knowledgeDocumentCounts = mapOf("kb-1" to 3),
            knowledgeBaseImportingIds = setOf("kb-2"),
            selectedKnowledgeBaseIds = setOf("kb-1"),
            localImageLoras = listOf(
                LocalImageLoraRecord(
                    id = LORA_ID,
                    name = "风格 LoRA",
                    fileName = "$LORA_ID.safetensors",
                    path = "C:/images/loras/$LORA_ID.safetensors",
                    sha256 = "a".repeat(64),
                    sizeBytes = 1_024L,
                    importedAt = 1L
                )
            ),
            activeLocalImageLoraIds = setOf(LORA_ID),
            localImageTextualInversions = listOf(
                TextualInversionArtifact(
                    id = TEXTUAL_INVERSION_ID,
                    name = "风格词",
                    trigger = "mystyle",
                    fileName = "$TEXTUAL_INVERSION_ID.bin",
                    path = "C:/images/ti/$TEXTUAL_INVERSION_ID.bin",
                    sha256 = "c".repeat(64),
                    sizeBytes = 1_024L,
                    format = TextualInversionFormat.BINARY,
                    importedAt = 1L
                )
            ),
            activeLocalImageTextualInversionIds = setOf(TEXTUAL_INVERSION_ID),
            localImageUpscalers = listOf(
                LocalImageUpscalerRecord(
                    id = UPSCALER_ID,
                    name = "放大模型",
                    fileName = "$UPSCALER_ID.pth",
                    path = "C:/images/upscalers/$UPSCALER_ID.pth",
                    sha256 = "b".repeat(64),
                    sizeBytes = 2_048L,
                    importedAt = 1L
                )
            ),
            selectedLocalImageUpscalerId = UPSCALER_ID,
            activeLocalImageUpscalerId = UPSCALER_ID
        )
        return ChatProjectionFixture(
            state = state,
            locale = Locale.US,
            imageGenerationHistoryById = imageHistory,
            modelParams = emptyMap()
        )
    }

    fun model(
        id: String,
        displayName: String,
        runtime: ChatModelRuntime,
        lastLoadedAt: Long? = null
    ): ModelManifest = ModelManifest(
        id = id,
        displayName = displayName,
        path = "C:/models/$id.gguf",
        runtime = runtime,
        source = ModelSource.LOCAL,
        fileName = "$id.gguf",
        sizeBytes = 1L,
        sha256 = "0".repeat(64),
        quant = "Q4",
        createdAt = 1L,
        lastLoadedAt = lastLoadedAt
    )

    fun cloudModel(
        id: String,
        kind: CloudModelKind,
        displayName: String,
        modelName: String,
        updatedAt: Long
    ): CloudModelRecord = CloudModelRecord(
        id = id,
        kind = kind,
        apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
        providerName = "OpenAI",
        displayName = displayName,
        baseUrl = "https://api.example.com/v1",
        apiKey = "test-key",
        modelName = modelName,
        createdAt = 1L,
        updatedAt = updatedAt
    )

    fun contextSummary(
        id: String = "summary-1",
        text: String = "此前对话摘要",
        active: Boolean = true
    ): ContextSummaryRecord = ContextSummaryRecord(
        id = id,
        version = 1,
        source = "deterministic",
        text = text,
        sourceMessageIds = listOf("message-1"),
        sourceFingerprint = "source-fingerprint",
        scopeFingerprint = "scope-fingerprint",
        createdAt = 1_704_067_200_000L,
        active = active,
        summaryModelIdentity = "current",
        summaryDiagnostic = "ok"
    )

    private fun imageJob(): ImageGenerationJobRecord {
        val cloudConfig = CloudApiConfig(
            enabled = true,
            baseUrl = "https://api.example.com/v1",
            apiKey = "test-key",
            chatModel = "gpt-test",
            imageModel = "image-test"
        )
        return ImageGenerationJobRecord(
            id = "job-1",
            prompt = "画一只猫",
            status = ImageGenerationStatusRecord.DONE,
            backend = ImageBackend.CLOUD,
            modelId = "cloud-image",
            modelName = "云端生图",
            spec = ImageGenerationJobSpec(
                prompt = "画一只猫",
                backend = ImageBackend.CLOUD,
                localModelSnapshot = null,
                cloudConfigSnapshot = cloudConfig,
                modelId = "cloud-image",
                modelName = "云端生图",
                inputDraft = LocalImageInputDraft(taskMode = LocalImageTaskMode.TEXT_TO_IMAGE),
                options = LocalImageGenerationOptions(batchCount = 2),
                chatSessionId = SESSION_ID,
                chatMessageId = "message-1"
            ),
            imageAssetIds = listOf(IMAGE_ID),
            completedProgressCount = 1,
            previewUriString = "content://preview/1",
            previewMode = "vae",
            previewStep = 3,
            previewRevision = 4L,
            previewWidth = 512,
            previewHeight = 512,
            startedAtMillis = 1_704_067_200_000L,
            startedAtElapsedMs = 100L
        )
    }

    fun npuAcceleration(
        chipsetCode: String = "SM8850",
        usable: Boolean = true
    ): DeviceAccelerationProfile = DeviceAccelerationProfile.CpuOnly.copy(
        chipsetCode = chipsetCode,
        qnnRuntime = if (usable) {
            QnnRuntimeStatus(
                qnnSystemLibraryPresent = true,
                qnnHtpLibraryPresent = true,
                htpSkelLibraryPresent = true,
                htpStubLibraryPresent = true,
                probeState = QnnRuntimeProbeState.LOADABLE,
                htpArchVersion = 81,
                preferredHtpArchVersion = 81
            )
        } else {
            QnnRuntimeStatus.Missing
        }
    )

    fun deviceProfile(
        acceleration: DeviceAccelerationProfile = npuAcceleration()
    ): DeviceProfile = DeviceProfile(
        socManufacturer = "Qualcomm",
        socModel = "SM8850",
        socFamily = SocFamily.Snapdragon,
        cpuCores = 8,
        estimatedBigCores = 4,
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 8L * 1024 * 1024 * 1024,
        storageFreeBytes = 32L * 1024 * 1024 * 1024,
        androidApi = 35,
        thermalStatus = ThermalStatus.None,
        batteryPercent = 80,
        isCharging = false,
        supportedAbis = listOf("arm64-v8a"),
        primaryAbi = "arm64-v8a",
        accelerationProfile = acceleration
    )

    /** Image generation UI options covering every field the old inline lambda mapped. */
    fun imageUiOptions(
        loras: List<com.muyuchat.feature.chat.ImageGenerationUiLoraSelection>
    ) = com.muyuchat.feature.chat.ImageGenerationUiOptions(
        taskMode = ImageGenerationUiTaskMode.IMG2IMG,
        negativePrompt = "模糊",
        inputImageUri = "content://input/image",
        maskImageUri = "content://input/mask",
        controlImageUri = "content://input/control",
        strength = 0.6,
        controlStrength = 0.4,
        clipSkip = 2,
        batchCount = 3,
        loras = loras,
        vaeTileSize = 512,
        vaeTileOverlap = 0.3,
        width = 768,
        height = 1024,
        steps = 24,
        cfgScale = 6.5,
        seed = 123,
        sampleMethod = "euler",
        previewMode = com.muyuchat.feature.chat.ImageGenerationUiPreviewMode.VAE,
        previewInterval = 2,
        textualInversionIds = listOf(TEXTUAL_INVERSION_ID),
        ultraFix = com.muyuchat.feature.chat.ImageGenerationUiUltraFixOptions(
            targetWidth = 1024,
            targetHeight = 1024,
            strength = 0.35,
            inversionSteps = 4,
            refinementSteps = 10,
            tileSize = 512,
            overlap = 0.25
        )
    )
}
