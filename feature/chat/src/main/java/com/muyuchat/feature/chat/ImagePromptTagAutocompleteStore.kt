package com.muyuchat.feature.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** Private, no-backup dictionary storage. Every public operation switches to [ioDispatcher]. */
internal class ImagePromptTagAutocompleteStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext ?: context
    private val root = File(appContext.noBackupFilesDir, STORE_DIRECTORY_NAME)
    private val operationMutex = Mutex()
    private var cachedStatus: ImagePromptTagStoreStatus? = null

    suspend fun status(): ImagePromptTagStoreStatus = withContext(ioDispatcher) {
        val coroutineContext = currentCoroutineContext()
        operationMutex.withLock { cachedOrScanStatusLocked(coroutineContext::ensureActive) }
    }

    suspend fun load(): ImagePromptTagStoreLoadResult = withContext(ioDispatcher) {
        val coroutineContext = currentCoroutineContext()
        operationMutex.withLock { loadLocked(coroutineContext::ensureActive) }
    }

    suspend fun importFromContentUri(
        kind: ImagePromptTagDictionaryKind,
        uri: Uri
    ): ImagePromptTagStoreChangeResult = withImagePromptTagCommittedMutation(
        ioDispatcher
    ) { checkCancelled, markCommitted ->
        operationMutex.withLock {
            val preOperationStatus = cachedOrScanStatusLocked(checkCancelled)
            if (!isSafeImagePromptTagContentUri(uri.toString())) {
                return@withLock ImagePromptTagStoreChangeResult.Rejected(
                    kind,
                    ImagePromptTagStoreIssue.UNSUPPORTED_URI,
                    preOperationStatus
                )
            }
            val directory = ensureStoreRoot()
            val target = imagePromptTagStoreFile(directory, kind)
            val staged = File(
                directory,
                ".${kind.fileName}.${UUID.randomUUID()}.incoming.tmp"
            )
            var phase = ImportPhase.COPY
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw ImagePromptTagStoreValidationException(
                        ImagePromptTagStoreIssue.SOURCE_UNREADABLE
                    )
                input.use { source ->
                    FileOutputStream(staged).use { destination ->
                        copyImagePromptTagStreamBounded(
                            input = source,
                            output = destination,
                            maximumBytes = kind.maximumBytes,
                            checkCancelled = checkCancelled
                        )
                        destination.flush()
                        destination.fd.sync()
                    }
                }

                phase = ImportPhase.VALIDATE
                val applied = buildAndCommitImagePromptTagSnapshot(
                    staged = staged,
                    target = target,
                    checkCancelled = checkCancelled,
                    build = {
                        val validated = validateCandidateAgainstCurrent(
                            kind,
                            staged,
                            checkCancelled
                        )
                        ImagePromptTagStoreChangeResult.Applied(
                            kind,
                            validated.loadResult.status,
                            loadResult = validated.loadResult
                        )
                    },
                    publish = { source, destination ->
                        phase = ImportPhase.PUBLISH
                        replaceImagePromptTagSnapshot(source, destination)
                    },
                    onCommitted = markCommitted
                )
                cachedStatus = applied.status
                applied
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (validation: ImagePromptTagStoreValidationException) {
                ImagePromptTagStoreChangeResult.Rejected(
                    kind,
                    validation.issue,
                    preOperationStatus
                )
            } catch (_: Exception) {
                val issue = when (phase) {
                    ImportPhase.COPY -> ImagePromptTagStoreIssue.SOURCE_UNREADABLE
                    ImportPhase.VALIDATE -> ImagePromptTagStoreIssue.STORAGE_READ_FAILED
                    ImportPhase.PUBLISH -> ImagePromptTagStoreIssue.TRANSACTION_FAILED
                }
                ImagePromptTagStoreChangeResult.Rejected(
                    kind,
                    issue,
                    preOperationStatus
                )
            } finally {
                staged.delete()
            }
        }
    }

    suspend fun clear(kind: ImagePromptTagDictionaryKind): ImagePromptTagStoreChangeResult =
        withImagePromptTagCommittedMutation(ioDispatcher) { checkCancelled, markCommitted ->
            operationMutex.withLock {
                val preOperationStatus = cachedOrScanStatusLocked(checkCancelled)
                val directory = root.canonicalFile
                val target = imagePromptTagStoreFile(directory, kind)
                val postOperationStatus = preOperationStatus.withState(
                    kind,
                    ImagePromptTagDictionaryState.NotConfigured
                )
                val applied = ImagePromptTagStoreChangeResult.Applied(kind, postOperationStatus)
                if (!target.exists()) {
                    checkCancelled()
                    cachedStatus = applied.status
                    markCommitted(applied)
                    return@withLock applied
                }
                try {
                    checkCancelled()
                    check(target.isFile && target.delete()) {
                        "Unable to delete dictionary snapshot."
                    }
                    cachedStatus = applied.status
                    markCommitted(applied)
                    try {
                        syncImagePromptTagDirectory(directory)
                    } catch (_: Exception) {
                        // The logical deletion already committed; a later mutation retries directory sync.
                    }
                    applied
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ImagePromptTagStoreChangeResult.Rejected(
                        kind,
                        ImagePromptTagStoreIssue.TRANSACTION_FAILED,
                        preOperationStatus
                    )
                }
            }
        }

    private fun ensureStoreRoot(): File {
        val noBackupRoot = appContext.noBackupFilesDir.canonicalFile
        val directory = root.canonicalFile
        require(directory.parentFile == noBackupRoot) { "Dictionary root escaped no-backup storage." }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create private dictionary directory.")
        }
        if (!directory.isDirectory) throw IOException("Private dictionary root is not a directory.")
        return directory
    }

    private fun loadLocked(checkCancelled: () -> Unit): ImagePromptTagStoreLoadResult {
        val tags = parseTagSnapshot(
            imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TAGS),
            checkCancelled
        )
        val translations = parseTranslationSnapshot(
            imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TRANSLATIONS),
            checkCancelled
        )
        return createLoadResult(tags, translations, checkCancelled).also { result ->
            cachedStatus = result.status
        }
    }

    private fun cachedOrScanStatusLocked(checkCancelled: () -> Unit): ImagePromptTagStoreStatus {
        cachedStatus?.let { return it }
        return ImagePromptTagStoreStatus(
            tags = scanSnapshotState(
                imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TAGS),
                ImagePromptTagDictionaryKind.TAGS,
                checkCancelled
            ),
            translations = scanSnapshotState(
                imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TRANSLATIONS),
                ImagePromptTagDictionaryKind.TRANSLATIONS,
                checkCancelled
            )
        ).also { cachedStatus = it }
    }

    private fun scanSnapshotState(
        file: File,
        kind: ImagePromptTagDictionaryKind,
        checkCancelled: () -> Unit
    ): ImagePromptTagDictionaryState {
        if (!file.exists()) return ImagePromptTagDictionaryState.NotConfigured
        if (!file.isFile) return ImagePromptTagDictionaryState.ReadFailed
        if (file.length() > kind.maximumBytes) {
            return ImagePromptTagDictionaryState.Corrupt(
                ImagePromptTagStoreIssue.FILE_TOO_LARGE
            )
        }
        return try {
            var indexedTerms = 0L
            val rows = readImagePromptTagCsvRows(
                file = file,
                maximumRows = ImagePromptTagAutocomplete.MAX_DICTIONARY_ENTRIES,
                checkCancelled = checkCancelled
            ) { row ->
                when (kind) {
                    ImagePromptTagDictionaryKind.TAGS -> {
                        val parsed = ImagePromptTagCsv.parseTagUtf8Line(row)
                            ?: throw ImagePromptTagStoreValidationException(
                                ImagePromptTagStoreIssue.MALFORMED_CSV
                            )
                        indexedTerms += 1L + parsed.aliases.count {
                            normalizeImagePromptTag(it).isNotEmpty()
                        }
                        if (indexedTerms > ImagePromptTagAutocomplete.MAX_INDEX_TERMS) {
                            throw ImagePromptTagStoreValidationException(
                                ImagePromptTagStoreIssue.INDEX_REJECTED
                            )
                        }
                    }

                    ImagePromptTagDictionaryKind.TRANSLATIONS -> {
                        if (ImagePromptTagCsv.parseTranslationUtf8Line(row) == null) {
                            throw ImagePromptTagStoreValidationException(
                                ImagePromptTagStoreIssue.MALFORMED_CSV
                            )
                        }
                    }
                }
            }
            if (rows == 0) {
                ImagePromptTagDictionaryState.Corrupt(
                    ImagePromptTagStoreIssue.EMPTY_DICTIONARY
                )
            } else {
                ImagePromptTagDictionaryState.Available(rows, file.length())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (validation: ImagePromptTagStoreValidationException) {
            ImagePromptTagDictionaryState.Corrupt(validation.issue)
        } catch (_: Exception) {
            ImagePromptTagDictionaryState.ReadFailed
        }
    }

    private fun createLoadResult(
        tags: ParsedSnapshot<List<ImagePromptTagRecord>>,
        translations: ParsedSnapshot<Map<String, String>>,
        checkCancelled: () -> Unit
    ): ImagePromptTagStoreLoadResult {
        var tagState = tags.toState()
        var translationState = translations.toState()
        val tagPayload = tags.validPayloadOrNull()
            ?: return ImagePromptTagStoreLoadResult.Unavailable(
                ImagePromptTagStoreStatus(tagState, translationState)
            )
        val translationMap = translations.validPayloadOrNull().orEmpty()
        val autocomplete = try {
            val prepared = prepareImagePromptTagAutocomplete(
                tagPayload,
                translationMap,
                checkCancelled = checkCancelled
            )
            if (!prepared.translationsAccepted && translationMap.isNotEmpty()) {
                translationState = ImagePromptTagDictionaryState.Corrupt(
                    ImagePromptTagStoreIssue.INDEX_REJECTED
                )
            }
            prepared.autocomplete
        } catch (_: IllegalArgumentException) {
            tagState = ImagePromptTagDictionaryState.Corrupt(
                ImagePromptTagStoreIssue.INDEX_REJECTED
            )
            return ImagePromptTagStoreLoadResult.Unavailable(
                ImagePromptTagStoreStatus(tagState, translationState)
            )
        }
        return ImagePromptTagStoreLoadResult.Ready(
            autocomplete,
            ImagePromptTagStoreStatus(tagState, translationState)
        )
    }

    private fun validateCandidateAgainstCurrent(
        kind: ImagePromptTagDictionaryKind,
        staged: File,
        checkCancelled: () -> Unit
    ): ValidatedImport {
        val tags: ParsedSnapshot<List<ImagePromptTagRecord>>
        val translations: ParsedSnapshot<Map<String, String>>
        when (kind) {
            ImagePromptTagDictionaryKind.TAGS -> {
                tags = parseTagSnapshot(staged, checkCancelled).also { it.requireValid() }
                translations = parseTranslationSnapshot(
                    imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TRANSLATIONS),
                    checkCancelled
                )
            }

            ImagePromptTagDictionaryKind.TRANSLATIONS -> {
                translations = parseTranslationSnapshot(staged, checkCancelled).also { it.requireValid() }
                tags = parseTagSnapshot(
                    imagePromptTagStoreFile(root, ImagePromptTagDictionaryKind.TAGS),
                    checkCancelled
                )
            }
        }
        val loadResult = createLoadResult(tags, translations, checkCancelled)
        if (kind == ImagePromptTagDictionaryKind.TAGS && loadResult !is ImagePromptTagStoreLoadResult.Ready) {
            throw ImagePromptTagStoreValidationException(ImagePromptTagStoreIssue.INDEX_REJECTED)
        }
        return ValidatedImport(loadResult)
    }

    private fun parseTagSnapshot(
        file: File,
        checkCancelled: () -> Unit = {}
    ): ParsedSnapshot<List<ImagePromptTagRecord>> {
        if (!file.exists()) return ParsedSnapshot.Missing
        return parseSnapshot(file, ImagePromptTagDictionaryKind.TAGS, checkCancelled) {
            val records = ArrayList<ImagePromptTagRecord>()
            val normalizedTags = HashSet<String>()
            var indexedTermCount = 0L
            val rowCount = readImagePromptTagCsvRows(
                file = file,
                maximumRows = ImagePromptTagAutocomplete.MAX_DICTIONARY_ENTRIES,
                checkCancelled = checkCancelled
            ) { row ->
                val record = ImagePromptTagCsv.parseTagUtf8Line(row)
                    ?: throw ImagePromptTagStoreValidationException(
                        ImagePromptTagStoreIssue.MALFORMED_CSV
                    )
                val normalizedTag = normalizeImagePromptTag(record.tag)
                if (normalizedTag.isEmpty() || !normalizedTags.add(normalizedTag)) {
                    throw ImagePromptTagStoreValidationException(
                        ImagePromptTagStoreIssue.INDEX_REJECTED
                    )
                }
                indexedTermCount += 1L + record.aliases.count {
                    normalizeImagePromptTag(it).isNotEmpty()
                }
                if (indexedTermCount > ImagePromptTagAutocomplete.MAX_INDEX_TERMS) {
                    throw ImagePromptTagStoreValidationException(
                        ImagePromptTagStoreIssue.INDEX_REJECTED
                    )
                }
                records += record
            }
            ParsedPayload(records, rowCount)
        }
    }

    private fun parseTranslationSnapshot(
        file: File,
        checkCancelled: () -> Unit = {}
    ): ParsedSnapshot<Map<String, String>> {
        if (!file.exists()) return ParsedSnapshot.Missing
        return parseSnapshot(file, ImagePromptTagDictionaryKind.TRANSLATIONS, checkCancelled) {
            val translations = LinkedHashMap<String, String>()
            val rowCount = readImagePromptTagCsvRows(
                file = file,
                maximumRows = ImagePromptTagAutocomplete.MAX_DICTIONARY_ENTRIES,
                checkCancelled = checkCancelled
            ) { row ->
                val parsed = ImagePromptTagCsv.parseTranslationUtf8Line(row)
                    ?: throw ImagePromptTagStoreValidationException(
                        ImagePromptTagStoreIssue.MALFORMED_CSV
                    )
                translations[parsed.tag] = parsed.translation
            }
            ParsedPayload(translations, rowCount)
        }
    }

    private fun <T> parseSnapshot(
        file: File,
        kind: ImagePromptTagDictionaryKind,
        checkCancelled: () -> Unit,
        readPayload: () -> ParsedPayload<T>
    ): ParsedSnapshot<T> {
        if (!file.isFile) return ParsedSnapshot.ReadFailure
        if (file.length() > kind.maximumBytes) {
            return ParsedSnapshot.Invalid(ImagePromptTagStoreIssue.FILE_TOO_LARGE)
        }
        return try {
            checkCancelled()
            val parsed = readPayload()
            if (parsed.rowCount == 0) {
                ParsedSnapshot.Invalid(ImagePromptTagStoreIssue.EMPTY_DICTIONARY)
            } else {
                ParsedSnapshot.Valid(
                    payload = parsed.payload,
                    rowCount = parsed.rowCount,
                    byteCount = file.length()
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (validation: ImagePromptTagStoreValidationException) {
            ParsedSnapshot.Invalid(validation.issue)
        } catch (_: Exception) {
            ParsedSnapshot.ReadFailure
        }
    }

    private enum class ImportPhase {
        COPY,
        VALIDATE,
        PUBLISH
    }

    private data class ValidatedImport(
        val loadResult: ImagePromptTagStoreLoadResult
    )
}

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

internal fun isSafeImagePromptTagContentUri(rawUri: String): Boolean = try {
    val uri = URI(rawUri)
    !uri.isOpaque &&
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) &&
        !uri.authority.isNullOrBlank() &&
        uri.path.orEmpty().split('/').none { segment ->
            segment == "." || segment == ".." || '\\' in segment
        }
} catch (_: Exception) {
    false
}

internal fun imagePromptTagStoreFile(
    root: File,
    kind: ImagePromptTagDictionaryKind
): File = requireNotNull(safeImagePromptTagStoreChild(root, kind.fileName))

internal fun safeImagePromptTagStoreChild(root: File, fileName: String): File? {
    if (fileName !in ImagePromptTagDictionaryKind.entries.map { it.fileName }) return null
    return try {
        val canonicalRoot = root.canonicalFile
        File(canonicalRoot, fileName).canonicalFile.takeIf { it.parentFile == canonicalRoot }
    } catch (_: Exception) {
        null
    }
}

internal fun copyImagePromptTagStreamBounded(
    input: InputStream,
    output: OutputStream,
    maximumBytes: Long,
    checkCancelled: () -> Unit = {}
): Long {
    require(maximumBytes > 0L) { "Copy limit must be positive." }
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var copied = 0L
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (copied > maximumBytes - count) {
            throw ImagePromptTagStoreValidationException(ImagePromptTagStoreIssue.FILE_TOO_LARGE)
        }
        output.write(buffer, 0, count)
        copied += count
    }
    return copied
}

internal fun replaceImagePromptTagSnapshot(
    staged: File,
    target: File,
    moveFile: (source: File, destination: File, atomic: Boolean) -> Unit = ::moveImagePromptTagFile,
    copyAndSync: (source: File, destination: File) -> Unit = ::copyAndSyncImagePromptTagFile,
    syncDirectory: (File) -> Unit = ::syncImagePromptTagDirectory
) {
    val stagedCanonical = staged.canonicalFile
    val targetCanonical = target.canonicalFile
    val parent = requireNotNull(targetCanonical.parentFile)
    require(stagedCanonical.parentFile == parent && parent.isDirectory) {
        "Dictionary replacement must stay inside one directory."
    }
    require(stagedCanonical.isFile && stagedCanonical != targetCanonical) {
        "Dictionary staging file is invalid."
    }
    require(!targetCanonical.exists() || targetCanonical.isFile) {
        "Dictionary target must be a regular file."
    }

    val hadPrevious = targetCanonical.isFile
    val backup = if (hadPrevious) {
        File(parent, ".${targetCanonical.name}.${UUID.randomUUID()}.rollback.tmp")
    } else {
        null
    }
    var backupReady = false
    var publishStarted = false
    var committed = false
    var rolledBack = false
    try {
        if (backup != null) {
            copyAndSync(targetCanonical, backup)
            backupReady = true
        }
        publishStarted = true
        moveWithAtomicFallback(stagedCanonical, targetCanonical, moveFile)
        check(!stagedCanonical.exists() && targetCanonical.isFile) {
            "Dictionary move did not publish its target."
        }
        syncDirectory(parent)
        committed = true
    } catch (failure: Throwable) {
        if (backupReady && publishStarted && backup != null) {
            try {
                if (targetCanonical.exists() && !targetCanonical.delete()) {
                    throw IOException("Unable to remove failed dictionary publication.")
                }
                moveWithAtomicFallback(backup, targetCanonical, moveFile)
                syncDirectory(parent)
                rolledBack = true
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
        } else if (!hadPrevious && publishStarted) {
            try {
                if (targetCanonical.exists() && !targetCanonical.delete()) {
                    throw IOException("Unable to remove failed dictionary publication.")
                }
                syncDirectory(parent)
            } catch (cleanupFailure: Exception) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        throw failure
    } finally {
        stagedCanonical.delete()
        if (backup != null && (committed || rolledBack || !backupReady)) {
            if (backup.delete() && committed) {
                try {
                    syncDirectory(parent)
                } catch (_: Exception) {
                    // Publication is already durable; stale directory metadata is non-fatal.
                }
            }
        }
    }
}

private fun moveWithAtomicFallback(
    source: File,
    target: File,
    moveFile: (source: File, destination: File, atomic: Boolean) -> Unit
) {
    try {
        moveFile(source, target, true)
    } catch (_: AtomicMoveNotSupportedException) {
        moveFile(source, target, false)
    }
}

private fun moveImagePromptTagFile(source: File, destination: File, atomic: Boolean) {
    val options = if (atomic) {
        arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        arrayOf(StandardCopyOption.REPLACE_EXISTING)
    }
    Files.move(source.toPath(), destination.toPath(), *options)
}

private fun copyAndSyncImagePromptTagFile(source: File, destination: File) {
    FileInputStream(source).use { input ->
        FileOutputStream(destination).use { output ->
            input.copyTo(output, COPY_BUFFER_BYTES)
            output.flush()
            output.fd.sync()
        }
    }
}

private fun syncImagePromptTagDirectory(directory: File) {
    val canonical = directory.canonicalFile
    require(canonical.isDirectory) { "Dictionary parent must be a directory." }
    val descriptor = try {
        Os.open(canonical.path, OsConstants.O_RDONLY, 0)
    } catch (error: ErrnoException) {
        throw IOException("Unable to open dictionary directory for sync.", error)
    }
    try {
        Os.fsync(descriptor)
    } catch (error: ErrnoException) {
        throw IOException("Unable to sync dictionary directory.", error)
    } finally {
        try {
            Os.close(descriptor)
        } catch (_: Exception) {
            // Preserve the primary sync outcome.
        }
    }
}

private fun readImagePromptTagCsvRows(
    file: File,
    maximumRows: Int,
    checkCancelled: () -> Unit,
    consumeRow: (ByteArray) -> Unit
): Int {
    val current = ByteArrayOutputStream()
    var physicalLineCount = 0
    var validRowCount = 0
    fun finishLine() {
        physicalLineCount++
        if (physicalLineCount > maximumRows) {
            throw ImagePromptTagStoreValidationException(ImagePromptTagStoreIssue.TOO_MANY_LINES)
        }
        val raw = current.toByteArray()
        current.reset()
        val withoutCarriageReturn = if (raw.lastOrNull() == '\r'.code.toByte()) {
            raw.copyOf(raw.size - 1)
        } else {
            raw
        }
        if (withoutCarriageReturn.all { it == ' '.code.toByte() || it == '\t'.code.toByte() }) {
            return
        }
        consumeRow(withoutCarriageReturn)
        validRowCount++
    }

    FileInputStream(file).buffered(COPY_BUFFER_BYTES).use { input ->
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == '\n'.code.toByte()) {
                    finishLine()
                } else {
                    if (current.size() >= ImagePromptTagCsv.MAX_UTF8_LINE_BYTES) {
                        throw ImagePromptTagStoreValidationException(
                            ImagePromptTagStoreIssue.MALFORMED_CSV
                        )
                    }
                    current.write(byte.toInt())
                }
            }
        }
    }
    if (current.size() > 0) finishLine()
    return validRowCount
}

private data class ParsedPayload<T>(
    val payload: T,
    val rowCount: Int
)

private sealed interface ParsedSnapshot<out T> {
    data object Missing : ParsedSnapshot<Nothing>

    data class Valid<T>(
        val payload: T,
        val rowCount: Int,
        val byteCount: Long
    ) : ParsedSnapshot<T>

    data class Invalid(val issue: ImagePromptTagStoreIssue) : ParsedSnapshot<Nothing>

    data object ReadFailure : ParsedSnapshot<Nothing>
}

private fun ParsedSnapshot<*>.toState(): ImagePromptTagDictionaryState = when (this) {
    ParsedSnapshot.Missing -> ImagePromptTagDictionaryState.NotConfigured
    is ParsedSnapshot.Valid<*> -> ImagePromptTagDictionaryState.Available(rowCount, byteCount)
    is ParsedSnapshot.Invalid -> ImagePromptTagDictionaryState.Corrupt(issue)
    ParsedSnapshot.ReadFailure -> ImagePromptTagDictionaryState.ReadFailed
}

private fun <T> ParsedSnapshot<T>.requireValid(): T = when (this) {
    is ParsedSnapshot.Valid -> payload
    is ParsedSnapshot.Invalid -> throw ImagePromptTagStoreValidationException(issue)
    ParsedSnapshot.Missing -> throw ImagePromptTagStoreValidationException(
        ImagePromptTagStoreIssue.EMPTY_DICTIONARY
    )
    ParsedSnapshot.ReadFailure -> throw ImagePromptTagStoreValidationException(
        ImagePromptTagStoreIssue.STORAGE_READ_FAILED
    )
}

private fun <T> ParsedSnapshot<T>.validPayloadOrNull(): T? =
    (this as? ParsedSnapshot.Valid<T>)?.payload

private fun ImagePromptTagStoreStatus.withState(
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

private const val STORE_DIRECTORY_NAME: String = "image_prompt_tag_autocomplete"
private const val COPY_BUFFER_BYTES: Int = 32 * 1024
private const val STORE_CANCELLATION_CHECK_INTERVAL: Int = 1_024
