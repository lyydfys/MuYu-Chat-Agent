package com.muyuchat.core.modelstore

import android.net.Uri
import java.io.File
import java.util.UUID
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.MODEL_IMPORT_LOCK

    internal fun ModelStoreRepository.registerDownloadedModelImpl(
        file: File,
        repoId: String,
        revision: String,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE
    ): ModelManifest {
        if (file.name.endsWith(".litertlm", ignoreCase = true) || file.hasLiteRtLmMagic()) {
            return registerDownloadedLiteRtLmModel(
                file = file,
                repoId = repoId,
                revision = revision,
                license = license,
                source = source
            )
        }
        require(file.exists()) { "Downloaded file does not exist: ${file.absolutePath}" }
        require(file.extension.equals("gguf", ignoreCase = true)) { "下载完成的聊天模型不是 GGUF。" }
        val metadata = GgufMetadataReader.read(file)
        require(metadata.isGguf) { "下载完成的文件不是 GGUF。" }
        val compatibility = ModelCompatibility.check(file, metadata)
        require(compatibility.canLoad) { compatibility.message }
        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = file.name.removeSuffix(".gguf"),
            path = file.absolutePath,
            runtime = ChatModelRuntime.LLAMA_CPP,
            source = source,
            repoId = repoId,
            revision = revision,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            quant = metadata.quant,
            architecture = metadata.architecture,
            license = license
        )
        return upsert(manifest)
    }

    /** Registers an already-downloaded official LiteRT-LM container. */

    internal fun ModelStoreRepository.registerDownloadedLiteRtLmModelImpl(
        file: File,
        repoId: String? = null,
        revision: String? = null,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE,
        displayName: String? = null
    ): ModelManifest {
        require(file.exists()) { "Downloaded file does not exist: ${file.absolutePath}" }
        val compatibility = validateLiteRtLmLoadPreflight(file, file.length())
        require(compatibility.canLoad) { compatibility.message }
        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = displayName?.trim()?.takeIf { it.isNotBlank() }
                ?: stripKnownExtension(file.name, ".litertlm"),
            path = file.absolutePath,
            runtime = ChatModelRuntime.LITERT_LM,
            source = source,
            repoId = repoId,
            revision = revision,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            quant = "LiteRT-LM",
            architecture = inferArchitectureLabel(file.name, ChatModelRuntime.LITERT_LM),
            license = license
        )
        return upsert(manifest)
    }

    internal fun ModelStoreRepository.registerDownloadedMnnBundleImpl(
        displayName: String,
        bundleDir: File,
        repoId: String?,
        revision: String?,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE,
        quant: String? = "MNN",
        architecture: String? = null,
        requiredFiles: List<String> = emptyList(),
        checkCancelled: () -> Unit = {},
        onProgress: (ModelImportProgress) -> Unit = {}
    ): ModelManifest {
        val declaredRequiredFiles = requiredFiles.map(::normalizeMnnComponentPath).distinct()
        val readiness = MnnBundleReadinessAnalyzer.analyze(
            bundleDir = bundleDir,
            additionalRequiredComponents = declaredRequiredFiles
        )
        require(readiness.canLoad) { "MNN 模型包不完整：${readiness.diagnosticSummary()}" }
        val fingerprintFiles = (readiness.requiredComponentPaths + declaredRequiredFiles).distinct()
        val coreSizeBytes = mnnBundleSize(bundleDir, fingerprintFiles)
        val coreSha256 = sha256MnnBundle(bundleDir, fingerprintFiles, checkCancelled, onProgress)
        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = displayName.ifBlank { bundleDir.name },
            path = bundleDir.absolutePath,
            runtime = ChatModelRuntime.MNN,
            source = source,
            repoId = repoId,
            revision = revision,
            fileName = bundleDir.name,
            sizeBytes = coreSizeBytes,
            sha256 = coreSha256,
            quant = quant,
            architecture = architecture?.takeIf { it.isNotBlank() }
                ?: inferArchitectureLabel(bundleDir.name, ChatModelRuntime.MNN),
            license = license
        )
        checkCancelled()
        return upsert(manifest)
    }

    internal fun ModelStoreRepository.registerDownloadedQairtBundleImpl(
        displayName: String,
        bundleDir: File,
        repoId: String?,
        revision: String?,
        license: String? = null,
        source: ModelSource = ModelSource.HUGGING_FACE,
        quant: String? = "w4a16 QAIRT",
        architecture: String? = null
    ): ModelManifest {
        val resolvedBundleDir = resolveQairtBundleRoot(bundleDir)
        val readiness = QairtBundleReadinessAnalyzer.analyze(resolvedBundleDir)
        require(readiness.canLoad) {
            readiness.diagnosticSummary()
        }
        val manifest = ModelManifest(
            id = UUID.randomUUID().toString(),
            displayName = displayName.ifBlank { inferQairtDisplayName(resolvedBundleDir.name) },
            path = resolvedBundleDir.absolutePath,
            runtime = ChatModelRuntime.GENIEX_QAIRT,
            source = source,
            repoId = repoId,
            revision = revision,
            fileName = resolvedBundleDir.name,
            sizeBytes = directorySize(resolvedBundleDir),
            sha256 = sha256Directory(resolvedBundleDir),
            quant = quant,
            architecture = architecture,
            license = license
        )
        return upsert(manifest)
    }

    /**
     * Resolves the only safe packaging wrapper we support: an otherwise empty directory
     * containing one complete QAIRT bundle directory. Ambiguous archives remain invalid.
     */
    internal fun ModelStoreRepository.resolveQairtBundleRootImpl(bundleDir: File): File = findQairtBundleRoot(bundleDir)
        ?: error("QAIRT 模型包根目录不明确或不完整：${bundleDir.absolutePath}")

    internal fun ModelStoreRepository.attachVisionProjectorImpl(modelId: String, uri: Uri, displayNameOverride: String? = null): ModelManifest =
        synchronized(MODEL_IMPORT_LOCK) {
            val models = listModels()
            val model = models.firstOrNull { it.id == modelId } ?: error("未找到要绑定视觉文件的本地模型。")
            val providedName = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "mmproj"
            val fileName = normalizedGgufImportName(providedName)

            managedModelDir.mkdirs()
            val target = uniqueTarget(fileName)
            val staging = atomicImportStagingFile(target)
            var ownsTarget = false
            try {
                val sourceDigest = appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取所选视觉文件。" }
                    copyWithSha256(input, staging)
                }
                verifyProviderCopyAgainstAdvisorySize(querySize(uri), staging.length())
                val copiedMetadata = GgufMetadataReader.read(staging)
                require(copiedMetadata.isGguf && isVisionProjectorCandidate(fileName, copiedMetadata)) {
                    "视觉投影器复制后文件头或类型校验失败。"
                }
                require(sha256(staging).equals(sourceDigest, ignoreCase = true)) {
                    "视觉投影器复制后 SHA-256 校验失败。"
                }
                require(commitAtomicImportPath(staging, target)) { "无法原子提交视觉投影器。" }
                ownsTarget = true
                val updated = model.copy(
                    visionProjectorPath = target.absolutePath,
                    visionProjectorFileName = target.name,
                    visionProjectorSizeBytes = target.length(),
                    visionProjectorSha256 = sourceDigest
                )
                save(models.map { if (it.id == modelId) updated else it })
                updated
            } catch (error: Throwable) {
                if (ownsTarget) target.delete()
                throw error
            } finally {
                cleanupAtomicImportStagingFile(staging, requireNotNull(target.parentFile))
            }
        }

    internal fun ModelStoreRepository.attachVisionProjectorFileImpl(modelId: String, file: File, displayNameOverride: String? = null): ModelManifest =
        synchronized(MODEL_IMPORT_LOCK) {
            val models = listModels()
            val model = models.firstOrNull { it.id == modelId } ?: error("未找到要绑定视觉文件的本地模型。")
            require(file.exists() && file.isFile && file.canRead()) { "无法读取视觉投影器文件：${file.absolutePath}" }
            val fileName = displayNameOverride?.takeIf { it.isNotBlank() } ?: file.name
            require(fileName.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 视觉投影器文件（通常文件名包含 mmproj）。" }
            val metadata = GgufMetadataReader.read(file)
            require(metadata.isGguf) { "文件头不是 GGUF，可能不是 llama.cpp 可加载的视觉投影器。" }
            require(isVisionProjectorCandidate(fileName, metadata)) {
                "这个 GGUF 不像视觉投影器。请选择与主模型匹配的 mmproj / projector 文件。"
            }

            managedModelDir.mkdirs()
            var ownsTarget = false
            val target = if (file.isUnderManagedModelDir()) {
                file
            } else {
                val destination = uniqueTarget(fileName)
                val staging = atomicImportStagingFile(destination)
                try {
                    val sourceDigest = file.inputStream().use { input -> copyWithSha256(input, staging) }
                    require(staging.length() == file.length()) { "视觉投影器复制不完整。" }
                    require(sha256(staging).equals(sourceDigest, ignoreCase = true)) {
                        "视觉投影器复制后 SHA-256 校验失败。"
                    }
                    require(commitAtomicImportPath(staging, destination)) { "无法原子提交视觉投影器。" }
                    ownsTarget = true
                    destination
                } finally {
                    cleanupAtomicImportStagingFile(staging, requireNotNull(destination.parentFile))
                }
            }
            try {
                val updated = model.copy(
                    visionProjectorPath = target.absolutePath,
                    visionProjectorFileName = target.name,
                    visionProjectorSizeBytes = target.length(),
                    visionProjectorSha256 = sha256(target)
                )
                save(models.map { if (it.id == modelId) updated else it })
                updated
            } catch (error: Throwable) {
                if (ownsTarget) target.delete()
                throw error
            }
        }

    internal fun ModelStoreRepository.markLoadedImpl(id: String) {
        save(listModels().map { model ->
            if (model.id == id) model.copy(lastLoadedAt = System.currentTimeMillis()) else model
        })
    }

    internal fun ModelStoreRepository.updateModelImpl(model: ModelManifest): List<ModelManifest> {
        val models = listModels().map { existing ->
            if (existing.id == model.id) model else existing
        }
        save(models)
        return listModels()
    }

    internal fun ModelStoreRepository.managedFileForImpl(fileName: String): File {
        managedModelDir.mkdirs()
        return uniqueTarget(fileName)
    }

    internal fun ModelStoreRepository.managedBundleDirForImpl(bundleId: String): File {
        managedModelDir.mkdirs()
        // Bundle installers stage components beside this public path and only
        // atomically publish the directory after validation. Creating the
        // destination here made interrupted downloads look like installed
        // models with an empty folder. Direct-file flows still create it via
        // managedBundleFileFor when they are ready to write a file.
        return File(managedModelDir, safeFileName(bundleId))
    }

    internal fun ModelStoreRepository.managedBundleFileForImpl(bundleDir: File, fileName: String): File {
        bundleDir.mkdirs()
        return File(bundleDir, safeFileName(fileName.substringAfterLast('/')))
    }

    private fun ModelStoreRepository.inferQairtDisplayName(value: String): String =
        stripKnownExtension(value.substringAfterLast('/'), ".zip")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "GenieX QAIRT NPU" }
