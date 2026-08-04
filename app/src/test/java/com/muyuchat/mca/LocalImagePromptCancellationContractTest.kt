package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImagePromptCancellationContractTest {
    @Test
    fun `prompt preparation failures use fixed public messages and hide internals`() {
        val expectedMessages = linkedMapOf(
            "invalid_image_prompt" to
                "图片提示词长度或内容无效，尚未启动图片生成。",
            "invalid_image_profile_prompt_language" to
                "模型默认负向提示词语言不兼容，尚未启动图片生成。",
            "execution_contract_unsupported" to
                "当前生成设置与模型执行合同不兼容，尚未启动图片生成。",
            "image_prompt_translation_input_too_large" to
                "离线中译英输入过长，尚未启动图片生成。",
            "image_prompt_translation_input_too_complex" to
                "离线中译英输入结构过于复杂，尚未启动图片生成。",
            "image_prompt_translation_busy" to
                "本地离线翻译运行时正忙，尚未启动图片生成，请稍后重试。",
            "image_prompt_translation_unavailable" to
                "没有可核验的本地翻译模型，尚未启动图片生成。",
            "image_prompt_translation_timeout" to
                "中文提示词转换超时，尚未启动图片生成。",
            "image_prompt_translation_invalid" to
                "翻译结果未通过格式或语义一致性校验，尚未启动图片生成。",
            "image_prompt_translation_failed" to
                "中文提示词转换失败，尚未启动图片生成。"
        )

        expectedMessages.forEach { (code, expected) ->
            val internalDetail = "internal translator detail for $code"
            val actual = localImagePromptPreparationFailureMessage(
                LocalImageProductContractException(
                    code = code,
                    message = internalDetail
                )
            )
            assertEquals(expected, actual)
            assertFalse(actual.contains(internalDetail))
        }

        listOf<Exception>(
            LocalImageProductContractException(
                code = "unknown_internal_code",
                message = "native path and parser details"
            ),
            IllegalStateException("internal translator detail")
        ).forEach { error ->
            val actual = localImagePromptPreparationFailureMessage(error)
            assertEquals(LOCAL_IMAGE_PROMPT_PREPARATION_FALLBACK_MESSAGE, actual)
            assertFalse(actual.contains(requireNotNull(error.message)))
        }
    }

    @Test
    fun `legacy LLM prompt translation is not a local generation fallback`() {
        val source = mainViewModelSource()
        val prepare = functionBody(
            source,
            "private suspend fun prepareLocalImagePromptExecution("
        )

        assertFalse(source.contains("private suspend fun translateLocalImagePrompt("))
        assertFalse(prepare.contains("LocalImagePromptAlignmentV4"))
        assertFalse(prepare.contains("OfflinePromptTranslation"))
        assertTrue(prepare.contains("requireLocalImagePromptLanguageAdmission("))
        assertTrue(
            prepare.contains(
                "New local image requests cannot enter the legacy V4 LLM prompt translation path."
            )
        )
    }

    @Test
    fun `prompt preparation never acquires the chat runtime lifecycle lease`() {
        val prepare = functionBody(
            mainViewModelSource(),
            "private suspend fun prepareLocalImagePromptExecution("
        )

        assertFalse(prepare.contains("acquireExclusiveLifecycleLease("))
        assertFalse(prepare.contains("engine.streamChat("))
        assertFalse(prepare.contains("withTimeoutOrNull("))
        assertFalse(prepare.contains("LOCAL_IMAGE_PROMPT_TRANSLATION_TIMEOUT_MS"))
    }

    @Test
    fun `prompt preparation bounds request and final native text before execution`() {
        val prepare = functionBody(
            mainViewModelSource(),
            "private suspend fun prepareLocalImagePromptExecution("
        )
        val originalLimit = prepare.indexOf("if (prompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS")
        val finalNegative = prepare.indexOf("val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(")
        val effectiveLimit = prepare.indexOf(
            "if (finalNegativePrompt.value.length > LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS)",
            finalNegative
        )
        val evidence = prepare.indexOf("return LocalImagePromptExecution(", effectiveLimit)

        assertTrue(originalLimit >= 0)
        assertTrue(finalNegative > originalLimit)
        assertTrue(effectiveLimit > finalNegative)
        assertTrue(evidence > effectiveLimit)
        assertFalse(prepare.contains("LOCAL_IMAGE_PROMPT_TRANSLATION_"))
    }

    @Test
    fun `late prompt failure cannot overwrite cancellation or a different active job`() {
        val jobId = "prompt-race"
        val message = "中文提示词转换失败，尚未启动图片生成。"
        val generatingJobs = listOf(
            ImageGenerationJobRecord(
                id = jobId,
                prompt = "一只红色小鸟",
                status = ImageGenerationStatusRecord.GENERATING,
                backend = ImageBackend.LOCAL,
                message = "正在转换中文提示词"
            )
        )

        val failedJobs = generatingJobs.withLocalImagePromptPreparationFailureIfActive(
            activeJobId = jobId,
            jobId = jobId,
            message = message
        )
        assertNotSame(generatingJobs, failedJobs)
        assertEquals(ImageGenerationStatusRecord.FAILED, failedJobs.single().status)
        assertEquals(message, failedJobs.single().message)

        ImageGenerationStatusRecord.values()
            .filterNot { status -> status == ImageGenerationStatusRecord.GENERATING }
            .forEach { interleavedStatus ->
                val interleavedJobs = generatingJobs.map { job ->
                    job.copy(status = interleavedStatus, message = "取消路径已取得状态所有权")
                }
                val afterLateFailure =
                    interleavedJobs.withLocalImagePromptPreparationFailureIfActive(
                        activeJobId = jobId,
                        jobId = jobId,
                        message = message
                    )

                assertSame(interleavedJobs, afterLateFailure)
                assertEquals(interleavedStatus, afterLateFailure.single().status)
                assertEquals("取消路径已取得状态所有权", afterLateFailure.single().message)
            }

        listOf<String?>(null, "new-active-job").forEach { activeJobId ->
            val reassignedJobs = generatingJobs.withLocalImagePromptPreparationFailureIfActive(
                activeJobId = activeJobId,
                jobId = jobId,
                message = message
            )
            assertSame(generatingJobs, reassignedJobs)
            assertEquals(ImageGenerationStatusRecord.GENERATING, reassignedJobs.single().status)
        }
    }

    @Test
    fun `prompt method is derived from current text encoder and current text`() {
        assertEquals(
            LocalImagePromptTransformationMethod.DIRECT,
            requiredLocalImagePromptTransformationMethod(
                containsChinese = false,
                languageCapability = LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT
            )
        )
        assertEquals(
            LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL,
            requiredLocalImagePromptTransformationMethod(
                containsChinese = true,
                languageCapability = LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
            )
        )
        val rejection = assertThrows(IllegalStateException::class.java) {
            requiredLocalImagePromptTransformationMethod(
                containsChinese = true,
                languageCapability = LocalImageTextEncoderLanguageCapability.ENGLISH_DOMINANT
            )
        }
        assertTrue(requireNotNull(rejection.message).contains("residual Chinese"))
    }

    @Test
    fun `captured prompt evidence cannot override the required method`() {
        val prepare = functionBody(
            mainViewModelSource(),
            "private suspend fun prepareLocalImagePromptExecution("
        )
        val derive = prepare.indexOf("val requiredMethod = requiredLocalImagePromptTransformationMethod(")
        val captured = prepare.indexOf("captured?.let { execution ->")
        val stableBindingCheck = prepare.indexOf(
            "execution.promptLanguageBindingFingerprint ==",
            captured
        )
        val methodCheck = prepare.indexOf("execution.method == requiredMethod", stableBindingCheck)
        val capturedRebind = prepare.indexOf(
            "val rebound = execution.rebindToCurrentImageProfile(",
            methodCheck
        )
        val capturedReturn = prepare.indexOf("return rebound.copy(", capturedRebind)

        assertTrue(derive >= 0)
        assertTrue(captured > derive)
        assertTrue(stableBindingCheck > captured)
        assertTrue(methodCheck > stableBindingCheck)
        assertTrue(capturedRebind > methodCheck)
        assertTrue(capturedReturn > capturedRebind)
    }

    @Test
    fun `prompt evidence full fingerprint is resolved from final effective negative`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val prepare = functionBody(
            source,
            "private suspend fun prepareLocalImagePromptExecution("
        )
        val finalNegative = prepare.indexOf(
            "val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution("
        )
        val effectiveResolver = prepare.indexOf(
            "fun effectiveExecutionProfile(effectiveNegativePrompt: String)"
        )
        val effectiveOverride = prepare.indexOf(
            "options = profileOptions.copy(negativePrompt = effectiveNegativePrompt)",
            effectiveResolver
        )
        val directOrNative = prepare.indexOf("if (requiredMethod in setOf(", effectiveOverride)
        val effectiveProfile = prepare.indexOf(
            "val effectiveProfile = effectiveExecutionProfile(finalNegativePrompt.value)",
            directOrNative
        )
        val evidence = prepare.indexOf("return LocalImagePromptExecution(", effectiveProfile)

        assertTrue(finalNegative >= 0)
        assertTrue(effectiveResolver >= 0)
        assertTrue(effectiveResolver > finalNegative)
        assertTrue(effectiveOverride > effectiveResolver)
        assertTrue(directOrNative > effectiveOverride)
        assertTrue(effectiveProfile > directOrNative)
        assertTrue(evidence > effectiveProfile)
    }

    @Test
    fun `main activity validates prompt binding before publishing any image bytes`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val publish = functionBody(
            source,
            "private suspend fun createLocalGeneratedImageAsset("
        )
        val validate = publish.indexOf("validateLocalImagePromptExecutionBinding(")
        val firstWrite = publish.indexOf("writeImageAssetBytesAtomically(")
        val attachMetadata = publish.indexOf(".withNativeExecution(result.executionMetadataJson)")

        assertTrue(validate >= 0)
        assertTrue(attachMetadata > validate)
        assertTrue(firstWrite > attachMetadata)
    }

    @Test
    fun `authenticated api materializes random seed before prompt preparation`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val api = functionBody(source, "private suspend fun generateLocalApiImage(")
        val plan = api.indexOf("options = planLocalImageBatch(")
        val prepare = api.indexOf("prepareLocalImagePromptExecution(")
        val worker = api.indexOf("localImageWorkerClient.generate(")

        assertTrue(plan >= 0)
        assertTrue(prepare > plan)
        assertTrue(worker > prepare)
    }

    @Test
    fun `persisted translated prompt evidence cannot bypass current phase B verification`() {
        val recreate = functionBody(mainViewModelSource(), "fun recreateImageAsset(")
        val captured = recreate.indexOf("promptExecution = history.promptExecution?.takeIf")
        val trustBoundary = recreate.indexOf(
            "execution.method.isReusableFromImageHistory()",
            captured
        )

        assertTrue(captured >= 0)
        assertTrue(trustBoundary > captured)
    }

    @Test
    fun `UI prompt admission never truncates at six hundred and rejects only the explicit hard limit`() {
        val enqueue = functionBody(mainViewModelSource(), "private fun enqueueImageGeneration(")
        val normalization = enqueue.indexOf(".trim()")
        val hardLimit = enqueue.indexOf("LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS")

        assertTrue(normalization >= 0)
        assertFalse(enqueue.contains("take(600)"))
        assertTrue(hardLimit > normalization)
        assertTrue("中".repeat(601).length < LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS)
        assertTrue("中".repeat(LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS + 1).length >
            LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS
        )
    }

    @Test
    fun `language admission receives the final executed negative without automatic translation`() {
        val prepare = functionBody(
            mainViewModelSource(),
            "private suspend fun prepareLocalImagePromptExecution("
        )
        val finalNegative = prepare.indexOf(
            "val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution("
        )
        val admission = prepare.indexOf("requireLocalImagePromptLanguageAdmission(", finalNegative)
        val method = prepare.indexOf("val requiredMethod = requiredLocalImagePromptTransformationMethod(", admission)

        assertTrue(finalNegative >= 0)
        assertTrue(admission > finalNegative)
        assertTrue(method > admission)
        assertFalse(prepare.contains("LOCAL_IMAGE_PROMPT_TRANSLATION_"))
        assertFalse(prepare.contains("LocalImagePromptAlignmentV4"))
        assertTrue(prepare.contains("legacy V4 LLM prompt translation path"))
    }

    @Test
    fun `UI prepares prompt before worker admission and cancellation remains coroutine owned`() {
        val source = mainViewModelSource()
        val enqueue = functionBody(source, "private fun enqueueImageGeneration(")
        val promptPreparation = enqueue.indexOf(
            "prepareLocalImagePromptExecution("
        )
        val cancellationCatch = enqueue.indexOf(
            "catch (error: CancellationException)",
            promptPreparation
        )
        val nonFatalCatch = enqueue.indexOf("catch (error: Exception)", cancellationCatch)
        val guardedFailure = enqueue.indexOf(
            "withLocalImagePromptPreparationFailureIfActive(",
            nonFatalCatch
        )
        val activeOwnership = enqueue.indexOf(
            "activeJobId = activeImageGenerationJobId",
            guardedFailure
        )
        val ignoredTransition = enqueue.indexOf(
            "if (updatedJobs === state.imageJobs)",
            activeOwnership
        )
        val preparationReturn = enqueue.indexOf("return@launch", ignoredTransition)
        val promptPrepared = enqueue.indexOf(
            "currentCoroutineContext().ensureActive()",
            preparationReturn
        )
        val workerBegin = enqueue.indexOf("localImageWorkerClient.begin(model.runtime)")
        val postBeginCancellationCheck = enqueue.indexOf(
            "currentCoroutineContext().ensureActive()",
            workerBegin
        )
        val nativeBatch = enqueue.indexOf("executeLocalImageBatchPlan(", workerBegin)

        assertTrue(promptPreparation >= 0)
        assertFalse(enqueue.substring(0, promptPreparation).contains("localImageWorkerClient.begin("))
        assertTrue(cancellationCatch > promptPreparation)
        assertTrue(enqueue.substring(cancellationCatch, nonFatalCatch).contains("throw error"))
        assertTrue(nonFatalCatch > cancellationCatch)
        assertTrue(guardedFailure > nonFatalCatch)
        assertTrue(activeOwnership > guardedFailure)
        assertTrue(ignoredTransition > activeOwnership)
        assertTrue(preparationReturn > ignoredTransition)
        assertTrue(
            enqueue.substring(nonFatalCatch, preparationReturn)
                .contains("localImagePromptPreparationFailureMessage(error)")
        )
        assertTrue(promptPrepared > promptPreparation)
        assertTrue(promptPrepared > preparationReturn)
        assertTrue(workerBegin > promptPrepared)
        assertTrue(postBeginCancellationCheck > workerBegin)
        assertTrue(nativeBatch > postBeginCancellationCheck)

        val cancel = functionBody(source, "fun cancelImageGeneration()")
        val clientCancel = cancel.indexOf("localImageWorkerClient.cancel()")
        val callerOwnedBranch = cancel.indexOf("if (!(localGeneration && nativeCancelRequested))")
        val coroutineCancel = cancel.indexOf("?.cancel()", callerOwnedBranch)
        assertTrue(clientCancel >= 0)
        assertTrue(callerOwnedBranch > clientCancel)
        assertTrue(coroutineCancel > callerOwnedBranch)

        assertFalse(source.contains("private suspend fun translateLocalImagePrompt("))
        assertFalse(source.contains("LOCAL_IMAGE_PROMPT_TRANSLATION_TIMEOUT_MS"))
    }

    @Test
    fun `preparation-only worker cancellation cannot claim a native request`() {
        val client = localImageWorkerClientSource()
        val begin = functionBody(client, "fun begin(runtime: LocalImageRuntime)")
        val cancel = functionBody(client, "fun cancel(): Boolean")
        val noRequest = cancel.indexOf("if (request == null) {")
        val remotePreparationCancel = cancel.indexOf(
            "endpoint.service.cancel(LocalImageWorkerProtocol.cancelRequest(null))",
            noRequest
        )
        val callerOwned = cancel.indexOf("return false", remotePreparationCancel)
        val registeredRequest = cancel.indexOf("return when (request.handshake.requestCancel())")

        assertTrue(noRequest >= 0)
        assertTrue(remotePreparationCancel > noRequest)
        assertTrue(callerOwned > remotePreparationCancel)
        assertTrue(registeredRequest > callerOwned)
        assertFalse(cancel.substring(noRequest, registeredRequest).contains("return supportsNativeCancel"))
        assertTrue(begin.contains("if (next.cancelRequested || !isCurrentPreparation(next))"))
        assertTrue(begin.contains("releaseBindingAfterPreparationFailure(next, cancelled)"))
    }

    private fun mainViewModelSource(): String = sourceFile("MainViewModel.kt")

    private fun localImageWorkerClientSource(): String = sourceFile("LocalImageWorkerClient.kt")

    private fun sourceFile(fileName: String): String {
        var root = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            listOf(
                File(root, fileName),
                File(root, "src/main/java/com/muyuchat/mca/$fileName"),
                File(root, "app/src/main/java/com/muyuchat/mca/$fileName")
            ).firstOrNull(File::isFile)?.let { return it.readText(Charsets.UTF_8) }
            root = root.parentFile ?: return@repeat
        }
        error("Unable to locate $fileName")
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingParenthesis = source.indexOf('(', start)
        require(openingParenthesis >= 0) { "Missing function parameter list: $signature" }
        var parenthesisDepth = 0
        var closingParenthesis = -1
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> parenthesisDepth += 1
                ')' -> {
                    parenthesisDepth -= 1
                    if (parenthesisDepth == 0) {
                        closingParenthesis = index
                        break
                    }
                }
            }
        }
        require(closingParenthesis >= 0) { "Unterminated function parameter list: $signature" }
        val openingBrace = source.indexOf('{', closingParenthesis)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }
}
