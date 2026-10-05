package com.muyuchat.mca

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * P5/S48: readiness/selection extensions for local image models, moved verbatim out of
 * LocalImageModelStore.kt (post-class tail cluster; recon verified zero leakage in both
 * directions and no test source-text pins). Behavior unchanged.
 */
internal fun findPrimaryImageModel(root: File): File? =
    root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .sortedWith(
            compareByDescending<File> { it.name.isPrimaryImageModelName() }
                .thenByDescending { it.length() }
        )
        .firstOrNull()

fun LocalImageModelRecord.localImageReadinessMessage(): String? {
    // Persisted verification is diagnostic evidence from an earlier attempt,
    // not a certificate.  A complete bundle must always be allowed to reach
    // the real native load/graph/generation path, including UNKNOWN and FAILED
    // records.  Concrete package/format/runtime structure remains the only
    // pre-execution admission check.
    return localImageStructuralReadinessMessage()
}

/**
 * Returns true only when FAILED is backed by a result recorded for the current
 * model record.  Legacy/stale FAILED bits must not be presented as a current
 * native failure and never participate in admission.
 */
internal fun LocalImageModelRecord.hasCurrentLocalImageExecutionFailure(): Boolean =
    verificationStatus == LocalImageVerificationStatus.FAILED &&
        verificationMessage.isNotBlank() &&
        verifiedAt > 0L &&
        !hasLegacyQwenMemoryEstimateFailure() &&
        localImageStructuralReadinessMessage() == null

/** Old APKs persisted this preflight estimate as FAILED before calling the native graph. */
private fun LocalImageModelRecord.hasLegacyQwenMemoryEstimateFailure(): Boolean =
    runtime == LocalImageRuntime.MNN_DIFFUSION &&
        verificationMessage.trim().startsWith("Qwen-Image-2.1 预计需要约 ") &&
        verificationMessage.contains("可用内存，但当前只有约")

/** Advisory text only; callers must never use this value to block selection or execution. */
fun LocalImageModelRecord.localImageVerificationDiagnosticMessage(): String? {
    if (localImageStructuralReadinessMessage() != null) return null
    val runtimeName = when (runtime) {
        LocalImageRuntime.MNN_DIFFUSION -> "MNN-Diffusion"
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
        else -> return null
    }
    return when (verificationStatus) {
        LocalImageVerificationStatus.UNKNOWN ->
            "$runtimeName 尚未记录真实运行结果，可直接尝试；本次 native 执行结果将作为诊断依据。"
        LocalImageVerificationStatus.FAILED -> if (hasLegacyQwenMemoryEstimateFailure()) {
            "旧版本曾因内存估算中止准备，这不是真实推理失败。现已允许按所选尺寸重新运行，以本次实际分配和执行结果为准。"
        } else if (hasCurrentLocalImageExecutionFailure()) {
            "$runtimeName 上次真实执行失败：${verificationMessage.trim()}；仍可直接重试，以本次执行结果为准。"
        } else {
            "$runtimeName 的历史失败状态没有当前执行证据，不会阻止使用；可直接重新尝试。"
        }
        LocalImageVerificationStatus.PASSED -> null
        LocalImageVerificationStatus.MNN_SMOKE_PASSED ->
            if (runtime == LocalImageRuntime.MNN_DIFFUSION) null else "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
        LocalImageVerificationStatus.QNN_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED ->
            "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
    }
}

fun LocalImageModelRecord.localImageStructuralReadinessMessage(): String? {
    val primary = File(path)
    if (path.isBlank() || !primary.isFile) {
        return "本地图像生成模型主文件不存在，请重新下载或重新导入。"
    }
    if (!primary.canRead() || primary.length() <= 0L) {
        return "本地图像生成模型主文件为空或不可读，请重新下载或重新导入。"
    }
    val declaredRoot = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
    if (declaredRoot != null && (!declaredRoot.isDirectory || !declaredRoot.canRead())) {
        return "本地图像生成模型组件目录不可读或不存在，请重新下载或重新导入完整模型包。"
    }
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return mnnDiffusionReadinessMessage()
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return qnnImageBundleReadinessMessage()
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
    }
    val componentSelection = runCatching { resolveStableDiffusionComponentSelection(this) }
        .getOrElse { error ->
            return error.message ?: "stable-diffusion.cpp image bundle component selection failed."
        }
    if (componentSelection.mode == STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST) return null
    if (!family.requiresCompanionComponents()) return null
    val requirement = family.requiredCompanionComponentHint()
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: return "缺少组件包：${displayName} 只有 diffusion 主模型，还需要 $requirement。请在模型管理 > 文件中导入包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 zip 引擎包。"
    val primaryCanonicalPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val files = root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .filterNot {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == primaryCanonicalPath
        }
        .toList()
    val missing = buildList {
        if (files.none { it.isVaeComponentFile() }) add("VAE")
        if (files.none { it.isTextEncoderComponentFile() }) add("文本编码器/LLM")
    }
    return if (missing.isEmpty()) {
        null
    } else {
        "缺少组件：${missing.joinToString("、")}。${displayName} 需要 $requirement，不能只用单个 GGUF 生成图片。"
    }
}

fun LocalImageModelRecord.isReadyForLocalImageGeneration(): Boolean =
    localImageReadinessMessage() == null

/** Automatic selection is structural only; verification state is deliberately ignored. */
internal fun selectStructurallyReadyLocalImageModelId(
    models: List<LocalImageModelRecord>,
    preferredId: String?
): String? = preferredId
    ?.takeIf { id ->
        models.any { model -> model.id == id && model.localImageStructuralReadinessMessage() == null }
    }
    ?: models.firstOrNull { model -> model.localImageStructuralReadinessMessage() == null }?.id

fun LocalImageModelRecord.localImageReadinessLabel(): String {
    val structuralReady = localImageStructuralReadinessMessage() == null
    if (!structuralReady) return "缺少组件"
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.UNKNOWN -> "已安装·待首次运行"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.UNKNOWN -> "已安装·待首次运行"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED -> "NPU 1-step smoke"
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "NPU probe"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED -> "NPU smoke"
            LocalImageVerificationStatus.UNKNOWN -> "NPU 待校验"
            LocalImageVerificationStatus.FAILED -> "NPU 校验失败"
            LocalImageVerificationStatus.PASSED -> "可用"
        }
    }
    return "可用"
}

private fun LocalImageModelRecord.qnnImageBundleReadinessMessage(): String? {
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: File(path).parentFile?.takeIf { it.isDirectory }
        ?: return "QNN image engine requires a complete QNN bundle directory."
    val manifest = when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared -> null
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
        is LocalImageBundleManifestInspection.Invalid ->
            return "QNN image bundle manifest is invalid: ${inspection.message}"
    }
    qnnRequiredBundleRuntimeReadinessMessage(root, manifest?.requiredRuntimeProfile)?.let { return it }
    val requiresControlNet = manifest?.task == "CONTROL_IMAGE" ||
        manifest?.id.orEmpty().contains("controlnet", ignoreCase = true)
    if (manifest != null) {
        when (val contract = manifest.resolveRuntimeComponentContract(root)) {
            is LocalImageRuntimeComponentContract.Invalid ->
                return "QNN image bundle runtime contract is invalid: ${contract.message}"
            is LocalImageRuntimeComponentContract.Ready -> {
                if (contract.missingPaths.isNotEmpty()) {
                    return "QNN image bundle manifest requires missing or empty components: " +
                        contract.missingPaths.joinToString(", ") + "."
                }
                return if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
                    "QNN image bundle is incomplete: controlnet.bin."
                } else {
                    null
                }
            }
            LocalImageRuntimeComponentContract.UndeclaredLegacy -> Unit
        }
    }
    val files = root.walkTopDown().filter { it.isFile }.toList()
    val names = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasAny(vararg tokens: String): Boolean = names.any { name -> tokens.any { it in name } }
    val missing = buildList {
        if (!hasAny("qnn", "context", "unet", "diffusion", "transformer")) add("QNN diffusion/context")
        if (!hasAny("vae", "decoder", "ae")) add("VAE/AE decoder")
        if (!hasAny("text_encoder", "clip", "t5", "tokenizer", "qwen", "llm")) add("text encoder/tokenizer")
        if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
            add("controlnet.bin")
        }
    }
    return if (missing.isEmpty()) null else "QNN image bundle is incomplete: ${missing.joinToString(", ")}."
}

private fun LocalImageModelRecord.mnnDiffusionReadinessMessage(): String? {
    if (isQwenImage21MnnModel()) {
        val primary = File(path)
        return LocalImageBundleContract.inspectQwenImage21Bundle(
            bundleRoot = bundleRoot?.let(::File),
            primaryFile = primary
        ).readinessMessage()
    }
    val effectiveFamily = resolvedMnnFamily()
    return LocalImageBundleContract.inspectMnnBundle(
        bundleRoot = bundleRoot?.let(::File),
        primaryFile = File(path),
        family = effectiveFamily
    ).readinessMessage(effectiveFamily)
}

internal fun LocalImageModelRecord.isQwenImage21MnnModel(): Boolean {
    if (runtime != LocalImageRuntime.MNN_DIFFUSION || family != LocalImageModelFamily.QWEN_IMAGE) return false
    if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) return true
    val roots = listOfNotNull(bundleRoot?.takeIf(String::isNotBlank)?.let(::File), File(path).parentFile)
        .distinctBy { root -> runCatching { root.canonicalPath }.getOrDefault(root.absolutePath) }
    return roots.any { root ->
        runCatching { localImageBundleManifestFromRoot(root) }
            .getOrNull()
            ?.let { manifest ->
                manifest.id == QWEN_IMAGE_21_RECOMMENDATION_ID ||
                    manifest.recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID
            } == true
    }
}

private fun LocalImageModelRecord.resolvedMnnFamily(): LocalImageModelFamily {
    val primaryFile = File(path)
    val manifest = sequenceOf(
        bundleRoot?.takeIf { it.isNotBlank() }?.let(::File),
        primaryFile.parentFile
    )
        .filterNotNull()
        .distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        .mapNotNull { root ->
            runCatching { localImageBundleManifestFromRoot(root) }.getOrNull()
        }
        .firstOrNull()
    return mnnVerificationRoute(family, manifest).family
}

// P5/S48: called by the remaining LocalImageModelStore class body (pure classifier).
internal fun inferLocalImageRuntimeForBundle(root: File, primary: File, discovery: Boolean = false): LocalImageRuntime =
    when {
        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any {
            it.isFile && (
                listOf("qnn", "qairt", "htp").any { token -> token in it.invariantSeparatorsPath.lowercase() } ||
                    it.name.endsWith(".ctx", ignoreCase = true) ||
                    it.name.equals("unet.bin", ignoreCase = true) &&
                        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any { sibling -> sibling.name.contains("clip", true) }
            )
        } -> LocalImageRuntime.QNN_HTP
        primary.extension.equals("mnn", ignoreCase = true) -> LocalImageRuntime.MNN_DIFFUSION
        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any { it.isFile && it.extension.equals("mnn", ignoreCase = true) } -> LocalImageRuntime.MNN_DIFFUSION
        else -> LocalImageRuntime.infer(primary.name)
    }

internal fun resolveSdxlQnnConditioningRoot(bundleRoot: File): File {
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "SDXL QNN bundle directory is missing." }
    if (root.hasCompleteSdxlQnnConditioningAssets()) return root

    val manifestFile = File(root, "manifest.json").takeIf(File::isFile)
        ?: root.findDescendantFile("manifest.json")
    val manifestDirectories = manifestFile
        ?.let { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() }
        ?.optJSONArray("components")
        ?.let { components ->
            buildList {
                for (index in 0 until components.length()) {
                    val path = components.optJSONObject(index)
                        ?.optString("path")
                        ?.takeIf(String::isNotBlank)
                        ?: continue
                    val component = root.safeDescendantOrNull(path) ?: continue
                    if (component.name in SDXL_QNN_CONDITIONING_ASSET_NAMES) {
                        component.parentFile?.let(::add)
                    }
                }
            }
        }
        .orEmpty()

    val candidates = (manifestDirectories + root.walkTopDown()
        .maxDepth(5)
        .filter(File::isDirectory)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .toList())
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter { it.path.startsWith(root.path + File.separator) }
        .distinctBy(File::getPath)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

    return candidates.firstOrNull()
        ?: error(
            "SDXL QNN bundle requires clip.mnn, clip_2.mnn(+weight), tokenizer.json, " +
                "token_emb*.bin and pos_emb*.bin in one component directory."
        )
}

/**
 * Returns whether an imported bundle contains the complete dual-CLIP SDXL
 * conditioning contract in one safe directory. This is intentionally a
 * package-layout check, not a device capability check.
 */
internal fun hasCompleteSdxlQnnConditioningBundle(bundleRoot: File): Boolean =
    runCatching { resolveSdxlQnnConditioningRoot(bundleRoot) }.isSuccess

private fun File.hasCompleteSdxlQnnConditioningAssets(): Boolean {
    val directory = runCatching { canonicalFile }.getOrNull() ?: return false
    return SDXL_QNN_CONDITIONING_ASSET_NAMES.all { name ->
        val asset = runCatching { File(directory, name).canonicalFile }.getOrNull()
        asset != null &&
            asset.parentFile == directory &&
            asset.isFile &&
            asset.length() > 0L
    }
}

private val SDXL_QNN_CONDITIONING_ASSET_NAMES = setOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight",
    "tokenizer.json",
    "token_emb.bin",
    "token_emb_2.bin",
    "pos_emb.bin",
    "pos_emb_2.bin"
)

internal const val SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE = "clip.mnn+clip_2.mnn"

// P5/S48: read by the remaining LocalImageModelStore class body (read-only set).
internal val READINESS_MODEL_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")

private fun LocalImageModelFamily.requiresCompanionComponents(): Boolean =
    when (this) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.WAN -> true
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> false
    }

private fun LocalImageModelFamily.requiredCompanionComponentHint(): String =
    when (this) {
        LocalImageModelFamily.FLUX -> "VAE/AE（如 flux2_ae、ae.sft 或 ae.safetensors）和 Qwen3 4B 文本编码器/LLM"
        LocalImageModelFamily.QWEN_IMAGE -> "Qwen-Image VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.Z_IMAGE -> "VAE/AE 和 Qwen3 文本编码器/LLM"
        LocalImageModelFamily.LONGCAT_IMAGE -> "FLUX VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.SANA -> "connector、projector、transformer、VAE decoder 和 Sana LLM"
        LocalImageModelFamily.SD_TURBO -> "SD-Turbo 完整 checkpoint"
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.WAN -> "VAE/AE 和文本编码器/LLM"
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> "VAE/AE 和文本编码器/LLM"
    }

private fun File.isVaeComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "vae" in lower ||
        lower.endsWith("/ae.sft") ||
        lower.endsWith("/ae.safetensors") ||
        lower.endsWith("_ae.safetensors") ||
        lower.endsWith("-ae.safetensors") ||
        lower.endsWith("_ae.gguf") ||
        lower.endsWith("-ae.gguf")
}

private fun File.isTextEncoderComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "text_encoder" in lower ||
        "text-encoder" in lower ||
        "text_encoders" in lower ||
        "t5xxl" in lower ||
        "t5-xxl" in lower ||
        "umt5" in lower ||
        "qwen2.5" in lower ||
        "qwen3" in lower ||
        "qwen_3" in lower ||
        "qwen-3" in lower ||
        "mistral" in lower ||
        "gemma" in lower ||
        "llm" in lower
}

// P5/S48: called by the remaining LocalImageModelStore class body (pure classifier).
internal fun String.isPrimaryImageModelName(): Boolean {
    val lower = lowercase()
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("gguf", "safetensors", "sft", "ckpt", "pth", "pt", "onnx", "mnn", "bin", "ctx", "qnn") &&
        (
        lower == "unet.mnn" ||
                lower == "unet.ctx" ||
                lower == "unet.qnn" ||
                lower.endsWith("unet.bin") ||
                lower.endsWith("unet.ctx") ||
                lower.endsWith("unet.qnn") ||
                lower.endsWith("diffusion.bin") ||
                lower.endsWith("diffusion.ctx") ||
                lower.endsWith("diffusion.qnn") ||
                lower.endsWith("transformer.bin") ||
                lower.endsWith("transformer.ctx") ||
                lower.endsWith("transformer.qnn") ||
                lower.endsWith("context.bin") ||
                lower.endsWith("context.ctx") ||
                lower.endsWith("context.qnn") ||
                lower == "transformer.mnn" ||
                lower == "diffusion.mnn" ||
                "qnn" in lower ||
            "diffusion" in lower ||
                "z-image" in lower ||
                "z_image" in lower ||
                "qwen-image" in lower ||
                "qwen_image" in lower ||
                "glm-image" in lower ||
                "glm_image" in lower ||
                "longcat-image" in lower ||
                "longcat_image" in lower ||
                "dreamlite" in lower ||
                "flux" in lower ||
                "sd-turbo" in lower ||
                "sd_turbo" in lower
            ) &&
        "vae" !in lower &&
        "clip" !in lower &&
        "text" !in lower &&
        "tokenizer" !in lower &&
        "encoder" !in lower
}
internal const val QWEN_IMAGE_21_RECOMMENDATION_ID = "qwen_image_21_mnn_opencl"
