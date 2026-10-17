package com.muyuchat.core.engine

import android.content.Context
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.RuntimeIdValue
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.runtime.BackendRuntimeOwner
import com.muyuchat.core.engine.runtime.MnnChatEnginePort
import com.muyuchat.core.engine.runtime.MnnChatNativeLeaf
import com.muyuchat.core.engine.runtime.MnnChatSessionPort
import com.muyuchat.core.nativebridge.NativeLlamaBridge
import com.muyuchat.core.nativebridge.NativeMnnBridge
import org.json.JSONObject

private const val MNN_CHAT_DEFAULT_SESSION_ID = "mnn-chat-default"
internal class LlamaCppChatRunner(
    private val bridge: NativeLlamaBridge = NativeLlamaBridge()
) : LocalChatRunner {
    @Volatile private var submittedGenerationJson: String? = null
    @Volatile private var nativeReleasePending = false
    @Volatile private var nativeReleaseFailure: String? = null
    override val runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP
    override val isAvailable: Boolean
        get() = NativeLlamaBridge.isAvailable
    override val loadError: Throwable?
        get() = NativeLlamaBridge.loadError

    override fun initBackends(nativeLibDir: String) = bridge.initBackends(nativeLibDir)
    override fun loadModel(modelPath: String, paramsJson: String): Int {
        submittedGenerationJson = null
        return bridge.loadModel(modelPath, paramsJson)
    }
    override fun unloadModel() {
        submittedGenerationJson = null
        try {
            bridge.unloadModel()
            nativeReleasePending = false
            nativeReleaseFailure = null
        } catch (error: Throwable) {
            // A throwing unload is not a clean release: keep the failure observable so the caller
            // refuses to start a second load over an owner that may still be alive.
            nativeReleasePending = true
            nativeReleaseFailure = "llama.cpp unload failed: " +
                (error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName)
            throw error
        }
    }

    override fun isNativeReleaseConfirmed(): Boolean = !nativeReleasePending
    override fun nativeReleaseMessage(): String? = nativeReleaseFailure
    override fun isSessionKnownLost(): Boolean = nativeReleasePending
    override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
        submittedGenerationJson = null
        GenerationParams.fromJson(paramsJson).requireFiniteSamplerValues()
        return bridge.beginCompletion(messagesJson, paramsJson).also { rc ->
            if (rc == 0) submittedGenerationJson = paramsJson
        }
    }
    override fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        prefixCache: PersistentPrefixCacheRequest?
    ): Int {
        if (prefixCache == null) return beginCompletion(messagesJson, paramsJson)
        submittedGenerationJson = null
        GenerationParams.fromJson(paramsJson).requireFiniteSamplerValues()
        return bridge.beginCompletionWithPrefixCache(
            messagesJson = messagesJson,
            paramsJson = paramsJson,
            restoreStatePath = prefixCache.restoreStatePath,
            writeStatePath = prefixCache.writeStatePath,
            fixedSystemPrompt = prefixCache.fixedSystemPrompt,
            fullSessionState = prefixCache.fullSessionState
        ).also { rc -> if (rc == 0) submittedGenerationJson = paramsJson }
    }
    override fun prefillProgress(): TokenProgress? = runCatching {
        val root = JSONObject(bridge.getPrefillProgressJson())
        val total = root.optInt("totalTokens", 0)
        val completed = root.optInt("completedTokens", -1)
        if (total > 0 && completed in 0..total) {
            TokenProgress(completedTokens = completed, totalTokens = total)
        } else {
            null
        }
    }.getOrNull()
    override fun resetPrefillProgress() = bridge.resetPrefillProgress()
    override fun persistProgress(): PersistProgress? = runCatching {
        val root = JSONObject(bridge.getPersistProgressJson())
        val stageRaw = root.optInt("stage", 0)
        val stage = PersistStage.entries.getOrNull(stageRaw) ?: return@runCatching null
        val written = root.optLong("writtenBytes", 0L)
        val total = root.optLong("totalBytes", 0L)
        if (stage == PersistStage.IDLE || total <= 0L) return@runCatching null
        PersistProgress(stage = stage, writtenBytes = written.coerceAtLeast(0L), totalBytes = total)
    }.getOrNull()
    override fun generateNextChunk(): String? = bridge.generateNextChunk()
    override fun invalidateConversationContext() = bridge.invalidateTextContext()
    override fun requestStop() = bridge.requestStop()
    override fun requestStopIfActive(): Boolean = bridge.requestStopIfActive()
    override fun getRuntimeStatsJson(): String {
        val stats = JSONObject(bridge.getRuntimeStatsJson())
        submittedGenerationJson?.let { request ->
            stats.put("parameterApplication", nativeGenerationParameterApplication(runtime, request, stats))
        }
        return stats.toString()
    }
    override fun shutdown() = bridge.shutdown()
}

/**
 * Keeps the explicit GenieX llama.cpp CPU choice on the stable MCA llama.cpp
 * bridge.  GenieX 0.3.12's llama plugin is linked against an older ggml/llama
 * ABI than the current MCA llama.cpp libraries; loading that plugin for a CPU
 * request can therefore crash in decode even though model creation succeeds.
 *
 * This is deliberately a narrow transport choice: only an explicit CPU
 * compute-unit request takes this path.  Hybrid/GPU/NPU requests continue to
 * use GenieX and are never silently downgraded to CPU.
 */
internal class GenieXLlamaCppChatRunner(
    appContext: Context? = null
) : LocalChatRunner {
    override val runtime: LocalChatRuntime = LocalChatRuntime.GENIEX_LLAMA_CPP

    private val genieX = GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
        requestedRuntimeId = RuntimeIdValue.LLAMA_CPP.value.orEmpty(),
        defaultComputeUnit = ComputeUnitValue.HYBRID.value.orEmpty(),
        defaultBackendDevices = "骁龙 HTP + CPU / GenieX llama.cpp",
        appContext = appContext
    )
    private val llamaCpp = LlamaCppChatRunner()

    @Volatile
    private var activeDelegate: LocalChatRunner? = null
    @Volatile
    private var executionPath: String = EXECUTION_PATH_IDLE
    @Volatile
    private var nativeLibDir: String = ""
    @Volatile
    private var lastError: String? = null
    /**
     * True while a previous unload could not prove that its native owner was released.  The
     * delegate and the execution path stay untouched in that state, and the next load is refused
     * instead of creating a second runtime over the surviving owner.
     */
    @Volatile
    private var nativeReleasePending: Boolean = false
    @Volatile
    private var nativeReleaseFailure: String? = null

    override val isAvailable: Boolean
        get() = genieX.isAvailable || llamaCpp.isAvailable

    override val loadError: Throwable?
        get() = activeDelegate?.loadError
            ?: genieX.loadError
            ?: if (!genieX.isAvailable) llamaCpp.loadError else null

    override fun initBackends(nativeLibDir: String) {
        this.nativeLibDir = nativeLibDir.trim()
        // Do not touch NativeLlamaBridge here.  The worker constructs all
        // runtime objects before the user selects one; eagerly loading MCA's
        // llama stack would contaminate a later GenieX QAIRT request.
        genieX.initBackends(this.nativeLibDir)
    }

    override fun loadModel(modelPath: String, paramsJson: String): Int {
        return if (genieXLlamaCppCpuFallbackRequested(paramsJson)) {
            loadWithCpuFallback(modelPath, paramsJson)
        } else {
            loadWithGenieX(modelPath, paramsJson)
        }
    }

    /**
     * Releases the previous owner before a load.  Returns false (and keeps every piece of recovery
     * state) when the release could not be confirmed, so the caller must not create a second
     * runtime over a native owner that is still alive.
     */
    private fun releasePreviousOwnerBeforeLoad(): Boolean {
        unloadModel()
        if (nativeReleasePending) {
            // The failure state stays as it is on purpose: the previous owner, the execution path
            // and the reason are exactly what the recovery path needs.
            return false
        }
        return true
    }

    private fun loadWithCpuFallback(modelPath: String, paramsJson: String): Int {
        if (!releasePreviousOwnerBeforeLoad()) return UNAVAILABLE_RC
        val delegate = llamaCpp
        executionPath = EXECUTION_PATH_CPU_FALLBACK
        activeDelegate = delegate
        lastError = null

        if (!delegate.isAvailable) {
            lastError = "GenieX CPU 请求的安全 llama.cpp 路径不可用：${delegate.loadError?.message.orEmpty()}"
            return UNAVAILABLE_RC
        }
        runCatching { delegate.initBackends(nativeLibDir) }.onFailure { error ->
            lastError = "GenieX CPU 请求初始化 llama.cpp 失败：${describe(error)}"
        }
        if (!lastError.isNullOrBlank()) return UNAVAILABLE_RC

        val result = runCatching { delegate.loadModel(modelPath, paramsJson) }
            .getOrElse { error ->
                lastError = "GenieX CPU 请求的 llama.cpp 加载失败：${describe(error)}"
                UNAVAILABLE_RC
            }
        if (result != 0 && lastError.isNullOrBlank()) {
            lastError = "GenieX CPU 请求的 llama.cpp 加载失败（$result）。"
        }
        return result
    }

    private fun loadWithGenieX(modelPath: String, paramsJson: String): Int {
        if (!releasePreviousOwnerBeforeLoad()) return UNAVAILABLE_RC
        val delegate = genieX
        executionPath = EXECUTION_PATH_GENIEX
        activeDelegate = delegate
        lastError = null
        val result = runCatching { delegate.loadModel(modelPath, paramsJson) }
            .getOrElse { error ->
                lastError = "GenieX llama.cpp 加载失败：${describe(error)}"
                UNAVAILABLE_RC
            }
        if (result != 0 && lastError.isNullOrBlank()) {
            lastError = delegate.loadError?.message
                ?: "GenieX llama.cpp 加载失败（$result）。"
        }
        return result
    }

    override fun unloadModel() {
        val delegate = activeDelegate
        if (delegate == null) {
            // Nothing was ever installed: idle is the accurate state.
            executionPath = EXECUTION_PATH_IDLE
            lastError = null
            nativeReleasePending = false
            nativeReleaseFailure = null
            return
        }
        val failure = runCatching { delegate.unloadModel() }.exceptionOrNull()
        // `unloadModel()` returning normally is not proof of a released owner: the delegate may
        // still be holding a native handle or a generation thread inside a native call.
        val released = failure == null &&
            delegate.isNativeReleaseConfirmed() &&
            !delegate.isSessionKnownLost()
        if (!released) {
            val reason = failure?.let { "GenieX delegate unload failed: ${describe(it)}" }
                ?: delegate.nativeReleaseMessage()
                ?: "GenieX delegate unload did not confirm that the native owner was released."
            // Keep the delegate and the execution path: the next load must not create a second
            // runtime over an owner that is still alive, and the recovery path needs this state.
            nativeReleasePending = true
            nativeReleaseFailure = reason
            lastError = reason
            LocalChatRunnerDebug.emit(
                "geniex_delegate_unload_failed",
                JSONObject()
                    .put("executionPath", executionPath)
                    .put("nativeReleasePending", true)
                    .put("error", reason)
            )
            return
        }
        activeDelegate = null
        executionPath = EXECUTION_PATH_IDLE
        lastError = null
        nativeReleasePending = false
        nativeReleaseFailure = null
    }

    override fun isNativeReleaseConfirmed(): Boolean =
        !nativeReleasePending && (activeDelegate?.isNativeReleaseConfirmed() ?: true)

    override fun nativeReleaseMessage(): String? = nativeReleaseFailure

    override fun isSessionKnownLost(): Boolean =
        nativeReleasePending || activeDelegate?.isSessionKnownLost() == true

    override fun sessionRecoveryPolicy(): LocalChatSessionRecoveryPolicy =
        if (nativeReleasePending) {
            // A native owner survived the unload: never silently reload over it.
            LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED
        } else {
            activeDelegate?.sessionRecoveryPolicy() ?: LocalChatSessionRecoveryPolicy.AUTOMATIC
        }

    override fun sessionRecoveryMessage(): String? =
        nativeReleaseFailure ?: activeDelegate?.sessionRecoveryMessage() ?: lastError

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int =
        activeDelegate?.beginCompletion(messagesJson, paramsJson)
            ?: -4.also { lastError = "GenieX llama.cpp model is not loaded." }

    override fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        prefixCache: PersistentPrefixCacheRequest?
    ): Int = activeDelegate?.beginCompletionWithPrefixCache(messagesJson, paramsJson, prefixCache)
        ?: -4.also { lastError = "GenieX llama.cpp model is not loaded." }

    override fun prefillProgress(): TokenProgress? = activeDelegate?.prefillProgress()

    override fun persistProgress(): PersistProgress? = activeDelegate?.persistProgress()

    override fun resetPrefillProgress() {
        activeDelegate?.resetPrefillProgress()
    }

    override fun generateNextChunk(): String? = activeDelegate?.generateNextChunk()

    override fun invalidateConversationContext() {
        activeDelegate?.invalidateConversationContext()
    }

    override fun requestStop() {
        activeDelegate?.requestStop()
    }

    override fun requestStopIfActive(): Boolean = activeDelegate?.requestStopIfActive() == true

    override fun getRuntimeStatsJson(): String {
        val delegate = activeDelegate
        val root = runCatching {
            delegate?.getRuntimeStatsJson()?.let(::JSONObject)
        }.getOrNull() ?: JSONObject()
        val delegateBackend = root.optString("backend").takeIf { it.isNotBlank() }
        if (delegateBackend != null) root.put("delegateBackend", delegateBackend)
        if (!root.has("loaded")) root.put("loaded", false)
        if (!root.has("runnerReady")) root.put("runnerReady", isAvailable)
        root.put("backend", runtime.backendId)
        root.put("requestedRuntime", runtime.backendId)
        root.put("executionPath", executionPath)
        root.put("cpuFallbackActive", executionPath == EXECUTION_PATH_CPU_FALLBACK)
        root.put("nativeReleasePending", nativeReleasePending)
        nativeReleaseFailure?.takeIf { it.isNotBlank() }?.let { root.put("nativeReleaseFailure", it) }
        if (nativeReleasePending) {
            // Existing isolated-worker recovery channel (the service reads this stats field when it
            // decides whether the session must be reclaimed/reloaded).
            root.put("workerSessionLost", true)
        }
        lastError?.takeIf { it.isNotBlank() }?.let { root.put("lastError", it) }
        return root.toString()
    }

    override fun shutdown() = unloadModel()

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

    private companion object {
        private const val EXECUTION_PATH_IDLE = "idle"
        private const val EXECUTION_PATH_CPU_FALLBACK = "llama_cpp_cpu_fallback"
        private const val EXECUTION_PATH_GENIEX = "geniex_llama_cpp"
        private const val UNAVAILABLE_RC = -100
    }
}

/** Returns true only for an explicit CPU request on the GenieX llama runtime. */
internal fun genieXLlamaCppCpuFallbackRequested(paramsJson: String): Boolean {
    val parsed = LoadParams.fromJson(paramsJson).geniexComputeUnit
        ?.trim()
        ?.lowercase()
    if (parsed == ComputeUnitValue.CPU.value.orEmpty().lowercase() || parsed == "cpu_only") {
        return true
    }
    // Parameter profiles may retain the value under advanced_json.  Treat it
    // as an equivalent explicit request, but never infer CPU from a missing or
    // unknown value (hybrid/GPU/NPU must stay on GenieX).
    val root = runCatching { JSONObject(paramsJson.ifBlank { "{}" }) }.getOrNull() ?: return false
    val advanced = when (val raw = root.opt("advanced_json")) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }
    val raw = sequenceOf("geniex_compute_unit", "compute_unit")
        .mapNotNull { key ->
            root.optString(key).takeIf { it.isNotBlank() }
                ?: advanced?.optString(key).takeIf { !it.isNullOrBlank() }
        }
        .firstOrNull()
        ?.trim()
        ?.lowercase()
    return raw == ComputeUnitValue.CPU.value.orEmpty().lowercase() || raw == "cpu_only"
}

internal class MnnCpuChatRunner(
    private val bridge: NativeMnnBridge = NativeMnnBridge()
) : LocalChatRunner {
    @Volatile private var submittedGenerationJson: String? = null
    override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU

    /**
     * E05: the MNN chat lifecycle runs through the real Runtime Port. This runner stays the single
     * construction point per process (no second owner), and [NativeMnnChatLeaf] keeps the exact
     * production JNI exchange as the port's only native leaf.
     */
    private val nativeLeaf: MnnChatNativeLeaf = NativeMnnChatLeaf(bridge)
    private val runtimeOwner = BackendRuntimeOwner(
        backendKind = "MNN_CPU",
        epoch = RuntimeEpoch(1L),
        engineFactory = { engineId, modelPath ->
            MnnChatEnginePort(
                engineId = engineId,
                modelPath = modelPath,
                leaf = nativeLeaf,
                runtimeEpoch = RuntimeEpoch(1L)
            )
        }
    )
    @Volatile private var portEngine: MnnChatEnginePort? = null
    @Volatile private var portSession: MnnChatSessionPort? = null

    /** Engine of the most recent unload whose native release was not yet confirmed. */
    @Volatile private var pendingReleaseEngine: MnnChatEnginePort? = null

    override val isAvailable: Boolean
        get() = NativeMnnBridge.runnerReady
    override val loadError: Throwable?
        get() = NativeMnnBridge.loadError
            ?: if (!NativeMnnBridge.runnerReady) {
                IllegalStateException("MNN-LLM CPU executor is not packaged in this APK.")
            } else {
                null
            }

    override fun initBackends(nativeLibDir: String) {
        if (isAvailable) bridge.initBackends(nativeLibDir)
    }

    override fun loadModel(modelPath: String, paramsJson: String): Int {
        submittedGenerationJson = null
        if (!isAvailable) return UNAVAILABLE_RC
        // A previous engine object is retired and released as bookkeeping only: the native loadModel
        // below stays the only native call of a load, exactly as it was before this port existed,
        // and superseded bookkeeping does not accumulate for the process lifetime.
        portEngine?.let { previous ->
            previous.supersede()
            runtimeOwner.releaseEngine(previous.engineId)
        }
        portSession = null
        val engine = runtimeOwner.createEngine(modelPath) as MnnChatEnginePort
        portEngine = engine
        val rc = engine.load(paramsJson)
        if (rc == 0) {
            portSession = engine.openSession(MNN_CHAT_DEFAULT_SESSION_ID)
        }
        return rc
    }

    override fun unloadModel() {
        submittedGenerationJson = null
        if (!isAvailable) return
        val engine = portEngine
        if (engine != null) {
            engine.unload()
            // Verified physical-release state stays queryable through isNativeReleaseConfirmed();
            // an unconfirmed release is reported honestly instead of being treated as free.
            pendingReleaseEngine = if (engine.isNativeReleaseConfirmed) null else engine
        } else {
            bridge.unloadModel()
        }
        portSession = null
        portEngine = null
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int {
        submittedGenerationJson = null
        GenerationParams.fromJson(paramsJson).requireFiniteSamplerValues()
        if (!isAvailable) return UNAVAILABLE_RC
        val session = portSession
        val handle = session?.beginRequest(messagesJson, paramsJson)
        val rc = if (handle != null) {
            handle.beginRc
        } else {
            // No live session (a load that failed, or a session retired without a reload yet):
            // keep the pre-port native exchange so executor return codes stay identical.
            bridge.beginCompletion(messagesJson, paramsJson)
        }
        if (rc == 0) submittedGenerationJson = paramsJson
        return rc
    }

    override fun generateNextChunk(): String? {
        if (!isAvailable) return null
        val handle = portSession?.currentExecution
        return handle?.nextChunk() ?: bridge.generateNextChunk()
    }

    override fun requestStop() {
        if (!isAvailable) return
        val session = portSession
        if (session != null) session.requestStop() else bridge.requestStop()
    }

    override fun requestStopIfActive(): Boolean {
        if (!isAvailable) return false
        val session = portSession
        return if (session != null) session.requestStopIfActive() else bridge.requestStopIfActive()
    }

    override fun isGenerationRunning(): Boolean {
        if (!isAvailable) return false
        val session = portSession
        return if (session != null) session.isGenerationRunning() else bridge.isGenerationRunning()
    }

    override fun canReleasePreparedInputs(): Boolean {
        if (!isAvailable) return true
        val session = portSession
        return if (session != null) {
            session.canReleasePreparedInputs()
        } else {
            !bridge.isGenerationRunning()
        }
    }

    /** Physical release acknowledgement recorded by the runtime port on the last unload. */
    override fun isNativeReleaseConfirmed(): Boolean =
        pendingReleaseEngine?.isNativeReleaseConfirmed ?: true

    /** Prompt-free reason paired with [isNativeReleaseConfirmed]. */
    override fun nativeReleaseMessage(): String? =
        pendingReleaseEngine?.nativeReleaseMessage

    override fun prefillProgress(): TokenProgress? = runCatching {
        val root = JSONObject(bridge.getPrefillProgressJson())
        val total = root.optInt("totalTokens", 0)
        val completed = root.optInt("completedTokens", -1)
        if (total > 0 && completed in 0..total) {
            TokenProgress(completedTokens = completed, totalTokens = total)
        } else {
            null
        }
    }.getOrNull()

    override fun resetPrefillProgress() {
        if (isAvailable) bridge.resetPrefillProgress()
    }

    override fun getRuntimeStatsJson(): String =
        if (isAvailable) {
            JSONObject(bridge.getRuntimeStatsJson()).apply {
                submittedGenerationJson?.let { request ->
                    put("parameterApplication", nativeGenerationParameterApplication(runtime, request, this))
                }
                // E05: additive, prompt-free runtime-port release evidence. The keys are absent
                // when no unload left an unconfirmed release, so existing consumers stay compatible.
                pendingReleaseEngine?.let { engine ->
                    put("runtimePortReleaseConfirmed", engine.isNativeReleaseConfirmed)
                    engine.nativeReleaseMessage?.let { message ->
                        put("runtimePortReleaseMessage", message)
                    }
                }
            }.toString()
        } else {
            unavailableStats(runtime, loadError).toString()
        }

    override fun shutdown() {
        if (!isAvailable) return
        val engine = portEngine
        val pending = pendingReleaseEngine
        portSession = null
        portEngine = null
        pendingReleaseEngine = null
        // The native shutdown call is unchanged; the port state is retired as bookkeeping because
        // the native owner frees every resource here.
        bridge.shutdown()
        engine?.markNativeShutdown()
        pending?.markNativeShutdown()
    }
}

/**
 * E05: the runtime port's only native leaf. Every method forwards to the same bridge call with the
 * same arguments and return code the runner used before the port existed.
 */
private class NativeMnnChatLeaf(private val bridge: NativeMnnBridge) : MnnChatNativeLeaf {
    override fun loadModel(configPath: String, paramsJson: String): Int =
        bridge.loadModel(configPath, paramsJson)

    override fun unloadModel() {
        bridge.unloadModel()
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int =
        bridge.beginCompletion(messagesJson, paramsJson)

    override fun generateNextChunk(): String? = bridge.generateNextChunk()

    override fun requestStop() {
        bridge.requestStop()
    }

    override fun requestStopIfActive(): Boolean = bridge.requestStopIfActive()

    override fun isGenerationRunning(): Boolean = bridge.isGenerationRunning()

    override fun getRuntimeStatsJson(): String = bridge.getRuntimeStatsJson()

    override fun shutdown() {
        bridge.shutdown()
    }
}
