package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The owner seam for cloud-model configuration: the seven field setters, saving the chat and image
 * model, persisting the API config, and the API self-test.
 *
 * Eleven members. The collaborators are the two cloud collaborators, the image-model store, the
 * generation-parameter store, and three status helpers. None of them is a lock, a job or a scope --
 * this whole family is a settings form plus two provider writes, and it owns no execution beyond the
 * one self-test launch, whose scope is passed in as a parameter.
 *
 * The user-visible strings are reproduced verbatim -- saving a cloud model is one of the flows where a
 * wrong word changes whether the user believes their key was stored.
 */
internal interface CloudModelConfigPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val cloudApiStore: CloudApiStore

    val cloudChatProvider: OpenAiCompatibleChatProvider

    val localImageModelStore: LocalImageModelStore

    fun loadModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams

    fun persistChatSessions(
        sessions: List<ChatSessionRecord>?,
        knowledgeBinding: Pair<String, Set<String>>? = null
    )

    fun persistGenerationParams(params: GenerationParams)

    fun showStatus(message: String?)

    fun busy(message: String)

    fun fail(message: String)
}

internal fun List<ChatSessionRecord>.bindSession(
        sessionId: String?,
        assistantId: String? = null,
        assistantSnapshot: AssistantConversationSnapshot? = null,
        replaceAssistantSnapshot: Boolean = false,
        modelMode: String? = null,
        modelId: String? = null
    ): List<ChatSessionRecord> {
        if (sessionId == null) return this
        var changed = false
        val updated = map { session ->
            if (session.id != sessionId) {
                session
            } else {
                val owner = session.assistantSnapshot?.assistantId ?: session.assistantId
                if (session.mixedAssistantHistory ||
                    (assistantId != null && owner != assistantId) ||
                    (assistantSnapshot != null && assistantSnapshot.assistantId != owner)
                ) return@map session
                val next = session.copy(
                    assistantId = session.assistantId ?: assistantId,
                    assistantSnapshot = if (replaceAssistantSnapshot) {
                        assistantSnapshot
                    } else {
                        assistantSnapshot ?: session.assistantSnapshot
                    },
                    modelMode = modelMode ?: session.modelMode,
                    modelId = if (modelMode != null) modelId else session.modelId
                )
                if (next != session) changed = true
                next
            }
        }
        return if (changed) updated else this
    }

internal fun List<CloudModelRecord>.matchingCloudModel(
        kind: CloudModelKind,
        format: CloudApiFormat,
        baseUrl: String,
        modelName: String,
        imageFormat: CloudImageApiFormat? = null,
        imageEndpointPath: String = ""
    ): CloudModelRecord? =
        firstOrNull {
            it.kind == kind &&
                it.apiFormat == format &&
                (
                    kind != CloudModelKind.IMAGE ||
                        (
                            it.imageApiFormat == (imageFormat ?: it.imageApiFormat) &&
                                it.imageEndpointPath.trim().trim('/') == imageEndpointPath.trim().trim('/')
                            )
                    ) &&
                it.baseUrl.trim().trimEnd('/') == baseUrl.trim().trimEnd('/') &&
                it.modelName == modelName
        }

internal fun List<CloudModelRecord>.upsertCloudModel(model: CloudModelRecord): List<CloudModelRecord> =
        (listOf(model) + filterNot { it.id == model.id })
            .sortedWith(
                compareBy<CloudModelRecord> { it.kind.name }
                    .thenByDescending { it.updatedAt }
            )

internal fun CloudApiConfig.normalized(): CloudApiConfig =
        copy(
            providerName = providerName.trim().ifBlank { apiFormat.label },
            displayName = displayName.trim().ifBlank { chatModel.trim().ifBlank { "自定义推理引擎" } },
            baseUrl = baseUrl.trim().trimEnd('/'),
            chatModel = chatModel.trim(),
            imageModel = imageModel.trim(),
            imageSize = imageSize.trim().ifBlank { "1024x1024" },
            imageEndpointPath = imageEndpointPath.trim().trim('/')
        ).normalizedForImageRequest(preserveProviderName = true)

    internal fun cloudChatModelGenerationParams(
    port: CloudModelConfigPort,modelId: String): GenerationParams =
        port.loadModelGenerationParams(
            backend = ChatBackend.LOCAL,
            modelId = modelId,
            defaults = port.uiState().params
        )

    internal fun applyCloudSupportsTools(
    port: CloudModelConfigPort,value: Boolean) {
        port.updateUiState { state ->
            state.copy(
                cloudApiConfig = state.cloudApiConfig.copy(
                    supportsTools = value && state.cloudApiConfig.apiFormat == CloudApiFormat.OPENAI_RESPONSES
                )
            )
        }
    }

    internal fun applyCloudImageModel(
    port: CloudModelConfigPort,value: String) {
        port.updateUiState { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageModel = value))
        }
    }

    internal fun applyCloudResponsesReasoning(
    port: CloudModelConfigPort,value: Boolean) {
        port.updateUiState { it.copy(cloudApiConfig = it.cloudApiConfig.copy(responsesReasoningEnabled = value)) }
    }

    internal fun applyCloudImageSize(
    port: CloudModelConfigPort,value: String) {
        port.updateUiState { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageSize = value))
        }
    }

    internal fun applyCloudImageEndpointPath(
    port: CloudModelConfigPort,value: String) {
        port.updateUiState { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageEndpointPath = value))
        }
    }

    internal fun applyCloudDisplayName(
    port: CloudModelConfigPort,value: String) {
        port.updateUiState { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(displayName = value))
        }
    }

    internal fun persistCloudApiConfig(
    port: CloudModelConfigPort,) {
        saveCloudChatModelConfig(port = port)
    }

    internal fun saveCloudChatModelConfig(
    port: CloudModelConfigPort,) {
        val config = port.uiState().cloudApiConfig.normalized()
        if (!config.configured) {
            port.showStatus("请先填写对话推理模型的 Base URL、模型名和必要的 API Key。")
            return
        }
        val editing = port.uiState().cloudModels.firstOrNull {
            it.id == port.uiState().editingCloudModelId && it.kind == CloudModelKind.CHAT
        }
        val record = editing?.copy(
            apiFormat = config.apiFormat,
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.chatModel,
            supportsVision = config.supportsVision,
            supportsTools = config.supportsTools,
            responsesReasoningEnabled = config.responsesReasoningEnabled,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: port.uiState().cloudModels.matchingCloudModel(
            kind = CloudModelKind.CHAT,
            format = config.apiFormat,
            baseUrl = config.baseUrl,
            modelName = config.chatModel
        )?.copy(
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            apiKey = config.apiKey,
            supportsVision = config.supportsVision,
            supportsTools = config.supportsTools,
            responsesReasoningEnabled = config.responsesReasoningEnabled,
            updatedAt = System.currentTimeMillis()
        ) ?: CloudModelRecord(
            kind = CloudModelKind.CHAT,
            apiFormat = config.apiFormat,
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.chatModel,
            supportsVision = config.supportsVision,
            supportsTools = config.supportsTools,
            responsesReasoningEnabled = config.responsesReasoningEnabled,
            imageSize = config.imageSize
        )
        val models = port.uiState().cloudModels.upsertCloudModel(record)
        val selectedChatId = record.id
        port.cloudApiStore.save(config)
        port.cloudApiStore.saveModels(models)
        port.cloudApiStore.saveSelectedCloudChatModelId(selectedChatId)
        port.cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
        val modelParams = port.loadModelGenerationParams(
            backend = ChatBackend.CLOUD,
            modelId = selectedChatId,
            defaults = port.uiState().params
        )
        port.persistGenerationParams(modelParams)
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        port.updateUiState { state ->
            sessionsToPersist = state.chatSessions.bindSession(
                sessionId = state.activeChatSessionId,
                assistantId = state.selectedAssistantId,
                modelMode = ChatBackend.CLOUD.bindingValue(),
                modelId = selectedChatId
            )
            state.copy(
                cloudApiConfig = config,
                cloudModels = models,
                selectedCloudChatModelId = selectedChatId,
                selectedChatBackend = ChatBackend.CLOUD,
                params = modelParams,
                editingCloudModelId = null,
                chatSessions = sessionsToPersist,
                statusMessage = "已保存并加载云端推理模型：${record.displayName}"
            )
        }
        port.persistChatSessions(sessionsToPersist)
    }

    internal fun saveCloudImageModelConfig(
    port: CloudModelConfigPort,) {
        val config = port.uiState().cloudApiConfig.normalized()
        if (!config.imageConfigured) {
            port.showStatus("请先选择支持生图的协议，并填写生图模型、Base URL 和必要的 API Key。")
            return
        }
        val displayName = config.displayName.trim().ifBlank { "${config.providerName} · ${config.imageModel}" }
        val editing = port.uiState().cloudModels.firstOrNull {
            it.id == port.uiState().editingCloudModelId && it.kind == CloudModelKind.IMAGE
        }
        val record = editing?.copy(
            apiFormat = config.apiFormat,
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.imageModel,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: port.uiState().cloudModels.matchingCloudModel(
            kind = CloudModelKind.IMAGE,
            format = config.apiFormat,
            baseUrl = config.baseUrl,
            modelName = config.imageModel,
            imageFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest()
        )?.copy(
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            apiKey = config.apiKey,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: CloudModelRecord(
            kind = CloudModelKind.IMAGE,
            apiFormat = config.apiFormat,
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.imageModel,
            imageSize = config.imageSize
        )
        val models = port.uiState().cloudModels.upsertCloudModel(record)
        port.cloudApiStore.save(config)
        port.cloudApiStore.saveModels(models)
        val selectedImageId = record.id
        port.cloudApiStore.saveSelectedCloudImageModelId(selectedImageId)
        port.localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
        port.updateUiState { state ->
            state.copy(
                cloudApiConfig = config,
                cloudModels = models,
                selectedCloudImageModelId = selectedImageId,
                selectedImageBackend = ImageBackend.CLOUD,
                editingCloudModelId = null,
                statusMessage = "已保存并设为当前图像生成模型：${record.modelName}"
            )
        }
    }

    internal fun runCloudApiConfigTest(
    port: CloudModelConfigPort,
    scope: CoroutineScope,) {
        val config = port.uiState().cloudApiConfig.normalized()
        if (!config.configured) {
            port.showStatus("请先启用云端 API，并填写 Base URL、API Key 和对话推理模型。")
            return
        }
        scope.launch(Dispatchers.IO) {
            port.busy("正在快速测试云端 API...")
            port.cloudChatProvider.quickTest(config)
                .onSuccess {
                    val credential = port.cloudApiStore.save(config)
                    port.updateUiState {
                        it.copy(
                            busy = false,
                            cloudApiConfig = config,
                            // The request succeeded either way, but the API key is only
                            // usable later if it is durably stored. Never claim a completed
                            // save when encryption was unavailable or unverifiable.
                            statusMessage = credentialWriteFailureMessage(credential)
                                ?: "云端 API 快速测试成功：${config.safeDisplayName()}"
                        )
                    }
                }
                .onFailure { error ->
                    port.fail("云端 API 快速测试失败：${error.message ?: "请求失败"}")
                }
        }
    }

internal fun applyCloudApiEnabled(
    port: CloudModelConfigPort,
    enabled: Boolean
) {
    port.updateUiState { state ->
        state.copy(cloudApiConfig = state.cloudApiConfig.copy(enabled = enabled))
    }
}

internal fun applyCloudApiFormat(
    port: CloudModelConfigPort,
    value: String
) {
    val format = CloudApiFormat.entries
        .firstOrNull { it.name == value || it.label == value }
        ?: CloudApiFormat.OPENAI_COMPATIBLE
    val defaultBaseUrls = CloudApiFormat.entries.map { it.defaultBaseUrl }.filter { it.isNotBlank() }
    val defaultChatModels = CloudApiFormat.entries.map { it.defaultModel }.filter { it.isNotBlank() }
    port.updateUiState { state ->
        val current = state.cloudApiConfig
        state.copy(
            cloudApiConfig = current.copy(
                apiFormat = format,
                providerName = format.label,
                displayName = current.displayName.takeUnless {
                    it == current.apiFormat.label || it == current.chatModel || it == "自定义推理引擎"
                }.orEmpty(),
                baseUrl = current.baseUrl.takeUnless { it in defaultBaseUrls }.orEmpty(),
                chatModel = current.chatModel.takeUnless { it in defaultChatModels }.orEmpty()
            )
        )
    }
}

internal fun applyCloudImageApiFormat(
    port: CloudModelConfigPort,
    value: String
) {
    val format = CloudImageApiFormat.from(value)
    val defaultBaseUrls = CloudImageApiFormat.entries.map { it.defaultBaseUrl }.filter { it.isNotBlank() }
    val defaultImageModels = CloudImageApiFormat.entries.map { it.defaultImageModel }.filter { it.isNotBlank() }
    val defaultImagePaths = CloudImageApiFormat.entries.map { it.defaultEndpointPath }.filter { it.isNotBlank() }
    port.updateUiState { state ->
        val current = state.cloudApiConfig
        state.copy(
            cloudApiConfig = current.copy(
                imageApiFormat = format,
                providerName = format.label,
                baseUrl = current.baseUrl.takeUnless { it in defaultBaseUrls }.orEmpty(),
                imageModel = current.imageModel.takeUnless { it in defaultImageModels }.orEmpty(),
                imageEndpointPath = current.imageEndpointPath.takeUnless { it.trim().trim('/') in defaultImagePaths }.orEmpty(),
                imageSize = current.imageSize.takeUnless { it == "1024x1024" }.orEmpty()
            )
        )
    }
}

internal fun applyCloudProviderPreset(
    port: CloudModelConfigPort,
    value: String
) {
    val preset = findCloudProviderPreset(value) ?: return
    port.updateUiState { state ->
        state.copy(
            cloudApiConfig = state.cloudApiConfig.copy(
                enabled = true,
                apiFormat = preset.format,
                providerName = preset.providerName,
                displayName = preset.displayName,
                baseUrl = preset.baseUrl,
                chatModel = preset.chatModel
            ),
            statusMessage = "已选择 ${preset.providerName}，请填写自定义 Base URL、模型名和 API Key。"
        )
    }
}

internal fun applyBeginAddCloudModel(
    port: CloudModelConfigPort,
    kind: String
) {
    val modelKind = runCatching { CloudModelKind.valueOf(kind) }.getOrDefault(CloudModelKind.CHAT)
    val current = port.uiState().cloudApiConfig
    port.updateUiState { state ->
        state.copy(
            editingCloudModelId = null,
            cloudApiConfig = CloudApiConfig(
                enabled = true,
                apiFormat = current.apiFormat,
                imageApiFormat = current.imageApiFormat,
                displayName = "",
                providerName = if (modelKind == CloudModelKind.IMAGE) current.imageApiFormat.label else current.apiFormat.label,
                baseUrl = "",
                apiKey = "",
                chatModel = "",
                imageModel = "",
                imageEndpointPath = "",
                imageSize = ""
            )
        )
    }
}

internal fun applyEditCloudModel(
    port: CloudModelConfigPort,
    modelId: String
) {
    val model = port.uiState().cloudModels.firstOrNull { it.id == modelId } ?: return
    port.updateUiState { state ->
        state.copy(
            editingCloudModelId = model.id,
            cloudApiConfig = when (model.kind) {
                CloudModelKind.CHAT -> model.toChatConfig()
                CloudModelKind.IMAGE -> model.toImageConfig()
            },
            statusMessage = "正在编辑：${model.displayName}"
        )
    }
}

internal fun applyCloudBaseUrl(
    port: CloudModelConfigPort,
    value: String
) {
    port.updateUiState { state ->
        state.copy(cloudApiConfig = state.cloudApiConfig.copy(baseUrl = value))
    }
}

internal fun applyCloudApiKey(
    port: CloudModelConfigPort,
    value: String
) {
    port.updateUiState { state ->
        state.copy(cloudApiConfig = state.cloudApiConfig.copy(apiKey = value))
    }
}

internal fun applyCloudChatModel(
    port: CloudModelConfigPort,
    value: String
) {
    port.updateUiState { state ->
        state.copy(cloudApiConfig = state.cloudApiConfig.copy(chatModel = value))
    }
}

internal fun applyCloudSupportsVision(
    port: CloudModelConfigPort,
    value: Boolean
) {
    port.updateUiState { state ->
        state.copy(cloudApiConfig = state.cloudApiConfig.copy(supportsVision = value))
    }
}

internal fun selectCloudImageModelFor(
    port: CloudModelConfigPort,
    modelId: String
) {
    val model = port.uiState().cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.IMAGE }
    if (model == null) {
        port.showStatus("未找到图像生成模型")
        return
    }
    port.cloudApiStore.saveSelectedCloudImageModelId(model.id)
    port.localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
    port.updateUiState {
        it.copy(
            selectedCloudImageModelId = model.id,
            selectedImageBackend = ImageBackend.CLOUD,
            cloudApiConfig = model.toImageConfig(),
            statusMessage = "图片页已切换到：${model.modelName}"
        )
    }
}

internal const val CLOUD_REASONING_LOCKED_MESSAGE = "云端模型的思考模式由服务商和具体模型决定，MCA 会默认按开启处理；如需关闭或调整，请在云端模型配置中设置相关参数。"

internal fun updateReasoningModeFor(
    state: MainUiState,
    application: Application,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    uiStateStore: UiStateStore,
    showStatus: (String) -> Unit,
    mode: ReasoningMode
) {
    if (state.selectedChatBackend == ChatBackend.CLOUD) {
        showStatus(CLOUD_REASONING_LOCKED_MESSAGE)
        return
    }
    val updatedParams = state.params.copy(
        reasoningMode = mode,
        hideReasoning = mode == ReasoningMode.OFF
    ).let { params ->
        params.copy(nPredict = params.effectiveNPredict())
    }
    GenerationPreferences.persistGenerationParams(application, updatedParams)
    GenerationPreferences.persistModelGenerationParams(
        profileStore = modelGenerationProfileStore,
        backend = state.selectedChatBackend,
        modelId = state.currentChatModelId(),
        params = updatedParams
    )
    uiStateStore.update {
        it.copy(
            params = updatedParams,
            statusMessage = "思考模式已切换为：${mode.label}"
        )
    }
}

