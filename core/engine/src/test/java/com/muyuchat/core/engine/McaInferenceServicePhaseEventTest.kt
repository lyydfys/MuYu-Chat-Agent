package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McaInferenceServicePhaseEventTest {
    @Test
    fun streamEmitsOrderedIndeterminatePhasesAroundNativeGeneration() = runBlocking {
        val runner = PhaseRunner().apply { enqueue("answer") }
        val service = loadedService(runner)

        val events = service.streamChat(request()).onEach { event ->
            if (event is GenerateEvent.Phase) runner.timeline += "phase:${event.phase}"
        }.toList()
        val phases = events.filterIsInstance<GenerateEvent.Phase>()

        assertEquals(
            listOf(
                GenerationPhase.TOKENIZE,
                GenerationPhase.PREFILL,
                GenerationPhase.DECODE,
                GenerationPhase.PERSIST
            ),
            phases.map(GenerateEvent.Phase::phase)
        )
        assertNull(phases.single { it.phase == GenerationPhase.PREFILL }.tokenProgress)

        val decodeIndex = events.indexOfFirst { it is GenerateEvent.Phase && it.phase == GenerationPhase.DECODE }
        val chunkIndex = events.indexOfFirst { it is GenerateEvent.Chunk }
        val persistIndex = events.indexOfFirst { it is GenerateEvent.Phase && it.phase == GenerationPhase.PERSIST }
        val doneIndex = events.indexOfFirst { it is GenerateEvent.Done }
        assertTrue(decodeIndex in 0 until chunkIndex)
        assertTrue(chunkIndex < persistIndex)
        assertTrue(persistIndex < doneIndex)
        assertTrue(runner.timeline.indexOf("phase:TOKENIZE") < runner.timeline.indexOf("begin"))
        assertTrue(runner.timeline.indexOf("begin") < runner.timeline.indexOf("phase:PREFILL"))
    }

    @Test
    fun exactNativePrefillSnapshotIsPublishedBeforeDecode() = runBlocking {
        val runner = PhaseRunner().apply {
            enqueue("answer")
            prefillStepsDuringBegin = listOf(TokenProgress(completedTokens = 12, totalTokens = 12))
        }
        val service = loadedService(runner, prefillProgressPollIntervalMs = 5L)

        val events = service.streamChat(request()).toList()
        val exactPrefill = events.filterIsInstance<GenerateEvent.Phase>()
            .filter { it.phase == GenerationPhase.PREFILL }

        assertEquals(listOf(TokenProgress(12, 12)), exactPrefill.mapNotNull { it.tokenProgress })
        assertFalse(exactPrefill.any { it.tokenProgress == null })
        val prefillIndex = events.indexOfFirst {
            it is GenerateEvent.Phase && it.phase == GenerationPhase.PREFILL
        }
        val decodeIndex = events.indexOfFirst {
            it is GenerateEvent.Phase && it.phase == GenerationPhase.DECODE
        }
        assertTrue(prefillIndex in 0 until decodeIndex)
    }

    @Test
    fun streamPublishesExactPrefillBatchesBeforeNativeBeginReturns() = runBlocking {
        val expected = listOf(
            TokenProgress(completedTokens = 0, totalTokens = 12),
            TokenProgress(completedTokens = 5, totalTokens = 12),
            TokenProgress(completedTokens = 12, totalTokens = 12)
        )
        val runner = PhaseRunner().apply {
            enqueue("answer")
            prefillStepsDuringBegin = expected
        }
        val service = loadedService(runner, prefillProgressPollIntervalMs = 5L)

        val events = service.streamChat(request()).onEach { event ->
            if (event is GenerateEvent.Phase) runner.timeline += "phase:${event.phase}"
        }.toList()
        val reported = events.filterIsInstance<GenerateEvent.Phase>()
            .filter { it.phase == GenerationPhase.PREFILL }
            .mapNotNull(GenerateEvent.Phase::tokenProgress)

        assertEquals(expected, reported)
        assertEquals(1, runner.resetPrefillProgressCalls)
        assertTrue(
            runner.timeline.indexOf("phase:PREFILL") <
                runner.timeline.indexOf("begin:return")
        )
    }

    @Test
    fun decodePhaseCarriesTerminalPrefillStatsBeforeFirstChunk() = runBlocking {
        val runner = PhaseRunner().apply {
            enqueue("answer")
            terminalPrefillTokens = 80
            terminalPrefillMs = 400L
        }
        val service = loadedService(runner, prefillProgressPollIntervalMs = 5L)

        val events = service.streamChat(request()).toList()
        val decode = events.filterIsInstance<GenerateEvent.Phase>()
            .single { it.phase == GenerationPhase.DECODE }
        val decodeIndex = events.indexOf(decode)
        val firstChunkIndex = events.indexOfFirst { it is GenerateEvent.Chunk }

        assertEquals(80, decode.stats.promptTokens)
        assertEquals(80, decode.stats.prefillTokens)
        assertEquals(400L, decode.stats.prefillMs)
        assertEquals(200.0, decode.stats.prefillTps, 0.001)
        assertEquals(0, decode.stats.completionTokens)
        assertEquals(0.0, decode.stats.decodeTps, 0.001)
        assertTrue(decodeIndex in 0 until firstChunkIndex)
        val firstChunk = events.filterIsInstance<GenerateEvent.Chunk>().first()
        assertEquals(80, firstChunk.stats.prefillTokens)
    }

    @Test
    fun streamClearsCompletedPrefillSnapshotBeforeStartingNextNativeRequest() = runBlocking {
        val runner = PhaseRunner().apply {
            enqueue("answer")
            prefillProgress = TokenProgress(completedTokens = 9, totalTokens = 9)
        }
        val service = loadedService(runner, prefillProgressPollIntervalMs = 5L)

        val events = service.streamChat(request()).toList()

        assertEquals(1, runner.resetPrefillProgressCalls)
        assertTrue(
            events.filterIsInstance<GenerateEvent.Phase>()
                .filter { it.phase == GenerationPhase.PREFILL }
                .all { it.tokenProgress == null }
        )
    }

    @Test
    fun beginFailureReportsOnlyEnteredPhasesThenError() = runBlocking {
        val runner = PhaseRunner().apply { beginReturnCode = -9 }
        val service = loadedService(runner)

        val events = service.streamChat(request()).toList()

        assertEquals(
            listOf(GenerationPhase.TOKENIZE),
            events.filterIsInstance<GenerateEvent.Phase>().map(GenerateEvent.Phase::phase)
        )
        assertTrue(events.last() is GenerateEvent.Error)
        assertFalse(events.any { it is GenerateEvent.Phase && it.phase == GenerationPhase.DECODE })
        assertFalse(events.any { it is GenerateEvent.Phase && it.phase == GenerationPhase.PERSIST })
        assertEquals(0, runner.generateCalls)
    }

    @Test
    fun streamTimeMnnRecoveryEmitsLoadBeforeRetryingGeneration() = runBlocking {
        val runner = PhaseRunner().apply {
            beginFailure = IllegalStateException("first begin failed")
        }
        val service = loadedService(runner)

        val failed = service.streamChat(request()).toList()
        assertTrue(failed.last() is GenerateEvent.Error)

        runner.beginFailure = null
        runner.enqueue("recovered")
        val recovered = service.streamChat(request()).toList()
        val phases = recovered.filterIsInstance<GenerateEvent.Phase>().map(GenerateEvent.Phase::phase)

        assertEquals(GenerationPhase.LOAD, phases.first())
        assertEquals(
            listOf(
                GenerationPhase.LOAD,
                GenerationPhase.TOKENIZE,
                GenerationPhase.PREFILL,
                GenerationPhase.DECODE,
                GenerationPhase.PERSIST
            ),
            phases
        )
        assertTrue(recovered.last() is GenerateEvent.Done)
    }

    @Test
    fun conversationContextInvalidationIsSerializedThroughTheActiveRunner() = runBlocking {
        val runner = PhaseRunner()
        val service = loadedService(runner)

        service.invalidateConversationContext()
        runner.enqueue("fresh")
        val events = service.streamChat(request()).toList()

        assertEquals(1, runner.contextInvalidationCalls)
        assertFalse(
            events.any { it is GenerateEvent.Phase && it.phase == GenerationPhase.LOAD }
        )
        assertTrue(events.last() is GenerateEvent.Done)
    }

    @Test
    fun foregroundHealthSnapshotSkipsAnOwnedLifecycleAndReadsAfterRelease() = runBlocking {
        val runner = PhaseRunner()
        val service = loadedService(runner)
        val lease = service.acquireExclusiveLifecycleLease(stopActiveGeneration = false)

        try {
            assertNull(service.tryRuntimeHealthSnapshot())
        } finally {
            lease.release()
        }

        val snapshot = requireNotNull(service.tryRuntimeHealthSnapshot())
        assertEquals(service.stats.value, snapshot.runtimeStats)
        assertFalse(snapshot.workerSessionLost)
    }

    @Test
    fun lostWorkerHealthIsProjectedAsUnloadedWhileKeepingRecoveryPossible() = runBlocking {
        val runner = PhaseRunner()
        val service = loadedService(runner)
        runner.sessionKnownLost = true

        val snapshot = requireNotNull(service.tryRuntimeHealthSnapshot())

        assertTrue(snapshot.workerSessionLost)
        assertFalse(snapshot.runtimeStats.loaded)
        assertFalse(service.stats.value.loaded)
    }

    @Test
    fun beginFailureFromLostWorkerImmediatelyClearsLoadedProjection() = runBlocking {
        val runner = PhaseRunner().apply {
            beginFailure = IllegalStateException("worker binder died")
            sessionKnownLost = true
        }
        val service = loadedService(runner)

        val events = service.streamChat(request()).toList()

        val error = events.last() as GenerateEvent.Error
        assertFalse(error.stats.loaded)
        assertFalse(service.stats.value.loaded)
    }

    @Test
    fun staleGenerationStopTokenCannotStopAReplacementRequest() = runBlocking {
        val runner = PhaseRunner()
        val service = loadedService(runner)
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        runner.blockNextGenerate(firstEntered, firstRelease, "first")
        val first = async(Dispatchers.Default) { service.streamChat(request()).toList() }

        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val firstToken = requireNotNull(service.activeGenerationStopToken())
        firstRelease.countDown()
        assertTrue(first.await().last() is GenerateEvent.Done)

        val secondEntered = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        runner.blockNextGenerate(secondEntered, secondRelease, "second")
        val second = async(Dispatchers.Default) { service.streamChat(request()).toList() }
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        val secondToken = requireNotNull(service.activeGenerationStopToken())
        val stopsBeforeConditionalStop = runner.requestStopCalls.get()

        assertFalse(service.stopGenerationIfActive(firstToken))
        assertEquals(stopsBeforeConditionalStop, runner.requestStopCalls.get())
        assertTrue(service.stopGenerationIfActive(secondToken))
        assertEquals(stopsBeforeConditionalStop + 1, runner.requestStopCalls.get())
        assertTrue(second.await().last() is GenerateEvent.Error)
    }

    private suspend fun loadedService(
        runner: PhaseRunner,
        prefillProgressPollIntervalMs: Long = 5L
    ): McaInferenceService {
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            prefillProgressPollIntervalMs = prefillProgressPollIntervalMs
        )
        service.loadModel(
            modelPath = "/models/phase/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 4096, nThreads = 2)
        ).getOrThrow()
        return service
    }

    private fun request(): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "phase test")),
        params = GenerationParams(
            nCtx = 4096,
            nPredict = 8,
            nThreads = 2,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
    )

    private class PhaseRunner : LocalChatRunner {
        private var statsJson = loadedStatsJson("{}")
        private val chunks = ArrayDeque<String>()

        var beginReturnCode = 0
        var beginFailure: Throwable? = null
        @Volatile
        var prefillProgress: TokenProgress? = null
        var prefillStepsDuringBegin: List<TokenProgress> = emptyList()
        var terminalPrefillTokens: Int = 0
        var terminalPrefillMs: Long = 0L
        var sessionKnownLost: Boolean = false
        val timeline = Collections.synchronizedList(mutableListOf<String>())
        var generateCalls = 0
            private set
        var contextInvalidationCalls = 0
            private set
        var resetPrefillProgressCalls = 0
            private set
        val requestStopCalls = AtomicInteger(0)
        @Volatile
        private var blockedGenerateEntered: CountDownLatch? = null
        @Volatile
        private var blockedGenerateRelease: CountDownLatch? = null
        @Volatile
        private var blockedGenerateChunk: String? = null
        @Volatile
        private var stopRequested = false

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
            timeline += "begin"
            stopRequested = false
            beginFailure?.let { throw it }
            prefillStepsDuringBegin.forEach { progress ->
                prefillProgress = progress
                Thread.sleep(PREFILL_STEP_PAUSE_MS)
            }
            if (terminalPrefillTokens > 0 && terminalPrefillMs > 0L) {
                statsJson = JSONObject(statsJson)
                    .put("promptTokens", terminalPrefillTokens)
                    .put("prefillMs", terminalPrefillMs)
                    .put("prefillTps", terminalPrefillTokens * 1000.0 / terminalPrefillMs)
                    .toString()
            }
            timeline += "begin:return"
            return beginReturnCode
        }

        override fun prefillProgress(): TokenProgress? = prefillProgress

        override fun resetPrefillProgress() {
            resetPrefillProgressCalls += 1
            prefillProgress = null
        }

        override fun generateNextChunk(): String? {
            generateCalls += 1
            val entered = blockedGenerateEntered
            val release = blockedGenerateRelease
            if (entered != null && release != null) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "timed out waiting to release test generation" }
                blockedGenerateEntered = null
                blockedGenerateRelease = null
                val chunk = blockedGenerateChunk
                blockedGenerateChunk = null
                if (stopRequested) return null
                return chunk
            }
            return if (chunks.isEmpty()) null else chunks.removeFirst()
        }

        override fun invalidateConversationContext() {
            contextInvalidationCalls += 1
        }

        override fun requestStop() {
            requestStopCalls.incrementAndGet()
            stopRequested = true
            blockedGenerateRelease?.countDown()
        }
        override fun isSessionKnownLost(): Boolean = sessionKnownLost
        override fun getRuntimeStatsJson(): String = statsJson
        override fun shutdown() = Unit

        fun enqueue(vararg values: String) {
            chunks.clear()
            values.forEach(chunks::addLast)
        }

        fun blockNextGenerate(
            entered: CountDownLatch,
            release: CountDownLatch,
            chunk: String
        ) {
            blockedGenerateEntered = entered
            blockedGenerateRelease = release
            blockedGenerateChunk = chunk
        }

        private companion object {
            const val PREFILL_STEP_PAUSE_MS = 60L

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
                val nCtx = (value("n_ctx", 4096) as Number).toInt()
                val nThreads = (value("n_threads", 2) as Number).toInt()
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
                    .put("visionReady", true)
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
        private val root = File(System.getProperty("java.io.tmpdir"), "mca-phase-event-test-${System.nanoTime()}")
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
