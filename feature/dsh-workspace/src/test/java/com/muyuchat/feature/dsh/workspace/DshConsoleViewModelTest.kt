package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.viewModelScope
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot
import com.muyuchat.core.dsh.contract.console.DshConsoleDiagnosticsProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshConsoleIntentResult
import com.muyuchat.core.dsh.contract.console.DshConsoleMaintenanceProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshConsoleRejection
import com.muyuchat.core.dsh.contract.console.DshConsoleRuntimeProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshConsoleViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val viewModels = mutableListOf<DshConsoleViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun startLoadsTheReadOnlyProjection() = runTest(dispatcher) {
        val port = FakePort(snapshot(revision = 3))

        val model = start(port)

        val ready = model.state.value as DshConsoleUiState.Ready
        assertEquals(3L, ready.snapshot.revision)
        assertEquals(3L, ready.model.revision)
        assertEquals(1, port.snapshotCalls)
        assertTrue(DshConsoleIntent.Refresh in ready.availableIntents)
    }

    @Test
    fun startIsIdempotent() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        val model = start(port)

        model.start()
        scheduler.runCurrent()

        assertEquals(1, port.snapshotCalls)
    }

    @Test
    fun snapshotFailureProducesATypedFailureAndRetryReloads() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.failure = IllegalStateException("owner unavailable")
        val model = newModel(port)
        model.start()
        scheduler.runCurrent()

        val failure = model.state.value as DshConsoleUiState.Failure
        assertEquals("SNAPSHOT_UNAVAILABLE", failure.code)
        assertTrue(failure.retryable)

        port.failure = null
        model.retry()
        scheduler.runCurrent()
        assertTrue(model.state.value is DshConsoleUiState.Ready)
    }

    @Test
    fun aDeniedIntentNeverReachesThePort() = runTest(dispatcher) {
        val port = FakePort(snapshot(phase = DshRuntimePhase.STOPPED))
        val model = start(port)

        model.execute(DshConsoleIntent.StopRuntime)
        scheduler.runCurrent()

        assertEquals(0, port.executeCalls)
        assertTrue(model.notice.value?.text.orEmpty().contains("尚未就绪"))
    }

    @Test
    fun anAllowedIntentRunsOnceThenRefreshesTheProjection() = runTest(dispatcher) {
        val port = FakePort(snapshot(revision = 1))
        val model = start(port)

        model.execute(DshConsoleIntent.ExportDiagnostics)
        scheduler.runCurrent()

        assertEquals(1, port.executeCalls)
        assertEquals(listOf(DshConsoleIntent.ExportDiagnostics), port.executed)
        assertEquals(2, port.snapshotCalls)
        assertEquals(1L, (model.state.value as DshConsoleUiState.Ready).snapshot.revision)
    }

    @Test
    fun aRejectedIntentKeepsTheProjectionAndShowsTheReason() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.result = DshConsoleIntentResult.Rejected(
            DshConsoleRejection.USER_PRESENCE_REQUIRED,
            "需要在可见页面完成"
        )
        val model = start(port)

        model.execute(DshConsoleIntent.ExportDiagnostics)
        scheduler.runCurrent()

        assertEquals(1, port.executeCalls)
        assertEquals(1, port.snapshotCalls)
        assertTrue(model.notice.value?.text.orEmpty().contains("可见页面"))
    }

    @Test
    fun aPortExceptionBecomesANoticeInsteadOfACrash() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.executeFailure = IllegalStateException("owner failed")
        val model = start(port)

        model.execute(DshConsoleIntent.ExportDiagnostics)
        scheduler.runCurrent()

        assertTrue(model.notice.value?.text.orEmpty().contains("操作失败"))
        assertTrue(model.state.value is DshConsoleUiState.Ready)
    }

    @Test
    fun refreshKeepsTheOldProjectionVisibleWhileReloading() = runTest(dispatcher) {
        val port = FakePort(snapshot(revision = 1))
        val model = start(port)

        model.refresh()

        val refreshing = model.state.value as DshConsoleUiState.Ready
        assertTrue(refreshing.refreshing)
        assertEquals(1L, refreshing.snapshot.revision)

        scheduler.runCurrent()
        assertEquals(2, port.snapshotCalls)
    }

    @Test
    fun officialSurfaceIntentUsesTheListedSurfaceOnly() = runTest(dispatcher) {
        val port = FakePort(snapshot(surfaces = setOf(DshConsoleOfficialSurface.SETTINGS)))
        val model = start(port)

        model.execute(DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.PLUGINS))
        scheduler.runCurrent()
        assertEquals(0, port.executeCalls)

        model.execute(DshConsoleIntent.OpenOfficialSurface(DshConsoleOfficialSurface.SETTINGS))
        scheduler.runCurrent()
        assertEquals(1, port.executeCalls)
    }

    @Test
    fun consumeNoticeClearsOnlyTheMatchingNotice() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.result = DshConsoleIntentResult.Applied("done")
        val model = start(port)
        model.execute(DshConsoleIntent.ExportDiagnostics)
        scheduler.runCurrent()
        val notice = requireNotNull(model.notice.value)

        model.consumeNotice(notice.id + 1)
        assertEquals(notice, model.notice.value)
        model.consumeNotice(notice.id)
        assertEquals(null, model.notice.value)
    }

    private fun start(port: FakePort): DshConsoleViewModel =
        newModel(port).also {
            it.start()
            scheduler.runCurrent()
        }

    private fun newModel(port: FakePort): DshConsoleViewModel =
        DshConsoleViewModel(port).also(viewModels::add)

    private class FakePort(var current: DshConsoleSnapshot) : DshConsolePort {
        var snapshotCalls = 0
        var executeCalls = 0
        var failure: Throwable? = null
        var executeFailure: Throwable? = null
        var result: DshConsoleIntentResult = DshConsoleIntentResult.Applied()
        val executed = mutableListOf<DshConsoleIntent>()

        override suspend fun snapshot(): DshConsoleSnapshot {
            snapshotCalls += 1
            failure?.let { throw it }
            return current
        }

        override suspend fun execute(intent: DshConsoleIntent): DshConsoleIntentResult {
            executeCalls += 1
            executed += intent
            executeFailure?.let { throw it }
            return result
        }
    }

    private fun snapshot(
        revision: Long = 1L,
        phase: DshRuntimePhase = DshRuntimePhase.READY,
        surfaces: Set<DshConsoleOfficialSurface> = setOf(DshConsoleOfficialSurface.SETTINGS)
    ): DshConsoleSnapshot = DshConsoleSnapshot(
        revision = revision,
        capturedAtMillis = 1_000L,
        runtime = DshConsoleRuntimeProjection(
            phase = phase,
            generation = 4L,
            origin = "http://127.0.0.1:34000",
            stopRequested = false,
            firstFrameRendered = true,
            failureCode = null,
            modelBound = true
        ),
        maintenance = DshConsoleMaintenanceProjection(
            DshMaintenanceAdmissionSnapshot(
                barrierActive = false,
                mutationLeaseCount = 0,
                readLeaseCount = 0
            )
        ),
        diagnostics = DshConsoleDiagnosticsProjection(
            webViewGeneration = 1,
            rendererRestarts = 0,
            lastErrorCode = "RENDERER_GONE",
            redactedLines = listOf("host ready")
        ),
        officialSurfaces = surfaces
    )
}
