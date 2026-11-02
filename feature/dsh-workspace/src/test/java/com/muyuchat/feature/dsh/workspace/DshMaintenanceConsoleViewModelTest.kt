package com.muyuchat.feature.dsh.workspace

import androidx.lifecycle.viewModelScope
import com.muyuchat.core.dsh.contract.console.DshMaintenanceBackup
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleAvailability
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleIntent
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleRefusal
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleResult
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshMaintenanceOperationKind
import com.muyuchat.core.dsh.contract.console.DshMaintenanceSession
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DshMaintenanceConsoleViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val viewModels = mutableListOf<DshMaintenanceConsoleViewModel>()

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
        val port = FakePort(snapshot(revision = 5))

        val model = start(port)

        val ready = model.state.value as DshMaintenanceConsoleUiState.Ready
        assertEquals(5L, ready.snapshot.revision)
        assertEquals(5L, ready.model.revision)
        assertTrue(DshMaintenanceConsoleIntent.CreateBackup() in ready.availableIntents)
        assertEquals(1, port.snapshotCalls)
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
    fun unavailableOwnerProducesAnHonestDisabledState() = runTest(dispatcher) {
        val port = FakePort(snapshot(availability = DshMaintenanceConsoleAvailability.UNAVAILABLE))

        val model = start(port)

        val disabled = model.state.value as DshMaintenanceConsoleUiState.Disabled
        assertTrue(disabled.reason.isNotEmpty())
    }

    @Test
    fun snapshotFailureAndRetryAreTyped() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.snapshotFailure = IllegalStateException("owner unavailable")
        val model = newModel(port)
        model.start()
        scheduler.runCurrent()

        val failure = model.state.value as DshMaintenanceConsoleUiState.Failure
        assertEquals("MAINTENANCE_SNAPSHOT_UNAVAILABLE", failure.code)
        assertTrue(failure.retryable)

        port.snapshotFailure = null
        model.retry()
        scheduler.runCurrent()
        assertTrue(model.state.value is DshMaintenanceConsoleUiState.Ready)
    }

    @Test
    fun busyOwnerOffersNoActionButtons() = runTest(dispatcher) {
        val port = FakePort(
            snapshot(
                availability = DshMaintenanceConsoleAvailability.BUSY,
                busy = DshMaintenanceOperationKind.CREATE_BACKUP
            )
        )

        val ready = start(port).state.value as DshMaintenanceConsoleUiState.Ready

        assertTrue(ready.availableIntents.isEmpty())
        assertFalse(ready.model.actionsEnabled)
    }

    @Test
    fun unconfirmedDestructiveIntentIsRefusedLocally() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        val model = start(port)

        model.execute(DshMaintenanceConsoleIntent.ApplyConsolePatch())
        scheduler.runCurrent()

        assertEquals(0, port.executeCalls)
        val ready = model.state.value as DshMaintenanceConsoleUiState.Ready
        assertEquals("该操作需要先确认。", ready.notice)
    }

    @Test
    fun confirmedDestructiveIntentReachesThePortAndRefreshes() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.result = DshMaintenanceConsoleResult.Applied(
            DshMaintenanceOperationKind.PURGE_TRASHED_SESSION
        )
        val model = start(port)

        model.execute(
            DshMaintenanceConsoleIntent.PurgeTrashedSession("session-trashed", confirmed = true)
        )
        scheduler.runCurrent()

        assertEquals(1, port.executeCalls)
        assertEquals(2, port.snapshotCalls)
        val ready = model.state.value as DshMaintenanceConsoleUiState.Ready
        assertTrue(ready.notice.orEmpty().contains("彻底删除"))
    }

    @Test
    fun busyRefusalFromTheOwnerIsShownAsANoticeWithoutRefresh() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.result = DshMaintenanceConsoleResult.Refused(
            DshMaintenanceOperationKind.CREATE_BACKUP,
            DshMaintenanceConsoleRefusal.BUSY
        )
        val model = start(port)

        model.execute(DshMaintenanceConsoleIntent.CreateBackup())
        scheduler.runCurrent()

        assertEquals(1, port.executeCalls)
        assertEquals(1, port.snapshotCalls)
        val ready = model.state.value as DshMaintenanceConsoleUiState.Ready
        assertEquals("维护操作正在进行，请等待完成。", ready.notice)
    }

    @Test
    fun failedResultKeepsTheOwnerMessage() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.result = DshMaintenanceConsoleResult.Failed(
            DshMaintenanceOperationKind.CREATE_BACKUP,
            code = "STORAGE_INSUFFICIENT",
            retryable = true,
            message = "存储空间不足。"
        )
        val model = start(port)

        model.execute(DshMaintenanceConsoleIntent.CreateBackup())
        scheduler.runCurrent()

        assertEquals("存储空间不足。（STORAGE_INSUFFICIENT）", (model.state.value as DshMaintenanceConsoleUiState.Ready).notice)
    }

    @Test
    fun aPortExceptionBecomesANoticeInsteadOfACrash() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        port.executeFailure = IllegalStateException("owner failed")
        val model = start(port)

        model.execute(DshMaintenanceConsoleIntent.CreateBackup())
        scheduler.runCurrent()

        val ready = model.state.value as DshMaintenanceConsoleUiState.Ready
        assertEquals("维护操作失败，请重试。", ready.notice)
        assertFalse(ready.busy)
    }

    @Test
    fun aSecondIntentIsIgnoredWhileTheOwnerIsRunning() = runTest(dispatcher) {
        val port = FakePort(snapshot())
        val gate = CompletableDeferred<Unit>()
        port.executeGate = gate
        val model = start(port)

        model.execute(DshMaintenanceConsoleIntent.CreateBackup())
        scheduler.runCurrent()
        assertTrue((model.state.value as DshMaintenanceConsoleUiState.Ready).busy)

        model.execute(DshMaintenanceConsoleIntent.ListSessions)
        scheduler.runCurrent()
        assertEquals(1, port.executeCalls)

        gate.complete(Unit)
        scheduler.runCurrent()
        assertEquals(1, port.executeCalls)
    }

    @Test
    fun emptyListsKeepOnlyListAndCreateActions() = runTest(dispatcher) {
        val port = FakePort(snapshot(backups = emptyList(), sessions = emptyList()))

        val ready = start(port).state.value as DshMaintenanceConsoleUiState.Ready

        assertEquals("尚无备份", ready.model.backupsNote)
        assertEquals("暂无会话", ready.model.sessionsNote)
        assertTrue(ready.availableIntents.any { it == DshMaintenanceConsoleIntent.CreateBackup() })
        assertTrue(ready.availableIntents.none { it is DshMaintenanceConsoleIntent.RestoreBackup })
    }

    @Test
    fun projectionIsNotMutatedBySubmittingAnIntent() = runTest(dispatcher) {
        val port = FakePort(snapshot(revision = 7))
        port.result = DshMaintenanceConsoleResult.Applied(DshMaintenanceOperationKind.LIST_SESSIONS)
        val model = start(port)
        val before = (model.state.value as DshMaintenanceConsoleUiState.Ready).snapshot

        model.execute(DshMaintenanceConsoleIntent.ListSessions)
        scheduler.runCurrent()

        val after = (model.state.value as DshMaintenanceConsoleUiState.Ready).snapshot
        assertEquals(before, after)
        assertEquals(setOf(DshMaintenanceConsoleIntent.ListSessions), port.executed.toSet())
    }

    private fun start(port: FakePort): DshMaintenanceConsoleViewModel =
        newModel(port).also {
            it.start()
            scheduler.runCurrent()
        }

    private fun newModel(port: FakePort): DshMaintenanceConsoleViewModel =
        DshMaintenanceConsoleViewModel(port).also(viewModels::add)

    private class FakePort(var current: DshMaintenanceConsoleSnapshot) : DshMaintenanceConsolePort {
        var snapshotCalls = 0
        var executeCalls = 0
        var snapshotFailure: Throwable? = null
        var executeFailure: Throwable? = null
        var executeGate: CompletableDeferred<Unit>? = null
        var result: DshMaintenanceConsoleResult =
            DshMaintenanceConsoleResult.Applied(DshMaintenanceOperationKind.CREATE_BACKUP)
        val executed = mutableListOf<DshMaintenanceConsoleIntent>()

        override suspend fun snapshot(): DshMaintenanceConsoleSnapshot {
            snapshotCalls += 1
            snapshotFailure?.let { throw it }
            return current
        }

        override suspend fun execute(intent: DshMaintenanceConsoleIntent): DshMaintenanceConsoleResult {
            executeCalls += 1
            executed += intent
            executeGate?.await()
            executeFailure?.let { throw it }
            return result
        }
    }

    private fun snapshot(
        revision: Long = 1L,
        availability: DshMaintenanceConsoleAvailability = DshMaintenanceConsoleAvailability.AVAILABLE,
        busy: DshMaintenanceOperationKind? = null,
        backups: List<DshMaintenanceBackup> = listOf(
            DshMaintenanceBackup("backup-1", createdAtMillis = 900L, label = "每日备份")
        ),
        sessions: List<DshMaintenanceSession> = listOf(
            DshMaintenanceSession("session-1", updatedAtMillis = 950L, messageCount = 4),
            DshMaintenanceSession("session-trashed", updatedAtMillis = 940L, messageCount = 2, trashed = true)
        )
    ): DshMaintenanceConsoleSnapshot = DshMaintenanceConsoleSnapshot(
        revision = revision,
        capturedAtMillis = 1_000L,
        availability = availability,
        availableOperations = DshMaintenanceOperationKind.entries.toSet(),
        busyOperation = busy,
        backups = backups,
        sessions = sessions,
        trashCount = sessions.count { it.trashed },
        diagnosticsReady = true
    )
}
