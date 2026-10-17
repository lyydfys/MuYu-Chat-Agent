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
import com.muyuchat.mca.ChatImagePromptBridge.ChatImagePromptBridgeHandoff

/**
 * T2B22-T3（b 工位）拆片：chatImagePromptRecovery / chatImagePromptBridgeHandoff /
 * shouldBridge* 三谓词 / chatImagePromptNeedsDetailRepair 自 ChatImagePromptBridge.kt
 * 逐字节平移为同包顶层扩展（接收者 = 原 object，FQN 调用点不变）。
 */

/** A failed model pass never implies that raw text is safe for the chosen encoder. */
internal fun ChatImagePromptBridge.chatImagePromptRecovery(
    original: String,
    nativeMultilingual: Boolean
): ChatImagePromptRecovery {
    val parts = chatImagePromptParseInput(original)
    val positive = parts.positivePrompt.trim()
    val negative = parts.negativePrompt?.trim()?.takeIf(String::isNotBlank)
    val structuredWrapper = original.trimStart().let {
        it.startsWith('{') || it.startsWith('[') || it.startsWith("```")
    }
    val fits = positive.isNotBlank() &&
        positive.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
        (negative?.length ?: 0) <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    val syntaxCompatible = positive.isSafeAsciiDiffusionPrompt() &&
        (negative == null || negative.isSafeAsciiDiffusionPrompt())
    val languageCompatible = nativeMultilingual ||
        (!positive.containsHanScript() && negative?.containsHanScript() != true)
    val canUseOriginal = fits && syntaxCompatible && languageCompatible && !structuredWrapper
    return ChatImagePromptRecovery(
        originalPositive = positive,
        originalNegative = negative,
        canUseOriginal = canUseOriginal,
        actions = buildList {
            if (canUseOriginal) add(ChatImagePromptRecoveryAction.USE_ORIGINAL)
            if (!languageCompatible) add(ChatImagePromptRecoveryAction.TRANSLATE)
            add(ChatImagePromptRecoveryAction.EDIT)
            add(ChatImagePromptRecoveryAction.RETRY)
        }
    )
}

/**
 * Applies the bridge output to the actual image-job inputs. Keeping this boundary pure makes it
 * testable that translated positive and negative text, rather than the original Chinese draft,
 * is what reaches the image runner.
 */
internal fun ChatImagePromptBridge.chatImagePromptBridgeHandoff(
    result: ChatImagePromptBridge.Result.Prepared,
    baseOptions: LocalImageGenerationOptions,
    originalNegativePrompt: String?,
    translatedModelNegativePrompt: String?,
    nativeMultilingual: Boolean,
    allowNegativePrompt: Boolean = true
): ChatImagePromptBridgeHandoff {
    if (!allowNegativePrompt) {
        val authoredNegative = chatImagePromptParseInput(result.originalPrompt).negativePrompt
            ?.takeUnless(::chatImagePromptIsEmptyNegativePromptPlaceholder)
        require(authoredNegative.isNullOrBlank()) {
            "The selected image backend has no negative branch; an authored negative prompt requires an explicit edit or omit decision."
        }
        return ChatImagePromptBridgeHandoff(
            prompt = result.effectivePrompt,
            options = baseOptions.copy(negativePrompt = null),
            envelope = PromptEnvelope(
                original = result.originalPrompt,
                positive = result.effectivePrompt,
                negative = null
            )
        )
    }
    val effectiveModelNegative = effectiveChatImageNegativePrompt(
        original = originalNegativePrompt,
        translated = translatedModelNegativePrompt,
        nativeMultilingual = nativeMultilingual
    )?.takeUnless(::chatImagePromptIsEmptyNegativePromptPlaceholder)
    // The bridge's generic quality branch is only a fallback. Once a resolved image profile has
    // supplied a model-specific default, keep that profile value authoritative instead of sending
    // two near-duplicate negative lists to the text encoder.
    val bridgeNegative = result.effectiveNegativePrompt?.takeUnless {
        effectiveModelNegative?.isNotBlank() == true &&
            it.trim().equals(ChatImagePromptBridge.DEFAULT_QUALITY_NEGATIVE_PROMPT, ignoreCase = true)
    }
    val mergedNegative = mergeChatImageNegativePrompts(
        effectiveModelNegative,
        bridgeNegative
    )
    val effectiveNegative = mergedNegative ?: effectiveChatImageNegativePrompt(
        original = originalNegativePrompt,
        translated = translatedModelNegativePrompt,
        nativeMultilingual = nativeMultilingual
    )
    return ChatImagePromptBridgeHandoff(
        prompt = result.effectivePrompt,
        options = baseOptions.copy(negativePrompt = effectiveNegative),
        envelope = PromptEnvelope(
            original = result.originalPrompt,
            positive = result.effectivePrompt,
            negative = effectiveNegative
        )
    )
}

/** True when the bridge should be consulted for a prompt. ASCII prompts are passed unchanged. */
internal fun ChatImagePromptBridge.shouldBridgeChatImagePrompt(prompt: String): Boolean =
    prompt.containsHanScript()

internal fun ChatImagePromptBridge.shouldNormalizeChatImagePromptInput(prompt: String): Boolean =
    chatImagePromptParseInput(prompt).let { parts ->
        parts.hasExplicitSections || parts.cleanedInput != prompt.trim()
    }

internal fun ChatImagePromptBridge.shouldTranslateChatImagePrompt(prompt: String): Boolean =
    shouldBridgeChatImagePrompt(chatImagePromptParseInput(prompt).translationSource())

/**
 * Detects the failure shown by the old chat-image flow: a multi-clause Chinese request is
 * accepted after the chat model returns a one-line generic caption.  This is deliberately a
 * retry signal, not a language heuristic or a device/model admission rule.
 */
internal fun ChatImagePromptBridge.chatImagePromptNeedsDetailRepair(
    originalPrompt: String,
    prepared: ChatImagePromptBridge.Result.Prepared
): Boolean {
    val source = originalPrompt.trim()
    if (source.length < 24) return false
    val hasChinese = source.containsHanScript()
    val hasStructuredSections = chatImagePromptParseInput(source).hasExplicitSections
    val sourceClauses = source
        .split(',', '，', '、', ';', '；', '\n')
        .map(String::trim)
        .count(String::isNotBlank)
    if (!hasChinese && !hasStructuredSections && sourceClauses < 3) return false

    val output = prepared.effectivePrompt.trim()
    val outputClauses = output
        .split(',', ';')
        .map(String::trim)
        .count(String::isNotBlank)
    val genericCaption = Regex(
        "(?i)\\b(?:a|an)\\s+(?:beautiful|sexy|attractive|pretty|cute|handsome)\\s+" +
            "(?:woman|girl|man|person|character)\\b"
    ).containsMatchIn(output) && outputClauses < 5 && output.length < 240
    // Conversational Chinese often has no comma between visual clauses (for example, "让角色
    // 穿红裙在海边自拍并微笑"). Count common visual cue words so that a long but generic model
    // answer still receives the detail-repair pass instead of being accepted as complete.
    val visualCueCount = Regex(
        "(?:穿|着|发|眼|脸|手持|伞|站|坐|躺|自拍|看|街|窗|海|雨|夜|白天|红|蓝|绿|" +
            "低机位|高机位|半身|特写|侧光|构图|镜头|姿势|表情|背景|风格|长发|短发)"
    ).findAll(source).count()
    val denseChineseBrief = hasChinese && visualCueCount >= 3
    val expectedClauses = when {
        sourceClauses >= 6 -> 5
        sourceClauses >= 4 -> 4
        else -> 3
    }
    val expectedLength = when {
        source.length >= 120 -> 144
        source.length >= 70 -> 100
        else -> 64
    }
    return genericCaption || outputClauses < expectedClauses ||
        (hasChinese && sourceClauses >= 3 && output.length < expectedLength) ||
        (denseChineseBrief && outputClauses < 5 && output.length < maxOf(96, source.length / 2))
}
