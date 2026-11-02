package com.muyuchat.mca.video.model

import com.muyuchat.core.video.contract.VideoErrorCode

/**
 * Model manifest types of the video domain (proposal §5.1). A manifest is the ONLY accepted way
 * a model enters load: the UI never guesses filenames, and "same file name" never substitutes
 * for role + revision + checksum agreement.
 *
 * The pinned profiles live in [Wan22Ti2v5bProfiles]; this file defines the shape and the
 * verification outcome so the asset verifier, the workflow compiler and the owner all share one
 * vocabulary.
 */
enum class VideoModelComponentRole {
    /** The single DiT of the baseline (non-distilled) TI2V-5B. */
    DIT,

    /** UMT5-XXL text encoder (GGUF quantizations of the official encoder export). */
    TEXT_ENCODER_UMT5_XXL,

    /** The Wan2.2 5B-specific VAE. Never the Wan2.1 VAE, never TAEHV (those are separate profiles). */
    VAE_WAN22_5B,

    /** 4-step Turbo distillation LoRA applied by the external engine (product recipe). */
    LORA_TURBO,

    /** Optional TAEHV decoder head used only when the user turns full-VAE decode off. */
    TAE_HEAD,
}

data class VideoModelComponentFile(
    val role: VideoModelComponentRole,
    /** Path relative to the model bundle root. */
    val relativePath: String,
    val format: String,
    val quantization: String,
    val sizeBytes: Long,
    /** sha256 pinned from the source (HF LFS oid = file bytes); re-verified against the local file. */
    val sha256: String?,
    val sourceUrl: String,
    val license: String,
    /** Human-readable provenance note: repo, fetch date, digest method. */
    val provenance: String,
    /**
     * False for components that only back an optional path (the TAE head behind the decode
     * switch). A missing optional component never blocks `READY`; it is still listed and
     * downloadable.
     */
    val required: Boolean = true,
)

data class VideoModelManifest(
    val modelId: String,
    val family: String,
    val variant: String,
    /** Upstream/export revision the component chain was pinned from. */
    val revision: String,
    val components: List<VideoModelComponentFile>,
    /** Baseline sampling recipe documented by upstream (NOT a Turbo/distilled recipe). */
    val referenceSamplerRecipe: VideoSamplerRecipe,
    /**
     * Measured presets are EMPTY until P0-C produces real device evidence. Nothing may present an
     * unmeasured resolution/length/speed as a preset.
     */
    val measuredPresets: List<MeasuredPreset> = emptyList(),
    val supportedModes: Set<String> = setOf("text_to_video", "image_to_video"),
    /** Structural native rules this variant was verified against in the pinned sd.cpp revision. */
    val dimensionMultiple: Long = 32L,
    val frameAlignmentRule: String = "4k+1 (align_video_frames; frame counts 1,5,9,…)",
) {
    init {
        require(modelId.isNotEmpty()) { "modelId must not be empty" }
        require(components.isNotEmpty()) { "manifest must pin at least one component" }
        val roles = components.map { it.role }.toSet()
        require(
            roles.containsAll(
                setOf(
                    VideoModelComponentRole.DIT,
                    VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
                    VideoModelComponentRole.VAE_WAN22_5B,
                ),
            ),
        ) { "baseline TI2V-5B manifest requires dit + umt5 encoder + wan2.2 vae" }
    }

    fun componentFor(role: VideoModelComponentRole): VideoModelComponentFile =
        requireNotNull(components.firstOrNull { it.role == role }) { "missing role $role" }
}

/** Upstream-documented baseline recipe (sd.cpp wan.md, Wan2.2 TI2V 5B examples). */
data class VideoSamplerRecipe(
    val sampler: String,
    val guidanceUnscaled: Long,
    val guidanceScale: Int,
    val flowShiftUnscaled: Long,
    val flowShiftScale: Int,
    val referenceWidth: Long,
    val referenceHeight: Long,
    val referenceFrames: Long,
    val source: String,
)

/** A device-measured preset; only P0-C/P0-D evidence may create these. */
data class MeasuredPreset(
    val name: String,
    val width: Long,
    val height: Long,
    val frames: Long,
    val fps: Long,
    val steps: Long,
    val deviceModel: String,
    val measuredDurationMs: Long,
    val peakMemoryBytes: Long,
)

/** Per-component verification outcome. */
sealed class ComponentVerification {
    abstract val file: VideoModelComponentFile

    /** Present with pinned size AND pinned sha256 confirmed. */
    data class Ok(override val file: VideoModelComponentFile) : ComponentVerification()

    /**
     * Present with pinned size, but the manifest pin has no sha256 yet. Honest middle state:
     * usable for size accounting, never reported as checksum-verified.
     */
    data class SizeOnlyOk(override val file: VideoModelComponentFile) : ComponentVerification()

    data class Missing(override val file: VideoModelComponentFile) : ComponentVerification()

    data class SizeMismatch(
        override val file: VideoModelComponentFile,
        val actualBytes: Long,
        val code: VideoErrorCode,
    ) : ComponentVerification()

    data class ChecksumMismatch(
        override val file: VideoModelComponentFile,
        val actualSha256: String,
    ) : ComponentVerification()
}

sealed class VideoModelAssetVerification {
    /** Every required component present with pinned size and sha256. */
    data class Ready(
        val manifest: VideoModelManifest,
        val components: List<ComponentVerification> = emptyList(),
    ) : VideoModelAssetVerification()

    /** Specific, repairable problems; never a silent "unavailable". */
    data class Problems(
        val manifest: VideoModelManifest,
        val components: List<ComponentVerification>,
        val insufficientSpaceBytes: Long?,
    ) : VideoModelAssetVerification() {
        val isIncomplete: Boolean get() = components.any { it !is ComponentVerification.Ok && it !is ComponentVerification.SizeOnlyOk }
    }
}
