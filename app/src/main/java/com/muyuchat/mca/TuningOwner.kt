package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.benchmark.BenchmarkHistoryRecord
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.engine.EngineLifecycleLease
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateProcessBoundary
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.StagedCandidateSelectionPolicy
import com.muyuchat.core.tuning.TuningCandidateCanaryPlanner
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * C2/插队裁定 2/4/5：智能调参唯一 owner，由 McaAppContainer 工厂构造、构造注入真实依赖（无 host 适配器）。
 * startAgentTuning 体自 MainViewModel 移入（逐字等价）；调参域运行态与逻辑成员归 owner；
 * VM 只保留 intent 委托与别名。内嵌调参端口为具名适配器（不再新增 VM 匿名端口）。
 */
internal class TuningOwner(
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val engine: McaInferenceService,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val modelStore: ModelStoreRepository,
    private val benchmarkHistoryLogger: BenchmarkHistoryLogger,
    private val readinessGate: ManagedRuntimeReadinessRefreshGate,
    private val deviceProfileReader: DeviceProfileReader,
    private val application: Application,
    private val advisor: AgentAdvisor,
    private val admission: RuntimeAdmissionState
) {
    // 调参域运行态（原 VM 字段迁入；VM 以 get/set 别名共享同实例）。
    var adaptiveTuningJob: Job? = null

    val adaptiveTuningPauseRequested = AtomicBoolean(false)

    val adaptiveTuningCancelRequested = AtomicBoolean(false)

    var activeTuningJobId: String? = null

    // 调参端口：具名适配器，只持有真实依赖。
    private val candidateCanaryResultPort: CandidateCanaryResultPort =
        CandidateCanaryResultPortAdapter(deviceProfileReader)

    val canaryPort: TuningCanaryPort =
        TuningCanaryPortAdapter(store, deviceProfileReader, engine, candidateCanaryResultPort)

    fun startAgentTuning(
        debugMode: AgentDebugMode,
        autoApply: Boolean = true,
        precreatedJob: TuningJobEntity? = null,
        preferenceOverride: UserPreference? = null
    ): Boolean {
        if (adaptiveTuningJob?.isActive == true) {
            store.showStatus("已有调优任务在运行。")
            return false
        }
        val identity = admission.activeRuntimeIdentity
        val model = admission.activeModelForRuntimeProfile
        val committed = engine.activeExecutionProfile()
        if (identity == null || model == null || committed == null) {
            store.showStatus("请先通过正式模型页加载本地模型，再开始智能调参。")
            return false
        }
        require(precreatedJob == null || precreatedJob.identityKey == identity.identityHash) {
            "预创建调优任务不属于当前 runtime identity"
        }
        require(precreatedJob == null || precreatedJob.autoApplyLoadChanges == autoApply) {
            "预创建调优任务的 autoApply 授权不匹配"
        }
        adaptiveTuningPauseRequested.set(false)
        adaptiveTuningCancelRequested.set(false)
        activeTuningJobId = precreatedJob?.jobId
        adaptiveTuningJob = scope.launch(Dispatchers.IO) {
            var jobId: String? = precreatedJob?.jobId
            var transactionId: String? = null
            var staged = false
            var retainStagedJob = false
            var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
            try {
                val device = deviceProfileReader.read()
                val capabilities = modelTuningCapabilities(
                    model,
                    identity,
                    model.runtime == ChatModelRuntime.GENIEX_QAIRT
                )
                val recommendation = buildAdaptiveTuningRecommendation(
                    model = model,
                    identity = identity,
                    device = device,
                    capabilities = capabilities,
                    preference = preferenceOverride ?: store.current.preference
                )
                admission.activeAdaptiveRecommendation = recommendation
                val tuningDepth = debugMode.searchDepth()
                // RuntimeParameterAdapter.userOverrides also contains values
                // supplied by an automatically generated baseline. Only this
                // durable set is proven to originate from an explicit user edit.
                val explicitUserOverrides = admission.runtimeUserOverrideFields.toSet()
                // Search starts from the exact active profile so an automatic
                // candidate can never reset a user-loaded context or another
                // already effective execution value back to a recommendation.
                val searchBase = asTuningExecutionProfile(committed, 
                    kind = recommendation.executionProfile.kind,
                    verificationLevel = ProfileVerificationLevel.COMPATIBLE,
                    reason = "以当前 active profile 为逐阶段调参基线。"
                )
                val searchStages = agentTuningStages(
                    depth = tuningDepth,
                    capabilities = capabilities,
                    userOverrides = explicitUserOverrides
                )
                if (searchStages.isEmpty()) {
                    precreatedJob?.let { queued ->
                        runtimeProfileStore.transitionTuningJob(
                            jobId = queued.jobId,
                            state = PersistedTuningJobState.FAILED,
                            phase = "NO_TUNABLE_FIELDS",
                            failureCode = "NO_TUNABLE_FIELDS",
                            failureSummary = "当前运行时没有可安全自动搜索且未被用户锁定的执行字段。"
                        )
                    }
                    store.update {
                        it.copy(
                            tuningJobState = if (precreatedJob == null) {
                                AgentTuningJobState.SUCCEEDED
                            } else {
                                AgentTuningJobState.FAILED
                            },
                            tuningPhase = "没有可自动搜索的执行字段",
                            tuningCandidateProgress = AgentCandidateProgress(),
                            busy = false,
                            autoTuningInProgress = false,
                            statusMessage = "当前运行参数均为用户自定义值，或当前 runtime 没有可安全自动搜索的字段；未修改任何参数。"
                        )
                    }
                    return@launch
                }
                val plannedCandidateTotal = searchStages.sumOf { stage ->
                    buildAgentTuningStageCandidates(
                        stage = stage,
                        base = searchBase,
                        capabilities = capabilities,
                        cpuCores = device.cpuCores,
                        estimatedBigCores = device.estimatedBigCores,
                        depth = tuningDepth,
                        userOverrides = explicitUserOverrides,
                        profileIdPrefix = "plan",
                        revision = committed.revision + 1
                    ).size
                }
                val safety = SafetyEnvelope.forDevice(device).assess(measurementPoint(device))
                if (!safety.passed) error(safety.violations.joinToString("；") { it.message })
                val job = precreatedJob ?: runtimeProfileStore.createTuningJob(
                    identityKey = identity.identityHash,
                    autoApplyLoadChanges = autoApply,
                    phase = "QUEUED"
                )
                jobId = job.jobId
                activeTuningJobId = job.jobId
                runtimeProfileStore.transitionTuningJob(job.jobId, PersistedTuningJobState.RUNNING, "DISCOVERY")
                awaitTuningBoundary(job.jobId)
                store.update {
                    it.copy(
                        busy = true,
                        autoTuningInProgress = true,
                        tuningJobState = AgentTuningJobState.RUNNING,
                        tuningPhase = "候选搜索",
                        tuningEtaSeconds = estimateTuningEtaSeconds(model, debugMode),
                        tuningCandidateProgress = AgentCandidateProgress(
                            total = plannedCandidateTotal,
                            currentCandidate = tuningExecutionParameterSummary(searchBase)
                        ),
                        pendingProfile = null,
                        statusMessage = "${debugMode.label}：正在执行 ${searchStages.joinToString(" → ") { stage -> stage.label }} 逐阶段有界搜索…"
                    )
                }
                // Probe a bounded set of real runtime candidates. Each probe
                // is authorized, measured, correctness-gated, and rolled back
                // before the next candidate, so only the selected candidate is
                // persisted as active/LKG. This keeps tuning deterministic and
                // prevents a fast but unsafe candidate from winning by speed
                // alone.
                val probeCanaryParams = recommendation.canaryParams.copy(
                    maxOutputTokens = recommendation.canaryParams.maxOutputTokens.coerceIn(32, 48)
                )
                var eligibleCandidates = 0
                var completedCandidates = 0
                var rejectedCandidates = 0
                var currentBest = searchBase
                var selectedProbeResult: CandidateCanaryResult? = null
                val selectedStageSummaries = mutableListOf<String>()
                val probeRevisionBase = maxOf(
                    committed.revision + 1L,
                    (runtimeProfileStore.profiles(identity.identityHash).maxOfOrNull { it.revision } ?: 0L) + 1L
                )
                for ((stageIndex, stage) in searchStages.withIndex()) {
                    val stageCandidates = buildAgentTuningStageCandidates(
                        stage = stage,
                        base = currentBest,
                        capabilities = capabilities,
                        cpuCores = device.cpuCores,
                        estimatedBigCores = device.estimatedBigCores,
                        depth = tuningDepth,
                        userOverrides = explicitUserOverrides,
                        profileIdPrefix = "probe-${job.jobId.replace("-", "").take(12)}-$stageIndex",
                        revision = committed.revision + 1
                    )
                    if (stageCandidates.isEmpty()) continue
                    val measuredStage = mutableListOf<Pair<AgentTuningStageCandidate, CandidateCanaryResult>>()
                    store.update {
                        it.copy(
                            tuningPhase = "${stage.label}候选搜索",
                            statusMessage = "${debugMode.label}：开始 ${stage.label} 阶段；仅从上一阶段最佳配置继续。"
                        )
                    }
                    for ((candidateIndex, candidate) in stageCandidates.withIndex()) {
                        awaitTuningBoundary(job.jobId)
                        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
                        val summary = candidate.executionSummary
                        store.update {
                            it.copy(
                                tuningPhase = "${stage.label} ${candidateIndex + 1}/${stageCandidates.size}",
                                tuningCandidateProgress = AgentCandidateProgress(
                                    completed = completedCandidates,
                                    total = plannedCandidateTotal,
                                    currentCandidate = "${stage.label} · $summary",
                                    passed = eligibleCandidates,
                                    rejected = rejectedCandidates
                                ),
                                statusMessage = "${debugMode.label}：正在验证 ${stage.label} · $summary"
                            )
                        }
                        val canaryPlan = TuningCandidateCanaryPlanner.plan(searchBase, candidate.profile)
                        val result = when (canaryPlan.processBoundary) {
                            CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH -> {
                                error("候选与当前 committed profile 的 runtime identity 不一致")
                            }
                            CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED -> {
                                // Room keeps terminal probe records for crash audit and enforces a
                                // unique revision per identity. Give every disposable probe its own
                                // identity while retaining the exact authoritative signatures.
                                val probeToken = UUID.randomUUID().toString().replace("-", "").take(12)
                                val isolatedProfile = candidate.profile.copy(
                                    engineProfile = candidate.profile.engineProfile.copy(
                                        profileId = "probe-${job.jobId.replace("-", "").take(12)}-$probeToken",
                                        revision = probeRevisionBase + completedCandidates
                                    ),
                                    reason = "${candidate.profile.reason} Isolated persisted load-bound probe."
                                )
                                runIsolatedTuningCandidateCanary(
                                    candidate = isolatedProfile,
                                    committed = committed,
                                    model = model,
                                    jobId = job.jobId,
                                    stage = stage.name,
                                    onProgress = { progress ->
                                        store.update { state ->
                                            if (state.autoTuningInProgress) {
                                                state.copy(
                                                    tuningPhase = "${stage.label} · ${progress.stage}",
                                                    statusMessage = "${stage.label} 隔离探测：${progress.message}"
                                                )
                                            } else {
                                                state
                                            }
                                        }
                                    }
                                )
                            }
                            CandidateProcessBoundary.CALLER_PROCESS_ALLOWED -> {
                                var probeLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
                                try {
                                    probeLease = engine.acquireExclusiveLifecycleLease()
                                    val authorization = engine.stagePendingExecutionProfile(
                                        transactionId = "probe-hot-${job.jobId}-$stageIndex-$candidateIndex-${UUID.randomUUID()}",
                                        profile = candidate.profile.engineProfile,
                                        rollbackTargetProfileId = committed.profileId
                                    )
                                    runCandidateCanary(
                                        candidate = candidate.profile,
                                        canaryParams = probeCanaryParams,
                                        authorization = authorization,
                                        disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                                        lifecycleLease = probeLease
                                    )
                                } finally {
                                    runCatching { probeLease?.release() }
                                    // Hot-only probes still return to the committed execution values
                                    // before the next stage candidate starts.
                                    if (engine.activeExecutionProfile()?.profileId != committed.profileId) {
                                        restoreExactRuntimeProfile(model, committed)
                                    }
                                }
                            }
                        }
                        measuredStage += candidate to result
                        if (canaryScore(port = canaryPort, result).eligible) {
                            eligibleCandidates++
                        } else {
                            rejectedCandidates++
                        }
                        completedCandidates++
                        store.update {
                            it.copy(
                                tuningCandidateProgress = AgentCandidateProgress(
                                    completed = completedCandidates,
                                    total = plannedCandidateTotal,
                                    currentCandidate = "${stage.label} · $summary",
                                    passed = eligibleCandidates,
                                    rejected = rejectedCandidates
                                ),
                                statusMessage = "${debugMode.label}：已验证 $completedCandidates/$plannedCandidateTotal；${stage.label} · $summary"
                            )
                        }
                    }
                    val selectedStageCandidate = StagedCandidateSelectionPolicy.selectBestEligible(
                        stage = stage,
                        candidates = measuredStage,
                        scoreOf = { (_, result) -> canaryScore(port = canaryPort, result) },
                        contextTokensOf = { (candidate, _) -> candidate.profile.loadBound.nCtx ?: 0 }
                    ) ?: error("${stage.label}没有候选同时通过正确性、安全、签名和稳定性门槛")
                    currentBest = selectedStageCandidate.first.profile
                    selectedProbeResult = selectedStageCandidate.second
                    val selectedSummary = tuningExecutionParameterSummary(currentBest)
                    selectedStageSummaries += "${stage.label}：$selectedSummary"
                    store.update {
                        it.copy(
                            tuningPhase = "${stage.label}已选定",
                            tuningCandidateProgress = AgentCandidateProgress(
                                completed = completedCandidates,
                                total = plannedCandidateTotal,
                                currentCandidate = "${stage.label} · $selectedSummary",
                                passed = eligibleCandidates,
                                rejected = rejectedCandidates
                            ),
                            statusMessage = "${stage.label}阶段最佳已选定；下一阶段将从该配置继续：$selectedSummary"
                        )
                    }
                }
                val finalProbeResult = selectedProbeResult
                    ?: error("没有生成可执行的逐阶段候选")
                // Disposable load-bound probes are retained as terminal audit records, so the
                // selected production transaction receives a fresh profile id/revision. Native
                // equivalence is enforced by the two authoritative execution signatures below.
                val candidateProfile = currentBest.copy(
                    engineProfile = currentBest.engineProfile.copy(
                        profileId = "tuning-${job.jobId.replace("-", "").take(16)}-${UUID.randomUUID().toString().take(8)}",
                        revision = probeRevisionBase + maxOf(plannedCandidateTotal, completedCandidates) + 1L
                    )
                )
                finalProbeResult.testedResolvedLoadSignature?.let { tested ->
                    require(tested == candidateProfile.engineProfile.resolvedLoadSignature.digest) {
                        "选定 profile 的 resolved-load signature 与隔离探测证据不一致"
                    }
                }
                finalProbeResult.testedCommittedExecutionSignature?.let { tested ->
                    require(tested == candidateProfile.engineProfile.committedExecutionSignature.digest) {
                        "选定 profile 的 committed-execution signature 与隔离探测证据不一致"
                    }
                }
                val selectedThreads = candidateProfile.hotExecution.nThreads
                val finalExecutionSummary = tuningExecutionParameterSummary(candidateProfile)
                val finalCandidateTotal = maxOf(plannedCandidateTotal, completedCandidates)
                transactionId = "tuning-${job.jobId}"
                val snapshot = candidateProfile.engineProfile.toPersistedExecutionProfileSnapshot(
                    parentCommittedProfileId = committed.profileId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    sourceSummaryJson = JSONObject()
                        .put("mode", debugMode.name)
                        .put("source", "adaptive_candidate")
                        .put("candidateCount", completedCandidates)
                        .put("passedCount", eligibleCandidates)
                        .put("rejectedCount", rejectedCandidates)
                        .put("stages", JSONArray(searchStages.map { stage -> stage.name }))
                        .put("selectedStages", JSONArray(selectedStageSummaries))
                        .put("userOverrideFields", JSONArray(explicitUserOverrides.sorted()))
                        .put("selectedThreads", selectedThreads)
                        .put("testedProbeProfileId", finalProbeResult.testedProfileId)
                        .put("executionSummary", finalExecutionSummary)
                        .toString()
                )
                runtimeProfileStore.stageCandidate(
                    snapshot = snapshot,
                    transactionId = transactionId,
                    rollbackTargetProfileId = committed.profileId,
                    job = job.copy(state = PersistedTuningJobState.RUNNING.name, phase = "DISCOVERY")
                )
                staged = true
                runtimeProfileStore.recordMeasurement(
                    candidateMeasurement(
                        profileId = candidateProfile.profileId,
                        jobId = job.jobId,
                        phase = "BOUNDED_PROBE",
                        result = finalProbeResult
                    )
                )
                if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
                if (!job.autoApplyLoadChanges) {
                    val pausedJob = runtimeProfileStore.transitionTuningJob(
                        jobId = job.jobId,
                        state = PersistedTuningJobState.PAUSED,
                        phase = "STAGED_AWAITING_APPLY"
                    )
                    admission.activeAdaptiveRecommendation = recommendation.copy(executionProfile = candidateProfile)
                    retainStagedJob = true
                    store.update { state ->
                        state.copy(
                            tuningJobState = agentJobState(pausedJob),
                            tuningPhase = "候选已暂存，等待应用",
                            tuningEtaSeconds = null,
                            tuningCandidateProgress = AgentCandidateProgress(
                                completed = completedCandidates,
                                total = finalCandidateTotal,
                                currentCandidate = finalExecutionSummary,
                                passed = eligibleCandidates,
                                rejected = rejectedCandidates
                            ),
                            pendingProfile = AgentPendingProfile(
                                profileId = candidateProfile.profileId,
                                revision = candidateProfile.revision,
                                summary = "已通过逐阶段候选探测 · $finalExecutionSummary",
                                readyToApply = true
                            ),
                            busy = false,
                            autoTuningInProgress = false,
                            statusMessage = "逐阶段候选已通过硬门槛并暂存；autoApply=false，等待显式 apply：$finalExecutionSummary"
                        )
                    }
                    return@launch
                }
                runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.APPLYING, "CANDIDATE_APPLYING")
                lease = engine.acquireExclusiveLifecycleLease()
                val authorization = engine.stagePendingExecutionProfile(
                    transactionId = transactionId,
                    profile = candidateProfile.engineProfile,
                    rollbackTargetProfileId = committed.profileId
                )
                runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "CORRECTNESS_CANARY")
                runtimeProfileStore.transitionTuningJob(job.jobId, PersistedTuningJobState.VALIDATING, "CORRECTNESS_CANARY")
                val result = runCandidateCanary(
                    candidate = candidateProfile,
                    canaryParams = recommendation.canaryParams,
                    authorization = authorization,
                    disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                    lifecycleLease = lease
                )
                runtimeProfileStore.recordMeasurement(
                    candidateMeasurement(
                        profileId = candidateProfile.profileId,
                        jobId = job.jobId,
                        phase = "COMMIT_CANARY",
                        result = result
                    )
                )
                if (adaptiveTuningCancelRequested.get()) {
                    error("用户取消调优")
                }
                val commitScore = canaryScore(port = canaryPort, result)
                if (!commitScore.eligible) {
                    error(commitScore.reason ?: result.detail)
                }
                engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("调优 lease 已释放"))
                admission.activeAdaptiveRecommendation = recommendation.copy(executionProfile = candidateProfile)
                val signatures = engine.parameterSignatureSnapshot()
                    ?: error("候选提交缺少签名快照")
                runtimeProfileStore.commitCandidate(
                    transactionId = transactionId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    activeLoadedSignature = signatures.active?.digest ?: signatures.resolved.digest,
                    effectiveExecutionSignature = signatures.effective?.digest ?: signatures.committed.digest
                )
                val benchmark = BenchmarkResult(
                    ttftMs = result.stats.ttftMs,
                    promptTokens = result.stats.promptTokens,
                    prefillMs = result.stats.prefillMs,
                    prefillTps = result.stats.prefillTps,
                    genTokens = result.stats.completionTokens,
                    decodeMs = result.stats.decodeMs,
                    decodeTps = result.stats.decodeTps,
                    e2eTps = result.stats.e2eTps,
                    cacheReuseHit = result.stats.cacheReuseHit,
                    cacheReusedTokens = result.stats.cacheReusedTokens,
                    cacheReuseReason = result.stats.cacheReuseReason,
                    nativePssKb = result.stats.nativePssKb,
                    processRssKb = result.stats.processRssKb,
                    availMemKb = result.stats.availMemKb,
                    totalMemKb = result.stats.totalMemKb,
                    modelMemoryBudgetKb = result.stats.modelMemoryBudgetKb,
                    bestThreadCount = selectedThreads,
                    stable = true
                )
                appendBenchmarkHistoryFor(store.current, benchmarkHistoryLogger, device, benchmark, mergedExecutionProfile(store.current.params, candidateProfile.engineProfile))
                modelStore.markLoaded(model.id)
                val refreshedModels = modelStore.listModels()
                readinessGate.invalidate()
                val profileState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
                store.update { state ->
                    state.copy(
                        models = refreshedModels,
                        params = mergedExecutionProfile(state.params, candidateProfile.engineProfile),
                        benchmark = benchmark,
                        benchmarkHistory = benchmarkHistoryLogger.recent(),
                        profileId = candidateProfile.profileId,
                        revision = candidateProfile.revision,
                        profileRecordState = AgentProfileRecordState.COMMITTED,
                        verification = AgentProfileVerification.COMPATIBLE,
                        reloadRequired = false,
                        pendingProfile = null,
                        rollbackProfile = profileState?.activeProfile?.parentCommittedProfileId?.let {
                            AgentRollbackProfile(it, null, "可回退到上一稳定 profile", true)
                        },
                        tuningJobState = AgentTuningJobState.SUCCEEDED,
                        tuningPhase = "已提交 active/LKG",
                        tuningEtaSeconds = 0L,
                        tuningCandidateProgress = AgentCandidateProgress(
                            completed = completedCandidates,
                            total = finalCandidateTotal,
                            currentCandidate = finalExecutionSummary,
                            passed = eligibleCandidates,
                            rejected = rejectedCandidates
                        ),
                        busy = false,
                        autoTuningInProgress = false,
                        lastAutoTuningSummary = "${debugMode.label}按 ${searchStages.joinToString(" → ") { stage -> stage.label }} 完成逐阶段搜索并提交本机 profile：$finalExecutionSummary",
                        nativeStatsJson = engine.nativeStatsJson(),
                        logs = engine.recentLogs(),
                        statusMessage = "智能调参完成：逐阶段候选通过正确性、安全和签名核对，已原子提交：$finalExecutionSummary"
                    )
                }
            } catch (error: Throwable) {
                val message = error.message ?: "调优失败"
                val canceled = adaptiveTuningCancelRequested.get() || error is CancellationException
                withContext(NonCancellable) {
                    if (staged && transactionId != null) {
                        runCatching {
                            val recovery = runtimeProfileStore.rejectCandidate(
                                transactionId = transactionId,
                                failureStage = "TUNING",
                                failureCode = if (canceled) "USER_CANCELED" else "CANDIDATE_REJECTED",
                                failureSummary = message
                            )
                            lease?.release()
                            lease = null
                            val restoredProfileId = recovery.rollbackProfileId?.let { rollbackId ->
                                val rollback = runtimeProfileStore.reconstructedProfile(rollbackId)
                                    ?: committed.takeIf { it.profileId == rollbackId }
                                if (rollback != null && restoreExactRuntimeProfile(model, rollback)) rollbackId else null
                            }
                            runtimeProfileStore.completeRecovery(transactionId, restoredProfileId)
                        }
                    }
                    jobId?.let { persistedJobId ->
                        runCatching {
                            val persisted = runtimeProfileStore.tuningJob(persistedJobId)
                            if (persisted != null && persisted.state !in setOf(
                                    PersistedTuningJobState.SUCCEEDED.name,
                                    PersistedTuningJobState.FAILED.name
                                )
                            ) {
                                runtimeProfileStore.transitionTuningJob(
                                    jobId = persistedJobId,
                                    state = PersistedTuningJobState.FAILED,
                                    phase = if (canceled) "USER_CANCELED" else "FAILED",
                                    failureCode = if (canceled) "USER_CANCELED" else "TUNING_FAILED",
                                    failureSummary = message
                                )
                            }
                        }
                    }
                }
                store.update {
                    it.copy(
                        busy = false,
                        autoTuningInProgress = false,
                        tuningJobState = AgentTuningJobState.FAILED,
                        tuningPhase = "已恢复稳定 profile",
                        tuningEtaSeconds = null,
                        tuningCandidateProgress = it.tuningCandidateProgress.copy(rejected = it.tuningCandidateProgress.rejected + 1),
                        statusMessage = if (canceled) "调优已取消，稳定 profile 已恢复。" else "调优未通过：$message"
                    )
                }
            } finally {
                runCatching { lease?.release() }
                if (!retainStagedJob) activeTuningJobId = null
                adaptiveTuningJob = null
                adaptiveTuningPauseRequested.set(false)
                adaptiveTuningCancelRequested.set(false)
            }
        }
        return true
    }

    suspend fun awaitTuningBoundary(jobId: String) {
        while (adaptiveTuningPauseRequested.get() && !adaptiveTuningCancelRequested.get()) {
            runCatching { runtimeProfileStore.pauseTuningJob(jobId) }
            store.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待续跑") }
            delay(250L)
        }
        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
        runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
    }

    fun estimateTuningEtaSeconds(model: ModelManifest, mode: AgentDebugMode): Long {
        val history = store.current.benchmarkHistory
            .filter { it.modelId == model.id }
        return tuningEtaSecondsFromPreviousLoad(history.firstOrNull()?.result?.loadMs, mode)
    }

    /**
     * The ViewModel side of [RuntimeProfileTransactionPort]: one route per member, no logic of its
     * own. Both decisions moved to [RuntimeProfileTransactionOperations], which is now the only place
     * a runtime profile is applied or rolled back.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name with the ViewModel member they forward to, and an unqualified call would recurse.
     */

    suspend fun restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean {
        val active = engine.activeExecutionProfile()
        if (engine.stats.value.loaded &&
            active?.profileId == profile.profileId &&
            active.resolvedLoadSignature.digest == profile.resolvedLoadSignature.digest &&
            active.committedExecutionSignature.digest == profile.committedExecutionSignature.digest
        ) return true
        val qairtSha = currentQairtBundleSha256(model, modelStore.listModels())
        return engine.loadModel(
            modelPath = model.path,
            runtime = model.runtime.toLocalChatRuntime(),
            params = model.loadParamsForExecutionProfile(profile),
            qairtBundleSha256 = qairtSha,
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile
        ).isSuccess
    }

    fun failedIsolatedTuningCandidateResult(
        candidate: TuningExecutionProfile,
        start: MeasurementPoint,
        error: Throwable
    ): CandidateCanaryResult {
        return failedIsolatedCanaryResult(port = canaryPort, candidate = candidate, start = start, error = error)
    }


/** Delegates to [jsonOptionalFiniteDouble]. Signature kept verbatim. */

    fun modelTuningCapabilities(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        qairtAdmissionPassed: Boolean
    ): ModelTuningCapabilities = discoverModelTuningCapabilities(
        model = model,
        identity = identity,
        qairtAdmissionPassed = qairtAdmissionPassed
    )

    fun buildAdaptiveTuningRecommendation(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        device: DeviceProfile,
        capabilities: ModelTuningCapabilities,
        preference: UserPreference = store.current.preference
    ): AdaptiveTuningRecommendation {
        return advisor.recommendAdaptiveForModel(
            device = device,
            model = model,
            runtimeIdentity = identity,
            capabilities = capabilities,
            preference = preference,
            lastDecodeTps = null
        )
    }

    suspend fun runIsolatedTuningCandidateCanary(
        candidate: TuningExecutionProfile,
        committed: ModelExecutionProfile,
        model: ModelManifest,
        jobId: String,
        stage: String,
        onProgress: (TuningProbeWorkerProtocol.Progress) -> Unit
    ): CandidateCanaryResult {
        require(candidate.identityHash == committed.runtimeIdentity.identityHash) {
            "隔离探测候选与 committed profile 身份不一致"
        }
        val probeStart = measurementPoint()
        val transactionId = "probe-$jobId-${UUID.randomUUID()}"
        val snapshot = candidate.engineProfile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = committed.profileId,
            verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
            sourceSummaryJson = JSONObject()
                .put("kind", "isolated_load_bound_probe")
                .put("probeKind", TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE.name)
                .put("jobId", jobId)
                .put("stage", stage)
                .put("profileId", candidate.profileId)
                .put("resolvedLoadSignature", candidate.engineProfile.resolvedLoadSignature.digest)
                .put("committedExecutionSignature", candidate.engineProfile.committedExecutionSignature.digest)
                .toString()
        )
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = transactionId,
            rollbackTargetProfileId = committed.profileId,
            job = null
        )
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.APPLYING,
            stage = "MAIN_UNLOAD_BEFORE_ISOLATED_PROBE"
        )

        var workerResult: TuningProbeWorkerProtocol.Result? = null
        var failure: Throwable? = null
        var restored = false
        try {
            // Do not keep a second 35B native mapping in the app process while :tuning loads the
            // candidate. unloadModel() also stops and joins any in-flight UI/API generation.
            engine.unloadModel()
            workerResult = TuningProbeWorkerClient(application).probe(
                probeKind = TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE,
                transactionId = transactionId,
                identityKey = candidate.identityHash,
                modelId = model.id,
                profileId = candidate.profileId,
                resolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
                committedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
                onProgress = onProgress
            )
        } catch (error: CancellationException) {
            // withTimeout uses CancellationException for a candidate-local timeout even while the
            // parent tuning job remains active. Only a real caller/job cancellation may abort the
            // whole search; a timed-out disposable worker is simply an ineligible candidate.
            currentCoroutineContext().ensureActive()
            failure = error
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                restored = runCatching { restoreExactRuntimeProfile(model, committed) }.getOrDefault(false)
                if (restored) {
                    val result = workerResult
                    val summary = when {
                        failure != null -> failure?.message ?: "isolated probe failed"
                        result == null -> "isolated probe returned no evidence"
                        result.passed -> "isolated probe passed; committed profile restored"
                        else -> result.detail
                    }
                    runtimeProfileStore.completeIsolatedProbe(
                        transactionId = transactionId,
                        passed = result?.passed == true,
                        summary = summary
                    )
                }
            }
        }
        check(restored) {
            "隔离探测结束后无法恢复 committed profile；pending journal 已保留供进程恢复"
        }
        failure?.let { error ->
            return failedIsolatedTuningCandidateResult(candidate, probeStart, error)
        }
        return requireNotNull(workerResult).canaryResultFrom(port = canaryPort, candidate = candidate)
    }

    /**
     * The ViewModel side of [TuningCanaryPort]. The canary result port object stays here: it is the
     * adapter that binds that interface to this ViewModel.
     */

    suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: com.muyuchat.core.tuning.CanaryEvaluationParams,
        authorization: com.muyuchat.core.engine.LoadAuthorization,
        disposition: com.muyuchat.core.engine.PendingProfileDisposition =
            com.muyuchat.core.engine.PendingProfileDisposition.ROLLBACK_AFTER_REQUEST,
        lifecycleLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
    ): CandidateCanaryResult {
        return runCanary(port = canaryPort, candidate = candidate, canaryParams = canaryParams, authorization = authorization, disposition = disposition, lifecycleLease = lifecycleLease)
    }



/** Delegates to [canaryMeasurement]. Signature kept verbatim. */

    fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity {
        return canaryMeasurement(port = canaryPort, profileId = profileId, jobId = jobId, phase = phase, result = result)
    }

/** Delegates to [asTuningProfile]. Signature kept verbatim. */

    fun measurementPoint(device: DeviceProfile = deviceProfileReader.read()): MeasurementPoint {
        return currentMeasurementPoint(port = canaryPort, device = device)
    }

    /**
     * The ViewModel side of [CandidateCanaryResultPort]: two members, no logic of its own. The
     * conversion moved to [toCandidateCanaryResult], which is now the only place a probe result
     * becomes a canary verdict.
     *
     * `this@MainViewModel.` is spelled out on purpose: the port member shares its name with the
     * ViewModel member it forwards to.
     */

    fun asTuningExecutionProfile(
        profile: ModelExecutionProfile,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile {
        return profile.asTuningProfile(port = canaryPort, kind = kind, verificationLevel = verificationLevel, reason = reason)
    }

/** Delegates to [currentMeasurementPoint]. Signature kept verbatim. */
}

internal fun appendBenchmarkHistoryFor(
    state: MainUiState,
    logger: BenchmarkHistoryLogger,
        device: DeviceProfile,
        result: BenchmarkResult,
        params: GenerationParams
    ) {
        val state = state
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        logger.append(
            BenchmarkHistoryRecord(
                modelId = loadedModel?.id ?: state.loadedModelId,
                modelName = loadedModel?.displayName ?: state.loadedModelName,
                modelPath = loadedModel?.path ?: state.stats.modelPath,
                deviceSummary = "${device.socFamily.name} · ${device.socLabel}",
                paramsJson = params.toJson(),
                result = result
            )
        )
    }


/**
 * C2（插队裁定 2）：调参内嵌端口的具名实现——只依赖真实对象，不再反向捕获 ViewModel。
 */
internal class CandidateCanaryResultPortAdapter(
    private val deviceProfileReader: DeviceProfileReader
) : CandidateCanaryResultPort {
    override fun currentDeviceProfile(): DeviceProfile = deviceProfileReader.read()

    override fun optionalFiniteDouble(root: JSONObject, field: String): Double =
        root.jsonOptionalFiniteDouble(field = field)
}

internal class TuningCanaryPortAdapter(
    private val store: UiStateStore,
    private val deviceProfileReader: DeviceProfileReader,
    private val inferenceEngine: McaInferenceService,
    private val resultPort: CandidateCanaryResultPort
) : TuningCanaryPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (MainUiState) -> MainUiState) {
        store.update(transform)
    }

    override fun currentDeviceProfile(): DeviceProfile = deviceProfileReader.read()

    override val candidateCanaryResultPort: CandidateCanaryResultPort
        get() = resultPort

    override val engine: McaInferenceService
        get() = inferenceEngine
}
