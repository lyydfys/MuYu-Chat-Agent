package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.NativeGenerationActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatStopLatencyTraceTest {
    private var nowMs = 1_000L
    private val trace = LocalChatStopLatencyTrace { nowMs }

    @Test
    fun stopIsBoundToTheExactNativeOperationToken() {
        val ticket = requireNotNull(trace.request("prefill", 7L, 3L, "dsh:3:2"))
        assertEquals("prefill", ticket.stage)
        assertEquals(7L, ticket.operationToken)

        // A replacement operation must never be credited with an older stop.
        assertNull(trace.observe(8L))
        nowMs = 1_250L
        val observation = requireNotNull(trace.observe(7L))
        assertEquals(250L, observation.elapsedMs)

        // A different generation is not this stop's idle boundary.
        assertNull(trace.idle(7L, 4L))
        nowMs = 2_000L
        val idle = requireNotNull(trace.idle(7L, 3L))
        assertEquals(750L, idle.stopObservedToIdleMs)
        assertEquals(1_000L, idle.stopRequestedToIdleMs)
        assertNull("an idle boundary is emitted exactly once", trace.idle(7L, 3L))
    }

    @Test
    fun repeatedStopForTheSameOperationDoesNotRestartTheLatencyClock() {
        nowMs = 5_000L
        assertNotNull(trace.request("decode", 11L, 1L, null))
        nowMs = 5_400L
        assertNull("a duplicate requestStop must not overwrite the pending ticket", trace.request("decode", 11L, 1L, null))
        nowMs = 5_500L
        assertEquals(500L, requireNotNull(trace.observe(11L)).elapsedMs)
    }

    @Test
    fun idleRequiresTheStopObservationAndTheSameGeneration() {
        trace.request("decode", 4L, 9L, null)
        assertNull("idle cannot precede the native observation", trace.idle(4L, 9L))
        trace.observe(4L)
        assertNull(trace.idle(4L, 10L))
        assertNotNull(trace.idle(4L, 9L))
    }

    @Test
    fun aStopBetweenNativeOperationsHasNoExactTokenToTime() {
        assertNull(trace.request(stage = null, operationToken = 12L, generation = 2L, requestKey = null))
        assertNull(trace.request(stage = "decode", operationToken = 0L, generation = 2L, requestKey = null))
    }

    @Test
    fun requestKeyUsesThePromptFreeOwnerIdentityAndRejectsUnsafeValues() {
        assertEquals(
            "dsh:3:2",
            LocalChatWorkerStopRequestKey.fromParamsJson("""{"conversationOwnerId":"request:dsh:3:2"}""")
        )
        assertEquals(
            "local-42",
            LocalChatWorkerStopRequestKey.fromParamsJson("""{"requestId":"local-42"}""")
        )
        assertNull(LocalChatWorkerStopRequestKey.fromParamsJson("""{"conversationOwnerId":"contains space"}"""))
        assertNull(LocalChatWorkerStopRequestKey.fromParamsJson("""{"conversationOwnerId":"/data/user/0/x"}"""))
        assertNull(LocalChatWorkerStopRequestKey.fromParamsJson("{}"))
    }

    @Test
    fun forcedStopRecoveryBoundsPrefillAndDecodeForEveryRuntime() {
        // llama.cpp opts in through its operation policy; GenieX QAIRT does not today, but a user
        // stop during prompt prefill must still be bounded by the isolated worker recovery.
        assertTrue(shouldArmForcedStopRecovery("prefill", 9L, gateLocked = true, forceProcessRecoveryOnCancel = false))
        assertTrue(shouldArmForcedStopRecovery("decode", 9L, gateLocked = true, forceProcessRecoveryOnCancel = false))
        assertTrue(shouldArmForcedStopRecovery("prefill", 9L, gateLocked = true, forceProcessRecoveryOnCancel = true))
        // A non-generation operation keeps its existing policy.
        assertFalse(shouldArmForcedStopRecovery("load", 9L, gateLocked = true, forceProcessRecoveryOnCancel = false))
        assertTrue(shouldArmForcedStopRecovery("load", 9L, gateLocked = true, forceProcessRecoveryOnCancel = true))
        // Nothing is armed without a locked native gate or an exact active token.
        assertFalse(shouldArmForcedStopRecovery("prefill", 9L, gateLocked = false, forceProcessRecoveryOnCancel = true))
        assertFalse(shouldArmForcedStopRecovery("prefill", 0L, gateLocked = true, forceProcessRecoveryOnCancel = true))
        assertFalse(shouldArmForcedStopRecovery(null, 9L, gateLocked = true, forceProcessRecoveryOnCancel = true))
    }

    @Test
    fun qairtPrefillPolicyAloneWouldNotBoundAStop() {
        val target = localChatWorkerOperationTarget(LocalChatRuntime.GENIEX_QAIRT, "{}")
        assertFalse(
            "policy baseline: only the worker recovery bound covers this runtime today",
            localChatWorkerOperationPolicy(target, "prefill").forceProcessRecoveryOnCancel
        )
    }

    @Test
    fun workerActivityStateProvesIdleOnlyFromRealCallAndChunkEvidence() {
        val state = LocalChatWorkerActivityState()
        assertEquals(NativeGenerationActivity.IDLE, state.activity(closed = false, sessionLost = false))

        state.callStarted()
        assertEquals(NativeGenerationActivity.ACTIVE, state.activity(closed = false, sessionLost = false))
        state.callFinished()

        state.beginResult(0)
        assertEquals("a successful begin owns a live native generation",
            NativeGenerationActivity.ACTIVE, state.activity(closed = false, sessionLost = false))
        state.chunkResult("token")
        assertEquals(NativeGenerationActivity.ACTIVE, state.activity(closed = false, sessionLost = false))
        state.chunkResult(null)
        assertEquals("the native terminal null chunk is the idle proof",
            NativeGenerationActivity.IDLE, state.activity(closed = false, sessionLost = false))

        state.beginResult(-1)
        assertEquals(NativeGenerationActivity.IDLE, state.activity(closed = false, sessionLost = false))

        assertEquals("a lost session is missing evidence, never idle",
            NativeGenerationActivity.UNKNOWN, state.activity(closed = false, sessionLost = true))
        assertEquals(NativeGenerationActivity.UNKNOWN, state.activity(closed = true, sessionLost = false))
    }

    @Test
    fun workerEmitsTheBoundedStopLatencyVocabularyADeviceRunCanTime() {
        val worker = sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerNativeCall.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerService.kt") +
            sourceFile("app/src/main/java/com/muyuchat/mca/LocalChatWorkerRecovery.kt")
        // The device lane greps these exact prefixes on the worker tag; a refactor may reword the
        // values but must keep request id, stage, both timing boundaries and the grace-expiry line.
        listOf(
            "stop requested source=",
            "request=",
            "stage=",
            "stop observed request=",
            "stopRequestedToObservedMs=",
            "worker idle request=",
            "stopObservedToIdleMs=",
            "stopRequestedToIdleMs=",
            "stop grace expired"
        ).forEach { expected ->
            assertTrue("missing stop-latency evidence fragment: $expected", worker.contains(expected))
        }
        assertTrue(worker.contains("WORKER_LOG_TAG"))
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
