package com.muyuchat.mca

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.muyuchat.core.engine.deduplicateVisionAttachments
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun displayNameForUri(contentResolver: ContentResolver, uri: Uri): String {
    val queriedName = if (uri.scheme.equals("content", ignoreCase = true)) {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    } else {
        null
    }
    return queriedName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "上传文件"
}

internal fun isImageAttachment(contentResolver: ContentResolver, uri: Uri, name: String): Boolean {
    val mime = contentResolver.getType(uri).orEmpty().lowercase()
    val lowerName = name.lowercase()
    return mime.startsWith("image/") ||
        lowerName.endsWith(".jpg") ||
        lowerName.endsWith(".jpeg") ||
        lowerName.endsWith(".png") ||
        lowerName.endsWith(".webp") ||
        lowerName.endsWith(".heic") ||
        lowerName.endsWith(".heif")
}

internal fun readAttachmentText(contentResolver: ContentResolver, uri: Uri, maxBytes: Int = 64 * 1024): Pair<String, Boolean> {
    val output = ByteArrayOutputStream()
    var truncated = false
    contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = maxBytes - total
            if (read > remaining) {
                if (remaining > 0) output.write(buffer, 0, remaining)
                truncated = true
                break
            }
            output.write(buffer, 0, read)
            total += read
            if (total >= maxBytes) {
                truncated = input.read() >= 0
                break
            }
        }
    } ?: error("无法读取文件")
    return decodeImportedText(output.toByteArray()) to truncated
}

internal fun createFileAsset(
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    text: String,
    truncated: Boolean,
    source: String,
    chatSessionId: String? = null
): FileAssetRecord =
    FileAssetRecord(
        id = UUID.randomUUID().toString(),
        name = displayName.ifBlank { "文件" },
        mimeType = mimeTypeForTextAttachment(contentResolver, uri, displayName),
        text = text.trim(),
        preview = text.toFilePreview(),
        truncated = truncated,
        source = source,
        sizeBytes = sizeForUri(contentResolver, uri).takeIf { it > 0L } ?: text.toByteArray(Charsets.UTF_8).size.toLong(),
        chatSessionId = chatSessionId
    )

internal suspend fun importImageAsset(
    contentResolver: ContentResolver,
    imageAssetWriteMutex: Mutex,
    imageAssetDirectory: File,
    uri: Uri,
    displayName: String,
    source: String,
    prompt: String = "",
    chatSessionId: String? = null
): ImageAssetRecord {
    val extension = imageExtension(displayName)
    val imported = imageAssetWriteMutex.withLock {
        val input = if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let { File(it).inputStream() }
        } else {
            contentResolver.openInputStream(uri)
        } ?: error("无法读取图片")
        input.use { sourceStream ->
            copyImageAssetStreamAtomically(
                directory = imageAssetDirectory,
                suggestedExtension = extension,
                input = sourceStream
            )
        }
    }
    return ImageAssetRecord(
        id = UUID.randomUUID().toString(),
        name = displayName.ifBlank { "图片" },
        uriString = Uri.fromFile(imported.file).toString(),
        source = source,
        prompt = prompt,
        sizeBytes = imported.sizeBytes,
        width = imported.width,
        height = imported.height,
        chatSessionId = chatSessionId
    )
}

internal fun attachFileFor(
    store: UiStateWriter<ChatAttachmentFields>,
    scope: CoroutineScope,
    contentResolver: ContentResolver,
    imageAssetWriteMutex: Mutex,
    imageAssetDirectory: File,
    imageLibraryCommitPort: ImageLibraryCommitPort,
    awaitImageLibraryStartupReconciliation: suspend () -> Unit,
    prepareChatInput: (MainUiState) -> PreparedChatInput,
    persistFiles: (List<FileAssetRecord>) -> Unit,
    showStatus: (String) -> Unit,
    fail: (String) -> Unit,
    uriString: String
) {
    if (store.state.value.isGenerating) {
        showStatus("请先停止当前生成，再上传文件")
        return
    }
    scope.launch(Dispatchers.IO) {
        val result = runCatching {
            val uri = Uri.parse(uriString)
            val name = displayNameForUri(contentResolver, uri)
            if (isImageAttachment(contentResolver, uri, name)) {
                awaitImageLibraryStartupReconciliation()
                val image = importImageAsset(
                    contentResolver = contentResolver,
                    imageAssetWriteMutex = imageAssetWriteMutex,
                    imageAssetDirectory = imageAssetDirectory,
                    uri = uri,
                    displayName = name,
                    source = "uploaded",
                    chatSessionId = store.state.value.activeChatSessionId
                )
                return@runCatching AttachmentImportResult(name, image.toInputAttachment(), false, image)
            }
            if (!isSupportedTextAttachment(contentResolver, uri, name)) {
                error("当前上传入口暂支持文本、Markdown、JSON、XML 等文本类文件")
            }
            val (text, truncated) = readAttachmentText(contentResolver, uri)
            if (text.isBlank()) error("文件内容为空或无法作为文本读取")
            val file = createFileAsset(
                contentResolver = contentResolver,
                uri = uri,
                displayName = name,
                text = text,
                truncated = truncated,
                source = "uploaded",
                chatSessionId = store.state.value.activeChatSessionId
            )
            AttachmentImportResult(name, file.text, file.truncated, fileAsset = file)
        }
        result.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        result.onSuccess { imported ->
            fun publishImportedAttachment(): List<FileAssetRecord>? {
                return store.updateWithResult { state ->
                    val importedAttachment = imported.imageAsset?.toChatAttachment()
                    val alreadyAttached = importedAttachment != null &&
                        prepareChatInput(state.snapshot).imageAttachments.any { existing ->
                            listOf(existing, importedAttachment)
                                .deduplicateVisionAttachments()
                                .size == 1
                        }
                    val attachment = buildString {
                        if (!alreadyAttached) {
                            if (state.input.isNotBlank()) append("\n\n")
                            if (imported.imageAsset != null) {
                                append(imported.text.trim())
                            } else if (imported.fileAsset != null) {
                                append(imported.fileAsset.toInputAttachment())
                            } else {
                                append("【上传文件：").append(imported.name).append("】\n")
                                append(imported.text.trim())
                                if (imported.truncated) append("\n\n（文件较大，已截取前 64KB）")
                            }
                        }
                    }
                    val updatedImages = imported.imageAsset?.let { image ->
                        (listOf(image) + state.images.filterNot { it.id == image.id })
                            .sortedImagesForLibrary()
                    }
                    val updatedFiles = imported.fileAsset?.let { file ->
                        (listOf(file) + state.files.filterNot { it.id == file.id })
                            .sortedFilesForLibrary()
                    }
                    state.copy(
                        input = state.input + attachment,
                        images = updatedImages ?: state.images,
                        files = updatedFiles ?: state.files,
                        statusMessage = if (imported.imageAsset != null && alreadyAttached) {
                            "这张图片已在当前消息中，未重复添加：${imported.name}"
                        } else if (imported.imageAsset != null) {
                            "已添加图片：${imported.name}"
                        } else {
                            "已添加文件：${imported.name}"
                        }
                    ) to updatedFiles
                }
            }

            val filesToPersist = imported.imageAsset?.let { image ->
                val commit = runCatching {
                    upsertImportedImageUnderMutationLock(imageLibraryCommitPort, image) {
                        publishImportedAttachment()
                    }
                }
                val error = commit.exceptionOrNull()
                if (error != null) {
                    image.deleteLocalCopy(imageAssetDirectory)
                    if (error is CancellationException) throw error
                    fail("图片库保存失败：${error.message ?: "数据库提交失败"}")
                    return@onSuccess
                }
                commit.getOrThrow()
            } ?: publishImportedAttachment()
            filesToPersist?.let { persistFiles(it) }
        }.onFailure { error ->
            showStatus(error.message ?: "文件上传失败")
        }
    }
}

internal fun deleteFileAssetFor(
    store: UiStateWriter<ChatAttachmentFields>,
    persistFiles: (List<FileAssetRecord>) -> Unit,
    fileId: String
) {
    val committed = store.updateAndGet { state ->
        val removed = state.files.firstOrNull { it.id == fileId }
        val files = state.files.filterNot { it.id == fileId }
        state.copy(files = files, statusMessage = "已从文件库移除：${removed?.name ?: "文件"}")
    }
    persistFiles(committed.files)
}

internal fun clearFileLibraryFor(
    store: UiStateWriter<ChatAttachmentFields>,
    scope: CoroutineScope,
    showStatus: (String) -> Unit,
    persistFiles: suspend (List<FileAssetRecord>) -> Result<Unit>
): Job? {
    val filesToRemove = store.state.value.files
    if (filesToRemove.isEmpty()) {
        showStatus("文件库已经为空")
        return null
    }
    return scope.launch {
        val persistence = runCatching { persistFiles(emptyList()) }
            .getOrElse { failure ->
                if (failure is CancellationException) throw failure
                return@launch
            }
        if (persistence.isFailure) return@launch

        val currentFiles = store.state.value.files
        if (currentFiles != filesToRemove) {
            persistFiles(currentFiles)
            showStatus("文件库保存失败：清空期间文件库发生变化")
            return@launch
        }
        store.update {
            it.copy(
                files = emptyList(),
                statusMessage = "已清空文件库：${filesToRemove.size} 个文件"
            )
        }
    }
}

