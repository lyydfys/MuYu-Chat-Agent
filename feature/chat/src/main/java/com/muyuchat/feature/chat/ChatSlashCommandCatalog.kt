package com.muyuchat.feature.chat

/** A user-facing composer command. Only actions backed by a working execution path belong here. */
internal data class ChatSlashCommand(
    val command: String,
    val title: String,
    val description: String
)

/**
 * Extensible slash-command catalog for the chat composer. Register a command here only after its
 * execution path is available; this intentionally does not advertise web search yet.
 */
internal val CHAT_SLASH_COMMAND_CATALOG = listOf(
    ChatSlashCommand(
        command = "/image",
        title = "生成图片",
        description = "用中文描述图片，直接在当前对话中生成"
    )
)

/** Shows suggestions only while the first line is an unfinished slash command. */
internal fun chatSlashCommandSuggestions(input: String): List<ChatSlashCommand> {
    val firstLine = input.substringBefore('\n')
    val commandText = firstLine.trimStart()
    if (!commandText.startsWith('/')) return emptyList()
    val query = commandText.drop(1).takeWhile { !it.isWhitespace() }
    if (commandText.getOrNull(1 + query.length)?.isWhitespace() == true) return emptyList()
    return CHAT_SLASH_COMMAND_CATALOG.filter { candidate ->
        candidate.command.removePrefix("/").startsWith(query, ignoreCase = true)
    }
}

/** Replaces the in-progress command token and leaves any following user text intact. */
internal fun insertChatSlashCommand(input: String, command: ChatSlashCommand): String {
    val firstLine = input.substringBefore('\n')
    val leadingWhitespace = firstLine.takeWhile { it == ' ' || it == '\t' }
    val tokenStart = leadingWhitespace.length
    if (firstLine.getOrNull(tokenStart) != '/') return input
    var tokenEnd = tokenStart + 1
    while (tokenEnd < firstLine.length && !firstLine[tokenEnd].isWhitespace()) tokenEnd++
    val suffix = firstLine.substring(tokenEnd).trimStart(' ', '\t')
    // Keep a trailing space so the user can continue typing immediately. Preserve subsequent
    // lines, if any, after the command and its first-line prompt.
    val completed = "$leadingWhitespace${command.command} " + suffix
    return completed + input.substring(firstLine.length)
}
