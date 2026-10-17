package com.muyuchat.mca

import java.io.File
import org.json.JSONArray

/**
 * T2B22-T5（b 工位）拆片：image_models 扫描/发现与 catalog 持久化入口（discoverInstalledImageBundles /
 * suppressedImageModelPaths / persistModels）自 LocalImageModelStore.kt 逐字节平移；声明行去缩进并
 * 登记为 LocalImageModelStore 扩展（private→internal）。catalog 事务与 loadModels 编排留壳。
 */

internal fun LocalImageModelStore.discoverInstalledImageBundles(persisted: List<LocalImageModelRecord>): List<LocalImageModelRecord> {
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

internal fun LocalImageModelStore.suppressedImageModelPaths(): Set<String> =
        prefs.getStringSet(KEY_SUPPRESSED_PATHS, emptySet()).orEmpty()

internal fun LocalImageModelStore.persistModels(
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
