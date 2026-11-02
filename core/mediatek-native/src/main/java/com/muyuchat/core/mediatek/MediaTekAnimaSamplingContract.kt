package com.muyuchat.core.mediatek

enum class MediaTekAnimaScheduler {
    FLOW_MATCHING_EULER,
}

enum class MediaTekAnimaTensorPacking {
    LATENT_NCHW_TO_PATCH_TOKENS_C_SLOWEST,
    PATCH_PREDICTION_C_FASTEST,
}

data class MediaTekAnimaPreludeContract(
    val graph: MediaTekGraphContract,
    val patchTokensInput: String,
    val hiddenOutput: String,
)

data class MediaTekAnimaBlockContract(
    val index: Int,
    val graph: MediaTekGraphContract,
    val hiddenInput: String,
    val contextInput: String,
    val modulationInputs: List<String>,
    val hiddenOutput: String,
)

data class MediaTekAnimaFinalContract(
    val graph: MediaTekGraphContract,
    val hiddenInput: String,
    val finalModulationInput: String,
    val flowPredictionOutput: String,
)

enum class MediaTekAnimaContextPaddingPolicy {
    ZERO_PAD_RIGHT,
}

enum class MediaTekAnimaContextTruncationPolicy {
    KEEP_PREFIX,
}

data class MediaTekAnimaContextAdapterContract(
    val graph: MediaTekGraphContract,
    val hiddenInput: String,
    val qwenAttentionMaskInput: String,
    val t5TokenIdsInput: String,
    val t5AttentionMaskInput: String,
    val t5WeightsInput: String,
    val adaptedContextOutput: String,
    val outputSequenceLength: Int = 512,
    val paddingPolicy: MediaTekAnimaContextPaddingPolicy = MediaTekAnimaContextPaddingPolicy.ZERO_PAD_RIGHT,
    val truncationPolicy: MediaTekAnimaContextTruncationPolicy = MediaTekAnimaContextTruncationPolicy.KEEP_PREFIX,
) {
    fun validate(textEncoder: MediaTekAnimaTextEncoderContract): List<String> = buildList {
        if (graph.stage != MediaTekGraphStage.DIFFUSION) add("context adapter graph has stage ${graph.stage}")
        addAll(graph.validate())
        if (outputSequenceLength != CONTEXT_LENGTH) add("context adapter outputSequenceLength must be $CONTEXT_LENGTH")
        if (paddingPolicy != MediaTekAnimaContextPaddingPolicy.ZERO_PAD_RIGHT) add("context adapter must zero-pad on the right")
        if (truncationPolicy != MediaTekAnimaContextTruncationPolicy.KEEP_PREFIX) add("context adapter must retain the context prefix when truncating")
        val boundInputs = listOf(hiddenInput, qwenAttentionMaskInput, t5TokenIdsInput, t5AttentionMaskInput, t5WeightsInput)
        if (boundInputs.any(String::isBlank) || boundInputs.size != boundInputs.toSet().size) {
            add("context adapter must bind five unique nonempty inputs")
        }
        if (adaptedContextOutput.isBlank()) add("context adapter adaptedContextOutput is empty")

        val hidden = graph.inputTensor(hiddenInput)
        if (hidden == null) add("context adapter does not declare hidden input '$hiddenInput'")
        else if (!matches(hidden, "float32", "NTC", listOf(1, textEncoder.qwenTokenizer.sequenceLength, QWEN_HIDDEN_WIDTH))) {
            add("context adapter hidden input must be float32 NTC [1,${textEncoder.qwenTokenizer.sequenceLength},$QWEN_HIDDEN_WIDTH]")
        }
        val qwenMask = graph.inputTensor(qwenAttentionMaskInput)
        if (qwenMask == null) add("context adapter does not declare Qwen attention-mask input '$qwenAttentionMaskInput'")
        else if (!matches(qwenMask, "int32", "NT", listOf(1, textEncoder.qwenTokenizer.sequenceLength))) {
            add("context adapter Qwen attention mask must be int32 NT [1,${textEncoder.qwenTokenizer.sequenceLength}]")
        }
        val t5Ids = graph.inputTensor(t5TokenIdsInput)
        if (t5Ids == null) add("context adapter does not declare T5 token IDs input '$t5TokenIdsInput'")
        else if (!matches(t5Ids, "int32", "NT", listOf(1, textEncoder.t5Tokenizer.sequenceLength))) {
            add("context adapter T5 token IDs must be int32 NT [1,${textEncoder.t5Tokenizer.sequenceLength}]")
        }
        val t5Mask = graph.inputTensor(t5AttentionMaskInput)
        if (t5Mask == null) add("context adapter does not declare T5 attention-mask input '$t5AttentionMaskInput'")
        else if (!matches(t5Mask, "int32", "NT", listOf(1, textEncoder.t5Tokenizer.sequenceLength))) {
            add("context adapter T5 attention mask must be int32 NT [1,${textEncoder.t5Tokenizer.sequenceLength}]")
        }
        val t5Weights = graph.inputTensor(t5WeightsInput)
        if (t5Weights == null) add("context adapter does not declare T5 token weights input '$t5WeightsInput'")
        else if (!matches(t5Weights, "float32", "NT", listOf(1, textEncoder.t5Tokenizer.sequenceLength))) {
            add("context adapter T5 token weights must be float32 NT [1,${textEncoder.t5Tokenizer.sequenceLength}]")
        }
        val adapted = graph.outputTensor(adaptedContextOutput)
        if (adapted == null) add("context adapter does not declare adapted context output '$adaptedContextOutput'")
        else if (!matches(adapted, "float32", "NTC", listOf(1, outputSequenceLength, CONTEXT_WIDTH))) {
            add("context adapter output must be float32 NTC [1,$outputSequenceLength,$CONTEXT_WIDTH]")
        }
    }

    private fun matches(tensor: MediaTekTensorContract, dtype: String, layout: String, shape: List<Int>) =
        tensor.dtype.equals(dtype, ignoreCase = true) &&
            tensor.layout.equals(layout, ignoreCase = true) &&
            tensor.shape == shape

    companion object {
        const val CONTEXT_LENGTH = 512
        const val CONTEXT_WIDTH = 1024
        const val QWEN_HIDDEN_WIDTH = 1024
    }
}

/**
 * Anima's final graph predicts flow. CFG and Euler update the 16-channel latent
 * in this sampler before the updated latent is expanded and passed to the VAE.
 */
data class MediaTekAnimaSamplingContract(
    val scheduler: MediaTekAnimaScheduler,
    val cfgGuidanceFormula: String,
    val eulerUpdateFormula: String,
    val latentChannels: Int,
    val latentDownsample: Int,
    val patchSize: Int,
    val patchifyPacking: MediaTekAnimaTensorPacking,
    val predictionPacking: MediaTekAnimaTensorPacking,
    val contextAdapter: MediaTekAnimaContextAdapterContract,
    val prelude: MediaTekAnimaPreludeContract,
    val blocks: List<MediaTekAnimaBlockContract>,
    val final: MediaTekAnimaFinalContract,
    val vae: MediaTekGraphContract,
    val vaeLatentInput: String,
    val vaeExpansionAxis: Int,
    val vaeImageOutput: String,
    val generationProfiles: List<MediaTekAnimaGenerationProfile>,
) {
    fun validate(textEncoder: MediaTekAnimaTextEncoderContract): List<String> = buildList {
        if (scheduler != MediaTekAnimaScheduler.FLOW_MATCHING_EULER) add("unsupported sampler")
        if (cfgGuidanceFormula != CFG_FORMULA) add("CFG guidance formula must be $CFG_FORMULA")
        if (eulerUpdateFormula != EULER_FORMULA) add("Euler update formula must be $EULER_FORMULA")
        if (latentChannels != LATENT_CHANNELS) add("Anima latentChannels must be $LATENT_CHANNELS")
        if (latentDownsample != LATENT_DOWNSAMPLE) add("Anima latentDownsample must be $LATENT_DOWNSAMPLE")
        if (patchSize != PATCH_SIZE) add("Anima patchSize must be $PATCH_SIZE")
        if (patchifyPacking != MediaTekAnimaTensorPacking.LATENT_NCHW_TO_PATCH_TOKENS_C_SLOWEST) add("unsupported patchify packing")
        if (predictionPacking != MediaTekAnimaTensorPacking.PATCH_PREDICTION_C_FASTEST) add("unsupported prediction packing")
        if (vaeExpansionAxis != 2) add("VAE latent expansion axis must be 2")
        if (vae.stage != MediaTekGraphStage.VAE) add("VAE graph has stage ${vae.stage}")
        addAll(vae.validate())
        val vaeInput = vae.inputTensor(vaeLatentInput)
        if (vaeInput == null) add("VAE graph does not declare updated latent input '$vaeLatentInput'")
        else if (!vaeInput.layout.equals("NCDHW", ignoreCase = true)) add("VAE updated latent layout must be NCDHW")
        val imageOutput = vae.outputTensor(vaeImageOutput)
        if (imageOutput == null) add("VAE graph does not declare image output '$vaeImageOutput'")
        else if (!imageOutput.layout.equals("NCDHW", ignoreCase = true)) add("VAE image output layout must be NCDHW")

        if (blocks.size != DIT_BLOCK_COUNT) add("Anima requires exactly $DIT_BLOCK_COUNT ordered DiT blocks")
        if (blocks.map { it.index } != blocks.indices.toList()) add("DiT block indexes must be unique and ordered from zero")
        (listOf("prelude" to prelude.graph) + blocks.map { "block ${it.index}" to it.graph } + listOf("final" to final.graph))
            .forEach { (label, graph) ->
                if (graph.stage != MediaTekGraphStage.DIFFUSION) add("$label graph has stage ${graph.stage}")
                addAll(graph.validate())
            }
        validateTextContext(textEncoder)
        addAll(contextAdapter.validate(textEncoder))
        validatePrelude()
        validateBlocks()
        validateFinal()

        if (generationProfiles.isEmpty()) add("sampling has no generation profiles")
        if (generationProfiles.map { it.id }.size != generationProfiles.map { it.id }.toSet().size) add("duplicate generation profile id")
        generationProfiles.forEach { profile ->
            addAll(profile.validate())
            validateProfileShapes(profile)
        }
    }

    fun graphs(): List<MediaTekGraphContract> =
        listOf(contextAdapter.graph, prelude.graph) + blocks.map { it.graph } + listOf(final.graph, vae)

    private fun MutableList<String>.validateTextContext(textEncoder: MediaTekAnimaTextEncoderContract) {
        if (textEncoder.graphs.size != 1) add("v3 supports one prompt text-encoder graph; graph chaining needs an explicit tensor map")
        val context = contextAdapter.graph.outputTensor(contextAdapter.adaptedContextOutput)
        blocks.forEach { block ->
            val tensor = block.graph.inputTensor(block.contextInput)
            if (tensor == null) add("block ${block.index} does not declare context input '${block.contextInput}'")
            else if (context != null && !sameTensorFormat(context, tensor)) add("block ${block.index} context input is incompatible with adapted Anima text conditioning")
        }
    }

    private fun MutableList<String>.validatePrelude() {
        val input = prelude.graph.inputTensor(prelude.patchTokensInput)
        val output = prelude.graph.outputTensor(prelude.hiddenOutput)
        if (input == null) add("prelude does not declare patch-token input '${prelude.patchTokensInput}'")
        if (output == null) add("prelude does not declare hidden output '${prelude.hiddenOutput}'")
        generationProfiles.forEach { profile ->
            val tokenShape = profile.patchTokenShape()
            if (input != null && (!sameShapeAndType(input, "float32", tokenShape) || !input.layout.equals("NTC", ignoreCase = true))) {
                add("prelude patch-token input must be float32 NTC $tokenShape")
            }
            if (output != null && !sameShapeAndType(output, "float32", profile.hiddenShape())) add("prelude hidden output shape must be ${profile.hiddenShape()} float32")
        }
    }

    private fun MutableList<String>.validateBlocks() {
        blocks.forEach { block ->
            if (block.modulationInputs.size != 3 || block.modulationInputs.any(String::isBlank) ||
                block.modulationInputs.size != block.modulationInputs.toSet().size
            ) add("block ${block.index} must declare three unique modulation inputs")
            val hiddenInput = block.graph.inputTensor(block.hiddenInput)
            val hiddenOutput = block.graph.outputTensor(block.hiddenOutput)
            if (hiddenInput == null) add("block ${block.index} does not declare hidden input '${block.hiddenInput}'")
            if (hiddenOutput == null) add("block ${block.index} does not declare hidden output '${block.hiddenOutput}'")
            block.modulationInputs.forEachIndexed { modulationIndex, name ->
                val tensor = block.graph.inputTensor(name)
                if (tensor == null) add("block ${block.index} does not declare modulation input '$name'")
                else if (tensor.dtype.lowercase() != "float32" || tensor.shape != listOf(1, MODULATION_WIDTH)) {
                    add("block ${block.index} modulation $modulationIndex graph input must be float32 [1,$MODULATION_WIDTH]")
                }
            }
            generationProfiles.forEach { profile ->
                if (hiddenInput != null && !sameShapeAndType(hiddenInput, "float32", profile.hiddenShape())) add("block ${block.index} hidden input shape must be ${profile.hiddenShape()} float32")
                if (hiddenOutput != null && !sameShapeAndType(hiddenOutput, "float32", profile.hiddenShape())) add("block ${block.index} hidden output shape must be ${profile.hiddenShape()} float32")
            }
        }
    }

    private fun MutableList<String>.validateFinal() {
        val hidden = final.graph.inputTensor(final.hiddenInput)
        val modulation = final.graph.inputTensor(final.finalModulationInput)
        val prediction = final.graph.outputTensor(final.flowPredictionOutput)
        if (hidden == null) add("final graph does not declare hidden input '${final.hiddenInput}'")
        if (modulation == null) add("final graph does not declare final modulation input '${final.finalModulationInput}'")
        if (prediction == null) add("final graph does not declare flow prediction output '${final.flowPredictionOutput}'")
        if (modulation != null && !sameShapeAndType(modulation, "float32", listOf(1, FINAL_MODULATION_WIDTH))) {
            add("final modulation graph input must be float32 [1,$FINAL_MODULATION_WIDTH]")
        }
        generationProfiles.forEach { profile ->
            if (hidden != null && !sameShapeAndType(hidden, "float32", profile.hiddenShape())) add("final hidden input shape must be ${profile.hiddenShape()} float32")
            if (prediction != null && !sameShapeAndType(prediction, "float32", profile.predictionShape())) add("final flow prediction shape must be ${profile.predictionShape()} float32")
        }
    }

    private fun MutableList<String>.validateProfileShapes(profile: MediaTekAnimaGenerationProfile) {
        val latent = profile.latentShape()
        val vaeInput = vae.inputTensor(vaeLatentInput)
        val vaeInputShape = listOf(1, LATENT_CHANNELS, 1, latent[2], latent[3])
        val pixels = listOf(1, 3, 1, profile.heightPixels, profile.widthPixels)
        val image = vae.outputTensor(vaeImageOutput)
        if (profile.latentInitialization.verifiedLatentAsset?.shape?.let { it != latent } == true) {
            add("profile ${profile.id} initial latent asset shape must be $latent")
        }
        if (vaeInput != null && !sameShapeAndType(vaeInput, "float32", vaeInputShape)) {
            add("profile ${profile.id} VAE input must match sampler-updated latent after depth expansion: $vaeInputShape")
        }
        if (image != null && !sameShapeAndType(image, "float32", pixels)) add("profile ${profile.id} VAE output shape must be $pixels")
    }

    private fun MediaTekAnimaGenerationProfile.latentShape() =
        listOf(1, LATENT_CHANNELS, heightPixels / LATENT_DOWNSAMPLE, widthPixels / LATENT_DOWNSAMPLE)

    private fun MediaTekAnimaGenerationProfile.patchTokenShape(): List<Int> {
        val tokens = (heightPixels / LATENT_DOWNSAMPLE / PATCH_SIZE) * (widthPixels / LATENT_DOWNSAMPLE / PATCH_SIZE)
        return listOf(1, tokens, PATCH_FEATURES)
    }

    private fun MediaTekAnimaGenerationProfile.hiddenShape() = listOf(1, patchTokenShape()[1], HIDDEN_WIDTH)

    private fun MediaTekAnimaGenerationProfile.predictionShape() =
        listOf(1, patchTokenShape()[1], latentChannels * patchSize * patchSize)

    private fun sameShapeAndType(tensor: MediaTekTensorContract, dtype: String, shape: List<Int>) =
        tensor.dtype.equals(dtype, ignoreCase = true) && tensor.shape == shape

    private fun sameTensorFormat(left: MediaTekTensorContract, right: MediaTekTensorContract) =
        left.dtype.equals(right.dtype, ignoreCase = true) && left.layout.equals(right.layout, ignoreCase = true) && left.shape == right.shape

    companion object {
        const val DIT_BLOCK_COUNT = 28
        const val LATENT_CHANNELS = 16
        const val LATENT_DOWNSAMPLE = 8
        const val PATCH_SIZE = 2
        const val PATCH_FEATURES = 68
        const val HIDDEN_WIDTH = 2048
        const val CONTEXT_LENGTH = 512
        const val CONTEXT_WIDTH = 1024
        const val MODULATION_WIDTH = 6144
        const val FINAL_MODULATION_WIDTH = 4096
        const val CFG_FORMULA = "negative + cfgScale * (positive - negative)"
        const val EULER_FORMULA = "latent += guidedFlow * (sigmaNext - sigma)"
    }
}
