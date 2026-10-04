package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import java.io.File
import org.json.JSONObject

/**
 * QNN local-image prompt contracts (P5). Moved verbatim out of LocalImageProvider.kt: text-encoder
 * context and tokenizer resolution, CLIP prompt token encoding, executed negative-prompt and
 * generation-contract resolution, plus the QnnImageGenerationContract record. Call sites
 * (LocalImageProvider, LocalImageQnnRunner, ImagePromptTokenMeasurementHost) and tests are
 * unchanged -- same package, same internal visibility, byte-identical bodies.
 */

/**
 * Returns the exact bundle-relative QNN text-encoder context path.  Presence
 * of this graph selects the token-ID contract; it is a package capability,
 * never a device admission rule.
 */
internal fun qnnNativeTextEncoderContextPath(bundleRoot: File): String? =
    qnnFirstContextPath(bundleRoot, "text_encoder.bin")

/** Resolves the exact QNN text-encoder artifact declared by a shared-text graph topology. */
internal fun qnnDeclaredTextEncoderContextPath(
    bundleRoot: File,
    declaredArtifact: ImageGraphArtifactContract?
): String? {
    val relativePath = declaredArtifact
        ?.relativePath
        ?.trim()
        ?.takeIf { it.endsWith(".bin", ignoreCase = true) }
        ?: return null
    require(!File(relativePath).isAbsolute &&
        !relativePath.startsWith('/') &&
        !relativePath.startsWith('\\') &&
        !Regex("^[A-Za-z]:").containsMatchIn(relativePath)
    ) { "Declared QNN text-encoder path must stay inside the installed bundle." }
    val root = bundleRoot.canonicalFile
    val candidate = File(root, relativePath).canonicalFile
    require(candidate.path.startsWith(root.path + File.separator)) {
        "Declared QNN text-encoder path escapes the installed bundle."
    }
    return candidate
        .takeIf { it.isFile && it.length() > 0L }
        ?.relativeTo(root)
        ?.invariantSeparatorsPath
}

internal fun qnnFirstContextPath(bundleRoot: File, vararg names: String): String? {
    val expected = names.map(String::lowercase).toSet()
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
        ?.let { file ->
            runCatching { file.canonicalFile.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        }
}

/** Locates the MNN tokenizer sidecar consumed by [MtokTokenizer]. */
internal fun qnnClipTokenizerRoot(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .filter { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.mtok", ignoreCase = true)
        }
        .mapNotNull { file ->
            runCatching { file.canonicalFile }
                .getOrNull()
                ?.takeIf { candidate ->
                    candidate.path.startsWith(root.path + File.separator)
                }
                ?.parentFile
        }
        .firstOrNull()
}

/** Locates the complete tokenizer contract used by the standard CLIP backend. */
internal fun qnnClipTokenizerJsonFile(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.json", ignoreCase = true)
        }
        ?.let { file ->
            runCatching { file.canonicalFile }
                .getOrNull()
                ?.takeIf { candidate ->
                    candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
                }
        }
}

/**
 * Resolves the tokenizer JSON named by a verified native-multilingual proof.
 *
 * This deliberately does not search the bundle: a bundle may contain tokenizer sidecars for
 * unrelated graphs, and choosing the first matching file would disconnect Chinese text from the
 * signed prompt-to-encoder closure. Native opens this exact path with no-follow semantics and
 * verifies its descriptor while it is consumed.
 */
internal fun qnnPinnedNativeMultilingualTokenizerJsonFile(
    bundleRoot: File,
    evidence: ImageTextEncoderLanguageEvidence
): File {
    val tokenizerAsset = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Verified native-multilingual QNN execution requires exactly one TOKENIZER_JSON asset."
    )
    val relativePath = tokenizerAsset.relativePath.replace('\\', '/').trim()
    require(
        relativePath.isNotBlank() &&
            !relativePath.startsWith('/') &&
            !Regex("^[A-Za-z]:").containsMatchIn(relativePath) &&
            relativePath.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".."
            }
    ) { "Pinned native-multilingual tokenizer path must stay inside the image bundle." }
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "Pinned native-multilingual tokenizer bundle root is unavailable." }
    val requested = File(root, relativePath).canonicalFile
    require(requested.path.startsWith(root.path + File.separator)) {
        "Pinned native-multilingual tokenizer path escapes the image bundle."
    }
    require(requested.isFile && requested.length() == tokenizerAsset.sizeBytes) {
        "Pinned native-multilingual tokenizer asset is missing or has an unexpected size."
    }
    require(requested.sha256Contents().equals(tokenizerAsset.fingerprint, ignoreCase = true)) {
        "Pinned native-multilingual tokenizer asset digest differs from the signed closure."
    }
    return requested
}

/** Writes raw token IDs or the versioned token+weight payload selected by the resolved profile. */
internal fun encodeQnnClipPromptTokenIds(
    bridge: NativeMnnDiffusionBridge,
    bundleRoot: File,
    prompt: String,
    outputFile: File,
    negativePrompt: String = "",
    bosId: Int = 49_406,
    eosId: Int = 49_407,
    padId: Int = 49_407,
    maxTokens: Int = 77,
    promptWeightingEnabled: Boolean = false,
    /** Non-null only after the caller verifies the complete signed QNN Chinese closure. */
    nativeMultilingualEvidence: ImageTextEncoderLanguageEvidence? = null
): String = runCatching {
    require(maxTokens in 1..4_096) { "CLIP tokenizer max length is invalid: $maxTokens." }
    val tokenizerJson = nativeMultilingualEvidence?.let { evidence ->
        qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    } ?: qnnClipTokenizerJsonFile(bundleRoot)
    val tokenizerRoot = nativeMultilingualEvidence
        ?.let { null }
        ?: qnnClipTokenizerRoot(bundleRoot)
    val requireVersionedTokenWeightPayload = nativeMultilingualEvidence != null
    val tokenizerBackend: String
    var unweightedProbeEvidence: NativePromptEncodingEvidence? = null
    outputFile.parentFile?.mkdirs()
    if (promptWeightingEnabled || requireVersionedTokenWeightPayload) {
        require(tokenizerJson != null) {
            if (requireVersionedTokenWeightPayload) {
                "Native-multilingual QNN execution requires its pinned tokenizer JSON asset."
            } else {
                "Prompt weighting requires tokenizer/tokenizer.json in the image bundle."
            }
        }
        return@runCatching bridge.encodePromptTokenIdsWithWeightsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens,
            promptToEncoderClosureSha256 = nativeMultilingualEvidence
                ?.promptToEncoderClosureSha256()
                .orEmpty(),
            outputPath = outputFile.absolutePath
        )
    }
    if (tokenizerJson != null) {
        // An int32 token-id graph cannot apply attention weights before its Transformer. Parse
        // with the same native tokenizer first so a genuinely weighted prompt fails explicitly
        // instead of being silently reinterpreted as literal punctuation.
        val probeFile = File(outputFile.parentFile, outputFile.name + ".weight-probe")
        try {
            val probe = JSONObject(
                bridge.encodePromptTokenIdsWithWeightsFromJson(
                    tokenizerJsonPath = tokenizerJson.absolutePath,
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    bosId = bosId,
                    eosId = eosId,
                    padId = padId,
                    maxTokens = maxTokens,
                    promptToEncoderClosureSha256 = "",
                    outputPath = probeFile.absolutePath
                )
            )
            require(probe.optBoolean("ok", false)) {
                probe.optString("error").ifBlank { "Failed to inspect CLIP prompt weighting." }
            }
            val positiveWeighted = probe.optInt("positiveWeightedTokenCount", -1)
            val negativeWeighted = probe.optInt("negativeWeightedTokenCount", -1)
            require(positiveWeighted >= 0 && negativeWeighted >= 0) {
                "Native CLIP prompt-weighting evidence is incomplete."
            }
            val weightedTokenCount = positiveWeighted + negativeWeighted
            require(
                probe.optBoolean("promptWeightingApplied", false) == (weightedTokenCount > 0)
            ) { "Native CLIP prompt-weighting evidence is inconsistent." }
            if (weightedTokenCount > 0) {
                throw LocalImageProductContractException(
                    "prompt_weighting_execution_unsupported",
                    "This package's int32 token-id text encoder cannot apply non-unity prompt weights before the Transformer."
                )
            }
            unweightedProbeEvidence = requireNativePromptEncodingEvidence(
                source = probe,
                prompt = prompt,
                negativePrompt = negativePrompt,
                requireNoAppliedWeights = true
            )
        } finally {
            runCatching { probeFile.delete() }
            runCatching { File(probeFile.absolutePath + ".part").delete() }
        }
    }
    val tokenIds = if (tokenizerJson != null) {
        tokenizerBackend = "tokenizers_cpp"
        bridge.tokenizePromptTokenIdsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens
        )
    } else if (tokenizerRoot != null && negativePrompt.isEmpty()) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIdsWithConfig(
            bundleRoot = bundleRoot.absolutePath,
            prompt = prompt,
            tokenizerRoot = tokenizerRoot.absolutePath,
            bosId = bosId,
            eosId = eosId,
            maxTokens = maxTokens
        )
    } else if (negativePrompt.isEmpty() && bosId == 49_406 && eosId == 49_407 && padId == 49_407 && maxTokens == 77) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIds(bundleRoot.absolutePath, prompt)
    } else {
        error(
            "The image bundle does not contain tokenizer/tokenizer.json; " +
                "the requested negative prompt or tokenizer contract cannot be executed exactly."
        )
    }
    val expectedTokenCount = maxTokens * 2
    require(tokenIds.size == expectedTokenCount) {
        "QNN CLIP tokenizer returned ${tokenIds.size} IDs; expected $expectedTokenCount."
    }
    val bytes = java.nio.ByteBuffer
        .allocate(tokenIds.size * Int.SIZE_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    tokenIds.forEach(bytes::putInt)
    outputFile.writeBytes(bytes.array())
    val promptEvidence = requireNotNull(unweightedProbeEvidence) {
        "QNN CLIP token conditioning requires tokenizer.json native prompt evidence."
    }
    JSONObject()
        .put("ok", true)
        .put("conditioningFormat", "qnn_clip_token_ids_i32")
        .put("tokenizerBackend", tokenizerBackend)
        .put("negativePromptSpecified", negativePrompt.isNotEmpty())
        .put("bosId", bosId)
        .put("eosId", eosId)
        .put("padId", padId)
        .put("tokenCount", tokenIds.size)
        .put("outputPath", outputFile.absolutePath)
        .put("promptWeightingApplied", promptEvidence.promptWeightingApplied)
        .put("positiveWeightedTokenCount", promptEvidence.positiveWeightedTokenCount)
        .put("negativeWeightedTokenCount", promptEvidence.negativeWeightedTokenCount)
        .put("promptWeightFingerprint", promptEvidence.promptWeightFingerprint)
        .put("nativePromptExecutionSha256", promptEvidence.nativePromptExecutionSha256)
        .put("nativePromptBindingStage", promptEvidence.nativePromptBindingStage)
        .toString()
}.getOrElse { error ->
    JSONObject()
        .put("ok", false)
        .apply {
            if (error is LocalImageProductContractException) {
                put("errorCode", error.code)
            }
        }
        .put("error", error.message ?: "Failed to tokenize QNN CLIP prompt.")
        .toString()
}

internal fun resolveQnnExecutedNegativePrompt(
    useCfg: Boolean,
    effectiveNegativePrompt: String
): String {
    if (!useCfg && effectiveNegativePrompt.isNotEmpty()) {
        throw LocalImageProductContractException(
            code = "execution_contract_unsupported",
            message = "A negativePrompt cannot affect pixels when useCfg=false."
        )
    }
    return effectiveNegativePrompt
}

internal data class QnnImageGenerationContract(
    val width: Int,
    val height: Int,
    val steps: Int,
    val threads: Int,
    val seed: Int,
    val cfgScale: Double,
    val sampleMethod: String,
    val backendMode: String,
    val tokenEmbeddingMode: String,
    val memoryMode: Int,
    val useCfg: Boolean
) {
    val auditJson: JSONObject
        get() = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
            .put("tokenEmbeddingMode", tokenEmbeddingMode)
            .put("memoryMode", memoryMode)
            .put("useCfg", useCfg)
}

internal fun resolveQnnImageGenerationContract(
    resolution: ImageExecutionProfileResolution,
    defaultThreads: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val resolved = resolution.layers.resolved
    val width = resolved.width
    val height = resolved.height
    require(width > 0 && height > 0 && width % 8 == 0 && height % 8 == 0) {
        "Resolved QNN image dimensions must be positive multiples of 8."
    }
    val steps = resolved.steps
    require(steps in resolution.profile.scheduler.minSteps..resolution.profile.scheduler.maxSteps) {
        "Resolved QNN steps are outside the execution profile bounds."
    }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = resolved.cfgScale
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = resolved.seed.toInt().also {
            require(resolved.seed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "QNN seed must fit the native 32-bit RNG contract."
            }
        },
        cfgScale = cfgScale,
        sampleMethod = imageSchedulerProductName(resolved.scheduler),
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = resolved.useCfg
    )
}

internal fun resolveQnnImageGenerationContract(
    family: LocalImageModelFamily,
    defaultWidth: Int,
    defaultHeight: Int,
    defaultThreads: Int,
    fallbackSeed: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val width = options.width ?: defaultWidth
    val height = options.height ?: defaultHeight
    require(width == defaultWidth && height == defaultHeight) {
        "QNN context 固定为 ${defaultWidth}x${defaultHeight}，不能执行 ${width}x${height}。"
    }
    val defaultSteps = if (family == LocalImageModelFamily.SDXL) 30 else defaultStepsFor(family).coerceIn(1, 100)
    val steps = options.steps ?: defaultSteps
    require(steps in 1..100) { "QNN steps 必须在 1..100。" }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = options.cfgScale ?: defaultCfgFor(family)
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val sampleMethod = options.sampleMethod?.trim()?.lowercase().orEmpty().ifBlank { "pndm" }
    imageSchedulerAlgorithmFromProductName(sampleMethod)
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = options.seed ?: fallbackSeed,
        cfgScale = cfgScale,
        sampleMethod = sampleMethod,
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = options.useCfg ?: true
    )
}
