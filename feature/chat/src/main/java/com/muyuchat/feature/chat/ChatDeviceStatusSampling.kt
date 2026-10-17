package com.muyuchat.feature.chat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.BatteryManager
import java.io.File

internal fun moveFloatingDeviceStatusFraction(current: Float, deltaPx: Float, travelPx: Float): Float =
    if (travelPx > 0f && travelPx.isFinite()) {
        (current.coerceIn(0f, 1f) + deltaPx / travelPx).coerceIn(0f, 1f)
    } else {
        0f
    }

internal fun Context.readChatDeviceMemorySample(): ChatDeviceMemorySample? = runCatching {
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    if (info.totalMem <= 0L) null else ChatDeviceMemorySample(
        totalKb = info.totalMem / 1024L,
        availableKb = info.availMem / 1024L,
        lowMemory = info.lowMemory
    )
}.getOrNull()

/** Reads whichever real thermal sensor is readable; zone numbers are never assumed. */
internal fun readChatDeviceTemperatureSample(
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = runCatching {
    if (!thermalRoot.isDirectory) return@runCatching null
    val zones = thermalRoot.listFiles { file ->
        file.isDirectory && file.name.startsWith("thermal_zone")
    }.orEmpty()
    val candidates = zones.mapNotNull { zone ->
        val raw = zone.resolve("temp").readText().trim().toFloatOrNull() ?: return@mapNotNull null
        if (!raw.isFinite()) return@mapNotNull null
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        if (!celsius.isFinite() || celsius !in -40f..150f) return@mapNotNull null
        val type = zone.resolve("type").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val normalized = type.lowercase()
        val priority = when {
            normalized.contains("soc") -> 0
            normalized.contains("cpu") -> 1
            normalized.contains("gpu") -> 2
            normalized.contains("tsens") -> 3
            normalized.isNotBlank() -> 4
            else -> 5
        }
        Triple(priority, celsius, type.ifBlank { zone.name })
    }
    candidates.minWithOrNull(compareBy<Triple<Int, Float, String>> { it.first }.thenBy { it.third })
        ?.let { ChatDeviceTemperatureSample(it.second, it.third) }
}.getOrNull()

/** Battery temperature is the fallback exposed by Android when vendor thermal zones are hidden. */
internal fun batteryTemperatureSample(rawTenthsCelsius: Int): ChatDeviceTemperatureSample? {
    if (rawTenthsCelsius == Int.MIN_VALUE) return null
    val celsius = rawTenthsCelsius / 10f
    return celsius.takeIf { it.isFinite() && it in -40f..100f }
        ?.let { ChatDeviceTemperatureSample(it, "battery") }
}

internal fun readChatDeviceTemperatureSample(
    context: Context,
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = readChatDeviceTemperatureSample(thermalRoot) ?: runCatching {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    batteryTemperatureSample(
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    )
}.getOrNull()
