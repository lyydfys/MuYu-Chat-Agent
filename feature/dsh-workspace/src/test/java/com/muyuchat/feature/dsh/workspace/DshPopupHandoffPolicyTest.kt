package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupRejection
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPopupHandoffPolicyTest {

    private val url = "https://example.test/authorize?state=1"

    @Test
    fun handedOffAccountPopupTellsTheUserToFinishSignIn() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.HandedOff
        ) as DshPopupOutcome.Notify

        assertTrue(outcome.text.contains("账号"))
    }

    @Test
    fun handedOffNormalLinkReportsOpening() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.NORMAL_EXTERNAL,
            url,
            DshPopupHandoffResult.HandedOff
        ) as DshPopupOutcome.Notify

        assertTrue(outcome.text.contains("外部链接"))
    }

    @Test
    fun userCancellationIsSilent() {
        assertEquals(
            DshPopupOutcome.Silent,
            DshPopupHandoffPolicy.outcomeFor(
                DshPopupKind.ACCOUNT_AUTH,
                url,
                DshPopupHandoffResult.Cancelled
            )
        )
    }

    @Test
    fun expiredTicketNeverReopensTheUrl() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Expired
        )

        assertTrue(outcome is DshPopupOutcome.Notify)
        assertTrue((outcome as DshPopupOutcome.Notify).text.contains("超时"))
    }

    @Test
    fun accountPopupWithoutABrowserNeverFallsBackToAnIntent() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER)
        )

        assertTrue(outcome is DshPopupOutcome.Notify)
    }

    @Test
    fun normalPopupWithoutABrowserMayFallBackToTheHostBrowserPath() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.NORMAL_EXTERNAL,
            url,
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.NO_BROWSER_HANDLER)
        )

        assertEquals(DshPopupOutcome.FallbackExternal(url), outcome)
    }

    @Test
    fun disabledAccountBrowserRefusesInsteadOfFallingBack() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.FEATURE_DISABLED)
        )

        assertTrue(outcome is DshPopupOutcome.Notify)
    }

    @Test
    fun staleOrMismatchedTicketsAreReportedWithoutOpening() {
        val stale = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Rejected(DshPopupRejection.STALE_REQUEST)
        )
        val mismatch = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Rejected(DshPopupRejection.URL_MISMATCH)
        )
        val replayed = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.NORMAL_EXTERNAL,
            url,
            DshPopupHandoffResult.Rejected(DshPopupRejection.REPLAYED)
        )

        assertTrue(stale is DshPopupOutcome.Notify)
        assertTrue(mismatch is DshPopupOutcome.Notify)
        assertTrue(replayed is DshPopupOutcome.Notify)
    }

    @Test
    fun backgroundPresenceRequirementIsExplained() {
        val outcome = DshPopupHandoffPolicy.outcomeFor(
            DshPopupKind.ACCOUNT_AUTH,
            url,
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.USER_PRESENCE_REQUIRED)
        ) as DshPopupOutcome.Notify

        assertTrue(outcome.text.contains("回到应用"))
    }

    @Test
    fun noOutcomeEchoesTheAuthorizeUrl() {
        val outcomes = listOf(
            DshPopupHandoffResult.HandedOff,
            DshPopupHandoffResult.Expired,
            DshPopupHandoffResult.Unavailable(DshPopupUnavailable.ACCOUNT_ATTEMPT_UNAVAILABLE)
        ).map { DshPopupHandoffPolicy.outcomeFor(DshPopupKind.ACCOUNT_AUTH, url, it) }

        outcomes.forEach { outcome ->
            val text = (outcome as? DshPopupOutcome.Notify)?.text.orEmpty()
            assertTrue(!text.contains("state=1") && !text.contains("example.test"))
        }
    }
}
