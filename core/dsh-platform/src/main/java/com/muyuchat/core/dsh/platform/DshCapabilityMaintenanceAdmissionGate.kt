package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmission
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLease
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceLeaseKind
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceRefusal
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceRelease
import java.util.UUID

/**
 * In-memory maintenance admission gate for completion-v2 D07m/D22a.
 *
 * Rules:
 *  - Mutators ([DshMaintenanceLeaseKind.MUTATION]) coexist with each other but block the barrier.
 *  - Readers ([DshMaintenanceLeaseKind.READ]) coexist with readers and mutators, and also block
 *    the barrier, because an exclusive maintenance write must not overlap a live reader that may
 *    observe a half-written tree.
 *  - The barrier ([DshMaintenanceLeaseKind.MAINTENANCE]) is admitted only when no other lease is
 *    live, and while it is live every other attempt is refused with
 *    [DshMaintenanceRefusal.MAINTENANCE_ACTIVE].
 *  - Every lease has a bounded lifetime; expired leases are reclaimed before each decision so a
 *    crashed holder cannot pin the gate forever.
 *
 * The gate is per process and pure Kotlin (no file lock). It serialises participants that take a
 * lease; it does not stop a writer that ignores the gate, which is why D07m still requires the
 * runtime owner to stop or prove quiescence for every write subtree.
 */
class DshCapabilityMaintenanceAdmissionGate(
    private val leaseTtlMillis: Long = 10 * 60_000L,
    private val maxMutationLeases: Int = 32,
    private val maxReadLeases: Int = 32,
    private val releasedHistoryLimit: Int = 256,
    private val leaseIdFactory: () -> String = { "lease-" + UUID.randomUUID().toString().replace("-", "") },
    private val clock: () -> Long = System::currentTimeMillis
) : DshMaintenanceAdmissionGate {
    init {
        require(leaseTtlMillis >= 1L) { "maintenance lease lifetime must be positive" }
        require(maxMutationLeases >= 1) { "mutation lease budget must be positive" }
        require(maxReadLeases >= 1) { "read lease budget must be positive" }
        require(releasedHistoryLimit >= 1) { "released history budget must be positive" }
    }

    private val lock = Any()
    private var barrier: DshMaintenanceLease? = null
    private val mutations = LinkedHashMap<String, DshMaintenanceLease>()
    private val reads = LinkedHashMap<String, DshMaintenanceLease>()
    private val releasedIds = LinkedHashSet<String>()

    override fun tryAcquire(
        kind: DshMaintenanceLeaseKind,
        generation: Long,
        nowMillis: Long?
    ): DshMaintenanceAdmission {
        require(generation >= 0L) { "maintenance generation must be non-negative" }
        val now = nowMillis ?: clock()
        return synchronized(lock) {
            reclaimExpired(now)
            barrier?.let {
                return@synchronized DshMaintenanceAdmission.Refused(
                    DshMaintenanceRefusal.MAINTENANCE_ACTIVE
                )
            }
            when (kind) {
                DshMaintenanceLeaseKind.MAINTENANCE -> {
                    if (mutations.isNotEmpty()) {
                        return@synchronized DshMaintenanceAdmission.Refused(
                            DshMaintenanceRefusal.MUTATORS_ACTIVE,
                            "${mutations.size} mutation lease(s) are still active"
                        )
                    }
                    if (reads.isNotEmpty()) {
                        return@synchronized DshMaintenanceAdmission.Refused(
                            DshMaintenanceRefusal.READERS_ACTIVE,
                            "${reads.size} read lease(s) are still active"
                        )
                    }
                    val lease = newLease(kind, generation, now)
                    barrier = lease
                    DshMaintenanceAdmission.Admitted(lease)
                }

                DshMaintenanceLeaseKind.MUTATION -> {
                    if (mutations.size >= maxMutationLeases) {
                        return@synchronized DshMaintenanceAdmission.Refused(
                            DshMaintenanceRefusal.CAPACITY_EXCEEDED,
                            "mutation lease budget is exhausted"
                        )
                    }
                    val lease = newLease(kind, generation, now)
                    mutations[lease.leaseId] = lease
                    DshMaintenanceAdmission.Admitted(lease)
                }

                DshMaintenanceLeaseKind.READ -> {
                    if (reads.size >= maxReadLeases) {
                        return@synchronized DshMaintenanceAdmission.Refused(
                            DshMaintenanceRefusal.CAPACITY_EXCEEDED,
                            "read lease budget is exhausted"
                        )
                    }
                    val lease = newLease(kind, generation, now)
                    reads[lease.leaseId] = lease
                    DshMaintenanceAdmission.Admitted(lease)
                }
            }
        }
    }

    override fun release(leaseId: String, generation: Long): DshMaintenanceRelease {
        require(generation >= 0L) { "maintenance generation must be non-negative" }
        return synchronized(lock) {
            reclaimExpired(clock())
            val lease = barrier?.takeIf { it.leaseId == leaseId }
                ?: mutations[leaseId]
                ?: reads[leaseId]
                ?: return@synchronized if (leaseId in releasedIds) {
                    DshMaintenanceRelease.AlreadyReleased(leaseId)
                } else {
                    DshMaintenanceRelease.UnknownLease(leaseId)
                }
            if (lease.generation != generation) {
                return@synchronized DshMaintenanceRelease.GenerationMismatch(
                    leaseId = leaseId,
                    leaseGeneration = lease.generation,
                    requestedGeneration = generation
                )
            }
            when (lease.kind) {
                DshMaintenanceLeaseKind.MAINTENANCE -> barrier = null
                DshMaintenanceLeaseKind.MUTATION -> mutations.remove(leaseId)
                DshMaintenanceLeaseKind.READ -> reads.remove(leaseId)
            }
            rememberReleased(leaseId)
            DshMaintenanceRelease.Released(lease)
        }
    }

    override fun snapshot(nowMillis: Long?): DshMaintenanceAdmissionSnapshot {
        val now = nowMillis ?: clock()
        return synchronized(lock) {
            reclaimExpired(now)
            DshMaintenanceAdmissionSnapshot(
                barrierActive = barrier != null,
                mutationLeaseCount = mutations.size,
                readLeaseCount = reads.size
            )
        }
    }

    private fun newLease(
        kind: DshMaintenanceLeaseKind,
        generation: Long,
        nowMillis: Long
    ): DshMaintenanceLease {
        val leaseId = leaseIdFactory()
        require(leaseId !in releasedIds && leaseId != barrier?.leaseId &&
            leaseId !in mutations && leaseId !in reads) {
            "maintenance lease id factory produced a live id"
        }
        return DshMaintenanceLease(
            leaseId = leaseId,
            kind = kind,
            generation = generation,
            acquiredAtMillis = nowMillis,
            deadlineAtMillis = nowMillis + leaseTtlMillis
        )
    }

    private fun reclaimExpired(nowMillis: Long) {
        barrier?.let { lease ->
            if (lease.isExpiredAt(nowMillis)) {
                barrier = null
                rememberReleased(lease.leaseId)
            }
        }
        reclaimExpired(mutations, nowMillis)
        reclaimExpired(reads, nowMillis)
    }

    private fun reclaimExpired(
        leases: LinkedHashMap<String, DshMaintenanceLease>,
        nowMillis: Long
    ) {
        val iterator = leases.values.iterator()
        while (iterator.hasNext()) {
            val lease = iterator.next()
            if (lease.isExpiredAt(nowMillis)) {
                iterator.remove()
                rememberReleased(lease.leaseId)
            }
        }
    }

    private fun rememberReleased(leaseId: String) {
        releasedIds.add(leaseId)
        while (releasedIds.size > releasedHistoryLimit) {
            val oldest = releasedIds.first()
            releasedIds.remove(oldest)
        }
    }
}
