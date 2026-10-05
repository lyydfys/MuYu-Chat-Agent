package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineBundleSpec

/**
 * Image execution-profile domain types (P7/S30), moved verbatim from
 * ImageExecutionProfileResolver.kt: inputs, evidence, sidecar/config carriers and resolution
 * results. The resolver object keeps its logic; two former file-private carriers were
 * widened to internal with P7/S30 comments.
 */

internal data class ImageProfileSidecar(
    val profileId: String? = null,
    val profileRevision: Int? = null,
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val task: ImageTask? = null,
    val tokenizer: ImageTokenizerContract? = null,
    val conditioning: ImageConditioningContract? = null,
    val scheduler: ImageSchedulerContract? = null,
    val latent: ImageLatentContract? = null,
    val vae: ImageVaeContract? = null,
    val graph: ImageGraphContract? = null,
    val defaults: ImageGenerationDefaults? = null,
    val capabilities: ImageGenerationCapabilities? = null,
    val behavior: ImagePackageBehaviorConfig? = null
)

// Text-encoder language evidence is intentionally absent from sidecar overlays. Package sidecars
// are advisory behavior/configuration data and cannot grant direct Chinese prompt admission.

/**
 * Partial user-facing behavior declared by a model package. Unlike a complete
 * execution profile, every field is optional and only overrides the matching
 * built-in or generic field. It never grants device admission or bypasses a
 * native load/execute failure.
 */
internal data class ImagePackageBehaviorConfig(
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val defaultPrompt: String? = null,
    val defaultNegativePrompt: String? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val scheduler: ImageSchedulerAlgorithm? = null,
    val width: Int? = null,
    val height: Int? = null,
    val useCfg: Boolean? = null
) {
    fun overlay(higherPriority: ImagePackageBehaviorConfig): ImagePackageBehaviorConfig =
        ImagePackageBehaviorConfig(
            family = higherPriority.family ?: family,
            variant = higherPriority.variant ?: variant,
            defaultPrompt = higherPriority.defaultPrompt ?: defaultPrompt,
            defaultNegativePrompt = higherPriority.defaultNegativePrompt ?: defaultNegativePrompt,
            steps = higherPriority.steps ?: steps,
            cfgScale = higherPriority.cfgScale ?: cfgScale,
            scheduler = higherPriority.scheduler ?: scheduler,
            width = higherPriority.width ?: width,
            height = higherPriority.height ?: height,
            useCfg = higherPriority.useCfg ?: useCfg
        )
}

internal data class ImageCapabilityDiscovery(
    val family: LocalImageModelFamily? = null,
    val variant: ImageModelVariant? = null,
    val task: ImageTask? = null,
    val tokenizer: ImageTokenizerContract? = null,
    val conditioning: ImageConditioningContract? = null,
    val scheduler: ImageSchedulerContract? = null,
    val latent: ImageLatentContract? = null,
    val vae: ImageVaeContract? = null,
    val graph: ImageGraphContract? = null,
    val defaults: ImageGenerationDefaults? = null,
    val capabilities: ImageGenerationCapabilities? = null
)

// Runtime discovery describes graph mechanics only. It must never infer text-language semantics
// from a tokenizer path, model family, chipset, or device observation.

internal data class ImageGenerationOverrides(
    val expectedProfileId: String? = null,
    val expectedProfileRevision: Int? = null,
    val scheduler: ImageSchedulerAlgorithm? = null,
    val predictionType: ImagePredictionType? = null,
    val steps: Int? = null,
    /** Internal debug-only escape hatch for short semantic smoke requests. */
    val allowLowStepSmoke: Boolean = false,
    val cfgScale: Double? = null,
    val useCfg: Boolean? = null,
    val width: Int? = null,
    val height: Int? = null,
    val useUltraFixDimensionContract: Boolean = false,
    val seed: Long? = null,
    val negativePrompt: String? = null,
    val negativePromptSpecified: Boolean = false
)

/** Device hints may tune execution later, but are never part of profile admission. */
internal data class ImageDeviceExecutionHints(
    val localProfileKnown: Boolean = false,
    val preferredWorkerStrategy: ImageWorkerStrategy? = null,
    val preferredThreads: Int? = null
)

/**
 * Stable package identity carried independently from device information.
 * Every value is advisory unless it matches an exact catalog identity rule;
 * an unknown value always falls through to capability discovery or the
 * generic compatible profile.
 */
internal data class ImageRecommendationEvidence(
    val aliases: List<String> = emptyList(),
    val sourceRepositories: List<String> = emptyList(),
    val artifactPaths: List<String> = emptyList()
)

/**
 * Describes the byte scope used by [ImageExecutionProfileResolverInput.modelFingerprint].
 *
 * A single-file model is identified by the primary file SHA.  A model bundle is identified by
 * the complete observed component inventory, including the manifest contract.  Keeping the scope
 * explicit prevents a bundle digest from being accidentally interpreted as the catalog primary
 * file SHA during legacy profile migration.
 */
internal enum class ImageModelFingerprintScope {
    PRIMARY_FILE,
    COMPLETE_BUNDLE
}

/** Evidence captured from the installed bundle at the execution boundary. */
internal data class ImageBundleComponentEvidence(
    val relativePath: String,
    val role: String,
    val sha256: String,
    val sizeBytes: Long
)

internal data class ImageExecutionProfileResolverInput(
    val modelFingerprint: String,
    val modelFingerprintScope: ImageModelFingerprintScope = ImageModelFingerprintScope.PRIMARY_FILE,
    val bundleComponentEvidence: List<ImageBundleComponentEvidence> = emptyList(),
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily,
    val recommendationId: String? = null,
    val recommendationRevision: String? = null,
    val manifestProfile: ImageExecutionProfile? = null,
    val manifestBehavior: ImagePackageBehaviorConfig? = null,
    val sidecar: ImageProfileSidecar? = null,
    val capabilityDiscovery: ImageCapabilityDiscovery? = null,
    val recommendationEvidence: ImageRecommendationEvidence = ImageRecommendationEvidence(),
    val userOverrides: ImageGenerationOverrides = ImageGenerationOverrides(),
    val deviceHints: ImageDeviceExecutionHints = ImageDeviceExecutionHints()
)

internal data class ImageExecutionProfileResolution(
    val profile: ImageExecutionProfile,
    val layers: ImageExecutionLayers,
    val fieldSources: Map<String, ImageProfileSource>,
    val sourceChain: List<ImageProfileSource>,
    val validation: ImageProfileValidationReport,
    val warnings: List<String>,
    /** Always false: missing/unknown device information is advisory. */
    val deviceAdmissionRestricted: Boolean = false
)

internal class ImageProfileResolutionException(
    val validation: ImageProfileValidationReport
) : IllegalArgumentException(
    validation.issues.joinToString(" ") { issue -> "${issue.code}:${issue.field}:${issue.message}" }
)

internal data class BuiltInImageProfileTarget(
    val recommendationId: String,
    val profileId: String
)

// P7/S30: widened private->internal (consumed by ImageExecutionProfileResolver in the same package).
internal data class BuiltInImageProfileIdentityRule(
    val recommendationId: String,
    val aliases: Set<String> = emptySet(),
    val primaryRepositories: Set<String> = emptySet(),
    val artifactMarkers: Set<String> = emptySet(),
    val modelFingerprints: Set<String> = emptySet()
)

// P7/S30: widened private->internal (consumed by ImageExecutionProfileResolver in the same package).
internal data class CatalogImageProfileContract(
    val bundle: ImageEngineBundleSpec,
    val aliases: Set<String>,
    val primaryRepositories: Set<String>,
    val artifactMarkers: Set<String>,
    val modelFingerprint: String
)
