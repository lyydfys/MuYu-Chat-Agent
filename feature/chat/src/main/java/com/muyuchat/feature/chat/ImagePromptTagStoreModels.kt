package com.muyuchat.feature.chat

import java.io.IOException

internal enum class ImagePromptTagDictionaryKind(
    val fileName: String,
    val maximumBytes: Long
) {
    TAGS(fileName = "tags.csv", maximumBytes = 64L * 1024L * 1024L),
    TRANSLATIONS(fileName = "translations.csv", maximumBytes = 32L * 1024L * 1024L)
}

internal enum class ImagePromptTagStoreIssue {
    UNSUPPORTED_URI,
    SOURCE_UNREADABLE,
    FILE_TOO_LARGE,
    TOO_MANY_LINES,
    EMPTY_DICTIONARY,
    MALFORMED_CSV,
    INDEX_REJECTED,
    STORAGE_READ_FAILED,
    TRANSACTION_FAILED
}

internal sealed interface ImagePromptTagDictionaryState {
    data object NotConfigured : ImagePromptTagDictionaryState

    data class Available(
        val rowCount: Int,
        val byteCount: Long
    ) : ImagePromptTagDictionaryState

    data class Corrupt(
        val issue: ImagePromptTagStoreIssue
    ) : ImagePromptTagDictionaryState

    data object ReadFailed : ImagePromptTagDictionaryState
}

internal data class ImagePromptTagStoreStatus(
    val tags: ImagePromptTagDictionaryState,
    val translations: ImagePromptTagDictionaryState
)

internal sealed interface ImagePromptTagStoreLoadResult {
    val status: ImagePromptTagStoreStatus

    data class Ready(
        val autocomplete: ImagePromptTagAutocomplete,
        override val status: ImagePromptTagStoreStatus
    ) : ImagePromptTagStoreLoadResult

    data class Unavailable(
        override val status: ImagePromptTagStoreStatus
    ) : ImagePromptTagStoreLoadResult
}

internal sealed interface ImagePromptTagStoreChangeResult {
    val kind: ImagePromptTagDictionaryKind
    val status: ImagePromptTagStoreStatus

    data class Applied(
        override val kind: ImagePromptTagDictionaryKind,
        override val status: ImagePromptTagStoreStatus,
        /** Reuse after import to avoid immediately rebuilding a 100k+ tag index. */
        val loadResult: ImagePromptTagStoreLoadResult? = null
    ) : ImagePromptTagStoreChangeResult

    data class Rejected(
        override val kind: ImagePromptTagDictionaryKind,
        val issue: ImagePromptTagStoreIssue,
        override val status: ImagePromptTagStoreStatus
    ) : ImagePromptTagStoreChangeResult
}

internal data class ParsedPayload<T>(
    val payload: T,
    val rowCount: Int
)

internal sealed interface ParsedSnapshot<out T> {
    data object Missing : ParsedSnapshot<Nothing>

    data class Valid<T>(
        val payload: T,
        val rowCount: Int,
        val byteCount: Long
    ) : ParsedSnapshot<T>

    data class Invalid(val issue: ImagePromptTagStoreIssue) : ParsedSnapshot<Nothing>

    data object ReadFailure : ParsedSnapshot<Nothing>
}

internal fun ParsedSnapshot<*>.toState(): ImagePromptTagDictionaryState = when (this) {
    ParsedSnapshot.Missing -> ImagePromptTagDictionaryState.NotConfigured
    is ParsedSnapshot.Valid<*> -> ImagePromptTagDictionaryState.Available(rowCount, byteCount)
    is ParsedSnapshot.Invalid -> ImagePromptTagDictionaryState.Corrupt(issue)
    ParsedSnapshot.ReadFailure -> ImagePromptTagDictionaryState.ReadFailed
}

internal fun <T> ParsedSnapshot<T>.requireValid(): T = when (this) {
    is ParsedSnapshot.Valid -> payload
    is ParsedSnapshot.Invalid -> throw ImagePromptTagStoreValidationException(issue)
    ParsedSnapshot.Missing -> throw ImagePromptTagStoreValidationException(
        ImagePromptTagStoreIssue.EMPTY_DICTIONARY
    )
    ParsedSnapshot.ReadFailure -> throw ImagePromptTagStoreValidationException(
        ImagePromptTagStoreIssue.STORAGE_READ_FAILED
    )
}

internal fun <T> ParsedSnapshot<T>.validPayloadOrNull(): T? =
    (this as? ParsedSnapshot.Valid<T>)?.payload

internal fun ImagePromptTagStoreStatus.withState(
    kind: ImagePromptTagDictionaryKind,
    state: ImagePromptTagDictionaryState
): ImagePromptTagStoreStatus = when (kind) {
    ImagePromptTagDictionaryKind.TAGS -> copy(tags = state)
    ImagePromptTagDictionaryKind.TRANSLATIONS -> copy(translations = state)
}

internal class ImagePromptTagStoreValidationException(
    val issue: ImagePromptTagStoreIssue,
    cause: Throwable? = null
) : IOException(issue.name, cause)
