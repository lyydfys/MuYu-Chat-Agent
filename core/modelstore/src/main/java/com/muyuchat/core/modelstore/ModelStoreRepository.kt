package com.muyuchat.core.modelstore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.UUID

class ModelStoreRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val manifestFile: File by lazy { File(appContext.filesDir, "mca-models.json") }
    private val managedModelDir: File by lazy {
        (appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")).also { it.mkdirs() }
    }

    init {
        runCatching { cleanupStaleAtomicImports(managedModelDir) }
    }

    fun listModels(): List<ModelManifest> {
        val persisted = readPersistedModels()
        val normalized = persisted.map { normalizeManifest(it) }
        val loadablePersisted = normalized.filter { model ->
            when (model.runtime) {
                ChatModelRuntime.MNN -> isCompleteMnnBundleDirectory(File(model.path))
                ChatModelRuntime.GENIEX_QAIRT -> isCompleteQairtBundleDirectory(File(model.path))
                ChatModelRuntime.LLAMA_CPP -> true
            }
        }
        val recovered = recoverManagedMnnBundles(loadablePersisted)
        val merged = (loadablePersisted + recovered).distinctByPathKeepingNewest()
        if (normalized != persisted || loadablePersisted != normalized || recovered.isNotEmpty()) {
            save(merged)
        }
        return merged.sortedByDescending { it.createdAt }
    }

    private fun readPersistedModels(): List<ModelManifest> {
        recoverManifestCommitIfNeeded()
        if (!manifestFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(manifestFile.readText(Charsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    add(ModelManifest.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun getModel(id: String): ModelManifest? = listModels().firstOrNull { it.id == id }

    fun importFromUris(uris: List<Uri>, displayNameOverride: String? = null): ModelManifest =
        synchronized(MODEL_IMPORT_LOCK) {
            val validUris = uris.distinct()
            require(validUris.isNotEmpty()) { "请选择本地推理模型文件。" }
            if (validUris.size == 1) {
                return@synchronized importSingleUriLocked(
                    uri = validUris.single(),
                    displayNameOverride = displayNameOverride,
                    allowMnnZip = true
                )
            }
            importMnnBundleFromUris(validUris, displayNameOverride)
        }

    fun importFromUri(uri: Uri, displayNameOverride: String? = null): ModelManifest =
        synchronized(MODEL_IMPORT_LOCK) {
            importSingleUriLocked(
                uri = uri,
                displayNameOverride = displayNameOverride,
                allowMnnZip = false
            )
        }

    private fun importSingleUriLocked(
        uri: Uri,
        displayNameOverride: String?,
        allowMnnZip: Boolean
    ): ModelManifest {
        val providedName = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri)
        val expectedSize = querySize(uri).reliableProviderSize()
        return appContext.contentResolver.openInputStream(uri).use { rawInput ->
            requireNotNull(rawInput) { "无法读取所选文件。" }
            val source = PushbackInputStream(rawInput.buffered(), IMPORT_MAGIC_BYTES)
            val header = source.readPrefix(IMPORT_MAGIC_BYTES)
            if (header.isNotEmpty()) source.unread(header)
            when (classifyModelImport(providedName, header)) {
                ModelImportKind.GGUF -> importGgufFromStreamLocked(source, providedName, expectedSize)
                ModelImportKind.MNN_ZIP -> {
                    require(allowMnnZip) { "请选择有效的 GGUF 模型文件。" }
                    importMnnBundleFromZipStream(
                        source = source,
                        fileName = providedName?.trim().orEmpty().ifBlank { "mnn-bundle.zip" }
                    )
                }
                ModelImportKind.MNN_COMPONENT ->
                    error("MNN 高速引擎需要同时选择 config.json、llm_config.json、llm.mnn、llm.mnn.weight、tokenizer，以及 embeddings_bf16.bin / llm.mnn.json；或导入完整 zip 包。")
                ModelImportKind.UNKNOWN -> error("无法识别所选文件。请选择 GGUF 模型，或选择完整 MNN 组件 / zip 包。")
            }
        }
    }

    private fun importGgufFromStreamLocked(
        source: InputStream,
        providedName: String?,
        expectedSize: Long?
    ): ModelManifest {
        val fileName = normalizedGgufImportName(providedName)

        managedModelDir.mkdirs()
        val target = uniqueTarget(fileName)
        val staging = atomicImportStagingFile(target)
        var importedDigest: String? = null
        var importedMetadata: GgufMetadata? = null
        var ownsTarget = false
        try {
            val sourceDigest = copyWithSha256(source, staging)
            expectedSize?.let { size ->
                require(staging.length() == size) { "GGUF 文件复制不完整：源文件大小与导入结果不一致。" }
            }
            val copiedMetadata = GgufMetadataReader.read(staging)
            require(copiedMetadata.isGguf) { "GGUF 文件复制后文件头校验失败。" }
            require(sha256(staging).equals(sourceDigest, ignoreCase = true)) {
                "GGUF 文件复制后 SHA-256 校验失败。"
            }
            val compatibility = ModelCompatibility.check(staging, copiedMetadata)
            require(compatibility.canLoad) { compatibility.message }
            require(commitAtomicImportPath(staging, target)) { "无法原子提交导入的 GGUF 模型。" }
            ownsTarget = true
            importedDigest = sourceDigest
            importedMetadata = copiedMetadata
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        } finally {
            cleanupAtomicImportStagingFile(staging, requireNotNull(target.parentFile))
        }

        return try {
            val copiedMetadata = requireNotNull(importedMetadata) { "GGUF 导入元数据丢失。" }
            val copiedDigest = requireNotNull(importedDigest) { "GGUF 导入摘要丢失。" }
            val manifest = ModelManifest(
                id = UUID.randomUUID().toString(),
                displayName = fileName.removeSuffix(".gguf"),
                path = target.absolutePath,
                runtime = ChatModelRuntime.LLAMA_CPP,
                source = ModelSource.LOCAL,
                fileName = target.name,
                sizeBytes = target.length(),
                sha256 = copiedDigest,
                quant = copiedMetadata.quant,
                architecture = copiedMetadata.architecture
            )
            upsert(manifest)
            manifest
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        }
    }

    private fun importMnnBundleFromUris(uris: List<Uri>, displayNameOverride: String? = null): ModelManifest {
        val namedUris = uris.mapIndexed { index, uri ->
            val name = queryDisplayName(uri)?.takeIf { it.isNotBlank() } ?: "mnn-component-$index"
            uri to name
        }
        // Do not pre-classify by a fixed set of basenames here.  The readiness
        // analyzer below is config-driven and accepts valid exporters that use
        // renamed component files (and keeps the legacy flat layout compatible).
        // A multi-select import cannot preserve directory parents, however, so
        // reject sanitized basename collisions instead of silently overwriting
        // one selected component with another.
        val fileNames = namedUris.map { it.second }
        val sanitizedNames = fileNames.map(::safeFileName)
        require(sanitizedNames.distinct().size == sanitizedNames.size) {
            "MNN 组件导入包含重名文件；请一次只选择每个组件的唯一文件名，或改用完整 ZIP 包。"
        }

        val bundleDir = uniqueBundleTarget(displayNameOverride ?: inferMnnBundleName(fileNames))
        val stagingDir = File(bundleDir.parentFile, ".${bundleDir.name}.importing-${UUID.randomUUID()}")
        var ownsBundleDir = false
        try {
            require(stagingDir.mkdirs()) { "无法创建 MNN 组件导入暂存目录。" }
            namedUris.forEach { (uri, name) ->
                val target = managedBundleFileFor(stagingDir, name)
                val sourceDigest = appContext.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取 MNN 组件：$name" }
                    copyWithSha256(input, target)
                }
                querySize(uri).reliableProviderSize()?.let { expectedSize ->
                    require(target.length() == expectedSize) { "MNN 组件复制不完整：$name" }
                }
                require(sha256(target).equals(sourceDigest, ignoreCase = true)) { "MNN 组件复制校验失败：$name" }
                val importedKind = target.inputStream().use { input ->
                    classifyModelImport(name, input.readPrefix(IMPORT_MAGIC_BYTES))
                }
                require(importedKind != ModelImportKind.GGUF && importedKind != ModelImportKind.MNN_ZIP) {
                    "一次只能导入一个 GGUF 主模型或一个 MNN ZIP；MNN 多选仅用于完整组件文件。"
                }
            }
            val readiness = MnnBundleReadinessAnalyzer.analyze(stagingDir)
            require(readiness.canLoad) { "MNN 模型包不完整：${readiness.diagnosticSummary()}" }
            require(commitAtomicImportPath(stagingDir, bundleDir)) { "无法原子提交导入的 MNN 模型包。" }
            ownsBundleDir = true
        } catch (error: Throwable) {
            stagingDir.deleteRecursively()
            if (ownsBundleDir) bundleDir.deleteRecursively()
            throw error
        }
        return try {
            registerDownloadedMnnBundle(
                displayName = displayNameOverride ?: inferMnnDisplayName(bundleDir.name),
                bundleDir = bundleDir,
                repoId = null,
                revision = null,
                source = ModelSource.LOCAL,
                requiredFiles = requiredMnnFilesForDirectory(bundleDir)
            )
        } catch (error: Throwable) {
            if (ownsBundleDir) bundleDir.deleteRecursively()
            throw error
        }
    }

    private fun importMnnBundleFromZipStream(source: InputStream, fileName: String): ModelManifest {
        val bundleName = stripKnownExtension(fileName, ".zip").ifBlank { "mnn-bundle" }
        val bundleDir = uniqueBundleTarget(bundleName)
        var ownsBundleDir = false
        try {
            MnnZipBundleInstaller().install(
                source = source,
                finalBundleRoot = bundleDir,
                // OpenableColumns.SIZE is provider metadata, not a trustworthy
                // compressed-byte counter. Per-entry and absolute extraction
                // limits remain active inside the installer.
                compressedSizeBytes = null
            )
            ownsBundleDir = true
            return registerDownloadedMnnBundle(
                displayName = inferMnnDisplayName(bundleName),
                bundleDir = bundleDir,
                repoId = null,
                revision = null,
                source = ModelSource.LOCAL,
                requiredFiles = requiredMnnFilesForDirectory(bundleDir)
            )
        } catch (error: Throwable) {
            if (ownsBundleDir) bundleDir.deleteRecursively()
            throw error
        }
    }

    fun registerDownloadedModel(
        file: File,
        repoId: String,
        revision: String,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE
    ): ModelManifest {
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
        upsert(manifest)
        return manifest
    }

    fun registerDownloadedMnnBundle(
        displayName: String,
        bundleDir: File,
        repoId: String?,
        revision: String?,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE,
        quant: String? = "MNN",
        architecture: String? = null,
        requiredFiles: List<String> = emptyList()
    ): ModelManifest {
        val declaredRequiredFiles = requiredFiles.map(::normalizeMnnComponentPath).distinct()
        val readiness = MnnBundleReadinessAnalyzer.analyze(
            bundleDir = bundleDir,
            additionalRequiredComponents = declaredRequiredFiles
        )
        require(readiness.canLoad) { "MNN 模型包不完整：${readiness.diagnosticSummary()}" }
        val fingerprintFiles = (readiness.requiredComponentPaths + declaredRequiredFiles).distinct()
        val coreSizeBytes = mnnBundleSize(bundleDir, fingerprintFiles)
        val coreSha256 = sha256MnnBundle(bundleDir, fingerprintFiles)
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
            architecture = architecture,
            license = license
        )
        upsert(manifest)
        return manifest
    }

    fun registerDownloadedQairtBundle(
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
        require(isCompleteQairtBundleDirectory(resolvedBundleDir)) {
            "QAIRT 模型包不完整：需要解包后的 GenieX/QAIRT 配置、context/bin 或模型资产文件。"
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
        upsert(manifest)
        return manifest
    }

    /**
     * Resolves the only safe packaging wrapper we support: an otherwise empty directory
     * containing one complete QAIRT bundle directory. Ambiguous archives remain invalid.
     */
    fun resolveQairtBundleRoot(bundleDir: File): File = findQairtBundleRoot(bundleDir)
        ?: error("QAIRT 模型包根目录不明确或不完整：${bundleDir.absolutePath}")

    fun deleteModel(id: String): Boolean {
        val models = listModels()
        val target = models.firstOrNull { it.id == id } ?: return false
        runCatching {
            val file = File(target.path)
            val bundleDir = file.takeIf { it.isDirectory } ?: file.parentFile?.takeIf { it.isMcaManagedBundleDir() }
            if (bundleDir != null) bundleDir.deleteRecursively() else file.delete()
        }
        runCatching {
            val projector = target.visionProjectorPath?.let(::File)
            if (projector != null &&
                projector.parentFile?.absolutePath == managedModelDir.absolutePath &&
                projector.exists()
            ) {
                projector.delete()
            }
        }
        save(models.filterNot { it.id == id })
        return true
    }

    fun attachVisionProjector(modelId: String, uri: Uri, displayNameOverride: String? = null): ModelManifest =
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
                querySize(uri).reliableProviderSize()?.let { expectedSize ->
                    require(staging.length() == expectedSize) { "视觉投影器复制不完整。" }
                }
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

    fun attachVisionProjectorFile(modelId: String, file: File, displayNameOverride: String? = null): ModelManifest =
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

    fun markLoaded(id: String) {
        save(listModels().map { model ->
            if (model.id == id) model.copy(lastLoadedAt = System.currentTimeMillis()) else model
        })
    }

    fun updateModel(model: ModelManifest): List<ModelManifest> {
        val models = listModels().map { existing ->
            if (existing.id == model.id) model else existing
        }
        save(models)
        return listModels()
    }

    fun verify(id: String): Boolean {
        val model = getModel(id) ?: return false
        val file = File(model.path)
        return when (model.runtime) {
            ChatModelRuntime.MNN -> validateMnnBundle(model, file).canLoad
            ChatModelRuntime.GENIEX_QAIRT -> validateQairtBundle(model, file).canLoad
            ChatModelRuntime.LLAMA_CPP ->
                file.exists() && file.length() == model.sizeBytes && sha256(file).equals(model.sha256, ignoreCase = true)
        }
    }

    fun validateForLoad(id: String): ModelCompatibilityResult {
        val model = getModel(id) ?: return ModelCompatibilityResult(
            canLoad = false,
            title = "模型清单不存在",
            details = id
        )
        val file = File(model.path)
        if (model.runtime == ChatModelRuntime.MNN) {
            return validateMnnBundle(model, file)
        }
        if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
            return validateQairtBundle(model, file)
        }
        val compatibility = runCatching { ModelCompatibility.check(file) }.getOrElse { error ->
            ModelCompatibilityResult(
                canLoad = false,
                title = "模型预检失败",
                details = error.message.orEmpty()
            )
        }
        if (!compatibility.canLoad) return compatibility
        val hashOk = runCatching {
            file.length() == model.sizeBytes && sha256(file).equals(model.sha256, ignoreCase = true)
        }.getOrDefault(false)
        val projectorOk = model.visionProjectorPath?.let { path ->
            val projector = File(path)
            projector.exists() &&
                projector.length() == model.visionProjectorSizeBytes &&
                model.visionProjectorSha256?.let { sha256(projector).equals(it, ignoreCase = true) } != false
        } ?: true
        return if (hashOk) {
            if (projectorOk) {
                compatibility
            } else {
                ModelCompatibilityResult(
                    canLoad = false,
                    title = "视觉投影器校验失败",
                    details = "绑定的 mmproj 文件不存在、大小变化或 SHA-256 不一致，请重新绑定视觉文件"
                )
            }
        } else {
            ModelCompatibilityResult(
                canLoad = false,
                title = "模型文件校验失败",
                details = "文件大小或 SHA-256 与导入/下载时不一致，建议删除后重新下载"
            )
        }
    }

    fun managedFileFor(fileName: String): File {
        managedModelDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun managedBundleDirFor(bundleId: String): File {
        managedModelDir.mkdirs()
        return File(managedModelDir, safeFileName(bundleId)).also { it.mkdirs() }
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        bundleDir.mkdirs()
        return File(bundleDir, safeFileName(fileName.substringAfterLast('/')))
    }

    private fun upsert(model: ModelManifest) {
        val modelPath = File(model.path).absolutePath
        val without = listModels().filterNot { it.id == model.id || File(it.path).absolutePath == modelPath }
        save(without + model)
    }

    @Synchronized
    private fun save(models: List<ModelManifest>) {
        val array = JSONArray()
        models.sortedByDescending { it.createdAt }.forEach { array.put(it.toJson()) }
        val parent = requireNotNull(manifestFile.parentFile) { "模型清单目录不存在。" }
        parent.mkdirs()
        val staging = File(parent, ".${manifestFile.name}.writing")
        val backup = File(parent, ".${manifestFile.name}.backup")
        if (staging.exists() && !staging.delete()) error("无法清理旧的模型清单暂存文件。")
        FileOutputStream(staging).use { output ->
            output.write(array.toString(2).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        JSONArray(staging.readText(Charsets.UTF_8))
        if (backup.exists() && !backup.delete()) error("无法清理旧的模型清单备份。")
        val hadManifest = manifestFile.exists()
        if (hadManifest && !manifestFile.renameTo(backup)) {
            staging.delete()
            error("无法备份当前模型清单。")
        }
        if (!staging.renameTo(manifestFile)) {
            if (hadManifest && !manifestFile.exists()) backup.renameTo(manifestFile)
            staging.delete()
            error("无法原子提交模型清单。")
        }
        backup.delete()
    }

    private fun recoverManifestCommitIfNeeded() {
        val parent = manifestFile.parentFile ?: return
        val staging = File(parent, ".${manifestFile.name}.writing")
        val backup = File(parent, ".${manifestFile.name}.backup")
        when {
            manifestFile.isFile -> {
                staging.delete()
                backup.delete()
            }
            backup.isFile -> {
                backup.renameTo(manifestFile)
                staging.delete()
            }
            staging.isFile && runCatching {
                JSONArray(staging.readText(Charsets.UTF_8))
            }.isSuccess -> staging.renameTo(manifestFile)
            else -> staging.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val providerName = runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (providerName != null) return providerName
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun querySize(uri: Uri): Long? {
        val projection = arrayOf(OpenableColumns.SIZE)
        return runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = safeFileName(fileName)
        var target = File(managedModelDir, safeName)
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        var index = 1
        while (target.exists()) {
            val nextName = if (extension == null) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedModelDir, nextName)
            index += 1
        }
        return target
    }

    private fun uniqueBundleTarget(bundleName: String): File {
        managedModelDir.mkdirs()
        val safeName = safeFileName(bundleName).ifBlank { "mnn-bundle" }
        var target = File(managedModelDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedModelDir, "$safeName-$index")
            index += 1
        }
        return target
    }

    private fun requiredMnnFilesForDirectory(bundleDir: File): List<String> =
        MnnBundleReadinessAnalyzer.analyze(bundleDir).requiredComponentPaths

    private fun inferMnnBundleName(fileNames: List<String>): String {
        val candidate = fileNames.firstOrNull {
            val lower = it.lowercase()
            lower.endsWith(".zip") || lower.contains("qwen", ignoreCase = true) || lower.contains("gemma", ignoreCase = true)
        } ?: "mnn-bundle-${System.currentTimeMillis()}"
        return stripKnownExtension(candidate.substringAfterLast('/'), ".zip")
            .ifBlank { "mnn-bundle-${System.currentTimeMillis()}" }
    }

    private fun inferMnnDisplayName(value: String): String =
        stripKnownExtension(value.substringAfterLast('/'), ".zip")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "MNN 高速引擎" }

    private fun inferQairtDisplayName(value: String): String =
        stripKnownExtension(value.substringAfterLast('/'), ".zip")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "GenieX QAIRT NPU" }

    private fun recoverManagedMnnBundles(existing: List<ModelManifest>): List<ModelManifest> {
        val existingPaths = existing.map { File(it.path).absolutePath }.toSet()
        return managedModelDir.listFiles()
            ?.filter { candidate ->
                isCompleteMnnBundleDirectory(candidate) &&
                    File(candidate.absolutePath).absolutePath !in existingPaths
            }
            ?.mapNotNull { bundleDir ->
                runCatching {
                    val requiredFiles = requiredMnnFilesForDirectory(bundleDir)
                    val coreSizeBytes = mnnBundleSize(bundleDir, requiredFiles)
                    val coreSha256 = sha256MnnBundle(bundleDir, requiredFiles)
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
                        architecture = null,
                        createdAt = bundleDir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }.getOrNull()
            }
            .orEmpty()
    }

    /**
     * GenieX keeps an installed copy under internal app storage after its SDK
     * registration succeeds. Preserve that expensive local asset across an MCA
     * manifest loss/reinstall instead of requiring another multi-gigabyte download.
     *
     * This is only product registration recovery: it creates no execution
     * allowlist entry. The restored exact bundle fingerprint must still pass the
     * isolated QAIRT worker on the current chipset and runtime.
     */
    @Synchronized
    fun recoverInstalledQairtBundles(): List<ModelManifest> {
        val existing = listModels()
        val sdkModelsRoot = File(appContext.filesDir, "geniex/models")
        val recovered = findRecoverableInstalledQairtBundles(sdkModelsRoot, existing).mapNotNull { candidate ->
            runCatching {
                val bundleDir = candidate.bundleDir
                ModelManifest(
                    id = UUID.nameUUIDFromBytes(
                        "qairt-sdk:${bundleDir.canonicalPath}".toByteArray(Charsets.UTF_8)
                    ).toString(),
                    displayName = bundleDir.name,
                    path = bundleDir.absolutePath,
                    runtime = ChatModelRuntime.GENIEX_QAIRT,
                    source = ModelSource.HUGGING_FACE,
                    repoId = candidate.repoId,
                    revision = null,
                    fileName = bundleDir.name,
                    sizeBytes = directorySize(bundleDir),
                    sha256 = sha256Directory(bundleDir),
                    quant = "w4a16 QAIRT",
                    architecture = "GenieX QAIRT",
                    createdAt = bundleDir.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            }.getOrNull()
        }
        if (recovered.isNotEmpty()) {
            save((existing + recovered).distinctByPathKeepingNewest())
        }
        return recovered
    }

    private fun normalizeManifest(model: ModelManifest): ModelManifest = when (model.runtime) {
        ChatModelRuntime.MNN -> normalizeMnnManifest(model)
        ChatModelRuntime.GENIEX_QAIRT -> normalizeQairtManifest(model)
        ChatModelRuntime.LLAMA_CPP -> model
    }

    private fun normalizeMnnManifest(model: ModelManifest): ModelManifest {
        val bundleDir = File(model.path)
        val inferredName = inferMnnDisplayName(bundleDir.name)
        if (inferredName.isBlank() || inferredName == model.displayName) return model
        val current = model.displayName.trim()
        val compactCurrent = current.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactInferred = inferredName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val looksTruncated = current.isBlank() ||
            compactCurrent.length < compactInferred.length &&
            compactInferred.startsWith(compactCurrent)
        return if (looksTruncated) {
            model.copy(displayName = inferredName, fileName = bundleDir.name.ifBlank { model.fileName })
        } else {
            model
        }
    }

    private fun normalizeQairtManifest(model: ModelManifest): ModelManifest {
        val normalized = normalizeQairtManifestRoot(model)
        if (normalized == model) return model
        val bundleDir = File(normalized.path)
        val size = directorySize(bundleDir)
        val hash = runCatching { sha256Directory(bundleDir) }.getOrNull() ?: return normalized
        return normalized.copy(sizeBytes = size, sha256 = hash)
    }

    private fun List<ModelManifest>.distinctByPathKeepingNewest(): List<ModelManifest> =
        sortedByDescending { it.createdAt }
            .distinctBy { File(it.path).absolutePath }

    private fun stripKnownExtension(value: String, extension: String): String =
        if (value.endsWith(extension, ignoreCase = true)) value.dropLast(extension.length) else value

    private fun validateMnnBundle(model: ModelManifest, bundleDir: File): ModelCompatibilityResult {
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
        val coreHash = runCatching { sha256MnnBundle(bundleDir, requiredFiles) }.getOrNull()
        val hasReadableCore = coreHash != null && requiredFiles.all { File(bundleDir, it).length() > 0L }
        val coreFingerprintOk = hasReadableCore &&
            coreSize == model.sizeBytes &&
            coreHash.equals(model.sha256, ignoreCase = true)
        val legacyDirectoryFingerprintOk = runCatching {
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
            ModelCompatibilityResult(
                canLoad = false,
                title = "MNN 模型包校验失败",
                details = "核心组件不可读或为空，请重新下载 MNN 引擎包"
            )
        }
    }

    private fun validateQairtBundle(model: ModelManifest, bundleDir: File): ModelCompatibilityResult {
        if (!bundleDir.exists()) {
            return ModelCompatibilityResult(false, "QAIRT 模型包不存在", bundleDir.absolutePath)
        }
        if (!bundleDir.isDirectory) {
            return ModelCompatibilityResult(false, "QAIRT 模型包路径不是目录", bundleDir.absolutePath)
        }
        if (!isCompleteQairtBundleDirectory(bundleDir)) {
            return ModelCompatibilityResult(false, "QAIRT 模型包不完整", "请删除后重新下载完整 GenieX QAIRT zip 包")
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
                "size=${formatBytes(size)}, ${graphProfile.diagnosticSummary()}"
        )
    }

    private fun migrateMnnFingerprint(model: ModelManifest, coreSize: Long, coreHash: String) {
        if (coreHash.isBlank()) return
        if (model.sizeBytes == coreSize && model.sha256.equals(coreHash, ignoreCase = true)) return
        runCatching {
            updateModel(model.withMigratedMnnFingerprint(coreSize, coreHash))
        }
    }

    private fun isVisionProjectorCandidate(fileName: String, metadata: GgufMetadata): Boolean {
        val lower = fileName.lowercase()
        val architecture = metadata.architecture?.lowercase().orEmpty()
        return "mmproj" in lower ||
            "projector" in lower ||
            lower.startsWith("clip") ||
            architecture == "clip"
    }

    private fun sha256(file: File): String {
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

    private fun copyWithSha256(input: java.io.InputStream, target: File): String {
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

    private fun sha256Directory(directory: File): String {
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

    private fun directorySize(directory: File): Long =
        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun sha256MnnBundle(directory: File, requiredFiles: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
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
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mnnBundleSize(directory: File, requiredFiles: List<String>): Long =
        requiredFiles
            .map(::normalizeMnnComponentPath)
            .distinct()
            .sumOf { path -> File(directory, path).length() }

    private fun normalizeMnnComponentPath(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "MNN component path must not be blank." }
        require('\u0000' !in normalized) { "MNN component path contains a NUL character." }
        require(!normalized.startsWith('/')) { "MNN component path must be relative: $rawPath" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            "MNN component path must be relative: $rawPath"
        }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "MNN component path contains an invalid segment: $rawPath"
        }
        return segments.joinToString("/")
    }

    private fun safeFileName(fileName: String): String =
        fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun File.isMcaManagedBundleDir(): Boolean {
        if (!isDirectory) return false
        val managedRoot = runCatching { managedModelDir.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { canonicalFile }.getOrNull() ?: return false
        if (candidate.path == managedRoot.path || !candidate.path.startsWith(managedRoot.path + File.separator)) {
            return false
        }
        val schema = runCatching {
            val manifest = File(candidate, "manifest.json")
            if (manifest.isFile) manifest.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
        return "mca.vision_engine.bundle.v1" in schema ||
            "mca.image_engine.bundle.v1" in schema ||
            "mca.qairt_chat.bundle.v1" in schema
    }

    private fun File.isUnderManagedModelDir(): Boolean {
        val managedRoot = runCatching { managedModelDir.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { canonicalFile }.getOrNull() ?: return false
        return candidate.path == managedRoot.path ||
            candidate.path.startsWith(managedRoot.path + File.separator)
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / GB.toDouble()
        val mb = bytes / MB.toDouble()
        return if (gb >= 1.0) "%.2fGB".format(gb) else "%.1fMB".format(mb)
    }

    private companion object {
        private val MODEL_IMPORT_LOCK = Any()
        private const val IMPORT_MAGIC_BYTES = 4
        private const val MB = 1024L * 1024L
        private const val GB = 1024L * MB
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
    }

}

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
    val root = bundleDir.canonicalDirectoryOrNull() ?: return false
    if (!root.hasQairtRootMarker()) return false
    val files = root.safeFilesUnderRoot()
    if (files.isEmpty()) return false
    val names = files.map { it.name.lowercase() }
    val hasRuntimeConfig = names.any { name ->
        name == "genie_config.json" ||
            name == "htp_backend_ext_config.json" ||
            name == "config.json" ||
            name.endsWith(".json") && ("genie" in name || "qairt" in name || "qnn" in name)
    }
    val hasQairtArtifact = names.any { name ->
        name.endsWith(".bin") ||
            name.endsWith(".serialized") ||
            name.endsWith(".ctx") ||
            name.endsWith(".qnn") ||
            name.endsWith(".so") && ("qnn" in name || "genie" in name)
    }
    return hasRuntimeConfig && hasQairtArtifact
}

/** Returns this root or one unambiguous, in-place wrapper directory; never follows an external child. */
internal fun findQairtBundleRoot(bundleDir: File): File? {
    val root = bundleDir.canonicalDirectoryOrNull() ?: return null
    if (isCompleteQairtBundleDirectory(root)) return root

    val entries = root.listFiles()?.toList() ?: return null
    if (entries.size != 1) return null
    val child = entries.single().canonicalDirectoryOrNull() ?: return null
    if (child.parentFile?.canonicalFile?.path != root.path) return null
    return child.takeIf(::isCompleteQairtBundleDirectory)
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

private fun File.safeFilesUnderRoot(): List<File> {
    val root = canonicalDirectoryOrNull() ?: return emptyList()
    val files = mutableListOf<File>()
    val pending = ArrayDeque<File>().apply { add(root) }
    val visited = mutableSetOf(root.path)
    while (pending.isNotEmpty()) {
        val directory = pending.removeFirst()
        directory.listFiles()?.forEach { child ->
            val canonical = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(root.path + File.separator)) return@forEach
            when {
                canonical.isFile && canonical.length() > 0L -> files += canonical
                canonical.isDirectory && visited.add(canonical.path) -> pending += canonical
            }
        }
    }
    return files
}

/**
 * Keeps the exact destination basename and extension visible while a
 * user-supplied model is copied atomically. GGUF compatibility checks
 * intentionally inspect both the file suffix and basename (for
 * mmproj/MTP/split-part protection), so only the parent directory may carry
 * the transaction marker.
 */
internal fun atomicImportStagingFile(
    target: File,
    transactionId: String = UUID.randomUUID().toString()
): File {
    require(SAFE_IMPORT_TRANSACTION_ID.matches(transactionId)) {
        "Import transaction id contains unsupported characters."
    }
    return File(File(target.parentFile, ".importing-$transactionId"), target.name)
}

/**
 * The repository serializes imports in-process; this final existence check
 * additionally makes a failed commit non-destructive if another producer has
 * materialized the chosen destination since it was selected.
 */
internal fun commitAtomicImportPath(staging: File, target: File): Boolean {
    if (target.exists()) return false
    return staging.renameTo(target)
}

internal fun cleanupAtomicImportStagingFile(staging: File, expectedParent: File): Boolean {
    val root = runCatching { expectedParent.canonicalFile }.getOrNull() ?: return false
    val requestedStagingDirectory = staging.parentFile ?: return false
    val stagingDirectory = runCatching { requestedStagingDirectory.canonicalFile }.getOrNull() ?: return false
    if (requestedStagingDirectory.absolutePath != stagingDirectory.absolutePath) return false
    if (stagingDirectory.parentFile?.canonicalFile != root) return false
    if (!stagingDirectory.name.startsWith(".importing-")) return false
    return stagingDirectory.deleteRecursively()
}

internal fun cleanupStaleAtomicImports(
    managedRoot: File,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = 24L * 60L * 60L * 1000L
): Int {
    val root = runCatching { managedRoot.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
        ?: return 0
    return root.listFiles().orEmpty().count { candidate ->
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return@count false
        if (candidate.absolutePath != canonical.absolutePath) return@count false
        if (canonical.parentFile?.canonicalFile != root) return@count false
        val isStagingArtifact = when {
            canonical.isDirectory -> CURRENT_IMPORT_DIRECTORY.matches(canonical.name) ||
                LEGACY_NAMED_IMPORT_ARTIFACT.matches(canonical.name)
            canonical.isFile -> LEGACY_NAMED_IMPORT_ARTIFACT.matches(canonical.name)
            else -> false
        }
        if (!isStagingArtifact) return@count false
        val modifiedAt = canonical.lastModified().takeIf { it > 0L } ?: return@count false
        if (nowMillis - modifiedAt < minimumAgeMillis) return@count false
        candidate.deleteRecursively()
    }
}

internal fun Long?.reliableProviderSize(): Long? = this?.takeIf { it > 0L }

private val SAFE_IMPORT_TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,128}")
private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val CURRENT_IMPORT_DIRECTORY = Regex("^\\.importing-$UUID_PATTERN$")
private val LEGACY_NAMED_IMPORT_ARTIFACT = Regex("^\\..+\\.importing-$UUID_PATTERN$")

