package com.muyuchat.mca

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshBindingId
import com.muyuchat.core.dsh.runtime.state.DshInFlightChannel
import com.muyuchat.core.dsh.runtime.state.DshInFlightSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the single automatic DSH binding-refresh call site.
 *
 * These tests pin the properties the feature exists for: burst coalescing (a model operation
 * that publishes several states must not reload the host several times), the READY-only guard (a
 * model change never starts DSH), typed outcomes (a deferred/unobservable/rolled-back answer is
 * never reported as applied), bounded retrying of an unobservable deferral, and the idle reconcile
 * that retries a deferred in-flight refresh exactly once after a typed observation reports zero
 * running calls — while an unobservable probe is never read as idle.
 */
class DshBindingRefreshCoordinatorTest {

    private val requested = DshBindingId.of("requested")
    private val retained = DshBindingId.of("retained")

    private fun snapshot(epoch: Long = 3L) = DshRuntimeSnapshot(
        phase = DshRuntimePhase.READY,
        epoch = epoch,
        changedAtMillis = 1L
    )

    private fun result(
        status: DshApplyStatus,
        epoch: Long = 3L,
        unobservable: Boolean = false,
        change: String? = "model-catalog"
    ): DshApplyResult {
        val applied = when (status) {
            DshApplyStatus.APPLIED, DshApplyStatus.UNCHANGED -> requested
            DshApplyStatus.ROLLED_BACK -> retained
            else -> null
        }
        return DshApplyResult(
            status = status,
            requested = requested,
            applied = applied,
            retained = retained,
            epoch = epoch,
            snapshot = snapshot(epoch),
            change = change,
            inFlightCalls = if (unobservable) null else 0,
            inFlightUnobservable = unobservable,
            detail = if (unobservable) "the current generation was left running" else null
        )
    }

    private fun TestScope.coordinator(
        ready: Boolean = true,
        readyProvider: (() -> Boolean)? = null,
        refresh: suspend (Boolean) -> DshApplyResult?,
        debounce: Long = 500L,
        retry: Long = 2_000L,
        maxAttempts: Int = 2,
        observeInFlight: suspend () -> DshInFlightSnapshot? = { null },
        idlePoll: Long = 1_000L,
        maxIdlePolls: Int = 4,
        states: MutableList<DshBindingRefreshState> = mutableListOf()
    ) = DshBindingRefreshCoordinator(
        scope = this,
        ready = readyProvider ?: { ready },
        refresh = refresh,
        onState = { states += it },
        debounceMillis = debounce,
        retryDelayMillis = retry,
        maxAttempts = maxAttempts,
        observeInFlight = observeInFlight,
        idlePollIntervalMillis = idlePoll,
        maxIdlePolls = maxIdlePolls,
        minIntervalMillis = 1_000L,
        clock = { testScheduler.currentTime },
        sleep = { delay(it) }
    )

    @Test
    fun aBurstOfModelChangesProducesExactlyOneRefresh() = runTest {
        var calls = 0
        val coordinator = coordinator(refresh = {
            calls += 1
            result(DshApplyStatus.APPLIED)
        })

        coordinator.onModelCatalogChanged("model-loaded")
        advanceTimeBy(100L)
        coordinator.onModelCatalogChanged("cloud-model-saved")
        advanceTimeBy(100L)
        coordinator.onModelCatalogChanged("cloud-model-saved")
        advanceUntilIdle()

        assertEquals(1, calls)
        assertEquals(DshBindingRefreshState.Applied(3L, "model-catalog"), coordinator.state.value)
    }

    @Test
    fun aChangeDuringARefreshIsCoalescedIntoOneFollowUpAttempt() = runTest {
        var calls = 0
        val firstAttempt = CompletableDeferred<Unit>()
        val coordinator = coordinator(refresh = {
            calls += 1
            if (calls == 1) firstAttempt.await()
            result(DshApplyStatus.APPLIED, epoch = calls.toLong())
        })

        coordinator.onModelCatalogChanged("first")
        advanceTimeBy(600L)
        assertEquals(1, calls)

        // Arrives while the first apply is still running: it must not start a second one now.
        coordinator.onModelCatalogChanged("second")
        advanceTimeBy(100L)
        assertEquals(1, calls)

        firstAttempt.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(DshBindingRefreshState.Applied(2L, "model-catalog"), coordinator.state.value)
    }

    @Test
    fun aNotReadyRuntimeIsNeverStartedAndReportsNotReady() = runTest {
        var calls = 0
        val coordinator = coordinator(ready = false, refresh = {
            calls += 1
            result(DshApplyStatus.APPLIED)
        })

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals(0, calls)
        assertEquals(DshBindingRefreshState.NotReady, coordinator.state.value)
    }

    @Test
    fun aNullApplyResultIsReportedAsNotReadyRatherThanApplied() = runTest {
        val coordinator = coordinator(refresh = { null })

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals(DshBindingRefreshState.NotReady, coordinator.state.value)
    }

    @Test
    fun observedInFlightWorkIsADeferredInFlightOutcomeAndIsNotRetriedWhileUnobservable() = runTest {
        var calls = 0
        var probes = 0
        val coordinator = coordinator(
            refresh = {
                calls += 1
                result(DshApplyStatus.DEFERRED_IN_FLIGHT, unobservable = false)
            },
            // Wait: the deferral was caused by observed work, but the idle seam cannot answer.
            // "Cannot observe" must never become "idle", so no retry may happen.
            observeInFlight = {
                probes += 1
                DshInFlightSnapshot.unavailable("healthz returned 503")
            }
        )

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals(1, calls)
        assertTrue("the idle seam must be polled", probes >= 1)
        val state = coordinator.state.value
        assertTrue(state is DshBindingRefreshState.Deferred)
        assertEquals(
            DshBindingRefreshDeferReason.IN_FLIGHT,
            (state as DshBindingRefreshState.Deferred).reason
        )
    }

    @Test
    fun anInFlightDeferralIsRetriedOnceWhenTheObservedCallsDropToZero() = runTest {
        var calls = 0
        var observedCalls = 2
        val coordinator = coordinator(
            refresh = {
                calls += 1
                if (calls == 1) {
                    result(DshApplyStatus.DEFERRED_IN_FLIGHT)
                } else {
                    result(DshApplyStatus.APPLIED)
                }
            },
            observeInFlight = {
                DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to observedCalls))
            }
        )

        coordinator.onModelCatalogChanged("model-loaded")
        // Let the first attempt run and answer Deferred(IN_FLIGHT); the watchdog is now armed.
        advanceTimeBy(600L)
        assertEquals(1, calls)

        // The turn ends: the typed observation now reports a real zero.
        observedCalls = 0
        advanceUntilIdle()

        assertEquals("exactly one idle retry", 2, calls)
        assertEquals(DshBindingRefreshState.Applied(3L, "model-catalog"), coordinator.state.value)

        // One retry per idle transition: no further reload is invented afterwards.
        advanceTimeBy(120_000L)
        assertEquals(2, calls)
    }

    @Test
    fun aTurnThatIsStillRunningIsNeverCutByTheIdleWatchdog() = runTest {
        var calls = 0
        var probes = 0
        val coordinator = coordinator(
            refresh = {
                calls += 1
                result(DshApplyStatus.DEFERRED_IN_FLIGHT)
            },
            observeInFlight = {
                probes += 1
                DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 3))
            }
        )

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals("a running turn is never retried as idle", 1, calls)
        assertTrue("the watchdog must have polled", probes > 0)
        val state = coordinator.state.value
        assertTrue(state is DshBindingRefreshState.Deferred)
        assertEquals(
            DshBindingRefreshDeferReason.IN_FLIGHT,
            (state as DshBindingRefreshState.Deferred).reason
        )
    }

    @Test
    fun anUnobservableDeferralIsNeverReconciledByTheIdleWatchdog() = runTest {
        var calls = 0
        var probes = 0
        val coordinator = coordinator(
            refresh = {
                calls += 1
                result(DshApplyStatus.DEFERRED_IN_FLIGHT, unobservable = true)
            },
            // Even a real zero must not rescue an unobservable answer through this path: the
            // unobservable retry policy owns that case (bounded by maxAttempts).
            observeInFlight = {
                probes += 1
                DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 0))
            },
            maxAttempts = 2
        )

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals("only the bounded unobservable retries may run", 2, calls)
        assertEquals("an unobservable answer never arms the idle watchdog", 0, probes)
        assertEquals(
            DshBindingRefreshDeferReason.UNOBSERVABLE,
            (coordinator.state.value as DshBindingRefreshState.Deferred).reason
        )
    }

    @Test
    fun aStoppedHostEndsTheIdleWatchdogWithoutRetrying() = runTest {
        var calls = 0
        var isReady = true
        val coordinator = coordinator(
            readyProvider = { isReady },
            refresh = {
                calls += 1
                result(DshApplyStatus.DEFERRED_IN_FLIGHT)
            },
            observeInFlight = {
                DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 0))
            }
        )

        coordinator.onModelCatalogChanged("model-loaded")
        // The deferral happened while READY; the host then stops before the turn goes idle.
        advanceTimeBy(600L)
        assertEquals(1, calls)
        isReady = false
        advanceUntilIdle()

        assertEquals("a stopped host is not reconciled by this watchdog", 1, calls)
        assertTrue(coordinator.state.value is DshBindingRefreshState.Deferred)
    }

    @Test
    fun unobservableInFlightWorkIsRetriedBoundedThenReportedAsUnobservable() = runTest {
        var calls = 0
        val coordinator = coordinator(
            refresh = {
                calls += 1
                result(DshApplyStatus.DEFERRED_IN_FLIGHT, unobservable = true)
            },
            maxAttempts = 2
        )

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals(2, calls)
        val state = coordinator.state.value
        assertTrue(state is DshBindingRefreshState.Deferred)
        assertEquals(
            DshBindingRefreshDeferReason.UNOBSERVABLE,
            (state as DshBindingRefreshState.Deferred).reason
        )
    }

    @Test
    fun deferredGatewayUnavailableAndRolledBackAndFailedAreAllTypedAndNeverApplied() = runTest {
        val gatewayCoordinator = coordinator(refresh = {
            result(DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE, unobservable = true)
        })
        gatewayCoordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()
        assertEquals(
            DshBindingRefreshDeferReason.GATEWAY_UNAVAILABLE,
            (gatewayCoordinator.state.value as DshBindingRefreshState.Deferred).reason
        )

        val rolledBack = coordinator(refresh = { result(DshApplyStatus.ROLLED_BACK) })
        rolledBack.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()
        assertTrue(rolledBack.state.value is DshBindingRefreshState.RolledBack)
        assertFalse(rolledBack.state.value.confirmed)

        val failed = coordinator(refresh = { result(DshApplyStatus.FAILED) })
        failed.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()
        assertTrue(failed.state.value is DshBindingRefreshState.Failed)
        assertFalse(failed.state.value.confirmed)
    }

    @Test
    fun unchangedIsReportedWithoutClaimingAReload() = runTest {
        val coordinator = coordinator(refresh = { result(DshApplyStatus.UNCHANGED) })

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        assertEquals(DshBindingRefreshState.Unchanged, coordinator.state.value)
        assertFalse(coordinator.state.value.confirmed)
    }

    @Test
    fun aThrowingApplyIsReportedAsFailedAndDoesNotEscape() = runTest {
        val coordinator = coordinator(refresh = {
            error("binder unavailable")
        })

        coordinator.onModelCatalogChanged("model-loaded")
        advanceUntilIdle()

        val state = coordinator.state.value
        assertTrue(state is DshBindingRefreshState.Failed)
        assertFalse(state.confirmed)
    }

    @Test
    fun cancelDropsTheScheduledRefresh() = runTest {
        var calls = 0
        val coordinator = coordinator(refresh = {
            calls += 1
            result(DshApplyStatus.APPLIED)
        })

        coordinator.onModelCatalogChanged("model-loaded")
        coordinator.cancel()
        advanceUntilIdle()

        assertEquals(0, calls)
    }

    @Test
    fun theModelCatalogSignatureOnlyTracksTheGatewayInputs() {
        val local = DshModelCatalogSignature.of(
            loadedModelId = "model-a",
            localModelName = "Model A",
            localContextWindow = 8_192,
            cloudChatModels = emptyList()
        )
        assertEquals(local, DshModelCatalogSignature.of("model-a", "Model A", 8_192, emptyList()))
        assertTrue(local != DshModelCatalogSignature.of("model-b", "Model A", 8_192, emptyList()))
        assertTrue(local != DshModelCatalogSignature.of("model-a", "Model B", 8_192, emptyList()))
        // D07/D08: reloading the same id with a different `n_ctx` changes the `context_window` the
        // gateway advertises (and whether official compaction can work), so it is a catalog change
        // even though the model id and name are identical.
        assertTrue(local != DshModelCatalogSignature.of("model-a", "Model A", 4_096, emptyList()))
        assertTrue(local != DshModelCatalogSignature.of("model-a", "Model A", null, emptyList()))
        // "no window" and "zero window" are one fact, so the signature does not churn between them.
        assertEquals(
            DshModelCatalogSignature.of("model-a", "Model A", null, emptyList()),
            DshModelCatalogSignature.of("model-a", "Model A", 0, emptyList())
        )

        val chat = DshModelCatalogSignature.CloudChat(
            id = "cloud-1",
            displayName = "Cloud",
            modelName = "gpt-x",
            apiFormat = "OPENAI_COMPATIBLE",
            supportsVision = false,
            supportsTools = true,
            configured = true,
            updatedAt = 10L
        )
        val first = DshModelCatalogSignature.of("model-a", "Model A", 8_192, listOf(chat))
        assertEquals(first, DshModelCatalogSignature.of("model-a", "Model A", 8_192, listOf(chat)))

        // An edit of a configured record is a real catalog change.
        assertTrue(
            first != DshModelCatalogSignature.of(
                "model-a", "Model A", 8_192, listOf(chat.copy(updatedAt = 11L))
            )
        )
        // A capability-only edit of a configured record is a catalog change too: the advertised
        // tool mode follows `apiFormat`/`supportsTools`/`supportsVision`.
        assertTrue(
            first != DshModelCatalogSignature.of(
                "model-a", "Model A", 8_192, listOf(chat.copy(supportsTools = false))
            )
        )
        assertTrue(
            first != DshModelCatalogSignature.of(
                "model-a", "Model A", 8_192, listOf(chat.copy(supportsVision = true))
            )
        )
        assertTrue(
            first != DshModelCatalogSignature.of(
                "model-a", "Model A", 8_192, listOf(chat.copy(apiFormat = "OPENAI_RESPONSES"))
            )
        )
        // Adding a second configured model changes the signature.
        assertTrue(
            first != DshModelCatalogSignature.of(
                "model-a",
                "Model A",
                8_192,
                listOf(chat, chat.copy(id = "cloud-2"))
            )
        )
        // Declaration order is not a catalog change (the gateway sorts its list).
        assertEquals(
            DshModelCatalogSignature.of(
                "model-a",
                "Model A",
                8_192,
                listOf(chat, chat.copy(id = "cloud-2"))
            ),
            DshModelCatalogSignature.of(
                "model-a",
                "Model A",
                8_192,
                listOf(chat.copy(id = "cloud-2"), chat)
            )
        )
        // An unconfigured record is not part of the DSH catalog at all.
        assertEquals(
            first,
            DshModelCatalogSignature.of(
                "model-a",
                "Model A",
                8_192,
                listOf(chat, chat.copy(id = "cloud-2", configured = false))
            )
        )
    }
}
