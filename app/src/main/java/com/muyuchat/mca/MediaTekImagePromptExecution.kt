package com.muyuchat.mca

/**
 * Prompt execution evidence for the MediaTek Neuron pipeline.
 *
 * The Neuron bundle owns its tokenizer and text-conditioning graph, so the SD "profile + language
 * evidence" machinery does not apply: the package's v3/v4 manifest is the authority. This slice
 * therefore admits the same prompt text the user entered (no hidden rewriting), uses the manifest
 * bytes as the profile/prompt-language binding, and fails closed for text the packaged encoder has
 * no evidence for.
 *
 * ASCII admission matches the whole product's English-dominant default: the Anima reference
 * package was verified with English captions only, and inventing native-multilingual evidence for
 * it would overstate what was tested.
 */
internal fun prepareMediaTekImagePromptExecution(
    model: LocalImageModelRecord,
    prompt: String,
    options: LocalImageGenerationOptions
): LocalImagePromptExecution {
    val contract = model.requireMediaTekImageProductContract()
    val effectiveNegativePrompt = options.negativePrompt.orEmpty()
    if (prompt.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS ||
        effectiveNegativePrompt.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) {
        throw LocalImageProductContractException(
            code = "invalid_image_prompt",
            message = "天玑 NPU 提示词超过 ${LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS} " +
                "字符上限；本次尚未启动图片生成。"
        )
    }
    if (prompt.containsHanScript() ||
        effectiveNegativePrompt.containsHanScript() ||
        !prompt.isSafeAsciiDiffusionPrompt() ||
        !effectiveNegativePrompt.isSafeAsciiDiffusionPrompt()
    ) {
        throw LocalImageProductContractException(
            code = "unsupported_mediatek_anima_prompt_language",
            message = "天玑 NPU 模型当前只接受英文提示词（ASCII 字母、数字与常见标点）；" +
                "请将正向和负向提示词改写为英文后重试；本次尚未启动图片生成。"
        )
    }
    val bindings = contract.promptExecutionBindings()
    return LocalImagePromptExecution(
        originalPrompt = prompt,
        effectivePrompt = prompt,
        originalNegativePrompt = options.negativePrompt,
        effectiveNegativePrompt = effectiveNegativePrompt,
        negativePromptSource = if (options.negativePrompt != null) {
            LocalImageNegativePromptSource.USER
        } else {
            LocalImageNegativePromptSource.EMPTY
        },
        method = LocalImagePromptTransformationMethod.DIRECT,
        imageProfileBindingFingerprint = bindings.imageProfileFingerprint,
        promptLanguageBindingFingerprint = bindings.promptLanguageFingerprint
    )
}
