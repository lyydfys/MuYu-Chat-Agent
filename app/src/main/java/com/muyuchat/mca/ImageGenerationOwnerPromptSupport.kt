package com.muyuchat.mca

import java.io.File
import java.util.UUID

/** R3/C3-S20：Textual Inversion 解析顶层化（自 VM 迁出；体逐字；records 默认值留在 VM 委托上）。 */
internal fun resolveCurrentLocalImageTextualInversionIdsFor(
    ids: List<String>,
    records: List<TextualInversionArtifact>
): List<String> {
    require(ids.size <= TextualInversionContract.MAX_COUNT) {
        "单次最多使用 ${TextualInversionContract.MAX_COUNT} 个 Textual Inversion。"
    }
    val normalizedIds = ids.map { id -> UUID.fromString(id.trim()).toString() }
    require(normalizedIds.distinct().size == normalizedIds.size) {
        "同一个 Textual Inversion 不能重复选择。"
    }
    val availableIds = records.mapTo(mutableSetOf(), TextualInversionArtifact::id)
    normalizedIds.firstOrNull { it !in availableIds }?.let { missingId ->
        throw LocalImageProductContractException(
            "image_textual_inversion_not_found",
            "所选 Textual Inversion 已删除或不可用：$missingId"
        )
    }
    return normalizedIds
}

/** R3/C3-S21：负向提示支持判定顶层化（自 VM 迁出；体逐字，state 参数化）。 */
internal fun chatImageNegativePromptSupportedFor(state: MainUiState): Boolean {
    if (state.selectedImageBackend != ImageBackend.LOCAL) return true
    val model = state.selectedLocalImageModel() ?: return false
    if (model.runtime == LocalImageRuntime.MEDIATEK_NEURON) {
        return model.mediaTekImageProductContractOrNull()?.supportsNegativePrompt == true
    }
    val bundleRoot = model.bundleRoot
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.takeIf(File::isDirectory)
        ?: File(model.path).parentFile?.takeIf(File::isDirectory)
    return runCatching {
        val profile = resolveLocalImageExecutionProfile(
            model = model,
            options = LocalImageGenerationOptions()
                .normalizedForPromptExecutionProfile(model.runtime),
            bundleRoot = bundleRoot
        ).profile
        profile.capabilities.supportsNegativePrompt && profile.defaults.useCfg
    }.getOrElse {
        // Imported/legacy records can be usable before their sidecar is materialized. Keep
        // the same conservative family gate used for default conditioning instead of
        // silently dropping the negative branch for a valid SD/SDXL model.
        chatImageFallbackNegativePromptForModel(model.family, model.runtime) != null
    }
}
