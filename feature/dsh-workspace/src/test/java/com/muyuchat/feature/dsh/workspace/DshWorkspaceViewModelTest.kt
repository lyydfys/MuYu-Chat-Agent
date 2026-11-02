package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.viewModelScope
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshWorkspaceViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val viewModels = mutableListOf<DshWorkspaceViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun externalRestartReturnsFromStartingToOfficialPage() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)

        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        assertEquals(DshWorkspaceUiState.Starting, model.state.value)

        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        assertWeb(model, 2)
        assertEquals(2, client.entryCalls)
        assertEquals(1, client.ensureCalls)
        assertEquals(0, client.restartCalls)
    }

    @Test
    fun externalStartupFailureLeavesLoadingAndCanBeRetried() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = failed(2)
        scheduler.runCurrent()

        val failure = model.state.value as DshWorkspaceUiState.Failure
        assertEquals(DshFailureCode.NODE_START_FAILED, failure.projection.code)
        assertTrue(failure.requiresRestart)
        client.onRestart = { ready(3).also { client.snapshot.value = it } }

        model.retry()
        scheduler.runCurrent()
        assertWeb(model, 3)
        assertEquals(1, client.restartCalls)
        assertEquals(2, client.entryCalls)
    }

    @Test
    fun externalStartupEndingStoppedShowsAnExitInsteadOfLoadingForever() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STOPPED, epoch = 2)
        scheduler.runCurrent()

        val failure = model.state.value as DshWorkspaceUiState.Failure
        assertEquals(DshFailureCode.PROCESS_EXITED, failure.projection.code)
        assertTrue(failure.requiresRestart)
        assertEquals(1, client.entryCalls)
    }

    @Test
    fun readySnapshotDoesNotCompeteWithAnActiveStartForTheEntry() = runTest(dispatcher) {
        val client = RuntimeClient(DshRuntimeSnapshot())
        val startResult = CompletableDeferred<DshRuntimeSnapshot>()
        client.onEnsure = { startResult.await() }
        val model = newModel(client)
        scheduler.runCurrent()
        assertEquals(DshWorkspaceUiState.Starting, model.state.value)

        model.start()
        scheduler.runCurrent()
        client.snapshot.value = ready(1)
        scheduler.runCurrent()
        assertEquals(0, client.entryCalls)

        startResult.complete(ready(1))
        scheduler.runCurrent()
        assertWeb(model, 1)
        assertEquals(1, client.entryCalls)
    }

    @Test
    fun repeatedReadySnapshotsAndPageReentryShareTheActiveGenerationLoad() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val entry = CompletableDeferred<DshWebEntry?>()
        client.onEntry = { snapshot ->
            if (snapshot.epoch == 2L) entry.await() else tokenEntry(snapshot)
        }
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        assertEquals(2, client.entryCalls)

        client.snapshot.value = ready(2).copy(changedAtMillis = 10)
        model.start()
        scheduler.runCurrent()
        assertEquals(2, client.entryCalls)
        assertEquals(1, client.ensureCalls)

        entry.complete(tokenEntry(ready(2)))
        scheduler.runCurrent()
        assertWeb(model, 2)
        assertEquals(2, client.entryCalls)
    }

    @Test
    fun failedGenerationRejectsAnEntryReturnedAfterCancellation() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val entry = CompletableDeferred<DshWebEntry?>()
        client.onEntry = { snapshot ->
            if (snapshot.epoch == 2L) withContext(NonCancellable) { entry.await() }
            else tokenEntry(snapshot)
        }
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        client.snapshot.value = failed(2)
        scheduler.runCurrent()

        entry.complete(tokenEntry(ready(2)))
        scheduler.runCurrent()
        val failure = model.state.value as DshWorkspaceUiState.Failure
        assertEquals(DshFailureCode.NODE_START_FAILED, failure.projection.code)
        assertEquals(2, client.entryCalls)
    }

    @Test
    fun newerReadyGenerationSupersedesAnInFlightEntryWithoutStartingSnapshot() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val oldEntry = CompletableDeferred<DshWebEntry?>()
        client.onEntry = { snapshot ->
            if (snapshot.epoch == 2L) withContext(NonCancellable) { oldEntry.await() }
            else tokenEntry(snapshot)
        }
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        client.snapshot.value = ready(3)
        scheduler.runCurrent()
        assertWeb(model, 3)

        oldEntry.complete(tokenEntry(ready(2)))
        scheduler.runCurrent()
        assertWeb(model, 3)
        assertEquals(3, client.entryCalls)
    }

    @Test
    fun oldGenerationEntryFailureCannotReplaceTheNewOfficialPage() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val oldEntry = CompletableDeferred<DshWebEntry?>()
        client.onEntry = { snapshot ->
            if (snapshot.epoch == 2L) withContext(NonCancellable) { oldEntry.await() }
            else tokenEntry(snapshot)
        }
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        client.snapshot.value = ready(3)
        scheduler.runCurrent()
        assertWeb(model, 3)

        oldEntry.completeExceptionally(IllegalStateException("Old IPC failed after restart"))
        scheduler.runCurrent()
        assertWeb(model, 3)
    }

    @Test
    fun staleStartResultReconcilesTheLatestReadyGenerationWithoutConsumingOldEntry() = runTest(dispatcher) {
        val client = RuntimeClient(DshRuntimeSnapshot())
        val startResult = CompletableDeferred<DshRuntimeSnapshot>()
        client.onEnsure = { startResult.await() }
        val model = newModel(client)
        model.start()
        scheduler.runCurrent()
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        startResult.complete(ready(1))
        scheduler.runCurrent()

        assertWeb(model, 2)
        assertEquals(1, client.entryCalls)
    }

    @Test
    fun firstFrameIsReportedOnlyForTheCurrentReadyEpoch() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val reported = mutableListOf<Long>()
        val model = newModel(client, DshFirstFrameReporter { reported += it })
        model.start()
        scheduler.runCurrent()
        assertWeb(model, 1)

        model.onFirstFrameRendered(99)
        model.onFirstFrameRendered(2)
        assertTrue(reported.isEmpty())

        model.onFirstFrameRendered(1)
        model.onFirstFrameRendered(1)
        assertEquals(listOf(1L), reported)
    }

    @Test
    fun firstFrameReportIsRejectedWhenTheLiveSnapshotIsNoLongerReady() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val reported = mutableListOf<Long>()
        val model = newModel(client, DshFirstFrameReporter { reported += it })
        model.start()
        scheduler.runCurrent()
        assertWeb(model, 1)

        // The runtime left READY for the same epoch but the page has not collected it yet: a
        // visible retry/error surface must not be recorded as the official first frame.
        client.snapshot.value = failed(1)
        model.onFirstFrameRendered(1)
        assertTrue(reported.isEmpty())

        scheduler.runCurrent()
        assertTrue(model.state.value is DshWorkspaceUiState.Failure)
    }

    @Test
    fun aRecreatedGenerationReportsItsOwnFirstFrameAndIgnoresTheOldEpoch() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val reported = mutableListOf<Long>()
        val model = newModel(client, DshFirstFrameReporter { reported += it })
        model.start()
        scheduler.runCurrent()
        assertWeb(model, 1)
        model.onFirstFrameRendered(1)
        assertEquals(listOf(1L), reported)

        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        assertWeb(model, 2)

        model.onFirstFrameRendered(1)
        assertEquals(listOf(1L), reported)
        model.onFirstFrameRendered(2)
        assertEquals(listOf(1L, 2L), reported)
    }

    @Test
    fun rendererRecreationWithinOneEpochDoesNotReportTheFirstFrameTwice() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val reported = mutableListOf<Long>()
        val model = newModel(client, DshFirstFrameReporter { reported += it })
        model.start()
        scheduler.runCurrent()
        assertWeb(model, 1)

        model.onFirstFrameRendered(1)
        model.onRendererGone()
        scheduler.runCurrent()
        model.onFirstFrameRendered(1)

        assertEquals(listOf(1L), reported)
    }

    @Test
    fun firstFrameBeforeAnyPageExistsIsIgnored() = runTest(dispatcher) {
        val client = RuntimeClient(DshRuntimeSnapshot())
        val reported = mutableListOf<Long>()
        val model = newModel(client, DshFirstFrameReporter { reported += it })

        model.onFirstFrameRendered(1)

        assertTrue(reported.isEmpty())
    }

    @Test
    fun rendererGoneReloadsTheSameEpochWithoutRestartingOrResendingTheEntry() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val origin = ready(1).origin
        model.onPageFinished("$origin/chat/7", sameOrigin = true, containsLaunchToken = false)
        val before = model.state.value as DshWorkspaceUiState.Web

        model.onRendererGone()
        scheduler.runCurrent()

        val after = model.state.value as DshWorkspaceUiState.Web
        assertEquals(1L, after.epoch)
        assertTrue(!after.authenticatedEntry)
        assertEquals("$origin/chat/7", after.loadUrl)
        assertEquals(before.webGeneration + 1L, after.webGeneration)
        assertEquals(1, client.ensureCalls)
        assertEquals(0, client.restartCalls)
    }

    @Test
    fun hostDisposalDowngradesAnUnconsumedTokenEntryToCookieResume() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val web = model.state.value as DshWorkspaceUiState.Web
        assertTrue(web.authenticatedEntry)

        model.onWebHostDisposed(web.webGeneration)

        val after = model.state.value as DshWorkspaceUiState.Web
        assertTrue(!after.authenticatedEntry)
        assertEquals(web.origin.pathUrl("/"), after.loadUrl)
        assertEquals(web.epoch, after.epoch)
        assertEquals(0, client.restartCalls)
    }

    @Test
    fun disposalOfAnOlderWebGenerationCannotDowngradeANewerTokenEntry() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        client.snapshot.value = DshRuntimeSnapshot(DshRuntimePhase.STARTING, epoch = 2)
        scheduler.runCurrent()
        client.snapshot.value = ready(2)
        scheduler.runCurrent()
        val newer = model.state.value as DshWorkspaceUiState.Web
        assertTrue(newer.authenticatedEntry)

        model.onWebHostDisposed(webGeneration = 1L)

        val after = model.state.value as DshWorkspaceUiState.Web
        assertTrue(after.authenticatedEntry)
        assertEquals(2L, after.epoch)
    }

    @Test
    fun foregroundAfterALongBackgroundReloadsOnlyAnInterruptedLoad() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val before = model.state.value as DshWorkspaceUiState.Web

        model.onPageStarted()
        model.onHostBackgrounded(nowMillis = 1_000L)
        model.onHostForegrounded(nowMillis = 1_000L + DshWebRecoveryPolicy.STALE_BACKGROUND_MILLIS)

        val after = model.state.value as DshWorkspaceUiState.Web
        assertEquals(before.loadId + 1L, after.loadId)
        assertEquals(0, client.restartCalls)
        assertEquals(1, client.ensureCalls)
    }

    @Test
    fun aShortBackgroundDoesNotReloadThePage() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val before = model.state.value as DshWorkspaceUiState.Web
        model.onPageStarted()

        model.onHostBackgrounded(nowMillis = 1_000L)
        model.onHostForegrounded(nowMillis = 5_000L)

        assertEquals(before.loadId, (model.state.value as DshWorkspaceUiState.Web).loadId)
    }

    @Test
    fun aHealthyPageIsNotReloadedAfterALongBackground() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        val before = model.state.value as DshWorkspaceUiState.Web
        model.onPageCommitVisible()

        model.onHostBackgrounded(nowMillis = 1_000L)
        model.onHostForegrounded(nowMillis = 120_000L)

        assertEquals(before.loadId, (model.state.value as DshWorkspaceUiState.Web).loadId)
    }

    @Test
    fun backgroundingNeverStopsOrRestartsTheRuntime() = runTest(dispatcher) {
        val client = RuntimeClient(ready(1))
        val model = open(client)
        model.onPageStarted()

        model.onHostBackgrounded(nowMillis = 1_000L)
        model.onHostForegrounded(nowMillis = 200_000L)

        assertEquals(1, client.ensureCalls)
        assertEquals(0, client.restartCalls)
    }

    private fun newModel(
        client: RuntimeClient,
        firstFrameReporter: DshFirstFrameReporter = DshFirstFrameReporter.None
    ): DshWorkspaceViewModel =
        DshWorkspaceViewModel(client, MemoryNavigationStore(), firstFrameReporter)
            .also(viewModels::add)

    private fun open(client: RuntimeClient): DshWorkspaceViewModel = newModel(client).also {
        it.start()
        scheduler.runCurrent()
        assertWeb(it, 1)
    }

    private fun assertWeb(model: DshWorkspaceViewModel, epoch: Long) {
        val web = model.state.value as DshWorkspaceUiState.Web
        assertEquals(epoch, web.epoch)
        assertTrue(web.authenticatedEntry)
        assertEquals(tokenEntry(ready(epoch)).authenticatedUrl, web.loadUrl)
    }

    private class MemoryNavigationStore : DshNavigationStore {
        override suspend fun readLastPath(): String? = null
        override suspend fun writeLastPath(path: String?) = Unit
    }

    private class RuntimeClient(initial: DshRuntimeSnapshot) : DshRuntimeClient {
        override val snapshot = MutableStateFlow(initial)
        var ensureCalls = 0
        var restartCalls = 0
        var entryCalls = 0
        var onEnsure: (suspend () -> DshRuntimeSnapshot)? = null
        var onRestart: (suspend () -> DshRuntimeSnapshot)? = null
        var onEntry: (suspend (DshRuntimeSnapshot) -> DshWebEntry?)? = null
        private val consumedEpochs = mutableSetOf<Long>()

        override suspend fun ensureStarted(): DshRuntimeSnapshot {
            ensureCalls += 1
            return onEnsure?.invoke() ?: snapshot.value
        }

        override suspend fun restart(): DshRuntimeSnapshot {
            restartCalls += 1
            return onRestart?.invoke() ?: snapshot.value
        }

        override suspend fun stop() = Unit

        override suspend fun webEntry(): DshWebEntry? {
            entryCalls += 1
            val current = snapshot.value
            val handler = onEntry
            return if (handler != null) handler(current)
            else if (consumedEpochs.add(current.epoch)) tokenEntry(current) else null
        }
    }

    companion object {
        private fun ready(epoch: Long) = DshRuntimeSnapshot(
            phase = DshRuntimePhase.READY,
            epoch = epoch,
            origin = "http://127.0.0.1:${34000 + epoch}"
        )

        private fun failed(epoch: Long) = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = epoch,
            failure = DshRuntimeFailure(DshFailureCode.NODE_START_FAILED, "Test host exit")
        )

        private fun tokenEntry(snapshot: DshRuntimeSnapshot) = DshWebEntry(
            epoch = snapshot.epoch,
            authenticatedUrl = "${snapshot.origin}/?token=test-entry-${snapshot.epoch}"
        )
    }
}
