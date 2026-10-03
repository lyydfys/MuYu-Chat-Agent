package com.muyuchat.mca

import java.text.Normalizer

/**
 * Selects the world-book entries a chat turn should carry, under a token budget.
 *
 * Split out of WorldBookStore.kt during P2. Same package, so no caller changed a single import and
 * the block itself is verbatim.
 *
 * ## What this file is, and what it still reaches
 *
 * It holds the selection algorithm plus the data classes only it needs. It is **not** a purely
 * functional helper: it calls `compileWorldBookRegex`, which stays in WorldBookStore.kt because
 * `WorldBookCodec` validates triggers with it too. That function was widened from `private` to
 * `internal` so both callers keep working, so the re2j dependency still exists on this path and is
 * stated here rather than implied by omission.
 *
 * The remaining contents of WorldBookStore.kt are persistence: `Context`, files, `org.json`, a
 * lock, and the codec.
 */

object WorldBookResolver {
    private const val MAX_SCAN_CHARS = 24_576

    fun select(
        books: List<WorldBookRecord>,
        messages: List<com.muyuchat.core.engine.ChatMessage>,
        assistantId: String,
        chatSessionId: String?,
        tokenBudget: Int
    ): WorldBookSelection {
        if (tokenBudget <= 0) return WorldBookSelection()
        val scanText = boundedScanText(
            messages
                .filter { it.role != com.muyuchat.core.engine.Role.SYSTEM }
                .takeLast(16)
                .joinToString("\n") { it.content }
        )
        val candidates = books.asSequence()
            .filter { it.enabled && matchesScope(it, assistantId, chatSessionId) }
            .flatMap { book ->
                book.entries.asSequence()
                    .filter { it.enabled }
                    .filter { entry -> entry.constant || entryMatches(entry, scanText) }
                    .map { entry -> WorldBookCandidate(book, entry) }
            }
            .sortedWith(
                compareByDescending<WorldBookCandidate> { it.entry.constant }
                    .thenByDescending { it.entry.priority }
                    .thenBy { it.book.name }
                    .thenBy { it.entry.id }
            )
            .toList()
        var usedTokens = 0
        val selected = mutableListOf<WorldBookCandidate>()
        val skipped = mutableListOf<WorldBookCandidate>()
        candidates.forEach { candidate ->
            val entryTokens = estimateTokens(candidate.entry.content)
            if (entryTokens <= tokenBudget - usedTokens) {
                selected += candidate
                usedTokens += entryTokens
            } else {
                skipped += candidate
            }
        }
        val context = selected.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { it.entry.content }
            ?.let { "[World book]\n$it" }
            .orEmpty()
        return WorldBookSelection(
            context = context,
            selectedEntryIds = selected.map { it.entry.id },
            skippedEntryIds = skipped.map { it.entry.id },
            estimatedTokens = usedTokens,
            selectedSources = selected.map { candidate ->
                WorldBookSource(
                    bookId = candidate.book.id,
                    entryId = candidate.entry.id,
                    scope = candidate.book.scope,
                    excerpt = candidate.entry.content,
                    estimatedTokens = estimateTokens(candidate.entry.content),
                    reason = when {
                        candidate.entry.constant -> "constant"
                        candidate.entry.useRegex -> "regex"
                        else -> "keyword"
                    }
                )
            },
            skippedSources = skipped.map { candidate ->
                WorldBookSource(
                    bookId = candidate.book.id,
                    entryId = candidate.entry.id,
                    scope = candidate.book.scope,
                    excerpt = candidate.entry.content,
                    estimatedTokens = estimateTokens(candidate.entry.content),
                    reason = "budget"
                )
            }
        )
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { character ->
            if (character.isWhitespace()) return@forEach
            if (character.code in 0x2E80..0x9FFF || character.code in 0xAC00..0xD7AF ||
                character.code in 0x3040..0x30FF
            ) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk + (other + 2) / 3).coerceAtLeast(1)
    }

    private fun matchesScope(
        book: WorldBookRecord,
        assistantId: String,
        chatSessionId: String?
    ): Boolean = when (book.scope) {
        WorldBookScope.GLOBAL -> true
        WorldBookScope.ASSISTANT -> book.assistantId == assistantId
        WorldBookScope.CHAT -> book.chatSessionId == chatSessionId
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()

    private fun boundedScanText(value: String): String {
        if (value.length <= MAX_SCAN_CHARS) return value
        val half = MAX_SCAN_CHARS / 2
        return value.take(half) + "\n…\n" + value.takeLast(half)
    }

    private fun entryMatches(entry: WorldBookEntry, scanText: String): Boolean {
        val primary = entry.keys.any { matchesTrigger(it, entry, scanText) }
        if (!primary) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        return entry.secondaryKeys.any { matchesTrigger(it, entry, scanText) }
    }

    private fun matchesTrigger(trigger: String, entry: WorldBookEntry, scanText: String): Boolean {
        if (trigger.isBlank()) return false
        if (entry.useRegex) {
            return compileWorldBookRegex(trigger, entry.caseSensitive)
                ?.matcher(scanText)?.find() == true
        }
        val normalizedTrigger = if (entry.caseSensitive) {
            Normalizer.normalize(trigger, Normalizer.Form.NFKC)
        } else {
            normalize(trigger)
        }
        return if (entry.caseSensitive) {
            scanText.contains(normalizedTrigger)
        } else {
            normalize(scanText).contains(normalizedTrigger)
        }
    }

    private data class WorldBookCandidate(
        val book: WorldBookRecord,
        val entry: WorldBookEntry
    )
}
