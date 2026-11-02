package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.protocol.AgentHashes

/**
 * Platform wake installation for durable Agent parks (S4).
 *
 * The scheduler owns no execution and no state: it only asks WorkManager to run
 * [com.muyuchat.mca.AgentParkWakeWorker], which validates the token through the one process root.
 * Returning false means the token is durable but no wake was armed for this process.
 */
interface AgentParkWakeScheduler {
    fun schedule(runId: String, wakeId: String): Boolean

    fun cancel(runId: String)
}

/** Pure naming/identity so the platform adapter and tests agree without Android types. */
internal object AgentParkWakePlan {
    fun uniqueName(runId: String): String =
        "agent-park-" + AgentHashes.sha256HexUtf8(runId).take(32)
}
