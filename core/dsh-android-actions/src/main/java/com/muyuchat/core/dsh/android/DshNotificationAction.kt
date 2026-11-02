package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityLimits
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import org.json.JSONObject

/**
 * `POST /notify` — `dsh-tool-shizuku`'s `android_notify` and the notification half of the pinned
 * App-local server.
 *
 * Wire in: `{"title": "...", "text": "..."}` (the official tool always POSTs a JSON body and
 * authenticates with the `X-DSH-Token` header; the body never carries a token).
 * Wire out: `{"ok":true,"posted":true}` or `{"ok":false,"posted":false,"error":"CODE: message"}`.
 * The official tool overwrites `visible`/`evidence` itself from its own privileged check, so those
 * fields are not claimed here.
 *
 * The notification id is derived from `(title, text)`, so a retried identical request replaces the
 * same notification instead of stacking duplicates — the wire-level half of idempotency that this
 * route owns (the per-request ledger lives in `:core:dsh-platform`).
 */
class DshNotificationAction(
    private val port: DshNotificationPort
) : DshAndroidRoute() {

    override val path: String = ROUTE

    fun state(): DshCapabilityState = port.state()

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requirePost(request, ROUTE)
        val body = DshAndroidEnvelope.body(request)

        val rawTitle = DshAndroidWire.requireString(
            body,
            "title",
            DshCapabilityLimits.MAX_NOTIFICATION_TITLE_CHARS
        )
        val title = rawTitle.trim()
        if (title.isEmpty()) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "title 不能为空")
        }
        if (title.any(::isForbiddenFailureCharacter)) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "title 必须是单行文本")
        }
        val text = DshAndroidWire.requireString(
            body,
            "text",
            DshCapabilityLimits.MAX_NOTIFICATION_BODY_CHARS,
            allowBlank = true
        )
        if (text.any { it != '\n' && it != '\t' && isForbiddenFailureCharacter(it) }) {
            throw badRequest(DshAndroidActionErrorCode.INVALID_REQUEST, "text 含不允许的控制字符")
        }

        val notificationId = DshNotificationIds.of(title, text)
        return when (val outcome = port.post(title, text, notificationId)) {
            is DshNotificationPostOutcome.Posted ->
                DshAndroidWire.response(okBody().put("posted", true))
            is DshNotificationPostOutcome.Failed -> refused(outcome.failure)
        }
    }

    /** Clears the notification with the deterministic id of the given content. */
    suspend fun cancel(title: String, text: String): Boolean =
        port.cancel(DshNotificationIds.of(title, text))

    /** Lifecycle half: the runtime owner clears every posted notice when a generation stops. */
    suspend fun cancelAll(): Int = port.cancelAll()

    override fun failureBody(failure: DshAndroidActionFailure): JSONObject = JSONObject()
        .put("ok", false)
        .put("posted", false)
        .put("error", failure.wireMessage)

    companion object {
        const val ROUTE = "/notify"
    }
}

/**
 * Deterministic notification id.
 *
 * Android replaces a notification with the same id, so identical content posted twice (a retried
 * request after a lost response) updates one notification instead of posting a second. The hash is
 * spelled out instead of using [String.hashCode] so the id stays stable across implementations.
 */
internal object DshNotificationIds {
    private const val MASK = 0x3FFFFF

    fun of(title: String, text: String): Int {
        var hash = 17
        val payload = "dsh-notify-v1\u0000" + title + "\u0000" + text
        payload.forEach { character -> hash = 31 * hash + character.code }
        return hash and MASK
    }
}
