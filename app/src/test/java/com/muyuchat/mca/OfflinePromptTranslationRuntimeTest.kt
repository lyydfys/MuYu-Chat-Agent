package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class OfflinePromptTranslationRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing bundle falls back without changing positive negative or protected syntax`() = runBlocking {
        val request = OfflinePromptTranslationRequest(
            sourceText = "一位女孩 <lora:portrait:0.8>",
            negativePrompt = "低质量, blurry"
        )

        val result = OfflinePromptTranslationService().translate(null, request)

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        val fallback = result as OfflinePromptTranslationResolution.Fallback
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
        assertEquals(OfflinePromptTranslationFallbackReason.BUNDLE_UNAVAILABLE, fallback.reason)
        assertTrue(fallback.protectedTokens.contains("<lora:portrait:0.8>"))
    }

    @Test
    fun `protected token extraction reports outer controls without duplicating nested metadata`() {
        val tokens = extractOfflinePromptProtectedTokens(
            "<lora:portrait:0.8> (masterpiece:1.2)"
        )

        assertTrue(tokens.contains("<lora:portrait:0.8>"))
        assertTrue(tokens.contains("(masterpiece:1.2)"))
        assertTrue(tokens.none { it == "lora:portrait" })
        assertTrue(extractOfflinePromptProtectedTokens("lora:portrait").contains("lora:portrait"))
    }

    @Test
    fun `negative prompt participates in request identity`() {
        val positive = "一位女孩"
        val first = OfflinePromptTranslationRequest(sourceText = positive, negativePrompt = "blurry")
        val second = OfflinePromptTranslationRequest(sourceText = positive, negativePrompt = "low quality")

        assertTrue(first.fingerprint != second.fingerprint)
    }

    @Test
    fun `default provider remains explicit unavailable and does not claim native execution`() {
        assertEquals(
            OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME,
            UnavailableOfflinePromptTranslationRuntime.nativeLibraryFileName
        )
        assertEquals(
            UnavailableOfflinePromptTranslationRuntime,
            DefaultOfflinePromptTranslationRuntimeProvider.runtimeFor(mockRuntimeBundle())
        )
    }

    @Test
    fun `runtime adapter must match the verified bundle identity`() = runBlocking {
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = "lib-wrong-model.so"
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ) = error("must not be called")
        }
        val result = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        ).translate(mockRuntimeBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟"))

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        assertEquals(
            OfflinePromptTranslationFallbackReason.NATIVE_RUNTIME_UNSUPPORTED,
            (result as OfflinePromptTranslationResolution.Fallback).reason
        )
    }

    @Test
    fun `concurrent translation is rejected without racing native runtime`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                started.complete(Unit)
                release.await()
                return OfflinePromptTranslationRuntimeOutcome.Failed("controlled test failure")
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 2_000
        )
        val first = launch {
            service.translate(mockRuntimeBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟"))
        }
        try {
            withTimeout(2_000) { started.await() }
            val second = service.translate(mockRuntimeBundle(), OfflinePromptTranslationRequest("一只红色小鸟"))
            assertTrue(second is OfflinePromptTranslationResolution.Fallback)
            assertEquals(
                OfflinePromptTranslationFallbackReason.RUNTIME_BUSY,
                (second as OfflinePromptTranslationResolution.Fallback).reason
            )
        } finally {
            release.complete(Unit)
            withTimeout(2_000) { first.join() }
        }
    }

    @Test
    fun `invalid translated output falls back and preserves original prompts`() = runBlocking {
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest(
            sourceText = "一位女孩 <lora:portrait:0.8> (masterpiece:1.2)",
            negativePrompt = "blurry, low quality"
        )
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ) = OfflinePromptTranslationRuntimeOutcome.Translated(
                bundle.createResult(request, "A portrait of a young woman")
            )
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        )

        val result = service.translate(bundle, request)

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        val fallback = result as OfflinePromptTranslationResolution.Fallback
        assertEquals(OfflinePromptTranslationFallbackReason.PROTECTED_SYNTAX_LOST, fallback.reason)
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
    }

    @Test
    fun `timeout preserves both prompts and releases the invocation gate for retry`() = runBlocking {
        val request = OfflinePromptTranslationRequest("一只蓝色小鸟", negativePrompt = "模糊")
        val bundle = mockRuntimeBundle()
        val invocations = AtomicInteger()
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                if (invocations.incrementAndGet() == 1) {
                    delay(500)
                    error("timeout test runtime should not finish")
                }
                return OfflinePromptTranslationRuntimeOutcome.Translated(
                    bundle.createResult(request, "A blue bird", "blurry")
                )
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 10
        )

        val result = service.translate(bundle, request)

        assertTrue(result is OfflinePromptTranslationResolution.Fallback)
        val fallback = result as OfflinePromptTranslationResolution.Fallback
        assertEquals(OfflinePromptTranslationFallbackReason.TIMEOUT, fallback.reason)
        assertEquals("runtime", fallback.stage)
        assertEquals("translation_timeout", fallback.errorCode)
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
        val retry = service.translate(bundle, request)
        assertTrue(retry is OfflinePromptTranslationResolution.Translated)
        assertEquals("A blue bird", (retry as OfflinePromptTranslationResolution.Translated).result.translatedText)
        assertEquals("blurry", retry.result.translatedNegativePrompt)
        assertEquals(2, invocations.get())
    }

    @Test
    fun `caller cancellation waits for actual start and permits a fresh retry`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val invocations = AtomicInteger()
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest("一只蓝色小鸟")
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome {
                if (invocations.incrementAndGet() == 1) {
                    started.complete(Unit)
                    delay(30_000)
                    error("cancel test runtime should not finish")
                }
                return OfflinePromptTranslationRuntimeOutcome.Translated(
                    bundle.createResult(request, "A blue bird")
                )
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime },
            timeoutMs = 60_000
        )
        val job = launch { service.translate(bundle, request) }
        try {
            withTimeout(2_000) { started.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            val retry = withTimeout(2_000) { service.translate(bundle, request) }
            assertTrue(retry is OfflinePromptTranslationResolution.Translated)
            assertEquals("A blue bird", (retry as OfflinePromptTranslationResolution.Translated).result.translatedText)
            assertEquals(2, invocations.get())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `an unconfirmed native release propagates instead of becoming a usable fallback`() = runBlocking {
        val expectedFailure = OfflinePromptTranslationCleanupException(
            IllegalStateException("worker release was not confirmed")
        )
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome = throw expectedFailure
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        )
        val actualFailure = runCatching {
            service.translate(mockRuntimeBundle(), OfflinePromptTranslationRequest("一只蓝色小鸟"))
        }.exceptionOrNull()
        assertEquals(OfflinePromptTranslationCleanupException::class.java, actualFailure?.javaClass)
        assertEquals(expectedFailure.message, actualFailure?.message)
        // Coroutine stack recovery can copy the outer exception and chain the original.
        // The native cleanup cause must survive even when outer object identity changes.
        val nativeCause = requireNotNull(expectedFailure.cause)
        val causeChain = generateSequence(actualFailure) { it.cause }.take(8).toList()
        assertTrue(causeChain.any { it === nativeCause })
        assertEquals(nativeCause.javaClass, causeChain.last().javaClass)
        assertEquals(nativeCause.message, causeChain.last().message)
    }

    @Test
    fun `a native failure preserves both prompts and permits a successful retry`() = runBlocking {
        val invocations = AtomicInteger()
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest("一只蓝色小鸟", negativePrompt = "模糊")
        val runtime = object : OfflinePromptTranslationRuntime {
            override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
            override suspend fun translate(
                bundle: VerifiedOfflinePromptTranslationBundle,
                request: OfflinePromptTranslationRequest
            ): OfflinePromptTranslationRuntimeOutcome = if (invocations.incrementAndGet() == 1) {
                OfflinePromptTranslationRuntimeOutcome.Failed("controlled native error")
            } else {
                OfflinePromptTranslationRuntimeOutcome.Translated(
                    bundle.createResult(request, "A blue bird", "blurry")
                )
            }
        }
        val service = OfflinePromptTranslationService(
            runtimeProvider = OfflinePromptTranslationRuntimeProvider { runtime }
        )
        val failure = service.translate(bundle, request)
        assertTrue(failure is OfflinePromptTranslationResolution.Fallback)
        val fallback = failure as OfflinePromptTranslationResolution.Fallback
        assertEquals(OfflinePromptTranslationFallbackReason.RUNTIME_FAILED, fallback.reason)
        assertEquals(request.sourceText, fallback.originalPrompt)
        assertEquals(request.negativePrompt, fallback.originalNegativePrompt)
        val retry = withTimeout(2_000) { service.translate(bundle, request) }
        assertTrue(retry is OfflinePromptTranslationResolution.Translated)
        assertEquals("A blue bird", (retry as OfflinePromptTranslationResolution.Translated).result.translatedText)
        assertEquals("blurry", retry.result.translatedNegativePrompt)
        assertEquals(2, invocations.get())
    }

    @Test
    fun `hy-mt2 manifest fixture is pinned and incomplete bytes are rejected`() {
        val root = Files.createTempDirectory("mca-hymt2-fixture").toFile()
        try {
            val translation = File(root, OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)
            assertTrue(translation.mkdirs())
            val manifest = requireNotNull(javaClass.classLoader?.getResourceAsStream(
                "offline_translation/hy-mt2/translation_manifest.json"
            ))
            File(translation, OfflinePromptTranslationContract.MANIFEST_FILE_NAME).outputStream().use {
                manifest.copyTo(it)
            }
            manifest.close()
            File(translation, HyMt2PromptTranslationContract.MODEL_FILE).writeBytes(byteArrayOf(0))
            File(translation, HyMt2PromptTranslationContract.MODEL_NOTICE).writeBytes(byteArrayOf(0))
            File(translation, HyMt2PromptTranslationContract.RUNTIME_NOTICE).writeBytes(byteArrayOf(0))

            val verification = OfflinePromptTranslationBundleVerifier.verify(root)
            assertTrue(verification is OfflinePromptTranslationBundleVerification.Rejected)
            assertEquals(
                OfflinePromptTranslationBundleRejectionCode.INTEGRITY_MISMATCH,
                (verification as OfflinePromptTranslationBundleVerification.Rejected).code
            )
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun `nested weights schedules and alternatives translate without changing syntax`() {
        val source = "(红色:1.25), ((小鸟)), [蓝色:绿色:0.60], {左边|右边}"
        val translations = mapOf(
            "红色" to "red", "小鸟" to "bird", "蓝色" to "blue",
            "绿色" to "green", "左边" to "left", "右边" to "right"
        )
        val seen = mutableListOf<String>()
        val translated = translateOfflinePromptSyntaxPlan(parseOfflinePromptTranslationSyntax(source), 1_000) { text, _ ->
            seen += text
            requireNotNull(translations[text])
        }

        assertEquals("(red:1.25), ((bird)), [blue:green:0.60], {left|right}", translated)
        assertEquals(listOf("红色", "小鸟", "蓝色", "绿色", "左边", "右边"), seen)
        assertTrue(hasMatchingOfflinePromptStructure(source, translated))
    }

    @Test
    fun `repeated lora tags and unknown named controls retain exact order and values`() {
        val source = "女孩 <lora:portrait:0.8> <lora:portrait:0.8> custom_namespace:foo-bar:0.7 <future:style:v2> (中文:1.2)"
        val plan = parseOfflinePromptTranslationSyntax(source)
        val seen = mutableListOf<String>()
        val translated = translateOfflinePromptSyntaxPlan(plan, 1_000) { text, _ ->
            seen += text
            when (text) {
                "女孩" -> "girl"
                "中文" -> "text"
                else -> error("Unexpected model-visible control: $text")
            }
        }

        assertEquals("girl <lora:portrait:0.8> <lora:portrait:0.8> custom_namespace:foo-bar:0.7 <future:style:v2> (text:1.2)", translated)
        assertEquals(listOf("女孩", "中文"), seen)
        assertEquals(2, plan.protectedTokens.count { it == "<lora:portrait:0.8>" })
        assertTrue(plan.protectedTokens.contains("custom_namespace:foo-bar:0.7"))
        assertTrue(plan.protectedTokens.contains("<future:style:v2>"))
        assertTrue(hasMatchingOfflinePromptStructure(source, translated))
    }

    @Test
    fun `escaped delimiters stay outside the model-visible text`() {
        val source = "女孩 \\(小鸟\\) \\[天空\\]"
        val seen = mutableListOf<String>()
        val translated = translateOfflinePromptSyntaxPlan(parseOfflinePromptTranslationSyntax(source), 1_000) { text, _ ->
            seen += text
            mapOf("女孩" to "girl", "小鸟" to "bird", "天空" to "sky").getValue(text)
        }

        assertEquals("girl \\(bird\\) \\[sky\\]", translated)
        assertEquals(listOf("女孩", "小鸟", "天空"), seen)
        assertTrue(hasMatchingOfflinePromptStructure(source, translated))
    }

    @Test
    fun `english fragments and their existing controls never call the translator`() {
        val source = "best quality, (masterpiece:1.2), [day:night:0.6], <lora:portrait:0.8>, embedding:detail"
        val translated = translateOfflinePromptSyntaxPlan(parseOfflinePromptTranslationSyntax(source), 1_000) { _, _ ->
            error("English-only content must remain verbatim")
        }
        assertEquals(source, translated)
    }

    @Test
    fun `missing reordered duplicated and modified controls or numbers are rejected`() {
        val source = "2只小鸟 <lora:first:0.8> <lora:second:0.6> (红色:1.2)"
        val valid = "2 birds <lora:first:0.8> <lora:second:0.6> (red:1.2)"
        assertTrue(hasMatchingOfflinePromptStructure(source, valid))
        val invalid = listOf(
            valid.replace("2 birds", "3 birds"),
            valid.replace("<lora:first:0.8>", ""),
            "2 birds <lora:second:0.6> <lora:first:0.8> (red:1.2)",
            valid.replace("<lora:first:0.8>", "<lora:first:0.8> <lora:first:0.8>"),
            valid.replace("first:0.8", "first:0.9"),
            valid.replace("red:1.2", "red:1.3"),
            "$valid <new:control:0.4>"
        )
        invalid.forEach { assertFalse("Unexpected accepted translation: $it", hasMatchingOfflinePromptStructure(source, it)) }
    }

    @Test
    fun `malformed and oversized syntax preserves both branches before native execution`() = runBlocking {
        val cases = listOf(
            "女孩 ([场景)]" to "prompt_syntax_unbalanced",
            "女孩 <unfinished" to "prompt_syntax_invalid_tag",
            "(".repeat(17) + "女孩" + ")".repeat(17) to "prompt_syntax_depth_limit",
            "女孩 " + ":".repeat(513) to "prompt_syntax_part_limit",
            (1..33).joinToString(" | ") { "猫" } to "prompt_translation_fragment_limit",
            "女孩 <" + "x".repeat(201) + ">" to "prompt_syntax_invalid_tag"
        )
        val invocations = AtomicInteger()
        val service = translatingService { _, _ ->
            invocations.incrementAndGet()
            error("Invalid structure must not enter native execution")
        }
        val bundle = mockRuntimeBundle()
        cases.forEach { (source, expectedCode) ->
            val request = OfflinePromptTranslationRequest(source, negativePrompt = "模糊 <lora:negative:0.2>")
            val result = service.translate(bundle, request) as OfflinePromptTranslationResolution.Fallback
            assertEquals(expectedCode, result.errorCode)
            assertEquals("syntax_validation", result.stage)
            assertEquals(request.sourceText, result.originalPrompt)
            assertEquals(request.negativePrompt, result.originalNegativePrompt)
        }
        assertEquals(0, invocations.get())
    }

    @Test
    fun `multiline prompts are valid while hidden controls and oversized text are rejected`() {
        val request = OfflinePromptTranslationRequest("蓝色小鸟\n清晨天空", negativePrompt = "模糊\r\n低质量")
        assertEquals("蓝色小鸟\n清晨天空", request.sourceText)
        assertTrue(hasMatchingOfflinePromptStructure(request.sourceText, "blue bird\nmorning sky"))
        listOf("\u0000", "\t", "\u200b", "\u202e").forEach { hidden ->
            assertThrows(IllegalArgumentException::class.java) { OfflinePromptTranslationRequest("小鸟$hidden") }
            assertThrows(IllegalArgumentException::class.java) { OfflinePromptTranslationRequest("小鸟", negativePrompt = "模糊$hidden") }
            assertEquals(
                "prompt_syntax_unsafe_character",
                assertThrows(OfflinePromptTranslationSyntaxException::class.java) {
                    parseOfflinePromptTranslationSyntax("小鸟$hidden")
                }.errorCode
            )
        }
        assertEquals(
            "prompt_syntax_size_limit",
            assertThrows(OfflinePromptTranslationSyntaxException::class.java) {
                parseOfflinePromptTranslationSyntax("猫".repeat(OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS + 1))
            }.errorCode
        )
    }

    @Test
    fun `invalid or newly introduced syntax from a translated text slot is rejected`() {
        val plan = parseOfflinePromptTranslationSyntax("2只小鸟")
        listOf("", "两只小鸟", "3 birds", "2 birds <new:control>", "2 birds\u200b").forEach { output ->
            assertThrows(OfflinePromptTranslationSyntaxException::class.java) {
                translateOfflinePromptSyntaxPlan(plan, 1_000) { _, _ -> output }
            }
        }
        assertEquals(
            "translation_text_invalid",
            assertThrows(OfflinePromptTranslationSyntaxException::class.java) {
                translateOfflinePromptSyntaxPlan(plan, 8) { _, _ -> "2 beautiful birds" }
            }.errorCode
        )
    }

    @Test
    fun `mismatched request identity never becomes a translated draft`() = runBlocking {
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest("女孩", negativePrompt = "模糊")
        val result = translatingService { loadedBundle, _ ->
            OfflinePromptTranslationRuntimeOutcome.Translated(
                loadedBundle.createResult(OfflinePromptTranslationRequest("男孩"), "boy")
            )
        }.translate(bundle, request) as OfflinePromptTranslationResolution.Fallback

        assertEquals("output_validation", result.stage)
        assertEquals("translation_result_identity_mismatch", result.errorCode)
        assertEquals(request.sourceText, result.originalPrompt)
        assertEquals(request.negativePrompt, result.originalNegativePrompt)
    }

    @Test
    fun `missing chinese negative translation retains its original branch`() = runBlocking {
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest("女孩", negativePrompt = "模糊")
        val result = translatingService { loadedBundle, loadedRequest ->
            OfflinePromptTranslationRuntimeOutcome.Translated(loadedBundle.createResult(loadedRequest, "girl"))
        }.translate(bundle, request) as OfflinePromptTranslationResolution.Fallback

        assertEquals("translation_negative_output_missing", result.errorCode)
        assertEquals("output_validation", result.stage)
        assertEquals(request.negativePrompt, result.originalNegativePrompt)
    }

    @Test
    fun `negative branch weight changes are diagnosed without losing positive or negative source`() = runBlocking {
        val bundle = mockRuntimeBundle()
        val request = OfflinePromptTranslationRequest("(女孩:1.2)", negativePrompt = "(模糊:1.4) <lora:bad:0.1>")
        val result = translatingService { loadedBundle, loadedRequest ->
            OfflinePromptTranslationRuntimeOutcome.Translated(
                loadedBundle.createResult(loadedRequest, "(girl:1.2)", "(blurry:1.5) <lora:bad:0.1>")
            )
        }.translate(bundle, request) as OfflinePromptTranslationResolution.Fallback

        assertEquals("translation_negative_structure_changed", result.errorCode)
        assertEquals(request.sourceText, result.originalPrompt)
        assertEquals(request.negativePrompt, result.originalNegativePrompt)
    }

    @Test
    fun `runtime failure includes phase and native code with short slots paths and keys redacted`() = runBlocking {
        val request = OfflinePromptTranslationRequest("猫 <future:opaque:0.3>", negativePrompt = "模糊")
        val result = translatingService { _, _ ->
            OfflinePromptTranslationRuntimeOutcome.Failed(
                "decode echoed 猫; failed 模糊; file=/data/user/0/com.muyuchat.mca/files/model.gguf; api_key=private-key-123; \u200b\u0000",
                stage = "decode", errorCode = "hy_mt2_decode_failed", nativeCode = -7
            )
        }.translate(mockRuntimeBundle(), request) as OfflinePromptTranslationResolution.Fallback

        assertEquals("decode", result.stage)
        assertEquals("hy_mt2_decode_failed", result.errorCode)
        assertEquals(-7, result.nativeCode)
        assertTrue(result.message.contains("阶段=decode"))
        assertTrue(result.message.contains("native=-7"))
        listOf("猫", "模糊", "/data/user/0", "private-key-123", "\u200b", "\u0000").forEach {
            assertFalse("Diagnostic leaked $it", result.message.contains(it))
        }
        assertEquals(request.sourceText, result.originalPrompt)
        assertEquals(request.negativePrompt, result.originalNegativePrompt)
    }

    @Test
    fun `invalid diagnostic stage and code are not exposed as trusted fields`() = runBlocking {
        val result = translatingService { _, _ ->
            OfflinePromptTranslationRuntimeOutcome.Failed("controlled failure", "load\nsecret", "bad code api_key=private-key", -4)
        }.translate(mockRuntimeBundle(), OfflinePromptTranslationRequest("小鸟")) as OfflinePromptTranslationResolution.Fallback

        assertEquals(null, result.stage)
        assertEquals(null, result.errorCode)
        assertFalse(result.message.contains("load\nsecret"))
        assertFalse(result.message.contains("private-key"))
        assertEquals(-4, result.nativeCode)
    }

    @Test
    fun `native load failure retains its specific reason before unload overwrites the journal`() {
        val stats = JSONObject()
            .put("loadFailureCode", "GGUF_UNSUPPORTED_MODEL")
            .put("nativeLoadResult", -3)
            .put("lastError", "unsupported architecture; /data/user/0/app/files/model.gguf")
            .put("workerStageJournal", JSONObject().put("stage", "load").put("state", "failed").put("failureCode", "native_load_failed"))
        val failure = hyMt2TranslationFailure(
            HyMt2TranslationStageException("load", "hy_mt2_load_failed", "Native load did not complete", -3),
            "load", stats.toString()
        )

        assertEquals("load", failure.stage)
        assertEquals("GGUF_UNSUPPORTED_MODEL", failure.errorCode)
        assertEquals(-3, failure.nativeCode)
        assertTrue(failure.message.contains("unsupported architecture"))
        assertFalse(failure.message.contains("/data/user/0"))
    }

    @Test
    fun `a completed old journal does not replace the current output validation failure`() {
        val stats = JSONObject()
            .put("loadFailureCode", "STALE_LOAD_ERROR")
            .put("workerStageJournal", JSONObject().put("stage", "load").put("state", "completed").put("failureCode", "native_load_failed"))
        val failure = hyMt2TranslationFailure(
            IllegalStateException("outer wrapper", HyMt2TranslationStageException("output_validation", "translation_structure_changed", "Current structure mismatch")),
            "decode", stats.toString()
        )

        assertEquals("output_validation", failure.stage)
        assertEquals("translation_structure_changed", failure.errorCode)
        assertTrue(failure.message.contains("Current structure mismatch"))
    }

    @Test
    fun `a failed worker endpoint preserves its own boundary rather than a stale load code`() {
        val diagnostic = LocalChatWorkerStageDiagnostic(
            stage = "prefill", state = "failed", runtime = "llama.cpp", modelFingerprint = null,
            parameterSummary = emptyMap(), workerPid = 123, pssKb = 1_024, startedAtEpochMs = 100,
            updatedAtEpochMs = 150, elapsedMs = 50, failureCode = "worker_binder_died"
        )
        val failure = hyMt2TranslationFailure(
            IllegalStateException("outer wrapper", RemoteLocalChatRunnerException("Worker endpoint died", workerStageDiagnostic = diagnostic)),
            "decode", JSONObject().put("loadFailureCode", "STALE_LOAD_ERROR").toString()
        )

        assertEquals("prefill", failure.stage)
        assertEquals("worker_binder_died", failure.errorCode)
        assertTrue(failure.message.contains("Worker endpoint died"))
    }

    @Test
    fun `diagnostic capture bounds cause traversal and does not expose hidden controls`() {
        var error: Throwable = HyMt2TranslationStageException("load", "OUTSIDE_CAUSE_LIMIT", "deep failure")
        repeat(10) { error = IllegalStateException("wrapper $it\u200b", error) }
        val failure = hyMt2TranslationFailure(error, "decode", "x".repeat(65_537))

        assertEquals("decode", failure.stage)
        assertEquals("hy_mt2_decode_failed", failure.errorCode)
        assertTrue(failure.message.length <= 600)
        assertFalse(failure.message.contains("deep failure"))
        assertFalse(failure.message.contains("\u200b"))
    }

    @Test
    fun `only completed live llama translation execution is accepted`() {
        listOf("llama.cpp-cpu", "llama.cpp-gpu").forEach { backend ->
            listOf("stop_token", "normal_finished").forEach { stopReason ->
                val stats = completedTranslationStats().put("backend", backend).put("generationStopReason", stopReason)
                val result = requireCompletedHyMt2Translation(stats.toString(), "A blue bird")
                assertEquals(backend, result.getString("backend"))
                assertEquals(stopReason, result.getString("generationStopReason"))
            }
        }
    }

    @Test
    fun `stub deferred lost cancelled truncated and empty execution cannot claim translation success`() {
        val cases = listOf(
            "backend" to "cpu-stub",
            "loaded" to false,
            "runtimeStatsDeferred" to true,
            "workerSessionLost" to true,
            "generationActive" to true,
            "stopRequested" to true,
            "generationStopReason" to "requested_stop",
            "generationStopReason" to "max_new_tokens",
            "generationStopReason" to "context_limit",
            "generationSequence" to 0,
            "completionTokens" to 0,
            "lastError" to "native decode failed"
        )
        cases.forEach { (key, value) ->
            assertThrows("Unexpected accepted stats $key=$value", HyMt2TranslationStageException::class.java) {
                requireCompletedHyMt2Translation(completedTranslationStats().put(key, value).toString(), "A blue bird")
            }
        }
        assertEquals("hy_mt2_empty_output", assertThrows(HyMt2TranslationStageException::class.java) {
            requireCompletedHyMt2Translation(completedTranslationStats().toString(), "")
        }.errorCode)
        listOf("not json", "{}", "x".repeat(65_537)).forEach { invalidStats ->
            assertThrows(HyMt2TranslationStageException::class.java) {
                requireCompletedHyMt2Translation(invalidStats, "A blue bird")
            }
        }
    }

    private fun completedTranslationStats(): JSONObject = JSONObject()
        .put("loaded", true).put("backend", "llama.cpp-cpu")
        .put("generationActive", false).put("stopRequested", false)
        .put("generationStopReason", "normal_finished")
        .put("generationSequence", 1).put("promptTokens", 43).put("completionTokens", 20)

    private fun translatingService(
        translation: suspend (VerifiedOfflinePromptTranslationBundle, OfflinePromptTranslationRequest) -> OfflinePromptTranslationRuntimeOutcome
    ): OfflinePromptTranslationService = OfflinePromptTranslationService(
        runtimeProvider = OfflinePromptTranslationRuntimeProvider {
            object : OfflinePromptTranslationRuntime {
                override val nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME
                override suspend fun translate(
                    bundle: VerifiedOfflinePromptTranslationBundle,
                    request: OfflinePromptTranslationRequest
                ): OfflinePromptTranslationRuntimeOutcome = translation.invoke(bundle, request)
            }
        }
    )

    private fun mockRuntimeBundle(): VerifiedOfflinePromptTranslationBundle {
        // Runtime contract tests inject a fake adapter and do not execute a native model.
        // Use real canonical files for snapshot/lifecycle checks; the small fixture bytes
        // are deliberately not an installable package. Verifier tests enforce pinned hashes.
        val root = temporaryFolder.newFolder().canonicalFile
        val translation = File(root, OfflinePromptTranslationContract.TRANSLATION_DIRECTORY)
        check(translation.mkdir())
        val manifestFile = File(translation, OfflinePromptTranslationContract.MANIFEST_FILE_NAME)
            .apply { writeText("{\"runtimeContractTestFixture\":true}") }.canonicalFile
        val modelFile = File(translation, OfflinePromptTranslationContract.MODEL_FILE_NAME)
            .apply { writeText("mock native model") }.canonicalFile
        val modelNotice = File(translation, OfflinePromptTranslationContract.MODEL_NOTICE_FILE_NAME)
            .apply { writeText("mock model provenance notice") }.canonicalFile
        val runtimeNotice = File(translation, OfflinePromptTranslationContract.RUNTIME_NOTICE_FILE_NAME)
            .apply { writeText("mock runtime provenance notice") }.canonicalFile
        val model = OfflinePromptTranslationProvenance(
            OfflinePromptTranslationContract.MODEL_SOURCE_ID,
            OfflinePromptTranslationContract.MODEL_SOURCE_REVISION,
            OfflinePromptTranslationContract.MODEL_LICENSE
        )
        val runtime = OfflinePromptTranslationProvenance(
            OfflinePromptTranslationContract.RUNTIME_SOURCE_ID,
            OfflinePromptTranslationContract.RUNTIME_SOURCE_REVISION,
            OfflinePromptTranslationContract.RUNTIME_LICENSE
        )
        val notices = listOf(
            OfflinePromptTranslationNotice(
                OfflinePromptTranslationContract.MODEL_NOTICE_RELATIVE_PATH,
                modelNotice,
                model,
                OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SHA256,
                OfflinePromptTranslationContract.MODEL_NOTICE_ARTIFACT_SIZE_BYTES
            ),
            OfflinePromptTranslationNotice(
                OfflinePromptTranslationContract.RUNTIME_NOTICE_RELATIVE_PATH,
                runtimeNotice,
                runtime,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SHA256,
                OfflinePromptTranslationContract.RUNTIME_NOTICE_ARTIFACT_SIZE_BYTES
            )
        )
        val identity = OfflinePromptTranslationBundleIdentity(
            model = model,
            runtime = runtime,
            modelArchitecture = OfflinePromptTranslationContract.MODEL_ARCHITECTURE,
            modelQuantization = OfflinePromptTranslationContract.MODEL_QUANTIZATION,
            sourceLanguage = OfflinePromptTranslationLanguage.ZH_HANS,
            targetLanguage = OfflinePromptTranslationLanguage.ENGLISH,
            sourceM2m100LanguageCode = OfflinePromptTranslationContract.SOURCE_M2M100_LANGUAGE_CODE,
            targetM2m100LanguageCode = OfflinePromptTranslationContract.TARGET_M2M100_LANGUAGE_CODE,
            modelSha256 = OfflinePromptTranslationContract.MODEL_ARTIFACT_SHA256,
            modelSizeBytes = OfflinePromptTranslationContract.MODEL_ARTIFACT_SIZE_BYTES,
            nativeLibraryFileName = OfflinePromptTranslationContract.NATIVE_LIBRARY_FILE_NAME,
            notices = notices
        )
        return VerifiedOfflinePromptTranslationBundle(
            rootDirectory = root,
            manifestFile = manifestFile,
            modelFile = modelFile,
            identity = identity
        ).also { assertTrue(it.matchesVerifiedFiles()) }
    }
}
