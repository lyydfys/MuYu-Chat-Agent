package com.muyuchat.mca.agent.security

/**
 * Where the permission hook reads the current [ToolApprovalPolicy].
 *
 * The hook takes one of these instead of a concrete store so tests can drive the policy directly
 * and production can keep one persisted document. Implementations must be cheap and must not
 * block: [current] is called once per tool call, before freezing.
 */
fun interface AgentToolApprovalPolicySource {
    fun current(): ToolApprovalPolicy

    companion object {
        /** Everything asks. This is the behaviour of every build before the policy existed. */
        val DEFAULT: AgentToolApprovalPolicySource = AgentToolApprovalPolicySource {
            ToolApprovalPolicy.DEFAULT
        }
    }
}
