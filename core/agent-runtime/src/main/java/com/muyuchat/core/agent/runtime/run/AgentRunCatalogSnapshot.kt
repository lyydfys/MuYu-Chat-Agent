package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Immutable, run-scoped record of the tool catalog a run actually executed with (N04 / DESIGN 4/5).
 *
 * [catalogHash] is a SHA-256 over the sorted `(toolKey, version, schemaHash)` triples, so two runs
 * are provably identical only when every description, version and schema they saw is identical.
 * [toolKeys] is the visible surface for diagnostics; it never contains arguments or results.
 *
 * This record is deliberately not a second state machine: it is written once per execution by
 * [AgentRunCatalogSnapshotRecorder] and released by the coordinator's `release` call.
 */
data class AgentRunCatalogSnapshot(
    val runId: String,
    val catalogHash: String,
    val toolKeys: List<String>,
    val recordedAtMillis: Long
) {
    init {
        AgentContract.requireId(runId, "runId")
        require(catalogHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "catalog hash must be lowercase SHA-256"
        }
        require(toolKeys == toolKeys.distinct().sorted()) { "tool keys must be sorted and unique" }
        require(recordedAtMillis >= 0L) { "recordedAtMillis must not be negative" }
    }
}

/** One recorded tool identity: exact key/version/schema, never a wildcard. */
data class RecordedToolIdentity(
    val toolKey: String,
    val version: String,
    val schemaHash: String
) {
    init {
        AgentContract.requireNotBlank(toolKey, "recorded toolKey")
        AgentContract.requireNotBlank(version, "recorded version")
        AgentContract.requireNotBlank(schemaHash, "recorded schemaHash")
    }
}

/**
 * The catalog identity recorded by the run's first execution (N04). Old events may only carry the
 * hash and keys, in which case [tools] is empty and the run stays fail-closed on drift.
 */
data class AgentRecordedCatalog(
    val catalogHash: String,
    val tools: List<RecordedToolIdentity>
) {
    init {
        require(catalogHash.matches(Regex("^[0-9a-f]{64}$"))) { "catalog hash must be lowercase SHA-256" }
        require(tools == tools.sortedBy { it.toolKey }) { "recorded tools must be sorted by key" }
    }
}

/** Deterministic fingerprint of one immutable tool catalog snapshot. */
object AgentToolCatalogHashing {
    private const val DOMAIN = "mca.run-catalog.v1\n"
    private const val SEPARATOR = "\u0000"

    fun hash(catalog: AgentToolCatalog): String {
        val descriptors = catalog.snapshot()
        val sorted = descriptors.sortedWith(
            compareBy({ it.toolKey }, { it.version }, { it.schemaHash })
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        sorted.forEach { descriptor ->
            val line = buildString {
                append(descriptor.toolKey).append(SEPARATOR)
                append(descriptor.version).append(SEPARATOR)
                append(descriptor.schemaHash).append('\n')
            }
            digest.update(line.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }
}

/**
 * Exact reconstruction of a recorded surface from the *current* catalog (N04).
 *
 * Every recorded `(key, version, schemaHash)` must still exist unchanged; otherwise the result is
 * null and the caller keeps the fail-closed drift error. The filtered catalog is never wider than
 * the record and its hash is re-checked against the recorded hash.
 */
object AgentToolCatalogFiltering {
    fun filter(catalog: AgentToolCatalog, recorded: AgentRecordedCatalog): AgentToolCatalog? {
        val byKey = catalog.snapshot().associateBy { it.toolKey }
        val descriptors = recorded.tools.map { identity ->
            val descriptor = byKey[identity.toolKey] ?: return null
            if (descriptor.version != identity.version || descriptor.schemaHash != identity.schemaHash) {
                return null
            }
            descriptor
        }
        val filtered = object : AgentToolCatalog {
            override fun find(toolKey: String) =
                descriptors.firstOrNull { it.toolKey == toolKey }?.let { catalog.find(it.toolKey) }

            override fun snapshot() = descriptors.sortedBy { it.toolKey }
        }
        if (AgentToolCatalogHashing.hash(filtered) != recorded.catalogHash) return null
        return filtered
    }
}

/**
 * Decorator that records the immutable catalog identity used by each run and releases it with the
 * rest of the run-scoped resources.
 *
 * The coordinator already resolves the catalog exactly once per execution and calls `release` in a
 * `finally` block, so this class only adds the recorded identity. It does not cache the catalog
 * itself: a resumed execution under the same run id resolves a fresh snapshot, which is then
 * recorded (and the previous value replaced) — replacing means "this execution's identity", never
 * "follow the latest enabled extensions mid-run".
 */
class AgentRunCatalogSnapshotRecorder(
    private val delegate: AgentRunToolCatalogProvider,
    private val clockMillis: () -> Long = System::currentTimeMillis
) : AgentRunToolCatalogProvider {
    private val mutex = Mutex()
    private val snapshots = LinkedHashMap<String, AgentRunCatalogSnapshot>()

    override suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog {
        val catalog = delegate.catalogFor(run, task)
        val snapshot = AgentRunCatalogSnapshot(
            runId = run.runId,
            catalogHash = AgentToolCatalogHashing.hash(catalog),
            toolKeys = catalog.snapshot().map { it.toolKey }.distinct().sorted(),
            recordedAtMillis = clockMillis().coerceAtLeast(0L)
        )
        mutex.withLock { snapshots[run.runId] = snapshot }
        return catalog
    }

    override suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: AgentRecordedCatalog
    ): AgentToolCatalog? = delegate.catalogForRecorded(run, task, recorded)

    override suspend fun release(run: AgentRun) {
        try {
            mutex.withLock { snapshots.remove(run.runId) }
        } finally {
            delegate.release(run)
        }
    }

    /** Null after [release]; used by diagnostics and tests, never by the execution loop. */
    suspend fun snapshotFor(runId: String): AgentRunCatalogSnapshot? =
        mutex.withLock { snapshots[runId] }
}
