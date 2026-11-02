package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityFailure
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantRequest
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantResult
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantState
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeGrantStatus
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single owner of the persistent workspace-tree grant.
 *
 * One instance per app process (created by the capability assembly). It owns:
 * - the durable record (`takePersistableUriPermission` success only) and its explicit release;
 * - the one pending system picker, bounded by [pickTimeoutMillis];
 * - the live verification that turns a stale record into INVALID / PROVIDER_UNAVAILABLE.
 *
 * Nothing else may write this state: the loopback routes call this owner, the console only reads
 * [status], and the WebView / download / chooser paths never reach it. A pick is started from the
 * caller's coroutine ([scope]) and returns immediately, because the capability server times out a
 * request long before a human finishes choosing a directory.
 */
class DshWorkspaceTreeGrantOwner(
    private val platform: DshWorkspaceTreeGrantPlatform,
    private val store: DshWorkspaceTreeGrantStore,
    private val picker: DshWorkspaceTreePickerPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pickTimeoutMillis: Long = DEFAULT_PICK_TIMEOUT_MILLIS
) {
    init {
        require(pickTimeoutMillis in 1_000L..MAX_PICK_TIMEOUT_MILLIS) {
            "workspace tree picker timeout is out of range"
        }
    }

    private val lock = Any()
    private var record: DshWorkspaceTreeGrantRecord? = null
    private var loaded = false
    private var pending: PendingGrant? = null
    private var lastDetail: String? = null

    private class PendingGrant(val request: DshWorkspaceTreeGrantRequest) {
        @Volatile
        var job: Job? = null
    }

    private sealed interface StartDecision {
        data class AlreadyGranted(val status: DshWorkspaceTreeGrantStatus) : StartDecision
        data class AlreadyPending(val status: DshWorkspaceTreeGrantStatus) : StartDecision
        data class Begin(val status: DshWorkspaceTreeGrantStatus) : StartDecision
    }

    /** Current typed state. Verifies the durable record against the live platform permissions. */
    fun status(): DshWorkspaceTreeGrantStatus = synchronized(lock) { observeLocked() }

    /**
     * Starts the one system picker, or reports the existing grant / pending picker.
     *
     * The returned status is already the caller's view: GRANTED without opening anything,
     * `pendingPick=true` while the dialog is visible, or the unchanged observed state.
     */
    fun requestGrant(request: DshWorkspaceTreeGrantRequest): DshWorkspaceTreeGrantResult {
        val decision = synchronized(lock) {
            val current = observeLocked()
            when {
                current.state == DshWorkspaceTreeGrantState.GRANTED ->
                    StartDecision.AlreadyGranted(current)

                pending != null -> StartDecision.AlreadyPending(current.copy(pendingPick = true))

                else -> {
                    lastDetail = null
                    pending = PendingGrant(request)
                    StartDecision.Begin(current.copy(pendingPick = true))
                }
            }
        }
        return when (decision) {
            is StartDecision.AlreadyGranted -> DshWorkspaceTreeGrantResult.Granted(decision.status)
            is StartDecision.AlreadyPending -> DshWorkspaceTreeGrantResult.PickStarted(decision.status)
            is StartDecision.Begin -> {
                val job = scope.launch { completePick(request) }
                synchronized(lock) {
                    val current = pending
                    if (current != null && current.request === request) current.job = job
                }
                DshWorkspaceTreeGrantResult.PickStarted(decision.status)
            }
        }
    }

    /**
     * Explicit revoke. Calls `releasePersistableUriPermission` before claiming REVOKED; when the
     * provider is gone the grant is *not* reported as released.
     */
    fun release(): DshWorkspaceTreeGrantResult = synchronized(lock) {
        cancelPendingLocked()
        val current = loadLocked()
        if (current == null) {
            lastDetail = null
            return@synchronized DshWorkspaceTreeGrantResult.Released(DshWorkspaceTreeGrantStatus.NOT_GRANTED)
        }
        if (current.revokedAtMillis != null) {
            return@synchronized DshWorkspaceTreeGrantResult.Released(observeLocked())
        }
        val observation = runCatching { platform.releasePersistable(current.treeUri) }
            .getOrElse { DshWorkspaceTreeGrantObservation.ProviderUnavailable }
        if (observation is DshWorkspaceTreeGrantObservation.ProviderUnavailable) {
            lastDetail = RELEASE_PROVIDER_UNAVAILABLE
            return@synchronized DshWorkspaceTreeGrantResult.Rejected(
                DshCapabilityFailure(
                    code = DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                    capability = DshCapability.WORKSPACE_TREE,
                    message = "workspace tree release failed: the document provider is unavailable",
                    retryable = true
                )
            )
        }
        val revoked = current.copy(revokedAtMillis = maxOf(clock(), current.grantedAtMillis))
        record = revoked
        runCatching { store.write(revoked) }
        lastDetail = null
        DshWorkspaceTreeGrantResult.Released(observeLocked())
    }

    /**
     * Host generation teardown: the visible picker belongs to that generation, so its pending
     * request is cancelled. The persisted grant is user-owned and deliberately survives; only an
     * explicit [release] revokes it.
     */
    fun onHostStopped() {
        val job = synchronized(lock) {
            val current = pending
            pending = null
            current?.job
        }
        job?.cancel()
    }

    private suspend fun completePick(request: DshWorkspaceTreeGrantRequest) {
        val outcome = try {
            withTimeoutOrNull(pickTimeoutMillis) {
                picker.pick(
                    DshWorkspaceTreePickerRequest(
                        requestId = request.requestId,
                        generation = request.generation,
                        writeAccess = request.writeAccess
                    )
                )
            } ?: DshWorkspaceTreePickerOutcome.Cancelled
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            DshWorkspaceTreePickerOutcome.UserNotPresent
        }
        synchronized(lock) {
            val current = pending
            if (current == null || current.request !== request) return
            pending = null
            when (outcome) {
                is DshWorkspaceTreePickerOutcome.Selected ->
                    applySelectionLocked(outcome, request.writeAccess)
                DshWorkspaceTreePickerOutcome.Cancelled -> lastDetail = PICK_CANCELLED
                DshWorkspaceTreePickerOutcome.UserNotPresent -> lastDetail = USER_NOT_PRESENT
            }
        }
    }

    /** Must run under [lock]; persisting here keeps the record and the status in one transaction. */
    private fun applySelectionLocked(
        selection: DshWorkspaceTreePickerOutcome.Selected,
        writeAccess: Boolean
    ) {
        val requested = if (writeAccess) selection.flags else DshWorkspaceTreeFlags.READ
        if (!DshWorkspaceTreeGrantPolicy.isUsableSelection(selection.treeUri, requested)) {
            lastDetail = INVALID_SELECTION
            return
        }
        val observation = runCatching { platform.takePersistable(selection.treeUri, requested) }
            .getOrElse { DshWorkspaceTreeGrantObservation.ProviderUnavailable }
        when (observation) {
            is DshWorkspaceTreeGrantObservation.Held -> {
                // Never persist more than the caller asked for, even if the provider offered it.
                val effective = if (writeAccess) {
                    observation.flags
                } else {
                    DshWorkspaceTreeFlags(read = observation.flags.read, write = false)
                }
                if (!effective.read) {
                    lastDetail = INVALID_SELECTION
                    return
                }
                val granted = DshWorkspaceTreeGrantRecord(
                    treeUri = selection.treeUri,
                    flags = effective,
                    grantedAtMillis = clock()
                )
                record = granted
                loaded = true
                runCatching { store.write(granted) }
                lastDetail = null
            }

            DshWorkspaceTreeGrantObservation.NotHeld -> lastDetail = NOT_PERSISTED
            DshWorkspaceTreeGrantObservation.ProviderUnavailable -> lastDetail = GRANT_PROVIDER_UNAVAILABLE
        }
    }

    /** Must run under [lock]. Reads the durable record once; malformed data reads as null. */
    private fun loadLocked(): DshWorkspaceTreeGrantRecord? {
        if (!loaded) {
            record = runCatching { store.read() }.getOrNull()
            loaded = true
        }
        return record
    }

    private fun observeLocked(): DshWorkspaceTreeGrantStatus {
        val current = loadLocked()
        if (current == null) {
            return DshWorkspaceTreeGrantPolicy.statusOf(
                record = null,
                observation = null,
                pendingPick = pending != null,
                detail = lastDetail
            )
        }
        if (current.revokedAtMillis != null) {
            return DshWorkspaceTreeGrantPolicy.statusOf(
                current,
                null,
                pendingPick = pending != null,
                detail = lastDetail
            )
        }
        val observation = runCatching { platform.observe(current.treeUri) }
            .getOrElse { DshWorkspaceTreeGrantObservation.ProviderUnavailable }
        return DshWorkspaceTreeGrantPolicy.statusOf(
            record = current,
            observation = observation,
            pendingPick = pending != null,
            detail = lastDetail
        )
    }

    private fun cancelPendingLocked() {
        val current = pending
        pending = null
        current?.job?.cancel()
    }

    companion object {
        const val DEFAULT_PICK_TIMEOUT_MILLIS = 120_000L
        const val MAX_PICK_TIMEOUT_MILLIS = 600_000L

        private const val PICK_CANCELLED = "the workspace folder picker was dismissed"
        private const val USER_NOT_PRESENT = "no resumed app surface could show the folder picker"
        private const val INVALID_SELECTION = "the picker did not return a usable content tree URI"
        private const val NOT_PERSISTED = "the document provider did not persist the tree permission"
        private const val GRANT_PROVIDER_UNAVAILABLE = "the document provider is unavailable"
        private const val RELEASE_PROVIDER_UNAVAILABLE = "the document provider is unavailable; release was not confirmed"
    }
}
