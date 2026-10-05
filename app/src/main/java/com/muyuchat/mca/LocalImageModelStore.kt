package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.muyuchat.core.download.RemoteModelFile
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The local-image model catalog store and readiness diagnostics (P5/L4). Moved verbatim out of
 * LocalImageProvider.kt: the persisted model catalog (LocalImageModelStore + LocalImageCatalogSnapshot),
 * discovery/import/delete/selection, readiness and verification diagnostics, runtime inference for
 * bundles and the SDXL conditioning-asset helpers.
 *
 * Three members are internal instead of private because both halves of the split use them:
 * [QWEN_IMAGE_21_RECOMMENDATION_ID] and [LocalImageModelRecord.isQwenImage21MnnModel] (the provider's
 * generate() dispatch and nativeConfig still call them), and [SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE]
 * (nativeConfig SDXL parameter assembly). Everything else keeps its original visibility.
 */

private val imageModelCatalogLock = Any()

private fun canonicalImageModelPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)

private fun remoteImageModelSource(remote: RemoteModelFile): String =
    "${remote.provider.name.lowercase()}:${remote.repoId}@${remote.revision}/${remote.path}"

internal data class LocalImageCatalogSnapshot(
    val models: String?, val selectedModelId: String?, val selectedBackend: String?, val suppressedPaths: Set<String>
)

class LocalImageModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    private val managedDir: File by lazy {
        (appContext.getExternalFilesDir("image_models") ?: File(appContext.filesDir, "image_models")).also { it.mkdirs() }
    }

    /** Serialize promotion/registration with scans; restore only this catalog's keys on failure. */
    internal fun <T> withCatalogTransaction(block: () -> T): T = synchronized(imageModelCatalogLock) {
        val snapshot = captureCatalogSnapshot()
        try { block() } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(snapshot) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    internal fun captureCatalogSnapshot(): LocalImageCatalogSnapshot = synchronized(imageModelCatalogLock) {
        LocalImageCatalogSnapshot(prefs.getString(KEY_MODELS, null), prefs.getString(KEY_SELECTED_MODEL_ID, null),
            prefs.getString(KEY_SELECTED_BACKEND, null), suppressedImageModelPaths().toSet())
    }

    internal fun restoreCatalogSnapshot(snapshot: LocalImageCatalogSnapshot) = synchronized(imageModelCatalogLock) {
        check(prefs.edit().putString(KEY_MODELS, snapshot.models).putString(KEY_SELECTED_MODEL_ID, snapshot.selectedModelId)
            .putString(KEY_SELECTED_BACKEND, snapshot.selectedBackend).putStringSet(KEY_SUPPRESSED_PATHS, snapshot.suppressedPaths)
            .commit()) { "无法恢复模型目录登记状态，请释放存储空间后刷新。" }
    }

    fun loadModels(discover: Boolean = true): List<LocalImageModelRecord> = synchronized(imageModelCatalogLock) {
        val raw = prefs.getString(KEY_MODELS, null).orEmpty()
        var persisted = runCatching { parseImageCatalogRecords(raw) }.getOrElse {
            // Preserve the original catalog for diagnosis/recovery before writing discoveries.
            if (!prefs.contains("catalog_recovery_raw")) prefs.edit().putString("catalog_recovery_raw", raw).apply()
            emptyList()
        }
        if (raw.isNotBlank() && runCatching { JSONArray(raw).length() }.getOrDefault(0) > persisted.size &&
            !prefs.contains("catalog_recovery_raw")) {
            prefs.edit().putString("catalog_recovery_raw", raw).apply()
        }
        if (!discover) {
            val normalized = normalizeLocalImageModelRecords(persisted, loadSelectedModelId())
            if (normalized != persisted) persistModels(normalized)
            return@synchronized normalized
        }
        val storedRecords = persisted
        persisted = persisted.flatMap { it.expandPhysicalRecords() }
        // Re-observe every companion before retaining execution proof. The digest cache
        // is process-local and stat/ctime based; persisted metadata is never a hash source.
        persisted = persisted.filterNot { model ->
            model.source == "local:discovered" && imageDirectoryIsOwnedComponent(File(model.bundleRoot ?: model.path).let {
                if (it.isDirectory) it else it.parentFile
            }, File(model.path))
        }.map { model ->
            runCatching { model.withObservedContent() }.getOrElse {
                model.copy(verificationStatus = LocalImageVerificationStatus.UNKNOWN,
                    verificationMessage = "", verifiedAt = 0L, qnnVerificationStamp = "", verifiedContentFingerprint = "")
            }
        }
        if (!ManagedModelDownloadWorker.isInstalling) recoverInterruptedImageBundlePromotions(managedDir) { candidate ->
            when (val inspection = inspectLocalImageBundleManifestFromRoot(candidate)) {
                is LocalImageBundleManifestInspection.Ready -> {
                    val manifest = inspection.manifest
                    val primaryReady = manifest.primaryFile?.let { it.isFile && it.length() > 0L } == true
                    val requiredPaths = manifest.components.filter { it.required }.map { it.relativePath } +
                        manifest.executionProfileRequiredPaths
                    primaryReady && requiredPaths.all { relative -> candidate.safeDescendantOrNull(relative)
                        ?.let { it.isFile && it.canRead() && it.length() > 0L } == true }
                }
                else -> false
            }
        }
        // Models downloaded by the runtime/model smoke tools live below the
        // application-owned `files/models` directory, while the UI's import
        // flow historically persisted records under `image_models`.  Discover
        // complete, manifest-declared image bundles from both locations so a
        // valid QNN bundle is visible without requiring a second manual import.
        // Discovery is structural only: it never filters on chipset/profile;
        // the native load/graph path remains the execution authority.
        val suppressed = suppressedImageModelPaths()
        val discovered = discoverInstalledImageBundles(persisted)
            .filterNot { candidate ->
                localImagePhysicalIdentity(candidate) in suppressed ||
                    canonicalImageModelPath(candidate.path) in suppressed // Legacy path-only tombstones.
            }
        if (discovered.isEmpty()) {
            val normalized = normalizeLocalImageModelRecords(persisted, loadSelectedModelId())
            if (normalized != storedRecords) {
                runCatching { saveModels(normalized) }
            }
            return@synchronized normalized
        }
        val merged = normalizeLocalImageModelRecords(persisted + discovered, loadSelectedModelId())
            .sortedByDescending { it.updatedAt }
        if (merged != storedRecords) {
            // Best-effort persistence makes the next refresh stable, but a
            // read-only discovery failure must never hide the in-memory result.
            runCatching { saveModels(merged) }
        }
        return@synchronized merged
    }

    private fun discoverInstalledImageBundles(persisted: List<LocalImageModelRecord>): List<LocalImageModelRecord> {
        val roots = listOfNotNull(
            managedDir,
            appContext.getExternalFilesDir("models"),
            File(appContext.filesDir, "models")
        ).distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        // Materialize every candidate directory first. A container such as files/models is
        // itself walked at depth 0; without the descendant-ownership check below it would
        // recursively adopt a grandchild bundle's manifest/primary and be persisted as a
        // bogus "parent directory" record beside the real extracted bundle (the source of
        // the duplicate cyberrealistic entry / ":2" alias).
        val candidateDirs = roots
            .filter { it.isDirectory }
            .flatMap { root -> imageDiscoveryWalk(root).filter(File::isDirectory).toList() }
            .map { directory -> runCatching { directory.canonicalFile }.getOrDefault(directory) }
            .distinctBy { it.path }
        val candidatePaths = candidateDirs.map { it.path }.toSet()
        return candidateDirs.mapNotNull { bundleRoot ->
            runCatching {
                // Discovery must not require a manifest as an admission gate. Older
                // QNN/MNN archives shipped only graph files; infer the runtime and
                // primary artifact from the concrete directory, then let readiness and
                // native load decide whether the bundle is executable.
                // A stale/malformed optional manifest must not make an
                // otherwise discoverable downloaded bundle disappear
                // from the model list.  Treat it as advisory metadata;
                // structural files remain discoverable and native load
                // will report a concrete format error when selected.
                // Only a manifest that is a DIRECT child may authorize this directory's
                // identity; a descendant bundle's manifest belongs to that descendant.
                val hasDirectManifest = bundleRoot.listFiles()
                    ?.any { it.isFile && it.name.equals("manifest.json", ignoreCase = true) } == true
                val manifest = if (hasDirectManifest) {
                    runCatching {
                        when (val inspection = inspectLocalImageBundleManifestFromRoot(bundleRoot)) {
                            is LocalImageBundleManifestInspection.Ready -> inspection.manifest
                            else -> null
                        }
                    }.getOrNull()
                } else {
                    null
                }
                // A declared diffusion primary remains authoritative when missing. Falling
                // back to the VAE/encoder in that directory creates a second broken model.
                // Persisted rows keep the missing-file diagnosis; a complete retained copy
                // can take over during normalization without inheriting the old native proof.
                val declaredPrimary = manifest?.declaredPrimaryPath
                    ?.let(bundleRoot::safeDescendantOrNull) ?: manifest?.primaryFile
                if (declaredPrimary != null && (!declaredPrimary.isFile || declaredPrimary.length() <= 0L)) {
                    return@runCatching null
                }
                val primary = declaredPrimary
                    ?: imageDiscoveryWalk(bundleRoot).filter { it.isFile && it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
                        .maxWithOrNull(compareBy<File> { it.name.isPrimaryImageModelName() }.thenBy { it.length() })
                    ?: imageDiscoveryWalk(bundleRoot).firstOrNull {
                        it.isFile && it.length() > 0L &&
                            (it.name.endsWith(".bin", true) ||
                                it.name.endsWith(".mnn", true) ||
                                it.name.endsWith(".safetensors", true) ||
                                it.name.endsWith(".gguf", true))
                    }
                    ?: return@runCatching null
                // A container directory without its own manifest must not adopt a primary
                // that lives inside a different candidate bundle directory; the deeper
                // directory owns that artifact and emits the record instead.
                val primaryCanonical = runCatching { primary.canonicalFile }.getOrDefault(primary)
                if (!hasDirectManifest && imageDirectoryIsOwnedComponent(bundleRoot, primaryCanonical)) {
                    return@runCatching null
                }
                if (!hasDirectManifest && persisted.any { registered -> registered.allPhysicalCopies().any { physical ->
                    runCatching { File(physical.path).canonicalFile == primaryCanonical }.getOrDefault(false) &&
                        (physical.bundleRoot.isNullOrBlank() || !sameCanonicalPath(physical.bundleRoot, bundleRoot.path))
                } }) return@runCatching null
                val ownedByDescendantBundle = candidatePaths.any { otherPath ->
                    otherPath.startsWith(bundleRoot.path + File.separator) &&
                        primaryCanonical.path.startsWith(otherPath + File.separator)
                }
                if (!hasDirectManifest && ownedByDescendantBundle) return@runCatching null
                val runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleRoot, primary, discovery = true)
                if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION &&
                    runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP
                ) return@runCatching null
                LocalImageModelRecord(
                    id = manifest?.id ?: bundleRoot.name,
                    displayName = manifest?.displayName ?: bundleRoot.name,
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = imageDiscoveryWalk(bundleRoot).filter { it.isFile }.sumOf { it.length() },
                    sha256 = observedLocalImageFileSha256(primary),
                    runtime = runtime,
                    family = manifest?.family ?: LocalImageModelFamily.infer(bundleRoot.name),
                    imageSize = manifest?.imageSize ?: defaultImageSizeFor(bundleRoot.name),
                    source = "local:discovered",
                    bundleRoot = bundleRoot.absolutePath,
                    componentCount = imageDiscoveryWalk(bundleRoot).count { it.isFile }.coerceAtLeast(1),
                    updatedAt = bundleRoot.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                ).withObservedContent().let { observed ->
                    val existing = persisted.firstOrNull { localImagePhysicalIdentity(it) == localImagePhysicalIdentity(observed) &&
                        localImageSameCompleteContents(it, observed) }
                    observed.copy(updatedAt = existing?.updatedAt ?: (bundleRoot.lastModified().takeIf { it > 0L } ?: observed.updatedAt))
                }
            }.getOrNull()
        }
            .distinctBy { runCatching { it.bundleRoot?.let(::File)?.canonicalPath ?: it.path }.getOrDefault(it.path) }
    }

    private fun suppressedImageModelPaths(): Set<String> =
        prefs.getStringSet(KEY_SUPPRESSED_PATHS, emptySet()).orEmpty()

    private fun persistModels(
        models: List<LocalImageModelRecord>, suppressedPaths: Set<String>? = null,
        selectedModelId: String? = loadSelectedModelId(), selectedBackend: ImageBackend? = null
    ) {
        val normalized = normalizeLocalImageModelRecords(models, selectedModelId)
        val array = JSONArray()
        normalized.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        val canonicalSelection = normalized.firstOrNull { it.matchesCatalogId(selectedModelId) }?.id ?: selectedModelId
        val editor = prefs.edit().putString(KEY_MODELS, array.toString())
            .putString(KEY_SELECTED_MODEL_ID, canonicalSelection.orEmpty())
        suppressedPaths?.let { editor.putStringSet(KEY_SUPPRESSED_PATHS, it) }
        selectedBackend?.let { editor.putString(KEY_SELECTED_BACKEND, it.name) }
        check(editor.commit()) {
            "无法保存模型列表。请释放存储空间后刷新或重试导入，模型文件已保留。"
        }
    }

    /** The complete record, selection and backend are registered in one preferences commit. */
    private fun registerRecord(
        record: LocalImageModelRecord, selectWhenNew: Boolean = false,
        backendOnSelection: ImageBackend? = null
    ): LocalImageModelRecord {
        val current = loadModels(discover = false)
        val selected = loadSelectedModelId()
        val wasRegistered = current.any { localImagePhysicalIdentity(record) in it.catalogPhysicalKeys() }
        val normalized = normalizeLocalImageModelRecords(current + record, selected)
        val owner = normalized.first { localImagePhysicalIdentity(record) in it.catalogPhysicalKeys() }
        val select = owner.isReadyForLocalImageGeneration() && (selected == null || (selectWhenNew && !wasRegistered))
        val visibleKeys = normalized.flatMapTo(mutableSetOf()) { model ->
            model.allPhysicalCopies().flatMap { copy ->
                listOf(localImagePhysicalIdentity(model.runtime, copy.path), canonicalImageModelPath(copy.path))
            }
        }
        persistModels(normalized, suppressedImageModelPaths() - visibleKeys,
            if (select) owner.id else selected, if (select) backendOnSelection else null)
        return owner
    }

    fun saveModels(models: List<LocalImageModelRecord>) = synchronized(imageModelCatalogLock) {
        // Tombstones now include the runtime. Saving another runtime that shares
        // this primary must not resurrect the removed row on the next scan.
        val visibleKeys = models.flatMapTo(mutableSetOf()) {
            it.allPhysicalCopies().flatMap { copy ->
                listOf(localImagePhysicalIdentity(it.runtime, copy.path), canonicalImageModelPath(copy.path))
            }
        }
        persistModels(models, suppressedImageModelPaths() - visibleKeys)
    }

    fun removeModelRecord(id: String): Boolean = synchronized(imageModelCatalogLock) {
        val models = loadModels()
        val target = models.firstOrNull { it.matchesCatalogId(id) } ?: return@synchronized false
        val remaining = models.filterNot { it.id == target.id }
        val selected = if (target.matchesCatalogId(loadSelectedModelId()))
            remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id else loadSelectedModelId()
        persistModels(remaining, suppressedImageModelPaths() + target.catalogPhysicalKeys(), selected)
        true
    }

    fun updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> = synchronized(imageModelCatalogLock) {
        val now = System.currentTimeMillis()
        val current = loadModels()
        val index = current.indexOfFirst { existing ->
            existing.matchesCatalogId(record.id) &&
                localImagePhysicalIdentity(existing) == localImagePhysicalIdentity(record)
        }
        require(index >= 0) { "Image model catalog record no longer exists at this path." }
        val owner = current[index]
        val models = current.toMutableList().also {
            val observed = record.withObservedContent()
            // updateModel is the native-result persistence boundary. A changed snapshot
            // must not be certified by a result produced for its previous components.
            val sameRequestContent = localImageSameCompleteContents(record, observed)
            it[index] = observed.copy(
                id = owner.id, aliases = (owner.aliases + record.aliases).distinct(), updatedAt = now,
                physicalCopies = owner.physicalCopies,
                verificationStatus = if (sameRequestContent) record.verificationStatus else LocalImageVerificationStatus.UNKNOWN,
                verificationMessage = if (sameRequestContent) record.verificationMessage else "",
                verifiedAt = if (sameRequestContent) record.verifiedAt else 0L,
                qnnVerificationStamp = if (sameRequestContent) record.qnnVerificationStamp else "",
                verifiedContentFingerprint = if (sameRequestContent && record.verificationStatus != LocalImageVerificationStatus.UNKNOWN)
                    observed.contentFingerprint else ""
            )
        }
        saveModels(models)
        return@synchronized models
    }

    fun importFromUri(uri: Uri, checkCancelled: () -> Unit = {}): LocalImageModelRecord =
        importFromSource(queryDisplayName(uri) ?: "image-model.task", { inputStreamFor(uri) }, checkCancelled)

    /** Production import entry also usable by file fixtures, without an Android URI provider. */
    internal fun importFromSource(
        fileName: String, openSource: () -> InputStream, checkCancelled: () -> Unit = {},
        source: String = "local"
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        checkCancelled()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SUPPORTED_EXTENSIONS) {
            "请选择 .gguf、.safetensors、.ckpt、.pth、.pt、.onnx、.mnn，或包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 .zip 图像生成引擎包。"
        }
        if (extension == "zip") return@synchronized importBundleFromSource(fileName, openSource, checkCancelled, source)
        val catalogBefore = captureCatalogSnapshot()
        val workspace = File(managedDir, ".image-import-" + UUID.randomUUID())
        check(workspace.mkdirs()) { "无法创建图像模型导入临时目录。" }
        var promoted: File? = null
        try {
            val staged = File(workspace, "model." + extension)
            openSource().use { input -> staged.outputStream().use { output ->
                copyImageImportInput(input, output, checkCancelled)
            } }
            val draft = LocalImageModelRecord(
                displayName = fileName.substringBeforeLast('.', fileName), path = staged.absolutePath,
                fileName = fileName, sizeBytes = staged.length(), sha256 = "",
                runtime = LocalImageRuntime.infer(fileName), family = LocalImageModelFamily.infer(fileName),
                imageSize = defaultImageSizeFor(fileName), source = source
            ).withObservedContent(checkCancelled)
            checkCancelled()
            val target = uniqueTarget(fileName)
            require(!target.exists() && staged.renameTo(target)) { "图像模型导入提交失败，源文件仍可重新导入。" }
            promoted = target
            checkCancelled()
            return@synchronized registerRecord(draft.copy(path = target.absolutePath, fileName = target.name))
        } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(catalogBefore) }.exceptionOrNull()?.let(failure::addSuppressed)
            promoted?.let { file -> if (!runCatching { imageFileSharesCatalogOwnership(file, loadModels(discover = false)) }.getOrDefault(true))
                runCatching { if (file.exists()) file.delete() } }
            throw failure
        } finally {
            if (workspace.exists()) runCatching { workspace.deleteRecursively() }
        }
    }

    fun registerDownloadedModel(
        file: File,
        remote: RemoteModelFile
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        require(file.exists()) { "下载完成的图像模型文件不存在：${file.absolutePath}" }
        if (file.extension.equals("zip", ignoreCase = true)) {
            return@synchronized importFromSource(file.name, { file.inputStream() }, source = remoteImageModelSource(remote)).also {
                deleteManagedDuplicate(file)
            }
        }
        val digest = sha256(file)
        val runtime = LocalImageRuntime.infer(file.name)
        val family = LocalImageModelFamily.infer("${remote.repoId}/${remote.path}/${file.name}")
        val source = remoteImageModelSource(remote)
        val record = LocalImageModelRecord(
            displayName = file.name.substringBeforeLast('.', file.name),
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = digest,
            runtime = runtime,
            family = family,
            imageSize = defaultImageSizeFor("${remote.repoId}/${file.name}"),
            source = source,
            updatedAt = System.currentTimeMillis()
        )
        return@synchronized registerDownloadedRecord(record)
    }

    fun managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        // The bundle installer writes into a hidden staging directory and atomically
        // promotes it only after verification. Materializing the public destination
        // here leaves a misleading empty folder when Android stops the process during
        // a large download or while post-download validation is still running.
        return File(managedDir, "bundle-$safeName")
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(bundleDir, safeName.ifBlank { "component-${System.currentTimeMillis()}" })
    }

    fun registerDownloadedBundle(
        displayName: String,
        bundleDir: File,
        primaryFile: File,
        primaryRemote: RemoteModelFile,
        componentCount: Int,
        runtimeOverride: LocalImageRuntime? = null,
        imageSizeOverride: String? = null,
        primarySha256: String? = null,
        recordTransform: (LocalImageModelRecord) -> LocalImageModelRecord = { it }
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        require(bundleDir.isDirectory) { "本地生图引擎包目录不存在：${bundleDir.absolutePath}" }
        require(primaryFile.exists()) { "Local image engine bundle is missing a diffusion model: ${primaryFile.name}" }
        if (primaryFile.extension.equals("zip", ignoreCase = true)) {
            extractImageBundleZipIntoDirectory(primaryFile, bundleDir)
            runCatching { primaryFile.delete() }
        }
        prepareMnnDiffusionTokenizerIfPossible(bundleDir)
        val manifest = localImageBundleManifestFromRoot(bundleDir)
        val resolvedPrimary = manifest?.primaryFile
            ?: primaryFile.takeIf { it.isFile && !it.extension.equals("zip", ignoreCase = true) }
            ?: findPrimaryImageModel(bundleDir)
            ?: error("Local image engine bundle is missing a diffusion model.")
        val canonicalRoot = bundleDir.canonicalFile
        val canonicalPrimary = resolvedPrimary.canonicalFile
        require(canonicalPrimary.isFile && canonicalPrimary.length() > 0L &&
            canonicalPrimary.path.startsWith(canonicalRoot.path + File.separator)) {
            "生图引擎包主模型为空、不可读或位于包目录之外：${resolvedPrimary.name}"
        }
        val familyHint = "$displayName/${primaryRemote.repoId}/${primaryRemote.path}/${resolvedPrimary.name}"
        val record = LocalImageModelRecord(
            displayName = displayName,
            path = resolvedPrimary.absolutePath,
            fileName = resolvedPrimary.name,
            sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            sha256 = primarySha256
                ?.trim()
                ?.lowercase()
                ?.also { digest ->
                    require(digest.matches(Regex("^[0-9a-f]{64}$"))) {
                        "Downloaded image model SHA-256 is invalid."
                    }
                }
                ?: sha256(resolvedPrimary),
            runtime = runtimeOverride ?: manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleDir, resolvedPrimary),
            family = manifest?.family ?: LocalImageModelFamily.infer(familyHint),
            imageSize = imageSizeOverride ?: manifest?.imageSize ?: defaultImageSizeFor(familyHint),
            source = remoteImageModelSource(primaryRemote),
            bundleRoot = bundleDir.absolutePath,
            recommendationId = manifest?.recommendationId ?: manifest?.id,
            componentCount = bundleDir.walkTopDown().count { it.isFile }
                .coerceAtLeast(componentCount)
                .coerceAtLeast(manifest?.componentCount ?: 0)
                .coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        val observed = record.withObservedContent()
        observed.localImageStructuralReadinessMessage()?.let { readiness ->
            error("图像生成引擎包不完整：$readiness")
        }
        val transformed = recordTransform(observed)
        val prepared = transformed.withObservedContent()
        require(prepared.id == observed.id && localImagePhysicalIdentity(prepared) == localImagePhysicalIdentity(observed) &&
            sameCanonicalPath(prepared.bundleRoot, observed.bundleRoot) && prepared.contentFingerprint == observed.contentFingerprint &&
            prepared.componentSnapshots == observed.componentSnapshots) {
            "模型登记诊断不允许改变已经校验的文件或运行时身份。"
        }
        return@synchronized registerRecord(prepared, selectWhenNew = true, backendOnSelection = ImageBackend.LOCAL)
    }

    /** Complete equal bundles share a catalog owner while retaining every physical copy. */
    private fun registerDownloadedRecord(record: LocalImageModelRecord): LocalImageModelRecord =
        registerRecord(record.withObservedContent())

    /** Optional archive cleanup must not erase a primary or a shared bundle component. */
    internal fun deleteManagedDuplicate(file: File): Boolean = synchronized(imageModelCatalogLock) {
        val root = runCatching { managedDir.canonicalFile }.getOrNull() ?: return@synchronized false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return@synchronized false
        if (candidate.path == root.path || !candidate.path.startsWith(root.path + File.separator)) {
            return@synchronized false
        }
        if (imageFileSharesCatalogOwnership(candidate, loadModels(discover = false))) return@synchronized false
        runCatching {
            if (!candidate.exists()) true
            else if (candidate.isDirectory) candidate.deleteRecursively() && !candidate.exists()
            else candidate.delete() && !candidate.exists()
        }.getOrDefault(false)
    }

    private fun imageFileSharesCatalogOwnership(candidate: File, records: List<LocalImageModelRecord>): Boolean =
        localImageFileSharesCatalogOwnership(candidate, records)

    private fun sameCanonicalPath(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        return runCatching { File(first).canonicalPath == File(second).canonicalPath }.getOrDefault(false)
    }

    fun managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun deleteModel(id: String): Boolean = synchronized(imageModelCatalogLock) {
        val models = loadModels()
        val target = models.firstOrNull { it.matchesCatalogId(id) } ?: return@synchronized false
        val remaining = models.filterNot { it.id == target.id }
        val files = target.allPhysicalCopies().map { physical ->
            physical.bundleRoot?.takeIf(String::isNotBlank)?.let(::File) ?: File(physical.path)
        }.distinctBy { it.canonicalPath }
        for (file in files) {
            if (imageFileSharesCatalogOwnership(file, remaining)) continue
            val deleted = runCatching {
                if (!file.exists()) true else if (file.isDirectory) file.deleteRecursively() && !file.exists()
                else file.delete() && !file.exists()
            }.getOrDefault(false)
            if (!deleted) return@synchronized false
        }
        val selected = if (target.matchesCatalogId(loadSelectedModelId()))
            remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id else loadSelectedModelId()
        persistModels(remaining, suppressedImageModelPaths() + target.catalogPhysicalKeys(), selected)
        return@synchronized true
    }

    fun loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String?) = synchronized(imageModelCatalogLock) {
        val persisted = parseImageCatalogRecords(prefs.getString(KEY_MODELS, null).orEmpty())
        val normalized = normalizeLocalImageModelRecords(persisted, modelId)
        val canonicalId = normalized.firstOrNull { it.matchesCatalogId(modelId) }?.id ?: modelId
        val editor = prefs.edit().putString(KEY_SELECTED_MODEL_ID, canonicalId.orEmpty())
        if (normalized != persisted) {
            val rows = JSONArray()
            normalized.sortedByDescending { it.updatedAt }.forEach { rows.put(it.toJson()) }
            editor.putString(KEY_MODELS, rows.toString())
        }
        check(editor.commit()) { "无法保存所选模型。请释放存储空间后重试。" }
    }

    fun loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

    fun saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    private fun importBundleFromSource(
        fileName: String, openSource: () -> InputStream, checkCancelled: () -> Unit, source: String
    ): LocalImageModelRecord {
        val catalogBefore = captureCatalogSnapshot()
        val workspace = File(managedDir, ".image-import-" + UUID.randomUUID())
        val stagedRoot = File(workspace, "content")
        check(stagedRoot.mkdirs()) { "无法创建图像引擎包导入临时目录。" }
        var promoted: File? = null
        try {
            val extraction = openSource().use { input ->
                extractBoundedImageBundleZip(input, stagedRoot, checkCancelled = checkCancelled)
            }
            checkCancelled()
            prepareMnnDiffusionTokenizerIfPossible(stagedRoot, checkCancelled)
            val manifest = localImageBundleManifestFromRoot(stagedRoot)
            val primary = manifest?.primaryFile ?: extraction.extractedFiles
                .filter { it.extension.lowercase(Locale.ROOT) in MODEL_FILE_EXTENSIONS }
                .sortedWith(compareByDescending<File> { it.name.isPrimaryImageModelName() }.thenByDescending { it.length() })
                .firstOrNull() ?: error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX / MNN 模型文件。")
            require(primary.canonicalFile.toPath().startsWith(stagedRoot.canonicalFile.toPath())) {
                "图像引擎包主模型位于导入目录之外。"
            }
            val family = manifest?.family
                ?: LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val draft = LocalImageModelRecord(
                displayName = manifest?.displayName ?: fileName.substringBeforeLast('.', fileName),
                path = primary.absolutePath, fileName = primary.name, sizeBytes = 0L, sha256 = "",
                runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(stagedRoot, primary), family = family,
                imageSize = manifest?.imageSize ?: defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = stagedRoot.absolutePath, source = source,
                recommendationId = manifest?.recommendationId ?: manifest?.id
            ).withObservedContent(checkCancelled)
            draft.localImageStructuralReadinessMessage()?.let { readiness -> error("图像生成引擎包不完整：$readiness") }
            checkCancelled()
            val target = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
            require(!target.exists() && stagedRoot.renameTo(target)) { "图像引擎包导入提交失败，源文件仍可重新导入。" }
            promoted = target
            val finalPrimary = File(target, primary.relativeTo(stagedRoot).path)
            checkCancelled()
            return registerRecord(draft.copy(path = finalPrimary.absolutePath, bundleRoot = target.absolutePath))
        } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(catalogBefore) }.exceptionOrNull()?.let(failure::addSuppressed)
            promoted?.let { file -> if (!runCatching { imageFileSharesCatalogOwnership(file, loadModels(discover = false)) }.getOrDefault(true))
                runCatching { if (file.exists()) file.deleteRecursively() } }
            throw failure
        } finally {
            if (workspace.exists()) runCatching { workspace.deleteRecursively() }
        }
    }

    private fun inputStreamFor(uri: Uri): InputStream {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: error("无法读取图像生成引擎文件。")
            return File(path).inputStream()
        }
        return requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "无法读取图像生成引擎文件。"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path?.let { File(it).name }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            val next = if (extension.isBlank()) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedDir, next)
            index += 1
        }
        return target
    }

    private fun uniqueBundleDir(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-bundle" }
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedDir, "$safeName-$index")
            index += 1
        }
        return target
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

    companion object {
        private const val KEY_MODELS = "local_image_models_json"
        private const val KEY_SUPPRESSED_PATHS = "suppressed_local_image_model_paths"
        private const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
        private const val KEY_SELECTED_BACKEND = "selected_image_backend"
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")
        private val SUPPORTED_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "mnn", "zip")
    }
}
