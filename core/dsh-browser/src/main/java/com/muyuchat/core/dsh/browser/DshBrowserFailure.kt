package com.muyuchat.core.dsh.browser

import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityFailure
import com.muyuchat.core.dsh.contract.capability.DshCapabilityLimits

/**
 * Stable failure families of the browser protocol.
 *
 * [wire] is the value the official `dsh-tool-browser` package surfaces as `reason`. Where the
 * pinned reference already emits a reason string, the same string is kept, so a model that has
 * seen `stale-tab` / `stale-ref` / `blocked-url` behaviour does not have to relearn codes.
 *
 * Every reason is prompt-free and carries no URL, cookie, token or page text. The failure message
 * is a fixed human sentence; the caller adds the actionable `hint` separately.
 */
enum class DshBrowserFailureReason(val wire: String) {
    /** The `op` field is not one of the 10 official tools / 13 wire operations. */
    BAD_OP("bad-op"),

    /** The envelope or the per-op argument object is malformed or carries an undeclared field. */
    BAD_ARGS("bad-args"),

    /** A request or a produced response exceeds a documented budget. */
    TOO_LARGE("too-large"),

    /** Navigation policy refused the URL (scheme, userinfo, loopback/private address). */
    BLOCKED_URL("blocked-url"),

    /** The ref does not match the documented `rXXXXXXX[-N]` shape. */
    INVALID_REF("invalid-ref"),

    /** The ref is unknown in the active tab, or was recorded for a page generation that ended. */
    STALE_REF("stale-ref"),

    /** A ref-consuming action ran before any snapshot/find registered refs for this tab. */
    SNAPSHOT_REQUIRED("snapshot-required"),

    /** The resolved element is disabled and cannot act. */
    ELEMENT_DISABLED("element-disabled"),

    /** The target element is not editable, so `type` cannot write into it. */
    NOT_EDITABLE("not-editable"),

    /** `tabs.switch` / `tabs.close` needs a `tabId` and none was supplied. */
    TAB_REQUIRED("tab-required"),

    /** The `tabId` is not an existing tab. */
    NO_SUCH_TAB("no-such-tab"),

    /** The action carried a `tabId` that is not the active tab; refs must not cross tabs. */
    STALE_TAB("stale-tab"),

    /** The bounded tab count is reached; close a tab before opening another. */
    TAB_LIMIT("tab-limit"),

    /** `nav back` / `nav forward` has no history entry in that direction. */
    NO_HISTORY("no-history"),

    /** The page has not finished its first load, so it has nothing reliable to observe. */
    NOT_LOADED("not-loaded"),

    /** The isolated browser process / WebView host is not available. */
    ENGINE_UNAVAILABLE("engine-unavailable"),

    /** The whole browser session was closed; every tab was released. */
    BROWSER_CLOSED("browser-closed"),

    /** The platform reported a load error for the navigation. */
    LOAD_FAILED("load-failed"),

    /** The WebView has no measurable viewport, so coordinates cannot be mapped. */
    VIEWPORT_UNAVAILABLE("viewport-unavailable"),

    /** The bounded per-call timeout elapsed. The outcome of the action is unknown. */
    TIMEOUT("timeout"),

    /** The caller cancelled. The outcome of an in-flight action is unknown. */
    CANCELLED("cancelled"),

    /** A defect in the adapter or an unexpected engine exception. */
    INTERNAL("internal");

    /** Contract-level code, so the capability server can record a typed failure. */
    val capabilityErrorCode: DshCapabilityErrorCode
        get() = when (this) {
            BAD_OP, BAD_ARGS, TAB_REQUIRED, NO_SUCH_TAB, NO_HISTORY, INVALID_REF,
            SNAPSHOT_REQUIRED, ELEMENT_DISABLED, NOT_EDITABLE, BLOCKED_URL -> DshCapabilityErrorCode.INVALID_REQUEST
            STALE_REF, STALE_TAB -> DshCapabilityErrorCode.TARGET_CHANGED
            TOO_LARGE -> DshCapabilityErrorCode.TOO_LARGE
            TAB_LIMIT -> DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE
            NOT_LOADED, VIEWPORT_UNAVAILABLE, ENGINE_UNAVAILABLE, LOAD_FAILED ->
                DshCapabilityErrorCode.NOT_ENABLED
            BROWSER_CLOSED -> DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE
            TIMEOUT -> DshCapabilityErrorCode.TIMEOUT
            CANCELLED -> DshCapabilityErrorCode.USER_CANCELLED
            INTERNAL -> DshCapabilityErrorCode.INTERNAL
        }
}

/**
 * One prompt-free browser failure.
 *
 * The message must be a single line without control characters: it is written into a JSON
 * response the model reads, and page-controlled text must never be able to break that shape.
 */
data class DshBrowserFailure(
    val reason: DshBrowserFailureReason,
    val message: String,
    val hint: String? = null,
    val retryable: Boolean = false
) {
    init {
        require(message.isNotBlank() && message.length <= DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS) {
            "browser failure message must be 1..${DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS} characters"
        }
        require(message.none { it.code <= 0x1F || it.code in 0x7F..0x9F }) {
            "browser failure message must be a prompt-free single line"
        }
        require(hint == null || (hint.length <= DshCapabilityLimits.MAX_DETAIL_CHARS && hint.none { it.code <= 0x1F || it.code in 0x7F..0x9F })) {
            "browser failure hint must be a bounded single line"
        }
    }

    /** Contract projection for audit/ledger code. Never contains page content. */
    fun toCapabilityFailure(): DshCapabilityFailure = DshCapabilityFailure(
        code = reason.capabilityErrorCode,
        capability = DshCapability.BROWSER,
        message = message,
        retryable = retryable
    )
}

/** Convenience constructor used by policy/state code: reason + fixed sentence, no page text. */
internal fun browserFailure(
    reason: DshBrowserFailureReason,
    message: String,
    hint: String? = null,
    retryable: Boolean = false
): DshBrowserFailure = DshBrowserFailure(reason, message, hint, retryable)
