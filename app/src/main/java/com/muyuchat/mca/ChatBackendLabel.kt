package com.muyuchat.mca

import com.muyuchat.feature.chat.ChatBackendFamily

/**
 * Label shown to the user for the transport a local chat model is running on.
 *
 * The label appears in status messages such as "正在重新加载 …（GPU / OpenCL）", so it is
 * user-visible text derived from a runtime-reported family and backend id. Each family names
 * its transports differently, which is why the mapping is a single nested `when` rather than
 * a lookup shared across families.
 *
 * Three behaviours are worth knowing before changing anything here:
 *
 *  - `QAIRT` ignores its backend id and always reads `NPU`. The id is a qualcomm-specific
 *    detail that adds nothing for the user.
 *  - `MNN` reads `GPU / OpenCL` for `opencl` and `CPU` for **everything else**, including an
 *    `npu` id. That is the pre-existing mapping, not an oversight introduced here.
 *  - `UNKNOWN` echoes the backend id back. The id comes from the runtime manifest rather
 *    than from user input, and a blank one falls back to `默认`, so an unrecognised runtime
 *    still shows something legible instead of an empty label.
 *
 * Extracted verbatim from `MainViewModel` during P2; no family, id or fallback was changed.
 */
internal fun backendLabel(family: ChatBackendFamily, backendId: String): String = when (family) {
    ChatBackendFamily.MNN -> if (backendId == "opencl") "GPU / OpenCL" else "CPU"
    ChatBackendFamily.LITERT_LM -> when (backendId) {
        "npu" -> "NPU"
        "gpu" -> "GPU"
        else -> "CPU"
    }
    ChatBackendFamily.QAIRT -> "NPU"
    ChatBackendFamily.LLAMA_CPP -> when (backendId) {
        "gpu" -> "GPU（全量）"
        "auto" -> "自动调配"
        "custom" -> "自定义 GPU 层数"
        else -> com.muyuchat.feature.chat.customGpuLayerCountFromBackendId(backendId)
            ?.let { "自定义 GPU 层数（$it）" }
            ?: "CPU"
    }
    ChatBackendFamily.GENIEX_LLAMA_CPP -> when (backendId) {
        "gpu" -> "GPU"
        "npu" -> "NPU / HTP"
        "hybrid" -> "NPU + CPU"
        else -> "CPU"
    }
    ChatBackendFamily.UNKNOWN -> backendId.ifBlank { "默认" }
}
