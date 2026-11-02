package com.muyuchat.mca.video.model

/**
 * The pinned baseline Wan2.2 TI2V-5B asset chain (proposal §5.1 / V1-01, P0-A).
 *
 * Facts below were fetched on 2026-10-25 from the public Hugging Face API/CDN (sizes from
 * HTTP Content-Length; sha256 from the HF Xet CAS address of each file — the CAS is
 * sha256-content-addressed and the signed URL states Hash-Algorithm=SHA256). The verifier
 * re-hashes every local file at install/load time; these pins are the source declaration, not
 * the final word.
 *
 * Baseline-only policy: the DIET model is the BASE (non-distilled) TI2V-5B. The reference
 * project's Turbo distillation, its 4-step schedule, TAEHV decode and Hexagon paths are
 * explicitly NOT part of this profile and must never borrow its parameters or conclusions.
 *
 * License chain, as declared at the sources on 2026-10-25:
 * - Wan-AI/Wan2.2-TI2V-5B (official base): Apache-2.0.
 * - QuantStack/Wan2.2-TI2V-5B-GGUF (DiT GGUF + VAE safetensors): repo tag Apache-2.0, derived
 *   from the official base (declared base_model:Wan-AI/Wan2.2-TI2V-5B).
 * - city96/umt5-xxl-encoder-gguf (text encoder): repo tag Apache-2.0.
 * - Comfy-Org/Wan_2.2_ComfyUI_Repackaged fp16 alternates: license re-check pending (API
 *   rate-limited during pinning) — usable only after that box is verified; the GGUF chain above
 *   is the primary and does not depend on it.
 * - Weights are user-downloaded at runtime, never bundled into the APK.
 */
object Wan22Ti2v5bProfiles {

    const val MODEL_ID = "wan2.2-ti2v-5b"
    const val FAMILY = "WAN2_2"
    const val VARIANT = "TI2V_5B"

    /** Native baseline the structural rules were verified against (submodule revision + local patches). */
    const val NATIVE_BASELINE_REVISION =
        "stable-diffusion.cpp 16b3846acfdf2c9499a3ac0c3d5d2914ebe859a0 + local workspace patches (V0-01)"

    /** UMT5 tokenizer vocabulary is compiled into the native library (t5_unigram_tokenizer.cpp). */
    const val TOKENIZER_PROVISION =
        "embedded in native build; bound to sd-native build revision, no separate tokenizer download"

    private const val QUANTSTACK = "https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF"
    private const val CITY96 = "https://huggingface.co/city96/umt5-xxl-encoder-gguf"
    private const val COMFYORG = "https://huggingface.co/Comfy-Org/Wan_2.2_ComfyUI_Repackaged"

    /**
     * Primary Android candidate: Q4_0 DiT + Q8_0 encoder + pinned VAE. ~12 GiB total download;
     * whether a phone can actually run it is a P0-C measurement, not a claim of this manifest.
     */
    val Q4_0_Q8_0: VideoModelManifest = build(
        revision = "hf-2026-10-25/quantstack-q4_0+city96-q8_0",
        ditFile = component(
            role = VideoModelComponentRole.DIT,
            relativePath = "dit/Wan2.2-TI2V-5B-Q4_0.gguf",
            format = "gguf",
            quantization = "Q4_0",
            sizeBytes = 3_029_086_560L,
            sha256 = "f68930ef925cee2bc2ad68c07207d542d1fb37a5ff984665f55735732454c635",
            sourceUrl = "$QUANTSTACK/resolve/main/Wan2.2-TI2V-5B-Q4_0.gguf",
            provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF main; size via HTTP Content-Length; sha256 via HF Xet CAS address (2026-10-25)",
        ),
        encoderFile = component(
            role = VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
            relativePath = "text_encoder/umt5-xxl-encoder-Q8_0.gguf",
            format = "gguf",
            quantization = "Q8_0",
            sizeBytes = 6_043_068_256L,
            sha256 = "6048e11fd9863de836bc014d9cdde1ac4a2b8ba9a3fa89beb08528400d27d828",
            sourceUrl = "$CITY96/resolve/main/umt5-xxl-encoder-Q8_0.gguf",
            provenance = "city96/umt5-xxl-encoder-gguf main; size via HTTP Content-Length; sha256 via HF Xet CAS address (2026-10-25)",
        ),
    )

    /** Alternate: Q4_K_M DiT + Q4_K_M encoder (smaller encoder, different quality tradeoff). */
    val Q4_K_M_Q4_K_M: VideoModelManifest = build(
        revision = "hf-2026-10-25/quantstack-q4_k_m+city96-q4_k_m",
        ditFile = component(
            role = VideoModelComponentRole.DIT,
            relativePath = "dit/Wan2.2-TI2V-5B-Q4_K_M.gguf",
            format = "gguf",
            quantization = "Q4_K_M",
            sizeBytes = 3_433_116_000L,
            sha256 = "9fc99dd5c3e564d54a18147ae40bc8c74afe07f393c27955c427d84d18f15b05",
            sourceUrl = "$QUANTSTACK/resolve/main/Wan2.2-TI2V-5B-Q4_K_M.gguf",
            provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF main; size via HTTP Content-Length; sha256 via HF Xet CAS address (2026-10-25)",
        ),
        encoderFile = component(
            role = VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
            relativePath = "text_encoder/umt5-xxl-encoder-Q4_K_M.gguf",
            format = "gguf",
            quantization = "Q4_K_M",
            sizeBytes = 3_655_145_312L,
            // HF rate-limited the raw probe for this file during pinning; re-pin before use.
            sha256 = null,
            sourceUrl = "$CITY96/resolve/main/umt5-xxl-encoder-Q4_K_M.gguf",
            provenance = "city96/umt5-xxl-encoder-gguf main; size via HTTP Content-Length; sha256 pending re-fetch (2026-10-25 rate limit)",
        ),
    )

    /** The Wan2.2 5B-specific VAE, shared by every profile (single pinned copy). */
    val VAE_FILE: VideoModelComponentFile = component(
        role = VideoModelComponentRole.VAE_WAN22_5B,
        relativePath = "vae/Wan2.2_VAE.safetensors",
        format = "safetensors",
        quantization = "fp16",
        sizeBytes = 1_409_400_960L,
        sha256 = "53b5f2941a82a2494fd4315151050f9022eefcea4bcc51e0e15b99079f92947f",
        sourceUrl = "$QUANTSTACK/resolve/main/VAE/Wan2.2_VAE.safetensors",
        provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF main (identical bytes to Comfy-Org split_files/vae/wan2.2_vae.safetensors, same size); sha256 via HF Xet CAS address (2026-10-25)",
    )

    /**
     * Baseline sampler recipe as documented by the pinned sd.cpp revision's wan.md for
     * "Wan2.2 TI2V 5B" (T2V and I2V share it; cfg 6.0, euler, 33 frames, flow shift 3.0).
     * Recorded as REFERENCE input defaults only — measured presets stay empty until P0-C.
     */
    val REFERENCE_RECIPE = VideoSamplerRecipe(
        sampler = "euler",
        guidanceUnscaled = 60L,
        guidanceScale = 1,
        flowShiftUnscaled = 30L,
        flowShiftScale = 1,
        referenceWidth = 480L,
        referenceHeight = 832L,
        referenceFrames = 33L,
        source = "third_party/stable-diffusion.cpp docs/wan.md @16b3846, section 'Wan2.2 TI2V 5B'",
    )

    /** All profiles exportable to the model hub; primary first. */
    val ALL: List<VideoModelManifest> = listOf(Q4_0_Q8_0, Q4_K_M_Q4_K_M)

    fun byModelId(modelId: String): VideoModelManifest? =
        ALL.firstOrNull { it.modelId == modelId }

    private fun build(
        revision: String,
        ditFile: VideoModelComponentFile,
        encoderFile: VideoModelComponentFile,
    ): VideoModelManifest = VideoModelManifest(
        modelId = MODEL_ID,
        family = FAMILY,
        variant = VARIANT,
        revision = revision,
        components = listOf(ditFile, encoderFile, VAE_FILE),
        referenceSamplerRecipe = REFERENCE_RECIPE,
    )

    private fun component(
        role: VideoModelComponentRole,
        relativePath: String,
        format: String,
        quantization: String,
        sizeBytes: Long,
        sha256: String?,
        sourceUrl: String,
        provenance: String,
    ): VideoModelComponentFile = VideoModelComponentFile(
        role = role,
        relativePath = relativePath,
        format = format,
        quantization = quantization,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        sourceUrl = sourceUrl,
        license = "Apache-2.0 (declared at source repo, 2026-10-25); weights user-downloaded, never bundled",
        provenance = provenance,
    )
}
