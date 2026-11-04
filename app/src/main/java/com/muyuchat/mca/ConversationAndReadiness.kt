package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity

// Conversation rollback state, assistant completion fallback, and the local image readiness
// projection used by the UI.
//
// Moved here verbatim from MainViewModel during P2, with two exceptions noted below.
//
// pruneLastConversationTurn and ConversationMutationRollbackState are how a failed
// conversation mutation is undone without losing the durable session, and
// withVisibleAssistantCompletionFallback is what stops a generation that returned no visible
// text from looking like an empty reply. The readiness helpers turn a downloaded model into
// the label the UI shows and decide whether an exact Qairt execution has been verified.
//
// Two declarations were widened from private to internal, and only two:
// AttachmentImportResult and PreparedChatInput. Both are small data carriers that the
// ViewModel class body constructs, so a top-level private would not have been visible across
// files. internal keeps them inside this module, and every other declaration here was already
// internal and is unchanged.
//
// Kept in com.muyuchat.mca: no import changes anywhere else, and the tests that call
// withVisibleAssistantCompletionFallback and EMPTY_ASSISTANT_COMPLETION_MESSAGE call them
// directly rather than reading source, so no test changed.
//
// No field, type, default, branch or sentence was edited.

internal data class ConversationMutationRollbackState(
    val messages: List<ChatMessage>,
    val activeChatSessionId: String?,
    val chatSessions: List<ChatSessionRecord>,
    val selectedKnowledgeBaseIds: Set<String>,
    val generationPhase: GenerationPhase?,
    val generationTokenProgress: TokenProgress?,
    val promptContextUsage: PromptContextUsage?
)

/** Removes the latest complete user/assistant turn, or one dangling final user turn. */
internal fun List<ChatMessage>.pruneLastConversationTurn(): ConversationTailPrune? {
    val lastUserIndex = indexOfLast { it.role == Role.USER }
    if (lastUserIndex < 0) return null
    val lastAssistantIndex = indexOfLast { it.role == Role.ASSISTANT }
    val endIndex = if (lastAssistantIndex > lastUserIndex) lastAssistantIndex else lastUserIndex
    val updated = buildList(size - (endIndex - lastUserIndex + 1)) {
        addAll(this@pruneLastConversationTurn.subList(0, lastUserIndex))
        addAll(
            this@pruneLastConversationTurn.subList(
                endIndex + 1,
                this@pruneLastConversationTurn.size
            )
        )
    }
    return ConversationTailPrune(
        messages = updated,
        removedMessageCount = endIndex - lastUserIndex + 1
    )
}

/** Shared with the editor save path so manual prompts cannot exceed persisted card limits. */
internal fun boundedManualAssistantSystemPrompt(value: String): String = value
    .trim()
    .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
    .ifBlank { GenerationParams().systemPrompt }
internal data class AttachmentImportResult(
    val name: String,
    val text: String,
    val truncated: Boolean,
    val imageAsset: ImageAssetRecord? = null,
    val fileAsset: FileAssetRecord? = null
)

internal data class PreparedChatInput(
    val text: String,
    val imageAttachments: List<ChatImageAttachment>,
    /** One-turn structured quote; folded into the request copy only, never into stored text. */
    val quotedMessage: com.muyuchat.feature.chat.ChatQuotedMessage? = null
)

internal const val EMPTY_ASSISTANT_COMPLETION_MESSAGE =
    "本次生成没有返回可见正文。请重试；如果问题持续，请重新加载当前模型。"

internal fun List<ChatMessage>.withVisibleAssistantCompletionFallback(): List<ChatMessage> {
    val index = indexOfLast { it.role == Role.ASSISTANT }
    if (index < 0 || this[index].content.isNotBlank()) return this
    return toMutableList().also { messages ->
        messages[index] = messages[index].copy(content = EMPTY_ASSISTANT_COMPLETION_MESSAGE)
    }
}

/**
 * validateForLoad may refresh a QAIRT manifest fingerprint after inspecting a
 * changed bundle directory.  Always pass that refreshed value into the exact
 * bundle/chipset/runtime admission check; the caller's stale manifest must not
 * inherit a previous bundle's certification.
 */
internal fun currentQairtBundleSha256(
    requested: ModelManifest,
    persistedModels: List<ModelManifest>
): String? {
    if (requested.runtime != ChatModelRuntime.GENIEX_QAIRT) return null
    val current = persistedModels.firstOrNull {
        it.id == requested.id && it.runtime == ChatModelRuntime.GENIEX_QAIRT
    } ?: requested
    return current.sha256.trim().lowercase().takeIf { it.isNotBlank() }
}

/** Verification metadata is diagnostic only; structural readiness is the use gate. */
internal fun LocalImageModelRecord.localImageReadinessForUi(
    qnnVerificationCurrent: Boolean?,
    imageCapabilities: LocalImageUiCapabilitiesSnapshot? = null
): String? = localImageReadinessMessage()
    ?: (imageCapabilities ?: imageCapabilitiesForUi()).readinessError

internal fun LocalImageModelRecord.localImageReadinessLabelForUi(
    qnnVerificationCurrent: Boolean?,
    imageCapabilities: LocalImageUiCapabilitiesSnapshot? = null
): String =
    if ((imageCapabilities ?: imageCapabilitiesForUi()).readinessError != null) {
        "配置错误"
    } else if (runtime == LocalImageRuntime.QNN_HTP && qnnVerificationCurrent != true) {
        "NPU 可直接尝试"
    } else {
        localImageReadinessLabel()
    }

internal fun isExactQairtExecutionVerified(
    identity: QairtBundleRuntimeIdentity?,
    verifiedIdentities: Set<QairtBundleRuntimeIdentity>
): Boolean = identity
    ?.takeIf(QairtBundleRuntimeIdentity::isComplete)
    ?.let { it in verifiedIdentities }
    ?: false
