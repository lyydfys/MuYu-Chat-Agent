package com.muyuchat.core.engine

import org.json.JSONObject
import java.io.File

/**
 * Compatibility policy for upstream MNN LLM sessions whose in-place reset is
 * not equivalent to a fresh text turn.
 *
 * Gemma 4 packages exported by MNN 3.5 have been observed to produce a valid
 * first answer and then immediately emit only EOP after `Llm::reset()` under
 * the MNN 3.6 loader. A text-only isolation bundle can be reconstructed safely
 * (unlike the incompatible legacy multimodal graph), so refresh that narrowly
 * identified session before its next request. Other model families retain the
 * normal fast reset/reuse path.
 */
internal object MnnSessionLifecyclePolicy {
    fun requiresFreshSessionAfterSuccessfulTextTurn(modelPath: String): Boolean {
        val bundleRoot = modelBundleRoot(modelPath) ?: return false
        val rootConfig = readJson(File(bundleRoot, "config.json")) ?: return false
        val llmConfigPath = rootConfig.optString("llm_config")
            .trim()
            .ifBlank { "llm_config.json" }
        val llmConfig = resolveContainedFile(bundleRoot, llmConfigPath)
            ?.let(::readJson)
            ?: return false

        val modelType = llmConfig.optString("model_type").trim().lowercase()
        val rootIsVisual = rootConfig.optBoolean("is_visual", false) ||
            rootConfig.optString("visual_model").isNotBlank()
        val llmIsVisual = llmConfig.optBoolean("is_visual", false)
        return modelType == "gemma4" && !rootIsVisual && !llmIsVisual
    }

    private fun modelBundleRoot(modelPath: String): File? {
        val selected = File(modelPath)
        val root = when {
            selected.isDirectory -> selected
            selected.isFile -> selected.parentFile
            selected.name.equals("config.json", ignoreCase = true) -> selected.parentFile
            else -> selected
        } ?: return null
        return root.takeIf { it.isDirectory }
    }

    private fun resolveContainedFile(root: File, relativePath: String): File? {
        if (relativePath.isBlank() || File(relativePath).isAbsolute) return null
        val rootPath = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(rootPath, relativePath).canonicalFile }.getOrNull() ?: return null
        val rootPrefix = rootPath.path.trimEnd(File.separatorChar) + File.separator
        return candidate.takeIf { it.path.startsWith(rootPrefix, ignoreCase = true) && it.isFile }
    }

    private fun readJson(file: File): JSONObject? = runCatching {
        if (!file.isFile || !file.canRead() || file.length() <= 0L) return@runCatching null
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()
}
