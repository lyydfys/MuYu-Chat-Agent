package com.muyuchat.core.modelstore

import android.util.Log
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

    internal fun ModelStoreRepository.verifyImpl(id: String): Boolean {
        val model = getModel(id) ?: return false
        val file = File(model.path)
        return when (model.runtime) {
            ChatModelRuntime.MNN -> validateMnnBundle(model, file, fullHash = true).canLoad
            ChatModelRuntime.GENIEX_QAIRT -> validateQairtBundle(model, file).canLoad
            ChatModelRuntime.LITERT_LM -> {
                if (!validateLiteRtLmLoadPreflight(file, model.sizeBytes).canLoad) return false
                val actualSha256 = runCatching { sha256(file) }.getOrNull() ?: return false
                val verified = isFastRecoveryFingerprint(model.sha256) ||
                    actualSha256.equals(model.sha256, ignoreCase = true)
                if (!verified) return false
                if (isFastRecoveryFingerprint(model.sha256)) {
                    updateModel(model.copy(sha256 = actualSha256))
                }
                true
            }
            ChatModelRuntime.LLAMA_CPP -> {
                if (!file.isFile || file.length() != model.sizeBytes) return false
                val actualSha256 = runCatching { sha256(file) }.getOrNull() ?: return false
                val mainVerified = isFastRecoveryFingerprint(model.sha256) ||
                    actualSha256.equals(model.sha256, ignoreCase = true)
                if (!mainVerified) return false

                val projector = model.visionProjectorPath?.let(::File)
                val actualProjectorSha256 = projector?.let { candidate ->
                    if (!candidate.isFile || candidate.length() != model.visionProjectorSizeBytes) return false
                    runCatching { sha256(candidate) }.getOrNull() ?: return false
                }
                val projectorVerified = actualProjectorSha256 == null ||
                    model.visionProjectorSha256.isNullOrBlank() ||
                    actualProjectorSha256.equals(model.visionProjectorSha256, ignoreCase = true)
                if (!projectorVerified) return false

                val migrated = model.copy(
                    sha256 = if (isFastRecoveryFingerprint(model.sha256)) actualSha256 else model.sha256,
                    visionProjectorSha256 = actualProjectorSha256 ?: model.visionProjectorSha256
                )
                if (migrated != model) {
                    updateModel(migrated)
                }
                true
            }
        }
    }

    internal fun ModelStoreRepository.validateForLoadImpl(id: String): ModelCompatibilityResult {
        val model = getModel(id) ?: return ModelCompatibilityResult(
            canLoad = false,
            title = "模型清单不存在",
            details = id
        )
        val file = File(model.path)
        if (model.runtime == ChatModelRuntime.MNN) {
            return validateMnnBundle(model, file, fullHash = false)
        }
        if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
            return validateQairtBundle(model, file)
        }
        if (model.runtime == ChatModelRuntime.LITERT_LM) {
            return validateLiteRtLmLoadPreflight(file, model.sizeBytes)
        }
        val compatibility = validateGgufLoadPreflight(file, model.sizeBytes, expectedSha256 = model.sha256)
        if (!compatibility.canLoad) return compatibility
        val projectorCompatibility = model.visionProjectorPath?.let { path ->
            validateGgufProjectorLoadPreflight(
                file = File(path),
                expectedSizeBytes = model.visionProjectorSizeBytes,
                expectedSha256 = model.visionProjectorSha256
            )
        }
        return projectorCompatibility?.takeUnless { it.canLoad } ?: compatibility
    }

    private fun ModelStoreRepository.validateMnnBundle(
        model: ModelManifest,
        bundleDir: File,
        fullHash: Boolean
    ): ModelCompatibilityResult {
        val readiness = MnnBundleReadinessAnalyzer.analyze(bundleDir)
        if (!readiness.canLoad) {
            return ModelCompatibilityResult(
                canLoad = false,
                title = "MNN 模型包不完整",
                details = readiness.diagnosticSummary()
            )
        }
        val requiredFiles = readiness.requiredComponentPaths
        val coreSize = mnnBundleSize(bundleDir, requiredFiles)
        val hasReadableCore = requiredFiles.all { File(bundleDir, it).let { file ->
            file.isFile && file.canRead() && file.length() > 0L
        } }
        if (!fullHash && isFastRecoveryFingerprint(model.sha256)) {
            val currentFingerprint = runCatching {
                fastManagedRecoveryFingerprint(
                    managedRoot = managedModelDir,
                    files = requiredFiles.map { File(bundleDir, it) }
                )
            }.getOrNull()
            if (
                hasReadableCore &&
                coreSize == model.sizeBytes &&
                currentFingerprint.equals(model.sha256, ignoreCase = true)
            ) {
                return ModelCompatibilityResult(
                    canLoad = true,
                    title = "MNN 模型包校验通过",
                    details = "runtime=${model.runtime.storageValue}, core=${formatBytes(coreSize)}"
                )
            }
            // A fast-recovery fingerprint is deliberately only a bounded-I/O
            // hint.  It can change after a move, timestamp normalization, or a
            // harmless metadata rewrite.  Do not turn that hint into a hard
            // rejection: fall through to the complete component scan/hash
            // below.  The native load remains gated by the real bundle
            // readiness result, and a successful scan migrates the persisted
            // identity to the new exact component hash.
        }
        if (!fullHash && hasReadableCore && coreSize == model.sizeBytes && model.sha256.isNotBlank()) {
            // Import/download and the explicit "verify" action own full content hashing.
            // Re-hashing a 6-10 GB MNN bundle before every native load added tens of seconds
            // while the old path accepted any readable core even after a digest mismatch.
            // Stable component membership, non-empty files, and the persisted aggregate size
            // are the fast load-time integrity check; the real native load remains authoritative.
            return ModelCompatibilityResult(
                canLoad = true,
                title = "MNN 模型包校验通过",
                details = "runtime=${model.runtime.storageValue}, core=${formatBytes(coreSize)}"
            )
        }
        val coreHash = runCatching { sha256MnnBundle(bundleDir, requiredFiles) }.getOrNull()
        val coreFingerprintOk = coreHash != null && hasReadableCore &&
            coreSize == model.sizeBytes &&
            coreHash.equals(model.sha256, ignoreCase = true)
        val legacyDirectoryFingerprintOk = !isFastRecoveryFingerprint(model.sha256) && runCatching {
            directorySize(bundleDir) == model.sizeBytes &&
                sha256Directory(bundleDir).equals(model.sha256, ignoreCase = true)
        }.getOrDefault(false)
        if (hasReadableCore && (!coreFingerprintOk || legacyDirectoryFingerprintOk)) {
            migrateMnnFingerprint(model, coreSize, coreHash.orEmpty())
        }
        return if (coreFingerprintOk || legacyDirectoryFingerprintOk || hasReadableCore) {
            ModelCompatibilityResult(
                canLoad = true,
                title = "MNN 模型包校验通过",
                details = "runtime=${model.runtime.storageValue}, core=${formatBytes(coreSize)}"
            )
        } else {
            Log.e(
                "MCA-ModelStore",
                "mnn_validation_failed path=${bundleDir.absolutePath.take(256)} " +
                    "required=${requiredFiles.size} coreSize=$coreSize modelSize=${model.sizeBytes}"
            )
            ModelCompatibilityResult(
                canLoad = false,
                title = "MNN 模型包校验失败",
                details = "核心组件不可读或为空，请重新下载 MNN 引擎包"
            )
        }
    }

    private fun ModelStoreRepository.validateQairtBundle(model: ModelManifest, bundleDir: File): ModelCompatibilityResult {
        if (!bundleDir.exists()) {
            return ModelCompatibilityResult(false, "QAIRT 模型包不存在", bundleDir.absolutePath)
        }
        if (!bundleDir.isDirectory) {
            return ModelCompatibilityResult(false, "QAIRT 模型包路径不是目录", bundleDir.absolutePath)
        }
        val readiness = QairtBundleReadinessAnalyzer.analyze(bundleDir)
        if (!readiness.canLoad) {
            return ModelCompatibilityResult(
                canLoad = false,
                title = "QAIRT 模型包不完整",
                details = readiness.diagnosticSummary() + " 请删除后重新下载完整 GenieX QAIRT zip 包。"
            )
        }
        val size = directorySize(bundleDir)
        val hash = runCatching { sha256Directory(bundleDir) }.getOrNull()
        val fingerprintOk = hash != null &&
            size == model.sizeBytes &&
            hash.equals(model.sha256, ignoreCase = true)
        if (hash != null && !fingerprintOk) {
            runCatching { updateModel(model.copy(sizeBytes = size, sha256 = hash)) }
        }
        val graphProfile = QairtBundleRiskAnalyzer.analyze(bundleDir)
        return ModelCompatibilityResult(
            canLoad = true,
            title = "QAIRT 模型包校验通过",
            details = "runtime=${model.runtime.storageValue}, files=${bundleDir.walkTopDown().count { it.isFile }}, " +
                "size=${formatBytes(size)}, ${readiness.diagnosticSummary()} ${graphProfile.diagnosticSummary()}"
        )
    }

    private fun ModelStoreRepository.migrateMnnFingerprint(model: ModelManifest, coreSize: Long, coreHash: String) {
        if (coreHash.isBlank()) return
        if (model.sizeBytes == coreSize && model.sha256.equals(coreHash, ignoreCase = true)) return
        runCatching {
            updateModel(model.withMigratedMnnFingerprint(coreSize, coreHash))
        }
    }

    internal fun ModelStoreRepository.sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun ModelStoreRepository.copyWithSha256(input: java.io.InputStream, target: File): String {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().buffered().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun ModelStoreRepository.sha256Directory(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
            .forEach { file ->
                digest.update(file.relativeTo(directory).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun ModelStoreRepository.directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    internal fun ModelStoreRepository.sha256MnnBundle(
        directory: File, requiredFiles: List<String>, checkCancelled: () -> Unit = {},
        onProgress: (ModelImportProgress) -> Unit = {}
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        requiredFiles
            .map(::normalizeMnnComponentPath)
            .distinct()
            .sorted()
            .forEach { path ->
                val file = File(directory, path)
                digest.update(path.toByteArray(Charsets.UTF_8))
                digest.update(byteArrayOf(0))
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        copied += read
                        onProgress(ModelImportProgress("verifying", path, copiedBytes = copied))
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun ModelStoreRepository.mnnBundleSize(directory: File, requiredFiles: List<String>): Long =
        requiredFiles
            .map(::normalizeMnnComponentPath)
            .distinct()
            .sumOf { path -> File(directory, path).length() }

    internal fun ModelStoreRepository.normalizeMnnComponentPath(rawPath: String): String = normalizeMnnImportPath(rawPath)

    internal fun ModelStoreRepository.safeFileName(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun ModelStoreRepository.formatBytes(bytes: Long): String = formatModelSizeBytes(bytes)

/**
 * A visual acceptance result belongs to one exact MNN bundle fingerprint.
 * Re-fingerprinting a changed bundle must never carry that acceptance forward,
 * even when all required files remain readable and the text runtime can load it.
 */
internal fun ModelManifest.withMigratedMnnFingerprint(
    coreSize: Long,
    coreHash: String
): ModelManifest = copy(
    sizeBytes = coreSize,
    sha256 = coreHash,
    visionValidated = false
)

internal fun isCompleteMnnBundleDirectory(bundleDir: File): Boolean {
    return MnnBundleReadinessAnalyzer.analyze(bundleDir).canLoad
}

internal fun isCompleteQairtBundleDirectory(bundleDir: File): Boolean {
    return QairtBundleReadinessAnalyzer.analyze(bundleDir).canLoad
}

/** Returns this root or one unambiguous, in-place wrapper directory; never follows an external child. */

internal fun findQairtBundleRoot(bundleDir: File): File? {
    val root = bundleDir.canonicalDirectoryOrNull() ?: return null
    if (root.hasQairtRootMarker()) return root

    val entries = root.listFiles()?.toList() ?: return null
    if (entries.size != 1) return null
    val child = entries.single().canonicalDirectoryOrNull() ?: return null
    if (child.parentFile?.canonicalFile?.path != root.path) return null
    return child.takeIf(File::hasQairtRootMarker)
}

internal data class RecoverableInstalledQairtBundle(
    val bundleDir: File,
    val repoId: String
)

/**
 * Finds complete SDK-managed QAIRT packages that no current product manifest
 * already represents. Only the controlled `<filesDir>/geniex/models/<owner>/<repo>`
 * layout is accepted; arbitrary descendants and external symlinks are ignored.
 */

internal fun findRecoverableInstalledQairtBundles(
    sdkModelsRoot: File,
    existing: List<ModelManifest>
): List<RecoverableInstalledQairtBundle> {
    val root = runCatching { sdkModelsRoot.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
        ?: return emptyList()
    val existingPaths = existing.asSequence()
        .filter { it.runtime == ChatModelRuntime.GENIEX_QAIRT }
        .mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }
        .toSet()
    val existingRepoIds = existing.asSequence()
        .filter { it.runtime == ChatModelRuntime.GENIEX_QAIRT }
        .mapNotNull(ModelManifest::repoId)
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    return root.listFiles()
        ?.asSequence()
        ?.mapNotNull { owner ->
            runCatching { owner.canonicalFile }.getOrNull()
                ?.takeIf { it.isDirectory && it.parentFile?.canonicalPath == root.path }
        }
        ?.flatMap { owner ->
            owner.listFiles().orEmpty().asSequence().mapNotNull { candidate ->
                val bundle = runCatching { candidate.canonicalFile }.getOrNull()
                    ?.takeIf {
                        it.isDirectory &&
                            it.parentFile?.canonicalPath == owner.path &&
                            isCompleteQairtBundleDirectory(it)
                    }
                    ?: return@mapNotNull null
                val repoId = "${owner.name}/${bundle.name}"
                RecoverableInstalledQairtBundle(bundle, repoId).takeUnless {
                    bundle.path in existingPaths || repoId.lowercase() in existingRepoIds
                }
            }
        }
        ?.sortedBy { it.repoId.lowercase() }
        ?.toList()
        .orEmpty()
}

/** Rewrites a legacy outer-directory manifest without changing non-QAIRT models. */

internal fun normalizeQairtManifestRoot(model: ModelManifest): ModelManifest {
    if (model.runtime != ChatModelRuntime.GENIEX_QAIRT) return model
    val root = findQairtBundleRoot(File(model.path)) ?: return model
    if (root.absolutePath == File(model.path).absolutePath) return model
    return model.copy(path = root.absolutePath, fileName = root.name)
}

private fun File.canonicalDirectoryOrNull(): File? = runCatching { canonicalFile }
    .getOrNull()
    ?.takeIf { it.isDirectory }

private fun File.hasQairtRootMarker(): Boolean = listOf("metadata.json", "genie_config.json").any { name ->
    val candidate = File(this, name)
    val canonical = runCatching { candidate.canonicalFile }.getOrNull()
    canonical?.isFile == true && canonical.parentFile?.canonicalFile?.path == path
}
