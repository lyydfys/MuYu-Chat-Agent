package com.muyuchat.core.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class McaInferenceStreamPrefillOutcome(
    val result: Result<Int>,
    val activePersistentPrefix: PreparedPersistentPrefix?,
    val managedPersistentPrefixFailure: ManagedPersistentPrefixFailure?
)

internal suspend fun McaInferenceService.beginNativeWithPrefillProgressLoop(
    paramsJson: String,
    activeRequest: ChatRequest,
    preparedParameters: CompletionPreflight.Ready?,
    hasImageAttachments: Boolean,
    runner: LocalChatRunner,
    executionContext: LocalChatExecutionContext,
    activePersistentPrefixSeed: PreparedPersistentPrefix?,
    managedPersistentPrefixFailureSeed: ManagedPersistentPrefixFailure?,
    emit: suspend (GenerateEvent) -> Unit
): McaInferenceStreamPrefillOutcome {
    var activePersistentPrefix: PreparedPersistentPrefix? = activePersistentPrefixSeed
    var managedPersistentPrefixFailure: ManagedPersistentPrefixFailure? = managedPersistentPrefixFailureSeed
                var downstreamPrefillEmissionFailed = false
                val result = try {
                    val runtimeParamsJson = JSONObject(paramsJson).apply {
                        val sessionId = activeRequest.persistentSessionId?.trim()
                            ?.takeIf(String::isNotBlank)
                        val roleId = activeRequest.persistentRoleId?.trim()
                            ?.takeIf(String::isNotBlank)
                        put("conversationOwnerId", when {
                            sessionId == null -> "request:${executionContext.requestId}"
                            roleId == null -> sessionId
                            else -> PrefixCacheKey.sha256Utf8("native-owner-v1\n$sessionId\n$roleId")
                        })
                        val contextIdentity = listOf(
                            activeLoadSession?.runtimeIdentity?.identityHash.orEmpty(),
                            activeRequest.persistentRoleCardVersion.orEmpty(),
                            activeRequest.persistentMemoryVersion.orEmpty(),
                            activeRequest.conversationContextRevision.orEmpty()
                        )
                        if (contextIdentity.any(String::isNotBlank)) {
                            put("conversationContextRevision", PrefixCacheKey.sha256Utf8(
                                contextIdentity.joinToString("\n")
                            ))
                        }
                    }.toString()
                    activePersistentPrefix?.let { previous ->
                        withContext(NonCancellable + io) {
                            discardPersistentPrefix(previous)
                            if (activePersistentPrefix === previous) activePersistentPrefix = null
                        }
                    }
                    val persistentPrefix = withContext(NonCancellable + io) {
                        preparePersistentPrefix(
                            request = activeRequest,
                            preparedParameters = preparedParameters,
                            paramsJson = paramsJson,
                            hasImageAttachments = hasImageAttachments
                        ).also { activePersistentPrefix = it }
                    }
                    val messagesJson = activeRequest.messagesJson(
                        multimodal = hasImageAttachments,
                        contentEncoding = if (activeRuntime == LocalChatRuntime.MNN_CPU) {
                            MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST
                        } else {
                            MultimodalContentEncoding.OPENAI_PARTS
                        }
                    )
                    var lastPrefillProgress: TokenProgress? = null
                    suspend fun emitExactPrefillProgress(progress: TokenProgress) {
                        try {
                            emit(
                                GenerateEvent.Phase(
                                    phase = GenerationPhase.PREFILL,
                                    stats = _stats.value,
                                    tokenProgress = progress
                                )
                            )
                        } catch (error: Throwable) {
                            downstreamPrefillEmissionFailed = true
                            throw error
                        }
                        lastPrefillProgress = progress
                    }
                    suspend fun emitPersistProgress(progress: PersistProgress) {
                        try {
                            emit(GenerateEvent.Persist(progress))
                        } catch (error: Throwable) {
                            downstreamPrefillEmissionFailed = true
                            throw error
                        }
                    }
                    val resultCode =
                        coroutineScope {
                            // A previous request leaves its final 100% snapshot
                            // available for diagnostics. Clear it before this
                            // request starts so polling can never attribute that
                            // completed work to a new prompt.
                            runCatching {
                                withContext(io) { runner.resetPrefillProgress() }
                            }
                            try {
                                emit(GenerateEvent.Phase(GenerationPhase.PREFILL, _stats.value))
                            } catch (error: Throwable) {
                                downstreamPrefillEmissionFailed = true
                                throw error
                            }
                            val nativeBegin = async(io) {
                                if (persistentPrefix == null) {
                                    runner.beginCompletion(messagesJson, runtimeParamsJson)
                                } else {
                                    runner.beginCompletionWithPrefixCache(
                                        messagesJson = messagesJson,
                                        paramsJson = runtimeParamsJson,
                                        prefixCache = persistentPrefix.request
                                    )
                                }
                            }
                            var nativeResult: Int? = null
                            var lastPersist: PersistProgress? = null
                            while (nativeResult == null) {
                                // Isolated text runners obtain this snapshot through Binder.
                                // Keep that IPC off the caller/UI dispatcher while
                                // native prefill runs in the worker process.
                                val progress = withContext(io) { runner.prefillProgress() }
                                if (progress != null && progress != lastPrefillProgress) {
                                    emitExactPrefillProgress(progress)
                                }
                                val persist = withContext(io) { runner.persistProgress() }
                                if (persist != null && persist != lastPersist) {
                                    lastPersist = persist
                                    emitPersistProgress(persist)
                                }
                                // Do not impose one polling interval of TTFT
                                // latency on short prompts: return immediately
                                // as soon as native begin completes, otherwise
                                // wake only to publish a newer exact batch count.
                                nativeResult = withTimeoutOrNull(prefillProgressPollIntervalMs) {
                                    nativeBegin.await()
                                }
                            }
                            // The final native batch can finish between the last
                            // poll and completion. Publish that exact terminal
                            // snapshot before decode starts.
                            val finalProgress = withContext(io) { runner.prefillProgress() }
                            if (finalProgress != null && finalProgress != lastPrefillProgress) {
                                emitExactPrefillProgress(finalProgress)
                            }
                            val finalPersist = withContext(io) { runner.persistProgress() }
                            if (finalPersist != null && finalPersist != lastPersist && finalPersist.isActive) {
                                emitPersistProgress(finalPersist)
                            }
                            checkNotNull(nativeResult)
                        }
                    if (persistentPrefix?.request?.fullSessionState != true || resultCode != 0) {
                        withContext(NonCancellable + io) {
                            finishPersistentPrefix(persistentPrefix, resultCode, runner)?.let { failure ->
                                managedPersistentPrefixFailure = failure
                                _stats.value = _stats.value.copy(
                                    persistentPrefixCacheHit = false,
                                    persistentPrefixCacheTokens = failure.tokens,
                                    persistentPrefixCacheReason = failure.reason
                                )
                            }
                            if (persistentPrefix != null && activePersistentPrefix === persistentPrefix) {
                                activePersistentPrefix = null
                            }
                        }
                    }
                    Result.success(resultCode)
                } catch (error: Throwable) {
                    withContext(NonCancellable + io) {
                        activePersistentPrefix?.let(::discardPersistentPrefix)
                        if (activePersistentPrefix != null) {
                            activePersistentPrefix = null
                        }
                    }
                    if (downstreamPrefillEmissionFailed) throw error
                    Result.failure(error)
                }
    return McaInferenceStreamPrefillOutcome(result, activePersistentPrefix, managedPersistentPrefixFailure)
            }
