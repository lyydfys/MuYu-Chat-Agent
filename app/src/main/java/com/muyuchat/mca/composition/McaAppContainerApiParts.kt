package com.muyuchat.mca.composition

import com.muyuchat.mca.RuntimeProfileFields
import com.muyuchat.mca.UiStateWriter
import com.muyuchat.mca.LocalApiRuntimeOwner
import com.muyuchat.mca.LocalImageGenerationCoordinator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.mca.ChatBackend
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.MainUiState
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.VerifiedOfflinePromptTranslationBundle
import kotlinx.coroutines.Job
import com.muyuchat.mca.ChatGenerationExecutionPort
import com.muyuchat.mca.ChatGenerationExecutionPortAdapter
import com.muyuchat.mca.ChatImagePromptBridgePort
import com.muyuchat.mca.ChatImagePromptBridgePortAdapter
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.LocalApiImageGenerationPortAdapter
import com.muyuchat.mca.LocalApiImageWorkerGatewayAdapter
import com.muyuchat.mca.LocalApiImageLoraGatewayAdapter
import com.muyuchat.mca.LocalImagePreparedLora
import com.muyuchat.mca.LocalImagePromptExecution
import com.muyuchat.mca.LocalApiImageGenerationOwnership
import com.muyuchat.mca.QnnImageEvidenceEntryPoint
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImageResult
import com.muyuchat.mca.LocalImageLoraRecord
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.LocalApiTuningJobCreateRequest
import com.muyuchat.api.local.LocalApiIdempotencyContext
import com.muyuchat.mca.LocalApiControlPlaneAdapter
import com.muyuchat.feature.chat.ChatImageActionKind
import com.muyuchat.mca.PendingImageTranslation
import com.muyuchat.mca.PromptEnvelope
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.ChatContextComposer
import com.muyuchat.mca.ChatRuntimeContextPlan
import com.muyuchat.mca.WebSearchCitationAudit
import com.muyuchat.mca.WebSearchDiagnosticRecord
import com.muyuchat.mca.AssistantConversationSnapshot
import com.muyuchat.mca.AssistantImageModelBinding
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope
import com.muyuchat.mca.RuntimeProfileTransactionPort
import com.muyuchat.mca.RuntimeProfileTransactionPortAdapter
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.mca.LocalApiAuxProviderPort
import com.muyuchat.mca.LocalApiAuxProviderPortAdapter
import com.muyuchat.mca.LocalApiTuningControlPort
import com.muyuchat.mca.LocalApiTuningControlPortAdapter
import com.muyuchat.mca.TuningJobEntity
import com.muyuchat.mca.PendingRuntimeProfileTransaction
import com.muyuchat.api.local.LocalApiControlResult
import org.json.JSONObject

internal fun McaAppContainer.createRuntimeProfileTransactionPort(
        store: UiStateStore,
        engine: McaInferenceService,
        tuningOwner: TuningOwner,
        showStatusAction: (String) -> Unit
    ): RuntimeProfileTransactionPort =
        RuntimeProfileTransactionPortAdapter(
            store = store.runtimeProfileWriter(),
            runtimeProfileStore = runtimeProfileStore,
            engine = engine,
            runtimeAdmission = runtimeAdmission,
            tuningOwner = tuningOwner,
            showStatusAction = showStatusAction
        )

internal fun McaAppContainer.createLocalApiAuxProviderPort(
        store: UiStateStore,
        engine: McaInferenceService,
        localApiPayloadPortProvider: () -> LocalApiPayloadPort,
        benchmarkRunner: BenchmarkRunner,
        buildRecommendationAction: (UserPreference) -> AgentRecommendation,
        generateLocalApiImageAction: suspend (String, String) -> String,
        generateLocalApiVideoAction: suspend (String, String) -> String,
        stopLocalApiVideoAction: suspend (String) -> Boolean,
    ): LocalApiAuxProviderPort =
        LocalApiAuxProviderPortAdapter(
            store = store,
            engine = engine,
            localApiPayloadPortProvider = localApiPayloadPortProvider,
            deviceProfileReader = deviceProfileReader,
            buildRecommendationAction = buildRecommendationAction,
            benchmarkRunner = benchmarkRunner,
            generateLocalApiImageAction = generateLocalApiImageAction,
            generateLocalApiVideoAction = generateLocalApiVideoAction,
            stopLocalApiVideoAction = stopLocalApiVideoAction,
        )

internal fun McaAppContainer.createLocalApiTuningControlPortImpl(
        store: UiStateWriter<RuntimeProfileFields>,
        tuningOwner: TuningOwner,
        localApiModelMatchesRef: (String, MainUiState, ModelManifest) -> Boolean,
        localApiPerformancePreferenceRef: (String?) -> UserPreference?,
        localApiTuningRejectedRef: (Int, String, String, JSONObject) -> LocalApiControlResult.Rejected,
        localApiTuningConflictRef: (String, String?, String) -> LocalApiControlResult.Rejected,
        localApiExclusiveLifecycleConflictRef: (String, String?) -> LocalApiControlResult.Rejected?,
        assignActiveTuningJobIdRef: (String?) -> Unit,
        tuningJobPublicJsonRef: (TuningJobEntity) -> JSONObject,
        setTuningCancelRequestedRef: (Boolean) -> Unit,
        setTuningPauseRequestedRef: (Boolean) -> Unit,
        launchTuningApplyRef: (PendingRuntimeProfileTransaction) -> Unit,
        launchTuningRollbackRef: (ModelRuntimeIdentity, ModelManifest) -> Unit,
    ): LocalApiTuningControlPort =
        LocalApiTuningControlPortAdapter(
            runtimeAdmission = runtimeAdmission,
            store = store,
            runtimeProfileStore = runtimeProfileStore,
            tuningOwner = tuningOwner,
            localApiModelMatchesRef = localApiModelMatchesRef,
            localApiPerformancePreferenceRef = localApiPerformancePreferenceRef,
            localApiTuningRejectedRef = localApiTuningRejectedRef,
            localApiTuningConflictRef = localApiTuningConflictRef,
            localApiExclusiveLifecycleConflictRef = localApiExclusiveLifecycleConflictRef,
            assignActiveTuningJobIdRef = assignActiveTuningJobIdRef,
            tuningJobPublicJsonRef = tuningJobPublicJsonRef,
            setTuningCancelRequestedRef = setTuningCancelRequestedRef,
            setTuningPauseRequestedRef = setTuningPauseRequestedRef,
            launchTuningApplyRef = launchTuningApplyRef,
            launchTuningRollbackRef = launchTuningRollbackRef,
        )

internal fun McaAppContainer.createChatGenerationExecutionPort(
        store: UiStateStore,
        engine: McaInferenceService,
        chatContextComposer: ChatContextComposer,
        visionAttachmentPort: VisionAttachmentPort,
        getActiveWebSearchTaskIdRef: () -> String?,
        setActiveWebSearchTaskIdRef: (String?) -> Unit,
        getForegroundRecoveryJobRef: () -> Job?,
        persistChatSessionsRef: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
        appendAssistantRef: (String, String, Long, Boolean, Long?) -> Unit,
        flushPendingAssistantOutputRef: (Long?) -> Boolean,
        removePendingAssistantPlaceholderRef: (Long?) -> Unit,
        ensureVisibleAssistantReplyAfterCompletionRef: (Long?) -> Unit,
        applyWebSearchAnswerGuardsToLastAssistantRef: (Long?) -> WebSearchCitationAudit?,
        recordWebSearchCitationAuditRef: (String?, WebSearchCitationAudit, Long?) -> Unit,
        attachWebSearchEvidenceToPendingAssistantRef: (List<ChatSourceReference>, ChatWebSearchTrace?, Long?) -> Unit,
        attachContextAssemblyTraceRef: (String?, Long, String?, String?) -> Unit,
        promptContextUsageForRef: (ChatRuntimeContextPlan, ContextWindowAdmission, GenerationParams) -> PromptContextUsage,
        appendWebSearchDiagnosticRef: (WebSearchDiagnosticRecord) -> List<WebSearchDiagnosticRecord>,
        recordCompletedCharacterMemoryTurnRef: (String, String, AssistantConversationSnapshot, ChatMessage, ChatMessage, Job?, ChatBackend, GenerationParams, CloudApiConfig?, String?) -> Unit,
        summarizeContextForGenerationRef: suspend (ChatRequest, ContextCompressionSettings, ContextCompressionTrigger, MainUiState, ChatBackend, String?, Long, Boolean) -> ContextCompressionResult,
        ensureLocalConversationContextInvalidatedRef: suspend () -> Boolean,
        loadContextCompressionThresholdRef: () -> ContextCompressionThreshold,
        handleAssistantImageToolCallsRef: suspend (List<ChatToolCall>, List<ChatMessage>, GenerationParams, String?, String?, String?, AssistantImageModelBinding?, CloudApiConfig?) -> Boolean,
        finishAssistantImageContinuationRef: (String, Boolean, String) -> Unit,
    ): ChatGenerationExecutionPort =
        ChatGenerationExecutionPortAdapter(
            application = application,
            store = store.generationExecutionWriter(),
            engine = engine,
            chatRuns = chatRuns,
            chatSessionStore = chatSessionStore,
            chatContextComposer = chatContextComposer,
            cloudChatProvider = cloudChatProvider,
            webSearchProvider = webSearchProvider,
            manualContextCompressionRequests = manualContextCompressionRequests,
            runtimeAdmission = runtimeAdmission,
            visionAttachmentPort = visionAttachmentPort,
            getActiveWebSearchTaskIdRef = getActiveWebSearchTaskIdRef,
            setActiveWebSearchTaskIdRef = setActiveWebSearchTaskIdRef,
            getForegroundRecoveryJobRef = getForegroundRecoveryJobRef,
            persistChatSessionsRef = persistChatSessionsRef,
            appendAssistantRef = appendAssistantRef,
            flushPendingAssistantOutputRef = flushPendingAssistantOutputRef,
            removePendingAssistantPlaceholderRef = removePendingAssistantPlaceholderRef,
            ensureVisibleAssistantReplyAfterCompletionRef = ensureVisibleAssistantReplyAfterCompletionRef,
            applyWebSearchAnswerGuardsToLastAssistantRef = applyWebSearchAnswerGuardsToLastAssistantRef,
            recordWebSearchCitationAuditRef = recordWebSearchCitationAuditRef,
            attachWebSearchEvidenceToPendingAssistantRef = attachWebSearchEvidenceToPendingAssistantRef,
            attachContextAssemblyTraceRef = attachContextAssemblyTraceRef,
            promptContextUsageForRef = promptContextUsageForRef,
            appendWebSearchDiagnosticRef = appendWebSearchDiagnosticRef,
            recordCompletedCharacterMemoryTurnRef = recordCompletedCharacterMemoryTurnRef,
            summarizeContextForGenerationRef = summarizeContextForGenerationRef,
            ensureLocalConversationContextInvalidatedRef = ensureLocalConversationContextInvalidatedRef,
            loadContextCompressionThresholdRef = loadContextCompressionThresholdRef,
            handleAssistantImageToolCallsRef = handleAssistantImageToolCallsRef,
            finishAssistantImageContinuationRef = finishAssistantImageContinuationRef,
        )

internal fun McaAppContainer.createChatImagePromptBridgePort(
        store: UiStateStore,
        chatContextComposer: ChatContextComposer,
        engine: McaInferenceService,
        currentBridgeGenerationRef: () -> Long,
        incrementBridgeGenerationRef: () -> Long,
        cancelBridgeJobRef: () -> Unit,
        setBridgeJobRef: (Job) -> Unit,
        bridgeScopeRef: () -> CoroutineScope,
        setPendingChatImageActionRef: (String, ChatImageActionKind, String, Int?, () -> Boolean) -> Unit,
        setPendingImageTranslationRef: (PendingImageTranslation) -> Unit,
        showStatusRef: (String?) -> Unit,
        chatImageOptionsForCurrentSelectionRef: (MainUiState) -> LocalImageGenerationOptions,
        offlinePromptTranslationServiceProvider: () -> OfflinePromptTranslationService,
        verifiedOfflineTranslationBundleRef: suspend () -> VerifiedOfflinePromptTranslationBundle?,
        sendChatImageRequestRef: (String, String, LocalImageGenerationOptions?, PromptEnvelope?, String?) -> Unit,
        negativePromptSupportedRef: (MainUiState) -> Boolean,
    ): ChatImagePromptBridgePort =
        ChatImagePromptBridgePortAdapter(
            store = store.chatPromptDraftWriter(),
            chatContextComposer = chatContextComposer,
            cloudChatProvider = cloudChatProvider,
            engine = engine,
            currentBridgeGenerationRef = currentBridgeGenerationRef,
            incrementBridgeGenerationRef = incrementBridgeGenerationRef,
            cancelBridgeJobRef = cancelBridgeJobRef,
            setBridgeJobRef = setBridgeJobRef,
            bridgeScopeRef = bridgeScopeRef,
            setPendingChatImageActionRef = setPendingChatImageActionRef,
            setPendingImageTranslationRef = setPendingImageTranslationRef,
            showStatusRef = showStatusRef,
            chatImageOptionsForCurrentSelectionRef = chatImageOptionsForCurrentSelectionRef,
            offlinePromptTranslationServiceProvider = offlinePromptTranslationServiceProvider,
            verifiedOfflineTranslationBundleRef = verifiedOfflineTranslationBundleRef,
            sendChatImageRequestRef = sendChatImageRequestRef,
            negativePromptSupportedRef = negativePromptSupportedRef,
            chatExecutionFacade = chatExecutionFacadeFor(engine),
        )

internal fun McaAppContainer.createLocalApiImageGenerationPortImpl(
        store: UiStateStore,
        getActiveLocalApiImageModelIdRef: () -> String?,
        setActiveLocalApiImageModelIdRef: (String?) -> Unit,
        tryAcquireObservedImageGenerationLeaseRef: (String) -> LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLeaseRef: (LocalImageGenerationCoordinator.Lease) -> Boolean,
        registerLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorkerRef: (LocalApiImageGenerationOwnership) -> Boolean,
        resolveCurrentLocalImageLorasRef1: (List<Pair<String, Double>>) -> List<LocalImagePreparedLora>,
        resolveCurrentLocalImageLorasRef2: (List<Pair<String, Double>>, List<LocalImageLoraRecord>) -> List<LocalImagePreparedLora>,
        resolveCurrentLocalImageTextualInversionIdsRef: (List<String>) -> List<String>,
        prepareLocalImagePromptExecutionRef: suspend (LocalImageModelRecord, String, LocalImageGenerationOptions, LocalImagePromptExecution?) -> LocalImagePromptExecution,
        prepareLocalImageRuntimeMemoryRef: suspend () -> EngineLifecycleLease?,
        executionWithQnnVerificationEvidenceRef: (LocalImageModelRecord, LocalImageResult, LocalImageGenerationOptions, String, String, QnnImageEvidenceEntryPoint) -> String,
    ): LocalApiImageGenerationPort =
        LocalApiImageGenerationPortAdapter(
            store = store.localApiImageWriter(),
            localImageLoraLifecycleLockRef = localImageLoraLifecycleLock,
            localImageTextualInversionLifecycleLockRef = localImageTextualInversionLifecycleLock,
            getActiveLocalApiImageModelIdRef = getActiveLocalApiImageModelIdRef,
            setActiveLocalApiImageModelIdRef = setActiveLocalApiImageModelIdRef,
            localImageWorkerClient = LocalApiImageWorkerGatewayAdapter { localImageWorkerClient },
            localImageLoraStore = LocalApiImageLoraGatewayAdapter { localImageLoraStore },
            tryAcquireObservedImageGenerationLeaseRef = tryAcquireObservedImageGenerationLeaseRef,
            releaseObservedImageGenerationLeaseRef = releaseObservedImageGenerationLeaseRef,
            registerLocalApiImageGenerationOwnershipRef = registerLocalApiImageGenerationOwnershipRef,
            unregisterLocalApiImageGenerationOwnershipRef = unregisterLocalApiImageGenerationOwnershipRef,
            cancelOwnedLocalApiImageWorkerRef = cancelOwnedLocalApiImageWorkerRef,
            resolveCurrentLocalImageLorasRef1 = resolveCurrentLocalImageLorasRef1,
            resolveCurrentLocalImageLorasRef2 = resolveCurrentLocalImageLorasRef2,
            resolveCurrentLocalImageTextualInversionIdsRef = resolveCurrentLocalImageTextualInversionIdsRef,
            prepareLocalImagePromptExecutionRef = prepareLocalImagePromptExecutionRef,
            prepareLocalImageRuntimeMemoryRef = prepareLocalImageRuntimeMemoryRef,
            executionWithQnnVerificationEvidenceRef = executionWithQnnVerificationEvidenceRef,
        )

internal fun McaAppContainer.createLocalApiControlPlaneImpl(
        busyStateProvider: () -> LocalApiBusyState,
        preflightProvider: (LocalApiPreflightRequest) -> LocalApiPreflightResult,
        profileJsonProvider: () -> String,
        tuningJsonProvider: () -> String,
        tuningJobProvider: (String?) -> LocalApiControlResult,
        createTuningJobDelegate: (LocalApiTuningJobCreateRequest, String) -> LocalApiControlResult,
        pauseTuningJobDelegate: (String, String) -> LocalApiControlResult,
        resumeTuningJobDelegate: (String, String) -> LocalApiControlResult,
        cancelTuningJobDelegate: (String, String) -> LocalApiControlResult,
        applyTuningJobDelegate: (String, String) -> LocalApiControlResult,
        rollbackTuningDelegate: (String) -> LocalApiControlResult,
        runDurableLocalApiControl: (LocalApiIdempotencyContext, () -> LocalApiControlResult) -> LocalApiControlResult,
    ): LocalApiControlPlane =
        LocalApiControlPlaneAdapter(
            busyStateProvider = busyStateProvider,
            preflightProvider = preflightProvider,
            profileJsonProvider = profileJsonProvider,
            tuningJsonProvider = tuningJsonProvider,
            tuningJobProvider = tuningJobProvider,
            createTuningJobDelegate = createTuningJobDelegate,
            pauseTuningJobDelegate = pauseTuningJobDelegate,
            resumeTuningJobDelegate = resumeTuningJobDelegate,
            cancelTuningJobDelegate = cancelTuningJobDelegate,
            applyTuningJobDelegate = applyTuningJobDelegate,
            rollbackTuningDelegate = rollbackTuningDelegate,
            runDurableLocalApiControl = runDurableLocalApiControl,
        )

internal fun McaAppContainer.createInferenceServiceImpl(
        runners: Map<LocalChatRuntime, LocalChatRunner> = isolatedLocalChatRunners.runners,
        installationScopeId: String = runBlocking(Dispatchers.IO) { runtimeProfileStore.installationScopeId() }
    ): McaInferenceService =
        McaInferenceService(
            context = application,
            runners = runners,
            installationScopeId = installationScopeId
        )

internal fun McaAppContainer.createLocalApiRuntimeOwnerImpl(): LocalApiRuntimeOwner =
        LocalApiRuntimeOwner()
