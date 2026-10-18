package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ImageAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiLoraSelection
import com.muyuchat.feature.chat.ImageGenerationUiPreset
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import com.muyuchat.feature.chat.ImageGenerationUiUltraFixOptions
import com.muyuchat.mca.presentation.formatAssetBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P02 等值契约：缓存命中路径与"没有缓存时调用原映射函数"逐字段等值。
 *
 * The oracle is the pre-existing mapping itself: `buildAssistantUiItems`, `buildLocalChatModelChoices`,
 * `buildImageLoraUiItems`, ... are unchanged by this change and are called directly here. `ChatUiState`
 * is a data class, so `assertEquals(fresh, warm)` also compares every direct field (messages, input,
 * stats, backgrounds, selected ids, web-search strings, ...) of the whole projection.
 */
class ChatProjectionEquivalenceTest {
    private val fixture = ChatProjectionFixtures.fixture()

    private val generationParamsForChatModel: (String) -> GenerationParams = { modelId ->
        fixture.modelParams[modelId] ?: ChatProjectionFixtures.defaultModelParams
    }

    @Test
    fun warmCacheProjectionEqualsTheUncachedOriginalMappingFieldByField() {
        val cache = ChatUiProjectionCache()
        fixture.project(cache)

        val streamingState = fixture.state.copy(
            messages = fixture.state.messages + ChatMessage(Role.ASSISTANT, "流式增量"),
            input = "正在输入",
            statusMessage = "状态"
        )
        val streamingFixture = fixture.copy(state = streamingState)
        val warm = streamingFixture.project(cache)
        val fresh = streamingFixture.project(ChatUiProjectionCache())

        assertEquals("whole ChatUiState must be field-by-field equal", fresh, warm)
        assertEquals(buildChatHistoryItems(streamingState, fixture.sessionPresentations, fixture.locale), warm.history)
        assertEquals(buildLocalChatModelChoices(streamingState, generationParamsForChatModel), warm.localModels)
        assertEquals(buildAssistantUiItems(streamingState), warm.assistants)
        assertEquals(buildWorldBookUiItems(streamingState), warm.worldBooks)
        assertEquals(buildKnowledgeBaseUiItems(streamingState), warm.knowledgeBases)
        assertEquals(buildImageLoraUiItems(streamingState), warm.imageLoras)
        assertEquals(buildImageTextualInversionUiItems(streamingState), warm.imageTextualInversions)
        assertEquals(buildImageUpscalerUiItems(streamingState), warm.imageUpscalers)
        assertEquals(buildFileAssetUiItems(streamingState, fixture.locale), warm.files)
        assertEquals(buildImageGenerationUiJobs(streamingState), warm.imageJobs)
        assertEquals(fresh.images, warm.images)
        assertEquals(buildContextSummaryModelOptions(streamingState), warm.contextSummaryModels)
        assertEquals(buildContextSummaryHistoryItems(streamingState), warm.contextSummaryHistory)
        assertEquals(canUndoContextSummaryFor(streamingState), warm.canUndoContextSummary)
    }

    @Test
    fun warmCacheProjectionEqualsTheUncachedMappingAfterEveryDomainChanges() {
        val cache = ChatUiProjectionCache()
        fixture.project(cache)

        val changedState = fixture.state.copy(
            messages = fixture.state.messages + ChatMessage(Role.USER, "新问题"),
            images = fixture.state.images.map { image -> image.copy(name = image.name + "改") },
            files = fixture.state.files.map { file -> file.copy(name = file.name + "改") },
            imageJobs = fixture.state.imageJobs.map { job -> job.copy(prompt = job.prompt + "改") },
            assistants = fixture.state.assistants.map { assistant ->
                assistant.copy(name = assistant.name + "改")
            },
            worldBooks = fixture.state.worldBooks.map { book -> book.copy(name = book.name + "改") },
            knowledgeBases = fixture.state.knowledgeBases.map { base ->
                base.copy(description = base.description + "改")
            },
            knowledgeDocumentCounts = mapOf("kb-1" to 5),
            knowledgeBaseImportingIds = setOf("kb-1"),
            selectedKnowledgeBaseIds = setOf("kb-2"),
            localImageLoras = fixture.state.localImageLoras.map { lora -> lora.copy(name = lora.name + "改") },
            activeLocalImageLoraIds = emptySet(),
            localImageTextualInversions = fixture.state.localImageTextualInversions.map { artifact ->
                artifact.copy(trigger = "changedtrigger")
            },
            activeLocalImageTextualInversionIds = emptySet(),
            deletingLocalImageTextualInversionIds =
                setOf(ChatProjectionFixtures.TEXTUAL_INVERSION_ID),
            localImageUpscalers = fixture.state.localImageUpscalers.map { upscaler ->
                upscaler.copy(name = upscaler.name + "改")
            },
            selectedLocalImageUpscalerId = null,
            activeLocalImageUpscalerId = null,
            localImageUpscalerDeletingId = ChatProjectionFixtures.UPSCALER_ID,
            cloudModels = fixture.state.cloudModels.map { model ->
                model.copy(displayName = model.displayName + "改")
            },
            models = fixture.state.models.map { model ->
                model.copy(displayName = model.displayName + "改")
            }
        )
        val changedFixture = fixture.copy(
            state = changedState,
            imageGenerationHistoryById = fixture.imageGenerationHistoryById.mapValues { (id, metadata) ->
                if (id == ChatProjectionFixtures.IMAGE_ID) metadata?.copy(modelName = "改名模型") else metadata
            },
            modelParams = mapOf(
                ChatProjectionFixtures.IDLE_MODEL_ID to
                    GenerationParams(temperature = 0.33f, advancedJson = "{}")
            )
        )
        val warm = changedFixture.project(cache)
        val fresh = changedFixture.project(ChatUiProjectionCache())

        assertEquals(fresh, warm)
        assertEquals(fresh.assistants, warm.assistants)
        assertEquals(fresh.localModels, warm.localModels)
        assertEquals(fresh.images, warm.images)
        assertEquals(fresh.files, warm.files)
        assertEquals(fresh.imageJobs, warm.imageJobs)
        assertEquals(fresh.worldBooks, warm.worldBooks)
        assertEquals(fresh.knowledgeBases, warm.knowledgeBases)
        assertEquals(fresh.imageLoras, warm.imageLoras)
        assertEquals(fresh.imageTextualInversions, warm.imageTextualInversions)
        assertEquals(fresh.imageUpscalers, warm.imageUpscalers)
        assertEquals(fresh.contextSummaryModels, warm.contextSummaryModels)
        assertEquals(fresh.contextSummaryHistory, warm.contextSummaryHistory)
        assertEquals(fresh.canUndoContextSummary, warm.canUndoContextSummary)
    }

    @Test
    fun movedImageProjectionEqualsTheVerbatimPreChangeMapping() {
        // The images mapping is the only mapping body that physically moved (into a private
        // function in the same file). This oracle is the pre-change block copied verbatim, so the
        // comparison does not derive expected values from the new implementation.
        val projected = fixture.project(ChatUiProjectionCache())

        assertEquals(
            legacyImageAssets(fixture.state, fixture.imageGenerationHistoryById, fixture.locale),
            projected.images
        )
    }

    private fun legacyImageAssets(
        state: MainUiState,
        imageGenerationHistoryById: Map<String, ImageGenerationHistoryMetadata?>,
        timestampLocale: Locale
    ): List<ImageAssetUiItem> {
        val imageCreatedAtFormatter = SimpleDateFormat("MM-dd HH:mm", timestampLocale)
        return state.images.map { image ->
            val generation = imageGenerationHistoryById[image.id]
            val sourceGeneration = generation?.takeIf { it.sourceGenerationAvailable }
            val executionFacets = sourceGeneration?.executionFacets()
            ImageAssetUiItem(
                id = image.id,
                name = image.name,
                uriString = image.uriString,
                source = image.source,
                prompt = image.prompt,
                createdAtText = imageCreatedAtFormatter.format(Date(image.createdAt)),
                createdAtMillis = image.createdAt,
                sizeText = formatAssetBytes(image.sizeBytes),
                width = image.width,
                height = image.height,
                sizeBytes = image.sizeBytes,
                upscaleTargetScale = generation?.upscaleHistory?.lastOrNull()?.targetScale,
                generationDetails = generation?.displayDetails().orEmpty(),
                generationPrompt = sourceGeneration?.requestPrompt.orEmpty(),
                generationModelId = sourceGeneration?.modelId.orEmpty(),
                generationModelName = sourceGeneration?.modelName.orEmpty(),
                generationTaskMode = sourceGeneration?.inputDraft?.taskMode?.wireName.orEmpty(),
                generationOperation = sourceGeneration?.operationFacet()?.wireName.orEmpty(),
                generationSampler = sourceGeneration?.options?.sampleMethod.orEmpty(),
                generationRuntime = executionFacets?.runtimeLabel.orEmpty(),
                generationDevice = executionFacets?.deviceLabel.orEmpty(),
                parameterShareJson = sourceGeneration?.toShareJson().orEmpty(),
                generationPreset = sourceGeneration?.let { metadata ->
                    ImageGenerationUiPreset(
                        prompt = metadata.requestPrompt,
                        taskMode = ImageGenerationUiTaskMode.entries.firstOrNull { mode ->
                            mode.wireName == metadata.inputDraft.taskMode.wireName
                        },
                        negativePrompt = metadata.options.negativePrompt,
                        width = metadata.options.width,
                        height = metadata.options.height,
                        steps = metadata.options.steps,
                        cfgScale = metadata.options.cfgScale,
                        seed = metadata.options.seed,
                        sampleMethod = metadata.options.sampleMethod,
                        clipSkip = metadata.options.clipSkip,
                        batchCount = metadata.options.batchCount,
                        vaeTileSize = metadata.options.vaeTiling?.tileSize,
                        vaeTileOverlap = metadata.options.vaeTiling?.overlap,
                        loras = metadata.loras.map { selection ->
                            ImageGenerationUiLoraSelection(
                                id = selection.id,
                                multiplier = selection.multiplier
                            )
                        },
                        textualInversionIds = metadata.options.textualInversionIds,
                        strength = metadata.inputDraft.strength,
                        controlStrength = metadata.inputDraft.controlStrength,
                        ultraFix = metadata.options.ultraFix?.let { ultraFix ->
                            ImageGenerationUiUltraFixOptions(
                                targetWidth = ultraFix.targetWidth,
                                targetHeight = ultraFix.targetHeight,
                                strength = ultraFix.strength,
                                inversionSteps = ultraFix.inversionSteps,
                                refinementSteps = ultraFix.refinementSteps,
                                tileSize = ultraFix.tileSize,
                                overlap = ultraFix.overlap
                            )
                        }
                    )
                },
                favorite = image.favorite,
                canRecreate = sourceGeneration?.canRecreate() == true
            )
        }
    }

    @Test
    fun freshProjectionDoesNotDependOnAnEarlierWarmCacheFromAnotherState() {
        val cache = ChatUiProjectionCache()
        fixture.project(cache)

        val other = ChatProjectionFixture(state = MainUiState())
        val warmOther = other.project(cache)
        val freshOther = other.project(ChatUiProjectionCache())

        assertEquals(freshOther, warmOther)
        assertEquals(0, warmOther.assistants.size)
        assertEquals(0, warmOther.images.size)
        assertEquals(0, warmOther.localModels.size)
    }
}
