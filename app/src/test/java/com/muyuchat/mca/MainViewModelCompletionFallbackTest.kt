package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
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
    fun clearChatInvokesLifecycleClosureAfterStopAndCancel() {
        val body = functionBody(mainViewModelSource(), "fun clearChat()")
        val stop = body.indexOf("engine.stopGeneration()")
        val cancel = body.indexOf("generationJob?.cancel()")
        val close = body.indexOf("afterClearChatGenerationStopped(engine.stats.value)")

        assertTrue(stop >= 0)
        assertTrue(cancel > stop)
        assertTrue(close > cancel)
    }

    @Test
    fun backgroundStopInvokesLifecycleClosureAfterStopAndCancel() {
        val body = functionBody(mainViewModelSource(), "fun onAppBackgrounded()")
        val stop = body.indexOf("engine.stopGeneration()")
        val cancel = body.indexOf("generationJob?.cancel()")
        val close = body.indexOf("afterBackgroundGenerationStopped(engine.stats.value)")

        assertTrue(stop >= 0)
        assertTrue(cancel > stop)
        assertTrue(close > cancel)
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
    fun qairtCanaryReleasesMainProcessModelBeforeBindingWorker() {
        val body = functionBody(mainViewModelSource(), "fun loadModel(")
        val canaryBranch = body.indexOf("if (shouldRunAutomaticQairtCanary")
        val unload = body.indexOf("engine.unloadModel()", canaryBranch)
        val worker = body.indexOf("QairtDryRunWorkerClient", canaryBranch)

        assertTrue(canaryBranch >= 0)
        assertTrue(unload > canaryBranch)
        assertTrue(worker > unload)
        assertTrue(body.contains("restoreLoadedRuntimeSnapshot("))
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
