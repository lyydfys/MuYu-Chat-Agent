package com.muyuchat.core.modelstore

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal data class MnnZipInstallLimits(
    val maxEntryCount: Int = 32_768,
    val maxEntryBytes: Long = 128L * 1024L * 1024L * 1024L,
    val maxTotalBytes: Long = 256L * 1024L * 1024L * 1024L,
    val maxCompressionRatio: Long = 200L,
    val compressionRatioSlackBytes: Long = 64L * 1024L * 1024L
)

internal data class MnnZipInstallResult(
    val bundleRoot: File,
    val extractedEntryCount: Int,
    val extractedBytes: Long
)

/**
 * Installs a user supplied MNN archive through a bounded staging directory.
 *
 * Archive paths are retained because current MNN exporters may declare nested
 * components in config.json. The final bundle appears only after the complete
 * staged tree passes the same readiness contract used immediately before load.
 */
internal class MnnZipBundleInstaller(
    private val limits: MnnZipInstallLimits = MnnZipInstallLimits()
) {
    fun install(
        source: InputStream,
        finalBundleRoot: File,
        compressedSizeBytes: Long? = null
    ): MnnZipInstallResult {
        require(!finalBundleRoot.exists()) {
            "MNN bundle destination already exists: ${finalBundleRoot.absolutePath}"
        }
        val parent = finalBundleRoot.canonicalFile.parentFile
            ?: throw IllegalArgumentException("MNN bundle destination must have a parent directory.")
        require(parent.mkdirs() || parent.isDirectory) {
            "Unable to create the MNN bundle parent directory: ${parent.absolutePath}"
        }
        val stagingRoot = File(
            parent,
            ".${finalBundleRoot.name}.importing-${UUID.randomUUID()}"
        ).canonicalFile
        require(stagingRoot.mkdirs()) {
            "Unable to create the MNN bundle staging directory: ${stagingRoot.absolutePath}"
        }

        var committed = false
        try {
            val extraction = extract(source, stagingRoot, compressedSizeBytes)
            val stagedBundleRoot = findSingleReadyBundleRoot(stagingRoot)
            if (!stagedBundleRoot.renameTo(finalBundleRoot.canonicalFile)) {
                throw IOException("Unable to atomically commit the imported MNN bundle.")
            }
            committed = true
            if (stagedBundleRoot != stagingRoot) stagingRoot.deleteRecursively()
            return MnnZipInstallResult(
                bundleRoot = finalBundleRoot.canonicalFile,
                extractedEntryCount = extraction.entryCount,
                extractedBytes = extraction.totalBytes
            )
        } finally {
            if (!committed) stagingRoot.deleteRecursively()
        }
    }

    private fun extract(
        source: InputStream,
        stagingRoot: File,
        compressedSizeBytes: Long?
    ): ExtractionCounters {
        val knownSourceSize = compressedSizeBytes?.takeIf { it > 0L }
        val overallLimit = minOf(
            limits.maxTotalBytes,
            knownSourceSize?.ratioLimit() ?: Long.MAX_VALUE
        )
        val files = linkedSetOf<String>()
        val directories = linkedSetOf<String>()
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(source.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= limits.maxEntryCount) {
                    "MNN zip contains too many entries (limit=${limits.maxEntryCount})."
                }
                val relativePath = normalizeEntryPath(entry)
                rejectPathConflict(relativePath, entry.isDirectory, files, directories)
                if (entry.isDirectory) {
                    safeDescendant(stagingRoot, relativePath).let { directory ->
                        require(directory.mkdirs() || directory.isDirectory) {
                            "Unable to create MNN zip directory: $relativePath"
                        }
                    }
                    directories += relativePath.lowercase(Locale.ROOT)
                    zip.closeEntry()
                    continue
                }

                val declaredSize = entry.size
                require(declaredSize < 0L || declaredSize <= limits.maxEntryBytes) {
                    "MNN zip entry is too large: $relativePath"
                }
                val target = safeDescendant(stagingRoot, relativePath)
                require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
                    "Unable to create MNN zip component directory: $relativePath"
                }
                var entryBytes = 0L
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes = checkedAdd(entryBytes, read.toLong(), "MNN zip entry is too large: $relativePath")
                        totalBytes = checkedAdd(totalBytes, read.toLong(), "MNN zip expands beyond the supported size limit.")
                        require(entryBytes <= limits.maxEntryBytes) {
                            "MNN zip entry is too large: $relativePath"
                        }
                        require(totalBytes <= overallLimit) {
                            "MNN zip expands beyond the supported size or compression-ratio limit."
                        }
                        output.write(buffer, 0, read)
                    }
                }
                require(declaredSize < 0L || declaredSize == entryBytes) {
                    "MNN zip entry size does not match its directory record: $relativePath"
                }
                val compressedEntryBytes = entry.compressedSize.takeIf { it > 0L }
                require(compressedEntryBytes == null || entryBytes <= compressedEntryBytes.ratioLimit()) {
                    "MNN zip entry exceeds the supported compression ratio: $relativePath"
                }
                require(target.isFile && target.length() == entryBytes) {
                    "MNN zip component was not written completely: $relativePath"
                }
                files += relativePath.lowercase(Locale.ROOT)
                zip.closeEntry()
            }
        }
        require(files.isNotEmpty()) { "MNN zip does not contain any model files." }
        return ExtractionCounters(entryCount = entryCount, totalBytes = totalBytes)
    }

    private fun findSingleReadyBundleRoot(stagingRoot: File): File {
        val candidates = buildList {
            add(stagingRoot)
            stagingRoot.walkTopDown()
                .filter { it.isFile && it.name == "config.json" }
                .mapNotNull(File::getParentFile)
                .forEach(::add)
        }.distinctBy { it.canonicalPath }
            .filter { MnnBundleReadinessAnalyzer.analyze(it).canLoad }
        require(candidates.size == 1) {
            when {
                candidates.isEmpty() ->
                    "MNN zip 包不完整：未找到可加载的完整模型根目录，请确认压缩包包含全部组件且保留原目录结构。"
                else ->
                    "MNN zip 包包含多个完整模型根目录，无法安全判断要导入哪一个。"
            }
        }
        return candidates.single().canonicalFile
    }

    private fun normalizeEntryPath(entry: ZipEntry): String {
        val raw = entry.name
        require(raw.isNotBlank()) { "MNN zip contains a blank entry path." }
        require('\u0000' !in raw) { "MNN zip entry path contains a NUL character." }
        val normalized = raw.replace('\\', '/').let { path ->
            if (entry.isDirectory) path.trimEnd('/') else path
        }
        require(normalized.isNotBlank()) { "MNN zip contains an invalid root entry." }
        require(!normalized.startsWith('/')) { "MNN zip entry path must be relative: $raw" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            "MNN zip entry path must not use an absolute drive path: $raw"
        }
        val segments = normalized.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "MNN zip entry path contains an unsafe segment: $raw"
        }
        return segments.joinToString("/")
    }

    private fun rejectPathConflict(
        path: String,
        directory: Boolean,
        files: Set<String>,
        directories: Set<String>
    ) {
        val normalized = path.lowercase(Locale.ROOT)
        require(normalized !in files && normalized !in directories) {
            "MNN zip contains a duplicate path: $path"
        }
        val segments = normalized.split('/')
        for (index in 1 until segments.size) {
            val parent = segments.take(index).joinToString("/")
            require(parent !in files) {
                "MNN zip path conflicts with a parent file: $path"
            }
        }
        if (!directory) {
            require(directories.none { it.startsWith("$normalized/") }) {
                "MNN zip file path conflicts with an existing directory: $path"
            }
        }
    }

    private fun safeDescendant(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath.replace('/', File.separatorChar)).canonicalFile
        require(target != canonicalRoot && target.toPath().startsWith(canonicalRoot.toPath())) {
            "MNN zip entry escapes the staging directory: $relativePath"
        }
        return target
    }

    private fun Long.ratioLimit(): Long {
        val maximumBase = (Long.MAX_VALUE - limits.compressionRatioSlackBytes) / limits.maxCompressionRatio
        return if (this >= maximumBase) {
            Long.MAX_VALUE
        } else {
            this * limits.maxCompressionRatio + limits.compressionRatioSlackBytes
        }
    }

    private fun checkedAdd(current: Long, increment: Long, message: String): Long {
        require(increment >= 0L && current <= Long.MAX_VALUE - increment) { message }
        return current + increment
    }

    private data class ExtractionCounters(
        val entryCount: Int,
        val totalBytes: Long
    )

    private companion object {
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
    }
}
