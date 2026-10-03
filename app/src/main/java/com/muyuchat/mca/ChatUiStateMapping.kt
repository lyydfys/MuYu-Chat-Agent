package com.muyuchat.mca

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.core.designsystem.SystemBackMotionHandler
import com.muyuchat.core.deviceprofile.AccelerationCapabilityStatus
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.feature.agent.AgentDecisionItem
import com.muyuchat.feature.agent.AgentScreen
import com.muyuchat.feature.agent.AgentUiState
import com.muyuchat.feature.agent.BenchmarkHistoryItem
import com.muyuchat.feature.agent.TuningTrialItem
import com.muyuchat.feature.chat.AssistantEditorDraft
import com.muyuchat.feature.chat.AssistantMemoryUiItem
import com.muyuchat.feature.chat.AssistantUiItem
import com.muyuchat.feature.chat.ChatBackendFamily
import com.muyuchat.feature.chat.ChatBackgroundScaleMode as UiChatBackgroundScaleMode
import com.muyuchat.feature.chat.ChatBackgroundScope
import com.muyuchat.feature.chat.ChatBackgroundState
import com.muyuchat.feature.chat.ChatHistoryItem
import com.muyuchat.feature.chat.ChatModelChoice
import com.muyuchat.feature.chat.ChatScreen
import com.muyuchat.feature.chat.ChatUiState
import com.muyuchat.feature.chat.ConsumeImeBackHandler
import com.muyuchat.feature.chat.ContextSummaryModelOption
import com.muyuchat.feature.chat.ContextSummaryUiItem
import com.muyuchat.feature.chat.FileAssetUiItem
import com.muyuchat.feature.chat.ImageAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiJob
import com.muyuchat.feature.chat.ImageGenerationUiLoraSelection
import com.muyuchat.feature.chat.ImageGenerationUiOptions
import com.muyuchat.feature.chat.ImageGenerationUiPreset
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import com.muyuchat.feature.chat.ImageLibraryBackupUiState
import com.muyuchat.feature.chat.ImageLoraUiItem
import com.muyuchat.feature.chat.ImageTextualInversionUiItem
import com.muyuchat.feature.chat.ImageUpscaleUiJob
import com.muyuchat.feature.chat.ImageUpscalerUiItem
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import com.muyuchat.feature.chat.InternalBrowserDialog
import com.muyuchat.feature.chat.KnowledgeBaseUiItem
import com.muyuchat.feature.chat.WorldBookImportScope
import com.muyuchat.feature.chat.WorldBookUiItem
import com.muyuchat.feature.chat.chatBackendFamilyForRuntime
import com.muyuchat.feature.chat.chatBackendOptionsFor
import com.muyuchat.feature.chat.npuAvailabilityForChatBackend
import com.muyuchat.feature.chat.selectedChatBackendId
import com.muyuchat.feature.chat.withChatBackend
import com.muyuchat.feature.modelhub.CloudApiUiState
import com.muyuchat.feature.modelhub.CloudModelUiItem
import com.muyuchat.feature.modelhub.CloudProviderPresetUi
import com.muyuchat.feature.modelhub.LocalImageModelUiItem
import com.muyuchat.feature.modelhub.ModelHubScreen
import com.muyuchat.feature.modelhub.ModelHubUiState
import com.muyuchat.feature.settings.AppUpdateSettingsUiState
import com.muyuchat.feature.settings.LocalApiToolScreen
import com.muyuchat.feature.settings.SettingsHubScreen
import com.muyuchat.feature.settings.SettingsUiState
import com.muyuchat.feature.settings.WebSearchBackupProviderDraft
import com.muyuchat.feature.settings.WebSearchBackupProviderUiState
import com.muyuchat.feature.settings.WebSearchDiagnosticSourceUiItem
import com.muyuchat.feature.settings.WebSearchDiagnosticUiItem
import com.muyuchat.feature.settings.WebSearchSettingsDraft
import com.muyuchat.feature.settings.WebSearchSettingsUiState
import com.muyuchat.mca.presentation.VisionCapabilityUi
import com.muyuchat.mca.presentation.chatVisionCapability
import com.muyuchat.mca.presentation.cloudApiFormats
import com.muyuchat.mca.presentation.cloudImageApiFormats
import com.muyuchat.mca.presentation.cloudProviderPresets
import com.muyuchat.mca.presentation.deviceAccelerationSummary
import com.muyuchat.mca.presentation.deviceImagePolicy
import com.muyuchat.mca.presentation.deviceImageTierKey
import com.muyuchat.mca.presentation.existingImageModelChoiceIds
import com.muyuchat.mca.presentation.formatAppUpdateBytes
import com.muyuchat.mca.presentation.formatAssetBytes
import com.muyuchat.mca.presentation.imageModelPresetsFor
import com.muyuchat.mca.presentation.imageSizePresetsFor
import com.muyuchat.mca.presentation.liteRtVisionInputReadyForUi
import com.muyuchat.mca.presentation.liteRtVisionVerifiedReadyForUi
import com.muyuchat.mca.presentation.settingsUiState
import com.muyuchat.mca.presentation.toAppearance
import com.muyuchat.mca.presentation.toAppearanceScope
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions
import com.muyuchat.mca.presentation.toTuningTrialItems
import com.muyuchat.mca.presentation.toUiState
import com.muyuchat.mca.presentation.toWebSearchBackupConfigs
import com.muyuchat.mca.presentation.toWebSearchTurnModeLabel
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 把应用级 [MainUiState] 映射为聊天页的 [ChatUiState]。
 *
 * **P3-a：依赖边界划分**（不只是把文件搬走）。原块位于 `MainActivity.kt:504-1104`（601 行），
 * 是 composition root 里最大的一块业务状态映射。搬出后依赖收窄为 **6 个显式参数**，
 * 本函数**不再持有 MainViewModel**：
 *
 * - `sessionPresentations` / `imageGenerationHistoryById`：调用方 `remember` 记忆化后传入
 *   ⇒ 保持既有性能特征，同时不再捕获 Composable 作用域。
 * - `timestampLocale`：`LocalLocale` 环境输入。
 * - `assistantImageToolPermission`：原 `remember { viewModel.chatImageToolPermissionUiState() }`
 *   ⇒ 收窄为一个值。
 * - preparedImageUi：原 produceState<Map<String, PreparedLocalImageUi>>（异步 IO 派生）⇒ 传值。
 * - `generationParamsForChatModel`：原 `viewModel.generationParamsForChatModel(...)` 直调
 *   （块内 2 处）⇒ 收窄为一个 `(String) -> GenerationParams`。
 *
 * 三个**纯派生**改为在函数内计算（原本也是每次组合重算，语义不变）：
 * `activeChatSession`、`chatVisionCapability`、`generationHistoryInputUris`。
 *
 * 非 @Composable：已核对原块内**无任何 Composable 专属调用**。
 *
 * 行为等值：逐字段来源未改；仅去掉 `viewModel.` 前缀（改为参数调用）并把上述派生内联。
 */
internal fun buildChatUiState(
    state: MainUiState,
    sessionPresentations: Map<String, AssistantSessionPresentation>,
    timestampLocale: java.util.Locale,
    imageGenerationHistoryById: Map<String, ImageGenerationHistoryMetadata?>,
    assistantImageToolPermission: ChatImageToolPermissionUiState,
    preparedImageUi: Map<String, PreparedLocalImageUi>,
    generationParamsForChatModel: (String) -> GenerationParams
): ChatUiState {
    val chatVisionCapability = state.chatVisionCapability()
    val generationHistoryInputUris = retainedGenerationImageContentReferences(
        historyReferences = imageGenerationHistoryById.values
        .filterNotNull()
            .flatMapTo(mutableSetOf()) { history -> history.requiredContentInputReferences() },
        jobInputDrafts = state.imageJobs.mapNotNull { job -> job.spec?.inputDraft }
    )
    val activeChatSession = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
    return ChatUiState(
                    pendingImageTranslationDraft = state.pendingChatImageTranslationDraft,
                    pendingImageActionDraft = state.pendingChatImageActionDraft,
                    browserTask = state.browserTask,
                    modelLoadMessage = state.modelLoadStage.takeIf { state.busy && state.engineLifecycle == com.muyuchat.feature.agent.AgentEngineLifecycle.LOADING },
                    modelReadinessLabel = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        null
                    } else {
                        state.localChatReadinessLabel()
                    },
                    messages = state.messages,
                    history = state.chatSessions.map { session ->
                        val presentation = sessionPresentations[session.id]
                        ChatHistoryItem(
                            id = session.id,
                            title = session.title,
                            displayName = presentation?.name ?: session.title,
                            summary = presentation?.summary.orEmpty(),
                            assistantId = presentation?.assistantId,
                            updatedAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
                                .format(java.util.Date(session.updatedAt)),
                            updatedAtMillis = session.updatedAt,
                            messageCount = session.messages.size,
                            pinned = session.pinned,
                            selected = session.id == state.activeChatSessionId,
                            mixedAssistantHistory = session.mixedAssistantHistory
                        )
                    },
                    localModels = buildList {
                        addAll(
                            state.cloudModels
                                .filter { it.kind == CloudModelKind.CHAT && it.configured }
                                .sortedByDescending { it.updatedAt }
                                .map { model ->
                                ChatModelChoice(
                                    id = MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + model.id,
                                    displayName = model.displayName.ifBlank { model.modelName },
                                    quant = "云端推理",
                                    loaded = state.selectedChatBackend == ChatBackend.CLOUD && model.id == state.selectedCloudChatModelId,
                                    subtitle = model.apiFormat.label,
                                    cloud = true
                                )
                            }
                        )
                        addAll(
                            state.models
                                .sortedWith(
                                    compareByDescending<com.muyuchat.core.modelstore.ModelManifest> {
                                        if (it.id == state.loadedModelId) 1 else 0
                                    }
                                        .thenByDescending { it.lastLoadedAt ?: it.createdAt }
                                )
                                .map { model ->
                                    val backendFamily = chatBackendFamilyForRuntime(model.runtime.storageValue)
                                    val modelIsLoaded = state.isLocalChatModelLoaded(model.id)
                                    val acceleration = state.deviceProfile?.accelerationProfile
                                    val npuAvailable: Boolean? = when (backendFamily) {
                                        // LiteRT Qualcomm assets are staged on
                                        // first NPU load, so the generic QNN
                                        // inspector may still be looking at
                                        // GenieX's APK runtime here.  Treat an
                                        // exact packaged V73/V75/V79/V81
                                        // transport as actionable and let the
                                        // LiteRT native load perform the final
                                        // graph compatibility check.
                                        ChatBackendFamily.LITERT_LM,
                                        ChatBackendFamily.QAIRT -> npuAvailabilityForChatBackend(
                                            family = backendFamily,
                                            chipsetCode = acceleration?.chipsetCode,
                                            qnnRuntimeUsableForSmoke = acceleration?.qnnRuntime?.usableForSmoke,
                                            packagedLiteRtTransportAvailable =
                                                backendFamily == ChatBackendFamily.LITERT_LM &&
                                                    LiteRtQualcommRuntimeStager.variantForSocModel(
                                                        acceleration?.chipsetCode
                                                    ) != null
                                        )
                                        else -> null
                                    }
                                    val backendOptions = chatBackendOptionsFor(
                                        family = backendFamily,
                                        npuAvailable = npuAvailable,
                                        gpuAvailable = if (
                                            backendFamily == ChatBackendFamily.LLAMA_CPP && modelIsLoaded
                                        ) {
                                            state.stats.gpuOffloadSupported
                                        } else {
                                            null
                                        }
                                    )
                                    val selectedBackend = selectedChatBackendId(
                                        family = backendFamily,
                                        params = if (modelIsLoaded) {
                                            state.params
                                        } else {
                                            generationParamsForChatModel(model.id)
                                        },
                                        stats = if (model.id == state.loadedModelId) state.stats else com.muyuchat.core.engine.RuntimeStats()
                                    )
                                    val customGpuLayers = if (backendFamily == ChatBackendFamily.LLAMA_CPP) {
                                        LlamaAdvancedParams.parse(
                                            if (modelIsLoaded) state.params.advancedJson
                                            else generationParamsForChatModel(model.id).advancedJson
                                        ).params?.nGpuLayers?.takeIf { it >= 0 }
                                    } else {
                                        null
                                    }
                                    ChatModelChoice(
                                        id = model.id,
                                        displayName = model.displayName,
                                        quant = model.quant,
                                        sizeBytes = model.sizeBytes,
                                        loaded = modelIsLoaded,
                                        subtitle = listOfNotNull(
                                            model.runtime.label,
                                            state.localChatReadinessLabel().takeIf {
                                                state.selectedChatBackend == ChatBackend.LOCAL &&
                                                    model.id == state.loadedModelId
                                            }
                                        ).joinToString(" · "),
                                        backendOptions = backendOptions,
                                        selectedBackendId = selectedBackend,
                                        customGpuLayers = customGpuLayers
                                    )
                                }
                        )
                    },
                    imageModels = buildList {
                        addAll(
                            state.localImageModels
                                .sortedByDescending { if (it.id == state.selectedLocalImageModelId) 1 else 0 }
                                .mapNotNull { model ->
                                    val prepared = preparedImageUi[model.path] ?: return@mapNotNull null
                                    val imageCapabilities = prepared.capabilities ?: return@mapNotNull null
                                    val readiness = prepared.readiness
                                    val readinessLabel = prepared.label
                                    val imageDefaults = imageCapabilities.executionDefaults
                                    ChatModelChoice(
                                        id = MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.displayName,
                                        quant = if (readiness == null) "本地生图" else readinessLabel,
                                        sizeBytes = model.sizeBytes,
                                        loaded = state.selectedImageBackend == ImageBackend.LOCAL && model.id == state.selectedLocalImageModelId,
                                        subtitle = if (readiness == null) {
                                            "${model.family.label} · ${model.runtime.label}"
                                        } else {
                                            "${model.family.label} · $readinessLabel"
                                        },
                                        cloud = false,
                                        supportedImageTaskModes = imageCapabilities.supportedTaskModes,
                                        supportsImageNegativePrompt = imageCapabilities.supportsNegativePrompt,
                                        supportsImageClipSkip = imageCapabilities.supportsClipSkip,
                                        supportsImageVaeTiling = imageCapabilities.supportsVaeTiling,
                                        supportsImageTextualInversion = imageCapabilities.supportsTextualInversion,
                                        supportedImageTextualInversionFormats =
                                            imageCapabilities.supportedTextualInversionFormats,
                                        supportsImageUltraFix = imageCapabilities.supportsUltraFix,
                                        supportsImageLora = imageCapabilities.supportsLora,
                                        isQwenImage21Gguf = imageCapabilities.isQwenImage21Gguf,
                                        supportsImageLivePreview = imageCapabilities.supportsLivePreview,
                                        imagePreviewMode = imageCapabilities.previewMode,
                                        imageDefaultPreviewInterval = imageCapabilities.defaultPreviewInterval,
                                        maxImageBatchCount = imageCapabilities.maxBatchCount,
                                        imageDefaultWidth = imageDefaults.width,
                                        imageDefaultHeight = imageDefaults.height,
                                        imageDefaultSteps = imageDefaults.steps,
                                        imageMinSteps = imageDefaults.minSteps,
                                        imageMaxSteps = imageDefaults.maxSteps,
                                        imageDefaultCfgScale = imageDefaults.cfgScale,
                                        imageDefaultSeed = imageDefaults.seed,
                                        imageDefaultSampler = imageDefaults.sampler,
                                        imageMinWidth = imageDefaults.minWidth,
                                        imageMaxWidth = imageDefaults.maxWidth,
                                        imageMinHeight = imageDefaults.minHeight,
                                        imageMaxHeight = imageDefaults.maxHeight,
                                        imageWidthMultiple = imageDefaults.widthMultiple,
                                        imageHeightMultiple = imageDefaults.heightMultiple,
                                        imageUltraFixMinWidth = imageDefaults.ultraFixMinWidth,
                                        imageUltraFixMaxWidth = imageDefaults.ultraFixMaxWidth,
                                        imageUltraFixMinHeight = imageDefaults.ultraFixMinHeight,
                                        imageUltraFixMaxHeight = imageDefaults.ultraFixMaxHeight,
                                        imageUltraFixWidthMultiple = imageDefaults.ultraFixWidthMultiple,
                                        imageUltraFixHeightMultiple = imageDefaults.ultraFixHeightMultiple,
                                        // A fixed graph tile only means something when this same
                                        // model advertises UltraFix support.  `ChatModelChoice`
                                        // enforces that as a hard contract and it is built during
                                        // Compose recomposition, so never hand it a self-contradictory
                                        // pair -- that aborted the whole UI instead of one row.
                                        imageUltraFixRequiredTileSize =
                                            if (imageCapabilities.supportsUltraFix) {
                                                imageDefaults.ultraFixRequiredTileSize
                                            } else {
                                                0
                                            },
                                        imageSupportedSamplers = imageDefaults.supportedSamplers,
                                        imageImg2ImgSupportedSamplers =
                                            imageDefaults.img2ImgSupportedSamplers
                                    )
                                }
                        )
                        addAll(
                            state.cloudModels
                                .filter { it.kind == CloudModelKind.IMAGE && it.configured }
                                .sortedByDescending { it.updatedAt }
                                .map { model ->
                                    ChatModelChoice(
                                        id = MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + model.id,
                                        displayName = model.displayName.ifBlank { model.modelName },
                                        quant = "云端生图",
                                        loaded = state.selectedImageBackend == ImageBackend.CLOUD && model.id == state.selectedCloudImageModelId,
                                        subtitle = model.protocolLabel,
                                        cloud = true
                                    )
                            }
                        )
                    },
                    existingImageModelIds = existingImageModelChoiceIds(
                        localModelIds = state.localImageModels.map { model -> model.id },
                        cloudImageModelIds = state.cloudModels
                            .asSequence()
                            .filter { model -> model.kind == CloudModelKind.IMAGE }
                            .map { model -> model.id }
                            .asIterable()
                    ),
                    assistants = state.assistants.map { assistant ->
                        val assistantParams = GenerationParams.fromJson(assistant.paramsJson, state.params)
                        AssistantUiItem(
                            id = assistant.id,
                            name = assistant.name,
                            avatar = assistant.avatar,
                            tag = assistant.tag,
                            systemPrompt = assistant.systemPrompt,
                            modelSummary = when (assistant.defaultModelMode) {
                                "local" -> state.models.firstOrNull { it.id == assistant.defaultModelId }?.displayName ?: "指定本地模型"
                                "cloud" -> state.cloudModels.firstOrNull {
                                    it.id == assistant.defaultModelId && it.kind == CloudModelKind.CHAT
                                }?.modelName ?: "指定云端模型"
                                else -> "跟随当前模型"
                            },
                            defaultModelMode = assistant.defaultModelMode,
                            defaultModelId = assistant.defaultModelId,
                            temperature = assistantParams.temperature,
                            topP = assistantParams.topP,
                            nPredict = assistantParams.nPredict,
                            reasoningMode = assistantParams.reasoningMode,
                            memoryEnabled = assistant.memoryEnabled,
                            memorySummaryInterval = assistant.memorySummaryInterval,
                            characterCardImported = !assistant.characterCardJson.isNullOrBlank(),
                            webSearchEnabled = assistant.webSearchEnabled,
                            fileContextEnabled = assistant.fileContextEnabled,
                            selected = assistant.id == state.selectedAssistantId,
                            exportJson = assistant.toJson().toString(2),
                            topK = assistantParams.topK,
                            minP = assistantParams.minP,
                            repeatPenalty = assistantParams.repeatPenalty,
                            presencePenalty = assistantParams.presencePenalty,
                            frequencyPenalty = assistantParams.frequencyPenalty,
                            stopWords = assistantParams.stopWords
                        )
                    },
                    worldBooks = state.worldBooks
                        .filter { book ->
                            when (book.scope) {
                                WorldBookScope.GLOBAL -> true
                                WorldBookScope.ASSISTANT -> book.assistantId == state.selectedAssistantId
                                WorldBookScope.CHAT -> book.chatSessionId == state.activeChatSessionId
                            }
                        }
                        .map { book ->
                            WorldBookUiItem(
                                id = book.id,
                                name = book.name,
                                entryCount = book.entries.size,
                                scopeLabel = when (book.scope) {
                                    WorldBookScope.GLOBAL -> "全局"
                                    WorldBookScope.ASSISTANT -> "当前角色"
                                    WorldBookScope.CHAT -> "当前对话"
                                },
                                enabled = book.enabled,
                                constantEntryCount = book.entries.count { it.enabled && it.constant },
                                keywordEntryCount = book.entries.count { it.enabled && !it.constant }
                            )
                        },
                    knowledgeBases = state.knowledgeBases.map { knowledgeBase ->
                        KnowledgeBaseUiItem(
                            id = knowledgeBase.id,
                            name = knowledgeBase.name,
                            description = knowledgeBase.description,
                            selected = knowledgeBase.id in state.selectedKnowledgeBaseIds,
                            indexStateLabel = when (knowledgeBase.indexState) {
                                KnowledgeIndexState.LEXICAL_READY -> "本地检索已就绪"
                                KnowledgeIndexState.EMBEDDING_PENDING -> "等待向量索引"
                                KnowledgeIndexState.EMBEDDING_READY -> "向量索引已就绪"
                                KnowledgeIndexState.REINDEX_REQUIRED -> "需要重建索引"
                                KnowledgeIndexState.FAILED -> "索引失败"
                            },
                            documentCount = state.knowledgeDocumentCounts[knowledgeBase.id] ?: 0,
                            importing = knowledgeBase.id in state.knowledgeBaseImportingIds
                        )
                    },
                    statusMessage = state.statusMessage,
                    selectedAssistantId = state.selectedAssistantId,
                    generationHistoryInputUris = generationHistoryInputUris,
                    images = state.images.map { image ->
                        val generation = imageGenerationHistoryById[image.id]
                        val sourceGeneration = generation?.takeIf { it.sourceGenerationAvailable }
                        val executionFacets = sourceGeneration?.executionFacets()
                        ImageAssetUiItem(
                            id = image.id,
                            name = image.name,
                            uriString = image.uriString,
                            source = image.source,
                            prompt = image.prompt,
                            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
                                .format(java.util.Date(image.createdAt)),
                            createdAtMillis = image.createdAt,
                            sizeText = formatAssetBytes(image.sizeBytes),
                            width = image.width,
                            height = image.height,
                            sizeBytes = image.sizeBytes,
                            upscaleTargetScale = generation?.upscaleHistory?.lastOrNull()?.targetScale,
                            generationDetails = generation?.displayDetails().orEmpty(),
                            generationPrompt = sourceGeneration?.requestPrompt.orEmpty(),
                            generationModelId = sourceGeneration?.modelId.orEmpty(),
                            generationModelName = sourceGeneration?.modelName.orEmpty(),
                            generationTaskMode = sourceGeneration?.inputDraft?.taskMode?.wireName.orEmpty(),
                            generationOperation = sourceGeneration?.operationFacet()?.wireName.orEmpty(),
                            generationSampler = sourceGeneration?.options?.sampleMethod.orEmpty(),
                            generationRuntime = executionFacets?.runtimeLabel.orEmpty(),
                            generationDevice = executionFacets?.deviceLabel.orEmpty(),
                            parameterShareJson = sourceGeneration?.toShareJson().orEmpty(),
                            generationPreset = sourceGeneration?.let { metadata ->
                                ImageGenerationUiPreset(
                                    prompt = metadata.requestPrompt,
                                    taskMode = ImageGenerationUiTaskMode.entries.firstOrNull { mode ->
                                        mode.wireName == metadata.inputDraft.taskMode.wireName
                                    },
                                    negativePrompt = metadata.options.negativePrompt,
                                    width = metadata.options.width,
                                    height = metadata.options.height,
                                    steps = metadata.options.steps,
                                    cfgScale = metadata.options.cfgScale,
                                    seed = metadata.options.seed,
                                    sampleMethod = metadata.options.sampleMethod,
                                    clipSkip = metadata.options.clipSkip,
                                    batchCount = metadata.options.batchCount,
                                    vaeTileSize = metadata.options.vaeTiling?.tileSize,
                                    vaeTileOverlap = metadata.options.vaeTiling?.overlap,
                                    loras = metadata.loras.map { selection ->
                                        ImageGenerationUiLoraSelection(
                                            id = selection.id,
                                            multiplier = selection.multiplier
                                        )
                                    },
                                    textualInversionIds = metadata.options.textualInversionIds,
                                    strength = metadata.inputDraft.strength,
                                    controlStrength = metadata.inputDraft.controlStrength,
                                    ultraFix = metadata.options.ultraFix?.let { ultraFix ->
                                        com.muyuchat.feature.chat.ImageGenerationUiUltraFixOptions(
                                            targetWidth = ultraFix.targetWidth,
                                            targetHeight = ultraFix.targetHeight,
                                            strength = ultraFix.strength,
                                            inversionSteps = ultraFix.inversionSteps,
                                            refinementSteps = ultraFix.refinementSteps,
                                            tileSize = ultraFix.tileSize,
                                            overlap = ultraFix.overlap
                                        )
                                    }
                                )
                            },
                            favorite = image.favorite,
                            canRecreate = sourceGeneration?.canRecreate() == true
                        )
                    },
                    imageLibraryBackup = ImageLibraryBackupUiState(
                        running = state.imageLibraryBackup.running,
                        importing = state.imageLibraryBackup.importing,
                        done = state.imageLibraryBackup.done,
                        total = state.imageLibraryBackup.total,
                        message = state.imageLibraryBackup.message,
                        failed = state.imageLibraryBackup.failed
                    ),
                    imageLoras = state.localImageLoras.map { adapter ->
                        ImageLoraUiItem(
                            id = adapter.id,
                            name = adapter.name,
                            sizeText = formatAssetBytes(adapter.sizeBytes),
                            sha256 = adapter.sha256,
                            inUse = adapter.id in state.activeLocalImageLoraIds,
                            isQwenImage21ViggleV021 =
                                QwenImage21TurboSchedule.isOfficialV021Sha256(adapter.sha256)
                        )
                    },
                    imageLoraImporting = state.localImageLoraImporting,
                    imageLoraMessage = state.localImageLoraMessage,
                    imageTextualInversions = state.localImageTextualInversions.map { artifact ->
                        ImageTextualInversionUiItem(
                            id = artifact.id,
                            name = artifact.name,
                            trigger = artifact.trigger,
                            format = artifact.format.wireName,
                            sizeText = formatAssetBytes(artifact.sizeBytes),
                            sha256 = artifact.sha256,
                            inUse = artifact.id in state.activeLocalImageTextualInversionIds ||
                                artifact.id in state.deletingLocalImageTextualInversionIds
                        )
                    },
                    imageTextualInversionImporting = state.localImageTextualInversionLoading ||
                        state.localImageTextualInversionImporting,
                    imageTextualInversionMessage = state.localImageTextualInversionMessage,
                    imageUpscalers = state.localImageUpscalers.map { upscaler ->
                        ImageUpscalerUiItem(
                            id = upscaler.id,
                            name = upscaler.name,
                            sizeText = formatAssetBytes(upscaler.sizeBytes),
                            sha256 = upscaler.sha256,
                            selected = upscaler.id == state.selectedLocalImageUpscalerId,
                            inUse = upscaler.id == state.activeLocalImageUpscalerId,
                            deleting = upscaler.id == state.localImageUpscalerDeletingId
                        )
                    },
                    selectedImageUpscalerId = state.selectedLocalImageUpscalerId,
                    imageUpscalerImporting = state.localImageUpscalerImporting,
                    imageUpscalerMessage = state.localImageUpscalerMessage,
                    imageUpscaleJob = state.imageUpscaleJob?.let { job ->
                        ImageUpscaleUiJob(
                            id = job.id,
                            sourceImageId = job.spec.sourceImageSnapshot.id,
                            upscalerId = job.spec.upscalerSnapshot.id,
                            upscalerName = job.spec.upscalerSnapshot.name,
                            targetScale = job.spec.targetScale,
                            statusLabel = job.status.label,
                            running = !job.status.terminal,
                            failed = job.status.failed,
                            terminal = job.status.terminal,
                            resultImageAssetId = job.resultImageAssetId,
                            message = job.message
                        )
                    },
                    deferGenerationImageGrantRelease = state.deferGenerationImageGrantRelease,
                    files = state.files.map { file ->
                        FileAssetUiItem(
                            id = file.id,
                            name = file.name,
                            mimeType = file.mimeType,
                            preview = file.preview,
                            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
                                .format(java.util.Date(file.createdAt)),
                            sizeText = formatAssetBytes(file.sizeBytes),
                            truncated = file.truncated
                        )
                    },
                    imageJobs = state.imageJobs.map { job ->
                        ImageGenerationUiJob(
                            id = job.id,
                            prompt = job.prompt,
                            statusLabel = job.status.label,
                            modelId = job.modelId,
                            modelName = job.modelName,
                            modelIsCloud = job.backend == ImageBackend.CLOUD,
                            chatSessionId = job.spec?.chatSessionId,
                            chatMessageId = job.spec?.chatMessageId,
                            imageAssetId = job.imageAssetId,
                            imageAssetIds = job.imageAssetIds,
                            completedProgressCount = job.completedProgressCount,
                            expectedOutputCount = job.spec?.options?.batchCount ?: 1,
                            previewUriString = job.previewUriString,
                            previewMode = job.previewMode,
                            previewStep = job.previewStep,
                            previewRevision = job.previewRevision,
                            previewWidth = job.previewWidth,
                            previewHeight = job.previewHeight,
                            failed = job.status.failed,
                            terminal = job.status.terminal,
                            message = job.message,
                            translationPreview = job.spec?.promptExecution
                                ?.takeIf { execution ->
                                    execution.method in setOf(
                                        LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
                                        LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
                                    )
                                }
                                ?.let { execution ->
                                    if (execution.originalPrompt.containsHanScript()) {
                                        execution.effectivePrompt
                                    } else {
                                        execution.effectiveNegativePrompt
                                    }
                                },
                            startedAtMillis = job.startedAtMillis,
                            startedAtElapsedMs = job.startedAtElapsedMs
                        )
                    },
                    activeConversationId = state.activeChatSessionId,
                    conversationReadOnly = activeChatSession?.let { session ->
                        session.mixedAssistantHistory || session.assistantId == null ||
                            session.assistantSnapshot?.assistantId != session.assistantId ||
                            state.assistants.none { it.id == session.assistantId }
                    } ?: false,
                    input = state.input,
                    isGenerating = state.isGenerating,
                    generationPhase = state.generationPhase,
                    generationTokenProgress = state.generationTokenProgress,
                    generationPersistProgress = state.generationPersistProgress,
                    generationStats = state.generationStats,
                    promptContextUsage = state.promptContextUsage,
                    contextCompressionThresholdPercent = state.contextCompressionThresholdPercent,
                    contextCompressionPending = state.contextCompressionPending,
                    contextSummaryModelSelection = state.contextSummaryModelSelection,
                    contextSummaryModels = buildList {
                        add(ContextSummaryModelOption("current", "当前聊天模型"))
                        add(ContextSummaryModelOption("deterministic", "规则整理"))
                        add(ContextSummaryModelOption("local", "已加载的本地模型"))
                        state.cloudModels.filter { it.kind == CloudModelKind.CHAT && it.configured }.forEach { model ->
                            add(ContextSummaryModelOption("cloud:${model.id}", "云端 · ${model.displayName}"))
                        }
                    },
                    contextSummaryHistory = state.chatSessions
                        .firstOrNull { it.id == state.activeChatSessionId }
                        ?.contextSummaries.orEmpty().map { summary ->
                            ContextSummaryUiItem(
                                version = summary.version,
                                source = summary.source,
                                text = summary.text,
                                sourceMessageIds = summary.sourceMessageIds,
                                active = summary.active,
                                createdAt = summary.createdAt,
                                coverageLimited = summary.structuredSummary.coverageLimited,
                                evidenceCount = summary.structuredSummary.evidence.size,
                                omittedExcerptCount = summary.structuredSummary.omittedExcerptCount,
                                omittedCharacterCount = summary.structuredSummary.omittedCharacterCount,
                                modelIdentity = summary.summaryModelIdentity,
                                diagnostic = summary.summaryDiagnostic
                            )
                        },
                    canUndoContextSummary = state.chatSessions
                        .firstOrNull { it.id == state.activeChatSessionId }
                        ?.contextSummaries?.any { it.active } == true,
                    selectedModelId = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.selectedCloudChatModelId?.let { MainViewModel.CLOUD_MODEL_CHOICE_PREFIX + it }
                    } else {
                        state.loadedModelId
                    },
                    selectedModelName = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudChatModelId }?.modelName
                    } else {
                        state.loadedModelName
                    },
                    selectedModelIsCloud = state.selectedChatBackend == ChatBackend.CLOUD,
                    selectedModelRuntimeLabel = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudChatModelId }?.apiFormat?.label
                    } else {
                        state.models.firstOrNull { it.id == state.loadedModelId }?.runtime?.label
                    },
                    selectedImageModelId = if (state.selectedImageBackend == ImageBackend.CLOUD) {
                        state.selectedCloudImageModelId?.let { MainViewModel.CLOUD_IMAGE_MODEL_CHOICE_PREFIX + it }
                    } else {
                        state.selectedLocalImageModelId?.let { MainViewModel.LOCAL_IMAGE_MODEL_CHOICE_PREFIX + it }
                    },
                    selectedImageModelName = if (state.selectedImageBackend == ImageBackend.CLOUD) {
                        state.cloudModels.firstOrNull { it.id == state.selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }?.modelName
                    } else {
                        state.localImageModels.firstOrNull { it.id == state.selectedLocalImageModelId }?.displayName
                    },
                    selectedImageModelIsCloud = state.selectedImageBackend == ImageBackend.CLOUD,
                    assistantImageToolAvailable = assistantImageToolPermission.available,
                    assistantImageToolUnavailableReason = assistantImageToolPermission.unavailableReason,
                    assistantImageToolAutoApproval = assistantImageToolPermission.autoApproval,
                    assistantImageToolCanChange = assistantImageToolPermission.canChange,
                    stats = state.stats,
                    apiEnabled = state.apiEnabled,
                    restEnabled = state.restEnabled,
                    generationParams = state.params,
                    reasoningMode = state.params.reasoningMode,
                    webSearchEnabled = state.webSearchConfig.enabled,
                    webSearchConfigured = state.webSearchConfig.realSearchConfigured,
                    webSearchEnabledForTurn = state.webSearchTurnMode == WebSearchTurnMode.ON ||
                        (state.webSearchTurnMode == WebSearchTurnMode.FOLLOW &&
                            state.assistants.firstOrNull { it.id == state.selectedAssistantId }?.webSearchEnabled == true),
                    webSearchStatusMessage = state.webSearchStatusMessage,
                    webSearchTurnModeLabel = state.toWebSearchTurnModeLabel(),
                    webSearchResearchMode = (state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).name,
                    webSearchResearchModeLabel = (state.webSearchResearchModeOverride ?: state.webSearchConfig.researchMode).label,
                    webSearchResearchOverridden = state.webSearchResearchModeOverride != null,
                    webSearchProviderLabel = buildString {
                        if (state.webSearchConfig.realSearchConfigured) {
                            append(state.webSearchConfig.realSearchProviderLabel.ifBlank { state.webSearchConfig.providerLabel })
                            append(" · ")
                            append(state.webSearchConfig.triggerMode.label)
                        } else if (state.webSearchConfig.isPublicCheckSource) {
                            append("协议自检源 · 网页直读")
                        } else if (state.webSearchConfig.enabled) {
                            append("网页直读 · ")
                            append(state.webSearchConfig.triggerMode.label)
                        } else {
                            append(state.webSearchConfig.providerLabel)
                        }
                    },
                    visionCapabilityLabel = chatVisionCapability.label,
                    visionCapabilityDetail = chatVisionCapability.detail,
                    visionCapabilityReady = chatVisionCapability.ready
    )
}
