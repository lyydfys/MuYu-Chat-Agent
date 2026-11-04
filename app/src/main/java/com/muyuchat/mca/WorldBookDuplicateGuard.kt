package com.muyuchat.mca

import java.security.MessageDigest

/**
 * Content-based duplicate detection for world-book imports.
 *
 * Why a content fingerprint instead of the file name: users re-export the same book under
 * `世界书.json`, `世界书 (1).json`, `Eternal Wind Box.json`… and the picker may return a provider
 * copy with a different display name. What matters is the book's data, not the file it arrived in.
 *
 * The fingerprint covers **entries only** (content, keys, flags), so:
 *  - re-importing the same file twice is a no-op;
 *  - the same book exported as Tavern JSON or JSONL, or under another file name, is still detected;
 *  - two books with an identical entry body would be treated as the same book, which is the
 *    intended "this is the same data" answer. The name is deliberately excluded because it comes
 *    from the file name when the export has none.
 *
 * Entry ids are excluded as well: they are random per parse and would make every fingerprint unique.
 */
internal object WorldBookDuplicateGuard {

    /** Stable fingerprint of one book's entries; independent of ids, name, scope and timestamps. */
    fun fingerprint(book: WorldBookRecord): String {
        val canonical = StringBuilder()
        book.entries.forEach { entry ->
            canonical
                .append(entry.content.trim().replace("\r\n", "\n").replace('\r', '\n'))
                .append('\u0001').append(entry.keys.joinToString("\u0002"))
                .append('\u0001').append(entry.secondaryKeys.joinToString("\u0002"))
                .append('\u0001').append(entry.enabled)
                .append('\u0001').append(entry.constant)
                .append('\u0001').append(entry.selective)
                .append('\u0001').append(entry.useRegex)
                .append('\u0001').append(entry.caseSensitive)
                .append('\u0001').append(entry.priority)
                .append('\u0000')
        }
        return sha256(canonical.toString())
    }

    /** Fingerprints of everything already stored; used to answer "did I import this before?". */
    fun fingerprints(books: List<WorldBookRecord>): Set<String> =
        books.mapTo(LinkedHashSet(), ::fingerprint)

    /**
     * Deterministic id for a content-addressed book, so an import that somehow bypasses the guard
     * still cannot create a second record with different id for the same data.
     */
    fun bookIdFor(fingerprint: String): String = "wb-" + fingerprint.take(24)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * One batch result line: how many books landed, how many were duplicates, and the first few
 * failures. A silent partial run is exactly what the user reported, so the message always states
 * all three counts when a batch ran.
 */
internal data class WorldBookBatchImportSummary(
    val imported: Int,
    val skippedDuplicates: Int,
    val failures: List<String>
) {
    val failed: Int get() = failures.size

    fun statusMessage(): String = buildString {
        append("已导入 ").append(imported).append(" 本世界书")
        if (skippedDuplicates > 0) {
            append("，跳过 ").append(skippedDuplicates).append(" 个重复")
        }
        if (failures.isNotEmpty()) {
            append("，").append(failures.size).append(" 本失败：")
            append(failures.take(MAX_REPORTED_FAILURES).joinToString("；"))
            if (failures.size > MAX_REPORTED_FAILURES) append("；…")
        }
    }

    /** A single successful file keeps the old quiet behaviour: its own import line is the feedback. */
    fun isQuietSingleImport(batchSize: Int): Boolean =
        batchSize == 1 && imported == 1 && skippedDuplicates == 0 && failures.isEmpty()

    companion object {
        const val MAX_REPORTED_FAILURES = 3
    }
}
