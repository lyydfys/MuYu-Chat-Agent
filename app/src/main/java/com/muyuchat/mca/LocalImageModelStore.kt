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

internal val imageModelCatalogLock = Any()

internal fun canonicalImageModelPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)

internal fun remoteImageModelSource(remote: RemoteModelFile): String =
    "${remote.provider.name.lowercase()}:${remote.repoId}@${remote.revision}/${remote.path}"

internal data class LocalImageCatalogSnapshot(
    val models: String?, val selectedModelId: String?, val selectedBackend: String?, val suppressedPaths: Set<String>
)

class LocalImageModelStore(context: Context) {
    internal val appContext = context.applicationContext
    internal val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    internal val managedDir: File by lazy {
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
        val ineligible = persisted.filter(LocalImageModelRecord::hasNegativeLocalImageEvidence)
        if (ineligible.isNotEmpty()) {
            // Older catalogs can contain chat GGUF/MNN packages that were inferred as
            // image models solely from their file extension. Preserve the original JSON
            // and tombstone only the image-catalog identity in one atomic commit. Model
            // files are never deleted, and an unrecognised package shape is retained.
            val remaining = persisted.filterNot { it in ineligible }
            val suppressed = suppressedImageModelPaths() + ineligible.flatMap { it.catalogPhysicalKeys() }
            val selected = loadSelectedModelId()?.takeUnless { selectedId ->
                ineligible.any { it.matchesCatalogId(selectedId) }
            }
            val normalizedRemaining = normalizeLocalImageModelRecords(remaining, selected)
            val recoveryRaw = raw.takeIf { it.isNotBlank() && !prefs.contains("catalog_recovery_raw") }
            runCatching {
                persistModels(normalizedRemaining, suppressed, selected, catalogRecoveryRaw = recoveryRaw)
            }
            persisted = normalizedRemaining
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

    companion object {
    }
}
