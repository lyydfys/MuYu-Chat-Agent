package com.muyuchat.core.modelstore

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Selects the contract to apply before an MNN-backed engine is loaded.
 *
 * The standard root-level MNN LLM layout is intentionally only a chat contract.
 * Vision and image engines use their own manifests and component layouts.
 */
enum class MnnBundleLoadScope {
    CHAT_LLM,
    VISION_ENGINE,
    IMAGE_ENGINE
}

enum class MnnBundleReadinessStatus {
    READY,
    BLOCKED,
    NOT_APPLICABLE
}

enum class MnnBundleDiagnosticSeverity {
    INFO,
    ERROR
}

enum class MnnBundleDiagnosticCode {
    BUNDLE_DIRECTORY_MISSING,
    BUNDLE_PATH_NOT_DIRECTORY,
    REQUIRED_COMPONENT_MISSING,
    REQUIRED_COMPONENT_EMPTY,
    REQUIRED_COMPONENT_NOT_FILE,
    REQUIRED_COMPONENT_UNREADABLE,
    CONFIG_DECLARED_COMPONENT_PATH_INVALID,
    TIE_EMBEDDINGS_INVALID,
    LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
    CHAT_LLM_CONTRACT_NOT_APPLICABLE
}

data class MnnBundleDiagnostic(
    val code: MnnBundleDiagnosticCode,
    val severity: MnnBundleDiagnosticSeverity,
    val path: String? = null,
    val message: String
)

/**
 * A structured pre-load result for one MNN bundle contract.
 *
 * [canLoad] means this check does not block the caller. A NOT_APPLICABLE result
 * is non-blocking because a different engine-specific validator owns that layout.
 */
data class MnnBundleReadiness(
    val scope: MnnBundleLoadScope,
    val status: MnnBundleReadinessStatus,
    /**
     * Concrete component paths selected while evaluating the contract.
     *
     * Alternatives are resolved to the usable file that was found. Callers use
     * this list for the persisted bundle fingerprint, so a tokenizer or model
     * sidecar cannot disappear after a successful registration without being
     * noticed on the next preflight.
     */
    val requiredComponentPaths: List<String> = emptyList(),
    val missingRequiredComponents: List<String> = emptyList(),
    val invalidRequiredComponents: List<String> = emptyList(),
    val diagnostics: List<MnnBundleDiagnostic> = emptyList()
) {
    val canLoad: Boolean
        get() = status != MnnBundleReadinessStatus.BLOCKED

    val applies: Boolean
        get() = status != MnnBundleReadinessStatus.NOT_APPLICABLE

    fun diagnosticSummary(): String = when (status) {
        MnnBundleReadinessStatus.READY -> "MNN chat LLM bundle is ready."
        MnnBundleReadinessStatus.NOT_APPLICABLE ->
            "The standard MNN chat LLM bundle contract does not apply to ${scope.displayName()}."
        MnnBundleReadinessStatus.BLOCKED -> buildString {
            append("MNN chat LLM bundle is blocked")
            if (missingRequiredComponents.isNotEmpty()) {
                append(": missing ")
                append(missingRequiredComponents.joinToString(", "))
            }
            if (invalidRequiredComponents.isNotEmpty()) {
                append(if (missingRequiredComponents.isEmpty()) ": unusable " else "; unusable ")
                append(invalidRequiredComponents.joinToString(", "))
            }
            if (missingRequiredComponents.isEmpty() && invalidRequiredComponents.isEmpty()) {
                diagnostics.firstOrNull()?.message?.let { message ->
                    append(": ")
                    append(message)
                }
            }
            append('.')
        }
    }
}

/**
 * Checks only the standard MNN chat-LLM root layout before native loading.
 *
 * It deliberately does not validate vision or image MNN bundles: their components
 * can be nested and have engine-specific contracts.
 */
object MnnBundleReadinessAnalyzer {
    fun analyze(
        bundleDir: File,
        scope: MnnBundleLoadScope = MnnBundleLoadScope.CHAT_LLM,
        /**
         * Model manifests can declare extra assets without weakening the base
         * chat contract. This keeps the validator forward-compatible with
         * future config-driven visual/audio package layouts.
         */
        additionalRequiredComponents: List<String> = emptyList()
    ): MnnBundleReadiness {
        if (scope != MnnBundleLoadScope.CHAT_LLM) return notApplicable(scope)
        if (!bundleDir.exists()) {
            return blocked(
                diagnostic = MnnBundleDiagnostic(
                    code = MnnBundleDiagnosticCode.BUNDLE_DIRECTORY_MISSING,
                    severity = MnnBundleDiagnosticSeverity.ERROR,
                    path = bundleDir.absolutePath,
                    message = "MNN chat bundle directory does not exist."
                )
            )
        }
        if (!bundleDir.isDirectory) {
            return blocked(
                diagnostic = MnnBundleDiagnostic(
                    code = MnnBundleDiagnosticCode.BUNDLE_PATH_NOT_DIRECTORY,
                    severity = MnnBundleDiagnosticSeverity.ERROR,
                    path = bundleDir.absolutePath,
                    message = "MNN chat bundle path is not a directory."
                )
            )
        }

        val missing = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        val diagnostics = mutableListOf<MnnBundleDiagnostic>()
        val usable = mutableListOf<String>()
        val requirements = (
            configDrivenChatLlmRequirements(bundleDir, invalid, diagnostics) +
                additionalRequiredComponents.map(::declaredRequirement)
            ).distinctBy { requirement -> requirement.paths }
        requirements.forEach { requirement ->
            inspectRequirement(bundleDir, requirement, usable, missing, invalid, diagnostics)
        }
        legacyVisualGraphRuntimeDiagnostic(bundleDir)?.let(diagnostics::add)
        return if (diagnostics.isEmpty()) {
            MnnBundleReadiness(
                scope = MnnBundleLoadScope.CHAT_LLM,
                status = MnnBundleReadinessStatus.READY,
                requiredComponentPaths = usable.distinct()
            )
        } else {
            MnnBundleReadiness(
                scope = MnnBundleLoadScope.CHAT_LLM,
                status = MnnBundleReadinessStatus.BLOCKED,
                requiredComponentPaths = usable.distinct(),
                missingRequiredComponents = missing,
                invalidRequiredComponents = invalid,
                diagnostics = diagnostics
            )
        }
    }

    private fun inspectRequirement(
        bundleDir: File,
        requirement: MnnBundleRequirement,
        usable: MutableList<String>,
        missing: MutableList<String>,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>
    ) {
        val candidates = requirement.paths.map { path -> path to File(bundleDir, path) }
        candidates.firstOrNull { (_, file) -> file.isUsableBundleComponent() }
            ?.let { (path, _) ->
                usable += path
                return
            }

        val existingCandidates = candidates.filter { (_, file) -> file.exists() }
        if (existingCandidates.isEmpty()) {
            missing += requirement.displayName
            diagnostics += MnnBundleDiagnostic(
                code = MnnBundleDiagnosticCode.REQUIRED_COMPONENT_MISSING,
                severity = MnnBundleDiagnosticSeverity.ERROR,
                path = requirement.displayName,
                message = "Required MNN chat component is missing: ${requirement.displayName}."
            )
            return
        }

        existingCandidates.forEach { (path, file) ->
            invalid += path
            diagnostics += unusableComponentDiagnostic(path, file)
        }
    }

    private fun unusableComponentDiagnostic(path: String, file: File): MnnBundleDiagnostic = when {
        !file.isFile -> MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.REQUIRED_COMPONENT_NOT_FILE,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = path,
            message = "Required MNN chat component is not a file: $path."
        )
        file.length() <= 0L -> MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.REQUIRED_COMPONENT_EMPTY,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = path,
            message = "Required MNN chat component is empty: $path."
        )
        else -> MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.REQUIRED_COMPONENT_UNREADABLE,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = path,
            message = "Required MNN chat component is not readable: $path."
        )
    }

    private fun notApplicable(scope: MnnBundleLoadScope): MnnBundleReadiness = MnnBundleReadiness(
        scope = scope,
        status = MnnBundleReadinessStatus.NOT_APPLICABLE,
        diagnostics = listOf(
            MnnBundleDiagnostic(
                code = MnnBundleDiagnosticCode.CHAT_LLM_CONTRACT_NOT_APPLICABLE,
                severity = MnnBundleDiagnosticSeverity.INFO,
                message = "The standard MNN chat LLM contract does not apply to ${scope.displayName()}."
            )
        )
    )

    private fun blocked(diagnostic: MnnBundleDiagnostic): MnnBundleReadiness = MnnBundleReadiness(
        scope = MnnBundleLoadScope.CHAT_LLM,
        status = MnnBundleReadinessStatus.BLOCKED,
        diagnostics = listOf(diagnostic)
    )

    /**
     * Build the actual upstream MNN LLM file contract from config.json.
     *
     * Earlier code always required the default names (llm.mnn, llm_config.json,
     * etc.). That accepted a half-installed multimodal bundle when the default
     * visual graph was absent, but also rejected valid exporters that deliberately
     * used nested or renamed files. MNN's LlmConfig treats the values below as
     * relative paths, so registration must use the same interpretation before
     * native loading begins.
     *
     * An unreadable or legacy non-JSON config retains the documented default
     * contract. Native loading will still report its own config parse error; this
     * preflight only owns component completeness.
     */
    private fun configDrivenChatLlmRequirements(
        bundleDir: File,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>
    ): List<MnnBundleRequirement> {
        val configFile = File(bundleDir, "config.json")
        val config = runCatching {
            JSONObject(configFile.readText(Charsets.UTF_8))
        }.getOrNull()
        val requirements = mutableListOf(MnnBundleRequirement.single("config.json"))
        if (config == null) {
            requirements += defaultChatLlmRequirements()
            return requirements
        }

        var modelConfig: JSONObject? = null

        fun effectiveHasNonBlankString(key: String): Boolean =
            modelConfig
                ?.takeIf { it.has(key) }
                ?.hasNonBlankString(key)
                ?: config.hasNonBlankString(key)

        fun effectiveBoolean(key: String): Boolean =
            modelConfig
                ?.takeIf { it.has(key) }
                ?.optBoolean(key, false)
                ?: config.optBoolean(key, false)

        fun configuredPath(
            key: String,
            defaultPath: String? = null,
            allowTokenizerAlternative: Boolean = false,
            rootConfigOnly: Boolean = false
        ) {
            val source = modelConfig
                ?.takeUnless { rootConfigOnly }
                ?.takeIf { it.opt(key) is String }
                ?: config
            val raw = source.optString(key).trim()
            if (raw.isBlank()) {
                when {
                    allowTokenizerAlternative -> requirements += MnnBundleRequirement.alternatives(
                        "tokenizer.txt",
                        "tokenizer.mtok"
                    )
                    defaultPath != null -> addConfiguredRequirement(
                        requirements,
                        key,
                        defaultPath,
                        invalid,
                        diagnostics
                    )
                }
            } else {
                addConfiguredRequirement(requirements, key, raw, invalid, diagnostics)
            }
        }

        configuredPath("llm_config", defaultPath = "llm_config.json", rootConfigOnly = true)
        modelConfig = readModelConfig(bundleDir, config)
        configuredPath("llm_model", defaultPath = "llm.mnn")
        configuredPath("llm_weight", defaultPath = "llm.mnn.weight")
        collectEmbeddingRequirement(
            bundleDir = bundleDir,
            rootConfig = config,
            modelConfig = modelConfig,
            requirements = requirements,
            invalid = invalid,
            diagnostics = diagnostics
        )
        configuredPath("tokenizer_file", allowTokenizerAlternative = true)

        // Gemma 4's Per-Layer Embeddings are declared by the model-side
        // llm_config.json rather than the runtime config.json.  The upstream
        // MNN 3.6 LLM loader opens that sidecar and passes `ple_embeddings` to
        // Module::load(), so it is part of the chat-load contract even though
        // it is not a generic root config field.  Read only the explicit
        // file-valued declaration; this remains forward-compatible for other
        // model families and does not turn arbitrary llm_config values into
        // filesystem paths.
        collectLlmConfigComponentRequirements(
            bundleDir = bundleDir,
            rootConfig = config,
            requirements = requirements,
            invalid = invalid,
            diagnostics = diagnostics
        )

        // These components have no universal default requirement. Their explicit
        // config declaration is a promise that the file accompanies this bundle.
        listOf(
            "lm_model",
            "embedding_model",
            "context_file",
            "ple_model",
            "ple_weight",
            "visual_weight",
            "audio_weight",
            "projector_model",
            "projector_weight"
        ).forEach { key ->
            if (effectiveHasNonBlankString(key)) {
                configuredPath(key)
            }
        }

        val visualConfigured = effectiveHasNonBlankString("visual_model")
        if (effectiveBoolean("is_visual") || visualConfigured) {
            configuredPath("visual_model", defaultPath = "visual.mnn")
        }
        val audioConfigured = effectiveHasNonBlankString("audio_model")
        if (effectiveBoolean("is_audio") || audioConfigured) {
            configuredPath("audio_model", defaultPath = "audio.mnn")
        }

        // Some MNN exporters keep modality components under mllm/processor
        // sections. Capture only recognised component keys recursively; general
        // strings such as model_type and backend_type are never treated as files.
        collectNestedComponentRequirements(
            config,
            requirements,
            invalid,
            diagnostics,
            inspectCurrentObjectKeys = false
        )
        return requirements
    }

    private fun defaultChatLlmRequirements(): List<MnnBundleRequirement> = listOf(
        MnnBundleRequirement.single("llm_config.json"),
        MnnBundleRequirement.single("llm.mnn"),
        MnnBundleRequirement.single("llm.mnn.weight"),
        MnnBundleRequirement.single("embeddings_bf16.bin"),
        MnnBundleRequirement.alternatives("tokenizer.txt", "tokenizer.mtok")
    )

    private fun readModelConfig(bundleDir: File, rootConfig: JSONObject): JSONObject? {
        val rawPath = rootConfig.optString("llm_config").trim().ifBlank { "llm_config.json" }
        val path = runCatching { normalizeRelativeComponentPath(rawPath) }.getOrNull() ?: return null
        return runCatching {
            JSONObject(File(bundleDir, path).readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * MNN 3.6 can read tied token embeddings directly from llm.mnn.weight.
     * The older readiness gate required an unrelated llm.mnn.json metadata
     * sidecar instead, rejecting valid tied bundles while accepting bundles
     * with no readable embedding data. Mirror DiskEmbedding's actual storage
     * choice and validate every range before native code can seek into it.
     */
    private fun collectEmbeddingRequirement(
        bundleDir: File,
        rootConfig: JSONObject,
        modelConfig: JSONObject?,
        requirements: MutableList<MnnBundleRequirement>,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>
    ) {
        fun effectiveString(key: String, defaultValue: String): String {
            val modelValue = modelConfig?.opt(key)
            if (modelValue is String && modelValue.isNotBlank()) return modelValue.trim()
            val rootValue = rootConfig.opt(key)
            if (rootValue is String && rootValue.isNotBlank()) return rootValue.trim()
            return defaultValue
        }

        val tieDeclared = modelConfig?.has("tie_embeddings") == true ||
            rootConfig.has("tie_embeddings")
        val tieValue = when {
            modelConfig?.has("tie_embeddings") == true -> modelConfig.opt("tie_embeddings")
            rootConfig.has("tie_embeddings") -> rootConfig.opt("tie_embeddings")
            else -> null
        }
        if (!tieDeclared) {
            addConfiguredRequirement(
                requirements,
                "embedding_file",
                effectiveString("embedding_file", "embeddings_bf16.bin"),
                invalid,
                diagnostics
            )
            return
        }

        val descriptor = runCatching {
            require(tieValue != null && tieValue != JSONObject.NULL) {
                "tie_embeddings must not be null."
            }
            parseTieEmbeddingDescriptor(tieValue)
        }.getOrElse { error ->
            addInvalidTieEmbeddingDiagnostic(
                rootConfig,
                invalid,
                diagnostics,
                error.message.orEmpty()
            )
            return
        }
        val storageKey = if (descriptor.weightOffset > 0L) "llm_weight" else "embedding_file"
        val storageDefault = if (descriptor.weightOffset > 0L) {
            "llm.mnn.weight"
        } else {
            "embeddings_bf16.bin"
        }
        val storagePath = effectiveString(storageKey, storageDefault)
        if (descriptor.weightOffset == 0L) {
            addConfiguredRequirement(
                requirements,
                storageKey,
                storagePath,
                invalid,
                diagnostics
            )
        }

        val normalizedStoragePath = runCatching {
            normalizeRelativeComponentPath(storagePath)
        }.getOrNull() ?: return
        val storage = File(bundleDir, normalizedStoragePath)
        if (!storage.isUsableBundleComponent()) return
        val hiddenSize = effectiveLong(modelConfig, rootConfig, "hidden_size")
        val validationError = validateTieEmbeddingDescriptor(
            descriptor = descriptor,
            hiddenSize = hiddenSize,
            storageSize = storage.length()
        )
        if (validationError != null) {
            addInvalidTieEmbeddingDiagnostic(rootConfig, invalid, diagnostics, validationError)
        }
    }

    private fun addInvalidTieEmbeddingDiagnostic(
        rootConfig: JSONObject,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>,
        detail: String
    ) {
        val llmConfigPath = rootConfig.optString("llm_config").trim().ifBlank { "llm_config.json" }
        val component = "$llmConfigPath: tie_embeddings"
        invalid += component
        diagnostics += MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.TIE_EMBEDDINGS_INVALID,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = llmConfigPath,
            message = "$component is invalid: $detail"
        )
    }

    private data class TieEmbeddingDescriptor(
        val weightOffset: Long,
        val alphaOffset: Long,
        val alphaSize: Long,
        val quantBit: Long,
        val quantBlock: Long,
        val alphaFp16: Boolean
    )

    private fun parseTieEmbeddingDescriptor(value: Any): TieEmbeddingDescriptor {
        fun exactLong(raw: Any?, field: String): Long {
            require(raw is Number) { "$field must be an integer." }
            val doubleValue = raw.toDouble()
            val longValue = raw.toLong()
            require(doubleValue.isFinite() && doubleValue == longValue.toDouble()) {
                "$field must be an exact 64-bit integer."
            }
            return longValue
        }

        return when (value) {
            is JSONArray -> {
                require(value.length() >= 5) { "legacy tie_embeddings must contain at least five integers." }
                TieEmbeddingDescriptor(
                    weightOffset = exactLong(value.opt(0), "weight_offset"),
                    alphaOffset = exactLong(value.opt(1), "alpha_offset"),
                    alphaSize = exactLong(value.opt(2), "alpha_size"),
                    quantBit = exactLong(value.opt(3), "quant_bit"),
                    quantBlock = exactLong(value.opt(4), "quant_block"),
                    alphaFp16 = value.opt(5).let { raw ->
                        when (raw) {
                            null, JSONObject.NULL -> false
                            is Boolean -> raw
                            is Number -> exactLong(raw, "alpha_fp16") != 0L
                            else -> error("alpha_fp16 must be a boolean or integer.")
                        }
                    }
                )
            }
            is JSONObject -> {
                val alphaDtype = value.optString("alpha_dtype", "fp32").lowercase()
                require(alphaDtype == "fp16" || alphaDtype == "fp32") {
                    "alpha_dtype must be fp16 or fp32."
                }
                TieEmbeddingDescriptor(
                    weightOffset = exactLong(value.opt("weight_offset"), "weight_offset"),
                    alphaOffset = exactLong(value.opt("alpha_offset"), "alpha_offset"),
                    alphaSize = exactLong(value.opt("alpha_size"), "alpha_size"),
                    quantBit = exactLong(value.opt("quant_bit"), "quant_bit"),
                    quantBlock = exactLong(value.opt("quant_block"), "quant_block"),
                    alphaFp16 = alphaDtype == "fp16"
                )
            }
            else -> error("tie_embeddings must be an array or object.")
        }
    }

    private fun effectiveLong(modelConfig: JSONObject?, rootConfig: JSONObject, key: String): Long {
        val raw = when {
            modelConfig?.opt(key) is Number -> modelConfig.opt(key)
            rootConfig.opt(key) is Number -> rootConfig.opt(key)
            else -> null
        }
        return (raw as? Number)?.toLong() ?: 0L
    }

    private fun validateTieEmbeddingDescriptor(
        descriptor: TieEmbeddingDescriptor,
        hiddenSize: Long,
        storageSize: Long
    ): String? {
        if (descriptor.weightOffset < 0L || descriptor.alphaOffset < 0L || descriptor.alphaSize <= 0L) {
            return "offsets must be non-negative and alpha_size must be positive"
        }
        if (hiddenSize <= 0L) return "hidden_size must be positive"
        if (descriptor.quantBit != 4L && descriptor.quantBit != 8L) {
            return "quant_bit must be 4 or 8"
        }
        if (descriptor.quantBlock < 0L || descriptor.quantBlock > hiddenSize ||
            (descriptor.quantBlock > 0L && hiddenSize % descriptor.quantBlock != 0L)
        ) {
            return "quant_block must be zero or divide hidden_size exactly"
        }
        if (descriptor.alphaOffset <= descriptor.weightOffset) {
            return "alpha_offset must follow the quantized weight range"
        }
        if (descriptor.alphaOffset > storageSize ||
            descriptor.alphaSize > storageSize - descriptor.alphaOffset
        ) {
            return "alpha range exceeds $storageSize-byte backing file"
        }
        val tokenBits = runCatching { Math.multiplyExact(hiddenSize, descriptor.quantBit) }.getOrNull()
            ?: return "token width overflows"
        if (tokenBits % 8L != 0L) return "token width is not byte-aligned"
        val tokenBytes = tokenBits / 8L
        val weightBytes = descriptor.alphaOffset - descriptor.weightOffset
        if (tokenBytes <= 0L || weightBytes <= 0L || weightBytes % tokenBytes != 0L) {
            return "quantized weight range is not a whole number of token rows"
        }
        val vocabularySize = weightBytes / tokenBytes
        val blockCount = if (descriptor.quantBlock == 0L) 1L else hiddenSize / descriptor.quantBlock
        val alphaElementBytes = if (descriptor.alphaFp16) 2L else 4L
        if (descriptor.alphaSize % alphaElementBytes != 0L) {
            return "alpha_size is not aligned to alpha_dtype"
        }
        val symmetricElements = runCatching {
            Math.multiplyExact(vocabularySize, blockCount)
        }.getOrNull() ?: return "scale count overflows"
        val asymmetricElements = runCatching {
            Math.multiplyExact(symmetricElements, 2L)
        }.getOrNull() ?: return "scale count overflows"
        val actualElements = descriptor.alphaSize / alphaElementBytes
        if (actualElements != symmetricElements && actualElements != asymmetricElements) {
            return "alpha range does not match token and quant-block geometry"
        }
        return null
    }

    /**
     * Gemma 4's published MNN 3.5 visual graphs crash inside the MNN 3.6
     * Omni loader even when the base text graph and every sidecar are present.
     * This is deliberately a narrow, fail-safe gate: it applies only when a
     * visual processor is actually present/enabled and the graph metadata
     * explicitly reports the known incompatible exporter version. A text-only
     * isolation of the same package remains usable.
     */
    private fun legacyVisualGraphRuntimeDiagnostic(bundleDir: File): MnnBundleDiagnostic? {
        val config = runCatching {
            JSONObject(File(bundleDir, "config.json").readText(Charsets.UTF_8))
        }.getOrNull()
        val configuredVisual = config?.optString("visual_model").orEmpty().trim()
        val configuredVisualFile = configuredVisual
            .takeIf(String::isNotBlank)
            ?.let { path ->
                runCatching { File(bundleDir, normalizeRelativeComponentPath(path)) }.getOrNull()
            }
        val visualEnabled = config?.optBoolean("is_visual", false) == true ||
            configuredVisual.isNotBlank() ||
            configuredVisualFile?.isUsableBundleComponent() == true ||
            File(bundleDir, DEFAULT_VISUAL_MODEL_PATH).isUsableBundleComponent()
        if (!visualEnabled) return null

        val metadataPaths = buildList {
            fun addMetadataPath(rawPath: String?) {
                rawPath
                    ?.trim()
                    ?.takeIf { it.endsWith(".json", ignoreCase = true) }
                    ?.let { path -> runCatching { normalizeRelativeComponentPath(path) }.getOrNull() }
                    ?.let(::add)
            }

            // Exporters that rename llm.mnn also rename its metadata sidecar
            // to <llm_model>.json.  Checking only the default sidecar would
            // allow the known 3.5 visual graph through this fail-safe gate.
            addMetadataPath(config?.optString("embedding_file"))
            config?.optString("llm_model")
                ?.trim()
                ?.takeIf { it.endsWith(".mnn", ignoreCase = true) }
                ?.let { modelPath -> addMetadataPath("$modelPath.json") }
            configuredVisual
                .ifBlank { DEFAULT_VISUAL_MODEL_PATH }
                .takeIf { it.endsWith(".mnn", ignoreCase = true) }
                ?.let { visualPath -> addMetadataPath("$visualPath.json") }
            add(DEFAULT_GRAPH_METADATA_PATH)
        }.distinct()
        val incompatibleMetadata = metadataPaths.firstOrNull { path ->
            val metadata = runCatching {
                JSONObject(File(bundleDir, path).readText(Charsets.UTF_8))
            }.getOrNull() ?: return@firstOrNull false
            metadata.optJSONObject("extraInfo")
                ?.optString("version")
                ?.trim() == LEGACY_VISUAL_GRAPH_VERSION
        } ?: return null

        return MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.LEGACY_VISUAL_GRAPH_RUNTIME_INCOMPATIBLE,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = incompatibleMetadata,
            message = "MNN 多模态图版本 $LEGACY_VISUAL_GRAPH_VERSION 与当前 MNN $PRODUCT_RUNTIME_VERSION " +
                "runtime 不兼容，已安全阻止加载。请使用 MNN $PRODUCT_RUNTIME_VERSION 重新导出的多模态包，" +
                "或移除视觉组件后仅作为文本模型使用。"
        )
    }

    private fun addConfiguredRequirement(
        requirements: MutableList<MnnBundleRequirement>,
        key: String,
        rawPath: String,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>
    ) {
        val requirement = runCatching { declaredRequirement(rawPath) }.getOrElse { error ->
            invalid += "config.json: $key"
            diagnostics += MnnBundleDiagnostic(
                code = MnnBundleDiagnosticCode.CONFIG_DECLARED_COMPONENT_PATH_INVALID,
                severity = MnnBundleDiagnosticSeverity.ERROR,
                path = "config.json",
                message = "config.json declares an unsafe $key path: ${error.message.orEmpty()}"
            )
            return
        }
        requirements += requirement
    }

    /**
     * Collect component files declared by the model-side LLM config.
     *
     * MNN's root config defaults to `llm_config.json`, but exporters may
     * relocate it through `llm_config`.  The root contract already validates
     * that path; this helper follows the same safe-relative-path rule before
     * opening it and adds only PLE's documented sidecar declaration.
     */
    private fun collectLlmConfigComponentRequirements(
        bundleDir: File,
        rootConfig: JSONObject,
        requirements: MutableList<MnnBundleRequirement>,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>
    ) {
        val rawLlmConfigPath = rootConfig.optString("llm_config").trim()
            .ifBlank { "llm_config.json" }
        val llmConfigPath = runCatching {
            normalizeRelativeComponentPath(rawLlmConfigPath)
        }.getOrNull() ?: return
        val llmConfig = runCatching {
            JSONObject(File(bundleDir, llmConfigPath).readText(Charsets.UTF_8))
        }.getOrNull() ?: return
        val pleEmbedFile = llmConfig.optString("ple_embed_file").trim()
        if (pleEmbedFile.isBlank()) return

        val requirement = runCatching { declaredRequirement(pleEmbedFile) }.getOrElse { error ->
            invalid += "$llmConfigPath: ple_embed_file"
            diagnostics += MnnBundleDiagnostic(
                code = MnnBundleDiagnosticCode.CONFIG_DECLARED_COMPONENT_PATH_INVALID,
                severity = MnnBundleDiagnosticSeverity.ERROR,
                path = llmConfigPath,
                message = "$llmConfigPath declares an unsafe ple_embed_file path: " +
                    error.message.orEmpty()
            )
            return
        }
        requirements += requirement
    }

    private fun collectNestedComponentRequirements(
        value: Any?,
        requirements: MutableList<MnnBundleRequirement>,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>,
        inspectCurrentObjectKeys: Boolean = true
    ) {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (inspectCurrentObjectKeys && key in NESTED_COMPONENT_KEYS && child is String && child.isNotBlank()) {
                        addConfiguredRequirement(requirements, key, child, invalid, diagnostics)
                    }
                    collectNestedComponentRequirements(child, requirements, invalid, diagnostics)
                }
            }
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectNestedComponentRequirements(value.opt(index), requirements, invalid, diagnostics)
                }
            }
        }
    }

    private fun JSONObject.hasNonBlankString(key: String): Boolean =
        opt(key) is String && optString(key).isNotBlank()

    private fun declaredRequirement(rawPath: String): MnnBundleRequirement =
        MnnBundleRequirement.single(normalizeRelativeComponentPath(rawPath))

    private fun normalizeRelativeComponentPath(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/')
        require(normalized.isNotBlank()) { "MNN component path must not be blank." }
        require('\u0000' !in normalized) { "MNN component path contains a NUL character." }
        require(!normalized.startsWith('/')) { "MNN component path must be relative: $rawPath" }
        require(!WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
            "MNN component path must be relative: $rawPath"
        }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
            "MNN component path contains an invalid segment: $rawPath"
        }
        return segments.joinToString("/")
    }

    private data class MnnBundleRequirement(
        val paths: List<String>,
        val displayName: String
    ) {
        companion object {
            fun single(path: String): MnnBundleRequirement = MnnBundleRequirement(
                paths = listOf(path),
                displayName = path
            )

            fun alternatives(vararg paths: String): MnnBundleRequirement = MnnBundleRequirement(
                paths = paths.toList(),
                displayName = paths.joinToString(" or ")
            )
        }
    }

    private val NESTED_COMPONENT_KEYS = setOf(
        "llm_config",
        "llm_model",
        "llm_weight",
        "lm_model",
        "embedding_file",
        "embedding_model",
        "tokenizer_file",
        "visual_model",
        "visual_weight",
        "audio_model",
        "audio_weight",
        "context_file",
        "ple_model",
        "ple_weight",
        "projector_model",
        "projector_weight"
    )
    private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:($|/)")

    private const val DEFAULT_VISUAL_MODEL_PATH = "visual.mnn"
    private const val DEFAULT_GRAPH_METADATA_PATH = "llm.mnn.json"
    private const val LEGACY_VISUAL_GRAPH_VERSION = "3.5.0"
    // Keep this in lock-step with the pinned vendor runtime in vendor/mnn.
    private const val PRODUCT_RUNTIME_VERSION = "3.6"
}

private fun File.isUsableBundleComponent(): Boolean =
    isFile && length() > 0L && canRead()

private fun MnnBundleLoadScope.displayName(): String =
    name.lowercase().replace('_', ' ')
