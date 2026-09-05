package com.muyuchat.mca.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only, one-shot llama.cpp CPU/GPU measurement harness. */
class LlamaGpuBenchActivity : Activity() {
    private val finished = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                runBench()
            } catch (error: Throwable) {
                Log.e("MCA-LLAMA-BENCH", "benchmark failed", error)
            } finally {
                if (finished.compareAndSet(false, true)) runOnUiThread { finish() }
            }
        }.start()
    }

    private fun runBench() {
        val intent = intent
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank { "llama-bench-${System.currentTimeMillis()}" }
        val gpu = intent.getBooleanExtra("gpu", false)
        val modelPath = requireNotNull(intent.getStringExtra("modelPath")) { "modelPath is required" }
        val nCtx = intent.getIntExtra("nCtx", 2048).coerceAtLeast(128)
        val nThreads = intent.getIntExtra("nThreads", 4).coerceAtLeast(1)
        val maxTokens = intent.getIntExtra("maxTokens", 32).coerceAtLeast(1)
        val prompt = intent.getStringExtra("prompt").orEmpty().ifBlank {
            "请只输出数字42。" + " 请严格遵守要求。".repeat(64)
        }
        val out = File(getExternalFilesDir("chat_smoke"), "runs/$runId.json").apply { parentFile?.mkdirs() }
        fun record(value: JSONObject) {
            value.put("runId", runId).put("gpuRequested", gpu)
            val existing = if (out.isFile) runCatching { JSONObject(out.readText()) }.getOrNull() else null
            val events = existing?.optJSONArray("events") ?: JSONArray()
            events.put(value)
            out.writeText(JSONObject().put("runId", runId).put("events", events).toString(2))
            Log.i("MCA-LLAMA-BENCH", value.toString())
        }
        fun emit(value: JSONObject) = record(value.put("elapsedMs", System.currentTimeMillis()))
        val engine = McaInferenceService(applicationContext)
        val identity = ModelRuntimeIdentity(
            modelId = File(modelPath).name.ifBlank { "local-model" },
            artifactFingerprint = "bench-${File(modelPath).length()}-${File(modelPath).lastModified()}",
            runtime = LocalChatRuntime.LLAMA_CPP,
            runtimeVersion = "llama.cpp-embedded",
            nativeLibrarySha256 = "bench",
            backendFingerprint = "llama.cpp",
            capabilities = if (gpu) setOf("gpu_offload") else emptySet()
        )
        val loadParams = LoadParams(
            nCtx = nCtx,
            nThreads = nThreads,
            advancedJson = JSONObject()
                .put("n_gpu_layers", if (gpu) -1 else 0)
                .put("n_batch", 512)
                .put("n_ubatch", 512)
                .put("cache_type_k", "f16")
                .put("cache_type_v", "f16")
                .toString()
        )
        val generationParams = GenerationParams(
            nCtx = nCtx,
            nPredict = maxTokens,
            nThreads = nThreads,
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            frequencyPenalty = 0.0f,
            seed = 1234,
            systemPrompt = "",
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true
        )
        try {
            val loaded = runBlocking {
                engine.loadModel(
                    modelPath = modelPath,
                    runtime = LocalChatRuntime.LLAMA_CPP,
                    params = loadParams,
                    runtimeIdentity = identity
                )
            }.getOrThrow()
            emit(JSONObject().put("status", "loaded").put("stats", JSONObject(engine.nativeStatsJson())))
            // Warm up OpenCL kernel compilation (and the CPU page cache) separately.
            measure(engine, generationParams, prompt, "warmup", emit = ::emit)
            repeat(intent.getIntExtra("repetitions", 3).coerceIn(1, 10)) { index ->
                measure(engine, generationParams, prompt + " #$index", "measure_$index", emit = ::emit)
            }
            emit(JSONObject().put("status", "completed").put("finalStats", JSONObject(engine.nativeStatsJson())))
        } finally {
            runCatching { runBlocking { engine.unloadModel() } }
        }
    }

    private fun measure(
        engine: McaInferenceService,
        params: GenerationParams,
        prompt: String,
        label: String,
        emit: (JSONObject) -> Unit
    ) {
        var text = ""
        var done: GenerateEvent.Done? = null
        var error: GenerateEvent.Error? = null
        runBlocking {
            engine.streamChat(
                ChatRequest(
                    messages = listOf(ChatMessage(Role.USER, prompt)),
                    params = params
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> text += event.text
                    is GenerateEvent.Done -> done = event
                    is GenerateEvent.Error -> error = event
                    else -> Unit
                }
            }
        }
        val stats = done?.stats ?: error?.stats
        emit(
            JSONObject()
                .put("status", label)
                .put("textChars", text.length)
                .put("error", error?.message ?: JSONObject.NULL)
                .put("stats", JSONObject(engine.nativeStatsJson()))
                .put("runtimeStats", stats?.let { statsJson(it) } ?: JSONObject())
        )
    }

    private fun statsJson(stats: com.muyuchat.core.engine.RuntimeStats): JSONObject = JSONObject()
        .put("backend", stats.backend)
        .put("promptTokens", stats.promptTokens)
        .put("completionTokens", stats.completionTokens)
        .put("prefillMs", stats.prefillMs)
        .put("prefillTps", stats.prefillTps)
        .put("decodeMs", stats.decodeMs)
        .put("decodeTps", stats.decodeTps)
        .put("ttftMs", stats.ttftMs)
        .put("gpuOffloadActive", stats.gpuOffloadActive)
        .put("gpuOffloadAllocationObserved", stats.gpuOffloadAllocationObserved)
        .put("gpuOffloadExecutionObserved", stats.gpuOffloadExecutionObserved)
        .put("gpuOffloadLayers", stats.gpuOffloadLayers)
}
