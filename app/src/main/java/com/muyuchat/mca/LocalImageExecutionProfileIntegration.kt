package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONObject

internal typealias LocalImageManifestProfileResolver =
    (ImageExecutionProfileResolverInput) -> ImageExecutionProfileResolution

/** Resolves the immutable execution contract used by one concrete generation request. */
internal fun resolveLocalImageExecutionProfile(
    model: LocalImageModelRecord,
    options: LocalImageGenerationOptions,
    bundleRoot: File?,
    familyOverride: LocalImageModelFamily? = null,
    captureTextualInversionExecutionAssets: Boolean = false
): ImageExecutionProfileResolution {
    val canonicalRoot = bundleRoot
        ?.takeIf(File::isDirectory)
        ?.let { root -> runCatching { root.canonicalFile }.getOrElse { root.absoluteFile } }
    val manifestFile = canonicalRoot?.let(::findImageManifestFile)
    val manifestJson = manifestFile?.let { file ->
        runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrElse { error ->
                throw ImageExecutionProfileJsonException(
                    code = "PROFILE_JSON_INVALID",
                    field = "manifest",
                    message = "Image bundle manifest is not valid JSON.",
                    cause = error
                )
            }
    }
    val recommendationId = manifestJson
        ?.optString("recommendationId")
        ?.takeIf(String::isNotBlank)
        ?: manifestJson?.optString("id")?.takeIf(String::isNotBlank)
    val manifestProfile = manifestJson
        ?.let(ImageExecutionProfileJson::parseManifest)
        ?.let { profile -> canonicalRoot?.let(profile::rebindInstalledArtifactPaths) ?: profile }
    val manifestBehavior = if (manifestProfile == null) {
        manifestJson?.let(ImageExecutionProfileJson::parseManifestBehavior)
    } else {
        null
    }
    val sidecar = canonicalRoot?.let { root ->
        parseLocalImageExecutionProfileSidecars(root, manifestProfile)
    }
    val inferredRecommendationFamily = recommendationId
        ?.let(LocalImageModelFamily::from)
        ?.takeUnless { it == LocalImageModelFamily.CUSTOM }
    val effectiveFamily = familyOverride
        ?: manifestProfile?.family
        ?: manifestBehavior?.family
        ?: sidecar?.behavior?.family
        ?: model.family.takeUnless { it == LocalImageModelFamily.CUSTOM }
        ?: inferredRecommendationFamily
        ?: model.family
    val discovery = canonicalRoot?.let { root ->
        discoverLocalImageExecutionCapabilities(root, model.runtime, effectiveFamily)
    }
    val schedulerOverride = options.sampleMethod
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(::imageSchedulerAlgorithmFromProductName)
    val qnnSd15ApiSmoke = model.runtime == LocalImageRuntime.QNN_HTP &&
        effectiveFamily == LocalImageModelFamily.SD15 && options.steps == 4
    val overrides = ImageGenerationOverrides(
        scheduler = schedulerOverride,
        steps = options.steps,
        // The Local API's bounded QNN SD1.5 health request is a supported four-step
        // semantic execution, not a device admission bypass. Treat it like the
        // debug smoke override so manifest/sidecar profiles are normalized before
        // strict validation rather than rejected at the Kotlin boundary.
        allowLowStepSmoke = options.allowLowStepSmoke || qnnSd15ApiSmoke,
        cfgScale = options.cfgScale,
        useCfg = options.useCfg,
        width = options.width,
        height = options.height,
        useUltraFixDimensionContract = options.ultraFix != null,
        seed = options.seed?.toLong(),
        negativePrompt = options.negativePrompt,
        negativePromptSpecified = options.negativePrompt != null
    )
    val resolverInput = ImageExecutionProfileResolverInput(
        modelFingerprint = modelExecutionFingerprint(model, manifestProfile),
        runtime = model.runtime,
        family = effectiveFamily,
        recommendationId = recommendationId,
        recommendationRevision = manifestJson
            ?.optString("revision")
            ?.takeIf(String::isNotBlank),
        manifestProfile = manifestProfile,
        manifestBehavior = manifestBehavior,
        sidecar = sidecar,
        capabilityDiscovery = discovery,
        recommendationEvidence = localImageRecommendationEvidence(
            model = model,
            bundleRoot = canonicalRoot,
            manifestJson = manifestJson
        ),
        userOverrides = overrides
    )
    val initialResolution = if (schedulerOverride != null) {
        // Validate the explicit sampler against the base task topology before applying it as a
        // profile override. Otherwise a split-QNN profile that does not globally advertise PNDM
        // would fail generic profile validation first and lose the stable
        // `unsupported_img2img_sampler` product/API error.
        val baseResolution = ImageExecutionProfileResolver.resolve(
            resolverInput.copy(
                userOverrides = overrides.copy(scheduler = null)
            )
        )
        validateLocalImageTaskSamplerCapability(
            profile = baseResolution.profile,
            taskMode = options.taskMode,
            requestedScheduler = schedulerOverride
        )
        ImageExecutionProfileResolver.resolve(resolverInput)
    } else {
        ImageExecutionProfileResolver.resolve(resolverInput)
    }
    // A missing sampler is a product default, not an explicit request. Generic QNN profiles
    // historically default to PNDM, but PNDM/PLMS cannot enter a strength-derived img2img tail.
    // Re-resolve only when that default is task-incompatible, retaining the original provenance
    // and requested-override metadata so the fallback is not misreported as a user override.
    val resolution = if (schedulerOverride == null) {
        val taskDefault = initialResolution.profile.defaultSchedulerForProductTask(options.taskMode)
        if (taskDefault == initialResolution.profile.scheduler.algorithm) {
            initialResolution
        } else {
            val taskResolution = ImageExecutionProfileResolver.resolve(
                resolverInput.copy(
                    userOverrides = overrides.copy(scheduler = taskDefault)
                )
            )
            taskResolution.copy(
                profile = taskResolution.profile.copy(
                    provenance = initialResolution.profile.provenance
                ),
                layers = taskResolution.layers.copy(
                    requested = initialResolution.layers.requested
                ),
                fieldSources = initialResolution.fieldSources,
                sourceChain = initialResolution.sourceChain,
                warnings = initialResolution.warnings
            )
        }
    } else {
        initialResolution
    }
    // QNN SD1.5 bundles may provide a scheduler sidecar with the historical
    // ten-step quality floor while their manifest/API smoke contract explicitly
    // requests four steps. Reconcile only that concrete bounded request before
    // validation; normal requests and other families retain the declared floor.
    require(resolution.profile.runtime == model.runtime) {
        "Image execution profile runtime ${resolution.profile.runtime} does not match ${model.runtime}."
    }
    require(effectiveFamily == LocalImageModelFamily.CUSTOM || resolution.profile.family == effectiveFamily) {
        "Image execution profile family ${resolution.profile.family} does not match $effectiveFamily."
    }
    val scheduled = resolution.withProductDenoisingSchedule(options)
    return if (!captureTextualInversionExecutionAssets || options.textualInversionIds.isEmpty()) {
        scheduled
    } else {
        scheduled.withExactTextualInversionExecutionAssets(
            model = model,
            bundleRoot = requireNotNull(canonicalRoot) {
                "Textual inversion requires a concrete image bundle root."
            }
        )
    }
}

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

/** Captures a canonical, non-symlink regular file into an exact reusable package descriptor. */
internal fun captureImageExecutionAssetDescriptor(
    bundleRoot: File,
    source: File,
    installedPin: ImageProfileAsset? = null
): TextualInversionExecutionAssetDescriptor {
    val root = bundleRoot.canonicalFile
    val requested = source.absoluteFile
    require(!Files.isSymbolicLink(requested.toPath())) {
        "Prompt execution asset must not be a symbolic link: ${requested.path}"
    }
    val file = requested.canonicalFile
    require(file.path.startsWith(root.path + File.separator)) {
        "Prompt execution asset escapes its bundle root: ${file.path}"
    }
    fun attributes(): BasicFileAttributes = Files.readAttributes(
        file.toPath(),
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )
    val before = attributes()
    require(before.isRegularFile && !before.isSymbolicLink && before.size() > 0L) {
        "Prompt execution asset is missing, empty, or not a regular file: ${file.path}"
    }
    val label = file.relativeTo(root).invariantSeparatorsPath
    val sha256 = installedPin?.let { pin ->
        require(pin.relativePath.replace('\\', '/').trim() == label &&
            pin.sizeBytes == before.size() &&
            TextualInversionContract.SHA256_PATTERN.matches(pin.fingerprint.lowercase())
        ) { "Prompt execution asset $label differs from its installed profile pin." }
        pin.fingerprint.lowercase()
    } ?: file.sha256ForProfile()
    val after = attributes()
    require(after.isRegularFile && !after.isSymbolicLink &&
        before.fileKey()?.toString() == after.fileKey()?.toString() &&
        before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime()
    ) { "Prompt execution asset changed while it was being captured: ${file.path}" }
    installedPin?.let { pin ->
        require(pin.sizeBytes == after.size()) {
            "Prompt execution asset $label differs from its installed profile pin."
        }
    }
    return TextualInversionExecutionAssetDescriptor(
        label = label,
        path = file.path,
        sizeBytes = after.size(),
        sha256 = sha256,
        fileKey = after.fileKey()?.toString(),
        lastModifiedMillis = after.lastModifiedTime().toMillis()
    )
}

/**
 * Verifies every concrete text-encoder file that authorizes direct Simplified Chinese input.
 *
 * This deliberately does not reuse a downloaded profile pin: direct multilingual admission is a
 * semantic claim about the exact bytes native will consume, so the SHA-256 is recomputed for the
 * requested file every time this helper is called. Callers choose the request boundary; this
 * helper performs no caching or filesystem mutation.
 */
internal fun verifyNativeMultilingualTextEncoderEvidenceAsset(
    bundleRoot: File,
    profile: ImageExecutionProfile
) {
    // An unsigned, expired, or malformed multilingual declaration is deliberately retained for
    // diagnostics, but it must fall back to the English-dominant product path. Only the verified
    // closure authorizes direct Chinese input and therefore requires this strict byte check.
    if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return

    val evidenceAssets = requireNotNull(profile.textEncoderLanguage?.evidence).consumedAssets()
    try {
        val root = bundleRoot.canonicalFile
        require(root.isDirectory) { "Text-encoder evidence bundle root is missing." }
        evidenceAssets.forEach { evidenceAsset ->
            val expectedPath = evidenceAsset.relativePath.replace('\\', '/').trim()
            require(expectedPath.isNotBlank()) { "Text-encoder evidence asset path is blank." }

            // Check every requested component before canonicalization so an in-tree symlink cannot
            // be accepted merely because its target resolves below the bundle root.
            var requestedPath = root.toPath()
            expectedPath.split('/').forEach { segment ->
                requestedPath = requestedPath.resolve(segment)
                require(!Files.isSymbolicLink(requestedPath)) {
                    "Text-encoder evidence asset path must not traverse a symbolic link: $requestedPath"
                }
            }
            val requested = requestedPath.toFile().absoluteFile
            val file = requested.canonicalFile
            require(file.path.startsWith(root.path + File.separator)) {
                "Text-encoder evidence asset escapes its bundle root: ${file.path}"
            }
            val actualPath = file.relativeTo(root).invariantSeparatorsPath
            require(actualPath == expectedPath) {
                "Text-encoder evidence asset path differs from its profile evidence pin."
            }

            fun attributes(): BasicFileAttributes = Files.readAttributes(
                file.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )

            val before = attributes()
            require(before.isRegularFile && !before.isSymbolicLink && before.size() > 0L) {
                "Text-encoder evidence asset is missing, empty, or not a regular file: ${file.path}"
            }
            require(before.size() == evidenceAsset.sizeBytes) {
                "Text-encoder evidence asset size differs from its profile evidence pin."
            }
            val actualSha256 = file.sha256ForProfile()
            val after = attributes()
            require(after.isRegularFile && !after.isSymbolicLink &&
                before.fileKey()?.toString() == after.fileKey()?.toString() &&
                before.size() == after.size() &&
                before.lastModifiedTime() == after.lastModifiedTime()
            ) {
                "Text-encoder evidence asset changed while its SHA-256 was verified: ${file.path}"
            }
            require(after.size() == evidenceAsset.sizeBytes &&
                actualSha256.equals(evidenceAsset.fingerprint, ignoreCase = true)
            ) {
                "Text-encoder evidence asset bytes differ from its profile evidence pin."
            }
        }
    } catch (error: IOException) {
        throw IllegalArgumentException(
            "Text-encoder evidence asset is missing or cannot be read.",
            error
        )
    }
}

/** Converts raw filesystem failures into the product error returned by UI and Local API flows. */
internal fun requireNativeMultilingualTextEncoderEvidenceAsset(
    bundleRoot: File,
    profile: ImageExecutionProfile
) {
    try {
        verifyNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
    } catch (error: Exception) {
        throw LocalImageProductContractException(
            code = "invalid_image_prompt_language_evidence",
            message = "原生中文文本编码器证据文件不完整或校验失败，尚未启动图片生成。"
        )
    }
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

internal fun resolveStableDiffusionCompatibilityPromptComponents(
    root: File,
    primary: File
): List<File> {
    val modelExtensions = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "sft")
    val selected = linkedMapOf<String, File>()
    root.walkTopDown()
        .filter { file -> file.isFile && file.extension.lowercase() in modelExtensions }
        .map(File::getCanonicalFile)
        .sortedWith { left, right ->
            compareUtf8Unsigned(left.invariantSeparatorsPath, right.invariantSeparatorsPath)
        }
        .forEach { file ->
            if (file == primary) return@forEach
            val lower = file.invariantSeparatorsPath.lowercase()
            when {
                "high" in lower && "noise" in lower && "diffusion" in lower ->
                    selected["high_noise"] = file
                "clip_vision" in lower || "clip-vision" in lower || "vision_h" in lower -> Unit
                "clip_g" in lower || "clip-g" in lower -> selected["clip_g"] = file
                "clip_l" in lower || "clip-l" in lower -> selected["clip_l"] = file
                "t5xxl" in lower || "umt5" in lower || "t5-xxl" in lower ->
                    selected["t5xxl"] = file
                "vae" in lower || lower.endsWith("ae.sft") ||
                    lower.endsWith("ae.safetensors") || lower.endsWith("_ae.safetensors") ||
                    lower.endsWith("-ae.safetensors") || lower.endsWith("_ae.gguf") ||
                    lower.endsWith("-ae.gguf") || "/ae." in lower -> Unit
                "embeddings" in lower && "connector" in lower ->
                    selected["embeddings_connectors"] = file
                "controlnet" in lower || "control_net" in lower -> Unit
                "llm_vision" in lower || "llm-vision" in lower ->
                    selected["llm_vision"] = file
                "qwen" in lower || "mistral" in lower || "gemma" in lower || "llm" in lower ->
                    selected["llm"] = file
                "diffusion" in lower || "unet" in lower || "dit" in lower -> Unit
            }
        }
    return selected.values.toList()
}

private fun LocalImageRuntime.toTextualInversionRuntime(): TextualInversionRuntime = when (this) {
    LocalImageRuntime.QNN_HTP -> TextualInversionRuntime.QNN_HTP
    LocalImageRuntime.MNN_DIFFUSION -> TextualInversionRuntime.MNN_DIFFUSION
    LocalImageRuntime.STABLE_DIFFUSION_CPP -> TextualInversionRuntime.STABLE_DIFFUSION_CPP
    LocalImageRuntime.ONNX_RUNTIME,
    LocalImageRuntime.CUSTOM -> error("Textual inversion is not executable on runtime $this.")
}

/**
 * Resolves only a complete, versioned manifest profile before readiness
 * inspects required paths. A narrowly migrated catalog profile must not be
 * rejected because an older persisted revision named sidecars that were never
 * present in the extracted archive. This path intentionally reuses the
 * persisted fingerprint and never hashes a large graph during UI readiness;
 * real generation rebinds the profile to LocalImageModelRecord.sha256.
 *
 * Graph-only legacy manifests are left to the lightweight manifest parser.
 * A versioned profile, however, is owned by this app and is therefore parsed
 * and validated fail-closed instead of being downgraded to legacy discovery.
 */
internal fun resolveEffectiveLocalImageManifestProfile(
    manifestJson: JSONObject,
    resolver: LocalImageManifestProfileResolver = ImageExecutionProfileResolver::resolve
): ImageExecutionProfile? {
    val rawProfile = manifestJson.opt("executionProfile") as? JSONObject ?: return null
    val isVersionedProfile = listOf(
        "schemaVersion",
        "profileId",
        "profileRevision",
        "modelFingerprint"
    ).any(rawProfile::has)
    if (!isVersionedProfile) return null

    val persisted = requireNotNull(ImageExecutionProfileJson.parseManifest(manifestJson))
    // Readiness must stay O(manifest + directory entries). The persisted
    // fingerprint is the migration identity here; real generation resolves
    // the LocalImageModelRecord fingerprint again and rejects any mismatch
    // before native execution.
    val installedFingerprint = persisted.modelFingerprint.trim().lowercase()
    val recommendationId = manifestJson
        .optString("recommendationId")
        .takeIf(String::isNotBlank)
        ?: manifestJson.optString("id").takeIf(String::isNotBlank)
    return resolver(
        ImageExecutionProfileResolverInput(
            modelFingerprint = installedFingerprint,
            runtime = persisted.runtime,
            family = persisted.family,
            recommendationId = recommendationId,
            recommendationRevision = manifestJson
                .optString("revision")
                .takeIf(String::isNotBlank),
            manifestProfile = persisted,
            recommendationEvidence = localImageManifestRecommendationEvidence(manifestJson)
        )
    ).profile
}

/**
 * The configured sampler step count remains unchanged for img2img/inpaint, while native starts
 * later in that schedule according to strength. Bind the strict evidence to the actually visited
 * timetable instead of pretending every configured step executed.
 */
internal fun ImageExecutionProfileResolution.withProductDenoisingSchedule(
    options: LocalImageGenerationOptions
): ImageExecutionProfileResolution {
    if (profile.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP ||
        options.taskMode !in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)
    ) {
        return this
    }
    val strength = options.strength ?: 1.0
    require(strength.isFinite() && strength >= 0.0 && strength <= 1.0) {
        "Image strength must be finite and in [0, 1]."
    }
    if (strength >= 1.0) return this
    val resolved = layers.resolved
    val timetableCount = options.ultraFix?.inversionSteps
        ?: localImageDenoisingTailStepCount(resolved.steps, strength)
    val branchCount = if (resolved.useCfg) 2 else 1
    return copy(
        layers = layers.copy(
            resolved = resolved.copy(
                timetableCount = timetableCount,
                unetExecutionCount = timetableCount * branchCount
            )
        )
    )
}

/** Reads standard package sidecars plus JSON config sidecars declared by a full manifest profile. */
internal fun parseLocalImageExecutionProfileSidecars(
    bundleRoot: File,
    manifestProfile: ImageExecutionProfile?
): ImageProfileSidecar? {
    val graph = manifestProfile?.graph
    val behaviorSidecars = buildList {
        add(DEFAULT_IMAGE_BEHAVIOR_SIDECAR)
        graph?.configSidecars
            .orEmpty()
            .filter(::isJsonImageBehaviorSidecar)
            .forEach(::add)
    }
    return ImageExecutionProfileJson.parseSidecars(
        bundleRoot = bundleRoot,
        schedulerRelativePath = graph?.schedulerSidecar ?: DEFAULT_IMAGE_SCHEDULER_SIDECAR,
        tokenizerRelativePath = graph?.tokenizerSidecar ?: DEFAULT_IMAGE_TOKENIZER_SIDECAR,
        behaviorRelativePaths = behaviorSidecars
    )
}

private fun isJsonImageBehaviorSidecar(relativePath: String): Boolean {
    val fileName = relativePath
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .lowercase()
    return fileName.endsWith(".json") && fileName !in NON_BEHAVIOR_RUNTIME_JSON_FILES
}

/**
 * Collects package identity without consulting hardware. Exact catalog
 * matching may select a richer execution profile; unknown evidence remains a
 * normal generic-compatible import.
 */
internal fun localImageRecommendationEvidence(
    model: LocalImageModelRecord,
    bundleRoot: File?,
    manifestJson: JSONObject?
): ImageRecommendationEvidence {
    val manifestEvidence = manifestJson?.let(::localImageManifestRecommendationEvidence)
        ?: ImageRecommendationEvidence()
    val modelArtifactPaths = buildList {
        add(model.displayName)
        add(model.fileName)
        add(model.path)
        model.bundleRoot?.takeIf(String::isNotBlank)?.let(::add)
        bundleRoot?.absolutePath?.takeIf(String::isNotBlank)?.let(::add)
    }
    return ImageRecommendationEvidence(
        aliases = (listOf(model.id) + manifestEvidence.aliases).distinct(),
        sourceRepositories = (
            listOf(model.source).filter(String::isNotBlank) +
                manifestEvidence.sourceRepositories
            ).distinct(),
        artifactPaths = (modelArtifactPaths + manifestEvidence.artifactPaths).distinct()
    )
}

private fun localImageManifestRecommendationEvidence(
    manifestJson: JSONObject
): ImageRecommendationEvidence {
    val aliases = buildList {
        manifestJson.optString("recommendationId").takeIf(String::isNotBlank)?.let(::add)
        manifestJson.optString("id").takeIf(String::isNotBlank)?.let(::add)
    }
    val sourceRepositories = buildList {
        manifestJson.optString("sourceRepo").takeIf(String::isNotBlank)?.let(::add)
        val components = manifestJson.optJSONArray("components")
        if (components != null) {
            for (index in 0 until components.length()) {
                val component = components.optJSONObject(index) ?: continue
                val role = component.optString("role").trim().uppercase()
                if (role in PRIMARY_IMAGE_COMPONENT_ROLES) {
                    component.optString("sourceRepo").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
    val artifactPaths = buildList {
        listOf("title", "recommendedFileName", "primary", "primaryFile").forEach { field ->
            manifestJson.optString(field).takeIf(String::isNotBlank)?.let(::add)
        }
        val components = manifestJson.optJSONArray("components")
        if (components != null) {
            for (index in 0 until components.length()) {
                val component = components.optJSONObject(index) ?: continue
                listOf("path", "fileName", "sourcePath").forEach { field ->
                    component.optString(field).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
    return ImageRecommendationEvidence(
        aliases = aliases.distinct(),
        sourceRepositories = sourceRepositories.distinct(),
        artifactPaths = artifactPaths.distinct()
    )
}

/** A quality-bound MNN SD1.5 profile can only use the strict direct runner. */
internal fun resolveMnnDiffusionProfileRunner(
    profile: ImageExecutionProfile,
    requestedRunner: String?
): String {
    val resolved = resolveMnnDiffusionRunner(profile.family, requestedRunner)
    if (
        profile.runtime == LocalImageRuntime.MNN_DIFFUSION &&
        profile.profileId == "mnn.sd15.official.512"
    ) {
        require(resolved == "direct") {
            "The MNN SD1.5 quality profile requires runner=direct; module cannot satisfy its execution contract."
        }
        return "direct"
    }
    return resolved
}

private const val DEFAULT_IMAGE_SCHEDULER_SIDECAR = "scheduler/scheduler_config.json"
private const val DEFAULT_IMAGE_TOKENIZER_SIDECAR = "tokenizer/tokenizer_config.json"
private const val DEFAULT_IMAGE_BEHAVIOR_SIDECAR = "config.json"

private val NON_BEHAVIOR_RUNTIME_JSON_FILES = setOf(
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
    "added_tokens.json",
    "vocab.json",
    "scheduler_config.json"
)

private val PRIMARY_IMAGE_COMPONENT_ROLES = setOf("DIFFUSION", "MODEL", "UNET", "TRANSFORMER")

internal fun imageSchedulerAlgorithmFromProductName(value: String): ImageSchedulerAlgorithm =
    when (value.trim().lowercase().replace('-', '_').replace(' ', '_')) {
        "euler", "euler_discrete" -> ImageSchedulerAlgorithm.EULER
        "euler_a", "euler_ancestral", "euler_ancestral_discrete" -> ImageSchedulerAlgorithm.EULER_A
        "ddim", "ddim_trailing" -> ImageSchedulerAlgorithm.DDIM
        "pndm", "pndm_plms", "plms" -> ImageSchedulerAlgorithm.PNDM_PLMS
        "dpm++2m", "dpmpp_2m", "dpm_plus_plus_2m" -> ImageSchedulerAlgorithm.DPMPP_2M
        "lcm" -> ImageSchedulerAlgorithm.LCM
        "flow", "flow_match", "flowmatch" -> ImageSchedulerAlgorithm.FLOW_MATCH
        else -> throw IllegalArgumentException("Unsupported image scheduler: $value")
    }

internal fun imageSchedulerProductName(value: ImageSchedulerAlgorithm): String = when (value) {
    ImageSchedulerAlgorithm.EULER -> "euler"
    ImageSchedulerAlgorithm.EULER_A -> "euler_a"
    ImageSchedulerAlgorithm.DDIM -> "ddim"
    ImageSchedulerAlgorithm.PNDM_PLMS -> "pndm"
    ImageSchedulerAlgorithm.DPMPP_2M -> "dpmpp_2m"
    ImageSchedulerAlgorithm.LCM -> "lcm"
    ImageSchedulerAlgorithm.FLOW_MATCH -> "flow_match"
}

private fun findImageManifestFile(root: File): File? {
    val direct = File(root, "manifest.json")
    if (direct.isFile) return direct
    return root.walkTopDown()
        .maxDepth(4)
        .firstOrNull { file -> file.isFile && file.name.equals("manifest.json", ignoreCase = true) }
}

private fun discoverLocalImageExecutionCapabilities(
    root: File,
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): ImageCapabilityDiscovery? {
    if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION) return null
    val artifactSet = root.selectImageCapabilityArtifactSet(runtime, family) ?: return null
    val artifactDirectory = artifactSet.directory
    val tokenizerJson = artifactDirectory.findDirectExecutionArtifact("tokenizer.json")
    val tokenEmbedding = artifactDirectory.findDirectExecutionArtifact("token_emb.bin")
    val textEncoder = artifactDirectory.findDirectExecutionArtifact(*artifactSet.textEncoderNames)
    val unet = artifactDirectory.findDirectExecutionArtifact("unet.bin", "unet.mnn")
    val vae = artifactDirectory.findDirectExecutionArtifact(
        "vae.bin",
        "vae_decoder.bin",
        "vae_decoder.mnn",
        "vae.mnn"
    )
    val vaeEncoder = artifactDirectory.findDirectExecutionArtifact(
        "vae_encoder.bin",
        "vae_encoder.mnn"
    )
    if (tokenizerJson == null && textEncoder == null && unet == null && vae == null) return null
    val maxLength = 77
    val padId = 49_407
    val tokenizer = ImageTokenizerContract(
        backend = if (tokenizerJson != null) ImageTokenizerBackend.TOKENIZERS_CPP else ImageTokenizerBackend.MNN_MTOK,
        assets = tokenizerJson?.let { file ->
            listOf(ImageProfileAsset(file.relativeTo(root).invariantSeparatorsPath, file.sha256ForProfile()))
        }.orEmpty(),
        bosId = 49_406,
        eosId = 49_407,
        padId = padId,
        maxLength = maxLength,
        clip1PadRule = ImageClipPadRule.EOS,
        clip2PadRule = if (family == LocalImageModelFamily.SDXL) ImageClipPadRule.ZERO else null,
        supportsPromptWeighting = tokenizerJson != null,
        separateNegativePrompt = true
    )
    val width = when (family) {
        LocalImageModelFamily.SD21 -> 1_024
        LocalImageModelFamily.SDXL -> 2_048
        else -> 768
    }
    val textEncoderName = textEncoder?.name?.lowercase()
    val qnnGraphInternalVae = runtime == LocalImageRuntime.QNN_HTP &&
        textEncoderName == "text_encoder.bin" &&
        vae?.name.equals("vae.bin", ignoreCase = true)
    val mnnGraphInternalConditioning = runtime == LocalImageRuntime.MNN_DIFFUSION &&
        textEncoderName == "text_encoder.mnn"
    val tokenTableDataType = when (tokenEmbedding?.length()) {
        75_890_688L -> ImageEmbeddingDiskDataType.FP16
        151_781_376L -> ImageEmbeddingDiskDataType.FP32
        else -> null
    }
    val conditioningDataType = if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        resolveMnnConditioningDiskDataType(
            graphInternal = mnnGraphInternalConditioning,
            tokenEmbeddingByteSize = tokenEmbedding?.length()
        )
    } else {
        tokenTableDataType ?: if (qnnGraphInternalVae) {
            ImageEmbeddingDiskDataType.GRAPH_INTERNAL
        } else {
            ImageEmbeddingDiskDataType.RUNTIME_NATIVE
        }
    }
    val conditioningConversion = when (conditioningDataType) {
        ImageEmbeddingDiskDataType.FP16 -> ImageEmbeddingConversionStrategy.NONE
        ImageEmbeddingDiskDataType.FP32 -> ImageEmbeddingConversionStrategy.FP32_TO_FP16_STREAMING
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL -> ImageEmbeddingConversionStrategy.GRAPH_EXECUTION
        else -> ImageEmbeddingConversionStrategy.RUNTIME_NATIVE
    }
    return ImageCapabilityDiscovery(
        family = family,
        tokenizer = tokenizer,
        conditioning = ImageConditioningContract(
            diskDataType = conditioningDataType,
            exactByteSize = tokenEmbedding?.length()?.takeIf {
                conditioningDataType == ImageEmbeddingDiskDataType.FP16 ||
                    conditioningDataType == ImageEmbeddingDiskDataType.FP32
            },
            textEncoderInputShape = listOf(1, maxLength),
            textEncoderOutputShapes = if (family == LocalImageModelFamily.SDXL) {
                listOf(listOf(1, maxLength, 768), listOf(1, maxLength, 1_280))
            } else {
                listOf(listOf(1, maxLength, width))
            },
            conversionStrategy = conditioningConversion,
            dualEncoder = family == LocalImageModelFamily.SDXL,
            pooledOutput = family == LocalImageModelFamily.SDXL,
            concatenationOrder = if (family == LocalImageModelFamily.SDXL) {
                listOf("clip1_hidden", "clip2_hidden", "clip2_pooled")
            } else {
                listOf("negative", "positive")
            }
        ),
        vae = ImageVaeContract(
            scalingLocation = if (qnnGraphInternalVae) {
                ImageVaeScalingLocation.GRAPH_INTERNAL
            } else {
                ImageVaeScalingLocation.HOST_BEFORE_GRAPH
            },
            scalingFactor = if (family == LocalImageModelFamily.SDXL) 0.13025 else 0.18215,
            inputShape = if (family == LocalImageModelFamily.SDXL) listOf(1, 4, 128, 128) else listOf(1, 4, 64, 64),
            outputShape = if (family == LocalImageModelFamily.SDXL) listOf(1, 3, 1024, 1024) else listOf(1, 3, 512, 512),
            inputLayout = ImageTensorLayout.NCHW,
            outputLayout = ImageTensorLayout.NCHW,
            outputRange = ImagePixelRange.NEGATIVE_ONE_TO_ONE,
            channelOrder = ImageChannelOrder.RGB
        ),
        graph = ImageGraphContract(
            textEncoder = textEncoder?.toGraphArtifact(root),
            unet = unet?.toGraphArtifact(root),
            vae = vae?.toGraphArtifact(root),
            vaeEncoder = vaeEncoder?.toGraphArtifact(root),
            schedulerSidecar = File(artifactDirectory, "scheduler/scheduler_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            tokenizerSidecar = File(artifactDirectory, "tokenizer/tokenizer_config.json")
                .takeIf(File::isFile)
                ?.relativeTo(root)
                ?.invariantSeparatorsPath,
            workerStrategy = when {
                runtime == LocalImageRuntime.QNN_HTP &&
                    family == LocalImageModelFamily.SDXL &&
                    textEncoderName == "clip.mnn" -> ImageWorkerStrategy.SPLIT_UNET_VAE
                runtime == LocalImageRuntime.QNN_HTP &&
                    textEncoderName == "clip_v2.mnn" -> ImageWorkerStrategy.SHARED_UNET_VAE
                qnnGraphInternalVae -> ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
                else -> ImageWorkerStrategy.IN_PROCESS
            }
        )
    )
}

private data class ImageCapabilityArtifactSet(
    val directory: File,
    val textEncoderNames: Array<out String>
)

/**
 * Capability discovery must describe one physical graph bundle. Walking the root independently
 * for every component can splice unrelated or partially extracted directories into a profile
 * that no native process could load.
 */
private fun File.selectImageCapabilityArtifactSet(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): ImageCapabilityArtifactSet? {
    val completeClipBundle = when {
        runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL ->
            findCompleteExecutionArtifactDirectory(
                requiredNames = arrayOf(
                    "clip.mnn",
                    "clip_2.mnn",
                    "clip_2.mnn.weight",
                    "tokenizer.json",
                    "token_emb.bin",
                    "token_emb_2.bin",
                    "pos_emb.bin",
                    "pos_emb_2.bin"
                ),
                additionalRequirement = { directory ->
                    directory.hasCompleteDirectDiffusionGraph(runtime)
                }
            )?.let { directory ->
                ImageCapabilityArtifactSet(directory, arrayOf("clip.mnn"))
            }
        family in setOf(LocalImageModelFamily.SD15, LocalImageModelFamily.SD21) ->
            findCompleteExecutionArtifactDirectory(
                requiredNames = arrayOf(
                    "clip_v2.mnn",
                    "tokenizer.json",
                    "token_emb.bin",
                    "pos_emb.bin"
                ),
                additionalRequirement = { directory ->
                    directory.hasCompleteDirectDiffusionGraph(runtime)
                }
            )?.let { directory ->
                ImageCapabilityArtifactSet(directory, arrayOf("clip_v2.mnn"))
            }
        else -> null
    }
    if (completeClipBundle != null) return completeClipBundle

    val fallbackTextEncoderNames = if (runtime == LocalImageRuntime.QNN_HTP) {
        arrayOf("text_encoder.bin", "text_encoder.mnn")
    } else {
        arrayOf("text_encoder.mnn", "text_encoder.bin")
    }
    findExecutionArtifact(*fallbackTextEncoderNames)?.parentFile?.let { directory ->
        return ImageCapabilityArtifactSet(directory, fallbackTextEncoderNames)
    }
    val graphAnchor = findExecutionArtifact("unet.bin", "unet.mnn", "vae.bin", "vae_decoder.bin", "vae_decoder.mnn")
        ?: return null
    val graphDirectory = graphAnchor.parentFile ?: return null
    return ImageCapabilityArtifactSet(graphDirectory, fallbackTextEncoderNames)
}

private fun File.findCompleteExecutionArtifactDirectory(
    requiredNames: Array<out String>,
    additionalRequirement: (File) -> Boolean
): File? {
    val anchorName = requiredNames.first().lowercase()
    return walkTopDown()
        .filter { file -> file.isFile && file.name.lowercase() == anchorName }
        .mapNotNull(File::getParentFile)
        .distinctBy { directory -> runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath } }
        .sortedBy { directory -> runCatching { directory.relativeTo(this).invariantSeparatorsPath }.getOrElse { directory.path } }
        .firstOrNull { directory ->
            requiredNames.all { name -> directory.findDirectExecutionArtifact(name) != null } &&
                additionalRequirement(directory)
        }
}

private fun File.hasCompleteDirectDiffusionGraph(runtime: LocalImageRuntime): Boolean =
    when (runtime) {
        LocalImageRuntime.QNN_HTP ->
            findDirectExecutionArtifact("unet.bin") != null &&
                findDirectExecutionArtifact("vae_decoder.bin", "vae.bin") != null
        LocalImageRuntime.MNN_DIFFUSION ->
            findDirectExecutionArtifact("unet.mnn") != null &&
                findDirectExecutionArtifact("vae_decoder.mnn", "vae.mnn") != null
        else -> false
    }

private fun File.findDirectExecutionArtifact(vararg names: String): File? {
    val expected = names.map(String::lowercase)
    val filesByName = listFiles()
        ?.asSequence()
        ?.filter(File::isFile)
        ?.associateBy { file -> file.name.lowercase() }
        .orEmpty()
    return expected.firstNotNullOfOrNull(filesByName::get)
}

/** Resolves MNN conditioning storage without guessing from an unknown token-table size. */
internal fun resolveMnnConditioningDiskDataType(
    graphInternal: Boolean,
    tokenEmbeddingByteSize: Long?
): ImageEmbeddingDiskDataType {
    val tokenTableDataType = when (tokenEmbeddingByteSize) {
        null -> null
        75_890_688L -> ImageEmbeddingDiskDataType.FP16
        151_781_376L -> ImageEmbeddingDiskDataType.FP32
        else -> throw IllegalArgumentException(
            "PACKAGE_FORMAT_INVALID: token_emb.bin must be exactly 75890688 bytes (FP16) " +
                "or 151781376 bytes (FP32); found $tokenEmbeddingByteSize bytes."
        )
    }
    return if (graphInternal) {
        ImageEmbeddingDiskDataType.GRAPH_INTERNAL
    } else {
        tokenTableDataType ?: ImageEmbeddingDiskDataType.RUNTIME_NATIVE
    }
}

private fun File.toGraphArtifact(root: File): ImageGraphArtifactContract =
    ImageGraphArtifactContract(relativeTo(root).invariantSeparatorsPath)

private fun File.findExecutionArtifact(vararg names: String): File? {
    val expected = names.map(String::lowercase).toSet()
    return walkTopDown().firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
}

private fun modelExecutionFingerprint(
    model: LocalImageModelRecord,
    manifestProfile: ImageExecutionProfile?
): String {
    val stored = model.sha256.trim().lowercase()
    if (stored.matches(Regex("^[0-9a-f]{64}$"))) return stored
    // Older records did not persist a content SHA. Resolve their real content
    // identity at the execution boundary; trusting a manifest value as both
    // expected and actual would let a replaced/corrupt model self-attest.
    val manifestFingerprint = manifestProfile?.modelFingerprint
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
    val file = File(model.path)
    require(file.isFile && file.length() > 0L) {
        "Image model file is missing or empty: ${file.path}"
    }
    val actual = file.sha256ForProfile()
    require(manifestFingerprint == null || manifestFingerprint == actual) {
        "Image model content does not match the execution profile fingerprint."
    }
    return actual
}

internal fun File.sha256ForProfile(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
