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
import com.muyuchat.mca.ChatImagePromptBridge.ChatImagePromptBridgeException
import com.muyuchat.mca.ChatImagePromptBridge.Code
import com.muyuchat.mca.ChatImagePromptBridge.ProtectedPromptBranch
import com.muyuchat.mca.ChatImagePromptBridge.Result

/**
 * T2B22-T3（b 工位）拆片：translateChatImagePrompt 自 ChatImagePromptBridge.kt 逐字节
 * 平移为同包顶层扩展（接收者 = 原 object，FQN 调用点不变）；嵌套类型经显式 import 引用；
 * ChatImagePromptBridgeException 由 private 提升为 internal（widen 登记）。
 */

/**
 * Collects a provider stream under a hard timeout and validates the model's output. The Flow is
 * supplied by either the selected cloud connector or the selected local chat runner.
 */
internal suspend fun ChatImagePromptBridge.translateChatImagePrompt(
    prompt: String,
    stream: Flow<GenerateEvent>,
    /** Image skill callers can require a model pass even for an already-English prompt. */
    requireModelSummary: Boolean = false
): Result {
    val original = prompt.trim()
    if (original.isBlank() || original.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
        return Result.Failed(
            Code.INVALID_INPUT,
            "图片描述为空或超过 ${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符上限。"
        )
    }
    val inputParts = chatImagePromptParseInput(original)
    val recovery = chatImagePromptRecovery(original, nativeMultilingual = false)
    fun failed(code: Code, message: String): Result.Failed = Result.Failed(
        code = code,
        message = message,
        recovery = recovery
    )
    // Once the image skill has been selected, the chat model is the canonical prompt
    // normalizer.  Do not let the convenience parser decide which lines are "positive",
    // "negative", English, JSON, Markdown, or metadata before that pass: users may paste any
    // image-prompt dialect and the model must see the complete payload in order to summarize it.
    // The parser remains useful for the legacy direct-prompt path and for conservative fallback
    // preservation of an explicitly authored ASCII negative branch below.
    val translationSource = if (requireModelSummary) original else inputParts.translationSource()
    if (translationSource.isBlank()) {
        return failed(Code.INVALID_INPUT, "没有找到可用于生图的正向描述。")
    }
    if (!requireModelSummary && !shouldBridgeChatImagePrompt(translationSource)) {
        val positive = inputParts.englishPositivePrompt ?: inputParts.positivePrompt
        val negative = inputParts.negativePrompt?.takeUnless(String::containsHanScript)
        if (positive.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS ||
            negative?.length?.let { it > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS } == true
        ) {
            return failed(
                Code.UNSAFE_OUTPUT,
                "图片描述或负面提示词超过 ${LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS} 字符上限。"
            )
        }
        return Result.Prepared(
            originalPrompt = original,
            effectivePrompt = positive,
            translated = false,
            effectiveNegativePrompt = negative
        )
    }

    // Protect control syntax and cardinal/ordinal numbers before the model sees the request.
    // Local chat models frequently translate the prose correctly but silently rewrite a LoRA
    // weight or turn "2 girls" into "a girl".  The protected payload is deterministic and is
    // restored only after the model output has passed the normal wrapper cleanup.
    // Image-skill payloads may be JSON, Markdown, or another structured dialect containing
    // dimensions, seeds, version numbers, and token-count notes. Those metadata numbers are not
    // prompt syntax and must not make a valid model summary fail. Control tokens such as LoRA,
    // embeddings, and explicit weights remain protected in every mode.
    val protectNumericLiterals = !requireModelSummary
    val protected = chatImagePromptProtect(
        translationSource,
        protectNumericLiterals = protectNumericLiterals
    )
    val output = StringBuilder()
    try {
        withTimeout(TRANSLATION_TIMEOUT_MS) {
            stream.collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        if (output.length < MAX_OUTPUT_CHARS * 2) {
                            output.append(event.text)
                        }
                    }
                    is GenerateEvent.Error -> {
                        throw ChatImagePromptBridgeException(Code.MODEL_ERROR, event.message)
                    }
                    is GenerateEvent.Done -> Unit
                    is GenerateEvent.Phase,
                    is GenerateEvent.Persist -> Unit
                }
            }
        }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        return failed(Code.TIMEOUT, "聊天模型翻译图片描述超时，请重试或直接输入英文提示词。")
    } catch (error: CancellationException) {
        throw error
    } catch (error: ChatImagePromptBridgeException) {
        return failed(
            error.code,
            error.message ?: "聊天模型无法完成图片描述转换。请重试或直接输入英文提示词。"
        )
    } catch (error: Throwable) {
        return failed(
            Code.MODEL_ERROR,
            "聊天模型无法完成图片描述转换：${error.message.orEmpty().ifBlank { "未知错误" }}"
        )
    }

    val normalizedParts = chatImagePromptNormalizeResponse(output.toString())
        ?: return failed(Code.EMPTY_OUTPUT, "聊天模型没有返回可用的英文图片描述，请重试。")
    var normalized = normalizedParts.positive
    var normalizedNegative = normalizedParts.negative
    if (normalized.containsHanScript() || normalizedNegative?.containsHanScript() == true) {
        return failed(Code.NON_ASCII_OUTPUT, "聊天模型返回了中文，未直接提交给英文图像模型。请重试。")
    }
    // Every protected marker stands for semantics that cannot be reconstructed safely after
    // translation (counts, ordinals, LoRA/embedding tags, or explicit weights). Require each
    // marker exactly once across the positive and negative branches. This prevents both silent
    // loss and a model duplicating an image count while summarizing the sentence.
    val emitted = normalized + "\n" + normalizedNegative.orEmpty()
    val branchViolations = protected.markerBranches.mapNotNull { (marker, branch) ->
        val expected = if (branch == ProtectedPromptBranch.POSITIVE) normalized else normalizedNegative.orEmpty()
        val other = if (branch == ProtectedPromptBranch.POSITIVE) normalizedNegative.orEmpty() else normalized
        val expectedCount = Regex(Regex.escape(marker)).findAll(expected).count()
        val otherCount = Regex(Regex.escape(marker)).findAll(other).count()
        val semanticNumber = marker.startsWith("MCA_KEEP_NUMBER_") &&
            !protectNumericLiterals && expectedCount == 0 &&
            chatImagePromptSemanticNumberPreserved(protected.replacements.getValue(marker), expected)
        if (marker.startsWith("MCA_KEEP_TOKEN_") &&
            !((expectedCount == 1 || semanticNumber) && otherCount == 0)
        ) protected.replacements.getValue(marker) else null
    }.distinct()
    if (branchViolations.isNotEmpty()) {
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型移动了正负向分支中的 LoRA 或权重标记，未启动生图。请重试。"
        )
    }
    val missingOrDuplicated = protected.replacements.filter { (marker, original) ->
        val markerCount = Regex(Regex.escape(marker)).findAll(emitted).count()
        // Structured image-skill payloads deliberately leave transport metadata numbers
        // visible to the model.  Visual counts in that payload are still replaced with a
        // marker, but a capable model may translate the marker into an English cardinal (for
        // example `两只猫` -> `two cats`) or keep compact diffusion syntax (`1girl`).  Accept
        // that semantic equivalent only when the marker is absent; duplicated markers remain
        // unsafe because they can change the requested image count.
        val semanticEquivalent = markerCount == 0 &&
            !protectNumericLiterals &&
            marker.startsWith("MCA_KEEP_NUMBER_") &&
            chatImagePromptSemanticNumberPreserved(original, emitted)
        markerCount != 1 && !semanticEquivalent
    }.map { it.key }
    if (missingOrDuplicated.isNotEmpty()) {
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型没有完整保留图片数量、序号、LoRA 或权重信息，未启动生图。请重试。"
        )
    }
    val restoredPositive = chatImagePromptRestoreProtectedPart(normalized, protected)
    val restoredNegative = normalizedNegative?.let { chatImagePromptRestoreProtectedPart(it, protected) }
    if (restoredPositive == null || (normalizedNegative != null && restoredNegative == null)) {
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型没有完整保留 LoRA、权重或数量信息，未启动生图。请重试。"
        )
    }
    normalized = restoredPositive
    normalizedNegative = restoredNegative?.takeIf(String::isNotBlank)
    // Marker validation above proves that each placeholder was emitted once, but a model can
    // still echo the authored LoRA/weight text in addition to the marker.  Keep the duplicate
    // guard for authored control tokens while accounting for two benign cases: a semantic count
    // accepted above has no numeric literal to restore, and a numeric token must not match the
    // decimal suffix of an unrelated weight such as `1.2`.
    val semanticFallbackMarkers = protected.replacements.filter { (marker, original) ->
        val markerCount = Regex(Regex.escape(marker)).findAll(emitted).count()
        markerCount == 0 &&
            !protectNumericLiterals &&
            marker.startsWith("MCA_KEEP_NUMBER_") &&
            chatImagePromptSemanticNumberPreserved(original, emitted)
    }.keys
    val expectedAuthoredCounts = protected.replacements
        .filterKeys { it !in semanticFallbackMarkers }
        .values
        .groupingBy { it }
        .eachCount()
    val restoredOutput = normalized + "\n" + normalizedNegative.orEmpty()
    val restoredMismatch = expectedAuthoredCounts.any { (token, expectedCount) ->
        chatImagePromptAuthoredTokenOccurrences(token, restoredOutput) != expectedCount
    }
    if (restoredMismatch) {
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型重复或改写了图片数量、LoRA 或权重信息，未启动生图。请重试。"
        )
    }
    // An image-skill invocation always asks the selected chat model to summarize the complete
    // request.  In particular, do not silently bypass that pass just because the user pasted an
    // "English positive" section: it may contain only a partial translation while the Chinese
    // section carries pose, identity, count, negative clauses, or other constraints.  The
    // non-summary path below remains backwards-compatible for the standalone prompt field.
    // In the image-skill path the model output is always authoritative, even when the input
    // already contains an English section. That section may be incomplete while another part of
    // the same payload carries pose, identity, exclusions, or control syntax. The legacy direct
    // prompt path retains its previous explicit-English preference for compatibility.
    val effectivePositive = if (!requireModelSummary && inputParts.englishPositivePrompt != null) {
        inputParts.englishPositivePrompt
    } else {
        normalized
    }
    // Treat authored placeholders such as `negative_prompt: none` as an explicit opt-out rather
    // than sending the literal word `none` to the image encoder.
    val explicitNegative = inputParts.negativePrompt?.takeUnless(::chatImagePromptIsEmptyNegativePromptPlaceholder)
    val effectiveNegative = when {
        explicitNegative.isNullOrBlank() -> {
            normalizedNegative ?: DEFAULT_QUALITY_NEGATIVE_PROMPT.takeUnless {
                !requireModelSummary || chatImagePromptInputDisablesNegativePrompt(original)
            }
        }
        explicitNegative.containsHanScript() -> {
            val translatedNegative = normalizedNegative
                ?.takeUnless {
                    // The standard branch is only an automatic fallback. It cannot stand in for
                    // a user's authored Chinese exclusions because doing so would silently drop
                    // constraints such as "不要文字、不要水印".
                    it.trim().equals(
                        ChatImagePromptBridge.DEFAULT_QUALITY_NEGATIVE_PROMPT,
                        ignoreCase = true
                    )
                }
                ?: return failed(
                    Code.PROTECTED_SYNTAX_LOST,
                    "聊天模型没有保留负面提示词，未启动生图。请重试。"
                )
            // The original Chinese branch is only source material and must never be sent to an
            // English-only image encoder. Keep any already-ASCII authored tags from a mixed
            // negative section (for example "不要文字, lowres") alongside the model's
            // translation, while filtering the Chinese source clauses.
            mergeChatImageNegativePrompts(
                translatedNegative,
                explicitNegative
                    .let(::splitTopLevelPromptClauses)
                    .map(String::trim)
                    .filter {
                        it.isNotBlank() &&
                            !it.containsHanScript() &&
                            !chatImagePromptIsStructuredMetadataClause(it)
                    }
                    .joinToString(", ")
            )
        }
        requireModelSummary -> {
            // Preserve an ASCII negative section that the model omitted while still allowing the
            // model to add exclusions inferred from natural-language prose.
            mergeChatImageNegativePrompts(normalizedNegative, explicitNegative)
        }
        else -> explicitNegative
    }
    if (effectivePositive.containsHanScript() || effectiveNegative?.containsHanScript() == true) {
        return failed(Code.NON_ASCII_OUTPUT, "生图提示词中仍包含中文，未直接提交给英文图像模型。请重试。")
    }
    if (!effectivePositive.isSafeAsciiDiffusionPrompt()) {
        return failed(Code.UNSAFE_OUTPUT, "聊天模型返回的图片描述包含不支持的字符，未启动生图。请重试。")
    }
    if (effectiveNegative != null && !effectiveNegative.isSafeAsciiDiffusionPrompt()) {
        return failed(Code.UNSAFE_OUTPUT, "聊天模型返回的负面图片描述包含不支持的字符，未启动生图。请重试。")
    }
    if (effectiveNegative != null &&
        effectiveNegative.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS
    ) {
        return failed(
            Code.UNSAFE_OUTPUT,
            "英文负面图片描述过长，未启动生图。请缩短描述后重试。"
        )
    }
    if (effectivePositive.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS) {
        return failed(Code.UNSAFE_OUTPUT, "英文图片描述过长，未启动生图。请缩短描述后重试。")
    }
    val protectedTokens = chatImagePromptProtectedTokens(
        translationSource,
        includeNumericLiterals = protectNumericLiterals
    )
    val missing = protectedTokens.filterNot { token ->
        effectivePositive.contains(token) || effectiveNegative?.contains(token) == true
    }
    if (missing.isNotEmpty()) {
        // A missing protected token means the chat model changed user-authored control syntax or
        // counts. Do not silently append it to a different semantic location: fail closed and let
        // the caller retry the bounded translation pass.
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型没有完整保留 LoRA、权重或数量信息，未启动生图。请重试。"
        )
    }
    val effective = effectivePositive
    if (effective.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS ||
        !effective.isSafeAsciiDiffusionPrompt()
    ) {
        return failed(
            Code.PROTECTED_SYNTAX_LOST,
            "聊天模型没有完整保留 LoRA 或权重标记，未启动生图。请重试或直接输入英文提示词。"
        )
    }
    return Result.Prepared(
        originalPrompt = original,
        effectivePrompt = effective,
        translated = requireModelSummary || shouldBridgeChatImagePrompt(translationSource),
        effectiveNegativePrompt = effectiveNegative
    )
}
