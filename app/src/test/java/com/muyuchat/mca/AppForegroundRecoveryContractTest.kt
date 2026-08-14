package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundRecoveryContractTest {
    @Test
    fun applicationOwnsProcessLifecycleSoConfigurationChangesAreNotTransitions() {
        val application = sourceFile("app/src/main/java/com/muyuchat/mca/McaApplication.kt")
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")

        assertTrue(application.contains("ProcessLifecycleOwner.get().lifecycle.addObserver(this)"))
        assertTrue(application.contains("ProcessUiLifecycleEvent.FOREGROUNDED"))
        assertTrue(application.contains("ProcessUiLifecycleEvent.BACKGROUNDED"))
        assertTrue(viewModel.contains("ProcessUiLifecycleEvents.events.collect"))
        assertTrue(viewModel.contains("ProcessUiLifecycleEvent.FOREGROUNDED -> onAppForegrounded()"))
        assertTrue(viewModel.contains("ProcessUiLifecycleEvent.BACKGROUNDED -> onAppBackgrounded()"))
        assertFalse(activity.contains("ProcessLifecycleOwner"))
        assertFalse(activity.contains("LifecycleEventObserver"))
        assertFalse(activity.contains("viewModel.onAppBackgrounded()"))
        assertFalse(activity.contains("override fun onStop()"))
    }

    @Test
    fun processLifecycleRelayDeduplicatesCallbacksAndReplaysTheCurrentState() = runBlocking {
        val relay = ProcessUiLifecycleEventRelay()
        val observed = mutableListOf<ProcessUiLifecycleEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            relay.events.take(2).toList(observed)
        }

        assertTrue(relay.publish(ProcessUiLifecycleEvent.FOREGROUNDED))
        assertFalse(relay.publish(ProcessUiLifecycleEvent.FOREGROUNDED))
        assertTrue(relay.publish(ProcessUiLifecycleEvent.BACKGROUNDED))
        assertFalse(relay.publish(ProcessUiLifecycleEvent.BACKGROUNDED))
        collector.join()

        assertEquals(
            listOf(
                ProcessUiLifecycleEvent.FOREGROUNDED,
                ProcessUiLifecycleEvent.BACKGROUNDED
            ),
            observed
        )
        assertEquals(ProcessUiLifecycleEvent.BACKGROUNDED, relay.events.first())
    }

    @Test
    fun foregroundRecoverySerializesApiAndProbesOnlyAnIdleEngine() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "fun onAppForegrounded()")

        assertTrue(body.contains("foregroundRecoverySequence.incrementAndGet()"))
        assertTrue(body.contains("foregroundRecoveryJob?.cancel()"))
        assertTrue(body.contains("apiLifecycleSequence.incrementAndGet()"))
        assertTrue(body.contains("applyLocalApiState("))
        assertTrue(body.contains("engine.tryRuntimeHealthSnapshot()"))
        assertTrue(body.contains("engine.stats.value == health.runtimeStats"))
        assertFalse(body.contains("Dispatchers.Main"))
        assertFalse(body.contains("engine.nativeStatsJson()"))
        assertFalse(body.contains("engine.loadModel("))
    }

    @Test
    fun apiSideEffectsAndFailureRollbackShareTheSerializedLifecycleGate() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "private suspend fun applyLocalApiState(")

        assertTrue(body.contains("apiLifecycleMutex.withLock"))
        assertTrue(body.contains("operationIsCurrent()"))
        assertTrue(body.contains("startApiServer("))
        assertTrue(body.contains("stopApiServer()"))
        assertTrue(body.contains("setLocalApiForegroundService("))
        assertFalse(body.contains("persistApiPreferences(apiEnabled = false, restEnabled = false)"))
        assertTrue(body.contains("apiEnabled = false"))
        assertTrue(body.contains("restEnabled = false"))
    }

    @Test
    fun backgroundStopCannotCancelAReplacementGenerationJob() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "fun onAppBackgrounded()")

        assertTrue(body.contains("val uiRunId = generationRunSequence.get()"))
        assertTrue(body.contains("engine.activeGenerationStopToken()"))
        assertTrue(body.contains("token.requestId.startsWith(\"ui-${'$'}uiRunId-\")"))
        assertTrue(body.contains("val cancellation = uiGenerationOwnership.background()"))
        assertTrue(body.contains("val backgroundedJob = cancellation.owner as? Job"))
        assertTrue(body.contains("generationRunSequence.get() != cancellation.invalidatedRunId"))
        assertTrue(
            body.split("generationRunSequence.get() != cancellation.invalidatedRunId").size - 1 >= 3
        )
        assertTrue(body.contains("backgroundedJob.cancel()"))
        assertTrue(body.contains("engine.stopGenerationIfActive(expectedStopToken)"))
        assertTrue(
            body.split("persistChatSessions(generationRunId = cancellation.invalidatedRunId)").size - 1 == 2
        )
        assertTrue(
            body.indexOf("backgroundedJob.cancel()") <
                body.indexOf("engine.stopGenerationIfActive(expectedStopToken)")
        )
        assertTrue(body.contains("nativeStopIssued = false"))
        assertFalse(body.contains("engine.stopGeneration()"))
        assertFalse(body.contains("generationJob?.cancel()"))
    }

    @Test
    fun apiServerBindingRequiresCurrentProcessOwnership() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "private fun startApiServer(")

        assertTrue(body.contains("synchronized(localApiProcessLifecycleLock)"))
        assertTrue(body.contains("localApiProcessOwnerToken !== localApiRuntimeOwner"))
        assertTrue(body.contains("apiLifecycleClosed.get()"))
        assertTrue(body.indexOf("localApiProcessOwnerToken !== localApiRuntimeOwner") < body.indexOf("next.start()"))
    }

    @Test
    fun workerStatsCannotObserveHalfAppliedLoadOrUnloadState() {
        val worker = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt")
        val body = functionBody(worker, "override fun getRuntimeStatsJson()")

        assertTrue(body.contains("synchronized(lock)"))
        assertTrue(body.contains("runner?.getRuntimeStatsJson()"))
    }

    @Test
    fun viewModelReleasesGlobalApiProvidersOnlyThroughItsOwnerToken() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val runtime = sourceFile("api/local/src/main/java/com/muyuchat/api/local/LocalApiRuntime.kt")
        val cleared = functionBody(viewModel, "override fun onCleared()")
        val release = functionBody(runtime, "fun releaseOwner(token: Any)")

        assertTrue(viewModel.contains("LocalApiRuntime.claimOwner(localApiRuntimeOwner)"))
        assertTrue(cleared.contains("LocalApiRuntime.releaseOwner(localApiRuntimeOwner)"))
        assertFalse(cleared.contains("LocalApiRuntime.engine = null"))
        assertTrue(release.contains("streamChatWithContextProvider = null"))
        assertTrue(release.contains("loadedModelJsonProvider = { \"{}\" }"))
        assertTrue(release.contains("benchmarkJsonProvider = { \"{}\" }"))
        assertTrue(release.contains("controlPlane = null"))
    }

    @Test
    fun cloudChoicesPreferTheUserFacingNameOverTheProviderModelId() {
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")

        assertTrue(
            activity.split("displayName = model.displayName.ifBlank { model.modelName }").size - 1 >= 2
        )
        assertFalse(activity.contains("displayName = model.modelName,"))
    }

    @Test
    fun rootBackMovesTheTaskToBackgroundWithoutDestroyingRuntimeOwnership() {
        val activity = sourceFile("app/src/main/java/com/muyuchat/mca/MainActivity.kt")

        assertTrue(activity.contains("onBackPressedDispatcher.addCallback("))
        assertTrue(activity.contains("moveTaskToBack(true)"))
        assertFalse(activity.contains("override fun onBackPressed()"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate source file: $relativePath")
    }
}
