package com.muyuchat.core.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlin.math.max

internal fun McaInferenceService.streamChatImpl(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext = LocalChatExecutionContext()
    ): Flow<GenerateEvent> = flow {
        // The execution record outlives the request-scoped stop token: a cleared token is not a
        // quiescence proof, and a proof must remain queryable after the collector has returned.
        val executionRecord = beginRequestExecutionRecord(executionContext.requestId)
        try {
        var pendingRollbackContext: PendingRollbackContext? = null
        var pendingTransactionCommitted = false
        var pendingTransactionDeferred = false
        try {
            withLifecycleLock(executionContext.lifecycleLease) lifecycle@ {
            var current = _stats.value
            if (activeLoadSession != null &&
                (isolatedWorkerSessionNeedsReload || isolatedWorkerSessionLostLocked())
            ) {
                val recoveryRunner = runnerFor(activeRuntime)
                if (recoveryRunner.sessionRecoveryPolicy() ==
                    LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED
                ) {
                    parameterCoordinator.markUnloaded()
                    isolatedWorkerSessionNeedsReload = true
                    val message = recoveryRunner.sessionRecoveryMessage()
                        ?: "加速后端上一轮未在限定时间内完成，隔离会话已回收。请显式重新加载模型或选择 CPU 后重试。"
                    val errorStats = current.copy(loaded = false, lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
                emit(GenerateEvent.Phase(GenerationPhase.LOAD, current))
                val reloadError = reloadActiveSessionForNextRequestLocked(
                    SessionReloadReason.WORKER_SESSION_LOST
                )
                if (reloadError != null) {
                    val errorStats = _stats.value.copy(lastError = reloadError)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(reloadError, errorStats))
                    return@lifecycle
                }
                current = _stats.value
            }
            if (!current.loaded) {
                val errorStats = current.copy(lastError = "No local chat model is loaded.")
                emit(GenerateEvent.Error("请先在模型页加载一个本地推理模型。", errorStats))
                return@lifecycle
            }
            val memoryBeforeGenerate = telemetry.memorySnapshotDetailed()

            val deviceClockContext = if (executionContext.includeDeviceClockContext) {
                deviceClockContextProvider.contextFor(request.messages)
            } else {
                ""
            }
            val requestWithRuntimeContext = request.copy(
                runtimeSystemContext = listOf(request.runtimeSystemContext, deviceClockContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )

            val contextAdmission = localContextWindowAdmission(requestWithRuntimeContext)
            if (!contextAdmission.isAccepted) {
                val message = contextAdmission.userMessage
                    ?: "\u5f53\u524d\u8bf7\u6c42\u65e0\u6cd5\u653e\u5165\u672c\u5730\u6a21\u578b\u4e0a\u4e0b\u6587\u3002"
                val errorStats = current.copy(lastError = message)
                _stats.value = errorStats
                emit(
                    GenerateEvent.Error(
                        message = message,
                        stats = errorStats,
                        code = CONTEXT_LENGTH_EXCEEDED_ERROR_CODE
                    )
                )
                return@lifecycle
            }
            val contextSafeRequest = contextAdmission.request

            val incomingHasImageAttachments = contextSafeRequest.hasImageAttachments()
            if (activeRuntime == LocalChatRuntime.MNN_CPU && mnnSessionNeedsReloadBeforeNextRequest) {
                emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                val reloadError = reloadActiveSessionForNextRequestLocked(
                    SessionReloadReason.MNN_CONTEXT_RESET
                )
                if (reloadError != null) {
                    val errorStats = _stats.value.copy(lastError = reloadError)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(reloadError, errorStats))
                    return@lifecycle
                }
                current = _stats.value
            }

            var preparedParameters: CompletionPreflight.Ready? = null
            if (activeRuntime.usesCoordinatedParameters()) {
                val session = activeLoadSession
                if (session == null) {
                    val message = "模型运行参数状态缺失，请重新加载当前模型。"
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
                val suppliedLoadAuthorization = executionContext.engineLoadAuthorization
                if (suppliedLoadAuthorization != null &&
                    parameterCoordinator.isAuthorizedPendingActive(suppliedLoadAuthorization)
                ) {
                    pendingRollbackContext = PendingRollbackContext(
                        baseSession = session,
                        authorization = suppliedLoadAuthorization
                    )
                }
                var preflight = parameterCoordinator.preflight(
                    identity = session.runtimeIdentity,
                    requestParamsJson = requestWithRuntimeContext.params.toJson(),
                    trustedAuthorization = executionContext.engineLoadAuthorization,
                    allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                    hotOverrideAuthorization = executionContext.engineHotOverrideAuthorization
                )
                if (preflight is CompletionPreflight.Rejected &&
                    preflight.code == "model_reload_required_authorized"
                ) {
                    val decision = parameterCoordinator.decideMismatchRecovery(
                        requestId = executionContext.requestId,
                        identity = session.runtimeIdentity,
                        trustedAuthorization = executionContext.engineLoadAuthorization
                    )
                    val reloadError = when (decision) {
                        is MismatchRecoveryDecision.ReloadAuthorizedPending -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = session,
                                authorization = decision.transaction.authorization
                            )
                            reloadCoordinatedProfileLocked(
                                session = session,
                                target = decision.transaction.profile,
                                authorization = decision.transaction.authorization,
                                commitAfterLoad = false
                            )
                        }
                        is MismatchRecoveryDecision.ReloadCommittedForDrift -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            reloadCoordinatedProfileLocked(session, decision.profile, null, false)
                        }
                        is MismatchRecoveryDecision.Fail -> decision.message
                    }
                    if (reloadError != null) {
                        val errorStats = _stats.value.copy(lastError = reloadError)
                        _stats.value = errorStats
                        emit(GenerateEvent.Error(reloadError, errorStats))
                        return@lifecycle
                    }
                    current = _stats.value
                    val reloadedSession = activeLoadSession ?: session
                    preflight = parameterCoordinator.preflight(
                        identity = reloadedSession.runtimeIdentity,
                        requestParamsJson = requestWithRuntimeContext.params.toJson(),
                        trustedAuthorization = executionContext.engineLoadAuthorization,
                        allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                        hotOverrideAuthorization = executionContext.engineHotOverrideAuthorization
                    )
                }
                when (preflight) {
                    is CompletionPreflight.Ready -> {
                        preparedParameters = preflight
                        val staged = executionContext.engineLoadAuthorization
                            ?.let(parameterCoordinator::authorizedPendingFor)
                            ?.takeIf { transaction ->
                                transaction.profile.desiredSignature.digest == preflight.signatures.desired.digest &&
                                    transaction.profile.resolvedLoadSignature.digest == preflight.signatures.resolved.digest &&
                                    transaction.profile.committedExecutionSignature.digest == preflight.signatures.committed.digest
                            }
                        if (staged != null) {
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = activeLoadSession ?: session,
                                authorization = staged.authorization
                            )
                        }
                    }
                    is CompletionPreflight.Rejected -> {
                        val presentation = preflight.userFacingPresentation()
                        val errorStats = current.copy(lastError = presentation.message)
                        _stats.value = errorStats
                        emit(
                            GenerateEvent.Error(
                                message = presentation.message,
                                stats = errorStats,
                                code = preflight.code,
                                changedFields = preflight.changedFields.toSortedSet(),
                                action = presentation.action
                            )
                        )
                        return@lifecycle
                    }
                }
            }

            val requestWithVisionFiles = stageVisionInputsForStreamChat(
                incomingHasImageAttachments = incomingHasImageAttachments,
                contextSafeRequest = contextSafeRequest,
                current = current,
                executionContext = executionContext,
                emit = { event -> emit(event) }
            ) ?: return@lifecycle
            val activeRequest = requestWithVisionFiles
            val runner = runnerFor(activeRuntime)
            val generationStopToken = activateGenerationStopTargetImpl(
                executionContext.requestId,
                runner,
                currentCoroutineContext()[Job]
            )
            try {

            val started = clock.nowMs()
            _stats.value = _stats.value.copy(
                cacheReuseHit = false, cacheReusedTokens = 0, cacheReuseHits = 0,
                cacheReuseMisses = 0, cacheReuseReason = "unknown"
            )
            val hasImageAttachments = activeRequest.hasImageAttachments()
            val shouldRefreshMnnAfterRequest = activeRuntime == LocalChatRuntime.MNN_CPU
            var activePersistentPrefix: PreparedPersistentPrefix? = null
            var managedPersistentPrefixFailure: ManagedPersistentPrefixFailure? = null
            suspend fun emitTokenizePhase() {
                emit(GenerateEvent.Phase(GenerationPhase.TOKENIZE, _stats.value))
            }
            emitTokenizePhase()
            val beginOutcome = beginNativeWithPrefillProgressLoop(
                generationStopToken = generationStopToken,
                paramsJson = preparedParameters?.nativeParamsJson ?: activeRequest.params.toJson(),
                activeRequest = activeRequest,
                preparedParameters = preparedParameters,
                hasImageAttachments = hasImageAttachments,
                runner = runner,
                executionContext = executionContext,
                activePersistentPrefixSeed = activePersistentPrefix,
                managedPersistentPrefixFailureSeed = managedPersistentPrefixFailure,
                emit = { event -> emit(event) }
            )
            activePersistentPrefix = beginOutcome.activePersistentPrefix
            managedPersistentPrefixFailure = beginOutcome.managedPersistentPrefixFailure
            var beginResult = beginOutcome.result
            beginResult.exceptionOrNull()?.let { error ->
                if (shouldRefreshMnnAfterRequest) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
                runCatching { runner.requestStop() }
                if (error is CancellationException) throw error
                val message = "Native beginCompletion failed: ${error.message ?: error::class.java.simpleName}"
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@lifecycle
            }
            var beginRc = beginResult.getOrThrow()
            if (beginRc != 0 && activeRuntime.usesCoordinatedParameters()) {
                val session = activeLoadSession
                val nativeError = runCatching {
                    JSONObject(nativeStatsJsonOnIo()).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                if (session != null && parameterCoordinator.isLoadSignatureMismatch(
                        session.runtimeIdentity,
                        beginRc,
                        nativeError
                    )
                ) {
                    val decision = parameterCoordinator.decideMismatchRecovery(
                        requestId = executionContext.requestId,
                        identity = session.runtimeIdentity,
                        trustedAuthorization = executionContext.engineLoadAuthorization
                    )
                    val reloadError = when (decision) {
                        is MismatchRecoveryDecision.ReloadAuthorizedPending -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = session,
                                authorization = decision.transaction.authorization
                            )
                            reloadCoordinatedProfileLocked(
                                session,
                                decision.transaction.profile,
                                decision.transaction.authorization,
                                false
                            )
                        }
                        is MismatchRecoveryDecision.ReloadCommittedForDrift -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            reloadCoordinatedProfileLocked(session, decision.profile, null, false)
                        }
                        is MismatchRecoveryDecision.Fail -> decision.message
                    }
                    if (reloadError == null) {
                        current = _stats.value
                        val reloadedSession = activeLoadSession
                        val retryPreflight = reloadedSession?.let {
                            parameterCoordinator.preflight(
                                identity = it.runtimeIdentity,
                                requestParamsJson = activeRequest.params.toJson(),
                                trustedAuthorization = executionContext.engineLoadAuthorization,
                                allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                                hotOverrideAuthorization = executionContext.engineHotOverrideAuthorization
                            )
                        }
                        if (retryPreflight is CompletionPreflight.Ready) {
                            preparedParameters = retryPreflight
                            emitTokenizePhase()
                            val retryBeginOutcome = beginNativeWithPrefillProgressLoop(
                                generationStopToken = generationStopToken,
                                paramsJson = retryPreflight.nativeParamsJson,
                                activeRequest = activeRequest,
                                preparedParameters = preparedParameters,
                                hasImageAttachments = hasImageAttachments,
                                runner = runner,
                                executionContext = executionContext,
                                activePersistentPrefixSeed = activePersistentPrefix,
                                managedPersistentPrefixFailureSeed = managedPersistentPrefixFailure,
                                emit = { event -> emit(event) }
                            )
                            activePersistentPrefix = retryBeginOutcome.activePersistentPrefix
                            managedPersistentPrefixFailure = retryBeginOutcome.managedPersistentPrefixFailure
                            beginResult = retryBeginOutcome.result
                            beginResult.exceptionOrNull()?.let { error ->
                                if (shouldRefreshMnnAfterRequest) mnnSessionNeedsReloadBeforeNextRequest = true
                                runCatching { runner.requestStop() }
                                if (error is CancellationException) throw error
                                val message = "Native beginCompletion recovery failed: ${error.message ?: error::class.java.simpleName}"
                                val errorStats = errorStatsForRunnerFailure(runner, message)
                                _stats.value = errorStats
                                emit(GenerateEvent.Error(message, errorStats))
                                return@lifecycle
                            }
                            beginRc = beginResult.getOrThrow()
                        } else if (retryPreflight is CompletionPreflight.Rejected) {
                            beginRc = NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH
                        }
                    }
                }
            }
            if (beginRc != 0) {
                if (shouldRefreshMnnAfterRequest) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
                runCatching { runner.requestStop() }
                val nativeError = runCatching {
                    JSONObject(nativeStatsJsonOnIo()).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                val message = buildString {
                    append("Native beginCompletion failed: ").append(beginRc)
                    if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                }
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@lifecycle
            }

            // Synchronous runners may already expose final prefill metrics here.
            // Async runners can still be prefilling; begin returning is not decode evidence.
            var promptEndsInsideReasoning = false
            runCatching { JSONObject(nativeStatsJsonOnIo()) }.getOrNull()?.let { nativeStats ->
                promptEndsInsideReasoning = nativeStats.optBoolean("promptEndsInsideReasoning", false)
                val prefillStats = mergeNativeStats(
                    base = _stats.value,
                    nativeStats = nativeStats,
                    memory = memoryBeforeGenerate,
                    started = started,
                    lastTokenAt = started,
                    request = activeRequest,
                    managedPrefixFailure = managedPersistentPrefixFailure
                ).copy(
                    completionTokens = 0,
                    ttftMs = 0,
                    decodeMs = 0,
                    decodeTps = 0.0,
                    e2eTps = 0.0
                )
                _stats.value = prefillStats
            }

            val decodeOutcome = runDecodeSamplingLoop(
                started = started,
                memoryBeforeGenerate = memoryBeforeGenerate,
                promptEndsInsideReasoning = promptEndsInsideReasoning,
                request = request,
                activeRequest = activeRequest,
                runner = runner,
                generationStopToken = generationStopToken,
                executionContext = executionContext,
                shouldRefreshMnnAfterRequest = shouldRefreshMnnAfterRequest,
                hasImageAttachments = hasImageAttachments,
                pendingRollbackContext = pendingRollbackContext,
                activePersistentPrefixSeed = activePersistentPrefix,
                managedPersistentPrefixFailureSeed = managedPersistentPrefixFailure,
                markPendingCommitted = { pendingTransactionCommitted = true },
                markPendingDeferred = { pendingTransactionDeferred = true },
                emit = { event -> emit(event) }
            )
            if (decodeOutcome.returnedEarly) return@lifecycle
            } finally {
                clearGenerationStopTargetImpl(generationStopToken)
                withContext(NonCancellable + io) {
                    retainedVisionInputs += Triple(runner, contextSafeRequest, activeRequest)
                    retainedVisionInputs.removeAll { (consumer, original, prepared) ->
                        val released = runCatching { consumer.canReleasePreparedInputs() }.getOrDefault(false)
                        if (released) {
                            LocalVisionInputPreparer.releasePreparedInputs(original, prepared, appContext.cacheDir)
                        }
                        released
                    }
                }
            }
            }
        } finally {
            try {
                val rollback = pendingRollbackContext
                if (rollback != null && !pendingTransactionCommitted && !pendingTransactionDeferred) {
                    withContext(NonCancellable) {
                        withLifecycleLock(executionContext.lifecycleLease) {
                            val rollbackError = rollbackAuthorizedPendingLocked(rollback)
                            if (rollbackError != null) {
                                parameterCoordinator.abortAuthorizedPending(rollback.authorization)
                                parameterCoordinator.markUnloaded()
                                activeLoadSession = null
                                _stats.value = _stats.value.copy(
                                    loaded = false,
                                    lastError = "Pending profile rollback failed: $rollbackError"
                                )
                            }
                        }
                    }
                }
            } finally {
                parameterCoordinator.finishRequest(executionContext.requestId)
            }
        }
        } finally {
            finishRequestExecutionRecord(executionRecord, "engine stream returned")
        }
    }
