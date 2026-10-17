package com.muyuchat.feature.chat

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PreparedImagePromptTagAutocomplete(
    val autocomplete: ImagePromptTagAutocomplete,
    val translationsAccepted: Boolean
)

internal fun prepareImagePromptTagAutocomplete(
    tags: List<ImagePromptTagRecord>,
    translations: Map<String, String>,
    checkCancelled: () -> Unit = {},
    indexFactory: (
        List<ImagePromptTagRecord>,
        Map<String, String>,
        () -> Unit
    ) -> ImagePromptTagAutocomplete = { finalTags, finalTranslations, checkpoint ->
        ImagePromptTagAutocomplete.create(finalTags, finalTranslations, checkpoint)
    }
): PreparedImagePromptTagAutocomplete {
    checkCancelled()
    val translationsAccepted = canCombineImagePromptTagDictionaries(
        tags,
        translations,
        checkCancelled
    )
    val effectiveTranslations = if (translationsAccepted) translations else emptyMap()
    val autocomplete = indexFactory(tags, effectiveTranslations, checkCancelled)
    checkCancelled()
    return PreparedImagePromptTagAutocomplete(
        autocomplete = autocomplete,
        translationsAccepted = translationsAccepted
    )
}

/**
 * Preserves the linearized result when coroutine cancellation races with return dispatch after a
 * file mutation has committed. Before [markCommitted] cancellation still propagates normally.
 */
internal suspend fun <T : Any> withImagePromptTagCommittedMutation(
    ioDispatcher: CoroutineDispatcher,
    block: suspend (
        checkCancelled: () -> Unit,
        markCommitted: (T) -> Unit
    ) -> T
): T {
    val callerContext = currentCoroutineContext()
    val committed = AtomicReference<T?>(null)
    return try {
        withContext(ioDispatcher) {
            block(callerContext::ensureActive) { value ->
                check(committed.compareAndSet(null, value)) {
                    "Image prompt tag mutation crossed its commit point more than once."
                }
            }
        }
    } catch (cancelled: CancellationException) {
        committed.get() ?: throw cancelled
    }
}

internal fun canCombineImagePromptTagDictionaries(
    tags: List<ImagePromptTagRecord>,
    translations: Map<String, String>,
    checkCancelled: () -> Unit = {}
): Boolean {
    var baseTerms = 0L
    for ((index, tag) in tags.withIndex()) {
        if (index % STORE_CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        baseTerms += 1L + tag.aliases.count { normalizeImagePromptTag(it).isNotEmpty() }
        if (baseTerms > ImagePromptTagAutocomplete.MAX_INDEX_TERMS) return false
    }
    val conservativeTotal = baseTerms + minOf(tags.size, translations.size).toLong()
    if (conservativeTotal <= ImagePromptTagAutocomplete.MAX_INDEX_TERMS) return true

    val searchableTranslationKeys = HashSet<String>(translations.size.coerceAtLeast(16))
    for ((index, entry) in translations.entries.withIndex()) {
        if (index % STORE_CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        val rawTag = entry.key
        val translation = entry.value
        if (translation.any { it != '_' && it != '-' && !it.isWhitespace() }) {
            searchableTranslationKeys += normalizeImagePromptTag(rawTag)
        }
    }
    var exactTotal = baseTerms
    for ((index, tag) in tags.withIndex()) {
        if (index % STORE_CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        if (normalizeImagePromptTag(tag.tag) in searchableTranslationKeys) {
            exactTotal++
            if (exactTotal > ImagePromptTagAutocomplete.MAX_INDEX_TERMS) return false
        }
    }
    return true
}

internal fun <T> buildAndCommitImagePromptTagSnapshot(
    staged: File,
    target: File,
    checkCancelled: () -> Unit,
    build: () -> T,
    publish: (source: File, destination: File) -> Unit,
    onCommitted: (T) -> Unit = {}
): T {
    return try {
        val prepared = build()
        // Check directly at the irreversible publication boundary, after all expensive parsing.
        checkCancelled()
        publish(staged, target)
        onCommitted(prepared)
        prepared
    } finally {
        staged.delete()
    }
}

private const val STORE_CANCELLATION_CHECK_INTERVAL: Int = 1_024
