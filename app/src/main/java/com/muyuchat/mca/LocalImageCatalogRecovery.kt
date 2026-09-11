package com.muyuchat.mca

import java.io.File
import org.json.JSONArray

/** Prune before entering a directory; filtering yielded entries does not bound traversal. */
internal fun imageDiscoveryWalk(root: File, maxDepth: Int = 5): Sequence<File> {
    val canonicalRoot = root.canonicalFile
    return root.walkTopDown().maxDepth(maxDepth).onEnter { directory ->
        val name = directory.name
        val temporary = name.startsWith(".") || name.endsWith(".part")
        val canonical = directory.canonicalFile
        (directory == root || !temporary) &&
            (canonical == canonicalRoot || canonical.toPath().startsWith(canonicalRoot.toPath())) &&
            !java.nio.file.Files.isSymbolicLink(directory.toPath())
    }
}

internal fun parseImageCatalogRecords(raw: String): List<LocalImageModelRecord> {
    if (raw.isBlank()) return emptyList()
    val array = JSONArray(raw)
    return (0 until array.length()).mapNotNull { index ->
        runCatching { LocalImageModelRecord.fromJson(array.getJSONObject(index)).takeIf { it.path.isNotBlank() && it.fileName.isNotBlank() } }.getOrNull()
    }.sortedByDescending { it.updatedAt }
}
