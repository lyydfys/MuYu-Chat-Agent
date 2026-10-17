package com.muyuchat.core.engine

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback as LiteRtMessageCallback
import com.muyuchat.core.modelstore.isLiteRtLmVisionModel
import com.muyuchat.core.modelstore.liteRtLmModelTypes
import com.muyuchat.core.modelstore.validateLiteRtLmLoadPreflight
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject


/**
 * LiteRT-LM runner backed by the official Kotlin Engine API.
 *
 * The service-facing LocalChatRunner contract is pull based, while LiteRT-LM
 * exposes a streaming Flow<Message>. A small daemon worker collects that Flow
 * into a queue and keeps all JNI work off the caller thread. LiteRT-LM owns
 * conversation history, so a new request sends only its newest user turn when
 * the supplied prefix matches the history already held by the Conversation.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtLmChatRunner(
    private val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock,
    internal val wallClockMs: () -> Long = System::currentTimeMillis
) : LocalChatRunner {
    override val runtime: LocalChatRuntime = LocalChatRuntime.LITERT_LM

    override val isAvailable: Boolean
        get() = classesPresent

    override val loadError: Throwable?
        get() = loadFailure ?: if (!classesPresent) {
            IllegalStateException("LiteRT-LM Android classes are not on the runtime classpath.")
        } else {
            null
        }

    internal data class LiteRtLoadConfig(
        val backend: String,
        val maxNumTokens: Int,
        val cacheDir: String?,
        val nThreads: Int
    )

    internal val lifecycleLock = Any()
    private val stopRequested = AtomicBoolean(false)
    @Volatile internal var queue = StreamingChunkQueue()
    @Volatile internal var nativeLibDir: String = ""
    @Volatile internal var engine: Engine? = null
    @Volatile internal var submittedEngineConfig: EngineConfig? = null
    @Volatile internal var conversation: Conversation? = null
    @Volatile private var generationThread: Thread? = null
    @Volatile internal var generationRunning = false
    @Volatile internal var loaded = false
    @Volatile internal var successfulImageTurn = false
    @Volatile internal var visionModelKnownTextOnly = false
    /** True only when the bounded LiteRT-LM metadata contains visual sections. */
    @Volatile internal var visionModelVisualComponentsPresent = false
    @Volatile internal var visionBindingState: VisionBindingState = VisionBindingState.UNBOUND
    @Volatile private var loadFailure: Throwable? = null
    @Volatile internal var lastError: String = ""
    @Volatile internal var modelPath: String? = null
    @Volatile internal var activeConfig: LiteRtLoadConfig = LiteRtLoadConfig(
        backend = "cpu",
        maxNumTokens = 4096,
        cacheDir = null,
        nThreads = 4
    )
    @Volatile internal var loadMs: Long = 0L
    internal val generationTiming = GenerationElapsedTiming(clock)
    @Volatile internal var promptTokens: Int = 0
    @Volatile internal var completionTokens: Int = 0
    @Volatile internal var conversationHistory: List<LiteRtMessageSpec> = emptyList()
    @Volatile internal var conversationOwnerId: String? = null
    @Volatile private var conversationContextRevision: String? = null
    /** Sampler settings are immutable per LiteRT conversation. */
    @Volatile internal var conversationSamplerValues: LiteRtSamplerValues? = null
    /** Only a normally completed turn may leave a Conversation reusable. */
    @Volatile internal var conversationReusable = false
    // Only the generation owner may query SDK telemetry, after its terminal callback. Binder/UI
    // reads must never enter a Conversation while LiteRT's async native executor owns it.
    internal data class CompletedMetrics(
        val prefillTokens: Int,
        val decodeTokens: Int,
        val tokenCount: Int,
        val prefillTps: Double,
        val decodeTps: Double,
        val ttftMs: Long
    )
    @Volatile internal var completedMetrics: CompletedMetrics? = null
    @Volatile internal var parameterApplicationJson: String = "{}"
    internal val cancellationGate = NativeCancellationGate()
    @Volatile internal var cancellationRecoveryRequired = false

    internal val classesPresent: Boolean by lazy {
        runCatching {
            Class.forName("com.google.ai.edge.litertlm.Engine", false, javaClass.classLoader)
            Class.forName("com.google.ai.edge.litertlm.Conversation", false, javaClass.classLoader)
        }.isSuccess
    }

    override fun initBackends(nativeLibDir: String) {
        this.nativeLibDir = nativeLibDir
    }

    override fun loadModel(modelPath: String, paramsJson: String): Int = synchronized(lifecycleLock) {
        if (!classesPresent) {
            loadFailure = IllegalStateException("LiteRT-LM Android classes are not on the runtime classpath.")
            lastError = loadFailure?.message.orEmpty()
            return@synchronized UNAVAILABLE_RC
        }
        val file = File(modelPath)
        if (!file.isFile || !file.canRead()) {
            lastError = "LiteRT-LM model path is not readable: $modelPath"
            loadFailure = IllegalArgumentException(lastError)
            return@synchronized -2
        }
        // The model-store gate is normally evaluated before this runner is
        // reached, but Local API/debug callers can invoke a runner directly.
        // Repeat the bounded container/range check at the execution boundary
        // so truncated or non-text .litertlm files fail with a useful reason
        // instead of an opaque mmap/compiled-model exception.
        val preflight = validateLiteRtLmLoadPreflight(file, file.length())
        if (!preflight.canLoad) {
            loadFailure = IllegalArgumentException(preflight.details)
            lastError = "LiteRT-LM model preflight failed: ${preflight.title}: ${preflight.details}"
            LocalChatRunnerDebug.emit(
                "litert_lm_load_rejected_preflight",
                JSONObject()
                    .put("modelPath", file.absolutePath)
                    .put("error", lastError)
            )
            return@synchronized -2
        }
        val config = runCatching { parseLoadConfig(paramsJson) }.getOrElse { error ->
            loadFailure = error
            lastError = "LiteRT-LM parameters are invalid: ${error.message ?: error::class.java.simpleName}"
            return@synchronized -3
        }
        unloadModel()
        if (isGenerationRunning()) {
            // Never replace Engine while a cancelled callback thread still
            // owns the old Conversation. The isolated worker's cancellation
            // watchdog will recover the process if native ignores the stop.
            lastError = "LiteRT-LM previous generation did not stop within the cancellation grace period."
            loadFailure = IllegalStateException(lastError)
            return@synchronized -5
        }
        // Determine image capability from the package metadata before creating
        // the native engine.  Filename heuristics were insufficient: CPU/NPU
        // visual bundles can have arbitrary names, while GPU text-only bundles
        // may look like a Gemma multimodal package.  A pure text package must
        // never receive an image request because some delegates abort instead
        // of returning a structured unsupported-capability error.
        visionModelVisualComponentsPresent = isLiteRtLmVisionModel(file)
        visionModelKnownTextOnly = !visionModelVisualComponentsPresent &&
            liteRtLmModelTypes(file).isNotEmpty()
        visionBindingState = if (visionModelVisualComponentsPresent) {
            VisionBindingState.LOADING
        } else {
            VisionBindingState.UNBOUND
        }
        val started = clock.nowMs()
        return@synchronized runCatching {
            if (config.backend.equals("npu", ignoreCase = true)) {
                // LiteRT's Qualcomm dispatch loads libQnnHtp.so by SONAME after
                // loading libQnnSystem.so from the selected runtime directory.
                // If another backend (notably GenieX/QAIRT) has already put an
                // older QNN host library in this process, the SONAME lookup can
                // resolve that handle instead of the staged Edge Gallery set.
                // Preload the coherent staged pair by absolute path so the
                // subsequent dispatch lookup reuses the intended objects.
                preloadQualcommHostLibraries(nativeLibDir)
            }
            val engineConfig = liteRtEngineConfig(
                modelPath = file.absolutePath,
                backend = backendFor(config),
                maxNumTokens = config.maxNumTokens,
                cacheDir = config.cacheDir,
                hasVisionComponents = visionModelVisualComponentsPresent
            )
            // BenchmarkInfo is used for real prefill/decode diagnostics. The
            // flag is read only when Engine is constructed.
            ExperimentalFlags.enableBenchmark = true
            fun createInitializedEngine() = initializeOwnedNativeResource(
                create = { Engine(engineConfig).also { submittedEngineConfig = it.engineConfig } },
                initialize = { it.initialize() },
                // LiteRT 0.16.1 close() rejects an engine without a published native handle.
                // A failed nativeCreateEngine has no Java-owned handle to release.
                close = { if (it.isInitialized()) it.close() }
            )
            val createdEngine = try {
                createInitializedEngine()
            } catch (firstError: Throwable) {
                // LiteRT GPU/compiled-model caches are backend- and artifact-
                // specific.  A cache produced by an older delegate revision
                // can load successfully but fail the first compiled invoke
                // with Status Code 13.  Invalidate only this model's private
                // cache and retry one clean engine initialization; CPU/NPU
                // paths retain their original failure semantics.
                val cacheDir = config.cacheDir
                val recoverable = firstError !is NativeResourceCleanupException &&
                    config.backend.equals("gpu", ignoreCase = true) &&
                    cacheDir != null &&
                    isCompiledModelCacheFailure(firstError)
                if (!recoverable) throw firstError
                clearPrivateCacheDirectory(File(requireNotNull(cacheDir)))
                LocalChatRunnerDebug.emit(
                    "litert_lm_cache_invalidated",
                    JSONObject()
                        .put("backend", config.backend)
                        .put("cacheDir", cacheDir)
                        .put("reason", describe(firstError))
                )
                createInitializedEngine()
            }
            engine = createdEngine
            // Conversation creation is deferred until the first request so its
            // sampler config reflects the request instead of silently using
            // LiteRT's defaults. This also avoids allocating an unused KV
            // cache during model load.
            conversation = null
            conversationSamplerValues = null
            conversationReusable = false
            completedMetrics = null
            activeConfig = config
            this@LiteRtLmChatRunner.modelPath = file.absolutePath
            loaded = true
            successfulImageTurn = false
            visionBindingState = if (visionModelVisualComponentsPresent) {
                VisionBindingState.LOADING
            } else {
                VisionBindingState.UNBOUND
            }
            loadFailure = null
            lastError = ""
            loadMs = (clock.nowMs() - started).coerceAtLeast(0L)
            conversationHistory = emptyList()
            LocalChatRunnerDebug.emit(
                "litert_lm_load_ok",
                JSONObject()
                    .put("modelPath", file.absolutePath)
                    .put("backend", config.backend)
                    .put("maxNumTokens", config.maxNumTokens)
                    .put("loadMs", loadMs)
            )
            0
        }.getOrElse { error ->
            val hadVisualComponents = visionModelVisualComponentsPresent
            loadFailure = error
            lastError = describe(error)
            loaded = false
            visionModelKnownTextOnly = false
            visionModelVisualComponentsPresent = false
            successfulImageTurn = false
            visionBindingState = if (hadVisualComponents) {
                VisionBindingState.LOAD_FAILED
            } else {
                VisionBindingState.UNBOUND
            }
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            conversation = null
            engine = null
            conversationReusable = false
            LocalChatRunnerDebug.emit(
                "litert_lm_load_failed",
                JSONObject().put("error", lastError).put("backend", config.backend)
            )
            -4
        }
    }

    private fun preloadQualcommHostLibraries(directory: String) {
        val root = directory.trim().takeIf { it.isNotEmpty() }?.let(::File) ?: return
        if (!root.isDirectory) return
        // Only preload a directory that carries the complete dispatch/QNN set.
        // A generic APK native directory may contain GenieX's older QAIRT
        // libraries and must remain the fallback for a real native-load error.
        val dispatch = File(root, "libLiteRtDispatch_Qualcomm.so")
        val system = File(root, "libQnnSystem.so")
        val htp = File(root, "libQnnHtp.so")
        if (!dispatch.isFile || !system.isFile || !htp.isFile) return
        val loaded = mutableListOf<String>()
        // Load the dispatch plugin by absolute path as well.  LiteRT's
        // Qualcomm delegate normally resolves this library by SONAME; when
        // the app has already loaded GenieX's QAIRT runtime, SONAME lookup
        // can otherwise bind the dispatch plugin from the wrong revision (or
        // fail because the staged directory is not in the linker namespace).
        // Loading the coherent staged trio up front keeps the selected
        // V73/V75/V79/V81 profile together.  The delegate still remains the
        // authority for graph compatibility and can report a real load error.
        listOf(system, htp, dispatch).forEach { library ->
            runCatching {
                System.load(library.absolutePath)
                loaded += library.name
            }.onFailure { error ->
                LocalChatRunnerDebug.emit(
                    "litert_lm_qnn_runtime_preload_failed",
                    JSONObject()
                        .put("library", library.name)
                        .put("path", library.absolutePath)
                        .put("error", error.message ?: error::class.java.simpleName)
                )
            }
        }
        if (loaded.isNotEmpty()) {
            LocalChatRunnerDebug.emit(
                "litert_lm_qnn_runtime_preload",
                JSONObject()
                    .put("directory", root.absolutePath)
                    .put("libraries", loaded.joinToString(","))
            )
        }
    }

    private fun isCompiledModelCacheFailure(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return "status code: 13" in text ||
            "failed to invoke the compiled model" in text ||
            "compiled model" in text && "invoke" in text
    }

    private fun clearPrivateCacheDirectory(directory: File) {
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty().forEach { child ->
            runCatching {
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        }
    }

    override fun unloadModel() {
        unloadModelImpl()
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int = synchronized(lifecycleLock) {
        if (!loaded || engine == null) {
            lastError = "LiteRT-LM model is not loaded."
            return@synchronized -4
        }
        if (!stopAndJoinGenerationLocked()) return@synchronized -7
        // Reset before Conversation creation, so a Stop arriving during that native call is
        // not discarded when creation finally returns.
        stopRequested.set(false)
        val messages = runCatching { parseLiteRtMessages(messagesJson) }.getOrElse { error ->
            lastError = "LiteRT-LM messages are invalid: ${error.message ?: error::class.java.simpleName}"
            return@synchronized -6
        }
        if (messages.isEmpty()) {
            lastError = "LiteRT-LM received no chat messages."
            return@synchronized -6
        }
        val latestIndex = messages.lastIndex
        if (!messages[latestIndex].role.equals("user", ignoreCase = true)) {
            lastError = "LiteRT-LM completion must end with a user message."
            return@synchronized -6
        }
        val prefix = messages.dropLast(1)
        val latest = messages.last()
        // Enforce the package capability at the runner boundary as well as in
        // the app/service admission checks. Local API callers and older UI
        // paths can invoke beginCompletion directly; sending an image into a
        // text-only delegate has caused native aborts on some LiteRT builds.
        // Check the complete conversation, not only the latest turn. A text
        // follow-up after an earlier image still reuses the same conversation
        // and therefore still requires a visual encoder. The previous
        // latest-only check let that path reach a text-only delegate and could
        // crash on GPU/NPU LiteRT packages.
        if (messages.any { it.hasImageInput } && !visionModelVisualComponentsPresent) {
            lastError = LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
            return@synchronized -8
        }
        val generationParams = GenerationParams.fromJson(paramsJson)
        parameterApplicationJson = "{}"
        val generationConfig = runCatching {
            liteRtGenerationConfiguration(generationParams, activeConfig.backend)
        }.getOrElse { error ->
            lastError = "LiteRT-LM generation parameters are invalid: ${error.message}"
            return@synchronized -6
        }
        val requestedOwnerId = JSONObject(paramsJson).optString("conversationOwnerId")
            .takeIf(String::isNotBlank)
        val requestedContextRevision = JSONObject(paramsJson).optString("conversationContextRevision")
            .takeIf(String::isNotBlank)
        val requestedSamplerValues = generationConfig.sampler
        val currentConversation = if (
            conversationHistory == prefix &&
            liteRtConversationScopeMatches(
                conversationOwnerId, conversationContextRevision,
                requestedOwnerId, requestedContextRevision
            ) &&
            conversationSamplerValues == requestedSamplerValues &&
            conversationReusable &&
            conversation != null
        ) {
            conversation
        } else {
            // Detach before closing/creating. If creation fails, no closed
            // handle or stale history remains eligible for reuse.
            val oldConversation = conversation
            if (oldConversation != null && !closeConversationSafely(oldConversation)) return@synchronized -7
            conversationHistory = emptyList()
            conversationOwnerId = null
            conversationSamplerValues = null
            conversationReusable = false
            runCatching {
                val created = engine?.createConversation(
                    ConversationConfig(
                        initialMessages = prefix.map(::toLiteRtMessage),
                        samplerConfig = requestedSamplerValues?.toSdkConfig()
                    )
                ) ?: error("LiteRT-LM engine is not initialized.")
                conversation = created
                conversationHistory = prefix
                conversationOwnerId = requestedOwnerId
                conversationContextRevision = requestedContextRevision
                conversationSamplerValues = requestedSamplerValues
                conversationReusable = false
                created
            }.getOrElse { error ->
                lastError = "LiteRT-LM conversation creation failed: ${describe(error)}"
                return@synchronized -6
            }
        } ?: return@synchronized -4

        if (stopRequested.get()) {
            lastError = "LiteRT-LM generation was cancelled while preparing the conversation."
            conversationReusable = false
            return@synchronized -7
        }

        val outputQueue = StreamingChunkQueue().also { queue = it }
        lastError = ""
        generationTiming.start()
        promptTokens = (prefix + latest).sumOf { estimateTokens(it.content) }
        completionTokens = 0
        completedMetrics = null
        generationRunning = true
        conversationReusable = false
        generationThread = thread(
            start = true,
            isDaemon = true,
            name = "mca-${runtime.backendId}-generate"
        ) {
            val output = StringBuilder()
            var callbackError: Throwable? = null
            var failedCompiledCache: File? = null
            val generationResult = runCatching {
                check(!stopRequested.get()) { "LiteRT-LM generation was cancelled before execution." }
                val completed = CountDownLatch(1)
                val callback = object : LiteRtMessageCallback {
                    override fun onMessage(message: LiteRtMessage) {
                        val chunk = message.toString()
                        if (chunk.isNotEmpty()) {
                            generationTiming.markFirstToken()
                            completionTokens += estimateTokens(chunk).coerceAtLeast(1)
                            output.append(chunk)
                            outputQueue.put(chunk)
                        }
                    }

                    override fun onDone() {
                        completed.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        callbackError = throwable
                        completed.countDown()
                    }
                }
                parameterApplicationJson = liteRtGenerationParameterApplication(
                    paramsJson, activeConfig.backend, generationConfig
                ).toString()
                currentConversation.sendMessageAsync(
                    latest.toLiteRtContents(),
                    callback,
                    repetitionPenaltyConfig = generationConfig.repetitionPenalty,
                    maxOutputToken = generationConfig.maxOutputTokens,
                    thinkingConfig = generationConfig.thinking
                )
                while (!completed.await(250L, TimeUnit.MILLISECONDS)) {
                    if (stopRequested.get()) {
                        requestStop()
                    }
                }
                callbackError?.let { throw it }
            }.onFailure { error ->
                if (!stopRequested.get()) {
                    val compiledCacheDir = activeConfig.cacheDir
                    if (activeConfig.backend.equals("gpu", ignoreCase = true) &&
                        compiledCacheDir != null &&
                        isCompiledModelCacheFailure(error)
                    ) {
                        // Compiled-model failures can occur on the first
                        // conversation invoke (not only Engine.initialize).
                        // Drop the private delegate cache and invalidate this
                        // session so the service's normal recovery path will
                        // recreate the engine cleanly on the next request.
                        failedCompiledCache = File(compiledCacheDir)
                        conversationHistory = emptyList()
                        conversationOwnerId = null
                        conversationSamplerValues = null
                        conversationReusable = false
                        lastError = "LiteRT-LM GPU compiled model cache was invalidated; reload required: ${describe(error)}"
                        LocalChatRunnerDebug.emit(
                            "litert_lm_generation_cache_invalidated",
                            JSONObject()
                                .put("backend", activeConfig.backend)
                                .put("cacheDir", activeConfig.cacheDir)
                                .put("error", describe(error))
                        )
                    } else {
                        lastError = describe(error)
                    }
                    generationTiming.complete()
                    outputQueue.finish(lastError)
                }
            }
            generationTiming.complete()
            val completedNormally = liteRtConversationReusableAfterTurn(
                generationResult = generationResult,
                stopRequested = stopRequested.get(),
                output = output
            )
            if (completedNormally) {
                if (latest.hasImageInput) {
                    successfulImageTurn = true
                    visionBindingState = VisionBindingState.READY
                }
                // The SDK has delivered Done. Capture once on the generation thread before
                // publishing completion, never from a concurrent Binder/main-thread query.
                cancellationGate.whenIdle {
                    completedMetrics = runCatching {
                        val benchmark = currentConversation.getBenchmarkInfo()
                        CompletedMetrics(
                            prefillTokens = benchmark.lastPrefillTokenCount.toInt(),
                            decodeTokens = benchmark.lastDecodeTokenCount.toInt(),
                            tokenCount = currentConversation.getTokenCount(),
                            prefillTps = benchmark.lastPrefillTokensPerSecond.toDouble(),
                            decodeTps = benchmark.lastDecodeTokensPerSecond.toDouble(),
                            ttftMs = (benchmark.timeToFirstTokenInSecond * 1000.0).toLong()
                        )
                    }.getOrNull()
                }
                // The lifecycle owner cannot enter begin/unload until this
                // generation thread terminates, so volatile fields are enough
                // here and avoid joining while holding lifecycleLock.
                if (conversation === currentConversation && !stopRequested.get()) {
                    conversationHistory = prefix + latest + LiteRtMessageSpec(
                        role = "model",
                        parts = listOf(LiteRtPromptPart.Text(output.toString()))
                    )
                    conversationReusable = true
                }
            }
            if (!completedNormally || stopRequested.get()) {
                // A cancelled or failed stream is not a valid KV boundary.
                // Invalidate it before a same-prefix retry can reuse it.
                conversationHistory = emptyList()
                conversationOwnerId = null
                conversationSamplerValues = null
                conversationReusable = false
                val closed = closeConversationSafely(currentConversation)
                failedCompiledCache?.let { cache ->
                    if (closed) cancellationGate.whenIdle {
                        val oldEngine = engine
                        engine = null
                        loaded = false
                        // Only remove delegate cache files after native owners release them.
                        if (runCatching { oldEngine?.close() }.isSuccess) clearPrivateCacheDirectory(cache)
                        else cancellationRecoveryRequired = true
                    }
                }
            }
            generationRunning = false
            outputQueue.finish(if (generationResult.isFailure && !stopRequested.get()) lastError else null)
        }
        0
    }

    override fun generateNextChunk(): String? {
        return queue.next()
    }

    override fun requestStop() {
        // Do not wait for beginCompletion's lifecycle lock: native
        // Conversation creation can block while cancellation must still reach
        // the isolated worker independently.
        stopRequested.set(true)
        queue.cancel()
        cancellationGate.request { conversation?.let { current -> { current.cancelProcess() } } }
    }

    override fun requestStopIfActive(): Boolean {
        val active = isGenerationRunning()
        if (active) requestStop()
        return active
    }

    override fun isGenerationRunning(): Boolean = generationRunning || cancellationGate.isPending() || cancellationRecoveryRequired

    override fun canReleasePreparedInputs(): Boolean =
        generationThread?.isAlive != true && !generationRunning &&
            !cancellationGate.isPending() && !cancellationRecoveryRequired

    override fun invalidateConversationContext() {
        invalidateConversationContextImpl()
    }

    override fun getRuntimeStatsJson(): String = liteRtStatsJson()

    override fun shutdown() {
        unloadModel()
    }

    internal fun stopAndJoinGenerationLocked(): Boolean {
        if (cancellationRecoveryRequired) return false
        val deadline = System.nanoTime() + CANCEL_JOIN_TIMEOUT_MS * 1_000_000L
        if (generationThread?.isAlive == true) requestStop()
        if (!cancellationGate.await(CANCEL_JOIN_TIMEOUT_MS)) {
            lastError = "LiteRT-LM native cancellation did not return; worker recovery required."
            cancellationRecoveryRequired = true
            return false
        }
        val running = generationThread ?: run {
            generationRunning = false
            return true
        }
        if (running === Thread.currentThread()) {
            lastError = "LiteRT-LM generation cannot stop itself while unloading."
            return false
        }
        val stopped = runCatching {
            running.join(((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L))
            !running.isAlive
        }.getOrDefault(false)
        if (!stopped) {
            // The isolated worker owns final recovery for a native call that
            // ignores cancellation. Never close Engine/Conversation beneath
            // a still-running callback thread.
            lastError = "LiteRT-LM generation did not stop within the cancellation grace period."
            conversationReusable = false
            return false
        }
        generationThread = null
        generationRunning = false
        return true
    }


    companion object {
        internal const val CANCEL_JOIN_TIMEOUT_MS = 2_000L
    }
}
