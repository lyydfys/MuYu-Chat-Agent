package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHostClient
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.maintenance.DshHostHealth
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchPort
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchRequest
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchResult
import com.muyuchat.core.dsh.maintenance.DshTrialRuntimeStatus
import com.muyuchat.core.dsh.maintenance.DshTrialStopProof
import kotlinx.coroutines.runBlocking

/**
 * App-side [DshTrialLaunchPort] over the managed runtime owner's typed trial client (D24).
 *
 * The isolated trial generation must be created inside the single `:dsh` host owner; launching Node
 * from the app process would be a second owner and would bypass the stop fence/orphan scan. This
 * adapter therefore owns no process and no policy: it only maps one typed request onto one typed
 * client call. [DshClosureTrialHost] keeps the arbitration, the durable journal and the
 * commit/rollback boundaries.
 *
 * The client is the app's one runtime client, probed for the trial contract by
 * [fromRuntimeClient]. A runtime module that does not implement [DshTrialHostClient] (the D24
 * owner half has not landed in this build) yields `client == null`: [launch] then returns
 * [DshTrialLaunchResult.Failed] with [NOT_CONNECTED_DETAIL] and nothing reports a READY trial.
 * A connected client is the only way to observe RUNNING, and its own reply is trusted verbatim -
 * with one honesty rule: RUNNING without a positive pid is not a proven process and therefore a
 * failure, never a started trial.
 *
 * - [status] reports `running = false` without a client, because this adapter instance cannot have
 *   started one; `running = null` whenever a connected owner reply is missing or unknown;
 * - [stopAndProve] reports [DshTrialStopProof.Stopped] without a client: no build that cannot
 *   launch a trial can have one running, and that keeps an honest failed launch out of
 *   `RECOVERY_REQUIRED`;
 * - [probeHealth] reports every positive observation as absent.
 *
 * Residual risk: if a build that *can* launch a trial is replaced (downgrade) by one whose runtime
 * client no longer implements the contract, a leftover `trial/active.json` would be released as
 * trivially stopped. [fromRuntimeClient] makes that state exact rather than accidental; the exit
 * condition is the runtime owner always implementing the contract once trial launch ships.
 *
 * Blocking: the maintenance trial host is a synchronous port and already runs its production half
 * with [runBlocking]. These calls must be made from the maintenance transaction's IO dispatcher,
 * never from the main thread.
 */
internal class DshRuntimeTrialLaunchAdapter(
    private val client: DshTrialHostClient? = null
) : DshTrialLaunchPort {

    override fun launch(request: DshTrialLaunchRequest): DshTrialLaunchResult {
        val connected = client ?: return DshTrialLaunchResult.Failed(NOT_CONNECTED_DETAIL)
        val snapshot = runCatching {
            runBlocking {
                connected.startTrial(
                    candidateRoot = request.candidateRoot.absolutePath,
                    candidateFingerprint = request.candidateFingerprint,
                    homeRoot = request.homeRoot.absolutePath,
                    workspaceRoot = request.workspaceRoot.absolutePath,
                    tmpRoot = request.tmpRoot.absolutePath
                )
            }
        }.getOrElse { failure ->
            return DshTrialLaunchResult.Failed("trial launch failed: ${failure.javaClass.simpleName}")
        }
        return when (snapshot.phase) {
            // RUNNING is the only positive outcome, and only with a real process identity.
            DshTrialPhase.RUNNING -> if (snapshot.pid > 0L) {
                DshTrialLaunchResult.Started(
                    pid = snapshot.pid,
                    generation = snapshot.generation,
                    detail = snapshot.detail.take(MAX_DETAIL_CHARS)
                )
            } else {
                DshTrialLaunchResult.Failed(
                    "the runtime owner reported a trial without a process id"
                )
            }

            DshTrialPhase.STOPPED -> DshTrialLaunchResult.Refused(
                snapshot.detail.ifBlank { "the runtime owner refused the trial" }.take(MAX_DETAIL_CHARS)
            )

            DshTrialPhase.FAILED,
            DshTrialPhase.UNKNOWN -> DshTrialLaunchResult.Failed(
                snapshot.detail.ifBlank { "the runtime owner did not confirm a trial generation" }
                    .take(MAX_DETAIL_CHARS)
            )
        }
    }

    override fun probeHealth(): DshHostHealth {
        val connected = client ?: return DshHostHealth(detail = NOT_CONNECTED_DETAIL)
        val health: DshTrialHealthSnapshot = runCatching {
            runBlocking { connected.trialHealth() }
        }.getOrElse { failure ->
            return DshHostHealth(detail = "trial health probe failed: ${failure.javaClass.simpleName}")
        }
        // A trial is served from an isolated home with no account: the adapter never claims
        // provider/auth readiness even if the owner reports the host ready.
        return DshHostHealth(
            hostReady = health.hostReady,
            homeReadable = health.homeReadable,
            sessionReaderReady = health.sessionReaderReady,
            sessionStoreInitialized = health.sessionStoreInitialized,
            newUserProfile = health.newUserProfile,
            providerConfigured = false,
            authReady = false,
            detail = health.detail.take(MAX_DETAIL_CHARS)
        )
    }

    override fun stopAndProve(timeoutMillis: Long): DshTrialStopProof {
        // No client means this adapter never launched a trial; there is no process to stop.
        val connected = client ?: return DshTrialStopProof.Stopped
        val snapshot: DshTrialSnapshot = runCatching {
            runBlocking { connected.stopTrial() }
        }.getOrElse { failure ->
            return DshTrialStopProof.Unverified("trial stop failed: ${failure.javaClass.simpleName}")
        }
        return when (snapshot.phase) {
            DshTrialPhase.STOPPED -> DshTrialStopProof.Stopped
            // A positive "still running" answer is not a stop proof; it becomes recovery-required.
            DshTrialPhase.RUNNING -> DshTrialStopProof.Unverified("the trial process is still running")
            DshTrialPhase.FAILED,
            DshTrialPhase.UNKNOWN -> DshTrialStopProof.Unverified(
                snapshot.detail.ifBlank { "the trial stop is unconfirmed" }.take(MAX_STOP_DETAIL_CHARS)
            )
        }
    }

    override fun status(): DshTrialRuntimeStatus {
        val connected = client ?: return DshTrialRuntimeStatus(running = false, detail = NOT_CONNECTED_DETAIL)
        val snapshot: DshTrialSnapshot = runCatching {
            runBlocking { connected.trialStatus() }
        }.getOrElse { failure ->
            // Unknown is never rounded to "not running": the host stays conservatively unknown.
            return DshTrialRuntimeStatus(
                running = null,
                detail = "trial status failed: ${failure.javaClass.simpleName}"
            )
        }
        return when (snapshot.phase) {
            DshTrialPhase.RUNNING -> DshTrialRuntimeStatus(
                running = true,
                pid = snapshot.pid,
                detail = snapshot.detail.take(MAX_STOP_DETAIL_CHARS)
            )

            DshTrialPhase.STOPPED -> DshTrialRuntimeStatus(
                running = false,
                pid = snapshot.pid,
                detail = snapshot.detail.take(MAX_STOP_DETAIL_CHARS)
            )

            DshTrialPhase.FAILED,
            DshTrialPhase.UNKNOWN -> DshTrialRuntimeStatus(
                running = null,
                pid = snapshot.pid,
                detail = snapshot.detail.ifBlank { "trial status is unknown" }.take(MAX_STOP_DETAIL_CHARS)
            )
        }
    }

    companion object {
        /**
         * Bounded capability probe of the one managed runtime client (D24).
         *
         * This is deliberately not a general service locator: the source is the single statically
         * typed runtime client this app already owns, the target is the frozen trial contract, and
         * a miss becomes the exact typed "not connected" failure - never a default client, a second
         * host or an optimistic state. Casting through the [DshRuntimeClient] supertype (rather
         * than the concrete service client) keeps the probe valid for any client implementation
         * that also implements [DshTrialHostClient], which is how the runtime-owner half lands.
         *
         * No app-process host is created here; the runtime owner remains the only process owner.
         */
        fun fromRuntimeClient(runtimeClient: DshRuntimeClient): DshTrialLaunchPort =
            DshRuntimeTrialLaunchAdapter(client = runtimeClient as? DshTrialHostClient)

        /**
         * Exact detail used by [com.muyuchat.core.dsh.maintenance.DshClosureTrialHost] when no port
         * exists; repeating it keeps the unconnected adapter indistinguishable from the honest
         * NULL path for maintenance journals and console messages.
         */
        const val NOT_CONNECTED_DETAIL =
            "the isolated trial launcher is not connected to the managed runtime owner"

        private const val MAX_DETAIL_CHARS = 200
        private const val MAX_STOP_DETAIL_CHARS = 160
    }
}
