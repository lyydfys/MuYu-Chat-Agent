package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.contract.SessionGeneration

/**
 * Universal Hardware Admission Notice:
 * Unknown chipset, missing profile, uncertified, or untested device must never block user operations.
 * Hardware probe results are advisory only and must fallback to generic compatible path.
 */
data class HardwareProbeAdvisory(
    val chipset: String = "generic",
    val isSnapdragon: Boolean = false,
    val recommendedBackend: String = "CPU",
    val isGenericFallback: Boolean = true,
    val advisoryNotes: String = "Generic compatible path active; no device allowlist applied"
)

/**
 * Execution lease held by an active request.
 */
interface ExecutionLease : AutoCloseable {
    val identity: RequestIdentity
    val isCancelled: Boolean
    fun requestCancel(reason: String = "Client cancelled")
}

/**
 * Session port interface representing an isolated session context.
 */
interface SessionPort : AutoCloseable {
    val sessionId: String
    val currentGeneration: SessionGeneration
    val isLeaseActive: Boolean

    fun tryAcquireExecutionLease(identity: RequestIdentity): ExecutionLease?
    fun advanceGeneration(): SessionGeneration
    fun releaseAllResources()
}

/**
 * Engine port interface representing long-lived loaded model resources.
 */
interface EnginePort : AutoCloseable {
    val engineId: String
    val modelPath: String
    val isReady: Boolean

    fun createSession(sessionId: String): SessionPort
    fun unload()
}

/**
 * Backend port interface representing backend provider and hardware capability owner.
 */
interface BackendPort : AutoCloseable {
    val backendKind: String
    val epoch: RuntimeEpoch
    val hardwareAdvisory: HardwareProbeAdvisory
    val isInitialized: Boolean

    fun createEngine(modelPath: String): EnginePort
    fun shutdown()
}

/**
 * Top-level Process Runtime port contract.
 */
interface RuntimePort : AutoCloseable {
    val currentEpoch: RuntimeEpoch
    val isHealthy: Boolean

    fun advanceEpoch(): RuntimeEpoch
    fun getBackend(kind: String): BackendPort?
    fun registerBackend(kind: String, backend: BackendPort)
    fun shutdownProcess()
}

/**
 * Callback sink for receiving asynchronous execution events.
 */
fun interface EventConsumer {
    fun onEvent(event: CallbackEvent)
}

/**
 * Health status evaluation result for native subsystems.
 */
data class RuntimeHealthReport(
    val epoch: RuntimeEpoch,
    val isHealthy: Boolean,
    val activeRequestCount: Int,
    val totalSessions: Int,
    val details: Map<String, String> = emptyMap()
)
