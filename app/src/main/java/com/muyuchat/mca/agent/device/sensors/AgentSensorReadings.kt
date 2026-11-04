package com.muyuchat.mca.agent.device.sensors

/**
 * Typed results of the sensor lane.
 *
 * The reason codes are the only vocabulary DSH sees when a read cannot happen, so "not granted"
 * can never be mistaken for a real value and the tool can explain the situation to the user.
 * Every refusal carries a user-facing [AgentSensorRefusal.settingsHint] when the fix lives in
 * Android Settings (permission), instead of crashing or silently returning a placeholder.
 */
enum class AgentSensorReasonCode {
    /** The user has not switched this capability on in Settings. */
    NOT_GRANTED,

    /** Switched on, but the Android runtime permission is missing. */
    PERMISSION_MISSING,

    /** The device has no such sensor, or the sensor returned no event in the read window. */
    SENSOR_UNAVAILABLE,

    /** Location: no cached fix exists, or location services are off. */
    LOCATION_UNAVAILABLE,

    /** Torch: no flash unit, or the camera service refused the request. */
    TORCH_UNAVAILABLE,

    /** The request itself was malformed (unknown id, missing/!invalid `on` flag). */
    INVALID_REQUEST
}

/** One successful single-shot reading. [capturedAtMillis] is the event's own timestamp. */
data class AgentSensorReading(
    val sensorId: AgentSensorId,
    val value: Double,
    val unit: String,
    val capturedAtMillis: Long,
    val detail: String? = null
)

/** A torch command result: the switch's real state after the call, not the requested value. */
data class AgentTorchState(
    val enabled: Boolean,
    val detail: String? = null
)

/** Success or a typed refusal; there is no third "unknown but pretend ok" case. */
sealed interface AgentSensorResult {
    data class Value(val reading: AgentSensorReading) : AgentSensorResult
    data class Toggled(val state: AgentTorchState) : AgentSensorResult
    data class Refused(
        val reason: AgentSensorReasonCode,
        val detail: String,
        val settingsHint: String? = null
    ) : AgentSensorResult
}

/** Raw platform sample handed to the pure evaluator; nulls mean "the platform had nothing". */
data class AgentSensorSample(
    val value: Double? = null,
    /** Location only: coordinates and accuracy (metres). */
    val secondValue: Double? = null,
    val accuracyMeters: Float? = null,
    val capturedAtMillis: Long = 0L,
    val detail: String? = null
)

/**
 * Pure sensor decision matrix.
 *
 * Order of checks is the product rule: consent first (user switch), then the runtime permission,
 * then the platform sample. A value is only ever fabricated by the platform, never here; when the
 * sample is missing the result is a typed refusal.
 *
 * Clamping is deliberately narrow and observable:
 *  - the step counter is a monotonically increasing boot counter, so a negative reading is clamped
 *    to 0 (Some OEMs reset it to -1 before the first event) and the raw value is kept in `detail`;
 *  - latitude/longitude outside the valid range is a refusal, not a clamped coordinate, because a
 *    clamped coordinate is a *wrong* location rather than a rounded one.
 */
object AgentSensorReadings {

    /** A step counter larger than this is treated as a sensor fault rather than "today's steps". */
    const val MAX_DAILY_STEPS: Int = 1_000_000

    fun evaluate(
        capability: AgentSensorCapability,
        consentEnabled: Boolean,
        permissionGranted: Boolean,
        sample: AgentSensorSample?
    ): AgentSensorResult {
        if (!consentEnabled) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.NOT_GRANTED,
                detail = "「${capability.displayName}」未在设置中授权，DSH 不能读取。",
                settingsHint = "在 MCA 设置 → 隐私与数据 → 设备传感器与位置中打开「${capability.displayName}」。"
            )
        }
        if (capability.permission != null && !permissionGranted) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.PERMISSION_MISSING,
                detail = "「${capability.displayName}」缺少系统权限：${capability.permissionLabel}。",
                settingsHint = "在系统设置 → 应用 → MCA → 权限中允许「${capability.permissionLabel}」。"
            )
        }
        if (sample == null) {
            return when (capability.readMode) {
                AgentSensorReadMode.LAST_KNOWN_LOCATION -> AgentSensorResult.Refused(
                    reason = AgentSensorReasonCode.LOCATION_UNAVAILABLE,
                    detail = "没有可用的最近已知位置；请先在地图类应用中获得一次定位，或打开系统定位。",
                    settingsHint = "在系统设置中打开「位置信息」，并允许 MCA 使用位置。"
                )
                AgentSensorReadMode.TOGGLE -> AgentSensorResult.Refused(
                    reason = AgentSensorReasonCode.TORCH_UNAVAILABLE,
                    detail = "未找到可用的闪光灯。"
                )
                else -> AgentSensorResult.Refused(
                    reason = AgentSensorReasonCode.SENSOR_UNAVAILABLE,
                    detail = "设备没有返回「${capability.displayName}」数据（可能没有该传感器或当前不可用）。"
                )
            }
        }
        return when (capability.readMode) {
            AgentSensorReadMode.SAMPLE -> {
                val value = sample.value ?: return AgentSensorResult.Refused(
                    AgentSensorReasonCode.SENSOR_UNAVAILABLE,
                    "设备没有返回「${capability.displayName}」数据。"
                )
                AgentSensorResult.Value(
                    AgentSensorReading(
                        sensorId = capability.id,
                        value = value,
                        unit = capability.unit,
                        capturedAtMillis = sample.capturedAtMillis,
                        detail = sample.detail
                    )
                )
            }
            AgentSensorReadMode.DAILY_TOTAL -> evaluateStepCounter(capability, sample)
            AgentSensorReadMode.LAST_KNOWN_LOCATION -> evaluateLocation(capability, sample)
            AgentSensorReadMode.TOGGLE -> AgentSensorResult.Refused(
                AgentSensorReasonCode.INVALID_REQUEST,
                "「${capability.displayName}」是开关，不是读取项；请使用 /sensors/torch。"
            )
        }
    }

    /** Torch switch: consent first, then the platform's real post-command state. */
    fun evaluateTorch(
        consentEnabled: Boolean,
        requestedOn: Boolean,
        platformState: Boolean?,
        failureDetail: String? = null
    ): AgentSensorResult {
        if (!consentEnabled) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.NOT_GRANTED,
                detail = "「手电筒」未在设置中授权，DSH 不能开关闪光灯。",
                settingsHint = "在 MCA 设置 → 隐私与数据 → 设备传感器与位置中打开「手电筒」。"
            )
        }
        if (platformState == null) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.TORCH_UNAVAILABLE,
                detail = failureDetail ?: "闪光灯不可用（可能被其它应用占用或设备没有闪光灯）。"
            )
        }
        return AgentSensorResult.Toggled(AgentTorchState(enabled = platformState))
    }

    private fun evaluateStepCounter(
        capability: AgentSensorCapability,
        sample: AgentSensorSample
    ): AgentSensorResult {
        val raw = sample.value ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "设备没有返回步数。"
        )
        val clamped = raw.toInt().coerceIn(0, MAX_DAILY_STEPS)
        return AgentSensorResult.Value(
            AgentSensorReading(
                sensorId = capability.id,
                value = clamped.toDouble(),
                unit = capability.unit,
                capturedAtMillis = sample.capturedAtMillis,
                detail = if (clamped.toDouble() != raw) "原始步数 $raw 已钳制为 $clamped" else sample.detail
            )
        )
    }

    private fun evaluateLocation(
        capability: AgentSensorCapability,
        sample: AgentSensorSample
    ): AgentSensorResult {
        val latitude = sample.value
        val longitude = sample.secondValue
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite()) {
            return AgentSensorResult.Refused(
                AgentSensorReasonCode.LOCATION_UNAVAILABLE,
                "最近已知位置没有有效坐标。",
                "请先打开系统定位并获得一次定位。"
            )
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return AgentSensorResult.Refused(
                AgentSensorReasonCode.LOCATION_UNAVAILABLE,
                "最近已知位置坐标越界（$latitude, $longitude）。"
            )
        }
        val accuracy = sample.accuracyMeters
        val detail = if (accuracy != null && accuracy.isFinite()) {
            "精度约 ${accuracy.toInt()} 米（最近已知位置，未发起新定位）"
        } else {
            "最近已知位置，未发起新定位"
        }
        return AgentSensorResult.Value(
            AgentSensorReading(
                sensorId = capability.id,
                // A single numeric channel keeps the wire shape uniform: "lat,lon".
                value = latitude,
                unit = capability.unit,
                capturedAtMillis = sample.capturedAtMillis,
                detail = "$detail；经度 $longitude"
            )
        )
    }
}
