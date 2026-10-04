package com.muyuchat.mca

import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Local image-bundle staging and recovery helpers (P5/L2). Moved verbatim out of
 * LocalImageProvider.kt: interrupted promotion recovery, stale empty bundle-directory cleanup,
 * bundle-manifest detection and best-effort MNN tokenizer preparation. Same package and
 * internal visibility, so LocalImageModelStore, callers and tests are unchanged.
 */

/**
 * Finishes the last atomic rename when Android stopped the app after a complete
 * image bundle had been validated but before it was registered in preferences.
 * Incomplete candidates are retained so ModelBundleInstaller can resume them.
 */
internal fun recoverInterruptedImageBundlePromotions(
    managedDir: File,
    isReady: (File) -> Boolean
): List<File> {
    val root = runCatching { managedDir.canonicalFile }.getOrNull() ?: return emptyList()
    if (!root.isDirectory) return emptyList()
    val recovered = mutableListOf<File>()
    root.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith(".bundle-") && it.name.endsWith(".candidate") }
        .forEach { candidateFile ->
            val candidate = runCatching { candidateFile.canonicalFile }.getOrNull() ?: return@forEach
            if (!candidate.path.startsWith(root.path + File.separator) || !runCatching { isReady(candidate) }.getOrDefault(false)) {
                return@forEach
            }
            val destinationName = candidate.name.removePrefix(".").removeSuffix(".candidate")
            if (!destinationName.startsWith("bundle-") || destinationName.isBlank()) return@forEach
            val destination = runCatching { File(root, destinationName).canonicalFile }.getOrNull() ?: return@forEach
            if (!destination.path.startsWith(root.path + File.separator)) return@forEach
            val emptyBackup = if (destination.exists()) {
                if (!destination.isDirectory || destination.listFiles()?.isNotEmpty() != false) return@forEach
                val backup = File(root, ".${destination.name}.empty-${UUID.randomUUID()}")
                if (!destination.renameTo(backup)) return@forEach
                backup
            } else {
                null
            }
            if (candidate.renameTo(destination)) {
                recovered += destination
            } else {
                emptyBackup?.renameTo(destination)
            }
        }
    return recovered
}

/** Removes only stale, unregistered, empty public bundle directories from older builds. */
internal fun removeUnregisteredEmptyImageBundleDirectories(
    managedDir: File,
    registeredRoots: Set<String>
) {
    val registered = registeredRoots.mapTo(mutableSetOf()) { path ->
        runCatching { File(path).canonicalPath }.getOrDefault(path)
    }
    managedDir.listFiles()
        .orEmpty()
        .filter { it.isDirectory && it.name.startsWith("bundle-") && it.listFiles()?.isEmpty() == true }
        .forEach { directory ->
            val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEach
            if (canonical.path !in registered) canonical.delete()
        }
}

internal fun File.isMcaImageBundleManifest(): Boolean =
    isFile && runCatching {
        JSONObject(readText(Charsets.UTF_8)).optString("schema") == "mca.image_engine.bundle.v1"
    }.getOrDefault(false)

internal fun prepareMnnDiffusionTokenizerIfPossible(root: File, checkCancelled: () -> Unit = {}): Boolean {
    checkCancelled()
    if (!root.isDirectory) return false
    val rootCanonical = root.canonicalFile
    val rootMtok = File(root, "tokenizer.mtok")
    val existingMtok = root.findDescendantFile("tokenizer.mtok")
    if (existingMtok != null) {
        if (existingMtok.parentFile?.canonicalFile != rootCanonical || !rootMtok.isFile) {
            copyImageImportFile(existingMtok, rootMtok, checkCancelled)
        }
        return true
    }
    val existingTxt = root.findDescendantFile("tokenizer.txt")
    if (existingTxt != null) {
        val rootTxt = File(root, "tokenizer.txt")
        val tokenizerTxt = if (existingTxt.parentFile?.canonicalFile != rootCanonical) {
            copyImageImportFile(existingTxt, rootTxt, checkCancelled)
            rootTxt
        } else {
            existingTxt
        }
        copyImageImportFile(tokenizerTxt, rootMtok, checkCancelled)
        return true
    }

    val vocabFile = root.findDescendantFile("vocab.json") ?: return false
    val mergesFile = root.findDescendantFile("merges.txt") ?: return false
    val vocabJson = JSONObject(readImageImportText(vocabFile, checkCancelled))
    val idToToken = mutableMapOf<Int, String>()
    var maxId = -1
    val keys = vocabJson.keys()
    while (keys.hasNext()) {
        checkCancelled()
        val token = keys.next()
        val rawId = vocabJson.opt(token)
        val id = when (rawId) {
            is Number -> rawId.toInt()
            is String -> rawId.toIntOrNull()
            else -> null
        } ?: continue
        if (id >= 0) {
            idToToken[id] = token
            if (id > maxId) maxId = id
        }
    }
    require(maxId >= 0) { "MNN-Diffusion tokenizer vocab.json has no valid token ids." }
    val decoder = (0..maxId).map { id ->
        checkCancelled()
        idToToken[id] ?: error("MNN-Diffusion tokenizer vocab.json is missing token id $id.")
    }
    val merges = readImageImportText(mergesFile, checkCancelled).lineSequence()
        .map { checkCancelled(); it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }.toList()
    require(merges.isNotEmpty()) { "MNN-Diffusion tokenizer merges.txt has no merge rules." }

    val target = File(root, "tokenizer.txt")
    val temp = File(root, "tokenizer.txt.tmp")
    temp.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("430 3")
        writer.appendLine("0 0 0")
        writer.appendLine()
        writer.appendLine("${decoder.size} ${merges.size}")
        decoder.forEach { checkCancelled(); writer.appendLine(it) }
        merges.forEach { checkCancelled(); writer.appendLine(it) }
    }
    if (target.exists()) target.delete()
    require(temp.renameTo(target)) { "Failed to write MNN-Diffusion tokenizer.txt." }
    copyImageImportFile(target, rootMtok, checkCancelled)
    return true
}
