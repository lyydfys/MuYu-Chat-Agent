package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import org.junit.Assert.assertTrue

/** Shared deterministic inputs for K07 tests. No Android, no clock, no random ids. */
internal object AgentSecurityFixtures {
    const val SCHEMA_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    const val OTHER_SCHEMA_HASH = "b3a8f0d0d6f0e6a3b5d0e6c6f6b8f8b0d6a3c5e7f9a1b3c5d7e9f1a3b5c7d9e1"

    val factory = AgentFrozenCommandFactory()

    /** Passes `{}` through for descriptor-level tests of tools outside the four V1 built-ins. */
    val passthroughNormalizer = object : AgentToolArgumentNormalizer {
        override fun normalize(
            descriptor: AgentToolDescriptor,
            rawArgumentsJson: String
        ): AgentArgumentNormalization = AgentArgumentNormalization.Normalized("{}", listOf("inputs"), null)
    }

    val passthroughFactory = AgentFrozenCommandFactory(passthroughNormalizer)

    fun freezePassthrough(
        descriptor: AgentToolDescriptor,
        invocationId: String = "inv-1",
        runId: String = "run-1",
        stepId: String = "step-1",
        nowMillis: Long = 1_000_000L,
        approvalTtlMillis: Long = 300_000L,
        policyVersion: Long = 7L,
        runEpoch: Long = 3L,
        networkDestinations: List<String> = emptyList()
    ): AgentFreezeResult.Frozen {
        val result = passthroughFactory.freeze(
            request(
                descriptor = descriptor,
                rawArgumentsJson = "{}",
                invocationId = invocationId,
                runId = runId,
                stepId = stepId,
                nowMillis = nowMillis,
                approvalTtlMillis = approvalTtlMillis,
                policyVersion = policyVersion,
                runEpoch = runEpoch,
                networkDestinations = networkDestinations
            )
        )
        assertTrue("expected frozen command, got $result", result is AgentFreezeResult.Frozen)
        return result as AgentFreezeResult.Frozen
    }

    fun descriptor(
        toolKey: String = AgentBuiltInToolArgumentNormalizer.READ_TEXT,
        version: String = "1.0.0",
        schemaHash: String = SCHEMA_HASH,
        sideEffect: AgentToolSideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass: AgentDataClass = AgentDataClass.PRIVATE
    ): AgentToolDescriptor = AgentToolDescriptor(
        toolKey = toolKey,
        version = version,
        displayName = "Test tool",
        description = "Deterministic K07 test tool",
        schemaJson = "{}",
        schemaHash = schemaHash,
        sideEffect = sideEffect,
        dataClass = dataClass
    )

    fun request(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String,
        invocationId: String = "inv-1",
        runId: String = "run-1",
        stepId: String = "step-1",
        callOrdinal: Int = 0,
        workspaceId: String = "ws-1",
        taskId: String = "task-1",
        runEpoch: Long = 3L,
        policyVersion: Long = 7L,
        nowMillis: Long = 1_000_000L,
        approvalTtlMillis: Long = 300_000L,
        networkDestinations: List<String> = emptyList(),
        typedScope: AgentScopePrecondition? = null
    ): AgentFreezeRequest = AgentFreezeRequest(
        descriptor = descriptor,
        rawArgumentsJson = rawArgumentsJson,
        invocationId = invocationId,
        runId = runId,
        stepId = stepId,
        callOrdinal = callOrdinal,
        workspaceId = workspaceId,
        taskId = taskId,
        runEpoch = runEpoch,
        policyVersion = policyVersion,
        nowMillis = nowMillis,
        approvalTtlMillis = approvalTtlMillis,
        networkDestinations = networkDestinations,
        typedScope = typedScope
    )

    fun freeze(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String,
        invocationId: String = "inv-1",
        runId: String = "run-1",
        stepId: String = "step-1",
        callOrdinal: Int = 0,
        nowMillis: Long = 1_000_000L,
        approvalTtlMillis: Long = 300_000L,
        policyVersion: Long = 7L,
        runEpoch: Long = 3L,
        networkDestinations: List<String> = emptyList(),
        typedScope: AgentScopePrecondition? = null,
        factory: AgentFrozenCommandFactory = this.factory
    ): AgentFreezeResult.Frozen {
        val result = factory.freeze(
            request(
                descriptor = descriptor,
                rawArgumentsJson = rawArgumentsJson,
                invocationId = invocationId,
                runId = runId,
                stepId = stepId,
                callOrdinal = callOrdinal,
                nowMillis = nowMillis,
                approvalTtlMillis = approvalTtlMillis,
                policyVersion = policyVersion,
                runEpoch = runEpoch,
                networkDestinations = networkDestinations,
                typedScope = typedScope
            )
        )
        assertTrue("expected frozen command, got $result", result is AgentFreezeResult.Frozen)
        return result as AgentFreezeResult.Frozen
    }

    fun rejection(result: AgentFreezeResult): AgentSecurityError {
        assertTrue("expected rejection, got $result", result is AgentFreezeResult.Rejected)
        return (result as AgentFreezeResult.Rejected).error
    }

    fun candidate(
        frozen: AgentFreezeResult.Frozen,
        descriptor: AgentToolDescriptor
    ): AgentPermissionCandidate = AgentPermissionCandidate(frozen.command, descriptor, frozen.scope)

    fun policy(
        runEpoch: Long = 3L,
        policyVersion: Long = 7L,
        networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.LOCAL_ONLY
    ): AgentPermissionPolicy = AgentPermissionPolicy(runEpoch, policyVersion, networkPolicy)

    fun grant(
        scopePrefixes: List<String> = listOf("inputs"),
        revoked: Boolean = false
    ): AgentWorkspaceReadGrant = AgentWorkspaceReadGrant(
        workspaceId = "ws-1",
        taskId = "task-1",
        taskRoot = "ws-1/tasks/task-1",
        scopePrefixes = scopePrefixes,
        revoked = revoked
    )

    fun source(
        sourceId: String = "source-1",
        sha256: String = SCHEMA_HASH,
        dataClass: AgentDataClass = AgentDataClass.PRIVATE
    ): AgentEgressSource = AgentEgressSource(sourceId, sha256, dataClass)
}
