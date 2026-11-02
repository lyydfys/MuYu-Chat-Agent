package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

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
                val hasDirectManifest = bundleRoot.directImageManifestFile() != null
                val inspection = if (hasDirectManifest) {
                    runCatching { inspectLocalImageBundleManifestFromRoot(bundleRoot) }
                        .getOrNull() ?: LocalImageBundleManifestInspection.Undeclared
                } else {
                    LocalImageBundleManifestInspection.Undeclared
                }
                val manifest = (inspection as? LocalImageBundleManifestInspection.Ready)?.manifest
                val rawManifest = if (hasDirectManifest) {
                    runCatching { JSONObject(bundleRoot.directImageManifestFile()!!.readText(Charsets.UTF_8)) }
                        .getOrNull()
                } else {
                    null
                }
                // Keep a malformed declared MediaTek package visible as an incompatible
                // model so readiness can report the concrete contract/file error. Never
                // fall through to the generic GGUF/CPU Anima route.
                val declaredMediaTek = rawManifest?.optString("runtime")
                    ?.equals(LocalImageRuntime.MEDIATEK_NEURON.name, ignoreCase = true) == true
                // A declared diffusion primary remains authoritative when missing. Falling
                // back to the VAE/encoder in that directory creates a second broken model.
                // Persisted rows keep the missing-file diagnosis; a complete retained copy
                // can take over during normalization without inheriting the old native proof.
                val declaredPrimaryPath = manifest?.declaredPrimaryPath
                    ?: if (declaredMediaTek) {
                        rawManifest?.optJSONArray("components")?.firstComponentPath("DIFFUSION")
                            ?: rawManifest?.optString("primary")?.takeIf(String::isNotBlank)
                    } else null
                val declaredPrimary = declaredPrimaryPath
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
                // The shared `models` directory contains chat and image models. A bare
                // `.gguf`/`.mnn` file is not image evidence: those are also the native
                // formats for chat GGUF and MNN LLM bundles. Only directories explicitly
                // dedicated to image models, a valid image manifest, a recognised image
                // primary, or a known image-family bundle may enter this catalog.
                if (!localImageBundleHasPositiveImageEvidence(bundleRoot, primary)) {
                    return@runCatching null
                }
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
                val runtime = manifest?.runtime
                    ?: if (declaredMediaTek) LocalImageRuntime.MEDIATEK_NEURON
                    else inferLocalImageRuntimeForBundle(bundleRoot, primary, discovery = true)
                if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION &&
                    runtime != LocalImageRuntime.MEDIATEK_NEURON &&
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

/**
 * Distinguishes image packages from chat packages that share the same native file
 * extensions. `image_models` is an explicit user/runtime image area; elsewhere the
 * directory must carry positive image evidence. This is a package-shape classifier,
 * never a device/chipset admission decision.
 */
internal fun localImageBundleHasPositiveImageEvidence(bundleRoot: File, primary: File): Boolean {
    val root = runCatching { bundleRoot.canonicalFile }.getOrDefault(bundleRoot)
    if (root.isExplicitLocalImageModelRoot()) return true
    if (root.directImageManifestFile() != null &&
        runCatching { inspectLocalImageBundleManifestFromRoot(root) }.getOrNull() is LocalImageBundleManifestInspection.Ready
    ) {
        return true
    }
    val declaresMediaTekImage = root.directImageManifestFile()?.let { file ->
        runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).optString("runtime")
                .equals(LocalImageRuntime.MEDIATEK_NEURON.name, ignoreCase = true)
        }.getOrDefault(false)
    } == true
    if (declaresMediaTekImage && primary.extension.lowercase() in READINESS_MODEL_EXTENSIONS) return true
    if (primary.name.isPrimaryImageModelName()) return true
    val familyHint = LocalImageModelFamily.infer(root.name)
    if (familyHint != LocalImageModelFamily.CUSTOM &&
        primary.extension.lowercase() in READINESS_MODEL_EXTENSIONS &&
        !localImageBundleHasExplicitChatEvidence(root, primary)
    ) {
        return true
    }
    val files = runCatching {
        imageDiscoveryWalk(root).filter { it.isFile && it.length() > 0L }.toList()
    }.getOrDefault(emptyList())
    if (files.any { it.name.isPrimaryImageModelName() } ||
        localImageBundleHasImageCompanionShape(root, primary, files)
    ) {
        return true
    }
    return false
}

/**
 * True only when every readable physical copy is positively identifiable as a chat
 * package. An unknown model shape is retained; missing/unreadable content is never
 * tombstoned. This prevents the migration from deleting a valid legacy image bundle
 * merely because its files use generic names.
 */
internal fun LocalImageModelRecord.hasNegativeLocalImageEvidence(): Boolean {
    val copies = allPhysicalCopies()
    if (copies.isEmpty()) return false
    var observedReadableCopy = false
    for (copy in copies) {
        val primary = File(copy.path)
        if (!primary.isFile || !primary.canRead() || primary.length() <= 0L) return false
        val root = copy.bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
            ?: primary.parentFile
            ?: return false
        if (!root.isDirectory || !root.canRead()) return false
        observedReadableCopy = true
        if (localImageBundleHasPositiveImageEvidence(root, primary)) return false
        if (!localImageBundleHasExplicitChatEvidence(root, primary)) return false
    }
    return observedReadableCopy
}

private fun File.isExplicitLocalImageModelRoot(): Boolean =
    invariantSeparatorsPath.split('/').any { it.equals("image_models", ignoreCase = true) }

private fun File.directImageManifestFile(): File? =
    listFiles()?.firstOrNull { it.isFile && it.name.equals("manifest.json", ignoreCase = true) }

private fun localImageBundleHasImageCompanionShape(root: File, primary: File, files: List<File>): Boolean {
    fun modelFiles(): List<File> = files.filter { file ->
        file.extension.lowercase() in READINESS_MODEL_EXTENSIONS
    }
    fun hasModelToken(vararg tokens: String): Boolean = modelFiles().any { file ->
        val name = file.name.lowercase()
        tokens.any { token -> token in name }
    }
    val hasVae = hasModelToken("vae") || modelFiles().any { file ->
        file.name.lowercase().let { name ->
            name == "ae.sft" || name == "ae.safetensors" || name == "ae.gguf" ||
                name.endsWith("_ae.safetensors") || name.endsWith("-ae.safetensors") ||
                name.endsWith("_ae.gguf") || name.endsWith("-ae.gguf")
        }
    }
    val hasEncoder = files.any { file ->
        val name = file.name.lowercase()
        val extension = file.extension.lowercase()
        val tokenizerAsset = extension in setOf("json", "txt", "mtok") &&
            listOf("tokenizer", "vocab", "merges").any { token -> token in name }
        val encoderModel = extension in READINESS_MODEL_EXTENSIONS &&
            name !in setOf("llm_config.json", "te_llm_config.json", "config.json") &&
            listOf("text_encoder", "text-encoder", "text_encoders", "t5xxl", "t5-xxl", "clip", "qwen", "llm")
                .any { token -> token in name }
        tokenizerAsset || encoderModel
    }
    val primaryLower = primary.name.lowercase()
    val primaryLooksLikeDiffusion = primary.name.isPrimaryImageModelName() ||
        listOf("vae", "text_encoder", "text-encoder", "tokenizer", "clip").none { it in primaryLower }
    val relativeCount = files.count { file ->
        runCatching { file.relativeTo(root) }.isSuccess
    }
    return relativeCount == files.size && hasVae && hasEncoder && primaryLooksLikeDiffusion
}

private fun localImageBundleHasExplicitChatEvidence(root: File, primary: File): Boolean {
    val files = runCatching {
        imageDiscoveryWalk(root).filter { it.isFile && it.length() > 0L }.toList()
    }.getOrDefault(emptyList())
    val relativeNames = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasName(name: String): Boolean = relativeNames.any { it.endsWith("/$name") || it == name }
    val hasMnnLlmPair = hasName("llm.mnn") && hasName("llm.mnn.weight") &&
        relativeNames.none { path -> path.endsWith("/unet.mnn") || path.endsWith("/diffusion.mnn") || path.endsWith("/transformer.mnn") }
    val hasQairtChatLayout = hasName("htp_backend_ext_config.json") ||
        hasName("genie_config.json") || hasName("part1_of_2.bin") || hasName("part2_of_2.bin")
    val hasChatConfig = hasName("llm_config.json") || hasName("te_llm_config.json") || hasName("mca_runtime_llm_config.json")
    val chatIdentity = (root.name + "/" + primary.name).lowercase()
    val namedChatGguf = primary.extension.equals("gguf", ignoreCase = true) &&
        listOf("gemma", "qwen", "llama", "mistral", "deepseek", "instruct", "abliterated", "chat")
            .any { token -> token in chatIdentity }
    return hasMnnLlmPair || hasQairtChatLayout || (hasChatConfig && primary.extension.equals("mnn", ignoreCase = true)) || namedChatGguf
}

internal fun LocalImageModelStore.suppressedImageModelPaths(): Set<String> =
        prefs.getStringSet(KEY_SUPPRESSED_PATHS, emptySet()).orEmpty()

internal fun LocalImageModelStore.persistModels(
        models: List<LocalImageModelRecord>, suppressedPaths: Set<String>? = null,
        selectedModelId: String? = loadSelectedModelId(), selectedBackend: ImageBackend? = null,
        catalogRecoveryRaw: String? = null
    ) {
        val normalized = normalizeLocalImageModelRecords(models, selectedModelId)
        val array = JSONArray()
        normalized.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        val canonicalSelection = normalized.firstOrNull { it.matchesCatalogId(selectedModelId) }?.id ?: selectedModelId
        val editor = prefs.edit().putString(KEY_MODELS, array.toString())
            .putString(KEY_SELECTED_MODEL_ID, canonicalSelection.orEmpty())
        suppressedPaths?.let { editor.putStringSet(KEY_SUPPRESSED_PATHS, it) }
        catalogRecoveryRaw?.let { editor.putString("catalog_recovery_raw", it) }
        selectedBackend?.let { editor.putString(KEY_SELECTED_BACKEND, it.name) }
        check(editor.commit()) {
            "无法保存模型列表。请释放存储空间后刷新或重试导入，模型文件已保留。"
        }
    }
