package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import org.json.JSONObject

/**
 * `POST /clipboard` — `dsh-tool-shizuku`'s `android_clipboard`.
 *
 * Wire in: `{"action":"read"|"write","content":"..."}`. Wire out: read →
 * `{"ok":true,"content":"..."}`, write → `{"ok":true}` (the tool shows “已写入剪贴板” only when
 * `content` is absent), failure → `{"ok":false,"error":"CODE: message"}`.
 *
 * POST-only by contract. The official package comments that a `GET /clipboard?action=write` used to
 * be silently executed as a read because the reference App dropped the query for POST paths; this
 * route refuses a GET and refuses a query on POST instead of repeating that failure mode.
 *
 * Writes always set the platform sensitive flag: the text came from a model call and may contain
 * private content, and Android 13+ then hides it from the clipboard preview.
 */
class DshClipboardAction(
    private val port: DshClipboardPort
) : DshAndroidRoute() {

    override val path: String = ROUTE

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requirePost(request, ROUTE)
        val body = DshAndroidEnvelope.body(request)
        val action = DshAndroidWire.requireString(body, "action", MAX_ACTION_CHARS)
        val content = DshAndroidWire.optionalString(
            body,
            "content",
            DshAndroidActionLimits.MAX_CLIPBOARD_CHARS
        )

        return when (action) {
            ACTION_READ -> readClipboard()
            ACTION_WRITE -> writeClipboard(content)
            else -> throw badRequest(
                DshAndroidActionErrorCode.INVALID_REQUEST,
                "action 必须是 read 或 write"
            )
        }
    }

    private suspend fun readClipboard(): DshCapabilityRouteResponse =
        when (val outcome = port.read()) {
            is DshClipboardReadOutcome.Text -> {
                if (outcome.value.length > DshAndroidActionLimits.MAX_CLIPBOARD_CHARS) {
                    refused(
                        DshAndroidActionFailure.of(
                            DshAndroidActionErrorCode.TOO_LARGE,
                            "剪贴板内容超过 ${DshAndroidActionLimits.MAX_CLIPBOARD_CHARS} 字符上限"
                        )
                    )
                } else {
                    DshAndroidWire.response(okBody().put("content", outcome.value))
                }
            }
            DshClipboardReadOutcome.Empty ->
                DshAndroidWire.response(okBody().put("content", ""))
            is DshClipboardReadOutcome.Unavailable -> refused(outcome.failure)
        }

    private suspend fun writeClipboard(content: String?): DshCapabilityRouteResponse {
        val text = content
            ?: throw badRequest(
                DshAndroidActionErrorCode.INVALID_REQUEST,
                "action=write 需要 content 参数"
            )
        val failure = port.write(text, sensitive = true)
        return if (failure == null) {
            DshAndroidWire.response(okBody())
        } else {
            refused(failure)
        }
    }

    companion object {
        const val ROUTE = "/clipboard"

        private const val MAX_ACTION_CHARS = 16
        private const val ACTION_READ = "read"
        private const val ACTION_WRITE = "write"
    }
}
