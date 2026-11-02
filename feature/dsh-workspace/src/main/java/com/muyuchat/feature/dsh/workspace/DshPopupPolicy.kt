package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupLimits
import java.net.URI
import java.util.Locale

/** What the container must do with one popup navigation. */
sealed interface DshPopupClassification {
    /** Same-origin navigation: keep the official page inside the single WebView. */
    data object InWebView : DshPopupClassification

    /** External http(s): hand a typed request to the host browser port, never load it here. */
    data class Handoff(val kind: DshPopupKind) : DshPopupClassification

    data class Blocked(val reason: DshBlockReason) : DshPopupClassification
}

/**
 * Pure popup classification (D04/D25).
 *
 * It reuses the same origin fence as normal navigation, then splits external links into the two
 * classes the container must keep apart:
 *  - [DshPopupKind.ACCOUNT_AUTH] only when the injected account matcher recognises the exact
 *    authorize URL of the current official attempt;
 *  - [DshPopupKind.NORMAL_EXTERNAL] otherwise, which may be opened by a separate browser surface
 *    but never receives the DSH loopback cookie.
 *
 * `about:blank` is deliberately not classified here: the account frontend pre-opens a blank popup
 * and replaces its location later, so the WebView host holds it in a bounded pending state.
 */
object DshPopupPolicy {

    fun classify(
        url: String?,
        origin: DshOrigin?,
        accountAuthMatcher: DshAccountAuthUrlMatcher
    ): DshPopupClassification {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) {
            return DshPopupClassification.Blocked(DshBlockReason.INVALID_URL)
        }
        if (candidate.length > DshPopupLimits.MAX_URL_CHARS) {
            return DshPopupClassification.Blocked(DshBlockReason.URL_TOO_LONG)
        }
        if (candidate.any(::isControlCharacter)) {
            return DshPopupClassification.Blocked(DshBlockReason.INVALID_URL)
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return DshPopupClassification.Blocked(DshBlockReason.INVALID_URL)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return DshPopupClassification.Blocked(DshBlockReason.NON_HTTP_SCHEME)
        }
        if (uri.host.isNullOrBlank()) {
            return DshPopupClassification.Blocked(DshBlockReason.INVALID_URL)
        }
        if (uri.rawUserInfo != null) {
            return DshPopupClassification.Blocked(DshBlockReason.CREDENTIALS_IN_URL)
        }
        if (origin != null && scheme == "http" && origin.matches(candidate)) {
            return DshPopupClassification.InWebView
        }
        val kind = if (runCatching { accountAuthMatcher.isAccountAuthUrl(candidate) }
                .getOrDefault(false)
        ) {
            DshPopupKind.ACCOUNT_AUTH
        } else {
            DshPopupKind.NORMAL_EXTERNAL
        }
        return DshPopupClassification.Handoff(kind)
    }

    private fun isControlCharacter(character: Char): Boolean =
        character.code < 0x20 || character.code in 0x7F..0x9F
}
