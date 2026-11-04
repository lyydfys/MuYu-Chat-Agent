package com.muyuchat.mca.agent.device

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteHandler
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteResponse
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.mca.agent.device.sensors.AgentSensorCatalog
import com.muyuchat.mca.agent.device.sensors.AgentSensorId
import com.muyuchat.mca.agent.device.sensors.AgentSensorReasonCode
import com.muyuchat.mca.agent.device.sensors.AgentSensorResult
import com.muyuchat.mca.agent.device.sms.AgentSmsConfirmationGate
import com.muyuchat.mca.agent.device.sms.AgentSmsConfirmationOutcome
import com.muyuchat.mca.agent.device.sms.AgentSmsPolicy
import com.muyuchat.mca.agent.device.sms.AgentSmsQueryResult
import com.muyuchat.mca.agent.device.sms.AgentSmsRawQueryResult
import com.muyuchat.mca.agent.device.sms.AgentSmsReasonCode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fixed route table of the DSH device-access lane (parity with the reference project's
 * "sensors/location" and "SMS read-only" entries).
 *
 * Surfaces (mounted under [com.muyuchat.core.dsh.contract.capability.DshCapability.ANDROID_SYSTEM]
 * because the capability enum is a contract file this cut must not change; the console detail
 * names both lanes explicitly):
 *  - `GET  /sensors[?id=light|accelerometer|gyroscope|magnetometer|barometer|step_counter|location]`
 *    — consent/permission status when `id` is absent, one single-shot reading when it is present;
 *  - `POST /sensors/torch?on=1|0` — the torch switch (the only side effect of the family);
 *  - `GET  /sms[?limit=1..20][&keyword=…]` — read-only masked preview of recent messages.
 *
 * Everything is injected ([AgentDeviceConsentGate], [AgentDevicePermissionGate], sources,
 * [AgentSmsConfirmationPort]), so the whole handler matrix is pure JVM testable. A refusal is a
 * typed `{"ok":false,"errorCode":…}` document, never an empty success: DSH must be able to say
 * "not granted" instead of "no data".
 */
class AgentDeviceCapabilityRoutes(
    private val consent: AgentDeviceConsentGate,
    private val permissions: AgentDevicePermissionGate,
    private val sensors: AgentSensorSource,
    private val sms: AgentSmsSource,
    private val smsConfirmation: AgentSmsConfirmationPort,
    private val smsConfirmationGate: AgentSmsConfirmationGate = AgentSmsConfirmationGate(
        skipThisSession = consent::smsSkipConfirmationThisSession
    )
) {

    fun register(builder: DshCapabilityRouteTable.Builder): DshCapabilityRouteTable.Builder {
        builder.route(SENSORS_PATH, DshCapabilityRouteHandler { request -> sensorsRoute(request) })
        builder.route(TORCH_PATH, DshCapabilityRouteHandler { request -> torchRoute(request) })
        builder.route(SMS_PATH, DshCapabilityRouteHandler { request -> smsRoute(request) })
        return builder
    }

    /**
     * One-line, prompt-free summary for the ANDROID_SYSTEM console detail: how many sensor
     * capabilities the user switched on and whether SMS reading is on. No names, no values.
     */
    fun summaryDetail(): String = summaryDetail(consent)

    private suspend fun sensorsRoute(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.GET) return methodNotAllowed("/sensors 只接受 GET")
        rejectUnknownKeys(request, setOf(QUERY_ID))?.let { return it }
        val rawId = request.query[QUERY_ID]
        if (rawId == null) return json(200, statusBody())
        val id = AgentSensorCatalog.fromWireName(rawId)
            ?: return refused(
                AgentSensorReasonCode.INVALID_REQUEST,
                "未知传感器「$rawId」；可用值：" +
                    AgentSensorCatalog.all.joinToString("、") { AgentSensorCatalog.wireName(it.id) }
            )
        val capability = AgentSensorCatalog.capability(id)
        if (!consent.sensorEnabled(id)) {
            return refused(
                AgentSensorReasonCode.NOT_GRANTED,
                "「${capability.displayName}」未在 MCA 设置中授权。",
                "在 MCA 设置 → 隐私与数据 → 设备传感器与位置中打开「${capability.displayName}」。"
            )
        }
        if (capability.permission != null && !permissions.granted(capability.permission)) {
            return refused(
                AgentSensorReasonCode.PERMISSION_MISSING,
                "缺少系统权限：${capability.permissionLabel}。",
                "在系统设置 → 应用 → MCA → 权限中允许「${capability.permissionLabel}」。"
            )
        }
        return when (val result = sensors.read(id)) {
            is AgentSensorResult.Value -> {
                val reading = result.reading
                json(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("sensor", AgentSensorCatalog.wireName(reading.sensorId))
                        .put("value", reading.value)
                        .put("unit", reading.unit)
                        .put("at", reading.capturedAtMillis)
                        .put("detail", reading.detail)
                )
            }
            is AgentSensorResult.Toggled -> refused(
                AgentSensorReasonCode.INVALID_REQUEST,
                "「${capability.displayName}」是开关，请使用 /sensors/torch。"
            )
            is AgentSensorResult.Refused -> refused(result.reason, result.detail, result.settingsHint)
        }
    }

    private suspend fun torchRoute(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.POST) {
            return methodNotAllowed("/sensors/torch 只接受 POST")
        }
        rejectUnknownKeys(request, setOf(QUERY_ON))?.let { return it }
        val requested = when (request.query[QUERY_ON]?.trim()?.lowercase()) {
            "1", "true", "on" -> true
            "0", "false", "off" -> false
            else -> return refused(
                AgentSensorReasonCode.INVALID_REQUEST,
                "开关请求必须带 on=1 或 on=0。"
            )
        }
        val capability = AgentSensorCatalog.capability(AgentSensorId.TORCH)
        if (!consent.sensorEnabled(AgentSensorId.TORCH)) {
            return refused(
                AgentSensorReasonCode.NOT_GRANTED,
                "「${capability.displayName}」未在 MCA 设置中授权。",
                "在 MCA 设置 → 隐私与数据 → 设备传感器与位置中打开「${capability.displayName}」。"
            )
        }
        return when (val result = sensors.setTorch(requested)) {
            is AgentSensorResult.Toggled -> json(
                200,
                JSONObject()
                    .put("ok", true)
                    .put("sensor", AgentSensorCatalog.wireName(AgentSensorId.TORCH))
                    .put("enabled", result.state.enabled)
            )
            is AgentSensorResult.Refused -> refused(result.reason, result.detail, result.settingsHint)
            is AgentSensorResult.Value -> refused(
                AgentSensorReasonCode.INVALID_REQUEST,
                "手电筒不是采样传感器。"
            )
        }
    }

    private suspend fun smsRoute(request: DshCapabilityRouteRequest): DshCapabilityRouteResponse {
        if (request.method != DshCapabilityHttpMethod.GET) return methodNotAllowed("/sms 只接受 GET")
        rejectUnknownKeys(request, setOf(QUERY_LIMIT, QUERY_KEYWORD))?.let { return it }
        if (!consent.smsReadEnabled()) {
            return refused(
                AgentSmsReasonCode.NOT_GRANTED,
                "短信只读查询未在 MCA 设置中开启。",
                "在 MCA 设置 → 隐私与数据 → 短信只读查询中打开开关。"
            )
        }
        if (!permissions.granted(SMS_PERMISSION)) {
            return refused(
                AgentSmsReasonCode.PERMISSION_MISSING,
                "缺少系统权限：短信（READ_SMS）。",
                "在系统设置 → 应用 → MCA → 权限中允许「短信」。"
            )
        }
        val rawLimit = request.query[QUERY_LIMIT]
        val limit = if (rawLimit == null) {
            AgentSmsPolicy.DEFAULT_RESULTS
        } else {
            val parsed = rawLimit.trim().toIntOrNull()
                ?: return refused(AgentSmsReasonCode.QUERY_FAILED, "limit 必须是 1–${AgentSmsPolicy.MAX_RESULTS} 的整数。")
            AgentSmsPolicy.clampLimit(parsed)
        }
        val keyword = request.query[QUERY_KEYWORD]?.trim()?.takeIf(String::isNotEmpty)
        if (AgentSmsPolicy.keywordTooLong(keyword)) {
            return refused(
                AgentSmsReasonCode.QUERY_FAILED,
                "keyword 最多 ${AgentSmsPolicy.MAX_KEYWORD_CHARS} 个字符。"
            )
        }
        val outcome = smsConfirmationGate.confirm(
            detail = "DSH 请求只读查询最近 $limit 条短信" +
                (keyword?.let { "（关键词「$it」）" } ?: "") +
                "。只返回号码后 4 位、时间和 120 字预览，不发送、不修改、不保存。",
            port = smsConfirmation
        )
        when (outcome) {
            AgentSmsConfirmationOutcome.DENIED -> return refused(
                AgentSmsReasonCode.CONFIRMATION_DENIED,
                "本次短信查询被用户拒绝。",
                "需要查询时请再次发起，并在确认弹窗中选择允许。"
            )
            AgentSmsConfirmationOutcome.TIMED_OUT -> return refused(
                AgentSmsReasonCode.CONFIRMATION_DENIED,
                "确认弹窗在 ${
                    AgentSmsConfirmationGate.CONFIRMATION_TIMEOUT_MILLIS / 1000
                } 秒内没有得到响应，本次查询未执行。",
                "需要查询时请再次发起，并在确认弹窗中选择允许。"
            )
            AgentSmsConfirmationOutcome.ALLOWED,
            AgentSmsConfirmationOutcome.SKIPPED_BY_SESSION_SWITCH -> Unit
        }
        return when (val raw = sms.query(limit, keyword)) {
            is AgentSmsRawQueryResult.Failed -> refused(raw.reason, raw.detail)
            is AgentSmsRawQueryResult.Rows -> {
                val result = AgentSmsPolicy.query(raw.messages, limit, keyword)
                val messages = JSONArray()
                result.messages.forEach { message ->
                    messages.put(
                        JSONObject()
                            .put("sender", message.senderMasked)
                            .put("at", message.receivedAtMillis)
                            .put("preview", message.bodyPreview)
                    )
                }
                json(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("count", result.messages.size)
                        .put("limit", limit)
                        .put("scanned", result.scanned)
                        .put("filteredOut", result.filteredOut)
                        .put("messages", messages)
                )
            }
        }
    }

    /** Status document: consent + permission + platform support for each capability, no values. */
    private fun statusBody(): JSONObject {
        val sensorsJson = JSONArray()
        AgentSensorCatalog.all.forEach { capability ->
            sensorsJson.put(
                JSONObject()
                    .put("id", AgentSensorCatalog.wireName(capability.id))
                    .put("name", capability.displayName)
                    .put("mode", capability.readMode.name.lowercase())
                    .put("enabled", consent.sensorEnabled(capability.id))
                    .put("permission", capability.permission)
                    .put("permissionGranted", capability.permission == null || permissions.granted(capability.permission))
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("sensors", sensorsJson)
            .put("sms", JSONObject()
                .put("enabled", consent.smsReadEnabled())
                .put("permission", SMS_PERMISSION)
                .put("permissionGranted", permissions.granted(SMS_PERMISSION))
                .put("maxResults", AgentSmsPolicy.MAX_RESULTS)
            )
    }

    private fun rejectUnknownKeys(
        request: DshCapabilityRouteRequest,
        allowed: Set<String>
    ): DshCapabilityRouteResponse? {
        val unknown = request.query.keys - allowed
        if (unknown.isEmpty()) return null
        return refused(
            AgentSensorReasonCode.INVALID_REQUEST,
            "不支持的查询参数：${unknown.sorted().joinToString("、")}"
        )
    }

    private fun methodNotAllowed(detail: String): DshCapabilityRouteResponse =
        json(405, JSONObject().put("ok", false).put("errorCode", "INVALID_REQUEST").put("detail", detail))

    private fun refused(
        reason: AgentSensorReasonCode,
        detail: String,
        settingsHint: String? = null
    ): DshCapabilityRouteResponse = refused(reason.name, detail, settingsHint)

    private fun refused(
        reason: AgentSmsReasonCode,
        detail: String,
        settingsHint: String? = null
    ): DshCapabilityRouteResponse = refused(reason.name, detail, settingsHint)

    private fun refused(
        reason: String,
        detail: String,
        settingsHint: String? = null
    ): DshCapabilityRouteResponse {
        val body = JSONObject()
            .put("ok", false)
            .put("errorCode", reason)
            .put("detail", detail)
        settingsHint?.let { body.put("settingsHint", it) }
        return json(200, body)
    }

    private fun json(statusCode: Int, body: JSONObject): DshCapabilityRouteResponse =
        DshCapabilityRouteResponse.Json(statusCode, body.toString())

    companion object {
        const val SENSORS_PATH = "/sensors"
        const val TORCH_PATH = "/sensors/torch"
        const val SMS_PATH = "/sms"

        /** Same literal as `AgentSmsPermissions.READ_SMS`; pinned by the contract test. */
        const val SMS_PERMISSION = "android.permission.READ_SMS"

        private const val QUERY_ID = "id"
        private const val QUERY_ON = "on"
        private const val QUERY_LIMIT = "limit"
        private const val QUERY_KEYWORD = "keyword"

        fun paths(): List<String> = listOf(SENSORS_PATH, TORCH_PATH, SMS_PATH)

        /**
         * Verb per route: the two reads are GET; the torch switch is POST. A read-only token must
         * not be able to toggle hardware.
         */
        fun methodsFor(path: String): Set<DshCapabilityHttpMethod> = when (path) {
            SENSORS_PATH, SMS_PATH -> setOf(DshCapabilityHttpMethod.GET)
            TORCH_PATH -> setOf(DshCapabilityHttpMethod.POST)
            else -> throw IllegalArgumentException("unknown device-access route: $path")
        }

        /** Pure summary used by the console projection; no route instance needed. */
        fun summaryDetail(consent: AgentDeviceConsentGate): String {
            val enabled = AgentSensorCatalog.all.count { consent.sensorEnabled(it.id) }
            val smsState = if (consent.smsReadEnabled()) "on" else "off"
            return "device-access: sensors=$enabled/${AgentSensorCatalog.all.size} on, sms=$smsState"
        }
    }
}
