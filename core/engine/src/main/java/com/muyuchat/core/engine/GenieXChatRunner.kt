package com.muyuchat.core.engine

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.VlmCapabilities
import com.geniex.sdk.bean.VlmCreateInput
import com.muyuchat.core.modelstore.QairtBundleReadiness
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject


internal class GenieXChatRunner(
    override val runtime: LocalChatRuntime,
    private val requestedRuntimeId: String,
    internal val defaultComputeUnit: String,
    internal val defaultBackendDevices: String,
    private val appContext: Context? = null,
    private val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
) : LocalChatRunner, NativeQuiescenceProbe {
    override val isAvailable: Boolean
        get() = appContext != null && classesPresent && sdkInitError == null
    override val loadError: Throwable?
        get() = sdkInitError ?: if (appContext == null) {
            IllegalStateException("Android Context is required for ${runtime.label}.")
        } else if (!classesPresent) {
            IllegalStateException("GenieX Android SDK is not on the runtime classpath.")
        } else {
            null
        }

    private val classesPresent: Boolean by lazy {
        runCatching {
            Class.forName("com.geniex.sdk.GenieXSdk", false, javaClass.classLoader)
            Class.forName("com.geniex.sdk.jni.Llm", false, javaClass.classLoader)
        }.isSuccess
    }
    @Volatile private var sdkInitAttempted = false
    @Volatile private var sdkInitError: Throwable? = null
    /** The service records this during runner construction; SDK work is deferred until load. */
    @Volatile private var requestedNativeLibDir: String = ""
    @Volatile internal var modelPath: String? = null
    @Volatile internal var modelName: String = ""
    @Volatile internal var loaded = false
    @Volatile internal var lastError: String? = null
    /** Set when the last unload could not prove that the native owner was released. */
    @Volatile internal var nativeReleasePending = false
    @Volatile internal var nativeReleaseFailure: String? = null
    @Volatile internal var activeRuntimeId: String = requestedRuntimeId
    @Volatile internal var activeComputeUnit: String = defaultComputeUnit
    @Volatile internal var activeBackendDevices: String = defaultBackendDevices
    @Volatile internal var activeGpuLayers: Int = 0
    @Volatile internal var engine: Any? = null
    @Volatile internal var handle: Long = 0L
    @Volatile internal var activeModelType: ModelType = ModelType.LLM
    @Volatile internal var activeMmprojPath: String? = null
    @Volatile internal var activeVlmCapabilities: VlmCapabilities? = null
    @Volatile internal var visionBindingState: VisionBindingState = VisionBindingState.UNBOUND
    @Volatile internal var lastQairtBundleReadiness: QairtBundleReadiness? = null
    @Volatile internal var generateThread: Thread? = null
    @Volatile internal var lastPromptEndsInsideReasoning = false
    private val lifecycleLock = Any()
    internal val stopRequested = AtomicBoolean(false)
    /** Non-zero while a begin/stop SDK call has not returned to the caller thread. */
    private val nativeCallsInFlight = AtomicInteger(0)
    @Volatile internal var queue = StreamingChunkQueue()
    internal var params: GenerationParams = GenerationParams()
    internal var loadParams: LoadParams = LoadParams()
    internal var completionTokens = 0
    internal var promptTokens = 0
    @Volatile internal var prefillTotalTokens = 0
    internal var loadMs = 0L
    internal val generationTiming = GenerationElapsedTiming(clock)
    internal var nativeProfileJson = JSONObject()
    @Volatile internal var parameterApplicationJson: String = "{}"
    internal val isQairtRuntime: Boolean
        get() = requestedRuntimeId == RuntimeIdValue.QAIRT.value
    private val stagePrefix: String
        get() = if (isQairtRuntime) "qairt" else "geniex_llama_cpp"

    override fun initBackends(nativeLibDir: String) {
        // McaInferenceService constructs every runner up front. Retain the
        // directory and initialize GenieX only when one of its runtimes is
        // selected, so unrelated backends never load its native plugins.
        requestedNativeLibDir = nativeLibDir.trim()
    }

    private fun ensureSdkInitialized(nativeLibDir: String) {
        val context = appContext
        if (sdkInitAttempted || context == null || !classesPresent) return
        synchronized(GENIEX_PROCESS_INIT.lock) {
            if (sdkInitAttempted) return
            sdkInitAttempted = true
            val initDirectory = nativeLibDir.trim()
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_sdk_init_start",
                JSONObject()
                    .put("nativeLibDir", initDirectory)
                    .put("filesDir", context.filesDir.absolutePath)
            )

            if (!GENIEX_PROCESS_INIT.sdkInitAttempted) {
                GENIEX_PROCESS_INIT.sdkInitAttempted = true
                runCatching {
                    val sdk = GenieXSdk.getInstance()
                    var initFailure: String? = null
                    sdk.init(context, object : GenieXSdk.InitCallback {
                        override fun onSuccess() = Unit

                        override fun onFailure(reason: String) {
                            initFailure = reason
                        }
                    })
                    requireSuccessfulGenieXSdkInit(initFailure)
                }.onFailure { error ->
                    GENIEX_PROCESS_INIT.sdkInitError =
                        error.message ?: error::class.java.simpleName
                }
            }

            var errorMessage = GENIEX_PROCESS_INIT.sdkInitError
            if (errorMessage == null && isQairtRuntime &&
                !GENIEX_PROCESS_INIT.modelManagerInitAttempted
            ) {
                GENIEX_PROCESS_INIT.modelManagerInitAttempted = true
                runCatching {
                    val dataDir = File(context.filesDir, "geniex").apply { mkdirs() }
                    runBlocking { ModelManagerWrapper.init(dataDir.absolutePath).getOrThrow() }
                }.onFailure { error ->
                    GENIEX_PROCESS_INIT.modelManagerInitError =
                        error.message ?: error::class.java.simpleName
                }
            }
            if (errorMessage == null && isQairtRuntime) {
                errorMessage = GENIEX_PROCESS_INIT.modelManagerInitError
            }

            if (!errorMessage.isNullOrBlank()) {
                sdkInitError = IllegalStateException(errorMessage)
                LocalChatRunnerDebug.emit(
                    "${stagePrefix}_sdk_init_failed",
                    JSONObject().put("error", sdkInitError?.message.orEmpty())
                )
            } else {
                LocalChatRunnerDebug.emit("${stagePrefix}_sdk_init_ok")
            }
        }
    }

    override fun loadModel(modelPath: String, paramsJson: String): Int {
        val context = appContext ?: return UNAVAILABLE_RC.also {
            lastError = "Android Context is required for ${runtime.label}."
        }
        if (!classesPresent) {
            lastError = "GenieX Android SDK is not on the runtime classpath."
            return UNAVAILABLE_RC
        }
        ensureSdkInitialized(
            requestedNativeLibDir.ifBlank { context.applicationInfo.nativeLibraryDir }
        )
        sdkInitError?.let { error ->
            lastError = error.message
            return UNAVAILABLE_RC
        }
        val file = File(modelPath)
        if (!file.exists()) {
            lastError = "GenieX model path does not exist: $modelPath"
            return -2
        }
        unloadModel()
        // A failed root-resolution path must not inherit a successful
        // readiness result from the previously loaded QAIRT bundle.
        if (isQairtRuntime) {
            lastQairtBundleReadiness = null
        }
        loadParams = LoadParams.fromJson(paramsJson)
        visionBindingState = if (loadParams.visionProjectorPath.isNullOrBlank()) {
            VisionBindingState.UNBOUND
        } else {
            VisionBindingState.BOUND_PENDING_RELOAD
        }
        val started = clock.nowMs()
        LocalChatRunnerDebug.emit(
            "${stagePrefix}_load_start",
            JSONObject()
                .put("inputPath", file.absolutePath)
                .put("nCtx", loadParams.nCtx)
                .put("nThreads", loadParams.nThreads)
                .put("requestedRuntimeId", requestedRuntimeId)
                .put("requestedComputeUnit", loadParams.geniexComputeUnit ?: defaultComputeUnit)
        )
        return runCatching {
            LocalChatRunnerDebug.emit("${stagePrefix}_prepare_model_start")
            val managed = if (isQairtRuntime) {
                prepareManagedQairtModel(file)
            } else {
                prepareDirectGgufModel(file)
            }
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_prepare_model_ok",
                JSONObject()
                    .put("modelName", managed.modelName)
                    .put("modelPath", managed.modelPath)
                    .put("tokenizerPath", managed.tokenizerPath)
                    .put("runtimeId", managed.runtimeId)
                    .put("modelType", managed.modelType.name.lowercase())
                    .put("mmprojPath", managed.mmprojPath)
            )
            val computeUnit = if (isQairtRuntime) {
                ComputeUnitValue.NPU.value.orEmpty()
            } else {
                resolveLlamaCppComputeUnit(loadParams.geniexComputeUnit)
            }
            val gpuLayers = if (isQairtRuntime || computeUnit == ComputeUnitValue.CPU.value) 0 else 999
            val config = if (isQairtRuntime) {
                ModelConfig(
                    nCtx = 0,
                    nThreads = if (managed.modelType == ModelType.VLM) 8 else 0,
                    nThreadsBatch = 0,
                    nBatch = 0,
                    nUBatch = 0,
                    nSeqMax = 0,
                    nGpuLayers = 0,
                    chat_template_path = "",
                    chat_template_content = ""
                )
            } else {
                val threads = loadParams.nThreads.coerceAtLeast(1)
                ModelConfig(
                    nCtx = loadParams.nCtx.coerceAtLeast(1),
                    nThreads = threads,
                    nThreadsBatch = threads,
                    nBatch = 512,
                    nUBatch = 128,
                    nSeqMax = 1,
                    nGpuLayers = gpuLayers,
                    chat_template_path = "",
                    chat_template_content = ""
                )
            }
            val runtimeId = managed.runtimeId.ifBlank { requestedRuntimeId }
            val engineKind = managed.modelType.name.lowercase()
            visionBindingState = if (managed.mmprojPath.isNullOrBlank()) {
                VisionBindingState.UNBOUND
            } else {
                VisionBindingState.LOADING
            }
            val createInput: Any
            val createInputClass: Class<*>
            val engineClassName: String
            if (managed.modelType == ModelType.VLM) {
                createInput = VlmCreateInput(
                    model_path = managed.modelPath,
                    mmproj_path = managed.mmprojPath.orEmpty(),
                    config = config,
                    runtime_id = runtimeId,
                    compute_unit = computeUnit
                )
                createInputClass = VlmCreateInput::class.java
                engineClassName = "com.geniex.sdk.jni.Vlm"
            } else {
                createInput = LlmCreateInput(
                    model_path = managed.modelPath,
                    tokenizer_path = managed.tokenizerPath,
                    config = config,
                    runtime_id = runtimeId,
                    compute_unit = computeUnit
                )
                createInputClass = LlmCreateInput::class.java
                engineClassName = "com.geniex.sdk.jni.Llm"
            }
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_${engineKind}_create_start",
                JSONObject()
                    .put("modelName", managed.modelName)
                    .put("runtimeId", runtimeId)
                    .put("computeUnit", computeUnit)
                    .put("modelType", engineKind)
                    .put("mmprojPath", managed.mmprojPath)
                    .put("nGpuLayers", config.nGpuLayers)
                    .put("nCtx", config.nCtx)
                    .put("nThreads", config.nThreads)
            )
            val engineClass = Class.forName(engineClassName)
            val instance = engineClass.getDeclaredConstructor().newInstance()
            val create = engineClass.getDeclaredMethod("create", createInputClass)
            val nativeHandle = create.invoke(instance, createInput) as Long
            if (nativeHandle == 0L) error("GenieX ${managed.modelType.name} create returned an empty handle.")
            // Own the handle before any follow-up SDK call so a load failure can always destroy it.
            synchronized(lifecycleLock) {
                engine = instance
                handle = nativeHandle
            }
            val vlmCapabilities = if (managed.modelType == ModelType.VLM) {
                runCatching {
                    engineClass.getDeclaredMethod("getCapabilities", Long::class.javaPrimitiveType)
                        .invoke(instance, nativeHandle) as VlmCapabilities
                }.onFailure { error ->
                    LocalChatRunnerDebug.emit(
                        "${stagePrefix}_vlm_capabilities_failed",
                        JSONObject().put("error", error.describeForUser())
                    )
                }.getOrNull()
            } else {
                null
            }
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_${engineKind}_create_ok",
                JSONObject()
                    .put("handleNonZero", nativeHandle != 0L)
                    .put("supportsVision", vlmCapabilities?.supportsVision)
                    .put("supportsAudio", vlmCapabilities?.supportsAudio)
            )
            this.modelPath = managed.modelPath
            modelName = managed.modelName
            activeRuntimeId = runtimeId
            activeComputeUnit = computeUnit
            activeGpuLayers = config.nGpuLayers
            activeBackendDevices = backendDevicesFor(computeUnit, managed.modelType)
            activeModelType = managed.modelType
            activeMmprojPath = managed.mmprojPath
            activeVlmCapabilities = vlmCapabilities
            loaded = true
            visionBindingState = if (managed.mmprojPath.isNullOrBlank()) {
                VisionBindingState.UNBOUND
            } else if (genieXVisionReady(
                    runtime = runtime,
                    loaded = true,
                    modelType = activeModelType,
                    mmprojPath = activeMmprojPath,
                    supportsVision = vlmCapabilities?.supportsVision
                )) {
                VisionBindingState.READY
            } else {
                VisionBindingState.LOAD_FAILED
            }
            lastError = null
            loadMs = (clock.nowMs() - started).coerceAtLeast(0L)
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_load_ok",
                JSONObject()
                    .put("loadMs", loadMs)
                    .put("runtimeId", activeRuntimeId)
                    .put("computeUnit", activeComputeUnit)
                    .put("backendDevices", activeBackendDevices)
                    .put("modelType", activeModelType.name.lowercase())
                    .put(
                        "visionReady",
                        genieXVisionReady(
                            runtime = runtime,
                            loaded = true,
                            modelType = activeModelType,
                            mmprojPath = activeMmprojPath,
                            supportsVision = vlmCapabilities?.supportsVision
                        )
                    )
            )
            0
        }.getOrElse { error ->
            val nativeDescription = error.describeForUser()
            lastError = if (isQairtRuntime && lastQairtBundleReadiness?.canLoad == true) {
                "GenieX QAIRT native create failed (-3): $nativeDescription. " +
                    "包结构完整；请检查 QAIRT/GenieX 运行时版本、预编译 bundle 目标和设备固件兼容性。"
            } else {
                nativeDescription
            }
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_load_failed",
                JSONObject()
                    .put("error", lastError)
                    .put("rawError", error.stackTraceToString())
            )
            runCatching { unloadModel() }
            visionBindingState = if (loadParams.visionProjectorPath.isNullOrBlank()) {
                VisionBindingState.UNBOUND
            } else {
                VisionBindingState.LOAD_FAILED
            }
            -3
        }
    }


    override fun unloadModel() = synchronized(lifecycleLock) {
        if (!stopAndJoinGenerationLocked()) {
            // The generation thread is still inside a native call.  Detaching the handle here would
            // discard the only reference able to release it, so keep the owner and report the
            // failure instead of returning as if the unload had completed.
            markNativeReleasePendingLocked(
                "GenieX generation is still inside a native call; the unload did not release it."
            )
            return@synchronized
        }
        val current = engine
        val nativeHandle = handle
        val engineKind = activeModelType.name.lowercase()
        if (current != null && nativeHandle != 0L) {
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_${engineKind}_destroy_start",
                JSONObject().put("handleNonZero", true)
            )
            val destroyResult = runCatching { destroyGenieXHandle(current, nativeHandle) }
            val destroyCode = destroyResult.getOrNull()
            if (destroyResult.isFailure) {
                markNativeReleasePendingLocked(
                    "GenieX ${engineKind} destroy failed: " +
                        (destroyResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                            ?: destroyResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
                )
                return@synchronized
            }
            if (destroyCode != 0) {
                LocalChatRunnerDebug.emit(
                    "${stagePrefix}_${engineKind}_destroy_failed",
                    JSONObject().put("result", destroyCode)
                )
                markNativeReleasePendingLocked("GenieX ${engineKind} destroy returned $destroyCode.")
                return@synchronized
            }
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_${engineKind}_destroy_ok",
                JSONObject().put("result", destroyCode)
            )
        }
        // Native release is confirmed: only now detach the owner and publish the unloaded state.
        engine = null
        handle = 0L
        loaded = false
        activeModelType = ModelType.LLM
        activeMmprojPath = null
        activeVlmCapabilities = null
        visionBindingState = VisionBindingState.UNBOUND
        clearNativeReleasePendingLocked()
        queue.cancel()
    }

    /** Records that the native owner survived this unload attempt; the owner is deliberately kept. */
    private fun markNativeReleasePendingLocked(message: String) {
        nativeReleasePending = true
        nativeReleaseFailure = message
        lastError = message
        LocalChatRunnerDebug.emit(
            "${stagePrefix}_native_release_failed",
            JSONObject().put("error", message).put("handleNonZero", handle != 0L)
        )
    }

    private fun clearNativeReleasePendingLocked() {
        nativeReleasePending = false
        nativeReleaseFailure = null
    }

    override fun isNativeReleaseConfirmed(): Boolean = !nativeReleasePending

    override fun nativeReleaseMessage(): String? = nativeReleaseFailure

    override fun isSessionKnownLost(): Boolean = nativeReleasePending

    override fun sessionRecoveryPolicy(): LocalChatSessionRecoveryPolicy =
        if (nativeReleasePending) {
            LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED
        } else {
            LocalChatSessionRecoveryPolicy.AUTOMATIC
        }

    override fun sessionRecoveryMessage(): String? = nativeReleaseFailure

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int = synchronized(lifecycleLock) {
        nativeCallsInFlight.incrementAndGet()
        try {
            beginCompletionLocked(messagesJson, paramsJson)
        } finally {
            nativeCallsInFlight.decrementAndGet()
        }
    }

    override fun generateNextChunk(): String? {
        return queue.next()
    }

    override fun requestStop() {
        stopRequested.set(true)
        queue.cancel()
        nativeCallsInFlight.incrementAndGet()
        try {
            synchronized(lifecycleLock) {
                requestStopLocked()
            }
        } finally {
            nativeCallsInFlight.decrementAndGet()
        }
    }

    /**
     * GenieX generation runs on its own native thread. [generateThread] is alive exactly while the
     * native `generate` call has not returned; an unconfirmed native release is a deliberate fence.
     */
    override fun nativeGenerationActivity(): NativeGenerationActivity = when {
        nativeReleasePending -> NativeGenerationActivity.ACTIVE
        nativeCallsInFlight.get() > 0 -> NativeGenerationActivity.ACTIVE
        generateThread?.isAlive == true -> NativeGenerationActivity.ACTIVE
        else -> NativeGenerationActivity.IDLE
    }

    override fun prefillProgress(): TokenProgress? {
        // GenieX is a closed SDK whose prefill produces no intermediate events.
        // Report the estimated total once the run starts; completed jumps to
        // total when the first generated token arrives.
        val total = prefillTotalTokens
        if (total <= 0) return null
        val completed = if (generationTiming.hasFirstToken()) total else 0
        return TokenProgress(completedTokens = completed, totalTokens = total)
    }

    override fun getRuntimeStatsJson(): String = genieXStatsJson()

    override fun shutdown() {
        unloadModel()
    }


    companion object {
        internal const val GENIEX_BOUNDARY_TAG = "MCA-GENIEX-BOUNDARY"
        private const val GENIEX_ERROR_ALREADY_INITIALIZED = -100008

        // Buffer the head of a generation until it grows past this many
        // (estimated) tokens; a turn that never crosses this window is evaluated
        // for the one-shot rescue. Sized to retain the observed 3-5 token prompt
        // echo of a short Chinese question.
        internal const val GENIEX_RESCUE_PROBE_TOKENS = 8
        // Native generated-token ceiling for a collapsed turn eligible for rescue.
        internal const val GENIEX_RESCUE_MAX_GENERATED_TOKENS = 8
        // A collapsed echo fragment may not exceed this many visible characters.
        internal const val GENIEX_ECHO_FRAGMENT_MAX_CHARS = 12
        // Gentle internal sampler used ONLY by the single rescue attempt.
        internal const val GENIEX_RESCUE_TEMPERATURE = 0.3f
        internal const val GENIEX_RESCUE_TOP_K = 20
        internal const val GENIEX_RESCUE_TOP_P = 0.9f
    }
}
