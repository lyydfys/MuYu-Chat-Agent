package com.muyuchat.core.deviceprofile

import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.telemetry.SocInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

enum class SnapdragonAccelerationTier(val label: String) {
    NONE("非骁龙 NPU 平台"),
    SNAPDRAGON_8_GEN1("骁龙 8 Gen 1"),
    SNAPDRAGON_8_GEN2("骁龙 8 Gen 2"),
    SNAPDRAGON_8_GEN3("骁龙 8 Gen 3"),
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

/** Selects an exact, internally coherent QNN profile for a public chipset. */
object QnnRuntimeProfileSelector {
    fun htpArchVersionForChipsetCode(chipsetCode: String): Int? =
        when (chipsetCode.trim().uppercase(Locale.US)) {
            "SM8350" -> 68
            "SM8450", "SM8475" -> 69
            "SM8550", "SM8550P", "QCS8550", "QCM8550" -> 73
            "SM8635", "SM8650", "SM8650P" -> 75
            "SM8750", "SM8750P" -> 79
            "SM8850", "SM8850P" -> 81
            else -> null
        }

    fun htpArchVersionForSocModel(socModel: Int): Int? =
        when (socModel) {
            30 -> 68
            36, 42 -> 69
            43 -> 73
            57, 68 -> 75
            69 -> 79
            87 -> 81
            else -> null
        }

    fun select(
        searchDirectories: List<File>,
        preferredHtpArchVersion: Int? = null
    ): QnnRuntimeProfile? {
        val dirs = searchDirectories
            .mapNotNull { directory -> runCatching { directory.canonicalFile }.getOrNull() }
            .filter(File::isDirectory)
            .distinctBy { directory -> directory.absolutePath }
        val hosts = dirs.mapNotNull { directory ->
            val system = File(directory, "libQnnSystem.so")
            val htp = File(directory, "libQnnHtp.so")
            if (system.isFile && htp.isFile) HostCandidate(directory, system, htp) else null
        }
        if (hosts.isEmpty()) return null

        val versions = preferredHtpArchVersion?.let(::listOf) ?: dirs
            .flatMap { directory -> directory.listFiles()?.asList().orEmpty() }
            .mapNotNull { file -> htpLibraryVersion(file.name) }
            .distinct()
            .sortedDescending()

        versions.forEach { archVersion ->
            val dspCandidates = dirs.mapNotNull { directory ->
                val skel = File(directory, "libQnnHtpV${archVersion}Skel.so")
                if (!skel.isFile) {
                    null
                } else {
                    DspCandidate(
                        directory = directory,
                        skel = skel,
                        stub = File(directory, "libQnnHtpV${archVersion}Stub.so").takeIf(File::isFile)
                    )
                }
            }
            hosts.forEach { host ->
                dspCandidates.firstOrNull { dsp -> mayPair(host.directory, dsp.directory) }?.let { dsp ->
                    return QnnRuntimeProfile(
                        hostDirectory = host.directory,
                        dspDirectory = dsp.directory,
                        systemLibrary = host.system,
                        htpLibrary = host.htp,
                        htpSkelLibrary = dsp.skel,
                        htpStubLibrary = dsp.stub,
                        cdspRpcLibrary = File(host.directory, "libcdsprpc.so").takeIf(File::isFile),
                        htpArchVersion = archVersion
                    )
                }
            }
        }
        return null
    }

    private data class HostCandidate(
        val directory: File,
        val system: File,
        val htp: File
    )

    private data class DspCandidate(
        val directory: File,
        val skel: File,
        val stub: File?
    )

    private fun htpLibraryVersion(name: String): Int? =
        Regex("""^libQnnHtpV(\d+)(?:Skel|Stub)\.so$""")
            .matchEntire(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    private fun mayPair(hostDirectory: File, dspDirectory: File): Boolean {
        if (hostDirectory == dspDirectory) return true
        return isPlatformDirectory(hostDirectory) && isPlatformDirectory(dspDirectory)
    }

    private fun isPlatformDirectory(directory: File): Boolean {
        val path = directory.absolutePath.replace('\\', '/')
        return path.startsWith("/vendor/") ||
            path.startsWith("/odm/") ||
            path.startsWith("/system/") ||
            path.startsWith("/system_ext/") ||
            path.startsWith("/product/")
    }
}

data class QnnRuntimeStatus(
    val qnnSystemLibraryPresent: Boolean,
    val qnnHtpLibraryPresent: Boolean,
    val htpSkelLibraryPresent: Boolean,
    val htpStubLibraryPresent: Boolean = false,
    val cdspRpcLibraryPresent: Boolean = false,
    val cdspRpcLibraryLoadable: Boolean = false,
    val searchDirectories: List<String> = emptyList(),
    val qnnSystemLibraryPath: String? = null,
    val qnnHtpLibraryPath: String? = null,
    val htpSkelLibraryPath: String? = null,
    val htpStubLibraryPath: String? = null,
    val cdspRpcLibraryPath: String? = null,
    val cdspRpcMessage: String = "",
    val htpArchVersion: Int = 0,
    val runtimeDirectory: String? = null,
    val dspRuntimeDirectory: String? = null,
    val probeState: QnnRuntimeProbeState = QnnRuntimeProbeState.NOT_REQUESTED,
    val probeMessage: String = ""
) {
    val ready: Boolean
        get() = qnnSystemLibraryPresent && qnnHtpLibraryPresent && htpSkelLibraryPresent

    val loadable: Boolean
        get() = probeState == QnnRuntimeProbeState.LOADABLE

    val hostRuntimeLoadable: Boolean
        get() = loadable

    val htpTransportVerified: Boolean
        get() = ready && htpStubLibraryPresent && loadable

    val transportDependencyBlocked: Boolean
        get() = probeState == QnnRuntimeProbeState.LOAD_FAILED &&
            (probeMessage.contains("libcdsprpc", ignoreCase = true) ||
                probeMessage.contains("libhidlbase", ignoreCase = true))

    val usableForSmoke: Boolean
        get() = ready &&
            htpStubLibraryPresent &&
            probeState == QnnRuntimeProbeState.LOADABLE &&
            !transportDependencyBlocked

    fun toJson(): JSONObject = JSONObject()
        .put("ready", ready)
        .put("loadable", loadable)
        .put("usableForSmoke", usableForSmoke)
        .put("qnnSystemLibraryPresent", qnnSystemLibraryPresent)
        .put("qnnHtpLibraryPresent", qnnHtpLibraryPresent)
        .put("htpSkelLibraryPresent", htpSkelLibraryPresent)
        .put("htpStubLibraryPresent", htpStubLibraryPresent)
        .put("cdspRpcLibraryPresent", cdspRpcLibraryPresent)
        .put("cdspRpcLibraryLoadable", cdspRpcLibraryLoadable)
        .put("hostRuntimeLoadable", hostRuntimeLoadable)
        .put("htpTransportVerified", htpTransportVerified)
        .put("transportDependencyBlocked", transportDependencyBlocked)
        .put("qnnSystemLibraryPath", qnnSystemLibraryPath)
        .put("qnnHtpLibraryPath", qnnHtpLibraryPath)
        .put("htpSkelLibraryPath", htpSkelLibraryPath)
        .put("htpStubLibraryPath", htpStubLibraryPath)
        .put("cdspRpcLibraryPath", cdspRpcLibraryPath)
        .put("cdspRpcMessage", cdspRpcMessage)
        .put("htpArchVersion", htpArchVersion)
        .put("runtimeDirectory", runtimeDirectory)
        .put("dspRuntimeDirectory", dspRuntimeDirectory)
        .put("probeState", probeState.name.lowercase(Locale.US))
        .put("probeMessage", probeMessage)
        .put("searchDirectories", JSONArray(searchDirectories))

    companion object {
        val Missing: QnnRuntimeStatus = QnnRuntimeStatus(
            qnnSystemLibraryPresent = false,
            qnnHtpLibraryPresent = false,
            htpSkelLibraryPresent = false,
            probeState = QnnRuntimeProbeState.FILES_MISSING,
            probeMessage = "QNN runtime libraries were not found."
        )

        fun inspect(
            searchDirectories: List<File>,
            probeLibraries: Boolean = false,
            preferredHtpArchVersion: Int? = null,
            libraryLoader: (String) -> Unit = { path -> System.load(path) }
        ): QnnRuntimeStatus {
            val dirs = searchDirectories
                .mapNotNull { dir -> runCatching { dir.canonicalFile }.getOrNull() }
                .distinctBy { it.absolutePath }
            val profile = QnnRuntimeProfileSelector.select(dirs, preferredHtpArchVersion)
            val filesReady = profile != null
            val probe = when {
                !filesReady -> QnnRuntimeProbeState.FILES_MISSING to missingQnnRuntimeMessage(
                    searchDirectories = dirs,
                    preferredHtpArchVersion = preferredHtpArchVersion
                )
                !probeLibraries -> QnnRuntimeProbeState.NOT_REQUESTED to "QNN runtime files were found; native load probe was not requested."
                else -> runCatching {
                    // Keep libQnnHtp unloaded until native code has read the
                    // context metadata and selected its exact HTP profile.
                    libraryLoader(requireNotNull(profile).systemLibrary.absolutePath)
                }.fold(
                    onSuccess = {
                        QnnRuntimeProbeState.LOADABLE to
                            "QNN System metadata preflight loaded; Snapdragon NPU profile selection is deferred until context metadata is parsed."
                    },
                    onFailure = { error ->
                        QnnRuntimeProbeState.LOAD_FAILED to (error.message ?: error::class.java.simpleName)
                    }
                )
            }
            val rpcProbe = if (profile?.cdspRpcLibrary != null) {
                false to "The device transport library is present but not probed directly; the Snapdragon NPU runtime will resolve it during graph smoke."
            } else {
                false to "The device transport library was not found; this is acceptable when using a QAIRT-packaged Snapdragon NPU runtime."
            }
            return QnnRuntimeStatus(
                qnnSystemLibraryPresent = profile != null,
                qnnHtpLibraryPresent = profile != null,
                htpSkelLibraryPresent = profile != null,
                htpStubLibraryPresent = profile?.htpStubLibrary != null,
                cdspRpcLibraryPresent = profile?.cdspRpcLibrary != null,
                cdspRpcLibraryLoadable = rpcProbe.first,
                searchDirectories = dirs.map { it.absolutePath },
                qnnSystemLibraryPath = profile?.systemLibrary?.absolutePath,
                qnnHtpLibraryPath = profile?.htpLibrary?.absolutePath,
                htpSkelLibraryPath = profile?.htpSkelLibrary?.absolutePath,
                htpStubLibraryPath = profile?.htpStubLibrary?.absolutePath,
                cdspRpcLibraryPath = profile?.cdspRpcLibrary?.absolutePath,
                cdspRpcMessage = rpcProbe.second,
                htpArchVersion = profile?.htpArchVersion ?: 0,
                runtimeDirectory = profile?.runtimeDirectory,
                dspRuntimeDirectory = profile?.dspDirectory?.absolutePath,
                probeState = probe.first,
                probeMessage = probe.second
            )
        }

        private fun missingQnnRuntimeMessage(
            searchDirectories: List<File>,
            preferredHtpArchVersion: Int?
        ): String {
            val files = searchDirectories
                .flatMap { directory -> directory.listFiles()?.asList().orEmpty() }
                .filter(File::isFile)
            val missing = buildList<String> {
                if (files.none { it.name == "libQnnSystem.so" }) add("libQnnSystem.so")
                if (files.none { it.name == "libQnnHtp.so" }) add("libQnnHtp.so")
                val expectedSkel = preferredHtpArchVersion?.let { "libQnnHtpV${it}Skel.so" }
                    ?: "libQnnHtpVxxSkel.so"
                val hasExpectedSkel = if (preferredHtpArchVersion == null) {
                    files.any { it.name.matches(Regex("""^libQnnHtpV\d+Skel\.so$""")) }
                } else {
                    files.any { it.name == expectedSkel }
                }
                if (!hasExpectedSkel) add(expectedSkel)
            }
            return if (missing.isEmpty()) {
                "No coherent QNN host/DSP profile was found in one side-loaded bundle or compatible OEM runtime directories."
            } else {
                "Missing QNN runtime libraries: ${missing.joinToString(", ")}."
            }
        }
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

object DeviceAccelerationAnalyzer {
    fun assess(
        soc: SocInfo,
        totalRamBytes: Long,
        qnnRuntime: QnnRuntimeStatus = QnnRuntimeStatus.Missing
    ): DeviceAccelerationProfile {
        if (soc.family != SocFamily.Snapdragon) {
            return DeviceAccelerationProfile.CpuOnly.copy(
                notes = listOf("非骁龙设备暂不展示 QNN/NPU 路线。")
            )
        }

        val code = normalizeChipsetCode("${soc.manufacturer} ${soc.model}")
        val tier = snapdragonTierFor(code)
        val htp = qnnHtpGenerationFor(tier)
        val ramGb = totalRamBytes / GB.toDouble()
        val sd15Candidate = tier in setOf(
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1,
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2,
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3,
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE,
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5
        )
        val sdxlCandidate = tier in setOf(
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3,
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE,
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5
        )
        val runtimeStatus = when {
            !qnnRuntime.ready -> AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING
            qnnRuntime.probeState == QnnRuntimeProbeState.LOAD_FAILED ->
                AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED
            !qnnRuntime.loadable -> AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED
            qnnRuntime.transportDependencyBlocked ->
                AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED
            else -> AccelerationCapabilityStatus.EXPERIMENTAL_READY
        }

        return DeviceAccelerationProfile(
            snapdragonTier = tier,
            chipsetCode = code,
            qnnHtpGeneration = htp,
            qnnRuntime = qnnRuntime,
            localChat = AccelerationCapability(
                label = "MNN CPU + GGUF 兼容",
                backend = "mnn_cpu,llama.cpp",
                status = AccelerationCapabilityStatus.READY,
                reason = "本地聊天继续以 MNN CPU 为主线，GGUF 作为兼容补充。"
            ),
            localVision = if (tier == SnapdragonAccelerationTier.SNAPDRAGON_OTHER) {
                AccelerationCapability(
                    label = "本地识图 CPU 兼容",
                    backend = "llama.cpp,mnn_cpu",
                    status = AccelerationCapabilityStatus.READY,
                    reason = "未命中 MCA 的骁龙 NPU 白名单，默认使用本地 CPU 识图。"
                )
            } else {
                AccelerationCapability(
                    label = "LiteRT-LM / QNN 视觉实验",
                    backend = "litert_qualcomm_npu,qnn_htp",
                    status = runtimeStatus,
                    reason = when {
                        !qnnRuntime.ready ->
                            "设备具备 NPU 路线，等待打包 QNN/QAIRT runtime 和 LiteRT-LM 模型包。"
                        qnnRuntime.probeState == QnnRuntimeProbeState.LOAD_FAILED ->
                            "QNN runtime files were found, but native load probe failed: ${qnnRuntime.probeMessage}"
                        !qnnRuntime.loadable ->
                            "QNN runtime files were found, but native load probe has not passed yet."
                        qnnRuntime.transportDependencyBlocked ->
                            "The Snapdragon NPU runtime loads, but its device transport is blocked: ${qnnRuntime.cdspRpcMessage}"
                        else ->
                            "设备和 QNN runtime 已满足 NPU 识图实验入口，需模型包 smoke test 后开放。"
                    },
                    minRamGb = 8
                )
            },
            localImage = when {
                !sd15Candidate -> AccelerationCapability(
                    label = "本地生图 CPU 兼容",
                    backend = "stable-diffusion.cpp",
                    status = AccelerationCapabilityStatus.READY,
                    reason = "该骁龙档位暂不作为 QNN 生图目标，使用 CPU 兼容生图。"
                )
                !qnnRuntime.ready -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN 生图" else "SD1.5 QNN 生图",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING,
                    reason = "设备满足 QNN 生图候选条件；需认证完整生图包并打包 QNN runtime 后启用。",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
                qnnRuntime.probeState == QnnRuntimeProbeState.LOAD_FAILED -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN image" else "SD1.5 QNN image",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED,
                    reason = "QNN runtime files were found, but native load probe failed: ${qnnRuntime.probeMessage}",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
                !qnnRuntime.loadable -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN image" else "SD1.5 QNN image",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED,
                    reason = "QNN runtime files were found, but native load probe has not passed yet.",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
                qnnRuntime.transportDependencyBlocked -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN image" else "SD1.5 QNN image",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED,
                    reason = "The Snapdragon NPU runtime loads, but its device transport is blocked: ${qnnRuntime.cdspRpcMessage}",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
                else -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN 生图" else "SD1.5 QNN 生图",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.EXPERIMENTAL_READY,
                    reason = "设备和 QNN runtime 就绪；仍需每个生图包通过 1-step smoke test 后才可选择。",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
            },
            stableDiffusion15NpuCandidate = sd15Candidate,
            sdxlNpuCandidate = sdxlCandidate && ramGb >= 10.0,
            notes = buildList {
                if (code.isNotBlank()) add("SoC: ${publicChipsetDisplayName(code, SocFamily.Snapdragon)}")
                if (htp.isNotBlank()) add("NPU：${publicChipsetDisplayName(code, SocFamily.Snapdragon)}")
                if (sdxlCandidate && ramGb < 10.0) add("SDXL NPU route hidden until memory is at least about 12GB.")
                if (!qnnRuntime.ready) add("QNN runtime is not packaged yet; do not claim active NPU execution.")
                if (qnnRuntime.ready && !qnnRuntime.loadable) add("QNN runtime is not load-verified yet; do not enter NPU smoke.")
                if (qnnRuntime.transportDependencyBlocked) add("Snapdragon NPU device transport is blocked; keep NPU hidden until graph smoke passes.")
            }
        )
    }

    fun normalizeChipsetCode(value: String): String {
        val upper = value.uppercase(Locale.US)
        return Regex("""\b(SM|QCS|QCM|SDM|MSM)\d{3,4}P?\b""")
            .find(upper)
            ?.value
            .orEmpty()
    }

    /**
     * Converts Qualcomm internal part numbers into the product names shown in the UI.
     * The raw code remains the compatibility key for QNN assets and must not be used
     * as a user-facing device name.
     */
    fun userFacingChipsetName(chipsetCode: String): String? {
        val upper = chipsetCode.trim().uppercase(Locale.US)
        val normalized = normalizeChipsetCode(upper).ifBlank { upper }
        return when (normalized) {
            "SM6115" -> "骁龙 662"
            "SM6125" -> "骁龙 665"
            "SM6225" -> "骁龙 680"
            "SM6375" -> "骁龙 695"
            "SM6450" -> "骁龙 6 Gen 1"
            "SM6475" -> "骁龙 6 Gen 3"
            "SM7325" -> "骁龙 778G"
            "SM7350" -> "骁龙 780G"
            "SM7435" -> "骁龙 7s Gen 2"
            "SM7450" -> "骁龙 7 Gen 1"
            "SM7475" -> "骁龙 7+ Gen 2"
            "SM7550" -> "骁龙 7 Gen 3"
            "SM7635" -> "骁龙 7s Gen 3"
            "SM7675" -> "骁龙 7+ Gen 3"
            "SM7750" -> "骁龙 7 Gen 4"
            "SM8150" -> "骁龙 855"
            "SM8250" -> "骁龙 865"
            "SM8350" -> "骁龙 888"
            "SM8450" -> "骁龙 8 Gen 1"
            "SM8475" -> "骁龙 8+ Gen 1"
            "SM8550", "SM8550P", "QCS8550", "QCM8550" -> "骁龙 8 Gen 2"
            "SM8635" -> "骁龙 8s Gen 3"
            "SM8650", "SM8650P" -> "骁龙 8 Gen 3"
            "SM8735" -> "骁龙 8s Gen 4"
            "SM8750", "SM8750P" -> "骁龙 8 Elite"
            "SM8850", "SM8850P" -> "骁龙 8 Elite Gen 5"
            else -> if (normalized.matches(Regex("""(SM|QCS|QCM|SDM|MSM)\d{3,4}P?"""))) {
                "骁龙芯片"
            } else {
                null
            }
        }
    }

    /**
     * The only chipset formatter intended for user-visible surfaces. Internal
     * part numbers remain available in [DeviceAccelerationProfile.chipsetCode]
     * for compatibility matching and diagnostics, but never pass through here.
     */
    fun publicChipsetDisplayName(
        chipsetIdentity: String,
        family: SocFamily? = null
    ): String {
        userFacingChipsetName(chipsetIdentity)?.let { return it }

        val upper = chipsetIdentity.trim().uppercase(Locale.US)
        val mediatekCode = Regex("""\bMT\d{4}[A-Z]?\b""")
            .find(upper)
            ?.value
            ?.take(6)
            .orEmpty()
        val mediatekName = when (mediatekCode) {
            "MT6878" -> "天玑 7300 系列"
            "MT6879" -> "天玑 7400 系列"
            "MT6893" -> "天玑 1100 / 1200 系列"
            "MT6895" -> "天玑 8000 系列"
            "MT6896" -> "天玑 8200"
            "MT6897" -> "天玑 8300 系列"
            "MT6899" -> "天玑 8400 系列"
            "MT6983" -> "天玑 9000 系列"
            "MT6985" -> "天玑 9200 系列"
            "MT6989" -> "天玑 9300 系列"
            "MT6991" -> "天玑 9400 系列"
            "MT6993" -> "天玑 9500 系列"
            else -> null
        }
        if (mediatekName != null) return mediatekName
        if (mediatekCode.isNotBlank()) return "未识别芯片"

        val raw = chipsetIdentity.trim()
        if (raw.isBlank() || raw.equals("unknown", ignoreCase = true)) {
            return if (family == SocFamily.Snapdragon) "骁龙芯片" else "未识别芯片"
        }
        val localizedPublicName = raw
            .replace(Regex("(?i)qualcomm\\s*"), "")
            .replace(Regex("(?i)snapdragon"), "骁龙")
            .replace(Regex("(?i)mediatek\\s*"), "")
            .replace(Regex("(?i)dimensity"), "天玑")
            .replace(Regex("\\s+"), " ")
            .trim()
        val containsKnownPublicBrand = listOf("骁龙", "天玑", "Exynos", "Tensor", "麒麟")
            .any { localizedPublicName.contains(it, ignoreCase = true) }
        return when {
            containsKnownPublicBrand -> localizedPublicName
            family == SocFamily.Snapdragon -> "骁龙芯片"
            else -> "未识别芯片"
        }
    }

    fun userFacingQnnSocModelName(socModel: Int): String =
        publicChipsetDisplayName(qnnSocModelName(socModel), SocFamily.Snapdragon)

    fun snapdragonTierFor(chipsetCode: String): SnapdragonAccelerationTier =
        when (chipsetCode.uppercase(Locale.US)) {
            "SM8850", "SM8850P" -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5
            "SM8750", "SM8750P" -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE
            "SM8650", "SM8650P", "SM8635" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3
            "SM8550", "SM8550P", "QCS8550", "QCM8550" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2
            "SM8475", "SM8450" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1
            "" -> SnapdragonAccelerationTier.SNAPDRAGON_OTHER
            else -> SnapdragonAccelerationTier.SNAPDRAGON_OTHER
        }

    fun expectedQnnSocModelForChipsetCode(chipsetCode: String): Int? =
        when (chipsetCode.uppercase(Locale.US)) {
            "SM8850", "SM8850P" -> 87
            "SM8750", "SM8750P" -> 69
            "SM8650", "SM8650P" -> 57
            "SM8635" -> 68
            "SM8550", "SM8550P" -> 43
            "QCS8550" -> 66
            "SM8475" -> 42
            "SM8450" -> 36
            else -> null
        }

    /** Exact HTP profile required by the public Snapdragon part number. */
    fun expectedQnnHtpArchVersionForChipsetCode(chipsetCode: String): Int? =
        QnnRuntimeProfileSelector.htpArchVersionForChipsetCode(chipsetCode)

    fun qnnSocModelName(socModel: Int): String =
        when (socModel) {
            87 -> "SM8850"
            69 -> "SM8750"
            68 -> "SM8635"
            66 -> "QCS8550"
            57 -> "SM8650"
            43 -> "SM8550"
            42 -> "SM8475"
            36 -> "SM8450"
            else -> "socModel=$socModel"
        }

    private fun qnnHtpGenerationFor(tier: SnapdragonAccelerationTier): String =
        when (tier) {
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5 -> "HTP v81 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE -> "HTP v79 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3 -> "HTP v75 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2 -> "HTP v73 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1 -> "HTP v68+ class"
            else -> ""
        }

    private const val GB = 1024L * 1024L * 1024L
}
