package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

enum class MnnImportMode { FULL, TEXT_ONLY }

data class ModelImportOptions(
    val mnnMode: MnnImportMode = MnnImportMode.FULL,
    /** Persist this with the task. Reusing it retries the same import, rather than installing a duplicate. */
    val resumeKey: String? = null
)

data class ModelImportProgress(
    val stage: String,
    val relativePath: String? = null,
    val fileIndex: Int = 0,
    val fileCount: Int = 0,
    val copiedBytes: Long = 0L,
    val totalBytes: Long? = null
)

/** Preserve valid component names; only path traversal and absolute paths are forbidden. */
internal fun normalizeMnnImportPath(raw: String): String {
    require(raw.isNotBlank() && '\u0000' !in raw) { "MNN component path is empty or contains NUL." }
    val path = raw.replace('\\', '/')
    require(!path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
        "MNN component path must be relative: $raw"
    }
    val parts = path.split('/')
    require(parts.none { it == ".." }) { "MNN component path escapes its bundle: $raw" }
    require(parts.none { ':' in it }) { "MNN component path contains an invalid colon: $raw" }
    val normalized = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
    require(normalized.isNotBlank()) { "MNN component path does not name a file: $raw" }
    return normalized
}

internal fun safeMnnImportFile(root: File, path: String): File {
    val base = root.canonicalFile
    val file = File(base, normalizeMnnImportPath(path)).canonicalFile
    require(file != base && file.toPath().startsWith(base.toPath())) { "MNN component escapes its bundle: $path" }
    return file
}

/** Absolute exporter base_dir is stale after copying. Never read from that external directory. */
internal fun mnnImportBasePrefix(config: JSONObject): String {
    val value = config.opt("base_dir")
    require(value == null || value == JSONObject.NULL || value is String) { "MNN base_dir must be a string." }
    val path = (value as? String).orEmpty().trim().replace('\\', '/')
    require('\u0000' !in path) { "MNN base_dir contains NUL." }
    if (path.isEmpty() || path == "." || path == "./") return ""
    require(path.split('/').none { it == ".." }) { "MNN base_dir cannot contain parent traversal: $path" }
    if (path.startsWith('/') || Regex("^[A-Za-z]:/").containsMatchIn(path)) return ""
    return normalizeMnnImportPath(path.trimEnd('/'))
}

internal val MNN_IMPORT_COMPONENT_KEYS = setOf(
    "llm_config", "llm_model", "llm_weight", "block_model", "lm_model", "embedding_file", "embedding_model",
    "tokenizer_file", "visual_model", "visual_weight", "audio_model", "audio_weight",
    "context_file", "ple_model", "ple_weight", "ple_embed_file", "projector_model", "projector_weight",
    "talker_model", "talker_weight", "talker_embedding_file", "predit_model", "dit_model", "bigvgan_model",
    "spk_dict", "lookup_file", "draft_model", "mtp_model", "eagle_model", "eagle_fc", "eagle_d2t",
    "dflash_model", "dflash_fc", "dflash_lmhead"
)

private val MNN_MODALITY_PATH_KEYS = setOf(
    "visual_model", "visual_weight", "audio_model", "audio_weight", "projector_model", "projector_weight",
    "talker_model", "talker_weight", "talker_embedding_file", "predit_model", "dit_model", "bigvgan_model", "spk_dict"
)

/** A view shared by import and readiness; it does not change source files. */
internal fun normalizedMnnImportConfig(
    original: JSONObject,
    basePrefix: String,
    mode: MnnImportMode = MnnImportMode.FULL,
    rootConfig: Boolean = false,
    strictPaths: Boolean = true
): JSONObject {
    val result = JSONObject(original.toString())
    fun component(path: String): String = normalizeMnnImportPath(
        if (basePrefix.isEmpty()) path else "$basePrefix/${normalizeMnnImportPath(path)}"
    )
    fun visit(value: Any?, depth: Int = 0) {
        require(depth <= 32) { "MNN config nesting exceeds 32 levels." }
        when (value) {
            is JSONObject -> value.keys().asSequence().toList().forEach { key ->
                val child = value.opt(key)
                when {
                    key == "base_dir" -> value.remove(key)
                    mode == MnnImportMode.TEXT_ONLY && key in MNN_MODALITY_PATH_KEYS -> value.remove(key)
                    mode == MnnImportMode.TEXT_ONLY && key in setOf("is_visual", "is_audio", "is_talker") -> value.put(key, false)
                    key in MNN_IMPORT_COMPONENT_KEYS && child is String && child.isNotBlank() -> value.put(key,
                        if (strictPaths) component(child) else runCatching { component(child) }.getOrDefault(child))
                    else -> visit(child, depth + 1)
                }
            }
            is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it), depth + 1) }
        }
    }
    visit(result)
    if (rootConfig && basePrefix.isNotEmpty()) {
        mapOf("llm_config" to "llm_config.json", "llm_model" to "llm.mnn", "llm_weight" to "llm.mnn.weight",
            "embedding_file" to "embeddings_bf16.bin").forEach { (key, default) ->
            if (!result.has(key)) result.put(key, component(default))
        }
        if (!result.has("tokenizer_file")) result.put("tokenizer_file", component("tokenizer.txt"))
        if (mode == MnnImportMode.FULL && result.optBoolean("is_visual", false) && !result.has("visual_model")) {
            result.put("visual_model", component("visual.mnn"))
        }
        if (mode == MnnImportMode.FULL && result.optBoolean("is_audio", false) && !result.has("audio_model")) {
            result.put("audio_model", component("audio.mnn"))
        }
    }
    if (mode == MnnImportMode.TEXT_ONLY) {
        result.put("is_visual", false).put("is_audio", false).put("is_talker", false)
        result.put("mca_import_mode", "text_only")
    }
    return result
}

internal data class MnnImportConfiguration(
    val root: JSONObject,
    val model: JSONObject?,
    val modelPath: String,
    val basePrefix: String
)

internal fun readMnnImportConfiguration(
    bundle: File, mode: MnnImportMode = MnnImportMode.FULL, lenient: Boolean = false
): MnnImportConfiguration {
    val original = readBoundedMnnJson(safeMnnImportFile(bundle, "config.json"))
    val prefix = mnnImportBasePrefix(original)
    val normalized = normalizedMnnImportConfig(original, prefix, mode, rootConfig = true, strictPaths = !lenient)
    // MNN defaults to tokenizer.txt; current exporters also ship tokenizer.mtok.
    if (!original.has("tokenizer_file")) {
        val txt = listOf(prefix, "tokenizer.txt").filter(String::isNotEmpty).joinToString("/")
        val mtok = listOf(prefix, "tokenizer.mtok").filter(String::isNotEmpty).joinToString("/")
        if (!safeMnnImportFile(bundle, txt).isFile && safeMnnImportFile(bundle, mtok).isFile) normalized.put("tokenizer_file", mtok)
    }
    val rawModelPath = normalized.optString("llm_config").ifBlank { "llm_config.json" }
    val modelPath = if (lenient) runCatching { normalizeMnnImportPath(rawModelPath) }.getOrDefault(rawModelPath)
        else normalizeMnnImportPath(rawModelPath)
    val model = if (lenient) runCatching {
        safeMnnImportFile(bundle, modelPath).takeIf { it.isFile }?.let {
            normalizedMnnImportConfig(readBoundedMnnJson(it), prefix, mode, strictPaths = false)
        }
    }.getOrNull() else safeMnnImportFile(bundle, modelPath).takeIf { it.isFile }?.let {
        normalizedMnnImportConfig(readBoundedMnnJson(it), prefix, mode)
    }
    return MnnImportConfiguration(normalized, model, modelPath, prefix)
}

internal fun readBoundedMnnJson(file: File): JSONObject {
    require(file.length() in 1..(16L * 1024L * 1024L)) { "MNN config is empty or exceeds 16 MiB: ${file.name}" }
    return JSONObject(file.readText(Charsets.UTF_8))
}

/** Original configs are retained as sidecars. FULL never removes modality declarations or files. */
internal fun prepareImportedMnnConfiguration(bundle: File, mode: MnnImportMode, checkCancelled: () -> Unit = {}) {
    checkCancelled()
    val config = readMnnImportConfiguration(bundle, mode)
    listOfNotNull("config.json" to config.root, config.model?.let { config.modelPath to it }).forEach { (path, json) ->
        checkCancelled()
        val file = safeMnnImportFile(bundle, path)
        val content = json.toString(2) + "\n"
        if (file.readText(Charsets.UTF_8) != content) {
            val backup = File(file.parentFile, "${file.name}.mca-import-original")
            if (!backup.exists()) file.copyTo(backup, overwrite = false)
            writeMnnImportJsonAtomically(file, json)
        }
    }
}

internal fun writeMnnImportJsonAtomically(file: File, json: JSONObject) {
    file.parentFile?.mkdirs()
    val pending = File(file.parentFile, ".${file.name}.pending")
    FileOutputStream(pending).use { output ->
        output.write((json.toString(2) + "\n").toByteArray(Charsets.UTF_8))
        output.fd.sync()
    }
    java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

internal fun findMnnImportBundleRoot(root: File, mode: MnnImportMode, checkCancelled: () -> Unit = {}): File {
    val candidates = root.walkTopDown().filter { it.isFile && it.name == "config.json" }
        .mapNotNull { it.parentFile }.distinctBy { it.canonicalPath }.toList()
    val results = candidates.map { candidate ->
        checkCancelled()
        candidate to MnnBundleReadinessAnalyzer.analyze(candidate, importMode = mode)
    }
    val ready = results.filter { it.second.canLoad }
    require(ready.size == 1) {
        if (ready.size > 1) "MNN 包包含多个完整模型根目录，请只选择一个模型目录。"
        else "MNN 包未通过完整性预检：" + if (results.isEmpty()) "缺少 config.json；请选择完整模型目录或 ZIP。"
            else results.take(4).joinToString("；") { (candidate, readiness) ->
                "${candidate.relativeTo(root).path.ifBlank { "." }}: ${readiness.diagnosticSummary()} " +
                    readiness.diagnostics.take(3).joinToString { it.message }
            }
    }
    return ready.single().first.canonicalFile
}
