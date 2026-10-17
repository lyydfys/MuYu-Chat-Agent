package com.muyuchat.core.modelstore

import java.io.File
import java.security.MessageDigest
import java.util.UUID

    internal fun ModelStoreRepository.recoverManagedMnnBundles(existing: List<ModelManifest>): List<ModelManifest> {
        val existingPaths = existing.map { File(it.path).absolutePath }.toSet()
        return managedModelDir.listFiles()
            ?.filter { candidate ->
                File(candidate.absolutePath).absolutePath !in existingPaths &&
                    isCompleteMnnBundleDirectory(candidate)
            }
            ?.mapNotNull { bundleDir ->
                runCatching {
                    val requiredFiles = requiredMnnFilesForDirectory(bundleDir)
                    val coreSizeBytes = mnnBundleSize(bundleDir, requiredFiles)
                    val coreSha256 = fastManagedRecoveryFingerprint(
                        managedRoot = managedModelDir,
                        files = requiredFiles.map { File(bundleDir, it) }
                    )
                    ModelManifest(
                        id = UUID.nameUUIDFromBytes("mnn:${bundleDir.absolutePath}".toByteArray()).toString(),
                        displayName = inferMnnDisplayName(bundleDir.name),
                        path = bundleDir.absolutePath,
                        runtime = ChatModelRuntime.MNN,
                        source = ModelSource.LOCAL,
                        repoId = null,
                        revision = null,
                        fileName = bundleDir.name,
                        sizeBytes = coreSizeBytes,
                        sha256 = coreSha256,
                        quant = "MNN",
                        architecture = inferArchitectureLabel(bundleDir.name, ChatModelRuntime.MNN),
                        createdAt = bundleDir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }.getOrNull()
            }
            .orEmpty()
    }

    internal fun ModelStoreRepository.recoverManagedGgufModels(existing: List<ModelManifest>): List<ModelManifest> =
        findRecoverableManagedGgufFiles(managedModelDir, existing).mapNotNull { candidate ->
            runCatching {
                val file = candidate.file
                ModelManifest(
                    id = UUID.nameUUIDFromBytes(
                        "gguf:${file.canonicalPath}".toByteArray(Charsets.UTF_8)
                    ).toString(),
                    displayName = stripKnownExtension(file.name, ".gguf"),
                    path = file.absolutePath,
                    runtime = ChatModelRuntime.LLAMA_CPP,
                    source = ModelSource.LOCAL,
                    repoId = null,
                    revision = null,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    sha256 = fastManagedRecoveryFingerprint(managedModelDir, listOf(file)),
                    quant = candidate.metadata.quant,
                    architecture = candidate.metadata.architecture,
                    createdAt = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }.getOrNull()
        }

    internal fun ModelStoreRepository.recoverManagedLiteRtLmModels(existing: List<ModelManifest>): List<ModelManifest> =
        findRecoverableManagedLiteRtLmFiles(managedModelDir, existing).mapNotNull { candidate ->
            runCatching {
                val file = candidate.file
                ModelManifest(
                    id = UUID.nameUUIDFromBytes(
                        "litertlm:${file.canonicalPath}".toByteArray(Charsets.UTF_8)
                    ).toString(),
                    displayName = stripKnownExtension(file.name, ".litertlm"),
                    path = file.absolutePath,
                    runtime = ChatModelRuntime.LITERT_LM,
                    source = ModelSource.LOCAL,
                    repoId = null,
                    revision = null,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    sha256 = fastManagedRecoveryFingerprint(managedModelDir, listOf(file)),
                    quant = "LiteRT-LM",
                    architecture = inferArchitectureLabel(file.name, ChatModelRuntime.LITERT_LM),
                    createdAt = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }.getOrNull()
        }

internal data class RecoverableManagedGgufFile(
    val file: File,
    val metadata: GgufMetadata
)

internal data class RecoverableManagedLiteRtLmFile(
    val file: File
)

/**
 * Produces a stable, bounded-I/O identity for assets rediscovered after their
 * product manifest was lost. Large tensor/weight payloads contribute path,
 * size, and mtime without being read. GGUF headers and small metadata/config
 * files additionally contribute a bounded prefix hash.
 */

internal fun fastManagedRecoveryFingerprint(
    managedRoot: File,
    files: List<File>
): String {
    val root = managedRoot.canonicalFile
    require(root.isDirectory) { "Managed model root is not a directory: ${root.absolutePath}" }
    val rootPrefix = root.path + File.separator
    val entries = files
        .map { it.canonicalFile }
        .onEach { file ->
            require(file.isFile && file.canRead() && file.path.startsWith(rootPrefix)) {
                "Recovery fingerprint file is not readable managed content: ${file.absolutePath}"
            }
        }
        .distinctBy(File::getPath)
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
    require(entries.isNotEmpty()) { "Recovery fingerprint requires at least one managed file." }

    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(FAST_RECOVERY_FINGERPRINT_SCHEMA.toByteArray(Charsets.UTF_8))
    digest.update(byteArrayOf(0))
    entries.forEach { file ->
        val relativePath = file.relativeTo(root).invariantSeparatorsPath
        digest.update(relativePath.toByteArray(Charsets.UTF_8))
        digest.update(byteArrayOf(0))
        digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
        digest.update(byteArrayOf(0))
        digest.update(file.lastModified().toString().toByteArray(Charsets.US_ASCII))
        digest.update(byteArrayOf(0))
        if (file.hasBoundedRecoveryMetadata()) {
            digest.update(boundedFilePrefixSha256(file).toByteArray(Charsets.US_ASCII))
        }
        digest.update(byteArrayOf(0))
    }
    val hashed = digest.digest().joinToString("") { "%02x".format(it) }
    return FAST_RECOVERY_FINGERPRINT_PREFIX + hashed.drop(FAST_RECOVERY_FINGERPRINT_PREFIX.length)
}

internal fun isFastRecoveryFingerprint(value: String): Boolean =
    value.length == 64 && value.startsWith(FAST_RECOVERY_FINGERPRINT_PREFIX, ignoreCase = true)

private fun File.hasBoundedRecoveryMetadata(): Boolean {
    val lowerName = name.lowercase()
    val extension = extension.lowercase()
    return extension == "gguf" ||
        extension in RECOVERY_METADATA_EXTENSIONS ||
        "config" in lowerName ||
        "tokenizer" in lowerName ||
        "manifest" in lowerName ||
        "metadata" in lowerName ||
        "vocab" in lowerName
}

private fun boundedFilePrefixSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = MAX_RECOVERY_METADATA_HASH_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            remaining -= read
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Finds compatible GGUF main models anywhere below the app-owned model root.
 * Download/profiling tools historically placed GGUFs one directory below that
 * root, so a top-level-only scan silently omitted those otherwise loadable
 * models. Canonical containment prevents a linked directory from expanding the
 * recovery scope beyond app-owned storage.
 */

internal fun findRecoverableManagedGgufFiles(
    managedRoot: File,
    existing: List<ModelManifest>
): List<RecoverableManagedGgufFile> {
    val root = runCatching { managedRoot.canonicalFile }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: return emptyList()
    val rootPrefix = root.path + File.separator
    val representedPaths = buildSet {
        existing.asSequence()
            .filter { it.runtime == ChatModelRuntime.LLAMA_CPP }
            .mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }
            .forEach(::add)
        existing.asSequence()
            .mapNotNull(ModelManifest::visionProjectorPath)
            .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
            .forEach(::add)
    }
    val pending = ArrayDeque<File>().apply { add(root) }
    val visitedDirectories = mutableSetOf(root.path)
    val recovered = mutableListOf<RecoverableManagedGgufFile>()

    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        directory.listFiles().orEmpty().forEach { entry ->
            val candidate = runCatching { entry.canonicalFile }.getOrNull() ?: return@forEach
            if (!candidate.path.startsWith(rootPrefix)) return@forEach
            when {
                candidate.isDirectory -> {
                    if (candidate.isManagedImportStagingDirectory()) return@forEach
                    if (visitedDirectories.add(candidate.path)) pending += candidate
                }
                candidate.isFile &&
                    candidate.canRead() &&
                    candidate.length() > 0L &&
                    candidate.name.endsWith(".gguf", ignoreCase = true) &&
                    candidate.path !in representedPaths &&
                    candidate.isPrimaryGgufShard() -> {
                    val metadata = runCatching { GgufMetadataReader.read(candidate) }.getOrNull()
                        ?: return@forEach
                    if (!ModelCompatibility.check(candidate, metadata).canLoad) return@forEach
                    recovered += RecoverableManagedGgufFile(candidate, metadata)
                }
            }
        }
    }
    return recovered
        .distinctBy { it.file.path }
        .sortedBy { it.file.relativeTo(root).invariantSeparatorsPath.lowercase() }
}

/**
 * Finds valid LiteRT-LM containers below the app-owned model root after a
 * manifest loss. The scan reads only the bounded container header and never
 * hashes or parses the model payload.
 */

internal fun findRecoverableManagedLiteRtLmFiles(
    managedRoot: File,
    existing: List<ModelManifest>
): List<RecoverableManagedLiteRtLmFile> {
    val root = runCatching { managedRoot.canonicalFile }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: return emptyList()
    val rootPrefix = root.path + File.separator
    val representedPaths = existing
        .mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }
        .toSet()
    val pending = ArrayDeque<File>().apply { add(root) }
    val visitedDirectories = mutableSetOf(root.path)
    val recovered = mutableListOf<RecoverableManagedLiteRtLmFile>()

    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        directory.listFiles().orEmpty().forEach { entry ->
            val candidate = runCatching { entry.canonicalFile }.getOrNull() ?: return@forEach
            if (!candidate.path.startsWith(rootPrefix)) return@forEach
            when {
                candidate.isDirectory -> {
                    if (candidate.isManagedImportStagingDirectory()) return@forEach
                    if (visitedDirectories.add(candidate.path)) pending += candidate
                }
                candidate.isFile &&
                    candidate.canRead() &&
                    candidate.length() >= LITERT_LM_MAGIC_SIZE &&
                    candidate.path !in representedPaths &&
                    (candidate.name.endsWith(".litertlm", ignoreCase = true) || candidate.hasLiteRtLmMagic()) &&
                    candidate.hasLiteRtLmMagic() &&
                    isLiteRtLmFile(candidate) -> {
                    recovered += RecoverableManagedLiteRtLmFile(candidate)
                }
            }
        }
    }
    return recovered
        .distinctBy { it.file.path }
        .sortedBy { it.file.relativeTo(root).invariantSeparatorsPath.lowercase() }
}

private fun File.isPrimaryGgufShard(): Boolean {
    val match = GGUF_SHARD_FILE.matchEntire(name) ?: return true
    return match.groupValues[1].toIntOrNull() == 1
}

private val GGUF_SHARD_FILE = Regex("^.+-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

private const val FAST_RECOVERY_FINGERPRINT_SCHEMA = "mca-managed-recovery-v1"

private const val FAST_RECOVERY_FINGERPRINT_PREFIX = "6d63612d72656331"

private const val MAX_RECOVERY_METADATA_HASH_BYTES = 1024L * 1024L

private val RECOVERY_METADATA_EXTENSIONS = setOf(
    "json",
    "txt",
    "model",
    "jinja",
    "jinja2",
    "tmpl",
    "yaml",
    "yml"
)
