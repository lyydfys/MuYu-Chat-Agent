package com.muyuchat.core.dsh.runtime.auth

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptBounds
import com.muyuchat.core.dsh.contract.auth.DshAuthorizeUrlRules

/**
 * Runtime-side names for the shared [DshAuthorizeUrlRules].
 *
 * The rules themselves live in `core/dsh-contract` so the Web container (which may depend only on
 * the contract module) and the host use exactly one implementation. This object exists so the
 * account machine and its tests keep a narrow, account-specific entry point.
 */
internal object DshAuthUrlPolicy {

    fun isValidCallbackOrigin(origin: String): Boolean =
        DshAuthorizeUrlRules.isValidCallbackOrigin(origin)

    fun normalizeCallbackOrigin(origin: String): String? =
        DshAuthorizeUrlRules.normalizeCallbackOrigin(origin)

    fun redirectUri(callbackOrigin: String, bounds: DshAuthAttemptBounds): String? =
        DshAuthorizeUrlRules.redirectUri(callbackOrigin, bounds)

    fun isValidAuthorizeUrl(url: String, bounds: DshAuthAttemptBounds): Boolean =
        DshAuthorizeUrlRules.isOfficialAuthorizeUrl(url, bounds)

    fun matchesOriginalAuthorizeUrl(
        original: String,
        candidate: String,
        bounds: DshAuthAttemptBounds
    ): Boolean = DshAuthorizeUrlRules.matchesOfficialAuthorizeUrl(original, candidate, bounds)
}
