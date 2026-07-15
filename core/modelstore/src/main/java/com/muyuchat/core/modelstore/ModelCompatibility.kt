package com.muyuchat.core.modelstore

import java.io.File

data class ModelCompatibilityResult(
    val canLoad: Boolean,
    val title: String,
    val details: String,
    val warnings: List<String> = emptyList()
) {
    val message: String
        get() = buildString {
            append(title)
            if (details.isNotBlank()) append("：").append(details)
            if (warnings.isNotEmpty()) append("。提示：").append(warnings.joinToString("；"))
        }
}

object ModelCompatibility {
    fun check(file: File, metadata: GgufMetadata = GgufMetadataReader.read(file)): ModelCompatibilityResult {
        if (!file.exists()) {
            return blocked("模型文件不存在", file.absolutePath)
        }
        if (!file.canRead()) {
            return blocked("模型文件不可读", file.absolutePath)
        }
        if (file.length() <= 0L) {
            return blocked("模型文件为空", file.absolutePath)
        }
        if (!file.name.endsWith(".gguf", ignoreCase = true)) {
            return blocked("不是 GGUF 文件", "请选择 .gguf 后缀的主模型文件")
        }
        if (!metadata.isGguf) {
            return blocked("文件头不是 GGUF", "文件可能是下载错误页、损坏文件或辅助文件")
        }

        val lower = file.name.lowercase()
        val pathLower = file.absolutePath.lowercase()
        when {
            "mmproj" in pathLower || "projector" in pathLower -> {
                return blocked("这是视觉投影辅助文件", "mmproj 不能作为聊天主模型加载，请下载 Q4_K_M/Q5_K_M 主模型")
            }
            "imatrix" in pathLower -> {
                return blocked("这是量化校准辅助文件", "imatrix 不能直接推理，请选择主模型 GGUF")
            }
            lower.startsWith("mtp-") -> {
                return blocked("这是投机解码辅助文件", "MTP 文件不能作为当前聊天主模型加载")
            }
            Regex("""-\d{5}-of-\d{5}\.gguf$""").containsMatchIn(lower) -> {
                return blocked("这是分片 GGUF 的其中一片", "MCA 首版只把单文件 GGUF 作为聊天主模型管理")
            }
        }

        val architecture = metadata.architecture?.lowercase()
        if (architecture in NON_CHAT_ARCHITECTURES) {
            return blocked("这不是聊天生成主模型", "architecture=$architecture 不能作为 decoder-only 聊天模型加载")
        }
        if (metadata.causalAttention == false) {
            return blocked("这不是自回归聊天主模型", "GGUF metadata 声明 attention.causal=false")
        }
        if (metadata.poolingType != null && metadata.poolingType > 0) {
            return blocked(
                "这不是聊天生成主模型",
                "GGUF metadata 声明 pooling_type=${metadata.poolingType}，属于 embedding / reranker / classification 路径"
            )
        }

        val warnings = buildList {
            if (architecture == null) add("没有读到 general.architecture，将按文件名推断")
            if (architecture != null && !isKnownChatArchitecture(architecture)) {
                add("architecture=$architecture 不在内置保守调参列表，将由当前 llama.cpp 原生加载结果最终判定")
            }
            if (metadata.quant == null) add("没有读到量化类型，加载前请确认不是 BF16/F32 超大模型")
            if (file.length() > 8L * GB) add("文件超过 8GB，手机端可能因为内存或温控加载失败")
            if (metadata.quant in setOf("F32", "F16", "BF16")) add("${metadata.quant} 精度文件很大，手机端建议优先 Q4_K_M/Q5_K_M")
            if (metadata.quant.isVeryLowBitQuant()) {
                add("${metadata.quant} 属于极低比特量化，中文、OCR、事实准确性和指令遵循可能明显下降；不要作为高质量默认，优先 Q4_K_M/Q5_K_M")
            }
        }
        return ModelCompatibilityResult(
            canLoad = true,
            title = "预检通过",
            details = "architecture=${architecture ?: "unknown"}, quant=${metadata.quant ?: "unknown"}, size=${formatBytes(file.length())}",
            warnings = warnings
        )
    }

    private fun isKnownChatArchitecture(value: String): Boolean =
        value.startsWith("qwen") ||
            value.startsWith("llama") ||
            value.startsWith("gemma") ||
            value.startsWith("mistral") ||
            value.startsWith("phi")

    private fun blocked(title: String, details: String): ModelCompatibilityResult =
        ModelCompatibilityResult(canLoad = false, title = title, details = details)

    private fun String?.isVeryLowBitQuant(): Boolean {
        val value = this?.uppercase().orEmpty()
        return value.startsWith("IQ1_") || value.startsWith("IQ2_") || value.startsWith("Q2_")
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / GB.toDouble()
        val mb = bytes / MB.toDouble()
        return if (gb >= 1.0) "%.2fGB".format(gb) else "%.1fMB".format(mb)
    }

    private const val MB = 1024L * 1024L
    private const val GB = 1024L * MB
    private val NON_CHAT_ARCHITECTURES = setOf(
        "clip",
        "bert",
        "modern-bert",
        "nomic-bert",
        "nomic-bert-moe",
        "neo-bert",
        "jina-bert-v2",
        "jina-bert-v3",
        "eurobert",
        "gemma-embedding",
        "llama-embed",
        "dream",
        "llada",
        "llada-moe",
        "rnd1",
        "t5",
        "t5encoder",
        "wavtokenizer-dec"
    )
}
