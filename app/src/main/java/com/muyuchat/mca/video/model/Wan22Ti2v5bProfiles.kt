package com.muyuchat.mca.video.model

/**
 * The pinned baseline Wan2.2 TI2V-5B asset chain (proposal §5.1 / V1-01, P0-A).
 *
 * Facts below were fetched from the public Hugging Face API/CDN (sizes from HTTP
 * Content-Length; sha256 from the repository file metadata's LFS oid, i.e. the hash of the
 * actual file bytes). The earlier `xetHash` values are Xet CAS identifiers, not file sha256;
 * they are recorded in MODEL_ASSET_SOURCES.md as provenance only. The verifier re-hashes every
 * local file at install/load time; these pins are the source declaration, not the final word.
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

    /**
     * Every video asset is mirrored in this project's own ModelScope repository, laid out with the
     * same relative paths the app installs them under. The device cannot reach the Hugging Face
     * front-end or GitHub raw; a single account-owned, revision-pinned source keeps the download
     * chain domestic and auditable. The Hugging Face repositories remain the provenance of record
     * in each component's `provenance` field.
     */
    private const val VIDEO_ASSETS = "https://modelscope.cn/models/lyydfys/mca-wan22-video-assets"

    /**
     * Immutable repository commits, never `main`. `resolve/main` is a moving target: a later
     * upstream push would change the bytes while this manifest kept the old sha256 pin, so a
     * healthy download would fail as `MODEL_CHECKSUM_MISMATCH`. These commits are exactly the
     * revisions the LFS oids and sizes below were read from (HF API, 2026-10-28); `main` pointed
     * at them when pinned.
     */
    /** ModelScope per-file revisions (the commit each pinned file was last written in). */
    const val DIT_Q4_0_REVISION = "2372988c0c71777f042f0193873eccc2749a7fe2"
    const val DIT_Q4_K_M_REVISION = "60e705897aeb1443e4ae56ab3e3c4c8b4acebf91"
    const val ENCODER_Q4_K_M_REVISION = "42324c9dd547e53c7ae4e7df1652a123ddadfb33"
    const val ENCODER_Q8_0_REVISION = "4649b9c8dc29fa5da3ec47b2d88e398bd7864f72"
    const val VAE_REVISION = "3ca684eee932b3faa236399f3f600be81a8aabfa"
    const val TURBO_LORA_REVISION = "5ec39bef6ecfb5c82d8f7292dd2daaa5d021b623"
    const val TAE_REVISION = "7fedbb1baf0aa0384a3415d7f1cc79d2bcf7a10e"

    /**
     * Baseline sampler recipe as documented by the pinned sd.cpp revision's wan.md for
     * "Wan2.2 TI2V 5B" (T2V and I2V share it; cfg 6.0, euler, 33 frames, flow shift 3.0).
     * Recorded as REFERENCE input defaults only — measured presets stay empty until P0-C.
     *
     * Declared BEFORE the profiles: Kotlin initializes object properties in declaration order,
     * and the profile constructors read this recipe (and [VAE_FILE]) immediately.
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

    /** The Wan2.2 5B-specific VAE, shared by every profile (single pinned copy). */
    val VAE_FILE: VideoModelComponentFile = component(
        role = VideoModelComponentRole.VAE_WAN22_5B,
        relativePath = "vae/Wan2.2_VAE.safetensors",
        format = "safetensors",
        quantization = "fp16",
        sizeBytes = 1_409_400_960L,
        sha256 = "e40321bd36b9709991dae2530eb4ac303dd168276980d3e9bc4b6e2b75fed156",
        sourceUrl = "$VIDEO_ASSETS/resolve/${VAE_REVISION}/vae/Wan2.2_VAE.safetensors",
        provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF on Hugging Face @57437632ddd08bdcbd1508c866aa22e126ed51d2 (identical bytes to Comfy-Org split_files/vae/wan2.2_vae.safetensors, same size); sha256 = HF LFS oid, xetHash = 53b5f2941a82a2494fd4315151050f9022eefcea4bcc51e0e15b99079f92947f",
    )

    /**
     * The 4-step Turbo distillation LoRA the product recipe submits with the external engine.
     * Byte-identical ModelScope mirror of the pinned hum-ma file (verified against the local copy
     * and the HF LFS oid on 2026-11-02).
     */
    val TURBO_LORA_FILE: VideoModelComponentFile = component(
        role = VideoModelComponentRole.LORA_TURBO,
        relativePath = "loras/turbo.safetensors",
        format = "safetensors",
        quantization = "fp16 rank 64",
        sizeBytes = 332_348_584L,
        sha256 = "0ace5244e3d1256f884662c261b017249796cf5b95f05d5ed93cc02a478967b8",
        sourceUrl = "$VIDEO_ASSETS/resolve/${TURBO_LORA_REVISION}/loras/turbo.safetensors",
        provenance = "hum-ma/Wan2.2-TI2V-5B-Turbo-GGUF on Hugging Face @9873ba175b4b204a81f4a43c9644e6c385ee4459 (Apache-2.0); mirrored in the project ModelScope repo; base_model quanhaol/Wan2.2-TI2V-5B-Turbo",
        license = "Apache-2.0 (hum-ma repository tag; ModelScope mirror Kijai/WanVideo_comfy)",
    )

    /**
     * TAEHV head for the user's TAE fallback. Optional: full-VAE decode is the default, so a
     * missing head never blocks readiness. Source is the MIT upstream repo and the bytes match the
     * file already verified on device (the Kijai re-save has different tensor data and is not used).
     */
    val TAE_FILE: VideoModelComponentFile = component(
        role = VideoModelComponentRole.TAE_HEAD,
        relativePath = "vae/taew2_2.safetensors",
        format = "safetensors",
        quantization = "fp16 TAEHV",
        sizeBytes = 22_848_048L,
        sha256 = "b84609b2a133d48434bd9636bfcb44bf05168dc436e2d3cecf26256faa1f5325",
        sourceUrl = "$VIDEO_ASSETS/resolve/${TAE_REVISION}/vae/taew2_2.safetensors",
        provenance = "madebyollin/taehv @fa579a9a726b0a55951998d73e309bfdf0abd342 safetensors/taew2_2.safetensors (MIT, named by sd.cpp docs/taesd.md); the project ModelScope repo (lyydfys/mca-wan22-video-assets) mirrors those exact bytes at revision 7fedbb1baf0aa0384a3415d7f1cc79d2bcf7a10e (sha256 re-computed from a fresh download on 2026-11-02)",
        license = "MIT (madebyollin/taehv)",
        required = false,
    )

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
            sha256 = "fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463",
            sourceUrl = "$VIDEO_ASSETS/resolve/${DIT_Q4_0_REVISION}/dit/Wan2.2-TI2V-5B-Q4_0.gguf",
            provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF on Hugging Face @57437632; sha256 = HF LFS oid, xetHash = f68930ef925cee2bc2ad68c07207d542d1fb37a5ff984665f55735732454c635; mirrored in the project ModelScope repo",
        ),
        encoderFile = component(
            role = VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
            relativePath = "text_encoder/umt5-xxl-encoder-Q8_0.gguf",
            format = "gguf",
            quantization = "Q8_0",
            sizeBytes = 6_043_068_256L,
            sha256 = "2521d4de0bf9e1cc6549866463ceae85e4ec3239bc6063f7488810be39033bbc",
            sourceUrl = "$VIDEO_ASSETS/resolve/${ENCODER_Q8_0_REVISION}/text_encoder/umt5-xxl-encoder-Q8_0.gguf",
            provenance = "city96/umt5-xxl-encoder-gguf on Hugging Face @b535255b; sha256 = HF LFS oid, xetHash = 6048e11fd9863de836bc014d9cdde1ac4a2b8ba9a3fa89beb08528400d27d828; mirrored in the project ModelScope repo",
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
            sha256 = "95b19697b7f98e65b0a543640e9ca7b4dfec32e2a6e3731e8e10708be52655e2",
            sourceUrl = "$VIDEO_ASSETS/resolve/${DIT_Q4_K_M_REVISION}/dit/Wan2.2-TI2V-5B-Q4_K_M.gguf",
            provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF on Hugging Face @57437632; sha256 = HF LFS oid, xetHash = 9fc99dd5c3e564d54a18147ae40bc8c74afe07f393c27955c427d84d18f15b05; mirrored in the project ModelScope repo",
        ),
        encoderFile = component(
            role = VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
            relativePath = "text_encoder/umt5-xxl-encoder-Q4_K_M.gguf",
            format = "gguf",
            quantization = "Q4_K_M",
            sizeBytes = 3_655_145_312L,
            sha256 = "17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40",
            sourceUrl = "$VIDEO_ASSETS/resolve/${ENCODER_Q4_K_M_REVISION}/text_encoder/umt5-xxl-encoder-Q4_K_M.gguf",
            provenance = "city96/umt5-xxl-encoder-gguf on Hugging Face @b535255b; sha256 = HF LFS oid, xetHash = 34aac234db818530b0f7511073d178a53863337154ce6b5f03bae15134be8448; mirrored in the project ModelScope repo",
        ),
    )

    /**
     * Same DiT and VAE as [Q4_0_Q8_0], with the Q4_K_M text encoder. The Q8_0 encoder keeps ~6.0 GiB
     * resident, which is what pushed a 480x832x5 run over the app-process memory limit on a 12 GB
     * device (the worker was killed during sampling). The Q4_K_M encoder is 2.4 GiB smaller and is
     * the only quantisation change that can be made without touching the DiT or VAE numerics.
     */
    val Q4_0_Q4_K_M: VideoModelManifest = build(
        revision = "hf-2026-10-25/quantstack-q4_0+city96-q4_k_m",
        ditFile = component(
            role = VideoModelComponentRole.DIT,
            relativePath = "dit/Wan2.2-TI2V-5B-Q4_0.gguf",
            format = "gguf",
            quantization = "Q4_0",
            sizeBytes = 3_029_086_560L,
            sha256 = "fcf40dd62cb5e15556eef5f975700ab0b071aacd1939607689276e88f81ba463",
            sourceUrl = "$VIDEO_ASSETS/resolve/${DIT_Q4_0_REVISION}/dit/Wan2.2-TI2V-5B-Q4_0.gguf",
            provenance = "QuantStack/Wan2.2-TI2V-5B-GGUF on Hugging Face @57437632; same file as Q4_0_Q8_0; mirrored in the project ModelScope repo",
        ),
        encoderFile = component(
            role = VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL,
            relativePath = "text_encoder/umt5-xxl-encoder-Q4_K_M.gguf",
            format = "gguf",
            quantization = "Q4_K_M",
            sizeBytes = 3_655_145_312L,
            sha256 = "17cf97a5bbbc60a646d6105b832b6f657ce904a8a1ad970e4b59df0c67584a40",
            sourceUrl = "$VIDEO_ASSETS/resolve/${ENCODER_Q4_K_M_REVISION}/text_encoder/umt5-xxl-encoder-Q4_K_M.gguf",
            provenance = "city96/umt5-xxl-encoder-gguf on Hugging Face @b535255b; same file as Q4_K_M_Q4_K_M; mirrored in the project ModelScope repo",
        ),
    )

    /** All profiles exportable to the model hub; primary first. */
    val ALL: List<VideoModelManifest> = listOf(Q4_0_Q8_0, Q4_K_M_Q4_K_M, Q4_0_Q4_K_M)

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
        components = listOf(ditFile, encoderFile, VAE_FILE, TURBO_LORA_FILE, TAE_FILE),
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
        license: String = "Apache-2.0 (declared at source repo, 2026-10-25); weights user-downloaded, never bundled",
        required: Boolean = true,
    ): VideoModelComponentFile = VideoModelComponentFile(
        role = role,
        relativePath = relativePath,
        format = format,
        quantization = quantization,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        sourceUrl = sourceUrl,
        license = license,
        provenance = provenance,
        required = required,
    )
}
