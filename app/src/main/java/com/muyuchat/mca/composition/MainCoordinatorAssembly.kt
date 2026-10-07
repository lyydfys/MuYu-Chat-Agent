package com.muyuchat.mca.composition

import com.muyuchat.mca.*

/**
 * Holds all coordinators assembled for MainViewModel.
 */
internal class MainCoordinatorAssembly(
    val modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator,
    val localImageLibraryAndModelCoordinator: LocalImageLibraryAndModelCoordinator,
    val assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
    val offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    val agentTuningCoordinator: AgentTuningCoordinator,
    val chatModelCoordinator: ChatModelCoordinator,
    val localApiControlPlaneCoordinator: LocalApiControlPlaneCoordinator,
    val modelScopeHubCoordinator: ModelScopeHubCoordinator,
    val assistantProfileCoordinator: AssistantProfileCoordinator,
    val contextCompressionCoordinator: ContextCompressionCoordinator,
    val managedRuntimeReadinessPort: ManagedRuntimeReadinessPort,
    val contentImportPreviewCoordinator: ContentImportPreviewCoordinator,
    val mainPostStartupCoordinator: MainPostStartupCoordinator,
    val chatSessionManagementCoordinator: ChatSessionManagementCoordinator,
    val chatInputAndAttachmentCoordinator: ChatInputAndAttachmentCoordinator,
    val chatExecutionPort: ChatGenerationExecutionPort,
) {
    companion object {
        fun create(
            host: MainCoordinatorHost,
            injectedMainPostStartupCoordinator: Any? = null,
            injectedChatSessionManagementCoordinator: Any? = null,
            injectedChatInputAndAttachmentCoordinator: Any? = null,
        ): MainCoordinatorAssembly {
            val store = host.store
            val scope = host.scope
            val application = host.application
            val container = host.container
            val engine = host.engine

            lateinit var managedRuntimeReadinessPortRef: ManagedRuntimeReadinessPort

            val modelImportAndDownloadCoordinator = ModelImportAndDownloadCoordinator(
                application = application,
                scope = scope,
                store = store,
                modelStore = container.modelStore,
                localImageModelStore = container.localImageModelStore,
                getManagedRuntimeReadinessRefreshGate = { container.readinessRefreshGate },
                getManagedRuntimeReadinessPort = { managedRuntimeReadinessPortRef },
                chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore,
                getInitialChatSessionIds = { host.initialChatSessions.map { it.id } },
                showStatus = host::hostShowStatus,
                failDownload = host::hostFailDownload,
                busy = host::hostBusy,
                fail = host::hostFail,
                loadModel = host::hostLoadModel,
                reconcileModelIdentities = {
                    reconcileModelIdentitiesFor(
                        chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
                        modelIdentityReferenceMigration = host.modelIdentityReferenceMigration,
                        store = store,
                        getDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions },
                        setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it }
                    )
                }
            )

            val localImageLibraryAndModelCoordinator = LocalImageLibraryAndModelCoordinator(
                store = store,
                scope = scope,
                application = application,
                container = container,
                localImageModelStore = container.localImageModelStore,
                localImageWorkerClient = container.localImageWorkerClient,
                localImageLoraStore = container.localImageLoraStore,
                managedRuntimeReadinessRefreshGate = container.readinessRefreshGate,
                launchModelOperation = host::hostLaunchModelOperation,
                tryAcquireObservedImageGenerationLease = host::hostTryAcquireObservedImageGenerationLease,
                releaseObservedImageGenerationLease = host::hostReleaseObservedImageGenerationLease,
                showStatus = host::hostShowStatus,
                busy = host::hostBusy,
                fail = host::hostFail,
                persistFiles = host::hostPersistFiles,
                activeImageGenerationModelId = { container.runtimeAdmission.activeImageGenerationModelId },
                activeLocalApiImageModelId = { container.runtimeAdmission.activeLocalApiImageModelId },
                imageUpscaleOwnerProvider = { host.imageUpscaleOwner }
            )

            val assistantImageContinuationCoordinator = AssistantImageToolContinuationCoordinator(
                store = store,
                admission = container.runtimeAdmission,
                container = container,
                cloudChatProvider = container.cloudChatProvider,
                chatContextComposer = host.chatContextComposer,
                imageGenerationOwnerProvider = { host.imageGenerationOwner },
                chatImageRequestPort = { host.chatImageRequestPort },
                persistChatSessions = { host.hostPersistChatSessions(it) },
                buildChatImageRetrySnapshot = { req, sId -> host.hostBuildChatImageRetrySnapshot(req, sId ?: "") },
                chatImageNegativePromptSupported = { host.hostChatImageNegativePromptSupported(it) },
                cancelImageGenerationForJob = { host.hostCancelImageGenerationForJob(it) },
                reserveUiGenerationStart = host::hostReserveUiGenerationStart,
                cancelPendingReservation = { container.chatRuns.cancelPending(it) },
                showStatus = host::hostShowStatus,
                startGeneration = { requestMessages, reservation, toolExchanges, paramsOverride, offerAssistantImageTool, chatModelIdOverride, continuationRequestId ->
                    host.hostStartGeneration(
                        requestMessages = requestMessages,
                        reservation = reservation,
                        toolExchanges = toolExchanges,
                        paramsOverride = paramsOverride,
                        offerAssistantImageTool = offerAssistantImageTool,
                        chatModelIdOverride = chatModelIdOverride,
                        continuationRequestId = continuationRequestId
                    )
                }
            )

            val offlinePromptTranslationAndChatImageCoordinator = OfflinePromptTranslationAndChatImageCoordinator(
                store = store,
                admission = container.runtimeAdmission,
                scope = scope,
                container = container,
                installer = container.offlineTranslationInstaller,
                downloader = container.offlineTranslationDownloader,
                offlinePromptTranslationServiceProvider = { host.offlinePromptTranslationService },
                chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore,
                prepareChatInput = { host.hostPrepareChatInput(it) },
                showStatus = host::hostShowStatus,
                rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
                hasUnresolvedAssistantImageToolTurn = assistantImageContinuationCoordinator::hasUnresolvedAssistantImageToolTurn,
                sendChatImageRequestWithPromptBridge = host::hostSendChatImageRequestWithPromptBridge,
                sendPreparedMessage = host::hostSendPreparedMessage
            )

            lateinit var chatModelCoordinatorRef: ChatModelCoordinator
            lateinit var localApiControlPlaneCoordinatorRef: LocalApiControlPlaneCoordinator

            val agentTuningCoordinator = container.createAgentTuningCoordinator(
                store = store,
                scope = scope,
                engine = engine,
                tuningOwner = host.tuningOwner,
                agentLogger = host.agentLogger,
                benchmarkRunner = host.benchmarkRunner,
                diagnosticsOperations = host.diagnosticsOperations,
                managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
                localApiRuntimeOwner = host.localApiRuntimeOwner,
                modelScopeClient = host.modelScopeClient,
                cancelPersistedTuningJob = { localApiControlPlaneCoordinatorRef.cancelPersistedTuningJob(it) },
                activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
                activeAdaptiveRecommendation = { container.runtimeAdmission.activeAdaptiveRecommendation },
                setActiveAdaptiveRecommendation = { container.runtimeAdmission.activeAdaptiveRecommendation = it },
                busy = host::hostBusy,
                fail = host::hostFail,
                showStatus = host::hostShowStatus,
                persistModelGenerationParams = { backend, modelId, params -> host.hostPersistModelGenerationParams(backend, modelId, params) },
                updateParams = host::hostUpdateParams,
                loadModel = host::hostLoadModel,
                timedLoadModel = { model, runtime, loadParams ->
                    chatModelCoordinatorRef.timedLoadModel(model, runtime, loadParams)
                },
                runLocalGenerationSmoke = { params ->
                    chatModelCoordinatorRef.runLocalGenerationSmoke(params)
                },
                cancelGenerationJob = host::hostCancelGenerationJob,
                activeModelForRuntimeProfile = { container.runtimeAdmission.activeModelForRuntimeProfile },
                getPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId },
                setPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId = it }
            )

            val chatModelCoordinator = container.createChatModelCoordinator(
                store = store,
                scope = scope,
                engine = engine,
                tuningOwner = host.tuningOwner,
                modelGenerationProfileStore = container.modelGenerationProfileStore,
                managedRuntimeReadinessPort = { managedRuntimeReadinessPortRef },
                installationScopeId = host.installationScopeId,
                cancelGenerationJob = host::hostCancelGenerationJob,
                persistChatSessions = { host.hostPersistChatSessions(it) },
                persistChatSessionsWithBinding = { sessions, binding -> host.hostPersistChatSessions(sessions, binding) },
                publishManagedChatCatalogAfterValidation = host::hostPublishManagedChatCatalogAfterValidation,
                busy = host::hostBusy,
                loadModelGenerationParams = { backend, modelId, defaults ->
                    host.hostLoadModelGenerationParams(backend, modelId, defaults)
                },
                persistModelGenerationParams = { backend, modelId, params ->
                    host.hostPersistModelGenerationParams(backend, modelId, params)
                },
                updateParams = host::hostUpdateParams,
                cancelDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob?.cancel() },
                rejectImageModelSwitchWhileGenerationIsActive = { rejectImageModelSwitchWhileGenerationIsActiveFor(store, container.runtimeAdmission) },
                invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
                activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
                currentDeviceProfile = host::hostCurrentDeviceProfile,
                currentQairtBundleSha256 = ::currentQairtBundleSha256,
                currentQairtVerifiedLocalModelIds = ::currentQairtVerifiedLocalModelIds
            )
            chatModelCoordinatorRef = chatModelCoordinator

            val localApiControlPlaneCoordinator = LocalApiControlPlaneCoordinator(
                container = container,
                store = store,
                engine = engine,
                tuningOwner = host.tuningOwner,
                runtimeProfileStore = container.runtimeProfileStore,
                agentTuningCoordinator = agentTuningCoordinator,
                getActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId },
                setActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId = it },
                getAdaptiveTuningJob = { agentTuningCoordinator.adaptiveTuningJob },
                setAdaptiveTuningJob = { agentTuningCoordinator.adaptiveTuningJob = it },
                getActiveRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
                applyPendingRuntimeTransaction = agentTuningCoordinator::applyPendingRuntimeTransaction,
                rollbackCommittedRuntimeProfile = agentTuningCoordinator::rollbackCommittedRuntimeProfile,
                idempotencyJournalLock = host.localApiIdempotencyJournalLock,
                application = application,
                scope = scope
            )
            localApiControlPlaneCoordinatorRef = localApiControlPlaneCoordinator

            val modelScopeHubCoordinator = container.createModelScopeHubCoordinator(
                scope = scope,
                store = store,
                client = host.modelScopeClient,
                currentDeviceProfile = host::hostCurrentDeviceProfile,
                busy = host::hostBusy,
                fail = host::hostFail,
                showStatus = host::hostShowStatus,
                enqueueManagedDownload = host::hostEnqueueManagedDownload
            )

            val assistantProfileCoordinator = container.createAssistantProfileCoordinator(
                store = store,
                scope = scope,
                modelGenerationProfileStore = container.modelGenerationProfileStore,
                chatSessionStore = container.chatSessionStore,
                knowledgeLibraryPort = host.knowledgeLibraryPort,
                chatSessionPersistenceStateLock = container.chatSessionPersistenceState.stateLock,
                pendingWorldBookCleanupOwners = container.chatSessionPersistenceState.pendingWorldBookCleanupOwners,
                engine = engine,
                characterMemorySummaryCoordinator = host.characterMemorySummaryCoordinator,
                chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
                chatSessionPersistenceSequence = container.chatSessionPersistenceState.persistenceSequence,
                getPendingKnowledgeBindings = { container.chatSessionPersistenceState.pendingKnowledgeBindings },
                setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it },
                conversationMutationBarrierIsActive = { container.runtimeAdmission.conversationMutationBarrier?.isActive == true },
                contentImportSelectionRevision = container.runtimeAdmission.contentImportSelectionRevision,
                activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
                runtimeUserOverrideFields = container.runtimeAdmission.runtimeUserOverrideFields,
                directParameterStageGeneration = container.runtimeAdmission.directParameterStageGeneration,
                getActiveDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob },
                setActiveDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob = it },
                directParameterStageMutex = container.runtimeAdmission.directParameterStageMutex,
                adaptiveTuningJobIsActive = { agentTuningCoordinator.adaptiveTuningJob?.isActive == true },
                getActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId },
                getPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId },
                setPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId = it },
                rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
                showStatus = host::hostShowStatus,
                persistModelGenerationParams = { state, params -> host.hostPersistModelGenerationParams(state, params) },
                persistChatSessions = { sessions -> host.hostPersistChatSessions(sessions) },
                markLocalConversationContextInvalid = host::hostMarkLocalConversationContextInvalid,
                onLocalConversationContextNeedsInvalidation = {
                    container.runtimeAdmission.localConversationContextNeedsInvalidation = true
                    container.runtimeAdmission.localConversationContextInvalidationSequence.incrementAndGet()
                },
                selectChatSession = host::hostSelectChatSession,
                loadModel = host::hostLoadModel,
                selectCloudChatModel = { chatModelCoordinator.selectCloudChatModel(it) },
                newChatForAssistant = host::hostNewChatForAssistant,
                invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
                chatAppearanceMutex = host.chatAppearanceCoordinator.mutex,
                cleanupUnusedChatBackgrounds = { host.chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() },
                captureContentImportOwner = host::hostCaptureContentImportOwner,
                stageContentImportPreview = host::hostStageContentImportPreview,
                readAttachmentText = { uri, maxBytes -> host.hostReadAttachmentText(uri, maxBytes) },
                currentModelGenerationProfileKey = { GenerationPreferences.modelGenerationProfileKey(store.current.selectedChatBackend, store.current.currentChatModelId()) }
            )

            val contextCompressionCoordinator = ContextCompressionCoordinator(
                store = store,
                engine = engine,
                cloudChatProvider = container.cloudChatProvider,
                modelGenerationProfileStore = container.modelGenerationProfileStore,
                hasActiveImageRequest = { container.localImageGenerationCoordinator.activeRequestId() != null },
                manualContextCompressionRequests = container.manualContextCompressionRequests,
                preferences = host.contextCompressionPreferences,
                chatRuns = container.chatRuns,
                getActiveRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
                markLocalConversationContextInvalid = host::hostMarkLocalConversationContextInvalid,
                persistChatSessions = { host.hostPersistChatSessions() },
                showStatus = host::hostShowStatus,
                onLocalInferenceStarted = {
                    container.runtimeAdmission.localConversationContextNeedsInvalidation = true
                    container.runtimeAdmission.localConversationContextInvalidationSequence.incrementAndGet()
                }
            )

            val managedRuntimeReadinessPort = container.createManagedRuntimeReadinessPort(
                store = store,
                imageLibraryBackupJobActive = { container.runtimeAdmission.imageLibraryBackupJob?.isCompleted == false },
                hasActiveImageUpscale = { host.imageUpscaleOwner.hasActiveUpscale() }
            )
            managedRuntimeReadinessPortRef = managedRuntimeReadinessPort

            val contentImportPreviewCoordinator = container.createContentImportPreviewCoordinator(
                store = store,
                scope = scope,
                assistantProfileCoordinator = assistantProfileCoordinator,
                showStatus = host::hostShowStatus
            )

            val mainPostStartupCoordinator = (injectedMainPostStartupCoordinator as? MainPostStartupCoordinator)
                ?: container.createMainPostStartupCoordinator(
                    store = store,
                    scope = scope,
                    engine = engine,
                    imageLibraryStartupReconciliation = host.imageLibraryStartupReconciliation,
                    modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
                    offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
                    chatModelCoordinator = chatModelCoordinator,
                    restorePendingContentImportPreview = { contentImportPreviewCoordinator.restorePendingContentImportPreview() },
                    observeManagedDownloadsAction = { host.hostObserveManagedDownloads() },
                    schedulePersistentPrefixCacheClear = { op, succ, fail -> host.hostSchedulePersistentPrefixCacheClear(op, succ, fail) },
                    persistentPrefixCacheOperationSequence = host.persistentPrefixCacheOperationSequence,
                    managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
                    chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
                    modelIdentityReferenceMigration = host.modelIdentityReferenceMigration,
                    getDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions },
                    setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it },
                    skipInitialModelDiscovery = host.skipInitialModelDiscovery
                )

            val chatSessionManagementCoordinator = (injectedChatSessionManagementCoordinator as? ChatSessionManagementCoordinator)
                ?: container.createChatSessionManagementCoordinator(
                    store = store,
                    scope = scope,
                    rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
                    hasActiveAssistantImageExecutionIn = { state, assistantId -> hasActiveAssistantImageExecutionIn(state, assistantId) },
                    showStatus = host::hostShowStatus,
                    fail = host::hostFail,
                    invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
                    cancelActiveWebSearch = { container.runtimeAdmission.activeWebSearchDeferred?.cancel() },
                    bumpContentImportSelectionRevision = { container.runtimeAdmission.contentImportSelectionRevision.incrementAndGet() },
                    loadModelGenerationParams = { backend, modelId, defaults -> host.hostLoadModelGenerationParams(backend, modelId, defaults) },
                    markLocalConversationContextInvalid = host::hostMarkLocalConversationContextInvalid,
                    continueAssistantImageTurn = assistantImageContinuationCoordinator::continueAssistantImageTurn,
                    persistChatSessions = { host.hostPersistChatSessions(it) }
                )

            val chatInputAndAttachmentCoordinator = (injectedChatInputAndAttachmentCoordinator as? ChatInputAndAttachmentCoordinator)
                ?: container.createChatInputAndAttachmentCoordinator(
                    store = store,
                    scope = scope,
                    showStatus = host::hostShowStatus,
                    fail = host::hostFail,
                    invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
                    awaitImageLibraryStartupReconciliation = { host.imageLibraryStartupReconciliation.await() },
                    imageAssetWriteMutex = container.imageAssetWriteMutex,
                    imageAssetDirectory = container.imageAssetDirectory,
                    imageLibraryCommitPort = { host.imageLibraryCommitPort },
                    imagePromptTokenMeasurementHost = host.imagePromptTokenMeasurementHost,
                    visionAttachmentPort = host.visionAttachmentPort,
                    chatSessionStore = container.chatSessionStore
                )

            val chatExecutionPort = container.createChatGenerationExecutionPort(
                store = store,
                engine = engine,
                chatContextComposer = host.chatContextComposer,
                visionAttachmentPort = host.visionAttachmentPort,
                getActiveWebSearchTaskIdRef = { host.activeWebSearchTaskId },
                setActiveWebSearchTaskIdRef = { host.activeWebSearchTaskId = it },
                getForegroundRecoveryJobRef = { host.foregroundRecoveryJob },
                persistChatSessionsRef = host::hostPersistChatSessions,
                appendAssistantRef = host::hostAppendAssistant,
                flushPendingAssistantOutputRef = host::hostFlushPendingAssistantOutput,
                removePendingAssistantPlaceholderRef = { settlePendingAssistantPlaceholder(port = host.chatTerminalSettlementPort, generationRunId = it) },
                ensureVisibleAssistantReplyAfterCompletionRef = { settleVisibleAssistantReply(port = host.chatTerminalSettlementPort, generationRunId = it) },
                applyWebSearchAnswerGuardsToLastAssistantRef = host.webSearchWorkflowCoordinator::applyWebSearchAnswerGuardsToLastAssistant,
                recordWebSearchCitationAuditRef = host.webSearchWorkflowCoordinator::recordWebSearchCitationAudit,
                attachWebSearchEvidenceToPendingAssistantRef = host.webSearchWorkflowCoordinator::attachWebSearchEvidenceToPendingAssistant,
                attachContextAssemblyTraceRef = { traceJson, generationRunId, sessionId, assistantMessageId ->
                    attachContextAssemblyTrace(
                        uiStateStore = store,
                        chatRuns = container.chatRuns,
                        traceJson = traceJson,
                        generationRunId = generationRunId,
                        sessionId = sessionId,
                        assistantMessageId = assistantMessageId
                    )
                },
                promptContextUsageForRef = contextCompressionCoordinator::promptContextUsageFor,
                appendWebSearchDiagnosticRef = host.webSearchWorkflowCoordinator::appendDiagnostic,
                recordCompletedCharacterMemoryTurnRef = assistantProfileCoordinator::recordCompletedCharacterMemoryTurn,
                summarizeContextForGenerationRef = contextCompressionCoordinator::summarizeContextForGeneration,
                ensureLocalConversationContextInvalidatedRef = host::hostEnsureLocalConversationContextInvalidated,
                loadContextCompressionThresholdRef = contextCompressionCoordinator::loadContextCompressionThreshold,
                handleAssistantImageToolCallsRef = assistantImageContinuationCoordinator::handleAssistantImageToolCalls,
                finishAssistantImageContinuationRef = assistantImageContinuationCoordinator::finishAssistantImageContinuation,
            )

            return MainCoordinatorAssembly(
                modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
                localImageLibraryAndModelCoordinator = localImageLibraryAndModelCoordinator,
                assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
                offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
                agentTuningCoordinator = agentTuningCoordinator,
                chatModelCoordinator = chatModelCoordinator,
                localApiControlPlaneCoordinator = localApiControlPlaneCoordinator,
                modelScopeHubCoordinator = modelScopeHubCoordinator,
                assistantProfileCoordinator = assistantProfileCoordinator,
                contextCompressionCoordinator = contextCompressionCoordinator,
                managedRuntimeReadinessPort = managedRuntimeReadinessPort,
                contentImportPreviewCoordinator = contentImportPreviewCoordinator,
                mainPostStartupCoordinator = mainPostStartupCoordinator,
                chatSessionManagementCoordinator = chatSessionManagementCoordinator,
                chatInputAndAttachmentCoordinator = chatInputAndAttachmentCoordinator,
                chatExecutionPort = chatExecutionPort
            )
        }
    }
}
