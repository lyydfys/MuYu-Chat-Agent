package com.muyuchat.mca

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONObject
import com.muyuchat.core.engine.compareUtf8Unsigned

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
        // Debug and legacy records may carry the catalog identity on the
        // model record while their bundle has only a minimal manifest.
        ?: model.recommendationId?.trim()?.takeIf(String::isNotBlank)
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
    val executionIdentity = modelExecutionIdentity(model, manifestProfile)
    val resolverInput = ImageExecutionProfileResolverInput(
        modelFingerprint = executionIdentity.fingerprint,
        modelFingerprintScope = executionIdentity.scope,
        bundleComponentEvidence = executionIdentity.components,
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
    // Catalog revision migration can replace a persisted graph contract with root-relative
    // catalog paths. Rebind after resolution too: publisher ZIPs keep graphs in a wrapper
    // directory, and an upgraded installed bundle must not suddenly look incomplete.
    val installedResolution = canonicalRoot?.let { root ->
        resolution.copy(profile = resolution.profile.rebindInstalledArtifactPaths(root))
    } ?: resolution
    val scheduled = installedResolution.withProductDenoisingSchedule(options)
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

private fun findImageManifestFile(root: File): File? {
    val direct = File(root, "manifest.json")
    if (direct.isFile) return direct
    return root.walkTopDown()
        .maxDepth(4)
        .firstOrNull { file -> file.isFile && file.name.equals("manifest.json", ignoreCase = true) }
}
