package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：运行时组件契约解析（resolveRuntimeComponentContract /
 * missingRequiredComponentPaths / hasExactRuntimeComponentContract / isArchiveContainerPath /
 * components/smokes 解析 helper / firstComponentPath）自 LocalImageProviderBundleManifest.kt
 * 逐字节平移；仅跨件消费的声明行登记为 internal（isArchiveContainerPath 保持 private）。
 */

internal fun LocalImageBundleManifest.resolveRuntimeComponentContract(
    root: File
): LocalImageRuntimeComponentContract {
    if (runtime == LocalImageRuntime.MEDIATEK_NEURON) {
        if ((mediatekAnimaManifest == null && mediatekAotManifest == null) || mediatekAnimaManifestPath == null) {
            return LocalImageRuntimeComponentContract.Invalid(
                "MediaTek component manifest was not strictly parsed from the bundle contract."
            )
        }
        val paths = mediatekAnimaRequiredPaths
        val missing = paths.filter { path ->
            root.safeDescendantOrNull(path)?.let { it.isFile && it.canRead() && it.length() > 0L } != true
        }
        return LocalImageRuntimeComponentContract.Ready(paths, missing)
    }
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
internal fun JSONArray.toLocalImageBundleComponentContracts(): List<LocalImageBundleComponentContract> =
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
                    required = required,
                    expectedSizeBytes = component.opt("expectedSizeBytes")?.let { value ->
                        require(value is Number) {
                            "Image bundle manifest components[$index].expectedSizeBytes must be numeric."
                        }
                        value.toLong().takeIf { it > 0L }
                            ?: throw IllegalArgumentException(
                                "Image bundle manifest components[$index].expectedSizeBytes must be positive."
                            )
                    },
                    sha256 = component.opt("sha256")?.let { value ->
                        require(value is String && value.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                            "Image bundle manifest components[$index].sha256 must be a SHA-256 hex string."
                        }
                        value
                    }
                )
            )
        }
    }

internal fun JSONArray.toQnnSmokeSpecs(): List<QnnSmokeSpec> =
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
internal fun JSONArray.firstComponentPath(role: String): String? {
    for (index in 0 until length()) {
        val component = optJSONObject(index) ?: continue
        if (component.optString("role").equals(role, ignoreCase = true)) {
            return component.optString("path").takeIf { it.isNotBlank() }
                ?: component.optString("fileName").takeIf { it.isNotBlank() }
        }
    }
    return null
}
