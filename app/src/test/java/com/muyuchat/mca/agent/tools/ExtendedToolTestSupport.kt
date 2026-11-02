package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import org.junit.Assert.fail

/**
 * Shared fixtures for the extended built-in tool tests.
 *
 * The freeze helpers use the real factory plus [AgentExtendedToolArgumentNormalizer], so the tests
 * exercise the same approval-time normalization the production path uses instead of a hand-built
 * argument string.
 */
internal object ExtendedToolTestSupport {
    fun context(workspaceReadGranted: Boolean = true): AgentToolContext = AgentToolContext(
        taskId = "task-1",
        workspaceId = "workspace-1",
        runId = "run-1",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 2L,
        workspaceReadGranted = workspaceReadGranted
    )

    /** Directly frozen command used to test executor-side defence (bypassing the normalizer). */
    fun command(
        descriptor: AgentToolDescriptor,
        argumentsJson: String,
        invocationId: String = "inv-1"
    ): FrozenToolCommand = FrozenToolCommand(
        invocationId = invocationId,
        runId = "run-1",
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = descriptor.toolKey,
        toolVersion = descriptor.version,
        schemaHash = descriptor.schemaHash,
        normalizedArgumentsJson = argumentsJson,
        argumentsHash = "arguments-hash",
        resourceFingerprint = "resource-fingerprint",
        runEpoch = 1L,
        policyVersion = 2L,
        expiresAtMillis = Long.MAX_VALUE
    )

    fun factory(): AgentFrozenCommandFactory =
        AgentFrozenCommandFactory(AgentExtendedToolArgumentNormalizer())

    fun freeze(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String,
        typedScope: AgentScopePrecondition? = null
    ): AgentFreezeResult = factory().freeze(
        AgentFreezeRequest(
            descriptor = descriptor,
            rawArgumentsJson = rawArgumentsJson,
            invocationId = "inv-1",
            runId = "run-1",
            stepId = "step-1",
            callOrdinal = 0,
            workspaceId = "workspace-1",
            taskId = "task-1",
            runEpoch = 1L,
            policyVersion = 1L,
            nowMillis = 1_000L,
            approvalTtlMillis = 60_000L,
            typedScope = typedScope
        )
    )

    fun frozen(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String,
        typedScope: AgentScopePrecondition? = null
    ): AgentFreezeResult.Frozen = when (val result = freeze(descriptor, rawArgumentsJson, typedScope)) {
        is AgentFreezeResult.Frozen -> result
        is AgentFreezeResult.Rejected -> {
            fail("expected a frozen command, got rejection: ${result.error.safeMessage}")
            throw IllegalStateException("unreachable")
        }
    }

    fun assertRejected(result: AgentFreezeResult, code: String) {
        when (result) {
            is AgentFreezeResult.Frozen -> fail("expected a rejection with code $code")
            is AgentFreezeResult.Rejected -> org.junit.Assert.assertEquals(code, result.error.code)
        }
    }
}

/** Fake HTTP transport: records the request and answers with a scripted outcome. */
internal class FakeHttpTextClient(
    var outcome: AgentHttpGetOutcome = AgentHttpGetOutcome.Response(
        statusCode = 200,
        contentType = "text/plain",
        bodyBytes = 5,
        text = "hello",
        truncated = false
    )
) : AgentHttpTextClient {
    val requests = mutableListOf<AgentHttpGetRequest>()

    override suspend fun get(request: AgentHttpGetRequest): AgentHttpGetOutcome {
        requests += request
        return outcome
    }
}

/** Deterministic gate; records every authorization request it saw. */
internal class RecordingHttpGetGate(
    private val decision: AgentHttpGetGateDecision = AgentHttpGetGateDecision.Allowed
) : AgentHttpGetGate {
    val requests = mutableListOf<AgentHttpGetAuthorization>()

    override suspend fun authorize(request: AgentHttpGetAuthorization): AgentHttpGetGateDecision {
        requests += request
        return decision
    }
}

/** Fake sandbox; never starts a process. */
internal class FakeTerminalSandbox(
    var outcome: AgentTerminalOutcome = AgentTerminalOutcome.Completed(0, "ok\n", "", false),
    private val available: Boolean = true,
    private val delayMillis: Long = 0L
) : AgentTerminalSandbox {
    override val isAvailable: Boolean get() = available

    val requests = mutableListOf<AgentTerminalRequest>()

    override suspend fun execute(request: AgentTerminalRequest): AgentTerminalOutcome {
        requests += request
        if (delayMillis > 0L) kotlinx.coroutines.delay(delayMillis)
        return outcome
    }
}
