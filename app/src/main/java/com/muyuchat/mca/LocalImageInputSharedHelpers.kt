package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val NATIVE_FLOAT_RELATIVE_TOLERANCE = 1e-6


private fun nativeFloatMatches(first: Double, second: Double): Boolean {
    if (!first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    return kotlin.math.abs(first - second) <= NATIVE_FLOAT_RELATIVE_TOLERANCE * scale
}

/** Product-visible image operation. This is independent of runtime or device discovery. */
enum class LocalImageTaskMode(val wireName: String) {
    TEXT_TO_IMAGE("text_to_image"),
    IMG2IMG("img2img"),
    INPAINT("inpaint"),
    CONTROL("control"),
    EDIT("edit");

    companion object {
        fun fromWireName(value: String?): LocalImageTaskMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image taskMode: ${value.orEmpty()}")
        }
    }
}

enum class LocalImagePreviewMode(val wireName: String) {
    NONE("none"),
    PROJECTION("projection"),
    TAE("tae"),
    VAE("vae");

    companion object {
        fun fromWireName(value: String?): LocalImagePreviewMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image preview mode: ${value.orEmpty()}")
        }
    }
}

internal fun validateLocalImageRuntimeProductOptions(
    runtime: LocalImageRuntime,
    options: LocalImageGenerationOptions
) {
    options.validateProductInputContract()
    if (options.ultraFix != null && runtime !in setOf(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageRuntime.QNN_HTP
        )
    ) {
        localImageInputReject(
            "unsupported_ultrafix",
            "Runtime ${runtime.name} does not expose a complete native UltraFix pipeline."
        )
    }
    if (options.batchCount != 1 && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_batch_count",
            "Runtime ${runtime.name} currently supports one output per request; native execution was not started."
        )
    }
    if (options.preview != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        runtime != LocalImageRuntime.QNN_HTP
    ) {
        localImageInputReject(
            "unsupported_preview",
            "Runtime ${runtime.name} does not expose a native preview callback."
        )
    }
    if (options.clipSkip != null && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_clip_skip",
            "Runtime ${runtime.name} does not consume clipSkip; native execution was not started."
        )
    }
    if (options.vaeTiling != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        !(runtime == LocalImageRuntime.QNN_HTP && options.ultraFix != null)
    ) {
        localImageInputReject(
            "unsupported_vae_tiling",
            "Runtime ${runtime.name} does not expose native VAE tiling controls."
        )
    }
    if (options.loras.isNotEmpty() && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        localImageInputReject(
            "unsupported_lora",
            "Runtime ${runtime.name} does not expose a native LoRA execution path."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        options.distilledGuidance != null
    ) {
        localImageInputReject(
            "unsupported_distilled_guidance",
            "Runtime ${runtime.name} has no distilled-guidance graph input; native execution was not started."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP && options.flowShift != null) {
        localImageInputReject(
            "unsupported_flow_shift",
            "Runtime ${runtime.name} does not expose a writable native flow-shift control."
        )
    }
    if (runtime == LocalImageRuntime.MNN_DIFFUSION &&
        options.taskMode == LocalImageTaskMode.EDIT
    ) {
        if (options.strength != null) {
            localImageInputReject(
                "unsupported_edit_strength",
                "This MNN edit execution path does not consume strength; native execution was not started."
            )
        }
    }
    // Runtime/profile capability checks belong to the resolved execution path. This generic
    // boundary only enforces product-wide lifecycle limits; it must not pre-emptively block an
    // edit or control request merely because a runtime is new or was not previously validated.
}

/**
 * Enforces the selected package's executable capability contract after profile resolution.
 * Runtime-wide checks above remain the early safety boundary; this check prevents a runtime
 * feature from being exposed to a model family whose native conditioner cannot consume it.
 */
internal fun validateLocalImageProfileProductOptions(
    profile: ImageExecutionProfile,
    options: LocalImageGenerationOptions
) {
    val capabilities = profile.capabilities
    val qwen21TextOnly = profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        profile.variant == ImageModelVariant.QWEN_IMAGE_21
    if (qwen21TextOnly && options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE) {
        localImageInputReject("task_mode_execution_unsupported", "Qwen-Image-2.1 GGUF 当前原生引擎仅接入文生图，未包含图像编辑编码器执行链。")
    }
    val sharedQnnVaePreview = profile.hasSharedQnnVaePreviewTopology()
    val runtimeHasNativePreview =
        (profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP && !qwen21TextOnly) ||
            sharedQnnVaePreview
    if (profile.runtime == LocalImageRuntime.QNN_HTP &&
        options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE
    ) {
        val hasExecutableTaskTopology = when (options.taskMode) {
            LocalImageTaskMode.IMG2IMG -> profile.hasExecutableQnnImg2ImgTopology()
            LocalImageTaskMode.INPAINT -> profile.hasExecutableQnnInpaintTopology()
            LocalImageTaskMode.CONTROL -> profile.graph.controlNet != null
            LocalImageTaskMode.TEXT_TO_IMAGE -> true
            LocalImageTaskMode.EDIT -> false
        }
        if (!hasExecutableTaskTopology) {
            localImageInputReject(
                "task_mode_execution_unsupported",
                "The resolved QNN graph topology cannot execute ${options.taskMode.wireName}; " +
                    "a concrete compatible input graph is required before native execution starts."
            )
        }
    }
    if (options.textualInversionIds.isNotEmpty() &&
        (!profile.capabilities.supportsTextualInversion ||
            !profile.tokenizer.supportsTextualInversion)
    ) {
        localImageInputReject(
            "textual_inversion_execution_unsupported",
            "The resolved tokenizer and conditioning graph do not support textual inversion."
        )
    }
    if (options.ultraFix != null && !profile.capabilities.supportsUltraFix) {
        localImageInputReject(
            "ultrafix_execution_unsupported",
            "The resolved graph does not expose the complete UltraFix pipeline."
        )
    }
    options.ultraFix?.let { request ->
        require(request.targetWidth in
            capabilities.ultraFixMinWidth..capabilities.ultraFixMaxWidth &&
            request.targetHeight in
            capabilities.ultraFixMinHeight..capabilities.ultraFixMaxHeight &&
            request.targetWidth % capabilities.ultraFixWidthMultiple == 0 &&
            request.targetHeight % capabilities.ultraFixHeightMultiple == 0 &&
            request.tileSize % capabilities.ultraFixWidthMultiple == 0 &&
            request.tileSize % capabilities.ultraFixHeightMultiple == 0 &&
            (capabilities.ultraFixRequiredTileSize == 0 ||
                request.tileSize == capabilities.ultraFixRequiredTileSize)
        ) {
            "UltraFix target and tile dimensions do not match the resolved graph topology."
        }
    }
    validateLocalImageTaskSamplerCapability(
        profile = profile,
        taskMode = options.taskMode,
        requestedScheduler = profile.scheduler.algorithm
    )
    if (options.batchCount > capabilities.maxBatchCount) {
        localImageInputReject(
            "unsupported_batch_count",
            "Profile ${profile.profileId} supports at most ${capabilities.maxBatchCount} output(s) per request."
        )
    }
    if (options.clipSkip != null && !capabilities.supportsClipSkip) {
        localImageInputReject(
            "unsupported_clip_skip",
            "Profile ${profile.profileId} has no CLIP layer-skip execution path."
        )
    }
    if (options.vaeTiling != null && options.ultraFix == null &&
        !capabilities.supportsVaeTiling
    ) {
        localImageInputReject(
            "unsupported_vae_tiling",
            "Profile ${profile.profileId} does not consume native VAE tiling controls."
        )
    }
    if (options.preview != null && !runtimeHasNativePreview) {
        localImageInputReject(
            "unsupported_preview",
            "Profile ${profile.profileId} does not expose a native live-preview callback."
        )
    }
    if (options.preview != null && profile.runtime == LocalImageRuntime.QNN_HTP) {
        if (options.preview.interval !in 1..10) {
            localImageInputReject(
                "invalid_preview_interval",
                "QNN live preview interval must be between 1 and 10 steps."
            )
        }
        val supportedPreview =
            sharedQnnVaePreview && options.preview.mode == LocalImagePreviewMode.VAE
        if (!supportedPreview) {
            localImageInputReject(
                "unsupported_preview",
                "QNN preview requires a resolved shared-QNN VAE topology."
            )
        }
    }
    if (options.loras.isNotEmpty() && !capabilities.supportsLora) {
        localImageInputReject(
            "unsupported_lora",
            "Profile ${profile.profileId} does not expose LoRA adapter execution."
        )
    }
}


// Split bridge: nativeFloatMatches is file-private and serves both the SDCPP and QNN
// successor files.  Keeping the original private declaration and call sites byte-identical
// requires this single internal top-level dispatch entry.
internal fun localImageInputNativeFloatMatches(first: Double, second: Double): Boolean =
    nativeFloatMatches(first, second)
