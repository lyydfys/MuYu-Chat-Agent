package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaManifest
import com.muyuchat.core.mediatek.MediaTekAnimaManifestJson
import com.muyuchat.core.mediatek.MediaTekAotManifest
import com.muyuchat.core.mediatek.MediaTekAotManifestJson
import com.muyuchat.core.mediatek.MediaTekBundleAssetContract
import com.muyuchat.core.mediatek.MediaTekGraphContract
import com.muyuchat.core.mediatek.MediaTekNpugRouteJson
import java.io.File
import org.json.JSONObject

internal data class ParsedMediaTekComponentBundleManifest(
    val manifestPath: String,
    val animaManifest: MediaTekAnimaManifest?,
    val aotManifest: MediaTekAotManifest?,
    val requiredPaths: List<String>
)

/** Dispatches the pinned component manifest by its declared schema; unknown schemas never guess a runtime. */
internal fun parseMediaTekComponentBundleManifest(
    root: File,
    manifestPath: String,
    components: List<LocalImageBundleComponentContract>
): ParsedMediaTekComponentBundleManifest {
    val pinned = readPinnedMediaTekComponentManifest(root, manifestPath, components)
    return when (val schema = declaredMediaTekComponentSchema(pinned.text)) {
        MediaTekAnimaManifest.SCHEMA -> parseMediaTekAnimaManifestBody(root, pinned.path, pinned.text, components)
        MediaTekAotManifest.SCHEMA -> parseMediaTekAotManifestBody(root, pinned.path, pinned.text, components)
        else -> throw IllegalArgumentException("Unsupported MediaTek component manifest schema '$schema'.")
    }
}

internal fun parseMediaTekAnimaBundleManifest(
    root: File,
    manifestPath: String,
    components: List<LocalImageBundleComponentContract>
): ParsedMediaTekComponentBundleManifest {
    val pinned = readPinnedMediaTekComponentManifest(root, manifestPath, components)
    require(declaredMediaTekComponentSchema(pinned.text) == MediaTekAnimaManifest.SCHEMA) {
        "MediaTek Anima v3 manifest must declare schema ${MediaTekAnimaManifest.SCHEMA}."
    }
    return parseMediaTekAnimaManifestBody(root, pinned.path, pinned.text, components)
}

/** Parses and content-pins the v4 NPUG/AOT contract plus every asset and route before the model is usable. */
internal fun parseMediaTekAotBundleManifest(
    root: File,
    manifestPath: String,
    components: List<LocalImageBundleComponentContract>
): ParsedMediaTekComponentBundleManifest {
    val pinned = readPinnedMediaTekComponentManifest(root, manifestPath, components)
    require(declaredMediaTekComponentSchema(pinned.text) == MediaTekAotManifest.SCHEMA) {
        "MediaTek NPUG/AOT manifest must declare schema ${MediaTekAotManifest.SCHEMA}."
    }
    return parseMediaTekAotManifestBody(root, pinned.path, pinned.text, components)
}

private data class PinnedMediaTekComponentManifest(val path: String, val text: String)

private fun readPinnedMediaTekComponentManifest(
    root: File,
    manifestPath: String,
    components: List<LocalImageBundleComponentContract>
): PinnedMediaTekComponentManifest {
    val normalizedManifestPath = requireSafeMediaTekRelativePath(manifestPath)
    val manifestComponents = components.filter { component ->
        component.required && component.role == "CONFIG" &&
            component.relativePath.replace('\\', '/') == normalizedManifestPath
    }
    require(manifestComponents.size == 1) {
        "MediaTek component manifest must be exactly one required CONFIG component in the MCA envelope."
    }
    val manifestComponent = manifestComponents.single()
    require(manifestComponent.expectedSizeBytes != null && manifestComponent.sha256 != null) {
        "MediaTek component manifest must pin source size and SHA-256."
    }
    val manifestFile = root.safeDescendantOrNull(normalizedManifestPath)
        ?: throw IllegalArgumentException("MediaTek component manifest path escapes the bundle root.")
    requirePinnedMediaTekFile(manifestFile, manifestComponent.expectedSizeBytes, manifestComponent.sha256, "component manifest")
    require(manifestFile.length() <= MAX_MANIFEST_BYTES) {
        "MediaTek component manifest exceeds the supported size limit."
    }
    return PinnedMediaTekComponentManifest(
        path = normalizedManifestPath,
        text = manifestFile.readText(Charsets.UTF_8)
    )
}

private fun declaredMediaTekComponentSchema(text: String): String {
    val root = try {
        JSONObject(text)
    } catch (error: Throwable) {
        throw IllegalArgumentException(
            "MediaTek component manifest is malformed: ${error.message?.takeIf(String::isNotBlank) ?: "invalid JSON"}.",
            error
        )
    }
    return (root.opt("schema") as? String)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("MediaTek component manifest must declare a string schema.")
}

private fun parseMediaTekAnimaManifestBody(
    root: File,
    normalizedManifestPath: String,
    text: String,
    components: List<LocalImageBundleComponentContract>
): ParsedMediaTekComponentBundleManifest {
    val manifest = MediaTekAnimaManifestJson.parse(text)
    val requiredComponentsByPath = components
        .filter(LocalImageBundleComponentContract::required)
        .groupBy { it.relativePath.replace('\\', '/') }
    val graphs = manifest.textEncoder.graphs + manifest.sampling.graphs()
    val assets = buildList {
        addAll(manifest.textEncoder.assets())
        manifest.sampling.generationProfiles.forEach { profile ->
            profile.latentInitialization.verifiedLatentAsset?.let { add(it.asset) }
            profile.blockModulationsByStepCount.values.forEach { add(it.asset) }
            profile.finalModulationsByStepCount.values.forEach { add(it.asset) }
        }
    }
    val requiredPaths = buildList {
        add(normalizedManifestPath)
        graphs.forEach { graph ->
            val file = verifyMediaTekGraph(root, graph)
            val relativePath = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            requireMatchingMediaTekComponent(requiredComponentsByPath, relativePath, graph.sizeBytes, graph.sha256)
            add(relativePath)
        }
        assets.forEach { asset ->
            val file = verifyMediaTekAsset(root, asset)
            val relativePath = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
            requireMatchingMediaTekComponent(requiredComponentsByPath, relativePath, asset.sizeBytes, asset.sha256)
            add(relativePath)
        }
    }.distinct()
    requireEnvelopePins(requiredPaths, components, "MediaTek Anima v3")
    return ParsedMediaTekComponentBundleManifest(
        manifestPath = normalizedManifestPath,
        animaManifest = manifest,
        aotManifest = null,
        requiredPaths = requiredPaths
    )
}

private fun parseMediaTekAotManifestBody(
    root: File,
    normalizedManifestPath: String,
    text: String,
    components: List<LocalImageBundleComponentContract>
): ParsedMediaTekComponentBundleManifest {
    val manifest = MediaTekAotManifestJson.parse(text)
    val requiredComponentsByPath = components
        .filter(LocalImageBundleComponentContract::required)
        .groupBy { it.relativePath.replace('\\', '/') }
    val verifiedAssets = linkedMapOf<String, File>()
    manifest.assets().forEach { asset ->
        val file = verifyMediaTekAsset(root, asset)
        val relativePath = file.relativeTo(root.canonicalFile).invariantSeparatorsPath
        requireMatchingMediaTekComponent(requiredComponentsByPath, relativePath, asset.sizeBytes, asset.sha256)
        verifiedAssets[asset.file.replace('\\', '/')] = file
    }
    manifest.variants.forEach { variant ->
        val routeAsset = variant.route.asset
        val routeFile = requireNotNull(verifiedAssets[routeAsset.file.replace('\\', '/')]) {
            "MediaTek NPUG route ${routeAsset.file} was not pinned."
        }
        require(routeFile.length() <= MAX_ROUTE_BYTES) {
            "MediaTek NPUG route ${routeAsset.file} exceeds the supported size limit."
        }
        val route = MediaTekNpugRouteJson.parse(routeFile.readText(Charsets.UTF_8))
        val mismatches = route.verifyAgainst(variant.route, variant.topology, variant.compiledNetwork)
        require(mismatches.isEmpty()) {
            "MediaTek NPUG route ${routeAsset.file} does not match its manifest: ${mismatches.joinToString("; ")}."
        }
    }
    val requiredPaths = buildList {
        add(normalizedManifestPath)
        manifest.assets().forEach { add(it.file.replace('\\', '/')) }
    }.distinct()
    requireEnvelopePins(requiredPaths, components, "MediaTek NPUG/AOT")
    return ParsedMediaTekComponentBundleManifest(
        manifestPath = normalizedManifestPath,
        animaManifest = null,
        aotManifest = manifest,
        requiredPaths = requiredPaths
    )
}

private fun requireEnvelopePins(
    requiredPaths: List<String>,
    components: List<LocalImageBundleComponentContract>,
    label: String
) {
    val envelopeRequiredPaths = components.asSequence()
        .filter(LocalImageBundleComponentContract::required)
        .map { it.relativePath.replace('\\', '/') }
        .toSet()
    val unpinnedPaths = requiredPaths.filterNot(envelopeRequiredPaths::contains)
    require(unpinnedPaths.isEmpty()) {
        "$label graph/assets must also be required MCA envelope components: ${unpinnedPaths.joinToString(", ")}."
    }
}

internal fun requireMatchingMediaTekComponent(
    requiredComponentsByPath: Map<String, List<LocalImageBundleComponentContract>>,
    path: String,
    expectedSizeBytes: Long,
    expectedSha256: String
) {
    val matches = requiredComponentsByPath[path].orEmpty()
    require(matches.size == 1) {
        "MediaTek graph/asset '$path' must have exactly one required MCA v1 component."
    }
    val component = matches.single()
    require(component.expectedSizeBytes == expectedSizeBytes &&
        component.sha256?.equals(expectedSha256, ignoreCase = true) == true
    ) {
        "MediaTek graph/asset '$path' v1 size/SHA-256 must match its component declaration."
    }
}

internal fun LocalImageModelRecord.mediaTekAnimaBundleReadinessMessage(): String? {
    val root = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)?.takeIf { it.isDirectory }
        ?: File(path).parentFile?.takeIf { it.isDirectory }
        ?: return "MediaTek Anima requires a complete bundle directory."
    val manifest = when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared ->
            return "MediaTek Anima bundle is missing the MCA v1 manifest envelope."
        is LocalImageBundleManifestInspection.Invalid ->
            return "MediaTek Anima bundle contract is invalid: ${inspection.message}"
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
    }
    if (manifest.runtime != LocalImageRuntime.MEDIATEK_NEURON ||
        manifest.family != LocalImageModelFamily.ANIMA || manifest.task != "TEXT_TO_IMAGE"
    ) return "Installed bundle metadata does not describe MediaTek Anima text-to-image."
    val aotManifest = manifest.mediatekAotManifest
    if (aotManifest != null && manifest.mediatekAnimaManifest == null) {
        val problems = aotManifest.executionBlockers().toMutableList()
        if (aotManifest.variants.size < 2) {
            problems += "an NPUG chain needs at least two execution variants"
        }
        if (aotManifest.conditioning.contextAsset == null && aotManifest.conditioning.textAssets.isEmpty()) {
            problems += "NPUG execution requires an ACTX context or text assets"
        }
        return if (problems.isEmpty()) {
            null
        } else {
            "MediaTek NPUG AOT bundle is incomplete: ${problems.joinToString("; ")}."
        }
    }
    return when (val contract = manifest.resolveRuntimeComponentContract(root)) {
        is LocalImageRuntimeComponentContract.Invalid ->
            "MediaTek Anima runtime contract is invalid: ${contract.message}"
        is LocalImageRuntimeComponentContract.Ready -> if (contract.missingPaths.isEmpty()) {
            null
        } else {
            "MediaTek Anima requires missing or empty components: ${contract.missingPaths.joinToString(", ")}."
        }
        LocalImageRuntimeComponentContract.UndeclaredLegacy ->
            "MediaTek Anima bundle is missing its complete v3 runtime component contract."
    }
}

private fun verifyMediaTekGraph(root: File, graph: MediaTekGraphContract): File {
    val path = requireSafeMediaTekRelativePath(graph.file)
    val file = root.safeDescendantOrNull(path)
        ?: throw IllegalArgumentException("MediaTek graph path escapes the bundle root: $path")
    requirePinnedMediaTekFile(file, graph.sizeBytes, graph.sha256, "${graph.stage} graph")
    return file.canonicalFile
}

private fun verifyMediaTekAsset(root: File, asset: MediaTekBundleAssetContract): File {
    val path = requireSafeMediaTekRelativePath(asset.file)
    val file = root.safeDescendantOrNull(path)
        ?: throw IllegalArgumentException("MediaTek asset path escapes the bundle root: $path")
    requirePinnedMediaTekFile(file, asset.sizeBytes, asset.sha256, "MediaTek asset")
    return file.canonicalFile
}

internal fun resolvePinnedMediaTekAsset(
    root: File,
    asset: MediaTekBundleAssetContract,
    label: String
): File {
    val path = requireSafeMediaTekRelativePath(asset.file)
    val file = root.safeDescendantOrNull(path)
        ?: throw IllegalArgumentException("$label path escapes the bundle root: $path")
    requirePinnedMediaTekFile(file, asset.sizeBytes, asset.sha256, label)
    return file.canonicalFile
}

private fun requirePinnedMediaTekFile(file: File, expectedSize: Long, expectedSha256: String, label: String) {
    require(file.isFile && file.canRead() && file.length() > 0L) {
        "$label is missing, empty, or unreadable: ${file.name}"
    }
    require(file.length() == expectedSize) {
        "$label size does not match its component manifest: ${file.name}"
    }
    require(observedLocalImageFileSha256(file).equals(expectedSha256, ignoreCase = true)) {
        "$label SHA-256 does not match its component manifest: ${file.name}"
    }
}

private fun requireSafeMediaTekRelativePath(path: String): String {
    val normalized = path.trim().replace('\\', '/')
    require(normalized.isNotBlank() && !normalized.startsWith('/') &&
        !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
        normalized.split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }
    ) { "MediaTek component manifest contains an unsafe relative bundle path." }
    return normalized
}

private const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L
private const val MAX_ROUTE_BYTES = 8L * 1024L * 1024L
