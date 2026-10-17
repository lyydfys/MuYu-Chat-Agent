package com.muyuchat.mca

import java.security.MessageDigest

internal data class ImageGenerationDefaults(
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfgScale: Double,
    val seed: Long,
    val useCfg: Boolean,
    val defaultPrompt: String? = null,
    val defaultNegativePrompt: String? = null
)

internal data class ImageGenerationCapabilities(
    val supportedSchedulers: Set<ImageSchedulerAlgorithm>,
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int,
    val widthMultiple: Int = 8,
    val heightMultiple: Int = 8,
    val supportsNegativePrompt: Boolean = true,
    val supportsPromptWeighting: Boolean = false,
    val supportsTextualInversion: Boolean = false,
    val requiresControlImage: Boolean = false,
    val requiresInputImage: Boolean = false,
    val supportsMask: Boolean = false,
    val supportsClipSkip: Boolean = false,
    val supportsVaeTiling: Boolean = false,
    val supportsUltraFix: Boolean = false,
    val ultraFixMinWidth: Int = if (supportsUltraFix) minWidth else 0,
    val ultraFixMaxWidth: Int = if (supportsUltraFix) maxWidth else 0,
    val ultraFixMinHeight: Int = if (supportsUltraFix) minHeight else 0,
    val ultraFixMaxHeight: Int = if (supportsUltraFix) maxHeight else 0,
    val ultraFixWidthMultiple: Int = if (supportsUltraFix) widthMultiple else 0,
    val ultraFixHeightMultiple: Int = if (supportsUltraFix) heightMultiple else 0,
    /** Zero means the runtime accepts any topology-aligned tile in the advertised range. */
    val ultraFixRequiredTileSize: Int = 0,
    val supportsLivePreview: Boolean = false,
    val supportsLora: Boolean = false,
    val maxBatchCount: Int = 1
)

internal data class ImageExecutionProfile(
    val schemaVersion: Int = IMAGE_EXECUTION_PROFILE_SCHEMA_VERSION,
    val profileId: String,
    val profileRevision: Int,
    val modelFingerprint: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily,
    val variant: ImageModelVariant,
    val task: ImageTask,
    val provenance: ImageProfileProvenance,
    val tokenizer: ImageTokenizerContract,
    val conditioning: ImageConditioningContract,
    val scheduler: ImageSchedulerContract,
    val latent: ImageLatentContract,
    val vae: ImageVaeContract,
    val graph: ImageGraphContract,
    val defaults: ImageGenerationDefaults,
    val capabilities: ImageGenerationCapabilities,
    /**
     * Optional positive admission proof for direct non-English text. Missing fields in legacy
     * manifests remain readable and conservatively resolve to English-dominant.
     */
    val textEncoderLanguage: ImageTextEncoderLanguageContract? = null,
    /** Request-scoped exact file snapshot. It is intentionally omitted from persisted JSON. */
    val textualInversionExecutionAssets: TextualInversionExecutionAssetBinding? = null
) {
    /** Stable binding over the model fingerprint and every executable contract. */
    val bindingFingerprint: String
        get() = sha256Hex(
            buildList {
                addAll(listOf(
                schemaVersion,
                profileId,
                profileRevision,
                modelFingerprint.lowercase(),
                runtime,
                family,
                variant,
                task,
                provenance.primarySource,
                provenance.sources.joinToString("\u001e") { source -> source.name },
                tokenizer,
                conditioning,
                scheduler,
                latent,
                vae,
                graph,
                defaults,
                capabilities
                ))
                // Keep the historical digest byte-for-byte stable when an old profile lacks the
                // optional contract. A positive declaration must invalidate stale prompt evidence.
                textEncoderLanguage?.let { add("textEncoderLanguage:${it.bindingToken()}") }
            }.joinToString("\u001f")
        )

    /**
     * Stable binding for prompt preparation and translation reuse. Generation defaults and
     * sampling/image topology deliberately stay out of this digest: changing a seed, scheduler,
     * size, or default negative prompt cannot change how captured text is tokenized and encoded.
     */
    val promptLanguageBindingFingerprint: String
        get() {
            val profileFingerprint = computedPromptLanguageBindingFingerprint()
            val executionAssets = textualInversionExecutionAssets ?: return profileFingerprint
            // The execution-asset snapshot is captured from this exact profile before the tokenizer
            // asset list is narrowed. Requiring equality here prevents that snapshot branch from
            // bypassing a subsequently declared text-encoder language contract.
            if (textEncoderLanguage != null) {
                require(executionAssets.profilePromptFingerprint == profileFingerprint) {
                    "Textual-inversion execution assets are bound to a different text-encoder language contract."
                }
            }
            return executionAssets.profilePromptFingerprint
        }

    private fun computedPromptLanguageBindingFingerprint(): String = sha256Hex(
            buildList {
                addAll(listOf(
                "prompt-language-binding-v2",
                "prompt-language-contract-version=$LOCAL_IMAGE_PROMPT_LANGUAGE_CONTRACT_VERSION",
                modelFingerprint.lowercase(),
                runtime,
                family,
                variant,
                task,
                provenance.primarySource,
                provenance.sources.joinToString("\u001e") { source -> source.name },
                tokenizer,
                conditioning,
                graph.textEncoder,
                graph.workerStrategy
                ))
                textEncoderLanguage?.let { add("textEncoderLanguage:${it.bindingToken()}") }
            }.joinToString("\u001f")
        )
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
