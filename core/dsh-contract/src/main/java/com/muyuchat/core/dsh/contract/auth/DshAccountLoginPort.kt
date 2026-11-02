package com.muyuchat.core.dsh.contract.auth

/**
 * Host-facing seam of the official DeepSeek account sign-in popup.
 *
 * The DSH Web container (WebView chrome client / route) observes trusted transport facts and
 * reports them here; the account lane owns the correlation with the one official attempt, the
 * external-browser handoff and the terminal states. The container must not start, retry, cancel or
 * "complete" the login itself, and it must never treat a handed-off URL as a successful login.
 *
 * Invariants:
 * - every method is a fact report or a bounded query; none of them returns a token, an
 *   authorization code, a PKCE verifier, a cookie or a signed-in claim;
 * - a pre-opened `about:blank` window is not a login attempt yet: only the account lane may bind it
 *   to the current attempt and decide whether an authorize URL may be opened;
 * - all methods may be called from the WebView/UI thread; an implementation must be cheap and
 *   must not block on disk or network.
 *
 * Implemented by the account lane; the account browser port of the same lane remains the only
 * component that opens the authorize URL.
 */
interface DshAccountLoginPort {

    /**
     * A trusted DSH page pre-opened a blank popup on a user gesture
     * (`window.open('about:blank')`), exactly as the official account frontend does before
     * `auth_init` answers.
     *
     * [requesterOrigin] is the origin of the hosting DSH page. An implementation must ignore a
     * value that is not the current trusted DSH origin instead of starting an attempt for it.
     */
    fun onPopupPreopened(requesterOrigin: String)

    /**
     * The pending pre-opened window ended before an authorize URL was handed off (page closed it
     * or the bounded hold elapsed). A live attempt that is still waiting for its authorize URL
     * must be cancelled: it can never receive a browser handoff any more.
     */
    fun onPopupAbandoned()

    /**
     * The DSH page released the pre-opened window **after** the authorize URL was handed off to the
     * external browser.
     *
     * This is the only host-observable consequence of the official sign-in cancel: the pinned
     * official client watches the attempt stream and, on `cancelled` / `failed` / `expired`, closes
     * exactly the window it pre-opened (`BrowserLogin.update` -> `popup.close()`); no DOM event and
     * no host-reachable `cancelSignIn` RPC exists for the app. A live attempt that is still waiting
     * for its callback must therefore end as a typed
     * [DshAuthFailureCode.OFFICIAL_POPUP_RELEASED] `CANCELLED` terminal state — never as a success,
     * and never by starting, retrying or completing anything new.
     *
     * Implementations must ignore the fact when no attempt is active (a late release of a window
     * that belonged to an already-terminal attempt) and must keep a `committing` attempt untouched,
     * because the official credential store is writing the grant there and a closed window cannot
     * prove the write did not land.
     */
    fun onOfficialPopupReleased()

    /**
     * The DSH Web document itself answered 401/403. This is the launch-cookie session, never the
     * official account grant, so it must not clear the account credential or the account attempt.
     */
    fun onWebSessionUnauthorized()

    /**
     * Remaining budget of the current attempt for holding the hidden pre-opened window, in
     * milliseconds; `0` when no attempt is active. The container bounds it further.
     */
    fun handoffBudgetMillis(): Long
}
