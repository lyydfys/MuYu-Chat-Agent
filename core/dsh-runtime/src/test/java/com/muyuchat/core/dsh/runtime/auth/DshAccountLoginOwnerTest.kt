package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import com.muyuchat.core.dsh.contract.auth.DshUnauthorizedScope
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import com.muyuchat.core.dsh.contract.console.DshPopupRejection
import com.muyuchat.core.dsh.contract.console.DshPopupRequest
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D03/D04/D05: the production account-login correlation lane.
 *
 * Grounding: the pinned official `dsh-deepseek-account-platform` module owns the attempt, PKCE,
 * `auth_init`, the fixed `GET /oauth/callback` route, the exchange and the credential store. This
 * lane only owns the Android host additions: one bounded attempt bound to the trusted pre-opened
 * window, the correlated browser handoff, the durable "outcome unknown" marker and the typed
 * terminal states. Every test asserts that a success is never published without evidence.
 */
class DshAccountLoginOwnerTest {

    private var now = 1_000_000L
    private var origin: String? = TRUSTED_ORIGIN
    private val bounds = DshAuthAttemptBounds()

    private class FakeJournal(
        var marker: DshAccountAttemptMarker? = null
    ) : DshAccountAttemptJournal {
        override fun load(): DshAccountAttemptMarker? = marker
        override fun save(marker: DshAccountAttemptMarker) {
            this.marker = marker
        }

        override fun clear() {
            marker = null
        }
    }

    private class FakeLauncher(
        var result: DshBrowserHandoffResult = DshBrowserHandoffResult.Opened
    ) : DshExternalBrowserLauncher {
        val opened = mutableListOf<String>()

        override fun open(url: String): DshBrowserHandoffResult {
            opened += url
            return result
        }
    }

    private class FakeExternalPort : DshExternalBrowserPort {
        val requests = mutableListOf<DshPopupRequest>()
        var result: DshPopupHandoffResult = DshPopupHandoffResult.HandedOff

        override suspend fun openPopup(request: DshPopupRequest): DshPopupHandoffResult {
            requests += request
            return result
        }
    }

    private fun owner(
        scope: kotlinx.coroutines.CoroutineScope,
        journal: DshAccountAttemptJournal = FakeJournal(),
        launcher: FakeLauncher = FakeLauncher(),
        external: FakeExternalPort = FakeExternalPort(),
        attemptBounds: DshAuthAttemptBounds = bounds,
        credentialFingerprint: () -> String? = { null }
    ): DshAccountLoginOwner = DshAccountLoginOwner(
        scope = scope,
        callbackOrigin = { origin },
        trustedWebOrigin = { origin },
        launcher = launcher,
        externalFallback = external,
        journal = journal,
        bounds = attemptBounds,
        clock = { now },
        credentialFingerprint = credentialFingerprint
    )

    private fun popupRequest(
        url: String,
        kind: DshPopupKind = DshPopupKind.ACCOUNT_AUTH,
        createdAtMillis: Long = now,
        deadlineAtMillis: Long = now + 60_000L
    ): DshPopupRequest = DshPopupRequest(
        ticketId = 1L,
        url = url,
        kind = kind,
        createdAtMillis = createdAtMillis,
        deadlineAtMillis = deadlineAtMillis
    )

    // ------------------------------------------------------------------ correlation happy path

    @Test
    fun aPreopenedAuthorizePopupIsCorrelatedWithTheAttemptAndOpenedInTheSystemBrowser() = runTest {
        val launcher = FakeLauncher()
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)
        assertTrue("the official authorize URL belongs to the live attempt", owner.isAccountAuthUrl(AUTHORIZE_URL))

        val result = owner.openPopup(popupRequest(AUTHORIZE_URL))

        assertEquals(DshPopupHandoffResult.HandedOff, result)
        assertEquals(listOf(AUTHORIZE_URL), launcher.opened)
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)
        assertNull(owner.attemptView().errorCode)
        // The attempt is journaled while it is still open, so a killed process can restore it.
        assertTrue(journal.marker?.active == true)
    }

    @Test
    fun noLiveAttemptMeansNoCorrelationAndNoBrowserLaunch() = runTest {
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, launcher = launcher)

        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
        assertEquals(
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.ACCOUNT_ATTEMPT_UNAVAILABLE),
            owner.openPopup(popupRequest(AUTHORIZE_URL))
        )
        // An ordinary link keeps the existing container behavior (delegated to the fallback port).
        assertEquals(
            DshPopupHandoffResult.HandedOff,
            owner.openPopup(popupRequest(AUTHORIZE_URL, DshPopupKind.NORMAL_EXTERNAL))
        )
        assertTrue("an account URL is never opened without a live attempt", launcher.opened.isEmpty())
    }

    @Test
    fun aPopupFromAnUntrustedOriginOrWithoutARuntimeOriginNeverStartsAnAttempt() = runTest {
        val owner = owner(backgroundScope)
        owner.onPopupPreopened("http://evil.example:8080")
        assertEquals(DshAuthAttemptPhase.IDLE, owner.attemptView().phase)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))

        origin = null
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.IDLE, owner.attemptView().phase)
    }

    @Test
    fun anAuthorizeUrlFromAnotherAttemptIsRejectedWithoutOpeningAnything() = runTest {
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, launcher = launcher)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshPopupHandoffResult.HandedOff, owner.openPopup(popupRequest(AUTHORIZE_URL)))

        val tampered = AUTHORIZE_URL + "&redirect_uri=http%3A%2F%2Fevil.example%2Fcb"
        assertFalse(owner.isAccountAuthUrl(tampered))
        assertEquals(
            DshPopupHandoffResult.Rejected(DshPopupRejection.URL_MISMATCH),
            owner.openPopup(popupRequest(tampered))
        )
        assertEquals("only the validated URL was ever opened", listOf(AUTHORIZE_URL), launcher.opened)
    }

    @Test
    fun aSecondPreopenWhileTheAttemptWaitsReusesTheSameAttemptInsteadOfStartingAnother() = runTest {
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, launcher = launcher)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))
        val attemptId = owner.attemptView().attemptId

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(attemptId, owner.attemptView().attemptId)
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)

        // Reopening the very same authorize page is allowed; a different URL is not.
        assertEquals(DshPopupHandoffResult.HandedOff, owner.openPopup(popupRequest(AUTHORIZE_URL)))
        assertEquals(listOf(AUTHORIZE_URL, AUTHORIZE_URL), launcher.opened)
    }

    // ------------------------------------------------------------------ cancel

    @Test
    fun cancellingThePreopenedWindowEndsTheAttemptWithoutClaimingSuccess() = runTest {
        val launcher = FakeLauncher()
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)

        owner.onPopupAbandoned()

        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertEquals("ATTEMPT_CANCELLED", owner.attemptView().errorCode)
        assertNull("a cancelled attempt leaves nothing to recover", journal.marker)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
        // A late authorize URL can never be opened any more.
        assertTrue(
            owner.openPopup(popupRequest(AUTHORIZE_URL)) !is DshPopupHandoffResult.HandedOff
        )
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun aBrowserWithoutAHandlerIsATypedFailureAndEndsTheAttempt() = runTest {
        val launcher = FakeLauncher(result = DshBrowserHandoffResult.NoHandler)
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)
        owner.onPopupPreopened(TRUSTED_ORIGIN)

        val result = owner.openPopup(popupRequest(AUTHORIZE_URL))

        assertEquals(
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER),
            result
        )
        assertEquals(DshAuthAttemptPhase.FAILED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.BROWSER_HANDLER_MISSING.name, owner.attemptView().errorCode)
        assertNull(journal.marker)
    }

    // ------------------------------------------------------------------ official cancel (popup released)

    @Test
    fun theOfficialPageReleasingTheHandedOffWindowCancelsTheAttemptAndClaimsNoSuccess() = runTest {
        val launcher = FakeLauncher()
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshPopupHandoffResult.HandedOff, owner.openPopup(popupRequest(AUTHORIZE_URL)))
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)
        assertTrue("a handed-off attempt keeps its recovery marker", journal.marker?.active == true)

        owner.onOfficialPopupReleased()

        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertEquals(
            DshAuthFailureCode.OFFICIAL_POPUP_RELEASED.name,
            owner.attemptView().errorCode
        )
        assertNull("a cancelled attempt leaves nothing to recover", journal.marker)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
        // Only the one validated handoff ever reached the browser; the cancel opens nothing.
        assertEquals(listOf(AUTHORIZE_URL), launcher.opened)
        // A late authorize URL or a replayed popup can never be opened again.
        assertTrue(owner.openPopup(popupRequest(AUTHORIZE_URL)) !is DshPopupHandoffResult.HandedOff)
        // The deadline ticker has nothing left to observe.
        assertFalse(owner.tick(now))
        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        // A repeated release is not a second decision and cannot turn into a success.
        owner.onOfficialPopupReleased()
        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertEquals(
            DshAuthFailureCode.OFFICIAL_POPUP_RELEASED.name,
            owner.attemptView().errorCode
        )
    }

    @Test
    fun aReleaseAfterTheDeadlineKeepsTheTimeoutAsTheTerminalFact() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))

        now += bounds.defaultAttemptMillis
        owner.onOfficialPopupReleased()

        assertEquals(DshAuthAttemptPhase.EXPIRED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.ATTEMPT_TIMEOUT.name, owner.attemptView().errorCode)
        assertNull(journal.marker)
    }

    @Test
    fun aReleaseWithoutALiveAttemptIsIgnoredAndCannotRelabelATerminalOne() = runTest {
        val owner = owner(backgroundScope)
        // Nothing was ever started: the fact is dropped instead of inventing an attempt.
        owner.onOfficialPopupReleased()
        assertEquals(DshAuthAttemptPhase.IDLE, owner.attemptView().phase)

        // A locally cancelled attempt keeps its own cause; the release may not overwrite it.
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.onPopupAbandoned()
        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.ATTEMPT_CANCELLED.name, owner.attemptView().errorCode)
        owner.onOfficialPopupReleased()
        assertEquals(DshAuthFailureCode.ATTEMPT_CANCELLED.name, owner.attemptView().errorCode)
    }

    @Test
    fun aRestoredProcessAttemptIgnoresTheReleaseAndStaysUnprovable() = runTest {
        val owner = owner(
            backgroundScope,
            journal = FakeJournal(
                DshAccountAttemptMarker(
                    attemptId = "attempt-from-killed-process",
                    active = true,
                    updatedAtEpochMillis = now
                )
            )
        )
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, owner.attemptView().phase)

        owner.onOfficialPopupReleased()

        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED.name, owner.attemptView().errorCode)
    }

    @Test
    fun aLostRuntimeGenerationWinsOverALateRelease() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))

        origin = null
        assertFalse(owner.tick(now))
        assertEquals(DshAuthAttemptPhase.FAILED, owner.attemptView().phase)

        owner.onOfficialPopupReleased()

        assertEquals(DshAuthAttemptPhase.FAILED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED.name, owner.attemptView().errorCode)
    }

    @Test
    fun anAccountScoped401DoesNotConsumeTheCancelSignal() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))

        assertEquals(
            DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY,
            owner.onUnauthorized(DshUnauthorizedScope.INFERENCE, 401)
        )
        assertEquals("a 401 never invents a cancel", DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)

        owner.onOfficialPopupReleased()

        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertEquals(
            DshAuthFailureCode.OFFICIAL_POPUP_RELEASED.name,
            owner.attemptView().errorCode
        )
    }

    @Test
    fun aNewSignInAfterTheOfficialCancelStartsAFreshAttempt() = runTest {
        val owner = owner(backgroundScope)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))
        val cancelledAttemptId = owner.attemptView().attemptId
        owner.onOfficialPopupReleased()

        now += 1_000L
        owner.onPopupPreopened(TRUSTED_ORIGIN)

        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)
        assertTrue(owner.attemptView().attemptId != cancelledAttemptId)
        assertTrue("the fresh attempt may hand off again", owner.isAccountAuthUrl(AUTHORIZE_URL))
    }

    // ------------------------------------------------------------------ timeout

    @Test
    fun theAttemptDeadlineExpiresTheCorrelationAndClearsTheRecoveryMarker() = runTest {
        val journal = FakeJournal()
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)

        now += bounds.defaultAttemptMillis

        assertFalse("the ticker stops once the attempt is terminal", owner.tick(now))
        assertEquals(DshAuthAttemptPhase.EXPIRED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.ATTEMPT_TIMEOUT.name, owner.attemptView().errorCode)
        assertNull(journal.marker)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
        assertTrue(owner.openPopup(popupRequest(AUTHORIZE_URL)) !is DshPopupHandoffResult.HandedOff)
        assertTrue("a timed-out attempt never opens a browser", launcher.opened.isEmpty())
    }

    @Test
    fun anExpiredHiddenWindowTicketIsReportedAsExpiredAndNeverOpensTheBrowser() = runTest {
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, launcher = launcher)
        owner.onPopupPreopened(TRUSTED_ORIGIN)

        val stale = popupRequest(
            AUTHORIZE_URL,
            createdAtMillis = now - 2_000L,
            deadlineAtMillis = now - 1_000L
        )

        assertEquals(DshPopupHandoffResult.Expired, owner.openPopup(stale))
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun theHiddenWindowBudgetFollowsTheLiveAttemptAndStaysInsideThePopupLimit() = runTest {
        val shortAttempt = DshAuthAttemptBounds(
            defaultAttemptMillis = 60_000L,
            maxAttemptMillis = 120_000L
        )
        val owner = owner(backgroundScope, attemptBounds = shortAttempt)
        assertEquals(0L, owner.handoffBudgetMillis())

        owner.onPopupPreopened(TRUSTED_ORIGIN)

        val budget = owner.handoffBudgetMillis()
        assertEquals("the hold budget is the attempt's remaining time", 60_000L, budget)
        assertTrue(
            "a hidden native window may never outlive the popup limit",
            budget <= DshPopupLimits.MAX_LIFETIME_MILLIS
        )
        // The budget shrinks with the attempt clock.
        now += 30_000L
        assertEquals(30_000L, owner.handoffBudgetMillis())
    }

    @Test
    fun theHoldBudgetIsClampedToThePopupLimitForTheDefaultTenMinuteAttempt() = runTest {
        val owner = owner(backgroundScope)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshPopupLimits.MAX_LIFETIME_MILLIS, owner.handoffBudgetMillis())
    }

    // ------------------------------------------------------------------ process restart

    @Test
    fun aKilledProcessRestoresTheAttemptAsUnknownAndNeverAsSuccess() = runTest {
        val journal = FakeJournal(
            DshAccountAttemptMarker(
                attemptId = "attempt-from-killed-process",
                active = true,
                updatedAtEpochMillis = now
            )
        )
        val launcher = FakeLauncher()
        val owner = owner(backgroundScope, journal = journal, launcher = launcher)

        val view = owner.attemptView()
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, view.phase)
        assertEquals("attempt-from-killed-process", view.attemptId)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED.name, view.errorCode)
        assertFalse("an unknown outcome is not a live attempt", owner.isAccountAuthUrl(AUTHORIZE_URL))
        assertTrue(owner.openPopup(popupRequest(AUTHORIZE_URL)) !is DshPopupHandoffResult.HandedOff)
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun aUserInitiatedSignInSupersedesAnUnprovablePreviousOutcome() = runTest {
        val journal = FakeJournal(
            DshAccountAttemptMarker(
                attemptId = "attempt-from-killed-process",
                active = true,
                updatedAtEpochMillis = now
            )
        )
        val owner = owner(backgroundScope, journal = journal)
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, owner.attemptView().phase)

        now += 1_000L
        owner.onPopupPreopened(TRUSTED_ORIGIN)

        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)
        assertTrue(owner.attemptView().attemptId != "attempt-from-killed-process")
        assertTrue(owner.isAccountAuthUrl(AUTHORIZE_URL))
    }

    @Test
    fun aRuntimeStopEndsTheAttemptWithoutCompletionAndClearsTheRecoveryMarker() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)

        origin = null

        assertFalse(owner.tick(now))
        assertEquals(DshAuthAttemptPhase.FAILED, owner.attemptView().phase)
        assertEquals(DshAuthFailureCode.PROCESS_RESTARTED.name, owner.attemptView().errorCode)
        assertNull(journal.marker)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
    }

    @Test
    fun aNewRuntimeGenerationNeverInheritsTheOldAttemptOrItsOutcome() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))

        // The DSH host restarted with a new random port: the old attempt lived in the old host.
        origin = "http://127.0.0.1:31234"

        assertEquals(DshAuthAttemptPhase.IDLE, owner.attemptView().phase)
        assertNull(journal.marker)
        assertFalse(owner.isAccountAuthUrl(AUTHORIZE_URL))
    }

    // ------------------------------------------------------------------ HTTP 401

    @Test
    fun aWebSession401NeverSignsOutTheAccountOrDropsTheCorrelatedAttempt() = runTest {
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal)
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))

        owner.onWebSessionUnauthorized()

        assertEquals(
            DshAccountUnauthorizedOutcome.NOT_ACCOUNT_SCOPE,
            owner.onUnauthorized(DshUnauthorizedScope.WEB_COOKIE, 401)
        )
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)
        assertTrue(journal.marker?.active == true)
    }

    @Test
    fun aPlatform401IsClassifiedWithoutClaimingSignOutOrDeletingTheGrant() = runTest {
        val owner = owner(backgroundScope)
        owner.onPopupPreopened(TRUSTED_ORIGIN)

        assertEquals(
            "a model-listing 401 is not identity loss",
            DshAccountUnauthorizedOutcome.NOT_IDENTITY_LOSS,
            owner.onUnauthorized(DshUnauthorizedScope.MODEL_LISTING, 401)
        )
        assertEquals(
            "MCA holds no credential record, so only the official store may invalidate it",
            DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY,
            owner.onUnauthorized(DshUnauthorizedScope.INFERENCE, 401)
        )
        assertEquals(
            DshAccountUnauthorizedOutcome.OFFICIAL_STORE_ONLY,
            owner.onUnauthorized(DshUnauthorizedScope.PROFILE, 200, officialCode = 40_003)
        )
        assertEquals(
            DshAccountUnauthorizedOutcome.NOT_UNAUTHORIZED,
            owner.onUnauthorized(DshUnauthorizedScope.BALANCE, 500)
        )
        assertEquals(
            "the MCA gateway bearer is not the account credential",
            DshAccountUnauthorizedOutcome.NOT_ACCOUNT_SCOPE,
            owner.onUnauthorized(DshUnauthorizedScope.MCA_GATEWAY_BEARER, 401)
        )
        // No rejection above may have invented a signed-in or signed-out terminal state.
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)
    }

    // ------------------------------------------------------------------ ordinary links

    @Test
    fun anOrdinaryExternalPopupReleasesTheProvisionalAttemptAndKeepsTheFallbackBehavior() = runTest {
        val journal = FakeJournal()
        val external = FakeExternalPort()
        val owner = owner(backgroundScope, journal = journal, external = external)

        // Every window creation reports a pre-open; an ordinary link must release it again.
        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)

        val result = owner.openPopup(
            popupRequest("https://example.test/document", DshPopupKind.NORMAL_EXTERNAL)
        )

        assertEquals(DshPopupHandoffResult.HandedOff, result)
        assertEquals(1, external.requests.size)
        assertEquals(DshAuthAttemptPhase.CANCELLED, owner.attemptView().phase)
        assertNull(journal.marker)
    }

    private companion object {
        const val TRUSTED_ORIGIN = "http://127.0.0.1:25140"
        const val AUTHORIZE_URL =
            "https://platform.deepseek.com/dsh/authorize?state=s&code_challenge=c" +
                "&code_challenge_method=S256&redirect_uri=http%3A%2F%2F127.0.0.1%3A25140%2Foauth" +
                "%2Fcallback&login_source=web"
    }

    // ------------------------------------------------- credential-store success observation

    @Test
    fun aCredentialStoreChangeAfterHandoffPublishesSignedInAndClearsTheJournal() = runTest {
        val journal = FakeJournal()
        var fingerprint: String? = null
        val owner = owner(backgroundScope, journal = journal, credentialFingerprint = { fingerprint })

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        assertEquals(DshAuthAttemptPhase.INITIALIZING, owner.attemptView().phase)
        owner.openPopup(popupRequest(AUTHORIZE_URL))
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)

        fingerprint = "42:2000"
        now += 1_000L
        assertFalse("a signed-in attempt stops the deadline ticker", owner.tick(now))
        assertEquals(DshAuthAttemptPhase.SUCCEEDED, owner.attemptView().phase)
        assertNull(owner.attemptView().errorCode)
        assertNull("a terminal success clears the recovery marker", journal.marker)
    }

    @Test
    fun anUnchangedPreexistingCredentialStoreIsNotANewSuccess() = runTest {
        var fingerprint: String? = "42:1000"
        val journal = FakeJournal()
        val owner = owner(backgroundScope, journal = journal, credentialFingerprint = { fingerprint })

        owner.onPopupPreopened(TRUSTED_ORIGIN)
        owner.openPopup(popupRequest(AUTHORIZE_URL))
        now += 1_000L
        assertTrue("the attempt keeps waiting", owner.tick(now))
        assertEquals(DshAuthAttemptPhase.WAITING_BROWSER, owner.attemptView().phase)
        assertTrue(journal.marker?.active == true)
    }

    @Test
    fun aRestoredAttemptResolvesToSignedInOnlyWhenTheStoreChanged() = runTest {
        var fingerprint: String? = "42:2000"
        val journal = FakeJournal(
            DshAccountAttemptMarker(
                attemptId = "attempt-restored",
                active = true,
                updatedAtEpochMillis = 900L,
                credentialFingerprintAtStart = "42:1000"
            )
        )
        val owner = owner(backgroundScope, journal = journal, credentialFingerprint = { fingerprint })
        assertEquals(DshAuthAttemptPhase.SUCCEEDED, owner.attemptView().phase)
        assertNull(journal.marker)
    }

    @Test
    fun aRestoredAttemptWithAnUnchangedStoreStaysUnknown() = runTest {
        var fingerprint: String? = "42:1000"
        val journal = FakeJournal(
            DshAccountAttemptMarker(
                attemptId = "attempt-restored",
                active = true,
                updatedAtEpochMillis = 900L,
                credentialFingerprintAtStart = "42:1000"
            )
        )
        val owner = owner(backgroundScope, journal = journal, credentialFingerprint = { fingerprint })
        assertEquals(DshAuthAttemptPhase.OUTCOME_UNKNOWN, owner.attemptView().phase)
        assertTrue("an unproven outcome keeps its marker", journal.marker?.active == true)
    }

}
