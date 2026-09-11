package com.muyuchat.mca

internal enum class ImagePromptTokenMeasurementRoute {
    MNN_BRIDGE,
    SDCPP_CLIP,
    ESTIMATE,
}

/** Selects an exact tokenizer adapter when one is known, otherwise an explicit estimate. */
internal fun imagePromptTokenMeasurementRoute(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily,
    backend: ImageTokenizerBackend,
): ImagePromptTokenMeasurementRoute? = when {
    backend == ImageTokenizerBackend.SDCPP_NATIVE &&
        runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        family in setOf(
            LocalImageModelFamily.SD15,
            LocalImageModelFamily.SD21,
            LocalImageModelFamily.SDXL,
            LocalImageModelFamily.SD_TURBO,
        ) -> ImagePromptTokenMeasurementRoute.SDCPP_CLIP

    backend == ImageTokenizerBackend.SDCPP_NATIVE &&
        runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP ->
        ImagePromptTokenMeasurementRoute.ESTIMATE

    backend == ImageTokenizerBackend.TOKENIZERS_CPP ||
        backend == ImageTokenizerBackend.MNN_MTOK ->
        ImagePromptTokenMeasurementRoute.MNN_BRIDGE

    else -> null
}
