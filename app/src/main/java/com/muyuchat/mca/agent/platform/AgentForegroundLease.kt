package com.muyuchat.mca.agent.platform

import android.content.Context
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.mca.GenerationForegroundLease
import com.muyuchat.mca.McaGenerationForegroundService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Typed platform failures. These are additive platform codes; the core contract only fixes the
 * shared families and the matching [AgentPauseReason] values.
 */
object AgentPlatformErrorCodes {
    const val FOREGROUND_SERVICE_UNAVAILABLE = "FOREGROUND_SERVICE_UNAVAILABLE"
    const val NOTIFICATION_UNAVAILABLE = "NOTIFICATION_UNAVAILABLE"
}

/**
 * Identity used by the existing generation foreground service. The service currently knows only
 * chat/image/api kinds; `agent` deliberately routes to the generic fallback text until the service
 * owner applies the reported [AGENT_FOREGROUND_KIND] mapping.
 */
const val AGENT_FOREGROUND_KIND: String = "agent"

/** Low-level abstraction so tests can prove start-failure and exactly-once release without Android. */
fun interface AgentProcessProtectionStarter {
    /** Returns null when Android rejects the foreground start. Must never throw for a normal rejection. */
    fun acquire(): AgentProcessProtectionLease?

    /**
     * Variant that lets the notification carry an exact Agent run/epoch stop action. Existing fakes
     * stay source-compatible; Android implementations override it.
     */
    fun acquire(stopAction: AgentStopAction?): AgentProcessProtectionLease? = acquire()
}

interface AgentProcessProtectionLease {
    fun release()
}

/**
 * Result of requesting process priority for Agent execution.
 *
 * A missing lease is a recoverable platform condition, not a run failure: the caller must pause the
 * run with [AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE] and continue only after an explicit
 * user retry. Waiting approval/resource/user states must never request this result.
 */
sealed interface AgentForegroundLeaseResult {
    data class Acquired(val lease: AgentForegroundLease) : AgentForegroundLeaseResult

    data class Unavailable(
        val error: AgentError,
        val pauseReason: AgentPauseReason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
    ) : AgentForegroundLeaseResult
}

/** Result of releasing the process-protection lease. The underlying registry is never called twice. */
sealed interface AgentForegroundLeaseRelease {
    data object Released : AgentForegroundLeaseRelease

    data object AlreadyReleased : AgentForegroundLeaseRelease

    data class Failed(val error: AgentError) : AgentForegroundLeaseRelease
}

/**
 * Opaque Agent process-protection lease over the existing [GenerationForegroundLease].
 *
 * `release()` is exactly-once even if the delegate throws; a second call returns
 * [AgentForegroundLeaseRelease.AlreadyReleased] and never touches another owner's lease. This is a
 * process-priority/FGS lease only; it is not the native engine lifecycle lease.
 */
class AgentForegroundLease internal constructor(
    private val delegate: AgentProcessProtectionLease,
    private val correlationId: String,
    private val released: AtomicBoolean = AtomicBoolean(false)
) {
    fun release(): AgentForegroundLeaseRelease {
        if (!released.compareAndSet(false, true)) {
            return AgentForegroundLeaseRelease.AlreadyReleased
        }
        return try {
            delegate.release()
            AgentForegroundLeaseRelease.Released
        } catch (failure: Throwable) {
            AgentForegroundLeaseRelease.Failed(
                platformError(
                    code = AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                    correlationId = correlationId,
                    causeRef = "foreground_release_failed:${failure.javaClass.simpleName}"
                )
            )
        }
    }

    fun isReleased(): Boolean = released.get()

    companion object {
        /**
         * Requests one process-protection lease. Null starter results and starter exceptions become
         * [AgentForegroundLeaseResult.Unavailable]; this function never throws for a start failure.
         */
        fun acquire(
            correlationId: String,
            starter: AgentProcessProtectionStarter
        ): AgentForegroundLeaseResult {
            val delegate = try {
                starter.acquire()
            } catch (failure: Throwable) {
                return AgentForegroundLeaseResult.Unavailable(
                    platformError(
                        code = AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                        correlationId = correlationId,
                        causeRef = "foreground_start_failed:${failure.javaClass.simpleName}"
                    )
                )
            } ?: return AgentForegroundLeaseResult.Unavailable(
                platformError(
                    code = AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                    correlationId = correlationId,
                    causeRef = "foreground_start_returned_null"
                )
            )
            return AgentForegroundLeaseResult.Acquired(
                AgentForegroundLease(delegate, correlationId)
            )
        }
    }
}

/** Narrow typed source so the run-state guard can be tested with a fake lease factory. */
fun interface AgentForegroundLeaseSource {
    fun acquire(correlationId: String): AgentForegroundLeaseResult

    /**
     * Carries the exact run/epoch into the notification stop action. Default delegates to the
     * one-argument form so existing test fakes remain valid.
     */
    fun acquire(correlationId: String, stopAction: AgentStopAction?): AgentForegroundLeaseResult =
        acquire(correlationId)
}

/**
 * Production entry point. It parses the existing [McaGenerationForegroundService] path with the Agent
 * kind; no second service or runtime is created.
 */
class AgentForegroundLeaseController(
    context: Context,
    private val kind: String = AGENT_FOREGROUND_KIND
) : AgentForegroundLeaseSource {
    private val appContext: Context = context.applicationContext

    override fun acquire(correlationId: String): AgentForegroundLeaseResult =
        acquire(correlationId, null)

    override fun acquire(
        correlationId: String,
        stopAction: AgentStopAction?
    ): AgentForegroundLeaseResult = AgentForegroundLease.acquire(
        correlationId = correlationId,
        starter = AndroidAgentProcessProtectionStarter(appContext, kind, stopAction)
    )
}

/**
 * Lease policy, kept pure so the owner and tests can prove that waiting states hold no lease.
 * PAUSING/STOPPING do not acquire a new lease; an already-held lease is retained until stop proof.
 */
object AgentForegroundLeasePolicy {
    fun shouldAcquire(state: AgentRunState): Boolean = state == AgentRunState.RUNNING

    fun shouldRetain(state: AgentRunState): Boolean = when (state) {
        AgentRunState.RUNNING,
        AgentRunState.PAUSING,
        AgentRunState.STOPPING -> true

        else -> false
    }

    fun mustNotHoldWhileWaiting(state: AgentRunState): Boolean = when (state) {
        AgentRunState.WAITING_APPROVAL,
        AgentRunState.WAITING_RESOURCE,
        AgentRunState.WAITING_USER -> true

        else -> false
    }
}

internal class AndroidAgentProcessProtectionStarter(
    private val context: Context,
    private val kind: String,
    private val stopAction: AgentStopAction? = null
) : AgentProcessProtectionStarter {
    override fun acquire(): AgentProcessProtectionLease? = acquire(stopAction)

    override fun acquire(stopAction: AgentStopAction?): AgentProcessProtectionLease? {
        val lease: GenerationForegroundLease =
            McaGenerationForegroundService.acquire(context, kind, stopAction) ?: return null
        return object : AgentProcessProtectionLease {
            override fun release() {
                McaGenerationForegroundService.release(context, lease)
            }
        }
    }
}

internal fun platformError(
    code: String,
    correlationId: String,
    causeRef: String?,
    safeMessage: String = "Agent 无法保持后台运行，任务已暂停；请回到应用后重试。"
): AgentError = AgentError(
    code = code,
    category = AgentErrorCategory.RESOURCE,
    retryability = AgentRetryability.AFTER_USER_ACTION,
    safeMessage = safeMessage,
    correlationId = correlationId.trim().ifBlank { "agent-platform" },
    causeRef = causeRef,
    possibleSideEffects = false
)
