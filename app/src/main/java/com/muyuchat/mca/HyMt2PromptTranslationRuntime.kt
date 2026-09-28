package com.muyuchat.mca

import android.content.Context
import android.content.Intent
import android.os.Process
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
            appContext, LocalChatRuntime.LLAMA_CPP,
            OfflineTranslationWorkerService::class.java, "offline_translation"
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
                        unloadFailure = runCatching { runner.unloadModel() }.exceptionOrNull()
                    }
                } finally {
                    runner.close()
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
            try {
                val verified = OfflinePromptTranslationBundleVerifier.requireVerified(bundle.rootDirectory)
                check(verified.identity.fingerprint == bundle.identity.fingerprint) { "Translation package changed before model load." }
                check(!cancelled.get()) { "Translation was cancelled before load." }
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
                loadStarted.set(true)
                val loaded = runner.loadModel(verified.modelFile.absolutePath, params.toString())
                check(loaded == 0) { "Hy-MT2 native load failed: code=$loaded, stats=${runner.getRuntimeStatsJson()}" }
                val positive = translateBranch(runner, params, request.sourceText, request.maxOutputChars, cancelled)
                val negative = if (request.negativePrompt.isBlank()) null else {
                    runner.invalidateConversationContext()
                    translateBranch(runner, params, request.negativePrompt, request.maxOutputChars, cancelled)
                }
                OfflinePromptTranslationRuntimeOutcome.Translated(verified.createResult(request, positive, negative))
            } catch (error: Throwable) {
                OfflinePromptTranslationRuntimeOutcome.Failed(error.message ?: "Hy-MT2 native translation failed.")
            }
        }
    }

    private fun translateBranch(
        runner: RemoteLocalChatRunner,
        params: JSONObject,
        source: String,
        maxChars: Int,
        cancelled: AtomicBoolean
    ): String {
        check(!cancelled.get()) { "Translation was cancelled." }
        val protected = extractOfflinePromptProtectedTokens(source)
        val instruction = "Translate the following text into English. Only output the translated result without any additional explanation. " +
            "Preserve all control syntax, numeric values, delimiters, and the following exact fragments in their original positions: " +
            protected.joinToString(" | ") + "\n\n" + source
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", instruction))
        val began = runner.beginCompletion(messages.toString(), params.toString())
        check(began == 0) { "Hy-MT2 prefill failed: code=$began, stats=${runner.getRuntimeStatsJson()}" }
        val output = StringBuilder()
        while (!cancelled.get()) {
            val chunk = runner.generateNextChunk() ?: break
            check(output.length.toLong() + chunk.length <= maxChars) { "Hy-MT2 output exceeded the requested limit." }
            output.append(chunk)
        }
        check(!cancelled.get()) { "Translation was cancelled." }
        val stats = JSONObject(runner.getRuntimeStatsJson())
        check(stats.optString("lastError").isBlank()) { "Hy-MT2 decode failed: ${stats.optString("lastError")}" }
        check(!stats.optBoolean("generationActive", false) && !stats.optBoolean("stopRequested", false) &&
            stats.optString("generationStopReason") in setOf("stop_token", "normal_finished")) {
            "Hy-MT2 did not complete the translation: ${stats.optString("generationStopReason")}"
        }
        check(stats.optInt("completionTokens", 0) > 0 && output.isNotBlank()) { "Hy-MT2 produced no native translation output." }
        return output.toString().trim()
    }
}
