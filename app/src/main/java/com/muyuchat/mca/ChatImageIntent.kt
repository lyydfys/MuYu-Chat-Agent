package com.muyuchat.mca

/**
 * A request that should enter the chat-image pipeline.
 *
 * The parser is intentionally only used for the automatic composer route.  Once the user has
 * selected the image skill (for example by typing `/image` or pressing the image action), the
 * returned text is opaque input for [ChatImagePromptBridge]: it is never rejected just because it
 * contains a structured prompt, a negative prompt, LoRA syntax, JSON, or a non-image noun.
 */
internal data class ChatImageIntent(
    val prompt: String,
    /** Full payload that the prompt bridge should summarize, including sections and role context. */
    val sourceText: String = prompt
)

/**
 * Routing decision for the natural-language composer path.  Explicit slash commands still
 * bypass this classifier and are treated as a selected image skill by the caller.
 */
internal enum class ChatImageIntentRoute {
    GENERATE,
    CHAT,
    AMBIGUOUS
}

/** Only same-session evidence may resolve an elliptical follow-up such as "again, two". */
internal data class ChatImageIntentContext(
    val sessionId: String? = null,
    val conversationRevision: String? = null,
    val lastImageRequestId: String? = null,
    val lastImageSessionId: String? = null,
    val recentText: List<String> = emptyList(),
    val characterName: String? = null,
    val imageSkillSelected: Boolean = false
) {
    val sameSessionImageRequestId: String?
        get() = lastImageRequestId?.takeIf {
            it.isNotBlank() && !sessionId.isNullOrBlank() && sessionId == lastImageSessionId
        }
}

internal data class ChatImageIntentDecision(
    val route: ChatImageIntentRoute,
    val sourceText: String,
    val prompt: String? = null,
    val reason: String,
    val requestedOutputCount: Int? = null,
    val visualSubjectCount: Int? = null,
    val referenceImageRequestId: String? = null
) {
    val intent: ChatImageIntent?
        get() {
            if (route != ChatImageIntentRoute.GENERATE) return null
            val value = prompt?.takeIf(String::isNotBlank) ?: return null
            return ChatImageIntent(prompt = value, sourceText = sourceText)
        }
}

/*
 * Automatic intent detection avoids stealing ordinary text requests such as “生成一段代码”,
 * while the explicit image skill remains completely opaque.  The natural-language set is broad
 * enough for role-play actions and subject-first requests; the selected chat model performs the
 * actual prompt normalization after routing.
 */
private val explicitImageSlashCommand = Regex(
    """(?is)^/(?:image|img|draw|txt2img|生图|画图)(?:\s+|[:：,，]\s*)(.+)$"""
)

private val explicitImageCommandPrefix = Regex(
    """(?i)^/(?:image|img|draw|txt2img|生图|画图)(?:$|\s|[:：,，])"""
)

internal fun hasExplicitChatImageCommand(input: String): Boolean =
    explicitImageCommandPrefix.containsMatchIn(input.trimStart())

private val explicitImageGenerateCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我)\s*)?(?:(?:我想(?:要)?|我需要|想要)\s*)?(?:生成|制作|创作)\s*(?:一张|一幅|一组|一个)?\s*(?:图片|图像|插画|海报|头像|壁纸|图)\s*(?:[:：,，]\s*|\s+)(.+)$"""
)

/** Handles natural subject-first requests such as “帮我生成一张美女图片”. */
private val explicitImageSubjectGenerateCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我)\s*)?(?:(?:我想(?:要)?|我需要|想要)\s*)?(?:生成|制作|创作)\s*(?:(?:一张|一幅|一组|一个|一只|张|幅|个|只)\s*)?(.+?)(?:图片|图像|插画|海报|头像|壁纸|照片|图)(?:(?:\s*[,，:：]\s*|\s+)(.+))?$"""
)

private val explicitImageProduceCommand = Regex(
    """(?is)^(?:请\s*)?(?:(?:帮我|替我)\s*)?(?:(?:我想|我需要)\s*)?生图\s*(?:[:：,，]\s*|\s+)(.+)$"""
)

private val explicitImageSelfieCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我|让角色)\s*)?(?:给角色\s*)?(?:拍|来|生成|制作)\s*(?:一张|一幅|张|幅)?\s*(自拍|角色自拍)(?:图片|图像|照片|图)?(?:\s*[:：,，]\s*|\s+)?(.*)$"""
)

private val explicitImageDrawClassifierCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我)\s*)?(?:(?:我想(?:要)?|我需要|想要)\s*)?(?:画|绘制)\s*(?:一张|一幅|一个|一只|张|个|出)\s*(?:(?:图片|图像|插画|海报|头像|壁纸|图)\s*)?(?:[:：,，]\s*)?(.+)$"""
)

private val explicitImageDrawSpacedCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我)\s*)?(?:(?:我想(?:要)?|我需要|想要)\s*)?(?:画|绘制)\s+(.+)$"""
)

private val explicitImageCreateOneCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦)\s*)?(?:(?:帮我|替我|给我)\s*)?(?:来|做)\s*(?:一张|一幅|一个|一只)\s*(?:图片|图像|插画|海报|头像|壁纸|图)?\s*(?:[:：,，]\s*|\s+|(?=[一-龥A-Za-z0-9]))(.+)$"""
)

/**
 * Handles subject-first requests where the user omits the word “图片”, e.g. “帮我生成一张美女”
 * or “换个姿势再拍一张”.  The image-specific action is enough to route the request; the selected
 * chat model will turn the complete text into the final positive/negative prompt.
 */
private val naturalImageActionCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦|拜托)\s*)?(?:(?:帮我|替我|给我|为我)\s*)?(?:(?:我想(?:要)?|我需要|想要)\s*)?(?:生成|制作|创作|绘制|画|画出|生图|出图|做图)\s*(?:(?:一张|一幅|一组|一个|一只|张|幅|个|只)\s*)?(?:(?:图片|图像|插画|海报|头像|壁纸|照片|图)\s*(?:[:：,，]\s*|\s+))?(.+)$"""
)

private val naturalImagePhotoCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦|拜托)\s*)?(?:(?:让|给|替)\s*(?:我|他|她|角色|人物|自己)?\s*)?(?:拍|照|来)\s*(?:(?:一张|一幅|张|幅)\s*)?(?:(自拍|照片|相片|人像|图|图片))(?:\s*[:：,，]\s*|\s+)?(.*)$"""
)

private val naturalImageReshootCommand = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦|拜托)\s*)?(?:(?:让|给|替)\s*(?:我|他|她|角色|人物|自己)?\s*)?(?:(?:换个|换一个|再来一个|改成)\s*(?:姿势|角度|构图|表情|动作)\s*)?(?:(?:再|重新|继续)\s*)?(?:拍|照)\s*(?:一张|一幅|张|幅)?(?:\s*[:：,，]\s*|\s+)?(.+)?$"""
)

private val naturalImageEnglishCommand = Regex(
    """(?is)^(?:(?:please|can you|could you|would you)\s+)?(?:generate|create|make|draw|paint|render)\s+(?:(?:an?|the)\s+)?(?:image|picture|illustration|photo|portrait|wallpaper|poster|art)\b(?:\s+of)?\s*(?::|,|-)?\s*(.+)$"""
)

/** Structured diffusion prompt pasted without an action verb. */
private val structuredImagePrompt = Regex(
    """(?is)(?:^|[\n,;，；])\s*(?:中文正向|英文正向|正向|positive(?:\s+prompt)?|负向|负面提示词|negative(?:\s+prompt)?)\s*[:：]?\s*\S+"""
)

private val imagePromptQuestionPrefix = Regex(
    """(?is)^(?:怎么|如何|为什么|是否|能否|可以不可以|请解释|解释一下|分析一下|翻译一下|总结一下)"""
)

private val imagePromptNegativePrefix = Regex(
    """(?is)^(?:不要|别|无需|不需要|拒绝|禁止)\s*(?:生成|制作|创作|绘制|画|生图|出图)"""
)

private val negatedActionCorrection = Regex(
    """(?is)^(?:不要|别|无需|不需要|拒绝|禁止).+?[，,；;。]\s*(?:但|但是|而是)?\s*(?:改画|改绘制|改生成|改成画|请画|请生成|画|绘制|生成)\s*(.+)$"""
)

private val nonImageOutputNoun = Regex(
    """(?is)^(?:(?:一|两|二|三|四|五|六|七|八|九|十|\d+)\s*(?:段|个|篇|首|份|张)?\s*)?(?:代码|程序|脚本|函数|算法|九九乘法表|乘法表|表格|公式|作文|故事|句子|文字|翻译|解释|总结|清单|列表|视频|音频|音乐|录音|语音|声音|文案|字幕|json|markdown)(?:\b|[，,。\s]|$)"""
)

private val quotedOrConditionalImageDiscussion = Regex(
    """(?is)^(?:如果|假如|假设|当|关于|讨论|分析|解释|翻译|总结|教我|告诉我|["“'`]).*"""
)

private val followUpImageAction = Regex(
    """(?is)^(?:再来|再画|再生成|继续画|继续生成|换个姿势|换一个姿势|换个角度|换一个角度)\s*(?:([1-8]|一|两|二|三|四|五|六|七|八)\s*张)?\s*(?:[,，]\s*每张\s*(?:[1-9]\d*|一|两|二|三|四|五|六|七|八|九|十)\s*(?:个|位|名|只)?\s*(?:人|女孩|男孩|人物|猫|狗|主体))?\s*[。.!！]?\s*$"""
)

private val outputCountPattern = Regex(
    """(?:生成|制作|创作|绘制|画|来|出图)\s*(?:一组\s*)?([1-9]\d*|一|两|二|三|四|五|六|七|八|九|十)\s*张"""
)

private val visualSubjectCountPattern = Regex(
    """每张\s*([1-9]\d*|一|两|二|三|四|五|六|七|八|九|十)\s*(?:个|位|名|只)?\s*(?:人|女孩|男孩|人物|猫|狗|主体)"""
)

private fun imageCount(value: String?): Int? = when (value) {
    "一" -> 1
    "二", "两" -> 2
    "三" -> 3
    "四" -> 4
    "五" -> 5
    "六" -> 6
    "七" -> 7
    "八" -> 8
    "九" -> 9
    "十" -> 10
    else -> value?.toIntOrNull()
}

private fun cleanImagePromptCandidate(value: String?): String? = value
    ?.replace(Regex("""^[\s:：,，、-]+|[\s。！？!?]+$"""), "")
    ?.trim()
    ?.takeIf(String::isNotBlank)

private val IMAGE_ACTION_WITHOUT_SUBJECT = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦|拜托)\s*)?(?:(?:帮我|替我|给我|让角色)\s*)?(?:(?:再|重新|继续)\s*)?(?:拍|照|来|生成|制作|创作|画|绘制|生图|出图)\s*(?:一张|一幅|一组|一个|一只|张|幅|个|只)?\s*$"""
)

// Keep bare camera/image actions out of the automatic route.  They do not contain a
// subject for the chat model to summarize and otherwise become a confusing "empty"
// image request (for example, "拍一张" or "再来一幅").  This guard is deliberately
// applied after command extraction so explicit /image payloads remain opaque.
private val IMAGE_ACTION_WITHOUT_SUBJECT_NORMALIZED = Regex(
    """(?is)^(?:(?:请(?:你)?|能不能|可以|麻烦|拜托)\s*)?(?:(?:帮我|替我|给我|让角色|为我)\s*)?(?:(?:再|重新|继续)\s*)?(?:拍|照|来|生成|制作|创作|画|绘制|生图|出图)\s*(?:一张|一幅|一组|一个|一只|张|幅|个|只)?(?:图片|图像|插画|照片|图片)?\s*$"""
)

private fun shouldKeepAutomaticImageIntent(prompt: String): Boolean {
    val candidate = prompt.trim()
    if (candidate.isBlank() || imagePromptQuestionPrefix.containsMatchIn(candidate) ||
        imagePromptNegativePrefix.containsMatchIn(candidate)
    ) return false
    return true
}

private fun parseImageIntentInternal(input: String): ChatImageIntent? {
    val value = input.trim()
    if (value.isBlank()) return null
    val explicitSlashPrompt = explicitImageSlashCommand.matchEntire(value)?.groupValues?.getOrNull(1)
    if (explicitSlashPrompt == null && quotedOrConditionalImageDiscussion.containsMatchIn(value)) return null
    // A slash command is an explicit skill selection.  Do not apply the automatic intent safety
    // filter to its payload: JSON, negative prompts, LoRA tags, or prose are all valid model input.
    val prompt = explicitSlashPrompt?.let(::cleanImagePromptCandidate) ?: sequenceOf(
        explicitImageGenerateCommand,
        explicitImageSubjectGenerateCommand,
        explicitImageProduceCommand,
        explicitImageSelfieCommand,
        explicitImageDrawClassifierCommand,
        explicitImageDrawSpacedCommand,
        explicitImageCreateOneCommand,
        naturalImageActionCommand,
        naturalImagePhotoCommand,
        naturalImageReshootCommand,
        naturalImageEnglishCommand
    ).mapNotNull { pattern ->
        pattern.matchEntire(value)?.let { match ->
            if (pattern === explicitImageSubjectGenerateCommand || pattern === explicitImageSelfieCommand ||
                pattern === naturalImagePhotoCommand
            ) {
                listOfNotNull(
                    match.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank),
                    match.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
                ).joinToString(", ")
            } else if (pattern === naturalImageReshootCommand) {
                // Keep the complete role-play instruction so the bridge can resolve “换个姿势”
                // against the conversation and character context.
                value
            } else {
                match.groupValues.getOrNull(1)
            }
        }
    }
        .firstOrNull()
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: cleanImagePromptCandidate(
            value.takeIf { structuredImagePrompt.containsMatchIn(it) }
        )
        ?: return null
    if (IMAGE_ACTION_WITHOUT_SUBJECT.matches(prompt) ||
        IMAGE_ACTION_WITHOUT_SUBJECT_NORMALIZED.matches(prompt) ||
        Regex("""(?is)^(?:一张|一幅|一组|一个|一只|张|个|图片|图像|插画|海报|头像|壁纸|照片|图)\s*[:：,，]?\s*$""").matches(prompt) ||
        prompt.all { it.isWhitespace() || it in "：:,，。！？!?" }) {
        return null
    }
    // Avoid stealing ordinary text/code requests.  A slash command bypasses this guard because it
    // is an explicit skill selection; all other forms must look like an action, not a discussion.
    if (explicitSlashPrompt == null &&
        (!shouldKeepAutomaticImageIntent(value) || nonImageOutputNoun.containsMatchIn(prompt))
    ) {
        return null
    }
    val sourceText = if (explicitSlashPrompt != null) {
        // Strip only the command token. Everything after it is model input, including JSON,
        // negative sections, LoRA tags, and line breaks.
        explicitSlashPrompt.trim()
    } else {
        // For natural language actions retain the complete utterance. The bridge needs action
        // context (“换个姿势再拍一张”) and all positive/negative sections, not just the extracted
        // subject noun.
        value
    }
    return ChatImageIntent(prompt, sourceText)
}

/**
 * Classifies one composer submission without invoking a model.  Discussion, negation, and code
 * requests remain ordinary chat; an image action with no subject is surfaced as an explicit
 * choice so the UI can keep the draft and ask for clarification instead of silently doing both
 * a chat turn and an image job.
 */
internal fun classifyChatImageIntent(
    input: String,
    context: ChatImageIntentContext = ChatImageIntentContext()
): ChatImageIntentDecision {
    val value = input.trim()
    if (value.isBlank()) {
        return ChatImageIntentDecision(
            route = ChatImageIntentRoute.CHAT,
            sourceText = input,
            reason = "blank_input"
        )
    }
    if (context.imageSkillSelected && !hasExplicitChatImageCommand(value)) {
        return ChatImageIntentDecision(
            route = ChatImageIntentRoute.GENERATE,
            sourceText = value,
            prompt = value,
            reason = "selected_image_skill",
            requestedOutputCount = imageCount(outputCountPattern.find(value)?.groupValues?.get(1)),
            visualSubjectCount = imageCount(visualSubjectCountPattern.find(value)?.groupValues?.get(1)),
            referenceImageRequestId = context.sameSessionImageRequestId
        )
    }
    negatedActionCorrection.matchEntire(value)?.let { correction ->
        val corrected = parseImageIntentInternal("画 " + correction.groupValues[1])
        if (corrected != null) {
            return ChatImageIntentDecision(
                route = ChatImageIntentRoute.GENERATE,
                sourceText = value,
                prompt = correction.groupValues[1].trim(),
                reason = "corrected_image_action",
                requestedOutputCount = imageCount(outputCountPattern.find(value)?.groupValues?.get(1)),
                visualSubjectCount = imageCount(visualSubjectCountPattern.find(value)?.groupValues?.get(1))
            )
        }
    }
    if (followUpImageAction.matches(value)) {
        val referenced = context.sameSessionImageRequestId
        return ChatImageIntentDecision(
            route = if (referenced == null) ChatImageIntentRoute.AMBIGUOUS else ChatImageIntentRoute.GENERATE,
            sourceText = value,
            prompt = value.takeIf { referenced != null },
            reason = if (referenced == null) "image_follow_up_needs_reference" else "same_session_image_follow_up",
            requestedOutputCount = imageCount(followUpImageAction.matchEntire(value)?.groupValues?.get(1)),
            visualSubjectCount = imageCount(visualSubjectCountPattern.find(value)?.groupValues?.get(1)),
            referenceImageRequestId = referenced
        )
    }
    parseImageIntentInternal(value)?.let { intent ->
        return ChatImageIntentDecision(
            route = ChatImageIntentRoute.GENERATE,
            sourceText = intent.sourceText,
            prompt = intent.prompt,
            reason = if (hasExplicitChatImageCommand(value)) "explicit_image_skill" else "image_action",
            requestedOutputCount = imageCount(outputCountPattern.find(value)?.groupValues?.get(1)),
            visualSubjectCount = imageCount(visualSubjectCountPattern.find(value)?.groupValues?.get(1))
        )
    }
    if (hasExplicitChatImageCommand(value)) {
        return ChatImageIntentDecision(
            route = ChatImageIntentRoute.AMBIGUOUS,
            sourceText = input,
            reason = "explicit_image_skill_missing_prompt"
        )
    }
    if (IMAGE_ACTION_WITHOUT_SUBJECT.matches(value) ||
        IMAGE_ACTION_WITHOUT_SUBJECT_NORMALIZED.matches(value)
    ) {
        return ChatImageIntentDecision(
            route = ChatImageIntentRoute.AMBIGUOUS,
            sourceText = input,
            reason = "image_action_missing_subject"
        )
    }
    return ChatImageIntentDecision(
        route = ChatImageIntentRoute.CHAT,
        sourceText = input,
        reason = when {
            imagePromptNegativePrefix.containsMatchIn(value) -> "negative_or_discussion"
            imagePromptQuestionPrefix.containsMatchIn(value) -> "question_or_discussion"
            else -> "no_image_action"
        }
    )
}

internal fun parseExplicitChatImageIntent(input: String): ChatImageIntent? =
    parseImageIntentInternal(input)

/** Name used by new call sites; the old function remains source-compatible for existing tests. */
internal fun parseChatImageIntent(input: String): ChatImageIntent? =
    parseExplicitChatImageIntent(input)
