package com.muyuchat.core.modelstore

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal fun validateGgufLoadPreflight(
    file: File,
    expectedSizeBytes: Long,
    metadataReader: (File) -> GgufMetadata = ::readBoundedGgufMetadata,
    expectedSha256: String? = null
): ModelCompatibilityResult {
    ggufLoadShapeFailure(file, expectedSizeBytes, "模型文件")?.let { return it }
    val metadata = runCatching { metadataReader(file) }.getOrElse { error ->
        return ModelCompatibilityResult(
            canLoad = false,
            title = "模型预检失败",
            details = error.message.orEmpty()
        )
    }
    val compatibility = ModelCompatibility.check(file, metadata)
    if (!compatibility.canLoad) return compatibility
    return ggufLoadContentHashFailure(file, expectedSha256, "模型文件") ?: compatibility
}

internal fun validateGgufProjectorLoadPreflight(
    file: File,
    expectedSizeBytes: Long,
    metadataReader: (File) -> GgufMetadata = ::readBoundedGgufMetadata,
    expectedSha256: String? = null
): ModelCompatibilityResult {
    ggufLoadShapeFailure(file, expectedSizeBytes, "视觉投影器")?.let { return it }
    if (!file.name.endsWith(".gguf", ignoreCase = true)) {
        return ModelCompatibilityResult(false, "视觉投影器格式错误", "请选择 .gguf 投影器文件")
    }
    val metadata = runCatching { metadataReader(file) }.getOrElse { error ->
        return ModelCompatibilityResult(false, "视觉投影器预检失败", error.message.orEmpty())
    }
    if (!metadata.isGguf) {
        return ModelCompatibilityResult(false, "视觉投影器格式错误", "文件头不是 GGUF")
    }
    if (!isVisionProjectorCandidate(file.name, metadata)) {
        return ModelCompatibilityResult(false, "视觉投影器类型错误", "metadata 与文件名均不表示视觉投影器")
    }
    ggufLoadContentHashFailure(file, expectedSha256, "视觉投影器")?.let { return it }
    return ModelCompatibilityResult(
        canLoad = true,
        title = "视觉投影器预检通过",
        details = "architecture=${metadata.architecture ?: "unknown"}, size=${file.length()}"
    )
}

private fun ggufLoadShapeFailure(
    file: File,
    expectedSizeBytes: Long,
    label: String
): ModelCompatibilityResult? = when {
    !file.exists() -> ModelCompatibilityResult(false, "${label}不存在", file.absolutePath)
    !file.isFile -> ModelCompatibilityResult(false, "${label}不是普通文件", file.absolutePath)
    !file.canRead() -> ModelCompatibilityResult(false, "${label}不可读", file.absolutePath)
    file.length() <= 0L -> ModelCompatibilityResult(false, "${label}为空", file.absolutePath)
    expectedSizeBytes <= 0L || file.length() != expectedSizeBytes -> ModelCompatibilityResult(
        false,
        "${label}大小不一致",
        "expected=$expectedSizeBytes, actual=${file.length()}"
    )
    else -> null
}

internal fun readBoundedGgufMetadata(file: File): GgufMetadata =
    file.inputStream().use { input ->
        readBoundedGgufMetadata(input, file.name)
    }

internal fun readBoundedGgufMetadata(
    input: InputStream,
    fileName: String,
    byteLimit: Long = MAX_GGUF_LOAD_PREFLIGHT_BYTES
): GgufMetadata {
    require(byteLimit > 0L) { "GGUF preflight byte limit must be positive." }
    return GgufMetadataReader.read(ReadLimitInputStream(input, byteLimit), fileName)
}

private class ReadLimitInputStream(
    private val delegate: InputStream,
    byteLimit: Long
) : InputStream() {
    private var remaining = byteLimit

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = delegate.read()
        if (value >= 0) remaining -= 1L
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val allowed = minOf(length.toLong(), remaining).toInt()
        val read = delegate.read(buffer, offset, allowed)
        if (read > 0) remaining -= read.toLong()
        return read
    }

    override fun skip(byteCount: Long): Long {
        if (remaining <= 0L) return 0L
        val skipped = delegate.skip(minOf(byteCount, remaining))
        if (skipped > 0L) remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(delegate.available().toLong(), remaining).toInt()
}

internal fun isVisionProjectorCandidate(fileName: String, metadata: GgufMetadata): Boolean {
    val lower = fileName.lowercase()
    val architecture = metadata.architecture?.lowercase().orEmpty()
    return "mmproj" in lower ||
        "projector" in lower ||
        lower.startsWith("clip") ||
        architecture == "clip"
}

private fun ggufLoadContentHashFailure(
    file: File,
    expectedSha256: String?,
    label: String
): ModelCompatibilityResult? {
    val expected = expectedSha256?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
        ?: return null
    val beforeSize = file.length()
    val beforeModified = file.lastModified()
    val actual = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        require(file.length() == beforeSize && file.lastModified() == beforeModified) {
            "file_changed_during_sha256"
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }.getOrElse { error ->
        return ModelCompatibilityResult(false, "${label}完整校验失败", error.message.orEmpty())
    }
    return if (actual.equals(expected, ignoreCase = true)) null else ModelCompatibilityResult(
        false, "${label}内容已变更", "SHA-256 expected=$expected, actual=$actual"
    )
}

private const val MAX_GGUF_LOAD_PREFLIGHT_BYTES = 4L * 1024L * 1024L
