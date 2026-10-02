package com.muyuchat.mca

import com.muyuchat.api.local.ImageGenerationProviderException
import kotlinx.coroutines.CancellationException

// Textual inversion library state and embedded character book import.
//
// Moved here verbatim from MainViewModel during P2.
//
// The status and failure sentences here are user-visible, including the ones that explain a
// character book import that succeeded only in part: the card imported and the book did not,
// or the book imported and could not be saved. Each says which half worked, so a partial
// import is never presented as a total failure or a total success. No sentence was edited.
//
// Kept in com.muyuchat.mca; no import changes anywhere.

internal data class InitialTextualInversionLibraryState(
    val records: List<TextualInversionArtifact>,
    val message: String
)

internal suspend fun loadInitialTextualInversionLibraryState(
    store: TextualInversionStore
): InitialTextualInversionLibraryState = try {
    InitialTextualInversionLibraryState(store.load(), "")
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    val detail = error.message?.trim()?.take(240).orEmpty()
        .ifBlank { "清单损坏或文件不可读" }
    InitialTextualInversionLibraryState(
        records = emptyList(),
        message = "Textual Inversion 库读取失败：$detail。原文件已保留，请修复后重试。"
    )
}

internal fun requireTextualInversionPromptTriggers(
    prompt: String,
    negativePrompt: String?,
    ids: List<String>,
    records: List<TextualInversionArtifact>
) {
    if (ids.isEmpty()) return
    val recordsById = records.associateBy(TextualInversionArtifact::id)
    val selected = ids.map { id ->
        recordsById[id] ?: throw LocalImageProductContractException(
            "image_textual_inversion_not_found",
            "所选 Textual Inversion 已删除或不可用：$id"
        )
    }
    val missing = TextualInversionContract.missingPromptTriggers(
        prompts = listOf(prompt, negativePrompt.orEmpty()),
        artifacts = selected
    )
    if (missing.isNotEmpty()) {
        throw LocalImageProductContractException(
            "image_textual_inversion_trigger_missing",
            "所选 Textual Inversion 的触发词未出现在提示词中：${missing.take(3).joinToString()}"
        )
    }
}

internal fun localImageExtensionResolutionFailure(
    error: Exception,
    fallbackCode: String,
    fallbackMessage: String
): ImageGenerationProviderException {
    val code = (error as? LocalImageProductContractException)?.code ?: fallbackCode
    val httpStatus = when {
        code == "image_textual_inversion_trigger_missing" -> 422
        code.endsWith("_not_found") -> 404
        else -> 422
    }
    return ImageGenerationProviderException(
        code = code,
        httpStatus = httpStatus,
        message = error.message ?: fallbackMessage
    )
}

internal data class EmbeddedCharacterBookImportResult(
    val present: Boolean,
    val book: WorldBookRecord? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList()
)

internal fun parseEmbeddedCharacterBook(
    card: CharacterCard,
    assistantId: String
): EmbeddedCharacterBookImportResult {
    val root = card.toJson()
    val data = root.optJSONObject("data") ?: root
    if (!data.has("character_book") || data.isNull("character_book")) {
        return EmbeddedCharacterBookImportResult(present = false)
    }
    val embedded = data.optJSONObject("character_book")
        ?: return EmbeddedCharacterBookImportResult(
            present = true,
            error = "角色卡中的 character_book 不是有效的对象。"
        )
    return runCatching {
        WorldBookCodec.parseDetailed(
            root = embedded,
            scope = WorldBookScope.ASSISTANT,
            assistantId = assistantId,
            fallbackName = "${card.name.ifBlank { "导入角色" }}的世界书"
        )
    }.fold(
        onSuccess = { result ->
            if (result.book != null) {
                EmbeddedCharacterBookImportResult(
                    present = true,
                    book = result.book,
                    warnings = result.warnings
                )
            } else {
                EmbeddedCharacterBookImportResult(
                    present = true,
                    error = result.error ?: "角色卡内置世界书没有可用条目。"
                )
            }
        },
        onFailure = { error ->
            EmbeddedCharacterBookImportResult(
                present = true,
                error = error.message?.trim()?.takeIf(String::isNotBlank)
                    ?: "角色卡内置世界书格式无效。"
            )
        }
    )
}

internal fun embeddedCharacterBookStatusSuffix(
    result: EmbeddedCharacterBookImportResult,
    saveError: String? = null
): String {
    if (!result.present) return ""
    val book = result.book
    val status = when {
        result.error != null -> "内置世界书导入失败：${result.error}（角色卡已成功导入）"
        book == null -> "内置世界书导入失败：没有可用条目（角色卡已成功导入）"
        !saveError.isNullOrBlank() -> "内置世界书保存失败：${saveError.trim().take(240)}（角色卡已成功导入）"
        else -> "内置世界书已导入 ${book.entries.size} 条"
    }
    val warnings = result.warnings.takeIf { book != null && saveError.isNullOrBlank() && it.isNotEmpty() }
        ?.joinToString("；")
    return if (warnings.isNullOrBlank()) "；$status" else "；$status；提示：$warnings"
}
