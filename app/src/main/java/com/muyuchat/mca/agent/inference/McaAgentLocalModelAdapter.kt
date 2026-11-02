package com.muyuchat.mca.agent.inference

import android.util.Log
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgress
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentStopDispatch
import com.muyuchat.core.engine.EngineLifecycleLeaseHandle
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.mca.facade.ChatExecutionFacade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/** Narrow read/acquire surface over the real engine, so the adapter stays JVM-testable. */
internal interface AgentLocalEngineSurface {
    fun activeExecutionProfile(): ModelExecutionProfile?
    fun parameterSignatureSnapshot(): ParameterSignatureSnapshot?
    suspend fun acquireLifecycleLease(stopActiveGeneration: Boolean): EngineLifecycleLeaseHandle
}

internal class McaInferenceServiceEngineSurface(
    private val engine: com.muyuchat.core.engine.McaInferenceService
) : AgentLocalEngineSurface {
    override fun activeExecutionProfile(): ModelExecutionProfile? = engine.activeExecutionProfile()
    override fun parameterSignatureSnapshot(): ParameterSignatureSnapshot? = engine.parameterSignatureSnapshot()
    override suspend fun acquireLifecycleLease(stopActiveGeneration: Boolean): EngineLifecycleLeaseHandle =
        engine.acquireExclusiveLifecycleLease(stopActiveGeneration = stopActiveGeneration)
}

/** Reads the app ModelRuntimeProfileStore; fails closed when the query itself fails. */
internal fun interface AgentPendingProfileGuard {
    suspend fun hasPendingTransaction(identityHash: String): Boolean
}

internal class RuntimeProfileStorePendingProfileGuard(
    private val store: ModelRuntimeProfileStore
) : AgentPendingProfileGuard {
    override suspend fun hasPendingTransaction(identityHash: String): Boolean =
        runCatching { store.pendingTransaction(identityHash) != null }.getOrDefault(true)
}

/**
 * Local MCA model adapter for Agent V1 (architecture 5.2/5.2.1).
 *
 * Reuses the single production funnel: `ChatExecutionFacade.streamChat` → `ChatExecutionOwner` →
 * `McaInferenceService.streamChat`, under an exclusive engine lifecycle lease acquired
 * non-preemptively. It never constructs an engine, never calls the global stop, and never reuses a
 * request id. Only `Chunk.text` is consumed; reasoning is deliberately ignored.
 */
internal class McaAgentLocalModelAdapter(
    private val engine: AgentLocalEngineSurface,
    private val facade: ChatExecutionFacade,
    private val pendingProfiles: AgentPendingProfileGuard,
    private val retention: AgentModelLeaseRetention,
    private val stopProof: AgentStopProofSource,
    private val scope: CoroutineScope,
    private val authority: AgentAttemptAuthority = AgentAttemptAuthority.AlwaysCurrent,
    private val clock: AgentInferenceClock = SystemAgentInferenceClock,
    private val resourceWaitTimeoutMillis: Long = AgentContract.RESOURCE_WAIT_TIMEOUT_MILLIS,
    private val stopProofTimeoutMillis: Long = DEFAULT_STOP_PROOF_TIMEOUT_MILLIS,
    private val stopGraceMillis: Long = DEFAULT_STOP_GRACE_MILLIS,
    private val requestIds: AgentRequestIdLedger = AgentRequestIdLedger(),
    /**
     * App-scope observer for real lease settlements. It is how a late engine proof that releases
     * the fence reaches `AgentRunOwner.reportStopSettlement`.
     */
    private val leaseSettlements: AgentLeaseSettlementListener? = null,
    private val lateProofWindowMillis: Long = DEFAULT_LATE_PROOF_WINDOW_MILLIS,
    private val lateProofRetryMillis: Long = DEFAULT_LATE_PROOF_RETRY_MILLIS
) : AgentModelPort {

    private val activeLock = Any()
    private val activeAttempts = linkedMapOf<String, ActiveAttempt>()

    override suspend fun infer(
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        val attempt = request.attempt
        val requestId = requestIds.claim(attempt)
            ?: return AgentInferenceErrors.failure(
                code = AgentErrorCodes.INTERNAL_INVARIANT,
                category = AgentErrorCategory.INTERNAL,
                safeMessage = "同一模型尝试被重复执行；requestId 不可复用。",
                attempt = attempt,
                retryability = AgentRetryability.NEVER
            )
        val active = ActiveAttempt(attempt, requestId)
        synchronized(activeLock) { activeAttempts[attempt.attemptId] = active }
        active.attemptJob = currentCoroutineContext()[Job]
        try {
            return runInference(active, request, progress)
        } catch (cancelled: CancellationException) {
            // Structured cancellation is a normal Agent attempt terminal; cleanup below must still
            // dispatch the exact stop and settle the lease.
            return AgentModelResult.Cancelled(null)
        } catch (failure: Throwable) {
            return AgentInferenceErrors.failure(
                code = AgentInferenceErrorCodes.MODEL_GENERATION_FAILED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "本地模型生成异常：" + (failure.message ?: "未知错误").take(160),
                attempt = attempt
            )
        } finally {
            active.finished.set(true)
            synchronized(activeLock) {
                if (activeAttempts[attempt.attemptId] === active) activeAttempts.remove(attempt.attemptId)
            }
            withContext(NonCancellable) { finalizeAttempt(active) }
        }
    }

    /**
     * Dispatches the exact engine stop for every live attempt of [runId].
     *
     * The run owner cancels the execution job itself and then waits for a settlement proof. A local
     * runner that ignores coroutine cancellation never produces that proof, so the engine stop has
     * to be asked for here as well; without it the lease stays held and the run can never leave
     * STOPPING. Returns true when at least one live attempt accepted the stop.
     */
    suspend fun stopRun(runId: String): Boolean {
        val live = synchronized(activeLock) {
            activeAttempts.values.filter { it.attempt.runId == runId && !it.finished.get() }
        }
        var dispatched = false
        for (attempt in live) {
            if (requestStop(attempt.attempt).accepted) dispatched = true
        }
        return dispatched
    }

    override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch {        val active = synchronized(activeLock) {
            activeAttempts[attempt.attemptId]?.takeIf { it.attempt == attempt }
        } ?: return AgentStopDispatch(false, "no live Agent model attempt")
        if (active.finished.get()) return AgentStopDispatch(false, "attempt already finished")
        if (!active.stopRequested.compareAndSet(false, true)) {
            return AgentStopDispatch(true, "stop already requested for this attempt")
        }
        retention.markStopRequested(attempt)
        // Exact attempt only: facade.stopGenerationIfActive(requestId) → engine stopGenerationIfActive.
        val dispatched = runCatching { facade.stopGenerationIfActive(active.requestId) }.getOrDefault(false)
        active.stopDispatched.set(true)
        val job = active.attemptJob
        scope.launch {
            delay(stopGraceMillis)
            if (!active.finished.get()) job?.cancel()
        }
        return AgentStopDispatch(
            accepted = true,
            detail = if (dispatched) "exact engine stop dispatched" else "attempt cancelled before engine stop"
        )
    }

    private suspend fun runInference(
        active: ActiveAttempt,
        request: AgentModelRequest,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        val attempt = request.attempt
        Log.e(
            "McaAgentDiag",
            "run start model=${request.binding.modelId} runtime=${request.binding.runtime} " +
                "hash=${request.binding.assetFingerprint}"
        )
        // Measured, not claimed: the uid/descriptor/socket/RSS facts of the process that will run the
        // attempt. A denied proc entry stays "unknown" instead of looking like isolation.
        Log.e(
            "McaAgentDiag",
            "isolation " + com.muyuchat.mca.agent.platform.readAgentRuntimeIsolation().describe()
        )
        when (authority.currentState(attempt)) {
            AgentAttemptAuthorityState.CURRENT -> Unit
            AgentAttemptAuthorityState.CANCELLED,
            AgentAttemptAuthorityState.SUPERSEDED -> return AgentModelResult.Cancelled(
                AgentInferenceErrors.staleAttempt(attempt)
            )
        }
        // Reject load-bound sampling keys before taking the native resource; the profile-based
        // parse after acquisition is still authoritative for the values actually sent.
        val preValidation = parseAgentSampling(request.samplingJson, request.maxOutputTokens)
        if (preValidation is AgentSamplingParse.Rejected) {
            return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_BINDING_CHANGED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "采样参数包含不允许的加载绑定字段：${preValidation.field}",
                attempt = attempt,
                retryability = AgentRetryability.AFTER_USER_ACTION
            )
        }
        val acquisition = acquireBounded(active, progress)
        if (acquisition !is LeaseAcquisition.Acquired) {
            return when (acquisition) {
                is LeaseAcquisition.TimedOut -> {
                    Log.e("McaAgentDiag", "lease acquisition timed out")
                    AgentInferenceErrors.failure(
                        code = AgentErrorCodes.RESOURCE_BUSY,
                        category = AgentErrorCategory.RESOURCE,
                        safeMessage = "等待本地模型资源超过 ${resourceWaitTimeoutMillis / 1000} 秒，任务已暂停。",
                        attempt = attempt,
                        retryability = AgentRetryability.AFTER_USER_ACTION
                    )
                }

                is LeaseAcquisition.Failed -> {
                    Log.e("McaAgentDiag", "lease acquisition failed: ${acquisition.reason}")
                    AgentInferenceErrors.failure(
                        code = AgentErrorCodes.RESOURCE_BUSY,
                        category = AgentErrorCategory.RESOURCE,
                        safeMessage = "本地模型资源获取失败：" + (acquisition.reason ?: "未知原因").take(160),
                        attempt = attempt
                    )
                }

                LeaseAcquisition.Aborted -> AgentModelResult.Cancelled(
                    AgentInferenceErrors.staleAttempt(attempt)
                )

                is LeaseAcquisition.Acquired -> error("acquired branch handled above")
            }
        }

        // Immediately after acquire: re-check run authority and the stop flag before touching the
        // native engine, then verify the frozen binding and the absence of a pending transaction.
        if (authority.currentState(attempt) != AgentAttemptAuthorityState.CURRENT || active.stopRequested.get()) {
            withContext(NonCancellable) { abandonLeaseBeforeDispatch(attempt) }
            return AgentModelResult.Cancelled(AgentInferenceErrors.staleAttempt(attempt))
        }
        val bindingCheck = verifyBinding(request)
        if (bindingCheck is BindingCheck.Rejected) {
            withContext(NonCancellable) { abandonLeaseBeforeDispatch(attempt) }
            return bindingCheck.result
        }
        Log.e("McaAgentDiag", "binding ok; dispatching engine")
        val profile = engine.activeExecutionProfile()
            ?: run {
                withContext(NonCancellable) { abandonLeaseBeforeDispatch(attempt) }
                return AgentInferenceErrors.failure(
                    code = AgentErrorCodes.MODEL_NOT_LOADED,
                    category = AgentErrorCategory.MODEL,
                    safeMessage = "本地模型尚未加载。",
                    attempt = attempt,
                    retryability = AgentRetryability.AFTER_USER_ACTION
                )
            }
        val activeSampling = activeAgentSampling(profile)
        val sampling = when (
            val parsed = parseAgentSampling(
                request.samplingJson,
                request.maxOutputTokens,
                activeSampling
            )
        ) {
            is AgentSamplingParse.Accepted -> parsed.params
            is AgentSamplingParse.Rejected -> {
                withContext(NonCancellable) { abandonLeaseBeforeDispatch(attempt) }
                return AgentInferenceErrors.failure(
                    code = AgentErrorCodes.MODEL_BINDING_CHANGED,
                    category = AgentErrorCategory.MODEL,
                    safeMessage = "采样参数包含不允许的加载绑定字段：${parsed.field}",
                    attempt = attempt,
                    retryability = AgentRetryability.AFTER_USER_ACTION
                )
            }
        }

        val chatRequest = mapAgentRequestToChatRequest(
            request,
            sampling,
            nPredict = activeSampling.nPredict
        )
        val context = LocalChatExecutionContext(
            requestId = active.requestId,
            // The SAME acquired lease must reach the engine; it is not re-acquired there.
            lifecycleLease = acquisition.handle,
            includeDeviceClockContext = false,
            allowTrustedHotOverride = false,
            pendingProfileDisposition = com.muyuchat.core.engine.PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS
        )
        active.dispatchStarted.set(true)
        retention.markDispatchStarted(attempt)
        safeProgress(progress, AgentModelProgress.Phase("LOCAL_GENERATING"))
        return collectOnce(active, request, chatRequest, context, progress)
    }

    private suspend fun collectOnce(
        active: ActiveAttempt,
        request: AgentModelRequest,
        chatRequest: com.muyuchat.core.engine.ChatRequest,
        context: LocalChatExecutionContext,
        progress: AgentModelProgressSink
    ): AgentModelResult {
        val text = StringBuilder()
        var done: GenerateEvent.Done? = null
        var errorEvent: GenerateEvent.Error? = null
        try {
            facade.streamChat(chatRequest, context).collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        // Only visible text is Agent protocol input; reasoning is ignored by contract.
                        if (event.text.isNotEmpty()) {
                            text.append(event.text)
                            safeProgress(progress, AgentModelProgress.TextDelta(event.text))
                        }
                    }

                    is GenerateEvent.Phase -> {
                        safeProgress(progress, AgentModelProgress.Phase("LOCAL_${event.phase.name}"))
                    }

                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Done -> {
                        done = event
                        active.terminalObserved.set(true)
                        active.completedNaturally.set(true)
                    }

                    is GenerateEvent.Error -> {
                        errorEvent = event
                        active.terminalObserved.set(true)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }

        if (active.stopRequested.get()) return AgentModelResult.Cancelled(null)
        val terminalError = errorEvent
        if (terminalError != null) {
            val code = terminalError.code?.trim()?.uppercase()
            val cancellation = terminalError.errorEnvelope?.category ==
                com.muyuchat.core.engine.contract.ErrorCategory.Cancel ||
                code in CANCELLATION_CODES
            if (cancellation) return AgentModelResult.Cancelled(null)
            Log.e(
                "McaAgentDiag",
                "engine terminal error code=${terminalError.code} message=${terminalError.message?.take(120)}"
            )
            return agentEngineFailure(request.attempt, terminalError.code, terminalError.message)
        }
        val completed = done
        if (completed == null) {
            return AgentInferenceErrors.failure(
                code = AgentErrorCodes.MODEL_TERMINAL_UNVERIFIED,
                category = AgentErrorCategory.MODEL,
                safeMessage = "本地模型流已结束但未收到完整成功 Done，结果未采用。",
                attempt = request.attempt,
                retryability = AgentRetryability.SAFE_RETRY
            )
        }
        if (authority.currentState(request.attempt) != AgentAttemptAuthorityState.CURRENT) {
            return AgentModelResult.Cancelled(AgentInferenceErrors.staleAttempt(request.attempt))
        }
        return AgentModelResult.Success(
            visibleText = text.toString(),
            usage = usageFromStats(completed.stats)
        )
    }

    /** Runs under NonCancellable: idempotent lease settlement and exact stop dispatch. */
    private suspend fun finalizeAttempt(active: ActiveAttempt) {
        val attempt = active.attempt
        val leaseSnapshot = retention.snapshot(attempt)
        if (leaseSnapshot == null) return
        if (!active.dispatchStarted.get()) {
            abandonLeaseBeforeDispatch(attempt)
            return
        }
        if (!active.terminalObserved.get() && !active.stopDispatched.get()) {
            active.stopDispatched.set(true)
            retention.markStopRequested(attempt)
            runCatching { facade.stopGenerationIfActive(active.requestId) }
        }
        val stoppedExecution = active.stopRequested.get() || active.stopDispatched.get()
        // A natural completion already carries the engine's terminal Done: the exact request
        // returned and generation is over. Requiring a separate native-idle probe here blocks the
        // next attempt for runtimes that expose no probe (observed on GGUF/llama_cpp), so the
        // lease fence is released on that terminal fact. Stop paths still demand a real proof.
        val proof = if (active.completedNaturally.get() && !stoppedExecution) {
            AgentStopProof.Confirmed(
                evidence = "engine stream completed with terminal Done (request returned)",
                waitedMillis = 0
            )
        } else {
            val waited = awaitStopProof(attempt, active.requestId, stopProofTimeoutMillis)
            if (waited is AgentStopProof.Confirmed) waited else nativeSessionEndedProof(attempt, waited)
        }
        val release = retention.releaseAfterProof(attempt, proof)
        notifyLeaseSettlement(attempt, release, stoppedExecution)
        if (release is AgentLeaseRelease.Blocked) {
            // The first bounded proof wait expired without a release. A real late proof may still
            // arrive; keep watching in the app scope for a finite window instead of unlocking the
            // lease or waiting forever inside this terminal path.
            scheduleLateStopProof(active)
        }
    }

    /**
     * Bounded app-scope retry for a lease the first proof wait left fenced.
     *
     * It never unlocks: only a real [AgentStopProof.Confirmed] may release the lease, and the
     * retention claims that release exactly once. The task dies with the injected app scope and
     * stops after [lateProofWindowMillis], so a native call that never returns keeps the fence.
     */
    private fun scheduleLateStopProof(active: ActiveAttempt) {
        if (lateProofWindowMillis <= 0L) return
        if (!active.lateProofScheduled.compareAndSet(false, true)) return
        val attempt = active.attempt
        val requestId = active.requestId
        val retryMillis = lateProofRetryMillis.coerceAtLeast(1L)
        scope.launch {
            val deadlineNanos = System.nanoTime() + lateProofWindowMillis * 1_000_000L
            while (true) {
                val remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMillis <= 0L) return@launch
                delay(retryMillis.coerceAtMost(remainingMillis))
                val proofMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000L)
                    .coerceAtMost(stopProofTimeoutMillis)
                if (proofMillis <= 0L) return@launch
                val rawProof = awaitStopProof(attempt, requestId, proofMillis)
                val proof = if (rawProof is AgentStopProof.Confirmed) {
                    rawProof
                } else {
                    nativeSessionEndedProof(attempt, rawProof)
                }
                if (proof !is AgentStopProof.Confirmed) continue
                val release = retention.releaseAfterProof(attempt, proof)
                notifyLeaseSettlement(attempt, release, stoppedExecution = true)
                if (release is AgentLeaseRelease.Released ||
                    release is AgentLeaseRelease.AlreadyReleased
                ) {
                    return@launch
                }
            }
        }
    }

    /** A proof-source failure is missing evidence, never a release. */
    private suspend fun awaitStopProof(
        attempt: AgentModelAttemptRef,
        requestId: String,
        timeoutMillis: Long
    ): AgentStopProof = try {
        stopProof.awaitStopped(attempt, requestId, timeoutMillis)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        AgentStopProof.Unknown(
            "stop proof source failed: " +
                (failure.message?.takeIf { it.isNotBlank() }
                    ?: failure.javaClass.simpleName).take(200)
        )
    }

    /**
     * Upgrades a missing stop proof into a confirmation only when the engine reports **no loaded
     * model**: the native session that could still be running this exact attempt no longer exists.
     *
     * The release stays attempt-scoped (it can only release this attempt's own lease), and the
     * engine's request-id settlement contract is untouched. Without this, a run whose isolated
     * worker was reclaimed during a prefill never receives any later proof — the engine marks the
     * record superseded at the lifecycle boundary and reports `unknown` for ever — so the task sat
     * in STOPPING until the process was killed (observed on device).
     */
    private fun nativeSessionEndedProof(
        attempt: AgentModelAttemptRef,
        waited: AgentStopProof
    ): AgentStopProof {
        if (engine.activeExecutionProfile() != null) return waited
        val previous = when (waited) {
            is AgentStopProof.Confirmed -> "confirmed"
            is AgentStopProof.TimedOut -> "engine wait timed out after ${waited.waitedMillis}ms"
            is AgentStopProof.Unknown -> waited.reason.take(160)
        }
        return AgentStopProof.Confirmed(
            evidence = "the engine reports no loaded model, so the native session holding attempt " +
                "'${attempt.attemptId}' ended (previous proof: $previous)",
            waitedMillis = when (waited) {
                is AgentStopProof.Confirmed -> waited.waitedMillis
                is AgentStopProof.TimedOut -> waited.waitedMillis
                is AgentStopProof.Unknown -> 0L
            }
        )
    }

    /** Releases a never-dispatched lease and reports it without a stop proof. */
    private suspend fun abandonLeaseBeforeDispatch(attempt: AgentModelAttemptRef) {
        val release = retention.abandonBeforeDispatch(attempt)
        notifyLeaseSettlement(attempt, release, stoppedExecution = false)
    }

    private fun notifyLeaseSettlement(
        attempt: AgentModelAttemptRef,
        release: AgentLeaseRelease,
        stoppedExecution: Boolean
    ) {
        val listener = leaseSettlements ?: return
        // A listener failure must never change the model terminal or lose the lease accounting.
        runCatching { listener.onLeaseSettled(attempt, release, stoppedExecution) }
    }

    private suspend fun acquireBounded(
        active: ActiveAttempt,
        progress: AgentModelProgressSink
    ): LeaseAcquisition {
        val signal = CompletableDeferred<EngineLifecycleLeaseHandle>()
        var acquireJob: Job? = null
        var registered: AgentRetainedLease? = null
        var outcome: LeaseAcquisition = LeaseAcquisition.Aborted
        try {
            kotlinx.coroutines.coroutineScope {
                acquireJob = launch {
                    try {
                        signal.complete(engine.acquireLifecycleLease(stopActiveGeneration = false))
                    } catch (failure: Throwable) {
                        signal.completeExceptionally(failure)
                    }
                }
                val started = clock.nowMillis()
                while (true) {
                    if (active.stopRequested.get()) {
                        acquireJob?.cancel()
                        outcome = LeaseAcquisition.Aborted
                        return@coroutineScope
                    }
                    if (signal.isCompleted) {
                        val lease = runCatching { signal.await() }.getOrElse { failure ->
                            outcome = LeaseAcquisition.Failed(
                                failure.message ?: failure::class.java.simpleName
                            )
                            return@coroutineScope
                        }
                        // No suspension between acquire and registration.
                        registered = retention.register(attempt = active.attempt, requestId = active.requestId, lease = lease)
                        outcome = LeaseAcquisition.Acquired(lease, registered)
                        return@coroutineScope
                    }
                    val waited = clock.nowMillis() - started
                    if (waited >= resourceWaitTimeoutMillis) {
                        // coroutineScope waits for its children: the acquire child is suspended in
                        // the engine's non-cancellable lease wait, so it must be cancelled explicitly
                        // before returning or this function would never leave the scope.
                        acquireJob?.cancel()
                        outcome = LeaseAcquisition.TimedOut(waited)
                        return@coroutineScope
                    }
                    safeProgress(progress, AgentModelProgress.ResourceWait(waited))
                    withTimeoutOrNull(minOf(ACQUIRE_POLL_SLICE_MILLIS, resourceWaitTimeoutMillis - waited)) {
                        signal.await()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (registered == null) {
                    acquireJob?.cancelAndJoin()
                    if (signal.isCompleted) {
                        // Boundary race: the engine lease may have been acquired exactly as the
                        // attempt was cancelled. It was never dispatched, so release it directly.
                        runCatching { signal.await() }.getOrNull()?.let { late ->
                            runCatching { late.release() }
                        }
                    }
                }
            }
        }
        return outcome
    }

    private suspend fun verifyBinding(request: AgentModelRequest): BindingCheck {
        val binding = request.binding
        val runtime = LocalChatRuntime.entries.firstOrNull { it.backendId == binding.runtime }
            ?: return rejected(
                request,
                AgentErrorCodes.MODEL_BINDING_CHANGED,
                "冻结的运行时 ${binding.runtime} 不是当前可执行的本地后端。"
            )
        val activeProfile = engine.activeExecutionProfile()
            ?: return rejected(request, AgentErrorCodes.MODEL_NOT_LOADED, "本地模型尚未加载。")
        val signatures = engine.parameterSignatureSnapshot()
            ?: return rejected(request, AgentErrorCodes.MODEL_NOT_LOADED, "本地模型运行参数尚未就绪。")
        if (activeProfile.runtimeIdentity.identityHash != binding.assetFingerprint ||
            activeProfile.modelId != binding.modelId ||
            activeProfile.runtimeIdentity.runtime != runtime
        ) {
            Log.e(
                "McaAgentDiag",
                "binding mismatch profileModel=${activeProfile.modelId} " +
                    "profileRuntime=${activeProfile.runtimeIdentity.runtime.backendId} " +
                    "profileHash=${activeProfile.runtimeIdentity.identityHash} " +
                    "bindingModel=${binding.modelId} bindingRuntime=${binding.runtime} " +
                    "bindingHash=${binding.assetFingerprint}"
            )
            return rejected(
                request,
                AgentErrorCodes.MODEL_BINDING_CHANGED,
                "当前加载的本地模型与任务冻结绑定不一致。"
            )
        }
        if (signatures.active == null || signatures.effective == null) {
            Log.e(
                "McaAgentDiag",
                "binding rejected signatures active=${signatures.active != null} " +
                    "effective=${signatures.effective != null}"
            )
            return rejected(request, AgentErrorCodes.MODEL_NOT_LOADED, "本地模型执行配置尚未完成加载。")
        }
        if (pendingProfiles.hasPendingTransaction(binding.assetFingerprint)) {
            Log.e("McaAgentDiag", "binding rejected pending profile ${binding.assetFingerprint}")
            return rejected(
                request,
                AgentErrorCodes.RESOURCE_BUSY,
                "模型存在未完成的参数事务，Agent 不会提交或回滚该候选。"
            )
        }
        return BindingCheck.Ok
    }

    private fun rejected(
        request: AgentModelRequest,
        code: String,
        message: String
    ): BindingCheck = BindingCheck.Rejected(
        AgentInferenceErrors.failure(
            code = code,
            category = if (code == AgentErrorCodes.RESOURCE_BUSY) AgentErrorCategory.RESOURCE
            else AgentErrorCategory.MODEL,
            safeMessage = message,
            attempt = request.attempt,
            retryability = AgentRetryability.AFTER_USER_ACTION
        )
    ).also {
        Log.e(
            "McaAgentDiag",
            "binding rejected code=$code modelId=${request.binding.modelId} " +
                "runtime=${request.binding.runtime} fingerprint=${request.binding.assetFingerprint}"
        )
    }

    private fun safeProgress(sink: AgentModelProgressSink, progress: AgentModelProgress) {
        runCatching { sink.onProgress(progress) }
    }

    private sealed interface BindingCheck {
        data object Ok : BindingCheck
        data class Rejected(val result: AgentModelResult.Failure) : BindingCheck
    }

    private sealed interface LeaseAcquisition {
        data class Acquired(
            val handle: EngineLifecycleLeaseHandle,
            val retained: AgentRetainedLease
        ) : LeaseAcquisition

        data class TimedOut(val waitedMillis: Long) : LeaseAcquisition
        data class Failed(val reason: String?) : LeaseAcquisition
        data object Aborted : LeaseAcquisition
    }

    private class ActiveAttempt(
        val attempt: AgentModelAttemptRef,
        val requestId: String
    ) {
        val stopRequested = AtomicBoolean(false)
        val stopDispatched = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val dispatchStarted = AtomicBoolean(false)
        val terminalObserved = AtomicBoolean(false)
        val completedNaturally = AtomicBoolean(false)
        val lateProofScheduled = AtomicBoolean(false)

        @Volatile
        var attemptJob: Job? = null
    }

    private companion object {
        const val DEFAULT_STOP_PROOF_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_STOP_GRACE_MILLIS = 1_500L
        const val ACQUIRE_POLL_SLICE_MILLIS = 250L
        const val DEFAULT_LATE_PROOF_WINDOW_MILLIS = 15_000L
        const val DEFAULT_LATE_PROOF_RETRY_MILLIS = 1_000L

        val CANCELLATION_CODES = setOf(
            "REQUEST_CANCELLED",
            "CANCELLED",
            "CANCELED",
            "CNC_USER_CANCELLED",
            "CNC_ALREADY_CANCELLED"
        )
    }
}
