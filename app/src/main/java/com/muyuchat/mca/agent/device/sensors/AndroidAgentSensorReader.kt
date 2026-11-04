package com.muyuchat.mca.agent.device.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.muyuchat.mca.agent.device.AgentDeviceAccessStore
import com.muyuchat.mca.agent.device.AgentSensorSource
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single-shot Android implementation of [AgentSensorSource].
 *
 * Every read registers at most one listener, waits for the first event (bounded by
 * [SAMPLE_TIMEOUT_MILLIS]), unregisters in `finally`, and returns a typed result. There is no
 * long-lived subscription, no background service and no cached value: an authorised capability can
 * only cost one sample per request.
 *
 * A platform failure (missing sensor, security exception, no cached fix, occupied flash unit) is
 * mapped to the matching [AgentSensorReasonCode]; nothing here throws into the route.
 */
class AndroidAgentSensorReader(
    context: Context,
    private val accessStore: AgentDeviceAccessStore = AgentDeviceAccessStore(context)
) : AgentSensorSource {

    private val appContext = context.applicationContext
    private val sensors = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    override suspend fun read(id: AgentSensorId): AgentSensorResult {
        val capability = AgentSensorCatalog.capability(id)
        return when (capability.readMode) {
            AgentSensorReadMode.SAMPLE -> readSample(capability)
            AgentSensorReadMode.DAILY_TOTAL -> readStepCounter(capability)
            AgentSensorReadMode.LAST_KNOWN_LOCATION -> readLastKnownLocation(capability)
            AgentSensorReadMode.TOGGLE -> AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.INVALID_REQUEST,
                detail = "「${capability.displayName}」是开关，请使用 /sensors/torch。"
            )
        }
    }

    override suspend fun setTorch(on: Boolean): AgentSensorResult {
        if (!accessStore.sensorEnabled(AgentSensorId.TORCH)) {
            // Defense in depth: the route already checked consent, the reader re-checks it so no
            // future caller can reach the camera service without the user's switch.
            return AgentSensorReadings.evaluateTorch(
                consentEnabled = false,
                requestedOn = on,
                platformState = null
            )
        }
        val manager = cameraManager ?: return AgentSensorReadings.evaluateTorch(
            consentEnabled = true,
            requestedOn = on,
            platformState = null,
            failureDetail = "设备没有相机服务，无法控制闪光灯。"
        )
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return AgentSensorReadings.evaluateTorch(
            consentEnabled = true,
            requestedOn = on,
            platformState = null,
            failureDetail = "设备没有可用的闪光灯。"
        )
        var registered: CameraManager.TorchCallback? = null
        val state = try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(TORCH_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine { continuation ->
                        val callback = object : CameraManager.TorchCallback() {
                            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                                if (continuation.isActive) continuation.resume(enabled)
                            }

                            override fun onTorchModeUnavailable(cameraId: String) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        registered = callback
                        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                        continuation.invokeOnCancellation {
                            runCatching { manager.unregisterTorchCallback(callback) }
                        }
                        try {
                            manager.setTorchMode(cameraId, on)
                        } catch (_: Throwable) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }
        } finally {
            registered?.let { callback -> runCatching { manager.unregisterTorchCallback(callback) } }
        }
        return AgentSensorReadings.evaluateTorch(
            consentEnabled = true,
            requestedOn = on,
            platformState = state,
            failureDetail = "闪光灯当前不可用（可能被其它应用占用，或系统拒绝了切换）。"
        )
    }

    private suspend fun readSample(capability: AgentSensorCapability): AgentSensorResult {
        val manager = sensors ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "设备没有传感器服务。"
        )
        val sensorType = when (capability.id) {
            AgentSensorId.LIGHT -> Sensor.TYPE_LIGHT
            AgentSensorId.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            AgentSensorId.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
            AgentSensorId.MAGNETOMETER -> Sensor.TYPE_MAGNETIC_FIELD
            AgentSensorId.BAROMETER -> Sensor.TYPE_PRESSURE
            else -> return AgentSensorResult.Refused(
                AgentSensorReasonCode.SENSOR_UNAVAILABLE,
                "「${capability.displayName}」不是单值传感器。"
            )
        }
        val sensor = manager.getDefaultSensor(sensorType) ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "设备没有「${capability.displayName}」传感器。"
        )
        val sample = awaitFirstEvent(manager, sensor) ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "「${capability.displayName}」在 ${SAMPLE_TIMEOUT_MILLIS}ms 内没有返回数据。"
        )
        val value = when (capability.id) {
            AgentSensorId.LIGHT, AgentSensorId.BAROMETER -> sample.values.firstOrNull()?.toDouble()
            AgentSensorId.ACCELEROMETER -> vectorMagnitude(sample.values)
            AgentSensorId.GYROSCOPE -> vectorMagnitude(sample.values)
            AgentSensorId.MAGNETOMETER -> vectorMagnitude(sample.values)
            else -> sample.values.firstOrNull()?.toDouble()
        }
        return AgentSensorReadings.evaluate(
            capability = capability,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(
                value = value,
                capturedAtMillis = sample.timestampMillis,
                detail = if (capability.id in VECTOR_SENSORS) {
                    "三轴合矢量（x=${sample.values.getOrNull(0)} y=${sample.values.getOrNull(1)} " +
                        "z=${sample.values.getOrNull(2)}）"
                } else {
                    null
                }
            )
        )
    }

    private suspend fun readStepCounter(capability: AgentSensorCapability): AgentSensorResult {
        val manager = sensors ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "设备没有传感器服务。"
        )
        // ACTIVITY_RECOGNITION is a runtime permission only from Android 10 on; older releases
        // expose the counter without a grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.PERMISSION_MISSING,
                detail = "读取步数需要「${capability.permissionLabel}」权限。",
                settingsHint = "在系统设置 → 应用 → MCA → 权限中允许「${capability.permissionLabel}」。"
            )
        }
        val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "设备没有计步传感器。"
        )
        val sample = awaitFirstEvent(manager, sensor) ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.SENSOR_UNAVAILABLE,
            "计步传感器在 ${SAMPLE_TIMEOUT_MILLIS}ms 内没有返回数据。"
        )
        val counter = sample.values.firstOrNull()?.toInt()
        val today = LocalDate.now().toEpochDay()
        val baseline = accessStore.stepBaseline()
        val policy = AgentStepCounterPolicy.dailySteps(today, counter, baseline)
        policy.nextBaseline?.let { accessStore.setStepBaseline(it) }
        return AgentSensorReadings.evaluate(
            capability = capability,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(
                value = policy.steps.toDouble(),
                capturedAtMillis = sample.timestampMillis,
                detail = policy.detail
            )
        )
    }

    private suspend fun readLastKnownLocation(capability: AgentSensorCapability): AgentSensorResult {
        val manager = locationManager ?: return AgentSensorResult.Refused(
            AgentSensorReasonCode.LOCATION_UNAVAILABLE,
            "设备没有定位服务。"
        )
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.PERMISSION_MISSING,
                detail = "读取最近已知位置需要「${capability.permissionLabel}」权限。",
                settingsHint = "在系统设置 → 应用 → MCA → 权限中允许「${capability.permissionLabel}」。"
            )
        }
        if (!runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)) {
            return AgentSensorResult.Refused(
                reason = AgentSensorReasonCode.LOCATION_UNAVAILABLE,
                detail = "系统定位已关闭，无法读取最近已知位置。",
                settingsHint = "在系统设置中打开「位置信息」。"
            )
        }
        val location = withContext(Dispatchers.IO) {
            runCatching {
                manager.getProviders(true)
                    .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                    .maxByOrNull { it.time }
            }.getOrNull()
        } ?: return AgentSensorResult.Refused(
            reason = AgentSensorReasonCode.LOCATION_UNAVAILABLE,
            detail = "没有可用的最近已知位置。",
            settingsHint = "请先在地图类应用中获得一次定位，或打开系统定位。"
        )
        return AgentSensorReadings.evaluate(
            capability = capability,
            consentEnabled = true,
            permissionGranted = true,
            sample = AgentSensorSample(
                value = location.latitude,
                secondValue = location.longitude,
                accuracyMeters = location.accuracy,
                capturedAtMillis = location.time,
                detail = "来源 ${location.provider ?: "unknown"}，最近已知位置，未发起新定位"
            )
        )
    }

    private suspend fun awaitFirstEvent(
        manager: SensorManager,
        sensor: Sensor
    ): SensorSample? = withTimeoutOrNull(SAMPLE_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!continuation.isActive) return
                    manager.unregisterListener(this)
                    continuation.resume(
                        SensorSample(
                            values = event.values.copyOf(),
                            timestampMillis = System.currentTimeMillis()
                        )
                    )
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            continuation.invokeOnCancellation { manager.unregisterListener(listener) }
            val registered = manager.registerListener(
                listener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                Handler(Looper.getMainLooper())
            )
            if (!registered && continuation.isActive) {
                manager.unregisterListener(listener)
                continuation.resume(null)
            }
        }
    }

    private fun vectorMagnitude(values: FloatArray): Double {
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value.toDouble()
        return kotlin.math.sqrt(sum)
    }

    private data class SensorSample(val values: FloatArray, val timestampMillis: Long)

    private companion object {
        const val SAMPLE_TIMEOUT_MILLIS = 2_000L
        const val TORCH_TIMEOUT_MILLIS = 2_000L

        val VECTOR_SENSORS = setOf(
            AgentSensorId.ACCELEROMETER,
            AgentSensorId.GYROSCOPE,
            AgentSensorId.MAGNETOMETER
        )
    }
}
