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
        val body = functionBody(mainViewModelSource(), "private fun startGeneration(")
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
        val source = mainViewModelSource()
        val body = functionBody(source, "private fun startGeneration(")
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

        val append = functionBody(source, "private fun appendAssistant(")
        val flush = functionBody(source, "private fun flushPendingAssistantOutput(")
        assertTrue(append.contains("assistantOutputBufferGenerationId"))
        assertTrue(append.contains("generationRunSequence.get() != generationRunId"))
        assertTrue(flush.contains("generationRunSequence.get() != generationRunId"))

        val provider = source.substring(
            source.indexOf("LocalApiRuntime.streamChatWithContextProvider ="),
            source.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )
        assertTrue(provider.contains("substringBefore('-')"))
        assertTrue(source.contains("ui-\$generationRunId-"))
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
        val body = functionBody(mainViewModelSource(), "fun clearChat()")
        val cancel = body.indexOf("cancelGenerationJob()")
        val stop = body.indexOf("engine.stopGeneration()")
        val close = body.indexOf("afterClearChatGenerationStopped(engine.stats.value)")

        assertTrue(cancel >= 0)
        assertTrue(stop > cancel)
        assertTrue(close > stop)
    }

    @Test
    fun backgroundStopClosesOnlyTheCapturedGenerationAndNewRunsJoinIt() {
        val source = mainViewModelSource()
        val body = functionBody(source, "fun onAppBackgrounded()")
        val ownership = body.indexOf("val cancellation = uiGenerationOwnership.background()")
        val capture = body.indexOf("val backgroundedJob = cancellation.owner as? Job")
        val token = body.indexOf("engine.activeGenerationStopToken()")
        val stop = body.indexOf("engine.stopGenerationIfActive(expectedStopToken)")
        val cancel = body.indexOf("backgroundedJob.cancel()")
        val nullOwnerEpochGuard = body.indexOf(
            "generationRunSequence.get() != cancellation.invalidatedRunId"
        )
        val epochGuard = body.indexOf(
            "generationRunSequence.get() != cancellation.invalidatedRunId",
            stop
        )
        val close = body.indexOf(
            "afterBackgroundGenerationStopped(engine.stats.value, nativeStopIssued)",
            epochGuard
        )

        assertTrue(ownership >= 0)
        assertTrue(capture > ownership)
        assertTrue(token >= 0)
        assertTrue(token < ownership)
        assertTrue(cancel > capture)
        assertTrue(stop > cancel)
        assertTrue(nullOwnerEpochGuard > capture)
        assertTrue(nullOwnerEpochGuard < cancel)
        assertTrue(epochGuard > stop)
        assertTrue(close > epochGuard)
        assertFalse(body.contains("generationJob?.cancel()"))
        assertTrue(
            functionBody(source, "private fun startGeneration(")
                .contains("pendingBackgroundStop?.join()")
        )
    }

    @Test
    fun localApiProviderOwnsSharedGenerationLifecycle() {
        val source = mainViewModelSource()
        val provider = source.substring(
            source.indexOf("LocalApiRuntime.streamChatWithContextProvider ="),
            source.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )

        assertTrue(provider.contains(".onStart"))
        assertTrue(provider.contains("afterGenerationStarted"))
        assertTrue(provider.contains(".onCompletion"))
        assertTrue(provider.contains("afterGenerationCompleted(engine.stats.value)"))
        assertTrue(source.contains("state.engineLifecycle == AgentEngineLifecycle.GENERATING"))
        assertTrue(source.contains("\"generation_in_progress\""))
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
        val body = functionBody(mainViewModelSource(), "fun updateAgentPreference(")
        val recommendation = body.indexOf("tuningPlan?.applyTo")
        val transactionalUpdate = body.indexOf("updateParams(updatedParams)")
        val preferenceUpdate = body.indexOf("_uiState.update", transactionalUpdate)

        assertTrue(recommendation >= 0)
        assertTrue(transactionalUpdate > recommendation)
        assertTrue(preferenceUpdate > transactionalUpdate)
        assertFalse(body.contains("persistGenerationParams(updatedParams)"))
        assertFalse(Regex("""\bparams\s*=\s*updatedParams\b""").containsMatchIn(body))
    }

    @Test
    fun runtimeSessionClearerDropsUiAndNonUiOwners() {
        val body = functionBody(mainViewModelSource(), "private fun clearNativeRuntimeSessionState(")

        listOf(
            "activeRuntimeIdentity = null",
            "activeModelForRuntimeProfile = null",
            "activeAdaptiveRecommendation = null",
            "pendingAdaptiveRecommendation = null",
            "pendingProfileTransactionId = null",
            "activeProfileTransactionId = null",
            "afterNativeRuntimeReleased("
        ).forEach { required ->
            assertTrue("Missing cleanup: $required", body.contains(required))
        }
    }

    @Test
    fun loadedModelDeletionClearsRuntimeBeforeFileDeletionCanFail() {
        val body = functionBody(mainViewModelSource(), "fun deleteModel(")
        val unload = body.indexOf("engine.unloadModel()")
        val clear = body.indexOf("clearNativeRuntimeSessionState(", unload)
        val deleteFiles = body.indexOf("modelStore.deleteModel(model.id)", unload)

        assertTrue(unload >= 0)
        assertTrue(clear > unload)
        assertTrue(deleteFiles > clear)
        assertTrue(body.contains("删除失败，但模型已安全卸载"))
    }

    @Test
    fun qairtLoadUsesGenericEnginePathWithoutMandatoryDiagnosticCanary() {
        val body = functionBody(mainViewModelSource(), "fun loadModel(")
        val nativeLoad = body.indexOf("val nativeLoad = engine.loadModel(")

        assertTrue(nativeLoad >= 0)
        assertTrue(body.contains("qairtBundleSha256 = qairtBundleSha256"))
        assertFalse(body.contains("shouldRunAutomaticQairtCanary"))
        assertFalse(body.contains("QairtDryRunWorkerClient"))
    }

    @Test
    fun failedLoadReleasesCancelledGenerationBeforeReadingDiagnostics() {
        val body = functionBody(mainViewModelSource(), "fun loadModel(")
        val failStart = body.indexOf("fun failBeforeNativeReplacement")
        val cancel = body.indexOf("cancelGenerationJob()", failStart)
        val fail = body.substring(failStart, cancel)
        val release = fail.indexOf("afterGenerationTerminated(")
        val uiUpdate = fail.indexOf("_uiState.update")
        val nativeStats = fail.indexOf("currentNativeStatsJson()")

        assertTrue(failStart >= 0)
        assertTrue(release >= 0)
        assertTrue(uiUpdate >= 0)
        assertTrue(nativeStats > uiUpdate)
        assertTrue(nativeStats > release)
    }

    @Test
    fun failedLifecycleOperationsReleaseCancelledGeneration() {
        val source = mainViewModelSource()
        val unload = functionBody(source, "fun unloadModel(")
        val delete = functionBody(source, "fun deleteModel(")

        val unloadCancel = unload.indexOf("cancelGenerationJob()")
        val unloadRelease = unload.indexOf("afterGenerationTerminated(")
        assertTrue(unloadCancel >= 0)
        assertTrue(unloadRelease > unloadCancel)

        val deleteCancel = delete.indexOf("cancelGenerationJob()")
        val deleteFailure = delete.indexOf("wasLoaded && !nativeReleased")
        val deleteRelease = delete.indexOf("afterGenerationTerminated(", deleteFailure)
        assertTrue(deleteCancel >= 0)
        assertTrue(deleteFailure > deleteCancel)
        assertTrue(deleteRelease > deleteFailure)
    }

    @Test
    fun localApiActiveProfileProjectionRequiresARealLoadedRuntime() {
        val source = mainViewModelSource()
        val profileBody = functionBody(source, "private fun runtimeProfileJson()")
        val paramsBody = functionBody(source, "private fun apiGenerationParams()")

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
        val body = functionBody(mainViewModelSource(), "fun loadModel(")
        val nativeLoad = body.indexOf("val nativeLoad = engine.loadModel(")
        val getOrThrow = body.indexOf("nativeLoad.getOrThrow()", nativeLoad)
        val persist = body.indexOf("persistBootstrapProfile(", getOrThrow)
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
        val unloadBody = functionBody(mainViewModelSource(), "fun unloadModel(")
        val unloadNative = unloadBody.indexOf("engine.unloadModel()")
        val unloadProjection = unloadBody.indexOf("clearNativeRuntimeSessionState(", unloadNative)
        val unloadPending = unloadBody.indexOf("clearPendingRuntimeTransactionForLifecycle(", unloadProjection)

        assertTrue(unloadNative >= 0)
        assertTrue(unloadProjection > unloadNative)
        assertTrue(unloadPending > unloadProjection)

        val deleteBody = functionBody(mainViewModelSource(), "fun deleteModel(")
        val deleteNative = deleteBody.indexOf("engine.unloadModel()")
        val deleteProjection = deleteBody.indexOf("clearNativeRuntimeSessionState(", deleteNative)
        val deletePending = deleteBody.indexOf("clearPendingRuntimeTransactionForLifecycle(", deleteProjection)

        assertTrue(deleteNative >= 0)
        assertTrue(deleteProjection > deleteNative)
        assertTrue(deletePending > deleteProjection)
    }

    @Test
    fun localSendWaitsForForegroundWorkerReconciliation() {
        val body = functionBody(mainViewModelSource(), "private fun startGeneration(")
        val wait = body.indexOf("foregroundRecoveryJob?.takeUnless")
        val invalidate = body.indexOf("ensureLocalConversationContextInvalidated()")

        assertTrue(wait >= 0)
        assertTrue(invalidate > wait)
        assertTrue(body.contains("initialState.selectedChatBackend == ChatBackend.LOCAL"))
    }

    private fun mainViewModelSource(): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/MainViewModel.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("MainViewModel.kt not found from ${System.getProperty("user.dir")}")
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
