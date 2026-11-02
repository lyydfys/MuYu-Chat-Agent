package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthCallbackRequest
import com.muyuchat.core.dsh.contract.auth.DshAuthCallbackVerdict
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshAuthRejectionCode
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.contract.auth.DshLoginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03/D04: the host-side account attempt machine.
 *
 * Grounding: pinned `dsh-deepseek-account-platform` (`startSignIn`, fixed `GET /oauth/callback`,
 * 400/410 admission, 600 s attempt deadline with a 3,600 s ceiling, `expires_in` only shortening
 * it, timer cleared at `committing`, cancel deferred during `committing`). Official attempt state
 * stays authoritative; these tests only pin the host additions.
 */
class DshAccountSignInMachineTest {

    private var now = 1_000_000L
    private val bounds = DshAuthAttemptBounds()
    private val handle = "handle-1"
    private var attempts = 0

    private fun machine(): DshAccountSignInMachine = DshAccountSignInMachine(
        bounds = bounds,
        trustedWebOrigin = TRUSTED_ORIGIN,
        clock = { now },
        attemptIdFactory = { "attempt-${++attempts}" }
    )

    private fun DshAccountSignInMachine.startWaiting(): DshAccountSignInMachine {
        requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        onAuthorizeUrl(handle, AUTHORIZE_URL)
        return this
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun fullHappyPathReachesAuthenticatedOnlyAfterTheOfficialCommit() {
        val machine = machine()
        assertEquals(DshHostAuthPhase.IDLE, machine.snapshot().phase)

        val start = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshHostAuthPhase.OPENING_POPUP, start.phase)
        assertTrue(start.effects.isEmpty())

        machine.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        val url = machine.onAuthorizeUrl(handle, AUTHORIZE_URL)
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, url.phase)
        assertEquals(
            listOf(DshAuthEffect.OpenExternalBrowser("attempt-1", AUTHORIZE_URL)),
            url.effects
        )

        machine.onBrowserHandoffFinished(handle, DshBrowserHandoffResult.Opened)
        val accepted = machine.onCallback(callback(), officialStateMatches = true)
        assertTrue(accepted.verdict is DshAuthCallbackVerdict.Accepted)
        assertEquals(DshHostAuthPhase.EXCHANGING, accepted.transition.phase)
        assertEquals(
            listOf(DshAuthEffect.SubmitCallback("attempt-1")),
            accepted.transition.effects
        )

        machine.onCommitting()
        val done = machine.onCommitFinished(success = true)
        assertEquals(DshHostAuthPhase.AUTHENTICATED, done.phase)
        assertNull(done.failure)
        assertEquals(DshAuthAttemptPhase.SUCCEEDED, machine.attemptView().phase)
    }

    @Test
    fun aSecondSignInWhileTheOfficialAttemptIsActiveIsBusyInsteadOfStartingAnother() {
        val machine = machine().startWaiting()
        val busy = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshAuthFailureCode.ATTEMPT_BUSY, busy.failure)
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, busy.phase)
        assertEquals("attempt-1", machine.snapshot().attemptId)
    }

    @Test
    fun invalidCallbackOriginIsRejectedBeforeAnyAttemptStarts() {
        val machine = machine()
        for (invalid in listOf(
            "https://127.0.0.1:1234",
            "http://127.0.0.1",
            "http://127.0.0.1:0",
            "http://user:pass@127.0.0.1:1234",
            "http://127.0.0.1:1234/path",
            "http://127.0.0.1:1234?x=1",
            "http://10.0.0.2:1234"
        )) {
            val transition = machine.requestSignIn(invalid, DshLoginSource.WEB)
            assertEquals(
                "callback origin '$invalid' must be rejected",
                DshAuthFailureCode.CALLBACK_ORIGIN_INVALID,
                transition.failure
            )
            assertEquals(DshHostAuthPhase.IDLE, transition.phase)
        }
    }

    // ------------------------------------------------------------------ trusted popup seam

    @Test
    fun aPopupHandleFromAnUntrustedOriginIsClosedAndNeverBoundToTheAttempt() {
        val machine = machine()
        machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        val rejected = machine.onPopupWindowCreated(handle, "http://evil.example:8080")
        assertEquals(DshAuthFailureCode.POPUP_ORIGIN_UNTRUSTED, rejected.failure)
        assertTrue(rejected.effects.contains(DshAuthEffect.ClosePendingWindow(handle)))

        // The untrusted handle never became the attempt's handle, so a URL cannot be attached to it.
        val url = machine.onAuthorizeUrl(handle, AUTHORIZE_URL)
        assertEquals(DshAuthFailureCode.AUTHORIZE_URL_INVALID, url.failure)
        assertTrue(url.effects.contains(DshAuthEffect.ClosePendingWindow(handle)))
        assertTrue(url.effects.none { it is DshAuthEffect.OpenExternalBrowser })
    }

    @Test
    fun aNonOfficialAuthorizeUrlFailsClosedAndNeverOpensABrowser() {
        for (bad in listOf(
            "https://evil.example/dsh/authorize?state=s",
            "https://platform.deepseek.com/oauth/authorize?state=s",
            "https://platform.deepseek.com/dsh/authorize?state=s#frag",
            "https://user:pw@platform.deepseek.com/dsh/authorize?state=s",
            "http://platform.deepseek.com/dsh/authorize?state=s"
        )) {
            val fresh = machine()
            fresh.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
            fresh.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
            val transition = fresh.onAuthorizeUrl(handle, bad)
            assertEquals(
                "url '$bad' must be rejected",
                DshAuthFailureCode.AUTHORIZE_URL_INVALID,
                transition.failure
            )
            assertEquals(DshHostAuthPhase.FAILED, transition.phase)
            assertTrue(
                "a rejected attempt must cancel the official attempt",
                transition.effects.any { it is DshAuthEffect.CancelOfficialAttempt }
            )
            assertTrue(transition.effects.none { it is DshAuthEffect.OpenExternalBrowser })
        }
    }

    @Test
    fun theOfficialThemeConversionIsAcceptedWhileAnyOtherQueryChangeIsRejected() {
        val machine = machine()
        machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        machine.onPopupWindowCreated(handle, TRUSTED_ORIGIN)

        val themed = AUTHORIZE_URL + "&theme=dark"
        val opened = machine.onAuthorizeUrl(handle, AUTHORIZE_URL, openUrl = themed)
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, opened.phase)
        assertEquals(
            listOf(DshAuthEffect.OpenExternalBrowser("attempt-1", themed)),
            opened.effects
        )

        val machine2 = machine()
        machine2.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        machine2.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        val tampered = AUTHORIZE_URL + "&redirect_uri=http://evil.example/cb"
        val rejected = machine2.onAuthorizeUrl(handle, AUTHORIZE_URL, openUrl = tampered)
        assertEquals(DshAuthFailureCode.AUTHORIZE_URL_INVALID, rejected.failure)
        assertTrue(rejected.effects.none { it is DshAuthEffect.OpenExternalBrowser })
    }

    @Test
    fun aLateAuthorizeUrlForAnotherHandleCannotOpenABrowser() {
        val machine = machine().startWaiting()
        val late = machine.onAuthorizeUrl("handle-other", AUTHORIZE_URL)
        assertEquals(DshAuthFailureCode.AUTHORIZE_URL_INVALID, late.failure)
        assertTrue(late.effects.none { it is DshAuthEffect.OpenExternalBrowser })
    }

    @Test
    fun missingBrowserHandlerIsATypedFailureWithAttemptCancellation() {
        val machine = machine().startWaiting()
        val failed = machine.onBrowserHandoffFinished(handle, DshBrowserHandoffResult.NoHandler)
        assertEquals(DshAuthFailureCode.BROWSER_HANDLER_MISSING, failed.failure)
        assertEquals(DshHostAuthPhase.FAILED, failed.phase)
        assertTrue(failed.effects.any { it is DshAuthEffect.CancelOfficialAttempt })
    }

    // ------------------------------------------------------------------ 400 branches

    @Test
    fun callbackShapeAndStateViolationsAnswer400WithoutConsumingTheAttempt() {
        fun fresh(): DshAccountSignInMachine = machine().startWaiting()
        val cases = listOf(
            DshAuthCallbackRequest("POST", "/oauth/callback", mapOf("code" to listOf("c"), "state" to listOf("s"))) to
                DshAuthRejectionCode.WRONG_METHOD,
            DshAuthCallbackRequest("GET", "/wrong", mapOf("code" to listOf("c"), "state" to listOf("s"))) to
                DshAuthRejectionCode.WRONG_PATH,
            DshAuthCallbackRequest("GET", "/oauth/callback", mapOf("state" to listOf("s"))) to
                DshAuthRejectionCode.MISSING_CODE,
            DshAuthCallbackRequest(
                "GET", "/oauth/callback",
                mapOf("code" to listOf("c1", "c2"), "state" to listOf("s"))
            ) to DshAuthRejectionCode.DUPLICATE_CODE,
            DshAuthCallbackRequest("GET", "/oauth/callback", mapOf("code" to listOf("c"))) to
                DshAuthRejectionCode.MISSING_STATE,
            DshAuthCallbackRequest(
                "GET", "/oauth/callback",
                mapOf("code" to listOf("c"), "state" to listOf("s1", "s2"))
            ) to DshAuthRejectionCode.DUPLICATE_STATE,
            DshAuthCallbackRequest(
                "GET", "/oauth/callback",
                mapOf("code" to listOf("c"), "state" to listOf("s"), "error" to listOf("access_denied"))
            ) to DshAuthRejectionCode.CALLBACK_ERROR_PARAM
        )
        for ((request, expected) in cases) {
            val machine = fresh()
            val outcome = machine.onCallback(request, officialStateMatches = true)
            val verdict = outcome.verdict as DshAuthCallbackVerdict.Rejected
            assertEquals("status for $expected", 400, verdict.status)
            assertEquals(expected, verdict.code)
            assertEquals(
                "a 400 must not consume the attempt",
                DshHostAuthPhase.WAITING_CALLBACK,
                outcome.transition.phase
            )
        }

        val mismatched = fresh().onCallback(callback(), officialStateMatches = false)
        assertEquals(
            DshAuthRejectionCode.STATE_MISMATCH,
            (mismatched.verdict as DshAuthCallbackVerdict.Rejected).code
        )
        assertEquals(400, (mismatched.verdict as DshAuthCallbackVerdict.Rejected).status)
    }

    // ------------------------------------------------------------------ 410 branches

    @Test
    fun aDuplicateCallbackIsReplayed410AndTheFirstOneWins() {
        val machine = machine().startWaiting()
        val first = machine.onCallback(callback(), officialStateMatches = true)
        assertTrue(first.verdict is DshAuthCallbackVerdict.Accepted)
        val second = machine.onCallback(callback(), officialStateMatches = true)
        val verdict = second.verdict as DshAuthCallbackVerdict.Rejected
        assertEquals(410, verdict.status)
        assertEquals(DshAuthRejectionCode.REPLAYED_CALLBACK, verdict.code)
    }

    @Test
    fun aCallbackAfterCancelIsRejected410AndNeverSubmitted() {
        val machine = machine().startWaiting()
        val cancelled = machine.cancel()
        assertEquals(DshHostAuthPhase.CANCELLED, cancelled.phase)
        assertEquals(DshAuthFailureCode.ATTEMPT_CANCELLED, cancelled.failure)
        assertTrue(cancelled.effects.contains(DshAuthEffect.CancelOfficialAttempt("attempt-1")))
        assertTrue(cancelled.effects.contains(DshAuthEffect.ClosePendingWindow(handle)))

        val outcome = machine.onCallback(callback(), officialStateMatches = true)
        val verdict = outcome.verdict as DshAuthCallbackVerdict.Rejected
        assertEquals(410, verdict.status)
        assertEquals(DshAuthRejectionCode.ATTEMPT_NOT_WAITING, verdict.code)
        assertTrue(outcome.transition.effects.isEmpty())
    }

    @Test
    fun aCallbackForAnAttemptThatExpiredAnswers410ExpiredAndCancelsTheOfficialAttempt() {
        val machine = machine().startWaiting()
        now += bounds.defaultAttemptMillis + 1
        val outcome = machine.onCallback(callback(), officialStateMatches = true)
        val verdict = outcome.verdict as DshAuthCallbackVerdict.Rejected
        assertEquals(410, verdict.status)
        assertEquals(DshAuthRejectionCode.ATTEMPT_EXPIRED, verdict.code)
        assertEquals(DshHostAuthPhase.EXPIRED, outcome.transition.phase)
        assertTrue(outcome.transition.effects.any { it is DshAuthEffect.CancelOfficialAttempt })
    }

    // ------------------------------------------------------------------ deadline / cancel semantics

    @Test
    fun theAttemptDeadlineExpiresTheAttemptAndReleasesTheWindowAndOfficialAttempt() {
        val machine = machine().startWaiting()
        assertEquals(now + bounds.defaultAttemptMillis, machine.snapshot().expiresAtEpochMillis)
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, machine.tick().phase)
        now += bounds.defaultAttemptMillis
        val expired = machine.tick()
        assertEquals(DshHostAuthPhase.EXPIRED, expired.phase)
        assertEquals(DshAuthFailureCode.ATTEMPT_TIMEOUT, expired.failure)
        assertTrue(expired.effects.any { it is DshAuthEffect.ClosePendingWindow })
        assertTrue(expired.effects.any { it is DshAuthEffect.CancelOfficialAttempt })
        // The window is gone: a late URL can no longer open a browser.
        val late = machine.onAuthorizeUrl(handle, AUTHORIZE_URL)
        assertTrue(late.effects.none { it is DshAuthEffect.OpenExternalBrowser })
    }

    @Test
    fun theOfficialExpiresInOnlyShortensTheDeadlineAndNeverExtendsPastTheCeiling() {
        val machine = machine()
        machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        machine.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        machine.onAuthorizeUrl(handle, AUTHORIZE_URL, expiresInMillis = 30)
        assertEquals(now + 30_000L, machine.snapshot().expiresAtEpochMillis)

        val longLived = machine()
        longLived.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        longLived.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        longLived.onAuthorizeUrl(handle, AUTHORIZE_URL, expiresInMillis = 7_200)
        assertEquals(
            "a server window longer than the default cannot extend the attempt",
            now + bounds.defaultAttemptMillis,
            longLived.snapshot().expiresAtEpochMillis
        )
    }

    @Test
    fun committingClearsTheDeadlineSoARecoveredCommitIsNotExpiredByTheHost() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        machine.onCommitting()
        now += bounds.defaultAttemptMillis * 2
        val transition = machine.tick()
        assertEquals(DshHostAuthPhase.COMMITTING, transition.phase)
        assertNull(transition.failure)
        assertEquals(DshHostAuthPhase.AUTHENTICATED, machine.onCommitFinished(true).phase)
    }

    @Test
    fun cancelDuringCommittingIsDeferredExactlyLikeTheOfficialCancelSignIn() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        machine.onCommitting()
        val deferred = machine.cancel()
        assertTrue("official cancel does not abort a committing attempt", deferred.cancelDeferred)
        assertEquals(DshHostAuthPhase.COMMITTING, deferred.phase)
        assertTrue(deferred.effects.isEmpty())
        assertEquals(DshHostAuthPhase.AUTHENTICATED, machine.onCommitFinished(true).phase)
    }

    @Test
    fun repeatedCancelIsIdempotentAndCancellingAnIdleMachineIsANoOp() {
        val idle = machine().cancel()
        assertEquals(DshHostAuthPhase.IDLE, idle.phase)
        assertTrue(idle.effects.isEmpty())
        assertNull(idle.failure)

        val machine = machine().startWaiting()
        val first = machine.cancel()
        val second = machine.cancel()
        assertEquals(DshHostAuthPhase.CANCELLED, second.phase)
        assertTrue(second.effects.isEmpty())
        assertEquals(first.phase, second.phase)
    }

    // --------------------------------------------------- official cancel (popup released)

    @Test
    fun theOfficialPageReleasingTheWindowEndsTheWaitingAttemptAsCancelledWithoutSuccess() {
        val machine = machine().startWaiting()

        val released = machine.onOfficialPopupReleased()

        assertEquals(DshHostAuthPhase.CANCELLED, released.phase)
        assertEquals(DshAuthFailureCode.OFFICIAL_POPUP_RELEASED, released.failure)
        assertTrue(
            "the window is already gone and the official attempt is already terminal",
            released.effects.isEmpty()
        )
        assertEquals(DshAuthAttemptPhase.CANCELLED, machine.attemptView().phase)
        assertEquals(
            DshAuthFailureCode.OFFICIAL_POPUP_RELEASED.name,
            machine.attemptView().errorCode
        )

        // A late callback for the cancelled attempt is refused exactly like the official 410.
        val late = machine.onCallback(callback(), officialStateMatches = true)
        assertTrue(late.verdict is DshAuthCallbackVerdict.Rejected)
        assertEquals(410, (late.verdict as DshAuthCallbackVerdict.Rejected).status)
        assertEquals(
            DshAuthRejectionCode.ATTEMPT_NOT_WAITING,
            (late.verdict as DshAuthCallbackVerdict.Rejected).code
        )

        // Repeating the observation is not a second decision.
        assertEquals(DshHostAuthPhase.CANCELLED, machine.onOfficialPopupReleased().phase)
        // No commit report may resurrect a released attempt as a signed-in one.
        val lateCommit = machine.onCommitFinished(true)
        assertEquals(DshHostAuthPhase.CANCELLED, lateCommit.phase)
        assertEquals(DshAuthFailureCode.OFFICIAL_POPUP_RELEASED, lateCommit.failure)
        assertEquals(DshHostAuthPhase.CANCELLED, machine.snapshot().phase)
    }

    @Test
    fun aReleaseBeforeTheAuthorizeUrlEndsTheAttemptAndNoBrowserIsEverOpened() {
        val machine = machine()
        machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        machine.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        assertEquals(DshHostAuthPhase.OPENING_POPUP, machine.snapshot().phase)

        val released = machine.onOfficialPopupReleased()

        assertEquals(DshHostAuthPhase.CANCELLED, released.phase)
        assertEquals(DshAuthFailureCode.OFFICIAL_POPUP_RELEASED, released.failure)
        // The official auth_init answer arrives after the user already cancelled.
        val late = machine.onAuthorizeUrl(handle, AUTHORIZE_URL)
        assertTrue(late.effects.none { it is DshAuthEffect.OpenExternalBrowser })
        assertEquals(DshHostAuthPhase.CANCELLED, late.phase)
    }

    @Test
    fun aReleaseCannotRelabelAnAlreadyTerminalAttempt() {
        val authenticated = machine().startWaiting()
        authenticated.onCallback(callback(), officialStateMatches = true)
        authenticated.onCommitting()
        assertEquals(DshHostAuthPhase.AUTHENTICATED, authenticated.onCommitFinished(true).phase)
        authenticated.onOfficialPopupReleased()
        assertEquals(
            "a release after the official commit proves nothing and may not demote a commit",
            DshHostAuthPhase.AUTHENTICATED,
            authenticated.snapshot().phase
        )

        val expired = machine().startWaiting()
        now += bounds.defaultAttemptMillis
        assertEquals(DshHostAuthPhase.EXPIRED, expired.tick().phase)
        expired.onOfficialPopupReleased()
        assertEquals(
            "the timeout stays the terminal fact instead of becoming a cancel",
            DshHostAuthPhase.EXPIRED,
            expired.snapshot().phase
        )
        assertEquals(DshAuthFailureCode.ATTEMPT_TIMEOUT, expired.snapshot().lastFailure)

        val unknown = machine()
        unknown.restoreAfterProcessRestart("attempt-old")
        unknown.onOfficialPopupReleased()
        assertEquals(
            "an unprovable outcome stays unprovable",
            DshHostAuthPhase.UNKNOWN,
            unknown.snapshot().phase
        )
    }

    @Test
    fun aReleaseDuringCommittingKeepsTheAttemptForTheOfficialOutcome() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        machine.onCommitting()

        val released = machine.onOfficialPopupReleased()

        assertEquals(
            "the official credential store owns the write; a closed window proves nothing",
            DshHostAuthPhase.COMMITTING,
            released.phase
        )
        assertNull(released.failure)
        assertEquals(DshHostAuthPhase.AUTHENTICATED, machine.onCommitFinished(true).phase)
    }

    @Test
    fun aReleasedAttemptCanBeReplacedByAFreshUserInitiatedSignIn() {
        val machine = machine().startWaiting()
        machine.onOfficialPopupReleased()
        assertEquals("attempt-1", machine.snapshot().attemptId)

        val fresh = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshHostAuthPhase.OPENING_POPUP, fresh.phase)
        assertNull(fresh.failure)
        assertEquals("attempt-2", machine.snapshot().attemptId)
    }

    @Test
    fun credentialStoreFailureNeverReportsSuccess() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        val failed = machine.onCommitFinished(success = false)
        assertEquals(DshHostAuthPhase.FAILED, failed.phase)
        assertEquals(DshAuthFailureCode.CREDENTIAL_STORE_FAILED, failed.failure)
    }

    @Test
    fun exchangeFailureIsTypedAndReleasesTheAttempt() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        val failed = machine.onExchangeFailed(DshAuthFailureCode.PROTOCOL_ERROR)
        assertEquals(DshHostAuthPhase.FAILED, failed.phase)
        assertEquals(DshAuthFailureCode.PROTOCOL_ERROR, failed.failure)
        assertTrue(failed.effects.any { it is DshAuthEffect.CancelOfficialAttempt })
    }

    // ------------------------------------------------------------------ restart

    @Test
    fun processRestartLeavesTheAttemptUnknownAndBlocksASecondAttemptUntilResolved() {
        val machine = machine().startWaiting()
        val restarted = machine.onProcessRestart()
        assertEquals(DshHostAuthPhase.UNKNOWN, restarted.phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED, restarted.failure)
        assertTrue(restarted.effects.any { it is DshAuthEffect.ClosePendingWindow })
        assertTrue(
            "a dead process cannot cancel an official attempt it no longer owns",
            restarted.effects.none { it is DshAuthEffect.CancelOfficialAttempt }
        )

        val busy = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshAuthFailureCode.ATTEMPT_BUSY, busy.failure)

        val oldCallback = machine.onCallback(callback(), officialStateMatches = true)
        assertEquals(
            DshAuthRejectionCode.ATTEMPT_NOT_WAITING,
            (oldCallback.verdict as DshAuthCallbackVerdict.Rejected).code
        )

        val resolved = machine.resolveAfterRestart(credentialPresent = false)
        assertEquals(DshHostAuthPhase.IDLE, resolved.phase)
        val fresh = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshHostAuthPhase.OPENING_POPUP, fresh.phase)
    }

    @Test
    fun restartWithACommittedCredentialResolvesToAuthenticated() {
        val machine = machine().startWaiting()
        machine.onCallback(callback(), officialStateMatches = true)
        machine.onCommitting()
        assertEquals(DshHostAuthPhase.UNKNOWN, machine.onProcessRestart().phase)
        val resolved = machine.resolveAfterRestart(credentialPresent = true)
        assertEquals(DshHostAuthPhase.AUTHENTICATED, resolved.phase)
        assertEquals(DshAuthAttemptPhase.SUCCEEDED, machine.attemptView().phase)
    }

    @Test
    fun restartWhileIdleIsANoOp() {
        val machine = machine()
        val restarted = machine.onProcessRestart()
        assertEquals(DshHostAuthPhase.IDLE, restarted.phase)
        assertTrue(restarted.effects.isEmpty())
    }

    // ------------------------------------------------------------------ app-process restart (journal)

    @Test
    fun restoringAfterAnAppProcessRestartPublishesUnknownAndNeverASuccess() {
        val machine = machine()
        val restored = machine.restoreAfterProcessRestart("attempt-from-killed-process")
        assertEquals(DshHostAuthPhase.UNKNOWN, restored.phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED, restored.failure)
        assertTrue("a dead host has no window to close", restored.effects.isEmpty())

        val view = machine.attemptView()
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, view.phase)
        assertEquals("attempt-from-killed-process", machine.snapshot().attemptId)
        // No commit report may upgrade an unprovable outcome to a signed-in state.
        machine.onCommitFinished(success = true)
        assertEquals(DshHostAuthPhase.UNKNOWN, machine.snapshot().phase)
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, machine.attemptView().phase)
    }

    @Test
    fun aRestoreIsRefusedWhileAnAttemptIsStillLive() {
        val machine = machine().startWaiting()
        val ignored = machine.restoreAfterProcessRestart("another-attempt")
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, ignored.phase)
        assertEquals("attempt-1", machine.snapshot().attemptId)
    }

    @Test
    fun anUnknownOutcomeIsOnlySupersededByANewUserSignIn() {
        val machine = machine()
        machine.restoreAfterProcessRestart("attempt-old")

        // A callback for the dead attempt is refused with the official 410, never accepted.
        val callbackOutcome = machine.onCallback(callback(), officialStateMatches = true)
        assertEquals(
            DshAuthRejectionCode.ATTEMPT_NOT_WAITING,
            (callbackOutcome.verdict as DshAuthCallbackVerdict.Rejected).code
        )
        assertEquals(410, (callbackOutcome.verdict as DshAuthCallbackVerdict.Rejected).status)

        // A silent retry cannot start a second grant over a possibly committed one.
        val busy = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshAuthFailureCode.ATTEMPT_BUSY, busy.failure)
        assertEquals(DshHostAuthPhase.UNKNOWN, busy.phase)

        val superseded = machine.supersedeUnknownAttempt()
        assertEquals(DshHostAuthPhase.IDLE, superseded.phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED, superseded.failure)

        val fresh = machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        assertEquals(DshHostAuthPhase.OPENING_POPUP, fresh.phase)
        assertNull(fresh.failure)
    }

    @Test
    fun supersedingAnAttemptThatIsNotUnknownIsANoOp() {
        val waiting = machine().startWaiting()
        val unchanged = waiting.supersedeUnknownAttempt()
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, unchanged.phase)
        assertEquals("attempt-1", waiting.snapshot().attemptId)
    }

    @Test
    fun aLostRuntimeGenerationEndsTheAttemptAsRestartedWithoutCancellingADeadHost() {
        val machine = machine().startWaiting()
        val lost = machine.onRuntimeGenerationLost()
        assertEquals(DshHostAuthPhase.FAILED, lost.phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED, lost.failure)
        assertTrue(
            "the process that owned the official attempt is gone; nothing can be cancelled",
            lost.effects.isEmpty()
        )
        assertEquals(DshAuthAttemptPhase.FAILED, machine.attemptView().phase)

        // A late callback for the lost attempt can never complete it.
        val late = machine.onCallback(callback(), officialStateMatches = true)
        assertEquals(
            DshAuthRejectionCode.ATTEMPT_NOT_WAITING,
            (late.verdict as DshAuthCallbackVerdict.Rejected).code
        )

        // Idempotent on an already terminal phase.
        assertEquals(DshHostAuthPhase.FAILED, machine.onRuntimeGenerationLost().phase)
    }

    @Test
    fun aLostRuntimeGenerationNeverUpgradesAnUnprovableOutcome() {
        val machine = machine()
        machine.restoreAfterProcessRestart("attempt-old")
        machine.onRuntimeGenerationLost()
        assertEquals(
            "an unknown outcome must stay unknown after another host generation is lost",
            DshHostAuthPhase.UNKNOWN,
            machine.snapshot().phase
        )
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, machine.attemptView().phase)
    }

    @Test
    fun theAttemptViewNeverCarriesASecretAndReportsNoSecretFlag() {
        val machine = machine().startWaiting()
        val view = machine.attemptView()
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, view.phase)
        assertTrue(view.authorizeUrl != null)
        assertFalse(view.toString().contains("state="))
    }

    private fun callback(
        code: String = "code-value",
        state: String = "state-value"
    ): DshAuthCallbackRequest = DshAuthCallbackRequest(
        method = "GET",
        path = bounds.callbackPath,
        query = mapOf("code" to listOf(code), "state" to listOf(state))
    )

    private companion object {
        const val TRUSTED_ORIGIN = "http://127.0.0.1:25140"
        const val CALLBACK_ORIGIN = "http://127.0.0.1:25140"
        const val AUTHORIZE_URL =
            "https://platform.deepseek.com/dsh/authorize?state=s&code_challenge=c&code_challenge_method=S256" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A25140%2Foauth%2Fcallback&login_source=web"
    }

    @Test
    fun aCredentialStoreObservationAuthenticatesOnlyAfterTheHandoff() {
        val machine = machine()
        machine.requestSignIn(CALLBACK_ORIGIN, DshLoginSource.WEB)
        machine.onPopupWindowCreated(handle, TRUSTED_ORIGIN)
        // No authorize URL yet: a store change cannot belong to a handoff that never happened.
        assertEquals(
            DshHostAuthPhase.OPENING_POPUP,
            machine.onCredentialStoreObserved().phase
        )
        assertEquals(DshHostAuthPhase.WAITING_CALLBACK, machine.onAuthorizeUrl(handle, AUTHORIZE_URL).phase)
        val observed = machine.onCredentialStoreObserved()
        assertEquals(DshHostAuthPhase.AUTHENTICATED, observed.phase)
        assertNull(observed.failure)
        assertEquals(DshAuthAttemptPhase.SUCCEEDED, machine.attemptView().phase)
        // A later popup release must never re-label a committed success as a cancel.
        assertEquals(
            DshHostAuthPhase.AUTHENTICATED,
            machine.onOfficialPopupReleased().phase
        )
    }

}


