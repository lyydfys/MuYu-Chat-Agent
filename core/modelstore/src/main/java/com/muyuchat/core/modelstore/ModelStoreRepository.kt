package com.muyuchat.core.modelstore

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

class ModelStoreRepository(private val context: Context) {
    internal val appContext = context.applicationContext
    internal val manifestFile: File by lazy { File(appContext.filesDir, "mca-models.json") }
    internal val managedModelDir: File by lazy {
        appContext.getExternalFilesDir("models") ?: File(appContext.filesDir, "models")
    }

    fun listModels(): List<ModelManifest> = listModelsImpl()

    fun <T> withCatalogTransaction(block: () -> T): T = withCatalogTransactionImpl(block)

    fun getModel(id: String): ModelManifest? = getModelImpl(id)

    fun reconcileStableIdentities(): ModelIdentityReconciliation = reconcileStableIdentitiesImpl()

    fun pendingIdentityReferenceMigration(): Map<String, String> = pendingIdentityReferenceMigrationImpl()

    fun acknowledgeIdentityReferenceMigration() = acknowledgeIdentityReferenceMigrationImpl()

    fun importFromUris(
        uris: List<Uri>,
        displayNameOverride: String? = null,
        options: ModelImportOptions = ModelImportOptions(),
        onProgress: (ModelImportProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ModelManifest = importFromUrisImpl(uris, displayNameOverride, options, onProgress, checkCancelled)

    fun importFromTreeUri(
        treeUri: Uri,
        displayNameOverride: String? = null,
        options: ModelImportOptions = ModelImportOptions(),
        onProgress: (ModelImportProgress) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): ModelManifest = importFromTreeUriImpl(treeUri, displayNameOverride, options, onProgress, checkCancelled)

    internal fun importPreparedSources(
        sources: List<ModelImportSource>, identity: String, kind: ModelImportKind,
        displayNameOverride: String?, options: ModelImportOptions,
        onProgress: (ModelImportProgress) -> Unit, checkCancelled: () -> Unit
    ): ModelManifest = importPreparedSourcesImpl(sources, identity, kind, displayNameOverride, options, onProgress, checkCancelled)

    fun importFromUri(uri: Uri, displayNameOverride: String? = null): ModelManifest = importFromUriImpl(uri, displayNameOverride)

    fun registerDownloadedModel(
        file: File,
        repoId: String,
        revision: String,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE,
        runtime: ChatModelRuntime = ChatModelRuntime.LLAMA_CPP
    ): ModelManifest = registerDownloadedModelImpl(file, repoId, revision, license, source, runtime)

    fun registerDownloadedLiteRtLmModel(
        file: File,
        repoId: String? = null,
        revision: String? = null,
        license: String? = null,
        source: ModelSource = ModelSource.MODELSCOPE,
        displayName: String? = null
    ): ModelManifest = registerDownloadedLiteRtLmModelImpl(file, repoId, revision, license, source, displayName)

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
    ): ModelManifest = registerDownloadedMnnBundleImpl(displayName, bundleDir, repoId, revision, license, source, quant, architecture, requiredFiles, checkCancelled, onProgress)

    fun registerDownloadedQairtBundle(
        displayName: String,
        bundleDir: File,
        repoId: String?,
        revision: String?,
        license: String? = null,
        source: ModelSource = ModelSource.HUGGING_FACE,
        quant: String? = "w4a16 QAIRT",
        architecture: String? = null
    ): ModelManifest = registerDownloadedQairtBundleImpl(displayName, bundleDir, repoId, revision, license, source, quant, architecture)

    fun resolveQairtBundleRoot(bundleDir: File): File = resolveQairtBundleRootImpl(bundleDir)

    fun deleteModel(id: String): Boolean = deleteModelImpl(id)

    fun attachVisionProjector(modelId: String, uri: Uri, displayNameOverride: String? = null): ModelManifest = attachVisionProjectorImpl(modelId, uri, displayNameOverride)

    fun attachVisionProjectorFile(modelId: String, file: File, displayNameOverride: String? = null): ModelManifest = attachVisionProjectorFileImpl(modelId, file, displayNameOverride)

    fun markLoaded(id: String) = markLoadedImpl(id)

    fun updateModel(model: ModelManifest): List<ModelManifest> = updateModelImpl(model)

    fun verify(id: String): Boolean = verifyImpl(id)

    fun validateForLoad(id: String): ModelCompatibilityResult = validateForLoadImpl(id)

    fun managedFileFor(fileName: String): File = managedFileForImpl(fileName)

    fun managedBundleDirFor(bundleId: String): File = managedBundleDirForImpl(bundleId)

    fun managedBundleFileFor(bundleDir: File, fileName: String): File = managedBundleFileForImpl(bundleDir, fileName)

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

    internal fun File.isMcaManagedBundleDir(): Boolean {
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

    internal fun File.isUnderManagedModelDir(): Boolean {
        val managedRoot = runCatching { managedModelDir.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { canonicalFile }.getOrNull() ?: return false
        return candidate.path == managedRoot.path ||
            candidate.path.startsWith(managedRoot.path + File.separator)
    }

    internal companion object {
        internal val MODEL_IMPORT_LOCK = Any()
        internal val MODEL_IMPORT_TASK_LOCK = Any()
        internal const val IMPORT_MAGIC_BYTES = LITERT_LM_MAGIC_SIZE
        private const val MB = 1024L * 1024L
        internal const val GB = 1024L * MB
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")
    }

}
