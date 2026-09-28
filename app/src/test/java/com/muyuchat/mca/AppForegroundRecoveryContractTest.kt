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
        assertEquals(ProcessUiLifecycleEvent.BACKGROUNDED, relay.current())
    }

    @Test
    fun localApiForegroundServicePromotesBeforeQueuedCommandsCanStopIt() {
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/LocalApiForegroundService.kt")
        val onCreate = functionBody(service, "override fun onCreate()")
        val onStart = functionBody(service, "override fun onStartCommand(")
        val stop = functionBody(service, "fun stop(context: Context)")

        assertTrue(onCreate.contains("ServiceCompat.startForeground("))
        assertTrue(onCreate.indexOf("ServiceCompat.startForeground(") < onCreate.indexOf("serviceForeground = true"))
        assertTrue(onStart.contains("if (!persistedEnabled || !isRequested())"))
        assertTrue(stop.contains("serviceCreated || serviceForeground"))
        assertTrue(stop.contains("do not cancel"))
        assertTrue(service.contains("restartAfterDestroy"))
        assertTrue(service.contains("requestedRunning && wasForeground"))
    }

    @Test
    fun initialApiRecoveryDoesNotDependOnAConsumedForegroundReplay() {
        val application = sourceFile("app/src/main/java/com/muyuchat/mca/McaApplication.kt")
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")

        assertTrue(application.contains("fun current(): ProcessUiLifecycleEvent?"))
        assertTrue(viewModel.contains("if (initialApiPreferences.apiEnabled)"))
        assertFalse(viewModel.contains("initialApiPreferences.apiEnabled &&\n            ProcessUiLifecycleEvents.current() != ProcessUiLifecycleEvent.FOREGROUNDED"))
        val foreground = functionBody(viewModel, "fun onAppForegrounded()")
        assertTrue(foreground.contains("apiLifecycleRequestJob?.let"))
        assertTrue(foreground.contains("pending.join()"))
        assertFalse(foreground.contains("apiLifecycleRequestJob?.cancel()"))
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
    fun backgroundingPreservesGenerationAndUsesAForegroundService() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "fun onAppBackgrounded()")

        assertTrue(body.contains("uiGenerationOwnership.background()"))
        assertTrue(body.contains("pauseAgentTuning()"))
        assertFalse(body.contains("engine.stopGenerationIfActive"))
        assertFalse(body.contains("backgroundedJob.cancel()"))

        assertTrue(viewModel.contains("McaGenerationForegroundService.acquire("))
        assertTrue(viewModel.contains("McaGenerationForegroundService.release("))
        assertTrue(viewModel.contains("McaGenerationForegroundService.KIND_CHAT"))
    }

    @Test
    fun imagesAndUpscaleKeepTheCallerAliveUntilTheJobHasFinished() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        for (signature in listOf("private fun enqueueImageGeneration(", "fun upscaleImageAsset(")) {
            val body = functionBody(viewModel, signature)
            val acquire = body.indexOf("val foregroundLease = McaGenerationForegroundService.acquire(")
            val launch = body.indexOf("val executionJob = viewModelScope.launch")
            val completion = body.indexOf("executionJob.invokeOnCompletion")
            val release = body.indexOf("McaGenerationForegroundService.release(getApplication<Application>(), foregroundLease)")
            assertTrue(acquire >= 0 && acquire < launch)
            assertTrue(completion > launch && release > completion)
            assertTrue(body.substring(completion, release).contains("finally"))
        }
    }

    @Test
    fun regenerationIsProtectedBeforeAsynchronousPersistence() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "fun regenerateLastResponse()")
        val acquire = body.indexOf("val preparationLease = McaGenerationForegroundService.acquire(")
        val persist = body.indexOf("val persistenceJob = persistConversationMutation(")
        assertTrue(acquire >= 0 && persist > acquire)
        assertTrue(body.contains("persistenceJob.invokeOnCompletion"))
        assertTrue(body.contains("McaGenerationForegroundService.release(getApplication<Application>(), preparationLease)"))
    }

    @Test
    fun apiStartRejectionReachesTheExistingLifecycleRollback() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "private fun setLocalApiForegroundService(")
        assertTrue(body.contains("check(LocalApiForegroundService.start("))
    }

    @Test
    fun apiListenerIsPublishedOnlyAfterForegroundServicePromotion() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "private suspend fun applyLocalApiState(")
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/LocalApiForegroundService.kt")

        assertTrue(body.contains("ensureLocalApiForegroundServiceReady("))
        assertTrue(body.indexOf("ensureLocalApiForegroundServiceReady(") < body.indexOf("startApiServer("))
        assertTrue(viewModel.contains("awaitLocalApiForegroundServiceReady()"))
        assertTrue(service.contains("fun isForegroundReady(): Boolean"))
    }

    @Test
    fun generationForegroundServiceIsDeclaredAsAUserVisibleSpecialUseService() {
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val service = sourceFile("app/src/main/java/com/muyuchat/mca/McaGenerationForegroundService.kt")

        assertTrue(manifest.contains(".McaGenerationForegroundService"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertTrue(manifest.contains("mca_user_generation"))
        assertTrue(service.contains("ContextCompat.startForegroundService"))
        assertTrue(service.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue(service.contains("START_NOT_STICKY"))
    }

    @Test
    fun apiServerBindingRequiresCurrentProcessOwnership() {
        val viewModel = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val body = functionBody(viewModel, "private fun startApiServer(")

        assertTrue(body.contains("synchronized(localApiProcessLifecycleLock)"))
        assertTrue(body.contains("localApiProcessOwnerToken !== localApiRuntimeOwner"))
        assertTrue(body.contains("apiLifecycleClosed.get()"))
        assertTrue(body.indexOf("localApiProcessOwnerToken !== localApiRuntimeOwner") < body.indexOf("LocalApiForegroundService.ensureListener("))
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
        assertTrue(cleared.contains("stopForegroundService = false"))
        assertTrue(cleared.contains("forceServerStop = false"))
        assertTrue(cleared.indexOf("LocalApiRuntime.releaseOwner(localApiRuntimeOwner)") <
            cleared.indexOf("retireLocalApiListener("))
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
