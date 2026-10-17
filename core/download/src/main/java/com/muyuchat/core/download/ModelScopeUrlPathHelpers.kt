package com.muyuchat.core.download

import java.net.URLEncoder

enum class RemoteModelFileKind {
    CHAT_MODEL,
    LITERT_LM_MODEL,
    VISION_MODEL,
    IMAGE_MODEL,
    IMAGE_VAE,
    IMAGE_TEXT_ENCODER,
    IMAGE_TOKENIZER,
    MNN_COMPONENT,
    PROJECTOR,
    IMATRIX,
    SPECULATIVE,
    SPLIT_PART,
    OTHER
}

fun RemoteModelFile.fileKind(): RemoteModelFileKind {
    val lowerPath = path.lowercase()
    val lowerName = name.lowercase()
    return when {
        bundleRole == ImageEngineBundleComponentRole.DIFFUSION -> RemoteModelFileKind.IMAGE_MODEL
        bundleRole == ImageEngineBundleComponentRole.VAE ||
            bundleRole == ImageEngineBundleComponentRole.VAE_ENCODER -> RemoteModelFileKind.IMAGE_VAE
        bundleRole == ImageEngineBundleComponentRole.TEXT_ENCODER -> RemoteModelFileKind.IMAGE_TEXT_ENCODER
        bundleRole == ImageEngineBundleComponentRole.TOKENIZER -> RemoteModelFileKind.IMAGE_TOKENIZER
        bundleRole == ImageEngineBundleComponentRole.CONFIG ||
            bundleRole == ImageEngineBundleComponentRole.CONDITIONING -> RemoteModelFileKind.MNN_COMPONENT
        mnnBundleRole != null -> RemoteModelFileKind.MNN_COMPONENT
        visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL -> RemoteModelFileKind.VISION_MODEL
        visionBundleRole == VisionModelBundleComponentRole.PROJECTOR -> RemoteModelFileKind.PROJECTOR
        !lowerName.hasModelFileExtension() -> RemoteModelFileKind.OTHER
        lowerName.endsWith(".litertlm") -> RemoteModelFileKind.LITERT_LM_MODEL
        "mmproj" in lowerPath || "projector" in lowerPath -> RemoteModelFileKind.PROJECTOR
        "imatrix" in lowerPath -> RemoteModelFileKind.IMATRIX
        lowerName.startsWith("mtp-") -> RemoteModelFileKind.SPECULATIVE
        Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lowerName) -> RemoteModelFileKind.SPLIT_PART
        looksLikeImageGenerationModel(lowerPath, lowerName) -> RemoteModelFileKind.IMAGE_MODEL
        else -> RemoteModelFileKind.CHAT_MODEL
    }
}

fun RemoteModelFile.isChatModelCandidate(): Boolean =
    fileKind() == RemoteModelFileKind.CHAT_MODEL || fileKind() == RemoteModelFileKind.LITERT_LM_MODEL

fun RemoteModelFile.isLiteRtLmModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.LITERT_LM_MODEL

fun RemoteModelFile.isVisionModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.VISION_MODEL

fun RemoteModelFile.isImageModelCandidate(): Boolean = fileKind() == RemoteModelFileKind.IMAGE_MODEL

fun RemoteModelFile.kindLabel(): String = when (fileKind()) {
    RemoteModelFileKind.CHAT_MODEL -> "聊天主模型"
    RemoteModelFileKind.LITERT_LM_MODEL -> "LiteRT-LM 聊天模型"
    RemoteModelFileKind.VISION_MODEL -> "本地识图主模型"
    RemoteModelFileKind.IMAGE_MODEL -> "图像生成模型"
    RemoteModelFileKind.IMAGE_VAE -> "VAE / AE"
    RemoteModelFileKind.IMAGE_TEXT_ENCODER -> "文本编码器 / LLM"
    RemoteModelFileKind.IMAGE_TOKENIZER -> "Tokenizer"
    RemoteModelFileKind.PROJECTOR -> "视觉投影辅助文件"
    RemoteModelFileKind.IMATRIX -> "量化校准辅助文件"
    RemoteModelFileKind.SPECULATIVE -> "投机解码辅助文件"
    RemoteModelFileKind.SPLIT_PART -> "分片文件"
    RemoteModelFileKind.OTHER -> "其他文件"
    else -> "MNN 模型组件"
}

private fun looksLikeImageGenerationModel(lowerPath: String, lowerName: String): Boolean {
    val value = "$lowerPath/$lowerName"
    return listOf(
        "z-image",
        "z_image",
        "zimage",
        "qwen-image",
        "qwen_image",
        "flux",
        "glm-image",
        "glm_image",
        "longcat-image",
        "longcat_image",
        "dreamlite",
        "dreamshaper",
        "meinamix",
        "majicmix",
        "sdxl",
        "stable-diffusion",
        "stable_diffusion",
        "diffusion",
        "text-to-image",
        "image-generation"
    ).any { it in value } &&
        listOf("clip", "t5", "text_encoder", "vae", "mmproj", "projector").none { it in lowerName }
}

private fun String.hasModelFileExtension(): Boolean =
    endsWith(".gguf") ||
        endsWith(".safetensors") ||
        endsWith(".sft") ||
        endsWith(".ckpt") ||
        endsWith(".pth") ||
        endsWith(".pt") ||
        endsWith(".onnx") ||
        endsWith(".mnn") ||
        endsWith(".litertlm") ||
        endsWith(".tflite") ||
        endsWith(".ctx") ||
        endsWith(".zip")

fun modelScopeModelPageUrl(repoId: String): String =
    "https://www.modelscope.cn/models/${repoId.modelScopePath()}/summary"

fun huggingFaceModelPageUrl(repoId: String): String =
    "https://hf-mirror.com/${repoId.modelScopePath()}"

private fun String.modelScopePath(): String =
    trim('/')
        .split('/')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
