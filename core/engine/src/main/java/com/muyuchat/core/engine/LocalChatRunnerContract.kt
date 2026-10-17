package com.muyuchat.core.engine

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal class StreamingChunkQueue(capacity: Int = 64) {
    private val chunks = LinkedBlockingQueue<String>(capacity)
    @Volatile private var finished = false
    @Volatile private var cancelled = false
    @Volatile private var error: String? = null

    fun reset() {
        chunks.clear()
        error = null
        cancelled = false
        finished = false
    }

    fun put(chunk: String) {
        while (!cancelled && !finished) {
            if (chunks.offer(chunk, 100, TimeUnit.MILLISECONDS)) return
        }
    }

    fun finish(failure: String? = null) {
        error = failure
        finished = true
    }

    fun cancel() {
        cancelled = true
        chunks.clear()
        finished = true
    }

    fun next(): String? {
        while (true) {
            if (cancelled) return null
            val chunk = chunks.poll(100, TimeUnit.MILLISECONDS)
            if (cancelled) return null
            if (chunk != null) return chunk
            if (finished) {
                error?.let { throw IllegalStateException(it) }
                return null
            }
        }
    }
}

enum class LocalChatRuntime(val backendId: String, val label: String) {
    MNN_CPU("mnn_cpu", "MNN 高速引擎"),
    LLAMA_CPP("llama_cpp", "GGUF 兼容引擎"),
    GENIEX_LLAMA_CPP("geniex_llama_cpp", "GenieX llama.cpp / 骁龙 HTP"),
    GENIEX_QAIRT("geniex_qairt", "GenieX QAIRT NPU"),
    LITERT_LM("litert_lm", "LiteRT-LM 引擎")
}


/** Native-facing paths for one fixed, non-conversational prefix state. */
data class PersistentPrefixCacheRequest(
    val restoreStatePath: String? = null,
    val writeStatePath: String? = null,
    val fixedSystemPrompt: String,
    val fullSessionState: Boolean = false
) {
    init {
        require(restoreStatePath?.isNotBlank() != false) {
            "restoreStatePath must be blank or a non-blank path."
        }
        require(writeStatePath?.isNotBlank() != false) {
            "writeStatePath must be blank or a non-blank path."
        }
        require(fullSessionState || fixedSystemPrompt.isNotBlank()) {
            "fixedSystemPrompt must not be blank for a fixed-prefix request."
        }
        require(restoreStatePath != null || writeStatePath != null) {
            "A persistent prefix request needs a restore or write path."
        }
    }
}

/**
 * Describes whether a disposable worker can restore its last loaded session
 * without requiring an explicit user choice after that worker was reclaimed.
 *
 * Accelerated backends that timed out must not silently retry or switch to CPU:
 * the next backend is a user-facing execution choice, not an implementation
 * detail.
 */
enum class LocalChatSessionRecoveryPolicy {
    AUTOMATIC,
    EXPLICIT_RELOAD_REQUIRED
}

interface LocalChatRunner {
    val runtime: LocalChatRuntime
    val isAvailable: Boolean
    val loadError: Throwable?

    fun initBackends(nativeLibDir: String)
    fun loadModel(modelPath: String, paramsJson: String): Int
    fun unloadModel()

    /**
     * B44: releases the isolated worker process that owned the resident model so the next
     * replacement load allocates into a fresh process. Returns true when there is nothing to
     * recycle (in-process runners and workerless runtimes); an isolated-worker backend returns
     * false only when its worker was asked to recycle and did not exit in time.
     */
    fun recycleWorkerForReplacement(): Boolean = true
    fun beginCompletion(messagesJson: String, paramsJson: String): Int
    /**
     * Starts a text completion with an optional persistent fixed-prefix state.
     * Non-llama runtimes intentionally fall back to their ordinary begin path.
     */
    fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        prefixCache: PersistentPrefixCacheRequest?
    ): Int = beginCompletion(messagesJson, paramsJson)
    /**
     * Exact native prompt-prefill snapshot when the runtime can report one.
     * `null` deliberately means indeterminate rather than an estimated value.
     */
    fun prefillProgress(): TokenProgress? = null
    /**
     * KB/KV cache serialization progress while a state file is written.
     * `null` means no write is currently in flight (or the runtime reports none).
     */
    fun persistProgress(): PersistProgress? = null
    /**
     * Clears the previous request's prefill snapshot before a new native begin.
     * This prevents a completed prior request from being presented as progress
     * for the request that is about to start.
     */
    fun resetPrefillProgress() = Unit
    fun generateNextChunk(): String?
    /** Clears any runtime state derived from editable conversation history. */
    fun invalidateConversationContext() = Unit
    fun requestStop()
    fun requestStopIfActive(): Boolean = false
    /** Java-only lifecycle evidence for an isolated worker's cancellation watchdog. */
    fun isGenerationRunning(): Boolean? = null
    /** False while a native owner may still read request-scoped media files. */
    fun canReleasePreparedInputs(): Boolean = true
    /** Non-blocking process/session loss evidence; implementations must not perform Binder or JNI IO. */
    fun isSessionKnownLost(): Boolean = false
    /**
     * True only when the last [unloadModel] released every native owner this runner created.
     *
     * Returning normally from [unloadModel] is not by itself evidence of a clean release: a native
     * generation call can still be running and a native handle can still be alive. Runners that can
     * be left in that state override this (and keep the state needed for a later retry or worker
     * recovery) so callers never start a second runtime over a surviving owner.
     */
    fun isNativeReleaseConfirmed(): Boolean = true
    /** Prompt-free reason paired with [isNativeReleaseConfirmed] when a release was not confirmed. */
    fun nativeReleaseMessage(): String? = null
    /**
     * Default workers can reload an otherwise identical session after process
     * loss. Remote accelerated timeouts override this to preserve the user's
     * requested backend semantics.
     */
    fun sessionRecoveryPolicy(): LocalChatSessionRecoveryPolicy =
        LocalChatSessionRecoveryPolicy.AUTOMATIC
    /** A prompt-free explanation paired with [sessionRecoveryPolicy], when needed. */
    fun sessionRecoveryMessage(): String? = null
    fun getRuntimeStatsJson(): String
    fun shutdown()
}

object LocalChatRunnerDebug {
    @Volatile
    var stageSink: ((String, JSONObject) -> Unit)? = null

    fun emit(stage: String, details: JSONObject = JSONObject()) {
        runCatching {
            stageSink?.invoke(stage, details)
        }
    }
}


internal const val UNAVAILABLE_RC = -100
