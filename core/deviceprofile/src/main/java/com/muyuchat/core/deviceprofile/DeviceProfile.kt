package com.muyuchat.core.deviceprofile

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.muyuchat.core.telemetry.SocDetector
import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.telemetry.SystemMemoryReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

data class DeviceProfile(
    val socManufacturer: String,
    val socModel: String,
    val socFamily: SocFamily,
    val cpuCores: Int,
    val estimatedBigCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val storageFreeBytes: Long,
    val androidApi: Int,
    val thermalStatus: ThermalStatus,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val batteryTemperatureC: Float? = null,
    val supportedAbis: List<String>,
    val primaryAbi: String,
    val advertisedRamBytes: Long = 0,
    val memoryThresholdBytes: Long = 0,
    val isLowMemory: Boolean = false,
    val procMemAvailableBytes: Long = 0,
    val procMemFreeBytes: Long = 0,
    val cachedBytes: Long = 0,
    val reclaimableBytes: Long = 0,
    val swapFreeBytes: Long = 0,
    val swapTotalBytes: Long = 0,
    val modelMemoryBudgetBytes: Long = 0,
    val accelerationProfile: DeviceAccelerationProfile = DeviceAccelerationProfile.CpuOnly
) {
    val socLabel: String
        get() = DeviceAccelerationAnalyzer.publicChipsetDisplayName(
            chipsetIdentity = accelerationProfile.chipsetCode.ifBlank {
                listOf(socManufacturer, socModel).filter(String::isNotBlank).joinToString(" ")
            },
            family = socFamily
        )

    val displayTotalRamBytes: Long
        get() = advertisedRamBytes.takeIf { it > 0L } ?: totalRamBytes

    fun toJson(): JSONObject = JSONObject()
        .put("socManufacturer", socManufacturer)
        .put("socModel", socModel)
        .put("socFamily", socFamily.name.lowercase())
        .put("socLabel", socLabel)
        .put("cpuCores", cpuCores)
        .put("estimatedBigCores", estimatedBigCores)
        .put("totalRamBytes", totalRamBytes)
        .put("availableRamBytes", availableRamBytes)
        .put("storageFreeBytes", storageFreeBytes)
        .put("androidApi", androidApi)
        .put("thermalStatus", thermalStatus.name.lowercase())
        .put("batteryPercent", batteryPercent)
        .put("isCharging", isCharging)
        .put("batteryTemperatureC", batteryTemperatureC ?: JSONObject.NULL)
        .put("supportedAbis", JSONArray(supportedAbis))
        .put("primaryAbi", primaryAbi)
        .put("advertisedRamBytes", advertisedRamBytes)
        .put("displayTotalRamBytes", displayTotalRamBytes)
        .put("memoryThresholdBytes", memoryThresholdBytes)
        .put("isLowMemory", isLowMemory)
        .put("procMemAvailableBytes", procMemAvailableBytes)
        .put("procMemFreeBytes", procMemFreeBytes)
        .put("cachedBytes", cachedBytes)
        .put("reclaimableBytes", reclaimableBytes)
        .put("swapFreeBytes", swapFreeBytes)
        .put("swapTotalBytes", swapTotalBytes)
        .put("modelMemoryBudgetBytes", modelMemoryBudgetBytes)
        .put("accelerationProfile", accelerationProfile.toJson())
}

enum class ThermalStatus {
    Unknown,
    None,
    Light,
    Moderate,
    Severe,
    Critical,
    Emergency,
    Shutdown
}

class DeviceProfileReader(private val context: Context) {
    private val appContext = context.applicationContext

    fun read(): DeviceProfile {
        val soc = SocDetector.detect()
        val memory = SystemMemoryReader.read(appContext)
        val battery = batteryInfo()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val chipsetCode = DeviceAccelerationAnalyzer.normalizeChipsetCode("${soc.manufacturer} ${soc.model}")
        val acceleration = DeviceAccelerationAnalyzer.assess(
            soc = soc,
            totalRamBytes = memory.advertisedBytes.takeIf { it > 0L } ?: memory.totalBytes,
            qnnRuntime = QnnRuntimeStatus.inspect(
                searchDirectories = qnnRuntimeSearchDirectories(),
                probeLibraries = true,
                preferredHtpArchVersion = DeviceAccelerationAnalyzer
                    .expectedQnnHtpArchVersionForChipsetCode(chipsetCode)
            )
        )
        return DeviceProfile(
            socManufacturer = soc.manufacturer,
            socModel = soc.model,
            socFamily = soc.family,
            cpuCores = cores,
            estimatedBigCores = estimateBigCores(cores),
            totalRamBytes = memory.totalBytes,
            availableRamBytes = memory.availableBytes,
            storageFreeBytes = storageFreeBytes(),
            androidApi = Build.VERSION.SDK_INT,
            thermalStatus = thermalStatus(),
            batteryPercent = battery.percent,
            isCharging = battery.isCharging,
            batteryTemperatureC = battery.temperatureC,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            advertisedRamBytes = memory.advertisedBytes,
            memoryThresholdBytes = memory.thresholdBytes,
            isLowMemory = memory.lowMemory,
            procMemAvailableBytes = memory.procMemAvailableBytes,
            procMemFreeBytes = memory.procMemFreeBytes,
            cachedBytes = memory.procCachedBytes,
            reclaimableBytes = memory.reclaimableBytes,
            swapFreeBytes = memory.procSwapFreeBytes,
            swapTotalBytes = memory.procSwapTotalBytes,
            modelMemoryBudgetBytes = memory.modelBudgetBytes,
            accelerationProfile = acceleration
        )
    }

    private fun qnnRuntimeSearchDirectories(): List<File> =
        buildList {
            fun addPrivateRuntimeRoot(root: File) {
                // Versioned content-addressed runtime stages live one level
                // below their root. Add both layouts so existing private
                // deployments keep working while image bundles can be staged
                // atomically into code_cache.
                add(root)
                root.listFiles()
                    ?.filter(File::isDirectory)
                    ?.forEach(::add)
            }

            addPrivateRuntimeRoot(File(appContext.codeCacheDir, "qnn-image-runtime"))
            addPrivateRuntimeRoot(File(appContext.filesDir, "qnnlibs"))
            addPrivateRuntimeRoot(File(appContext.filesDir, "runtime_libs"))
            add(File(appContext.applicationInfo.nativeLibraryDir))
            // Do not discover QNN libraries from external storage or
            // /data/local/tmp. Android's linker namespace/SELinux policy lets
            // a debug shell inspect them but does not let an application map
            // them. Selecting one would therefore make a coherent profile
            // appear available and then fail deterministically at dlopen.
            // App-private, packaged, and OEM runtime locations remain valid.
            add(File("/vendor/lib64"))
            add(File("/vendor/lib/rfsa/adsp"))
            add(File("/odm/lib64"))
            add(File("/system/lib64"))
            add(File("/system_ext/lib64"))
            add(File("/product/lib64"))
        }

    private fun batteryInfo(): BatterySnapshot {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val rawTemperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val temperatureC = rawTemperature
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
        return BatterySnapshot(
            percent = percent,
            isCharging = charging,
            temperatureC = temperatureC
        )
    }

    private fun storageFreeBytes(): Long {
        val modelsDir = appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
        return max(modelsDir.usableSpace, appContext.filesDir.usableSpace)
    }

    private fun thermalStatus(): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.Unknown
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.None
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.Light
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.Moderate
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.Severe
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.Critical
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.Emergency
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.Shutdown
            else -> ThermalStatus.Unknown
        }
    }

    private fun estimateBigCores(cores: Int): Int {
        val maxFreqs = File("/sys/devices/system/cpu")
            .listFiles { file -> file.name.matches(Regex("cpu\\d+")) }
            ?.mapNotNull { cpu ->
                File(cpu, "cpufreq/cpuinfo_max_freq").runCatchingReadLong()
            }
            .orEmpty()
        if (maxFreqs.size < 2) return (cores / 2).coerceAtLeast(1)
        val median = maxFreqs.sorted()[maxFreqs.size / 2]
        return maxFreqs.count { it >= median }.coerceIn(1, cores)
    }

    private fun File.runCatchingReadLong(): Long? = runCatching {
        readText().trim().toLong()
    }.getOrNull()

    private data class BatterySnapshot(
        val percent: Int,
        val isCharging: Boolean,
        val temperatureC: Float?
    )
}
