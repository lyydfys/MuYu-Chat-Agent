package com.muyuchat.mca.presentation

import com.muyuchat.feature.settings.WebSearchBackupProviderDraft
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.WebSearchBackupProviderConfig
import com.muyuchat.mca.WebSearchProviderType
import com.muyuchat.mca.WebSearchTriggerMode
import com.muyuchat.mca.WebSearchTurnMode

// Web search mapping for the UI, moved verbatim from MainActivity during P2.
// No label, fallback or ordering was edited.

internal fun List<WebSearchBackupProviderDraft>.toWebSearchBackupConfigs(): List<WebSearchBackupProviderConfig> =
    take(3).map { draft ->
        WebSearchBackupProviderConfig(
            enabled = draft.enabled,
            provider = WebSearchProviderType.from(draft.provider),
            endpoint = draft.endpoint.trim(),
            apiKey = draft.apiKey.trim()
        )
    }

internal fun MainUiState.toWebSearchTurnModeLabel(): String {
    if (!webSearchConfig.enabled) return "未启用"
    val assistantDefault = assistants.firstOrNull { it.id == selectedAssistantId }?.webSearchEnabled == true
    return when (webSearchTurnMode) {
        WebSearchTurnMode.ON -> "本轮开启"
        WebSearchTurnMode.OFF -> "本轮关闭"
        WebSearchTurnMode.FOLLOW -> when {
            assistantDefault -> "助手默认"
            webSearchConfig.triggerMode == WebSearchTriggerMode.ALWAYS -> "始终"
            webSearchConfig.triggerMode == WebSearchTriggerMode.SMART -> "智能"
            else -> "手动"
        }
    }

}
