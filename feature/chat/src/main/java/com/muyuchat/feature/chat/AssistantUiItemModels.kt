package com.muyuchat.feature.chat

import androidx.compose.runtime.Immutable
import com.muyuchat.core.engine.ReasoningMode

/**
 * T2B22-T1（b 工位）拆片：AssistantUiItem / AssistantMemoryUiItem /
 * ContextSummaryModelOption / ContextSummaryUiItem / ContextSummaryEvidenceUiItem /
 * ChatImageTranslationDraftUi 自 ChatUiItemModels.kt 逐字节平移；零改写。
 */

@Immutable
data class AssistantUiItem(
    val id: String,
    val name: String,
    val avatar: String,
    val tag: String,
    val systemPrompt: String,
    val modelSummary: String,
    val defaultModelMode: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val nPredict: Int,
    val reasoningMode: ReasoningMode,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val selected: Boolean,
    val exportJson: String,
    val topK: Int = 20,
    val minP: Float = 0f,
    val repeatPenalty: Float = 1.08f,
    val presencePenalty: Float = 0f,
    val frequencyPenalty: Float = 0.2f,
    val stopWords: List<String> = emptyList(),
    val memorySummaryInterval: Int = 12,
    val characterCardImported: Boolean = false,
    /** Empty means every semantic generation field follows the active model preset. */
    val generationOverrideFields: Set<String> = emptySet()
)

@Immutable
data class AssistantMemoryUiItem(
    val id: String,
    val assistantId: String,
    val scope: String,
    val content: String,
    val source: String,
    val createdAt: Long
)

@Immutable
data class ContextSummaryModelOption(val id: String, val label: String)

@Immutable
data class ContextSummaryUiItem(
    val version: Int,
    val source: String,
    val text: String,
    val sourceMessageIds: List<String>,
    val active: Boolean,
    val createdAt: Long,
    val coverageLimited: Boolean = false,
    val evidenceCount: Int = 0,
    val omittedExcerptCount: Int = 0,
    val omittedCharacterCount: Int = 0,
    val modelIdentity: String? = null,
    val diagnostic: String? = null
)

@Immutable
data class ContextSummaryEvidenceUiItem(
    val kind: String,
    val text: String,
    val sourceMessageId: String,
    val sourceOffset: Int
)

@Immutable
data class ChatImageTranslationDraftUi(
    val id: String,
    val originalPositive: String,
    val originalNegative: String?,
    val proposedPositive: String,
    val proposedNegative: String?,
    val sourceLabel: String
)
