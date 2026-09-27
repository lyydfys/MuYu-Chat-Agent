package com.muyuchat.core.modelstore

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.UUID

private val CANONICAL_SHA256 = Regex("^[0-9a-fA-F]{64}$")

private fun String.isCanonicalSha256(): Boolean = CANONICAL_SHA256.matches(trim())

class ModelStoreRepository(private val context: Context) {
    private val appContext = context.applicationContext
    private val manifestFile: File by lazy { File(appContext.filesDir, "mca-models.json") }
    private val managedModelDir: File by lazy {
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
    }

    fun listModels(): List<ModelManifest> = synchronized(MODEL_IMPORT_LOCK) {
        val persisted = readPersistedModels()
        val normalized = persisted.map { normalizeManifest(it) }
        // Catalog reads must retain damaged records for diagnosis and never
        // merge or delete distinct physical files from a bounded fingerprint.
        val pathDistinct = deduplicateModelRecordsByCanonicalPath(normalized)
        val recoveredMnn = recoverManagedMnnBundles(pathDistinct)
        val recoveredGguf = recoverManagedGgufModels(pathDistinct + recoveredMnn)
        val recoveredLiteRtLm = recoverManagedLiteRtLmModels(pathDistinct + recoveredMnn + recoveredGguf)
        val merged = deduplicateModelRecordsByCanonicalPath(
            pathDistinct + recoveredMnn + recoveredGguf + recoveredLiteRtLm
        )
        merged.sortedByDescending { it.createdAt }
    }

    private fun readPersistedModels(): List<ModelManifest> {
        val backups = listOf(
            manifestFile,
            File(manifestFile.parentFile, ".${manifestFile.name}.backup"),
            File(manifestFile.parentFile, ".${manifestFile.name}.writing")
        )
        var damaged: List<ModelManifest>? = null
        backups.forEach { source ->
            if (source.isFile) {
                runCatching { parsePersistedModelManifest(source.readText(Charsets.UTF_8)) }
                    .getOrNull()?.let { parsed ->
                        if (parsed.none { it.integrityState == ModelIntegrityState.DAMAGED }) return parsed
                        if (damaged == null) damaged = parsed
                    }
            }
        }
        return damaged.orEmpty()
    }

    fun getModel(id: String): ModelManifest? = listModels().firstOrNull { it.id == id || id in it.aliases }

    /**
     * Explicit, full-byte identity audit. The catalog is not rewritten until
     * every record has been checked and the replacement manifest is durable.
     * The caller can then migrate external references with [idMapping].
     */
    fun reconcileStableIdentities(): ModelIdentityReconciliation = synchronized(MODEL_IMPORT_LOCK) {
        val persisted = readPersistedModels()
        val catalog = listModels()
        val retainedCopies = persisted.flatMap { model ->
            model.physicalCopies.filter { copy -> canonicalModelPath(copy.path) != canonicalModelPath(model.path) }
                .map { copy -> model.copy(
                id = UUID.nameUUIDFromBytes(
                    "retained-copy:${model.id}:${canonicalModelPath(copy.path)}".toByteArray(Charsets.UTF_8)
                ).toString(),
                path = copy.path,
                sizeBytes = copy.sizeBytes,
                sha256 = copy.sha256,
                visionProjectorPath = copy.visionProjectorPath,
                visionProjectorSizeBytes = copy.visionProjectorSizeBytes,
                visionProjectorSha256 = copy.visionProjectorSha256,
                source = copy.source,
                repoId = copy.repoId,
                revision = copy.revision,
                createdAt = Long.MIN_VALUE,
                lastLoadedAt = null,
                fileName = File(copy.path).name,
                visionValidated = false,
                physicalCopies = emptyList(),
                aliases = emptyList()
            ) }
        }
        // Audit each physical path independently. A retained copy may have changed
        // since the last reconciliation and must not inherit its parent's id or
        // enter a complete component group before its own byte hash is checked.
        val checked = (catalog.map { it.copy(physicalCopies = emptyList()) } + retainedCopies)
            .map(::inspectModelIdentity)
        val result = reconcileStableModelIdentities(checked)
        if (result.models != persisted) {
            val pending = pendingIdentityReferenceMigration()
            val mergedMapping = pending.mapValues { (_, priorTarget) ->
                result.idMapping[priorTarget] ?: priorTarget
            } + result.idMapping
            if (mergedMapping.any { (old, new) -> old != new }) writeModelIdentityJournal(mergedMapping)
            save(result.models)
        }
        result
    }

    fun pendingIdentityReferenceMigration(): Map<String, String> = synchronized(MODEL_IMPORT_LOCK) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        val source = journal.takeIf(File::isFile) ?: backup.takeIf(File::isFile) ?: return@synchronized emptyMap()
        runCatching {
            val mapping = org.json.JSONObject(source.readText(Charsets.UTF_8)).getJSONObject("idMapping")
            mapping.keys().asSequence().associateWith(mapping::getString)
        }.getOrDefault(emptyMap())
    }

    fun acknowledgeIdentityReferenceMigration() = synchronized(MODEL_IMPORT_LOCK) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        if (journal.exists() && !journal.delete()) error("无法清理已完成的模型引用迁移日志。")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        if (backup.exists() && !backup.delete()) error("无法清理已完成的模型引用迁移日志备份。")
    }

    private fun writeModelIdentityJournal(idMapping: Map<String, String>) {
        val journal = File(appContext.filesDir, "mca-model-identity-migration.json")
        val mapping = org.json.JSONObject().apply {
            idMapping.forEach { (old, stable) -> put(old, stable) }
        }
        val payload = org.json.JSONObject().put("schema", 1).put("idMapping", mapping)
        val staging = File(journal.parentFile, ".${journal.name}.writing")
        val backup = File(journal.parentFile, ".${journal.name}.backup")
        staging.parentFile?.mkdirs()
        FileOutputStream(staging).use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (journal.isFile) {
            if (backup.exists() && !backup.delete()) error("无法替换模型引用迁移日志备份。")
            if (!journal.renameTo(backup)) error("无法备份模型引用迁移日志。")
        }
        if (!staging.renameTo(journal)) {
            if (!journal.exists()) backup.renameTo(journal)
            error("无法提交模型引用迁移日志。")
        }
        backup.delete()
    }

    private fun inspectModelIdentity(model: ModelManifest): ModelManifest {
        if (model.integrityState == ModelIntegrityState.DAMAGED &&
            model.diagnostic?.startsWith("模型清单") == true) return model
        val main = File(model.path)
        if (!main.exists() || !main.canRead()) return model.copy(
            integrityState = ModelIntegrityState.DAMAGED,
            diagnostic = "模型文件不存在或不可读：${model.path}"
        )
        val digest = runCatching {
            when (model.runtime) {
                ChatModelRuntime.MNN -> {
                    val readiness = MnnBundleReadinessAnalyzer.analyze(main)
                    require(readiness.canLoad) { readiness.diagnosticSummary() }
                    sha256MnnBundle(main, readiness.requiredComponentPaths)
                }
                ChatModelRuntime.GENIEX_QAIRT -> sha256Directory(main)
                else -> {
                    require(main.isFile) { "模型路径不是文件" }
                    sha256(main)
                }
            }
        }.getOrElse { error ->
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "无法计算模型完整摘要：${error.message.orEmpty()}")
        }
        val legacyMnnDirectoryHash = if (model.runtime == ChatModelRuntime.MNN &&
            !model.sha256.equals(digest, ignoreCase = true) && main.isDirectory
        ) runCatching { sha256Directory(main) }.getOrNull() else null
        if (model.sha256.isCanonicalSha256() && !isFastRecoveryFingerprint(model.sha256) &&
            !model.sha256.equals(digest, ignoreCase = true) &&
            !model.sha256.equals(legacyMnnDirectoryHash, ignoreCase = true)) {
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "模型完整 SHA-256 与清单不一致")
        }
        val projector = model.visionProjectorPath?.let(::File)
        val projectorDigest = projector?.let { file ->
            if (!file.isFile || !file.canRead()) return model.copy(
                integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "视觉投影器不存在或不可读：${model.visionProjectorPath}"
            )
            runCatching { sha256(file) }.getOrElse { error ->
                return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                    diagnostic = "无法计算视觉投影器摘要：${error.message.orEmpty()}")
            }
        }
        if (projectorDigest != null && model.visionProjectorSha256?.isCanonicalSha256() == true &&
            !model.visionProjectorSha256.equals(projectorDigest, ignoreCase = true)) {
            return model.copy(integrityState = ModelIntegrityState.DAMAGED,
                diagnostic = "视觉投影器完整 SHA-256 与清单不一致")
        }
        return model.copy(
            sha256 = digest,
            sizeBytes = when {
                main.isFile -> main.length()
                model.runtime == ChatModelRuntime.MNN ->
                    mnnBundleSize(main, MnnBundleReadinessAnalyzer.analyze(main).requiredComponentPaths)
                else -> directorySize(main)
            },
            visionProjectorSha256 = projectorDigest,
            visionProjectorSizeBytes = projector?.length() ?: model.visionProjectorSizeBytes,
            integrityState = ModelIntegrityState.COMPLETE,
            visionValidated = model.visionValidated && legacyMnnDirectoryHash == null,
            diagnostic = null
        )
    }

    fun importFromUris(
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
    fun importFromTreeUri(
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

    private fun isReadableGgufFile(file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return false
        if (!file.name.endsWith(".gguf", ignoreCase = true)) return false
        return runCatching { GgufMetadataReader.read(file).isGguf }.getOrDefault(false)
    }

    private fun modelImportSource(uri: Uri, name: String): ModelImportSource =
        ModelImportSource(uri.toString(), name, querySize(uri), runCatching {
            appContext.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
            }
        }.getOrNull()) { requireNotNull(appContext.contentResolver.openInputStream(uri)) { "无法读取组件：$name" } }

    private fun importPreparedSources(
        sources: List<ModelImportSource>, identity: String, kind: ModelImportKind,
        displayNameOverride: String?, options: ModelImportOptions,
        onProgress: (ModelImportProgress) -> Unit, checkCancelled: () -> Unit
    ): ModelManifest {
        val name = displayNameOverride?.takeIf(String::isNotBlank) ?: sources.first().path.substringAfterLast('/')
        val transaction = ResumableModelImport(managedModelDir, "$identity\n${options.mnnMode}", options.resumeKey,
            newDestination = {
                when (kind) {
                    ModelImportKind.GGUF -> uniqueTarget(normalizedGgufImportName(name))
                    ModelImportKind.LITERT_LM -> uniqueTarget(normalizedLiteRtLmImportName(name))
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
            upsert(completed)
            return transaction.complete(completed)
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
                    }, quant = metadata?.quant ?: "LiteRT-LM", architecture = metadata?.architecture).also {
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

    fun importFromUri(uri: Uri, displayNameOverride: String? = null): ModelManifest =
        importFromUris(listOf(uri), displayNameOverride)

    private fun importSingleUriLocked(
        uri: Uri,
        displayNameOverride: String?,
        allowMnnZip: Boolean
    ): ModelManifest {
        val providedName = displayNameOverride?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri)
        val providerReportedSize = querySize(uri)
        return appContext.contentResolver.openInputStream(uri).use { rawInput ->
            requireNotNull(rawInput) { "无法读取所选文件。" }
            val source = PushbackInputStream(rawInput.buffered(), IMPORT_MAGIC_BYTES)
            val header = source.readPrefix(IMPORT_MAGIC_BYTES)
            if (header.isNotEmpty()) source.unread(header)
            when (classifyModelImport(providedName, header)) {
                ModelImportKind.GGUF -> importGgufFromStreamLocked(source, providedName, providerReportedSize)
                ModelImportKind.LITERT_LM ->
                    importLiteRtLmFromStreamLocked(source, providedName, providerReportedSize)
                ModelImportKind.MNN_ZIP -> {
                    require(allowMnnZip) { "请选择有效的 GGUF 模型文件。" }
                    importMnnBundleFromZipStream(
                        source = source,
                        fileName = providedName?.trim().orEmpty().ifBlank { "mnn-bundle.zip" }
                    )
                }
                ModelImportKind.MNN_COMPONENT ->
                    error("MNN 高速引擎需要同时选择 config.json、llm_config.json、llm.mnn、llm.mnn.weight、tokenizer，以及 embeddings_bf16.bin / llm.mnn.json；或导入完整 zip 包。")
                ModelImportKind.UNKNOWN -> error("无法识别所选文件。请选择 GGUF / LiteRT-LM 模型，或选择完整 MNN 组件 / zip 包。")
            }
        }
    }

    private fun importLiteRtLmFromStreamLocked(
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
            manifest
        } catch (error: Throwable) {
            if (ownsTarget) target.delete()
            throw error
        }
    }

    private fun importGgufFromStreamLocked(
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
                verifyProviderCopyAgainstAdvisorySize(querySize(uri), target.length())
                require(sha256(target).equals(sourceDigest, ignoreCase = true)) { "MNN 组件复制校验失败：$name" }
                val importedKind = target.inputStream().use { input ->
                    classifyModelImport(name, input.readPrefix(IMPORT_MAGIC_BYTES))
                }
                require(
                    importedKind != ModelImportKind.GGUF &&
                        importedKind != ModelImportKind.LITERT_LM &&
                        importedKind != ModelImportKind.MNN_ZIP
                ) {
                    "一次只能导入一个 GGUF / LiteRT-LM 主模型或一个 MNN ZIP；MNN 多选仅用于完整组件文件。"
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
        upsert(manifest)
        return manifest
    }

    /** Registers an already-downloaded official LiteRT-LM container. */
    fun registerDownloadedLiteRtLmModel(
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
        upsert(manifest)
        return manifest
    }

    /**
     * Resolves the only safe packaging wrapper we support: an otherwise empty directory
     * containing one complete QAIRT bundle directory. Ambiguous archives remain invalid.
     */
    fun resolveQairtBundleRoot(bundleDir: File): File = findQairtBundleRoot(bundleDir)
        ?: error("QAIRT 模型包根目录不明确或不完整：${bundleDir.absolutePath}")

    fun deleteModel(id: String): Boolean = synchronized(MODEL_IMPORT_LOCK) {
        val models = listModels()
        val target = models.firstOrNull { it.id == id } ?: return@synchronized false
        val modelPath = File(target.path)
        val deletionTarget = modelPath.takeIf { it.isDirectory }
            ?: modelPath.parentFile?.takeIf { it.isMcaManagedBundleDir() }
            ?: modelPath
        val ownedProjector = target.visionProjectorPath
            ?.let(::File)
            ?.takeIf { projector ->
                projector.parentFile?.absolutePath == managedModelDir.absolutePath
            }

        deleteModelArtifactsBeforeManifestUpdate(
            modelPath = modelPath,
            mainDeletionTarget = deletionTarget,
            ownedProjectorPath = ownedProjector
        ) {
            save(models.filterNot { it.id == id })
        }
        true
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

    fun validateForLoad(id: String): ModelCompatibilityResult {
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

    fun managedFileFor(fileName: String): File {
        managedModelDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun managedBundleDirFor(bundleId: String): File {
        managedModelDir.mkdirs()
        // Bundle installers stage components beside this public path and only
        // atomically publish the directory after validation. Creating the
        // destination here made interrupted downloads look like installed
        // models with an empty folder. Direct-file flows still create it via
        // managedBundleFileFor when they are ready to write a file.
        return File(managedModelDir, safeFileName(bundleId))
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        bundleDir.mkdirs()
        return File(bundleDir, safeFileName(fileName.substringAfterLast('/')))
    }

    /** Every imported/downloaded physical copy remains addressable until explicit reconciliation. */
    private fun upsert(model: ModelManifest) = synchronized(MODEL_IMPORT_LOCK) {
        val existingModels = readPersistedModels()
        val old = existingModels.firstOrNull { it.id == model.id } ?: existingModels.firstOrNull {
            canonicalModelPath(it.path) == canonicalModelPath(model.path) && it.componentIdentity == model.componentIdentity &&
                it.componentIdentity != null
        }
        val stable = if (old == null) model else model.copy(
            id = old.id,
            aliases = (old.aliases + model.aliases + listOfNotNull(model.id.takeIf { it != old.id })).distinct(),
            physicalCopies = (old.physicalCopies + model.physicalCopies +
                ModelPhysicalCopy(old.path, old.sizeBytes, old.sha256, old.visionProjectorPath,
                    old.visionProjectorSizeBytes, old.visionProjectorSha256,
                    old.source, old.repoId, old.revision)).distinctBy { canonicalModelPath(it.path) },
            createdAt = old.createdAt,
            lastLoadedAt = old.lastLoadedAt ?: model.lastLoadedAt,
            visionValidated = if (old.componentIdentity != null && old.componentIdentity == model.componentIdentity) {
                old.visionValidated || model.visionValidated
            } else model.visionValidated
        )
        save(existingModels.filterNot { it.id == old?.id } + stable)
    }

    private fun save(models: List<ModelManifest>) = synchronized(MODEL_IMPORT_LOCK) {
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

    private fun recoverManagedGgufModels(existing: List<ModelManifest>): List<ModelManifest> =
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

    private fun recoverManagedLiteRtLmModels(existing: List<ModelManifest>): List<ModelManifest> =
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
        ChatModelRuntime.LLAMA_CPP -> normalizeLlamaManifest(model)
        ChatModelRuntime.LITERT_LM -> normalizeLiteRtLmManifest(model)
    }

    private fun normalizeLiteRtLmManifest(model: ModelManifest): ModelManifest {
        val file = File(model.path)
        if (file.name.isBlank()) return model
        val inferredName = stripKnownExtension(file.name, ".litertlm")
        val inferredArchitecture = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: inferArchitectureLabel(file.name, ChatModelRuntime.LITERT_LM)
        val displayName = model.displayName.trim()
        val normalizedDisplayName = if (displayName.isBlank()) inferredName else displayName
        val normalizedFileName = if (model.fileName.isBlank()) file.name else model.fileName
        return if (normalizedDisplayName != model.displayName ||
            normalizedFileName != model.fileName ||
            inferredArchitecture != model.architecture) {
            model.copy(
                displayName = normalizedDisplayName,
                fileName = normalizedFileName,
                architecture = inferredArchitecture
            )
        } else {
            model
        }
    }

    private fun normalizeLlamaManifest(model: ModelManifest): ModelManifest {
        val file = File(model.path)
        if (!file.isFile) return model
        val normalizedName = file.nameWithoutExtension
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
        val inferred = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: when {
                "gemma" in normalizedName -> "gemma"
                "qwen" in normalizedName -> "qwen"
                "llama" in normalizedName -> "llama"
                "mistral" in normalizedName -> "mistral"
                "phi" in normalizedName -> "phi"
                else -> "gguf"
            }
        return if (inferred != model.architecture) model.copy(architecture = inferred) else model
    }

    private fun normalizeMnnManifest(model: ModelManifest): ModelManifest {
        val bundleDir = File(model.path)
        val inferredName = inferMnnDisplayName(bundleDir.name)
        val inferredArchitecture = model.architecture
            ?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: inferArchitectureLabel(bundleDir.name, ChatModelRuntime.MNN)
        if (inferredName.isBlank() && inferredArchitecture == model.architecture) return model
        val current = model.displayName.trim()
        val compactCurrent = current.lowercase().replace(Regex("[^a-z0-9]"), "")
        val compactInferred = inferredName.lowercase().replace(Regex("[^a-z0-9]"), "")
        val looksTruncated = current.isBlank() ||
            compactCurrent.length < compactInferred.length &&
            compactInferred.startsWith(compactCurrent)
        return if (looksTruncated || inferredArchitecture != model.architecture) {
            model.copy(
                displayName = if (looksTruncated) inferredName else model.displayName,
                fileName = if (looksTruncated) bundleDir.name.ifBlank { model.fileName } else model.fileName,
                architecture = inferredArchitecture
            )
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
        deduplicateModelRecordsByCanonicalPath(this)

    private fun stripKnownExtension(value: String, extension: String): String =
        if (value.endsWith(extension, ignoreCase = true)) value.dropLast(extension.length) else value

    /**
     * Best-effort presentation metadata only.  This never participates in
     * compatibility, admission, or runtime selection; native load remains the
     * authority for whether a package can execute.
     */
    private fun inferArchitectureLabel(value: String, runtime: ChatModelRuntime): String? {
        val name = value.lowercase().replace('-', '_').replace(' ', '_')
        return when (runtime) {
            ChatModelRuntime.MNN -> when {
                name.contains("sdxl") -> "stable_diffusion_xl"
                name.contains("sd15") || name.contains("sd_1_5") || name.contains("stable_diffusion") ->
                    "stable_diffusion_1_5"
                name.contains("sana") -> "sana_diffusion"
                name.contains("qwen") -> "qwen"
                name.contains("gemma") -> "gemma"
                name.contains("llama") -> "llama"
                else -> "mnn"
            }
            ChatModelRuntime.LITERT_LM -> when {
                name.contains("gemma") -> "gemma"
                name.contains("qwen") -> "qwen"
                name.contains("llama") -> "llama"
                else -> "litert_lm_text_decoder"
            }
            else -> null
        }
    }

    private fun validateMnnBundle(
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

    private fun validateQairtBundle(model: ModelManifest, bundleDir: File): ModelCompatibilityResult {
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

    private fun migrateMnnFingerprint(model: ModelManifest, coreSize: Long, coreHash: String) {
        if (coreHash.isBlank()) return
        if (model.sizeBytes == coreSize && model.sha256.equals(coreHash, ignoreCase = true)) return
        runCatching {
            updateModel(model.withMigratedMnnFingerprint(coreSize, coreHash))
        }
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

    private fun sha256MnnBundle(
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

    private fun mnnBundleSize(directory: File, requiredFiles: List<String>): Long =
        requiredFiles
            .map(::normalizeMnnComponentPath)
            .distinct()
            .sumOf { path -> File(directory, path).length() }

    private fun normalizeMnnComponentPath(rawPath: String): String = normalizeMnnImportPath(rawPath)

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
        private val MODEL_IMPORT_TASK_LOCK = Any()
        private const val IMPORT_MAGIC_BYTES = LITERT_LM_MAGIC_SIZE
        private const val MB = 1024L * 1024L
        private const val GB = 1024L * MB
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
    }

}

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

/**
 * A damaged legacy entry must not hide every otherwise valid model. A malformed
 * top-level document still fails so repository recovery can rebuild it from the
 * managed model directory.
 */
internal fun parsePersistedModelManifest(contents: String): List<ModelManifest> {
    val array = JSONArray(contents)
    return buildList {
        for (index in 0 until array.length()) {
            val raw = array.opt(index)
            runCatching {
                when (raw) {
                    is org.json.JSONObject -> ModelManifest.fromJson(raw)
                    else -> ModelManifest.fromJson(org.json.JSONObject()
                        .put("id", "damaged-row-$index")
                        .put("diagnostic", "模型清单记录不是对象：index=$index"))
                }
            }
                .getOrNull()
                ?.let(::add)
        }
    }
}

/**
 * Preserve distinct ids for conflicting components on the same main path.
 * Only exact matching component identity can be reconciled into aliases.
 */
internal fun deduplicateModelRecordsByCanonicalPath(models: List<ModelManifest>): List<ModelManifest> {
    return models.sortedByDescending { it.createdAt }.distinctBy { model ->
        Triple(model.id, canonicalModelPath(model.path), model.stableIdentity)
    }
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

private fun canonicalModelPath(path: String): String = runCatching { File(path).canonicalPath }
    .getOrElse { File(path).absolutePath }

data class ModelIdentityReconciliation(
    val models: List<ModelManifest>,
    val idMapping: Map<String, String>,
    val diagnostics: List<String>
)

internal fun reconcileStableModelIdentities(models: List<ModelManifest>): ModelIdentityReconciliation {
    val result = mutableListOf<ModelManifest>()
    val mapping = linkedMapOf<String, String>()
    val diagnostics = mutableListOf<String>()
    models.withIndex().groupBy { (index, model) ->
        if (model.integrityState == ModelIntegrityState.COMPLETE) model.componentIdentity
            ?: "unresolved:$index"
        else "unresolved:$index"
    }.values.forEach { indexedGroup ->
        val group = indexedGroup.map { it.value }
        val preferred = group.maxWithOrNull(compareBy<ModelManifest> { it.physicalCopies.isNotEmpty() }
            .thenBy { it.lastLoadedAt ?: Long.MIN_VALUE }.thenBy { it.createdAt }) ?: return@forEach
        val allIds = group.flatMap { listOf(it.id) + it.aliases }.distinct()
        val copies = group.flatMap { model ->
            model.physicalCopies + ModelPhysicalCopy(
                model.path, model.sizeBytes, model.sha256, model.visionProjectorPath,
                model.visionProjectorSizeBytes, model.visionProjectorSha256,
                model.source, model.repoId, model.revision
            )
        }.distinctBy { copy ->
            listOf(canonicalModelPath(copy.path), copy.sha256,
                copy.visionProjectorPath.orEmpty(), copy.visionProjectorSha256.orEmpty()).joinToString("|")
        }
        val merged = preferred.copy(
            aliases = allIds.filter { it != preferred.id },
            physicalCopies = copies,
            lastLoadedAt = group.mapNotNull(ModelManifest::lastLoadedAt).maxOrNull(),
            visionValidated = preferred.visionValidated
        )
        result += merged
        allIds.forEach { mapping[it] = merged.id }
        if (group.size > 1) diagnostics += "合并 ${group.size} 条完全相同的组件记录；保留 ${copies.size} 份实体文件"
    }
    return ModelIdentityReconciliation(result.sortedByDescending { it.createdAt }, mapping, diagnostics)
}

/** Compatibility helper for older callers; reconciliation uses full component identity. */
internal fun deduplicateEquivalentModelRecordsForCatalog(models: List<ModelManifest>): List<ModelManifest> {
    return reconcileStableModelIdentities(models).models
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

private fun File.isManagedImportStagingDirectory(): Boolean =
    CURRENT_IMPORT_DIRECTORY.matches(name) || LEGACY_NAMED_IMPORT_ARTIFACT.matches(name) ||
        name.startsWith(".mca-import-resume-")

private fun File.isPrimaryGgufShard(): Boolean {
    val match = GGUF_SHARD_FILE.matchEntire(name) ?: return true
    return match.groupValues[1].toIntOrNull() == 1
}

/**
 * Deletes repository-owned artifacts before committing the manifest update.
 *
 * The standalone projector is removed first so a projector failure leaves the
 * primary text model intact. The main model/bundle is removed next, and every
 * owned path is checked again before [updateManifest] is allowed to run.
 */
internal fun deleteModelArtifactsBeforeManifestUpdate(
    modelPath: File,
    mainDeletionTarget: File,
    ownedProjectorPath: File?,
    deleteFile: (File) -> Boolean = { path -> path.delete() },
    deleteRecursively: (File) -> Boolean = { path -> path.deleteRecursively() },
    updateManifest: () -> Unit
) {
    val projectorCoveredByMainDeletion = ownedProjectorPath?.let { projector ->
        projector.isSameAsOrUnder(mainDeletionTarget)
    } == true

    if (ownedProjectorPath != null && !projectorCoveredByMainDeletion) {
        deleteRepositoryPathOrThrow(
            path = ownedProjectorPath,
            recursive = false,
            description = "视觉投影器",
            deleteFile = deleteFile,
            deleteRecursively = deleteRecursively
        )
    }
    deleteRepositoryPathOrThrow(
        path = mainDeletionTarget,
        recursive = mainDeletionTarget.isDirectory,
        description = if (mainDeletionTarget.isDirectory) "模型目录" else "模型文件",
        deleteFile = deleteFile,
        deleteRecursively = deleteRecursively
    )

    requireRepositoryPathAbsent(modelPath, "模型路径")
    ownedProjectorPath?.let { requireRepositoryPathAbsent(it, "视觉投影器路径") }
    updateManifest()
}

private fun deleteRepositoryPathOrThrow(
    path: File,
    recursive: Boolean,
    description: String,
    deleteFile: (File) -> Boolean,
    deleteRecursively: (File) -> Boolean
) {
    if (!path.exists()) return
    val operation = if (recursive) "deleteRecursively()" else "delete()"
    val deleted = try {
        if (recursive) deleteRecursively(path) else deleteFile(path)
    } catch (error: Exception) {
        throw IllegalStateException(
            "删除${description}失败：$operation 抛出 ${error.javaClass.simpleName}" +
                error.message?.let { "：$it" }.orEmpty() +
                "；路径=${path.absolutePath}",
            error
        )
    }
    if (!deleted) {
        val state = if (path.exists()) "路径仍存在" else "路径随后不存在"
        throw IllegalStateException(
            "删除${description}失败：$operation 返回 false，$state；路径=${path.absolutePath}"
        )
    }
    requireRepositoryPathAbsent(path, description)
}

private fun requireRepositoryPathAbsent(path: File, description: String) {
    check(!path.exists()) {
        "删除${description}失败：删除操作返回成功，但路径仍存在；路径=${path.absolutePath}"
    }
}

private fun File.isSameAsOrUnder(root: File): Boolean = runCatching {
    val candidatePath = absoluteFile.toPath().normalize()
    val rootPath = root.absoluteFile.toPath().normalize()
    candidatePath == rootPath || candidatePath.startsWith(rootPath)
}.getOrDefault(false)

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

/**
 * `OpenableColumns.SIZE` is advisory SAF metadata. Cloud drives and OEM file
 * providers may return an estimate, a stale value, or the encoded object size;
 * none proves whether the stream reached EOF. Import completeness is therefore
 * established by reading to EOF, hashing the copied bytes, and validating the
 * resulting GGUF/MNN payload. A provider-size mismatch must never reject an
 * otherwise valid import.
 */
internal fun verifyProviderCopyAgainstAdvisorySize(
    providerReportedSize: Long?,
    copiedSize: Long
) {
    require(copiedSize >= 0L) { "Copied byte count cannot be negative." }
    @Suppress("UNUSED_VARIABLE")
    val diagnosticOnly = providerReportedSize?.takeIf { it >= 0L }
}

private val SAFE_IMPORT_TRANSACTION_ID = Regex("[A-Za-z0-9._-]{1,128}")
private const val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val CURRENT_IMPORT_DIRECTORY = Regex("^\\.importing-$UUID_PATTERN$")
private val LEGACY_NAMED_IMPORT_ARTIFACT = Regex("^\\..+\\.importing-$UUID_PATTERN$")
private val GGUF_SHARD_FILE = Regex("^.+-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
private const val MAX_GGUF_LOAD_PREFLIGHT_BYTES = 4L * 1024L * 1024L
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

