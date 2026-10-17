package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.core.download.RemoteModelFile
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID

/**
 * T2B22-T5（b 工位）拆片：catalog 注册/保存/更新/删除与下载登记 helper 族自
 * LocalImageModelStore.kt 逐字节平移；声明行去缩进并登记为 LocalImageModelStore 扩展
 * （public 成员保持 public；仅 registerDownloadedRecord 保持 private）。
 */

    /** The complete record, selection and backend are registered in one preferences commit. */
internal fun LocalImageModelStore.registerRecord(
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

fun LocalImageModelStore.saveModels(models: List<LocalImageModelRecord>) = synchronized(imageModelCatalogLock) {
        // Tombstones now include the runtime. Saving another runtime that shares
        // this primary must not resurrect the removed row on the next scan.
        val visibleKeys = models.flatMapTo(mutableSetOf()) {
            it.allPhysicalCopies().flatMap { copy ->
                listOf(localImagePhysicalIdentity(it.runtime, copy.path), canonicalImageModelPath(copy.path))
            }
        }
        persistModels(models, suppressedImageModelPaths() - visibleKeys)
    }

fun LocalImageModelStore.removeModelRecord(id: String): Boolean = synchronized(imageModelCatalogLock) {
        val models = loadModels()
        val target = models.firstOrNull { it.matchesCatalogId(id) } ?: return@synchronized false
        val remaining = models.filterNot { it.id == target.id }
        val selected = if (target.matchesCatalogId(loadSelectedModelId()))
            remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id else loadSelectedModelId()
        persistModels(remaining, suppressedImageModelPaths() + target.catalogPhysicalKeys(), selected)
        true
    }

fun LocalImageModelStore.updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> = synchronized(imageModelCatalogLock) {
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

fun LocalImageModelStore.importFromUri(uri: Uri, checkCancelled: () -> Unit = {}): LocalImageModelRecord =
        importFromSource(queryDisplayName(uri) ?: "image-model.task", { inputStreamFor(uri) }, checkCancelled)

    /** Production import entry also usable by file fixtures, without an Android URI provider. */
internal fun LocalImageModelStore.importFromSource(
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

fun LocalImageModelStore.registerDownloadedModel(
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

fun LocalImageModelStore.managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        // The bundle installer writes into a hidden staging directory and atomically
        // promotes it only after verification. Materializing the public destination
        // here leaves a misleading empty folder when Android stops the process during
        // a large download or while post-download validation is still running.
        return File(managedDir, "bundle-$safeName")
    }

fun LocalImageModelStore.managedBundleFileFor(bundleDir: File, fileName: String): File {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(bundleDir, safeName.ifBlank { "component-${System.currentTimeMillis()}" })
    }

fun LocalImageModelStore.registerDownloadedBundle(
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
private fun LocalImageModelStore.registerDownloadedRecord(record: LocalImageModelRecord): LocalImageModelRecord =
        registerRecord(record.withObservedContent())

    /** Optional archive cleanup must not erase a primary or a shared bundle component. */
internal fun LocalImageModelStore.deleteManagedDuplicate(file: File): Boolean = synchronized(imageModelCatalogLock) {
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

internal fun LocalImageModelStore.imageFileSharesCatalogOwnership(candidate: File, records: List<LocalImageModelRecord>): Boolean =
        localImageFileSharesCatalogOwnership(candidate, records)

internal fun LocalImageModelStore.sameCanonicalPath(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        return runCatching { File(first).canonicalPath == File(second).canonicalPath }.getOrDefault(false)
    }

fun LocalImageModelStore.managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

fun LocalImageModelStore.deleteModel(id: String): Boolean = synchronized(imageModelCatalogLock) {
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
