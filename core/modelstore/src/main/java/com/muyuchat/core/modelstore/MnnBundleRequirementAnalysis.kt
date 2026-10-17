package com.muyuchat.core.modelstore

import com.muyuchat.core.modelstore.MnnBundleReadinessAnalyzer.MnnBundleRequirement
import java.io.File
import org.json.JSONObject

internal fun MnnBundleReadinessAnalyzer.inspectRequirement(
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

internal fun MnnBundleReadinessAnalyzer.unusableComponentDiagnostic(path: String, file: File): MnnBundleDiagnostic = when {
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

internal fun MnnBundleReadinessAnalyzer.notApplicable(scope: MnnBundleLoadScope): MnnBundleReadiness = MnnBundleReadiness(
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

internal fun MnnBundleReadinessAnalyzer.blocked(diagnostic: MnnBundleDiagnostic): MnnBundleReadiness = MnnBundleReadiness(
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
internal fun MnnBundleReadinessAnalyzer.configDrivenChatLlmRequirements(
        bundleDir: File,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>,
        importMode: MnnImportMode
    ): List<MnnBundleRequirement> {
        val configFile = File(bundleDir, "config.json")
        val sourceConfig = runCatching {
            readBoundedMnnJson(configFile)
        }.getOrNull()
        val requirements = mutableListOf(MnnBundleRequirement.single("config.json"))
        if (sourceConfig == null) {
            requirements += defaultChatLlmRequirements()
            return requirements
        }

        val configuration = runCatching { readMnnImportConfiguration(bundleDir, importMode, lenient = true) }.getOrElse { error ->
            invalid += "config.json"
            diagnostics += MnnBundleDiagnostic(
                MnnBundleDiagnosticCode.CONFIG_DECLARED_COMPONENT_PATH_INVALID,
                MnnBundleDiagnosticSeverity.ERROR, "config.json", error.message.orEmpty()
            )
            null
        }
        val config = configuration?.root ?: sourceConfig

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
        modelConfig = configuration?.model ?: readModelConfig(bundleDir, config)
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
            diagnostics = diagnostics,
            normalizedModelConfig = modelConfig
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

internal fun MnnBundleReadinessAnalyzer.defaultChatLlmRequirements(): List<MnnBundleRequirement> = listOf(
        MnnBundleRequirement.single("llm_config.json"),
        MnnBundleRequirement.single("llm.mnn"),
        MnnBundleRequirement.single("llm.mnn.weight"),
        MnnBundleRequirement.single("embeddings_bf16.bin"),
        MnnBundleRequirement.alternatives("tokenizer.txt", "tokenizer.mtok")
    )

internal fun MnnBundleReadinessAnalyzer.readModelConfig(bundleDir: File, rootConfig: JSONObject): JSONObject? {
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
internal fun MnnBundleReadinessAnalyzer.collectEmbeddingRequirement(
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
