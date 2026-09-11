package com.muyuchat.core.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Extract into a candidate directory. Completed entries survive interruption; never expose partial files. */
suspend fun extractResumableModelZip(
    archive: File,
    destination: File,
    storageBudget: DownloadStorageBudget = DownloadStorageBudget(),
    maxExpandedBytes: Long = 128L * 1024 * 1024 * 1024,
    maxEntries: Int = 100_000,
    maxEntryBytes: Long = maxExpandedBytes,
    shouldSkipTarget: (File) -> Boolean = { false }
) {
    destination.mkdirs()
    val root = destination.canonicalFile
    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        var count = 0
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            if (++count > maxEntries) throw IOException("模型包文件数量超过限制。")
            val entry = entries.nextElement()
            val path = entry.name.replace('\\', '/')
            require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) { "模型 ZIP 包含不安全路径：${entry.name}" }
            val target = File(root, path).canonicalFile
            require(target.toPath().startsWith(root.toPath()) && target != root) { "模型 ZIP 包含不安全路径：${entry.name}" }
            if (entry.isDirectory) { target.mkdirs(); continue }
            require(entry.size >= 0 && entry.size <= maxEntryBytes && entry.size <= maxExpandedBytes - total) { "模型包展开大小超过限制。" }
            total += entry.size
            if (shouldSkipTarget(target)) continue
            if (target.isFile && target.length() == entry.size && crc32(target, buffer) == entry.crc) continue
            storageBudget.requireAvailable(target, entry.size)
            target.parentFile?.mkdirs()
            val partial = File(target.parentFile, ".${target.name}.extracting")
            val crc = CRC32()
            var written = 0L
            var nextSpaceCheck = 0L
            zip.getInputStream(entry).use { input ->
                partial.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(read.toLong() <= entry.size - written) { "模型包条目超出声明大小：${entry.name}" }
                        if (written >= nextSpaceCheck) {
                            storageBudget.requireAvailable(partial, minOf(1024L * 1024, entry.size - written))
                            nextSpaceCheck = written + 1024L * 1024
                        }
                        output.write(buffer, 0, read); crc.update(buffer, 0, read); written += read
                    }
                    output.fd.sync()
                }
            }
            require(written == entry.size && crc.value == entry.crc) { "模型包条目校验失败：${entry.name}" }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private suspend fun crc32(file: File, buffer: ByteArray): Long {
    val crc = CRC32()
    file.inputStream().use { input ->
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            crc.update(buffer, 0, read)
        }
    }
    return crc.value
}
