package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.muyuchat.mca.presentation.lifecycleAfterGeneration
import com.muyuchat.mca.presentation.afterClearChatGenerationStopped
import com.muyuchat.mca.presentation.afterBackgroundGenerationStopped
import com.muyuchat.mca.presentation.afterGenerationStarted
import com.muyuchat.mca.presentation.afterGenerationCompleted
import com.muyuchat.mca.presentation.afterGenerationTerminated
import com.muyuchat.mca.presentation.afterNativeRuntimeReleased
import com.muyuchat.mca.presentation.nativeRuntimeReleaseObserved
import com.muyuchat.mca.presentation.withBackgroundCancellationFinalized
import com.muyuchat.mca.presentation.BackgroundCancelledAssistantOutput

class MainViewModelCompletionFallbackTest {
    @Test
    fun blankAssistantReplyGetsVisibleFallbackWithoutDroppingReasoning() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "  ", reasoningContent = "hidden reasoning")
        )

        val updated = messages.withVisibleAssistantCompletionFallback()

        assertEquals(EMPTY_ASSISTANT_COMPLETION_MESSAGE, updated.last().content)
        assertEquals("hidden reasoning", updated.last().reasoningContent)
    }

    @Test
    fun visibleAssistantReplyIsLeftUntouched() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "visible answer")
        )

        val updated = messages.withVisibleAssistantCompletionFallback()

        assertSame(messages, updated)
    }

    @Test
    fun completedLoadedGenerationAlwaysReturnsLifecycleToReady() {
        assertEquals(
            AgentEngineLifecycle.READY,
            RuntimeStats(loaded = true).lifecycleAfterGeneration()
        )
        assertEquals(
            AgentEngineLifecycle.READY,
            RuntimeStats(loaded = true, lastError = "request failed").lifecycleAfterGeneration()
        )
    }

    @Test
    fun completedUnloadedGenerationPreservesErrorOrUnloadedState() {
        assertEquals(
            AgentEngineLifecycle.ERROR,
            RuntimeStats(loaded = false, lastError = "failed").lifecycleAfterGeneration()
        )
        assertEquals(
            AgentEngineLifecycle.UNLOADED,
            RuntimeStats(loaded = false).lifecycleAfterGeneration()
        )
    }

    @Test
    fun sharedGenerationLifecycleMarksApiAndUiWorkAsGenerating() {
        val started = MainUiState(
            isGenerating = false,
            engineLifecycle = AgentEngineLifecycle.READY
        ).afterGenerationStarted()

        assertTrue(started.isGenerating)
        assertEquals(AgentEngineLifecycle.GENERATING, started.engineLifecycle)
    }

    @Test
    fun sharedGenerationLifecycleReturnsLoadedRuntimeToReadyOnCompletion() {
        val completed = MainUiState(
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterGenerationCompleted(RuntimeStats(loaded = true, completionTokens = 3))

        assertEquals(false, completed.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, completed.engineLifecycle)
        assertEquals(3, completed.stats.completionTokens)
    }

    @Test
    fun terminalGenerationStateReleasesTheInputBeforeAnyOptionalBookkeeping() {
        val terminated = MainUiState(
            isGenerating = true,
            generationPhase = GenerationPhase.DECODE,
            generationTokenProgress = TokenProgress(3, 7),
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterGenerationTerminated(
            stats = RuntimeStats(loaded = true, completionTokens = 3),
            statusMessage = "stream ended"
        )

        assertFalse(terminated.isGenerating)
        assertNull(terminated.generationPhase)
        assertNull(terminated.generationTokenProgress)
        assertEquals(AgentEngineLifecycle.READY, terminated.engineLifecycle)
        assertEquals("stream ended", terminated.statusMessage)
    }

    @Test
    fun terminalEventsReleaseUiBeforeBinderAndPersistenceWorkAndHaveFallback() {
        // P3 step 1 moved the execution body to ChatExecutionOperations.runChatGeneration; the
        // assertions are unchanged -- the terminal-event sequence is read where it now lives.
        val body = functionBody(chatExecutionOperationsSource(), "fun ChatGenerationExecutionPort.runChatGeneration(")
        val doneStart = body.indexOf("is GenerateEvent.Done ->")
        val errorStart = body.indexOf("is GenerateEvent.Error ->")
        val done = body.substring(doneStart, errorStart)
        val error = body.substring(errorStart)

        val doneRelease = done.indexOf("settleGenerationUi(event.stats)")
        assertTrue(doneStart >= 0)
        assertTrue(errorStart > doneStart)
        assertTrue(doneRelease >= 0)
        assertTrue(done.indexOf("flushPendingAssistantOutput(generationRunId)") > doneRelease)
        assertTrue(done.indexOf("LocalApiRuntime.generationSequence()") > doneRelease)
        assertTrue(done.indexOf("applyWebSearchAnswerGuardsToLastAssistant(generationRunId)") > doneRelease)
        assertTrue(done.indexOf("engine.recentLogs()") > doneRelease)
        assertTrue(done.contains("withContext(Dispatchers.IO) { engine.recentLogs() }"))

        val errorRelease = error.indexOf("settleGenerationUi(event.stats, event.message)")
        assertTrue(errorRelease >= 0)
        assertTrue(error.indexOf("flushPendingAssistantOutput(generationRunId)") > errorRelease)
        assertTrue(error.indexOf("LocalApiRuntime.generationSequence()") > errorRelease)
        assertTrue(body.contains("catch (error: Throwable)"))
        assertTrue(body.contains("!terminalEventSeen && generationStillOwnsUi()"))
        assertTrue(body.contains("生成流已结束，但运行时未返回完成状态，请重试。"))
    }

    @Test
    fun staleGenerationEventsCannotWriteIntoANewerUiRun() {
        // P3 step 1 moved the execution body to ChatExecutionOperations.runChatGeneration; the
        // assertions are unchanged -- the event-order contract is read where it now lives.
        val source = mainViewModelSource()
        val execution = chatExecutionOperationsSource()
        val body = functionBody(execution, "fun ChatGenerationExecutionPort.runChatGeneration(")
        val collect = body.indexOf("stream.collect { event ->")
        val phase = body.indexOf("is GenerateEvent.Phase ->", collect)
        val chunk = body.indexOf("is GenerateEvent.Chunk ->", phase)
        val done = body.indexOf("is GenerateEvent.Done ->", chunk)
        val error = body.indexOf("is GenerateEvent.Error ->", done)
        val eventOwnershipCheck = body.indexOf("if (!generationStillOwnsUi()) return@collect", collect)

        assertTrue(collect >= 0)
        assertTrue(phase > collect)
        assertTrue(chunk > phase)
        assertTrue(done > chunk)
        assertTrue(error > done)
        assertTrue(eventOwnershipCheck in (collect + 1) until phase)

        val phaseBody = body.substring(phase, chunk)
        val chunkBody = body.substring(chunk, done)
        val doneBody = body.substring(done, error)
        val errorBody = body.substring(error)
        assertTrue(phaseBody.contains("if (!generationStillOwnsUi())"))
        assertTrue(chunkBody.contains("if (!generationStillOwnsUi())"))
        assertTrue(chunkBody.contains("generationRunId = generationRunId"))
        assertTrue(doneBody.contains("flushPendingAssistantOutput(generationRunId)"))
        assertTrue(doneBody.contains("persistChatSessions(generationRunId = generationRunId)"))
        assertTrue(errorBody.contains("flushPendingAssistantOutput(generationRunId)"))

        // The buffering itself moved to ChatStreamAccumulator: appendAssistant and
        // flushPendingAssistantOutput are one-line forwarders now, and the generation-ownership
        // guard lives in the owner. The contract under test is unchanged -- it is read where the
        // guard now is.
        val accumulator = chatStreamAccumulatorSource()
        val append = functionBody(accumulator, "fun append(")
        val flush = functionBody(accumulator, "fun flush(")
        assertTrue(append.contains("bufferGenerationId"))
        assertTrue(append.contains("currentRunId() != generationRunId"))
        assertTrue(flush.contains("currentRunId() != generationRunId"))

        val install = chatLocalApiProviderSource()
        val provider = install.substring(
            install.indexOf("LocalApiRuntime.streamChatWithContextProvider ="),
            install.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )
        assertTrue(provider.contains("substringBefore('-')"))
        assertTrue(execution.contains("ui-\$generationRunId-"))
    }

    @Test
    fun clearChatStopPathReturnsLoadedRuntimeToReady() {
        val stopped = MainUiState(
            messages = listOf(ChatMessage(Role.USER, "hello")),
            input = "draft",
            activeChatSessionId = "session-1",
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterClearChatGenerationStopped(RuntimeStats(loaded = true))

        assertEquals(emptyList<ChatMessage>(), stopped.messages)
        assertEquals("", stopped.input)
        assertNull(stopped.activeChatSessionId)
        assertEquals(false, stopped.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, stopped.engineLifecycle)
        assertEquals("已清空对话，上下文已重置", stopped.statusMessage)
    }

    @Test
    fun backgroundStopPathReturnsLoadedRuntimeToReadyWithoutClearingConversation() {
        val messages = listOf(ChatMessage(Role.USER, "hello"))
        val stopped = MainUiState(
            messages = messages,
            input = "draft",
            activeChatSessionId = "session-1",
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterBackgroundGenerationStopped(RuntimeStats(loaded = true))

        assertSame(messages, stopped.messages)
        assertEquals("draft", stopped.input)
        assertEquals("session-1", stopped.activeChatSessionId)
        assertEquals(false, stopped.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, stopped.engineLifecycle)
        assertEquals("应用进入后台，已停止生成以降低发热和耗电。", stopped.statusMessage)
    }

    @Test
    fun backgroundCancellationRemovesAnUntouchedAssistantPlaceholder() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "")
        )

        val updated = messages.withBackgroundCancellationFinalized(null)

        assertEquals(1, updated.size)
        assertSame(messages.first(), updated.first())
    }

    @Test
    fun backgroundCancellationCommitsTheLastBufferedAssistantOutput() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "visible")
        )

        val updated = messages.withBackgroundCancellationFinalized(
            BackgroundCancelledAssistantOutput(
                content = " tail",
                reasoning = "reasoning",
                reasoningDurationMs = 17L
            )
        )

        assertEquals("visible tail", updated.last().content)
        assertEquals("reasoning", updated.last().reasoningContent)
        assertEquals(17L, updated.last().reasoningDurationMs)
    }

    @Test
    fun clearChatInvokesLifecycleClosureAfterStopAndCancel() {
        val entry = functionBody(mainViewModelSource(), "fun clearChat()")
        assertTrue(entry.contains("clearConversationFor("))
        assertTrue(entry.contains("cancelGeneration = { cancelGenerationJob() }"))
        assertTrue(entry.contains("stopGenerationAndReadStats = runtimeAssembly::stopGenerationAndReadStats"))
        // D02 moved the transaction to its field-limited owner; pin the same order at that owner.
        val source = conversationReplayOperationsSource()
        val body = functionBody(source, "internal suspend fun clearConversationFor(")
        val cancel = body.indexOf("cancelGeneration()")
        val stop = body.indexOf("stopGenerationAndReadStats()")
        val close = body.indexOf("afterConversationGenerationStopped(stoppedStats)")
        val persist = body.indexOf("persistChatSessions()")

        assertTrue(cancel >= 0)
        assertTrue(stop > cancel)
        assertTrue(close > stop)
        assertTrue(persist > close)
    }

    @Test
    fun userStopBoundsTheNativeStopRequestBeforeJoiningTheUiJob() {
        // The stop path moved to its own operations file during R1 (item 2); the bounded-request
        // invariant is read where the orchestration now lives.
        val source = stopOperationsSource()
        val body = functionBody(source, "internal fun stopActiveGeneration(")
        val stop = body.indexOf("port.requestRuntimeStop(attempt)")
        val requestTimeout = body.indexOf("withTimeoutOrNull(STOP_GENERATION_REQUEST_TIMEOUT_MS)")
        val joinTimeout = body.indexOf("withTimeoutOrNull(STOP_GENERATION_JOIN_TIMEOUT_MS)")

        assertTrue(stop >= 0)
        assertTrue(requestTimeout >= 0)
        assertTrue(joinTimeout > requestTimeout)
        assertTrue("the exact attempt must be frozen before the stop coroutine is launched",
            body.indexOf("port.cancelCurrentGeneration()") in 0 until body.indexOf("port.stopScope().launch"))
        assertTrue(source.contains("internal const val STOP_GENERATION_REQUEST_TIMEOUT_MS = 750L"))
        assertTrue(body.contains("已请求停止，正在恢复本地推理进程"))
        assertTrue(body.contains("rejectWhileConversationMutationInProgress()"))
    }

    @Test
    fun backgroundKeepsTheCapturedGenerationAndItsCompletionProtection() {
        val source = mainViewModelSource()
        val body = functionBody(source, "fun onAppBackgrounded()")
        // Read at its new owner: the fact -- backgrounding keeps the captured generation -- is the same.
        assertTrue(body.contains("chatRuns.background()"))
        assertFalse(body.contains("stopGeneration"))
        assertFalse(body.contains("generationJob?.cancel()"))
        assertFalse(body.contains("backgroundedJob.cancel()"))
        assertFalse(body.contains("afterBackgroundGenerationStopped"))

        val generation = functionBody(source, "fun startGeneration(")
        assertTrue(generation.contains("val foregroundLease = McaGenerationForegroundService.acquire("))
        assertTrue(generation.contains("ownedGenerationJob.invokeOnCompletion"))
        assertTrue(generation.contains("McaGenerationForegroundService.release(getApplication<Application>(), foregroundLease)"))
        assertFalse(generation.contains("McaGenerationForegroundService.stop("))
    }

    @Test
    fun startGenerationBodyTakesItsOwnJobFromItsCoroutineScope() {
        // The launch body used to close over the `lateinit var ownedGenerationJob` that was still
        // being assigned while the lambda was built. Resolving the handle from the body's own scope
        // receiver removed that self-reference, which was the documented blocker for moving the
        // execution body to its own owner. P3 step 1 has now moved it: the launcher shape is pinned
        // on MainViewModel, the body's own handle and its five markPhase calls on the execution
        // owner. The assertions are otherwise unchanged.
        val launcher = functionBody(mainViewModelSource(), "fun startGeneration(")
        // Generation now runs on the process-level runtime, not on viewModelScope: leaving the chat
        // page (character-card screen, tab switch, Activity recreation) must not cancel a running
        // generation. The scope itself is the contract this test pins.
        assertTrue(
            launcher.contains(
                "val ownedGenerationJob = McaGenerationRuntime.scope.launch(start = CoroutineStart.LAZY)"
            )
        )
        assertFalse(launcher.contains("viewModelScope.launch(start = CoroutineStart.LAZY)"))
        assertFalse(launcher.contains("lateinit var ownedGenerationJob"))
        val body = functionBody(chatExecutionOperationsSource(), "fun ChatGenerationExecutionPort.runChatGeneration(")
        assertTrue(body.contains("val selfJob = requireNotNull(coroutineContext[Job])"))
        val selfOwnedMarkPhases = Regex("""markPhase\(\s*\n\s*generationRunId,\s*\n\s*selfJob,""").findAll(body).count()
        assertEquals("all in-body markPhase calls must pass the body's own handle", 5, selfOwnedMarkPhases)
    }

    @Test
    fun localApiProviderOwnsSharedGenerationLifecycle() {
        // P3 step 2 moved the provider installation to ChatLocalApiProviderOperations; the
        // lifecycle assertions are unchanged -- the provider is read where it now lives. P8/S40
        // then moved the read-only control-plane facts to LocalApiControlPlaneOperations.kt, so
        // the two `contains` facts below are read dual-source; assertion strength is unchanged.
        val source = mainViewModelSource()
        val controlPlane = localApiControlPlaneSource()
        val install = chatLocalApiProviderSource()
        val provider = install.substring(
            install.indexOf("LocalApiRuntime.streamChatWithContextProvider ="),
            install.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )

        assertTrue(provider.contains(".onStart"))
        assertTrue(provider.contains("afterGenerationStarted"))
        assertTrue(provider.contains(".onCompletion"))
        // The typed CAS reducer uses one frozen runtime snapshot across retries.
        val completion = provider.substring(provider.indexOf(".onCompletion"))
        val frozenStats = completion.indexOf("val completedStats = engine.stats.value")
        assertTrue(frozenStats >= 0)
        assertTrue(frozenStats < completion.indexOf("port.updateUiState {"))
        assertTrue(completion.contains("afterGenerationCompleted(completedStats)"))
        assertTrue(
            source.contains("state.engineLifecycle == AgentEngineLifecycle.GENERATING") ||
                controlPlane.contains("state.engineLifecycle == AgentEngineLifecycle.GENERATING")
        )
        assertTrue(
            source.contains("\"generation_in_progress\"") ||
                controlPlane.contains("\"generation_in_progress\"")
        )
    }

    @Test
    fun nativeRuntimeReleaseClearsEveryActiveRuntimeProjection() {
        val released = MainUiState(
            loadedModelId = "old-model",
            loadedModelName = "Old model",
            busy = true,
            isGenerating = true,
            autoTuningInProgress = true,
            rollbackParams = com.muyuchat.core.engine.GenerationParams(nCtx = 4096),
            profileId = "active-profile",
            revision = 7,
            profileRecordState = AgentProfileRecordState.COMMITTED,
            verification = AgentProfileVerification.DEVICE_VERIFIED,
            engineLifecycle = AgentEngineLifecycle.READY,
            tuningJobState = AgentTuningJobState.VALIDATING,
            reloadRequired = true,
            pendingProfile = AgentPendingProfile("pending", 8, "pending", true),
            rollbackProfile = AgentRollbackProfile("lkg", 6, "lkg", true),
            tuningEtaSeconds = 30,
            tuningPhase = "canary",
            tuningCandidateProgress = AgentCandidateProgress(completed = 1, total = 2)
        ).afterNativeRuntimeReleased(
            lifecycle = AgentEngineLifecycle.ERROR,
            statusMessage = "load failed"
        )

        assertNull(released.loadedModelId)
        assertNull(released.loadedModelName)
        assertFalse(released.busy)
        assertFalse(released.isGenerating)
        assertFalse(released.autoTuningInProgress)
        assertFalse(released.reloadRequired)
        assertNull(released.rollbackParams)
        assertNull(released.profileId)
        assertNull(released.revision)
        assertNull(released.pendingProfile)
        assertNull(released.rollbackProfile)
        assertNull(released.tuningEtaSeconds)
        assertNull(released.tuningPhase)
        assertEquals(AgentProfileRecordState.NONE, released.profileRecordState)
        assertEquals(AgentProfileVerification.UNKNOWN, released.verification)
        assertEquals(AgentTuningJobState.IDLE, released.tuningJobState)
        assertEquals(AgentCandidateProgress(), released.tuningCandidateProgress)
        assertEquals(AgentEngineLifecycle.ERROR, released.engineLifecycle)
    }

    @Test
    fun agentPreferenceRuntimeChangesUseTransactionalUpdateParamsEntryPoint() {
        val vmBody = functionBody(mainViewModelSource(), "fun updateAgentPreference(")
        assertTrue(vmBody.contains("agentTuningCoordinator.updateAgentPreference(preference)"))

        val body = functionBody(agentTuningOperationsSource(), "fun updateAgentPreference(")
        val recommendation = body.indexOf("tuningPlan?.applyTo")
        val transactionalUpdate = body.indexOf("updateParams(updatedParams)")
        // R3/S23: 写入侧全面迁经 UiStateStore；顺序锚点随迁。
        val preferenceUpdate = body.indexOf("store.update", transactionalUpdate)

        assertTrue(recommendation >= 0)
        assertTrue(transactionalUpdate > recommendation)
        assertTrue(preferenceUpdate > transactionalUpdate)
        assertFalse(body.contains("persistGenerationParams(updatedParams)"))
        assertFalse(Regex("""\bparams\s*=\s*updatedParams\b""").containsMatchIn(body))
    }

    @Test
    fun runtimeSessionClearerDropsUiAndNonUiOwners() {
        // D02 keeps the same non-UI cleanup and UI terminal publication, now through the
        // RuntimeProfileFields transition so this owner cannot write unrelated state.
        val body = functionBody(
            runtimeReleaseOperationsSource(),
            "internal fun clearNativeRuntimeSessionStateFor("
        )

        listOf(
            "admission.activeRuntimeIdentity = null",
            "admission.activeModelForRuntimeProfile = null",
            "admission.activeAdaptiveRecommendation = null",
            "admission.pendingAdaptiveRecommendation = null",
            "admission.pendingProfileTransactionId = null",
            "admission.activeProfileTransactionId = null",
            "afterRuntimeNativeReleased("
        ).forEach { required ->
            assertTrue("Missing cleanup: $required", body.contains(required))
        }
        assertTrue(body.contains("store: UiStateWriter<RuntimeProfileFields>"))
        val snapshot = body.indexOf("val releasedStats = engine.stats.value")
        val publication = body.indexOf("store.update")
        val terminal = body.indexOf("afterRuntimeNativeReleased(")
        assertTrue("Native stats must be frozen before the retryable publication", snapshot >= 0 && snapshot < publication)
        assertTrue("The typed terminal transition must be committed by the writer", terminal > publication)
        assertTrue(body.contains("stats = releasedStats"))
    }

    @Test
    fun loadedModelDeletionClearsRuntimeBeforeFileDeletionCanFail() {
        // R3-S23: 删除体逐字迁至 RuntimeLifecycleOperations.kt；顺序合同不变——读新落点，
        // 调用拼写随迁（clearNativeRuntimeSessionStateFor）。
        val body = functionBody(
            runtimeLifecycleOperationsSource(),
            "internal fun deleteModelFor("
        )
        val unload = body.indexOf("engine.unloadModel()")
        val clear = body.indexOf("clearNativeRuntimeSessionStateFor(", unload)
        val deleteFiles = body.indexOf("modelStore.deleteModel(model.id)", unload)

        assertTrue(unload >= 0)
        assertTrue(clear > unload)
        assertTrue(deleteFiles > clear)
        assertTrue(body.contains("删除失败，但模型已安全卸载"))
    }

    @Test
    fun qairtLoadUsesGenericEnginePathWithoutMandatoryDiagnosticCanary() {
        // R3-S24: the load chain moved verbatim to LoadModelOperations.kt; read where it lives.
        val body = functionBody(
            loadModelOperationsSource(),
            "internal suspend fun CoroutineScope.runLoadModelBody("
        )
        val nativeLoad = body.indexOf("val nativeLoad = engine.loadModel(")

        assertTrue(nativeLoad >= 0)
        assertTrue(body.contains("qairtBundleSha256 = qairtBundleSha256"))
        assertFalse(body.contains("shouldRunAutomaticQairtCanary"))
        assertFalse(body.contains("QairtDryRunWorkerClient"))
    }

    @Test
    fun failedLoadReleasesCancelledGenerationBeforeReadingDiagnostics() {
        val body = functionBody(
            loadModelOperationsSource(),
            "internal suspend fun CoroutineScope.runLoadModelBody("
        )
        // The preflight failure releases the same input lock through the field-limited terminal
        // transition; native/Binder diagnostics must still run only after that commit.
        val fail = functionBody(body, "fun failBeforeNativeReplacement(")
        val uiUpdate = fail.indexOf("store.update")
        val release = fail.indexOf("afterRuntimeGenerationTerminated(")
        val nativeStats = fail.indexOf("engine.nativeStatsJsonOrEmpty()")
        val recentLogs = fail.indexOf("engine.recentEngineLogsOrEmpty()")
        val diagnosticPublication = fail.indexOf("store.update", uiUpdate + 1)

        assertTrue(uiUpdate >= 0)
        assertTrue(release > uiUpdate)
        assertTrue(nativeStats > release)
        assertTrue(recentLogs > nativeStats)
        assertTrue("Diagnostics must be read before their separate pure publication", diagnosticPublication > recentLogs)
        assertTrue(fail.contains("nativeStatsJson = nativeStats"))
        assertTrue(fail.contains("logs = recentLogs"))
        val cancel = body.indexOf("cancelGeneration()")
        val preflight = body.indexOf("val preflight = modelStore.validateForLoad(")
        assertTrue("The superseded generation must be cancelled before preflight can refuse the load",
            cancel >= 0 && preflight > cancel)
    }

    @Test
    fun failedLifecycleOperationsReleaseCancelledGeneration() {
        // D02 narrows the writer and terminal transition without changing cancellation/failure
        // order. Keep reading the real unload/delete owners rather than a VM delegate.
        val source = runtimeLifecycleOperationsSource()
        val unload = functionBody(source, "internal fun unloadModelFor(")
        val delete = functionBody(source, "internal fun deleteModelFor(")

        val unloadCancel = unload.indexOf("cancelGeneration()")
        val unloadRelease = unload.indexOf("afterRuntimeGenerationTerminated(")
        assertTrue(unloadCancel >= 0)
        val unloadFailure = unload.indexOf("nativeRelease.isFailure &&")
        assertTrue(unloadFailure > unloadCancel)
        assertTrue(unloadRelease > unloadFailure)
        assertTrue(unload.contains("store: UiStateWriter<RuntimeProfileFields>"))

        val deleteCancel = delete.indexOf("cancelGeneration()")
        val deleteFailure = delete.indexOf("wasLoaded && !nativeReleased")
        val deleteRelease = delete.indexOf("afterRuntimeGenerationTerminated(", deleteFailure)
        assertTrue(deleteCancel >= 0)
        assertTrue(deleteFailure > deleteCancel)
        assertTrue(deleteRelease > deleteFailure)
        assertTrue(delete.contains("store: UiStateWriter<RuntimeProfileFields>"))
    }

    @Test
    fun localApiActiveProfileProjectionRequiresARealLoadedRuntime() {
        val source = mainViewModelSource()
        // R3/S18+S19: runtimeProfileJson 与 apiGenerationParams 均已移至 RuntimeEngineReads 缝（体逐字）；双源读取。
        val profileBody = functionBody(runtimeEngineReadsSource(), "internal fun runtimeProfileJsonFor(")
        val paramsBody = functionBody(runtimeEngineReadsSource(), "internal fun engineApiGenerationParams(")

        assertTrue(profileBody.contains("state.loadedModelId != null && engine.stats.value.loaded"))
        assertTrue(paramsBody.contains("state.loadedModelId != null && engine.stats.value.loaded"))
    }

    @Test
    fun nativeReleaseWitnessNeverTrustsMalformedDiagnosticsOverLoadedEngine() {
        assertTrue(nativeRuntimeReleaseObserved(engineLoaded = false, nativeStatsJson = "not-json"))
        assertTrue(nativeRuntimeReleaseObserved(engineLoaded = true, nativeStatsJson = """{"loaded":false}"""))
        assertFalse(nativeRuntimeReleaseObserved(engineLoaded = true, nativeStatsJson = """{"loaded":true}"""))
        assertFalse(nativeRuntimeReleaseObserved(engineLoaded = true, nativeStatsJson = "not-json"))
    }

    @Test
    fun modelLoadUsesOneCatchBoundaryForNativeCanaryAndPersistenceFailures() {
        // R3-S24: 读新落点；persistBootstrapProfile 拼写随迁为 For。
        val body = functionBody(
            loadModelOperationsSource(),
            "internal suspend fun CoroutineScope.runLoadModelBody("
        )
        val nativeLoad = body.indexOf("val nativeLoad = engine.loadModel(")
        val getOrThrow = body.indexOf("nativeLoad.getOrThrow()", nativeLoad)
        val persist = body.indexOf("persistBootstrapProfileFor(", getOrThrow)
        val catchBoundary = body.lastIndexOf("catch (error: Throwable)")
        val recovery = body.indexOf("recoverAfterNativeReplacement", catchBoundary)

        assertTrue(nativeLoad >= 0)
        assertTrue(getOrThrow > nativeLoad)
        assertTrue(persist > getOrThrow)
        assertTrue(catchBoundary > persist)
        assertTrue(recovery > catchBoundary)
        assertFalse(body.contains("}.onSuccess {"))
    }

    @Test
    fun unloadAndDeleteClearPendingOnlyAfterNativeOwnershipIsGone() {
        // R3-S23: 两体迁至 RuntimeLifecycleOperations.kt；两个 For 调用拼写随迁，顺序合同不变。
        val source = runtimeLifecycleOperationsSource()
        val unloadBody = functionBody(source, "internal fun unloadModelFor(")
        val unloadNative = unloadBody.indexOf("engine.unloadModel()")
        val unloadProjection = unloadBody.indexOf("clearNativeRuntimeSessionStateFor(", unloadNative)
        val unloadPending = unloadBody.indexOf("clearPendingRuntimeTransactionForLifecycleFor(", unloadProjection)

        assertTrue(unloadNative >= 0)
        assertTrue(unloadProjection > unloadNative)
        assertTrue(unloadPending > unloadProjection)

        val deleteBody = functionBody(source, "internal fun deleteModelFor(")
        val deleteNative = deleteBody.indexOf("engine.unloadModel()")
        val deleteProjection = deleteBody.indexOf("clearNativeRuntimeSessionStateFor(", deleteNative)
        val deletePending = deleteBody.indexOf("clearPendingRuntimeTransactionForLifecycleFor(", deleteProjection)

        assertTrue(deleteNative >= 0)
        assertTrue(deleteProjection > deleteNative)
        assertTrue(deletePending > deleteProjection)
    }

    @Test
    fun localSendWaitsForForegroundWorkerReconciliation() {
        // P3 step 1 moved the execution body to ChatExecutionOperations.runChatGeneration; the
        // wait and its ordering are unchanged -- they are read where they now live.
        val body = functionBody(chatExecutionOperationsSource(), "fun ChatGenerationExecutionPort.runChatGeneration(")
        // Wait only for the recovery job; waiting for the published generation would self-join.
        val wait = body.indexOf("recoveryJobToAwait(foregroundRecoveryJob, selfJob)?.join()")
        val invalidate = body.indexOf("ensureLocalConversationContextInvalidated()")

        assertTrue(wait >= 0)
        assertTrue(invalidate > wait)
        assertTrue(body.contains("effectiveChatBackend == ChatBackend.LOCAL"))
    }

    /** v4-①：位置无关——按标记定位承载文件（VM 类声明处），读者不再含路径字面量。 */
    private fun mainViewModelSource(): String =
        SourceTextFixtures.sourceOf("class MainViewModel @JvmOverloads internal constructor(")

    /** R3/C3-S24: the load vertical (runLoadModelBody etc.)；v4-① 标记定位。 */
    private fun loadModelOperationsSource(): String =
        SourceTextFixtures.sourceOf("internal suspend fun CoroutineScope.runLoadModelBody(")

    /** R3/C3-S23: the load/unload/delete vertical；v4-① 标记定位。 */
    private fun runtimeLifecycleOperationsSource(): String =
        SourceTextFixtures.sourceOf("internal suspend fun restoreLoadedRuntimeSnapshotFor(")

    /** R3/C3-S19: the runtime release family；v4-① 标记定位。 */
    private fun runtimeReleaseOperationsSource(): String =
        SourceTextFixtures.sourceOf("internal fun clearNativeRuntimeSessionStateFor(")

    /** R3/S18: the runtime engine-read seam that now hosts apiGenerationParams. */
    private fun runtimeEngineReadsSource(): String {        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/RuntimeEngineReads.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/RuntimeEngineReads.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("RuntimeEngineReads.kt not found from ${System.getProperty("user.dir")}")
    }

    /** The owner that holds the streamed-output buffer and its generation-ownership guard. */
    private fun chatStreamAccumulatorSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/ChatStreamAccumulator.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/ChatStreamAccumulator.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("ChatStreamAccumulator.kt not found from ${System.getProperty("user.dir")}")
    }

    /** The owner that runs the chat generation execution body (P3 step 1). */
    private fun chatExecutionOperationsSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("ChatExecutionOperations.kt not found from ${System.getProperty("user.dir")}")
    }

    /** The owner that installs the Local API chat providers (P3 step 2). */
    private fun localApiControlPlaneSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/LocalApiControlPlaneOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/LocalApiControlPlaneOperations.kt")
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("Unable to locate LocalApiControlPlaneOperations.kt")
    }

    private fun chatLocalApiProviderSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/ChatLocalApiProviderOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/ChatLocalApiProviderOperations.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("ChatLocalApiProviderOperations.kt not found from ${System.getProperty("user.dir")}")
    }

    /** The owner that orchestrates the user-facing stop path. */
    private fun stopOperationsSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/ChatStopGenerationOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/ChatStopGenerationOperations.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("ChatStopGenerationOperations.kt not found from ${System.getProperty("user.dir")}")
    }

    private fun agentTuningOperationsSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/AgentTuningOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/AgentTuningOperations.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("AgentTuningOperations.kt not found from ${System.getProperty("user.dir")}")
    }

    private fun conversationReplayOperationsSource(): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/ConversationReplayOperations.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/ConversationReplayOperations.kt")
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("Unable to locate ConversationReplayOperations.kt")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
