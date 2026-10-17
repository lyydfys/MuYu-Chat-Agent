package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import java.io.File
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

private const val OWNED_IMAGE_BUNDLE_SCHEMA = "mca.image_engine.bundle.v1"
private val OWNED_IMAGE_BUNDLE_SCHEMA_PATTERN = Regex(
    "\\\"schema\\\"\\s*:\\s*\\\"mca\\.image_engine\\.bundle\\.v1\\\""
)

internal fun inspectLocalImageBundleManifestFromRoot(
    root: File,
    effectiveProfileResolver: LocalImageManifestProfileResolver =
        ImageExecutionProfileResolver::resolve
): LocalImageBundleManifestInspection {
    if (!root.isDirectory) return LocalImageBundleManifestInspection.Undeclared
    val manifestFile = root.findDescendantFile("manifest.json")
        ?: return LocalImageBundleManifestInspection.Undeclared
    val rawManifest = runCatching { manifestFile.readText(Charsets.UTF_8) }
        .getOrElse { return LocalImageBundleManifestInspection.Undeclared }
    val manifest = try {
        JSONObject(rawManifest)
    } catch (error: Throwable) {
        return if (OWNED_IMAGE_BUNDLE_SCHEMA_PATTERN.containsMatchIn(rawManifest)) {
            LocalImageBundleManifestInspection.Invalid(
                error.message?.takeIf(String::isNotBlank)
                    ?: "Image bundle manifest is malformed."
            )
        } else {
            LocalImageBundleManifestInspection.Undeclared
        }
    }
    val declaredSchema = (manifest.opt("schema") as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    if (declaredSchema != null && declaredSchema != OWNED_IMAGE_BUNDLE_SCHEMA) {
        return LocalImageBundleManifestInspection.Undeclared
    }
    val ownedSchema = declaredSchema == OWNED_IMAGE_BUNDLE_SCHEMA
    return try {
        LocalImageBundleManifestInspection.Ready(
            parseLocalImageBundleManifest(
                root = root,
                manifest = manifest,
                effectiveProfileResolver = effectiveProfileResolver,
                resolveVersionedProfile = ownedSchema
            )
        )
    } catch (error: Throwable) {
        if (ownedSchema) {
            LocalImageBundleManifestInspection.Invalid(
                error.message?.takeIf(String::isNotBlank)
                    ?: "Image bundle manifest is malformed."
            )
        } else {
            LocalImageBundleManifestInspection.Undeclared
        }
    }
}

internal fun localImageBundleManifestFromRoot(root: File): LocalImageBundleManifest? =
    when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared -> null
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
        is LocalImageBundleManifestInspection.Invalid ->
            throw IllegalArgumentException(inspection.message)
    }

private fun parseLocalImageBundleManifest(
    root: File,
    manifest: JSONObject,
    effectiveProfileResolver: LocalImageManifestProfileResolver,
    resolveVersionedProfile: Boolean
): LocalImageBundleManifest {
    val runtime = manifest.optString("runtime").takeIf { it.isNotBlank() }?.let(LocalImageRuntime::from)
    val family = manifest.optString("family").takeIf { it.isNotBlank() }?.let(LocalImageModelFamily::from)
    val imageSize = manifest.manifestImageSize()
    val smoke = manifest.strictOptionalObject("smoke")
        ?: manifest.strictOptionalObject("smokeSpec")
    val qnnSmokeSpecs = manifest.strictOptionalArray("smokes")
        .takeIf { it != null && it.length() > 0 }
        ?.toQnnSmokeSpecs()
        ?: manifest.strictOptionalArray("smokeSpecs")
            .takeIf { it != null && it.length() > 0 }
            ?.toQnnSmokeSpecs()
        ?: smoke?.let { listOf(QnnSmokeSpec.fromSmokeJson(it)) }
        ?: emptyList()
    val components = manifest.strictOptionalArray("components")
    val componentContracts = components?.toLocalImageBundleComponentContracts().orEmpty()
    val executionProfileDeclared = manifest.has("executionProfile")
    val executionProfile = manifest.strictOptionalObject("executionProfile")
    val effectiveExecutionProfile = executionProfile?.takeIf { resolveVersionedProfile }?.let {
        resolveEffectiveLocalImageManifestProfile(manifest, effectiveProfileResolver)
    }
    val executionProfilePaths = effectiveExecutionProfile?.requiredExecutionProfilePaths()
        ?: executionProfile?.requiredExecutionProfilePaths()
        ?: ParsedExecutionProfilePaths.Empty
    val executionProfileQnnSdk = if (effectiveExecutionProfile != null) {
        effectiveExecutionProfile.graph.qnnSdk
    } else {
        executionProfile
            ?.strictOptionalObject("graph")
            ?.strictOptionalNonBlankString("qnnSdk")
    }
    val requiredRuntimeProfile = manifest.strictOptionalObject("requiredRuntimeProfile")?.let { profile ->
        val qnnSdk = profile.strictRequiredString("qnnSdk")
        val htpArch = profile.strictRequiredPositiveInt("htpArch")
        LocalImageQnnRuntimeProfile(
            qnnSdk = qnnSdk,
            htpArch = htpArch,
            completeBundleRuntime = profile.strictOptionalBoolean(
                "completeBundleRuntime",
                default = true
            )
        )
    }
    require(
        requiredRuntimeProfile == null ||
            executionProfileQnnSdk == null ||
            requiredRuntimeProfile.qnnSdk == executionProfileQnnSdk
    ) {
        "Image bundle requiredRuntimeProfile.qnnSdk (${requiredRuntimeProfile?.qnnSdk}) " +
            "does not match executionProfile.graph.qnnSdk ($executionProfileQnnSdk)."
    }
    val primaryPath = components?.firstComponentPath("DIFFUSION")
        ?: components?.firstComponentPath("MODEL")
        ?: components?.firstComponentPath("UNET")
        ?: components?.firstComponentPath("TRANSFORMER")
        ?: manifest.optString("primary").takeIf { it.isNotBlank() }
        ?: manifest.optString("primaryFile").takeIf { it.isNotBlank() }
    val primaryFile = primaryPath
        ?.let { root.safeDescendantOrNull(it) }
        ?.takeIf { it.isFile }
        ?: executionProfilePaths.graphPaths["unet"]
            ?.let { root.safeDescendantOrNull(it) }
            ?.takeIf { it.isFile && it.length() > 0L }
    return LocalImageBundleManifest(
        id = manifest.optString("id").takeIf { it.isNotBlank() },
        recommendationId = manifest.optString("recommendationId").takeIf { it.isNotBlank() },
        displayName = manifest.optString("title").takeIf { it.isNotBlank() }
            ?: manifest.optString("displayName").takeIf { it.isNotBlank() }
            ?: manifest.optString("name").takeIf { it.isNotBlank() },
        task = manifest.optString("task")
            .trim()
            .uppercase()
            .takeIf(String::isNotBlank),
        runtime = runtime,
        family = family,
        imageSize = imageSize,
        minDeviceTier = manifest.optImageEngineMinDeviceTier(),
        requiresQnnRuntime = manifest.strictOptionalBoolean(
            "requiresQnnRuntime",
            default = runtime == LocalImageRuntime.QNN_HTP
        ),
        requiredRuntimeProfile = requiredRuntimeProfile,
        requiresSmokeTest = manifest.strictOptionalBoolean("requiresSmokeTest", default = true),
        smokeWidth = smoke?.optInt("width", 0) ?: 0,
        smokeHeight = smoke?.optInt("height", 0) ?: 0,
        smokeSteps = smoke?.optInt("steps", 0) ?: 0,
        smokeTimeoutSeconds = smoke?.optInt("timeoutSeconds", 0) ?: 0,
        qnnSmokeSpec = qnnSmokeSpecs.firstOrNull() ?: QnnSmokeSpec.Empty,
        qnnSmokeSpecs = qnnSmokeSpecs,
        primaryFile = primaryFile,
        declaredPrimaryPath = primaryPath ?: executionProfilePaths.graphPaths["unet"],
        components = componentContracts,
        executionProfileRequiredPaths = executionProfilePaths.requiredPaths,
        executionProfileGraphPaths = executionProfilePaths.graphPaths,
        executionProfileQnnSdk = executionProfileQnnSdk,
        executionProfileDeclared = executionProfileDeclared,
        componentCount = components?.length() ?: 0
    )
}

/**
 * Public SDXL archives commonly keep all prompt-conditioning assets in a
 * chipset-neutral nested directory. The manifest path is authoritative; a
 * bounded unique-directory fallback keeps older imported bundles usable.
 */
