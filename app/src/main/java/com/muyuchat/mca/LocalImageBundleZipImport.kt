package com.muyuchat.mca

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Bounded ZIP extraction and import stream copying for local image bundles.
 *
 * Moved out of `LocalImageProvider.kt` verbatim: the entry-count/byte limits, the size-checked copy
 * helpers, the path normalisation, the directory creation, and the two entry points
 * (`extractBoundedImageBundleZip`, `extractImageBundleZipIntoDirectory`). Every limit, every rejection
 * message, and the order of the checks is unchanged.
 *
 * `copyImageImportFile` and `readImageImportText` are internal here because `LocalImageProvider.kt`
 * still stages the MNN tokenizer sidecar with them; they are declared once, in this file.
 */
internal data class ImageBundleZipExtractionLimits(
    val maxEntryCount: Int = 32_768,
    val maxEntryBytes: Long = 32L * 1_024L * 1_024L * 1_024L,
    val maxTotalBytes: Long = 64L * 1_024L * 1_024L * 1_024L,
    val minFreeSpaceReserveBytes: Long = 64L * 1_024L * 1_024L
) {
    init {
        require(maxEntryCount >= 0) { "Image bundle zip entry limit must not be negative." }
        require(maxEntryBytes >= 0L) { "Image bundle zip entry byte limit must not be negative." }
        require(maxTotalBytes >= 0L) { "Image bundle zip total byte limit must not be negative." }
        require(minFreeSpaceReserveBytes >= 0L) {
            "Image bundle zip free-space reserve must not be negative."
        }
    }
}

internal data class ImageBundleZipExtractionResult(
    val extractedFiles: List<File>,
    val entryCount: Int,
    val extractedBytes: Long
)

/**
 * Extracts an image bundle without trusting zip directory sizes. Every entry path and every
 * expanded chunk is checked before bytes are committed to an app-owned target.
 */
internal fun copyImageImportInput(input: InputStream, output: OutputStream, checkCancelled: () -> Unit = {}) {
    val buffer = ByteArray(128 * 1_024)
    while (true) {
        checkCancelled()
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) output.write(buffer, 0, read)
    }
    checkCancelled()
}

internal fun copyImageImportFile(source: File, target: File, checkCancelled: () -> Unit) {
    if (source.canonicalFile == target.canonicalFile) return
    val temp = File(target.parentFile, "." + target.name + ".import-" + UUID.randomUUID())
    try {
        source.inputStream().use { input -> temp.outputStream().use { output -> copyImageImportInput(input, output, checkCancelled) } }
        checkCancelled()
        if (target.exists()) require(target.delete()) { "无法替换分词器文件：" + target.name }
        require(temp.renameTo(target)) { "无法保存分词器文件：" + target.name }
    } finally { if (temp.exists()) temp.delete() }
}

internal fun readImageImportText(file: File, checkCancelled: () -> Unit): String {
    val builder = StringBuilder()
    file.reader(Charsets.UTF_8).buffered().use { reader ->
        val chars = CharArray(16 * 1_024)
        while (true) {
            checkCancelled()
            val read = reader.read(chars)
            if (read < 0) break
            if (read > 0) builder.append(chars, 0, read)
        }
    }
    checkCancelled()
    return builder.toString()
}

internal fun extractBoundedImageBundleZip(
    source: InputStream,
    bundleDir: File,
    limits: ImageBundleZipExtractionLimits = ImageBundleZipExtractionLimits(),
    shouldSkipTarget: (File) -> Boolean = { false },
    usableSpaceProvider: (File) -> Long = { root -> root.usableSpace },
    copyBufferSize: Int = DEFAULT_BUFFER_SIZE,
    checkCancelled: () -> Unit = {}
): ImageBundleZipExtractionResult {
    checkCancelled()
    require(copyBufferSize > 0) { "Image bundle zip copy buffer must be positive." }
    val canonicalRoot = bundleDir.canonicalFile
    require(canonicalRoot.isDirectory) {
        "Image bundle extraction root must be an existing directory."
    }
    val rootPath = canonicalRoot.toPath()
    val archiveTargets = linkedSetOf<java.nio.file.Path>()
    val archiveFileTargets = linkedSetOf<java.nio.file.Path>()
    val createdFiles = mutableListOf<File>()
    val createdDirectories = mutableListOf<File>()
    val extractedFiles = mutableListOf<File>()
    var entryCount = 0
    var totalBytes = 0L
    var remainingWritableBudget = Long.MAX_VALUE

    try {
        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= limits.maxEntryCount) {
                    "Image bundle zip contains too many entries (limit=${limits.maxEntryCount})."
                }
                val relativePath = normalizeImageBundleZipEntryPath(entry.name, entry.isDirectory)
                val target = File(
                    canonicalRoot,
                    relativePath.replace('/', File.separatorChar)
                ).canonicalFile
                val targetPath = target.toPath()
                require(targetPath != rootPath && targetPath.startsWith(rootPath)) {
                    "Image bundle zip entry escapes the extraction root: $relativePath"
                }
                require(archiveTargets.add(targetPath)) {
                    "Image bundle zip contains a duplicate target: $relativePath"
                }
                var parentPath = targetPath.parent
                while (parentPath != null && parentPath != rootPath) {
                    require(parentPath !in archiveFileTargets) {
                        "Image bundle zip path conflicts with a parent file: $relativePath"
                    }
                    parentPath = parentPath.parent
                }
                if (!entry.isDirectory) {
                    require(archiveTargets.none { existing ->
                        existing != targetPath && existing.startsWith(targetPath)
                    }) {
                        "Image bundle zip file conflicts with an existing child path: $relativePath"
                    }
                    archiveFileTargets.add(targetPath)
                }

                val skipTarget = !entry.isDirectory && shouldSkipTarget(target)
                when {
                    skipTarget -> require(target.isFile) {
                        "Image bundle zip skip target must be an existing file: $relativePath"
                    }
                    entry.isDirectory && target.exists() -> require(
                        target.isDirectory && target in createdDirectories
                    ) {
                        "Image bundle zip target already exists: $relativePath"
                    }
                    !entry.isDirectory -> require(!target.exists()) {
                        "Image bundle zip target already exists: $relativePath"
                    }
                }

                val declaredSize = entry.size
                require(declaredSize < 0L || declaredSize <= limits.maxEntryBytes) {
                    "Image bundle zip entry is too large: $relativePath"
                }
                var entryBytes = 0L
                val output = if (!entry.isDirectory && !skipTarget) {
                    createImageBundleDirectories(
                        canonicalRoot,
                        requireNotNull(target.parentFile),
                        createdDirectories
                    )
                    require(target.createNewFile()) {
                        "Image bundle zip target appeared during extraction: $relativePath"
                    }
                    createdFiles.add(target)
                    target.outputStream().buffered()
                } else {
                    null
                }
                try {
                    val buffer = ByteArray(copyBufferSize)
                    while (true) {
                        checkCancelled()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes = checkedImageBundleZipAdd(
                            entryBytes,
                            read.toLong(),
                            "Image bundle zip entry is too large: $relativePath"
                        )
                        totalBytes = checkedImageBundleZipAdd(
                            totalBytes,
                            read.toLong(),
                            "Image bundle zip expands beyond the supported total size."
                        )
                        require(entryBytes <= limits.maxEntryBytes) {
                            "Image bundle zip entry is too large: $relativePath"
                        }
                        require(totalBytes <= limits.maxTotalBytes) {
                            "Image bundle zip expands beyond the supported total size."
                        }
                        if (output != null) {
                            val usableSpace = usableSpaceProvider(canonicalRoot)
                            require(usableSpace >= limits.minFreeSpaceReserveBytes) {
                                "Image bundle zip extraction would consume the reserved free space."
                            }
                            remainingWritableBudget = minOf(
                                remainingWritableBudget,
                                usableSpace - limits.minFreeSpaceReserveBytes
                            )
                            require(read.toLong() <= remainingWritableBudget) {
                                "Image bundle zip extraction would consume the reserved free space."
                            }
                            remainingWritableBudget -= read.toLong()
                        }
                        output?.write(buffer, 0, read)
                    }
                } finally {
                    output?.close()
                }
                require(declaredSize < 0L || declaredSize == entryBytes) {
                    "Image bundle zip entry size does not match its directory record: $relativePath"
                }
                zip.closeEntry()

                if (entry.isDirectory) {
                    createImageBundleDirectories(canonicalRoot, target, createdDirectories)
                } else if (!skipTarget) {
                    require(target.isFile && target.length() == entryBytes) {
                        "Image bundle zip entry was not written completely: $relativePath"
                    }
                    extractedFiles += target
                }
            }
        }
        checkCancelled()
        return ImageBundleZipExtractionResult(
            extractedFiles = extractedFiles.toList(),
            entryCount = entryCount,
            extractedBytes = totalBytes
        )
    } catch (error: Throwable) {
        createdFiles.asReversed().forEach { file -> runCatching { file.delete() } }
        createdDirectories.asReversed().forEach { directory -> runCatching { directory.delete() } }
        throw error
    }
}

private fun normalizeImageBundleZipEntryPath(raw: String, directory: Boolean): String {
    require(raw.isNotBlank()) { "Image bundle zip contains a blank entry path." }
    require('\u0000' !in raw) { "Image bundle zip entry path contains a NUL character." }
    val withForwardSeparators = raw.replace('\\', '/')
    val normalized = if (directory) {
        withForwardSeparators.removeSuffix("/")
    } else {
        withForwardSeparators
    }
    require(normalized.isNotBlank()) { "Image bundle zip contains an invalid root entry." }
    require(!normalized.startsWith('/')) {
        "Image bundle zip entry path must be relative: $raw"
    }
    require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
        "Image bundle zip entry path must not use a drive path: $raw"
    }
    val segments = normalized.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) {
        "Image bundle zip entry path contains an unsafe segment: $raw"
    }
    return segments.joinToString("/")
}

private fun createImageBundleDirectories(
    canonicalRoot: File,
    requestedDirectory: File,
    createdDirectories: MutableList<File>
) {
    val rootPath = canonicalRoot.toPath()
    val directory = requestedDirectory.canonicalFile
    require(directory.toPath().startsWith(rootPath)) {
        "Image bundle zip directory escapes the extraction root."
    }
    val missing = mutableListOf<File>()
    var cursor = directory
    while (cursor != canonicalRoot && !cursor.exists()) {
        missing += cursor
        cursor = requireNotNull(cursor.parentFile).canonicalFile
        require(cursor.toPath().startsWith(rootPath)) {
            "Image bundle zip directory escapes the extraction root."
        }
    }
    require(cursor.isDirectory) { "Image bundle zip path has a non-directory parent." }
    missing.asReversed().forEach { candidate ->
        require(candidate.mkdir()) {
            "Unable to create image bundle zip directory: ${candidate.name}"
        }
        createdDirectories += candidate
    }
}

private fun checkedImageBundleZipAdd(current: Long, increment: Long, message: String): Long {
    require(increment >= 0L && current <= Long.MAX_VALUE - increment) { message }
    return current + increment
}

internal fun extractImageBundleZipIntoDirectory(zipFile: File, bundleDir: File) {
    val canonicalRoot = bundleDir.canonicalFile
    val canonicalZip = zipFile.canonicalFile
    val mcaManifest = File(canonicalRoot, "manifest.json").canonicalFile
    zipFile.inputStream().use { input ->
        extractBoundedImageBundleZip(
            source = input,
            bundleDir = canonicalRoot,
            shouldSkipTarget = { target ->
                target == canonicalZip ||
                    (target == mcaManifest && mcaManifest.isMcaImageBundleManifest())
            }
        )
    }
}
