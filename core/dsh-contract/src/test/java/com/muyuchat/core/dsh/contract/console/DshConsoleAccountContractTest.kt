package com.muyuchat.core.dsh.contract.console

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded account attempt mapping (D20).
 *
 * The official module owns the attempt and the credential; the console projection may only observe
 * the host attempt view. These tests pin the display-safe mapping: waiting/terminal states exist,
 * a success is never claimed, the age stays bounded, and no URL/token can reach the projection.
 */
class DshConsoleAccountContractTest {

    @Test
    fun noAttemptProjectsNotStartedWithoutAnAge() {
        val projection = DshConsoleAccountProjection.from(view(DshAuthAttemptPhase.IDLE), 9_000L)

        assertEquals(DshConsoleAccountState.NOT_STARTED, projection.state)
        assertEquals(DshConsoleAccountReason.NONE, projection.reason)
        assertEquals("an idle attempt has no observed age", 0L, projection.attemptAgeMillis)
    }

    @Test
    fun aMissingSourceIsTypedUnavailableAndNotNotStarted() {
        val projection = DshConsoleAccountProjection.from(view = null, attemptAgeMillis = 0L)

        assertEquals(DshConsoleAccountState.UNKNOWN, projection.state)
        assertEquals(DshConsoleAccountReason.SOURCE_UNAVAILABLE, projection.reason)
        assertEquals(0L, projection.attemptAgeMillis)
    }

    @Test
    fun initializingWaitsForTheBrowserWithoutClaimingSuccess() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.INITIALIZING),
            attemptAgeMillis = 2_000L
        )

        assertEquals(DshConsoleAccountState.WAITING_BROWSER, projection.state)
        assertEquals(DshConsoleAccountReason.AUTHORIZE_URL_PENDING, projection.reason)
        assertEquals(2_000L, projection.attemptAgeMillis)
    }

    @Test
    fun aHandedOffAuthorizeUrlWaitsForTheCallbackWithoutClaimingSuccess() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.WAITING_BROWSER),
            attemptAgeMillis = 30_000L
        )

        assertEquals(DshConsoleAccountState.WAITING_CALLBACK, projection.state)
        assertEquals(DshConsoleAccountReason.CALLBACK_PENDING, projection.reason)
        assertEquals(30_000L, projection.attemptAgeMillis)
    }

    @Test
    fun theOfficialExchangeStaysWaitingCallbackBecauseTheAppCannotSeeTheCommit() {
        listOf(DshAuthAttemptPhase.EXCHANGING, DshAuthAttemptPhase.COMMITTING).forEach { phase ->
            val projection = DshConsoleAccountProjection.from(view(phase), 45_000L)

            assertEquals(DshConsoleAccountState.WAITING_CALLBACK, projection.state)
            assertEquals(DshConsoleAccountReason.OFFICIAL_EXCHANGE_PENDING, projection.reason)
        }
    }

    @Test
    fun anObservedCredentialStoreWriteIsProjectedAsPresenceOnlySignedIn() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.SUCCEEDED),
            attemptAgeMillis = 10_000L
        )

        // The owner publishes SUCCEEDED only after the official credential store changed on disk,
        // so the console can show presence. It still says nothing about which account or token.
        assertEquals(DshConsoleAccountState.SIGNED_IN, projection.state)
        assertEquals(DshConsoleAccountReason.CREDENTIAL_STORE_PRESENT, projection.reason)
        assertEquals("a committed attempt carries no live attempt age", 0L, projection.attemptAgeMillis)
        assertFalse(
            "identity-level states stay out of the console vocabulary",
            DshConsoleAccountState.entries.any {
                it.name == "AUTHENTICATED" || it.name == "LOGGED_IN"
            }
        )
    }

    @Test
    fun theExpiredDeadlineIsATimeoutNotATokenExpiry() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.EXPIRED, errorCode = "ATTEMPT_TIMEOUT"),
            attemptAgeMillis = 600_000L
        )

        assertEquals(DshConsoleAccountState.TIMED_OUT, projection.state)
        assertEquals(DshConsoleAccountReason.ATTEMPT_TIMEOUT, projection.reason)
        assertEquals(600_000L, projection.attemptAgeMillis)
    }

    @Test
    fun aCancelledAttemptStaysCancelled() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.CANCELLED, errorCode = "ATTEMPT_CANCELLED"),
            3_000L
        )

        assertEquals(DshConsoleAccountState.CANCELLED, projection.state)
        assertEquals(DshConsoleAccountReason.ATTEMPT_CANCELLED, projection.reason)
    }

    @Test
    fun aLostRuntimeGenerationIsAFailedAttemptWithALostGenerationReason() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.FAILED, errorCode = "PROCESS_RESTARTED"),
            4_000L
        )

        assertEquals(DshConsoleAccountState.FAILED, projection.state)
        assertEquals(DshConsoleAccountReason.RUNTIME_GENERATION_LOST, projection.reason)
    }

    @Test
    fun aRestoredUnknownAttemptIsUnknownAndNeverSucceeded() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.OUTCOME_UNKNOWN, errorCode = "PROCESS_RESTARTED"),
            0L
        )

        assertEquals(DshConsoleAccountState.UNKNOWN, projection.state)
        assertEquals(DshConsoleAccountReason.RUNTIME_GENERATION_LOST, projection.reason)
    }

    @Test
    fun anUnrecognizedFailureCodeDoesNotLeakOwnerText() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.FAILED, errorCode = "https://evil.example/?code=SECRET"),
            1_000L
        )

        assertEquals(DshConsoleAccountState.FAILED, projection.state)
        assertEquals(DshConsoleAccountReason.OTHER_FAILURE, projection.reason)
        assertFalse(projection.toString().contains("evil"))
        assertFalse(projection.toString().contains("SECRET"))
    }

    @Test
    fun attemptAgeIsBoundedIntoTheOfficialAttemptWindow() {
        val negative = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.WAITING_BROWSER),
            attemptAgeMillis = -1L
        )
        val tooLarge = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.WAITING_BROWSER),
            attemptAgeMillis = Long.MAX_VALUE
        )

        assertEquals(0L, negative.attemptAgeMillis)
        assertEquals(
            DshConsoleAccountProjection.MAX_ATTEMPT_AGE_MILLIS,
            tooLarge.attemptAgeMillis
        )
        assertTrue(tooLarge.attemptAgeMillis <= 3_600_000L)
    }

    @Test
    fun aNotStartedProjectionCannotCarryAnAge() {
        try {
            DshConsoleAccountProjection(
                state = DshConsoleAccountState.NOT_STARTED,
                attemptAgeMillis = 1_000L,
                reason = DshConsoleAccountReason.NONE
            )
            throw AssertionError("a not-started attempt must reject an age")
        } catch (expected: IllegalArgumentException) {
            // Contract invariant: idle means no measured attempt.
        }
    }

    @Test
    fun aWaitingProjectionNeedsATypedReasonAndTheSourceReasonIsAlwaysUnknown() {
        try {
            DshConsoleAccountProjection(
                state = DshConsoleAccountState.WAITING_BROWSER,
                attemptAgeMillis = 1_000L,
                reason = DshConsoleAccountReason.NONE
            )
            throw AssertionError("a waiting state must carry a typed reason")
        } catch (expected: IllegalArgumentException) {
            // Contract invariant: a started attempt always has a typed reason.
        }
        try {
            DshConsoleAccountProjection(
                state = DshConsoleAccountState.WAITING_CALLBACK,
                attemptAgeMillis = 1_000L,
                reason = DshConsoleAccountReason.SOURCE_UNAVAILABLE
            )
            throw AssertionError("a missing source cannot look like a live attempt")
        } catch (expected: IllegalArgumentException) {
            // Contract invariant: no source is always UNKNOWN, never WAITING_*.
        }
    }

    @Test
    fun theProjectionAndItsStatesCarryNoUrlOrCredentialSurface() {
        val projection = DshConsoleAccountProjection.from(
            view(DshAuthAttemptPhase.WAITING_BROWSER),
            12_000L
        )
        val text = listOf(
            projection.toString(),
            DshConsoleAccountState.entries.joinToString(" "),
            DshConsoleAccountReason.entries.joinToString(" ")
        ).joinToString(" ")

        assertFalse(text.contains("http", ignoreCase = true))
        assertFalse(text.contains("?"))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("code", ignoreCase = true))
        assertFalse(text.contains("cookie", ignoreCase = true))
    }

    private fun view(
        phase: DshAuthAttemptPhase,
        errorCode: String? = null
    ): DshAuthAttemptView = DshAuthAttemptView(
        attemptId = if (phase == DshAuthAttemptPhase.IDLE) "" else "attempt-1",
        phase = phase,
        authorizeUrl = null,
        expiresAtEpochMillis = null,
        errorCode = errorCode
    )
}
