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

private const val STORE_DIRECTORY_NAME: String = "image_prompt_tag_autocomplete"
