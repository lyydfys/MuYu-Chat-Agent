package com.muyuchat.mca.agent.projection

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.agent.data.AgentArtifactLayout

/**
 * One agent task's view of the chat history that came before it.
 *
 * [CreateAgentTask][com.muyuchat.core.agent.contract.CreateAgentTask] has no context field and the
 * goal is capped at 8 KiB, so the history cannot travel in the command. It travels as a read-only
 * workspace input instead: the caller writes [seedText] to `inputs/[inputFileName]` and appends
 * [goalHint] to the goal. The agent then reads the file with its ordinary workspace tools.
 *
 * [goalHint] is `null` exactly when there is nothing worth reading, so a caller can concatenate it
 * without producing a dangling instruction.
 */
internal data class ChatHistorySeed(
    /** Conversation digest, oldest first, one `角色: 摘要` line per message. Empty when nothing fits. */
    val seedText: String,
    /** Sentence that points the agent at [inputFileName]; `null` when [seedText] is empty. */
    val goalHint: String?,
    /** File name to write under `inputs/`; already referenced by [goalHint]. */
    val inputFileName: String,
    /** Number of messages that actually produced a line. */
    val includedMessageCount: Int,
    /** Messages dropped because they were agent transcript (`agentTrace != null`). */
    val skippedAgentMessageCount: Int,
    /** Included messages whose body had to be shortened to fit the per-message budget. */
    val truncatedMessageCount: Int
) {
    val isEmpty: Boolean get() = seedText.isEmpty()
}

/** Default total budget for the seed file. Four KiB of headroom under the 8 KiB goal ceiling. */
internal const val CHAT_HISTORY_SEED_MAX_BYTES: Int = 6 * 1024

/** Upper bound on how many recent messages may enter one seed. */
internal const val CHAT_HISTORY_SEED_MAX_MESSAGES: Int = 24

/** Per-message body budget; one huge message cannot consume the whole seed. */
internal const val CHAT_HISTORY_SEED_MAX_MESSAGE_BYTES: Int = 512

/** Below this a truncated body is not worth rendering, so the seed stops instead. */
private const val CHAT_HISTORY_SEED_MIN_BODY_BYTES: Int = 24

private const val CHAT_HISTORY_SEED_MARKER: String = "…"

private const val CHAT_HISTORY_SEED_DEFAULT_FILE_NAME: String = "chat-history.txt"

/**
 * Builds a bounded digest of the conversation that precedes an agent task.
 *
 * Rules, in the order they apply:
 *  - Messages carrying an [com.muyuchat.core.engine.AgentTurnTrace] are **skipped**: they are the
 *    agent's own transcript (or a previous turn summary), and replaying them would feed the agent
 *    its own protocol noise. This is the only hard filter besides blank bodies.
 *  - Only the most recent [maxMessages] messages are considered, and the byte budget is spent from
 *    the newest backwards, so an old long message can never squeeze out the latest turn.
 *  - Each message becomes `角色: 摘要` with newlines/tabs collapsed to single spaces.
 *  - A single body longer than [CHAT_HISTORY_SEED_MAX_MESSAGE_BYTES] is cut on a code-point boundary
 *    and marked with `…`; [ChatHistorySeed.truncatedMessageCount] records how many were cut.
 *  - The last written line never pushes `seedText` past [maxBytes] UTF-8 bytes.
 *
 * Pure: no clock, no I/O, no state. The timestamped file name is chosen by the caller through
 * [chatHistorySeedFileName], which keeps this function deterministic for tests.
 */
internal fun buildSeed(
    history: List<ChatMessage>,
    maxBytes: Int = CHAT_HISTORY_SEED_MAX_BYTES,
    maxMessages: Int = CHAT_HISTORY_SEED_MAX_MESSAGES,
    inputFileName: String = CHAT_HISTORY_SEED_DEFAULT_FILE_NAME
): ChatHistorySeed {
    require(maxBytes > 0) { "maxBytes must be positive" }
    require(maxMessages > 0) { "maxMessages must be positive" }
    require(inputFileName.isNotBlank()) { "inputFileName must not be blank" }

    val lines = ArrayList<String>(maxMessages.coerceAtMost(history.size))
    var remainingBudget = maxBytes
    var skippedAgentMessages = 0
    var truncatedMessages = 0
    var includedMessages = 0

    for (message in history.asReversed()) {
        if (message.agentTrace != null) {
            skippedAgentMessages += 1
            continue
        }
        if (includedMessages >= maxMessages) break

        val body = chatHistorySeedBody(message.content)
        if (body.isEmpty()) continue

        val prefix = "${chatHistorySeedRoleLabel(message.role)}: "
        val separatorBytes = if (lines.isEmpty()) 0 else NEWLINE_BYTES
        val bodyBudget = remainingBudget - separatorBytes - prefix.utf8Size()
        if (bodyBudget < CHAT_HISTORY_SEED_MIN_BODY_BYTES) break

        val fittedBody = fitUtf8(body, minOf(bodyBudget, CHAT_HISTORY_SEED_MAX_MESSAGE_BYTES))
            ?: break
        val line = prefix + fittedBody
        lines += line
        remainingBudget -= separatorBytes + line.utf8Size()
        includedMessages += 1
        if (fittedBody != body) truncatedMessages += 1
    }

    val seedText = lines.asReversed().joinToString("\n")
    return ChatHistorySeed(
        seedText = seedText,
        goalHint = if (seedText.isEmpty()) {
            null
        } else {
            "先读 ${AgentArtifactLayout.INPUTS}/$inputFileName 了解此前对话"
        },
        inputFileName = inputFileName,
        includedMessageCount = includedMessages,
        skippedAgentMessageCount = skippedAgentMessages,
        truncatedMessageCount = truncatedMessages
    )
}

/**
 * Timestamped input file name for one seed, e.g. `chat-history-1762560000000.txt`.
 *
 * The clock stays with the caller so [buildSeed] remains a pure function.
 */
internal fun chatHistorySeedFileName(timestampMillis: Long): String =
    "chat-history-$timestampMillis.txt"

// ------------------------------------------------------------------ internals

private const val NEWLINE_BYTES: Int = 1

/** Collapses the whitespace an LLM output or a pasted document tends to carry. */
private fun chatHistorySeedBody(content: String): String {
    val builder = StringBuilder(content.length)
    var pendingSpace = false
    for (character in content) {
        if (character.isWhitespace()) {
            pendingSpace = builder.isNotEmpty()
        } else {
            if (pendingSpace) builder.append(' ')
            pendingSpace = false
            builder.append(character)
        }
    }
    return builder.toString()
}

private fun chatHistorySeedRoleLabel(role: Role): String = when (role) {
    Role.SYSTEM -> "系统"
    Role.USER -> "用户"
    Role.ASSISTANT -> "助手"
}

/** Returns [text] unchanged when it fits, otherwise a code-point-safe head plus the marker. */
private fun fitUtf8(text: String, budgetBytes: Int): String? {
    if (budgetBytes <= 0) return null
    if (text.utf8Size() <= budgetBytes) return text
    val markerBytes = CHAT_HISTORY_SEED_MARKER.utf8Size()
    if (budgetBytes <= markerBytes) return null
    val bodyBudget = budgetBytes - markerBytes
    val builder = StringBuilder(text.length)
    var usedBytes = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val codePointBytes = utf8LengthOf(codePoint)
        if (usedBytes + codePointBytes > bodyBudget) break
        builder.appendCodePoint(codePoint)
        usedBytes += codePointBytes
        index += Character.charCount(codePoint)
    }
    val head = builder.toString().trimEnd()
    return if (head.isEmpty()) null else head + CHAT_HISTORY_SEED_MARKER
}

/** UTF-8 length of one code point: 1..4 bytes, per Unicode Table 3-7. */
private fun utf8LengthOf(codePoint: Int): Int = when {
    codePoint <= 0x7F -> 1
    codePoint <= 0x7FF -> 2
    codePoint <= 0xFFFF -> 3
    else -> 4
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size
