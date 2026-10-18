package com.muyuchat.mca

import com.muyuchat.feature.chat.ImageGenerationUiLoraSelection
import com.muyuchat.mca.presentation.toLocalImagePreviewOptions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P03：`onGenerateImagePrompt` 的 LoRA 校验必须在 intent 触发时读取真实 owner 的最新快照，
 * 而参数映射必须与旧的 inline lambda 逐字段一致。
 *
 * `submitChatImageFromUi` is the extracted body of that intent; `mainChatScreenActions` wires
 * `currentLocalLoras` to `viewModel.uiState.value.localImageLoras`. These tests pin both halves that
 * can be executed on the JVM: latest-snapshot lookup and the exact draft/options translation.
 */
class ChatImagePromptActionTest {
    private val loraA = lora(
        id = "11111111-1111-4111-8111-111111111111",
        name = "风格 A"
    )
    private val loraB = lora(
        id = "22222222-2222-4222-8222-222222222222",
        name = "风格 B"
    )

    @Test
    fun validationReadsTheLatestOwnerSnapshotWhenTheIntentFires() {
        var available: List<LocalImageLoraRecord> = emptyList()
        var missingReports = 0
        var submissions = 0
        val options = ChatProjectionFixtures.imageUiOptions(
            loras = listOf(ImageGenerationUiLoraSelection(loraA.id, 0.75))
        )

        val rejected = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = { available },
            onMissingLocalImageLora = { missingReports += 1 },
            submit = { _, _, _ ->
                submissions += 1
                true
            }
        )
        assertFalse(rejected)
        assertEquals(1, missingReports)
        assertEquals(0, submissions)

        // The catalog changed after the lambda was built: the second trigger observes it.
        available = listOf(loraA)
        var submittedLoras: List<LocalImagePreparedLora>? = null
        val accepted = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = { available },
            onMissingLocalImageLora = { missingReports += 1 },
            submit = { _, _, submittedOptions ->
                submissions += 1
                submittedLoras = submittedOptions.loras
                true
            }
        )
        assertTrue(accepted)
        assertEquals("the missing-LoRA report must not repeat after the catalog recovers", 1, missingReports)
        assertEquals(1, submissions)
        assertEquals(listOf(loraA.toPrepared(0.75)), submittedLoras)
    }

    @Test
    fun oneMissingSelectionRejectsTheWholeRequestWithoutSubmitting() {
        var missingReports = 0
        var submissions = 0
        val options = ChatProjectionFixtures.imageUiOptions(
            loras = listOf(
                ImageGenerationUiLoraSelection(loraA.id, 0.75),
                ImageGenerationUiLoraSelection(loraB.id, 1.0)
            )
        )

        val rejected = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = { listOf(loraA) },
            onMissingLocalImageLora = { missingReports += 1 },
            submit = { _, _, _ ->
                submissions += 1
                true
            }
        )

        assertFalse(rejected)
        assertEquals(1, missingReports)
        assertEquals(0, submissions)
    }

    @Test
    fun preparedLorasFollowTheSelectionOrderAndKeepTheirMultipliers() {
        val first = ImageGenerationUiLoraSelection(loraB.id, 0.5)
        val second = ImageGenerationUiLoraSelection(loraA.id, 1.25)
        val options = ChatProjectionFixtures.imageUiOptions(loras = listOf(first, second))
        var submittedLoras: List<LocalImagePreparedLora>? = null

        val accepted = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = { listOf(loraA, loraB) },
            onMissingLocalImageLora = { },
            submit = { _, _, submittedOptions ->
                submittedLoras = submittedOptions.loras
                true
            }
        )

        assertTrue(accepted)
        assertEquals(listOf(loraB.toPrepared(0.5), loraA.toPrepared(1.25)), submittedLoras)
    }

    @Test
    fun draftAndOptionsTranslationMatchesTheOldInlineMapping() {
        val selection = ImageGenerationUiLoraSelection(loraA.id, 0.75)
        val options = ChatProjectionFixtures.imageUiOptions(loras = listOf(selection))
        var submittedPrompt: String? = null
        var submittedDraft: LocalImageInputDraft? = null
        var submittedOptions: LocalImageGenerationOptions? = null

        val accepted = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = { listOf(loraA) },
            onMissingLocalImageLora = { },
            submit = { prompt, draft, generationOptions ->
                submittedPrompt = prompt
                submittedDraft = draft
                submittedOptions = generationOptions
                true
            }
        )

        assertTrue(accepted)
        assertEquals("画一只猫", submittedPrompt)
        assertEquals(
            LocalImageInputDraft(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImageReference = "content://input/image",
                maskImageReference = "content://input/mask",
                controlImageReference = "content://input/control",
                strength = 0.6,
                controlStrength = 0.4
            ),
            submittedDraft
        )
        assertEquals(
            LocalImageGenerationOptions(
                negativePrompt = "模糊",
                width = 768,
                height = 1024,
                steps = 24,
                seed = 123,
                cfgScale = 6.5,
                sampleMethod = "euler",
                clipSkip = 2,
                batchCount = 3,
                loras = listOf(loraA.toPrepared(0.75)),
                vaeTiling = LocalImageVaeTilingOptions(tileSize = 512, overlap = 0.3),
                textualInversionIds = listOf(ChatProjectionFixtures.TEXTUAL_INVERSION_ID),
                ultraFix = LocalImageUltraFixOptions(
                    targetWidth = 1024,
                    targetHeight = 1024,
                    strength = 0.35,
                    inversionSteps = 4,
                    refinementSteps = 10,
                    tileSize = 512,
                    overlap = 0.25
                ),
                preview = options.toLocalImagePreviewOptions()
            ),
            submittedOptions
        )
        assertEquals("euler", submittedOptions?.sampleMethod)
        assertEquals(2, submittedOptions?.preview?.interval ?: -1)
    }

    @Test
    fun submissionResultIsPassedThroughAndNoLoraSelectionsSkipTheCatalogLookup() {
        var catalogLookups = 0
        val options = ChatProjectionFixtures.imageUiOptions(loras = emptyList())

        val rejected = submitChatImageFromUi(
            prompt = "画一只猫",
            uiOptions = options,
            currentLocalLoras = {
                catalogLookups += 1
                emptyList()
            },
            onMissingLocalImageLora = { },
            submit = { _, _, submittedOptions ->
                assertEquals(0, submittedOptions.loras.size)
                assertTrue(submittedOptions.preview != null)
                false
            }
        )

        assertFalse("the submission result must be returned unchanged", rejected)
        assertEquals("no selection means no owner catalog read", 0, catalogLookups)
        assertTrue(options.loras.isEmpty())
    }

    @Test
    fun composedWiringBuildsTheBundleOnceAndReadsLatestOwnerState() {
        // P03 contract that cannot be exercised without a MainViewModel/Compose runtime: the bundle
        // is built once per (viewModel, host), host callbacks are published only after composition,
        // and the state-dependent intent reads the owner's current snapshot instead of a captured
        // frame. These assertions read the actual wiring, not a reimplementation of it.
        val actions = sourceFile("MainChatScreenActions.kt")
        val activity = sourceFile("MainActivity.kt")

        assertTrue(actions.contains("SideEffect {"))
        assertTrue(actions.contains("remember(viewModel, host) { mainChatScreenActions(viewModel, host) }"))
        assertTrue(actions.contains("onGenerateImagePrompt = { prompt, uiOptions ->"))
        assertTrue(actions.contains("currentLocalLoras = { viewModel.uiState.value.localImageLoras }"))
        assertFalse(
            "the action bundle must not freeze a composition state snapshot",
            actions.contains("state.localImageLoras")
        )
        assertTrue(actions.contains("{ sessionId -> host.onExportChatSession(sessionId) }"))
        assertTrue(actions.contains("{ open -> host.setAppMenuOpen(open) }"))
        assertTrue(actions.contains("host.onTab(AppTab.MODELS)"))
        assertTrue(activity.contains("rememberMainChatScreenActions("))
        assertFalse(
            "the per-frame builder must not be invoked with a state snapshot anymore",
            activity.contains("mainChatScreenActions(")
        )
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")

    private fun lora(id: String, name: String): LocalImageLoraRecord = LocalImageLoraRecord(
        id = id,
        name = name,
        fileName = "$id.safetensors",
        path = "C:/images/loras/$id.safetensors",
        sha256 = "a".repeat(64),
        sizeBytes = 1_024L,
        importedAt = 1L
    )
}
