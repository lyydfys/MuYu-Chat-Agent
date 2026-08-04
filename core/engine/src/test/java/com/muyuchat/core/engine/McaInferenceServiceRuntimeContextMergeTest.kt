package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McaInferenceServiceRuntimeContextMergeTest {
    @Test
    fun existingWorldBookAndKnowledgeContextPrecedesDeviceClockInNativeRequest() = runBlocking {
        val runner = CapturingRunner()
        val clockProvider = DeviceClockContextProvider(
            clock = Clock.fixed(Instant.parse("2026-08-04T02:03:04Z"), ZoneId.of("UTC")),
            zoneIdProvider = { ZoneId.of("Asia/Shanghai") },
            localeProvider = { Locale.SIMPLIFIED_CHINESE }
        )
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            deviceClockContextProvider = clockProvider
        )
        service.loadModel(
            modelPath = "/models/runtime-context/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 32_768, nThreads = 4)
        ).getOrThrow()
        val dynamicContext = """
            [World book]
            杭州以西湖闻名。

            [Knowledge]
            西湖位于浙江杭州。
        """.trimIndent()
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "现在几点？顺便介绍西湖。")),
            params = GenerationParams(
                nCtx = 32_768,
                nPredict = 8,
                nThreads = 4,
                systemPrompt = "",
                reasoningMode = ReasoningMode.OFF,
                hideReasoning = true
            ),
            runtimeSystemContext = dynamicContext
        )

        val events = service.streamChat(request).toList()

        assertTrue(events.any { it is GenerateEvent.Done })
        val nativeMessages = JSONArray(requireNotNull(runner.messagesJson))
        val systemContent = (0 until nativeMessages.length())
            .asSequence()
            .map(nativeMessages::getJSONObject)
            .first { it.getString("role") == "system" }
            .getString("content")

        assertTrue(systemContent.contains("[World book]"))
        assertTrue(systemContent.contains("[Knowledge]"))
        assertTrue(systemContent.contains("当前本地日期为 2026-08-04"))
        assertTrue(systemContent.contains("时区为 Asia/Shanghai"))
        assertTrue(systemContent.contains("设备本地时间为 10:03:04"))
        assertTrue(systemContent.contains("UTC 偏移为 +08:00"))
        assertTrue(systemContent.indexOf("[World book]") < systemContent.indexOf("[Knowledge]"))
        assertTrue(systemContent.indexOf("[Knowledge]") < systemContent.indexOf("当前本地日期"))
        assertEquals(dynamicContext, request.runtimeSystemContext)
    }

    private class CapturingRunner : LocalChatRunner {
        private var statsJson = loadedStatsJson("{}")
        private var chunkPending = false
        var messagesJson: String? = null
            private set

        override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        override fun initBackends(nativeLibDir: String) = Unit

        override fun loadModel(modelPath: String, paramsJson: String): Int {
            statsJson = loadedStatsJson(paramsJson)
            return 0
        }

        override fun unloadModel() = Unit

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
            this.messagesJson = messagesJson
            chunkPending = true
            return 0
        }

        override fun generateNextChunk(): String? {
            if (!chunkPending) return null
            chunkPending = false
            return "captured"
        }

        override fun requestStop() = Unit
        override fun getRuntimeStatsJson(): String = statsJson
        override fun shutdown() = Unit

        private companion object {
            fun loadedStatsJson(paramsJson: String): String {
                val params = runCatching { JSONObject(paramsJson) }.getOrElse { JSONObject() }
                val advanced = when (val raw = params.opt("advanced_json")) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                    else -> JSONObject()
                }
                fun value(key: String, fallback: Any): Any = when {
                    params.has(key) && !params.isNull(key) -> params.opt(key)
                    advanced.has(key) && !advanced.isNull(key) -> advanced.opt(key)
                    else -> fallback
                }
                val nCtx = (value("n_ctx", 32_768) as Number).toInt()
                val nThreads = (value("n_threads", 4) as Number).toInt()
                val loadedConfig = JSONObject()
                    .put("backend_type", value("backend_type", "cpu"))
                    .put("precision", value("precision", "low"))
                    .put("memory", value("memory", "low"))
                    .put("power", value("power", "normal"))
                    .put("use_mmap", value("mmap", true))
                    .put("kvcache_mmap", value("kvcache_mmap", true))
                    .put("max_all_tokens", nCtx)
                    .put("n_ctx", nCtx)
                    .put("thread_num", nThreads)
                    .put("chunk", value("chunk", 128))
                return JSONObject()
                    .put("backend", LocalChatRuntime.MNN_CPU.backendId)
                    .put("loaded", true)
                    .put("runnerReady", true)
                    .put("nThreads", nThreads)
                    .put("nCtx", nCtx)
                    .put("maxAllTokens", nCtx)
                    .put("maxNewTokens", 8)
                    .put("backendDevices", JSONArray().put("cpu"))
                    .put("loadedConfigJson", loadedConfig.toString())
                    .put("lastConfigJson", loadedConfig.toString())
                    .toString()
            }
        }
    }

    private class FakeContext : ContextWrapper(null) {
        private val root = File(
            System.getProperty("java.io.tmpdir"),
            "mca-runtime-context-test-${System.nanoTime()}"
        )
        private val files = File(root, "files").also { it.mkdirs() }
        private val cache = File(root, "cache").also { it.mkdirs() }
        private val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = File(root, "lib").also { it.mkdirs() }.absolutePath
        }

        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getApplicationInfo(): ApplicationInfo = appInfo
    }
}
