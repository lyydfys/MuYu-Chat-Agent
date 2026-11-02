package com.muyuchat.core.dsh.contract.capability

/**
 * Lifecycle of one DSH-driven phone action inside the capability ledger (completion-v2 D15).
 *
 * The names follow the state table in `ARCHITECTURE.md` 11.3 so a receipt, a diagnostic projection
 * and the D22a maintenance snapshot all describe the same fact. The invariant that matters most:
 * a state is either provably settled ([isTerminal]) or it is not, and only a settled state may
 * replay a stored result. [UNKNOWN] is specifically *not* replayable: the platform may have run
 * the action, so re-executing it on a retry could post a second notification or repeat a tap.
 */
enum class DshCapabilityOperationState {
    /** Accepted and authorized; no external action dispatched yet. */
    RECEIVED,

    /** The dispatch intent is durable and the action may have started. */
    DISPATCH_INTENT,

    /** The platform observed the result and it is stored; the receipt may be replayed. */
    OBSERVED,

    /** The action provably failed without a user-visible side effect. */
    FAILED,

    /** The action was rejected before dispatch (validation, permission or policy). */
    REJECTED,

    /** The user or the owner cancelled the action. */
    CANCELLED,

    /** No terminal outcome is known. Must never be re-executed under the same identity. */
    UNKNOWN;

    /** A receipt exists and may be replayed without running the action again. */
    val mayReplayReceipt: Boolean
        get() = this == OBSERVED || this == FAILED || this == REJECTED || this == CANCELLED

    /** No further transition is expected from this state. */
    val isTerminal: Boolean
        get() = mayReplayReceipt

    /** The request has been admitted but no receipt is stored yet. */
    val isPending: Boolean
        get() = this == RECEIVED || this == DISPATCH_INTENT
}

/**
 * Answer to "may this (requestId, payloadFingerprint) run now?".
 *
 * [Replay] is the only decision that returns a stored body without calling a handler. Every other
 * decision is fail-closed: [FingerprintMismatch] rejects a reused id with different parameters,
 * [InProgress] lets the original call own the result, [OutcomeUnknown] and [Expired] refuse to
 * re-execute, and [CapacityExceeded] refuses new work rather than silently dropping an identity
 * that may still be needed to reject a late duplicate.
 */
sealed interface DshCapabilityLedgerDecision {
    /** The identity is new; the caller now owns the outcome. */
    data object New : DshCapabilityLedgerDecision

    /** The identity already settled; replay the stored receipt and do not dispatch again. */
    data class Replay(
        val state: DshCapabilityOperationState,
        val statusCode: Int,
        val bodyJson: String
    ) : DshCapabilityLedgerDecision {
        init {
            require(state.mayReplayReceipt) { "only a settled operation may replay a receipt" }
            require(statusCode in 100..599) { "replayed status code is out of range" }
        }
    }

    /** A duplicate arrived while the original call still owns the outcome. */
    data object InProgress : DshCapabilityLedgerDecision

    /** A previous attempt was dispatched but never settled; it must not be replayed. */
    data object OutcomeUnknown : DshCapabilityLedgerDecision

    /** The same identity was presented with different parameters. */
    data object FingerprintMismatch : DshCapabilityLedgerDecision

    /** The receipt was evicted after aging out; the identity must not be re-executed. */
    data object Expired : DshCapabilityLedgerDecision

    /** The bounded ledger cannot admit another identity without losing a safety-critical record. */
    data object CapacityExceeded : DshCapabilityLedgerDecision
}

/** Result of settling an identity. */
enum class DshCapabilityLedgerCompletion {
    /** The pending identity moved to the requested terminal or unknown state. */
    COMPLETED,

    /** No record exists for this identity. */
    MISSING,

    /** The stored identity belongs to different parameters. */
    FINGERPRINT_MISMATCH,

    /** The identity was already settled; the first settlement is authoritative. */
    ALREADY_SETTLED
}

/**
 * One ledger record as the owner or a diagnostic surface sees it.
 *
 * [payloadFingerprint] binds the receipt to the actual method, path, query and body, so a reused
 * request id with different parameters is rejected instead of replaying a receipt for another
 * action. It is a SHA-256 hex digest, never the raw payload.
 */
data class DshCapabilityLedgerRecord(
    val requestId: DshCapabilityRequestId,
    val generation: Long,
    val path: String,
    val payloadFingerprint: String,
    val state: DshCapabilityOperationState,
    val recordedAtMillis: Long,
    val settledAtMillis: Long? = null,
    val statusCode: Int? = null,
    val bodyJson: String? = null
) {
    init {
        require(generation >= 0L) { "ledger record generation must be non-negative" }
        require(path.startsWith('/') && path.length <= DshCapabilityRouteLimits.MAX_PATH_CHARS) {
            "ledger record path must be an absolute local path"
        }
        require(isLowercaseSha256(payloadFingerprint)) {
            "ledger payload fingerprint must be 64 lowercase hexadecimal characters"
        }
        require(recordedAtMillis >= 0L) { "ledger record timestamp must be non-negative" }
        require(settledAtMillis == null || settledAtMillis >= recordedAtMillis) {
            "ledger settlement must not precede the record"
        }
        require(statusCode == null || statusCode in 100..599) {
            "ledger status code is out of range"
        }
        require(bodyJson == null || bodyJson.length <= DshCapabilityRouteLimits.MAX_RESPONSE_BYTES) {
            "ledger receipt body exceeds the contract limit"
        }
        if (state.mayReplayReceipt) {
            require(statusCode != null) { "a replayable receipt must carry its status code" }
            require(settledAtMillis != null) { "a replayable receipt must carry its settlement time" }
        } else {
            require(statusCode == null) { "an unsettled operation must not carry a receipt status" }
        }
    }
}

/** Bounded ledger size projection. Read-only; never contains tokens, bodies or user content. */
data class DshCapabilityLedgerStats(
    val entries: Int,
    val pending: Int,
    val unknown: Int,
    val capacity: Int,
    val tombstones: Int
) {
    init {
        require(entries >= 0 && pending in 0..entries && unknown in 0..entries)
        require(capacity >= 1)
        require(tombstones >= 0)
    }
}

private fun isLowercaseSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
