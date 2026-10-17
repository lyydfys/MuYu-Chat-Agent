package com.muyuchat.core.deviceprofile

import com.muyuchat.core.telemetry.SocFamily
import com.muyuchat.core.telemetry.SocInfo
import java.util.Locale

object DeviceAccelerationAnalyzer {
    fun assess(
        soc: SocInfo,
        totalRamBytes: Long,
        qnnRuntime: QnnRuntimeStatus = QnnRuntimeStatus.Missing
    ): DeviceAccelerationProfile {
        val isSnapdragon = soc.family == SocFamily.Snapdragon
        val code = normalizeChipsetCode("${soc.manufacturer} ${soc.model}")
        val tier = if (isSnapdragon) snapdragonTierFor(code) else SnapdragonAccelerationTier.NONE
        val htp = qnnHtpGenerationFor(tier)
        // The exact Snapdragon tier is a recommendation and transport hint,
        // never an admission list. A non-Snapdragon/unknown family keeps any
        // coherent QNN runtime that was actually discovered and may proceed to
        // native load/graph smoke; the candidate flags below remain advisory.
        val sd15Candidate = isSnapdragon
        val sdxlCandidate = isSnapdragon
        val runtimeStatus = when {
            !qnnRuntime.ready -> AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING
            !qnnRuntime.exactArchMatch -> AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED
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
            localVision = AccelerationCapability(
                label = if (isSnapdragon) "LiteRT-LM / QNN 视觉实验" else "QNN/NPU 视觉实验",
                backend = "litert_qualcomm_npu,qnn_htp",
                status = runtimeStatus,
                reason = when {
                    !qnnRuntime.ready ->
                        "QNN 视觉入口已开放；加载完整 runtime 与模型包后由真实执行确认。"
                    !qnnRuntime.exactArchMatch ->
                        "已发现 QNN HTP V${qnnRuntime.htpArchVersion}，但设备需要 HTP V${qnnRuntime.preferredHtpArchVersion}；不会把该 runtime 标记为可用。"
                    qnnRuntime.probeState == QnnRuntimeProbeState.LOAD_FAILED ->
                        "QNN runtime files were found, but native load probe failed: ${qnnRuntime.probeMessage}"
                    !qnnRuntime.loadable ->
                        "QNN runtime files were found, but native load probe has not passed yet."
                    qnnRuntime.transportDependencyBlocked ->
                        "The NPU runtime loads, but its device transport is blocked: ${qnnRuntime.cdspRpcMessage}"
                    else ->
                        "QNN 视觉入口已开放；以模型包真实 graph smoke 结果为准。"
                },
                minRamGb = 8
            ),
            localImage = when {
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
                !qnnRuntime.exactArchMatch -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN image" else "SD1.5 QNN image",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED,
                    reason = "已发现 HTP V${qnnRuntime.htpArchVersion} runtime，但设备需要 HTP V${qnnRuntime.preferredHtpArchVersion}；请安装匹配的 QNN/HTP 包后重试。",
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
                    reason = "The NPU runtime loads, but its device transport is blocked: ${qnnRuntime.cdspRpcMessage}",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
                else -> AccelerationCapability(
                    label = if (sdxlCandidate) "SD1.5 / SDXL QNN 生图" else "SD1.5 QNN 生图",
                    backend = "qnn_htp",
                    status = AccelerationCapabilityStatus.EXPERIMENTAL_READY,
                    reason = "QNN 生图入口已开放；以当前模型包真实 1-step graph smoke 结果为准。",
                    minRamGb = if (sdxlCandidate) 12 else 8
                )
            },
            stableDiffusion15NpuCandidate = sd15Candidate,
            sdxlNpuCandidate = sdxlCandidate,
            notes = buildList {
                if (code.isNotBlank()) add("SoC: ${publicChipsetDisplayName(code, SocFamily.Snapdragon)}")
                if (htp.isNotBlank()) add("NPU：${publicChipsetDisplayName(code, SocFamily.Snapdragon)}")
                if (!isSnapdragon) add("设备族仅用于推荐；若存在完整 coherent QNN runtime，仍交由真实 native graph smoke 判定。")
                if (!qnnRuntime.ready) add("QNN runtime is not packaged yet; do not claim active NPU execution.")
                if (qnnRuntime.ready && !qnnRuntime.loadable) add("QNN runtime is not load-verified yet; do not enter NPU smoke.")
                if (qnnRuntime.transportDependencyBlocked) add("Snapdragon NPU device transport is blocked; keep the entry visible and report the real graph smoke result.")
                if (qnnRuntime.ready && !qnnRuntime.exactArchMatch) {
                    add("QNN HTP V${qnnRuntime.htpArchVersion} does not match the device-required HTP V${qnnRuntime.preferredHtpArchVersion}; do not advertise NPU execution.")
                }
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

    fun snapdragonTierFor(chipsetCode: String): SnapdragonAccelerationTier {
        val normalized = chipsetCode.trim().uppercase(Locale.US)
        val numericSocModel = normalized.toIntOrNull()
        return when {
            numericSocModel == 87 -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5
            numericSocModel == 69 -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE
            numericSocModel == 85 -> SnapdragonAccelerationTier.SNAPDRAGON_8S_GEN4
            numericSocModel == 57 || numericSocModel == 68 -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3
            numericSocModel == 43 || numericSocModel == 66 -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2
            numericSocModel == 30 -> SnapdragonAccelerationTier.SNAPDRAGON_888
            numericSocModel == 42 || numericSocModel == 36 -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1
            else -> when (normalized) {
            "SM8850", "SM8850P" -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE_GEN5
            "SM8750", "SM8750P" -> SnapdragonAccelerationTier.SNAPDRAGON_8_ELITE
            "SM8735" -> SnapdragonAccelerationTier.SNAPDRAGON_8S_GEN4
            "SM8650", "SM8650P", "SM8635" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3
            "SM8550", "SM8550P", "QCS8550", "QCM8550" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2
            "SM8350" -> SnapdragonAccelerationTier.SNAPDRAGON_888
            "SM8475", "SM8450" -> SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1
            "" -> SnapdragonAccelerationTier.SNAPDRAGON_OTHER
            else -> SnapdragonAccelerationTier.SNAPDRAGON_OTHER
            }
        }
    }

    fun expectedQnnSocModelForChipsetCode(chipsetCode: String): Int? {
        val normalized = chipsetCode.trim().uppercase(Locale.US)
        val numericSocModel = normalized.toIntOrNull()
        return when {
            numericSocModel?.let { it in setOf(30, 36, 42, 43, 57, 66, 68, 69, 85, 87) } == true ->
                numericSocModel
            else -> when (normalized) {
            "SM8850", "SM8850P" -> 87
            "SM8750", "SM8750P" -> 69
            "SM8735" -> 85
            "SM8650", "SM8650P" -> 57
            "SM8635" -> 68
            "SM8550", "SM8550P" -> 43
            "QCS8550", "QCM8550" -> 66
            "SM8475" -> 42
            "SM8450" -> 36
            else -> null
            }
        }
    }

    /** Exact HTP profile required by the public Snapdragon part number. */
    fun expectedQnnHtpArchVersionForChipsetCode(chipsetCode: String): Int? =
        QnnRuntimeProfileSelector.htpArchVersionForChipsetCode(chipsetCode)

    fun qnnSocModelName(socModel: Int): String =
        when (socModel) {
            87 -> "SM8850"
            85 -> "SM8735"
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
            SnapdragonAccelerationTier.SNAPDRAGON_8S_GEN4 -> "HTP v79 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN3 -> "HTP v75 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN2 -> "HTP v73 class"
            SnapdragonAccelerationTier.SNAPDRAGON_8_GEN1 -> "HTP v69 class"
            SnapdragonAccelerationTier.SNAPDRAGON_888 -> "HTP v68 class"
            else -> ""
        }

    private const val GB = 1024L * 1024L * 1024L
}
