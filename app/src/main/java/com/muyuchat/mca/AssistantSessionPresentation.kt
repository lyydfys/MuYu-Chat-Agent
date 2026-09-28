package com.muyuchat.mca

internal data class AssistantSessionPresentation(
    val assistantId: String?,
    val name: String,
    val summary: String
)

internal fun List<ChatSessionRecord>.assistantSessionPresentations(
    assistants: List<AssistantRecord>
): Map<String, AssistantSessionPresentation> {
    val assistantsById = assistants.associateBy { it.id }
    val ownerBySession = associate { session ->
        val owner = session.assistantId?.takeIf { assistantId ->
            !session.mixedAssistantHistory && session.assistantSnapshot?.assistantId == assistantId
        }
        session.id to owner
    }
    val numberBySession = ownerBySession.values.filterNotNull().distinct().flatMap { assistantId ->
        filter { ownerBySession[it.id] == assistantId }
            .sortedWith(compareBy<ChatSessionRecord> { it.assistantSnapshot?.capturedAt ?: it.updatedAt }
                .thenBy { it.id })
            .mapIndexed { index, session -> session.id to index + 1 }
    }.toMap()

    return associate { session ->
        val assistantId = ownerBySession[session.id]
        val name = if (assistantId == null) {
            session.title
        } else {
            val cardName = assistantsById[assistantId]?.name
                ?: session.assistantSnapshot?.name
                ?: session.title
            cardName + (numberBySession[session.id] ?: 1).romanSuffix()
        }
        val lastContent = session.messages.asReversed().firstOrNull { it.content.isNotBlank() }?.content
        val summary = (lastContent ?: session.title.takeUnless { it == "新对话" }.orEmpty())
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
        session.id to AssistantSessionPresentation(assistantId, name, summary)
    }
}

private fun Int.romanSuffix(): String {
    if (this <= 1) return ""
    val compact = listOf("", "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ")
    compact.getOrNull(this)?.let { return it }
    var remaining = this
    return buildString {
        listOf(
            100 to "Ⅽ", 90 to "ⅩⅭ", 50 to "Ⅼ", 40 to "ⅩⅬ", 10 to "Ⅹ",
            9 to "Ⅸ", 5 to "Ⅴ", 4 to "Ⅳ", 3 to "Ⅲ", 2 to "Ⅱ", 1 to "Ⅰ"
        ).forEach { (value, numeral) ->
            while (remaining >= value) {
                append(numeral)
                remaining -= value
            }
        }
    }
}
