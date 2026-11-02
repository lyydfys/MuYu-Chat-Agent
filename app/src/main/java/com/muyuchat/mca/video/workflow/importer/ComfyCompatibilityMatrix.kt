package com.muyuchat.mca.video.workflow.importer

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.mca.video.model.VideoModelComponentRole
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles

/**
 * The frozen ComfyUI API-format support matrix (V2-01/V2-02).
 *
 * Only the class_types, inputs and outputs listed here are accepted. Everything else is
 * rejected with a diagnostic instead of being dropped or guessed. Link inputs carry the exact
 * source class_types they may consume; a two-element array on any other field is a type error.
 */
enum class ComfyInputKind { STRING, INTEGER, DECIMAL, LINK }

data class ComfyInputSpec(
    val name: String,
    val kind: ComfyInputKind,
    val required: Boolean,
    /** For LINK inputs: the class_types allowed to feed this input. Empty = any supported. */
    val sourceClassTypes: Set<String> = emptySet(),
    /** For LINK inputs: allowed output indices per source class; missing key = any in range. */
    val sourceOutputIndexes: Map<String, Set<Int>> = emptyMap(),
    val note: String = "",
)

data class ComfyNodeSpec(
    val classType: String,
    val role: String,
    val inputs: List<ComfyInputSpec>,
    val outputNames: List<String>,
) {
    val outputCount: Int get() = outputNames.size

    val inputsByName: Map<String, ComfyInputSpec> = inputs.associateBy { it.name }

    fun input(name: String): ComfyInputSpec? = inputsByName[name]
}

/** One pinned model profile the importer can bind a Comfy loader triple to. */
data class ComfyModelProfileBinding(
    val modelId: String,
    val profileRevision: String,
    val ditFileName: String,
    val encoderFileName: String,
    val vaeFileName: String,
)

enum class ComfyModelRole { DIT, TEXT_ENCODER, VAE }

data class ComfyModelBindingIssue(
    val roles: Set<ComfyModelRole>,
    val message: String,
)

sealed class ComfyModelBindingResult {
    data class Bound(val profile: ComfyModelProfileBinding) : ComfyModelBindingResult()

    data class Unbound(val issues: List<ComfyModelBindingIssue>) : ComfyModelBindingResult()
}

enum class ComfyWarningTarget { LATENT, CLIP, SAMPLER, SAVE }

data class ComfyMappingWarning(
    val target: ComfyWarningTarget,
    val input: String,
    val message: String,
)

/** Typed input failure from the compatibility readers; the importer turns it into a Rejected preview. */
class ComfyValueFailure(
    val code: VideoErrorCode,
    val nodeId: String?,
    val path: String,
    override val message: String,
) : RuntimeException(message)

object ComfyCompatibilityMatrix {

    const val MAPPING_VERSION = "comfy-api/2026-10-25"

    const val UNET_LOADER = "UNETLoader"
    const val CLIP_LOADER = "CLIPLoader"
    const val VAE_LOADER = "VAELoader"
    const val CLIP_TEXT_ENCODE = "CLIPTextEncode"
    const val EMPTY_LATENT = "EmptyHunyuanLatentVideo"
    const val WAN_IMAGE_TO_VIDEO = "WanImageToVideo"
    const val LOAD_IMAGE = "LoadImage"
    const val MODEL_SAMPLING_SD3 = "ModelSamplingSD3"
    const val K_SAMPLER = "KSampler"
    const val VAE_DECODE = "VAEDecode"
    const val CREATE_VIDEO = "CreateVideo"
    const val SAVE_VIDEO = "SaveVideo"

    private fun link(name: String, required: Boolean, vararg sources: Pair<String, Set<Int>>): ComfyInputSpec =
        ComfyInputSpec(
            name = name,
            kind = ComfyInputKind.LINK,
            required = required,
            sourceClassTypes = sources.map { it.first }.toSet(),
            sourceOutputIndexes = sources.toMap(),
        )

    val NODE_SPECS: List<ComfyNodeSpec> = listOf(
        ComfyNodeSpec(
            classType = UNET_LOADER,
            role = "DiT 模型加载；unet_name 参与固定 profile 文件名绑定",
            inputs = listOf(
                ComfyInputSpec("unet_name", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("weight_dtype", ComfyInputKind.STRING, required = true),
            ),
            outputNames = listOf("MODEL"),
        ),
        ComfyNodeSpec(
            classType = CLIP_LOADER,
            role = "UMT5 文本编码器加载；type 必须为 wan",
            inputs = listOf(
                ComfyInputSpec("clip_name", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("type", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("device", ComfyInputKind.STRING, required = false),
            ),
            outputNames = listOf("CLIP"),
        ),
        ComfyNodeSpec(
            classType = VAE_LOADER,
            role = "Wan2.2 5B VAE 加载；vae_name 参与固定 profile 文件名绑定",
            inputs = listOf(
                ComfyInputSpec("vae_name", ComfyInputKind.STRING, required = true),
            ),
            outputNames = listOf("VAE"),
        ),
        ComfyNodeSpec(
            classType = CLIP_TEXT_ENCODE,
            role = "文本条件；由 KSampler positive/negative 的连线方向决定正负",
            inputs = listOf(
                ComfyInputSpec("text", ComfyInputKind.STRING, required = true),
                link("clip", true, CLIP_LOADER to setOf(0)),
            ),
            outputNames = listOf("CONDITIONING"),
        ),
        ComfyNodeSpec(
            classType = EMPTY_LATENT,
            role = "文生视频条件几何与 latent 源",
            inputs = listOf(
                ComfyInputSpec("width", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("height", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("length", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("batch_size", ComfyInputKind.INTEGER, required = true),
            ),
            outputNames = listOf("LATENT"),
        ),
        ComfyNodeSpec(
            classType = WAN_IMAGE_TO_VIDEO,
            role = "图生视频条件几何；输出 2 为 latent，start_image 来自 LoadImage",
            inputs = listOf(
                link("positive", false, CLIP_TEXT_ENCODE to setOf(0)),
                link("negative", false, CLIP_TEXT_ENCODE to setOf(0)),
                link("vae", false, VAE_LOADER to setOf(0)),
                ComfyInputSpec("width", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("height", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("length", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("batch_size", ComfyInputKind.INTEGER, required = true),
                link("start_image", true, LOAD_IMAGE to setOf(0)),
            ),
            outputNames = listOf("positive", "negative", "latent"),
        ),
        ComfyNodeSpec(
            classType = LOAD_IMAGE,
            role = "图生视频输入图；仅记录来源名，导入不视为本地已验证资产",
            inputs = listOf(
                ComfyInputSpec("image", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("upload", ComfyInputKind.STRING, required = false),
            ),
            outputNames = listOf("IMAGE", "MASK"),
        ),
        ComfyNodeSpec(
            classType = MODEL_SAMPLING_SD3,
            role = "flow shift 映射",
            inputs = listOf(
                ComfyInputSpec("shift", ComfyInputKind.DECIMAL, required = true),
                link("model", true, UNET_LOADER to setOf(0)),
            ),
            outputNames = listOf("MODEL"),
        ),
        ComfyNodeSpec(
            classType = K_SAMPLER,
            role = "采样参数；只有 euler/normal/denoise=1 可精确映射",
            inputs = listOf(
                ComfyInputSpec("seed", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("steps", ComfyInputKind.INTEGER, required = true),
                ComfyInputSpec("cfg", ComfyInputKind.DECIMAL, required = true),
                ComfyInputSpec("sampler_name", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("scheduler", ComfyInputKind.STRING, required = true),
                ComfyInputSpec("denoise", ComfyInputKind.DECIMAL, required = true),
                link(
                    "model",
                    true,
                    UNET_LOADER to setOf(0),
                    MODEL_SAMPLING_SD3 to setOf(0),
                ),
                link(
                    "positive",
                    true,
                    CLIP_TEXT_ENCODE to setOf(0),
                    WAN_IMAGE_TO_VIDEO to setOf(0),
                ),
                link(
                    "negative",
                    true,
                    CLIP_TEXT_ENCODE to setOf(0),
                    WAN_IMAGE_TO_VIDEO to setOf(1),
                ),
                link(
                    "latent_image",
                    true,
                    EMPTY_LATENT to setOf(0),
                    WAN_IMAGE_TO_VIDEO to setOf(2, 0),
                ),
            ),
            outputNames = listOf("LATENT"),
        ),
        ComfyNodeSpec(
            classType = VAE_DECODE,
            role = "latent 解码",
            inputs = listOf(
                link("samples", true, K_SAMPLER to setOf(0)),
                link("vae", true, VAE_LOADER to setOf(0)),
            ),
            outputNames = listOf("IMAGE"),
        ),
        ComfyNodeSpec(
            classType = CREATE_VIDEO,
            role = "帧率映射",
            inputs = listOf(
                link("images", true, VAE_DECODE to setOf(0)),
                ComfyInputSpec("fps", ComfyInputKind.INTEGER, required = true),
            ),
            outputNames = listOf("VIDEO"),
        ),
        ComfyNodeSpec(
            classType = SAVE_VIDEO,
            role = "输出根；format/codec 只作元数据约束",
            inputs = listOf(
                link("video", true, CREATE_VIDEO to setOf(0)),
                ComfyInputSpec("filename_prefix", ComfyInputKind.STRING, required = false),
                ComfyInputSpec("format", ComfyInputKind.STRING, required = false),
                ComfyInputSpec("codec", ComfyInputKind.STRING, required = false),
            ),
            outputNames = emptyList(),
        ),
    )

    private val byClassType: Map<String, ComfyNodeSpec> = NODE_SPECS.associateBy { it.classType }

    fun nodeSpec(classType: String): ComfyNodeSpec? = byClassType[classType]

    val supportedClassTypes: Set<String> get() = byClassType.keys

    val modelProfiles: List<ComfyModelProfileBinding> = Wan22Ti2v5bProfiles.ALL.map { manifest ->
        ComfyModelProfileBinding(
            modelId = manifest.modelId,
            profileRevision = manifest.revision,
            ditFileName = baseFileName(manifest.componentFor(VideoModelComponentRole.DIT).relativePath),
            encoderFileName = baseFileName(
                manifest.componentFor(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).relativePath,
            ),
            vaeFileName = baseFileName(manifest.componentFor(VideoModelComponentRole.VAE_WAN22_5B).relativePath),
        )
    }

    fun baseFileName(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Binds the three Comfy loader file names to one pinned profile. A role present in no
     * profile, or roles split across profiles, yields Unbound with role-anchored issues.
     */
    fun bindModelFiles(unetName: String, clipName: String, vaeName: String): ComfyModelBindingResult {
        val profiles = modelProfiles
        val unetBase = baseFileName(unetName)
        val clipBase = baseFileName(clipName)
        val vaeBase = baseFileName(vaeName)
        val ditMatches = profiles.filter { it.ditFileName == unetBase }
        val clipMatches = profiles.filter { it.encoderFileName == clipBase }
        val vaeMatches = profiles.filter { it.vaeFileName == vaeBase }
        val common = profiles.filter { it in ditMatches && it in clipMatches && it in vaeMatches }
        if (common.size == 1) return ComfyModelBindingResult.Bound(common.single())

        val issues = mutableListOf<ComfyModelBindingIssue>()
        if (ditMatches.isEmpty()) {
            issues += ComfyModelBindingIssue(
                roles = setOf(ComfyModelRole.DIT),
                message = "unet_name '$unetName' 不是固定 profile 的 DiT 文件名" +
                    "（${profiles.joinToString("/") { it.ditFileName }}）；仍可导入，但需模型库绑定校验",
            )
        }
        if (clipMatches.isEmpty()) {
            issues += ComfyModelBindingIssue(
                roles = setOf(ComfyModelRole.TEXT_ENCODER),
                message = "clip_name '$clipName' 不是固定 profile 的文本编码器文件名" +
                    "（${profiles.joinToString("/") { it.encoderFileName }}）；仍可导入，但需模型库绑定校验",
            )
        }
        if (vaeMatches.isEmpty()) {
            issues += ComfyModelBindingIssue(
                roles = setOf(ComfyModelRole.VAE),
                message = "vae_name '$vaeName' 不是固定 profile 的 VAE 文件名" +
                    "（${profiles.joinToString("/") { it.vaeFileName }}）；仍可导入，但需模型库绑定校验",
            )
        }
        if (common.size > 1) {
            issues += ComfyModelBindingIssue(
                roles = setOf(ComfyModelRole.DIT),
                message = "多个固定 profile 共用这些文件名，无法唯一绑定模型角色；runnable=false",
            )
        } else if (ditMatches.isNotEmpty() && clipMatches.isNotEmpty() && vaeMatches.isNotEmpty()) {
            issues += ComfyModelBindingIssue(
                roles = setOf(ComfyModelRole.DIT, ComfyModelRole.TEXT_ENCODER, ComfyModelRole.VAE),
                message = "UNET/CLIP/VAE 文件名分别匹配不同固定 profile，无法确认同一 manifest；runnable=false",
            )
        }
        return ComfyModelBindingResult.Unbound(issues)
    }

    /** Converts an exact JSON decimal to a fixed-point value at [targetScale] digits, HALF_UP. */
    fun toFixedScale(value: ComfyJsonValue.NumberValue, targetScale: Int = 3): Long? {
        require(targetScale in 0..COMFY_JSON_MAX_SCALE) { "target scale out of range" }
        if (value.unscaled == Long.MIN_VALUE) return null
        if (value.scale <= targetScale) {
            val factor = pow10(targetScale - value.scale)
            return try {
                Math.multiplyExact(value.unscaled, factor)
            } catch (_: ArithmeticException) {
                null
            }
        }
        val divisor = pow10(value.scale - targetScale)
        val negative = value.unscaled < 0
        val magnitude = if (negative) -value.unscaled else value.unscaled
        val quotient = magnitude / divisor
        val remainder = magnitude % divisor
        val rounded = if (remainder * 2 >= divisor) quotient + 1 else quotient
        return if (negative) -rounded else rounded
    }

    /** Checks the fixed-value rules that cannot be represented exactly by the MCA contract. */
    fun mappingWarnings(
        batchSize: Long,
        clipType: String,
        samplerName: String,
        scheduler: String,
        denoise: ComfyJsonValue.NumberValue,
        saveFormat: String?,
        saveCodec: String?,
    ): List<ComfyMappingWarning> = buildList {
        if (batchSize != 1L) {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.LATENT,
                    "batch_size",
                    "batch_size=$batchSize 无法在 MCA 单视频图中精确表达；runnable=false",
                ),
            )
        }
        if (clipType != "wan") {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.CLIP,
                    "type",
                    "CLIPLoader.type='$clipType' 不是 wan；需要模型库绑定校验",
                ),
            )
        }
        if (samplerName != "euler") {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.SAMPLER,
                    "sampler_name",
                    "sampler_name='$samplerName' 不能精确映射；仅 euler 可运行",
                ),
            )
        }
        if (scheduler != "normal") {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.SAMPLER,
                    "scheduler",
                    "scheduler='$scheduler' 不能精确映射；仅 normal 可运行",
                ),
            )
        }
        if (denoise.scale != 0 || denoise.unscaled != 1L) {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.SAMPLER,
                    "denoise",
                    "denoise=${formatDecimal(denoise)} 不能精确映射；仅 1.0 可运行",
                ),
            )
        }
        if (saveFormat != null && saveFormat.lowercase() !in setOf("mp4", "h264")) {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.SAVE,
                    "format",
                    "SaveVideo.format='$saveFormat' 不支持；仅 mp4/h264 或未指定可运行",
                ),
            )
        }
        if (saveCodec != null && saveCodec.lowercase() != "h264") {
            add(
                ComfyMappingWarning(
                    ComfyWarningTarget.SAVE,
                    "codec",
                    "SaveVideo.codec='$saveCodec' 不支持；仅 h264 或未指定可运行",
                ),
            )
        }
    }

    fun formatDecimal(value: ComfyJsonValue.NumberValue): String {
        if (value.scale == 0) return value.unscaled.toString()
        val negative = value.unscaled < 0
        val digits = (if (negative) -value.unscaled else value.unscaled)
            .toString()
            .padStart(value.scale + 1, '0')
        val split = digits.length - value.scale
        val fraction = digits.substring(split).trimEnd('0')
        return buildString {
            if (negative) append('-')
            append(digits.substring(0, split))
            if (fraction.isNotEmpty()) append('.').append(fraction)
        }
    }

    private fun pow10(exponent: Int): Long {
        var value = 1L
        repeat(exponent) { value *= 10L }
        return value
    }
}

/** Typed accessors for a parsed node. The parser guarantees these shapes; failures stay explicit. */
fun ComfyApiNode.linkInput(name: String): ComfyApiInputValue.Link {
    val value = inputs[name] ?: failInput(VideoErrorCode.WORKFLOW_INVALID, name, "缺少输入 '$name'")
    return value as? ComfyApiInputValue.Link
        ?: failInput(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, name, "输入 '$name' 必须是引用")
}

fun ComfyApiNode.stringInput(name: String): String {
    val value = literalValue(name) ?: failInput(VideoErrorCode.WORKFLOW_INVALID, name, "缺少字符串输入 '$name'")
    return (value as? ComfyJsonValue.StringValue)?.value
        ?: failInput(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, name, "输入 '$name' 必须是字符串")
}

fun ComfyApiNode.optionalStringInput(name: String): String? {
    val inputValue = inputs[name] ?: return null
    if (inputValue is ComfyApiInputValue.Literal && inputValue.value is ComfyJsonValue.NullValue) return null
    return stringInput(name)
}

fun ComfyApiNode.longInput(name: String): Long {
    val value = literalValue(name) ?: failInput(VideoErrorCode.WORKFLOW_INVALID, name, "缺少整数输入 '$name'")
    val number = value as? ComfyJsonValue.NumberValue
        ?: failInput(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, name, "输入 '$name' 必须是整数")
    if (number.scale != 0) {
        failInput(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, name, "输入 '$name' 必须是整数")
    }
    return number.unscaled
}

fun ComfyApiNode.decimalInput(name: String): ComfyJsonValue.NumberValue {
    val value = literalValue(name) ?: failInput(VideoErrorCode.WORKFLOW_INVALID, name, "缺少数值输入 '$name'")
    return value as? ComfyJsonValue.NumberValue
        ?: failInput(VideoErrorCode.WORKFLOW_TYPE_MISMATCH, name, "输入 '$name' 必须是数值")
}

private fun ComfyApiNode.literalValue(name: String): ComfyJsonValue? =
    (inputs[name] as? ComfyApiInputValue.Literal)?.value

private fun ComfyApiNode.failInput(code: VideoErrorCode, name: String, message: String): Nothing =
    throw ComfyValueFailure(code, id, "$id/inputs/$name", message)
