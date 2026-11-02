package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshAuthorizeUrlRules
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.contract.auth.DshLoginSource
import com.muyuchat.core.dsh.contract.auth.DshUnauthorizedScope
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import com.muyuchat.core.dsh.contract.console.DshPopupRejection
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How the account lane classified one unauthorized response. Never a signed-in or signed-out claim. */
enum class DshAccountUnauthorizedOutcome {
    /** A model-listing 401 never clears the account login (official behavior). */
    NOT_IDENTITY_LOSS,

    /** Neither HTTP 401 nor the official `40003` business code. */
    NOT_UNAUTHORIZED,

    /** A 401 from a flow that does not own the account credential (DSH cookie, MCA gateway). */
    NOT_ACCOUNT_SCOPE,

    /**
     * The response did look like an account rejection, but MCA never reads or stores the official
     * grant, so it cannot prove which record failed and must leave deletion to the official store.
     */
    OFFICIAL_STORE_ONLY,

    /** The rejection belongs to a superseded credential lifetime or another record. */
    STALE
}

/**
 * Production owner of the DeepSeek account sign-in correlation on the Android host.
 *
 * It is the only adapter that connects the four transports of one official attempt:
 *
 * ```text
 * trusted DSH page pre-open (about:blank)
 *        -> one bounded host attempt (DshAccountSignInMachine)
 *        -> authorize URL observed in the popup (DshBrowserHandoffPolicy semantics)
 *        -> system browser handoff (DshExternalBrowserLauncher, injected Android intent)
 *        -> callback/commit phases (owned by the official module, not observable here)
 * ```
 *
 * Ownership split (do not blur it):
 * - the official `dsh-deepseek-account-platform` module owns state, PKCE verifier, `auth_init`,
 *   `auth_exchange`, the grant and the credential store;
 * - MCA owns only the host additions: the bounded attempt deadline, the trusted window binding, the
 *   browser decision, the durable "outcome unknown" marker and the typed terminal state.
 *
 * Honest limits, deliberately not faked:
 * - the fixed `/oauth/callback` route runs inside the DSH node host, so MCA cannot observe the
 *   exchange or the commit. Success is therefore *never* published by this class; a handed-off
 *   attempt stays `WAITING_CALLBACK` until its deadline expires it;
 * - the official `cancelSignIn` is an RPC inside the same node host, so MCA cannot call or observe
 *   it either. The one host-observable consequence is the official page releasing the pre-opened
 *   window (the pinned `BrowserLogin` closes it on `cancelled`/`failed`/`expired` and never after a
 *   success); [onOfficialPopupReleased] maps that fact to a typed `CANCELLED` terminal state and
 *   still claims no official phase and no success;
 * - MCA holds no credential record, so a 401 can never be turned into a local delete or a
 *   signed-out projection here (see [onUnauthorized]).
 *
 * All state is guarded by one lock; no suspend function is called while it is held.
 */
class DshAccountLoginOwner(
    /** App-scoped host scope; the deadline ticker is its child and dies with it. */
    private val scope: CoroutineScope,
    /** `callbackOrigin` the official module was started with: the DSH loopback origin. */
    private val callbackOrigin: () -> String?,
    /** Origin that is allowed to pre-open the account popup: the same DSH loopback origin. */
    private val trustedWebOrigin: () -> String?,
    private val launcher: DshExternalBrowserLauncher,
    /** Ordinary external links keep their existing container behavior. */
    private val externalFallback: DshExternalBrowserPort,
    private val journal: DshAccountAttemptJournal,
    private val bounds: DshAuthAttemptBounds = DshAuthAttemptBounds(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val attemptIdFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * Non-secret fingerprint of the official credential store (`size:mtime`), or null when it is
     * absent. It is the only host-observable success fact: the official module writes this store
     * exactly when a grant is committed. Metadata only; the file contents are never read.
     */
    private val credentialFingerprint: () -> String? = { null }
) : DshExternalBrowserPort, DshAccountAuthUrlMatcher, DshAccountLoginPort {

    private val lock = Any()
    private val credentialPolicy = DshCredentialLifecyclePolicy(currentIssuer = bounds.platformOrigin)

    private var machine: DshAccountSignInMachine? = null
    private var machineOrigin: String? = null
    private var journalRestored = false
    private var ticker: Job? = null

    /** Store fingerprint captured when the current attempt started (null = absent/unreadable). */
    private var credentialFingerprintAtStart: String? = null

    // ------------------------------------------------------------------ projection

    /**
     * Secret-free projection of the correlated attempt. Never contains the authorize URL query.
     *
     * Reading it also restores a durable attempt marker once the runtime origin is known, so a
     * screen can show `OUTCOME_UNKNOWN` after an app process restart without starting anything.
     */
    fun attemptView(): DshAuthAttemptView {
        trustedWebOrigin()?.let { origin -> machineFor(origin) }
        return synchronized(lock) { attemptViewLocked() }
    }

    private fun attemptViewLocked(): DshAuthAttemptView =
        machine?.attemptView() ?: DshAuthAttemptView(
            attemptId = "",
            phase = DshAuthAttemptPhase.IDLE,
            authorizeUrl = null,
            expiresAtEpochMillis = null
        )

    // ------------------------------------------------------------------ matcher seam

    /**
     * Exact classification for the popup policy: true only for the authorized URL of the live
     * attempt. Before `auth_init` answers, only the official origin/path shape is accepted, still
     * bound to a live attempt; with no live attempt nothing is an account popup, so the container
     * degrades to an ordinary external link instead of inventing an attempt.
     */
    override fun isAccountAuthUrl(url: String): Boolean {
        val snapshot = currentMachine()?.snapshot() ?: return false
        if (!snapshot.phase.isActive) return false
        val official = snapshot.authorizeUrl
        return if (official != null) {
            DshAuthorizeUrlRules.matchesOfficialAuthorizeUrl(official, url, bounds)
        } else {
            DshAuthorizeUrlRules.isOfficialAuthorizeUrl(url, bounds)
        }
    }

    // ------------------------------------------------------------------ login request

    override fun onPopupPreopened(requesterOrigin: String) {
        val origin = trustedWebOrigin() ?: return
        if (requesterOrigin != origin) return
        val declaredCallbackOrigin = callbackOrigin() ?: return
        val current = machineFor(origin)
        val started = synchronized(lock) {
            if (current.snapshot().phase == DshHostAuthPhase.UNKNOWN) {
                // The user asked again after an unprovable restart: the old outcome stays unknown,
                // only a fresh attempt may start.
                current.supersedeUnknownAttempt()
            }
            val snapshot = current.snapshot()
            when {
                snapshot.phase == DshHostAuthPhase.OPENING_POPUP -> {
                    current.onPopupWindowCreated(HANDLE_ID, requesterOrigin)
                    true
                }
                // An attempt is already waiting: the new hidden window is simply not bound.
                snapshot.phase.isActive -> false
                else -> {
                    val transition = current.requestSignIn(declaredCallbackOrigin, DshLoginSource.WEB)
                    if (transition.failure != null) {
                        false
                    } else {
                        // Capture BEFORE the handoff: a store change after this point is attributable
                        // to this attempt. A store that already existed and does not change must not
                        // be reported as a new success.
                        credentialFingerprintAtStart = runCatching { credentialFingerprint() }.getOrNull()
                        current.onPopupWindowCreated(HANDLE_ID, requesterOrigin)
                        syncJournal(current)
                        true
                    }
                }
            }
        }
        if (started) ensureTicker()
    }

    override fun onPopupAbandoned() {
        val current = currentMachine() ?: return
        synchronized(lock) {
            if (current.snapshot().phase != DshHostAuthPhase.OPENING_POPUP) return
            current.cancel()
            journal.clear()
        }
    }

    /**
     * The official page released the pre-opened window after the authorize URL was handed off.
     *
     * This is the only host-observable trace of the official cancel: the official UI's 取消 button
     * sends `account.cancelSignIn(id)` over its own RPC (invisible to the host) and the official
     * client then closes the pre-opened window (`BrowserLogin.update`); the closed window reaches
     * the container as a page-close. The official attempt is therefore already terminal, and the
     * host only records that no success is coming:
     *
     * - a live waiting attempt becomes `CANCELLED` with
     *   [DshAuthFailureCode.OFFICIAL_POPUP_RELEASED] and clears its recovery marker, so a restart
     *   cannot resurrect it as `UNKNOWN` and a later callback cannot be admitted;
     * - if the bounded deadline already elapsed, the timeout stays the terminal fact (`EXPIRED`)
     *   instead of being relabelled as a cancel;
     * - `committing` is left untouched (the official store is writing the grant);
     * - nothing is started, retried, revoked or reported as a success.
     */
    override fun onOfficialPopupReleased() {
        synchronized(lock) {
            val current = machine ?: return
            val snapshot = current.snapshot()
            if (!snapshot.phase.isActive) return
            if (snapshot.phase == DshHostAuthPhase.COMMITTING) return
            val expiresAt = snapshot.expiresAtEpochMillis
            val now = clock()
            when {
                expiresAt != null && now >= expiresAt -> current.tick(now)
                else -> current.onOfficialPopupReleased()
            }
            syncJournal(current)
        }
    }

    /**
     * Remaining attempt budget for holding the hidden pre-opened window. Bounded by
     * [DshPopupLimits.MAX_LIFETIME_MILLIS] so a hidden native window can never outlive it.
     */
    override fun handoffBudgetMillis(): Long {
        val snapshot = currentMachine()?.snapshot() ?: return 0L
        if (!snapshot.phase.isActive) return 0L
        val expiresAt = snapshot.expiresAtEpochMillis ?: return 0L
        val remaining = expiresAt - clock()
        if (remaining <= 0L) return 0L
        return remaining.coerceAtMost(DshPopupLimits.MAX_LIFETIME_MILLIS)
    }

    // ------------------------------------------------------------------ browser port

    override suspend fun openPopup(request: DshPopupRequest): DshPopupHandoffResult {
        if (request.kind != DshPopupKind.ACCOUNT_AUTH) {
            // A pre-open that turned out to be a plain link: release the provisional attempt only
            // while it is still waiting for its authorize URL.
            releaseProvisionalAttempt()
            return externalFallback.openPopup(request)
        }
        val decision = synchronized(lock) { decideAccountHandoff(request) }
        return when (decision) {
            is AccountHandoffDecision.Open -> {
                val result = launcher.open(decision.url)
                synchronized(lock) {
                    decision.machine.onBrowserHandoffFinished(HANDLE_ID, result)
                    syncJournal(decision.machine)
                }
                when (result) {
                    DshBrowserHandoffResult.Opened -> DshPopupHandoffResult.HandedOff
                    // No activity can handle the official authorize URL: typed degradation, and
                    // the machine has already failed the attempt with BROWSER_HANDLER_MISSING.
                    DshBrowserHandoffResult.NoHandler -> DshPopupHandoffResult.Unavailable(
                        DshPopupUnavailable.NO_BROWSER_HANDLER
                    )
                    is DshBrowserHandoffResult.Rejected -> DshPopupHandoffResult.Rejected(
                        DshPopupRejection.STALE_REQUEST
                    )
                }
            }

            is AccountHandoffDecision.Refused -> decision.result
        }
    }

    private sealed interface AccountHandoffDecision {
        data class Open(val machine: DshAccountSignInMachine, val url: String) : AccountHandoffDecision
        data class Refused(val result: DshPopupHandoffResult) : AccountHandoffDecision
    }

    private fun decideAccountHandoff(request: DshPopupRequest): AccountHandoffDecision {
        val origin = trustedWebOrigin()
        if (origin == null) {
            return AccountHandoffDecision.Refused(
                DshPopupHandoffResult.Unavailable(DshPopupUnavailable.ACCOUNT_ATTEMPT_UNAVAILABLE)
            )
        }
        val current = machineFor(origin)
        val now = clock()
        val phase = current.snapshot().phase
        if (!phase.isActive) {
            return AccountHandoffDecision.Refused(
                DshPopupHandoffResult.Unavailable(DshPopupUnavailable.ACCOUNT_ATTEMPT_UNAVAILABLE)
            )
        }
        if (phase == DshHostAuthPhase.EXCHANGING || phase == DshHostAuthPhase.COMMITTING) {
            // The callback was already admitted: a popup URL cannot belong to it any more.
            return AccountHandoffDecision.Refused(
                DshPopupHandoffResult.Rejected(DshPopupRejection.STALE_REQUEST)
            )
        }
        if (request.isExpiredAt(now)) {
            current.tick(now)
            syncJournal(current)
            return AccountHandoffDecision.Refused(DshPopupHandoffResult.Expired)
        }
        val transition = current.onAuthorizeUrl(HANDLE_ID, request.url, openUrl = request.url)
        val open = transition.effects.filterIsInstance<DshAuthEffect.OpenExternalBrowser>().firstOrNull()
        if (open != null) {
            syncJournal(current)
            return AccountHandoffDecision.Open(current, open.authorizeUrl)
        }
        current.tick(now)
        syncJournal(current)
        return AccountHandoffDecision.Refused(
            when (transition.failure) {
                DshAuthFailureCode.ATTEMPT_TIMEOUT -> DshPopupHandoffResult.Expired
                // The URL is a valid official authorize URL, but not the one of this attempt.
                DshAuthFailureCode.AUTHORIZE_URL_INVALID,
                DshAuthFailureCode.PROTOCOL_ERROR -> DshPopupHandoffResult.Rejected(
                    DshPopupRejection.URL_MISMATCH
                )
                else -> DshPopupHandoffResult.Rejected(DshPopupRejection.STALE_REQUEST)
            }
        )
    }

    private fun releaseProvisionalAttempt() {
        val current = currentMachine() ?: return
        synchronized(lock) {
            if (current.snapshot().phase != DshHostAuthPhase.OPENING_POPUP) return
            current.cancel()
            journal.clear()
        }
    }

    // ------------------------------------------------------------------ unauthorized (401)

    override fun onWebSessionUnauthorized() {
        onUnauthorized(scope = DshUnauthorizedScope.WEB_COOKIE, httpStatus = UNAUTHORIZED)
    }

    /**
     * Classifies one unauthorized response against the official conditional-invalidation rules.
     *
     * MCA never reads the official grant, so [DshCredentialLifecyclePolicy] always evaluates with
     * `current = null`: the account lane cannot prove that a 401 belongs to the live record and
     * therefore never deletes, revokes or publishes a signed-out state. A genuinely account-scoped
     * rejection still stops the correlated attempt so nothing can be reported as a success after
     * the platform rejected the credential.
     */
    fun onUnauthorized(
        scope: DshUnauthorizedScope,
        httpStatus: Int,
        officialCode: Int? = null
    ): DshAccountUnauthorizedOutcome = synchronized(lock) {
        val decision = credentialPolicy.onUnauthorized(
            event = DshUnauthorizedEvent(
                scope = scope,
                httpStatus = httpStatus,
                officialCode = officialCode,
                tokenFingerprint = null,
                issuer = null,
                observedLifetimeGeneration = 0L
            ),
            current = null
        )
        when (decision.action) {
            DshCredentialAction.DELETE_EXACT_GRANT -> {
                // Not reachable without a readable record; kept explicit so a future official
                // projection can never turn this branch into a local grant deletion.
                releaseActiveAttempt()
                DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY
            }

            DshCredentialAction.IGNORE_MODEL_LISTING -> DshAccountUnauthorizedOutcome.NOT_IDENTITY_LOSS
            DshCredentialAction.NOT_ACCOUNT_SCOPE -> DshAccountUnauthorizedOutcome.NOT_ACCOUNT_SCOPE
            DshCredentialAction.NOT_UNAUTHORIZED -> DshAccountUnauthorizedOutcome.NOT_UNAUTHORIZED
            DshCredentialAction.IGNORE_STALE -> DshAccountUnauthorizedOutcome.STALE
            DshCredentialAction.IGNORE_NO_CREDENTIAL -> DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY
        }
    }

    private fun releaseActiveAttempt() {
        val current = machine ?: return
        if (current.snapshot().phase.isActive) {
            current.cancel()
            journal.clear()
        }
    }

    // ------------------------------------------------------------------ deadline / teardown

    /**
     * One deadline step. Runs from the owner's own ticker while an attempt is active and is also
     * the entry point that notices a lost runtime generation (host stop/restart/crash), which ends
     * the attempt without claiming a success.
     *
     * @return true while the ticker should keep running.
     */
    internal fun tick(nowMillis: Long = clock()): Boolean = synchronized(lock) {
        val current = machine ?: return@synchronized false
        val origin = trustedWebOrigin()
        if (machineOrigin != null && origin != machineOrigin) {
            current.onRuntimeGenerationLost()
            journal.clear()
            return@synchronized false
        }
        val transition = current.tick(nowMillis)
        // The official store is the only success authority the host can observe without reading
        // credentials. A change after this attempt started is correlated with this attempt because
        // the owner admits one attempt at a time.
        val fingerprintNow = runCatching { credentialFingerprint() }.getOrNull()
        val baseline = credentialFingerprintAtStart
        if (current.snapshot().phase.isActive && fingerprintNow != null &&
            (baseline == null || fingerprintNow != baseline)
        ) {
            val observed = current.onCredentialStoreObserved()
            if (observed.phase == DshHostAuthPhase.AUTHENTICATED) {
                journal.clear()
                return@synchronized false
            }
        }
        syncJournal(current)
        transition.phase.isActive
    }

    // ------------------------------------------------------------------ internals

    private fun currentMachine(): DshAccountSignInMachine? = synchronized(lock) { machine }

    /**
     * The machine is bound to one trusted origin. A new origin means a new DSH runtime generation:
     * the previous generation's attempt cannot be correlated any more, so it is ended as a typed
     * failure (never a success) and the journal is cleared.
     */
    private fun machineFor(origin: String): DshAccountSignInMachine = synchronized(lock) {
        val existing = machine
        if (existing != null && machineOrigin == origin) {
            existing
        } else {
            if (existing != null && existing.snapshot().phase.isActive) {
                existing.onRuntimeGenerationLost()
                journal.clear()
            }
            val created = DshAccountSignInMachine(
                bounds = bounds,
                trustedWebOrigin = origin,
                clock = clock,
                attemptIdFactory = attemptIdFactory
            )
            if (!journalRestored) {
                // Only the first machine of this process may inherit a marker from a killed process.
                journalRestored = true
                journal.load()?.let { marker ->
                    if (marker.active) {
                        created.restoreAfterProcessRestart(marker.attemptId)
                        // Resolve the restored UNKNOWN attempt only from a *change* in the official
                        // store. An unchanged/absent store stays UNKNOWN (the official attempt may
                        // still be alive in the node host); absence is never treated as failure.
                        val nowFingerprint = runCatching { credentialFingerprint() }.getOrNull()
                        val before = marker.credentialFingerprintAtStart
                        if (nowFingerprint != null && before != null && nowFingerprint != before) {
                            created.resolveAfterRestart(credentialPresent = true)
                            journal.clear()
                        }
                    } else {
                        journal.clear()
                    }
                }
            }
            machine = created
            machineOrigin = origin
            created
        }
    }

    private fun syncJournal(current: DshAccountSignInMachine) {
        if (!current.snapshot().phase.isActive) {
            journal.clear()
            return
        }
        val id = current.snapshot().attemptId ?: return
        journal.save(
            DshAccountAttemptMarker(
                attemptId = id,
                active = true,
                updatedAtEpochMillis = clock(),
                credentialFingerprintAtStart = credentialFingerprintAtStart
            )
        )
    }

    private fun ensureTicker() {
        synchronized(lock) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (true) {
                    delay(DEADLINE_TICK_MILLIS)
                    if (!tick(clock())) return@launch
                }
            }
        }
    }

    private companion object {
        /**
         * Stable host handle for the single trusted pre-opened account window. The native
         * `about:blank` WebView is owned and destroyed by the container, which is why the host only
         * needs the identity of that one window.
         */
        const val HANDLE_ID = "deepseek-account-popup"

        const val DEADLINE_TICK_MILLIS = 1_000L
        const val UNAUTHORIZED = 401
    }
}
