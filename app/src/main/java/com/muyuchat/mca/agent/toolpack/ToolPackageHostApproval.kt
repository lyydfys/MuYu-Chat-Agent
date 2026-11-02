package com.muyuchat.mca.agent.toolpack

/** Why a per-call approval was refused. Every reason keeps the write undispersed. */
enum class ToolPackageHostApprovalRefusal {
    /** No approval owner is wired at all: the production-safe default. */
    NO_APPROVAL_SOURCE,

    /** The approval owner refused this exact call. */
    DENIED,

    /** The run epoch, policy version or binding was revoked after the decision. */
    REVOKED,

    /** The decision belongs to a different call binding than the one being dispatched. */
    BINDING_MISMATCH,

    /** The same approval was already consumed by another call. */
    ALREADY_CONSUMED
}

/**
 * The single decision an approval owner returns for one nested write call.
 *
 * [Approved] carries the binding it authorises and the revocation epoch it was issued under, so the
 * bridge can reject a decision that is re-used for another call or replayed across a revoked epoch.
 * There is deliberately no `approveAll`, TTL or "remember for this run" variant: the script-level
 * run permission never widens into a nested host capability.
 */
sealed interface ToolPackageHostApprovalDecision {
    data class Approved(
        val approvalId: String,
        val bindingId: String,
        val revocation: ToolPackageHostRevocation
    ) : ToolPackageHostApprovalDecision {
        init {
            require(approvalId.isNotBlank()) { "approved decision needs an approval id" }
            require(approvalId.none { it.isISOControl() }) { "approval id must not contain controls" }
            require(bindingId.isNotBlank()) { "approved decision needs a binding id" }
        }
    }

    data class Refused(
        val reason: ToolPackageHostApprovalRefusal,
        val safeMessage: String
    ) : ToolPackageHostApprovalDecision {
        init {
            require(safeMessage.isNotBlank()) { "approval refusal needs a safe message" }
            require(safeMessage.none { it.isISOControl() }) {
                "approval refusal must not contain control characters"
            }
            require(safeMessage.toByteArray(Charsets.UTF_8).size <= ToolPackageError.MAX_SAFE_MESSAGE_BYTES) {
                "approval refusal exceeds the safe message budget"
            }
        }
    }
}

/**
 * Per-call, capability-bound approval seam for nested host writes (N11 / PKG-07).
 *
 * The bridge calls this for **every** WRITE host call with the exact [ToolPackageHostCallBinding].
 * A returned approval is consumed for exactly that binding; the next call needs its own decision.
 * The default denies everything, so a composition that forgets to inject an approval owner keeps
 * refusing writes instead of silently acquiring them.
 *
 * Implementations must not widen the request: they may only answer for the binding they were given.
 * They must be safe to call from the execution coroutine and must not block the main thread.
 */
interface ToolPackageHostApprovalPort {
    suspend fun approve(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision

    companion object {
        /** Fail-closed default: no approval source, therefore no nested write is ever dispatched. */
        val DENY_ALL: ToolPackageHostApprovalPort = object : ToolPackageHostApprovalPort {
            override suspend fun approve(binding: ToolPackageHostCallBinding): ToolPackageHostApprovalDecision =
                ToolPackageHostApprovalDecision.Refused(
                    ToolPackageHostApprovalRefusal.NO_APPROVAL_SOURCE,
                    "No per-call approval source is wired for executable packages."
                )
        }
    }
}
