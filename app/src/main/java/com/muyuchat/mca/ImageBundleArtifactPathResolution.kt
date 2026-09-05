package com.muyuchat.mca

import java.io.File

/**
 * Resolves a graph artifact stored by an older image-bundle manifest.
 *
 * Qualcomm and community ZIPs commonly wrap graph files in one publisher
 * directory. New installs persist that directory in the MCA manifest, but an
 * older manifest can still name the root-level basename. Rebind only a unique,
 * non-empty in-bundle file; an ambiguous or unsafe declaration remains
 * unchanged and is rejected by the normal component validation path.
 */
internal fun resolveInstalledImageBundleArtifactPath(
    bundleRoot: File,
    declaredRelativePath: String
): String {
    val requested = declaredRelativePath.replace('\\', '/').trim()
    if (requested.isEmpty()) return requested
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return requested
    if (!root.isDirectory) return requested

    fun safeRelative(file: File): String? = runCatching { file.canonicalFile }
        .getOrNull()
        ?.takeIf { candidate ->
            candidate.path.startsWith(root.path + File.separator) &&
                candidate.isFile &&
                candidate.length() > 0L
        }
        ?.relativeTo(root)
        ?.invariantSeparatorsPath

    safeRelative(File(root, requested))?.let { return it }

    val fileName = requested.substringAfterLast('/').trim()
    if (fileName.isEmpty() || fileName == "." || fileName == "..") return requested
    val candidates = root.walkTopDown()
        .filter { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals(fileName, ignoreCase = true)
        }
        .mapNotNull(::safeRelative)
        .distinct()
        .take(2)
        .toList()
    return candidates.singleOrNull() ?: requested
}

/**
 * Rebinds graph paths persisted by older imports that treated the ZIP root as
 * the bundle root.  The profile remains immutable; this returns a request-
 * scoped copy and never rewrites the on-disk manifest implicitly.
 */
internal fun ImageExecutionProfile.rebindInstalledArtifactPaths(bundleRoot: File): ImageExecutionProfile {
    val root = runCatching { bundleRoot.canonicalFile }
        .getOrNull()
        ?.takeIf(File::isDirectory)
        ?: return this

    fun ImageGraphArtifactContract.rebind(): ImageGraphArtifactContract = copy(
        relativePath = resolveInstalledImageBundleArtifactPath(root, relativePath)
    )

    val reboundGraph = graph.copy(
        textEncoder = graph.textEncoder?.rebind(),
        unet = graph.unet?.rebind(),
        vae = graph.vae?.rebind(),
        vaeEncoder = graph.vaeEncoder?.rebind(),
        controlNet = graph.controlNet?.rebind(),
        schedulerSidecar = graph.schedulerSidecar?.let {
            resolveInstalledImageBundleArtifactPath(root, it)
        },
        tokenizerSidecar = graph.tokenizerSidecar?.let {
            resolveInstalledImageBundleArtifactPath(root, it)
        },
        configSidecars = graph.configSidecars.map {
            resolveInstalledImageBundleArtifactPath(root, it)
        }
    )
    return copy(graph = reboundGraph)
}
