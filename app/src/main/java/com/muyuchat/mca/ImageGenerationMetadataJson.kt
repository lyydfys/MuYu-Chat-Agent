package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: generation history JSON reader; moved verbatim from ImageGenerationHistoryMetadata.kt. */

internal fun ImageGenerationHistoryMetadata.Companion.fromJsonOrNull(raw: String?): ImageGenerationHistoryMetadata? = runCatching {
            val json = JSONObject(raw?.trim().orEmpty())
            val historyVersion = requireNotNull(json.historyExactIntOrNull("version"))
            require(historyVersion in LEGACY_VERSION..VERSION) {
                "Unsupported image history version."
            }
            val input = json.getJSONObject("inputDraft")
            val inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.fromWireName(input.getString("taskMode")),
                inputImageReference = input.optionalHistoryString("inputImageReference")
                    .safeHistoryInputReferenceOrNull(),
                maskImageReference = input.optionalHistoryString("maskImageReference")
                    .safeHistoryInputReferenceOrNull(),
                controlImageReference = input.optionalHistoryString("controlImageReference")
                    .safeHistoryInputReferenceOrNull(),
                strength = input.optionalHistoryDouble("strength"),
                controlStrength = input.optionalHistoryDouble("controlStrength")
            ).also(LocalImageInputDraft::validateForHistory)
            val persistedOptions = LocalImageGenerationOptions.fromHistoryJson(
                json.getJSONObject("options")
            )
            val backend = ImageBackend.valueOf(json.getString("backend"))
            val requestPrompt = json.getString("requestPrompt")
            var discardedLegacyTranslatedPromptExecution = false
            val promptExecution = if (json.has("promptExecution")) {
                val promptExecutionJson = requireNotNull(json.optJSONObject("promptExecution")) {
                    "Image history promptExecution is invalid."
                }
                if (promptExecutionJson.isDiscardableLegacyTranslatedPromptExecution(
                        requestPrompt = requestPrompt,
                        originalNegativePrompt = persistedOptions.negativePrompt
                    )
                ) {
                    discardedLegacyTranslatedPromptExecution = true
                    null
                } else {
                    requireNotNull(LocalImagePromptExecution.fromJsonOrNull(promptExecutionJson)) {
                        "Image history promptExecution is invalid."
                    }
                }
            } else {
                null
            }
            val sourceGenerationAvailable = if (json.has("sourceGenerationAvailable")) {
                requireNotNull(json.historyBooleanOrNull("sourceGenerationAvailable"))
            } else {
                true
            }
            require(historyVersion < VERSION || backend != ImageBackend.LOCAL ||
                !sourceGenerationAvailable || promptExecution != null ||
                discardedLegacyTranslatedPromptExecution
            ) {
                "Current local image history must include prompt execution evidence."
            }
            val historyOptions = persistedOptions.copy(
                taskMode = inputDraft.taskMode,
                strength = inputDraft.strength,
                controlStrength = inputDraft.controlStrength
            ).also(LocalImageGenerationOptions::validateHistoryProductInputContract)
            ImageGenerationHistoryMetadata(
                backend = backend,
                modelId = json.getString("modelId"),
                modelName = json.getString("modelName"),
                requestPrompt = requestPrompt,
                options = historyOptions,
                inputDraft = inputDraft,
                promptExecution = promptExecution,
                loras = json.optJSONArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT)
                    buildList {
                        for (index in 0 until array.length()) {
                            add(ImageGenerationLoraSelection.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                nativeExecutionJson = sanitizeNativeExecutionJson(
                    json.optJSONObject("nativeExecution")?.toString().orEmpty(),
                    allowLegacyQnnDetailStageMaskMigration =
                        historyVersion <= LEGACY_QNN_DETAIL_MASK_LAST_HISTORY_VERSION
                ),
                sourceGenerationAvailable = sourceGenerationAvailable,
                upscaleHistory = json.optJSONArray("upscaleHistory")?.let { array ->
                    require(array.length() <= MAX_UPSCALE_HISTORY)
                    buildList {
                        for (index in 0 until array.length()) {
                            add(requireNotNull(
                                ImageUpscaleHistoryMetadata.fromJsonOrNull(
                                    array.getJSONObject(index)
                                )
                            ))
                        }
                    }
                }.orEmpty(),
                batchLineage = json.optJSONObject("batchLineage")
                    ?.let(ImageGenerationBatchLineage::fromJson)
            )
        }.getOrNull()
