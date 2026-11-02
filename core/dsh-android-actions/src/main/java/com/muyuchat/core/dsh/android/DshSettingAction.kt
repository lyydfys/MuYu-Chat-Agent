package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import org.json.JSONObject

/**
 * `POST /setting` — `dsh-tool-shizuku`'s `android_setting_app`.
 *
 * Wire in: `{"key":"screen_brightness","value":"128"}`; the `system` namespace only, exactly like
 * the official tool (Global/Secure writes go through the privileged `/shell` route). Wire out:
 * `{"ok":true,"namespace":"system","key":"..","oldValue":"..","newValue":".."}` plus
 * `stream`/`level`/`max` for the four volume keys the reference maps to audio streams.
 *
 * Validation is fail-closed: a settings key is an identifier (`[A-Za-z0-9_.]`), so shell
 * metacharacters, whitespace, quotes and path separators are rejected before any platform call.
 * Before/after values are read by the adapter, so a write that the system normalized or ignored is
 * reported through `newValue` instead of a bare success.
 */
class DshSettingAction(
    private val port: DshAppSettingsPort
) : DshAndroidRoute() {

    override val path: String = ROUTE

    override suspend fun executeTyped(
        request: DshCapabilityRouteRequest
    ): DshCapabilityRouteResponse {
        DshAndroidEnvelope.requirePost(request, ROUTE)
        val body = DshAndroidEnvelope.body(request)

        val key = DshAndroidWire.requireString(
            body,
            "key",
            DshAndroidActionLimits.MAX_SETTING_KEY_CHARS
        ).trim()
        if (!KEY_PATTERN.matches(key)) {
            throw badRequest(
                DshAndroidActionErrorCode.INVALID_REQUEST,
                "key 只能包含字母、数字、下划线和点"
            )
        }
        val value = DshAndroidWire.requireString(
            body,
            "value",
            DshAndroidActionLimits.MAX_SETTING_VALUE_CHARS,
            allowBlank = true
        )

        val access = port.writeAccess(key)
        if (access != DshCapabilityState.GRANTED) {
            return refused(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.refusalFor(access),
                    refusalReason(access)
                ),
                key
            )
        }

        return when (val result = port.write(key, value)) {
            is DshSettingWriteResult.Written -> DshAndroidWire.response(writtenBody(key, result.outcome))
            is DshSettingWriteResult.Rejected -> refused(result.failure, key)
        }
    }

    private fun writtenBody(key: String, outcome: DshSettingWriteOutcome): JSONObject {
        val body = okBody()
            .put("namespace", NAMESPACE)
            .put("key", key)
            .put("oldValue", outcome.oldValue ?: "")
            .put("newValue", outcome.newValue ?: "")
        outcome.stream?.let { body.put("stream", it) }
        outcome.level?.let { body.put("level", it) }
        outcome.maxLevel?.let { body.put("max", it) }
        return body
    }

    private fun refused(
        failure: DshAndroidActionFailure,
        key: String? = null
    ): DshCapabilityRouteResponse = DshAndroidWire.response(
        failureBody(failure).also { body -> key?.let { body.put("key", it) } }
    )

    override fun failureBody(failure: DshAndroidActionFailure): JSONObject = JSONObject()
        .put("ok", false)
        .put("namespace", NAMESPACE)
        .put("error", failure.wireMessage)

    private fun refusalReason(state: DshCapabilityState): String = when (state) {
        DshCapabilityState.NOT_DECLARED ->
            "应用未声明「修改系统设置」权限（WRITE_SETTINGS）"
        DshCapabilityState.NOT_ENABLED ->
            "「修改系统设置」未启用"
        DshCapabilityState.UNSUPPORTED ->
            "当前系统版本不支持该设置写入"
        else ->
            "未授予「修改系统设置」权限（WRITE_SETTINGS）"
    }

    companion object {
        const val ROUTE = "/setting"

        private const val NAMESPACE = "system"

        /** `Settings.System` keys are identifiers; anything else is rejected, not escaped. */
        private val KEY_PATTERN = Regex("[A-Za-z0-9_.]{1,${DshAndroidActionLimits.MAX_SETTING_KEY_CHARS}}")
    }
}
