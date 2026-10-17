package com.muyuchat.core.engine

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun PersistentPrefixCacheStore.closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }

internal fun PersistentPrefixCacheStore.resolveSafeRoot(): File? = try {
        val requested = rootDirectory.toPath().toAbsolutePath().normalize()
        if (containsSymbolicLink(requested)) return null
        Files.createDirectories(requested)
        if (containsSymbolicLink(requested) || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        requested.toFile().canonicalFile.takeIf { it.isDirectory }
    } catch (_: Exception) {
        null
    }

internal fun PersistentPrefixCacheStore.containsSymbolicLink(path: java.nio.file.Path): Boolean {
        var current = path.root ?: return true
        path.forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                // Android exposes each app's credential/device-encrypted
                // directory through this system-owned per-user alias. It is
                // outside the app-controlled root; every component from the
                // package directory downward remains subject to the strict
                // no-link check below.
                if (!isFrameworkManagedAndroidUserAlias(current)) return true
            }
        }
        return false
    }

internal fun PersistentPrefixCacheStore.safeChild(root: File, name: String, pattern: Regex): File? {
        if (!pattern.matches(name)) return null
        val child = File(root, name)
        if (!isDirectChild(root, child)) return null
        return child.takeUnless(::existsAsSymbolicLink)
    }

internal fun PersistentPrefixCacheStore.isDirectChild(rootDirectory: File?, candidate: File): Boolean {
        val root = rootDirectory ?: return false
        return try {
            candidate.toPath().toAbsolutePath().normalize().parent ==
                root.toPath().toAbsolutePath().normalize()
        } catch (_: Exception) {
            false
        }
    }

internal fun PersistentPrefixCacheStore.isRegularFileWithoutLinks(file: File): Boolean = try {
        !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        false
    }

internal fun PersistentPrefixCacheStore.existsAsSymbolicLink(file: File): Boolean = try {
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(file.toPath())
    } catch (_: Exception) {
        true
    }

internal fun PersistentPrefixCacheStore.regularFileSize(file: File): Long = try {
        if (isRegularFileWithoutLinks(file)) Files.size(file.toPath()) else -1L
    } catch (_: Exception) {
        -1L
    }

internal fun PersistentPrefixCacheStore.createTemporaryFile(root: File, cacheId: String): File? = try {
        Files.createTempFile(root.toPath(), ".$cacheId-", TEMPORARY_SUFFIX).toFile()
    } catch (_: Exception) {
        null
    }

internal fun PersistentPrefixCacheStore.deleteTemporary(root: File, file: File): Boolean =
        if (TEMP_FILE_NAME.matches(file.name)) deleteManagedFile(root, file) else false

internal fun PersistentPrefixCacheStore.deleteManagedFile(root: File, file: File): Boolean {
        if (!isDirectChild(root, file) || !isManagedName(file.name)) return false
        return try {
            Files.deleteIfExists(file.toPath())
            true
        } catch (_: Exception) {
            false
        }
    }

private const val TEMPORARY_SUFFIX = ".tmp"
