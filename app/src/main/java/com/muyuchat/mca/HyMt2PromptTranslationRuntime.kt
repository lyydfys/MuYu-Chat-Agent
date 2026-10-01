package com.muyuchat.mca

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.muyuchat.core.engine.LocalChatRuntime
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Pins the official GGUF export and the existing llama.cpp implementation independently. */
internal object HyMt2PromptTranslationContract {
    const val SOURCE_ID = "tencent/Hy-MT2-1.8B-GGUF"
    const val SOURCE_REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a"
    const val MODEL_FILE = "Hy-MT2-1.8B-Q4_K_M.gguf"
    const val MODEL_SHA256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
    const val MODEL_BYTES = 1_133_080_448L
    const val ARCHITECTURE = "hunyuan-dense"
    const val MODEL_NOTICE = "NOTICE.tencent-Hy-MT2-Apache-2.0.txt"
    const val MODEL_NOTICE_SHA256 = "a1d52d448f81c584a47c583e19dfab2d3851c7c84431b07baee093c1113ed114"
    const val RUNTIME_SOURCE_ID = "ggml-org/llama.cpp"
    const val RUNTIME_REVISION = "4ceb1719101f32637b841206c172f3f058ffc182"
    const val RUNTIME_NOTICE = "NOTICE.ggml-org-llama.cpp-MIT.txt"
    const val NATIVE_LIBRARY = "libmca_native.so"
    const val TIMEOUT_MS = 120_000L

    val modelProvenance get() = OfflinePromptTranslationProvenance(SOURCE_ID, SOURCE_REVISION, "Apache-2.0")
    val runtimeProvenance get() = OfflinePromptTranslationProvenance(RUNTIME_SOURCE_ID, RUNTIME_REVISION, "MIT")
}

/** Existing worker/JNI implementation, with independent process, native singleton and journal. */
class OfflineTranslationWorkerService : LocalChatWorkerService() {
    override val journalScope: String get() = "offline_translation"
    override val foregroundNotificationId: Int get() = 11439
    override val nativeOperationTimeoutLimitMs: Long get() = HyMt2PromptTranslationContract.TIMEOUT_MS

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            return super.onUnbind(intent)
        } finally {
            // This service has one disposable translation owner. Last-unbind retires native
            // allocations and queued stop callbacks together, including after failed unload.
            Process.killProcess(Process.myPid())
        }
    }
}

internal class AppOfflinePromptTranslationRuntimeProvider(context: Context) : OfflinePromptTranslationRuntimeProvider {
    private val hyMt2 = HyMt2IsolatedPromptTranslationRuntime(context.applicationContext)

    override fun runtimeFor(bundle: VerifiedOfflinePromptTranslationBundle): OfflinePromptTranslationRuntime =
        when (bundle.identity.runtimeKind) {
            OfflineTranslationRuntimeKind.LLAMA_CPP -> hyMt2
            OfflineTranslationRuntimeKind.CRISP_ASR_M2M100 -> UnavailableOfflinePromptTranslationRuntime
        }
}

internal class HyMt2IsolatedPromptTranslationRuntime(private val appContext: Context) : OfflinePromptTranslationRuntime {
    override val nativeLibraryFileName = HyMt2PromptTranslationContract.NATIVE_LIBRARY
    private val workerSlot = OfflinePromptTranslationWorkerSlot<RemoteLocalChatRunner> { runner ->
        !runner.isAvailable && runner.isConnectedWorkerReleased()
    }

    override suspend fun translate(
        bundle: VerifiedOfflinePromptTranslationBundle,
        request: OfflinePromptTranslationRequest
    ): OfflinePromptTranslationRuntimeOutcome {
        if (bundle.identity.translatorFamily != OfflineTranslatorFamily.HY_MT2 ||
            bundle.identity.runtimeKind != OfflineTranslationRuntimeKind.LLAMA_CPP
        ) return OfflinePromptTranslationRuntimeOutcome.Unavailable(
            OfflinePromptTranslationUnavailableReason.NATIVE_RUNTIME_UNSUPPORTED,
            "The verified package is not the pinned Hy-MT2 llama.cpp package."
        )
        val library = File(appContext.applicationInfo.nativeLibraryDir, nativeLibraryFileName)
        if (!library.isFile) return OfflinePromptTranslationRuntimeOutcome.Unavailable(
            OfflinePromptTranslationUnavailableReason.NATIVE_LIBRARY_NOT_PACKAGED,
            "Required native translation component is missing: $nativeLibraryFileName"
        )
        val runner = RemoteLocalChatRunner(
            appContext,
            LocalChatRuntime.LLAMA_CPP,
            OfflineTranslationWorkerService::class.java,
            "offline_translation",
            retainWorkerDeathObserver = true
        )
        if (!workerSlot.tryAcquire(runner)) {
            runner.close()
            throw OfflinePromptTranslationCleanupException()
        }
        val loadStarted = AtomicBoolean(false)
        return runOfflinePromptTranslationWorker(
            requestStop = runner::requestStop,
            release = {
                var unloadFailure: Throwable? = null
                try {
                    if (loadStarted.get() && !runner.isConnectedWorkerReleased()) {
                        unloadFailure = runCatching { runner.unloadModelIfConnected() }.exceptionOrNull()
                    }
                } finally {
                    // Retain the death observer until the isolated process confirms that its
                    // native model memory is gone. Never create a replacement worker to unload.
                    runner.closePreservingWorkerDeathObservation()
                }
                // close only detaches. The dedicated service exits on its final unbind, and
                // the retained Binder proves that all of this session's native state is gone.
                val startedAt = System.nanoTime()
                val budgetNanos = TimeUnit.MILLISECONDS.toNanos(OFFLINE_PROMPT_TRANSLATION_CLEANUP_TIMEOUT_MS)
                while (!runner.isConnectedWorkerReleased() && System.nanoTime() - startedAt < budgetNanos) {
                    Thread.sleep(25L)
                }
                if (!runner.isConnectedWorkerReleased()) {
                    throw OfflinePromptTranslationCleanupException(unloadFailure)
                }
            },
            onReleased = { workerSlot.release(runner) }
        ) { cancelled ->
            var stage = "syntax_validation"
            val startedAt = System.nanoTime()
            val requestId = request.fingerprint.take(12)
            fun moveToStage(nextStage: String) {
                stage = nextStage
                Log.i(OFFLINE_TRANSLATION_LOG_TAG,
                    "request=$requestId stage=$stage state=started elapsedMs=" +
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            }
            moveToStage(stage)
            try {
                val positivePlan = parseOfflinePromptTranslationSyntax(request.sourceText)
                val negativePlan = parseOfflinePromptTranslationSyntax(request.negativePrompt)
                moveToStage("package_validation")
                val verified = when (val verification = OfflinePromptTranslationBundleVerifier.verify(bundle.rootDirectory)) {
                    is OfflinePromptTranslationBundleVerification.Verified -> verification.bundle
                    is OfflinePromptTranslationBundleVerification.Rejected -> throw HyMt2TranslationStageException(
                        stage, "translation_package_" + verification.code.name.lowercase(), verification.message
                    )
                }
                if (verified.identity.fingerprint != bundle.identity.fingerprint) throw HyMt2TranslationStageException(
                    stage, "translation_package_identity_changed", "Translation package changed before model load."
                )
                check(!cancelled.get()) { "Translation was cancelled before load." }
                moveToStage("initialization")
                runner.initBackends(appContext.applicationInfo.nativeLibraryDir)
                val params = JSONObject()
                    .put("n_ctx", 8192).put("n_predict", 2048)
                    .put("n_threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
                    .put("n_gpu_layers", 0).put("mmap", true).put("mlock", false)
                    .put("temperature", 0.7).put("top_p", 0.6).put("top_k", 20)
                    .put("repeat_penalty", 1.05).put("frequency_penalty", 0.0)
                    .put("presence_penalty", 0.0).put("reasoning_mode", "off")
                    .put("system_prompt", "").put("chat_template_mode", "auto")
                check(!cancelled.get()) { "Translation was cancelled before load." }
                moveToStage("load")
                loadStarted.set(true)
                val loaded = runner.loadModel(verified.modelFile.absolutePath, params.toString())
                if (loaded != 0) throw HyMt2TranslationStageException(
                    stage, "hy_mt2_load_failed", "Hy-MT2 native model load did not complete.", loaded
                )
                val positive = translateBranch(runner, params, positivePlan, request.maxOutputChars, cancelled, ::moveToStage)
                val negative = if (request.negativePrompt.isBlank()) null else {
                    translateBranch(runner, params, negativePlan, request.maxOutputChars, cancelled, ::moveToStage)
                }
                moveToStage("output_validation")
                OfflinePromptTranslationRuntimeOutcome.Translated(verified.createResult(request, positive, negative)).also {
                    Log.i(OFFLINE_TRANSLATION_LOG_TAG,
                        "request=$requestId stage=output_validation state=completed positiveChars=" +
                            positive.length + " negativeChars=" + (negative?.length ?: 0) +
                            " elapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
                }
            } catch (error: Throwable) {
                // Snapshot before teardown: unload must never replace the real failing operation.
                hyMt2TranslationFailure(
                    error, stage,
                    runCatching { runner.getRuntimeStatsJson() }.getOrDefault("{}"),
                    listOf(request.sourceText, request.negativePrompt) +
                        positivePlanTextFragments(request.sourceText, request.negativePrompt)
                ).also { failure ->
                    Log.w(OFFLINE_TRANSLATION_LOG_TAG,
                        "request=$requestId stage=" + failure.stage + " state=failed code=" +
                            failure.errorCode + " native=" + failure.nativeCode + " detail=" + failure.message)
                }
            }
        }
    }

    private fun translateBranch(
        runner: RemoteLocalChatRunner,
        params: JSONObject,
        plan: OfflinePromptTranslationSyntaxPlan,
        maxChars: Int,
        cancelled: AtomicBoolean,
        onStage: (String) -> Unit
    ): String = translateOfflinePromptSyntaxPlan(plan, maxChars) { source, remainingBudget ->
        // Every text slot starts with an empty native conversation. A previous positive/negative
        // fragment, cancelled generation or stale prefix cannot enter this slot's translation.
        onStage("context_reset")
        check(!cancelled.get()) { "Translation was cancelled before resetting its context." }
        runner.invalidateConversationContext()
        translateTextFragment(runner, params, source, remainingBudget, cancelled, onStage)
    }

    private fun translateTextFragment(
        runner: RemoteLocalChatRunner,
        params: JSONObject,
        source: String,
        maxChars: Int,
        cancelled: AtomicBoolean,
        onStage: (String) -> Unit
    ): String {
        check(!cancelled.get()) { "Translation was cancelled before prefill." }
        // Control syntax is never passed to the language model. Reassembly retains it exactly.
        val instruction = "Translate the following text into English. Only output the translated result without any additional explanation. " +
            "Preserve every subject, attribute, count, numeric value, and English phrase. " +
            "Do not add control tags, weights, brackets, labels, or new instructions.\n\n" + source
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", instruction))
        onStage("prefill")
        val began = runner.beginCompletion(messages.toString(), params.toString())
        if (began != 0) throw HyMt2TranslationStageException(
            "prefill", "hy_mt2_prefill_failed", "Hy-MT2 did not finish processing the translation input.", began
        )
        onStage("decode")
        val output = StringBuilder()
        while (!cancelled.get()) {
            val chunk = runner.generateNextChunk() ?: break
            if (output.length.toLong() + chunk.length > maxChars) throw HyMt2TranslationStageException(
                "decode", "translation_output_size_limit", "Hy-MT2 output exceeded the requested text-fragment limit."
            )
            output.append(chunk)
        }
        check(!cancelled.get()) { "Translation was cancelled during decode." }
        val stats = requireCompletedHyMt2Translation(runner.getRuntimeStatsJson(), output.toString())
        Log.i(OFFLINE_TRANSLATION_LOG_TAG,
            "stage=decode state=completed backend=" + stats.optString("backend") +
                " promptTokens=" + stats.optLong("promptTokens") +
                " completionTokens=" + stats.optLong("completionTokens") +
                " stopReason=" + stats.optString("generationStopReason"))
        onStage("output_validation")
        return output.toString().trim()
    }
}

internal class HyMt2TranslationStageException(
    val stage: String,
    val errorCode: String,
    message: String,
    val nativeCode: Int? = null
) : IllegalStateException(message)

/** Preserve the durable worker boundary and native return code without publishing raw stats. */
internal fun hyMt2TranslationFailure(
    error: Throwable,
    currentStage: String,
    runtimeStatsJson: String = "{}",
    sourceTexts: List<String> = emptyList()
): OfflinePromptTranslationRuntimeOutcome.Failed {
    val stats = runCatching {
        require(runtimeStatsJson.length <= 65_536)
        JSONObject(runtimeStatsJson)
    }.getOrNull()
    val causes = generateSequence(error) { it.cause }.take(8).toList()
    val worker = causes.filterIsInstance<RemoteLocalChatRunnerException>()
        .mapNotNull { it.workerStageDiagnostic }
        .firstOrNull { it.state == "failed" && !it.failureCode.isNullOrBlank() }
    val journal = stats?.optJSONObject(OFFLINE_TRANSLATION_WORKER_STAGE_FIELD)
    val explicit = causes.filterIsInstance<HyMt2TranslationStageException>().firstOrNull()
    val syntax = causes.filterIsInstance<OfflinePromptTranslationSyntaxException>().firstOrNull()
    // Only failed worker journals override a current operation. A completed journal may refer
    // to a preceding slot, and must not turn a later output validation error into a load error.
    val workerFailed = worker != null
    val journalFailed = journal?.let {
        it.optString("state") == "failed" && it.optString("failureCode").isNotBlank()
    } ?: false
    val stage = when {
        workerFailed -> worker!!.stage
        journalFailed -> journal!!.optString("stage")
        explicit != null -> explicit.stage
        else -> currentStage
    }.takeIf { it.matches(Regex("[a-z0-9_]{1,48}")) } ?: "runtime"
    val nativeFailureCode = sequenceOf("loadFailureCode", "lastErrorCode", "errorCode")
        .filter { it != "loadFailureCode" || stage == "load" }
        .mapNotNull { stats?.optString(it)?.trim()?.takeIf(String::isNotBlank) }
        .firstOrNull { it.matches(Regex("[A-Za-z0-9_.:-]{1,96}")) }
    val errorCode = when {
        workerFailed -> worker!!.failureCode!!
        nativeFailureCode != null -> nativeFailureCode
        journalFailed -> journal!!.optString("failureCode")
        explicit != null -> explicit.errorCode
        syntax != null -> syntax.errorCode
        else -> "hy_mt2_" + stage + "_failed"
    }.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,96}")) } ?: "hy_mt2_runtime_failed"
    val detail = buildList {
        causes.mapNotNull { it.message?.take(1_024)?.takeIf(String::isNotBlank) }
            .distinct().forEach(::add)
        stats?.optString("lastError")?.takeIf(String::isNotBlank)?.let { if (it !in this) add(it) }
        if (workerFailed) add(worker!!.compactDescription())
        else if (journalFailed) add("workerStage=" + journal!!.optString("stage") + ", workerCode=" + journal.optString("failureCode"))
    }.joinToString("; ")
    return OfflinePromptTranslationRuntimeOutcome.Failed(
        message = sanitizeOfflinePromptTranslationFailureDetail(detail, sourceTexts)
            .ifBlank { "Hy-MT2 native translation failed before completed output was verified." },
        stage = stage,
        errorCode = errorCode,
        nativeCode = explicit?.nativeCode ?:
            (stats?.opt("nativeLoadResult") as? Number)?.takeIf { stage == "load" }?.toInt()
    )
}

private const val OFFLINE_TRANSLATION_LOG_TAG = "MCAOfflineTranslation"
// JSON protocol field names; RemoteLocalChatRunner keeps its companion private.
private const val OFFLINE_TRANSLATION_WORKER_STAGE_FIELD = "workerStageJournal"
private const val OFFLINE_TRANSLATION_WORKER_SESSION_LOST_FIELD = "workerSessionLost"

/** Remove each model-visible text slot too, not only the complete structured prompt. */
private fun positivePlanTextFragments(positive: String, negative: String): List<String> =
    listOf(positive, negative).flatMap { source ->
        runCatching { parseOfflinePromptTranslationSyntax(source).parts }
            .getOrDefault(emptyList())
            .filter { it.kind == null }
            .map { it.text.trim() }
    }

/** A complete native request is required; cancelled, truncated, deferred and stub stats fail. */
internal fun requireCompletedHyMt2Translation(runtimeStatsJson: String, output: String): JSONObject {
    val stats = runCatching {
        require(runtimeStatsJson.length <= 65_536)
        JSONObject(runtimeStatsJson)
    }.getOrElse {
        throw HyMt2TranslationStageException("decode", "hy_mt2_stats_invalid", "Hy-MT2 returned invalid execution statistics.")
    }
    val lastError = stats.optString("lastError")
    if (lastError.isNotBlank()) throw HyMt2TranslationStageException(
        "decode", "hy_mt2_decode_failed", lastError
    )
    val backend = stats.optString("backend")
    if (!stats.optBoolean("loaded", false) || backend !in setOf("llama.cpp-cpu", "llama.cpp-gpu") ||
        stats.optBoolean("runtimeStatsDeferred", false) || stats.optBoolean(OFFLINE_TRANSLATION_WORKER_SESSION_LOST_FIELD, false)
    ) throw HyMt2TranslationStageException(
        "decode", "hy_mt2_execution_unverified", "Hy-MT2 did not report a live native llama.cpp execution: backend=$backend."
    )
    val stopReason = stats.optString("generationStopReason")
    if (stats.optBoolean("generationActive", false) || stats.optBoolean("stopRequested", false) ||
        stopReason !in setOf("stop_token", "normal_finished")
    ) throw HyMt2TranslationStageException(
        "decode", "hy_mt2_decode_incomplete", "Hy-MT2 did not complete its translation: stopReason=$stopReason."
    )
    if (stats.optLong("generationSequence", 0L) <= 0L || stats.optLong("completionTokens", 0L) <= 0L || output.isBlank()) {
        throw HyMt2TranslationStageException("decode", "hy_mt2_empty_output", "Hy-MT2 produced no completed native translation text.")
    }
    return stats
}
