package com.muyuchat.core.dsh.contract.capability

/**
 * Maintenance admission contract for completion-v2 D07m/D22a.
 *
 * Every writer that can mutate managed DSH data (agent turns, tools, jobs, PTY, plugin install,
 * account/settings/profile commits, session writes, user patches) takes a [DshMaintenanceLease]
 * before it starts. A maintenance transaction then takes the exclusive
 * [DshMaintenanceLeaseKind.MAINTENANCE] barrier and is admitted only when no mutator has a live
 * lease. This is the seam the data owner calls; the gate implementation lives in
 * `:core:dsh-platform` and writes no file by itself.
 *
 * Honest boundary: a lease only serialises participants that actually take it. A plugin or Node
 * subtree that ignores the barrier is not magically stopped; the runtime owner must still stop or
 * prove quiescence for those writers (D07m). A successful barrier is not proof that every
 * external writer is gone.
 */
enum class DshMaintenanceLeaseKind {
    /** Read-only work that is safe beside other readers but must not overlap the barrier. */
    READ,

    /** A mutator: agent turn, tool, install, credential/settings/session write. */
    MUTATION,

    /** The single exclusive maintenance write barrier. */
    MAINTENANCE;

    /** True only for the barrier kind that excludes every other admission. */
    val isExclusiveBarrier: Boolean
        get() = this == MAINTENANCE
}

/**
 * One admitted lease.
 *
 * [deadlineAtMillis] is bounded so a crashed holder cannot pin the gate forever; an expired lease
 * is reclaimed by the gate before the next decision and must then be re-acquired. [generation]
 * records the runtime generation that took the lease so a stale release cannot free a newer lease
 * with the same id.
 */
data class DshMaintenanceLease(
    val leaseId: String,
    val kind: DshMaintenanceLeaseKind,
    val generation: Long,
    val acquiredAtMillis: Long,
    val deadlineAtMillis: Long
) {
    init {
        require(isValidLeaseId(leaseId)) { "maintenance lease id must be 8..64 URL-safe characters" }
        require(generation >= 0L) { "maintenance lease generation must be non-negative" }
        require(acquiredAtMillis >= 0L) { "maintenance lease timestamp must be non-negative" }
        require(deadlineAtMillis > acquiredAtMillis) {
            "maintenance lease deadline must be after its acquisition"
        }
    }

    fun isExpiredAt(nowMillis: Long): Boolean = nowMillis >= deadlineAtMillis
}

/** Typed answer to an admission attempt. Nothing is admitted unless this is [Admitted]. */
sealed interface DshMaintenanceAdmission {
    data class Admitted(val lease: DshMaintenanceLease) : DshMaintenanceAdmission

    data class Refused(
        val reason: DshMaintenanceRefusal,
        val detail: String? = null
    ) : DshMaintenanceAdmission
}

/** Why the gate refused, so the caller can show a precise state instead of a generic failure. */
enum class DshMaintenanceRefusal {
    /** A live mutator lease exists, so the exclusive barrier cannot be taken. */
    MUTATORS_ACTIVE,

    /** A live read lease exists, so the exclusive barrier cannot be taken. */
    READERS_ACTIVE,

    /** The barrier is held; no other lease (including a second barrier) may be admitted. */
    MAINTENANCE_ACTIVE,

    /** The bounded mutator/reader lease table is full. */
    CAPACITY_EXCEEDED,

    /** The requested lease is already stale for the owner's current generation. */
    INVALID_GENERATION
}

/** Result of releasing a lease. Release is idempotent for a recently released id. */
sealed interface DshMaintenanceRelease {
    data class Released(val lease: DshMaintenanceLease) : DshMaintenanceRelease

    /** The same lease id was already released; the caller's retry is harmless. */
    data class AlreadyReleased(val leaseId: String) : DshMaintenanceRelease

    /** The lease id is unknown (never issued, evicted, or from another process). */
    data class UnknownLease(val leaseId: String) : DshMaintenanceRelease

    /** The lease belongs to another runtime generation; it was not released. */
    data class GenerationMismatch(
        val leaseId: String,
        val leaseGeneration: Long,
        val requestedGeneration: Long
    ) : DshMaintenanceRelease
}

/** Read-only projection for the console and for maintenance precondition checks. */
data class DshMaintenanceAdmissionSnapshot(
    val barrierActive: Boolean,
    val mutationLeaseCount: Int,
    val readLeaseCount: Int
) {
    init {
        require(mutationLeaseCount >= 0) { "mutation lease count must be non-negative" }
        require(readLeaseCount >= 0) { "read lease count must be non-negative" }
    }

    /** True when the barrier may be acquired without waiting for any live lease. */
    val barrierAcquirable: Boolean
        get() = !barrierActive && mutationLeaseCount == 0 && readLeaseCount == 0
}

/**
 * The narrow seam the maintenance owner uses. Implementations must be thread-safe and must expire
 * leases by [DshMaintenanceLease.deadlineAtMillis] before deciding.
 */
interface DshMaintenanceAdmissionGate {
    /**
     * Requests one lease. [MAINTENANCE] is admitted only when no read or mutation lease is live;
     * [MUTATION] and [READ] are refused while the barrier is live.
     *
     * [nowMillis] is the caller's clock reading, or null to let the gate use its own clock. Tests
     * pass an explicit value; production callers normally pass null.
     */
    fun tryAcquire(
        kind: DshMaintenanceLeaseKind,
        generation: Long,
        nowMillis: Long? = null
    ): DshMaintenanceAdmission

    /** Releases a lease taken at [generation]. Idempotent within the gate's release window. */
    fun release(leaseId: String, generation: Long): DshMaintenanceRelease

    /** Current leases; expired leases are reclaimed before the snapshot is built. */
    fun snapshot(nowMillis: Long? = null): DshMaintenanceAdmissionSnapshot
}

private fun isValidLeaseId(value: String): Boolean =
    value.length in 8..64 && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
    }
