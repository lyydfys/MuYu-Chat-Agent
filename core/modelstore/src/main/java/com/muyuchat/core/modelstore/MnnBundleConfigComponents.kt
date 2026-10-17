package com.muyuchat.core.modelstore

import com.muyuchat.core.modelstore.MnnBundleReadinessAnalyzer.MnnBundleRequirement
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

    /**
     * Gemma 4's published MNN 3.5 visual graphs crash inside the MNN 3.6
     * Omni loader even when the base text graph and every sidecar are present.
     * This is deliberately a narrow, fail-safe gate: it applies only when a
     * visual processor is actually present/enabled and the graph metadata
     * explicitly reports the known incompatible exporter version. A text-only
     * isolation of the same package remains usable.
     */
internal fun MnnBundleReadinessAnalyzer.legacyVisualGraphRuntimeDiagnostic(bundleDir: File): MnnBundleDiagnostic? {
        val config = runCatching {
            readMnnImportConfiguration(bundleDir, lenient = true).root
        }.getOrNull()
        if (config?.optString("mca_import_mode") == "text_only") return null
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

internal fun MnnBundleReadinessAnalyzer.addConfiguredRequirement(
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
internal fun MnnBundleReadinessAnalyzer.collectLlmConfigComponentRequirements(
        bundleDir: File,
        rootConfig: JSONObject,
        requirements: MutableList<MnnBundleRequirement>,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>,
        normalizedModelConfig: JSONObject? = null
    ) {
        val rawLlmConfigPath = rootConfig.optString("llm_config").trim()
            .ifBlank { "llm_config.json" }
        val llmConfigPath = runCatching {
            normalizeRelativeComponentPath(rawLlmConfigPath)
        }.getOrNull() ?: return
        val llmConfig = normalizedModelConfig ?: runCatching {
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

internal fun MnnBundleReadinessAnalyzer.collectNestedComponentRequirements(
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

internal fun JSONObject.hasNonBlankString(key: String): Boolean =
        opt(key) is String && optString(key).isNotBlank()

internal fun MnnBundleReadinessAnalyzer.declaredRequirement(rawPath: String): MnnBundleRequirement =
        MnnBundleRequirement.single(normalizeRelativeComponentPath(rawPath))

internal fun MnnBundleReadinessAnalyzer.normalizeRelativeComponentPath(rawPath: String): String = normalizeMnnImportPath(rawPath)
