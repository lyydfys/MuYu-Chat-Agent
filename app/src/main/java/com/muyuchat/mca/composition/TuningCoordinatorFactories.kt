package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.*
import kotlinx.coroutines.CoroutineScope

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleAgentTuningCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    engine: McaInferenceService,
    tuningOwner: TuningOwner,
    agentLogger: AgentDecisionLogger,
    benchmarkRunner: BenchmarkRunner,
    diagnosticsOperations: DiagnosticsOperations,
    localApiRuntimeOwner: LocalApiRuntimeOwner,
    modelScopeClient: ModelScopeClient,
    showStatus: (String?) -> Unit,
    fail: (String) -> Unit,
    busy: (String) -> Unit,
    loadModel: (ModelManifest) -> Unit,
    persistModelGenerationParams: (ChatBackend, String?, GenerationParams) -> Unit,
    updateParams: (GenerationParams) -> Unit,
    cancelGenerationJob: () -> UiGenerationCancellation,
    managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
    chatModelCoordinatorProvider: () -> ChatModelCoordinator,
    localApiControlPlaneCoordinatorProvider: () -> LocalApiControlPlaneCoordinator,
): AgentTuningCoordinator = container.createAgentTuningCoordinator(
    store = store,
    scope = scope,
    engine = engine,
    tuningOwner = tuningOwner,
    agentLogger = agentLogger,
    benchmarkRunner = benchmarkRunner,
    diagnosticsOperations = diagnosticsOperations,
    managedRuntimeReadinessPortProvider = managedRuntimeReadinessPortProvider,
    localApiRuntimeOwner = localApiRuntimeOwner,
    modelScopeClient = modelScopeClient,
    cancelPersistedTuningJob = { localApiControlPlaneCoordinatorProvider().cancelPersistedTuningJob(it) },
    activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
    activeAdaptiveRecommendation = { container.runtimeAdmission.activeAdaptiveRecommendation },
    setActiveAdaptiveRecommendation = { container.runtimeAdmission.activeAdaptiveRecommendation = it },
    busy = busy,
    fail = fail,
    showStatus = showStatus,
    persistModelGenerationParams = { backend, modelId, params -> persistModelGenerationParams(backend, modelId, params) },
    updateParams = updateParams,
    loadModel = loadModel,
    timedLoadModel = { model, runtime, loadParams ->
        chatModelCoordinatorProvider().timedLoadModel(model, runtime, loadParams)
    },
    runLocalGenerationSmoke = { params ->
        chatModelCoordinatorProvider().runLocalGenerationSmoke(params)
    },
    cancelGenerationJob = {
        cancelGenerationJob()
        Unit
    },
    activeModelForRuntimeProfile = { container.runtimeAdmission.activeModelForRuntimeProfile },
    getPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId },
    setPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId = it }
)

internal fun assembleLocalApiControlPlaneCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    container: McaAppContainer,
    engine: McaInferenceService,
    tuningOwner: TuningOwner,
    localApiIdempotencyJournalLock: Any,
    agentTuningCoordinator: AgentTuningCoordinator,
): LocalApiControlPlaneCoordinator = LocalApiControlPlaneCoordinator(
    runtimeAdmission = container.runtimeAdmission,
    store = store.runtimeProfileWriter(),
    engine = engine,
    tuningOwner = tuningOwner,
    runtimeProfileStore = container.runtimeProfileStore,
    agentTuningCoordinator = agentTuningCoordinator,
    getActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId },
    setActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId = it },
    getAdaptiveTuningJob = { agentTuningCoordinator.adaptiveTuningJob },
    setAdaptiveTuningJob = { agentTuningCoordinator.adaptiveTuningJob = it },
    getActiveRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
    applyPendingRuntimeTransaction = agentTuningCoordinator::applyPendingRuntimeTransaction,
    rollbackCommittedRuntimeProfile = agentTuningCoordinator::rollbackCommittedRuntimeProfile,
    idempotencyJournalLock = localApiIdempotencyJournalLock,
    application = application,
    scope = scope
)

