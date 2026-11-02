package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelRequest
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.engine.ActiveLoadedSignature
import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.CommittedExecutionSignature
import com.muyuchat.core.engine.DesiredProfileSignature
import com.muyuchat.core.engine.EffectiveExecutionSignature
import com.muyuchat.core.engine.EngineLifecycleLeaseHandle
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.ResolvedLoadSignature
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.agent.contract.AgentModelBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicInteger

internal fun attempt(
    ordinal: Int = 1,
    runId: String = "run-1",
    stepIndex: Int = 0
): AgentModelAttemptRef = AgentModelAttemptRef(
    attemptId = "attempt-$runId-$stepIndex-$ordinal",
    runId = runId,
    stepIndex = stepIndex,
    ordinal = ordinal
)

internal fun modelBinding(
    identityHash: String,
    modelId: String = "model-a",
    runtime: String = LocalChatRuntime.LLAMA_CPP.backendId,
    protocol: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1
): AgentModelBinding = AgentModelBinding(
    providerId = "mca-local",
    modelId = modelId,
    runtime = runtime,
    assetFingerprint = identityHash,
    protocolMode = protocol,
    modelDisplayName = "Test model"
)

internal fun agentRequest(
    attempt: AgentModelAttemptRef = attempt(),
    binding: AgentModelBinding,
    systemPrompt: String = "AGENT SYSTEM",
    messages: List<AgentModelMessage> = listOf(
        AgentModelMessage("m-user", AgentTranscriptRole.USER, "hello"),
        AgentModelMessage("m-assistant", AgentTranscriptRole.ASSISTANT, "hi"),
        AgentModelMessage("m-user-2", AgentTranscriptRole.USER, "next")
    ),
    maxOutputTokens: Int = 128,
    samplingJson: String = """{"temperature":0.2}""",
    tools: List<AgentToolDescriptor> = emptyList()
): AgentModelRequest = AgentModelRequest(
    attempt = attempt,
    binding = binding,
    systemPrompt = systemPrompt,
    messages = messages,
    maxOutputTokens = maxOutputTokens,
    samplingJson = samplingJson,
    tools = tools
)

internal class FakeLeaseHandle : EngineLifecycleLeaseHandle {
    override val leaseId: String = "lease-test"
    private val releaseCount = AtomicInteger(0)

    val releases: Int get() = releaseCount.get()

    override suspend fun release() {
        releaseCount.incrementAndGet()
    }
}

internal class FakeEngineSurface(
    var profile: ModelExecutionProfile?,
    var signature: ParameterSignatureSnapshot?,
    private val lease: FakeLeaseHandle = FakeLeaseHandle()
) : AgentLocalEngineSurface {
    val acquires = AtomicInteger(0)

    override fun activeExecutionProfile(): ModelExecutionProfile? = profile
    override fun parameterSignatureSnapshot(): ParameterSignatureSnapshot? = signature
    override suspend fun acquireLifecycleLease(stopActiveGeneration: Boolean): EngineLifecycleLeaseHandle {
        acquires.incrementAndGet()
        return lease
    }
}

internal fun testIdentity(
    modelId: String = "model-a",
    runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP,
    artifactFingerprint: String = "artifact-a"
): ModelRuntimeIdentity = ModelRuntimeIdentity(
    modelId = modelId,
    artifactFingerprint = artifactFingerprint,
    runtime = runtime
)

internal fun testProfile(identity: ModelRuntimeIdentity = testIdentity()): ModelExecutionProfile =
    ModelExecutionProfile(
        modelId = identity.modelId,
        runtimeIdentity = identity,
        desiredLoadBoundValues = CanonicalParameterSet.EMPTY,
        resolvedLoadBoundValues = CanonicalParameterSet.EMPTY,
        hotExecutionValues = CanonicalParameterSet.EMPTY
    )

internal fun testSignatures(identity: ModelRuntimeIdentity = testIdentity()): ParameterSignatureSnapshot {
    val active = ActiveLoadedSignature.of(identity, CanonicalParameterSet.EMPTY)
    val committed = CommittedExecutionSignature.of(identity, CanonicalParameterSet.EMPTY)
    val override = RuntimeOverrideSignature.none(identity)
    return ParameterSignatureSnapshot(
        desired = DesiredProfileSignature.of(identity, CanonicalParameterSet.EMPTY),
        resolved = ResolvedLoadSignature.of(identity, CanonicalParameterSet.EMPTY),
        active = active,
        committed = committed,
        override = override,
        effective = EffectiveExecutionSignature.of(identity, active, committed, override)
    )
}

/** Fake production execution body; records the exact request/context and stop calls. */
internal class FakeExecutionBody(
    val script: suspend FlowCollector<GenerateEvent>.(ChatRequest, LocalChatExecutionContext) -> Unit
) : ChatExecutionBody {
    var lastRequest: ChatRequest? = null
    var lastContext: LocalChatExecutionContext? = null
    val contexts = mutableListOf<LocalChatExecutionContext>()
    val requests = mutableListOf<ChatRequest>()
    var streamCalls = 0
    var globalStops = 0
    val stoppedRequestIds = mutableListOf<String>()

    override fun stream(request: ChatRequest, context: LocalChatExecutionContext): Flow<GenerateEvent> = flow {
        streamCalls += 1
        lastRequest = request
        lastContext = context
        requests += request
        contexts += context
        script(request, context)
    }

    override suspend fun stopGeneration() {
        globalStops += 1
    }

    override suspend fun stopGenerationIfActive(requestId: String): Boolean {
        stoppedRequestIds += requestId
        return true
    }
}

internal class FixedStopProofSource(
    @Volatile var proof: AgentStopProof = AgentStopProof.Confirmed(
        evidence = "native idle (test)",
        waitedMillis = 1
    )
) : AgentStopProofSource {
    var calls = 0

    override suspend fun awaitStopped(
        attempt: AgentModelAttemptRef,
        requestId: String,
        timeoutMillis: Long
    ): AgentStopProof {
        calls += 1
        return proof
    }
}

/** Records every lease settlement the adapter observes, for late-proof reporting tests. */
internal class RecordingLeaseSettlementListener : AgentLeaseSettlementListener {
    data class Event(
        val attempt: AgentModelAttemptRef,
        val release: AgentLeaseRelease,
        val stoppedExecution: Boolean
    )

    private val events = java.util.concurrent.CopyOnWriteArrayList<Event>()

    val recorded: List<Event> get() = events.toList()

    override fun onLeaseSettled(
        attempt: AgentModelAttemptRef,
        release: AgentLeaseRelease,
        stoppedExecution: Boolean
    ) {
        events += Event(attempt, release, stoppedExecution)
    }

    fun releasedEvents(): List<Event> =
        recorded.filter { it.release is AgentLeaseRelease.Released }

    fun lastReleased(): Event? = releasedEvents().lastOrNull()
}

internal suspend fun runAgentInference(
    adapter: McaAgentLocalModelAdapter,
    request: AgentModelRequest
): AgentModelResult = adapter.infer(request) { }

internal fun doneEvent(tokens: Int = 4): GenerateEvent.Done =
    GenerateEvent.Done(RuntimeStats(loaded = true, completionTokens = tokens, promptTokens = 8))

internal fun errorEvent(code: String?, message: String = "boom"): GenerateEvent.Error =
    GenerateEvent.Error(message, RuntimeStats(loaded = true), code = code)

internal fun cancelledErrorEvent(): GenerateEvent.Error =
    errorEvent("REQUEST_CANCELLED", "cancelled")

internal fun AgentModelResult.failureCode(): String? =
    (this as? AgentModelResult.Failure)?.error?.code

internal fun AgentModelResult.agentError(): AgentError? =
    (this as? AgentModelResult.Failure)?.error
