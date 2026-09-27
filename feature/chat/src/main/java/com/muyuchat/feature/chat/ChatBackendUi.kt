package com.muyuchat.feature.chat

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.RuntimeStats
import org.json.JSONObject

/**
 * A user-selectable transport for one local chat model.
 *
 * The option is deliberately model-scoped.  Selecting a transport only edits
 * the model's execution profile; the native load/readback path remains the
 * authority for whether the requested transport can actually run.
 */
data class ChatBackendOption(
    val id: String,
    val label: String,
    val detail: String,
    val enabled: Boolean = true,
    val availabilityNote: String? = null
)

/** Runtime families understood by the chat UI without coupling the feature to model-store. */
enum class ChatBackendFamily {
    MNN,
    LLAMA_CPP,
    /** GenieX's GGUF runner accepts `geniex_compute_unit`, not llama.cpp GPU-layer fields. */
    GENIEX_LLAMA_CPP,
    LITERT_LM,
    QAIRT,
    UNKNOWN
}

/**
 * Encodes a model-scoped custom llama.cpp GPU layer choice in the existing
 * backend callback without widening every Compose callback signature. The
 * persisted profile itself still contains the canonical integer
 * `n_gpu_layers`; this transport id is only used between the chip and the
 * ViewModel.
 */
private const val CUSTOM_GPU_BACKEND_PREFIX = "custom:"

fun customGpuLayerCountFromBackendId(backendId: String): Int? =
    backendId.trim().lowercase()
        .takeIf { it.startsWith(CUSTOM_GPU_BACKEND_PREFIX) }
        ?.removePrefix(CUSTOM_GPU_BACKEND_PREFIX)
        ?.toIntOrNull()
        ?.takeIf { it in 0..4096 }

/**
 * Computes the three-state NPU availability shown by the model selector.
 * `null` is intentional: an unknown SoC remains actionable and is checked by
 * the native loader instead of being presented as a hard incompatibility.
 */
fun npuAvailabilityForChatBackend(
    family: ChatBackendFamily,
    chipsetCode: String?,
    qnnRuntimeUsableForSmoke: Boolean?,
    packagedLiteRtTransportAvailable: Boolean = false
): Boolean? {
    if (
        family != ChatBackendFamily.LITERT_LM &&
        family != ChatBackendFamily.QAIRT &&
        family != ChatBackendFamily.GENIEX_LLAMA_CPP
    ) {
        return null
    }
    if (chipsetCode.isNullOrBlank()) return null
    return when (family) {
        ChatBackendFamily.LITERT_LM ->
            qnnRuntimeUsableForSmoke == true || packagedLiteRtTransportAvailable
        ChatBackendFamily.QAIRT,
        ChatBackendFamily.GENIEX_LLAMA_CPP -> qnnRuntimeUsableForSmoke == true
        else -> null
    }
}

fun chatBackendFamilyForRuntime(runtimeStorageValue: String?): ChatBackendFamily = when (
    runtimeStorageValue.orEmpty().trim().lowercase()
) {
    "mnn", "mnn_llm", "mnn-llm" -> ChatBackendFamily.MNN
    // Built-in llama.cpp and GenieX llama.cpp have different parameter
    // contracts. The former uses n_gpu_layers; the latter uses
    // geniex_compute_unit. Never collapse them or a UI GPU click becomes a
    // no-op for GenieX (or accidentally changes the built-in profile).
    "llama", "llama_cpp", "llama.cpp", "gguf" -> ChatBackendFamily.LLAMA_CPP
    "geniex_llama_cpp", "geniex_gguf", "geniex_htp" -> ChatBackendFamily.GENIEX_LLAMA_CPP
    "litert", "litertlm", "litert_lm", "litert-lm" -> ChatBackendFamily.LITERT_LM
    "geniex", "geniex_qairt", "qairt", "qnn", "qnn_htp" -> ChatBackendFamily.QAIRT
    else -> ChatBackendFamily.UNKNOWN
}

/**
 * Returns only transports that this runtime can meaningfully request.  A null
 * NPU value means that the device profile has not been read yet; in that case
 * the option remains actionable and native execution performs the real check.
 */
fun chatBackendOptionsFor(
    family: ChatBackendFamily,
    npuAvailable: Boolean? = null,
    gpuAvailable: Boolean? = null
): List<ChatBackendOption> = when (family) {
    ChatBackendFamily.MNN -> listOf(
        ChatBackendOption("cpu", "CPU", "MNN CPU"),
        ChatBackendOption("opencl", "GPU / OpenCL", "MNN OpenCL GPU")
    )

    ChatBackendFamily.LLAMA_CPP -> listOf(
        ChatBackendOption(
            id = "cpu",
            label = "CPU",
            detail = "llama.cpp CPU",
            availabilityNote = if (gpuAvailable == false) {
                "此安装包没有可用的 llama.cpp GPU 后端，当前使用 CPU。"
            } else {
                null
            }
        ),
        ChatBackendOption(
            id = "gpu",
            label = if (gpuAvailable == false) "GPU 不可用" else "GPU（全量）",
            detail = "llama.cpp GPU / OpenCL offload",
            enabled = gpuAvailable != false,
            availabilityNote = when (gpuAvailable) {
                true -> "llama.cpp GPU 后端可用；生成后仍以实际 offload 证据为准。"
                false -> "最近一次探测未发现可用的 llama.cpp GPU 后端；仍可尝试加载，最终以原生加载结果为准。"
                null -> "将在模型加载时检测 llama.cpp GPU 后端。"
            }
        ),
        ChatBackendOption(
            id = "auto",
            label = "自动",
            detail = "按设备和模型自动调配",
            availabilityNote = "自动尝试可用 GPU；初始化失败时安全回退到 CPU。"
        ),
        ChatBackendOption(
            id = "custom",
            label = "自定义",
            detail = "按 GPU 层数和 split_mode 调配",
            availabilityNote = "在高级参数中设置 GPU 层数；0=CPU，auto=自动，all=全量 GPU。"
        )
    )

    ChatBackendFamily.GENIEX_LLAMA_CPP -> listOf(
        ChatBackendOption(
            id = "cpu",
            label = "CPU",
            detail = "GenieX llama.cpp CPU",
            availabilityNote = "使用 GenieX 的 CPU compute unit；CPU 兼容回退仍由实际加载结果确认。"
        ),
        ChatBackendOption(
            id = "gpu",
            label = "GPU / OpenCL",
            detail = "GenieX llama.cpp Adreno GPU",
            availabilityNote = "请求 GenieX GPU compute unit；运行状态以 native readback 为准。"
        ),
        ChatBackendOption(
            id = "npu",
            label = "NPU / HTP",
            detail = "GenieX llama.cpp Qualcomm HTP",
            enabled = npuAvailable != false,
            availabilityNote = when (npuAvailable) {
                true -> "最近的 HTP runtime 探测通过；具体模型仍由加载和生成验证。"
                false -> "最近的 HTP runtime 探测未通过；仍可尝试加载，最终以原生加载结果为准。"
                null -> "HTP 兼容性尚未验证；仍可尝试加载，失败时会显示原生原因。"
            }
        ),
        ChatBackendOption(
            id = "hybrid",
            label = "混合",
            detail = "GenieX HTP + CPU",
            availabilityNote = "请求 GenieX hybrid compute unit；运行状态以 native readback 为准。"
        )
    )

    ChatBackendFamily.LITERT_LM -> listOf(
        ChatBackendOption("cpu", "CPU", "LiteRT-LM CPU"),
        ChatBackendOption("gpu", "GPU", "LiteRT-LM GPU"),
        ChatBackendOption(
            id = "npu",
            label = "NPU",
            detail = "LiteRT-LM Qualcomm NPU",
            enabled = npuAvailable != false,
            availabilityNote = when (npuAvailable) {
                true -> "最近的 NPU runtime 探测通过；具体模型仍由加载和生成验证。"
                false -> "当前设备的 NPU runtime 未就绪"
                null -> "NPU runtime 将在加载时检测"
            }
        )
    )

    ChatBackendFamily.QAIRT -> listOf(
        ChatBackendOption(
            id = "npu",
            label = "NPU",
            detail = "Qualcomm HTP / QAIRT",
            enabled = true,
            availabilityNote = when (npuAvailable) {
                true -> "最近的 HTP runtime 探测通过；具体模型仍由加载和生成验证。"
                false -> "当前设备的 HTP runtime 未就绪"
                null -> "HTP runtime 将在加载时检测"
            }
        )
    )

    ChatBackendFamily.UNKNOWN -> emptyList()
}

/**
 * Infers the selected chip from the persisted profile and, when loaded, the
 * native readback.  Native evidence wins because a requested GPU may have
 * been rejected and safely fallen back to CPU.
 */
fun selectedChatBackendId(
    family: ChatBackendFamily,
    params: GenerationParams,
    stats: RuntimeStats = RuntimeStats()
): String? {
    val observed = stats.backend.trim().lowercase()
    val observedDevices = stats.backendDevices.trim().lowercase()
    when (family) {
        ChatBackendFamily.MNN -> when {
            observed.contains("opencl") || observed.contains("gpu") -> return "opencl"
            stats.loaded && (observed.contains("mnn") || observedDevices.contains("mnn")) -> return "cpu"
        }

        ChatBackendFamily.LLAMA_CPP -> {
            val layers = LlamaAdvancedParams.parse(params.advancedJson).params?.nGpuLayers
            // A registered non-CPU device only proves that a backend plugin was
            // discoverable. The loaded model may still use n_gpu_layers=0 or
            // auto-fallback to CPU, so require native allocation and decode
            // evidence before showing the active transport. Preserve the
            // requested topology so a partial/custom split is visible in the UI.
            if (stats.hasVerifiedGpuExecution) {
                return when (layers) {
                    -2 -> "gpu"
                    0 -> "cpu"
                    -1, null -> "auto"
                    else -> "custom"
                }
            }
            // A load-bound GPU request is authoritative before the first
            // decode has produced allocation evidence. Do not make the chip
            // appear to have silently switched to CPU between load and the
            // first token; the native readback/error path remains authoritative
            // if the requested transport cannot initialize.
            if (stats.loaded && !stats.gpuAutoFallbackApplied) {
                when (layers) {
                    -2 -> return "gpu"
                    -1, null -> return "auto"
                    0 -> return "cpu"
                    else -> return "custom"
                }
            }
            if (stats.loaded && (observed.contains("llama") || observed.contains("cpp"))) {
                return "cpu"
            }
            return when {
                layers == 0 -> "cpu"
                layers == -2 -> "gpu"
                layers == -1 || layers == null -> "auto"
                else -> "custom"
            }
        }

        ChatBackendFamily.LITERT_LM -> when {
            observed.contains("npu") || observed.contains("qualcomm") -> return "npu"
            observed.contains("gpu") -> return "gpu"
            stats.loaded && observed.contains("litert") -> return "cpu"
        }

        ChatBackendFamily.GENIEX_LLAMA_CPP -> when {
            observed.contains("npu") || observed.contains("htp") || observed.contains("qualcomm") -> return "npu"
            observed.contains("gpu") || observed.contains("opencl") -> return "gpu"
            stats.loaded && (
                observed.contains("llama") ||
                    observed.contains("geniex") ||
                    observedDevices.contains("llama")
                ) -> return "cpu"
        }

        ChatBackendFamily.QAIRT -> if (
            observed.contains("qairt") || observed.contains("npu") || observed.contains("htp") ||
                observed.contains("qualcomm")
        ) return "npu"

        ChatBackendFamily.UNKNOWN -> Unit
    }

    val advanced = runCatching { JSONObject(params.advancedJson.ifBlank { "{}" }) }.getOrNull()
    return when (family) {
        ChatBackendFamily.MNN,
        ChatBackendFamily.LITERT_LM,
        ChatBackendFamily.QAIRT -> advanced
            ?.optString("backend", advanced.optString("backend_type"))
            ?.trim()
            ?.lowercase()
            ?.let { raw ->
                when {
                    raw in setOf("gpu", "opencl", "open_cl") ->
                        if (family == ChatBackendFamily.MNN) "opencl" else "gpu"
                    raw in setOf("npu", "qairt", "htp", "qualcomm") -> "npu"
                    raw == "cpu" -> "cpu"
                    else -> null
                }
            }

        ChatBackendFamily.LLAMA_CPP -> {
            val layers = LlamaAdvancedParams.parse(params.advancedJson).params?.nGpuLayers
            when {
                layers == 0 -> "cpu"
                layers == null || layers == -1 -> "auto"
                else -> "gpu"
            }
        }

        ChatBackendFamily.GENIEX_LLAMA_CPP -> {
            val computeUnit = advanced
                ?.optString("geniex_compute_unit", advanced.optString("compute_unit"))
                ?.trim()
                ?.lowercase()
            val observedCompute = when {
                observedDevices.contains("htp") || observedDevices.contains("npu") ->
                    if (observedDevices.contains("cpu")) "hybrid" else "npu"
                observedDevices.contains("opencl") || observedDevices.contains("adreno") ||
                    observedDevices.contains("gpu") -> "gpu"
                stats.loaded && observedDevices.contains("cpu") -> "cpu"
                else -> null
            }
            when (observedCompute ?: computeUnit) {
                "cpu", "cpu_only" -> "cpu"
                "gpu", "opencl", "adreno" -> "gpu"
                "npu", "htp", "qualcomm" -> "npu"
                "hybrid", "mixed" -> "hybrid"
                else -> "hybrid"
            }
        }

        ChatBackendFamily.UNKNOWN -> null
    }
}

/** Applies a transport choice to the model-scoped execution profile. */
fun GenerationParams.withChatBackend(
    family: ChatBackendFamily,
    backendId: String
): GenerationParams {
    val root = runCatching { JSONObject(advancedJson.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    when (family) {
        ChatBackendFamily.MNN -> {
            root.put("backend", if (backendId == "opencl") "opencl" else "cpu")
            root.remove("backend_type")
        }

        ChatBackendFamily.LITERT_LM -> {
            root.put("backend", when (backendId) {
                "gpu" -> "gpu"
                "npu" -> "npu"
                else -> "cpu"
            })
            root.remove("backend_type")
        }

        ChatBackendFamily.QAIRT -> {
            root.put("backend", "qairt")
            root.remove("backend_type")
        }

        ChatBackendFamily.LLAMA_CPP -> {
            val parsed = LlamaAdvancedParams.parse(root.toString()).params
            val updated = when (backendId) {
                // llama.cpp's explicit `all` sentinel is -2.  Keep -1 for the
                // separate custom/auto mode so an explicit GPU choice cannot
                // silently fall back to CPU when no backend is present.
                "gpu" -> parsed?.copy(nGpuLayers = -2, splitMode = "layer")
                // CPU mode cannot keep a previous MoE CPU-placement request:
                // native treats n_cpu_moe as a companion to GPU offload and
                // rejects the otherwise valid all-CPU profile.
                "cpu" -> parsed?.copy(
                    nGpuLayers = 0,
                    mainGpu = 0,
                    splitMode = "none",
                    nCpuMoe = 0
                )
                "auto" -> parsed?.copy(nGpuLayers = -1, mainGpu = 0, splitMode = "layer")
                "custom" -> {
                    // -2 means all layers and 0 means CPU.  Carrying either
                    // sentinel into custom mode makes the UI claim a tunable
                    // split while native receives a different topology.
                    // Enter custom mode with auto placement for sentinels,
                    // while preserving an explicit positive layer count.
                    val layers = parsed?.nGpuLayers
                        ?.takeIf { it > 0 }
                        ?: -1
                    parsed?.copy(
                        nGpuLayers = layers,
                        // A CPU-only split mode would otherwise survive a
                        // CPU -> custom transition and make the request
                        // ambiguous.  `layer` is llama.cpp's normal split
                        // topology for a GPU-layer request.
                        splitMode = parsed.splitMode
                            ?.takeIf { it != "none" }
                            ?: "layer"
                    )
                }
                else -> customGpuLayerCountFromBackendId(backendId)?.let { layers ->
                    parsed?.copy(
                        nGpuLayers = layers,
                        mainGpu = parsed.mainGpu ?: 0,
                        splitMode = if (layers == 0) {
                            "none"
                        } else {
                            parsed.splitMode?.takeIf { it != "none" } ?: "layer"
                        },
                        nCpuMoe = if (layers == 0) 0 else parsed.nCpuMoe
                    )
                }
                    ?: parsed
            }
            if (updated != null) {
                return copy(advancedJson = updated.toJsonString())
            }
            root.put(
                "n_gpu_layers",
                when {
                    backendId == "gpu" -> -2
                    backendId == "cpu" -> 0
                    backendId == "auto" -> -1
                    customGpuLayerCountFromBackendId(backendId) != null ->
                        customGpuLayerCountFromBackendId(backendId)!!
                    else -> -1
                }
            )
            if (backendId == "cpu") {
                root.put("main_gpu", 0).put("split_mode", "none").put("n_cpu_moe", 0)
            } else if (backendId == "gpu" || backendId == "auto") {
                root.put("main_gpu", 0).put("split_mode", "layer")
            } else if (customGpuLayerCountFromBackendId(backendId) != null) {
                val layers = customGpuLayerCountFromBackendId(backendId)!!
                root.put("main_gpu", 0)
                    .put("split_mode", if (layers == 0) "none" else "layer")
                if (layers == 0) root.put("n_cpu_moe", 0)
            }
        }

        ChatBackendFamily.GENIEX_LLAMA_CPP -> {
            val raw = root.optString("geniex_compute_unit", root.optString("compute_unit"))
                .trim()
                .lowercase()
            val computeUnit = when (backendId.trim().lowercase()) {
                "cpu" -> "cpu"
                "gpu", "opencl" -> "gpu"
                "npu", "htp" -> "npu"
                "hybrid", "mixed", "auto" -> "hybrid"
                else -> raw.takeIf { it in setOf("cpu", "gpu", "npu", "hybrid") } ?: "hybrid"
            }
            root.put("geniex_compute_unit", computeUnit)
            root.remove("compute_unit")
            root.remove("backend")
            root.remove("backend_type")
        }

        ChatBackendFamily.UNKNOWN -> return this
    }
    return copy(advancedJson = root.toString())
}
