package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LiteRtLmChatRunnerTest {
    @Test
    fun assistantAndModelRolesShareTheLiteRtHistorySpelling() {
        assertEquals("model", canonicalLiteRtMessageRole(" assistant "))
        assertEquals("model", canonicalLiteRtMessageRole("MODEL"))
        assertEquals(
            canonicalLiteRtMessageRole("assistant"),
            canonicalLiteRtMessageRole("model")
        )
    }

    @Test
    fun unknownRolesAreIgnoredInsteadOfEnteringTheConversationHistory() {
        assertNull(canonicalLiteRtMessageRole("developer"))
        assertNull(canonicalLiteRtMessageRole(""))
    }

    @Test
    fun imagePartDetectorDistinguishesStructuredImagesFromOrdinaryText() {
        val image = """
            [{"role":"user","content":[
              {"type":"text","text":"the literal words image_url are fine"},
              {"type":"image_url","image_url":{"url":"data:image/png;base64,AA=="}}
            ]}]
        """.trimIndent()
        val inputImage = """
            [{"role":"user","content":[{"type":"input_image","image":"/tmp/a.png"}]}]
        """.trimIndent()
        val textOnly = """
            [{"role":"user","content":"mention image_url in ordinary text"}]
        """.trimIndent()

        assertTrue(liteRtLmMessagesContainImageInput(image))
        assertTrue(liteRtLmMessagesContainImageInput(inputImage))
        assertFalse(liteRtLmMessagesContainImageInput(textOnly))
        assertTrue(LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE.contains("LiteRT-LM"))
        assertTrue(LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE.contains("GGUF/MNN/QNN"))
    }

    @Test
    fun liteRtVisionIsNotReportedReadyUntilARealImageTurnSucceeds() {
        assertFalse(liteRtVisionReady(loaded = false, successfulImageTurn = true))
        assertFalse(liteRtVisionReady(loaded = true, successfulImageTurn = false))
        assertTrue(liteRtVisionReady(loaded = true, successfulImageTurn = true))
    }

    @Test
    fun knownTextOnlyGemmaLiteRtPackageIsNotSentImages() {
        assertTrue(isKnownTextOnlyLiteRtModel("/models/gemma-4-E2B-it-Uncensored-MAX.litertlm"))
        assertFalse(isKnownTextOnlyLiteRtModel("/models/gemma-4-E2B-it-gpu.litertlm"))

        val textOnlyStats = """{
            "loaded":true,
            "runnerReady":true,
            "visionInputTransportReady":true,
            "visionModelKnownTextOnly":true,
            "visionModelVisualComponentsPresent":false
        }"""
        val metadataTextOnlyStats = """{
            "loaded":true,
            "runnerReady":true,
            "visionInputTransportReady":true,
            "visionModelVisualComponentsPresent":false
        }"""
        val metadataVisionStats = """{
            "loaded":true,
            "runnerReady":true,
            "visionInputTransportReady":true,
            "visionModelVisualComponentsPresent":true
        }"""
        val unknownModelStats = """{
            "loaded":true,
            "runnerReady":true
        }"""
        val unloadedStats = """{"loaded":false,"runnerReady":true}"""

        assertFalse(liteRtVisionInputAvailable(textOnlyStats))
        assertFalse(liteRtVisionInputAvailable(metadataTextOnlyStats))
        assertTrue(liteRtVisionInputAvailable(metadataVisionStats))
        assertTrue(liteRtVisionInputAvailable(unknownModelStats))
        assertFalse(liteRtVisionInputAvailable(unloadedStats))
    }

    @Test
    fun textOnlyCapabilityIsRejectedBeforeNativeImageInvocation() {
        val source = sourceFile("core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt")
        val start = source.indexOf("val latest = messages.last()")
        val end = source.indexOf("val generationParams", start)
        require(start >= 0 && end > start)
        val body = source.substring(start, end)
        assertTrue(body.contains("messages.any { it.hasImageInput }"))
        assertTrue(body.contains("visionModelVisualComponentsPresent"))
        assertTrue(body.contains("LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE"))
    }

    @Test
    fun inlineImageByteEstimateHandlesWhitespaceAndPaddingWithoutDecoding() {
        assertEquals(1L, estimatedInlineVisionImageBytes("AQ=="))
        assertEquals(2L, estimatedInlineVisionImageBytes("AQI=\n"))
        assertEquals(3L, estimatedInlineVisionImageBytes("AQID"))
        assertEquals(0L, estimatedInlineVisionImageBytes(" \n "))
        assertTrue(inlineVisionImageWithinLimit("AQID", maxBytes = 3L))
        assertFalse(inlineVisionImageWithinLimit("AQID", maxBytes = 2L))
    }

    @Test
    fun liteRtImagePartsParseToNativeImageFilePartsInOriginalOrder() {
        val imageFile = Files.createTempFile("mca-litert-vision", ".jpg").toFile().apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val messages = parseLiteRtMessages(
            """[{"role":"user","content":[{"type":"text","text":"describe"},{"type":"image_url","image_url":{"url":"${imageFile.toURI()}"}}]}]"""
        )

        assertEquals(1, messages.size)
        assertEquals("describe", messages.single().content)
        val parts = messages.single().parts
        assertEquals(2, parts.size)
        assertEquals(LiteRtPromptPart.Text("describe"), parts[0])
        assertEquals(LiteRtPromptPart.ImageFile(imageFile.canonicalPath), parts[1])
        assertTrue(messages.single().hasImageInput)
    }

    @Test
    fun liteRtImagePartsRejectUnpreparedDataUrlsInsteadOfSilentlyDroppingImages() {
        val messages = """[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AQID"}}]}]"""

        val error = runCatching { parseLiteRtMessages(messages) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("转换为本地文件"))
    }

    @Test
    fun textOnlyLiteRtMessageDoesNotClaimAnImageWasSent() {
        val message = parseLiteRtMessages("""[{"role":"user","content":"hello"}]""").single()

        assertFalse(message.hasImageInput)
    }

    @Test
    fun cpuAndGpuSamplerSettingsAreForwardedAndSanitized() {
        val params = GenerationParams(
            topK = 0,
            topP = 1.5f,
            temperature = -1.0f,
            seed = 123
        )

        val config = requireNotNull(liteRtSamplerValuesFor(params, "gpu"))

        assertEquals(1, config.topK)
        assertEquals(1.0, config.topP, 0.0)
        assertEquals(0.0, config.temperature, 0.0)
        assertEquals(123, config.seed)
    }

    @Test
    fun acceleratorSamplersUseLiteRtDefaultsWhenDelegateDoesNotSupportOverrides() {
        val params = GenerationParams(topK = 7, topP = 0.4f, temperature = 0.2f, seed = 9)

        assertNull(liteRtSamplerValuesFor(params, "npu"))
        assertNull(liteRtSamplerValuesFor(params, "google_tensor"))
    }

    @Test
    fun sendFailureAfterPartialOutputCannotCommitConversationHistory() {
        val output = StringBuilder()
        val result = runCatching<Unit> {
            output.append("partial answer")
            throw IllegalStateException("send failed without onError")
        }

        assertFalse(liteRtConversationReusableAfterTurn(result, false, output))
    }

    @Test
    fun interruptedAwaitAfterPartialOutputCannotCommitConversationHistory() {
        val output = StringBuilder("partial answer")
        val result = runCatching<Unit> { throw InterruptedException("await interrupted") }

        assertFalse(liteRtConversationReusableAfterTurn(result, false, output))
    }

    @Test
    fun cancelledTurnCannotCommitEvenWhenCallbackCompletesSuccessfully() {
        assertFalse(liteRtConversationReusableAfterTurn(Result.success(Unit), true, "answer"))
    }

    @Test
    fun emptyAndWhitespaceOnlySuccessfulTurnsCannotCommitHistory() {
        listOf("", " ", "\n\t\r  ").forEach { output ->
            assertFalse(liteRtConversationReusableAfterTurn(Result.success(Unit), false, output))
        }
    }

    @Test
    fun completedVisibleTurnPreservesWhitespaceAndCanCommitHistory() {
        val output = StringBuilder("\n    return 1\n")

        assertTrue(liteRtConversationReusableAfterTurn(Result.success(Unit), false, output))
        assertEquals("\n    return 1\n", output.toString())
    }

    @Test
    fun failedOrCancelledConversationCannotBeReused() {
        val source = sourceFile("core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt")
        assertTrue(source.contains("conversationReusable &&"))
        assertTrue(source.contains("conversationReusable = false"))
        assertTrue(source.contains("val oldConversation = conversation"))
        assertTrue(source.contains("conversation = null"))
        assertTrue(source.contains("LiteRT-LM generation did not stop within the cancellation grace period."))
    }

    @Test
    fun qualcommNpuPreloadLoadsTheStagedDispatchPluginByAbsolutePath() {
        val source = sourceFile("core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt")
        val start = source.indexOf("private fun preloadQualcommHostLibraries")
        require(start >= 0)
        val end = source.indexOf("private fun isCompiledModelCacheFailure", start)
        require(end > start)
        val body = source.substring(start, end)
        assertTrue(body.contains("listOf(system, htp, dispatch)"))
        assertTrue(body.contains("System.load(library.absolutePath)"))
        assertTrue(body.contains("libLiteRtDispatch_Qualcomm.so"))
    }

    @Test
    fun cancellationDoesNotWaitForeverOnTheLifecycleLock() {
        val source = sourceFile("core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt")
        val classStart = source.indexOf("internal class LiteRtLmChatRunner")
        require(classStart >= 0)
        val start = source.indexOf("override fun requestStop()", classStart)
        val end = source.indexOf("override fun requestStopIfActive()", start)
        require(start >= 0 && end > start)
        val body = source.substring(start, end)
        assertTrue(body.contains("stopRequested.set(true)"))
        assertTrue(body.contains("cancellationGate.request"))
        assertTrue(!body.contains("synchronized(lifecycleLock)"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}
