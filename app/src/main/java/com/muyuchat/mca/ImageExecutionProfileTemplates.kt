package com.muyuchat.mca

import com.muyuchat.core.download.RecommendedImageDefaults

/**
 * P5/S43: the built-in execution-profile template builders, moved verbatim out of the
 * ImageExecutionProfileResolver object together with the revision constants, runtime-asset
 * list and sdcpp scheduler set that only they use. `genericProfile` and `profileTemplate`
 * stay `internal` because the resolver object still calls them; every other builder is
 * file-private. Behavior is unchanged.
 */

internal const val QNN_SD15_EXECUTION_PROFILE_REVISION = 5
private const val QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION = 6
private const val QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION = 6

internal fun profileTemplate(
    target: BuiltInImageProfileTarget,
    fingerprint: String
): ImageExecutionProfile = when (val profileId = target.profileId) {
    "community.sd15.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        profileRevision = if (target.recommendationId == "dreamshaper_sd15_qnn228") {
            QNN_DREAMSHAPER_SD15_EXECUTION_PROFILE_REVISION
        } else {
            QNN_SD15_EXECUTION_PROFILE_REVISION
        },
        defaultNegativePrompt = if (target.recommendationId == "cyberrealistic_sd15_qnn228") {
            RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT
        } else {
            RecommendedImageDefaults.SD15_NEGATIVE_PROMPT
        }
    )
    "community.sd15.hyper.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        ImageModelVariant.HYPER,
        8,
        2.0,
        defaultNegativePrompt = RecommendedImageDefaults.PHOTO_NEGATIVE_PROMPT,
        profileRevision = QNN_REALISTICVISIONHYPER_SD15_EXECUTION_PROFILE_REVISION
    )
    "community.sd15.legacy-fp32.qnn228" -> qnnSd15Profile(
        profileId,
        fingerprint,
        ImageModelVariant.LEGACY_FP32,
        conditioningType = ImageEmbeddingDiskDataType.FP32,
        conversion = ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING,
        defaultNegativePrompt = RecommendedImageDefaults.ANIME_NEGATIVE_PROMPT
    )
    "community.sdxl.base.qnn228" -> qnnSdxlProfile(
        profileId = profileId,
        fingerprint = fingerprint,
        steps = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_STEPS
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_STEPS
            else -> 30
        },
        cfg = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_CFG
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_CFG
            else -> 7.0
        },
        defaultNegativePrompt = when (target.recommendationId) {
            "animagine_xl_v4_qnn228" -> RecommendedImageDefaults.ANIMAGINE_XL_NEGATIVE_PROMPT
            "cyberrealisticxl_qnn228" -> RecommendedImageDefaults.CYBERREALISTIC_XL_NEGATIVE_PROMPT
            else -> RecommendedImageDefaults.SDXL_NEGATIVE_PROMPT
        }
    )
    "community.sdxl.dmd2-alt.qnn228" -> qnnSdxlProfile(
        profileId,
        fingerprint,
        variant = ImageModelVariant.DMD2_ALT,
        steps = 4,
        cfg = 1.0,
        useCfg = false,
        timestepSpacing = ImageTimestepSpacing.LINSPACE,
        defaultNegativePrompt = null,
        supportsNegativePrompt = false
    )
    "qualcomm.sd15.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = false)
    "qualcomm.sd21.gen5.qnn245" -> qnnGen5Profile(profileId, fingerprint, sd21 = true)
    "qualcomm.controlnet-canny.gen5.qnn245" -> qnnControlNetProfile(profileId, fingerprint)
    "mnn.sd15.official.512" -> mnnSd15Profile(profileId, fingerprint)
    "mnn.sana-edit.v2" -> sanaEditProfile(profileId, fingerprint)
    "mnn.qwen-image-2.1.opencl" -> qwenImage21MnnProfile(profileId, fingerprint)
    "sdcpp.sd-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.SD_TURBO, ImageModelVariant.SD_TURBO, 4, 1.0, ImageSchedulerAlgorithm.EULER_A, supportsNegativePrompt = false)
    "sdcpp.z-image-turbo" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.Z_IMAGE, ImageModelVariant.Z_IMAGE_TURBO, 8, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, supportsNegativePrompt = false, maxPromptTokens = 512)
    "sdcpp.flux2-klein" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.FLUX, ImageModelVariant.FLUX2_KLEIN, 4, 1.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, supportsNegativePrompt = false, maxPromptTokens = 512)
    "sdcpp.qwen-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.QWEN_IMAGE, ImageModelVariant.QWEN_IMAGE, 40, 2.5, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.QWEN_IMAGE_2512_NEGATIVE_PROMPT, maxPromptTokens = 512)
    "sdcpp.qwen-image-2.1" -> qwenImage21GgufProfile(profileId, fingerprint)
    "sdcpp.longcat-image" -> sdcppProfile(profileId, fingerprint, LocalImageModelFamily.LONGCAT_IMAGE, ImageModelVariant.LONGCAT_IMAGE, 20, 5.0, ImageSchedulerAlgorithm.FLOW_MATCH, 1024, RecommendedImageDefaults.LONGCAT_IMAGE_NEGATIVE_PROMPT, maxPromptTokens = 512)
    "sdcpp.anima-preview" -> animaCatalogProfile(profileId, fingerprint)
    else -> error("Unknown built-in image profile target: $profileId")
}
