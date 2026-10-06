package com.muyuchat.mca

/**
 * R3/C3-S22：图片工具授权身份四件套自 MainViewModel 顶层化（体逐字；private → internal）。
 *
 * 调用点（VM 成员、tool 端口匿名对象、`chatImageToolPermissionUiState`）经同包顶层解析零改动。
 * `chatImageModelBinding` 内的 `chatImageOptionsForCurrentSelection(state)` 改调既有顶层
 * `chatImageOptionsForCurrentSelectionFor(state)`（VM 成员即其逐字委托，语义相同）。
 */
internal fun chatImageToolChatModelIdentity(
    modelId: String?,
    config: CloudApiConfig?
): String? {
    val normalizedId = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = config?.normalized() ?: return null
    if (!normalized.configured || normalized.apiFormat != CloudApiFormat.OPENAI_RESPONSES ||
        !normalized.supportsTools
    ) return null
    return encodeChatImageAuthorizationIdentity(
        listOf(
            normalizedId,
            normalized.apiFormat.name,
            normalized.baseUrl,
            normalized.chatModel,
            normalized.supportsTools.toString(),
            normalized.responsesReasoningEnabled.toString()
        )
    )
}

internal fun AssistantImageModelBinding.authorizationIdentity(): String =
    encodeChatImageAuthorizationIdentity(
        listOf(backend.name, modelId, fingerprint)
    )

internal fun encodeChatImageAuthorizationIdentity(parts: List<String>): String =
    parts.joinToString(separator = "\u0000") { value -> "${value.length}:$value" }

internal fun MainUiState.chatImageModelBinding(): AssistantImageModelBinding? {
    val state = this
    return when (selectedImageBackend) {
    ImageBackend.LOCAL -> selectedLocalImageModel()?.let { model ->
        AssistantImageModelBinding(
            backend = ImageBackend.LOCAL,
            modelId = model.id,
            modelName = model.displayName,
            options = chatImageOptionsForCurrentSelectionFor(state),
            fingerprint = model.chatImageRetryFingerprint()
        )
    }
    ImageBackend.CLOUD -> selectedImageCloudConfig()?.let { config ->
        val modelId = selectedCloudImageModelId ?: return@let null
        AssistantImageModelBinding(
            backend = ImageBackend.CLOUD,
            modelId = modelId,
            modelName = config.displayName.ifBlank { config.imageModel },
            options = LocalImageGenerationOptions(batchCount = 1),
            fingerprint = config.chatImageRetryFingerprint()
        )
    }
    }
}
