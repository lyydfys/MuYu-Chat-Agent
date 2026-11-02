package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthFailureCode
import com.muyuchat.core.dsh.contract.auth.DshBrowserHandoffResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D04: trusted pending-window seam and Android external-browser handoff.
 *
 * The card calls out two concrete defects of the reference behaviour: a blank popup destroyed after
 * ~1 s and a hard 5 s popup lifetime, while the official `auth_init` may answer much later. The
 * policy therefore keeps a bounded window for the whole attempt, never opens a blank page, opens
 * exactly the validated authorize URL once, and rejects a late URL after the window closed.
 */
class DshBrowserHandoffPolicyTest {

    private var now = 5_000L
    private val bounds = DshAuthAttemptBounds()
    private val deadline = now + bounds.defaultAttemptMillis
    private var opened = mutableListOf<String>()

    private fun policy(): DshBrowserHandoffPolicy = DshBrowserHandoffPolicy(bounds) { now }

    private fun launcher(result: DshBrowserHandoffResult = DshBrowserHandoffResult.Opened) =
        DshExternalBrowserLauncher { url ->
            opened += url
            result
        }

    // ------------------------------------------------------------------ reservation

    @Test
    fun anUntrustedOriginCannotReserveThePendingWindow() {
        val policy = policy()
        val reservation = policy.reserve("h1", "a1", "http://evil.example:8080", TRUSTED, deadline)
        assertTrue(reservation is DshWindowReservation.Rejected)
        assertEquals(
            DshAuthFailureCode.POPUP_ORIGIN_UNTRUSTED,
            (reservation as DshWindowReservation.Rejected).reason
        )
        assertTrue(reservation.closeWindow)
        assertEquals(0, policy.openWindowCount())
    }

    @Test
    fun aSecondReservationForTheSameAttemptReusesTheExistingWindow() {
        val policy = policy()
        val first = policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        val second = policy.reserve("h2", "a1", TRUSTED, TRUSTED, deadline)
        assertEquals(
            "h1",
            (first as DshWindowReservation.Reserved).handleId
        )
        assertEquals("h1", (second as DshWindowReservation.Reserved).handleId)
        assertTrue(second.reused)
        assertEquals(1, policy.openWindowCount())
    }

    @Test
    fun aWindowCannotBeReservedAfterItsAttemptDeadline() {
        val policy = policy()
        val reservation = policy.reserve("h1", "a1", TRUSTED, TRUSTED, now - 1)
        assertEquals(
            DshAuthFailureCode.ATTEMPT_TIMEOUT,
            (reservation as DshWindowReservation.Rejected).reason
        )
    }

    // ------------------------------------------------------------------ blank never opened

    @Test
    fun aBlankPendingWindowIsNeverHandedToTheBrowser() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        val outcome = policy.handOff("h1", launcher())
        assertTrue(outcome.result is DshBrowserHandoffResult.Rejected)
        assertEquals("blank_window_never_opened", (outcome.result as DshBrowserHandoffResult.Rejected).reason)
        assertNull(outcome.openedUrl)
        assertTrue("no browser may be opened for an about:blank handle", opened.isEmpty())
    }

    // ------------------------------------------------------------------ attach + handoff

    @Test
    fun theValidatedAuthorizeUrlIsOpenedExactlyOnceAndTheWindowIsClosed() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        assertEquals(DshAuthorizeAttachResult.Attached, policy.attachAuthorizeUrl("h1", AUTHORIZE_URL))
        val themed = AUTHORIZE_URL + "&theme=light"
        assertEquals(
            DshAuthorizeAttachResult.Attached,
            policy.attachAuthorizeUrl("h1", AUTHORIZE_URL, themed)
        )
        val outcome = policy.handOff("h1", launcher())
        assertEquals(DshBrowserHandoffResult.Opened, outcome.result)
        assertEquals(themed, outcome.openedUrl)
        assertEquals(listOf(themed), opened)

        val second = policy.handOff("h1", launcher())
        assertTrue(second.result is DshBrowserHandoffResult.Rejected)
        assertEquals("window_closed", (second.result as DshBrowserHandoffResult.Rejected).reason)
        assertEquals(1, opened.size)
    }

    @Test
    fun aNonThemeQueryChangeIsRejectedAndClosesTheWindowWithoutOpeningABrowser() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        val tampered = AUTHORIZE_URL + "&scope=admin"
        val attach = policy.attachAuthorizeUrl("h1", AUTHORIZE_URL, tampered)
        assertEquals(
            DshAuthFailureCode.AUTHORIZE_URL_INVALID,
            (attach as DshAuthorizeAttachResult.Rejected).reason
        )
        assertTrue(attach.closeWindow)
        val outcome = policy.handOff("h1", launcher())
        assertTrue(outcome.result is DshBrowserHandoffResult.Rejected)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aSecondDifferentAuthorizeUrlForTheSameAttemptIsAProtocolViolation() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        policy.attachAuthorizeUrl("h1", AUTHORIZE_URL)
        val other = AUTHORIZE_URL.replace("state=s", "state=other")
        val attach = policy.attachAuthorizeUrl("h1", other)
        assertEquals(
            DshAuthFailureCode.PROTOCOL_ERROR,
            (attach as DshAuthorizeAttachResult.Rejected).reason
        )
    }

    @Test
    fun aLateAuthorizeUrlAfterCancelIsRejectedAndCannotReopenTheBrowser() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        assertTrue(policy.close("h1", DshWindowCloseReason.CANCELLED))
        val attach = policy.attachAuthorizeUrl("h1", AUTHORIZE_URL)
        val rejected = attach as DshAuthorizeAttachResult.Rejected
        assertTrue(rejected.lateAfterClose)
        assertFalse(rejected.closeWindow)
        val outcome = policy.handOff("h1", launcher())
        assertTrue(outcome.result is DshBrowserHandoffResult.Rejected)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun anExpiredWindowIsClosedByTheExpirySweepAndItsLateUrlIsRejected() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, now + 10)
        now += 11
        assertEquals(listOf("h1"), policy.expire())
        val attach = policy.attachAuthorizeUrl("h1", AUTHORIZE_URL)
        assertTrue((attach as DshAuthorizeAttachResult.Rejected).lateAfterClose)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aWindowExpiringBetweenReservationAndAttachRejectsTheUrlAndCloses() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, now + 10)
        now += 11
        val attach = policy.attachAuthorizeUrl("h1", AUTHORIZE_URL)
        assertEquals(
            DshAuthFailureCode.ATTEMPT_TIMEOUT,
            (attach as DshAuthorizeAttachResult.Rejected).reason
        )
        assertEquals(0, policy.openWindowCount())
    }

    @Test
    fun aDeviceWithoutABrowserHandlerIsATypedFailureAndTheWindowCloses() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        policy.attachAuthorizeUrl("h1", AUTHORIZE_URL)
        val outcome = policy.handOff("h1", launcher(DshBrowserHandoffResult.NoHandler))
        assertEquals(DshBrowserHandoffResult.NoHandler, outcome.result)
        assertNull(outcome.openedUrl)
        assertTrue(outcome.closed)
        assertEquals(0, policy.openWindowCount())
    }

    @Test
    fun closeIsIdempotent() {
        val policy = policy()
        policy.reserve("h1", "a1", TRUSTED, TRUSTED, deadline)
        assertTrue(policy.close("h1", DshWindowCloseReason.TEARDOWN))
        assertFalse(policy.close("h1", DshWindowCloseReason.TEARDOWN))
        assertFalse(policy.close("unknown", DshWindowCloseReason.TEARDOWN))
    }

    // ------------------------------------------------------------------ URL policy

    @Test
    fun onlyLoopbackHttpCallbackOriginsWithAConcretePortAreAccepted() {
        assertEquals("http://127.0.0.1:8080", DshAuthUrlPolicy.normalizeCallbackOrigin("http://127.0.0.1:8080"))
        assertTrue(DshAuthUrlPolicy.isValidCallbackOrigin("http://[::1]:8080"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("http://localhost"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("http://127.0.0.1:8080/a"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("http://127.0.0.1:8080?x=1"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("http://u@127.0.0.1:8080"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("https://127.0.0.1:8080"))
        assertFalse(DshAuthUrlPolicy.isValidCallbackOrigin("http://192.168.0.2:8080"))
    }

    @Test
    fun theRedirectUriUsesTheFixedOfficialCallbackPath() {
        assertEquals(
            "http://127.0.0.1:8080/oauth/callback",
            DshAuthUrlPolicy.redirectUri("http://127.0.0.1:8080", bounds)
        )
        assertNull(DshAuthUrlPolicy.redirectUri("http://127.0.0.1:8080/x", bounds))
    }

    @Test
    fun theAuthorizeUrlMustBeTheOfficialOriginPathWithoutUserInfoOrFragment() {
        assertTrue(DshAuthUrlPolicy.isValidAuthorizeUrl(AUTHORIZE_URL, bounds))
        assertFalse(
            DshAuthUrlPolicy.isValidAuthorizeUrl(
                "https://platform.deepseek.com/dsh/authorized?x=1",
                bounds
            )
        )
        assertFalse(
            DshAuthUrlPolicy.isValidAuthorizeUrl(
                "https://platform.deepseek.com/dsh/authorize?x=1#f",
                bounds
            )
        )
        assertFalse(
            DshAuthUrlPolicy.isValidAuthorizeUrl(
                "https://a:b@platform.deepseek.com/dsh/authorize?x=1",
                bounds
            )
        )
    }

    @Test
    fun onlyTheSingleThemeParameterMayDifferFromTheOfficialAuthorizeUrl() {
        assertTrue(
            DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(
                AUTHORIZE_URL,
                AUTHORIZE_URL + "&theme=dark",
                bounds
            )
        )
        assertTrue(
            DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(
                AUTHORIZE_URL,
                AUTHORIZE_URL.replace("state=s", "state=s&theme=dark"),
                bounds
            )
        )
        assertFalse(
            "a second theme value is not the official conversion",
            DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(
                AUTHORIZE_URL,
                AUTHORIZE_URL + "&theme=dark&theme=light",
                bounds
            )
        )
        assertFalse(
            DshAuthUrlPolicy.matchesOriginalAuthorizeUrl(
                AUTHORIZE_URL,
                AUTHORIZE_URL + "&theme=dark&state=other",
                bounds
            )
        )
    }

    private companion object {
        const val TRUSTED = "http://127.0.0.1:25140"
        const val AUTHORIZE_URL =
            "https://platform.deepseek.com/dsh/authorize?state=s&code_challenge=c" +
                "&code_challenge_method=S256&login_source=web"
    }
}
