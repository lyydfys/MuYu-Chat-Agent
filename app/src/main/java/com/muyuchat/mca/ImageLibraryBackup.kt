package com.muyuchat.mca

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal fun canonicalImageAssetDirectory(filesDirectory: File): File {
    val filesRoot = filesDirectory.canonicalFile
    val configured = File(filesRoot, "image_assets").absoluteFile
    require(configured.parentFile == filesRoot) { "Image asset directory escaped app files." }
    require(!Files.isSymbolicLink(configured.toPath())) {
        "Image asset directory must not be a symbolic link."
    }
    return configured.canonicalFile.also { canonical ->
        require(canonical.parentFile == filesRoot) { "Image asset directory escaped app files." }
    }
}

internal fun deleteOwnedTreeWithoutFollowingSymbolicLinks(root: File, candidate: File): Boolean =
    runCatching {
        val canonicalRoot = root.canonicalFile
        val absoluteCandidate = candidate.absoluteFile
        if (absoluteCandidate.parentFile != canonicalRoot) return@runCatching false
        val path = absoluteCandidate.toPath()
        if (Files.isSymbolicLink(path)) {
            return@runCatching Files.deleteIfExists(path)
        }
        val canonicalCandidate = absoluteCandidate.canonicalFile
        if (canonicalCandidate.parentFile != canonicalRoot ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            return@runCatching false
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: IOException?
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(directory)
                    return FileVisitResult.CONTINUE
                }
            }
        )
        !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }.getOrDefault(false)

private data class ReadImageLibraryBackupManifest(
    val parsed: ParsedImageLibraryBackupManifest,
    val sha256: String
)

internal class ImageLibraryBackup(
    context: Context,
    private val store: ChatSessionStore
) {
    private val appContext = context.applicationContext

    /** Caller must serialize this with image-library mutations before supplying its snapshot. */
    suspend fun reconcile(existingImages: List<ImageAssetRecord>) = withContext(Dispatchers.IO) {
        val root = imageRoot().apply { mkdirs() }.canonicalFile
        cleanStaleImportDirectories(root)
        val referencedPaths = existingImages.mapNotNullTo(mutableSetOf()) { image ->
            canonicalOwnedImagePathOrNull(root, image.uriString)
        }
        cleanUnreferencedRestoredImages(root, referencedPaths)
    }

    suspend fun export(
        destination: Uri,
        images: List<ImageAssetRecord>,
        favoritesOnly: Boolean,
        onProgress: (done: Int, total: Int) -> Unit
    ): ImageLibraryBackupExportResult = withContext(Dispatchers.IO) {
        val selected = images
            .asSequence()
            .filter { !favoritesOnly || it.favorite }
            .distinctBy(ImageAssetRecord::id)
            .toList()
        val imageRoot = imageRoot()
        val prepared = mutableListOf<PreparedImageExport>()
        var preparedBytes = 0L
        selected.forEach { image ->
            ensureActive()
            if (prepared.size >= MAX_BACKUP_ITEMS) return@forEach
            val item = prepareExportItem(imageRoot, image) ?: return@forEach
            if (item.byteSize > MAX_TOTAL_IMAGE_BYTES - preparedBytes) return@forEach
            prepared += item
            preparedBytes += item.byteSize
        }
        val skipped = selected.size - prepared.size
        val manifestItems = JSONArray().apply {
            prepared.forEach { item -> put(item.manifestJson) }
        }
        val manifestBytes = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("items", manifestItems)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (manifestBytes.size > MAX_MANIFEST_BYTES) {
            throw IOException("备份清单过大")
        }
        val output = appContext.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("无法打开备份目标")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.setLevel(Deflater.NO_COMPRESSION)
            prepared.forEachIndexed { index, item ->
                ensureActive()
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                zip.putNextEntry(
                    ZipEntry(item.entryPath).apply { time = item.record.createdAt.coerceAtLeast(0L) }
                )
                try {
                    BufferedInputStream(item.file.inputStream()).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_IMAGE_BYTES) {
                                throw IOException("备份图片超过单文件大小限制")
                            }
                            digest.update(buffer, 0, read)
                            zip.write(buffer, 0, read)
                        }
                    }
                } finally {
                    zip.closeEntry()
                }
                if (copied != item.byteSize || digest.digest().toLowerHex() != item.sha256) {
                    throw IOException("图片在导出期间发生变化，请重试")
                }
                onProgress(index + 1, prepared.size)
            }
        }
        ImageLibraryBackupExportResult(exported = prepared.size, skipped = skipped)
    }

    suspend fun import(
        source: Uri,
        existingImages: List<ImageAssetRecord>,
        installedModelIds: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit
    ): ImageLibraryBackupImportResult = withContext(Dispatchers.IO) {
        reconcile(existingImages)
        val manifestRead = readManifest(source)
        val parsed = manifestRead.parsed
        val existingIds = existingImages.mapTo(mutableSetOf(), ImageAssetRecord::id)
        val seenIds = mutableSetOf<String>()
        val pending = linkedMapOf<String, ImageLibraryBackupManifestItem>()
        var duplicates = 0
        var failed = parsed.invalidItems
        var expectedTotalBytes = 0L
        parsed.items.forEach { item ->
            when {
                item.id in existingIds || !seenIds.add(item.id) -> duplicates++
                item.entryPath in pending -> failed++
                else -> {
                    expectedTotalBytes = safeAddBackupBytes(expectedTotalBytes, item.byteSize)
                    pending[item.entryPath] = item
                }
            }
        }
        if (expectedTotalBytes > MAX_TOTAL_IMAGE_BYTES) {
            throw ImageLibraryBackupFormatException("备份图片总大小超过限制")
        }

        val imageRoot = imageRoot().apply { mkdirs() }.canonicalFile
        if (pending.isEmpty()) {
            return@withContext ImageLibraryBackupImportResult(
                imported = 0,
                duplicates = duplicates,
                failed = failed,
                missingModelIds = emptySet()
            )
        }
        validateImageLibraryImportSpaceBudget(
            expectedImageBytes = expectedTotalBytes,
            usableBytes = imageRoot.usableSpace
        )

        val stageDir = File(imageRoot, ".backup-import-${UUID.randomUUID()}").canonicalFile
        if (stageDir.parentFile != imageRoot || !stageDir.mkdirs()) {
            throw IOException("无法创建图片恢复临时目录")
        }
        val prepared = mutableListOf<PreparedImageImport>()
        val seenArchiveEntries = mutableSetOf<String>()
        var processed = 0
        val totalPending = pending.size
        try {
            openZip(source).use { zip ->
                var archiveBytes = 0L
                var archiveEntryIndex = 0
                while (true) {
                    ensureActive()
                    val entry = zip.nextEntry() ?: break
                    validateArchiveEntryName(entry.name)
                    if (entry.isDirectory) {
                        throw ImageLibraryBackupFormatException("备份不能包含目录条目")
                    }
                    if (!seenArchiveEntries.add(entry.name)) {
                        throw ImageLibraryBackupFormatException("备份包含重复条目：${entry.name}")
                    }
                    if (archiveEntryIndex++ == 0) {
                        if (entry.name != MANIFEST_NAME) {
                            throw ImageLibraryBackupFormatException("manifest.json 必须是备份的首个条目")
                        }
                        val manifestBytes = readBounded(zip, MAX_MANIFEST_BYTES)
                        if (manifestBytes.sha256Hex() != manifestRead.sha256) {
                            throw ImageLibraryBackupFormatException("备份清单在读取期间发生变化")
                        }
                        zip.closeEntry()
                        continue
                    }
                    if (entry.name == MANIFEST_NAME || entry.name !in parsed.declaredEntryPaths) {
                        throw ImageLibraryBackupFormatException("备份包含清单外条目：${entry.name}")
                    }
                    val item = pending.remove(entry.name)
                    val remainingArchiveBytes = MAX_TOTAL_IMAGE_BYTES - archiveBytes
                    if (item != null) {
                        if (item.byteSize > remainingArchiveBytes) {
                            throw ImageLibraryBackupFormatException("备份图片总大小超过限制")
                        }
                        val staged = stageImage(zip, stageDir, imageRoot, item)
                        if (staged == null) {
                            failed++
                        } else {
                            prepared += staged
                        }
                        archiveBytes = safeAddBackupBytes(archiveBytes, item.byteSize)
                        processed++
                        onProgress(processed, totalPending)
                    } else {
                        archiveBytes = safeAddBackupBytes(
                            archiveBytes,
                            discardBounded(zip, minOf(MAX_IMAGE_BYTES, remainingArchiveBytes))
                        )
                    }
                    if (archiveBytes > MAX_TOTAL_IMAGE_BYTES) {
                        throw ImageLibraryBackupFormatException("备份图片总大小超过限制")
                    }
                    zip.closeEntry()
                }
                if (archiveEntryIndex == 0) {
                    throw ImageLibraryBackupFormatException("备份缺少 manifest.json")
                }
            }
            failed += pending.size
            if (pending.isNotEmpty()) onProgress(totalPending, totalPending)
            val moved = mutableListOf<File>()
            try {
                prepared.forEach { item ->
                    ensureActive()
                    if (item.finalFile.exists()) {
                        throw IOException("无法原子发布恢复图片：${item.finalFile.name}")
                    }
                    durableMoveFromChildDirectory(
                        source = item.stagedFile,
                        target = item.finalFile,
                        move = { staged, published ->
                            if (!staged.renameTo(published)) {
                                throw IOException("Unable to atomically publish restored image ${published.name}")
                            }
                        }
                    )
                    moved += item.finalFile
                }
                val records = prepared.map(PreparedImageImport::record)
                store.upsertImages(records)
            } catch (error: Throwable) {
                val publishedBeforeFailure = prepared
                    .filter { item -> !item.stagedFile.exists() && item.finalFile.isFile }
                    .map(PreparedImageImport::finalFile)
                (moved + publishedBeforeFailure)
                    .distinctBy(File::getPath)
                    .forEach { file -> runCatching { file.delete() } }
                throw error
            }
        } finally {
            deleteOwnedTreeWithoutFollowingSymbolicLinks(imageRoot, stageDir)
        }
        val missingModelIds = prepared
            .mapNotNull { it.modelId }
            .filterNot(installedModelIds::contains)
            .toSet()
        ImageLibraryBackupImportResult(
            imported = prepared.size,
            duplicates = duplicates,
            failed = failed,
            missingModelIds = missingModelIds
        )
    }

    private suspend fun readManifest(source: Uri): ReadImageLibraryBackupManifest {
        val manifestBytes = openZip(source).use { zip ->
            currentCoroutineContext().ensureActive()
            val entry = zip.nextEntry()
                ?: throw ImageLibraryBackupFormatException("备份缺少 manifest.json")
            validateArchiveEntryName(entry.name)
            if (entry.isDirectory || entry.name != MANIFEST_NAME) {
                throw ImageLibraryBackupFormatException("manifest.json 必须是备份的首个条目")
            }
            val bytes = readBounded(zip, MAX_MANIFEST_BYTES)
            zip.closeEntry()
            bytes
        }
        val manifest = runCatching { JSONObject(manifestBytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw ImageLibraryBackupFormatException("manifest.json 无法解析") }
        return ReadImageLibraryBackupManifest(
            parsed = parseImageLibraryBackupManifest(manifest),
            sha256 = manifestBytes.sha256Hex()
        )
    }

    private fun openZip(source: Uri): ImageLibraryBackupZipInput {
        val input = appContext.contentResolver.openInputStream(source)
            ?: throw IOException("无法打开备份文件")
        return ImageLibraryBackupZipInput(input)
    }

    private fun imageRoot(): File = canonicalImageAssetDirectory(appContext.filesDir)

    private suspend fun stageImage(
        zip: ImageLibraryBackupZipInput,
        stageDir: File,
        imageRoot: File,
        item: ImageLibraryBackupManifestItem
    ): PreparedImageImport? {
        val stagedFile = File(stageDir, "${item.id}.${item.extension}.part").canonicalFile
        if (stagedFile.parentFile != stageDir) {
            throw ImageLibraryBackupFormatException("恢复条目路径无效")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        return try {
            FileOutputStream(stagedFile).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = zip.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > item.byteSize || copied > MAX_IMAGE_BYTES) {
                        throw ImageLibraryBackupFormatException("备份图片大小与清单不一致")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.flush()
                fileOutput.fd.sync()
            }
            if (copied != item.byteSize || digest.digest().toLowerHex() != item.sha256) {
                stagedFile.delete()
                return null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stagedFile.absolutePath, bounds)
            if (bounds.outWidth !in 1..MAX_IMAGE_SIDE ||
                bounds.outHeight !in 1..MAX_IMAGE_SIDE ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_IMAGE_PIXELS
            ) {
                stagedFile.delete()
                return null
            }
            val finalFile = uniqueRestoredFile(imageRoot, item.id, item.extension)
            val record = ImageAssetRecord(
                id = item.id,
                name = item.name,
                uriString = Uri.fromFile(finalFile).toString(),
                source = item.source,
                prompt = item.prompt,
                createdAt = item.createdAt,
                sizeBytes = copied,
                width = bounds.outWidth,
                height = bounds.outHeight,
                generationMetadataJson = item.generationMetadataJson,
                favorite = item.favorite,
                chatSessionId = null,
                projectId = null
            )
            PreparedImageImport(
                stagedFile = stagedFile,
                finalFile = finalFile,
                record = record,
                modelId = item.generationModelId
            )
        } catch (error: ImageLibraryBackupFormatException) {
            stagedFile.delete()
            throw error
        } catch (error: IOException) {
            stagedFile.delete()
            throw error
        }
    }

    private fun uniqueRestoredFile(root: File, id: String, extension: String): File {
        var candidate = File(root, "restored-$id.$extension").canonicalFile
        if (candidate.parentFile != root) {
            throw ImageLibraryBackupFormatException("恢复目标路径无效")
        }
        while (candidate.exists()) {
            candidate = File(root, "restored-$id-${UUID.randomUUID().toString().take(8)}.$extension")
                .canonicalFile
            if (candidate.parentFile != root) {
                throw ImageLibraryBackupFormatException("恢复目标路径无效")
            }
        }
        return candidate
    }

    private suspend fun prepareExportItem(root: File, image: ImageAssetRecord): PreparedImageExport? {
        val file = ownedImageFileOrNull(root, image) ?: return null
        if (file.length() !in 1..MAX_IMAGE_BYTES) return null
        if (image.name.length > MAX_NAME_CHARS ||
            image.source.length > MAX_SOURCE_CHARS ||
            image.prompt.length > MAX_PROMPT_CHARS
        ) {
            return null
        }
        val extension = file.extension.lowercase().takeIf(ALLOWED_EXTENSIONS::contains) ?: return null
        val entryPath = "images/${image.id}.$extension"
        if (!IMAGE_ENTRY_REGEX.matches(entryPath)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth !in 1..MAX_IMAGE_SIDE ||
            bounds.outHeight !in 1..MAX_IMAGE_SIDE ||
            bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_IMAGE_PIXELS
        ) {
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                byteSize += read
                if (byteSize > MAX_IMAGE_BYTES) return null
                digest.update(buffer, 0, read)
            }
        }
        val sha256 = digest.digest().toLowerHex()
        return PreparedImageExport(
            record = image,
            file = file,
            entryPath = entryPath,
            byteSize = byteSize,
            sha256 = sha256,
            manifestJson = image.toBackupManifestJson(
                entryPath = entryPath,
                byteSize = byteSize,
                sha256 = sha256,
                width = bounds.outWidth,
                height = bounds.outHeight
            )
        )
    }

    private fun ownedImageFileOrNull(root: File, image: ImageAssetRecord): File? = runCatching {
        val uri = Uri.parse(image.uriString)
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val file = File(requireNotNull(uri.path)).canonicalFile
        file.takeIf { it.parentFile == root.canonicalFile && it.isFile }
    }.getOrNull()

    private fun canonicalOwnedImagePathOrNull(root: File, rawReference: String): String? = runCatching {
        val uri = Uri.parse(rawReference)
        if (!uri.scheme.equals("file", ignoreCase = true)) return@runCatching null
        val file = File(requireNotNull(uri.path)).canonicalFile
        file.path.takeIf { file.parentFile == root }
    }.getOrNull()

    private fun cleanUnreferencedRestoredImages(root: File, referencedCanonicalPaths: Set<String>) {
        unreferencedRestoredImageFiles(root, referencedCanonicalPaths).forEach { file ->
            runCatching { file.delete() }
        }
    }

    private fun cleanStaleImportDirectories(root: File) {
        root.listFiles()
            ?.filter { it.name.startsWith(".backup-import-") }
            ?.forEach { directory ->
                deleteOwnedTreeWithoutFollowingSymbolicLinks(root, directory)
            }
    }

    private data class PreparedImageImport(
        val stagedFile: File,
        val finalFile: File,
        val record: ImageAssetRecord,
        val modelId: String?
    )

    private data class PreparedImageExport(
        val record: ImageAssetRecord,
        val file: File,
        val entryPath: String,
        val byteSize: Long,
        val sha256: String,
        val manifestJson: JSONObject
    )
}

private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toLowerHex()

private const val MANIFEST_NAME = "manifest.json"
internal const val FORMAT = "mca-image-library-backup"
internal const val VERSION = 1
internal const val COPY_BUFFER_BYTES = 64 * 1024
private const val MAX_MANIFEST_BYTES = ImageLibraryBackupLimits.MAX_MANIFEST_BYTES
internal const val MAX_BACKUP_ITEMS = ImageLibraryBackupLimits.MAX_BACKUP_ITEMS
internal const val MAX_IMAGE_BYTES = ImageLibraryBackupLimits.MAX_IMAGE_BYTES
private const val MAX_TOTAL_IMAGE_BYTES = ImageLibraryBackupLimits.MAX_TOTAL_IMAGE_BYTES
internal const val MAX_IMAGE_SIDE = ImageLibraryBackupLimits.MAX_IMAGE_SIDE
internal const val MAX_IMAGE_PIXELS = ImageLibraryBackupLimits.MAX_IMAGE_PIXELS
internal const val MAX_ENTRY_PATH_CHARS = 192
internal const val MAX_NAME_CHARS = 256
internal const val MAX_SOURCE_CHARS = 256
internal const val MAX_PROMPT_CHARS = ImageLibraryBackupLimits.MAX_PROMPT_CHARS
private val ALLOWED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "heic", "heif")
internal val IMAGE_ENTRY_REGEX =
    Regex("images/([0-9a-fA-F-]{36})\\.(png|jpg|jpeg|webp|heic|heif)")
