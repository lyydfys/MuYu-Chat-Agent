package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatCancellationStateTest {
    @Test
    fun blockedCancelRemainsRecoverableEvenAfterTheGenerationReturnsDone() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        val stop = requireNotNull(state.request()).stop
        state.finish(state.begin("decode"), terminal = true)
        assertTrue(state.needsRecovery(stop)) // native cancel itself still owns the old handle
        assertTrue(runCatching { state.begin("prefill") }.isFailure)
        state.returned(stop)
        assertFalse(state.needsRecovery(stop))
        state.begin("prefill")
    }

    @Test
    fun stopSurvivesChunkBoundariesAndDuplicateStopsDoNotStartMoreNativeCalls() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        val request = requireNotNull(state.request())
        assertTrue(request.dispatch)
        assertEquals(request.stop, state.request()?.stop)
        assertFalse(requireNotNull(state.request()).dispatch)
        state.returned(request.stop)
        state.finish(state.begin("decode"), terminal = false)
        assertTrue(state.needsRecovery(request.stop))
        state.finish(state.begin("decode"), terminal = true)
        assertFalse(state.needsRecovery(request.stop))
    }

    @Test
    fun staleTimeoutCannotKillAReplacementGeneration() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        val old = requireNotNull(state.request()).stop
        state.returned(old)
        state.generationCompleted(old)
        state.finish(state.begin("prefill"), terminal = false)
        val current = requireNotNull(state.request()).stop
        state.returned(old)
        state.generationCompleted(old)
        assertFalse(state.needsRecovery(old))
        assertTrue(state.needsRecovery(current))
        var recovered = false
        assertFalse(state.recoverIfNeeded(old) { recovered = true })
        assertFalse(recovered)
        assertTrue(state.recoverIfNeeded(current) { recovered = true })
        assertTrue(recovered)
    }

    @Test
    fun noDecodePollIsRequiredAfterCooperativeStop() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        val stop = requireNotNull(state.request()).stop
        state.returned(stop)
        state.generationCompleted(stop) // runner's Java flag says its callback thread exited
        assertFalse(state.needsRecovery(stop))
        assertNull(state.request())
    }

    @Test
    fun cancelledLoadStillRecoversWhenNoGenerationThreadExists() {
        val state = LocalChatCancellationState()
        val load = state.begin("load")
        val stop = requireNotNull(state.request()).stop
        state.returned(stop)
        state.generationCompleted(stop)
        assertTrue(state.needsRecovery(stop))
        state.finish(load, terminal = true)
        assertFalse(state.needsRecovery(stop))
    }

    @Test
    fun nativeCleanupAfterAnErrorStillAllowsBoundedRecovery() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        state.finish(state.begin("decode"), terminal = true)
        val stop = requireNotNull(state.request(runnerStillActive = true)).stop
        state.returned(stop)
        assertTrue(state.needsRecovery(stop))
        state.generationCompleted(stop)
        assertFalse(state.needsRecovery(stop))
    }

    @Test
    fun errorDeliveryDoesNotDisarmRecoveryWhileNativeCleanupStillRuns() {
        val state = LocalChatCancellationState()
        state.finish(state.begin("prefill"), terminal = false)
        val stop = requireNotNull(state.request()).stop
        state.returned(stop)
        state.finish(state.begin("decode"), terminal = true, runnerStillActive = true)
        assertTrue(state.needsRecovery(stop))
        state.generationCompleted(stop)
        assertFalse(state.needsRecovery(stop))
    }

    @Test
    fun allLiteRtBackendsKeepCancellationRecoveryIncludingUnknownFutureDelegates() {
        listOf("cpu", "gpu", "npu", "google_tensor", "future_delegate", null).forEach { backend ->
            listOf("load", "prefill", "decode", "unload").forEach { stage ->
                val target = LocalChatWorkerOperationTarget(LocalChatRuntime.LITERT_LM, backend)
                assertTrue("$backend / $stage", localChatWorkerOperationPolicy(target, stage).forceProcessRecoveryOnCancel)
            }
        }
        val cpu = LocalChatWorkerOperationTarget(LocalChatRuntime.LITERT_LM, "cpu")
        assertEquals(1_800_000L, localChatWorkerOperationPolicy(cpu, "prefill").timeoutMs)
        assertEquals(1_800_000L, localChatWorkerOperationPolicy(cpu, "decode").timeoutMs)
    }
}
