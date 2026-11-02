package com.muyuchat.core.mediatek

/** Describes where a tokenizer comes from; device and chipset identity are deliberately absent. */
enum class MediaTekTokenizerSource {
    BUNDLE_ASSET,
    RUNTIME_PROVIDER,
}

enum class MediaTekConditioningBranch {
    POSITIVE,
    NEGATIVE,
}

enum class MediaTekAnimaTokenWeighting {
    UNIFORM_ONE,
    PROMPT_ATTENTION,
}

enum class MediaTekAnimaTokenTruncationPolicy {
    REJECT,
    TRUNCATE_TAIL,
}

enum class MediaTekAnimaTokenizerPaddingPolicy {
    RIGHT_PAD,
}

/** Fixed-shape tokenizer output contract; truncation and right-padding are part of the model input. */
data class MediaTekAnimaTokenizerContract(
    val source: MediaTekTokenizerSource,
    val asset: MediaTekBundleAssetContract?,
    val providerId: String?,
    val sequenceLength: Int,
    val padTokenId: Int,
    val paddingPolicy: MediaTekAnimaTokenizerPaddingPolicy,
    val paddingWeight: Float,
    val weighting: MediaTekAnimaTokenWeighting,
    val truncationPolicy: MediaTekAnimaTokenTruncationPolicy,
) {
    fun validate(label: String): List<String> = buildList {
        if (sequenceLength !in 1..4096) add("$label: sequenceLength must be within 1..4096")
        if (padTokenId < 0) add("$label: padTokenId must be nonnegative")
        if (paddingPolicy != MediaTekAnimaTokenizerPaddingPolicy.RIGHT_PAD) add("$label: tokenizer must right-pad to its fixed sequenceLength")
        if (!paddingWeight.isFinite() || paddingWeight != 0f) add("$label: paddingWeight must be finite zero")
        when (source) {
            MediaTekTokenizerSource.BUNDLE_ASSET -> {
                if (asset == null) add("$label: bundle tokenizer source requires asset")
                if (!providerId.isNullOrBlank()) add("$label: bundle tokenizer source cannot set providerId")
            }
            MediaTekTokenizerSource.RUNTIME_PROVIDER -> {
                if (asset != null) add("$label: runtime tokenizer source cannot set asset")
                if (providerId.isNullOrBlank()) add("$label: runtime tokenizer source requires providerId")
            }
        }
        asset?.let { addAll(it.validate("$label tokenizer")) }
    }
}

data class MediaTekBundleAssetContract(
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
) {
    fun validate(label: String): List<String> = buildList {
        if (!isSafeAssetPath(file)) add("$label: asset file must be a relative assets/ path")
        if (!sha256.matches(SHA256_PATTERN)) add("$label: asset sha256 is invalid")
        if (sizeBytes <= 0) add("$label: asset sizeBytes must be positive")
        if (mediaType.isBlank()) add("$label: mediaType is empty")
    }
}

data class MediaTekConditioningAssetContract(
    val asset: MediaTekBundleAssetContract,
    val consumerGraphFile: String,
    val consumerInput: String,
)

data class MediaTekAnimaTextEncoderContract(
    val graphs: List<MediaTekGraphContract>,
    val qwenTokenizer: MediaTekAnimaTokenizerContract,
    val t5Tokenizer: MediaTekAnimaTokenizerContract,
    val tokenIdsInput: String,
    val attentionMaskInput: String,
    val embeddingsOutput: String,
    val conditioningBranches: Set<MediaTekConditioningBranch> = MediaTekConditioningBranch.entries.toSet(),
    val conditioningAssets: List<MediaTekConditioningAssetContract> = emptyList(),
) {
    fun validate(): List<String> = buildList {
        if (graphs.size != 1) add("text encoder must contain exactly one graph; multi-graph chaining is unsupported")
        if (tokenIdsInput.isBlank()) add("text encoder tokenIdsInput is empty")
        if (attentionMaskInput.isBlank()) add("text encoder attentionMaskInput is empty")
        if (embeddingsOutput.isBlank()) add("text encoder embeddingsOutput is empty")
        addAll(qwenTokenizer.validate("Qwen"))
        addAll(t5Tokenizer.validate("T5"))
        if (qwenTokenizer.weighting != MediaTekAnimaTokenWeighting.UNIFORM_ONE) {
            add("Qwen token weighting must be UNIFORM_ONE")
        }
        if (t5Tokenizer.weighting != MediaTekAnimaTokenWeighting.PROMPT_ATTENTION) {
            add("T5 token weighting must be PROMPT_ATTENTION")
        }
        if (conditioningBranches != MediaTekConditioningBranch.entries.toSet()) {
            add("text encoder must support dynamic positive and negative prompt conditioning")
        }
        graphs.forEachIndexed { index, graph ->
            if (graph.stage != MediaTekGraphStage.TEXT_ENCODER) add("text encoder graph $index has stage ${graph.stage}")
            addAll(graph.validate())
        }
        val first = graphs.firstOrNull()
        val tokenIds = first?.inputTensor(tokenIdsInput)
        if (tokenIds == null) {
            add("first text encoder graph does not declare tokenIdsInput '$tokenIdsInput'")
        } else if (!tokenIds.dtype.equals("int32", ignoreCase = true) ||
            tokenIds.shape != listOf(1, qwenTokenizer.sequenceLength) ||
            !tokenIds.layout.equals("NT", ignoreCase = true)
        ) {
            add("text encoder token IDs must be int32 NT [1,${qwenTokenizer.sequenceLength}]")
        }
        val mask = first?.inputTensor(attentionMaskInput)
        if (mask == null) {
            add("first text encoder graph does not declare attentionMaskInput '$attentionMaskInput'")
        } else if (!mask.dtype.equals("int32", ignoreCase = true) ||
            mask.shape != listOf(1, qwenTokenizer.sequenceLength) ||
            !mask.layout.equals("NT", ignoreCase = true)
        ) {
            add("text encoder attention mask must be int32 NT [1,${qwenTokenizer.sequenceLength}]")
        }
        val last = graphs.lastOrNull()
        val embeddings = last?.outputTensor(embeddingsOutput)
        if (last != null && embeddings == null) add("last text encoder graph does not declare embeddingsOutput '$embeddingsOutput'")
        if (embeddings != null &&
            (embeddings.dtype != "float32" || embeddings.shape != listOf(1, qwenTokenizer.sequenceLength, 1024) ||
                !embeddings.layout.equals("NTC", ignoreCase = true))
        ) {
            add("text encoder must emit float32 NTC [1,${qwenTokenizer.sequenceLength},1024] Qwen hidden states")
        }
        val graphByFile = graphs.associateBy { normalizePath(it.file) }
        conditioningAssets.forEachIndexed { index, binding ->
            addAll(binding.asset.validate("conditioning asset $index"))
            val consumer = graphByFile[normalizePath(binding.consumerGraphFile)]
            if (consumer == null) {
                add("conditioning asset $index references a graph outside the text encoder")
            } else if (consumer.inputTensor(binding.consumerInput) == null) {
                add("conditioning asset $index references unknown input '${binding.consumerInput}'")
            }
        }
        if (conditioningAssets.map { normalizePath(it.asset.file) }.size !=
            conditioningAssets.map { normalizePath(it.asset.file) }.toSet().size
        ) add("duplicate conditioning asset file")
    }

    internal fun embeddingTensor(): MediaTekTensorContract? = graphs.lastOrNull()?.outputTensor(embeddingsOutput)

    val tokenizerSource: MediaTekTokenizerSource get() = qwenTokenizer.source
    val tokenizerAsset: MediaTekBundleAssetContract? get() = qwenTokenizer.asset
    val tokenizerProviderId: String? get() = qwenTokenizer.providerId
    val tokenizerAssets: List<MediaTekBundleAssetContract>
        get() = listOfNotNull(qwenTokenizer.asset, t5Tokenizer.asset)

    fun assets(): List<MediaTekBundleAssetContract> =
        tokenizerAssets + conditioningAssets.map { it.asset }
}

data class MediaTekRuntimeRequirements(
    val minimumNeuronVersion: String,
    val minimumLiteRtVersion: String,
    val compilerPluginId: String,
    val dispatchPluginId: String,
    val supportedPrecisions: Set<MediaTekPrecision>,
) {
    fun validate(): List<String> = buildList {
        if (!minimumNeuronVersion.matches(VERSION_PATTERN)) add("minimumNeuronVersion must be semantic major.minor.patch")
        if (!minimumLiteRtVersion.matches(VERSION_PATTERN)) add("minimumLiteRtVersion must be semantic major.minor.patch")
        if (compilerPluginId.isBlank()) add("compilerPluginId is empty")
        if (dispatchPluginId.isBlank()) add("dispatchPluginId is empty")
        if (supportedPrecisions.isEmpty()) add("supportedPrecisions is empty")
    }

    private companion object {
        val VERSION_PATTERN = Regex("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?")
    }
}

/** Schema v3 represents dynamic text conditioning and the complete ordered Anima graph topology. */
data class MediaTekAnimaManifest(
    val schema: String,
    val family: String,
    val runtime: String,
    val accelerator: String,
    val compileMode: MediaTekCompileMode,
    val sdkName: String,
    val sdkVersion: String,
    val runtimeRequirements: MediaTekRuntimeRequirements,
    val textEncoder: MediaTekAnimaTextEncoderContract,
    val sampling: MediaTekAnimaSamplingContract,
    val outputMime: String = "image/png",
) {
    fun validate(): List<String> = buildList {
        if (schema != SCHEMA) add("schema must be $SCHEMA")
        if (family != ANIMA_FAMILY) add("family must be $ANIMA_FAMILY")
        if (runtime != RUNTIME) add("runtime must be $RUNTIME")
        if (accelerator != ACCELERATOR) add("accelerator must be $ACCELERATOR")
        if (sdkName.isBlank()) add("sdk name is empty")
        if (sdkVersion.isBlank()) add("sdk version is empty")
        if (!outputMime.equals("image/png", ignoreCase = true)) add("outputMime must be image/png")
        addAll(runtimeRequirements.validate())
        addAll(textEncoder.validate())
        addAll(sampling.validate(textEncoder))

        val graphs = textEncoder.graphs + sampling.graphs()
        val duplicateGraphs = graphs.map { normalizePath(it.file) }
        if (duplicateGraphs.size != duplicateGraphs.toSet().size) add("duplicate graph file")
        val assets = textEncoder.assets() + sampling.generationProfiles.flatMap { profile ->
            profile.blockModulationsByStepCount.values.map { it.asset } +
                profile.finalModulationsByStepCount.values.map { it.asset } +
                listOfNotNull(profile.latentInitialization.verifiedLatentAsset?.asset)
        }
        val allFiles = duplicateGraphs + assets.map { normalizePath(it.file) }
        if (allFiles.size != allFiles.toSet().size) add("graph and asset paths must be unique")
        graphs.forEach { graph ->
            if (graph.precision !in runtimeRequirements.supportedPrecisions) {
                add("${graph.file}: precision ${graph.precision} is not declared as supported by the runtime")
            }
        }
    }

    companion object {
        const val SCHEMA = "mca.image_engine.bundle.v3"
        const val ANIMA_FAMILY = "ANIMA"
        const val RUNTIME = "MEDIATEK_NEURON"
        const val ACCELERATOR = "MEDIATEK_NPU"
    }
}

private fun normalizePath(path: String): String = path.replace('\\', '/')

private fun isSafeAssetPath(path: String): Boolean = isSafeRelativeBundlePath(path, "assets")

internal fun isSafeRelativeBundlePath(path: String, expectedRoot: String): Boolean {
    if (path.isBlank() || path.contains('\u0000') || path.contains(':') || path.startsWith('/') || path.startsWith('\\')) {
        return false
    }
    val normalized = normalizePath(path)
    val segments = normalized.split('/')
    return segments.firstOrNull() == expectedRoot && segments.none { it.isBlank() || it == "." || it == ".." }
}

private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
