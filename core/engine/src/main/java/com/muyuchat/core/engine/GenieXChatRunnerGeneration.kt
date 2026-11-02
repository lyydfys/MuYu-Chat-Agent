package com.muyuchat.core.engine

import android.util.Log
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import java.io.File
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread
import org.json.JSONObject
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_BOUNDARY_TAG
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_RESCUE_PROBE_TOKENS
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_RESCUE_MAX_GENERATED_TOKENS
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_ECHO_FRAGMENT_MAX_CHARS
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_RESCUE_TEMPERATURE
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_RESCUE_TOP_K
import com.muyuchat.core.engine.GenieXChatRunner.Companion.GENIEX_RESCUE_TOP_P

internal fun GenieXChatRunner.beginCompletionLocked(messagesJson: String, paramsJson: String): Int {
        val current = engine ?: return -4.also { lastError = "GenieX model is not loaded." }
        val nativeHandle = handle
        if (nativeHandle == 0L) return -4.also { lastError = "GenieX model handle is empty." }
        if (activeModelType != ModelType.VLM && liteRtLmMessagesContainImageInput(messagesJson)) {
            lastError = "${runtime.label} text runner does not handle image input yet; use the VLM runner path."
            return -5
        }
        if (!stopAndJoinGenerationLocked()) return -7
        val outputQueue = StreamingChunkQueue().also { queue = it }
        lastError = ""
        stopRequested.set(false)
        params = GenerationParams.fromJson(paramsJson)
        params.requireFiniteSamplerValues()
        parameterApplicationJson = "{}"
        completionTokens = 0
        promptTokens = 0
        nativeProfileJson = JSONObject()
        generationTiming.start()
        lastPromptEndsInsideReasoning = false

        val generationConfig = params.toGenieXGenerationConfig()
        val templateEnableThinking = params.reasoningMode != ReasoningMode.OFF && !params.hideReasoning
        // Last user turn text, used only to classify a tiny prompt-fragment echo
        // (e.g. echoing "介绍自己。" back from "用一句话介绍自己。") as collapsed.
        var rescueEchoReference = ""
        val prompt = if (activeModelType == ModelType.VLM) {
            val messages = genieXVlmMessagesFromJson(messagesJson)
            if (messages.isEmpty()) {
                lastError = "GenieX VLM received no chat messages."
                return -6
            }
            rescueEchoReference = runCatching {
                messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
                    ?.contents?.joinToString("") { it.text.orEmpty() }
                    ?.trim().orEmpty()
            }.getOrDefault("")
            val currentTurnImages = genieXCurrentTurnImagePaths(messages)
            generationConfig.imagePaths = currentTurnImages.takeIf { it.isNotEmpty() }
            generationConfig.imageCount = currentTurnImages.size
            runCatching {
                val method = current.javaClass.getDeclaredMethod(
                    "applyChatTemplate",
                    Long::class.javaPrimitiveType,
                    Array<VlmChatMessage>::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                val out = method.invoke(
                    current,
                    nativeHandle,
                    messages,
                    null,
                    templateEnableThinking,
                    true
                )
                formattedTemplateText(requireNotNull(out) { "GenieX VLM returned no chat template output." })
            }.getOrElse { error ->
                lastError = "GenieX VLM chat template failed: ${error.describeForUser()}"
                return -6
            }
        } else {
            val canonicalMessages = genieXLlmMessagesFromJson(messagesJson)
            if (canonicalMessages.isEmpty()) {
                lastError = "GenieX LLM received no chat messages."
                return -6
            }
            val messages = genieXNativeLlmMessages(canonicalMessages)
            rescueEchoReference = canonicalMessages
                .lastOrNull { it.role.equals("user", ignoreCase = true) }
                ?.content?.trim().orEmpty()
            if (!genieXLlmBoundaryIsCanonical(canonicalMessages, messages)) {
                lastError = "GenieX LLM message role/content changed before the JNI boundary."
                return -6
            }
            Log.i(
                GENIEX_BOUNDARY_TAG,
                "LLM messages before JNI: roles=${messages.joinToString(",") { it.role }}, " +
                    "contentLengths=${messages.joinToString(",") { it.content.length.toString() }}"
            )
            runCatching {
                val method = current.javaClass.getDeclaredMethod(
                    "applyChatTemplate",
                    Long::class.javaPrimitiveType,
                    Array<ChatMessage>::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                val out = method.invoke(
                    current,
                    nativeHandle,
                    messages,
                    null,
                    templateEnableThinking,
                    true,
                    true
                )
                val rendered = requireNonBlankGenieXTemplate(
                    formattedTemplateText(requireNotNull(out) { "GenieX LLM returned no chat template output." })
                )
                ensureGenieXUserTurn(rendered, canonicalMessages)
            }.getOrElse { error ->
                lastError = "GenieX LLM chat template failed: ${error.describeForUser()}"
                return -6
            }
        }
        promptTokens = estimateTokens(prompt)
        prefillTotalTokens = promptTokens
        lastPromptEndsInsideReasoning = promptEndsInsideReasoning(prompt)

        // Each request carries its FULL rendered transcript in `prompt`, so the
        // native llama_cpp plugin must not reuse KV/n_past from a previous turn.
        // When a prior identical turn ended on EOS, the plugin's prefix-match
        // rolls back to that EOS boundary and the next decode emits EOS again,
        // producing an empty completion. Clear the native session before every
        // generation (model stays loaded); history is preserved in the prompt.
        val requestResetRc = resetGenieXNativeSession(current, nativeHandle)
        Log.i(
            GENIEX_BOUNDARY_TAG,
            "GenieX beginCompletion reset native session before prefill rc=$requestResetRc, " +
                "promptTokens=$promptTokens."
        )

        generateThread = thread(
            start = true,
            isDaemon = true,
            name = "mca-${runtime.backendId}-generate"
        ) {
            runCatching {
                val generate = if (activeModelType == ModelType.VLM) {
                    current.javaClass.getDeclaredMethod(
                        "generate",
                        Long::class.javaPrimitiveType,
                        String::class.java,
                        GenerationConfig::class.java,
                        LLMTokenCallback::class.java
                    )
                } else {
                    current.javaClass.getDeclaredMethod(
                        "generate",
                        Long::class.javaPrimitiveType,
                        String::class.java,
                        IntArray::class.java,
                        FloatArray::class.java,
                        Int::class.javaPrimitiveType,
                        GenerationConfig::class.java,
                        LLMTokenCallback::class.java
                    )
                }

                // One GenieX native generation attempt. The first few tokens are
                // buffered (instead of streamed immediately) so a degenerate
                // greedy short turn — which only echoes the prompt and then stops
                // on EOS inside the probe window — can be discarded and retried
                // once with gentler sampling. Answers that grow past the window
                // switch to live streaming with no text loss.
                // Returns true ONLY when the attempt collapsed and qualifies for
                // the single soft-sampling rescue.
                fun runAttempt(config: GenerationConfig, isRescueAttempt: Boolean): Boolean {
                    val textAssembler = GenieXUtf8ChunkAssembler()
                    val headBuffer = StringBuilder()
                    var live = false
                    var sawCallbackChunk = false
                    var lastProfile: ProfilingData? = null

                    fun releaseHeadToLive() {
                        if (live) return
                        live = true
                        if (headBuffer.isNotEmpty()) {
                            outputQueue.put(headBuffer.toString())
                            headBuffer.setLength(0)
                        }
                    }

                    fun ingestVisibleChunk(chunk: String) {
                        if (chunk.isEmpty()) return
                        completionTokens += estimateTokens(chunk).coerceAtLeast(1)
                        if (live) {
                            outputQueue.put(chunk)
                        } else {
                            headBuffer.append(chunk)
                            if (completionTokens > GENIEX_RESCUE_PROBE_TOKENS) releaseHeadToLive()
                        }
                    }

                    fun flushTextAssembler() {
                        textAssembler.finish()
                            .takeIf { it.isNotEmpty() }
                            ?.let {
                                sawCallbackChunk = true
                                ingestVisibleChunk(it)
                            }
                    }

                    val callback = object : LLMTokenCallback {
                        override fun onToken(token: String): Boolean {
                            generationTiming.markFirstToken()
                            textAssembler.append(token)
                                .takeIf { it.isNotEmpty() }
                                ?.let {
                                    sawCallbackChunk = true
                                    ingestVisibleChunk(it)
                                }
                            return !stopRequested.get()
                        }

                        override fun onComplete(result: LlmGenerateResult) {
                            flushTextAssembler()
                            lastProfile = runCatching { result.profileData }.getOrNull()
                            if (!sawCallbackChunk) {
                                extractGenieXResultText(result)?.let { raw ->
                                    ingestVisibleChunk(stripGenieXPromptEcho(raw, prompt))
                                }
                            }
                            generationTiming.complete()
                        }
                    }
                    parameterApplicationJson = genieXGenerationParameterApplication(
                        paramsJson, config, templateEnableThinking, runtime,
                        attempt = if (isRescueAttempt) 2 else 1
                    ).toString()
                    val returned = if (activeModelType == ModelType.VLM) {
                        generate.invoke(current, nativeHandle, prompt, config, callback)
                    } else {
                        generate.invoke(
                            current,
                            nativeHandle,
                            prompt,
                            null,
                            null,
                            0,
                            config,
                            callback
                        )
                    }
                    if (!sawCallbackChunk) {
                        extractGenieXResultText(returned)?.let { raw ->
                            ingestVisibleChunk(stripGenieXPromptEcho(raw, prompt))
                        }
                    }
                    flushTextAssembler()
                    generationTiming.complete()
                    lastProfile?.let { nativeProfileJson = it.toJson() }

                    // Already streaming a normal-sized answer: nothing to rescue.
                    if (live) return false
                    // User actively stopped: never auto-retry.
                    if (stopRequested.get()) {
                        if (headBuffer.isNotEmpty()) outputQueue.put(headBuffer.toString())
                        return false
                    }

                    val visible = stripGenieXPromptEcho(headBuffer.toString(), prompt).trim()
                    val nativeGenerated = (lastProfile?.generatedTokens as? Number)?.toInt() ?: -1
                    val tokenCount = if (nativeGenerated >= 0) nativeGenerated else completionTokens
                    val stopReason = lastProfile?.stopReason.orEmpty()
                    val collapsed = visible.isBlank() ||
                        isGenieXMeaninglessShort(visible) ||
                        isGenieXPromptFragmentEcho(visible, rescueEchoReference)
                    val qualifiesRescue = !isRescueAttempt &&
                        collapsed &&
                        tokenCount in 0..GENIEX_RESCUE_MAX_GENERATED_TOKENS &&
                        stopReason.contains("eos", ignoreCase = true) &&
                        isGenieXDegenerateGreedy(params)
                    if (qualifiesRescue) {
                        // Discard the echo-only head; token counters restart so the
                        // delivered stats describe the rescue output only.
                        headBuffer.setLength(0)
                        completionTokens = 0
                        generationTiming.resetDecodeAttempt()
                        return true
                    }
                    if (visible.isNotEmpty()) outputQueue.put(visible)
                    return false
                }

                var needsRescue = runAttempt(generationConfig, isRescueAttempt = false)
                if (needsRescue) {
                    // The native llama_cpp plugin keeps a global KV/n_past across
                    // generate calls and prefix-matches the just-EOS'd turn, so an
                    // identical retry would deterministically pick EOS again. Reset
                    // the native session (model stays loaded) before the soft retry.
                    val resetRc = resetGenieXNativeSession(current, nativeHandle)
                    Log.i(
                        GENIEX_BOUNDARY_TAG,
                        "GenieX greedy short turn collapsed to prompt echo/empty EOS; " +
                            "native session reset rc=$resetRc; running one soft-sampling rescue " +
                            "(temperature=$GENIEX_RESCUE_TEMPERATURE, topK=$GENIEX_RESCUE_TOP_K, " +
                            "topP=$GENIEX_RESCUE_TOP_P)."
                    )
                    val rescueConfig = params.toGenieXGenerationConfig(softenGreedy = true)
                    runAttempt(rescueConfig, isRescueAttempt = true)
                }
            }.onFailure { error ->
                lastError = error.describeForUser()
                generationTiming.complete()
                outputQueue.finish(lastError)
            }
            generationTiming.complete()
            outputQueue.finish(if (lastError.isNullOrBlank() || stopRequested.get()) null else lastError)
        }
        return 0
    }

internal fun GenieXChatRunner.stopAndJoinGenerationLocked(): Boolean {
        requestStopLocked()
        val runningThread = generateThread ?: return true
        if (runningThread === Thread.currentThread()) {
            lastError = "GenieX generation cannot unload itself while its native call is active."
            return false
        }
        runningThread.join(2_000L)
        if (runningThread.isAlive) {
            lastError = "GenieX generation did not stop within the cancellation grace period; worker recovery required."
            return false
        }
        if (generateThread === runningThread) {
            generateThread = null
        }
        return true
    }

internal fun GenieXChatRunner.requestStopLocked() {
        stopRequested.set(true)
        queue.cancel()
        val current = engine
        val nativeHandle = handle
        if (current != null && nativeHandle != 0L) {
            runCatching {
                current.javaClass.getDeclaredMethod("stopStream", Long::class.javaPrimitiveType)
                    .invoke(current, nativeHandle)
            }
        }
    }

internal fun GenieXChatRunner.genieXStatsJson(): String {
        val timing = generationTiming.snapshot()
        return JSONObject()
        .put("backend", runtime.backendId)
        .put("loaded", loaded)
        .put("runnerReady", isAvailable)
        .put("modelPath", modelPath)
        .put("modelName", modelName)
        .put("modelType", activeModelType.name.lowercase())
        .put("promptEndsInsideReasoning", lastPromptEndsInsideReasoning)
        .put("mmprojPath", activeMmprojPath)
        .put("visionBindingPath", activeMmprojPath)
        .put("visionBindingState", visionBindingState.wireName)
        .put("visionProjectorExists", activeMmprojPath?.let { runCatching { File(it).isFile }.getOrDefault(false) } ?: false)
        .put(
            "visionFailureReason",
            genieXVisionFailureReason(
                runtime = runtime,
                loaded = loaded,
                modelType = activeModelType,
                mmprojPath = activeMmprojPath,
                supportsVision = activeVlmCapabilities?.supportsVision
            )
        )
        .put(
            "visionReady",
            genieXVisionReady(
                runtime = runtime,
                loaded = loaded,
                modelType = activeModelType,
                mmprojPath = activeMmprojPath,
                supportsVision = activeVlmCapabilities?.supportsVision
            )
        )
        .put(
            "capabilities",
            JSONObject()
                .put(
                    "supportsVision",
                    genieXVisionReady(
                        runtime = runtime,
                        loaded = loaded,
                        modelType = activeModelType,
                        mmprojPath = activeMmprojPath,
                        supportsVision = activeVlmCapabilities?.supportsVision
                    )
                )
                .put("supportsAudio", activeVlmCapabilities?.supportsAudio ?: false)
        )
        .put("loadMs", loadMs)
        .put("runtimeId", activeRuntimeId)
        .put("computeUnit", activeComputeUnit)
        .put("nGpuLayers", activeGpuLayers)
        .put(
            "nThreads",
            if (isQairtRuntime) {
                if (activeModelType == ModelType.VLM) 8 else 0
            } else {
                loadParams.nThreads.coerceAtLeast(1)
            }
        )
        .put("nThreadsBatch", if (isQairtRuntime) 0 else loadParams.nThreads.coerceAtLeast(1))
        .put("nBatch", if (isQairtRuntime) 0 else 512)
        .put("nUbatch", if (isQairtRuntime) 0 else 128)
        .put("nCtx", loadParams.nCtx)
        .put("maxAllTokens", loadParams.nCtx)
        .put("maxNewTokens", params.effectiveNPredict())
        .put("promptTokens", promptTokens)
        .put("completionTokens", completionTokens)
        .put("promptTokensEstimated", true)
        .put("completionTokensEstimated", true)
        .put("ttftMs", timing.ttftMs)
        .put("prefillMs", nativeProfileJson.optDouble("promptTimeMs", 0.0))
        .put("decodeMs", nativeProfileJson.optDouble("decodeTimeMs", timing.decodeMs.toDouble()))
        .put("decodeTps", nativeProfileJson.optDouble("decodingSpeed", timing.decodeTokensPerSecond(completionTokens)))
        .put("e2eTps", timing.endToEndTokensPerSecond(completionTokens))
        .put("backendDevices", activeBackendDevices)
        .put(
            "chatRoleMode",
            if (activeModelType == ModelType.LLM) "native_roles_geniex_0_10_0_mca1" else "native_roles"
        )
        .put("nativeProfile", nativeProfileJson)
        .put("parameterApplication", JSONObject(parameterApplicationJson))
        .put(
            "qairtBundleReadiness",
            lastQairtBundleReadiness?.let { readiness ->
                JSONObject()
                    .put("canLoad", readiness.canLoad)
                    .put("modelId", readiness.modelId)
                    .put("supportsVision", readiness.supportsVision)
                    .put("rootBinCount", readiness.rootBinPaths.size)
                    .put("diagnostic", readiness.diagnosticSummary())
                    .put("missing", readiness.missingRequiredComponents.joinToString(","))
                    .put("invalid", readiness.invalidRequiredComponents.joinToString(","))
            }
        )
        .put("lastError", lastError)
        .put("nativeReleasePending", nativeReleasePending)
        .put("nativeReleaseFailure", nativeReleaseFailure)
        // Existing isolated-worker recovery channel: a runner that could not release its native
        // owner publishes the same session-lost flag the service already acts on.
        .put("workerSessionLost", nativeReleasePending)
        .toString()
    }

internal fun GenieXChatRunner.formattedTemplateText(output: Any): String =
        runCatching {
            output.javaClass.getMethod("getFormattedText").invoke(output) as String
        }.getOrElse {
            val field = output.javaClass.getDeclaredField("formattedText").apply { isAccessible = true }
            field.get(output) as String
        }

    /** Some GenieX SDK revisions deliver the final text only in the result of
     * generate() (or onComplete) and emit no token callbacks. Preserve that
     * text so the common streaming consumer does not report a false empty
     * completion. */
internal fun GenieXChatRunner.extractGenieXResultText(value: Any?): String? {
        if (value == null) return null
        val candidates = listOf("getText", "getGeneratedText", "getOutputText", "getContent")
        for (name in candidates) {
            val text = runCatching {
                value.javaClass.getMethod(name).invoke(value) as? String
            }.getOrNull()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /** A few GenieX SDK revisions return the fully formatted input prompt as the
     * generation result when no completion callback is emitted. Remove only an
     * exact leading prompt echo; genuine model text is left untouched. */
internal fun GenieXChatRunner.stripGenieXPromptEcho(text: String, prompt: String): String {
        val normalizedText = text.trimStart()
        val normalizedPrompt = prompt.trim()
        return if (normalizedPrompt.isNotEmpty() && normalizedText.startsWith(normalizedPrompt)) {
            normalizedText.removePrefix(normalizedPrompt).trimStart()
        } else {
            text
        }
    }

    /** Protects against GenieX 0.3.x template implementations that silently
     * omit the final user turn for native-role arrays. Keep the SDK-rendered
     * prefix and append only when the user content is demonstrably absent. */
internal fun GenieXChatRunner.ensureGenieXUserTurn(
        rendered: String,
        messages: List<GenieXLlmMessage>
    ): String {
        val user = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
            ?.content
            ?.trim()
            .orEmpty()
        if (user.isBlank() || rendered.contains(user)) return rendered
        return buildString {
            append(rendered.trimEnd())
            append("\n<|im_start|>user\n")
            append(user)
            append("\n<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    /**
     * The rescue only targets the known greedy-degenerate corner
     * (temperature<=0, topK<=1, topP>=1). Any intentionally non-greedy sampler
     * (including the production default 0.6/20/0.95) is left untouched.
     */
internal fun GenieXChatRunner.isGenieXDegenerateGreedy(params: GenerationParams): Boolean =
        params.temperature <= 0f && params.topK <= 1 && params.topP >= 1f

    /**
     * Best-effort native session/KV reset (jni `Llm.reset(handle)`). The model
     * stays loaded; only the cached prefix/history is cleared so the soft-sampling
     * rescue decodes from a clean position instead of prefix-matching a turn that
     * already ended on EOS. Returns the native result code, or null when unavailable.
     */
internal fun GenieXChatRunner.resetGenieXNativeSession(current: Any, nativeHandle: Long): Int? = runCatching {
        val method = current.javaClass.getDeclaredMethod(
            "reset",
            Long::class.javaPrimitiveType
        )
        (method.invoke(current, nativeHandle) as? Number)?.toInt()
    }.onFailure { error ->
        Log.w(GENIEX_BOUNDARY_TAG, "GenieX native reset unavailable: ${error.describeForUser()}")
    }.getOrNull()

    /**
     * A very short completion that is merely a contiguous fragment of the user's
     * own last message is a prompt echo, not an answer (e.g. user asks
     * "用一句话介绍自己。" and the model echoes "介绍自己。"). Whitespace is ignored
     * for the containment check so CJK punctuation spacing cannot mask an echo.
     */
internal fun GenieXChatRunner.isGenieXPromptFragmentEcho(visible: String, referenceUserText: String): Boolean {
        if (visible.isBlank() || referenceUserText.isBlank()) return false
        if (visible.length > GENIEX_ECHO_FRAGMENT_MAX_CHARS) return false
        val compactAnswer = visible.filterNot { it.isWhitespace() }
        val compactReference = referenceUserText.filterNot { it.isWhitespace() }
        if (compactAnswer.isEmpty() || compactAnswer.length >= compactReference.length) return false
        return compactReference.contains(compactAnswer)
    }

    /** A 1-2 char punctuation-only fragment carries no visible answer. */
internal fun GenieXChatRunner.isGenieXMeaninglessShort(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.length > 2) return false
        return text.all { ch ->
            ch.isWhitespace() || ch in "。.，,！!？?、：:；;\"'“”‘’()（）【】[]<>《》"
        }
    }

internal fun GenerationParams.toGenieXGenerationConfig(
        softenGreedy: Boolean = false
    ): GenerationConfig =
        GenerationConfig(
            maxTokens = effectiveNPredict(),
            stopWords = stopWords.takeIf { it.isNotEmpty() }?.toTypedArray(),
            stopCount = stopWords.size,
            samplerConfig = SamplerConfig(
                temperature = if (softenGreedy) GENIEX_RESCUE_TEMPERATURE else temperature,
                topP = if (softenGreedy) GENIEX_RESCUE_TOP_P else topP,
                topK = if (softenGreedy) GENIEX_RESCUE_TOP_K else topK,
                minP = minP,
                repetitionPenalty = repeatPenalty,
                presencePenalty = presencePenalty,
                frequencyPenalty = frequencyPenalty,
                seed = seed ?: 0
            )
        )

internal fun ProfilingData.toJson(): JSONObject = JSONObject()
        .put("ttftMs", ttftMs)
        .put("promptTimeMs", promptTimeMs)
        .put("decodeTimeMs", decodeTimeMs)
        .put("promptTokens", promptTokens)
        .put("generatedTokens", generatedTokens)
        .put("prefillSpeed", prefillSpeed)
        .put("decodingSpeed", decodingSpeed)
        .put("stopReason", stopReason)

internal fun GenieXChatRunner.estimateTokens(text: String): Int =
        (text.length / 3).coerceAtLeast(if (text.isBlank()) 0 else 1)

internal fun Throwable.describeForUser(): String {
        val root = if (this is InvocationTargetException) {
            targetException ?: cause ?: this
        } else {
            cause ?: this
        }
        val message = root.message?.takeIf { it.isNotBlank() }
        return if (message == null) {
            root::class.java.simpleName
        } else {
            "${root::class.java.simpleName}: $message"
        }
    }
