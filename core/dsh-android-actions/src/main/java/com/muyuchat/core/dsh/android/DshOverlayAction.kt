package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import org.json.JSONObject

/**
 * `GET /overlay` — `dsh-tool-android`'s `android_overlay` (`action=show|hide|status`).
 *
 * Wire in: `?action=show|hide|status` (absent means `status`; the official tool always sends the
 * parameter). Wire out: `{"ok":true,"running":..,"engineUp":..,"granted":..,"action":"show",
 * "changed":true,"visible":true}`.
 *
 * `changed` is derived from the before/after `visible` snapshots instead of being assumed, so a
 * request that could not change the window does not report success as if it had. The tool package
 * additionally verifies visibility through `dumpsys`; this route only reports what the app owner
 * observed.
 */
class DshOverlayAction(
    private val port: DshOverlayPort
) : DshAndroidRoute() {

    override val path: String = ROUTE

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requireGet(request, ROUTE, QUERY_KEYS)
        val action = parseAction(DshAndroidEnvelope.queryString(request, "action", 16))

        val before = port.snapshot()
        val after = when (action) {
            ACTION_SHOW -> port.show()
            ACTION_HIDE -> port.hide()
            else -> before
        }
        val changed = before.visible != after.visible
        val body = stateBody(action, after, changed)
        if (!after.granted) {
            val failure = DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.PERMISSION_DENIED,
                "未授予悬浮窗权限（SYSTEM_ALERT_WINDOW）"
            )
            return refused(body, failure)
        }
        return DshAndroidWire.response(body)
    }

    private fun parseAction(raw: String?): String {
        val action = raw?.trim().orEmpty()
        if (action.isEmpty()) return ACTION_STATUS
        if (action !in ALLOWED_ACTIONS) {
            throw badRequest(
                DshAndroidActionErrorCode.INVALID_REQUEST,
                "action 必须是 show、hide 或 status"
            )
        }
        return action
    }

    private fun stateBody(
        action: String,
        snapshot: DshOverlaySnapshot,
        changed: Boolean
    ): JSONObject {
        val body = okBody()
            .put("running", snapshot.running)
            .put("engineUp", snapshot.engineUp)
            .put("granted", snapshot.granted)
            .put("action", action)
            .put("changed", changed)
            .put("visible", snapshot.visible)
        snapshot.message?.let { body.put("msg", it) }
        return body
    }

    override fun failureBody(failure: DshAndroidActionFailure): JSONObject = JSONObject()
        .put("ok", false)
        .put("running", false)
        .put("engineUp", false)
        .put("granted", false)
        .put("changed", false)
        .put("error", failure.wireMessage)

    companion object {
        const val ROUTE = "/overlay"

        private const val ACTION_SHOW = DshAndroidActionLimits.OVERLAY_ACTION_SHOW
        private const val ACTION_HIDE = DshAndroidActionLimits.OVERLAY_ACTION_HIDE
        private const val ACTION_STATUS = DshAndroidActionLimits.OVERLAY_ACTION_STATUS

        private val ALLOWED_ACTIONS = setOf(ACTION_SHOW, ACTION_HIDE, ACTION_STATUS)
        internal val QUERY_KEYS: Set<String> = setOf("action")
    }
}
