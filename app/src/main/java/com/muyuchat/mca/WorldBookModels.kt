package com.muyuchat.mca

import java.util.UUID

/**
 * Tavern World Info entries are stored as data. Regex triggers use RE2/J's
 * bounded linear-time matcher; macros and executable extensions are not run.
 */
enum class WorldBookScope(val wireName: String) {
    GLOBAL("global"),
    ASSISTANT("assistant"),
    CHAT("chat");

    companion object {
        fun fromWireName(value: String?): WorldBookScope =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
                ?: GLOBAL
    }
}

data class WorldBookEntry(
    val id: String = UUID.randomUUID().toString(),
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val priority: Int = 0,
    val selective: Boolean = false,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false
) {
    init {
        require(content.isNotBlank()) { "World book entry content is required." }
    }
}

data class WorldBookRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scope: WorldBookScope = WorldBookScope.GLOBAL,
    val assistantId: String? = null,
    val chatSessionId: String? = null,
    val entries: List<WorldBookEntry>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val originalJson: String? = null
) {
    init {
        require(name.isNotBlank()) { "World book name is required." }
        require(entries.isNotEmpty()) { "World book must contain at least one entry." }
        require(scope != WorldBookScope.ASSISTANT || !assistantId.isNullOrBlank()) {
            "Assistant world books require an assistant id."
        }
        require(scope != WorldBookScope.CHAT || !chatSessionId.isNullOrBlank()) {
            "Chat world books require a chat session id."
        }
    }
}

data class WorldBookImportResult(
    val book: WorldBookRecord? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList()
) {
    val isSuccess: Boolean
        get() = book != null && error == null
}

data class WorldBookSelection(
    val context: String = "",
    val selectedEntryIds: List<String> = emptyList(),
    val skippedEntryIds: List<String> = emptyList(),
    val estimatedTokens: Int = 0,
    val selectedSources: List<WorldBookSource> = emptyList(),
    val skippedSources: List<WorldBookSource> = emptyList()
)

data class WorldBookSource(
    val bookId: String,
    val entryId: String,
    val scope: WorldBookScope,
    val excerpt: String,
    val estimatedTokens: Int,
    val reason: String
)
