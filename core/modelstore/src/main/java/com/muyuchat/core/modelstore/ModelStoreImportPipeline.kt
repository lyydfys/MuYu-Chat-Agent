package com.muyuchat.core.modelstore

import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.util.UUID
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.IMPORT_MAGIC_BYTES
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.MODEL_IMPORT_LOCK
import com.muyuchat.core.modelstore.ModelStoreRepository.Companion.MODEL_IMPORT_TASK_LOCK

    internal fun ModelStoreRepository.importFromUrisImpl(
        uris: List<Uri>,
        displayNameOverride: String? = null,
        options: ModelImportOptions = ModelImportOptions(),
        onProgress: (ModelImportProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ModelManifest = synchronized(MODEL_IMPORT_TASK_LOCK) {
            val validUris = uris.distinct()
            require(validUris.isNotEmpty()) { "请选择本地推理模型文件。" }
            checkCancelled()
            onProgress(ModelImportProgress("inspecting"))
            val sources = validUris.mapIndexed { index, uri ->
                modelImportSource(uri, queryDisplayName(uri)?.takeIf(String::isNotBlank) ?: "component-$index")
            }
            val kind = if (sources.size == 1) sources.single().open().use { input ->
                classifyModelImport(sources.single().path, input.readPrefix(IMPORT_MAGIC_BYTES))
            } else ModelImportKind.MNN_COMPONENT
            if (sources.size == 1 && kind == ModelImportKind.MNN_COMPONENT) {
                error("MNN 需要完整模型目录、ZIP 或全部组件；单个 .mnn 文件不能运行。请选择文件夹导入。")
            }
            require(kind != ModelImportKind.UNKNOWN) { "无法识别模型，请选择 GGUF、LiteRT-LM、完整 MNN 目录或 ZIP。" }
            val mapped = if (sources.size > 1) mapMnnSelectedComponents(sources, options.mnnMode) else sources
            importPreparedSources(mapped, "documents:${validUris.map(Uri::toString).sorted().joinToString("\n")}",
                kind, displayNameOverride, options, onProgress, checkCancelled)
        }

    /** SAF tree access keeps nested component names intact; the caller owns persisted URI permission. */
    internal fun ModelStoreRepository.importFromTreeUriImpl(
        treeUri: Uri,
        displayNameOverride: String? = null,
        options: ModelImportOptions = ModelImportOptions(),
        onProgress: (ModelImportProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ModelManifest = synchronized(MODEL_IMPORT_TASK_LOCK) {
        checkCancelled()
        onProgress(ModelImportProgress("inspecting"))
        val pending = ArrayDeque<Pair<String, String>>().apply { add(DocumentsContract.getTreeDocumentId(treeUri) to "") }
        val seen = mutableSetOf<String>()
        val sources = mutableListOf<ModelImportSource>()
        while (pending.isNotEmpty()) {
            checkCancelled()
            val (documentId, prefix) = pending.removeFirst()
            require(seen.add(documentId)) { "模型文件夹包含循环目录。" }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val cursor = appContext.contentResolver.query(children, columns, null, null, null)
                ?: error("无法读取模型文件夹，请重新选择并授予访问权限。")
            cursor.use {
                while (it.moveToNext()) {
                    checkCancelled()
                    val childId = it.getString(0)
                    val name = it.getString(1)
                    require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", "..")) {
                        "模型组件名称无效：$name"
                    }
                    val path = normalizeMnnImportPath(if (prefix.isEmpty()) name else "$prefix/$name")
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) pending += childId to path
                    else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        sources += ModelImportSource(uri.toString(), path,
                            it.takeUnless { c -> c.isNull(3) }?.getLong(3),
                            it.takeUnless { c -> c.isNull(4) }?.getLong(4)) {
                            requireNotNull(appContext.contentResolver.openInputStream(uri)) { "无法读取组件：$path" }
                        }
                    }
                    require(sources.size + pending.size + seen.size <= 32_768) { "模型目录文件数量超过 32768。" }
                    onProgress(ModelImportProgress("inspecting", path, sources.size, 0))
                }
            }
        }
        require(sources.isNotEmpty()) { "所选模型文件夹为空。" }
        val directoryName = displayNameOverride ?: queryDisplayName(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)))
        importPreparedSources(sources.sortedBy { it.path }, "tree:$treeUri", ModelImportKind.MNN_COMPONENT,
            directoryName, options, onProgress, checkCancelled)
    }

    private fun ModelStoreRepository.modelImportSource(uri: Uri, name: String): ModelImportSource =
        ModelImportSource(uri.toString(), name, querySize(uri), runCatching {
            appContext.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull()) { requireNotNull(appContext.contentResolver.openInputStream(uri)) { "无法读取组件：$name" } }

    internal fun ModelStoreRepository.importPreparedSourcesImpl(
        sources: List<ModelImportSource>, identity: String, kind: ModelImportKind,
        displayNameOverride: String?, options: ModelImportOptions,
        onProgress: (ModelImportProgress) -> Unit, checkCancelled: () -> Unit
    ): ModelManifest {
        val sourceFileName = sources.first().path.substringAfterLast('/')
        val name = displayNameOverride?.takeIf(String::isNotBlank) ?: sourceFileName
        val transaction = ResumableModelImport(managedModelDir, "$identity\n${options.mnnMode}", options.resumeKey,
            newDestination = {
                when (kind) {
                    ModelImportKind.GGUF -> uniqueTarget(normalizedGgufImportName(sourceFileName))
                    ModelImportKind.LITERT_LM -> uniqueTarget(normalizedLiteRtLmImportName(sourceFileName))
                    else -> uniqueBundleTarget(stripKnownExtension(name, ".zip").ifBlank { "mnn-bundle" })
                }
            }, onProgress, checkCancelled)
        transaction.completedModel()?.let { completed ->
            checkCancelled()
            if (completed.runtime == ChatModelRuntime.MNN) {
                val readiness = MnnBundleReadinessAnalyzer.analyze(File(completed.path))
                require(readiness.canLoad) { readiness.diagnosticSummary() }
                require(sha256MnnBundle(File(completed.path), readiness.requiredComponentPaths,
                    checkCancelled, onProgress) == completed.sha256) { "已导入模型的组件已变更，请重新导入或重新校验。" }
            }
            return transaction.complete(upsert(completed))
        }
        val destination = transaction.destination
        transaction.requireOwnedDestination()
        // A crash after atomic commit but before catalog publication resumes registration here.
        if (!destination.exists()) {
            val staged = transaction.copySources(sources)
            checkCancelled()
            when (kind) {
                ModelImportKind.GGUF -> {
                    val metadata = GgufMetadataReader.read(staged.single())
                    val compatibility = ModelCompatibility.check(staged.single(), metadata)
                    require(metadata.isGguf && compatibility.canLoad) { compatibility.message }
                    transaction.commit(staged.single())
                }
                ModelImportKind.LITERT_LM -> {
                    val file = staged.single()
                    val compatibility = validateLiteRtLmLoadPreflight(file, file.length())
                    require(compatibility.canLoad) { compatibility.message }
                    transaction.commit(file)
                }
                ModelImportKind.MNN_ZIP -> {
                    val extracted = File(transaction.contentRoot, ".mnn-extracted")
                    if (extracted.exists()) extracted.deleteRecursively()
                    staged.single().inputStream().use { input ->
                        MnnZipBundleInstaller().install(input, extracted, staged.single().length(),
                            options.mnnMode, onProgress, checkCancelled)
                    }
                    transaction.commit(extracted)
                }
                else -> {
                    staged.forEach { file ->
                        val importedKind = file.inputStream().use { classifyModelImport(file.name, it.readPrefix(IMPORT_MAGIC_BYTES)) }
                        require(importedKind !in setOf(ModelImportKind.GGUF, ModelImportKind.LITERT_LM, ModelImportKind.MNN_ZIP)) {
                            "MNN 组件目录中混入其他主模型或压缩包，请只选择一个完整 MNN 模型。"
                        }
                    }
                    val root = findMnnImportBundleRoot(transaction.contentRoot, options.mnnMode, checkCancelled)
                    prepareImportedMnnConfiguration(root, options.mnnMode, checkCancelled)
                    transaction.commit(root)
                }
            }
        }
        checkCancelled()
        val alreadyRegistered = synchronized(MODEL_IMPORT_LOCK) {
            readPersistedModels().firstOrNull { File(it.path).canonicalFile == destination && !isFastRecoveryFingerprint(it.sha256) }
        }
        val model = alreadyRegistered ?: when (kind) {
            ModelImportKind.GGUF, ModelImportKind.LITERT_LM -> {
                val metadata = if (kind == ModelImportKind.GGUF) GgufMetadataReader.read(destination) else null
                val compatibility = if (metadata != null) ModelCompatibility.check(destination, metadata)
                    else validateLiteRtLmLoadPreflight(destination, destination.length())
                require(compatibility.canLoad) { compatibility.message }
                ModelManifest(UUID.randomUUID().toString(), stripKnownExtension(name, if (metadata == null) ".litertlm" else ".gguf"),
                    destination.absolutePath, if (metadata == null) ChatModelRuntime.LITERT_LM else ChatModelRuntime.LLAMA_CPP,
                    ModelSource.LOCAL, fileName = destination.name, sizeBytes = destination.length(),
                    sha256 = cancellableImportSha256(destination, checkCancelled) { bytes ->
                        onProgress(ModelImportProgress("verifying", destination.name, 1, 1, bytes, destination.length()))
                    }, quant = metadata?.quant ?: "LiteRT-LM", architecture = metadata?.architecture).let {
                        checkCancelled()
                        upsert(it)
                    }
            }
            else -> registerDownloadedMnnBundle(
                displayNameOverride ?: inferMnnDisplayName(destination.name), destination, null, null,
                source = ModelSource.LOCAL, requiredFiles = requiredMnnFilesForDirectory(destination),
                checkCancelled = checkCancelled, onProgress = onProgress)
        }
        return transaction.complete(model)
    }

    internal fun ModelStoreRepository.importFromUriImpl(uri: Uri, displayNameOverride: String? = null): ModelManifest =
        importFromUris(listOf(uri), displayNameOverride)

    private fun ModelStoreRepository.importLiteRtLmFromStreamLocked(
        source: InputStream,
        providedName: String?,
        providerReportedSize: Long?
    ): ModelManifest {
        val fileName = normalizedLiteRtLmImportName(providedName)

        managedModelDir.mkdirs()
        val target = uniqueTarget(fileName)
        val staging = atomicImportStagingFile(target)
        var importedDigest: String? = null
        var ownsTarget = false
        try {
            val sourceDigest = copyWithSha256(source, staging)
            verifyProviderCopyAgainstAdvisorySize(providerReportedSize, staging.length())
            val compatibility = validateLiteRtLmLoadPreflight(staging, staging.length())
            require(compatibility.canLoad) { compatibility.message }
            require(sha256(staging).equals(sourceDigest, ignoreCase = true)) {
                "LiteRT-LM 文件复制后 SHA-256 校验失败。"
            }
            require(commitAtomicImportPath(staging, target)) { "无法原子提交导入的 LiteRT-LM 模型。" }
            ownsTarget = true
            importedDigest = sourceDigest
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        } finally {
            cleanupAtomicImportStagingFile(staging, requireNotNull(target.parentFile))
        }

        return try {
            val copiedDigest = requireNotNull(importedDigest) { "LiteRT-LM 导入摘要丢失。" }
            val manifest = ModelManifest(
                id = UUID.randomUUID().toString(),
                displayName = stripKnownExtension(fileName, ".litertlm"),
                path = target.absolutePath,
                runtime = ChatModelRuntime.LITERT_LM,
                source = ModelSource.LOCAL,
                fileName = target.name,
                sizeBytes = target.length(),
                sha256 = copiedDigest,
                quant = "LiteRT-LM"
            )
            upsert(manifest)
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        }
    }

    private fun ModelStoreRepository.importGgufFromStreamLocked(
        source: InputStream,
        providedName: String?,
        providerReportedSize: Long?
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
            verifyProviderCopyAgainstAdvisorySize(providerReportedSize, staging.length())
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
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        }
    }

    private fun ModelStoreRepository.importMnnBundleFromZipStream(source: InputStream, fileName: String): ModelManifest {
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

    internal fun ModelStoreRepository.queryDisplayName(uri: Uri): String? {
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

    internal fun ModelStoreRepository.querySize(uri: Uri): Long? {
        val projection = arrayOf(OpenableColumns.SIZE)
        return runCatching {
            appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    internal fun ModelStoreRepository.uniqueTarget(fileName: String): File {
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

    private fun ModelStoreRepository.uniqueBundleTarget(bundleName: String): File {
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

    internal fun ModelStoreRepository.requiredMnnFilesForDirectory(bundleDir: File): List<String> =
        MnnBundleReadinessAnalyzer.analyze(bundleDir).requiredComponentPaths

    private fun ModelStoreRepository.inferMnnBundleName(fileNames: List<String>): String {
        val candidate = fileNames.firstOrNull {
            val lower = it.lowercase()
            lower.endsWith(".zip") || lower.contains("qwen", ignoreCase = true) || lower.contains("gemma", ignoreCase = true)
        } ?: "mnn-bundle-${System.currentTimeMillis()}"
        return stripKnownExtension(candidate.substringAfterLast('/'), ".zip")
            .ifBlank { "mnn-bundle-${System.currentTimeMillis()}" }
    }

    internal fun ModelStoreRepository.inferMnnDisplayName(value: String): String =
        stripKnownExtension(value.substringAfterLast('/'), ".zip")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "MNN 高速引擎" }
