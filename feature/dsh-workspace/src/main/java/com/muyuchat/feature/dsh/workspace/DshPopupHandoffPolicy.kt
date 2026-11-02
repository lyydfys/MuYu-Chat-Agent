package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.console.DshPopupHandoffResult
import com.muyuchat.core.dsh.contract.console.DshPopupKind
import com.muyuchat.core.dsh.contract.console.DshPopupRejection
import com.muyuchat.core.dsh.contract.console.DshPopupUnavailable

/** What the UI does after the trusted browser port answered a popup handoff. */
sealed interface DshPopupOutcome {
    /** Show a one-shot notice; no page and no browser surface is opened by the container. */
    data class Notify(val text: String) : DshPopupOutcome

    /**
     * A normal external link with no browser port wired: the host's existing validated
     * `ACTION_VIEW`/MCA-browser path may open it. Account popups never take this branch, because
     * the DSH WebView/Intent path must not receive account authorization URLs.
     */
    data class FallbackExternal(val url: String) : DshPopupOutcome

    /** The user cancelled; no notice is needed. */
    data object Silent : DshPopupOutcome
}

/**
 * Pure mapping from the browser port outcome to a user-visible result (D04/D25).
 *
 * Security invariants:
 * - an account popup never falls back to an Intent or to the DSH WebView, whatever failed;
 * - a replayed/cancelled/expired ticket never re-opens a URL;
 * - messages are fixed and prompt-free; the URL is never echoed.
 */
object DshPopupHandoffPolicy {

    fun outcomeFor(
        kind: DshPopupKind,
        url: String,
        result: DshPopupHandoffResult
    ): DshPopupOutcome = when (result) {
        DshPopupHandoffResult.HandedOff -> DshPopupOutcome.Notify(
            if (kind == DshPopupKind.ACCOUNT_AUTH) {
                "已在浏览器中打开官方账号登录。"
            } else {
                "已打开外部链接。"
            }
        )

        DshPopupHandoffResult.Cancelled -> DshPopupOutcome.Silent

        DshPopupHandoffResult.Expired -> DshPopupOutcome.Notify("登录窗口等待超时，请重新发起登录。")

        is DshPopupHandoffResult.Rejected -> when (result.reason) {
            DshPopupRejection.STALE_REQUEST -> DshPopupOutcome.Notify("登录尝试已结束，请重新发起登录。")
            DshPopupRejection.URL_MISMATCH -> DshPopupOutcome.Notify("该链接不属于当前登录尝试，已阻止。")
            DshPopupRejection.REPLAYED -> DshPopupOutcome.Notify("该登录窗口已处理，请重新发起登录。")
        }

        is DshPopupHandoffResult.Unavailable -> when (result.reason) {
            DshPopupUnavailable.NO_BROWSER_HANDLER ->
                if (kind == DshPopupKind.ACCOUNT_AUTH) {
                    DshPopupOutcome.Notify("未找到可处理账号登录的浏览器。")
                } else {
                    DshPopupOutcome.FallbackExternal(url)
                }

            DshPopupUnavailable.USER_PRESENCE_REQUIRED ->
                DshPopupOutcome.Notify("请回到应用后重试打开页面。")

            DshPopupUnavailable.ACCOUNT_ATTEMPT_UNAVAILABLE ->
                DshPopupOutcome.Notify("当前没有进行中的官方登录，请重新发起登录。")

            DshPopupUnavailable.FEATURE_DISABLED ->
                if (kind == DshPopupKind.ACCOUNT_AUTH) {
                    DshPopupOutcome.Notify("账号登录浏览器未启用。")
                } else {
                    DshPopupOutcome.FallbackExternal(url)
                }
        }
    }
}
