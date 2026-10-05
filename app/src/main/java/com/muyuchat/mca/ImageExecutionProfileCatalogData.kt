package com.muyuchat.mca


/**
 * P5/S45: the built-in identity-rule catalog data (the pinned legacy fingerprint, the
 * migratable set and the per-recommendation identity rules), moved out of
 * ImageExecutionProfileResolver. The QWEN recommendation id deduplicates to the canonical
 * top-level constant in LocalImageModelStore.kt; the rule factory, `builtInTargets` and the
 * runtime catalog map stay with the resolver (tests read `builtInTargets` directly).
 */

// P5/S44: read by the manifest-migration extraction (read-only constant).
internal const val QWEN_IMAGE_21_LEGACY_VAE_PROFILE_FINGERPRINT =
    "2525485bba44d4d8176d05522fa7e8b4b1080c9e7b7df18dfde7d58e667ff055"
// P5/S44: read by the manifest-migration extraction (read-only data set).
internal val MIGRATABLE_PINNED_RECOMMENDATIONS = setOf(
    "cyberrealistic_sd15_qnn228",
    "realisticvisionhyper_sd15_qnn228",
    "dreamshaper_sd15_qnn228",
    "meinamix_sd15_qnn228",
    "qualcomm_sd15_gen5_qnn",
    "qualcomm_sd21_gen5_qnn",
    "qualcomm_controlnet_canny_gen5_qnn",
    "sdxl_base_qnn228",
    "realismsdxl_dmd2_alt_qnn228",
    "animagine_xl_v4_qnn228",
    "cyberrealisticxl_qnn228",
    "sd_turbo_512_experimental",
    QWEN_IMAGE_21_RECOMMENDATION_ID
)

// P5/S44: read by the manifest-migration extraction (read-only rule table).
internal val identityRules: List<BuiltInImageProfileIdentityRule> = listOf(
    ImageExecutionProfileResolver.identityRule(
        "cyberrealistic_sd15_qnn228",
        aliases = setOf("cyberrealistic-sd15-qnn228-8gen2"),
        repositories = setOf("Mr-J-369/CyberRealistic_Final-SD1.5-qnn2.28"),
        artifacts = setOf(
            "cyberrealistic_final_qnn2.28_min.zip",
            "cyberrealistic_final_qnn2.28_8gen2.zip"
        ),
        fingerprints = setOf("9daf0e4d80d14ae93c774faf5366702c58b0cdb71618d5e5130b54226936bf3f")
    ),
    ImageExecutionProfileResolver.identityRule(
        "realisticvisionhyper_sd15_qnn228",
        repositories = setOf("Mr-J-369/RealisticVisionHyper-SD1.5-qnn2.28"),
        artifacts = setOf("RealisticVisionHyper-qnn2.28-min.zip"),
        fingerprints = setOf("7f552ad7f9070f1e482d93d3785ceedd6f3fc1d437db9c5da00d81d9edd34b86")
    ),
    ImageExecutionProfileResolver.identityRule(
        "dreamshaper_sd15_qnn228",
        repositories = setOf("Mr-J-369/DreamShaper-SD1.5-qnn2.28"),
        artifacts = setOf("DreamShaperV8-qnn2.28-min.zip"),
        fingerprints = setOf("e4fbd2a28db64b038372d1847d82b66f2f754ed0e95d412a283104b9382ae59c")
    ),
    ImageExecutionProfileResolver.identityRule(
        "meinamix_sd15_qnn228",
        repositories = setOf("Mr-J-369/MeinaMix-SD1.5-qnn2.28"),
        artifacts = setOf("MeinaMix-qnn2.28-8gen2.zip")
    ),
    ImageExecutionProfileResolver.identityRule(
        "sdxl_base_qnn228",
        repositories = setOf("xororz/sdxl-qnn"),
        artifacts = setOf("sdxl_base_qnn2.28_8gen3.zip"),
        fingerprints = setOf("426e36987fd3b84dd05255cb12bc5463c427c8b55598bd3b2486a72291d6be7f")
    ),
    ImageExecutionProfileResolver.identityRule(
        "realismsdxl_dmd2_alt_qnn228",
        repositories = setOf("Mr-J-369/RealismByStableYogiV8.0_DMD2_ALT-SDXL-qnn2.28"),
        artifacts = setOf("realismSDXLByStable_v80DMD2ALT_qnn2.28_8gen3.zip"),
        fingerprints = setOf("e95df91391f1f6f6f39416985ada906fec77d65496d3f52f54feb0c3da3744e8")
    ),
    ImageExecutionProfileResolver.identityRule(
        "animagine_xl_v4_qnn228",
        repositories = setOf("YuuiKurata/animagineXL_qnn2.28"),
        artifacts = setOf("animagineXL40_v4Opt_qnn2.28_8gen3.zip"),
        fingerprints = setOf("a08612048ad60e834ae7f5a1b234cfb7edd299e28dc20abab1a4a9be5bf34dfc")
    ),
    ImageExecutionProfileResolver.identityRule(
        "cyberrealisticxl_qnn228",
        repositories = setOf("xororz/sdxl-qnn"),
        artifacts = setOf("cyber_realistic_v10_qnn2.28_8gen3.zip"),
        fingerprints = setOf("2af39e9c80629a27406112e91627657981b50f28b477e7adaf9415d886e08ea2")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qualcomm_sd15_gen5_qnn",
        repositories = setOf("qualcomm/Stable-Diffusion-v1.5"),
        artifacts = setOf("stable_diffusion_v1_5-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qualcomm_sd21_gen5_qnn",
        repositories = setOf("qualcomm/Stable-Diffusion-v2.1"),
        artifacts = setOf("stable_diffusion_v2_1-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qualcomm_controlnet_canny_gen5_qnn",
        repositories = setOf("qualcomm/ControlNet-Canny"),
        artifacts = setOf("controlnet_canny-qnn_context_binary-w8a16-qualcomm_snapdragon_8_elite_gen5_for_galaxy.zip")
    ),
    ImageExecutionProfileResolver.identityRule(
        "sd15_mnn_512_quality",
        aliases = setOf("sd15_mnn_bundle"),
        repositories = setOf("MNN/stable-diffusion-v1-5-mnn-opencl"),
        artifacts = setOf("stable-diffusion-v1-5-mnn-opencl")
    ),
    ImageExecutionProfileResolver.identityRule(
        "mnn_sana_edit_v2",
        repositories = setOf("MNN/MNN-Sana-Edit-V2"),
        artifacts = setOf("MNN-Sana-Edit-V2")
    ),
    ImageExecutionProfileResolver.identityRule(
        "sd_turbo_512_experimental",
        repositories = setOf("AI-ModelScope/sd-turbo"),
        artifacts = setOf("sd_turbo.safetensors"),
        fingerprints = setOf("3f067a1b943cf162f2b8f8588f6cf5824bd5b4c7d1d88d87164b9ca123616549")
    ),
    ImageExecutionProfileResolver.identityRule(
        "z_image_turbo_q4",
        aliases = setOf("z_image_turbo_q2_bundle"),
        repositories = setOf("hf/leejet-Z-Image-Turbo-GGUF"),
        artifacts = setOf("z_image_turbo-Q2_K.gguf"),
        fingerprints = setOf("a9cf1b0368e24c2f9d542d2951c01f6f7fc85ed8c9ed39b5b37b15375508d58a")
    ),
    ImageExecutionProfileResolver.identityRule(
        "flux2_klein_4b_q4",
        repositories = setOf("hf/leejet-FLUX.2-klein-4B-GGUF"),
        artifacts = setOf("flux-2-klein-4b-Q4_0.gguf")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qwen_image_2512_q2",
        repositories = setOf("unsloth/Qwen-Image-2512-GGUF"),
        artifacts = setOf("qwen-image-2512-Q2_K.gguf"),
        fingerprints = setOf("176678f0d4e6c613c5a318014f16d829438b8feec9454bde7b3070a520bf1728")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qwen_image_21_q4_k_m",
        repositories = setOf("unsloth/Qwen-Image-2.1-GGUF"),
        artifacts = setOf("qwen-image-2.1-Q4_K_M.gguf"),
        fingerprints = setOf("631d532e7ca71e8d90a87c71d3699761a812039d22e3370e87498d87754660fe")
    ),
    ImageExecutionProfileResolver.identityRule(
        "qwen_image_21_mnn_opencl",
        repositories = setOf("evankuo/Qwen-Image-2.1-MNN")
    ),
    ImageExecutionProfileResolver.identityRule(
        "longcat_image_q4",
        repositories = setOf("vantagewithai/LongCat-Image-GGUF"),
        artifacts = setOf("LongCat-Image-Q4_0.gguf"),
        fingerprints = setOf("d494513ea95e82fb7069cdb914738f22dfc940fc770000fbbc8ad0a7a445f601")
    )
)
