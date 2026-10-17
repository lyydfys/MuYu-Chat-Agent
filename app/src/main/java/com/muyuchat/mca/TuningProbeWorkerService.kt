package com.muyuchat.mca

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.QairtExecutionPurpose
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.BatchKvCanaryPolicy
import com.muyuchat.core.tuning.BootstrapLoadCanaryPolicy
import com.muyuchat.core.tuning.CandidateExecutionEnvironment
import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateIsolationPolicy
import com.muyuchat.core.tuning.CandidateProcessBoundary
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.HotExecutionParams
import com.muyuchat.core.tuning.LoadBoundExecutionParams
import com.muyuchat.core.tuning.LongContextNeedleCanaryPolicy
import com.muyuchat.core.tuning.LongContextNeedleEvidence
import com.muyuchat.core.tuning.MinimumTextCanaryPolicy
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.RepeatedCandidateCanaryObservation
import com.muyuchat.core.tuning.SpecializedCanaryProbe
import com.muyuchat.core.tuning.SpecializedCanaryViolation
import com.muyuchat.core.tuning.SpeculativeMtpCanaryPolicy
import com.muyuchat.core.tuning.TuningCandidateCanaryPlanner
import com.muyuchat.core.tuning.TuningExecutionProfile
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disposable process for persisted bootstrap loads and load-bound tuning candidates.
 *
 * The worker never accepts caller prompts. It reconstructs the exact candidate from the local
 * pending journal, runs compile-time canaries, unloads native state, returns bounded evidence, and
 * exits. A synchronous native hang is contained by [HARD_PROCESS_TIMEOUT_MS].
 */
class TuningProbeWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val lock = Any()
    internal val handler = Handler(Looper.getMainLooper())
    internal val serviceEpoch = PROCESS_EPOCH.incrementAndGet()
    internal val processLifecycle = TuningProbeProcessLifecycle()
    private lateinit var modelStore: ModelStoreRepository
    private lateinit var profileStore: ModelRuntimeProfileStore
    internal var scheduledProcessExit: Runnable? = null

    @Volatile
    internal var active: ActiveRequest? = null

    private val binder = object : ITuningProbeWorker.Stub() {
        override fun start(requestJson: String, callback: ITuningProbeWorkerCallback): Boolean {
            val startToken = beginBinderStart() ?: run {
                sendError(callback, "", "service_destroyed", "Tuning probe service is shutting down.")
                return false
            }
            val request = runCatching { TuningProbeWorkerProtocol.parseStart(requestJson) }
                .getOrElse { error ->
                    sendError(
                        callback,
                        requestIdFromMalformedPayload(requestJson),
                        "invalid_request",
                        error.message ?: "Invalid isolated tuning request."
                    )
                    val startReleased = synchronized(lock) {
                        processLifecycle.abandonStart(startToken)
                    }
                    if (startReleased) scheduleProcessExit(PROCESS_EXIT_GRACE_MS)
                    return false
                }
            val next = ActiveRequest(request, callback, startToken)
            var staleExit: Runnable? = null
            val resolution = synchronized(lock) {
                processLifecycle.resolveStart(startToken).also { resolved ->
                    if (resolved == TuningProbeStartResolution.ACCEPTED) {
                        check(active == null) { "Process lifecycle and active request diverged." }
                        staleExit = scheduledProcessExit
                        scheduledProcessExit = null
                        // Publish the request under the same lock that releases
                        // the parse token. There is never an idle killable gap.
                        active = next
                    }
                }
            }
            staleExit?.let(handler::removeCallbacks)
            if (resolution != TuningProbeStartResolution.ACCEPTED) {
                if (resolution == TuningProbeStartResolution.BUSY) {
                    sendError(callback, request.requestId, "worker_busy", "Another tuning probe is active.")
                } else {
                    sendError(
                        callback,
                        request.requestId,
                        "service_destroyed",
                        "Tuning probe service is shutting down."
                    )
                }
                return false
            }
            next.deathRecipient = IBinder.DeathRecipient { cancelForDeadClient(next) }
            try {
                callback.asBinder().linkToDeath(requireNotNull(next.deathRecipient), 0)
            } catch (_: RemoteException) {
                finish(next)
                scheduleProcessExit(0L)
                return false
            }
            val stillActive = synchronized(lock) {
                active === next && !processLifecycle.destroyed
            }
            if (!stillActive) {
                unlinkDeathRecipient(next)
                sendError(
                    callback,
                    request.requestId,
                    "service_destroyed",
                    "Tuning probe service stopped before the request could start."
                )
                return false
            }
            val job = scope.launch {
                val startedAt = SystemClock.elapsedRealtime()
                val watchdog = Runnable {
                    if (synchronized(lock) { active === next }) {
                        val diagnostic = IsolatedNativeFailureDiagnostics.watchdog(
                            stage = next.stage,
                            timeoutMs = TuningProbeWorkerProtocol.HARD_PROCESS_TIMEOUT_MS
                        )
                        sendError(callback, request.requestId, diagnostic.code, diagnostic.message)
                        Process.killProcess(Process.myPid())
                    }
                }
                handler.postDelayed(watchdog, TuningProbeWorkerProtocol.HARD_PROCESS_TIMEOUT_MS)
                try {
                    val result = execute(next, startedAt)
                    if (!next.cancelRequested) sendComplete(callback, result)
                } catch (_: TimeoutCancellationException) {
                    if (!next.cancelRequested) {
                        val diagnostic = IsolatedNativeFailureDiagnostics.timeout(next.stage)
                        sendError(callback, request.requestId, diagnostic.code, diagnostic.message)
                    }
                } catch (_: CancellationException) {
                    // The caller cancelled or died. Never publish partial evidence.
                } catch (error: Throwable) {
                    val diagnostic = IsolatedNativeFailureDiagnostics.classify(error, next.stage)
                    sendError(
                        callback,
                        request.requestId,
                        diagnostic.code,
                        diagnostic.message.take(MAX_ERROR_CHARS)
                    )
                } finally {
                    handler.removeCallbacks(watchdog)
                    finish(next)
                    stopSelf()
                    scheduleProcessExit(PROCESS_EXIT_GRACE_MS)
                }
            }
            synchronized(lock) {
                if (active === next) next.job = job else job.cancel()
            }
            return true
        }

        override fun cancel(requestJson: String): Boolean {
            val requestId = TuningProbeWorkerProtocol.parseCancel(requestJson) ?: return false
            val current = synchronized(lock) {
                active?.takeIf { it.request.requestId == requestId }
            } ?: return false
            current.cancelRequested = true
            current.job?.cancel(CancellationException("Isolated tuning probe cancelled."))
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        modelStore = ModelStoreRepository(applicationContext)
        profileStore = ModelRuntimeProfileStore(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val (current, exit) = synchronized(lock) {
            val values = active to scheduledProcessExit
            active = null
            scheduledProcessExit = null
            processLifecycle.destroy()
            values
        }
        exit?.let(handler::removeCallbacks)
        current?.job?.cancel(CancellationException("Tuning probe service destroyed."))
        current?.let(::unlinkDeathRecipient)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun execute(
        activeRequest: ActiveRequest,
        startedAt: Long
    ): TuningProbeWorkerProtocol.Result {
        val request = activeRequest.request
        emit(activeRequest, startedAt, "journal", "Validating persisted probe journal.")
        val pending = profileStore.pendingTransaction(request.identityKey)
            ?: error("The exact pending tuning transaction is missing.")
        require(pending.journal.transactionId == request.transactionId) {
            "The pending journal belongs to another transaction."
        }
        require(pending.pendingProfile.profileId == request.profileId) {
            "The pending journal points to another profile."
        }
        require(pending.pendingProfile.resolvedLoadSignature == request.resolvedLoadSignature) {
            "The pending profile resolved-load signature changed."
        }
        require(pending.pendingProfile.committedExecutionSignature == request.committedExecutionSignature) {
            "The pending profile committed-execution signature changed."
        }
        val candidateProfile = pending.pendingExecutionProfile
        require(candidateProfile.modelId == request.modelId) { "The pending profile belongs to another model." }
        require(candidateProfile.runtimeIdentity.modelId == request.modelId) {
            "The runtime identity belongs to another model."
        }
        require(candidateProfile.runtimeIdentity.identityHash == request.identityKey) {
            "The runtime identity hash does not match the journal."
        }
        val persistedProbeKind = runCatching {
            JSONObject(pending.pendingProfile.sourceSummaryJson).optString("probeKind")
        }.getOrDefault("")
        require(persistedProbeKind == request.probeKind.name) {
            "The pending profile was staged for another probe kind."
        }
        val tuningProbe = when (request.probeKind) {
            TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE -> {
                val rollbackId = pending.journal.rollbackTargetProfileId
                    ?: error("A load-bound tuning probe requires an exact committed rollback profile.")
                val committedProfile = profileStore.reconstructedProfile(rollbackId)
                    ?: error("The committed rollback profile is unavailable.")
                require(committedProfile.runtimeIdentity.identityHash == request.identityKey) {
                    "The committed and candidate profiles have different runtime identities."
                }
                val candidate = candidateProfile.toIsolatedTuningProfile()
                val committed = committedProfile.toIsolatedTuningProfile()
                val plan = TuningCandidateCanaryPlanner.plan(committed, candidate)
                require(plan.processBoundary == CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED) {
                    "The disposable worker accepts only load-bound candidates."
                }
                val isolation = CandidateIsolationPolicy.assess(
                    plan,
                    CandidateExecutionEnvironment.ISOLATED_PROCESS
                )
                require(isolation.passed) {
                    isolation.violations.joinToString("; ") { it.message }
                }
                candidate to plan
            }
            TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD -> {
                pending.journal.rollbackTargetProfileId?.let { rollbackId ->
                    val rollback = profileStore.reconstructedProfile(rollbackId)
                        ?: error("The optional bootstrap rollback profile is unavailable.")
                    require(rollback.runtimeIdentity.identityHash == request.identityKey) {
                        "The bootstrap rollback profile belongs to another runtime identity."
                    }
                }
                null
            }
        }

        profileStore.updateJournalStage(
            transactionId = request.transactionId,
            state = TuningJournalState.VALIDATING,
            stage = when (request.probeKind) {
                TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE -> "ISOLATED_NATIVE_PROBE"
                TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD -> "BOOTSTRAP_ISOLATED_NATIVE_PROBE"
            }
        )

        val initialModel = modelStore.getModel(request.modelId)
            ?: error("The local model referenced by the pending journal is missing.")
        val validation = modelStore.validateForLoad(initialModel.id)
        require(validation.canLoad) { "Model package validation failed: ${validation.message}" }
        val model = modelStore.getModel(initialModel.id) ?: initialModel
        require(model.id == candidateProfile.modelId) { "The model record changed during validation." }
        if (request.probeKind == TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD) {
            require(model.runtime == ChatModelRuntime.MNN || model.runtime == ChatModelRuntime.LLAMA_CPP) {
                "BOOTSTRAP_LOAD accepts only ordinary MNN or llama.cpp runtimes."
            }
            require(candidateProfile.runtimeIdentity.runtime == model.runtime.toTuningRuntime()) {
                "The persisted bootstrap runtime does not match the model package."
            }
        }

        val installationScopeId = profileStore.installationScopeId()
        val engine = McaInferenceService(
            context = applicationContext,
            installationScopeId = installationScopeId
        )
        val startMemory = readMemoryPoint()
        var loaded = false
        try {
            emit(activeRequest, startedAt, "load", "Loading the exact persisted candidate in :tuning.")
            withTimeout(LOAD_TIMEOUT_MS) {
                engine.loadModel(
                    modelPath = model.path,
                    runtime = model.runtime.toTuningRuntime(),
                    params = model.loadParamsForExecutionProfile(candidateProfile),
                    qairtBundleSha256 = model.sha256.takeIf { model.runtime == ChatModelRuntime.GENIEX_QAIRT },
                    qairtExecutionPurpose = QairtExecutionPurpose.NORMAL,
                    runtimeIdentity = candidateProfile.runtimeIdentity,
                    executionProfile = candidateProfile
                ).getOrThrow()
            }
            loaded = true
            if (activeRequest.cancelRequested) throw CancellationException("Tuning probe cancelled.")

            val loadedSnapshot = engine.parameterSignatureSnapshot()
                ?: error("The isolated runtime did not publish parameter signatures.")
            val signatureEvidence = signatureEvidence(candidateProfile, loadedSnapshot)
            val violations = mutableListOf<SpecializedCanaryViolation>()
            if (!signatureEvidence.matched) {
                violations += SpecializedCanaryViolation(
                    "signature_mismatch",
                    "The native loaded/effective signatures do not match the exact pending candidate."
                )
            }

            if (request.probeKind == TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD) {
                emit(
                    activeRequest,
                    startedAt,
                    "bootstrap_canary",
                    "Running the fixed bootstrap-load canary."
                )
                val bootstrap = runFixedCanary(
                    engine = engine,
                    purpose = "bootstrap_load",
                    prompt = BootstrapLoadCanaryPolicy.prompt,
                    params = candidateProfile.bootstrapCanaryGenerationParams(),
                    timeoutMs = BOOTSTRAP_GENERATION_TIMEOUT_MS
                )
                if (bootstrap.error != null || !BootstrapLoadCanaryPolicy.matches(bootstrap.output)) {
                    violations += SpecializedCanaryViolation(
                        "bootstrap_output_failed",
                        bootstrap.error ?: "The fixed bootstrap-load output contract failed."
                    )
                }
                if (bootstrap.sequenceAfter <= bootstrap.sequenceBefore) {
                    violations += SpecializedCanaryViolation(
                        "bootstrap_generation_not_observed",
                        "The native generation sequence did not advance for BOOTSTRAP_LOAD."
                    )
                }
                val endMemory = peakMemoryPoint(
                    listOf(
                        readMemoryPoint(bootstrap.stats),
                        bootstrap.beforeMemory,
                        bootstrap.afterMemory
                    )
                )
                val lowMemory = startMemory.lowMemory || endMemory.lowMemory || bootstrap.stats.isLowMemory
                if (lowMemory) {
                    violations += SpecializedCanaryViolation(
                        "low_memory",
                        "BOOTSTRAP_LOAD observed Android or native low-memory pressure."
                    )
                }
                val evidence = buildEvidenceJson(
                    request = request,
                    modelArtifactFingerprint = candidateProfile.runtimeIdentity.artifactFingerprint,
                    planProbes = listOf("BOOTSTRAP_LOAD"),
                    changedLoadFields = emptySet(),
                    signatureEvidence = signatureEvidence,
                    runs = listOf(bootstrap),
                    startMemory = startMemory,
                    endMemory = endMemory,
                    violations = violations
                )
                emit(activeRequest, startedAt, "unload", "Unloading the isolated bootstrap runtime.")
                withTimeout(UNLOAD_TIMEOUT_MS) { engine.unloadModel() }
                loaded = false
                return TuningProbeWorkerProtocol.Result(
                    requestId = request.requestId,
                    probeKind = request.probeKind,
                    transactionId = request.transactionId,
                    identityKey = request.identityKey,
                    modelId = request.modelId,
                    profileId = request.profileId,
                    resolvedLoadSignature = request.resolvedLoadSignature,
                    committedExecutionSignature = request.committedExecutionSignature,
                    passed = violations.isEmpty(),
                    signatureMatched = signatureEvidence.matched,
                    output = bootstrap.output,
                    detail = if (violations.isEmpty()) {
                        "The isolated bootstrap load, generation, and unload passed."
                    } else {
                        violations.joinToString("; ") { "${it.code}: ${it.message}" }
                    },
                    runtimeStatsJson = bootstrap.stats.toWorkerJson(bootstrap.nativeStatsJson),
                    evidenceJson = evidence.toString(),
                    startAvailableMemoryBytes = startMemory.availableBytes,
                    startPssBytes = startMemory.pssBytes,
                    startRssBytes = startMemory.rssBytes,
                    endAvailableMemoryBytes = endMemory.availableBytes,
                    endPssBytes = endMemory.pssBytes,
                    endRssBytes = endMemory.rssBytes,
                    lowMemoryTriggered = lowMemory,
                    elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                )
            }

            val (candidate, plan) = requireNotNull(tuningProbe) {
                "The tuning candidate plan was not constructed."
            }
            val runEvidence = mutableListOf<FixedCanaryRun>()
            val beforeMinimumNative = engine.nativeStatsJson()
            emit(activeRequest, startedAt, "minimum_text", "Running the fixed minimum-text canary.")
            val minimum = runFixedCanary(
                engine = engine,
                purpose = "minimum_text",
                prompt = MinimumTextCanaryPolicy.prompt,
                params = CanaryEvaluationParams(maxOutputTokens = 48).toGenerationParams(candidate),
                timeoutMs = GENERATION_TIMEOUT_MS
            )
            runEvidence += minimum
            val minimumPassed = minimum.error == null && MinimumTextCanaryPolicy.matches(minimum.output)
            if (!minimumPassed) {
                violations += SpecializedCanaryViolation(
                    "minimum_text_failed",
                    minimum.error ?: "The fixed minimum-text output contract failed."
                )
            }
            if (minimum.sequenceAfter <= minimum.sequenceBefore) {
                violations += SpecializedCanaryViolation(
                    "minimum_generation_not_observed",
                    "The native generation sequence did not advance for the minimum-text canary."
                )
            }

            if (SpecializedCanaryProbe.REPEATED_BATCH_KV in plan.probes) {
                emit(activeRequest, startedAt, "batch_kv", "Running an independent repeated batch/KV canary.")
                while (runEvidence.count { it.purpose.startsWith("batch_kv") || it.purpose == "minimum_text" } <
                    plan.requiredRepeatedRuns
                ) {
                    runEvidence += runFixedCanary(
                        engine = engine,
                        purpose = "batch_kv_${runEvidence.size + 1}",
                        prompt = MinimumTextCanaryPolicy.prompt,
                        params = CanaryEvaluationParams(maxOutputTokens = 48).toGenerationParams(candidate),
                        timeoutMs = GENERATION_TIMEOUT_MS
                    )
                }
                val repeated = runEvidence
                    .filter { it.purpose == "minimum_text" || it.purpose.startsWith("batch_kv") }
                    .take(plan.requiredRepeatedRuns)
                    .map { run -> run.toRepeatedObservation(candidate, signatureEvidence.matched) }
                violations += BatchKvCanaryPolicy.assess(
                    candidate = candidate,
                    observations = repeated,
                    requiredRuns = plan.requiredRepeatedRuns
                ).violations
            }

            if (SpecializedCanaryProbe.SPECULATIVE_MTP in plan.probes) {
                emit(activeRequest, startedAt, "mtp", "Validating the per-request draft-MTP witness.")
                violations += SpeculativeMtpCanaryPolicy.assess(
                    candidate = candidate,
                    beforeNativeStatsJson = beforeMinimumNative,
                    afterNativeStatsJson = minimum.nativeStatsJson
                ).violations
            }

            if (SpecializedCanaryProbe.LONG_CONTEXT_NEEDLE in plan.probes) {
                val spec = requireNotNull(plan.longContextSpec)
                emit(activeRequest, startedAt, "long_context", "Running the tokenizer-measured needle canary.")
                val fillerTokens = spec.minimumPromptTokens.coerceAtLeast(128)
                val beforeCount = fillerTokens / 2
                val afterCount = fillerTokens - beforeCount
                val fillerBefore = " amber".repeat(beforeCount)
                val fillerAfter = " cobalt".repeat(afterCount)
                val prefixPrompt = buildString {
                    append("Inside the filler there is exactly one line beginning with NEEDLE=. ")
                    append("At the end, return that complete line exactly and nothing else.\nBEGIN_FILLER\n")
                    append(fillerBefore)
                    append("\nNEEDLE=")
                }
                val prefixMeasurement = runFixedCanary(
                    engine = engine,
                    purpose = "long_context_prefix_measure",
                    prompt = prefixPrompt,
                    params = CanaryEvaluationParams(maxOutputTokens = 1).toGenerationParams(candidate),
                    timeoutMs = GENERATION_TIMEOUT_MS
                )
                runEvidence += prefixMeasurement
                val longRun = runFixedCanary(
                    engine = engine,
                    purpose = "long_context_needle",
                    prompt = spec.prompt(fillerBefore, fillerAfter),
                    params = CanaryEvaluationParams(maxOutputTokens = spec.maximumOutputTokens)
                        .toGenerationParams(candidate),
                    timeoutMs = LONG_CONTEXT_TIMEOUT_MS
                )
                runEvidence += longRun
                val native = JSONObject(longRun.nativeStatsJson)
                violations += LongContextNeedleCanaryPolicy.assess(
                    spec,
                    LongContextNeedleEvidence(
                        requestId = longRun.requestId,
                        generationSequenceBefore = longRun.sequenceBefore,
                        generationSequenceAfter = longRun.sequenceAfter,
                        effectiveContextTokens = native.optInt("nCtx", longRun.stats.nCtx),
                        promptTokens = longRun.stats.promptTokens,
                        needleTokenIndex = prefixMeasurement.stats.promptTokens,
                        completionTokens = longRun.stats.completionTokens,
                        contextShifts = native.optInt("contextShifts", -1),
                        output = longRun.output,
                        nativeError = longRun.error ?: native.optString("lastError").takeIf(String::isNotBlank)
                    )
                ).violations
            }

            violations += independentRunViolations(runEvidence)
            val endMemory = peakMemoryPoint(
                buildList {
                    add(readMemoryPoint(runEvidence.lastOrNull()?.stats ?: minimum.stats))
                    runEvidence.forEach { run ->
                        add(run.beforeMemory)
                        add(run.afterMemory)
                    }
                }
            )
            val lowMemory = startMemory.lowMemory || endMemory.lowMemory || runEvidence.any { it.stats.isLowMemory }
            val passed = violations.isEmpty() && !lowMemory
            if (lowMemory) {
                violations += SpecializedCanaryViolation(
                    "low_memory",
                    "The isolated probe observed Android or native low-memory pressure."
                )
            }
            val evidence = buildEvidenceJson(
                request = request,
                modelArtifactFingerprint = candidateProfile.runtimeIdentity.artifactFingerprint,
                planProbes = plan.probes.map { it.name },
                changedLoadFields = plan.changedLoadFields,
                signatureEvidence = signatureEvidence,
                runs = runEvidence,
                startMemory = startMemory,
                endMemory = endMemory,
                violations = violations
            )
            emit(activeRequest, startedAt, "unload", "Unloading the isolated native candidate.")
            withTimeout(UNLOAD_TIMEOUT_MS) { engine.unloadModel() }
            loaded = false
            return TuningProbeWorkerProtocol.Result(
                requestId = request.requestId,
                probeKind = request.probeKind,
                transactionId = request.transactionId,
                identityKey = request.identityKey,
                modelId = request.modelId,
                profileId = request.profileId,
                resolvedLoadSignature = request.resolvedLoadSignature,
                committedExecutionSignature = request.committedExecutionSignature,
                passed = passed,
                signatureMatched = signatureEvidence.matched,
                output = minimum.output,
                detail = if (violations.isEmpty()) {
                    "All required isolated canaries passed."
                } else {
                    violations.joinToString("; ") { "${it.code}: ${it.message}" }
                },
                runtimeStatsJson = minimum.stats.toWorkerJson(minimum.nativeStatsJson),
                evidenceJson = evidence.toString(),
                startAvailableMemoryBytes = startMemory.availableBytes,
                startPssBytes = startMemory.pssBytes,
                startRssBytes = startMemory.rssBytes,
                endAvailableMemoryBytes = endMemory.availableBytes,
                endPssBytes = endMemory.pssBytes,
                endRssBytes = endMemory.rssBytes,
                lowMemoryTriggered = lowMemory,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
            )
        } finally {
            if (loaded) runCatching { withTimeout(UNLOAD_TIMEOUT_MS) { engine.unloadModel() } }
            runCatching { engine.shutdown() }
        }
    }

    private fun signatureEvidence(
        profile: ModelExecutionProfile,
        snapshot: ParameterSignatureSnapshot
    ): SignatureEvidence {
        val matched = profile.matchesExactParameterSignatures(snapshot)
        return SignatureEvidence(
            matched = matched,
            desired = snapshot.desired.digest,
            resolvedLoad = snapshot.resolved.digest,
            activeLoaded = snapshot.active?.digest,
            committedExecution = snapshot.committed.digest,
            runtimeOverride = snapshot.override.digest,
            effectiveExecution = snapshot.effective?.digest
        )
    }

    companion object {
        internal val PROCESS_EPOCH = AtomicLong(0L)
        private const val LOAD_TIMEOUT_MS = 120_000L
        private const val BOOTSTRAP_GENERATION_TIMEOUT_MS = 120_000L
        private const val GENERATION_TIMEOUT_MS = 90_000L
        private const val LONG_CONTEXT_TIMEOUT_MS = 150_000L
        private const val UNLOAD_TIMEOUT_MS = 30_000L
        internal const val PROCESS_EXIT_GRACE_MS = 750L
        private const val MAX_ERROR_CHARS = 1_024
    }
}
