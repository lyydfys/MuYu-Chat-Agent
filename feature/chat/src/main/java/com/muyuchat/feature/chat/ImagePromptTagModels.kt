package com.muyuchat.feature.chat

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

internal class ImagePromptTagRecord(
    val tag: String,
    val category: Int,
    val postCount: Long,
    aliases: List<String> = emptyList()
) {
    private val aliasSnapshot = immutableTagList(aliases)

    val aliases: List<String>
        get() = aliasSnapshot

    init {
        require(isSafeCanonicalImagePromptTag(tag)) {
            "Canonical tags must be bounded ASCII English prompt values."
        }
        require(category in MIN_TAG_CATEGORY..MAX_TAG_CATEGORY) { "Tag category is out of range." }
        require(postCount >= 0L) { "Tag popularity cannot be negative." }
        require(aliasSnapshot.size <= MAX_TAG_ALIASES) { "Tag alias count exceeds the limit." }
        require(aliasSnapshot.all(::isSafeTagValue)) { "Tag aliases must be bounded printable values." }
    }

    override fun equals(other: Any?): Boolean =
        other is ImagePromptTagRecord &&
            tag == other.tag &&
            category == other.category &&
            postCount == other.postCount &&
            aliasSnapshot == other.aliasSnapshot

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = 31 * result + category
        result = 31 * result + postCount.hashCode()
        result = 31 * result + aliasSnapshot.hashCode()
        return result
    }
}

internal data class ImagePromptTagTranslationRow(
    val tag: String,
    val translation: String
) {
    init {
        require(isSafeCanonicalImagePromptTag(tag)) { "Translation tag key is invalid." }
        require(isSafeTranslationValue(translation)) { "Translation text is invalid." }
    }
}

/** Strict, size-limited CSV parsing. Callers remain responsible for line iteration and storage. */
internal enum class ImagePromptTagMatchKind {
    TAG_PREFIX,
    ALIAS_PREFIX,
    TRANSLATION_PREFIX,
    FUZZY_TAG,
    FUZZY_ALIAS,
    FUZZY_TRANSLATION
}

internal data class ImagePromptTagSuggestion(
    val replacementTag: String,
    val category: Int,
    val postCount: Long,
    val matchKind: ImagePromptTagMatchKind,
    val matchedText: String,
    val translation: String?,
    val textualInversionId: String? = null,
    val textualInversionSelected: Boolean = false,
) {
    init {
        require(!textualInversionSelected || textualInversionId != null) {
            "A selected textual-inversion suggestion must identify its artifact."
        }
    }
}

internal data class ImagePromptActiveToken(
    val query: String,
    val contentStart: Int,
    val segmentEnd: Int
)

internal data class ImagePromptTextEdit(
    val text: String,
    val cursor: Int
)

internal fun <T> immutableTagList(values: Collection<T>): List<T> =
    if (values.isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(values))

private const val MIN_TAG_CATEGORY: Int = 0
private const val MAX_TAG_CATEGORY: Int = 255
internal const val MAX_TAG_ALIASES: Int = 64
