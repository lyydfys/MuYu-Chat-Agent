package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：profile 解析结果与 readiness 诊断（resolveExecutionProfileOutcomeForUi /
 * hasDeclaredImageExecutionContractForUi / 行为字段表 / readiness 错误消息）自
 * LocalImageUiCapabilities.kt 逐字节平移；仅壳消费的入口声明行登记为 internal。
 */

internal fun LocalImageModelRecord.resolveExecutionProfileOutcomeForUi(): LocalImageUiProfileOutcome {
    val root = installedImageBundleRootForUi()
    return try {
        LocalImageUiProfileOutcome(
            resolution = resolveLocalImageExecutionProfile(
                model = this,
                options = LocalImageGenerationOptions(),
                bundleRoot = root
            ),
            readinessError = null
        )
    } catch (error: Exception) {
        if (!root.hasDeclaredImageExecutionContractForUi()) {
            LocalImageUiProfileOutcome(resolution = null, readinessError = null)
        } else {
            LocalImageUiProfileOutcome(
                resolution = null,
                readinessError = imageExecutionProfileReadinessErrorForUi(error)
            )
        }
    }
}

private fun File?.hasDeclaredImageExecutionContractForUi(): Boolean {
    val root = this?.takeIf(File::isDirectory) ?: return false
    val manifest = findInstalledImageManifestForUi(root)
    if (manifest != null) {
        val json = try {
            JSONObject(manifest.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return true
        }
        if (json.has("executionProfile")) return true
        if (IMAGE_EXECUTION_BEHAVIOR_CONTAINER_FIELDS.any { field -> json.has(field) }) return true
        if (IMAGE_EXECUTION_BEHAVIOR_FIELDS.any { field -> json.has(field) }) return true
    }
    return listOf(
        "scheduler/scheduler_config.json",
        "tokenizer/tokenizer_config.json",
        "config.json"
    ).any { relativePath -> File(root, relativePath).isFile }
}
private val IMAGE_EXECUTION_BEHAVIOR_CONTAINER_FIELDS = listOf(
    "modelConfig",
    "model_config",
    "generation",
    "generationConfig",
    "generation_config",
    "generationDefaults",
    "generation_defaults",
    "defaults"
)
private val IMAGE_EXECUTION_BEHAVIOR_FIELDS = listOf(
    "defaultPrompt",
    "default_prompt",
    "positivePrompt",
    "positive_prompt",
    "prompt",
    "defaultNegativePrompt",
    "default_negative_prompt",
    "negativePrompt",
    "negative_prompt",
    "defaultSteps",
    "default_steps",
    "numInferenceSteps",
    "num_inference_steps",
    "steps",
    "defaultCfg",
    "default_cfg",
    "cfgScale",
    "cfg_scale",
    "guidanceScale",
    "guidance_scale",
    "cfg",
    "defaultScheduler",
    "default_scheduler",
    "sampleMethod",
    "sample_method",
    "schedulerName",
    "scheduler_name",
    "schedulerType",
    "scheduler_type",
    "samplerName",
    "sampler_name",
    "sampler",
    "scheduler",
    "imageSize",
    "image_size",
    "resolution",
    "defaultWidth",
    "default_width",
    "width",
    "defaultHeight",
    "default_height",
    "height",
    "useCfg",
    "use_cfg",
    "doClassifierFreeGuidance",
    "do_classifier_free_guidance",
    "modelFamily",
    "model_family",
    "family",
    "modelVariant",
    "model_variant",
    "variant",
    "modelType",
    "model_type",
    "pipelineType",
    "pipeline_type",
    "dmd2",
    "isDmd2",
    "is_dmd2",
    "turbo",
    "isTurbo",
    "is_turbo"
)
private fun imageExecutionProfileReadinessErrorForUi(error: Throwable): String {
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: error::class.java.simpleName
    return "图像模型包执行配置无效：$detail"
}
