package com.muyuchat.mca.debug

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.McaLoopbackServer
import com.muyuchat.mca.IsolatedLocalChatRunners
import com.muyuchat.mca.LiteRtQualcommRuntimeStager
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRunnerDebug
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.QairtExecutionPurpose
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.defaultLocalChatRunner
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal fun localChatSmokeGenerationTimeoutMs(promptChars: Int, nCtx: Int, maxTokens: Int): Long {
    val estimatedWorkMs = promptChars.coerceAtLeast(0).toLong() * 10L +
        nCtx.coerceAtLeast(0).toLong() * 5L +
        maxTokens.coerceAtLeast(0).toLong() * 7_000L
    return estimatedWorkMs.coerceIn(90_000L, 900_000L)
}

internal class AtomicSmokeEventLog(
    private val logFile: File,
    private val runId: String,
    private val startedAt: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val events = JSONArray()
    private val destroyFailures = linkedSetOf<String>()
    private val runnerStages = linkedSetOf<String>()

    fun append(event: JSONObject): JSONObject = synchronized(lock) {
        val snapshot = JSONObject(event.toString())
            .put("runId", runId)
            .put("elapsedMs", (clock() - startedAt).coerceAtLeast(0L))
        val stage = snapshot.optString("stage")
        if (stage.isNotBlank()) {
            runnerStages += stage
        }
        if (stage.contains("destroy_failed", ignoreCase = true)) {
            destroyFailures += stage
        }
        events.put(snapshot)
        val document = JSONObject()
            .put("runId", runId)
            .put("status", snapshot.optString("status"))
            .put("events", events)
            .toString(2)
        atomicReplaceUtf8(logFile, document)
        snapshot
    }

    fun destroyFailureStages(): List<String> = synchronized(lock) {
        destroyFailures.toList()
    }

    fun hasRunnerStage(suffix: String): Boolean = synchronized(lock) {
        runnerStages.any { stage -> stage.endsWith(suffix, ignoreCase = true) }
    }
}

internal fun atomicReplaceUtf8(target: File, content: String) {
    val parent = target.parentFile ?: error("Smoke log has no parent directory: ${target.absolutePath}")
    require(parent.isDirectory || parent.mkdirs()) {
        "Unable to create smoke log directory: ${parent.absolutePath}"
    }
    val temporary = File(parent, ".${target.name}.tmp")
    try {
        FileOutputStream(temporary, false).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (error: Throwable) {
        temporary.delete()
        throw error
    }
}

internal data class LocalChatSmokeGlobalSnapshot(
    private val stageSink: ((String, JSONObject) -> Unit)?,
    private val engine: McaInferenceService?,
    private val streamChatProvider: ((ChatRequest) -> Flow<GenerateEvent>)?,
    private val streamChatWithContextProvider: ((ChatRequest, LocalChatExecutionContext) -> Flow<GenerateEvent>)?,
    private val stopGenerationProvider: (suspend () -> Unit)?,
    private val stopGenerationIfRequestActiveProvider: (suspend (String) -> Boolean)?,
    private val controlPlane: LocalApiControlPlane?,
    private val loadedModelJsonProvider: () -> String,
    private val paramsJsonProvider: () -> String,
    private val generationParamsProvider: () -> GenerationParams,
    private val modelsJsonProvider: () -> String,
    private val deviceProfileJsonProvider: () -> String,
    private val agentRecommendationJsonProvider: (String) -> String,
    private val benchmarkJsonProvider: suspend (String) -> String
) {
    fun restore() {
        LocalApiRuntime.streamChatProvider = streamChatProvider
        LocalApiRuntime.streamChatWithContextProvider = streamChatWithContextProvider
        LocalApiRuntime.stopGenerationProvider = stopGenerationProvider
        LocalApiRuntime.stopGenerationIfRequestActiveProvider = stopGenerationIfRequestActiveProvider
        LocalApiRuntime.controlPlane = controlPlane
        LocalApiRuntime.loadedModelJsonProvider = loadedModelJsonProvider
        LocalApiRuntime.paramsJsonProvider = paramsJsonProvider
        LocalApiRuntime.generationParamsProvider = generationParamsProvider
        LocalApiRuntime.modelsJsonProvider = modelsJsonProvider
        LocalApiRuntime.deviceProfileJsonProvider = deviceProfileJsonProvider
        LocalApiRuntime.agentRecommendationJsonProvider = agentRecommendationJsonProvider
        LocalApiRuntime.benchmarkJsonProvider = benchmarkJsonProvider
        LocalApiRuntime.engine = engine
        LocalChatRunnerDebug.stageSink = stageSink
    }

    fun forwardStage(stage: String, details: JSONObject) {
        runCatching { stageSink?.invoke(stage, details) }
    }

    companion object {
        fun capture(): LocalChatSmokeGlobalSnapshot = LocalChatSmokeGlobalSnapshot(
            stageSink = LocalChatRunnerDebug.stageSink,
            engine = LocalApiRuntime.engine,
            streamChatProvider = LocalApiRuntime.streamChatProvider,
            streamChatWithContextProvider = LocalApiRuntime.streamChatWithContextProvider,
            stopGenerationProvider = LocalApiRuntime.stopGenerationProvider,
            stopGenerationIfRequestActiveProvider = LocalApiRuntime.stopGenerationIfRequestActiveProvider,
            controlPlane = LocalApiRuntime.controlPlane,
            loadedModelJsonProvider = LocalApiRuntime.loadedModelJsonProvider,
            paramsJsonProvider = LocalApiRuntime.paramsJsonProvider,
            generationParamsProvider = LocalApiRuntime.generationParamsProvider,
            modelsJsonProvider = LocalApiRuntime.modelsJsonProvider,
            deviceProfileJsonProvider = LocalApiRuntime.deviceProfileJsonProvider,
            agentRecommendationJsonProvider = LocalApiRuntime.agentRecommendationJsonProvider,
            benchmarkJsonProvider = LocalApiRuntime.benchmarkJsonProvider
        )
    }
}

internal fun resolveLocalChatSmokePrompt(promptExtra: String?, hasImage: Boolean): String =
    promptExtra?.takeIf { it.isNotBlank() }
        ?: if (hasImage) {
            "Describe this image in one short Chinese sentence."
        } else {
            "Answer in one short Chinese sentence: local chat smoke test completed."
        }

internal const val LOCAL_CHAT_SMOKE_DEFAULT_SYSTEM_PROMPT =
    "You are MCA smoke test. Answer briefly in Chinese."

// This key is consumed only by the MNN native debug runner. It retains a short
// raw-output prefix and a capped rendered-prompt trace in smoke nativeStats,
// never in normal UI/API telemetry, so a failed smoke can be classified without
// a second run.
internal const val LOCAL_CHAT_SMOKE_MNN_TRACE_ADVANCED_JSON =
    "{\"mca_debug_trace\":true}"

internal const val LOCAL_CHAT_SMOKE_CANCELLATION_PROMPT =
    "Count upward from 1, writing one integer and one space at a time. Continue until externally stopped."

internal const val LOCAL_CHAT_SMOKE_CANCELLATION_MIN_TOKENS = 128

internal enum class LocalChatSmokeCancellationPhase {
    COLLECTING,
    STOP_CALL_IN_PROGRESS,
    STOP_ACCEPTED,
    TERMINATED_BEFORE_STOP,
    TERMINATED_AFTER_STOP
}

internal fun resolveLocalChatSmokeAdvancedJson(input: String?): String {
    val merged = LlamaAdvancedParams.merge(
        baseJson = input ?: "{}",
        patchJson = LOCAL_CHAT_SMOKE_MNN_TRACE_ADVANCED_JSON
    )
    require(merged.issues.isEmpty()) {
        "Invalid advancedJson: ${merged.errorMessages.joinToString("; ")}"
    }
    return merged.json
}

/**
 * Debug-only escape hatch for load-bound MNN options. Passing a JSON object
 * containing booleans through `adb shell am` is fragile on Android: the shell
 * can reinterpret a colon as an intent URI separator. These dedicated extras
 * keep the benchmark protocol unambiguous while still flowing through the
 * normal advanced-parameter validation/merge path.
 */
internal fun resolveLocalChatSmokeMnnOverrides(input: String?, intent: Intent): String {
    val patch = JSONObject()
    intent.getStringExtra("mnnBackendType")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { patch.put("backend_type", it) }
    if (intent.hasExtra("mnnMmap")) {
        patch.put("mmap", intent.getBooleanExtra("mnnMmap", true))
    }
    if (intent.hasExtra("mnnKvcacheMmap")) {
        patch.put("kvcache_mmap", intent.getBooleanExtra("mnnKvcacheMmap", true))
    }
    if (patch.length() == 0) return input ?: "{}"
    val merged = LlamaAdvancedParams.merge(input ?: "{}", patch.toString())
    require(merged.issues.isEmpty()) {
        "Invalid MNN override parameters: ${merged.errorMessages.joinToString("; ")}"
    }
    return merged.json
}

/**
 * Sampling is explicit in device evidence. Deterministic greedy remains the
 * default for regression reproducibility, while product-profile smoke can pass
 * the model's recommended mixed-sampler values through adb intent extras.
 */
internal data class LocalChatSmokeSampling(
    val temperature: Float = 0.0f,
    val topK: Int = 1,
    val topP: Float = 1.0f,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.08f,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.2f,
    val seed: Int? = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("temperature", temperature)
        .put("topK", topK)
        .put("topP", topP)
        .put("minP", minP)
        .put("repeatPenalty", repeatPenalty)
        .put("presencePenalty", presencePenalty)
        .put("frequencyPenalty", frequencyPenalty)
        .put("seed", seed)
}

internal fun localChatSmokeTextSha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun readLocalChatSmokePromptFile(
    promptPath: String?,
    allowedRoot: File,
    maxBytes: Long = 512L * 1024L
): String? {
    val path = promptPath?.trim().orEmpty()
    if (path.isEmpty()) return null
    require('\u0000' !in path) { "promptPath contains a NUL character." }
    val root = allowedRoot.canonicalFile
    val file = File(path).canonicalFile
    require(file.toPath().startsWith(root.toPath()) && file != root) {
        "promptPath must stay inside the app external files directory."
    }
    require(file.isFile) { "promptPath does not reference a readable file." }
    require(file.length() in 1..maxBytes) { "promptPath must contain 1..$maxBytes bytes." }
    return file.readText(Charsets.UTF_8)
}

internal fun extractVisibleChatCompletionText(response: String): String {
    val visible = StringBuilder()
    response.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd('\r')
        if (!line.startsWith("data:")) return@forEach
        val payload = line.removePrefix("data:").trim()
        if (payload.isBlank() || payload == "[DONE]") return@forEach
        val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEach
        val choices = chunk.optJSONArray("choices") ?: return@forEach
        val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@forEach
        val content = delta.opt("content")
        if (content is String) visible.append(content)
    }
    return visible.toString()
}

internal fun requiresLocalChatSmokeVisionReady(
    runtime: LocalChatRuntime,
    imagePath: String?,
    visionProjectorPath: String?
): Boolean = !imagePath.isNullOrBlank() && (
    runtime == LocalChatRuntime.MNN_CPU ||
        runtime == LocalChatRuntime.GENIEX_QAIRT ||
        runtime == LocalChatRuntime.GENIEX_LLAMA_CPP ||
        runtime == LocalChatRuntime.LITERT_LM ||
        !visionProjectorPath.isNullOrBlank()
    )

/** A QAIRT dry-run may only certify a handle that reported an NPU/HTP backend. */
internal fun hasQairtDryRunNpuEvidence(nativeStats: JSONObject): Boolean {
    if (!nativeStats.optString("backend").equals("geniex_qairt", ignoreCase = true)) return false
    val devices = nativeStats.opt("backendDevices")?.toString().orEmpty().lowercase()
    return "qairt" in devices && ("npu" in devices || "htp" in devices)
}

internal fun finalizeLocalChatSmokeEvent(
    terminalEvent: JSONObject?,
    shutdownError: String?,
    destroyFailureStages: List<String>
): JSONObject {
    val finalEvent = terminalEvent?.let { JSONObject(it.toString()) }
        ?: JSONObject()
            .put("status", "failed")
            .put("error", "Smoke run ended without a terminal result.")
    val errors = mutableListOf<String>()
    finalEvent.optString("error").takeIf { it.isNotBlank() }?.let(errors::add)
    shutdownError?.takeIf { it.isNotBlank() }?.let { errors += "Shutdown failed:\n$it" }
    if (destroyFailureStages.isNotEmpty()) {
        errors += "Native teardown reported destroy_failed: ${destroyFailureStages.joinToString()}"
        finalEvent
            .put("destroyFailed", true)
            .put("destroyFailureStages", JSONArray(destroyFailureStages))
    }
    if (!shutdownError.isNullOrBlank()) {
        finalEvent.put("shutdownError", shutdownError)
    }
    if (!shutdownError.isNullOrBlank() || destroyFailureStages.isNotEmpty()) {
        finalEvent
            .put("status", "failed")
            .put("error", errors.distinct().joinToString("\n"))
    }
    return finalEvent
}

internal fun localChatSmokeGenerationParams(
    nCtx: Int,
    nThreads: Int,
    maxTokens: Int,
    systemPrompt: String = LOCAL_CHAT_SMOKE_DEFAULT_SYSTEM_PROMPT,
    sampling: LocalChatSmokeSampling = LocalChatSmokeSampling(),
    advancedJson: String = LOCAL_CHAT_SMOKE_MNN_TRACE_ADVANCED_JSON
): GenerationParams = GenerationParams(
    nCtx = nCtx,
    nPredict = maxTokens,
    nThreads = nThreads,
    temperature = sampling.temperature,
    topK = sampling.topK,
    topP = sampling.topP,
    minP = sampling.minP,
    repeatPenalty = sampling.repeatPenalty,
    presencePenalty = sampling.presencePenalty,
    frequencyPenalty = sampling.frequencyPenalty,
    seed = sampling.seed,
    reasoningMode = ReasoningMode.OFF,
    hideReasoning = true,
    systemPrompt = systemPrompt,
    advancedJson = advancedJson
)

internal fun localChatSmokeLoadParams(
    nCtx: Int,
    nThreads: Int,
    visionProjectorPath: String?,
    geniexComputeUnit: String?,
    advancedJson: String
): LoadParams = LoadParams(
    nCtx = nCtx,
    nThreads = nThreads,
    visionProjectorPath = visionProjectorPath,
    geniexComputeUnit = geniexComputeUnit,
    advancedJson = advancedJson
)

internal fun localChatSmokeGenerationResult(
    visibleText: String,
    imagePath: String?,
    doneSeen: Boolean,
    flowCompleted: Boolean,
    error: String?,
    nativeStats: JSONObject
): JSONObject = JSONObject()
    .put("visibleChars", visibleText.length)
    .put("text", visibleText)
    .put("textPreview", visibleText.take(120))
    .put("textSha256", localChatSmokeTextSha256(visibleText))
    .put("imagePath", imagePath ?: JSONObject.NULL)
    .put("doneSeen", doneSeen)
    .put("flowCompleted", flowCompleted)
    .put("error", error ?: JSONObject.NULL)
    .put("nativeStats", JSONObject(nativeStats.toString()))

internal fun localChatSmokeCancellationRequestResult(
    visibleText: String,
    imagePath: String?,
    doneSeen: Boolean,
    flowCompleted: Boolean,
    error: String?,
    stopCallAttempted: Boolean,
    stopCallSucceeded: Boolean,
    stopCallError: String?,
    terminalBeforeStop: String?,
    postStopTerminal: String?,
    timedOut: Boolean,
    requestedMaxTokens: Int,
    preStopStats: JSONObject?,
    requestInFlightAtStop: Boolean,
    cancellationPhase: String = "unknown",
    preStopStatsCapturedAtMonotonicNs: Long? = null,
    stopAttemptedAtMonotonicNs: Long? = null,
    stopAcceptedAtMonotonicNs: Long? = null,
    terminalAtMonotonicNs: Long? = null,
    nativeStats: JSONObject
): JSONObject {
    val evidence = localChatSmokeCancellationEvidence(
        visibleChunkSeen = visibleText.isNotBlank(),
        stopCallAttempted = stopCallAttempted,
        stopCallSucceeded = stopCallSucceeded,
        stopCallError = stopCallError,
        terminalBeforeStop = terminalBeforeStop,
        postStopTerminal = postStopTerminal,
        flowCompleted = flowCompleted,
        flowError = error,
        timedOut = timedOut,
        requestedMaxTokens = requestedMaxTokens,
        preStopStats = preStopStats,
        requestInFlightAtStop = requestInFlightAtStop,
        cancellationPhase = cancellationPhase,
        preStopStatsCapturedAtMonotonicNs = preStopStatsCapturedAtMonotonicNs,
        stopAttemptedAtMonotonicNs = stopAttemptedAtMonotonicNs,
        stopAcceptedAtMonotonicNs = stopAcceptedAtMonotonicNs,
        terminalAtMonotonicNs = terminalAtMonotonicNs,
        nativeStats = nativeStats
    )
    return localChatSmokeGenerationResult(
        visibleText = visibleText,
        imagePath = imagePath,
        doneSeen = doneSeen,
        flowCompleted = flowCompleted,
        error = error,
        nativeStats = nativeStats
    )
        .put("visibleChunkSeen", visibleText.isNotBlank())
        .put("stopRequested", stopCallAttempted)
        .put("stopCallAttempted", stopCallAttempted)
        .put("stopCallSucceeded", stopCallSucceeded)
        .put("stopCallError", stopCallError ?: JSONObject.NULL)
        .put("terminalBeforeStop", terminalBeforeStop ?: JSONObject.NULL)
        .put("postStopTerminal", postStopTerminal ?: JSONObject.NULL)
        .put("timedOut", timedOut)
        .put("preStopStats", preStopStats?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
        .put("requestInFlightAtStop", requestInFlightAtStop)
        .put("cancellationPhase", cancellationPhase)
        .put(
            "preStopStatsCapturedAtMonotonicNs",
            preStopStatsCapturedAtMonotonicNs ?: JSONObject.NULL
        )
        .put("stopAttemptedAtMonotonicNs", stopAttemptedAtMonotonicNs ?: JSONObject.NULL)
        .put("stopAcceptedAtMonotonicNs", stopAcceptedAtMonotonicNs ?: JSONObject.NULL)
        .put("terminalAtMonotonicNs", terminalAtMonotonicNs ?: JSONObject.NULL)
        .put("flowTerminated", evidence.getBoolean("flowTerminatedAfterStop"))
        .put("cancellationVerified", evidence.getBoolean("success"))
        .put("cancellationEvidence", evidence)
}

internal fun localChatSmokeCancellationEvidence(
    visibleChunkSeen: Boolean,
    stopCallAttempted: Boolean,
    stopCallSucceeded: Boolean,
    stopCallError: String?,
    terminalBeforeStop: String?,
    postStopTerminal: String?,
    flowCompleted: Boolean,
    flowError: String?,
    timedOut: Boolean,
    requestedMaxTokens: Int,
    preStopStats: JSONObject?,
    requestInFlightAtStop: Boolean,
    cancellationPhase: String = "unknown",
    preStopStatsCapturedAtMonotonicNs: Long? = null,
    stopAttemptedAtMonotonicNs: Long? = null,
    stopAcceptedAtMonotonicNs: Long? = null,
    terminalAtMonotonicNs: Long? = null,
    nativeStats: JSONObject
): JSONObject {
    val preStopActiveEvidence = localChatSmokePreStopActiveEvidence(
        nativeStats = preStopStats,
        requestInFlight = requestInFlightAtStop,
        requestedMaxTokens = requestedMaxTokens
    )
    val backend = nativeStats.optString("backend").trim().lowercase()
    val generationStopReason = nativeStats.optString("generationStopReason").trim()
    val profileStopReason = nativeStats.optJSONObject("nativeProfile")
        ?.optString("stopReason")
        ?.trim()
        .orEmpty()
    val nativeReasonSource = when {
        generationStopReason.isNotBlank() -> "generationStopReason"
        profileStopReason.isNotBlank() -> "nativeProfile.stopReason"
        else -> null
    }
    val nativeStopReason = when (nativeReasonSource) {
        "generationStopReason" -> generationStopReason
        "nativeProfile.stopReason" -> profileStopReason
        else -> null
    }
    val normalizedReason = nativeStopReason.orEmpty().lowercase()
    val nativeCancellationConfirmed = normalizedReason in setOf(
        "stop_requested",
        "mnn_user_cancel",
        "user_cancel",
        "user_cancelled",
        "user_canceled",
        "cancelled",
        "canceled",
        "abort_requested",
        "aborted_by_user",
        "stopped_by_user"
    ) || normalizedReason.contains("user_cancel")
    val reliableNativeReason = normalizedReason.isNotBlank() &&
        normalizedReason !in setOf("idle", "running", "unknown", "none")
    val nativeReasonRequired = backend == "mnn_cpu"
    val nativeEvidenceAccepted = when {
        nativeCancellationConfirmed -> true
        nativeReasonRequired -> false
        reliableNativeReason -> false
        else -> true
    }

    val effectiveMaxTokens = nativeStats.optInt("maxNewTokens", requestedMaxTokens)
        .takeIf { it > 0 }
        ?: requestedMaxTokens.coerceAtLeast(1)
    val completionTokens = nativeStats.optInt("completionTokens", -1)
    val maxTokenLimitReached = completionTokens >= effectiveMaxTokens && completionTokens >= 0
    val postStopError = postStopTerminal == "error" || postStopTerminal == "exception"
    val errorLooksCancelled = flowError.orEmpty().lowercase().let { message ->
        "cancel" in message || "stop" in message || "interrupt" in message
    }
    val postStopTerminationAccepted = when {
        timedOut -> false
        postStopError -> nativeCancellationConfirmed || errorLooksCancelled || !reliableNativeReason
        else -> flowCompleted || postStopTerminal == "done"
    }
    val explicitOrderVerified = stopCallAttempted &&
        stopCallSucceeded &&
        stopCallError.isNullOrBlank() &&
        terminalBeforeStop.isNullOrBlank() &&
        postStopTerminationAccepted
    val success = explicitOrderVerified &&
        nativeEvidenceAccepted &&
        !maxTokenLimitReached

    return JSONObject()
        .put("visibleChunkSeen", visibleChunkSeen)
        .put("stopCallAttempted", stopCallAttempted)
        .put("stopCallSucceeded", stopCallSucceeded)
        .put("stopCallError", stopCallError ?: JSONObject.NULL)
        .put("terminalBeforeStop", terminalBeforeStop ?: JSONObject.NULL)
        .put("postStopTerminal", postStopTerminal ?: JSONObject.NULL)
        .put("flowTerminatedAfterStop", postStopTerminationAccepted)
        .put("timedOut", timedOut)
        .put("preStopActiveEvidence", preStopActiveEvidence)
        .put("atomicStopAccepted", stopCallSucceeded)
        .put("cancellationPhase", cancellationPhase)
        .put(
            "preStopStatsCapturedAtMonotonicNs",
            preStopStatsCapturedAtMonotonicNs ?: JSONObject.NULL
        )
        .put("stopAttemptedAtMonotonicNs", stopAttemptedAtMonotonicNs ?: JSONObject.NULL)
        .put("stopAcceptedAtMonotonicNs", stopAcceptedAtMonotonicNs ?: JSONObject.NULL)
        .put("terminalAtMonotonicNs", terminalAtMonotonicNs ?: JSONObject.NULL)
        .put("backend", backend)
        .put("nativeStopReasonSource", nativeReasonSource ?: JSONObject.NULL)
        .put("nativeStopReason", nativeStopReason ?: JSONObject.NULL)
        .put("nativeCancellationConfirmed", nativeCancellationConfirmed)
        .put("nativeReasonRequired", nativeReasonRequired)
        .put("nativeEvidenceAccepted", nativeEvidenceAccepted)
        .put("completionTokens", completionTokens)
        .put("effectiveMaxTokens", effectiveMaxTokens)
        .put("maxTokenLimitReached", maxTokenLimitReached)
        .put("explicitOrderVerified", explicitOrderVerified)
        .put("success", success)
}

internal fun localChatSmokePreStopActiveEvidence(
    nativeStats: JSONObject?,
    requestInFlight: Boolean,
    requestedMaxTokens: Int
): JSONObject {
    val stats = nativeStats ?: JSONObject()
    val backend = stats.optString("backend").trim().lowercase()
    val hasGenerationActive = stats.has("generationActive") && !stats.isNull("generationActive")
    val generationActive = stats.optBoolean("generationActive", false)
    val nativeActiveRequired = backend == "mnn_cpu" || hasGenerationActive
    val configuredMaxTokens = stats.optInt("maxNewTokens", -1)
    val completionTokens = stats.optInt("completionTokens", -1)
    val requestConfigObserved = configuredMaxTokens == requestedMaxTokens &&
        completionTokens < requestedMaxTokens
    val activeAtStop = if (nativeActiveRequired) {
        generationActive
    } else {
        requestInFlight && requestConfigObserved
    }
    val source = when {
        nativeActiveRequired -> "nativeStats.generationActive"
        activeAtStop -> "nativeStats.request_config_in_flight"
        else -> "none"
    }
    return JSONObject()
        .put("backend", backend)
        .put("requestInFlight", requestInFlight)
        .put("hasGenerationActive", hasGenerationActive)
        .put("generationActive", generationActive)
        .put("nativeActiveRequired", nativeActiveRequired)
        .put("configuredMaxTokens", configuredMaxTokens)
        .put("requestedMaxTokens", requestedMaxTokens)
        .put("completionTokens", completionTokens)
        .put("requestConfigObserved", requestConfigObserved)
        .put("source", source)
        .put("activeAtStop", activeAtStop)
}

internal fun localChatSmokeCancelThenSecondResult(
    firstRequest: JSONObject,
    secondRequest: JSONObject
): JSONObject {
    val visibleChunkSeen = firstRequest.optBoolean("visibleChunkSeen", false)
    val stopRequested = firstRequest.optBoolean("stopCallAttempted", false)
    val stopCallSucceeded = firstRequest.optBoolean("stopCallSucceeded", false)
    val terminated = firstRequest.optBoolean("flowTerminated", false)
    val cancellationSuccess = firstRequest.optBoolean("cancellationVerified", false)
    val recoveryHasError = secondRequest.has("error") &&
        !secondRequest.isNull("error") &&
        secondRequest.optString("error").isNotBlank()
    val recoverySuccess = secondRequest.optString("text").isNotBlank() &&
        secondRequest.optBoolean("doneSeen", false) &&
        !recoveryHasError
    return JSONObject()
        .put("firstRequest", JSONObject(firstRequest.toString()))
        .put(
            "cancellation",
            JSONObject()
                .put("visibleChunkSeen", visibleChunkSeen)
                .put("stopRequested", stopRequested)
                .put("stopCallSucceeded", stopCallSucceeded)
                .put("terminated", terminated)
                .put("success", cancellationSuccess)
        )
        .put("secondRequest", JSONObject(secondRequest.toString()))
        .put("cancellationSuccess", cancellationSuccess)
        .put("recoverySuccess", recoverySuccess)
}

internal object LocalChatSmokeProcessGate {
    private val active = AtomicBoolean(false)

    fun tryAcquire(): Boolean = active.compareAndSet(false, true)

    fun release() {
        active.set(false)
    }
}

open class LocalChatSmokeActivity : Activity() {
    private val tag = "MCA-CHAT-SMOKE"
    private var smokeWakeLock: PowerManager.WakeLock? = null

    @Synchronized
    private fun acquireSmokePowerGuard() {
        // Long OpenCL/MNN runs are commonly frozen by vendor background policy
        // when the display sleeps. Keep this protection debug-only: production
        // chat lifecycle and battery policy remain unchanged.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        }
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:LocalChatSmoke"
        )
        smokeWakeLock = runCatching {
            wakeLock.apply {
                setReferenceCounted(false)
                // A bounded fallback prevents a leaked debug activity from holding
                // the CPU forever if the process is terminated before finally runs.
                acquire(4L * 60L * 60L * 1000L)
            }
        }.onFailure { error ->
            runCatching {
                if (wakeLock.isHeld) wakeLock.release()
            }
            // WAKE_LOCK is a normal permission, but a vendor may still reject
            // acquisition. Keep the screen-on flag and let the smoke proceed.
            Log.w(tag, "debug smoke partial wake lock unavailable", error)
        }.getOrNull()
        Log.i(tag, "debug smoke power guard acquired=${smokeWakeLock != null}")
    }

    @Synchronized
    private fun releaseSmokePowerGuard() {
        smokeWakeLock?.let { wakeLock ->
            runCatching {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
        smokeWakeLock = null
        val clearScreenFlag: () -> Unit = {
            runCatching {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            Unit
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            clearScreenFlag()
        } else {
            runCatching { runOnUiThread { clearScreenFlag() } }
        }
        Log.i(tag, "debug smoke power guard released")
    }

    open override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!LocalChatSmokeProcessGate.tryAcquire()) {
            finish()
            return
        }
        runCatching { acquireSmokePowerGuard() }
            .onFailure { error -> Log.w(tag, "debug smoke power guard setup failed", error) }
        Thread {
            try {
                runSmoke()
            } finally {
                releaseSmokePowerGuard()
                LocalChatSmokeProcessGate.release()
                runOnUiThread {
                    if (!isFinishing) finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        // The worker normally releases the guard in its finally block. Keep an
        // idempotent fallback for an externally destroyed debug activity.
        releaseSmokePowerGuard()
        super.onDestroy()
    }

    private fun runSmoke() {
        val startedAt = System.currentTimeMillis()
        val outDir = File(getExternalFilesDir("chat_smoke"), "runs").apply { mkdirs() }
        val runId = intent.getStringExtra("runId").orEmpty().ifBlank { "run-$startedAt" }
        val eventLog = AtomicSmokeEventLog(File(outDir, "$runId.json"), runId, startedAt)
        fun write(event: JSONObject) {
            val snapshot = eventLog.append(event)
            Log.i(tag, snapshot.toString())
        }
        fun writeLoad(status: String, loadMs: Long, engine: McaInferenceService) {
            write(
                JSONObject()
                    .put("status", status)
                    .put("loadMs", loadMs)
                    .put("nativeStats", JSONObject(engine.nativeStatsJson()))
            )
        }
        val globalSnapshot = LocalChatSmokeGlobalSnapshot.capture()
        LocalChatRunnerDebug.stageSink = { stage, details ->
            val logError = runCatching {
                write(
                    JSONObject()
                        .put("status", "runner_stage")
                        .put("stage", stage)
                        .put("details", details)
                )
            }.exceptionOrNull()
            if (logError != null) {
                Log.e(tag, "Unable to persist runner stage $stage", logError)
            }
            globalSnapshot.forwardStage(stage, details)
        }

        var engine: McaInferenceService? = null
        var isolatedRunners: IsolatedLocalChatRunners? = null
        var terminalEvent: JSONObject? = null
        var qairtDryRunBundleSha256: String? = null
        var qairtDryRunReadyToRecord = false
        val advancedJsonInput = intent.getStringExtra("advancedJson") ?: "{}"
        var advancedJsonMerged: String? = null
        var cancelThenSecondResult: JSONObject? = null
        var mnnCacheAbResult: JSONObject? = null
        try {
            val advancedJsonWithMnnOverrides = resolveLocalChatSmokeMnnOverrides(
                advancedJsonInput,
                intent
            )
            val resolvedAdvancedJson = resolveLocalChatSmokeAdvancedJson(advancedJsonWithMnnOverrides)
            advancedJsonMerged = resolvedAdvancedJson
            val target = runBlocking { resolveTarget(::write) }
            val nCtx = intent.getIntExtra("nCtx", 32768)
            val nThreads = intent.getIntExtra(
                "nThreads",
                (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)
            )
            val maxTokens = intent.getIntExtra("maxTokens", 32).coerceAtLeast(1)
            val continuousTurns = intent.getIntExtra("continuousTurns", 1).coerceIn(1, 64)
            val sampling = LocalChatSmokeSampling(
                temperature = intent.getFloatExtra("temperature", 0.0f).coerceIn(0.0f, 2.0f),
                topK = intent.getIntExtra("topK", 1).coerceIn(0, 256),
                topP = intent.getFloatExtra("topP", 1.0f).coerceIn(0.0f, 1.0f),
                minP = intent.getFloatExtra("minP", 0.0f),
                repeatPenalty = intent.getFloatExtra("repeatPenalty", 1.08f),
                presencePenalty = intent.getFloatExtra("presencePenalty", 0.0f),
                frequencyPenalty = intent.getFloatExtra("frequencyPenalty", 0.2f),
                seed = if (intent.hasExtra("seed")) intent.getIntExtra("seed", 0) else 0
            )
            val imagePath = intent.getStringExtra("imagePath").orEmpty().ifBlank { null }
            val secondImagePath = intent.getStringExtra("secondImagePath").orEmpty().ifBlank { null }
            val textPreludePrompt = intent.getStringExtra("textPreludePrompt")
                .orEmpty()
                .ifBlank { "Reply with only 42. What is 6 multiplied by 7?" }
            val promptFileText = readLocalChatSmokePromptFile(
                promptPath = intent.getStringExtra("promptPath"),
                allowedRoot = requireNotNull(getExternalFilesDir(null))
            )
            val prompt = resolveLocalChatSmokePrompt(
                promptFileText ?: intent.getStringExtra("prompt"),
                !imagePath.isNullOrBlank()
            )
            val generationTimeoutMs = localChatSmokeGenerationTimeoutMs(
                promptChars = prompt.length,
                nCtx = nCtx,
                maxTokens = maxTokens
            )
            val systemPrompt = intent.getStringExtra("systemPrompt")
                .orEmpty()
                .ifBlank { LOCAL_CHAT_SMOKE_DEFAULT_SYSTEM_PROMPT }
            val smokeMode = intent.getStringExtra("smokeMode").orEmpty().ifBlank { "full" }.lowercase()
            val visionValidated = intent.getBooleanExtra("visionValidated", false)
            val computeUnit = intent.getStringExtra("computeUnit").orEmpty().ifBlank { null }
            val isQairtDryRun = smokeMode == "qairt_dry_run"
            if (isQairtDryRun) {
                require(target.runtime == LocalChatRuntime.GENIEX_QAIRT) {
                    "qairt_dry_run requires a QAIRT target."
                }
                qairtDryRunBundleSha256 = target.qairtBundleSha256?.takeIf { it.isNotBlank() }
                    ?: error("qairt_dry_run requires a registered QAIRT bundle SHA-256.")
            }
            val liteRtQualcommStage = if (
                target.runtime == LocalChatRuntime.LITERT_LM &&
                    runCatching {
                        JSONObject(resolvedAdvancedJson).optString("backend")
                            .trim()
                            .equals("npu", ignoreCase = true)
                    }.getOrDefault(false)
            ) {
                LiteRtQualcommRuntimeStager.stage(applicationContext).also { stage ->
                    write(
                        JSONObject()
                            .put("status", "litert_lm_runtime_stage")
                            .put("ok", stage != null)
                            .put("variant", stage?.variant ?: JSONObject.NULL)
                            .put("fingerprint", stage?.fingerprint ?: JSONObject.NULL)
                            .put("reused", stage?.reused ?: JSONObject.NULL)
                            .put("directory", stage?.directory?.absolutePath ?: JSONObject.NULL)
                    )
                }
            } else {
                null
            }
            val productionBoundaryRunners = when (target.runtime) {
                LocalChatRuntime.MNN_CPU,
                LocalChatRuntime.LITERT_LM,
                LocalChatRuntime.GENIEX_LLAMA_CPP,
                LocalChatRuntime.GENIEX_QAIRT -> IsolatedLocalChatRunners(applicationContext)
                else -> null
            }
            isolatedRunners = productionBoundaryRunners
            val activeEngine = McaInferenceService(
                context = applicationContext,
                // MNN, LiteRT-LM, and both GenieX transports use the same
                // isolated worker as the product path. GenieX SDK plugin
                // registration is process-global and must never happen in the
                // debug/UI process before the selected runtime is loaded.
                runners = productionBoundaryRunners?.runners ?: mapOf(
                    target.runtime to defaultLocalChatRunner(target.runtime)
                ),
                nativeLibraryDirOverride = liteRtQualcommStage?.directory?.absolutePath
            )
            engine = activeEngine
            // The app process can retain a MainViewModel provider from a prior UI
            // session. Bind both loopback callbacks to this smoke's engine so the
            // direct and API legs exercise the same loaded native model. The
            // captured global values are restored in finally below.
            LocalApiRuntime.engine = activeEngine
            LocalApiRuntime.streamChatWithContextProvider = { request, context -> activeEngine.streamChat(request, context) }
            LocalApiRuntime.streamChatProvider = { request -> activeEngine.streamChat(request) }
            LocalApiRuntime.stopGenerationProvider = { activeEngine.stopGeneration() }
            LocalApiRuntime.stopGenerationIfRequestActiveProvider = null
            // This debug listener owns a separate engine from MainViewModel.
            // Production coordinator callbacks are restored with the snapshot.
            LocalApiRuntime.controlPlane = null
            LocalApiRuntime.loadedModelJsonProvider = {
                val nativeStats = runCatching { JSONObject(activeEngine.nativeStatsJson()) }
                    .getOrElse { JSONObject() }
                JSONObject()
                    .put("id", target.id)
                    .put("displayName", target.displayName)
                    .put("runtime", target.runtime.backendId)
                    .put("computeUnit", computeUnit)
                    .put("vision_ready", nativeStats.optBoolean("visionReady", false))
                    .put("vision_validated", visionValidated)
                    .toString()
            }
            LocalApiRuntime.generationParamsProvider = {
                localChatSmokeGenerationParams(
                    nCtx = nCtx,
                    nThreads = nThreads,
                    maxTokens = maxTokens,
                    systemPrompt = systemPrompt,
                    sampling = sampling,
                    advancedJson = resolvedAdvancedJson
                )
            }
            val loadParams = localChatSmokeLoadParams(
                nCtx = nCtx,
                nThreads = nThreads,
                visionProjectorPath = target.visionProjectorPath,
                geniexComputeUnit = computeUnit,
                advancedJson = resolvedAdvancedJson
            )

            write(
                JSONObject()
                    .put("status", "starting")
                    .put("target", target.toJson())
                    .put("nCtx", nCtx)
                    .put("nThreads", nThreads)
                    .put("maxTokens", maxTokens)
                    .put("generationTimeoutMs", generationTimeoutMs)
                    .put("continuousTurns", continuousTurns)
                    .put("sampling", sampling.toJson())
                    .put("imagePath", imagePath)
                    .put("secondImagePath", secondImagePath)
                    .put("prompt", prompt.takeIf { it.length <= 4096 } ?: JSONObject.NULL)
                    .put("promptChars", prompt.length)
                    .put("promptSha256", localChatSmokeTextSha256(prompt))
                    .put("promptPreview", prompt.take(256))
                    .put("systemPrompt", systemPrompt)
                    .put("smokeMode", smokeMode)
                    .put("advancedJsonInput", advancedJsonInput)
                    .put("advancedJsonMerged", resolvedAdvancedJson)
                    .put("visionValidated", visionValidated)
                    .put("computeUnit", computeUnit)
                    .put("qairtBundleSha256", target.qairtBundleSha256)
                    .put("qairtIsolatedDryRun", isQairtDryRun)
                    .put("runtimeAvailable", activeEngine.isRuntimeAvailable(target.runtime))
            )

            runBlocking {
                when (smokeMode) {
                    "qairt_dry_run" -> {
                        write(
                            JSONObject()
                                .put("status", "qairt_dry_run_start")
                                .put("bundleSha256", qairtDryRunBundleSha256)
                                .put("purpose", "isolated_create_generate_destroy")
                        )
                        writeLoad(
                            "qairt_dry_run_load_ok",
                            timedLoad(
                                engine = activeEngine,
                                target = target,
                                loadParams = loadParams,
                                qairtBundleSha256 = qairtDryRunBundleSha256,
                                qairtExecutionPurpose = QairtExecutionPurpose.ISOLATED_DRY_RUN
                            ),
                            activeEngine
                        )
                        val loadedNativeStats = JSONObject(activeEngine.nativeStatsJson())
                        require(hasQairtDryRunNpuEvidence(loadedNativeStats)) {
                            "QAIRT dry-run did not report NPU/HTP execution evidence."
                        }
                        if (!imagePath.isNullOrBlank()) {
                            require(loadedNativeStats.optBoolean("visionReady", false)) {
                                "QAIRT VLM dry-run loaded without visionReady=true."
                            }
                        }
                        write(
                            JSONObject()
                                .put("status", "qairt_dry_run_npu_evidence_ok")
                                .put("nativeStats", loadedNativeStats)
                        )
                        val generation = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "qairt_dry_run_generation_ok")
                                .put("generation", generation)
                        )
                        val unloadMs = timedUnload(activeEngine)
                        require(eventLog.hasRunnerStage("_destroy_ok")) {
                            "QAIRT dry-run did not observe a native destroy_ok stage."
                        }
                        val destroyedNativeStats = JSONObject(activeEngine.nativeStatsJson())
                        require(!destroyedNativeStats.optBoolean("loaded", true)) {
                            "QAIRT dry-run destroy left the native handle loaded."
                        }
                        require(destroyedNativeStats.optString("lastError").isBlank()) {
                            "QAIRT dry-run destroy reported: ${destroyedNativeStats.optString("lastError")}"
                        }
                        qairtDryRunReadyToRecord = true
                        write(
                            JSONObject()
                                .put("status", "qairt_dry_run_destroy_ok")
                                .put("unloadMs", unloadMs)
                                .put("nativeStats", destroyedNativeStats)
                        )
                    }
                    "api_only" -> {
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val guard = apiHiddenReasoningGuardSmoke()
                        write(JSONObject().put("status", "api_guard_ok").put("apiGuard", guard))
                        write(JSONObject().put("status", "api_engine_stream_start").put("imagePath", imagePath))
                        val realApi = apiEngineStreamSmoke(
                            nCtx = nCtx,
                            maxTokens = maxTokens,
                            imagePath = imagePath,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "api_engine_stream_ok")
                                .put("apiEngine", realApi)
                        )
                    }
                    "litertlm_cache_ab" -> {
                        require(target.runtime == LocalChatRuntime.LITERT_LM) {
                            "litertlm_cache_ab requires a LiteRT-LM target."
                        }
                        require(imagePath.isNullOrBlank()) {
                            "litertlm_cache_ab is text-only; omit imagePath."
                        }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)

                        // Keep the same LiteRT Conversation across two requests.
                        // The second request includes the first turn transcript,
                        // so the runtime can reuse its KV cache instead of
                        // creating a fresh conversation for the same prefix.
                        val firstMessages = listOf(
                            ChatMessage(Role.SYSTEM, systemPrompt),
                            ChatMessage(Role.USER, prompt)
                        )
                        val first = generationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = maxTokens,
                            imagePath = null,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson,
                            requestMessages = firstMessages
                        )
                        val firstText = first.optString("text")
                        require(firstText.isNotBlank()) {
                            "litertlm_cache_ab first turn produced no assistant text."
                        }
                        write(
                            JSONObject()
                                .put("status", "litertlm_cache_first_ok")
                                .put("round", 1)
                                .put("generation", first)
                        )

                        val followUpPrompt = intent.getStringExtra("followUpPrompt")
                            .orEmpty()
                            .ifBlank { "Reply with only the word cached." }
                        val secondMessages = firstMessages +
                            ChatMessage(Role.ASSISTANT, firstText) +
                            ChatMessage(Role.USER, followUpPrompt)
                        val second = generationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = maxTokens,
                            imagePath = null,
                            prompt = followUpPrompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson,
                            requestMessages = secondMessages,
                            allowGenerationFailure = true
                        )
                        require(second.optBoolean("doneSeen", false) &&
                            second.optString("text").isNotBlank() &&
                            (second.isNull("error") || second.optString("error").isBlank())) {
                            "litertlm_cache_ab second turn failed: ${second.optString("error")}"
                        }
                        val firstStats = first.optJSONObject("nativeStats") ?: JSONObject()
                        val secondStats = second.optJSONObject("nativeStats") ?: JSONObject()
                        val firstKv = firstStats.optInt("kvCacheTokens", 0)
                        val secondKv = secondStats.optInt("kvCacheTokens", 0)
                        require(firstKv > 0 && secondKv > firstKv) {
                            "litertlm_cache_ab did not observe a growing Conversation KV cache: first=$firstKv second=$secondKv"
                        }
                        val cacheResult = JSONObject()
                            .put("sameConversation", true)
                            .put("cacheHit", true)
                            .put("firstPromptChars", prompt.length)
                            .put("firstPromptSha256", localChatSmokeTextSha256(prompt))
                            .put("followUpPrompt", followUpPrompt.take(4096))
                            .put("followUpPromptSha256", localChatSmokeTextSha256(followUpPrompt))
                            .put("firstStats", firstStats)
                            .put("secondStats", secondStats)
                            .put("firstKvCacheTokens", firstKv)
                            .put("secondKvCacheTokens", secondKv)
                        write(
                            JSONObject(cacheResult.toString())
                                .put("status", "litertlm_cache_ab_ok")
                        )

                        val guard = apiHiddenReasoningGuardSmoke()
                        write(JSONObject().put("status", "api_guard_ok").put("apiGuard", guard))
                        write(JSONObject().put("status", "api_engine_stream_start"))
                        val realApi = apiEngineStreamSmoke(
                            nCtx = nCtx,
                            maxTokens = maxTokens,
                            imagePath = null,
                            prompt = followUpPrompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "api_engine_stream_ok")
                                .put("apiEngine", realApi)
                        )
                    }
                    "direct_once" -> {
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val generation = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "generation_ok")
                                .put("generation", generation)
                        )
                    }
                    "direct_twice" -> {
                        require(!imagePath.isNullOrBlank()) { "direct_twice smoke requires imagePath." }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val first = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_first_ok").put("generation", first))
                        val second = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_second_ok").put("generation", second))
                    }
                    "direct_counterfactual" -> {
                        require(!imagePath.isNullOrBlank()) {
                            "direct_counterfactual smoke requires imagePath."
                        }
                        require(!secondImagePath.isNullOrBlank()) {
                            "direct_counterfactual smoke requires secondImagePath."
                        }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val first = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_first_ok").put("generation", first))
                        val second = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            secondImagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_second_ok").put("generation", second))
                    }
                    "text_then_image" -> {
                        require(!imagePath.isNullOrBlank()) { "text_then_image smoke requires imagePath." }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val textFirst = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            null,
                            textPreludePrompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_text_ok").put("generation", textFirst))
                        val imageSecond = generationSmoke(
                            activeEngine,
                            nCtx,
                            nThreads,
                            maxTokens,
                            imagePath,
                            prompt,
                            systemPrompt,
                            sampling,
                            resolvedAdvancedJson
                        )
                        write(JSONObject().put("status", "generation_image_ok").put("generation", imageSecond))
                    }
                    "api_twice" -> {
                        require(!imagePath.isNullOrBlank()) { "api_twice smoke requires imagePath." }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        write(JSONObject().put("status", "api_engine_first_start").put("imagePath", imagePath))
                        val firstApi = apiEngineStreamSmoke(
                            nCtx = nCtx,
                            maxTokens = maxTokens,
                            imagePath = imagePath,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "api_engine_first_ok")
                                .put("apiEngine", firstApi)
                        )
                        write(JSONObject().put("status", "api_engine_second_start").put("imagePath", imagePath))
                        val secondApi = apiEngineStreamSmoke(
                            nCtx = nCtx,
                            maxTokens = maxTokens,
                            imagePath = imagePath,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "api_engine_second_ok")
                                .put("apiEngine", secondApi)
                        )
                    }
                    "cancel_then_second" -> {
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val cancellationMaxTokens = maxTokens.coerceAtLeast(
                            LOCAL_CHAT_SMOKE_CANCELLATION_MIN_TOKENS
                        )
                        write(
                            JSONObject()
                                .put("status", "cancel_first_start")
                                .put("prompt", LOCAL_CHAT_SMOKE_CANCELLATION_PROMPT)
                                .put("maxTokens", cancellationMaxTokens)
                        )
                        val firstRequest = cancellationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = cancellationMaxTokens,
                            imagePath = imagePath,
                            prompt = LOCAL_CHAT_SMOKE_CANCELLATION_PROMPT,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        cancelThenSecondResult = localChatSmokeCancelThenSecondResult(
                            firstRequest = firstRequest,
                            secondRequest = localChatSmokeGenerationResult(
                                visibleText = "",
                                imagePath = null,
                                doneSeen = false,
                                flowCompleted = false,
                                error = "Recovery request has not completed.",
                                nativeStats = JSONObject(activeEngine.nativeStatsJson())
                            )
                        )
                        require(firstRequest.optBoolean("stopCallSucceeded", false)) {
                            "cancel_then_second did not request cancellation."
                        }
                        write(
                            JSONObject()
                                .put("status", "cancel_stop_requested")
                                .put("firstRequest", firstRequest)
                        )
                        require(firstRequest.optBoolean("cancellationVerified", false)) {
                            val evidence = firstRequest.optJSONObject("cancellationEvidence")
                            "cancel_then_second did not prove pre-terminal cancellation: ${evidence ?: "missing evidence"}"
                        }
                        write(
                            JSONObject()
                                .put("status", "cancel_first_terminated")
                                .put("firstRequest", firstRequest)
                        )
                        val recoveryMaxTokens = maxTokens.coerceAtMost(16)
                        val recoveryPrompt = "Reply with one short word: recovered."
                        write(
                            JSONObject()
                                .put("status", "cancel_recovery_start")
                                .put("maxTokens", recoveryMaxTokens)
                                .put("prompt", recoveryPrompt)
                        )
                        val secondRequest = generationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = recoveryMaxTokens,
                            imagePath = null,
                            prompt = recoveryPrompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        val result = localChatSmokeCancelThenSecondResult(firstRequest, secondRequest)
                        require(result.optBoolean("cancellationSuccess", false)) {
                            "cancel_then_second did not observe a successful cancellation."
                        }
                        require(result.optBoolean("recoverySuccess", false)) {
                            "cancel_then_second recovery request failed."
                        }
                        cancelThenSecondResult = result
                        write(
                            JSONObject()
                                .put("status", "cancel_recovery_ok")
                                .put("cancelThenSecond", result)
                                .put("cancellationSuccess", true)
                                .put("recoverySuccess", true)
                        )
                    }
                    "mnn_cache_ab" -> {
                        require(target.runtime == LocalChatRuntime.MNN_CPU) {
                            "mnn_cache_ab requires an MNN target (runtime=mnn_cpu)."
                        }
                        require(imagePath.isNullOrBlank()) {
                            "mnn_cache_ab is text-only; omit imagePath so MNN prompt/KV reuse remains enabled."
                        }
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)

                        // Keep the first request's rendered prefix stable. The native MNN
                        // bridge commits this transcript plus the generated assistant turn;
                        // the second request must include that assistant content before the
                        // follow-up user turn for an exact prefix candidate.
                        val firstMessages = listOf(
                            ChatMessage(Role.SYSTEM, systemPrompt),
                            ChatMessage(Role.USER, prompt)
                        )
                        val first = generationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = maxTokens,
                            imagePath = null,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson,
                            requestMessages = firstMessages
                        )
                        // Preserve the visible assistant text exactly as emitted; the native
                        // prefix comparator already ignores presentation-only trailing spaces.
                        val firstText = first.optString("text")
                        require(firstText.isNotBlank()) {
                            "mnn_cache_ab first turn produced no assistant text."
                        }
                        val firstRound = mnnCacheRoundEvidence(first)
                        write(
                            JSONObject()
                                .put("status", "mnn_cache_ab_first_ok")
                                .put("round", 1)
                                .put("generation", first)
                                .put("stats", firstRound)
                                .put("promptCacheHit", firstRound.optBoolean("promptCacheHit", false))
                                .put("reusedTokens", firstRound.optInt("reusedTokens", 0))
                                .put("prefillMs", firstRound.optLong("prefillMs", 0L))
                                .put("decodeTps", firstRound.optDouble("decodeTps", 0.0))
                                .put("ttftMs", firstRound.optLong("ttftMs", 0L))
                        )

                        val followUpPrompt = intent.getStringExtra("followUpPrompt")
                            .orEmpty()
                            .ifBlank { "Reply with one short word confirming the cached context." }
                        val secondMessages = firstMessages +
                            ChatMessage(Role.ASSISTANT, firstText) +
                            ChatMessage(Role.USER, followUpPrompt)
                        val javaPrefixStable = mnnCacheMessageTranscriptSha256(firstMessages) ==
                            mnnCacheMessageTranscriptSha256(secondMessages.dropLast(2))
                        val second = generationSmoke(
                            engine = activeEngine,
                            nCtx = nCtx,
                            nThreads = nThreads,
                            maxTokens = maxTokens,
                            imagePath = null,
                            prompt = followUpPrompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson,
                            requestMessages = secondMessages,
                            allowGenerationFailure = true
                        )
                        val secondRound = mnnCacheRoundEvidence(second)
                        val secondSucceeded = mnnCacheGenerationSucceeded(second)
                        write(
                            JSONObject()
                                .put("status", if (secondSucceeded) "mnn_cache_ab_second_ok" else "mnn_cache_ab_second_failed")
                                .put("round", 2)
                                .put("generation", second)
                                .put("stats", secondRound)
                                .put("promptCacheHit", secondRound.optBoolean("promptCacheHit", false))
                                .put("reusedTokens", secondRound.optInt("reusedTokens", 0))
                                .put("prefillMs", secondRound.optLong("prefillMs", 0L))
                                .put("decodeTps", secondRound.optDouble("decodeTps", 0.0))
                                .put("ttftMs", secondRound.optLong("ttftMs", 0L))
                                .put("generationSucceeded", mnnCacheGenerationSucceeded(second))
                                .put("generationError", second.opt("error") ?: JSONObject.NULL)
                        )
                        val cacheAbResult = JSONObject()
                            .put("roundCount", 2)
                            .put("firstPrompt", prompt.take(4_096))
                            .put("firstPromptChars", prompt.length)
                            .put("firstPromptSha256", localChatSmokeTextSha256(prompt))
                            .put("followUpPrompt", followUpPrompt.take(4_096))
                            .put("followUpPromptChars", followUpPrompt.length)
                            .put("followUpPromptSha256", localChatSmokeTextSha256(followUpPrompt))
                            .put("firstAssistantTextSha256", localChatSmokeTextSha256(firstText))
                            .put("javaPrefixStable", javaPrefixStable)
                            .put("firstTranscriptSha256", mnnCacheMessageTranscriptSha256(firstMessages))
                            .put(
                                "secondPrefixTranscriptSha256",
                                mnnCacheMessageTranscriptSha256(secondMessages.dropLast(2))
                            )
                            .put("firstStats", firstRound)
                            .put("secondStats", secondRound)
                            .put("secondGenerationSucceeded", mnnCacheGenerationSucceeded(second))
                            .put("secondGenerationError", second.opt("error") ?: JSONObject.NULL)
                            .put("cacheHit", secondRound.optBoolean("promptCacheHit", false))
                            .put("reusedTokens", secondRound.optInt("reusedTokens", 0))
                        mnnCacheAbResult = cacheAbResult
                        // Execute the exact second transcript with a fresh native model,
                        // even when the cached leg failed. This is a control, not a retry.
                        write(JSONObject().put("status", "mnn_cache_cold_unload_ok")
                            .put("unloadMs", timedUnload(activeEngine)))
                        writeLoad("mnn_cache_cold_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        val cold = generationSmoke(
                            engine = activeEngine, nCtx = nCtx, nThreads = nThreads, maxTokens = maxTokens,
                            imagePath = null, prompt = followUpPrompt, systemPrompt = systemPrompt,
                            sampling = sampling, advancedJson = resolvedAdvancedJson,
                            requestMessages = secondMessages, allowGenerationFailure = true
                        )
                        val coldStats = mnnCacheRoundEvidence(cold)
                        val coldSucceeded = mnnCacheGenerationSucceeded(cold)
                        val coldNoReuse = !coldStats.optBoolean("promptCacheHit", false) &&
                            coldStats.optInt("reusedTokens", 0) == 0
                        val coldControl = JSONObject()
                            .put("generation", cold).put("stats", coldStats)
                            .put("generationSucceeded", coldSucceeded).put("noReuse", coldNoReuse)
                            .put("sameRequestTranscript", true)
                            .put("requestTranscriptSha256", mnnCacheMessageTranscriptSha256(secondMessages))
                            .put("cachedText", second.optString("text"))
                            .put("coldText", cold.optString("text"))
                        cacheAbResult.put("coldControl", coldControl)
                        write(JSONObject().put("status", if (coldSucceeded && coldNoReuse)
                            "mnn_cache_cold_ok" else "mnn_cache_cold_failed").put("coldControl", coldControl))
                        val cacheAbSucceeded = secondSucceeded && javaPrefixStable &&
                            cacheAbResult.optBoolean("cacheHit", false) &&
                            cacheAbResult.optInt("reusedTokens", 0) > 0 && coldSucceeded && coldNoReuse
                        write(
                            JSONObject(cacheAbResult.toString())
                                .put("status", if (cacheAbSucceeded) "mnn_cache_ab_ok" else "mnn_cache_ab_failed")
                        )
                        require(cacheAbSucceeded) {
                            "mnn_cache_ab failed: secondSucceeded=$secondSucceeded, " +
                                "cacheHit=${cacheAbResult.optBoolean("cacheHit", false)}, " +
                                "error=${second.optString("error")}."
                        }
                    }
                    else -> {
                        writeLoad("first_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)
                        write(
                            JSONObject()
                                .put("status", "first_unload_ok")
                                .put("unloadMs", timedUnload(activeEngine))
                                .put("nativeStats", JSONObject(activeEngine.nativeStatsJson()))
                        )
                        writeLoad("second_load_ok", timedLoad(activeEngine, target, loadParams), activeEngine)

                        if (continuousTurns == 1) {
                            val generation = generationSmoke(
                                activeEngine,
                                nCtx,
                                nThreads,
                                maxTokens,
                                imagePath,
                                prompt,
                                systemPrompt,
                                sampling,
                                resolvedAdvancedJson
                            )
                            write(JSONObject().put("status", "generation_ok").put("generation", generation))
                        } else {
                            val turns = JSONArray()
                            // Keep a real conversation transcript between turns.  Reissuing
                            // the same one-message request exercises repeated generation, but
                            // it cannot prove that a runtime's role formatting or KV cache can
                            // consume the previous assistant answer.
                            val conversationMessages = mutableListOf<ChatMessage>(
                                ChatMessage(Role.SYSTEM, systemPrompt),
                                smokeUserMessage(imagePath, prompt)
                            )
                            repeat(continuousTurns) { index ->
                                val generation = generationSmoke(
                                    activeEngine,
                                    nCtx,
                                    nThreads,
                                    maxTokens,
                                    imagePath,
                                    prompt,
                                    systemPrompt,
                                    sampling,
                                    resolvedAdvancedJson,
                                    requestMessages = conversationMessages.toList()
                                )
                                turns.put(generation)
                                write(
                                    JSONObject()
                                        .put("status", "generation_turn_ok")
                                        .put("turn", index + 1)
                                        .put("requestMessageCount", conversationMessages.size)
                                        .put(
                                            "requestTranscriptSha256",
                                            mnnCacheMessageTranscriptSha256(conversationMessages)
                                        )
                                        .put("generation", generation)
                                )
                                // Preserve the exact stream text in the transcript. The native
                                // MNN cache applies the same protocol filter before committing
                                // its assistant payload, so this remains the canonical follow-up
                                // text used by the next request.
                                val assistantText = generation.optString("text")
                                require(assistantText.isNotBlank()) {
                                    "continuous turn ${index + 1} produced no assistant text."
                                }
                                if (index + 1 < continuousTurns) {
                                    conversationMessages += ChatMessage(Role.ASSISTANT, assistantText)
                                    conversationMessages += smokeUserMessage(imagePath, prompt)
                                }
                            }
                            val finalGeneration = JSONObject(turns.getJSONObject(turns.length() - 1).toString())
                                .put("turnCount", continuousTurns)
                                .put("turns", turns)
                            write(JSONObject().put("status", "generation_ok").put("generation", finalGeneration))
                        }

                        val guard = apiHiddenReasoningGuardSmoke()
                        write(JSONObject().put("status", "api_guard_ok").put("apiGuard", guard))

                        write(JSONObject().put("status", "api_engine_stream_start").put("imagePath", imagePath))
                        val realApi = apiEngineStreamSmoke(
                            nCtx = nCtx,
                            maxTokens = maxTokens,
                            imagePath = imagePath,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            sampling = sampling,
                            advancedJson = resolvedAdvancedJson
                        )
                        write(
                            JSONObject()
                                .put("status", "api_engine_stream_ok")
                                .put("apiEngine", realApi)
                        )
                    }
                }
            }

            val nativeStats = JSONObject(activeEngine.nativeStatsJson())
            if (smokeMode != "qairt_dry_run" &&
                requiresLocalChatSmokeVisionReady(target.runtime, imagePath, target.visionProjectorPath)
            ) {
                require(nativeStats.opt("visionReady") == true) {
                    "VLM smoke completed generation without nativeStats.visionReady=true."
                }
            }
            terminalEvent = JSONObject()
                .put("status", "completed")
                .put("nativeStats", nativeStats)
                .put("qairtDryRunReadyToRecord", qairtDryRunReadyToRecord)
                .put("advancedJsonInput", advancedJsonInput)
                .put("advancedJsonMerged", resolvedAdvancedJson)
                .apply {
                    mnnCacheAbResult?.let { result ->
                        put("mnnCacheAb", JSONObject(result.toString()))
                    }
                    cancelThenSecondResult?.let { result ->
                        put("cancelThenSecond", result)
                        put("cancellationSuccess", result.optBoolean("cancellationSuccess", false))
                        put("recoverySuccess", result.optBoolean("recoverySuccess", false))
                    }
                }
        } catch (error: Throwable) {
            terminalEvent = JSONObject()
                .put("status", "failed")
                .put("error", error.stackTraceToString())
                .put("advancedJsonInput", advancedJsonInput)
                .put("advancedJsonMerged", advancedJsonMerged ?: JSONObject.NULL)
                .put(
                    "nativeStats",
                    engine?.let { smokeEngine ->
                        runCatching { JSONObject(smokeEngine.nativeStatsJson()) }.getOrNull()
                    }
                )
                .apply {
                    mnnCacheAbResult?.let { result ->
                        put("mnnCacheAb", JSONObject(result.toString()))
                    }
                    cancelThenSecondResult?.let { result ->
                        put("cancelThenSecond", result)
                        put("cancellationSuccess", result.optBoolean("cancellationSuccess", false))
                        put("recoverySuccess", result.optBoolean("recoverySuccess", false))
                    }
                }
        } finally {
            val smokeEngine = engine
            val shutdownError = runCatching {
                // Native shutdown can block inside JNI. Do not cancel and reuse this process;
                // the host-side ADB deadline is the hard stop for an unresponsive smoke run.
                runBlocking {
                    smokeEngine?.shutdown()
                }
            }.exceptionOrNull()?.stackTraceToString()
            runCatching { isolatedRunners?.close() }
            val destroyFailureStages = eventLog.destroyFailureStages()
            if (!qairtDryRunBundleSha256.isNullOrBlank()) {
                if (shutdownError.isNullOrBlank()) {
                    write(
                        JSONObject()
                            .put("status", "qairt_dry_run_shutdown_ok")
                            .put("bundleSha256", qairtDryRunBundleSha256)
                    )
                }
                val terminalCompleted = terminalEvent?.optString("status") == "completed"
                val recorded = if (
                    qairtDryRunReadyToRecord &&
                    terminalCompleted &&
                    shutdownError.isNullOrBlank() &&
                    destroyFailureStages.isEmpty()
                ) {
                    runCatching {
                        smokeEngine?.recordVerifiedQairtDryRun(qairtDryRunBundleSha256) == true
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (recorded) {
                    terminalEvent?.put("qairtDryRunVerified", true)
                    write(
                        JSONObject()
                            .put("status", "qairt_dry_run_verified")
                            .put("bundleSha256", qairtDryRunBundleSha256)
                            .put("verification", "exact_bundle_chipset_runtime")
                    )
                } else {
                    val unverified = terminalEvent?.let { JSONObject(it.toString()) } ?: JSONObject()
                    val priorError = unverified.optString("error").trim()
                    unverified
                        .put("status", "failed")
                        .put(
                            "error",
                            listOf(
                                priorError,
                                "QAIRT dry-run was not certified: require isolated create, visible generation, clean destroy, and clean shutdown."
                            ).filter { it.isNotBlank() }.joinToString("\n")
                        )
                        .put("qairtDryRunReadyToRecord", qairtDryRunReadyToRecord)
                        .put("qairtDryRunVerified", false)
                    terminalEvent = unverified
                }
            }
            globalSnapshot.restore()
            val finalEvent = finalizeLocalChatSmokeEvent(terminalEvent, shutdownError, destroyFailureStages)
            write(finalEvent)
        }
    }
    private suspend fun resolveTarget(write: (JSONObject) -> Unit): SmokeTarget {
        val recommendedId = intent.getStringExtra("recommendedId").orEmpty().ifBlank { null }
        if (recommendedId != null) {
            return downloadRecommendedQairtTarget(recommendedId, write)
        }
        val modelPath = intent.getStringExtra("modelPath").orEmpty()
        if (modelPath.isNotBlank()) {
            val runtime = intent.getStringExtra("runtime")
                .toLocalRuntime(defaultForPath(modelPath))
            val file = File(modelPath)
            require(file.exists()) { "modelPath does not exist: $modelPath" }
            val registeredQairt = if (runtime == LocalChatRuntime.GENIEX_QAIRT) {
                val requestedPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                ModelStoreRepository(applicationContext).listModels().firstOrNull { candidate ->
                    candidate.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                        runCatching { File(candidate.path).canonicalPath }.getOrDefault(candidate.path) == requestedPath
                }
            } else {
                null
            }
            return SmokeTarget(
                id = registeredQairt?.id ?: "intent-model",
                displayName = intent.getStringExtra("displayName").orEmpty()
                    .ifBlank { registeredQairt?.displayName ?: file.name },
                path = registeredQairt?.path ?: file.absolutePath,
                runtime = runtime,
                visionProjectorPath = intent.getStringExtra("visionProjectorPath").orEmpty().ifBlank { null },
                qairtBundleSha256 = registeredQairt?.sha256
            )
        }
        val store = ModelStoreRepository(applicationContext)
        val model = store.listModels()
            .maxWithOrNull(compareBy<ModelManifest> { it.lastLoadedAt ?: 0L }.thenBy { it.createdAt })
            ?: error("No local chat model registered. Pass modelPath or import/download a model first.")
        return SmokeTarget(
            id = model.id,
            displayName = model.displayName,
            path = model.path,
            runtime = model.runtime.toLocalRuntime(),
            visionProjectorPath = model.visionProjectorPath
                ?.takeIf { model.runtime == ChatModelRuntime.LLAMA_CPP }
                ?.takeIf { File(it).isFile },
            qairtBundleSha256 = model.sha256.takeIf {
                model.runtime == ChatModelRuntime.GENIEX_QAIRT
            }
        )
    }

    private suspend fun downloadRecommendedQairtTarget(
        recommendedId: String,
        write: (JSONObject) -> Unit
    ): SmokeTarget {
        val client = ModelScopeClient()
        val model = client.recommendedModels().firstOrNull { it.id == recommendedId }
            ?: error("Unknown recommendedId: $recommendedId")
        require(model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
            "recommendedId is not a GenieX QAIRT chat model: $recommendedId"
        }
        val remote = client.recommendedFile(
            model = model,
            preferredQairtChipsets = preferredQairtChipsets()
        )
        val downloadDir = File(getExternalFilesDir("recommended_downloads"), model.id).apply { mkdirs() }
        val finalZip = File(downloadDir, remote.name)
        val tempZip = File(downloadDir, "${remote.name}.part")
        val bundleDir = File(getExternalFilesDir("models"), "${model.id}_bundle")

        write(
            JSONObject()
                .put("status", "recommended_download_resolved")
                .put("recommendedId", model.id)
                .put("title", model.title)
                .put("repoId", model.repoId)
                .put("remoteName", remote.name)
                .put("remoteUrl", remote.downloadUrl)
                .put("finalZip", finalZip.absolutePath)
                .put("bundleDir", bundleDir.absolutePath)
                .put("chipsets", JSONArray(preferredQairtChipsets()))
        )

        if (!finalZip.isFile || finalZip.length() == 0L) {
            var lastProgressAt = 0L
            ResumableDownloader().download(
                remote = remote,
                tempFile = tempZip,
                finalFile = finalZip
            ) { snapshot ->
                val now = System.currentTimeMillis()
                if (snapshot.status == DownloadStatus.DONE || now - lastProgressAt >= 5_000L) {
                    lastProgressAt = now
                    write(
                        JSONObject()
                            .put("status", "recommended_download_progress")
                            .put("downloadStatus", snapshot.status.name.lowercase())
                            .put("downloadedBytes", snapshot.downloadedBytes)
                            .put("expectedLength", snapshot.expectedLength)
                            .put("downloaded", formatBytes(snapshot.downloadedBytes))
                            .put("expected", formatBytes(snapshot.expectedLength))
                            .put("speedBytesPerSecond", snapshot.speedBytesPerSecond)
                            .put("errorMessage", snapshot.errorMessage)
                    )
                }
            }
        } else {
            write(
                JSONObject()
                    .put("status", "recommended_download_reused")
                    .put("zipBytes", finalZip.length())
                    .put("zipSize", formatBytes(finalZip.length()))
            )
        }

        if (!looksLikeCompleteQairtBundle(bundleDir)) {
            clearDirectory(bundleDir)
            unzipIntoDirectory(finalZip, bundleDir)
            write(
                JSONObject()
                    .put("status", "recommended_unzip_ok")
                    .put("bundleDir", bundleDir.absolutePath)
                    .put("fileCount", bundleDir.walkTopDown().count { it.isFile })
                    .put("bundleSize", formatBytes(bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }))
            )
        } else {
            write(
                JSONObject()
                    .put("status", "recommended_bundle_reused")
                    .put("bundleDir", bundleDir.absolutePath)
            )
        }

        val runtimeBundleDir = selectQairtRuntimeDir(bundleDir)
        write(
            JSONObject()
                .put("status", "recommended_runtime_bundle_selected")
                .put("runtimeBundleDir", runtimeBundleDir.absolutePath)
        )
        val store = ModelStoreRepository(applicationContext)
        val existing = store.listModels()
            .firstOrNull { it.runtime == ChatModelRuntime.GENIEX_QAIRT && File(it.path).absolutePath == runtimeBundleDir.absolutePath }
        val manifest = existing ?: store.registerDownloadedQairtBundle(
            displayName = model.title,
            bundleDir = runtimeBundleDir,
            repoId = model.repoId,
            revision = model.revision,
            quant = model.quant,
            architecture = "GenieX QAIRT"
        )
        write(
            JSONObject()
                .put("status", if (existing == null) "recommended_registered" else "recommended_registration_reused")
                .put("modelId", manifest.id)
                .put("displayName", manifest.displayName)
                .put("path", manifest.path)
                .put("runtime", manifest.runtime.storageValue)
        )
        return SmokeTarget(
            id = manifest.id,
            displayName = manifest.displayName,
            path = manifest.path,
            runtime = LocalChatRuntime.GENIEX_QAIRT,
            visionProjectorPath = null,
            qairtBundleSha256 = manifest.sha256
        )
    }

    private fun preferredQairtChipsets(): List<String> {
        val socModel = Build.SOC_MODEL.orEmpty().uppercase()
        return when {
            "SM8850" in socModel -> listOf(
                "qualcomm-snapdragon-8-elite-gen5",
                "qualcomm-snapdragon-8-elite"
            )
            "SM8750" in socModel -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            else -> listOf(
                "qualcomm-snapdragon-8-elite-gen5",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-qcs9075"
            )
        }
    }

    private fun looksLikeCompleteQairtBundle(bundleDir: File): Boolean {
        if (!bundleDir.isDirectory) return false
        val files = bundleDir.walkTopDown()
            .filter { it.isFile && it.length() > 0L }
            .toList()
        if (files.isEmpty()) return false
        val names = files.map { it.name.lowercase() }
        val hasRuntimeConfig = names.any { name ->
            name == "genie_config.json" ||
                name == "htp_backend_ext_config.json" ||
                name == "config.json" ||
                (name.endsWith(".json") && ("genie" in name || "qairt" in name || "qnn" in name))
        }
        val hasQairtArtifact = names.any { name ->
            name.endsWith(".bin") ||
                name.endsWith(".serialized") ||
                name.endsWith(".ctx") ||
                name.endsWith(".qnn") ||
                (name.endsWith(".so") && ("qnn" in name || "genie" in name))
        }
        return hasRuntimeConfig || hasQairtArtifact
    }

    private fun clearDirectory(directory: File) {
        if (!directory.exists()) return
        val root = directory.canonicalFile
        directory.listFiles()?.forEach { child ->
            val candidate = child.canonicalFile
            require(candidate.absolutePath.startsWith(root.absolutePath + File.separator)) {
                "Refusing to delete outside bundle directory: ${candidate.absolutePath}"
            }
            child.deleteRecursively()
        }
    }

    private fun unzipIntoDirectory(zipFile: File, targetDir: File) {
        require(zipFile.isFile && zipFile.length() > 0L) { "QAIRT zip is empty or missing: ${zipFile.absolutePath}" }
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/').trimStart('/')
                if (entryName.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val target = File(root, entryName).canonicalFile
                require(target.absolutePath.startsWith(root.absolutePath + File.separator)) {
                    "Unsafe QAIRT zip entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
    }

    private fun selectQairtRuntimeDir(bundleDir: File): File {
        if (looksLikeCompleteQairtBundle(bundleDir) && hasDirectQairtArtifact(bundleDir)) return bundleDir
        val completeChildren = bundleDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && looksLikeCompleteQairtBundle(it) }
        return completeChildren.singleOrNull() ?: bundleDir
    }

    private fun hasDirectQairtArtifact(directory: File): Boolean {
        val names = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.length() > 0L }
            .map { it.name.lowercase() }
        return names.any { name ->
            name == "genie_config.json" ||
                name == "htp_backend_ext_config.json" ||
                name == "config.json" ||
                name.endsWith(".bin") ||
                name.endsWith(".serialized") ||
                name.endsWith(".ctx") ||
                name.endsWith(".qnn") ||
                (name.endsWith(".so") && ("qnn" in name || "genie" in name))
        }
    }

    private suspend fun timedLoad(
        engine: McaInferenceService,
        target: SmokeTarget,
        loadParams: LoadParams,
        qairtBundleSha256: String? = target.qairtBundleSha256,
        qairtExecutionPurpose: QairtExecutionPurpose = QairtExecutionPurpose.NORMAL
    ): Long {
        val started = System.currentTimeMillis()
        LocalChatRunnerDebug.emit(
            "smoke_engine_load_call_start",
            JSONObject()
                .put("targetId", target.id)
                .put("targetRuntime", target.runtime.backendId)
                .put("targetPath", target.path)
                .put("computeUnit", loadParams.geniexComputeUnit)
        )
        engine.loadModel(
            modelPath = target.path,
            runtime = target.runtime,
            params = loadParams,
            qairtBundleSha256 = qairtBundleSha256,
            qairtExecutionPurpose = qairtExecutionPurpose
        ).getOrThrow()
        val loadMs = System.currentTimeMillis() - started
        LocalChatRunnerDebug.emit(
            "smoke_engine_load_call_ok",
            JSONObject().put("loadMs", loadMs)
        )
        return loadMs
    }

    private suspend fun timedUnload(engine: McaInferenceService): Long {
        val started = System.currentTimeMillis()
        LocalChatRunnerDebug.emit("smoke_engine_unload_call_start", JSONObject())
        engine.unloadModel()
        val unloadMs = System.currentTimeMillis() - started
        LocalChatRunnerDebug.emit("smoke_engine_unload_call_ok", JSONObject().put("unloadMs", unloadMs))
        return unloadMs
    }

    private suspend fun generationSmoke(
        engine: McaInferenceService,
        nCtx: Int,
        nThreads: Int,
        maxTokens: Int,
        imagePath: String?,
        prompt: String,
        systemPrompt: String,
        sampling: LocalChatSmokeSampling,
        advancedJson: String,
        requestMessages: List<ChatMessage>? = null,
        allowGenerationFailure: Boolean = false
    ): JSONObject {
        val text = StringBuilder()
        var doneSeen = false
        val params = localChatSmokeGenerationParams(
            nCtx = nCtx,
            nThreads = nThreads,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            sampling = sampling,
            advancedJson = advancedJson
        )
        val timeoutPromptChars = requestMessages
            ?.sumOf { message -> message.content.length }
            ?.coerceAtMost(256 * 1024)
            ?: prompt.length
        var generationError: String? = null
        withTimeout(localChatSmokeGenerationTimeoutMs(timeoutPromptChars, nCtx, maxTokens)) {
            engine.streamChat(
                ChatRequest(
                    messages = requestMessages ?: listOf(smokeUserMessage(imagePath, prompt)),
                    params = params
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Phase -> Unit
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> text.append(event.text)
                    is GenerateEvent.Done -> doneSeen = true
                    is GenerateEvent.Error -> {
                        if (allowGenerationFailure) {
                            generationError = event.message
                        } else {
                            error(event.message)
                        }
                    }
                }
            }
        }
        if (!allowGenerationFailure) {
            require(text.isNotBlank()) { "generation smoke produced no visible text" }
            require(doneSeen) { "generation smoke did not receive Done" }
        }
        val visibleText = text.toString()
        return localChatSmokeGenerationResult(
            visibleText = visibleText,
            imagePath = imagePath,
            doneSeen = doneSeen,
            flowCompleted = true,
            error = generationError,
            nativeStats = JSONObject(engine.nativeStatsJson())
        )
    }

    /**
     * Extract the stable, round-level evidence used by the MNN cache A/B smoke.
     * Keep the complete nativeStats on the generation object as well; this
     * compact projection makes CPU/OpenCL comparisons easy to parse from the
     * event log without depending on a particular native stats alias.
     */
    private fun mnnCacheRoundEvidence(generation: JSONObject): JSONObject {
        val nativeStats = generation.optJSONObject("nativeStats") ?: JSONObject()
        val promptCache = nativeStats.optJSONObject("promptCache") ?: JSONObject()
        val cacheReuse = nativeStats.optJSONObject("cacheReuse") ?: JSONObject()
        fun cacheValue(name: String, alias: String): Any = when {
            promptCache.has(name) -> promptCache.opt(name) ?: JSONObject.NULL
            nativeStats.has(alias) -> nativeStats.opt(alias) ?: JSONObject.NULL
            else -> JSONObject.NULL
        }
        val promptCacheHit = when {
            promptCache.has("hit") -> promptCache.optBoolean("hit", false)
            nativeStats.has("promptCacheHit") -> nativeStats.optBoolean("promptCacheHit", false)
            else -> cacheReuse.optBoolean("hit", false)
        }
        val reusedTokens = when {
            promptCache.has("reusedTokens") -> promptCache.optInt("reusedTokens", 0)
            nativeStats.has("promptCacheReusedTokens") ->
                nativeStats.optInt("promptCacheReusedTokens", 0)
            else -> cacheReuse.optInt("reusedTokens", 0)
        }
        return JSONObject()
            .put("backend", nativeStats.optString("backend"))
            .put("backendType", nativeStats.optString("backendType"))
            .put("backendDevices", nativeStats.opt("backendDevices") ?: JSONArray())
            .put("promptCacheHit", promptCacheHit)
            .put("reusedTokens", reusedTokens)
            .put("promptCache", JSONObject(promptCache.toString()))
            .put("cacheReuse", JSONObject(cacheReuse.toString()))
            .put("kvHistoryBefore", cacheValue("kvHistoryBefore", "promptCacheKvHistoryBefore"))
            .put("tokenHistoryBefore", cacheValue("tokenHistoryBefore", "promptCacheTokenHistoryBefore"))
            .put(
                "tokenHistoryAfterPrefill",
                cacheValue("tokenHistoryAfterPrefill", "promptCacheTokenHistoryAfterPrefill")
            )
            .put("prefixExtended", cacheValue("prefixExtended", "promptCachePrefixExtended"))
            .put("committed", cacheValue("committed", "promptCacheCommitted"))
            .put("promptCacheState", cacheValue("state", "promptCacheState"))
            .put("promptCacheReason", cacheValue("reason", "promptCacheReason"))
            .put("promptTokens", nativeStats.optInt("promptTokens", 0))
            .put("prefillTokens", nativeStats.optInt("prefillTokens", 0))
            .put("prefillMs", nativeStats.optLong("prefillMs", 0L))
            .put("decodeTps", nativeStats.optDouble("decodeTps", 0.0))
            .put("ttftMs", nativeStats.optLong("ttftMs", 0L))
            .put("decodeMs", nativeStats.optLong("decodeMs", 0L))
            .put("completionTokens", nativeStats.optInt("completionTokens", 0))
            .put("generationStopReason", nativeStats.optString("generationStopReason"))
            .put("textSha256", generation.optString("textSha256"))
            .apply {
                // Preserve JSON null for unavailable diagnostics; measured zero is valid evidence.
                listOf(
                    "logitsCount", "logitsFiniteCount", "logitsNonFiniteCount", "logitsAllFinite",
                    "logitsMin", "logitsMax", "logitsDiagnostics", "firstLogits", "samplerConfig", "promptCacheReuse",
                    "effectiveConfigJson", "loadedConfigJson", "lastConfigJson",
                    "mnnDebugGeneratedTokenIds"
                ).forEach { key -> put(key, nativeStats.opt(key) ?: JSONObject.NULL) }
            }
    }

    private fun mnnCacheMessageTranscriptSha256(messages: List<ChatMessage>): String =
        localChatSmokeTextSha256(
            buildString {
                messages.forEach { message ->
                    append(message.role.name.lowercase())
                        .append('\u0000')
                        .append(message.content)
                        .append('\u0001')
                }
            }
        )

    private fun mnnCacheGenerationSucceeded(generation: JSONObject): Boolean =
        generation.optBoolean("doneSeen", false) &&
            generation.optString("text").isNotBlank() &&
            (!generation.has("error") || generation.isNull("error") ||
                generation.optString("error").isBlank())

    private suspend fun cancellationSmoke(
        engine: McaInferenceService,
        nCtx: Int,
        nThreads: Int,
        maxTokens: Int,
        imagePath: String?,
        prompt: String,
        systemPrompt: String,
        sampling: LocalChatSmokeSampling,
        advancedJson: String
    ): JSONObject {
        val text = StringBuilder()
        var doneSeen = false
        var flowCompleted = false
        var flowError: String? = null
        var stopCallAttempted = false
        var stopCallSucceeded = false
        var stopCallError: String? = null
        var terminalBeforeStop: String? = null
        var postStopTerminal: String? = null
        var timedOut = false
        var preStopStats: JSONObject? = null
        var requestInFlightAtStop = false
        val stateLock = Any()
        var phase = LocalChatSmokeCancellationPhase.COLLECTING
        var pendingTerminalDuringStopCall: String? = null
        var pendingTerminalAtMonotonicNs: Long? = null
        var stopAttemptRequestInFlight = false
        var preStopStatsCapturedAtMonotonicNs: Long? = null
        var stopAttemptedAtMonotonicNs: Long? = null
        var stopAcceptedAtMonotonicNs: Long? = null
        var terminalAtMonotonicNs: Long? = null

        fun recordTerminal(kind: String) {
            synchronized(stateLock) {
                val now = System.nanoTime()
                when (phase) {
                    LocalChatSmokeCancellationPhase.COLLECTING -> {
                        terminalBeforeStop = kind
                        terminalAtMonotonicNs = now
                        phase = LocalChatSmokeCancellationPhase.TERMINATED_BEFORE_STOP
                    }
                    LocalChatSmokeCancellationPhase.STOP_CALL_IN_PROGRESS -> {
                        if (pendingTerminalDuringStopCall == null) {
                            pendingTerminalDuringStopCall = kind
                            pendingTerminalAtMonotonicNs = now
                        }
                    }
                    LocalChatSmokeCancellationPhase.STOP_ACCEPTED -> {
                        postStopTerminal = kind
                        terminalAtMonotonicNs = now
                        phase = LocalChatSmokeCancellationPhase.TERMINATED_AFTER_STOP
                    }
                    LocalChatSmokeCancellationPhase.TERMINATED_BEFORE_STOP,
                    LocalChatSmokeCancellationPhase.TERMINATED_AFTER_STOP -> Unit
                }
            }
        }

        fun beginStopAttempt(stats: JSONObject?, requestInFlight: Boolean): Boolean =
            synchronized(stateLock) {
                if (phase != LocalChatSmokeCancellationPhase.COLLECTING) {
                    false
                } else {
                    val now = System.nanoTime()
                    phase = LocalChatSmokeCancellationPhase.STOP_CALL_IN_PROGRESS
                    stopCallAttempted = true
                    if (stopAttemptedAtMonotonicNs == null) {
                        stopAttemptedAtMonotonicNs = now
                    }
                    preStopStats = stats?.let { JSONObject(it.toString()) }
                    preStopStatsCapturedAtMonotonicNs = now
                    stopAttemptRequestInFlight = requestInFlight
                    pendingTerminalDuringStopCall = null
                    pendingTerminalAtMonotonicNs = null
                    true
                }
            }

        fun finishStopAttempt(accepted: Boolean, error: Throwable?) {
            synchronized(stateLock) {
                if (phase != LocalChatSmokeCancellationPhase.STOP_CALL_IN_PROGRESS) return@synchronized
                if (error != null) {
                    stopCallError = error.message ?: error::class.java.simpleName
                }
                if (accepted) {
                    stopCallSucceeded = true
                    requestInFlightAtStop = stopAttemptRequestInFlight
                    stopAcceptedAtMonotonicNs = System.nanoTime()
                    val pendingTerminal = pendingTerminalDuringStopCall
                    if (pendingTerminal == null) {
                        phase = LocalChatSmokeCancellationPhase.STOP_ACCEPTED
                    } else {
                        postStopTerminal = pendingTerminal
                        terminalAtMonotonicNs = pendingTerminalAtMonotonicNs
                        phase = LocalChatSmokeCancellationPhase.TERMINATED_AFTER_STOP
                    }
                } else {
                    requestInFlightAtStop = false
                    val pendingTerminal = pendingTerminalDuringStopCall
                    if (pendingTerminal == null) {
                        phase = LocalChatSmokeCancellationPhase.COLLECTING
                    } else {
                        terminalBeforeStop = pendingTerminal
                        terminalAtMonotonicNs = pendingTerminalAtMonotonicNs
                        phase = LocalChatSmokeCancellationPhase.TERMINATED_BEFORE_STOP
                    }
                }
                pendingTerminalDuringStopCall = null
                pendingTerminalAtMonotonicNs = null
            }
        }

        val params = localChatSmokeGenerationParams(
            nCtx = nCtx,
            nThreads = nThreads,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            sampling = sampling,
            advancedJson = advancedJson
        )
        try {
            withTimeout(localChatSmokeGenerationTimeoutMs(prompt.length, nCtx, maxTokens)) {
                coroutineScope {
                    val collection = async {
                        try {
                            engine.streamChat(
                                ChatRequest(
                                    messages = listOf(smokeUserMessage(imagePath, prompt)),
                                    params = params
                                )
                            ).collect { event ->
                                when (event) {
                                    is GenerateEvent.Phase -> Unit
                                    is GenerateEvent.Persist -> Unit
                                    is GenerateEvent.Chunk -> synchronized(stateLock) {
                                        text.append(event.text)
                                    }
                                    is GenerateEvent.Done -> {
                                        synchronized(stateLock) { doneSeen = true }
                                        recordTerminal("done")
                                    }
                                    is GenerateEvent.Error -> {
                                        synchronized(stateLock) { flowError = event.message }
                                        recordTerminal("error")
                                    }
                                }
                            }
                            synchronized(stateLock) { flowCompleted = true }
                            recordTerminal("flow_completed")
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            synchronized(stateLock) {
                                flowError = error.message ?: error::class.java.simpleName
                            }
                            recordTerminal("exception")
                        }
                    }

                    while (collection.isActive) {
                        val terminalSeen = synchronized(stateLock) {
                            phase == LocalChatSmokeCancellationPhase.TERMINATED_BEFORE_STOP ||
                                phase == LocalChatSmokeCancellationPhase.TERMINATED_AFTER_STOP
                        }
                        if (terminalSeen) break
                        val stats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrNull()
                        if (!beginStopAttempt(stats, collection.isActive)) break
                        val stopResult = try {
                            Result.success(engine.stopGenerationIfActive())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        val accepted = stopResult.getOrDefault(false)
                        val stopError = stopResult.exceptionOrNull()
                        finishStopAttempt(accepted, stopError)
                        if (accepted) break
                        if (stopError != null) {
                            recordTerminal("stop_call_failed")
                            collection.cancel()
                            break
                        }
                        delay(10L)
                    }
                    collection.join()
                }
            }
        } catch (error: Throwable) {
            synchronized(stateLock) {
                timedOut = error is TimeoutCancellationException
                flowError = error.message ?: error::class.java.simpleName
            }
            finishStopAttempt(accepted = false, error = error)
            recordTerminal(if (timedOut) "timeout" else "exception")
        }
        val nativeStats = JSONObject(engine.nativeStatsJson())
        return synchronized(stateLock) {
            localChatSmokeCancellationRequestResult(
                visibleText = text.toString(),
                imagePath = imagePath,
                doneSeen = doneSeen,
                flowCompleted = flowCompleted,
                error = flowError,
                stopCallAttempted = stopCallAttempted,
                stopCallSucceeded = stopCallSucceeded,
                stopCallError = stopCallError,
                terminalBeforeStop = terminalBeforeStop,
                postStopTerminal = postStopTerminal,
                timedOut = timedOut,
                requestedMaxTokens = maxTokens,
                preStopStats = preStopStats,
                requestInFlightAtStop = requestInFlightAtStop,
                cancellationPhase = phase.name.lowercase(),
                preStopStatsCapturedAtMonotonicNs = preStopStatsCapturedAtMonotonicNs,
                stopAttemptedAtMonotonicNs = stopAttemptedAtMonotonicNs,
                stopAcceptedAtMonotonicNs = stopAcceptedAtMonotonicNs,
                terminalAtMonotonicNs = terminalAtMonotonicNs,
                nativeStats = nativeStats
            )
        }
    }

    private fun smokeUserMessage(imagePath: String?, prompt: String): ChatMessage =
        if (imagePath.isNullOrBlank()) {
            ChatMessage(Role.USER, prompt)
        } else {
            ChatMessage(
                role = Role.USER,
                content = prompt,
                imageAttachments = listOf(
                    ChatImageAttachment(
                        name = File(imagePath).name.ifBlank { "smoke-image.jpg" },
                        uriString = imagePath,
                        mimeType = imageMimeType(imagePath)
                    )
                )
            )
        }

    private suspend fun apiHiddenReasoningGuardSmoke(): JSONObject {
        val previousStreamProvider = LocalApiRuntime.streamChatProvider
        val previousContextProvider = LocalApiRuntime.streamChatWithContextProvider
        val previousStopProvider = LocalApiRuntime.stopGenerationProvider
        val stopCalls = AtomicInteger(0)
        val port = freePort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
        return try {
            LocalApiRuntime.streamChatWithContextProvider = null
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Chunk(
                        text = "",
                        stats = com.muyuchat.core.engine.RuntimeStats(completionTokens = 128),
                        hiddenReasoning = true
                    ),
                    GenerateEvent.Chunk(
                        text = "visible answer",
                        stats = com.muyuchat.core.engine.RuntimeStats(completionTokens = 130)
                    )
                )
            }
            LocalApiRuntime.stopGenerationProvider = { stopCalls.incrementAndGet() }
            server.start()
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"hide_reasoning":true}"""
            val response = rawHttp(port, chatRequest(body))
            require(response.httpStatusLine() == "HTTP/1.1 200 OK") {
                "guard API status failed: ${response.take(2048)}"
            }
            require(response.contains("visible answer")) { "guard API did not emit visible answer" }
            require(response.contains("data: [DONE]")) { "guard API did not emit [DONE]" }
            require(stopCalls.get() == 0) { "guard API unexpectedly called stopGeneration" }
            JSONObject()
                .put("visibleSeen", true)
                .put("doneSeen", true)
                .put("stopCalls", stopCalls.get())
        } finally {
            server.shutdown()
            LocalApiRuntime.streamChatProvider = previousStreamProvider
            LocalApiRuntime.streamChatWithContextProvider = previousContextProvider
            LocalApiRuntime.stopGenerationProvider = previousStopProvider
        }
    }

    private suspend fun apiEngineStreamSmoke(
        nCtx: Int,
        maxTokens: Int,
        imagePath: String?,
        prompt: String,
        systemPrompt: String,
        sampling: LocalChatSmokeSampling,
        advancedJson: String,
        expectGenerationError: Boolean = false
    ): JSONObject {
        val port = freePort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
        return try {
            server.start()
            val userContent = if (imagePath.isNullOrBlank()) {
                prompt
            } else {
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "text")
                            .put("text", prompt)
                    )
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject()
                                    .put("url", imagePath.toApiImageUrl())
                            )
                    )
            }
            val body = JSONObject()
                .put("model", "mca-local")
                .put("stream", true)
                .put("hide_reasoning", true)
                .put("reasoning_mode", "off")
                .put("system_prompt", systemPrompt)
                .put("max_tokens", maxTokens)
                // n_ctx is load-bound. The local API must use the context of
                // the already-loaded model; sending it here is intentionally
                // rejected as a parameter_scope_conflict.
                .put("temperature", sampling.temperature)
                .put("top_k", sampling.topK)
                .put("top_p", sampling.topP)
                .put("min_p", sampling.minP)
                .put("repeat_penalty", sampling.repeatPenalty)
                .put("presence_penalty", sampling.presencePenalty)
                .put("frequency_penalty", sampling.frequencyPenalty)
                .put("seed", sampling.seed)
                // Native advanced_json belongs to the authorized runtime
                // profile and is intentionally rejected on the chat route.
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userContent)
                    )
                )
                .toString()
            val timeoutMs = localChatSmokeGenerationTimeoutMs(prompt.length, nCtx, maxTokens)
            val response = withTimeout(timeoutMs) {
                rawHttp(port, chatRequest(body), timeoutMs.toInt())
            }
            require(response.httpStatusLine() == "HTTP/1.1 200 OK") {
                "engine API status failed: ${response.lineSequence().firstOrNull().orEmpty()}"
            }
            require(response.contains("data: [DONE]")) { "engine API did not emit [DONE]" }
            val errorSeen = response.contains("generation_failed") ||
                response.contains("unsupported_local_api_vision") ||
                response.contains(""""error"""")
            val visibleText = extractVisibleChatCompletionText(response)
            val errorFrames = response.lineSequence()
                .map(String::trim)
                .filter { line ->
                    line.startsWith("data:") && (
                        line.contains("generation_failed") ||
                            line.contains("generation_empty_visible_output") ||
                            line.contains("\"error\"")
                    )
                }
                .take(2)
                .joinToString(" | ")
                .take(1_024)
            if (expectGenerationError) {
                require(errorSeen) { "engine API did not emit expected generation error" }
            } else {
                require(!errorSeen) {
                    "engine API emitted unexpected error: ${errorFrames.ifBlank { "no error frame captured" }}"
                }
                require(visibleText.isNotBlank()) { "engine API did not emit visible content" }
            }
            JSONObject()
                .put("visibleSeen", !expectGenerationError)
                .put("visibleChars", visibleText.length)
                .put("text", visibleText)
                .put("textPreview", visibleText.take(120))
                .put("textSha256", localChatSmokeTextSha256(visibleText))
                .put("errorSeen", errorSeen)
                .put("doneSeen", true)
                .put("imagePath", imagePath)
                .put("responseBytes", response.toByteArray(Charsets.UTF_8).size)
        } finally {
            server.shutdown()
        }
    }

    private fun chatRequest(body: String): String =
        "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
            body

    private fun String.httpStatusLine(): String =
        lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("HTTP/", ignoreCase = true) }
            .orEmpty()

    private fun rawHttp(port: Int, request: String, readTimeoutMs: Int = 15_000): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun freePort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun String?.toLocalRuntime(default: LocalChatRuntime): LocalChatRuntime =
        when (this?.lowercase()) {
            "mnn", "mnn_cpu", "mnn-llm" -> LocalChatRuntime.MNN_CPU
            "gguf", "llama", "llama_cpp", "llama.cpp" -> LocalChatRuntime.LLAMA_CPP
            "geniex_llama_cpp", "geniex_gguf", "geniex_htp" -> LocalChatRuntime.GENIEX_LLAMA_CPP
            "geniex", "geniex_qairt", "qairt", "qnn", "qnn_htp" -> LocalChatRuntime.GENIEX_QAIRT
            "litert_lm", "litertlm", "litert-lm" -> LocalChatRuntime.LITERT_LM
            else -> default
        }

    private fun defaultForPath(path: String): LocalChatRuntime =
        if (File(path).isDirectory || path.endsWith(".mnn", ignoreCase = true)) {
            LocalChatRuntime.MNN_CPU
        } else if (path.endsWith(".litertlm", ignoreCase = true)) {
            LocalChatRuntime.LITERT_LM
        } else {
            LocalChatRuntime.LLAMA_CPP
        }

    private fun imageMimeType(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }

    private fun String.toApiImageUrl(): String =
        if (
            startsWith("data:", ignoreCase = true) ||
            startsWith("file:", ignoreCase = true) ||
            startsWith("http://", ignoreCase = true) ||
            startsWith("https://", ignoreCase = true)
        ) {
            this
        } else {
            File(this).toURI().toString()
        }

    private fun ChatModelRuntime.toLocalRuntime(): LocalChatRuntime =
        when (this) {
            ChatModelRuntime.MNN -> LocalChatRuntime.MNN_CPU
            ChatModelRuntime.LLAMA_CPP -> LocalChatRuntime.LLAMA_CPP
            ChatModelRuntime.GENIEX_QAIRT -> LocalChatRuntime.GENIEX_QAIRT
            ChatModelRuntime.LITERT_LM -> LocalChatRuntime.LITERT_LM
        }

    private data class SmokeTarget(
        val id: String,
        val displayName: String,
        val path: String,
        val runtime: LocalChatRuntime,
        val visionProjectorPath: String?,
        val qairtBundleSha256: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("displayName", displayName)
            .put("path", path)
            .put("runtime", runtime.backendId)
            .put("visionProjectorPath", visionProjectorPath)
            .put("qairtBundleSha256", qairtBundleSha256)
    }
}
