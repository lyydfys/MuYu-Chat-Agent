package com.muyuchat.mca

import com.muyuchat.api.local.ImageGenerationApiRequest

internal data class LocalImageApiDispatch(
    val options: LocalImageGenerationOptions,
    val inputDraft: LocalImageInputDraft
)

/** Exact Local Images API to worker request mapping; no execution control is inferred here. */
internal fun ImageGenerationApiRequest.toLocalImageApiDispatch(): LocalImageApiDispatch =
    LocalImageApiDispatch(
        options = LocalImageGenerationOptions(
            negativePrompt = negativePrompt,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            cfgScale = cfgScale,
            sampleMethod = sampler,
            clipSkip = clipSkip,
            batchCount = imageCount,
            vaeTiling = vaeTiling?.let { tiling ->
                LocalImageVaeTilingOptions(tiling.tileSize, tiling.overlap)
            },
            preview = preview?.let { requestedPreview ->
                LocalImagePreviewOptions(
                    interval = requestedPreview.interval,
                    mode = LocalImagePreviewMode.fromWireName(requestedPreview.mode)
                )
            }
        ),
        inputDraft = LocalImageInputDraft(
            taskMode = LocalImageTaskMode.fromWireName(taskMode.wireName),
            inputImageReference = inputImage,
            maskImageReference = maskImage,
            controlImageReference = controlImage,
            strength = strength,
            controlStrength = controlStrength
        )
    )
