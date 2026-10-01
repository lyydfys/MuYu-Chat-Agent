package com.muyuchat.mca.presentation

import com.muyuchat.core.deviceprofile.AccelerationCapabilityStatus
import com.muyuchat.core.deviceprofile.DeviceProfile

/**
 * Turns a device profile into the sentences the UI shows about acceleration.
 *
 * Moved here verbatim from MainActivity during P2, whose mapping functions now live in this
 * package. The move is the whole change: no sentence, condition, order or label was edited.
 *
 * The text these produce is the app's answer to "what will this device do", and it is the
 * place where the admission policy is visible in words. Note what every branch says: an entry
 * is never described as closed. "入口不封禁", "入口仍开放", "入口保持可见",
 * "下载后可直接加载验证" -- a missing or unmatched runtime is reported as something to try and
 * verify, never as a refusal. That is the hard rule in AGENTS.md, and it is worth being able to
 * read it in one place rather than inside a 2236-line activity.
 *
 * deviceImageTierKey is a key rather than a sentence: it selects which image package to
 * suggest, and is advisory. It does not gate anything.
 */
internal fun DeviceProfile.deviceAccelerationSummary(): String {
    val acceleration = accelerationProfile
    val runtime = if (acceleration.qnnRuntime.usableForSmoke) {
        "QNN runtime 已就绪"
    } else if (acceleration.qnnRuntime.transportDependencyBlocked) {
        "NPU 运行环境受限"
    } else if (acceleration.qnnRuntime.ready) {
        "QNN runtime 探测失败"
    } else {
        "QNN runtime 待打包"
    }
    return "${acceleration.snapdragonTier.label} · ${acceleration.localChat.label} · $runtime"
}

internal fun DeviceProfile.deviceImagePolicy(): String {
    val acceleration = accelerationProfile
    val image = acceleration.localImage
    val runtimeText = when {
        acceleration.qnnRuntime.ready && !acceleration.qnnRuntime.exactArchMatch ->
            "已发现 HTP V${acceleration.qnnRuntime.htpArchVersion} runtime，但设备需要 HTP V${acceleration.qnnRuntime.preferredHtpArchVersion}；不会把它标记为可用 NPU。"
        else -> when (image.status) {
            AccelerationCapabilityStatus.EXPERIMENTAL_READY ->
                "QNN 生图运行包已就绪；是否成功以当前模型的实际执行结果为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING ->
                "QNN 生图需要匹配的 runtime 与模型文件；下载后可直接加载验证。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED ->
                "已找到 QNN runtime 文件；可直接尝试，原生加载与 graph smoke 结果会如实显示。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED ->
                "QNN runtime 原生加载探测失败；入口不封禁，修复包后可再次直接尝试。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED ->
                "设备通信依赖当前受限；QNN 生图入口保持可见并报告真实 graph 执行结果。"
            AccelerationCapabilityStatus.READY ->
                "当前稳定路径为 stable-diffusion.cpp / MNN CPU，NPU 生图不会被宣传为已启用。"
            AccelerationCapabilityStatus.UNSUPPORTED ->
                "未识别到已知 QNN 能力档案；入口仍开放，默认也保留 CPU 兼容生图。"
        }
    }
    val visionText = when {
        acceleration.qnnRuntime.ready && !acceleration.qnnRuntime.exactArchMatch ->
            "识别到的 QNN HTP V${acceleration.qnnRuntime.htpArchVersion} 与设备要求的 HTP V${acceleration.qnnRuntime.preferredHtpArchVersion} 不匹配；请安装对应 runtime。"
        else -> when (acceleration.localVision.status) {
            AccelerationCapabilityStatus.EXPERIMENTAL_READY ->
                "本地识图可使用 LiteRT-LM / QNN NPU 包；结果以实际模型执行为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_MISSING ->
                "本地识图需要匹配的 LiteRT-LM / QNN runtime 和模型文件；下载后可直接加载验证。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_UNVERIFIED ->
                "本地识图已找到 QNN runtime 文件；入口开放并以真实 NPU smoke 为准。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_RUNTIME_LOAD_FAILED ->
                "本地识图 QNN runtime 原生加载探测失败；入口不封禁，可在修复包后重试。"
            AccelerationCapabilityStatus.DEVICE_CAPABLE_HTP_TRANSPORT_BLOCKED ->
                "本地识图设备通信依赖受限；入口保持开放并报告真实 NPU 执行结果。"
            AccelerationCapabilityStatus.READY ->
                "本地识图使用 GGUF mmproj / MNN 兼容路径。"
            AccelerationCapabilityStatus.UNSUPPORTED ->
                "未识别到已知 NPU 档案；本地识图入口仍开放。"
        }
    }
    return "$visionText $runtimeText"
}

internal fun DeviceProfile.deviceImageTierKey(): String {
    val acceleration = accelerationProfile
    return when {
        acceleration.qnnRuntime.usableForSmoke && acceleration.sdxlNpuCandidate -> "qnn_sdxl_ready"
        acceleration.qnnRuntime.usableForSmoke && acceleration.stableDiffusion15NpuCandidate -> "qnn_sd15_ready"
        acceleration.sdxlNpuCandidate -> "qnn_sdxl_candidate"
        acceleration.stableDiffusion15NpuCandidate -> "qnn_sd15_candidate"
        else -> "cpu"
    }
}
