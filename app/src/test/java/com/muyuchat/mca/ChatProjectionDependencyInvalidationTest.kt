package com.muyuchat.mca

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.ChatUiState
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P02 依赖契约：每个被读取的依赖字段变化时，对应投影必须失效（缓存 miss），且结果仍与无缓存投影等值。
 *
 * Every case builds a warm cache, changes exactly one dependency, and asserts:
 * 1. the cached domain returns a new instance (so no stale frame is rendered);
 * 2. its value equals a projection from a brand-new cache, i.e. the key is not merely conservative
 *    but the recomputed output is the original mapping's output.
 */
class ChatProjectionDependencyInvalidationTest {
    private val base = ChatProjectionFixtures.fixture()

    private fun assertInvalidated(
        name: String,
        projection: (ChatUiState) -> Any,
        mutate: (ChatProjectionFixture) -> ChatProjectionFixture
    ) {
        val cache = ChatUiProjectionCache()
        val before = base.project(cache)
        val changed = mutate(base)
        val after = changed.project(cache)

        assertNotSame("$name must invalidate the cached projection", projection(before), projection(after))
        assertEquals(
            "$name must match the uncached original mapping",
            projection(changed.project(ChatUiProjectionCache())),
            projection(after)
        )
    }

    private fun localModelCase(
        name: String,
        mutate: (ChatProjectionFixture) -> ChatProjectionFixture
    ) = InvalidationCase(
        name = name,
        projection = { it.localModels },
        mutate = mutate
    )

    private data class InvalidationCase(
        val name: String,
        val projection: (ChatUiState) -> Any,
        val mutate: (ChatProjectionFixture) -> ChatProjectionFixture
    )

    @Test
    fun assistantProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("assistants", { it.assistants }) { fixture ->
            val first = fixture.state.assistants.first()
            fixture.copy(
                state = fixture.state.copy(
                    assistants = listOf(first.copy(name = first.name + "改")) + fixture.state.assistants.drop(1)
                )
            )
        }
        assertInvalidated("assistant fallback params", { it.assistants }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    params = fixture.state.params.copy(temperature = 0.11f)
                )
            )
        }
        assertInvalidated("assistant local model names", { it.assistants }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    models = fixture.state.models.map { model ->
                        if (model.id == ChatProjectionFixtures.LOADED_MODEL_ID) {
                            model.copy(displayName = "本地改名")
                        } else {
                            model
                        }
                    }
                )
            )
        }
        assertInvalidated("assistant cloud model names", { it.assistants }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    cloudModels = fixture.state.cloudModels.map { model ->
                        if (model.kind == CloudModelKind.CHAT) model.copy(modelName = "新云端名") else model
                    }
                )
            )
        }
        assertInvalidated("selected assistant", { it.assistants }) { fixture ->
            fixture.copy(state = fixture.state.copy(selectedAssistantId = "assistant-b"))
        }
    }

    @Test
    fun localModelProjectionInvalidatesOnEveryReadDependency() {
        val cases = listOf(
            localModelCase("cloud models") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        cloudModels = fixture.state.cloudModels.map { model ->
                            model.copy(displayName = model.displayName + "改")
                        }
                    )
                )
            },
            localModelCase("selected chat backend") { fixture ->
                fixture.copy(state = fixture.state.copy(selectedChatBackend = ChatBackend.CLOUD))
            },
            localModelCase("selected cloud model") { fixture ->
                fixture.copy(state = fixture.state.copy(selectedCloudChatModelId = null))
            },
            localModelCase("local models") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        models = fixture.state.models.map { model ->
                            if (model.id == ChatProjectionFixtures.IDLE_MODEL_ID) {
                                model.copy(quant = "Q8")
                            } else {
                                model
                            }
                        }
                    )
                )
            },
            localModelCase("loaded model id") { fixture ->
                fixture.copy(state = fixture.state.copy(loadedModelId = null))
            },
            localModelCase("stats.loaded") { fixture ->
                fixture.copy(state = fixture.state.copy(stats = fixture.state.stats.copy(loaded = false)))
            },
            localModelCase("stats.backend") { fixture ->
                fixture.copy(state = fixture.state.copy(stats = fixture.state.stats.copy(backend = "gpu")))
            },
            localModelCase("stats.backendDevices") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(stats = fixture.state.stats.copy(backendDevices = "[gpu]"))
                )
            },
            localModelCase("stats.gpuOffloadSupported") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(stats = fixture.state.stats.copy(gpuOffloadSupported = true))
                )
            },
            localModelCase("stats.gpuOffloadActive") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(stats = fixture.state.stats.copy(gpuOffloadActive = true))
                )
            },
            localModelCase("stats.gpuOffloadAllocationObserved") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        stats = fixture.state.stats.copy(gpuOffloadAllocationObserved = true)
                    )
                )
            },
            localModelCase("stats.gpuOffloadExecutionObserved") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        stats = fixture.state.stats.copy(gpuOffloadExecutionObserved = true)
                    )
                )
            },
            localModelCase("stats.gpuAutoFallbackApplied") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        stats = fixture.state.stats.copy(gpuAutoFallbackApplied = true)
                    )
                )
            },
            localModelCase("stats.lastError") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(stats = fixture.state.stats.copy(lastError = "boom"))
                )
            },
            localModelCase("device chipset") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        deviceProfile = ChatProjectionFixtures.deviceProfile(
                            ChatProjectionFixtures.npuAcceleration(chipsetCode = "SM8750")
                        )
                    )
                )
            },
            localModelCase("device qnn runtime") { fixture ->
                fixture.copy(
                    state = fixture.state.copy(
                        deviceProfile = ChatProjectionFixtures.deviceProfile(
                            ChatProjectionFixtures.npuAcceleration(usable = false)
                        )
                    )
                )
            },
            localModelCase("engine lifecycle") { fixture ->
                fixture.copy(state = fixture.state.copy(engineLifecycle = AgentEngineLifecycle.LOADING))
            },
            localModelCase("isGenerating") { fixture ->
                fixture.copy(state = fixture.state.copy(isGenerating = true))
            },
            localModelCase("params") { fixture ->
                fixture.copy(state = fixture.state.copy(params = fixture.state.params.copy(topK = 7)))
            },
            localModelCase("per-model generation params") { fixture ->
                fixture.copy(
                    modelParams = mapOf(
                        ChatProjectionFixtures.IDLE_MODEL_ID to
                            com.muyuchat.core.engine.GenerationParams(temperature = 0.22f, advancedJson = "{}")
                    )
                )
            }
        )
        cases.forEach { case -> assertInvalidated(case.name, case.projection, case.mutate) }
    }

    @Test
    fun imageProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("image list", { it.images }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    images = fixture.state.images.map { image ->
                        if (image.id == ChatProjectionFixtures.IMAGE_ID) {
                            image.copy(favorite = false)
                        } else {
                            image
                        }
                    }
                )
            )
        }
        assertInvalidated("generation history metadata", { it.images }) { fixture ->
            fixture.copy(
                imageGenerationHistoryById = fixture.imageGenerationHistoryById.mapValues { (id, metadata) ->
                    if (id == ChatProjectionFixtures.IMAGE_ID) metadata?.copy(modelName = "改名模型") else metadata
                }
            )
        }
        assertInvalidated("timestamp locale", { it.images }) { fixture ->
            fixture.copy(locale = Locale.GERMANY)
        }
    }

    @Test
    fun fileProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("file list", { it.files }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    files = fixture.state.files.map { file -> file.copy(name = file.name + "改") }
                )
            )
        }
        assertInvalidated("timestamp locale", { it.files }) { fixture ->
            fixture.copy(locale = Locale.GERMANY)
        }
    }

    @Test
    fun imageJobProjectionInvalidatesOnJobContent() {
        assertInvalidated("image jobs", { it.imageJobs }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    imageJobs = fixture.state.imageJobs.map { job ->
                        job.copy(previewStep = job.previewStep + 1)
                    }
                )
            )
        }
    }

    @Test
    fun worldBookProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("world books", { it.worldBooks }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    worldBooks = fixture.state.worldBooks.map { book ->
                        book.copy(name = book.name + "改")
                    }
                )
            )
        }
        assertInvalidated("selected assistant scope", { it.worldBooks }) { fixture ->
            fixture.copy(state = fixture.state.copy(selectedAssistantId = "assistant-b"))
        }
        assertInvalidated("active session scope", { it.worldBooks }) { fixture ->
            fixture.copy(state = fixture.state.copy(activeChatSessionId = null))
        }
    }

    @Test
    fun knowledgeBaseProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("knowledge bases", { it.knowledgeBases }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    knowledgeBases = fixture.state.knowledgeBases.map { base ->
                        base.copy(description = base.description + "改")
                    }
                )
            )
        }
        assertInvalidated("selected knowledge bases", { it.knowledgeBases }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(selectedKnowledgeBaseIds = setOf("kb-1", "kb-2"))
            )
        }
        assertInvalidated("document counts", { it.knowledgeBases }) { fixture ->
            fixture.copy(state = fixture.state.copy(knowledgeDocumentCounts = mapOf("kb-1" to 4)))
        }
        assertInvalidated("importing ids", { it.knowledgeBases }) { fixture ->
            fixture.copy(state = fixture.state.copy(knowledgeBaseImportingIds = setOf("kb-1")))
        }
    }

    @Test
    fun imageLoraProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("local image loras", { it.imageLoras }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    localImageLoras = fixture.state.localImageLoras.map { lora -> lora.copy(name = "改名") }
                )
            )
        }
        assertInvalidated("active lora ids", { it.imageLoras }) { fixture ->
            fixture.copy(state = fixture.state.copy(activeLocalImageLoraIds = emptySet()))
        }
    }

    @Test
    fun textualInversionProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("textual inversions", { it.imageTextualInversions }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    localImageTextualInversions = fixture.state.localImageTextualInversions.map { artifact ->
                        artifact.copy(trigger = "othertrigger")
                    }
                )
            )
        }
        assertInvalidated("active textual inversion ids", { it.imageTextualInversions }) { fixture ->
            fixture.copy(state = fixture.state.copy(activeLocalImageTextualInversionIds = emptySet()))
        }
        assertInvalidated("deleting textual inversion ids", { it.imageTextualInversions }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    deletingLocalImageTextualInversionIds =
                        setOf(ChatProjectionFixtures.TEXTUAL_INVERSION_ID)
                )
            )
        }
    }

    @Test
    fun upscalerProjectionInvalidatesOnEveryReadDependency() {
        assertInvalidated("local image upscalers", { it.imageUpscalers }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    localImageUpscalers = fixture.state.localImageUpscalers.map { upscaler ->
                        upscaler.copy(name = "改名")
                    }
                )
            )
        }
        assertInvalidated("selected upscaler", { it.imageUpscalers }) { fixture ->
            fixture.copy(state = fixture.state.copy(selectedLocalImageUpscalerId = null))
        }
        assertInvalidated("active upscaler", { it.imageUpscalers }) { fixture ->
            fixture.copy(state = fixture.state.copy(activeLocalImageUpscalerId = null))
        }
        assertInvalidated("deleting upscaler", { it.imageUpscalers }) { fixture ->
            fixture.copy(
                state = fixture.state.copy(
                    localImageUpscalerDeletingId = ChatProjectionFixtures.UPSCALER_ID
                )
            )
        }
    }

    @Test
    fun contextSummaryProjectionInvalidatesOnEveryReadDependency() {
        val cache = ChatUiProjectionCache()
        val before = base.project(cache)

        val cloudChanged = base.copy(
            state = base.state.copy(
                cloudModels = base.state.cloudModels.map { model ->
                    if (model.kind == CloudModelKind.CHAT) model.copy(displayName = "新名称") else model
                }
            )
        ).project(cache)
        assertNotSame(before.contextSummaryModels, cloudChanged.contextSummaryModels)
        assertEquals(
            base.copy(
                state = base.state.copy(
                    cloudModels = base.state.cloudModels.map { model ->
                        if (model.kind == CloudModelKind.CHAT) model.copy(displayName = "新名称") else model
                    }
                )
            ).project(ChatUiProjectionCache()).contextSummaryModels,
            cloudChanged.contextSummaryModels
        )

        val summaryChanged = base.copy(
            state = base.state.copy(
                chatSessions = base.state.chatSessions.map { session ->
                    if (session.id == ChatProjectionFixtures.SESSION_ID) {
                        session.copy(
                            contextSummaries = listOf(
                                ChatProjectionFixtures.contextSummary(text = "新的摘要")
                            )
                        )
                    } else {
                        session
                    }
                }
            )
        ).project(cache)
        assertNotSame(before.contextSummaryHistory, summaryChanged.contextSummaryHistory)
        assertEquals("新的摘要", summaryChanged.contextSummaryHistory.single().text)

        val inactive = base.copy(
            state = base.state.copy(
                chatSessions = base.state.chatSessions.map { session ->
                    if (session.id == ChatProjectionFixtures.SESSION_ID) {
                        session.copy(
                            contextSummaries = listOf(
                                ChatProjectionFixtures.contextSummary(active = false)
                            )
                        )
                    } else {
                        session
                    }
                }
            )
        ).project(cache)
        assertFalse(inactive.canUndoContextSummary)
        assertTrue(before.canUndoContextSummary)

        val sessionChanged = base.copy(
            state = base.state.copy(activeChatSessionId = "session-b")
        ).project(cache)
        assertFalse(sessionChanged.canUndoContextSummary)
        assertTrue(sessionChanged.contextSummaryHistory.isEmpty())
        assertEquals(
            base.copy(state = base.state.copy(activeChatSessionId = "session-b"))
                .project(ChatUiProjectionCache()).contextSummaryModels,
            sessionChanged.contextSummaryModels
        )
    }

    @Test
    fun sizeTextProjectionsInvalidateWhenTheDefaultFormatLocaleChanges() {
        // `formatAssetBytes` and `displayDetails()` format with the *default* locale, which is a
        // separate dependency from the Compose timestamp locale.
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val cache = ChatUiProjectionCache()
            val us = base.project(cache)

            Locale.setDefault(Locale.GERMANY)
            val german = base.project(cache)
            val freshGerman = base.project(ChatUiProjectionCache())

            assertNotSame("images", us.images, german.images)
            assertNotSame("files", us.files, german.files)
            assertNotSame("imageLoras", us.imageLoras, german.imageLoras)
            assertNotSame("textualInversions", us.imageTextualInversions, german.imageTextualInversions)
            assertNotSame("imageUpscalers", us.imageUpscalers, german.imageUpscalers)

            assertNotEquals(us.images.first().sizeText, german.images.first().sizeText)
            assertNotEquals(us.files.first().sizeText, german.files.first().sizeText)
            assertNotEquals(us.imageLoras.first().sizeText, german.imageLoras.first().sizeText)
            assertNotEquals(
                us.imageTextualInversions.first().sizeText,
                german.imageTextualInversions.first().sizeText
            )
            assertNotEquals(us.imageUpscalers.first().sizeText, german.imageUpscalers.first().sizeText)

            assertEquals(freshGerman.images, german.images)
            assertEquals(freshGerman.files, german.files)
            assertEquals(freshGerman.imageLoras, german.imageLoras)
            assertEquals(freshGerman.imageTextualInversions, german.imageTextualInversions)
            assertEquals(freshGerman.imageUpscalers, german.imageUpscalers)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun imageAndFileProjectionInvalidatesWhenTheDefaultTimeZoneChanges() {
        val originalTimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val cache = ChatUiProjectionCache()
            val utc = base.project(cache)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val shanghai = base.project(cache)
            val freshShanghai = base.project(ChatUiProjectionCache())

            assertNotSame("images", utc.images, shanghai.images)
            assertNotSame("files", utc.files, shanghai.files)
            assertNotEquals(utc.images.first().createdAtText, shanghai.images.first().createdAtText)
            assertNotEquals(utc.files.first().createdAtText, shanghai.files.first().createdAtText)
            assertEquals(freshShanghai.images, shanghai.images)
            assertEquals(freshShanghai.files, shanghai.files)
        } finally {
            TimeZone.setDefault(originalTimeZone)
        }
    }

    @Test
    fun streamingAppendDoesNotChangeAnyCachedProjectionValue() {
        val cache = ChatUiProjectionCache()
        val before = base.project(cache)
        val appended = base.copy(
            state = base.state.copy(
                messages = base.state.messages + com.muyuchat.core.engine.ChatMessage(
                    com.muyuchat.core.engine.Role.ASSISTANT,
                    "流式正文",
                    imageAttachments = listOf(
                        ChatImageAttachment(name = "new.png", uriString = "content://new")
                    )
                )
            )
        ).project(cache)

        // The appended message is not part of any cached key, so all eleven projections stay equal.
        assertEquals(before.assistants, appended.assistants)
        assertEquals(before.localModels, appended.localModels)
        assertEquals(before.images, appended.images)
        assertEquals(before.files, appended.files)
        assertEquals(before.imageJobs, appended.imageJobs)
        assertEquals(before.worldBooks, appended.worldBooks)
        assertEquals(before.knowledgeBases, appended.knowledgeBases)
        assertEquals(before.imageLoras, appended.imageLoras)
        assertEquals(before.imageTextualInversions, appended.imageTextualInversions)
        assertEquals(before.imageUpscalers, appended.imageUpscalers)
        assertEquals(before.contextSummaryModels, appended.contextSummaryModels)
        assertEquals(before.contextSummaryHistory, appended.contextSummaryHistory)
    }
}
