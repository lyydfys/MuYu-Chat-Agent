package com.muyuchat.mca

import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolDefinition
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import org.json.JSONObject
import java.security.MessageDigest

internal const val CHAT_IMAGE_TOOL_NAME = "generate_image"

/** Secret-free identity for the exact persisted message prefix used by a Responses tool turn. */
internal fun chatImageToolContextFingerprint(messages: List<ChatMessage>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun append(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(bytes)
        digest.update(0xff.toByte())
    }

    append(messages.size.toString())
    messages.forEach { message ->
        append(message.role.name)
        append(message.content)
        append(message.imageAttachments.size.toString())
        message.imageAttachments.forEach { attachment ->
            append(attachment.name)
            append(attachment.uriString)
            append(attachment.mimeType)
            append(attachment.width.toString())
            append(attachment.height.toString())
            append(attachment.sizeBytes.toString())
            append(attachment.dataBase64)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

internal fun ChatGeneratedImageRequest.canContinueAssistantImageTurn(): Boolean =
    origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
        toolContinuationStatus in setOf(
            ChatImageToolContinuationStatus.NOT_STARTED,
            ChatImageToolContinuationStatus.FAILED
        )

/** The model may ask for an image, but it cannot choose the backend or execution parameters. */
internal fun chatImageToolDefinition(): ChatToolDefinition = ChatToolDefinition(
    name = CHAT_IMAGE_TOOL_NAME,
    description = "Generate one image only when the user asks to create or draw an image. " +
        "Use a concise, detailed visual prompt. Do not call this tool to discuss, analyze, or edit a prompt.",
    parametersJson = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "prompt",
                    JSONObject()
                        .put("type", "string")
                        .put("minLength", 1)
                        .put("maxLength", LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS)
                        .put("description", "The visual content to generate.")
                )
                .put(
                    "negative_prompt",
                    JSONObject()
                        .put("type", "string")
                        .put("maxLength", LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS)
                        .put("description", "Optional negative or exclusion prompt; preserve its scope.")
                )
        )
        .put("required", org.json.JSONArray().put("prompt"))
        .put("additionalProperties", false)
        .toString()
)

internal sealed interface ChatImageToolArguments {
    data class Valid(
        val prompt: String,
        val negativePrompt: String? = null
    ) : ChatImageToolArguments
    data class Invalid(val reason: String) : ChatImageToolArguments
}

/** Reject unknown fields and malformed JSON instead of guessing what the model meant. */
internal fun validateChatImageToolCall(call: ChatToolCall): ChatImageToolArguments {
    if (call.id.isBlank()) return ChatImageToolArguments.Invalid("工具调用缺少唯一编号。")
    if (call.name != CHAT_IMAGE_TOOL_NAME) {
        return ChatImageToolArguments.Invalid("当前只允许调用生图工具。")
    }
    val arguments = runCatching { JSONObject(call.argumentsJson) }.getOrElse {
        return ChatImageToolArguments.Invalid("生图参数不是有效 JSON。")
    }
    val keys = arguments.keys().asSequence().toSet()
    if (!keys.all { it == "prompt" || it == "negative_prompt" } || "prompt" !in keys) {
        return ChatImageToolArguments.Invalid("生图参数只允许包含 prompt 和可选 negative_prompt。")
    }
    val promptValue = arguments.opt("prompt")
    if (promptValue !is String) {
        return ChatImageToolArguments.Invalid("生图提示词必须是文本。")
    }
    val prompt = promptValue.trim()
    if (prompt.isBlank()) return ChatImageToolArguments.Invalid("生图提示词不能为空。")
    if (prompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
        return ChatImageToolArguments.Invalid(
            "生图提示词超过 ${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符限制。"
        )
    }
    val negativePrompt = when {
        !arguments.has("negative_prompt") || arguments.isNull("negative_prompt") -> null
        arguments.opt("negative_prompt") !is String ->
            return ChatImageToolArguments.Invalid("负面提示词必须是文本。")
        else -> arguments.optString("negative_prompt").trim().takeIf(String::isNotBlank)
    }
    if (negativePrompt != null && negativePrompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
        return ChatImageToolArguments.Invalid(
            "负面提示词超过 ${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符限制。"
        )
    }
    return ChatImageToolArguments.Valid(prompt, negativePrompt)
}
