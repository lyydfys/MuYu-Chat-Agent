package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Bridges natural-language chat image requests to the ASCII prompt contract expected by
 * English-dominant image encoders (including Qwen-Image bundles). The bridge deliberately uses
 * the currently selected chat model instead of a heuristic dictionary. Callers may run the
 * bounded fallback contract once; English-only image profiles are never fed known-invalid
 * Chinese text when both passes fail.
 */
internal object ChatImagePromptBridge {
    const val TRANSLATION_TIMEOUT_MS = 30_000L
    const val MAX_OUTPUT_CHARS = 4_096

    /**
     * Conservative quality fallback for an explicit chat-image request with no authored negative
     * branch and no model/profile default. The final handoff still gates this on the selected
     * image topology, so CFG-off models do not receive a negative branch.
     */
    const val DEFAULT_QUALITY_NEGATIVE_PROMPT =
        "lowres, low quality, blurry, bad anatomy, bad hands, extra fingers, extra limbs, " +
            "deformed, text, watermark, logo"

    val SYSTEM_PROMPT = chatImagePromptSystemPromptText

    val FALLBACK_SYSTEM_PROMPT = chatImagePromptFallbackSystemPromptText

    val DETAIL_REPAIR_SYSTEM_PROMPT = chatImagePromptDetailRepairSystemPromptText

/** Immutable, auditable prompt boundary shared by UI, worker and history metadata. */
internal data class PromptEnvelope(
    val original: String,
    val positive: String,
    val negative: String? = null,
    val originalPositive: String = chatImagePromptParseInput(original).positivePrompt,
    val originalNegative: String? = chatImagePromptParseInput(original).negativePrompt,
    val negativeOrigin: ChatImageNegativeOrigin = if (originalNegative.isNullOrBlank())
        ChatImageNegativeOrigin.NONE else ChatImageNegativeOrigin.USER_AUTHORED,
    val protectedSpans: List<ProtectedPromptSpan> = chatImagePromptProtectedSpans(original),
    val protectedSyntax: List<String> = chatImagePromptProtectedTokens(original),
    val loras: List<String> = protectedSyntax.filter { token ->
        Regex("(?i)(?:<)?(?:lora|lyco|lycoris|embedding):").containsMatchIn(token)
    },
    val recovery: ChatImagePromptRecovery? = null,
    val warnings: List<String> = emptyList()
) {
    init {
        require(positive.isNotBlank()) { "Prompt envelope positive text must not be blank." }
        require(loras.all { it in protectedSyntax }) {
            "Prompt envelope LoRA syntax must be a protected token subset."
        }
    }
}

internal fun PromptEnvelope.toAuditJsonString(): String = JSONObject()
    .put("schemaVersion", 1)
    .put("original", original)
    .put("originalPositive", originalPositive)
    .put("originalNegative", originalNegative)
    .put("positive", positive)
    .put("negative", negative)
    .put("negativeOrigin", negativeOrigin.name)
    .put("protectedSpans", org.json.JSONArray().apply {
        protectedSpans.forEach { span ->
            put(JSONObject()
                .put("branch", span.branch.name)
                .put("kind", span.kind.name)
                .put("originalOffset", span.originalOffset)
                .put("rawText", span.rawText)
                .put("digest", span.digest))
        }
    })
    .put("warnings", org.json.JSONArray(warnings))
    .toString()

internal enum class ChatImageNegativeOrigin { NONE, MODEL_DEFAULT, USER_AUTHORED }
internal enum class ProtectedPromptBranch { POSITIVE, NEGATIVE }
internal enum class ProtectedPromptKind { LORA_OR_EMBEDDING, WEIGHT }

internal data class ProtectedPromptSpan(
    val branch: ProtectedPromptBranch,
    val kind: ProtectedPromptKind,
    val originalOffset: Int,
    val rawText: String,
    val digest: String
)

internal enum class ChatImagePromptRecoveryAction { USE_ORIGINAL, TRANSLATE, EDIT, RETRY }

internal data class ChatImagePromptRecovery(
    val originalPositive: String,
    val originalNegative: String?,
    val canUseOriginal: Boolean,
    val actions: List<ChatImagePromptRecoveryAction>
)


internal sealed interface Result {
    data class Prepared(
        val originalPrompt: String,
        val effectivePrompt: String,
        val translated: Boolean,
        /** A negative clause emitted together with a positive prompt, if any. */
        val effectiveNegativePrompt: String? = null,
        val envelope: PromptEnvelope = PromptEnvelope(
            original = originalPrompt,
            positive = effectivePrompt,
            negative = effectiveNegativePrompt
        )
    ) : Result

    data class Failed(
        val code: Code,
        val message: String,
        val recovery: ChatImagePromptRecovery? = null
    ) : Result
}

internal enum class Code {
    INVALID_INPUT,
    TIMEOUT,
    EMPTY_OUTPUT,
    NON_ASCII_OUTPUT,
    UNSAFE_OUTPUT,
    PROTECTED_SYNTAX_LOST,
    DETAIL_LOSS,
    MODEL_ERROR
}

/** Immutable handoff from the chat-model bridge to the selected image backend. */
internal data class ChatImagePromptBridgeHandoff(
    val prompt: String,
    val options: LocalImageGenerationOptions,
    val envelope: PromptEnvelope
)



internal data class ChatImagePromptInputParts(
    val cleanedInput: String,
    val positivePrompt: String,
    val englishPositivePrompt: String?,
    val negativePrompt: String?,
    val hasExplicitSections: Boolean
) {
    /** Builds the complete image brief that the chat model must normalize. */
    fun translationSource(): String {
        val negative = negativePrompt?.trim().orEmpty()
        val positiveParts = listOf(positivePrompt, englishPositivePrompt.orEmpty())
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val positive = positiveParts.joinToString("\n").ifBlank {
            if (hasExplicitSections && negative.isNotBlank()) "" else cleanedInput
        }
        if (positive.isBlank()) return ""
        return buildString {
            append(positive)
            negative.takeIf(String::isNotBlank)?.let {
                append("\nNegative prompt: ")
                append(it)
            }
        }.trim()
    }
}


/** Structured output used by the bridge.  The negative branch is optional for legacy models. */
internal data class NormalizedChatImagePromptResponse(
    val positive: String,
    val negative: String?
)

    // The protection implementation lives in ChatImagePromptProtection.kt; this is the
    // object-level entry point existing callers already use.
    internal fun protectedPromptSpansPreserved(
        original: List<ProtectedPromptSpan>,
        positive: String,
        negative: String?
    ): Boolean = chatImagePromptProtectedSpansPreserved(original, positive, negative)

internal class ChatImagePromptBridgeException(
    val code: Code,
    message: String
) : IllegalStateException(message)

    // Test-facing name kept on the object; the implementation lives in
    // ChatImagePromptBridgeNormalization.kt.
    internal fun normalizeChatImagePromptResponse(raw: String):
        NormalizedChatImagePromptResponse? = chatImagePromptNormalizeResponse(raw)
    // Callers reach the parser through the object; the implementation lives in
    // ChatImagePromptBridgeInputParsing.kt.
    internal fun parseChatImagePromptInput(prompt: String):
        ChatImagePromptInputParts = chatImagePromptParseInput(prompt)
    // Callers use the object form; the implementations live in
    // ChatImagePromptBridgeRequestConstruction.kt.
    internal fun chatImagePromptBridgeRequest(
        prompt: String,
        params: GenerationParams,
        systemPrompt: String = ChatImagePromptBridge.SYSTEM_PROMPT,
        assistantName: String? = null,
        characterContext: String? = null,
        conversationContext: List<ChatMessage> = emptyList(),
        runtimeContext: String? = null,
        protectNumericLiterals: Boolean = true
    ): ChatRequest = chatImagePromptBuildRequest(
        prompt = prompt,
        params = params,
        systemPrompt = systemPrompt,
        assistantName = assistantName,
        characterContext = characterContext,
        conversationContext = conversationContext,
        runtimeContext = runtimeContext,
        protectNumericLiterals = protectNumericLiterals
    )

    internal fun formatChatImagePromptReferenceContext(
        assistantName: String?,
        characterContext: String?,
        conversationContext: List<ChatMessage>,
        runtimeContext: String? = null
    ): String? = chatImagePromptFormatReferenceContext(
        assistantName = assistantName,
        characterContext = characterContext,
        conversationContext = conversationContext,
        runtimeContext = runtimeContext
    )
}

internal typealias PromptEnvelope = ChatImagePromptBridge.PromptEnvelope
internal typealias ChatImagePromptRecovery = ChatImagePromptBridge.ChatImagePromptRecovery
internal typealias ChatImagePromptRecoveryAction = ChatImagePromptBridge.ChatImagePromptRecoveryAction

internal fun parseChatImagePromptInput(prompt: String): ChatImagePromptBridge.ChatImagePromptInputParts =
    ChatImagePromptBridge.parseChatImagePromptInput(prompt)

internal fun chatImagePromptRecovery(original: String, nativeMultilingual: Boolean): ChatImagePromptRecovery =
    ChatImagePromptBridge.chatImagePromptRecovery(original, nativeMultilingual)

internal fun chatImagePromptBridgeHandoff(
    result: ChatImagePromptBridge.Result.Prepared,
    baseOptions: LocalImageGenerationOptions,
    originalNegativePrompt: String?,
    translatedModelNegativePrompt: String?,
    nativeMultilingual: Boolean,
    allowNegativePrompt: Boolean = true
): ChatImagePromptBridge.ChatImagePromptBridgeHandoff = ChatImagePromptBridge.chatImagePromptBridgeHandoff(
    result, baseOptions, originalNegativePrompt, translatedModelNegativePrompt, nativeMultilingual, allowNegativePrompt
)
