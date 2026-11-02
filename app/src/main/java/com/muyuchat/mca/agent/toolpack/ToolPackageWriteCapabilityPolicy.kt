package com.muyuchat.mca.agent.toolpack

/**
 * Explicit composition decision for executable packages that declare a WRITE host API.
 *
 * The default is [REFUSE]: a package that declares `artifact.write_text` cannot be enabled or
 * projected, which is the production-safe behavior while no nested-write approval owner is wired.
 *
 * [ALLOW_WITH_PER_CALL_APPROVAL] may only be selected by a composition that also injects a real
 * [ToolPackageHostApprovalPort]. The bridge still asks that port for **every** write call; this
 * policy never bypasses the per-call decision, it only lets such a package reach the bridge.
 * Selecting this policy without an approval owner leaves writes denied with zero effect because
 * the fail-closed port remains the default.
 */
enum class ToolPackageWriteCapabilityPolicy {
    REFUSE,
    ALLOW_WITH_PER_CALL_APPROVAL;

    val allowsWriteCapability: Boolean get() = this == ALLOW_WITH_PER_CALL_APPROVAL
}
