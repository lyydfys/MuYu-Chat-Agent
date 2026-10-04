package com.muyuchat.mca

import java.io.File

/**
 * Contract validation and canonicalisation for one request's outer image controls.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim. The check order and every
 * message are unchanged.
 */

internal fun LocalImageGenerationOptions.validateProductInputContract(
    requirePreparedPaths: Boolean = true
) {
    localImageInputValidateShape(
        taskMode = taskMode,
        inputPresent = inputImage != null,
        maskPresent = maskImage != null,
        controlPresent = controlImage != null,
        strength = strength,
        controlStrength = controlStrength
    )
    require(batchCount in 1..8) { "batchCount must be between 1 and 8." }
    require(loras.size <= LocalImagePreparedLora.MAX_COUNT) {
        "At most ${LocalImagePreparedLora.MAX_COUNT} LoRA adapters may be used per request."
    }
    require(loras.map(LocalImagePreparedLora::id).distinct().size == loras.size) {
        "LoRA adapter ids must be unique per request."
    }
    require(loras.map { File(it.path).canonicalPath }.distinct().size == loras.size) {
        "LoRA adapter paths must be unique per request."
    }
    clipSkip?.let { value ->
        require(value in -1..32) { "clipSkip must be -1 (model default) or between 0 and 32." }
    }
    if (maskImage != null && inputImage != null) {
        require(maskImage.orientedWidth == inputImage.orientedWidth &&
            maskImage.orientedHeight == inputImage.orientedHeight
        ) {
            "maskImage oriented dimensions must exactly match inputImage dimensions."
        }
    }
    if (requirePreparedPaths) {
        listOfNotNull(inputImage, maskImage, controlImage).forEach { input ->
            require(input.path.isNotBlank()) { "Prepared image input path must not be blank." }
        }
    }
    ultraFix?.let { request ->
        require(taskMode == LocalImageTaskMode.IMG2IMG && inputImage != null) {
            "UltraFix requires one prepared img2img source."
        }
        require(maskImage == null && controlImage == null) {
            "UltraFix does not accept mask or control inputs."
        }
        require(batchCount == 1) { "UltraFix supports exactly one output per native request." }
        require(preview == null) { "UltraFix does not publish live previews." }
        require(width == null || width == request.targetWidth)
        require(height == null || height == request.targetHeight)
        require(steps == null || steps == request.refinementSteps)
        require(strength == null || kotlin.math.abs(strength - request.strength) <= 1.0e-12)
        require(vaeTiling == null || (
            vaeTiling.tileSize == request.tileSize &&
                kotlin.math.abs(vaeTiling.overlap - request.overlap) <= 1.0e-12
            )) {
            "UltraFix outer execution controls must match its structured request."
        }
    }
}

/** Validates path-free persisted controls before a history item is shown or restaged. */
internal fun LocalImageGenerationOptions.validateHistoryProductInputContract() {
    require(inputImage == null && maskImage == null && controlImage == null &&
        loras.isEmpty() && preview == null
    ) { "Image history must not retain transient execution artifacts." }
    localImageInputValidateShape(
        taskMode = taskMode,
        inputPresent = false,
        maskPresent = false,
        controlPresent = false,
        strength = strength,
        controlStrength = controlStrength,
        allowMissingRequiredInputs = true
    )
    require(batchCount in 1..8) { "Image history batchCount must be between 1 and 8." }
    clipSkip?.let { require(it in -1..32) { "Image history clipSkip is invalid." } }
    ultraFix?.let { request ->
        require(taskMode == LocalImageTaskMode.IMG2IMG && batchCount == 1) {
            "UltraFix history must retain one img2img output."
        }
        require(width == request.targetWidth && height == request.targetHeight &&
            steps == request.refinementSteps &&
            strength != null && kotlin.math.abs(strength - request.strength) <= 1.0e-12 &&
            vaeTiling?.tileSize == request.tileSize &&
            kotlin.math.abs((vaeTiling?.overlap ?: Double.NaN) - request.overlap) <= 1.0e-12
        ) { "UltraFix history controls conflict with its structured request." }
    }
}

/**
 * UltraFix owns its target canvas, denoising tail, and VAE tile plan. UI and Local API callers may
 * omit the duplicated outer controls, but a conflicting duplicate is always rejected.
 */
internal fun LocalImageGenerationOptions.withCanonicalUltraFixControls(): LocalImageGenerationOptions {
    val request = ultraFix ?: return this
    validateProductInputContract(requirePreparedPaths = false)
    return copy(
        width = request.targetWidth,
        height = request.targetHeight,
        steps = request.refinementSteps,
        strength = request.strength,
        vaeTiling = LocalImageVaeTilingOptions(request.tileSize, request.overlap)
    )
}
