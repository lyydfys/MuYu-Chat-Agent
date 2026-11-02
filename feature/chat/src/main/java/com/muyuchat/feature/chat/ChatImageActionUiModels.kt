package com.muyuchat.feature.chat

import androidx.compose.runtime.Immutable
import com.muyuchat.core.engine.ReasoningMode

/**
 * T2B22-T1（b 工位）拆片：ChatImageActionKind / ChatImageActionDraftUi /
 * AssistantEditorDraft 自 ChatUiItemModels.kt 逐字节平移；零改写。
 */

enum class ChatImageActionKind { AMBIGUOUS, CLOUD_TRANSLATION }

@Immutable
data class ChatImageActionDraftUi(
    val id: String,
    val sourceText: String,
    val kind: ChatImageActionKind,
    val reason: String
)

@Immutable
data class AssistantEditorDraft(
    val id: String?,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val topK: Int = 20,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1.08f,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0.2f,
    val stopWords: List<String> = emptyList(),
    val memorySummaryInterval: Int = 12,
    /** Semantic fields explicitly overridden over the active model preset. */
    val generationOverrideFields: Set<String> = emptySet()
)
