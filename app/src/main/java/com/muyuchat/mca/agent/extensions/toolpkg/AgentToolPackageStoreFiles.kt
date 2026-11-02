package com.muyuchat.mca.agent.extensions.toolpkg

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Small filesystem helpers for the private tool-package preview store.
 *
 * They are deliberately free of package semantics. Every caller passes a target already validated
 * to be under the private root; [isInside] re-checks canonical containment as defense in depth.
 */
internal object AgentToolPackageStoreFiles {
    private const val COPY_BUFFER = 16 * 1024

    fun writeFileWithFsync(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    fun moveDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    fun moveFile(source: File, destination: File, replace: Boolean) {
        destination.parentFile?.mkdirs()
        val options = if (replace) {
            arrayOf<StandardCopyOption>(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf<StandardCopyOption>(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source.toPath(), destination.toPath(), *options)
        } catch (_: Exception) {
            val fallback = if (replace) {
                arrayOf<StandardCopyOption>(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray<StandardCopyOption>()
            }
            Files.move(source.toPath(), destination.toPath(), *fallback)
        }
    }

    fun makeReadOnly(directory: File) {
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) makeReadOnly(child) else child.setWritable(false, false)
        }
        directory.setWritable(false, false)
    }

    fun deleteRecursivelyWithWritable(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            runCatching { file.setWritable(true, true) }
        }
        directory.deleteRecursively()
    }

    fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ToolPackageJson.toHex(digest.digest())
    }

    fun isInside(directory: File, file: File): Boolean = try {
        val rootPath = directory.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        filePath != rootPath && filePath.startsWith(rootPath)
    } catch (_: Exception) {
        false
    }
}
