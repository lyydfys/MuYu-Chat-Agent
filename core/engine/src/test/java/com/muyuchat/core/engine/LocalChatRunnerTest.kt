package com.muyuchat.core.engine

import com.geniex.sdk.bean.ModelType
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalChatRunnerTest {
    @Test
    fun llmMessageParsingPreservesCanonicalRoleAndContentBeforeNativeAbiAdapter() {
        val messages = genieXLlmMessagesFromJson(
            """
            [
              {"role":"system","content":"Answer briefly."},
              {"role":"user","content":"请计算 6×7，只回复数字答案。"}
            ]
            """.trimIndent()
        )

        assertEquals("system", messages[0].role)
        assertEquals("Answer briefly.", messages[0].content)
        assertEquals("user", messages[1].role)
        assertEquals("请计算 6×7，只回复数字答案。", messages[1].content)

        val nativeMessages = genieXNativeLlmMessages(messages)
        assertEquals(2, nativeMessages.size)
        assertEquals("system", nativeMessages[0].role)
        assertEquals("Answer briefly.", nativeMessages[0].content)
        assertEquals("user", nativeMessages[1].role)
        assertEquals("请计算 6×7，只回复数字答案。", nativeMessages[1].content)
        assertTrue(genieXLlmBoundaryIsCanonical(messages, nativeMessages))
    }

    @Test
    fun llmTemplateMustBeVisibleBeforeGenerationStarts() {
        assertEquals("prompt", requireNonBlankGenieXTemplate("prompt"))
        assertThrows(IllegalStateException::class.java) {
            requireNonBlankGenieXTemplate("")
        }
    }

    @Test
    fun genieXInitAcceptsOnlyAnEmptySdkFailure() {
        requireSuccessfulGenieXSdkInit(null)
        requireSuccessfulGenieXSdkInit("")
        requireSuccessfulGenieXSdkInit("   ")
    }

    @Test
    fun genieXInitSurfacesFreshPluginRegistrationFailure() {
        val failure = assertThrows(IllegalStateException::class.java) {
            requireSuccessfulGenieXSdkInit("Cannot registerPlugin llama_cpp")
        }

        assertEquals("Cannot registerPlugin llama_cpp", failure.message)
    }

    @Test
    fun detectsTextAndVisionGenieXModelsWithoutGuessingFromRuntime() {
        val textDir = Files.createTempDirectory("mca-geniex-text").toFile()
        val visionDir = Files.createTempDirectory("mca-geniex-vlm").toFile()
        try {
            textDir.resolve("metadata.json").writeText("""{"model_name":"Qwen3-4B-Instruct"}""")
            visionDir.resolve("metadata.json").writeText("""{"model_name":"Qwen3-VL-4B-Instruct"}""")

            assertEquals(ModelType.LLM, detectGenieXModelType(textDir))
            assertEquals(ModelType.VLM, detectGenieXModelType(visionDir))
            assertEquals(
                ModelType.VLM,
                detectGenieXModelType(textDir, visionDir.resolve("mmproj-model.gguf").absolutePath)
            )
        } finally {
            textDir.deleteRecursively()
            visionDir.deleteRecursively()
        }
    }

    @Test
    fun vlmMessageParsingInjectsOnlyCurrentUserImages() {
        val messages = genieXVlmMessagesFromJson(
            """
            [
              {"role":"user","content":[
                {"type":"text","text":"old"},
                {"type":"image_url","image_url":{"url":"file:///tmp/old.jpg"}}
              ]},
              {"role":"assistant","content":"done"},
              {"role":"user","content":[
                {"type":"text","text":"describe this"},
                {"type":"image_url","image_url":{"url":"file:///tmp/current.jpg"}}
              ]}
            ]
            """.trimIndent()
        )

        assertEquals(3, messages.size)
        assertEquals("describe this", messages.last().contents.first().text)
        assertEquals(listOf("/tmp/current.jpg"), genieXCurrentTurnImagePaths(messages).toList())
    }

    @Test
    fun defaultRunnersExposeMnnAndGgufBackends() {
        val runners = defaultLocalChatRunners()

        assertEquals(LocalChatRuntime.MNN_CPU, runners.keys.first())
        assertEquals("MNN 高速引擎", LocalChatRuntime.MNN_CPU.label)
        assertEquals("GGUF 兼容引擎", LocalChatRuntime.LLAMA_CPP.label)
        assertEquals("GenieX llama.cpp / 骁龙 HTP", LocalChatRuntime.GENIEX_LLAMA_CPP.label)
        assertEquals("GenieX QAIRT NPU", LocalChatRuntime.GENIEX_QAIRT.label)
        assertTrue(runners.containsKey(LocalChatRuntime.MNN_CPU))
        assertTrue(runners.containsKey(LocalChatRuntime.LLAMA_CPP))
        assertTrue(runners.containsKey(LocalChatRuntime.GENIEX_LLAMA_CPP))
        assertTrue(runners.containsKey(LocalChatRuntime.GENIEX_QAIRT))
    }

    @Test
    fun geniexLlamaCpuFallbackRequiresAnExplicitCpuComputeUnit() {
        assertTrue(
            genieXLlamaCppCpuFallbackRequested(
                """{"geniex_compute_unit":"CPU"}"""
            )
        )
        assertTrue(
            genieXLlamaCppCpuFallbackRequested(
                """{"compute_unit":"cpu_only"}"""
            )
        )
        assertTrue(
            genieXLlamaCppCpuFallbackRequested(
                """{"advanced_json":"{\"geniex_compute_unit\":\"cpu\"}"}"""
            )
        )
        assertFalse(genieXLlamaCppCpuFallbackRequested("{}"))
        assertFalse(
            genieXLlamaCppCpuFallbackRequested(
                """{"geniex_compute_unit":"hybrid"}"""
            )
        )
        assertFalse(
            genieXLlamaCppCpuFallbackRequested(
                """{"geniex_compute_unit":"gpu"}"""
            )
        )
        assertFalse(
            genieXLlamaCppCpuFallbackRequested(
                """{"geniex_compute_unit":"npu"}"""
            )
        )
    }

    @Test
    fun geniexLlamaRuntimeUsesTheSafeSelectorWithoutReplacingAcceleratedChoices() {
        val source = sourceFile(
            "core/engine/src/main/java/com/muyuchat/core/engine/LocalChatRunner.kt"
        )
        val factoryBody = source.substringAfter("fun defaultLocalChatRunner(")
            .substringBefore("fun defaultLocalChatRunners(")

        assertTrue(factoryBody.contains("GenieXLlamaCppChatRunner(context)"))
        assertTrue(source.contains("only an explicit CPU"))
        assertTrue(source.contains("Hybrid/GPU/NPU requests continue to"))
        assertTrue(source.contains("EXECUTION_PATH_CPU_FALLBACK"))
        assertTrue(source.contains("delegate.initBackends(nativeLibDir)"))
    }

    @Test
    fun unavailableMnnRunnerReportsExplicitBackendError() {
        val runner = MnnCpuChatRunner()
        if (runner.isAvailable) return

        val stats = JSONObject(runner.getRuntimeStatsJson())

        assertFalse(stats.getBoolean("loaded"))
        assertFalse(stats.getBoolean("runnerReady"))
        assertEquals("mnn_cpu", stats.getString("backend"))
        assertEquals(0, stats.getInt("nCtx"))
        assertEquals(0, stats.getInt("maxAllTokens"))
        assertTrue(stats.getString("lastError").contains("MNN 高速引擎不可用"))
        assertEquals(-100, runner.loadModel("config.json", "{}"))
    }

    @Test
    fun genieXStreamChunksPreserveChineseAndSurrogatePairsWithoutCharsetRoundTrip() {
        val assembler = GenieXUtf8ChunkAssembler()

        val result = buildString {
            append(assembler.append("中文"))
            append(assembler.append("输出："))
            append(assembler.append("\uD83D"))
            append(assembler.append("\uDE00完成"))
            append(assembler.finish())
        }

        assertEquals("中文输出：😀完成", result)
    }

    @Test
    fun genieXStreamChunksDecodeGpt2ByteAlphabetAcrossCallbacks() {
        val assembler = GenieXUtf8ChunkAssembler()

        assertEquals("识别：", assembler.append("识别："))
        assertEquals("", assembler.append("\u00E5"))
        assertEquals("", assembler.append("\u0143"))
        assertEquals("子完成", assembler.append("\u0132完成"))
        assertEquals("", assembler.finish())
    }

    @Test
    fun genieXStreamChunksPreserveValidLatinText() {
        val assembler = GenieXUtf8ChunkAssembler()

        val result = buildString {
            append(assembler.append("Café "))
            append(assembler.append("å"))
            append(assembler.append("land"))
            append(assembler.finish())
        }

        assertEquals("Café åland", result)
    }

    @Test
    fun genieXStreamChunksPreserveStandaloneLatinExtendedACharacters() {
        val assembler = GenieXUtf8ChunkAssembler()

        assertEquals("\u0100", assembler.append("\u0100"))
        assertEquals("\u0120", assembler.append("\u0120"))
        assertEquals("\u0132", assembler.append("\u0132"))
        assertEquals("", assembler.finish())
    }

    @Test
    fun genieXStreamChunksPreserveLatinExtendedAAfterInterruptedUtf8Candidate() {
        val assembler = GenieXUtf8ChunkAssembler()

        assertEquals("", assembler.append("\u00E5"))
        assertEquals("\u00E5\u0100\u0120", assembler.append("\u0100\u0120"))
        assertEquals("", assembler.finish())
    }

    @Test
    fun genieXStreamChunksDecodeValidTwoThreeAndFourByteGpt2Utf8() {
        val assembler = GenieXUtf8ChunkAssembler()

        val result = buildString {
            append(assembler.append("\u00C3"))
            append(assembler.append("\u00A9"))
            append(assembler.append("\u00E5\u0143"))
            append(assembler.append("\u0132"))
            append(assembler.append("\u00F0\u0141\u013A"))
            append(assembler.append("\u0122"))
            append(assembler.finish())
        }

        assertEquals("é子😀", result)
    }

    @Test
    fun genieXStreamChunksFinishFlushesIncompleteGpt2Utf8Verbatim() {
        val assembler = GenieXUtf8ChunkAssembler()

        assertEquals("", assembler.append("\u00E5"))
        assertEquals("", assembler.append("\u0143"))
        assertEquals("\u00E5\u0143", assembler.finish())
        assertEquals("", assembler.finish())
    }

    @Test
    fun destroyGenieXHandleUsesSdkDestroyMethodExactlyOnce() {
        val engine = DestroyableEngine()

        assertEquals(0, destroyGenieXHandle(engine, 42L))
        assertEquals(42L, engine.destroyedHandle)
        assertEquals(1, engine.destroyCalls)
    }

    private class DestroyableEngine {
        var destroyedHandle = 0L
        var destroyCalls = 0

        @Suppress("unused")
        private fun destroy(handle: Long): Int {
            destroyedHandle = handle
            destroyCalls += 1
            return 0
        }
    }

    private fun sourceFile(relativePath: String): String {
        var directory = java.io.File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            val candidate = java.io.File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile ?: break
        }
        error("Unable to locate source: $relativePath")
    }
}
