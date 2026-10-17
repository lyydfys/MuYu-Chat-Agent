package com.muyuchat.core.engine

import android.util.Log
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.modelstore.validateLiteRtLmLoadPreflight
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID


internal suspend fun McaInferenceService.loadModelImpl(
        modelPath: String,
        runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU,
        params: LoadParams = LoadParams(),
        qairtBundleSha256: String? = null,
        qairtExecutionPurpose: QairtExecutionPurpose = QairtExecutionPurpose.NORMAL,
        runtimeIdentity: ModelRuntimeIdentity? = null,
        executionProfile: ModelExecutionProfile? = null
    ): Result<RuntimeStats> = withContext(io) {
        runCatching {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            if (runtime == LocalChatRuntime.LITERT_LM) {
                val modelFile = File(modelPath)
                val preflight = validateLiteRtLmLoadPreflight(modelFile, modelFile.length())
                require(preflight.canLoad) {
                    "${preflight.title}：${preflight.details}"
                }
            }
            val resolvedIdentity = executionProfile?.runtimeIdentity
                ?: runtimeIdentity
                ?: defaultRuntimeIdentity(
                    modelPath = modelPath,
                    runtime = runtime,
                    params = params,
                    artifactFingerprintOverride = qairtBundleSha256
                        ?.trim()
                        ?.takeIf { runtime == LocalChatRuntime.GENIEX_QAIRT && it.isNotBlank() }
                )
            executionProfile?.let { persisted ->
                require(persisted.runtimeIdentity.runtime == runtime) {
                    "execution profile runtime does not match requested runtime"
                }
                require(runtimeIdentity == null || runtimeIdentity.identityHash == persisted.runtimeIdentity.identityHash) {
                    "execution profile identity does not match requested runtime identity"
                }
            }
            val resolvedExecutionProfile = executionProfile ?: parameterCoordinator.resolveProfile(
                identity = resolvedIdentity,
                requestedParamsJson = params.toJson(),
                profileId = UUID.randomUUID().toString()
            ).profile
            require(resolvedExecutionProfile.runtimeIdentity.identityHash == resolvedIdentity.identityHash) {
                "execution profile identity changed during load resolution"
            }
            val effectiveLoadParams = loadParamsForProfile(params, resolvedExecutionProfile)
            // mmproj is a load-bound resource owned by the model manifest. Older
            // execution profiles may predate the binding, so materialize the
            // resolved projector on every native load boundary.
            val nativeLoadParamsJson = nativeLoadParamsJson(
                profile = resolvedExecutionProfile,
                visionProjectorPath = effectiveLoadParams.visionProjectorPath,
                fallbackPolicy = effectiveLoadParams.fallbackPolicy
            )
            val admissionMemory = memorySnapshotProvider?.invoke() ?: telemetry.memorySnapshotDetailed()
            val admission = qairtExecutionAdmissionForLoad(
                modelPath = modelPath,
                runtime = runtime,
                memory = admissionMemory,
                bundleSha256 = qairtBundleSha256
            )
            lastQairtExecutionAdmission = admission
            requireQairtExecutionPurpose(
                runtime = runtime,
                purpose = qairtExecutionPurpose,
            )
            // Selecting an already healthy MNN model is an idempotent UI action.
            // Avoiding a pointless native unload/load is essential for Gemma,
            // whose upstream session can hang after repeated cold construction.
            // Failed/cancelled MNN turns deliberately bypass this fast path and
            // are refreshed below before their next request.
            mutex.withLock {
                invalidateGenerationStopTargetAfterLifecycleTransition()
                validateExecutionProfilePathLocked(modelPath, resolvedExecutionProfile)
                parameterCoordinator.prepareOrdinaryLoad(resolvedExecutionProfile)
                reusableMnnLoadedStats(modelPath, runtime, resolvedExecutionProfile)
            }?.let { reusable ->
                if (executionProfile != null) {
                    mutex.withLock { adoptEquivalentLoadedProfileLocked(resolvedExecutionProfile) }
                }
                return@runCatching reusable
            }
            stopGeneration()
            mutex.withLock {
                invalidateGenerationStopTargetAfterLifecycleTransition()
                // A running request may have completed while stopGeneration()
                // waited outside the lock. Recheck so that concurrent model-page
                // taps still do not tear down a healthy MNN session.
                reusableMnnLoadedStats(modelPath, runtime, resolvedExecutionProfile)?.let { reusable ->
                    if (executionProfile != null) adoptEquivalentLoadedProfileLocked(resolvedExecutionProfile)
                    return@withLock reusable
                }
                runCatching { runnerFor(activeRuntime).unloadModel() }
                parameterCoordinator.markUnloaded()
                _stats.value = RuntimeStats(loaded = false, backend = runtime.backendId)
                activeRuntime = runtime
                activeLoadSession = null
                mnnSessionNeedsReloadBeforeNextRequest = false
                if (runtime == LocalChatRuntime.GENIEX_QAIRT) {
                    // A new QAIRT attempt must earn a new witness. Do not let a
                    // prior create/generate result certify a different handle.
                    qairtDryRunWitness = null
                }
                val runner = runnerFor(runtime)
                if (!runner.isAvailable) {
                    val message = unavailableStats(runtime, runner.loadError).optString("lastError")
                    _stats.value = RuntimeStats(
                        loaded = false,
                        backend = runtime.backendId,
                        modelPath = modelPath,
                        nThreads = effectiveLoadParams.nThreads,
                        nCtx = effectiveLoadParams.nCtx,
                        maxAllTokens = effectiveLoadParams.nCtx,
                        lastError = message
                    )
                    error(message)
                }
                val started = clock.nowMs()
                val rc = runner.loadModel(modelPath, nativeLoadParamsJson)
                if (rc != 0) {
                    val nativeStats = nativeStatsJson()
                    val nativeError = runCatching {
                        JSONObject(nativeStats).optString("lastError").takeIf { it.isNotBlank() }
                    }.getOrNull()
                    error(
                        buildString {
                            append("Native loadModel failed: ").append(rc)
                            if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                        }.also { message ->
                            runCatching { runner.requestStop() }
                            runCatching { runner.unloadModel() }
                            activeLoadSession = null
                            mnnSessionNeedsReloadBeforeNextRequest = false
                            _stats.value = RuntimeStats(
                                loaded = false,
                                modelPath = modelPath,
                                backend = runtime.backendId,
                                nThreads = effectiveLoadParams.nThreads,
                                nThreadsBatch = effectiveLoadParams.nThreads,
                                nCtx = effectiveLoadParams.nCtx,
                                maxAllTokens = effectiveLoadParams.nCtx,
                                lastError = message
                            )
                        }
                    )
                }
                val loadMs = (clock.nowMs() - started).coerceAtLeast(0L)
                val stats = loadedStatsFromNative(modelPath, runtime, effectiveLoadParams, loadMs)
                if (runtime == LocalChatRuntime.GENIEX_QAIRT &&
                    qairtExecutionPurpose == QairtExecutionPurpose.ISOLATED_DRY_RUN
                ) {
                    val identity = qairtIdentityProvider(qairtBundleSha256)
                        ?.takeIf(QairtBundleRuntimeIdentity::isComplete)
                    val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                    if (nativeStats == null || !hasQairtNpuExecutionEvidence(nativeStats)) {
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        parameterCoordinator.markUnloaded()
                        activeLoadSession = null
                        _stats.value = RuntimeStats(
                            loaded = false,
                            modelPath = modelPath,
                            backend = runtime.backendId,
                            nThreads = effectiveLoadParams.nThreads,
                            nCtx = effectiveLoadParams.nCtx,
                            maxAllTokens = effectiveLoadParams.nCtx,
                            lastError = "QAIRT 隔离安全启动未取得 NPU 执行证据。"
                        )
                        error("QAIRT 隔离安全启动未取得 NPU 执行证据。")
                    }
                    // A missing diagnostic identity must not stop a concrete
                    // native smoke. It only means this result cannot be cached
                    // as reusable verification evidence.
                    qairtDryRunWitness = identity?.let {
                        QairtDryRunWitness(
                            identity = it,
                            sawNpuExecution = true,
                            npuEvidence = nativeStats.opt("backendDevices")?.toString().orEmpty()
                        )
                    }
                }
                if (runtime.usesCoordinatedParameters()) {
                    val nativeStatsBeforeSignaturePublish = nativeStatsJson()
                    runCatching {
                        // QAIRT publish/commit is intentionally after both static
                        // admission and native NPU evidence validation above.
                        parameterCoordinator.publishLoaded(
                            resolvedExecutionProfile,
                            nativeStatsBeforeSignaturePublish
                        )
                        parameterCoordinator.commit(resolvedExecutionProfile)
                    }.getOrElse { signatureError ->
                        val diagnostic = parameterCoordinator.loadSignatureDiagnostic(
                            profile = resolvedExecutionProfile,
                            nativeStatsJson = nativeStatsBeforeSignaturePublish
                        ).put("error", signatureError.message.orEmpty())
                        Log.e(
                            "McaInferenceService",
                            "native load signature mismatch: $diagnostic"
                        )
                        LocalChatRunnerDebug.emit(
                            "native_load_signature_mismatch",
                            diagnostic
                        )
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        parameterCoordinator.markUnloaded()
                        if (runtime == LocalChatRuntime.GENIEX_QAIRT) qairtDryRunWitness = null
                        activeLoadSession = null
                        mnnSessionNeedsReloadBeforeNextRequest = false
                        error("Native effective parameter verification failed: ${signatureError.message}")
                    }
                }
                activeLoadSession = LoadedModelSession(
                    modelPath = modelPath,
                    runtime = runtime,
                    params = effectiveLoadParams,
                    runtimeIdentity = resolvedIdentity,
                    executionProfile = resolvedExecutionProfile,
                    nativeLoadParamsJson = nativeLoadParamsJson
                )
                mnnSessionNeedsReloadBeforeNextRequest = false
                isolatedWorkerSessionNeedsReload = false
                _stats.value = stats
                stats
            }
        }
    }

    /** Keeps an optional exclusive owner in place while releasing the active native model. */
internal suspend fun McaInferenceService.unloadModelImpl(lease: EngineLifecycleLease? = null) = withContext(io) {
        // The lease already owns the generation boundary. Do not issue an unowned stop or
        // reacquire the non-reentrant mutex while an image operation holds that boundary.
        if (lease == null) stopGeneration()
        withLifecycleLock(lease) {
            invalidateGenerationStopTargetAfterLifecycleTransition()
            val unloadedRuntime = activeRuntime
            val dryRunWitness = qairtDryRunWitness
            // An untouched isolated runner would bind a new process just to unload it. Keep
            // ordinary explicit unload behavior and release retained sessions even when a
            // worker-loss projection has already marked their stats as unloaded.
            if (lease == null || activeLoadSession != null || _stats.value.loaded) {
                runnerFor(unloadedRuntime).unloadModel()
            }
            parameterCoordinator.markUnloaded()
            if (unloadedRuntime == LocalChatRuntime.GENIEX_QAIRT && dryRunWitness != null) {
                val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                dryRunWitness.destroyedCleanly = nativeStats != null &&
                    !nativeStats.optBoolean("loaded", true) &&
                    nativeStats.optString("lastError").isBlank()
            }
            activeLoadSession = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            isolatedWorkerSessionNeedsReload = false
            lastQairtExecutionAdmission = null
            _stats.value = RuntimeStats(loaded = false, backend = activeRuntime.backendId)
        }
    }

    /**
     * B44: after an explicit unload, recycle the isolated worker process that owned the model
     * so the next load allocates into a fresh process. Device A reproduced a MIUI DMA-BUF
     * guard force-stop (0.6B -> 4B QAIRT switch) and cross-runtime load failures while the old
     * process kept its native allocator state. In-process runners return false.
     */
    suspend fun McaInferenceService.recycleIsolatedWorkerAfterUnload(): Boolean =
        withContext(io) {
            runCatching { runnerFor(activeRuntime).recycleWorkerForReplacement() }.getOrDefault(false)
        }

    /**
     * Discards text KV/checkpoint state after a user edits, removes, or switches
     * a conversation. The operation is serialized with generation so a later
     * request can never continue from the removed history.
     */
internal suspend fun McaInferenceService.invalidateConversationContextImpl() = withContext(io) {
        mutex.withLock {
            if (!_stats.value.loaded) return@withLock
            runnerFor(activeRuntime).invalidateConversationContext()
            // Current MNN runtimes clear their live prompt/KV state here and perform an
            // exact token-prefix check on the next begin. Reloading multi-gigabyte weights
            // after a new chat or edit is unnecessary; begin still falls back to a cold
            // prefill whenever the retained prefix does not match.
        }
    }


    /**
     * Reuse only an exact, healthy MNN load. Deliberately do not generalise this
     * to QAIRT: its bundle verification/admission and handle state are model
     * specific and must still run through a fresh load boundary when requested.
     */
internal fun McaInferenceService.reusableMnnLoadedStats(
        modelPath: String,
        runtime: LocalChatRuntime,
        profile: ModelExecutionProfile
    ): RuntimeStats? {
        if (runtime != LocalChatRuntime.MNN_CPU || activeRuntime != LocalChatRuntime.MNN_CPU) return null
        if (mnnSessionNeedsReloadBeforeNextRequest || !_stats.value.loaded) return null
        val session = activeLoadSession ?: return null
        if (session.runtime != LocalChatRuntime.MNN_CPU) return null
        if (session.runtimeIdentity.identityHash != profile.runtimeIdentity.identityHash) return null
        if (session.executionProfile.resolvedLoadSignature.digest != profile.resolvedLoadSignature.digest) return null
        if (session.executionProfile.committedExecutionSignature.digest != profile.committedExecutionSignature.digest) return null
        if (!sameModelLocation(session.modelPath, modelPath)) return null
        return _stats.value
    }

internal fun McaInferenceService.sameModelLocation(first: String, second: String): Boolean =
        runCatching { File(first).canonicalPath == File(second).canonicalPath }
            .getOrDefault(first == second)

internal suspend fun McaInferenceService.isolatedWorkerSessionLostLocked(): Boolean = withContext(io) {
        if (activeRuntime != LocalChatRuntime.LLAMA_CPP &&
            activeRuntime != LocalChatRuntime.MNN_CPU &&
            activeRuntime != LocalChatRuntime.GENIEX_QAIRT &&
            activeRuntime != LocalChatRuntime.LITERT_LM
        ) return@withContext false
        runCatching {
            JSONObject(runnerFor(activeRuntime).getRuntimeStatsJson())
                .optBoolean(ISOLATED_WORKER_SESSION_LOST_FIELD, false)
        }.getOrDefault(false)
    }

    /** Keeps the load descriptor for recovery while removing stale READY state. */
internal fun McaInferenceService.errorStatsForRunnerFailure(
        runner: LocalChatRunner,
        message: String
    ): RuntimeStats {
        if (!runner.isSessionKnownLost()) return _stats.value.copy(lastError = message)
        isolatedWorkerSessionNeedsReload = activeLoadSession != null
        parameterCoordinator.markUnloaded()
        return _stats.value.copy(loaded = false, lastError = message)
    }

internal suspend fun McaInferenceService.reloadActiveSessionForNextRequestLocked(
        reason: SessionReloadReason
    ): String? {
        val session = activeLoadSession
            ?: return reason.missingSessionMessage
        if (session.runtime != LocalChatRuntime.LLAMA_CPP &&
            session.runtime != LocalChatRuntime.MNN_CPU &&
            session.runtime != LocalChatRuntime.GENIEX_QAIRT &&
            session.runtime != LocalChatRuntime.LITERT_LM
        ) {
            return "当前运行时不支持隔离文本 worker 会话恢复，请重新加载模型。"
        }
        val runner = runnerFor(session.runtime)
        if (!runner.isAvailable) {
            return unavailableStats(session.runtime, runner.loadError).optString("lastError")
        }
        withContext(io) {
            runCatching { runner.requestStop() }
            runCatching { runner.unloadModel() }
        }
        parameterCoordinator.markUnloaded()
        _stats.value = RuntimeStats(
            loaded = false,
            modelPath = session.modelPath,
            backend = session.runtime.backendId,
            nThreads = session.params.nThreads,
            nCtx = session.params.nCtx,
            maxAllTokens = session.params.nCtx,
            lastError = reason.reloadingStatus
        )
        suspend fun finishThrownReload(error: Throwable): String {
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            parameterCoordinator.markUnloaded()
            if (reason != SessionReloadReason.WORKER_SESSION_LOST) {
                activeLoadSession = null
            }
            isolatedWorkerSessionNeedsReload = reason == SessionReloadReason.WORKER_SESSION_LOST
            mnnSessionNeedsReloadBeforeNextRequest = false
            val message = buildString {
                append(reason.loadFailurePrefix)
                append(error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName)
            }
            _stats.value = RuntimeStats(
                loaded = false,
                modelPath = session.modelPath,
                backend = session.runtime.backendId,
                nThreads = session.params.nThreads,
                nCtx = session.params.nCtx,
                maxAllTokens = session.params.nCtx,
                lastError = message
            )
            return message
        }

        val started = clock.nowMs()
        val rc = try {
            withContext(io) { runner.loadModel(session.modelPath, session.nativeLoadParamsJson) }
        } catch (error: Throwable) {
            val message = finishThrownReload(error)
            if (error is CancellationException) throw error
            return message
        }
        if (rc != 0) {
            val nativeError = withContext(io) {
                runCatching {
                    JSONObject(runner.getRuntimeStatsJson())
                        .optString("lastError")
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            val message = buildString {
                append(reason.loadFailurePrefix).append(rc)
                if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
            }
            activeLoadSession = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            isolatedWorkerSessionNeedsReload = false
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            _stats.value = RuntimeStats(
                loaded = false,
                modelPath = session.modelPath,
                backend = session.runtime.backendId,
                nThreads = session.params.nThreads,
                nCtx = session.params.nCtx,
                maxAllTokens = session.params.nCtx,
                lastError = message
            )
            return message
        }
        mnnSessionNeedsReloadBeforeNextRequest = false
        isolatedWorkerSessionNeedsReload = false
        val nativeStats = try {
            withContext(io) { runner.getRuntimeStatsJson() }
        } catch (error: Throwable) {
            val message = finishThrownReload(error)
            if (error is CancellationException) throw error
            return message
        }
        runCatching {
            parameterCoordinator.publishLoaded(session.executionProfile, nativeStats)
        }.getOrElse { signatureError ->
            activeLoadSession = null
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            parameterCoordinator.markUnloaded()
            return "${reason.signatureFailurePrefix}${signatureError.message}"
        }
        _stats.value = withContext(io) {
            loadedStatsFromNative(
                modelPath = session.modelPath,
                runtime = session.runtime,
                params = session.params,
                loadMs = (clock.nowMs() - started).coerceAtLeast(0L)
            )
        }
        return null
    }

    /** Called only while [mutex] is held; never re-enters public loadModel(). */
internal suspend fun McaInferenceService.reloadCoordinatedProfileLocked(
        session: LoadedModelSession,
        target: ModelExecutionProfile,
        authorization: LoadAuthorization?,
        commitAfterLoad: Boolean
    ): String? {
        if (session.runtimeIdentity.identityHash != target.runtimeIdentity.identityHash) {
            return "受控重载目标与当前模型身份不一致，请通过模型切换流程重新加载。"
        }
        val runner = runnerFor(session.runtime)
        if (!runner.isAvailable) {
            return unavailableStats(session.runtime, runner.loadError).optString("lastError")
        }
        if (authorization == null) {
            // A committed/drift recovery is an ordinary lifecycle boundary;
            // discard any stale pending transaction before publication.
            parameterCoordinator.prepareOrdinaryLoad(target)
        }
        val nativeLoadJson = nativeLoadParamsJson(
            profile = target,
            visionProjectorPath = session.params.visionProjectorPath,
            fallbackPolicy = session.params.fallbackPolicy
        )
        runCatching { runner.requestStop() }
        runCatching { runner.unloadModel() }
        parameterCoordinator.markUnloaded()
        _stats.value = RuntimeStats(
            loaded = false,
            modelPath = session.modelPath,
            backend = session.runtime.backendId,
            nThreads = (target.hotExecutionValues.value("n_threads") as? Number)?.toInt()
                ?: session.params.nThreads,
            nCtx = (target.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
                ?: session.params.nCtx,
            lastError = "Reloading verified model execution profile."
        )
        val started = clock.nowMs()
        val rc = withContext(io) { runner.loadModel(session.modelPath, nativeLoadJson) }
        if (rc != 0) {
            val nativeError = runCatching {
                JSONObject(nativeStatsJson()).optString("lastError").takeIf { it.isNotBlank() }
            }.getOrNull()
            activeLoadSession = null
            val message = buildString {
                append("受控模型重载失败：").append(rc)
                if (!nativeError.isNullOrBlank()) append("；").append(nativeError)
            }
            _stats.value = _stats.value.copy(loaded = false, lastError = message)
            return message
        }
        if (session.runtime == LocalChatRuntime.GENIEX_QAIRT) {
            val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
            if (nativeStats == null || !hasQairtNpuExecutionEvidence(nativeStats)) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
                parameterCoordinator.markUnloaded()
                activeLoadSession = null
                val message = "QAIRT 受控重载未取得 NPU 执行证据，拒绝发布 execution profile。"
                _stats.value = _stats.value.copy(loaded = false, lastError = message)
                return message
            }
        }
        runCatching {
            parameterCoordinator.publishLoaded(target, nativeStatsJson(), authorization)
            if (commitAfterLoad) parameterCoordinator.commit(target)
        }.getOrElse { signatureError ->
            runCatching { runner.requestStop() }
            runCatching { runner.unloadModel() }
            parameterCoordinator.markUnloaded()
            activeLoadSession = null
            val message = "受控重载后的参数回读不一致：${signatureError.message}"
            _stats.value = _stats.value.copy(loaded = false, lastError = message)
            return message
        }
        val resolvedParams = session.params.copy(
            nCtx = (target.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
                ?: session.params.nCtx,
            nThreads = (target.hotExecutionValues.value("n_threads") as? Number)?.toInt()
                ?: session.params.nThreads,
            mmap = target.resolvedLoadBoundValues.value("mmap") as? Boolean ?: session.params.mmap,
            mlock = target.resolvedLoadBoundValues.value("mlock") as? Boolean ?: session.params.mlock
        )
        activeLoadSession = LoadedModelSession(
            modelPath = session.modelPath,
            runtime = session.runtime,
            params = resolvedParams,
            runtimeIdentity = target.runtimeIdentity,
            executionProfile = target,
            nativeLoadParamsJson = nativeLoadJson
        )
        mnnSessionNeedsReloadBeforeNextRequest = false
        _stats.value = loadedStatsFromNative(
            modelPath = session.modelPath,
            runtime = session.runtime,
            params = resolvedParams,
            loadMs = (clock.nowMs() - started).coerceAtLeast(0L)
        )
        return null
    }


internal data class LoadedModelSession(
        val modelPath: String,
        val runtime: LocalChatRuntime,
        val params: LoadParams,
        val runtimeIdentity: ModelRuntimeIdentity,
        val executionProfile: ModelExecutionProfile,
        val nativeLoadParamsJson: String
    )

internal enum class SessionReloadReason(
        val missingSessionMessage: String,
        val reloadingStatus: String,
        val loadFailurePrefix: String,
        val signatureFailurePrefix: String
    ) {
        MNN_CONTEXT_RESET(
            missingSessionMessage = "MNN 会话需要刷新，但没有可恢复的模型加载记录。请重新加载本地模型后再试。",
            reloadingStatus = "Refreshing MNN session before next request.",
            loadFailurePrefix = "MNN 会话刷新失败：",
            signatureFailurePrefix = "MNN 会话刷新后的参数回读不一致："
        ),
        WORKER_SESSION_LOST(
            missingSessionMessage = "隔离文本 worker 已退出，且没有可恢复的模型加载记录。请重新加载本地模型后再试。",
            reloadingStatus = "Restoring model after isolated text worker loss.",
            loadFailurePrefix = "隔离文本 worker 会话恢复失败：",
            signatureFailurePrefix = "隔离文本 worker 会话恢复后的参数回读不一致："
        )
    }

internal data class QairtDryRunWitness(
        val identity: QairtBundleRuntimeIdentity,
        val sawNpuExecution: Boolean,
        val npuEvidence: String,
        var sawVisibleCompletion: Boolean = false,
        var destroyedCleanly: Boolean = false
    )
