package com.muyuchat.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C01 engine-side quiescence proof:
 *  * a cleared request token is never a proof;
 *  * only real runner idle evidence releases;
 *  * a model/native call that never returns times out precisely;
 *  * repeated/aligned stop calls are idempotent and cross-run proofs do not leak;
 *  * missing evidence and stale incarnations stay UNKNOWN.
 *
 * The fake runner below is a deterministic stand-in; real MNN/GGUF execution remains NOT_RUN.
 */
class EngineStopSettlementTest {

    @Test
    fun clearedStopTokenIsNotQuiescenceProof() = runBlocking {
        val runner = BlockingStopProofRunner()
        val service = loadedService(runner)
        val context = LocalChatExecutionContext(requestId = REQUEST_A)
        val collector = async(Dispatchers.Default) {
            service.streamChat(request(), context).toList()
        }
        assertTrue(runner.generateEntered.await(5, TimeUnit.SECONDS))
        val token = requireNotNull(service.activeGenerationStopToken())
        assertEquals(REQUEST_A, token.requestId)
        val whileRunning = service.stopSettlementSnapshot(token)
        assertTrue(
            "a live stream must never be reported quiescent: $whileRunning",
            whileRunning is EngineStopSettlement.StoppingUnknown
        )

        assertTrue(service.stopGenerationIfActive(token))
        assertTrue(withTimeoutOrNull(5_000L) { collector.await() } != null)
        assertNull("the request token must be cleared by the stream finally", service.activeGenerationStopToken())

        val beforeProof = service.stopSettlementSnapshot(token)
        assertTrue(
            "token disappearance must never be Confirmed: $beforeProof",
            beforeProof is EngineStopSettlement.StoppingUnknown
        )

        runner.reportedActivity = NativeGenerationActivity.IDLE
        val released = service.awaitStopSettlement(token, 1_000L)
        assertTrue("real idle evidence must release: $released", released is EngineStopSettlement.Released)
        assertEquals(token.epoch, (released as EngineStopSettlement.Released).identity.requestEpoch)
        assertEquals(REQUEST_A, released.identity.requestId)

        val repeat = service.awaitStopSettlement(token, 1_000L)
        assertTrue("repeated proof must stay idempotent: $repeat", repeat is EngineStopSettlement.Released)
    }

    @Test
    fun repeatedStopDispatchHitsTheExactRequestOnlyOnce() = runBlocking {
        val runner = BlockingStopProofRunner()
        val service = loadedService(runner)
        // loadModel() issues one bounded unconditional stop before the load; reset the counter so
        // the assertions below describe only the stream stop under test.
        runner.stopCalls.set(0)
        val collector = async(Dispatchers.Default) {
            service.streamChat(request(), LocalChatExecutionContext(requestId = REQUEST_A)).toList()
        }
        assertTrue(runner.generateEntered.await(5, TimeUnit.SECONDS))
        val token = requireNotNull(service.activeGenerationStopToken())

        assertTrue(service.stopGenerationIfActive(token))
        assertTrue(service.stopGenerationIfActive(token))
        assertEquals(1, runner.stopCalls.get())

        val forged = GenerationStopToken(epoch = token.epoch, requestId = REQUEST_B)
        assertFalse("a token for another request id is never a stop target", service.stopGenerationIfActive(forged))
        assertEquals(1, runner.stopCalls.get())

        assertTrue(withTimeoutOrNull(5_000L) { collector.await() } != null)
        runner.reportedActivity = NativeGenerationActivity.IDLE
        assertTrue(service.awaitStopSettlement(token, 1_000L) is EngineStopSettlement.Released)

        val staleEpoch = service.stopSettlementSnapshot(
            GenerationStopToken(epoch = token.epoch + 1L, requestId = REQUEST_A)
        )
        assertTrue("a stale request epoch must never be a proof: $staleEpoch", staleEpoch is EngineStopSettlement.Unknown)

        assertFalse("a cleared token cannot stop anything", service.stopGenerationIfActive(token))
        assertEquals(1, runner.stopCalls.get())
    }

    @Test
    fun neverReturningNativeCallTimesOutPreciselyWithoutRelease() = runBlocking {
        val runner = BlockingStopProofRunner(stopReleasesGenerate = false)
        val service = loadedService(runner)
        val collector = async(Dispatchers.Default) {
            service.streamChat(request(), LocalChatExecutionContext(requestId = REQUEST_A)).toList()
        }
        try {
            assertTrue(runner.generateEntered.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val settlement = service.awaitStopSettlement(REQUEST_A, 200L)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L

            assertTrue("a wedged native call must time out, got $settlement", settlement is EngineStopSettlement.TimedOut)
            settlement as EngineStopSettlement.TimedOut
            assertTrue("timeout must be bounded, waited=$elapsedMs", elapsedMs in 180L..5_000L)
            assertTrue(settlement.waitedMillis >= 180L)
            assertEquals(NativeGenerationActivity.ACTIVE, settlement.activity)
            assertEquals(REQUEST_A, settlement.identity.requestId)
        } finally {
            runner.releaseGenerate()
            service.stopGenerationIfActive()
            withTimeoutOrNull(5_000L) { collector.await() }
        }
    }

    @Test
    fun missingNativeEvidenceIsStoppingUnknownNotSuccess() = runBlocking {
        val runner = UnprovenActivityRunner()
        val service = loadedService(runner)
        val events = service.streamChat(
            request(),
            LocalChatExecutionContext(requestId = REQUEST_A)
        ).toList()
        assertTrue(events.isNotEmpty())

        val started = System.nanoTime()
        val settlement = service.awaitStopSettlement(REQUEST_A, 2_000L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue("missing evidence must not release: $settlement", settlement is EngineStopSettlement.StoppingUnknown)
        assertEquals(NativeGenerationActivity.UNKNOWN, (settlement as EngineStopSettlement.StoppingUnknown).activity)
        assertTrue("a failed evidence read must return UNKNOWN immediately, waited=$elapsedMs", elapsedMs < 1_000L)
    }

    @Test
    fun requestThatNeverDispatchedSettlesDirectly() = runBlocking {
        val runner = BlockingStopProofRunner()
        // No load: the stream fails before any native begin call.
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(LocalChatRuntime.MNN_CPU to runner),
            io = Dispatchers.IO,
            prefillProgressPollIntervalMs = 5L
        )
        val events = service.streamChat(
            request(),
            LocalChatExecutionContext(requestId = REQUEST_A)
        ).toList()
        assertTrue(events.lastOrNull() is GenerateEvent.Error)

        val settlement = service.stopSettlementSnapshot(REQUEST_A)
        assertTrue(
            "a request that returned before dispatch may release directly: $settlement",
            settlement is EngineStopSettlement.NotDispatched
        )
        assertEquals(0, runner.stopCalls.get())
    }

    @Test
    fun noCrossRequestProofOrStop() = runBlocking {
        val runner = BlockingStopProofRunner()
        val service = loadedService(runner)
        // loadModel() issues one bounded unconditional stop; ignore it for stream-stop counts.
        runner.stopCalls.set(0)
        val collectorA = async(Dispatchers.Default) {
            service.streamChat(request(), LocalChatExecutionContext(requestId = REQUEST_A)).toList()
        }
        assertTrue(runner.generateEntered.await(5, TimeUnit.SECONDS))
        val tokenA = requireNotNull(service.activeGenerationStopToken())

        assertTrue(
            service.stopSettlementSnapshot(REQUEST_B) is EngineStopSettlement.Unknown
        )
        assertTrue(
            service.awaitStopSettlement(REQUEST_B, 100L) is EngineStopSettlement.Unknown
        )

        assertTrue(service.stopGenerationIfActive(tokenA))
        assertTrue(withTimeoutOrNull(5_000L) { collectorA.await() } != null)
        assertEquals(1, runner.stopCalls.get())
        runner.reportedActivity = NativeGenerationActivity.IDLE
        assertTrue(service.awaitStopSettlement(tokenA, 1_000L) is EngineStopSettlement.Released)

        // A later request activates the single native target. The old request's record is stale,
        // so its proof must never be reused for the replacement execution.
        runner.blockWhenNoChunks = false
        runner.enqueue("second")
        val eventsB = service.streamChat(
            request(),
            LocalChatExecutionContext(requestId = REQUEST_B)
        ).toList()
        assertTrue(eventsB.any { it is GenerateEvent.Done || it is GenerateEvent.Chunk })
        val stale = service.stopSettlementSnapshot(tokenA)
        assertTrue("an old proof must not survive a replacement execution: $stale", stale is EngineStopSettlement.Unknown)
    }

    @Test
    fun nativeLifecycleTransitionInvalidatesOldProof() = runBlocking {
        val runner = BlockingStopProofRunner()
        val service = loadedService(runner)
        val collector = async(Dispatchers.Default) {
            service.streamChat(request(), LocalChatExecutionContext(requestId = REQUEST_A)).toList()
        }
        assertTrue(runner.generateEntered.await(5, TimeUnit.SECONDS))
        val token = requireNotNull(service.activeGenerationStopToken())
        assertTrue(service.stopGenerationIfActive(token))
        assertTrue(withTimeoutOrNull(5_000L) { collector.await() } != null)
        runner.reportedActivity = NativeGenerationActivity.IDLE
        assertTrue(service.awaitStopSettlement(token, 1_000L) is EngineStopSettlement.Released)

        // Same model path can be reused, but the native incarnation boundary still invalidates
        // every proof taken before it.
        service.loadModel(
            modelPath = "/models/stop-proof/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 4096, nThreads = 2)
        ).getOrThrow()

        val stale = service.stopSettlementSnapshot(token)
        assertTrue("a previous incarnation proof is UNKNOWN: $stale", stale is EngineStopSettlement.Unknown)
    }

    private suspend fun loadedService(runner: LocalChatRunner): McaInferenceService {
        val service = McaInferenceService(
            context = FakeContext(),
            runners = mapOf(runner.runtime to runner),
            io = Dispatchers.IO,
            prefillProgressPollIntervalMs = 5L
        )
        service.loadModel(
            modelPath = "/models/stop-proof/config.json",
            runtime = LocalChatRuntime.MNN_CPU,
            params = LoadParams(nCtx = 4096, nThreads = 2)
        ).getOrThrow()
        return service
    }

    private fun request(): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage(Role.USER, "stop proof fixture")),
        params = GenerationParams(
            nCtx = 4096,
            nPredict = 8,
            nThreads = 2,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
    )

    /**
     * Fake MNN-shaped runner. [reportedActivity] is controlled by the test; it must be set to IDLE
     * only after the test decides the "native" work is quiet.
     */
    private class BlockingStopProofRunner(
        private val stopReleasesGenerate: Boolean = true
    ) : LocalChatRunner, NativeQuiescenceProbe {
        override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null

        @Volatile var reportedActivity: NativeGenerationActivity = NativeGenerationActivity.ACTIVE
        @Volatile var blockWhenNoChunks: Boolean = true
        val stopCalls = AtomicInteger(0)
        val generateEntered = CountDownLatch(1)
        @Volatile private var generateRelease: CountDownLatch = CountDownLatch(1)
        private val chunks = ArrayDeque<String>()
        @Volatile private var statsJson: String = "{}"

        override fun nativeGenerationActivity(): NativeGenerationActivity = reportedActivity

        override fun initBackends(nativeLibDir: String) = Unit

        override fun loadModel(modelPath: String, paramsJson: String): Int {
            statsJson = stopProofStatsJson(runtime, paramsJson)
            return 0
        }

        override fun unloadModel() = Unit

        override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
            generateRelease = CountDownLatch(1)
            return 0
        }

        override fun generateNextChunk(): String? {
            val queued = synchronizeChunks { if (chunks.isEmpty()) null else chunks.removeFirst() }
            if (queued != null) return queued
            if (!blockWhenNoChunks) return null
            generateEntered.countDown()
            generateRelease.await(10, TimeUnit.SECONDS)
            return null
        }

        override fun requestStop() {
            stopCalls.incrementAndGet()
            if (stopReleasesGenerate) generateRelease.countDown()
        }

        override fun getRuntimeStatsJson(): String = statsJson
        override fun shutdown() = Unit

        fun enqueue(vararg values: String) {
            synchronizeChunks {
                chunks.clear()
                values.forEach(chunks::addLast)
            }
        }

        fun releaseGenerate() {
            generateRelease.countDown()
        }

        private inline fun <T> synchronizeChunks(block: () -> T): T = synchronized(chunks) { block() }
    }

    /** Native activity read failure: no probe evidence may ever be treated as idle. */
    private class UnprovenActivityRunner : LocalChatRunner, NativeQuiescenceProbe {
        override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
        override val isAvailable: Boolean = true
        override val loadError: Throwable? = null
        @Volatile private var statsJson: String = "{}"

        override fun nativeGenerationActivity(): NativeGenerationActivity =
            error("native activity probe is unavailable")

        override fun initBackends(nativeLibDir: String) = Unit
        override fun loadModel(modelPath: String, paramsJson: String): Int {
            statsJson = stopProofStatsJson(runtime, paramsJson)
            return 0
        }
        override fun unloadModel() = Unit
        override fun beginCompletion(messagesJson: String, paramsJson: String): Int = 0
        override fun generateNextChunk(): String? = null
        override fun requestStop() = Unit
        override fun getRuntimeStatsJson(): String = statsJson
        override fun shutdown() = Unit
    }

    private class FakeContext : ContextWrapper(null) {
        private val root = File(System.getProperty("java.io.tmpdir"), "mca-stop-proof-test-${System.nanoTime()}")
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

    private companion object {
        const val REQUEST_A = "engine-stop-proof-a"
        const val REQUEST_B = "engine-stop-proof-b"
    }
}

/** Minimal load-stat payload that satisfies the coordinated load-signature publication. */
private fun stopProofStatsJson(runtime: LocalChatRuntime, paramsJson: String): String {
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
        .put("backend", runtime.backendId)
        .put("loaded", true)
        .put("effectiveConfig", params)
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
