package com.muyuchat.mca

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Fingerprints stable Qwen bundle files while ignoring files MNN/OpenCL may
 * create beside the model at runtime. Those caches are disposable accelerators,
 * not model identity; including them makes a second generation spuriously
 * invalidate and reload the resident native model.
 */
internal fun qwenImage21BundleFingerprint(root: File, supplied: String?): String {
    val canonicalRoot = root.canonicalFile
    require(canonicalRoot.isDirectory && canonicalRoot.canRead()) {
        "The Qwen model bundle directory is missing or unreadable."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }
    add(supplied.orEmpty())
    val manifest = File(canonicalRoot, "manifest.json")
    if (manifest.isFile && manifest.canRead()) add(qwenFileSha256(manifest)) else add("no-manifest")
    val files = canonicalRoot.walkTopDown()
        .onEnter { directory -> !directory.isQwenRuntimeTransient() }
        .filter { file -> file.isFile && !file.isQwenRuntimeTransient() }
        .toList()
        .sortedBy { file ->
            runCatching { file.relativeTo(canonicalRoot).invariantSeparatorsPath }
                .getOrDefault(file.name)
        }
    require(files.isNotEmpty()) { "The Qwen model bundle directory is empty." }
    for (file in files) {
        val relative = runCatching { file.relativeTo(canonicalRoot).invariantSeparatorsPath }
            .getOrDefault(file.name)
        add(relative)
        add(file.length().toString())
        add(file.lastModified().toString())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun File.isQwenRuntimeTransient(): Boolean {
    val normalizedName = name.lowercase()
    if (normalizedName in QWEN_RUNTIME_CACHE_DIRECTORIES) return true
    return QWEN_RUNTIME_TEMP_SUFFIXES.any(normalizedName::endsWith)
}

private fun qwenFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val QWEN_RUNTIME_CACHE_DIRECTORIES = setOf(
    ".mnn_cl_cache",
    ".qnn_unet_cache"
)

private val QWEN_RUNTIME_TEMP_SUFFIXES = listOf(
    ".tmp",
    ".temp",
    ".part",
    ".partial",
    ".download"
)
