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

internal fun LocalImageBundleManifest.resolveRuntimeComponentContract(
    root: File
): LocalImageRuntimeComponentContract {
    fun exists(relativePath: String): Boolean = root.safeDescendantOrNull(relativePath)
        ?.let { file -> file.isFile && file.length() > 0L }
        ?: false
    val smokePaths = qnnSmokeSpecs
        .asSequence()
        .map(QnnSmokeSpec::contextBinary)
        .filter(String::isNotBlank)
        .toList()
    val requiredNonArchiveComponents = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filterNot { component -> component.relativePath.isArchiveContainerPath() }
        .map { component ->
            resolveInstalledImageBundleArtifactPath(root, component.relativePath)
        }
        .toList()
    val requiredArchives = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filter { component -> component.relativePath.isArchiveContainerPath() }
        .map(LocalImageBundleComponentContract::relativePath)
        .toList()
    val communityClipAssets = listOf(
        "clip_v2.mnn",
        "tokenizer.json",
        "token_emb.bin",
        "pos_emb.bin"
    )
    val communityClipLayoutAttempted = communityClipAssets.any(::exists)
    val inferredLegacyAssets = if (!executionProfileDeclared && communityClipLayoutAttempted) {
        communityClipAssets
    } else {
        emptyList()
    }
    val reboundExecutionProfileRequiredPaths = executionProfileRequiredPaths.map { path ->
        resolveInstalledImageBundleArtifactPath(root, path)
    }
    val reboundSmokePaths = smokePaths.map { path ->
        resolveInstalledImageBundleArtifactPath(root, path)
    }
    val requiredRuntimePaths = (
        reboundExecutionProfileRequiredPaths + reboundSmokePaths +
            requiredNonArchiveComponents + inferredLegacyAssets
        ).distinct()
    try {
        requiredRuntimePaths.forEachIndexed { index, path ->
            requireSafeImageRuntimePath(path, "runtimeComponents[$index]")
        }
        requiredArchives.forEachIndexed { index, path ->
            requireSafeImageRuntimePath(path, "archiveComponents[$index]")
        }
    } catch (error: IllegalArgumentException) {
        return LocalImageRuntimeComponentContract.Invalid(
            error.message ?: "Image runtime component path is invalid."
        )
    }

    val reboundGraphPaths = executionProfileGraphPaths.mapValues { (_, path) ->
        resolveInstalledImageBundleArtifactPath(root, path)
    }
    val graphTextEncoder = reboundGraphPaths["textEncoder"]
    val graphComplete = listOf("textEncoder", "unet", "vae")
        .all(reboundGraphPaths::containsKey) &&
        (task != "CONTROL_IMAGE" || reboundGraphPaths.containsKey("controlNet"))
    val declaredCommunityClipComplete = graphTextEncoder
        ?.substringAfterLast('/')
        ?.equals("clip_v2.mnn", ignoreCase = true) != true ||
        listOf("tokenizer.json", "token_emb.bin", "pos_emb.bin")
            .all(reboundExecutionProfileRequiredPaths::contains)
    val profileContractComplete = executionProfileDeclared &&
        graphComplete && declaredCommunityClipComplete

    val smokeNames = smokePaths.map { it.substringAfterLast('/').lowercase() }
    val smokeHasUnet = smokeNames.any { name ->
        "unet" in name || "diffusion" in name || "transformer" in name
    }
    val smokeHasVae = smokeNames.any { name -> "vae" in name || "decoder" in name }
    val smokeHasQnnTextEncoder = smokeNames.any { name ->
        "text_encoder" in name || "clip" in name
    }
    val legacySmokeContractComplete = !executionProfileDeclared &&
        smokeHasUnet && smokeHasVae &&
        (smokeHasQnnTextEncoder || communityClipLayoutAttempted)

    val componentRoles = components
        .asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .filterNot { component -> component.relativePath.isArchiveContainerPath() }
        .map(LocalImageBundleComponentContract::role)
        .toSet()
    val directComponentContractComplete =
        componentRoles.any { role -> role in setOf("DIFFUSION", "UNET", "TRANSFORMER") } &&
            componentRoles.any { role -> role in setOf("VAE", "VAE_DECODER") } &&
            componentRoles.any { role -> role in setOf("TEXT_ENCODER", "TOKENIZER") }
    val expandedContractComplete = profileContractComplete ||
        legacySmokeContractComplete || directComponentContractComplete

    if (executionProfileDeclared && !profileContractComplete) {
        return LocalImageRuntimeComponentContract.Invalid(
            "Image executionProfile does not declare a complete text encoder, UNet, VAE" +
                if (task == "CONTROL_IMAGE") ", and ControlNet runtime graph contract." else " runtime graph contract."
        )
    }
    val missingRuntimePaths = requiredRuntimePaths.filterNot(::exists)
    val missingArchives = requiredArchives.filterNot(::exists)
    if (missingArchives.isNotEmpty() &&
        (!expandedContractComplete || missingRuntimePaths.isNotEmpty())
    ) {
        val missingExtractedRuntime = missingRuntimePaths
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                prefix = " Missing or empty extracted runtime components: ",
                postfix = "."
            )
            .orEmpty()
        return LocalImageRuntimeComponentContract.Invalid(
            "Required archive container is absent before a complete extracted runtime contract was proven: " +
                missingArchives.joinToString(", ") + "." + missingExtractedRuntime
        )
    }
    if (expandedContractComplete) {
        return LocalImageRuntimeComponentContract.Ready(
            requiredPaths = requiredRuntimePaths,
            missingPaths = missingRuntimePaths
        )
    }
    return LocalImageRuntimeComponentContract.UndeclaredLegacy
}

internal fun LocalImageBundleManifest.missingRequiredComponentPaths(root: File): List<String> =
    when (val contract = resolveRuntimeComponentContract(root)) {
        LocalImageRuntimeComponentContract.UndeclaredLegacy -> emptyList()
        is LocalImageRuntimeComponentContract.Ready -> contract.missingPaths
        is LocalImageRuntimeComponentContract.Invalid -> listOf("<invalid-contract: ${contract.message}>")
    }

internal fun LocalImageBundleManifest.hasExactRuntimeComponentContract(root: File): Boolean =
    resolveRuntimeComponentContract(root) is LocalImageRuntimeComponentContract.Ready

private fun String.isArchiveContainerPath(): Boolean {
    val lower = trim().lowercase()
    return lower.endsWith(".zip") || lower.endsWith(".7z") ||
        lower.endsWith(".tar") || lower.endsWith(".tgz") ||
        lower.endsWith(".tar.gz")
}

private data class ParsedExecutionProfilePaths(
    val graphPaths: Map<String, String>,
    val requiredPaths: List<String>
) {
    companion object {
        val Empty = ParsedExecutionProfilePaths(emptyMap(), emptyList())
    }
}

// Provider binding for the effective strings handed to the selected runtime. Runtime-specific
// verifiers independently establish native graph and conditioning execution.
internal data class NativePromptEncodingEvidence(
    val promptWeightingApplied: Boolean,
    val positiveWeightedTokenCount: Int,
    val negativeWeightedTokenCount: Int,
    val promptWeightFingerprint: String,
    val nativePromptExecutionSha256: String,
    val nativePromptBindingStage: String
)

internal fun requireNativePromptEncodingEvidence(
    source: JSONObject,
    prompt: String,
    negativePrompt: String?,
    requireNoAppliedWeights: Boolean = false
): NativePromptEncodingEvidence {
    fun strictBoolean(field: String): Boolean =
        (source.opt(field) as? Boolean)
            ?: error("Native prompt encoding evidence field $field must be boolean.")

    fun strictNonNegativeInt(field: String): Int {
        val number = source.opt(field) as? Number
            ?: error("Native prompt encoding evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() &&
            value in 0L..Int.MAX_VALUE.toLong()
        ) { "Native prompt encoding evidence field $field must be a non-negative integer." }
        return value.toInt()
    }

    fun strictSha256(field: String): String {
        val value = source.opt(field) as? String
            ?: error("Native prompt encoding evidence field $field must be a string.")
        require(Regex("[0-9a-f]{64}").matches(value)) {
            "Native prompt encoding evidence field $field must be a lowercase SHA-256 value."
        }
        return value
    }

    val applied = strictBoolean("promptWeightingApplied")
    val positiveCount = strictNonNegativeInt("positiveWeightedTokenCount")
    val negativeCount = strictNonNegativeInt("negativeWeightedTokenCount")
    require(applied == (positiveCount.toLong() + negativeCount.toLong() > 0L)) {
        "Native prompt weighting flag conflicts with its token counts."
    }
    if (requireNoAppliedWeights) {
        require(!applied && positiveCount == 0 && negativeCount == 0) {
            "The non-weighted QNN token path cannot accept applied prompt weights."
        }
    }
    val promptSha256 = strictSha256("nativePromptExecutionSha256")
    require(promptSha256 == imagePromptExecutionSha256(prompt, negativePrompt)) {
        "Native prompt encoding evidence does not match the effective positive and negative prompts."
    }
    val stage = source.opt("nativePromptBindingStage") as? String
        ?: error("Native prompt encoding evidence field nativePromptBindingStage must be a string.")
    require(stage == "conditioning_encoded") {
        "Native prompt encoding evidence must be published at conditioning_encoded."
    }
    return NativePromptEncodingEvidence(
        promptWeightingApplied = applied,
        positiveWeightedTokenCount = positiveCount,
        negativeWeightedTokenCount = negativeCount,
        promptWeightFingerprint = strictSha256("promptWeightFingerprint"),
        nativePromptExecutionSha256 = promptSha256,
        nativePromptBindingStage = stage
    )
}

internal fun JSONObject.putPromptExecutionBinding(
    profile: ImageExecutionProfile,
    prompt: String,
    negativePrompt: String?
): JSONObject {
    val expectedPromptSha256 = imagePromptExecutionSha256(prompt, negativePrompt)
    val nativeEffective = optJSONObject("nativeEffective")
        ?: error("Native image execution metadata is missing nativeEffective prompt evidence.")
    fun requireConsumedPromptEvidence(source: JSONObject, layer: String) {
        val sha256 = source.opt("nativePromptExecutionSha256") as? String
            ?: error("Native $layer prompt execution SHA-256 must be a string.")
        val stage = source.opt("nativePromptBindingStage") as? String
            ?: error("Native $layer prompt binding stage must be a string.")
        require(sha256 == expectedPromptSha256) {
            "Native $layer prompt evidence does not match the effective prompts."
        }
        require(stage == "conditioning_consumed") {
            "Native $layer prompt evidence was not published after conditioning consumption."
        }
    }
    requireConsumedPromptEvidence(this, "outer")
    requireConsumedPromptEvidence(nativeEffective, "nativeEffective")
    require(
        getString("nativePromptExecutionSha256") ==
            nativeEffective.getString("nativePromptExecutionSha256") &&
            getString("nativePromptBindingStage") ==
            nativeEffective.getString("nativePromptBindingStage")
    ) { "Native outer and nativeEffective prompt evidence conflict." }
    profile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { expectedProof ->
        val nativeProof = nativeEffective.opt("languageProofSha256") as? String
            ?: error("Native multilingual receipt is missing languageProofSha256.")
        require(nativeProof == expectedProof) {
            "Native multilingual receipt returned a different text-encoder semantic proof."
        }
        put("languageProofSha256", expectedProof)
    }
    return put("imageProfileBindingFingerprint", profile.bindingFingerprint)
        .put("promptLanguageBindingFingerprint", profile.promptLanguageBindingFingerprint)
        .put("textEncoderLanguageCapability", profile.textEncoderLanguageCapability().name)
        .put("promptExecutionSha256", expectedPromptSha256)
}

private fun JSONObject.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graph = strictOptionalObject("graph")
        ?: throw IllegalArgumentException(
            "Image executionProfile must declare a graph object."
        )
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf("textEncoder", "unet", "vae", "vaeEncoder", "controlNet").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val artifact = graph.strictOptionalObject(field)
            ?: throw IllegalArgumentException("Image executionProfile.graph.$field must be an object.")
        val relativePath = artifact.strictRequiredString("relativePath")
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOf("schedulerSidecar", "tokenizerSidecar").forEach { field ->
        if (!graph.has(field) || graph.isNull(field)) return@forEach
        val relativePath = graph.strictRequiredString(field)
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field")
        requiredPaths += relativePath
    }
    graph.strictOptionalArray("configSidecars")?.let { sidecars ->
        for (index in 0 until sidecars.length()) {
            val value = sidecars.opt(index)
            val relativePath = (value as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Image executionProfile.graph.configSidecars[$index] must be a non-empty string."
                )
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.configSidecars[$index]"
            )
            requiredPaths += relativePath
        }
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}

private fun ImageExecutionProfile.requiredExecutionProfilePaths(): ParsedExecutionProfilePaths {
    val graphPaths = linkedMapOf<String, String>()
    val requiredPaths = mutableListOf<String>()
    listOf(
        "textEncoder" to graph.textEncoder,
        "unet" to graph.unet,
        "vae" to graph.vae,
        "vaeEncoder" to graph.vaeEncoder,
        "controlNet" to graph.controlNet
    ).forEach { (field, artifact) ->
        val relativePath = artifact?.relativePath ?: return@forEach
        requireSafeImageRuntimePath(relativePath, "executionProfile.graph.$field.relativePath")
        graphPaths[field] = relativePath
        requiredPaths += relativePath
    }
    listOfNotNull(graph.schedulerSidecar, graph.tokenizerSidecar)
        .forEachIndexed { index, relativePath ->
            requireSafeImageRuntimePath(
                relativePath,
                "executionProfile.graph.sidecars[$index]"
            )
            requiredPaths += relativePath
        }
    graph.configSidecars.forEachIndexed { index, relativePath ->
        requireSafeImageRuntimePath(
            relativePath,
            "executionProfile.graph.configSidecars[$index]"
        )
        requiredPaths += relativePath
    }
    return ParsedExecutionProfilePaths(
        graphPaths = graphPaths,
        requiredPaths = requiredPaths.distinct()
    )
}

private fun JSONObject.strictOptionalObject(field: String): JSONObject? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONObject
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an object.")
}

private fun JSONObject.strictOptionalArray(field: String): JSONArray? {
    if (!has(field) || isNull(field)) return null
    return opt(field) as? JSONArray
        ?: throw IllegalArgumentException("Image bundle manifest $field must be an array.")
}

private fun JSONObject.strictRequiredString(field: String): String {
    val value = opt(field)
    return (value as? String)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a non-empty string.")
}

private fun JSONObject.strictOptionalNonBlankString(field: String): String? {
    if (!has(field) || isNull(field)) return null
    val value = opt(field)
    require(value is String && value.isNotBlank()) {
        "Image bundle manifest $field must be a non-empty string when present."
    }
    return value.trim()
}

private fun JSONObject.strictRequiredPositiveInt(field: String): Int {
    val value = opt(field)
    return (value as? Number)
        ?.let { number ->
            val longValue = runCatching { BigDecimal(number.toString()).longValueExact() }
                .getOrNull()
            longValue?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        }
        ?: throw IllegalArgumentException(
            "Image bundle manifest $field must be a positive integer."
        )
}

private fun JSONObject.strictOptionalBoolean(field: String, default: Boolean): Boolean {
    if (!has(field) || isNull(field)) return default
    return opt(field) as? Boolean
        ?: throw IllegalArgumentException("Image bundle manifest $field must be a boolean.")
}

private fun requireSafeImageRuntimePath(path: String, field: String) {
    val normalized = path.trim().replace('\\', '/')
    val unsafe = normalized.isBlank() ||
        normalized.startsWith('/') ||
        Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
        normalized.split('/').any { segment ->
            segment.isBlank() || segment == "." || segment == ".."
        }
    require(!unsafe) { "Image bundle manifest $field escapes the bundle root: $path" }
}

private fun JSONArray.toLocalImageBundleComponentContracts(): List<LocalImageBundleComponentContract> =
    buildList {
        for (index in 0 until length()) {
            val component = opt(index) as? JSONObject
                ?: throw IllegalArgumentException(
                    "Image bundle manifest components[$index] must be an object."
                )
            val role = component.strictRequiredString("role").uppercase()
            val path = (component.opt("path") as? String)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: (component.opt("fileName") as? String)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException(
                    "Image bundle manifest components[$index] must declare path or fileName."
                )
            requireSafeImageRuntimePath(path, "components[$index]")
            val required = if (!component.has("required") || component.isNull("required")) {
                !role.equals("OPTIONAL", ignoreCase = true)
            } else {
                component.opt("required") as? Boolean
                    ?: throw IllegalArgumentException(
                        "Image bundle manifest components[$index].required must be a boolean."
                    )
            }
            add(
                LocalImageBundleComponentContract(
                    role = role,
                    relativePath = path,
                    required = required
                )
            )
        }
    }

private fun JSONArray.toQnnSmokeSpecs(): List<QnnSmokeSpec> =
    buildList {
        for (index in 0 until length()) {
            val smoke = opt(index) as? JSONObject
                ?: throw IllegalArgumentException(
                    "Image bundle manifest smokes[$index] must be an object."
                )
            smoke.opt("contextBinary")?.let { rawPath ->
                if (rawPath !is String || rawPath.isBlank()) {
                    throw IllegalArgumentException(
                        "Image bundle manifest smokes[$index].contextBinary must be a non-empty string."
                    )
                }
                requireSafeImageRuntimePath(rawPath, "smokes[$index].contextBinary")
            }
            add(QnnSmokeSpec.fromSmokeJson(smoke))
        }
    }

private fun JSONObject.optImageEngineMinDeviceTier(): ImageEngineMinDeviceTier {
    val value = optString("minDeviceTier").takeIf { it.isNotBlank() } ?: return ImageEngineMinDeviceTier.ANY
    return runCatching { ImageEngineMinDeviceTier.valueOf(value) }.getOrDefault(ImageEngineMinDeviceTier.ANY)
}

private fun JSONObject.manifestImageSize(): String? {
    val direct = optString("imageSize").takeIf { it.isNotBlank() }
        ?: optString("size").takeIf { it.isNotBlank() }
    if (direct != null) return direct
    val smoke = optJSONObject("smoke") ?: optJSONObject("smokeSpec")
    val width = smoke?.optInt("width", 0)?.takeIf { it > 0 }
        ?: optInt("width", 0).takeIf { it > 0 }
    val height = smoke?.optInt("height", 0)?.takeIf { it > 0 }
        ?: optInt("height", 0).takeIf { it > 0 }
    return if (width != null && height != null) "${width}x${height}" else null
}

private fun JSONArray.firstComponentPath(role: String): String? {
    for (index in 0 until length()) {
        val component = optJSONObject(index) ?: continue
        if (component.optString("role").equals(role, ignoreCase = true)) {
            return component.optString("path").takeIf { it.isNotBlank() }
                ?: component.optString("fileName").takeIf { it.isNotBlank() }
        }
    }
    return null
}

/**
 * Public SDXL archives commonly keep all prompt-conditioning assets in a
 * chipset-neutral nested directory. The manifest path is authoritative; a
 * bounded unique-directory fallback keeps older imported bundles usable.
 */
