package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * A concrete on-disk QAIRT package check. It owns package completeness only;
 * the native create/generate attempt remains the final compatibility decision.
 */
enum class QairtBundleReadinessStatus {
    READY,
    BLOCKED
}

enum class QairtBundleDiagnosticCode {
    BUNDLE_DIRECTORY_MISSING,
    BUNDLE_PATH_NOT_DIRECTORY,
    METADATA_MISSING,
    METADATA_NOT_FILE,
    METADATA_EMPTY,
    METADATA_UNREADABLE,
    METADATA_INVALID_JSON,
    REQUIRED_COMPONENT_MISSING,
    REQUIRED_COMPONENT_NOT_FILE,
    REQUIRED_COMPONENT_EMPTY,
    REQUIRED_COMPONENT_UNREADABLE,
    DECLARED_COMPONENT_PATH_INVALID,
    CONFIG_INVALID_JSON,
    LLM_CONTEXT_SHARD_MISSING,
    VISION_ENCODER_MISSING
}

data class QairtBundleDiagnostic(
    val code: QairtBundleDiagnosticCode,
    val path: String? = null,
    val message: String
)

data class QairtBundleReadiness(
    val status: QairtBundleReadinessStatus,
    val rootPath: String? = null,
    val modelId: String? = null,
    val supportsVision: Boolean = false,
    val rootBinPaths: List<String> = emptyList(),
    val requiredComponentPaths: List<String> = emptyList(),
    val missingRequiredComponents: List<String> = emptyList(),
    val invalidRequiredComponents: List<String> = emptyList(),
    val diagnostics: List<QairtBundleDiagnostic> = emptyList()
) {
    val canLoad: Boolean
        get() = status == QairtBundleReadinessStatus.READY

    fun diagnosticSummary(): String = when (status) {
        QairtBundleReadinessStatus.READY ->
            "QAIRT 模型包完整性校验通过（${rootBinPaths.size} 个 context/bin 文件${if (supportsVision) "，含视觉管线" else ""}）。"

        QairtBundleReadinessStatus.BLOCKED -> buildString {
            append("QAIRT 模型包不完整")
            if (missingRequiredComponents.isNotEmpty()) {
                append("：缺少 ")
                append(missingRequiredComponents.joinToString(", "))
            }
            if (invalidRequiredComponents.isNotEmpty()) {
                append(if (missingRequiredComponents.isEmpty()) "：不可用 " else "；不可用 ")
                append(invalidRequiredComponents.joinToString(", "))
            }
            if (missingRequiredComponents.isEmpty() && invalidRequiredComponents.isEmpty()) {
                diagnostics.firstOrNull()?.message?.let { append("：").append(it) }
            }
            append("。")
        }
    }
}

/**
 * Qualcomm AI Hub packages are flat directories.  The current GenieX QAIRT
 * plugin consumes root-level `.bin` context files plus `tokenizer.json`; newer
 * official packages additionally describe their exact files in metadata.json
 * and Genie pipeline configs.  Reading those declarations catches incomplete
 * extracts before native create collapses them into an opaque `-3`.
 */
object QairtBundleReadinessAnalyzer {
    private const val MAX_JSON_BYTES = 16L * 1024L * 1024L

    fun analyze(bundleDir: File): QairtBundleReadiness {
        if (!bundleDir.exists()) {
            return blocked(
                QairtBundleDiagnostic(
                    code = QairtBundleDiagnosticCode.BUNDLE_DIRECTORY_MISSING,
                    path = bundleDir.absolutePath,
                    message = "QAIRT bundle directory does not exist."
                )
            )
        }
        if (!bundleDir.isDirectory) {
            return blocked(
                QairtBundleDiagnostic(
                    code = QairtBundleDiagnosticCode.BUNDLE_PATH_NOT_DIRECTORY,
                    path = bundleDir.absolutePath,
                    message = "QAIRT bundle path is not a directory."
                )
            )
        }
        val root = runCatching { bundleDir.canonicalFile }.getOrNull()
            ?.takeIf(File::isDirectory)
            ?: return blocked(
                QairtBundleDiagnostic(
                    code = QairtBundleDiagnosticCode.BUNDLE_PATH_NOT_DIRECTORY,
                    path = bundleDir.absolutePath,
                    message = "QAIRT bundle directory cannot be resolved safely."
                )
            )
        val state = AnalysisState(root)
        val metadata = state.readRequiredJson("metadata.json", isMetadata = true) ?: return state.result()

        state.modelId = metadata.optString("model_id").trim().takeIf(String::isNotBlank)
        val metadataModelFiles = modelFilesFromMetadata(metadata)
        val pipelineConfigPaths = pipelineConfigPathsFromMetadata(metadata, state)
        state.supportsVision = metadata.optJSONObject("genie")
            ?.optBoolean("supports_vision", false) == true ||
            metadataModelFiles.any(::isVisionArtifactPath) ||
            pipelineConfigPaths.keys.any(::isVisionPipelineNode)

        val rootBins = state.rootLevelBinPaths()
        state.rootBinPaths += rootBins
        if (rootBins.isEmpty()) {
            state.missing(
                path = "*.bin",
                code = QairtBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING,
                message = "QAIRT bundle has no root-level .bin context shard."
            )
        } else {
            // GenieX enumerates every root .bin, so an empty leftover shard is
            // just as harmful as a missing one.
            rootBins.forEach { path -> state.requireComponent(path) }
        }

        // AI Hub metadata is an authoritative shard inventory when available.
        // Generic local QAIRT bundles often omit model_files, in which case the
        // root-level `.bin` rule above preserves the documented generic path.
        metadataModelFiles.forEach { path -> state.requireComponent(path) }
        state.requireComponent("tokenizer.json")

        val llmShards = rootBins.filterNot(::isVisionArtifactPath)
        if (llmShards.isEmpty()) {
            state.missing(
                path = "LLM context .bin",
                code = QairtBundleDiagnosticCode.LLM_CONTEXT_SHARD_MISSING,
                message = "QAIRT bundle has no text-generation context shard."
            )
        }
        if (state.supportsVision) {
            // GenieX's VLM plugin resolves this exact root-level name when no
            // explicit projector path is supplied.
            state.requireComponent(
                path = "vision_encoder.bin",
                missingCode = QairtBundleDiagnosticCode.VISION_ENCODER_MISSING,
                missingMessage = "QAIRT VLM metadata declares vision support but vision_encoder.bin is missing."
            )
        }

        // genie_config.json is optional for generic LOCALFS QAIRT bundles, but
        // when it is shipped it declares real runtime assets and must be valid.
        if (state.rootEntryExists("genie_config.json")) {
            state.inspectConfig("genie_config.json")
        }
        pipelineConfigPaths.values.distinct().forEach(state::inspectConfig)
        return state.result()
    }

    private fun modelFilesFromMetadata(metadata: JSONObject): List<String> {
        val modelFiles = metadata.optJSONObject("model_files") ?: return emptyList()
        return modelFiles.keys().asSequence()
            .filter(::isModelArtifactPath)
            .sorted()
            .toList()
    }

    private fun pipelineConfigPathsFromMetadata(
        metadata: JSONObject,
        state: AnalysisState
    ): Map<String, String> {
        val nodes = metadata.optJSONObject("genie")
            ?.optJSONObject("pipeline")
            ?.optJSONObject("nodes")
            ?: return emptyMap()
        val paths = linkedMapOf<String, String>()
        nodes.keys().asSequence().toList().sorted().forEach { node ->
            val raw = nodes.optString(node).trim()
            if (raw.isBlank()) {
                state.invalidPath(
                    path = "genie.pipeline.nodes.$node",
                    message = "QAIRT pipeline node '$node' does not declare a config file."
                )
            } else {
                paths[node] = raw
            }
        }
        return paths
    }

    private fun isModelArtifactPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".bin") ||
            lower.endsWith(".ctx") ||
            lower.endsWith(".serialized") ||
            lower.endsWith(".qnn")
    }

    private fun isVisionArtifactPath(path: String): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return "vision" in name || "image_encoder" in name || "img_encoder" in name
    }

    private fun isVisionPipelineNode(node: String): Boolean {
        val lower = node.lowercase()
        return "image" in lower || "vision" in lower || "visual" in lower
    }

    private fun blocked(diagnostic: QairtBundleDiagnostic): QairtBundleReadiness =
        QairtBundleReadiness(
            status = QairtBundleReadinessStatus.BLOCKED,
            diagnostics = listOf(diagnostic)
        )

    private class AnalysisState(private val root: File) {
        var modelId: String? = null
        var supportsVision: Boolean = false
        val rootBinPaths = mutableListOf<String>()
        val required = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        val invalid = linkedSetOf<String>()
        val diagnostics = mutableListOf<QairtBundleDiagnostic>()
        private val inspectedConfigs = mutableSetOf<String>()
        private val emitted = mutableSetOf<Pair<QairtBundleDiagnosticCode, String?>>()

        fun result(): QairtBundleReadiness = QairtBundleReadiness(
            status = if (diagnostics.isEmpty()) QairtBundleReadinessStatus.READY else QairtBundleReadinessStatus.BLOCKED,
            rootPath = root.absolutePath,
            modelId = modelId,
            supportsVision = supportsVision,
            rootBinPaths = rootBinPaths.distinct().sorted(),
            requiredComponentPaths = required.toList(),
            missingRequiredComponents = missing.toList(),
            invalidRequiredComponents = invalid.toList(),
            diagnostics = diagnostics.toList()
        )

        fun rootLevelBinPaths(): List<String> = root.listFiles().orEmpty()
            .mapNotNull { entry ->
                val canonical = runCatching { entry.canonicalFile }.getOrNull() ?: return@mapNotNull null
                canonical.takeIf {
                    it.isFile &&
                        it.parentFile?.canonicalPath == root.path &&
                        it.name.endsWith(".bin", ignoreCase = true)
                }?.name
            }
            .sorted()

        fun rootEntryExists(path: String): Boolean = resolveComponent(path)?.exists() == true

        fun requireComponent(
            path: String,
            missingCode: QairtBundleDiagnosticCode = QairtBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING,
            missingMessage: String? = null
        ): File? {
            val normalized = normalize(path) ?: run {
                invalidPath(path, "QAIRT config declares an unsafe component path: $path.")
                return null
            }
            required += normalized
            val file = resolveComponent(normalized)
            if (file == null) {
                invalidPath(normalized, "QAIRT component resolves outside the bundle root: $normalized.")
                return null
            }
            when {
                !file.exists() -> {
                    missing(
                        path = normalized,
                        code = missingCode,
                        message = missingMessage ?: "Required QAIRT component is missing: $normalized."
                    )
                    return null
                }
                !file.isFile -> {
                    unusable(
                        code = QairtBundleDiagnosticCode.REQUIRED_COMPONENT_NOT_FILE,
                        path = normalized,
                        message = "Required QAIRT component is not a file: $normalized."
                    )
                    return null
                }
                file.length() <= 0L -> {
                    unusable(
                        code = QairtBundleDiagnosticCode.REQUIRED_COMPONENT_EMPTY,
                        path = normalized,
                        message = "Required QAIRT component is empty: $normalized."
                    )
                    return null
                }
                !file.canRead() -> {
                    unusable(
                        code = QairtBundleDiagnosticCode.REQUIRED_COMPONENT_UNREADABLE,
                        path = normalized,
                        message = "Required QAIRT component is unreadable: $normalized."
                    )
                    return null
                }
                else -> return file
            }
        }

        fun readRequiredJson(path: String, isMetadata: Boolean = false): JSONObject? {
            val normalized = normalize(path) ?: run {
                invalidPath(path, "QAIRT config declares an unsafe JSON path: $path.")
                return null
            }
            val file = resolveComponent(normalized)
            if (file == null || !file.exists()) {
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_MISSING else QairtBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING,
                    path = normalized,
                    message = if (isMetadata) "QAIRT bundle is missing metadata.json." else "Required QAIRT JSON file is missing: $normalized."
                )
                missing += normalized
                return null
            }
            if (!file.isFile) {
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_NOT_FILE else QairtBundleDiagnosticCode.REQUIRED_COMPONENT_NOT_FILE,
                    path = normalized,
                    message = "Required QAIRT JSON path is not a file: $normalized."
                )
                invalid += normalized
                return null
            }
            if (file.length() <= 0L) {
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_EMPTY else QairtBundleDiagnosticCode.REQUIRED_COMPONENT_EMPTY,
                    path = normalized,
                    message = "Required QAIRT JSON file is empty: $normalized."
                )
                invalid += normalized
                return null
            }
            if (!file.canRead() || file.length() > MAX_JSON_BYTES) {
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_UNREADABLE else QairtBundleDiagnosticCode.REQUIRED_COMPONENT_UNREADABLE,
                    path = normalized,
                    message = "Required QAIRT JSON file is unreadable or too large: $normalized."
                )
                invalid += normalized
                return null
            }
            val value = runCatching {
                JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
            }.getOrElse { error ->
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_INVALID_JSON else QairtBundleDiagnosticCode.CONFIG_INVALID_JSON,
                    path = normalized,
                    message = "QAIRT JSON cannot be parsed ($normalized): ${error.message.orEmpty()}"
                )
                invalid += normalized
                return null
            }
            if (value !is JSONObject) {
                emit(
                    code = if (isMetadata) QairtBundleDiagnosticCode.METADATA_INVALID_JSON else QairtBundleDiagnosticCode.CONFIG_INVALID_JSON,
                    path = normalized,
                    message = "QAIRT JSON root must be an object: $normalized."
                )
                invalid += normalized
                return null
            }
            required += normalized
            return value
        }

        fun inspectConfig(path: String) {
            val normalized = normalize(path) ?: run {
                invalidPath(path, "QAIRT config declares an unsafe JSON path: $path.")
                return
            }
            if (!inspectedConfigs.add(normalized)) return
            val config = readRequiredJson(normalized) ?: return
            collectConfigComponentReferences(config, parentKey = null)
        }

        fun missing(path: String, code: QairtBundleDiagnosticCode, message: String) {
            missing += path
            emit(code, path, message)
        }

        fun invalidPath(path: String, message: String) {
            invalid += path
            emit(QairtBundleDiagnosticCode.DECLARED_COMPONENT_PATH_INVALID, path, message)
        }

        private fun unusable(code: QairtBundleDiagnosticCode, path: String, message: String) {
            invalid += path
            emit(code, path, message)
        }

        private fun collectConfigComponentReferences(value: Any?, parentKey: String?) {
            when (value) {
                is JSONObject -> {
                    value.keys().asSequence().toList().sorted().forEach { key ->
                        val child = value.opt(key)
                        val normalizedKey = key.lowercase()
                        when {
                            normalizedKey in DIRECT_CONFIG_PATH_KEYS && child is String -> requireComponent(child)
                            normalizedKey == "path" && parentKey in PATH_CONTAINER_KEYS && child is String -> requireComponent(child)
                            normalizedKey in CONTEXT_BIN_KEYS && child is JSONArray -> {
                                for (index in 0 until child.length()) {
                                    val raw = child.optString(index).trim()
                                    if (raw.isBlank()) {
                                        invalidPath(
                                            "$key[$index]",
                                            "QAIRT config declares a blank context shard at $key[$index]."
                                        )
                                    } else {
                                        requireComponent(raw)
                                    }
                                }
                            }
                        }
                        collectConfigComponentReferences(child, normalizedKey)
                    }
                }

                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        collectConfigComponentReferences(value.opt(index), parentKey)
                    }
                }
            }
        }

        private fun normalize(rawPath: String): String? {
            val value = rawPath.trim()
            if (value.isBlank() || value.indexOf('\u0000') >= 0) return null
            val candidate = File(value)
            if (candidate.isAbsolute) return null
            return value.replace('\\', '/')
        }

        private fun resolveComponent(path: String): File? = runCatching {
            File(root, path).canonicalFile
        }.getOrNull()?.takeIf { candidate ->
            candidate.path.startsWith(root.path + File.separator)
        }

        private fun emit(code: QairtBundleDiagnosticCode, path: String?, message: String) {
            if (emitted.add(code to path)) {
                diagnostics += QairtBundleDiagnostic(code, path, message)
            }
        }
    }

    private val DIRECT_CONFIG_PATH_KEYS = setOf(
        "extensions",
        "lut-path",
        "lut_path",
        "tokenizer_path",
        "embedding_path",
        "htp_config_path"
    )
    private val CONTEXT_BIN_KEYS = setOf("ctx-bins", "ctx_bins", "model_paths")
    private val PATH_CONTAINER_KEYS = setOf("tokenizer", "lut", "embedding")
}
