package com.muyuchat.core.modelstore

internal enum class ModelImportKind {
    GGUF,
    LITERT_LM,
    MNN_ZIP,
    MNN_COMPONENT,
    UNKNOWN
}

/**
 * Classifies user-selected model payloads by their actual header before using
 * the display name supplied by an Android document provider. Some providers
 * return aliases without an extension (or with a misleading one), while the
 * model bytes remain valid.
 */
internal fun classifyModelImport(displayName: String?, header: ByteArray): ModelImportKind {
    val normalizedName = displayName.orEmpty().trim()
    return when {
        header.hasLiteRtLmMagic() -> ModelImportKind.LITERT_LM
        header.hasGgufMagic() -> ModelImportKind.GGUF
        header.hasZipMagic() -> ModelImportKind.MNN_ZIP
        normalizedName.isMnnComponentName() -> ModelImportKind.MNN_COMPONENT
        header.size < LITERT_LM_MAGIC_SIZE && normalizedName.endsWith(".litertlm", ignoreCase = true) ->
            ModelImportKind.LITERT_LM
        header.size < MAGIC_SIZE && normalizedName.endsWith(".gguf", ignoreCase = true) -> ModelImportKind.GGUF
        header.size < MAGIC_SIZE && normalizedName.endsWith(".zip", ignoreCase = true) -> ModelImportKind.MNN_ZIP
        else -> ModelImportKind.UNKNOWN
    }
}

internal fun normalizedGgufImportName(displayName: String?): String {
    val candidate = displayName.orEmpty()
        .trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "model" }
    return if (candidate.endsWith(".gguf", ignoreCase = true)) candidate else "$candidate.gguf"
}

internal fun normalizedLiteRtLmImportName(displayName: String?): String {
    val candidate = displayName.orEmpty()
        .trim()
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "model" }
    return if (candidate.endsWith(".litertlm", ignoreCase = true)) candidate else "$candidate.litertlm"
}

private fun ByteArray.hasGgufMagic(): Boolean =
    size >= MAGIC_SIZE &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'G'.code.toByte() &&
        this[2] == 'U'.code.toByte() &&
        this[3] == 'F'.code.toByte()

private fun ByteArray.hasZipMagic(): Boolean =
    size >= MAGIC_SIZE &&
        this[0] == 'P'.code.toByte() &&
        this[1] == 'K'.code.toByte() &&
        ((this[2] == 3.toByte() && this[3] == 4.toByte()) ||
            (this[2] == 5.toByte() && this[3] == 6.toByte()) ||
            (this[2] == 7.toByte() && this[3] == 8.toByte()))

private fun String.isMnnComponentName(): Boolean {
    val value = lowercase()
    return value.endsWith(".mnn") ||
        value.endsWith(".weight") ||
        value == "config.json" ||
        value == "llm_config.json" ||
        value == "llm.mnn.json" ||
        value.startsWith("tokenizer") ||
        value == "embeddings_bf16.bin"
}

private const val MAGIC_SIZE = 4
