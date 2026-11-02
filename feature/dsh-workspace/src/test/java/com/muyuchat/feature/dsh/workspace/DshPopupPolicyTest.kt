package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPopupPolicyTest {

    private val origin = DshOrigin("http://127.0.0.1:34567")
    private val noAccount = DshAccountAuthUrlMatcher { false }
    private val accountUrl = "https://account.example.test/oauth/authorize?state=abc"

    @Test
    fun sameOriginPopupKeepsTheOfficialPage() {
        val result = DshPopupPolicy.classify(
            "http://127.0.0.1:34567/settings/account",
            origin,
            noAccount
        )

        assertEquals(DshPopupClassification.InWebView, result)
    }

    @Test
    fun externalHttpPopupBecomesANormalHandoff() {
        val result = DshPopupPolicy.classify("https://example.test/docs", origin, noAccount)

        assertEquals(
            DshPopupClassification.Handoff(DshPopupKind.NORMAL_EXTERNAL),
            result
        )
    }

    @Test
    fun accountMatcherClassifiesTheExactAuthorizeUrlAsAccountHandoff() {
        val matcher = DshAccountAuthUrlMatcher { it == accountUrl }

        val result = DshPopupPolicy.classify(accountUrl, origin, matcher)

        assertEquals(DshPopupClassification.Handoff(DshPopupKind.ACCOUNT_AUTH), result)
    }

    @Test
    fun aThrowingMatcherDegradesToNormalExternalInsteadOfCrashing() {
        val matcher = DshAccountAuthUrlMatcher { throw IllegalStateException("account state unavailable") }

        val result = DshPopupPolicy.classify("https://example.test/x", origin, matcher)

        assertEquals(DshPopupClassification.Handoff(DshPopupKind.NORMAL_EXTERNAL), result)
    }

    @Test
    fun nonHttpSchemeIsBlocked() {
        val result = DshPopupPolicy.classify("javascript:alert(1)", origin, noAccount)

        assertEquals(
            DshPopupClassification.Blocked(DshBlockReason.NON_HTTP_SCHEME),
            result
        )
    }

    @Test
    fun blankPopupTargetIsBlocked() {
        val result = DshPopupPolicy.classify("   ", origin, noAccount)

        assertEquals(
            DshPopupClassification.Blocked(DshBlockReason.INVALID_URL),
            result
        )
    }

    @Test
    fun credentialsInPopupUrlAreBlocked() {
        val result = DshPopupPolicy.classify("https://user:secret@example.test/", origin, noAccount)

        assertEquals(
            DshPopupClassification.Blocked(DshBlockReason.CREDENTIALS_IN_URL),
            result
        )
    }

    @Test
    fun overlongPopupUrlIsBlocked() {
        val result = DshPopupPolicy.classify(
            "https://example.test/" + "a".repeat(8_192),
            origin,
            noAccount
        )

        assertEquals(
            DshPopupClassification.Blocked(DshBlockReason.URL_TOO_LONG),
            result
        )
    }

    @Test
    fun controlCharactersInPopupUrlAreBlocked() {
        val result = DshPopupPolicy.classify("https://example.test/a\nb", origin, noAccount)

        assertEquals(
            DshPopupClassification.Blocked(DshBlockReason.INVALID_URL),
            result
        )
    }

    @Test
    fun popupWithoutAnOriginStillClassifiesExternalLinks() {
        val result = DshPopupPolicy.classify("https://example.test/", null, noAccount)

        assertTrue(result is DshPopupClassification.Handoff)
    }
}
