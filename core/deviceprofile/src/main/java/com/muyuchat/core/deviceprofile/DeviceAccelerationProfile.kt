package com.muyuchat.core.deviceprofile

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal val QNN_PLATFORM_RUNTIME_DIRECTORY_PREFIXES = listOf(
    "/vendor/",
    "/odm/",
    "/system/",
    "/system_ext/",
    "/product/"
)

enum class SnapdragonAccelerationTier(val label: String) {
    NONE("非骁龙 NPU 平台"),
    SNAPDRAGON_888("骁龙 888"),
    SNAPDRAGON_8_GEN1("骁龙 8 Gen 1"),
    SNAPDRAGON_8_GEN2("骁龙 8 Gen 2"),
    SNAPDRAGON_8_GEN3("骁龙 8 Gen 3"),
    SNAPDRAGON_8S_GEN4("骁龙 8s Gen 4"),
    SNAPDRAGON_8_ELITE("骁龙 8 Elite"),
    SNAPDRAGON_8_ELITE_GEN5("骁龙 8 Elite Gen 5"),
    SNAPDRAGON_OTHER("骁龙平台")
}

enum class AccelerationCapabilityStatus {
    UNSUPPORTED,
    DEVICE_CAPABLE_RUNTIME_MISSING,
    DEVICE_CAPABLE_RUNTIME_UNVERIFIED,
    DEVICE_CAPABLE_RUNTIME_LOAD_FAILED,
    DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED,
    EXPERIMENTAL_READY,
    READY
}

enum class QnnRuntimeProbeState {
    NOT_REQUESTED,
    FILES_MISSING,
    LOAD_FAILED,
    LOADABLE
}

data class AccelerationCapability(
    val label: String,
    val backend: String,
    val status: AccelerationCapabilityStatus,
    val reason: String,
    val minRamGb: Int = 0
) {
    val deviceCapable: Boolean
        get() = status != AccelerationCapabilityStatus.UNSUPPORTED

    val runtimeReady: Boolean
        get() = status == AccelerationCapabilityStatus.EXPERIMENTAL_READY ||
            status == AccelerationCapabilityStatus.READY

    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("backend", backend)
        .put("status", status.name.lowercase(Locale.US))
        .put("reason", reason)
        .put("minRamGb", minRamGb)
        .put("deviceCapable", deviceCapable)
        .put("runtimeReady", runtimeReady)
}

/**
 * A coherent QAIRT/QNN runtime is a host pair plus the DSP libraries for one
 * HTP architecture. Selecting libQnnSystem/libQnnHtp from one directory and
 * the numerically highest Skel from another can poison the process-level HTP
 * selection and make a later context load fail with incompatible binaries.
 */
data class QnnRuntimeProfile(
    val hostDirectory: File,
    val dspDirectory: File,
    val systemLibrary: File,
    val htpLibrary: File,
    val htpSkelLibrary: File,
    val htpStubLibrary: File?,
    val cdspRpcLibrary: File?,
    val htpArchVersion: Int
) {
    val runtimeDirectory: String
        get() = if (hostDirectory == dspDirectory) {
            hostDirectory.absolutePath
        } else {
            "host=${hostDirectory.absolutePath};dsp=${dspDirectory.absolutePath}"
        }
}

data class DeviceAccelerationProfile(
    val snapdragonTier: SnapdragonAccelerationTier,
    val chipsetCode: String,
    val qnnHtpGeneration: String,
    val qnnRuntime: QnnRuntimeStatus,
    val localChat: AccelerationCapability,
    val localVision: AccelerationCapability,
    val localImage: AccelerationCapability,
    val stableDiffusion15NpuCandidate: Boolean,
    val sdxlNpuCandidate: Boolean,
    val notes: List<String> = emptyList()
) {
    val supportsSnapdragonNpu: Boolean
        get() = snapdragonTier != SnapdragonAccelerationTier.NONE &&
            snapdragonTier != SnapdragonAccelerationTier.SNAPDRAGON_OTHER

    val summaryLabel: String
        get() {
            val npu = if (supportsSnapdragonNpu) "NPU candidate" else "CPU first"
            val runtime = when {
                qnnRuntime.transportDependencyBlocked -> "NPU device transport blocked"
                qnnRuntime.ready && !qnnRuntime.exactArchMatch -> "QNN HTP arch mismatch"
                qnnRuntime.loadable -> "QNN loadable"
                qnnRuntime.probeState == QnnRuntimeProbeState.LOAD_FAILED -> "QNN load failed"
                qnnRuntime.ready -> "QNN unverified"
                else -> "QNN runtime missing"
            }
            return "${snapdragonTier.label} · $npu · $runtime"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("snapdragonTier", snapdragonTier.name.lowercase(Locale.US))
        .put("snapdragonTierLabel", snapdragonTier.label)
        .put("chipsetCode", chipsetCode)
        .put("qnnHtpGeneration", qnnHtpGeneration)
        .put("supportsSnapdragonNpu", supportsSnapdragonNpu)
        .put("stableDiffusion15NpuCandidate", stableDiffusion15NpuCandidate)
        .put("sdxlNpuCandidate", sdxlNpuCandidate)
        .put("summaryLabel", summaryLabel)
        .put("qnnRuntime", qnnRuntime.toJson())
        .put("localChat", localChat.toJson())
        .put("localVision", localVision.toJson())
        .put("localImage", localImage.toJson())
        .put("notes", JSONArray(notes))

    companion object {
        val CpuOnly: DeviceAccelerationProfile = DeviceAccelerationProfile(
            snapdragonTier = SnapdragonAccelerationTier.NONE,
            chipsetCode = "",
            qnnHtpGeneration = "",
            qnnRuntime = QnnRuntimeStatus.Missing,
            localChat = AccelerationCapability(
                label = "MNN / GGUF CPU",
                backend = "mnn_cpu,llama.cpp",
                status = AccelerationCapabilityStatus.READY,
                reason = "本地聊天使用 CPU 稳定路径。"
            ),
            localVision = AccelerationCapability(
                label = "GGUF mmproj / MNN vision",
                backend = "llama.cpp,mnn_cpu",
                status = AccelerationCapabilityStatus.READY,
                reason = "本地识图可使用 CPU 兼容路径；NPU 需要 Qualcomm 设备和对应 runtime。"
            ),
            localImage = AccelerationCapability(
                label = "stable-diffusion.cpp CPU",
                backend = "stable-diffusion.cpp",
                status = AccelerationCapabilityStatus.READY,
                reason = "本地生图默认走 CPU 兼容路径，NPU 生图需要认证 QNN 生图包。"
            ),
            stableDiffusion15NpuCandidate = false,
            sdxlNpuCandidate = false
        )
    }
}
