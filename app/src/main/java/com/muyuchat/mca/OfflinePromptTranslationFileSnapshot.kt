package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Cheap invalidation of an already SHA-256-verified private package. This is installation
 * metadata only: it never proves native runtime readiness and does not replace the integrity
 * verification immediately before a native model load.
 */
internal class OfflinePromptTranslationFileSnapshot private constructor(
    private val entries: List<Entry>
) {
    fun isCurrent(): Boolean = entries.all { entry ->
        runCatching { Entry.read(entry.file) == entry }.getOrDefault(false)
    }

    private data class Entry(
        val file: File,
        val directory: Boolean,
        val key: String?,
        val bytes: Long,
        val modified: FileTime,
        val created: FileTime,
        val children: List<String>?
    ) {
        companion object {
            fun read(file: File): Entry {
                // A replacement symlink must not reuse a previously trusted canonical path.
                check(file.canonicalFile == file.absoluteFile) { "Translation package path changed." }
                val attributes = Files.readAttributes(
                    file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
                check(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)) {
                    "Translation package file is missing or unsafe."
                }
                check(file.canRead()) { "Translation package is unreadable." }
                return Entry(
                    file = file.absoluteFile,
                    directory = attributes.isDirectory,
                    key = attributes.fileKey()?.toString(),
                    bytes = attributes.size(),
                    modified = attributes.lastModifiedTime(),
                    created = attributes.creationTime(),
                    children = if (attributes.isDirectory) {
                        requireNotNull(file.list()) { "Translation package directory is unreadable." }.sorted()
                    } else null
                )
            }
        }
    }

    companion object {
        fun capture(files: List<File>): OfflinePromptTranslationFileSnapshot? = runCatching {
            OfflinePromptTranslationFileSnapshot(files.distinct().map { Entry.read(it) })
        }.getOrNull()
    }
}
