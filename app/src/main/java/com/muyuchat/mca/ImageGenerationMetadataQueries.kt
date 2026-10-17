package com.muyuchat.mca

import org.json.JSONObject
import org.json.JSONArray

/** T2B22-T4: generation history JSON/share/recreate queries; moved verbatim from ImageGenerationHistoryMetadata.kt. */

internal fun ImageGenerationHistoryMetadata.toJsonString(): String = JSONObject()
        .put(
            "version",
            if (backend == ImageBackend.LOCAL && promptExecution == null) {
                ImageGenerationHistoryMetadata.LAST_VERSION_WITHOUT_PROMPT_EXECUTION
            } else {
                ImageGenerationHistoryMetadata.VERSION
            }
        )
        .put("backend", backend.name)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("requestPrompt", requestPrompt)
        .put("sourceGenerationAvailable", sourceGenerationAvailable)
        .put(
            "options",
            options.withCanonicalPersistedUltraFixControls().copy(
                inputImage = null,
                maskImage = null,
                controlImage = null,
                taskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
                strength = null,
                controlStrength = null,
                loras = emptyList(),
                preview = null
            ).toJson()
        )
        .put("inputDraft", inputDraft.toHistoryJson())
        .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        .put("upscaleHistory", JSONArray().apply { upscaleHistory.forEach { put(it.toJson()) } })
        .apply {
            promptExecution?.let { put("promptExecution", it.toJson()) }
            batchLineage?.let { put("batchLineage", it.toJson()) }
            sanitizeNativeExecutionJson(nativeExecutionJson).takeIf(String::isNotEmpty)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { put("nativeExecution", it) }
            }
        }
        .toString()

internal fun ImageGenerationHistoryMetadata.withNativeExecution(nativeExecutionJson: String): ImageGenerationHistoryMetadata {
        val nativeEffective = runCatching {
            JSONObject(nativeExecutionJson).getJSONObject("nativeEffective")
        }.getOrNull() ?: return copy(
            nativeExecutionJson = sanitizeNativeExecutionJson(nativeExecutionJson)
        )
        return copy(
            options = options.copy(
                width = nativeEffective.historyExactIntOrNull("width") ?: options.width,
                height = nativeEffective.historyExactIntOrNull("height") ?: options.height,
                steps = nativeEffective.historyExactIntOrNull("steps") ?: options.steps,
                seed = nativeEffective.historyExactIntOrNull("seed") ?: options.seed,
                cfgScale = nativeEffective.historyFiniteDoubleOrNull("cfgScale") ?: options.cfgScale,
                sampleMethod = nativeEffective.historyNonBlankStringOrNull("sampleMethod")
                    ?: options.sampleMethod,
                clipSkip = nativeEffective.historyExactIntOrNull("clipSkip") ?: options.clipSkip,
                batchCount = nativeEffective.historyExactIntOrNull("batchCount")
                    ?.takeIf { it > 0 }
                    ?: options.batchCount
            ),
            nativeExecutionJson = sanitizeNativeExecutionJson(nativeExecutionJson)
        )
    }

internal fun ImageGenerationHistoryMetadata.withUpscale(lineage: ImageUpscaleHistoryMetadata): ImageGenerationHistoryMetadata = copy(
        upscaleHistory = (upscaleHistory + lineage).takeLast(ImageGenerationHistoryMetadata.MAX_UPSCALE_HISTORY)
    )

    /** Specializes parent/native-batch evidence for one library image without falsifying it. */
internal fun ImageGenerationHistoryMetadata.forBatchOutput(lineage: ImageGenerationBatchLineage): ImageGenerationHistoryMetadata = copy(
        options = options.copy(seed = lineage.seed, batchCount = 1),
        batchLineage = lineage
    )

    /** Portable, secret-free parameters for clipboard sharing and selective reuse. */
internal fun ImageGenerationHistoryMetadata.toShareJson(): String {
        val portableOptions = options.withCanonicalPersistedUltraFixControls()
        return JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("modelId", modelId)
            .put("modelName", modelName)
            .put("prompt", requestPrompt)
            .put("taskMode", inputDraft.taskMode.wireName)
            .put("batchCount", portableOptions.batchCount)
            .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
            .put("textualInversionIds", JSONArray(portableOptions.textualInversionIds))
            .apply {
                when (val negative = portableOptions.negativePrompt) {
                    null -> put("negativePromptMode", "model_default")
                    else -> {
                        put("negativePromptMode", "explicit")
                        put("negativePrompt", negative)
                    }
                }
                portableOptions.width?.let { put("width", it) }
                portableOptions.height?.let { put("height", it) }
                portableOptions.steps?.let { put("steps", it) }
                portableOptions.cfgScale?.let { put("cfgScale", it) }
                portableOptions.seed?.let { put("seed", it) }
                portableOptions.sampleMethod?.takeIf(String::isNotBlank)?.let {
                    put("sampleMethod", it)
                }
                portableOptions.clipSkip?.let { put("clipSkip", it) }
                portableOptions.vaeTiling?.let { tiling ->
                    put(
                        "vaeTiling",
                        JSONObject()
                            .put("tileSize", tiling.tileSize)
                            .put("overlap", tiling.overlap)
                    )
                }
                portableOptions.ultraFix?.let { put("ultraFix", it.toJson()) }
                inputDraft.strength?.let { put("strength", it) }
                inputDraft.controlStrength?.let { put("controlStrength", it) }
            }
            .toString()
    }

    /** Backup-safe history metadata without device-local paths or transient native evidence. */
internal fun ImageGenerationHistoryMetadata.toPortableBackupJsonString(): String = copy(
        inputDraft = inputDraft.copy(
            inputImageReference = null,
            maskImageReference = null,
            controlImageReference = null
        ),
        nativeExecutionJson = ""
    ).toJsonString()

internal fun ImageGenerationHistoryMetadata.canRecreate(): Boolean = sourceGenerationAvailable && inputDraft.hasRequiredInputReferences()

    /**
     * Product operation used by the image library. This is deliberately derived instead of
     * persisted: UltraFix is an img2img execution variant, so its wire taskMode must remain
     * `img2img` for share/recreate/API compatibility while history may expose it separately.
     */
internal fun ImageGenerationHistoryMetadata.operationFacet(): ImageGenerationHistoryOperation =
        ImageGenerationHistoryOperation.from(
            taskMode = inputDraft.taskMode,
            ultraFix = options.ultraFix != null
        )

    /** Required, path-free inputs that must retain their generation-owned read grant for recreate. */
internal fun ImageGenerationHistoryMetadata.requiredContentInputReferences(): Set<String> {
        if (!sourceGenerationAvailable) return emptySet()
        val required = when (inputDraft.taskMode) {
            LocalImageTaskMode.TEXT_TO_IMAGE -> emptyList()
            LocalImageTaskMode.IMG2IMG,
            LocalImageTaskMode.EDIT -> listOf(inputDraft.inputImageReference)
            LocalImageTaskMode.INPAINT -> listOf(
                inputDraft.inputImageReference,
                inputDraft.maskImageReference
            )
            LocalImageTaskMode.CONTROL -> listOf(inputDraft.controlImageReference)
        }
        return required.mapNotNullTo(mutableSetOf()) { reference ->
            reference.safeHistoryInputReferenceOrNull()
        }
    }

internal fun ImageGenerationHistoryMetadata.displayDetails(): String = buildList {
        if (sourceGenerationAvailable) {
        add("$modelName · ${if (options.ultraFix != null) "UltraFix" else inputDraft.taskMode.displayName()}")
        val elapsedMs = runCatching {
            JSONObject(nativeExecutionJson).optLong("elapsedMs", 0L)
        }.getOrDefault(0L)
        val parameters = buildList {
            if (options.width != null && options.height != null) add("${options.width}×${options.height}")
            options.steps?.let { add("$it steps") }
            options.cfgScale?.let { add("CFG ${it.toCompactText()}") }
            add(options.seed?.let { "seed $it" } ?: "随机 seed")
            options.sampleMethod?.takeIf(String::isNotBlank)?.let { add(it) }
            if (options.batchCount > 1) add("${options.batchCount} 张")
            batchLineage?.let { add("批次 ${it.index + 1}/${it.count}") }
            if (elapsedMs > 0L) add("用时 ${"%.1f".format(elapsedMs / 1000.0)} 秒")
        }
        if (parameters.isNotEmpty()) add(parameters.joinToString(" · "))
        val controls = buildList {
            options.clipSkip?.let { add("CLIP skip $it") }
            options.ultraFix?.let { ultraFix ->
                add(
                    "UltraFix ${ultraFix.targetWidth}×${ultraFix.targetHeight} · " +
                        "反演 ${ultraFix.inversionSteps} / 精修 ${ultraFix.refinementSteps} · " +
                        "分块 ${ultraFix.tileSize}/${ultraFix.overlap.toCompactText()}"
                )
            } ?: options.vaeTiling?.let {
                add("VAE 分块 ${it.tileSize}/${it.overlap.toCompactText()}")
            }
            if (loras.isNotEmpty()) {
                add(
                    "LoRA " + loras.joinToString { selection ->
                        "${selection.name} ${selection.multiplier.toCompactText()}"
                    }
                )
            }
            if (options.textualInversionIds.isNotEmpty()) {
                add("Textual Inversion ${options.textualInversionIds.size} 个")
            }
            if (!inputDraft.inputImageReference.isNullOrBlank()) add("原图")
            if (!inputDraft.maskImageReference.isNullOrBlank()) add("蒙版")
            if (!inputDraft.controlImageReference.isNullOrBlank()) add("控制图")
            if (options.ultraFix == null) {
                inputDraft.strength?.let { add("重绘强度 ${it.toCompactText()}") }
            }
            inputDraft.controlStrength?.let { add("控制强度 ${it.toCompactText()}") }
            when (val negative = options.negativePrompt) {
                null -> add("模型默认负向提示词")
                "" -> add("无负向提示词")
                else -> add("负向：$negative")
            }
        }
        if (controls.isNotEmpty()) add(controls.joinToString(" · "))
        }
        upscaleHistory.lastOrNull()?.let { upscale ->
            add(
                "ESRGAN ${upscale.targetScale}x | ${upscale.upscalerName} | " +
                    "${upscale.sourceWidth}x${upscale.sourceHeight} -> " +
                    "${upscale.outputWidth}x${upscale.outputHeight}"
            )
        }
    }.joinToString("\n")
