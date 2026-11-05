package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaManifest
import com.muyuchat.core.mediatek.MediaTekAnimaManifestJson
import com.muyuchat.core.mediatek.MediaTekAotCfgMode
import com.muyuchat.core.mediatek.MediaTekAotManifest
import com.muyuchat.core.mediatek.MediaTekAotManifestJson
import org.json.JSONObject

/**
 * Manifest-driven product contract for MediaTek Neuron (天玑 NPU) image bundles.
 *
 * The Neuron pipeline executes the package's own v3/v4 component manifest; it has no SD
 * `ImageExecutionProfile`, so the generic sampler preflight, the parameter panel and the
 * worker journal must read the declared generation profiles here instead of synthesizing a
 * generic stable-diffusion profile.
 *
 * This reader is deliberately JSON-only: the readiness path already hashed and pinned every
 * graph and asset, and native execution re-verifies each pin before use. Re-hashing a
 * multi-gigabyte NPUG/DLA package on every UI projection would be pure I/O for no added
 * guarantee.
 */

/** One declared generation profile (canvas + steps + CFG bounds) of a MediaTek image bundle. */
internal data class MediaTekImageGenerationProfile(
    val width: Int,
    val height: Int,
    val supportedSteps: Set<Int>,
    val defaultSteps: Int,
    val defaultCfgScale: Float,
    val minimumCfgScale: Float,
    val maximumCfgScale: Float,
    /** Non-null when the native call only honours this exact CFG value (`cfgMode=NONE`). */
    val fixedCfgScale: Float? = null
) {
    init {
        require(width in 256..1024 && height in 256..1024) {
            "MediaTek image profile resolution must be within 256..1024."
        }
        require(supportedSteps.isNotEmpty() && defaultSteps in supportedSteps) {
            "MediaTek image profile must declare a supported default step count."
        }
        require(minimumCfgScale.isFinite() && maximumCfgScale.isFinite() &&
            minimumCfgScale >= 0f && maximumCfgScale >= minimumCfgScale
        ) { "MediaTek image profile CFG bounds must be finite and ordered." }
        require(defaultCfgScale.isFinite() && defaultCfgScale in minimumCfgScale..maximumCfgScale) {
            "MediaTek image profile default CFG must be inside its declared bounds."
        }
        require(fixedCfgScale == null || fixedCfgScale == defaultCfgScale) {
            "A fixed MediaTek CFG value must equal the profile default."
        }
    }

    val minimumSteps: Int get() = supportedSteps.min()
    val maximumSteps: Int get() = supportedSteps.max()
}

/**
 * The exact product surface one installed MediaTek bundle declares. Both the UI defaults and
 * the pre-execution admission derive from this single value, so the panel can never advertise a
 * canvas, step count or CFG the package rejects.
 */
internal data class MediaTekImageProductContract(
    val schema: String,
    val manifestSha256: String,
    val recommendationId: String?,
    val recommendationRevision: String?,
    val supportsNegativePrompt: Boolean,
    val profiles: List<MediaTekImageGenerationProfile>,
    val defaultProfile: MediaTekImageGenerationProfile
) {
    init {
        require(profiles.isNotEmpty()) { "MediaTek image bundle must declare generation profiles." }
        require(defaultProfile in profiles) { "MediaTek default profile must be declared." }
        require(SHA256_PATTERN.matches(manifestSha256)) {
            "MediaTek component manifest identity must be a SHA-256 digest."
        }
    }

    fun profilesForSize(width: Int?, height: Int?): List<MediaTekImageGenerationProfile> =
        profiles.filter { profile ->
            (width == null || width == profile.width) &&
                (height == null || height == profile.height)
        }

    fun profileFor(width: Int?, height: Int?, steps: Int?): MediaTekImageGenerationProfile? =
        profiles.firstOrNull { profile ->
            (width == null || width == profile.width) &&
                (height == null || height == profile.height) &&
                (steps == null || steps in profile.supportedSteps)
        }

    /** Deterministic, non-reversible prompt-execution binding for this exact manifest. */
    fun promptExecutionBindings(): MediaTekPromptExecutionBindings = MediaTekPromptExecutionBindings(
        imageProfileFingerprint = promptFingerprint("mca-mediatek-image-profile:$manifestSha256"),
        promptLanguageFingerprint = promptFingerprint(
            "mca-mediatek-prompt-language:english-dominant:$manifestSha256"
        )
    )

    private companion object {
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

internal data class MediaTekPromptExecutionBindings(
    val imageProfileFingerprint: String,
    val promptLanguageFingerprint: String
)

/**
 * Reads the installed bundle's envelope and its pinned v3/v4 component manifest. Returns null for
 * non-MediaTek records, undeclared bundles and manifests that cannot be parsed as the declared
 * schema; callers that require the contract fail closed with an actionable message instead.
 */
internal fun LocalImageModelRecord.mediaTekImageProductContractOrNull(): MediaTekImageProductContract? {
    if (runtime != LocalImageRuntime.MEDIATEK_NEURON) return null
    val root = installedImageBundleRootForUi() ?: return null
    val envelope = readInstalledImageManifest() ?: return null
    if (!envelope.optString("runtime").trim().equals(LocalImageRuntime.MEDIATEK_NEURON.name, true)) {
        return null
    }
    if (!envelope.optString("family").trim().equals(LocalImageModelFamily.ANIMA.name, true)) {
        return null
    }
    if (!envelope.optString("task").trim().equals("TEXT_TO_IMAGE", true)) return null
    val relativePath = envelope.optString("mediatekAnimaManifestPath")
        .trim()
        .takeIf(String::isNotEmpty)
        ?: return null
    val manifestFile = root.safeDescendantOrNull(relativePath) ?: return null
    if (!manifestFile.isFile || manifestFile.length() !in 1L..MAX_MEDIA_TEK_COMPONENT_MANIFEST_BYTES) {
        return null
    }
    val manifestText = runCatching { manifestFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
    val schema = runCatching { JSONObject(manifestText).optString("schema").trim() }
        .getOrNull()
        ?.takeIf(String::isNotEmpty)
        ?: return null
    val manifestSha256 = runCatching { observedLocalImageFileSha256(manifestFile) }.getOrNull()
        ?: return null
    val smoke = envelope.optJSONObject("smoke")
    val recommendationId = envelope.optString("recommendationId")
        .trim()
        .takeIf(String::isNotEmpty)
        ?: envelope.optString("id").trim().takeIf(String::isNotEmpty)
    val recommendationRevision = envelope.optString("revision").trim().takeIf(String::isNotEmpty)
    return runCatching {
        when (schema) {
            MediaTekAotManifest.SCHEMA -> mediaTekAotProductContract(
                manifest = MediaTekAotManifestJson.parse(manifestText),
                smoke = smoke,
                manifestSha256 = manifestSha256,
                recommendationId = recommendationId,
                recommendationRevision = recommendationRevision
            )
            MediaTekAnimaManifest.SCHEMA -> mediaTekAnimaProductContract(
                manifest = MediaTekAnimaManifestJson.parse(manifestText),
                smoke = smoke,
                manifestSha256 = manifestSha256,
                recommendationId = recommendationId,
                recommendationRevision = recommendationRevision
            )
            else -> null
        }
    }.getOrNull()
}

internal fun LocalImageModelRecord.requireMediaTekImageProductContract(): MediaTekImageProductContract =
    mediaTekImageProductContractOrNull() ?: throw LocalImageProductContractException(
        "invalid_image_input_mediatek_manifest",
        "天玑 NPU 生成参数不可用：无法读取该模型包内的 v3/v4 执行清单；" +
            "请在模型管理中重新导入完整的 Anima 天玑 NPU 包。"
    )

private fun mediaTekAotProductContract(
    manifest: MediaTekAotManifest,
    smoke: JSONObject?,
    manifestSha256: String,
    recommendationId: String?,
    recommendationRevision: String?
): MediaTekImageProductContract {
    val fixedCfg = manifest.parameters.cfgMode == MediaTekAotCfgMode.NONE
    val profiles = manifest.profiles.map { profile ->
        MediaTekImageGenerationProfile(
            width = profile.widthPixels,
            height = profile.heightPixels,
            supportedSteps = profile.supportedSteps,
            defaultSteps = profile.defaultSteps,
            defaultCfgScale = profile.defaultCfgScale,
            minimumCfgScale = profile.minimumCfgScale,
            maximumCfgScale = profile.maximumCfgScale,
            fixedCfgScale = if (fixedCfg) profile.defaultCfgScale else null
        )
    }
    return MediaTekImageProductContract(
        schema = manifest.schema,
        manifestSha256 = manifestSha256,
        recommendationId = recommendationId,
        recommendationRevision = recommendationRevision,
        supportsNegativePrompt = manifest.parameters.supportsNegativePrompt,
        profiles = profiles,
        defaultProfile = mediaTekDefaultProfile(
            profiles = profiles,
            smokeWidth = smoke?.optInt("width", 0) ?: 0,
            smokeHeight = smoke?.optInt("height", 0) ?: 0,
            smokeSteps = smoke?.optInt("steps", 0) ?: 0
        )
    )
}

private fun mediaTekAnimaProductContract(
    manifest: MediaTekAnimaManifest,
    smoke: JSONObject?,
    manifestSha256: String,
    recommendationId: String?,
    recommendationRevision: String?
): MediaTekImageProductContract {
    val profiles = manifest.sampling.generationProfiles.map { profile ->
        MediaTekImageGenerationProfile(
            width = profile.widthPixels,
            height = profile.heightPixels,
            supportedSteps = profile.sigmaSchedules.keys.toSet(),
            defaultSteps = profile.defaultSteps,
            defaultCfgScale = profile.defaultCfgScale,
            minimumCfgScale = profile.minimumCfgScale,
            maximumCfgScale = profile.maximumCfgScale
        )
    }
    return MediaTekImageProductContract(
        schema = manifest.schema,
        manifestSha256 = manifestSha256,
        recommendationId = recommendationId,
        recommendationRevision = recommendationRevision,
        // The v3 Anima contract requires a complete positive and negative conditioning branch.
        supportsNegativePrompt = true,
        profiles = profiles,
        defaultProfile = mediaTekDefaultProfile(
            profiles = profiles,
            smokeWidth = smoke?.optInt("width", 0) ?: 0,
            smokeHeight = smoke?.optInt("height", 0) ?: 0,
            smokeSteps = smoke?.optInt("steps", 0) ?: 0
        )
    )
}

private fun mediaTekDefaultProfile(
    profiles: List<MediaTekImageGenerationProfile>,
    smokeWidth: Int,
    smokeHeight: Int,
    smokeSteps: Int
): MediaTekImageGenerationProfile =
    profiles.firstOrNull { profile ->
        profile.width == smokeWidth && profile.height == smokeHeight &&
            (smokeSteps == 0 || smokeSteps in profile.supportedSteps)
    } ?: profiles.first()

/**
 * Normalizes one product request against the bundle's declared surface. The MediaTek sampler is
 * fixed by the manifest (flow-matching Euler), so an explicit `euler` selection is cleared before
 * the request reaches the native pipeline, while any other sampler is rejected instead of being
 * silently ignored.
 */
internal fun normalizeMediaTekImageOptions(
    contract: MediaTekImageProductContract,
    options: LocalImageGenerationOptions
): LocalImageGenerationOptions {
    val unsupported = buildList {
        if (options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE) add("生成方式 ${options.taskMode.wireName}")
        if (options.batchCount != 1) add("批量数量 ${options.batchCount}")
        if (options.threads != null) add("线程数")
        if (options.inputImage != null) add("原图（img2img）")
        if (options.maskImage != null) add("蒙版（inpaint）")
        if (options.controlImage != null) add("控制图（control）")
        if (options.strength != null) add("重绘强度")
        if (options.controlStrength != null) add("控制强度")
        if (options.loras.isNotEmpty()) add("LoRA")
        if (options.textualInversionIds.isNotEmpty()) add("Textual Inversion")
        if (options.vaeTiling != null) add("VAE 分块")
        if (options.ultraFix != null) add("UltraFix")
        if (options.preview != null) add("实时预览")
        if (options.clipSkip != null) add("CLIP skip")
        if (options.distilledGuidance != null) add("distilled guidance")
        if (options.flowShift != null) add("flow shift")
        if (options.backendMode != null) add("后端模式")
        if (options.tokenEmbeddingMode != null) add("Token 嵌入模式")
        if (options.memoryMode != null) add("内存模式")
        if (options.runner != null) add("执行器")
        if (options.useCfg == false) add("关闭 CFG（useCfg=false）")
        options.sampleMethod?.trim()?.takeIf(String::isNotEmpty)?.let { sampler ->
            if (!sampler.equals(MEDIA_TEK_FIXED_SAMPLER, ignoreCase = true)) add("采样器 $sampler")
        }
    }
    if (unsupported.isNotEmpty()) {
        throw LocalImageProductContractException(
            "unsupported_mediatek_anima_options",
            "天玑 NPU 生成参数不可用：MediaTek Anima 仅支持单张文本生图，不支持 " +
                "${unsupported.joinToString("、")}。"
        )
    }
    options.seed?.let { seed ->
        if (seed < 0) {
            throw LocalImageProductContractException(
                "invalid_image_seed",
                "天玑 NPU 生成参数不可用：Seed 必须为非负整数。"
            )
        }
    }

    val sizeProfiles = contract.profilesForSize(options.width, options.height)
    if (sizeProfiles.isEmpty()) {
        val sizes = contract.profiles
            .map { profile -> "${profile.width}×${profile.height}" }
            .distinct()
            .joinToString(" / ")
        throw LocalImageProductContractException(
            "unsupported_mediatek_anima_size",
            "天玑 NPU 生成参数不可用：该模型只支持 $sizes 输出；当前尺寸为 " +
                "${options.width ?: "默认"}×${options.height ?: "默认"}。"
        )
    }
    val steps = options.steps
    val profile = sizeProfiles.firstOrNull { candidate ->
        steps == null || steps in candidate.supportedSteps
    } ?: throw LocalImageProductContractException(
        "unsupported_mediatek_anima_steps",
        "天玑 NPU 生成参数不可用：当前尺寸只支持 " + sizeProfiles.joinToString("；") { candidate ->
            "${candidate.width}×${candidate.height} 的 " +
                candidate.supportedSteps.sorted().joinToString("/") + " 步"
        } + "；当前为 $steps 步。"
    )
    options.cfgScale?.let { cfg ->
        val fixedCfg = profile.fixedCfgScale
        if (!cfg.isFinite() || cfg < profile.minimumCfgScale || cfg > profile.maximumCfgScale) {
            throw LocalImageProductContractException(
                "unsupported_mediatek_anima_cfg",
                "天玑 NPU 生成参数不可用：该模型只支持 CFG " +
                    "${formatMediaTekNumber(profile.minimumCfgScale)}-${formatMediaTekNumber(profile.maximumCfgScale)}" +
                    "；当前为 ${formatMediaTekNumber(cfg)}。"
            )
        }
        if (fixedCfg != null && kotlin.math.abs(cfg.toFloat() - fixedCfg) > MEDIA_TEK_CFG_TOLERANCE) {
            throw LocalImageProductContractException(
                "unsupported_mediatek_anima_cfg",
                "天玑 NPU 生成参数不可用：该模型只支持 CFG ${formatMediaTekNumber(fixedCfg)}" +
                    "；当前为 ${formatMediaTekNumber(cfg)}。"
            )
        }
    }
    if (!contract.supportsNegativePrompt && !options.negativePrompt.isNullOrEmpty()) {
        throw LocalImageProductContractException(
            "unsupported_mediatek_anima_negative_prompt",
            "天玑 NPU 生成参数不可用：该模型不执行负向提示词，请清空负向提示词后重试。"
        )
    }
    return options.copy(sampleMethod = null)
}

internal fun LocalImageModelRecord.normalizedMediaTekImageOptions(
    options: LocalImageGenerationOptions
): LocalImageGenerationOptions =
    normalizeMediaTekImageOptions(requireMediaTekImageProductContract(), options)

/** UI defaults come from the same contract the admission validates, never from SD fallbacks. */
internal fun mediaTekImageExecutionDefaultsForUi(
    contract: MediaTekImageProductContract
): LocalImageUiExecutionDefaults {
    val profile = contract.defaultProfile
    val supportedSteps = profile.supportedSteps.sorted()
    val fixedCfg = profile.fixedCfgScale?.toDouble()
    return LocalImageUiExecutionDefaults(
        width = profile.width,
        height = profile.height,
        steps = profile.defaultSteps,
        minSteps = supportedSteps.firstOrNull() ?: profile.defaultSteps,
        maxSteps = supportedSteps.lastOrNull() ?: profile.defaultSteps,
        cfgScale = profile.defaultCfgScale.toDouble(),
        seed = 42,
        sampler = MEDIA_TEK_FIXED_SAMPLER,
        minWidth = profile.width,
        maxWidth = profile.width,
        minHeight = profile.height,
        maxHeight = profile.height,
        widthMultiple = 16,
        heightMultiple = 16,
        ultraFixMinWidth = 0,
        ultraFixMaxWidth = 0,
        ultraFixMinHeight = 0,
        ultraFixMaxHeight = 0,
        ultraFixWidthMultiple = 0,
        ultraFixHeightMultiple = 0,
        ultraFixRequiredTileSize = 0,
        supportedSamplers = listOf(MEDIA_TEK_FIXED_SAMPLER),
        img2ImgSupportedSamplers = emptyList(),
        minCfgScale = fixedCfg ?: profile.minimumCfgScale.toDouble(),
        maxCfgScale = fixedCfg ?: profile.maximumCfgScale.toDouble()
    )
}

internal const val MEDIA_TEK_FIXED_SAMPLER = "euler"
private const val MEDIA_TEK_CFG_TOLERANCE = 1e-4f
private const val MAX_MEDIA_TEK_COMPONENT_MANIFEST_BYTES = 2L * 1024L * 1024L
private const val MEDIA_TEK_PARAMETER_PREFIX = "天玑 NPU 生成参数不可用："

/** One status line for UI admission; keeps the actionable detail and never double-prefixes it. */
internal fun mediaTekAdmissionFailureStatus(error: Throwable): String {
    val detail = error.message
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: "请使用当前模型支持的尺寸、步数和 CFG。"
    return if (detail.startsWith(MEDIA_TEK_PARAMETER_PREFIX)) {
        detail
    } else {
        MEDIA_TEK_PARAMETER_PREFIX + detail
    }
}

private fun formatMediaTekNumber(value: Number): String {
    val text = value.toDouble().toString()
    return text.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
