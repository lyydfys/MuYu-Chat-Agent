package com.muyuchat.mca

import java.io.File

/** Reads standard package sidecars plus JSON config sidecars declared by a full manifest profile. */
internal fun parseLocalImageExecutionProfileSidecars(
    bundleRoot: File,
    manifestProfile: ImageExecutionProfile?
): ImageProfileSidecar? {
    val graph = manifestProfile?.graph
    val behaviorSidecars = buildList {
        add(DEFAULT_IMAGE_BEHAVIOR_SIDECAR)
        graph?.configSidecars
            .orEmpty()
            .filter(::isJsonImageBehaviorSidecar)
            .forEach(::add)
    }
    val tokenizerSidecar = graph?.tokenizerSidecar
    val tokenizerJsonPath = when {
        tokenizerSidecar != null -> tokenizerSidecar.takeIf { path ->
            path.substringAfterLast('/').substringAfterLast('\\').endsWith(".json", ignoreCase = true)
        }
        manifestProfile?.tokenizer?.backend == ImageTokenizerBackend.MNN_QWEN_IMAGE -> null
        else -> DEFAULT_IMAGE_TOKENIZER_SIDECAR
    }
    return ImageExecutionProfileJson.parseSidecars(
        bundleRoot = bundleRoot,
        schedulerRelativePath = graph?.schedulerSidecar ?: DEFAULT_IMAGE_SCHEDULER_SIDECAR,
        tokenizerRelativePath = tokenizerJsonPath,
        behaviorRelativePaths = behaviorSidecars
    )
}

private fun isJsonImageBehaviorSidecar(relativePath: String): Boolean {
    val fileName = relativePath
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
    return fileName.endsWith(".json") && fileName !in NON_BEHAVIOR_RUNTIME_JSON_FILES
}

private const val DEFAULT_IMAGE_SCHEDULER_SIDECAR = "scheduler/scheduler_config.json"
private const val DEFAULT_IMAGE_TOKENIZER_SIDECAR = "tokenizer/tokenizer_config.json"
private const val DEFAULT_IMAGE_BEHAVIOR_SIDECAR = "config.json"

private val NON_BEHAVIOR_RUNTIME_JSON_FILES = setOf(
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "added_tokens.json",
    "vocab.json",
    "scheduler_config.json"
)

internal fun imageSchedulerAlgorithmFromProductName(value: String): ImageSchedulerAlgorithm =
    when (value.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "euler", "euler_discrete" -> ImageSchedulerAlgorithm.EULER
        "euler_a", "euler_ancestral", "euler_ancestral_discrete" -> ImageSchedulerAlgorithm.EULER_A
        "ddim", "ddim_trailing" -> ImageSchedulerAlgorithm.DDIM
        "pndm", "pndm_plms", "plms" -> ImageSchedulerAlgorithm.PNDM_PLMS
        "dpm++2m", "dpmpp_2m", "dpm_plus_plus_2m" -> ImageSchedulerAlgorithm.DPMPP_2M
        "lcm" -> ImageSchedulerAlgorithm.LCM
        "flow", "flow_match", "flowmatch" -> ImageSchedulerAlgorithm.FLOW_MATCH
        else -> throw IllegalArgumentException("Unsupported image scheduler: $value")
    }

internal fun imageSchedulerProductName(value: ImageSchedulerAlgorithm): String = when (value) {
    ImageSchedulerAlgorithm.EULER -> "euler"
    ImageSchedulerAlgorithm.EULER_A -> "euler_a"
    ImageSchedulerAlgorithm.DDIM -> "ddim"
    ImageSchedulerAlgorithm.PNDM_PLMS -> "pndm"
    ImageSchedulerAlgorithm.DPMPP_2M -> "dpmpp_2m"
    ImageSchedulerAlgorithm.LCM -> "lcm"
    ImageSchedulerAlgorithm.FLOW_MATCH -> "flow_match"
}
