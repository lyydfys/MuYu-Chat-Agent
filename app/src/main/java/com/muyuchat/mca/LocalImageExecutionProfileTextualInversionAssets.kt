package com.muyuchat.mca

import java.io.File
import com.muyuchat.core.engine.compareUtf8Unsigned

internal fun ImageExecutionProfileResolution.withExactTextualInversionExecutionAssets(
    model: LocalImageModelRecord,
    bundleRoot: File
): ImageExecutionProfileResolution {
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "Textual inversion image bundle root is missing." }
    val stableComponentSelection = if (profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        resolveStableDiffusionComponentSelection(model)
    } else {
        null
    }
    val files = resolveTextualInversionConsumerAssetFiles(
        profile = profile,
        primaryModel = File(model.path),
        root = root,
        stableComponentSelection = stableComponentSelection
    )
        .map(File::getCanonicalFile)
        .distinctBy(File::getPath)
    val declaredByLabel = profile.tokenizer.assets.associateBy { asset ->
        asset.relativePath.replace('\\', '/').trim()
    }
    val fullyPinned = files.all { file ->
        val label = file.relativeTo(root).invariantSeparatorsPath
        declaredByLabel[label]?.sizeBytes != null
    }
    val snapshots = files
        .map { file ->
            val label = file.relativeTo(root).invariantSeparatorsPath
            captureImageExecutionAssetDescriptor(
                bundleRoot = root,
                source = file,
                installedPin = declaredByLabel[label].takeIf { fullyPinned }
            )
        }
        .sortedWith { left, right -> compareUtf8Unsigned(left.label, right.label) }
    snapshots.forEach { actual ->
        declaredByLabel[actual.label]?.let { declared ->
            require(declared.fingerprint.equals(actual.sha256, ignoreCase = true) &&
                (declared.sizeBytes == null || declared.sizeBytes == actual.sizeBytes)
            ) {
                "Prompt execution asset ${actual.label} differs from its installed profile pin."
            }
        }
    }
    val exactModelFingerprint = if (profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        val primaryPath = File(model.path).canonicalPath
        snapshots.singleOrNull { snapshot -> snapshot.path == primaryPath }?.sha256
            ?: error("Textual inversion consumer snapshot is missing the selected primary model.")
    } else {
        profile.modelFingerprint
    }
    val assetProfiles = snapshots.map { snapshot ->
        ImageProfileAsset(
            relativePath = snapshot.label,
            fingerprint = snapshot.sha256,
            sizeBytes = snapshot.sizeBytes
        )
    }
    val exactProfile = profile.copy(
        modelFingerprint = exactModelFingerprint,
        tokenizer = profile.tokenizer.copy(assets = assetProfiles),
        textualInversionExecutionAssets = null
    )
    // A declared multilingual encoder is meaningful only together with the exact asset snapshot
    // native will consume. Legacy profiles keep their historical pre-snapshot fingerprint.
    val profilePromptFingerprint = if (profile.textEncoderLanguage != null) {
        exactProfile.promptLanguageBindingFingerprint
    } else {
        profile.promptLanguageBindingFingerprint
    }
    val executionBinding = TextualInversionExecutionAssetBinding(
        runtime = exactProfile.runtime.toTextualInversionRuntime(),
        bundleRoot = root.path,
        profilePromptFingerprint = profilePromptFingerprint,
        assets = snapshots
    )
    val boundProfile = exactProfile.copy(textualInversionExecutionAssets = executionBinding)
    require(boundProfile.promptLanguageBindingFingerprint == profilePromptFingerprint) {
        "Textual-inversion execution assets lost the text-encoder language binding."
    }
    val validation = ImageExecutionProfileValidator.validate(boundProfile)
    if (!validation.valid) throw ImageProfileResolutionException(validation)
    return copy(profile = boundProfile, validation = validation)
}

internal fun resolveTextualInversionConsumerAssetFiles(
    profile: ImageExecutionProfile,
    primaryModel: File,
    root: File,
    stableComponentSelection: StableDiffusionComponentSelection? = null
): List<File> {
    val bundleRoot = root.canonicalFile
    require(bundleRoot.isDirectory) { "Textual inversion image bundle root is missing." }
    return buildList {
        when (profile.runtime) {
            LocalImageRuntime.QNN_HTP -> {
                require(profile.hasHostWritableClipTextualInversionTopology()) {
                    "The resolved QNN profile has no host-writable CLIP textual-inversion topology."
                }
                val conditioningRoot = if (profile.family == LocalImageModelFamily.SDXL) {
                    resolveSdxlQnnConditioningRoot(bundleRoot)
                } else {
                    val relativePath = requireNotNull(profile.graph.textEncoder?.relativePath) {
                        "The resolved QNN profile is missing its host CLIP graph."
                    }
                    resolveBundleRelativeFile(bundleRoot, relativePath).parentFile
                }
                val required = if (profile.family == LocalImageModelFamily.SDXL) {
                    listOf(
                        "clip.mnn",
                        "clip_2.mnn",
                        "clip_2.mnn.weight",
                        "tokenizer.json",
                        "token_emb.bin",
                        "token_emb_2.bin",
                        "pos_emb.bin",
                        "pos_emb_2.bin"
                    )
                } else {
                    listOf("clip_v2.mnn", "tokenizer.json", "token_emb.bin", "pos_emb.bin")
                }
                required.mapTo(this) { name -> requireDirectExecutionAsset(conditioningRoot, name) }
                val optionalClipWeight = if (profile.family == LocalImageModelFamily.SDXL) {
                    "clip.mnn.weight"
                } else {
                    "clip_v2.mnn.weight"
                }
                File(conditioningRoot, optionalClipWeight)
                    .takeIf { file -> file.isFile && file.length() > 0L }
                    ?.let(::add)
            }
            LocalImageRuntime.MNN_DIFFUSION -> {
                require(profile.hasHostWritableClipTextualInversionTopology()) {
                    "The resolved MNN profile has no host-writable CLIP textual-inversion topology."
                }
                val relativePath = requireNotNull(profile.graph.textEncoder?.relativePath) {
                    "The resolved MNN profile is missing its host CLIP graph."
                }
                val conditioningRoot = requireNotNull(
                    resolveBundleRelativeFile(bundleRoot, relativePath).parentFile
                ) { "The resolved MNN CLIP graph has no conditioning directory." }
                listOf("clip_v2.mnn", "tokenizer.json", "token_emb.bin", "pos_emb.bin")
                    .mapTo(this) { name -> requireDirectExecutionAsset(conditioningRoot, name) }
                File(conditioningRoot, "clip_v2.mnn.weight")
                    .takeIf { file -> file.isFile && file.length() > 0L }
                    ?.let(::add)
            }
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
                val primary = primaryModel.canonicalFile
                require(primary.isFile && primary.path.startsWith(bundleRoot.path + File.separator)) {
                    "Textual inversion primary model is missing or escapes its bundle root."
                }
                add(primary)
                val selectedTextEncoder = stableComponentSelection?.textEncoderPath?.let(::File)
                    ?: profile.graph.textEncoder?.relativePath?.let { path ->
                        resolveBundleRelativeFile(bundleRoot, path)
                    }
                selectedTextEncoder?.canonicalFile?.also(::add)
                val compatibilityFallback = stableComponentSelection?.fallback ?: (
                    profile.provenance.primarySource in setOf(
                        ImageProfileSource.CAPABILITY_DISCOVERY,
                        ImageProfileSource.GENERIC_FALLBACK
                    )
                )
                if (compatibilityFallback) {
                    addAll(resolveStableDiffusionCompatibilityPromptComponents(bundleRoot, primary))
                }
            }
            LocalImageRuntime.ONNX_RUNTIME,
            LocalImageRuntime.MEDIATEK_NEURON,
            LocalImageRuntime.CUSTOM -> error(
                "Textual inversion is not executable on runtime ${profile.runtime}."
            )
        }
        // A positive Chinese-language admission is bound to the complete native text-encoder
        // closure. Retain every declared graph/sidecar in the same request snapshot so narrowing
        // the tokenizer asset list cannot detach semantic evidence from native execution.
        profile.textEncoderLanguage?.evidence?.consumedAssets()?.forEach { languageAsset ->
            add(resolveBundleRelativeFile(bundleRoot, languageAsset.relativePath))
        }
    }.map(File::getCanonicalFile)
        .distinctBy(File::getPath)
        .sortedWith { left, right ->
            compareUtf8Unsigned(
                left.relativeTo(bundleRoot).invariantSeparatorsPath,
                right.relativeTo(bundleRoot).invariantSeparatorsPath
            )
        }
}

private fun resolveBundleRelativeFile(root: File, relativePath: String): File {
    val normalized = relativePath.replace('\\', '/').trim()
    require(normalized.isNotBlank() && !File(normalized).isAbsolute &&
        !Regex("^[A-Za-z]:/").containsMatchIn(normalized)
    ) { "Prompt execution asset path must be bundle-relative." }
    val file = File(root, normalized).canonicalFile
    require(file.path.startsWith(root.path + File.separator) && file.isFile && file.length() > 0L) {
        "Prompt execution asset is missing or escapes its bundle root: $relativePath"
    }
    return file
}

private fun requireDirectExecutionAsset(directory: File, name: String): File =
    File(directory, name).canonicalFile.also { file ->
        require(file.parentFile == directory.canonicalFile && file.isFile && file.length() > 0L) {
            "Prompt execution asset is missing or empty: ${file.path}"
        }
    }

private fun LocalImageRuntime.toTextualInversionRuntime(): TextualInversionRuntime = when (this) {
    LocalImageRuntime.QNN_HTP -> TextualInversionRuntime.QNN_HTP
    LocalImageRuntime.MNN_DIFFUSION -> TextualInversionRuntime.MNN_DIFFUSION
    LocalImageRuntime.STABLE_DIFFUSION_CPP -> TextualInversionRuntime.STABLE_DIFFUSION_CPP
    LocalImageRuntime.ONNX_RUNTIME,
    LocalImageRuntime.MEDIATEK_NEURON,
    LocalImageRuntime.CUSTOM -> error("Textual inversion is not executable on runtime $this.")
}
