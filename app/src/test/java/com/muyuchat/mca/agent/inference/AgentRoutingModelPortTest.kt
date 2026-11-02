package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelPort
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentStopDispatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P05 routing contract: the frozen binding alone decides local vs cloud, and an exact stop reaches
 * only the adapter that owns the attempt.
 */
class AgentRoutingModelPortTest {

    @Test
    fun localBindingRoutesToTheLocalAdapterOnly() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)

        val request = agentRequest(binding = localBinding())
        val result = router.infer(request) { }

        assertTrue(result is AgentModelResult.Success)
        assertEquals(listOf(request), local.inferRequests)
        assertTrue(cloud.inferRequests.isEmpty())
    }

    @Test
    fun cloudRuntimePrefixRoutesToTheCloudAdapter() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)

        val request = agentRequest(
            binding = cloudBinding(
                providerId = "cloud:record-1",
                runtime = "CLOUD_OPENAI_RESPONSES"
            )
        )
        val result = router.infer(request) { }

        assertTrue(result is AgentModelResult.Success)
        assertEquals(listOf(request), cloud.inferRequests)
        assertTrue(local.inferRequests.isEmpty())
    }

    @Test
    fun cloudProviderIdAloneRoutesToCloudEvenWithLegacyRuntimeLabel() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)

        val request = agentRequest(
            binding = cloudBinding(
                providerId = "cloud:record-2",
                runtime = "cloud"
            )
        )
        router.infer(request) { }

        assertEquals(1, cloud.inferRequests.size)
        assertTrue(local.inferRequests.isEmpty())
    }

    @Test
    fun cloudResultAndFailureAreForwardedUnchanged() = runBlocking<Unit> {
        val failure = AgentModelResult.Failure(
            AgentInferenceErrors.of(
                code = "MODEL_NOT_LOADED",
                category = AgentErrorCategory.MODEL,
                safeMessage = "no record",
                attempt = attempt()
            )
        )
        val cloud = RecordingModelPort(result = { failure })
        val router = AgentRoutingModelPort(local = RecordingModelPort(), cloud = cloud)

        val result = router.infer(
            agentRequest(binding = cloudBinding(providerId = "cloud:missing")),
            AgentModelProgressSink { }
        )

        assertTrue(result === failure)
    }

    @Test
    fun stopReachesTheCloudAdapterThatOwnsTheAttempt() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val hanging = RecordingModelPort(onInfer = {
            started.complete(Unit)
            gate.await()
        })
        val router = AgentRoutingModelPort(local = local, cloud = hanging)
        val request = agentRequest(binding = cloudBinding(providerId = "cloud:record-3"))
        val inference = async { router.infer(request) { } }
        withTimeout(3_000) { started.await() }

        val dispatch = router.requestStop(request.attempt)

        assertTrue(dispatch.accepted)
        assertEquals(listOf(request.attempt), hanging.stopAttempts)
        assertTrue(local.stopAttempts.isEmpty())
        assertTrue(cloud.stopAttempts.isEmpty())

        gate.complete(Unit)
        assertTrue(inference.await() is AgentModelResult.Success)
    }

    @Test
    fun stopReachesTheLocalAdapterThatOwnsTheAttempt() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val local = RecordingModelPort(onInfer = {
            started.complete(Unit)
            gate.await()
        })
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)
        val request = agentRequest(binding = localBinding())
        val inference = async { router.infer(request) { } }
        withTimeout(3_000) { started.await() }

        val dispatch = router.requestStop(request.attempt)

        assertTrue(dispatch.accepted)
        assertEquals(listOf(request.attempt), local.stopAttempts)
        assertTrue(cloud.stopAttempts.isEmpty())

        gate.complete(Unit)
        inference.await()
    }

    @Test
    fun stopForAnAttemptThatWasNeverDispatchedIsRejected() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)

        val dispatch = router.requestStop(attempt(ordinal = 7))

        assertFalse(dispatch.accepted)
        assertTrue(local.stopAttempts.isEmpty())
        assertTrue(cloud.stopAttempts.isEmpty())
    }

    @Test
    fun stopAfterInferenceFinishedIsRejectedAndDoesNotReachAnyAdapter() = runBlocking<Unit> {
        val local = RecordingModelPort()
        val cloud = RecordingModelPort()
        val router = AgentRoutingModelPort(local = local, cloud = cloud)
        val request = agentRequest(binding = cloudBinding(providerId = "cloud:record-4"))

        router.infer(request) { }
        val dispatch = router.requestStop(request.attempt)

        assertFalse(dispatch.accepted)
        assertTrue(local.stopAttempts.isEmpty())
        assertTrue(cloud.stopAttempts.isEmpty())
    }

    @Test
    fun staleAttemptWithTheSameAttemptIdNeverReachesTheLiveAdapter() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val cloud = RecordingModelPort(onInfer = {
            started.complete(Unit)
            gate.await()
        })
        val router = AgentRoutingModelPort(local = RecordingModelPort(), cloud = cloud)
        val request = agentRequest(binding = cloudBinding(providerId = "cloud:record-5"))
        val inference = async { router.infer(request) { } }
        withTimeout(3_000) { started.await() }

        val stale = AgentModelAttemptRef(
            attemptId = request.attempt.attemptId,
            runId = "other-run",
            stepIndex = request.attempt.stepIndex,
            ordinal = request.attempt.ordinal
        )
        val dispatch = router.requestStop(stale)

        assertFalse(dispatch.accepted)
        assertTrue(cloud.stopAttempts.isEmpty())

        gate.complete(Unit)
        inference.await()
    }

    private fun localBinding(): AgentModelBinding = modelBinding(identityHash = "local-hash")

    private fun cloudBinding(
        providerId: String,
        runtime: String = "CLOUD_OPENAI_RESPONSES"
    ): AgentModelBinding = AgentModelBinding(
        providerId = providerId,
        modelId = "cloud-model",
        runtime = runtime,
        assetFingerprint = providerId + ":1",
        protocolMode = AgentProtocolMode.NATIVE,
        modelDisplayName = "Cloud model"
    )

    private class RecordingModelPort(
        private val result: suspend (AgentModelRequest) -> AgentModelResult = {
            AgentModelResult.Success("ok")
        },
        private val onInfer: suspend (AgentModelRequest) -> Unit = { }
    ) : AgentModelPort {
        val inferRequests = CopyOnWriteArrayList<AgentModelRequest>()
        val stopAttempts = CopyOnWriteArrayList<AgentModelAttemptRef>()

        override suspend fun infer(
            request: AgentModelRequest,
            progress: AgentModelProgressSink
        ): AgentModelResult {
            inferRequests += request
            onInfer(request)
            return result(request)
        }

        override suspend fun requestStop(attempt: AgentModelAttemptRef): AgentStopDispatch {
            stopAttempts += attempt
            return AgentStopDispatch(true, "fake stop")
        }
    }
}
