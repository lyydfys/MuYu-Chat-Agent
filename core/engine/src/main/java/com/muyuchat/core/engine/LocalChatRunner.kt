package com.muyuchat.core.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Contents as LiteRtContents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.MessageCallback as LiteRtMessageCallback
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig as LiteRtSamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LLMTokenCallback
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmGenerateResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ModelType
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmCapabilities
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import com.muyuchat.core.modelstore.QairtBundleReadiness
import com.muyuchat.core.modelstore.QairtBundleReadinessAnalyzer
import com.muyuchat.core.modelstore.validateLiteRtLmLoadPreflight
import com.muyuchat.core.nativebridge.NativeLlamaBridge
import com.muyuchat.core.nativebridge.NativeMnnBridge
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

enum class LocalChatRuntime(val backendId: String, val label: String) {
    MNN_CPU("mnn_cpu", "MNN 高速引擎"),
    LLAMA_CPP("llama_cpp", "GGUF 兼容引擎"),
    GENIEX_LLAMA_CPP("geniex_llama_cpp", "GenieX llama.cpp / 骁龙 HTP"),
    GENIEX_QAIRT("geniex_qairt", "GenieX QAIRT NPU"),
    LITERT_LM("litert_lm", "LiteRT-LM 引擎")
}

private data class PreparedGenieXModel(
    val modelName: String,
    val modelPath: String,
    val tokenizerPath: String?,
    val runtimeId: String,
    val modelType: ModelType,
    val mmprojPath: String? = null
)

internal fun detectGenieXModelType(
    input: File,
    explicitMmprojPath: String? = null
): ModelType {
    if (!explicitMmprojPath.isNullOrBlank()) return ModelType.VLM
    val signalText = genieXModelSignalText(input)
    return if (hasGenieXVisionSignal(input, signalText)) ModelType.VLM else ModelType.LLM
}

/**
 * GenieX callbacks can mix normal JVM Unicode with GPT-2's reversible bytes-to-Unicode alphabet.
 * A possible UTF-8 lead byte is held across callbacks and decoded only after a complete, valid
 * sequence arrives. GPT-2's U+0100-and-above escape characters are interpreted only as
 * continuation bytes for such a sequence; otherwise they are emitted verbatim because they are
 * indistinguishable from intentional Latin Extended-A text.
 */
internal class GenieXUtf8ChunkAssembler {
    private val pendingUtf8Chars = StringBuilder(4)
    private val pendingUtf8Bytes = IntArray(4)
    private var pendingUtf8Size = 0
    private var expectedUtf8Size = 0
    private var pendingHighSurrogate: Char? = null

    fun append(chunk: String): String {
        if (chunk.isEmpty()) return ""
        val output = StringBuilder()
        var index = 0

        pendingHighSurrogate?.let { highSurrogate ->
            flushPendingUtf8(output)
            output.append(highSurrogate)
            if (Character.isLowSurrogate(chunk.first())) {
                output.append(chunk.first())
                index = 1
            }
            pendingHighSurrogate = null
        }

        while (index < chunk.length) {
            val char = chunk[index]
            when {
                Character.isHighSurrogate(char) -> {
                    flushPendingUtf8(output)
                    if (index == chunk.lastIndex) {
                        pendingHighSurrogate = char
                        index += 1
                    } else {
                        output.append(char)
                        if (Character.isLowSurrogate(chunk[index + 1])) {
                            output.append(chunk[index + 1])
                            index += 2
                        } else {
                            index += 1
                        }
                    }
                }

                Character.isLowSurrogate(char) -> {
                    flushPendingUtf8(output)
                    output.append(char)
                    index += 1
                }

                else -> {
                    appendBmpChar(char, output)
                    index += 1
                }
            }
        }
        return output.toString()
    }

    fun finish(): String = buildString {
        flushPendingUtf8(this)
        pendingHighSurrogate?.let(::append)
        pendingHighSurrogate = null
    }

    private fun appendBmpChar(char: Char, output: StringBuilder) {
        val byte = gpt2Byte(char)
        if (expectedUtf8Size != 0) {
            if (byte != null && byte in UTF8_CONTINUATION_RANGE) {
                pendingUtf8Chars.append(char)
                pendingUtf8Bytes[pendingUtf8Size] = byte
                pendingUtf8Size += 1
                if (pendingUtf8Size == expectedUtf8Size) {
                    val codePoint = decodePendingUtf8()
                    if (codePoint == null) {
                        output.append(pendingUtf8Chars)
                    } else {
                        output.appendCodePoint(codePoint)
                    }
                    clearPendingUtf8()
                }
                return
            }
            flushPendingUtf8(output)
        }

        if (byte == null || char.code != byte) {
            output.append(char)
            return
        }

        when (val sequenceSize = utf8SequenceSize(byte)) {
            1 -> output.append(byte.toChar())
            2, 3, 4 -> {
                pendingUtf8Chars.append(char)
                pendingUtf8Bytes[0] = byte
                pendingUtf8Size = 1
                expectedUtf8Size = sequenceSize
            }
            else -> output.append(char)
        }
    }

    private fun decodePendingUtf8(): Int? {
        val first = pendingUtf8Bytes[0]
        val second = pendingUtf8Bytes[1]
        return when (expectedUtf8Size) {
            2 -> ((first and 0x1F) shl 6) or (second and 0x3F)
            3 -> {
                if ((first == 0xE0 && second < 0xA0) ||
                    (first == 0xED && second >= 0xA0)
                ) {
                    null
                } else {
                    ((first and 0x0F) shl 12) or
                        ((second and 0x3F) shl 6) or
                        (pendingUtf8Bytes[2] and 0x3F)
                }
            }
            4 -> {
                if ((first == 0xF0 && second < 0x90) ||
                    (first == 0xF4 && second > 0x8F)
                ) {
                    null
                } else {
                    ((first and 0x07) shl 18) or
                        ((second and 0x3F) shl 12) or
                        ((pendingUtf8Bytes[2] and 0x3F) shl 6) or
                        (pendingUtf8Bytes[3] and 0x3F)
                }
            }
            else -> null
        }
    }

    private fun flushPendingUtf8(output: StringBuilder) {
        if (pendingUtf8Size == 0) return
        output.append(pendingUtf8Chars)
        clearPendingUtf8()
    }

    private fun clearPendingUtf8() {
        pendingUtf8Chars.setLength(0)
        pendingUtf8Size = 0
        expectedUtf8Size = 0
    }

    private fun gpt2Byte(char: Char): Int? {
        val codePoint = char.code
        if (codePoint >= GPT2_CHAR_TO_BYTE.size) return null
        return GPT2_CHAR_TO_BYTE[codePoint].takeIf { it >= 0 }
    }

    private fun utf8SequenceSize(firstByte: Int): Int = when (firstByte) {
        in 0x00..0x7F -> 1
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 0
    }

    private companion object {
        val UTF8_CONTINUATION_RANGE = 0x80..0xBF

        val GPT2_CHAR_TO_BYTE = IntArray(0x144) { -1 }.apply {
            for (byte in 0x21..0x7E) this[byte] = byte
            for (byte in 0xA1..0xAC) this[byte] = byte
            for (byte in 0xAE..0xFF) this[byte] = byte

            var encodedCodePoint = 0x100
            for (byte in 0x00..0xFF) {
                val isDirect = byte in 0x21..0x7E || byte in 0xA1..0xAC || byte in 0xAE..0xFF
                if (!isDirect) {
                    this[encodedCodePoint] = byte
                    encodedCodePoint += 1
                }
            }
        }
    }
}

internal fun destroyGenieXHandle(engine: Any, nativeHandle: Long): Int {
    val destroy = engine.javaClass
        .getDeclaredMethod("destroy", Long::class.javaPrimitiveType)
        .apply { isAccessible = true }
    return destroy.invoke(engine, nativeHandle) as? Int
        ?: error("GenieX destroy returned no result.")
}

private fun genieXModelSignalText(input: File): String {
    val metadata = input.takeIf(File::isDirectory)
        ?.resolve("metadata.json")
        ?.takeIf(File::isFile)
        ?.let { runCatching { it.readText() }.getOrNull() }
        .orEmpty()
    return listOf(input.name, input.parentFile?.name.orEmpty(), metadata)
        .joinToString(" ")
        .lowercase()
}

internal fun resolveQairtBundleRootForLoad(bundleDir: File): File? {
    val root = runCatching { bundleDir.canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    if (root.hasQairtLoadRootMarker()) return root
    val entries = root.listFiles()?.toList() ?: return null
    if (entries.size != 1) return null
    val child = runCatching { entries.single().canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    if (child.parentFile?.canonicalFile?.path != root.path || !child.hasQairtLoadRootMarker()) return null
    return child
}

private fun File.hasQairtLoadRootMarker(): Boolean = listOf("metadata.json", "genie_config.json")
    .any { name ->
        val candidate = File(this, name)
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
        canonical?.isFile == true && canonical.parentFile?.canonicalFile?.path == path
    }

private fun hasGenieXVisionSignal(input: File, signalText: String): Boolean {
    val namedSignal = listOf(
        "multimodal",
        "vision-language",
        "vision_language",
        "vision language",
        "qwen3-vl",
        "qwen3_vl",
        "qwen2.5-vl",
        "qwen2_5_vl",
        "minicpm-v",
        "minicpm_v",
        "fastvlm"
    ).any(signalText::contains)
    val shortSignal = Regex("(^|[^a-z0-9])vlm?([^a-z0-9]|$)").containsMatchIn(signalText)
    val visionFile = input.takeIf(File::isDirectory)
        ?.walkTopDown()
        ?.maxDepth(3)
        ?.any { candidate ->
            if (!candidate.isFile) return@any false
            val name = candidate.name.lowercase()
            name.contains("vision_encoder") ||
                name.contains("visual_encoder") ||
                name.contains("mmproj") ||
                name.contains("projector")
        } == true
    return namedSignal || shortSignal || visionFile
}

internal fun genieXVlmMessagesFromJson(messagesJson: String): Array<VlmChatMessage> {
    val array = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return emptyArray()
    return Array(array.length()) { index ->
        val item = array.optJSONObject(index) ?: JSONObject()
        val contents = when (val raw = item.opt("content")) {
            is JSONArray -> genieXVlmContents(raw)
            is JSONObject -> listOf(VlmContent("text", raw.toString()))
            else -> listOf(VlmContent("text", item.optString("content")))
        }.ifEmpty { listOf(VlmContent("text", "")) }
        VlmChatMessage(
            role = item.optString("role", "user"),
            contents = contents
        )
    }
}

private fun genieXVlmContents(parts: JSONArray): List<VlmContent> = buildList {
    for (index in 0 until parts.length()) {
        val part = parts.optJSONObject(index) ?: continue
        when (part.optString("type").lowercase()) {
            "text", "input_text" -> add(VlmContent("text", part.optString("text")))
            "image", "image_url", "input_image" -> {
                val imageValue = part.opt("image_url") ?: part.opt("image")
                val path = when (imageValue) {
                    is JSONObject -> imageValue.optString("url")
                    is String -> imageValue
                    else -> part.optString("url")
                }.removePrefix("file://")
                if (path.isNotBlank()) add(VlmContent("image", path))
            }
        }
    }
}

internal fun genieXCurrentTurnImagePaths(messages: Array<VlmChatMessage>): Array<String> {
    val currentUserMessage = messages.lastOrNull { it.role.equals("user", ignoreCase = true) }
        ?: return emptyArray()
    return currentUserMessage.contents
        .asSequence()
        .filter { it.type.equals("image", ignoreCase = true) }
        .mapNotNull { it.text?.trim()?.takeIf(String::isNotBlank) }
        .toList()
        .toTypedArray()
}

internal data class GenieXLlmMessage(
    val role: String,
    val content: String
)

internal fun genieXLlmMessagesFromJson(messagesJson: String): List<GenieXLlmMessage> {
    val array = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return emptyList()
    return List(array.length()) { index ->
        val item = array.optJSONObject(index) ?: JSONObject()
        GenieXLlmMessage(
            role = item.optString("role", "user"),
            content = when (val raw = item.opt("content")) {
                is JSONArray -> raw.genieXTextContent()
                is JSONObject -> raw.toString()
                else -> item.optString("content")
            }
        )
    }
}

internal fun genieXNativeLlmMessages(messages: List<GenieXLlmMessage>): Array<ChatMessage> =
    messages.map { message ->
        ChatMessage(
            role = message.role,
            content = message.content
        )
    }.toTypedArray()

internal fun genieXLlmBoundaryIsCanonical(
    canonical: List<GenieXLlmMessage>,
    sdkMessages: Array<ChatMessage>
): Boolean = canonical.size == sdkMessages.size && canonical.indices.all { index ->
    canonical[index].role == sdkMessages[index].role &&
        canonical[index].content == sdkMessages[index].content
}

internal fun requireNonBlankGenieXTemplate(value: String): String =
    value.takeIf(String::isNotBlank)
        ?: error("GenieX LLM returned an empty chat template.")

private fun JSONArray.genieXTextContent(): String = buildString {
    for (index in 0 until length()) {
        val part = optJSONObject(index) ?: continue
        if (part.optString("type") == "text") {
            if (isNotEmpty()) append('\n')
            append(part.optString("text"))
        }
    }
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
    /** Non-blocking process/session loss evidence; implementations must not perform Binder or JNI IO. */
    fun isSessionKnownLost(): Boolean = false
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

internal class LlamaCppChatRunner(
    private val bridge: NativeLlamaBridge = NativeLlamaBridge()
) : LocalChatRunner {
    override val runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP
    override val isAvailable: Boolean
        get() = NativeLlamaBridge.isAvailable
    override val loadError: Throwable?
        get() = NativeLlamaBridge.loadError

    override fun initBackends(nativeLibDir: String) = bridge.initBackends(nativeLibDir)
    override fun loadModel(modelPath: String, paramsJson: String): Int = bridge.loadModel(modelPath, paramsJson)
    override fun unloadModel() = bridge.unloadModel()
    override fun beginCompletion(messagesJson: String, paramsJson: String): Int =
        bridge.beginCompletion(messagesJson, paramsJson)
    override fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        prefixCache: PersistentPrefixCacheRequest?
    ): Int = if (prefixCache == null) {
        bridge.beginCompletion(messagesJson, paramsJson)
    } else {
        bridge.beginCompletionWithPrefixCache(
            messagesJson = messagesJson,
            paramsJson = paramsJson,
            restoreStatePath = prefixCache.restoreStatePath,
            writeStatePath = prefixCache.writeStatePath,
            fixedSystemPrompt = prefixCache.fixedSystemPrompt,
            fullSessionState = prefixCache.fullSessionState
        )
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
    override fun getRuntimeStatsJson(): String = bridge.getRuntimeStatsJson()
    override fun shutdown() = bridge.shutdown()
}

/**
 * Keeps the explicit GenieX llama.cpp CPU choice on the stable MCA llama.cpp
 * bridge.  GenieX 0.3.12's llama plugin is linked against an older ggml/llama
 * ABI than the b10590 libraries shipped by MCA; loading that plugin for a CPU
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

    private fun loadWithCpuFallback(modelPath: String, paramsJson: String): Int {
        unloadModel()
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
        unloadModel()
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
        activeDelegate = null
        executionPath = EXECUTION_PATH_IDLE
        lastError = null
        runCatching { delegate?.unloadModel() }
    }

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

    override fun isSessionKnownLost(): Boolean = activeDelegate?.isSessionKnownLost() == true

    override fun sessionRecoveryPolicy(): LocalChatSessionRecoveryPolicy =
        activeDelegate?.sessionRecoveryPolicy()
            ?: LocalChatSessionRecoveryPolicy.AUTOMATIC

    override fun sessionRecoveryMessage(): String? =
        activeDelegate?.sessionRecoveryMessage() ?: lastError

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
    override val runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
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

    override fun loadModel(modelPath: String, paramsJson: String): Int =
        if (isAvailable) bridge.loadModel(modelPath, paramsJson) else UNAVAILABLE_RC

    override fun unloadModel() {
        if (isAvailable) bridge.unloadModel()
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int =
        if (isAvailable) bridge.beginCompletion(messagesJson, paramsJson) else UNAVAILABLE_RC

    override fun generateNextChunk(): String? =
        if (isAvailable) bridge.generateNextChunk() else null

    override fun requestStop() {
        if (isAvailable) bridge.requestStop()
    }

    override fun requestStopIfActive(): Boolean =
        isAvailable && bridge.requestStopIfActive()

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
            bridge.getRuntimeStatsJson()
        } else {
            unavailableStats(runtime, loadError).toString()
        }

    override fun shutdown() {
        if (isAvailable) bridge.shutdown()
    }
}

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
internal class LiteRtLmChatRunner : LocalChatRunner {
    override val runtime: LocalChatRuntime = LocalChatRuntime.LITERT_LM

    override val isAvailable: Boolean
        get() = classesPresent

    override val loadError: Throwable?
        get() = loadFailure ?: if (!classesPresent) {
            IllegalStateException("LiteRT-LM Android classes are not on the runtime classpath.")
        } else {
            null
        }

    private data class LiteRtMessageSpec(
        val role: String,
        val content: String
    )

    private data class LiteRtLoadConfig(
        val backend: String,
        val maxNumTokens: Int,
        val cacheDir: String?,
        val nThreads: Int
    )

    private val lifecycleLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var nativeLibDir: String = ""
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var generationThread: Thread? = null
    @Volatile private var generationRunning = false
    @Volatile private var loaded = false
    @Volatile private var loadFailure: Throwable? = null
    @Volatile private var lastError: String = ""
    @Volatile private var modelPath: String? = null
    @Volatile private var activeConfig: LiteRtLoadConfig = LiteRtLoadConfig(
        backend = "cpu",
        maxNumTokens = 4096,
        cacheDir = null,
        nThreads = 4
    )
    @Volatile private var loadMs: Long = 0L
    @Volatile private var startedAt: Long = 0L
    @Volatile private var firstTokenAt: Long = 0L
    @Volatile private var completedAt: Long = 0L
    @Volatile private var promptTokens: Int = 0
    @Volatile private var completionTokens: Int = 0
    @Volatile private var conversationHistory: List<LiteRtMessageSpec> = emptyList()
    /** Sampler settings are immutable per LiteRT conversation. */
    @Volatile private var conversationSamplerValues: LiteRtSamplerValues? = null

    private val classesPresent: Boolean by lazy {
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
        val started = System.currentTimeMillis()
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
            val engineConfig = EngineConfig(
                modelPath = file.absolutePath,
                backend = backendFor(config),
                maxNumTokens = config.maxNumTokens,
                cacheDir = config.cacheDir
            )
            // BenchmarkInfo is used for real prefill/decode diagnostics. The
            // flag is read only when Engine is constructed.
            ExperimentalFlags.enableBenchmark = true
            val createdEngine = try {
                Engine(engineConfig).also { it.initialize() }
            } catch (firstError: Throwable) {
                // LiteRT GPU/compiled-model caches are backend- and artifact-
                // specific.  A cache produced by an older delegate revision
                // can load successfully but fail the first compiled invoke
                // with Status Code 13.  Invalidate only this model's private
                // cache and retry one clean engine initialization; CPU/NPU
                // paths retain their original failure semantics.
                val cacheDir = config.cacheDir
                val recoverable = config.backend.equals("gpu", ignoreCase = true) &&
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
                Engine(engineConfig).also { it.initialize() }
            }
            engine = createdEngine
            // Conversation creation is deferred until the first request so its
            // sampler config reflects the request instead of silently using
            // LiteRT's defaults. This also avoids allocating an unused KV
            // cache during model load.
            conversation = null
            conversationSamplerValues = null
            activeConfig = config
            this@LiteRtLmChatRunner.modelPath = file.absolutePath
            loaded = true
            loadFailure = null
            lastError = ""
            loadMs = System.currentTimeMillis() - started
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
            loadFailure = error
            lastError = describe(error)
            loaded = false
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            conversation = null
            engine = null
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
        listOf(system, htp).forEach { library ->
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

    override fun unloadModel(): Unit = synchronized(lifecycleLock) {
        if (!stopAndJoinGenerationLocked()) return@synchronized
        val oldConversation = conversation
        val oldEngine = engine
        conversation = null
        engine = null
        loaded = false
        conversationHistory = emptyList()
        conversationSamplerValues = null
        queue.clear()
        runCatching { oldConversation?.close() }
        runCatching { oldEngine?.close() }
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int = synchronized(lifecycleLock) {
        if (!loaded || engine == null) {
            lastError = "LiteRT-LM model is not loaded."
            return@synchronized -4
        }
        if (!stopAndJoinGenerationLocked()) return@synchronized -7
        val messages = runCatching { parseMessages(messagesJson) }.getOrElse { error ->
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
        val generationParams = GenerationParams.fromJson(paramsJson)
        val requestedSamplerValues = liteRtSamplerValuesFor(
            generationParams,
            activeConfig.backend
        )
        val currentConversation = if (
            conversationHistory == prefix &&
            conversationSamplerValues == requestedSamplerValues &&
            conversation != null
        ) {
            conversation
        } else {
            runCatching {
                conversation?.close()
                val created = engine?.createConversation(
                    ConversationConfig(
                        initialMessages = prefix.map(::toLiteRtMessage),
                        samplerConfig = requestedSamplerValues?.toSdkConfig()
                    )
                ) ?: error("LiteRT-LM engine is not initialized.")
                conversation = created
                conversationHistory = prefix
                conversationSamplerValues = requestedSamplerValues
                created
            }.getOrElse { error ->
                lastError = "LiteRT-LM conversation creation failed: ${describe(error)}"
                return@synchronized -6
            }
        } ?: return@synchronized -4

        queue.clear()
        stopRequested.set(false)
        lastError = ""
        startedAt = System.currentTimeMillis()
        firstTokenAt = 0L
        completedAt = 0L
        promptTokens = estimateTokens(latest.content)
        completionTokens = 0
        generationRunning = true
        generationThread = thread(
            start = true,
            isDaemon = true,
            name = "mca-${runtime.backendId}-generate"
        ) {
            val output = StringBuilder()
            runCatching {
                val repetitionPenalty = RepetitionPenaltyConfig(
                    repetitionPenalty = generationParams.repeatPenalty.coerceAtLeast(1.0f),
                    presencePenalty = generationParams.presencePenalty,
                    frequencyPenalty = generationParams.frequencyPenalty
                )
                val thinking = generationParams.reasoningMode
                    .takeIf { it != ReasoningMode.OFF }
                    ?.let {
                        ThinkingConfig(
                            enableThinking = true,
                            thinkingTokenBudget = generationParams.effectiveThinkingBudget()
                        )
                    }
                val completed = CountDownLatch(1)
                var callbackError: Throwable? = null
                val callback = object : LiteRtMessageCallback {
                    override fun onMessage(message: LiteRtMessage) {
                        val chunk = message.toString()
                        if (chunk.isNotEmpty()) {
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                            completionTokens += estimateTokens(chunk).coerceAtLeast(1)
                            output.append(chunk)
                            queue.put(chunk)
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
                currentConversation.sendMessageAsync(
                    latest.content,
                    callback,
                    repetitionPenaltyConfig = repetitionPenalty,
                    maxOutputToken = generationParams.effectiveNPredict(),
                    thinkingConfig = thinking
                )
                while (!completed.await(250L, TimeUnit.MILLISECONDS)) {
                    if (stopRequested.get()) {
                        runCatching { currentConversation.cancelProcess() }
                    }
                }
                callbackError?.let { throw it }
            }.onFailure { error ->
                if (!stopRequested.get()) {
                    if (activeConfig.backend.equals("gpu", ignoreCase = true) &&
                        activeConfig.cacheDir != null &&
                        isCompiledModelCacheFailure(error)
                    ) {
                        // Compiled-model failures can occur on the first
                        // conversation invoke (not only Engine.initialize).
                        // Drop the private delegate cache and invalidate this
                        // session so the service's normal recovery path will
                        // recreate the engine cleanly on the next request.
                        clearPrivateCacheDirectory(File(activeConfig.cacheDir))
                        runCatching { conversation?.close() }
                        runCatching { engine?.close() }
                        conversation = null
                        engine = null
                        loaded = false
                        conversationHistory = emptyList()
                        conversationSamplerValues = null
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
                    queue.put(ERROR_PREFIX + lastError)
                }
            }
            completedAt = System.currentTimeMillis()
            if (!stopRequested.get() && output.isNotEmpty()) {
                conversationHistory = prefix + latest + LiteRtMessageSpec("model", output.toString())
            }
            generationRunning = false
            queue.put(DONE)
        }
        0
    }

    override fun generateNextChunk(): String? {
        while (true) {
            val item = queue.poll(250, TimeUnit.MILLISECONDS)
            if (item == null) {
                if (!generationRunning && queue.isEmpty()) return null
                continue
            }
            if (item == DONE) return null
            if (item.startsWith(ERROR_PREFIX)) {
                throw IllegalStateException(item.removePrefix(ERROR_PREFIX).ifBlank {
                    "LiteRT-LM generation failed without a diagnostic."
                })
            }
            return item
        }
    }

    override fun requestStop() {
        synchronized(lifecycleLock) {
            stopRequested.set(true)
            runCatching { conversation?.cancelProcess() }
        }
    }

    override fun requestStopIfActive(): Boolean {
        synchronized(lifecycleLock) {
            val active = generationRunning
            if (active) {
                stopRequested.set(true)
                runCatching { conversation?.cancelProcess() }
            }
            return active
        }
    }

    override fun invalidateConversationContext() {
        synchronized(lifecycleLock) {
            if (!stopAndJoinGenerationLocked()) return@synchronized
            runCatching { conversation?.close() }
            conversation = null
            conversationHistory = emptyList()
            conversationSamplerValues = null
        }
    }

    override fun getRuntimeStatsJson(): String {
        val benchmark = runCatching { conversation?.getBenchmarkInfo() }.getOrNull()
        val conversationTokenCount = runCatching { conversation?.getTokenCount() ?: 0 }.getOrDefault(0)
        val now = System.currentTimeMillis()
        val decodeMs = if (completedAt > 0L && firstTokenAt > 0L) {
            (completedAt - firstTokenAt).coerceAtLeast(1L)
        } else {
            0L
        }
        val ttftMs = if (startedAt > 0L && firstTokenAt > 0L) {
            (firstTokenAt - startedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        val effectiveDecodeTps = benchmark?.lastDecodeTokensPerSecond ?:
            if (decodeMs > 0L) completionTokens * 1000.0 / decodeMs else 0.0
        val effectivePrefillTps = benchmark?.lastPrefillTokensPerSecond ?: 0.0
        return JSONObject()
            .put("backend", runtime.backendId)
            .put("backendMode", activeConfig.backend)
            .put("backendDevices", "LiteRT-LM ${activeConfig.backend.uppercase()}")
            .put("loaded", loaded)
            .put("runnerReady", isAvailable)
            .put("modelPath", modelPath)
            .put("loadMs", loadMs)
            .put("nCtx", activeConfig.maxNumTokens)
            .put("maxAllTokens", activeConfig.maxNumTokens)
            .put("maxNewTokens", 0)
            .put("promptTokens", benchmark?.lastPrefillTokenCount ?: promptTokens)
            .put("prefillTokens", benchmark?.lastPrefillTokenCount ?: 0)
            .put("completionTokens", benchmark?.lastDecodeTokenCount ?: completionTokens)
            .put("conversationTokenCount", conversationTokenCount)
            .put("kvCacheTokens", conversationTokenCount)
            .put("ttftMs", if (benchmark != null) (benchmark.timeToFirstTokenInSecond * 1000.0).toLong() else ttftMs)
            .put("prefillTps", effectivePrefillTps)
            .put("decodeTps", effectiveDecodeTps)
            .put("prefillMs", if (effectivePrefillTps > 0.0 && promptTokens > 0) promptTokens * 1000.0 / effectivePrefillTps else 0.0)
            .put("decodeMs", decodeMs)
            .put("e2eTps", if (completedAt > 0L && startedAt > 0L && completionTokens > 0) {
                completionTokens * 1000.0 / (completedAt - startedAt).coerceAtLeast(1L)
            } else 0.0)
            .put("effectiveConfig", JSONObject()
                .put("backend", activeConfig.backend)
                .put("max_num_tokens", activeConfig.maxNumTokens)
                .put("n_threads", activeConfig.nThreads)
                .apply { activeConfig.cacheDir?.let { put("cache_dir", it) } })
            .put("benchmarkEnabled", benchmark != null)
            .put("statsAt", now)
            .put("lastError", lastError)
            .toString()
    }

    override fun shutdown() {
        unloadModel()
    }

    private fun stopAndJoinGenerationLocked(): Boolean {
        stopRequested.set(true)
        runCatching { conversation?.cancelProcess() }
        val running = generationThread ?: run {
            generationRunning = false
            return true
        }
        if (running === Thread.currentThread()) {
            lastError = "LiteRT-LM generation cannot stop itself while unloading."
            return false
        }
        runCatching { running.join() }
        generationThread = null
        generationRunning = false
        return true
    }

    private fun parseLoadConfig(paramsJson: String): LiteRtLoadConfig {
        val root = JSONObject(paramsJson.ifBlank { "{}" })
        val advanced = root.opt("advanced_json")
            ?.let { raw ->
                when (raw) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrNull()
                    else -> null
                }
            }
        fun value(vararg names: String): Any? = names.asSequence().mapNotNull { name ->
            root.opt(name).takeIf { it != JSONObject.NULL }
                ?: advanced?.opt(name)?.takeIf { it != JSONObject.NULL }
        }.firstOrNull()
        val backend = canonicalBackend(value("backend", "backend_type", "backendType")?.toString()) ?: "cpu"
        val maxTokens = integerValue(value("max_num_tokens", "maxNumTokens", "n_ctx"))
            ?: 4096
        val threads = integerValue(value("n_threads", "thread_count", "threadCount"))?.coerceAtLeast(1) ?: 4
        val cache = value("cache_dir", "cacheDir", "cache_directory")?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
        return LiteRtLoadConfig(
            backend = backend,
            maxNumTokens = maxTokens.coerceAtLeast(1),
            cacheDir = cache,
            nThreads = threads
        )
    }

    private fun backendFor(config: LiteRtLoadConfig): LiteRtBackend = when (config.backend) {
        "gpu" -> LiteRtBackend.GPU()
        "npu" -> LiteRtBackend.NPU(nativeLibDir)
        "google_tensor" -> LiteRtBackend.GOOGLE_TENSOR()
        else -> LiteRtBackend.CPU(threadCount = config.nThreads)
    }

    private fun parseMessages(messagesJson: String): List<LiteRtMessageSpec> {
        val array = JSONArray(messagesJson.ifBlank { "[]" })
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                // LiteRT-LM calls the assistant turn `model`; callers of the
                // Local API commonly send `assistant`. Normalize both before
                // comparing against the retained Conversation history so a
                // normal assistant response does not force a fresh KV cache.
                val role = canonicalLiteRtMessageRole(item.optString("role", "user"))
                    ?: continue
                val content = when (val raw = item.opt("content")) {
                    is String -> raw
                    is JSONArray -> buildString {
                        for (partIndex in 0 until raw.length()) {
                            val part = raw.optJSONObject(partIndex)
                            if (part?.optString("type") == "text") append(part.optString("text"))
                        }
                    }
                    is JSONObject -> raw.optString("text")
                    else -> raw?.toString().orEmpty()
                }
                add(LiteRtMessageSpec(role = role, content = content))
            }
        }
    }

    private fun toLiteRtMessage(message: LiteRtMessageSpec): LiteRtMessage = when (message.role) {
        "system" -> LiteRtMessage.system(message.content)
        "assistant", "model" -> LiteRtMessage.model(message.content)
        "tool" -> LiteRtMessage.tool(LiteRtContents.of(message.content))
        else -> LiteRtMessage.user(message.content)
    }

    private fun canonicalBackend(raw: String?): String? = when (
        raw?.trim()?.lowercase()?.replace('-', '_')
    ) {
        "cpu", "host" -> "cpu"
        "gpu", "opencl", "open_cl" -> "gpu"
        "npu", "qnn", "qualcomm" -> "npu"
        "google_tensor", "googletensor", "tpu", "google_tensor_processor" -> "google_tensor"
        else -> null
    }

    private fun integerValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun estimateTokens(text: String): Int =
        (text.length / 3).coerceAtLeast(if (text.isBlank()) 0 else 1)

    private fun describe(error: Throwable): String {
        val root = error.cause ?: error
        return root.message?.takeIf { it.isNotBlank() }
            ?: root::class.java.simpleName
    }

    companion object {
        private const val DONE = "\u0000MCA_LITERT_DONE"
        private const val ERROR_PREFIX = "\u0000MCA_LITERT_ERROR:"
    }
}

/**
 * Returns the role spelling used by LiteRT-LM for a Local API chat role.
 * `assistant` and `model` represent the same turn to LiteRT-LM and therefore
 * must share one spelling in both the Conversation seed and cache comparison.
 */
internal fun canonicalLiteRtMessageRole(raw: String): String? = when (raw.trim().lowercase()) {
    "system" -> "system"
    "user" -> "user"
    "assistant", "model" -> "model"
    "tool" -> "tool"
    else -> null
}

/**
 * Converts the shared generation settings to LiteRT-LM's immutable sampler
 * configuration. Qualcomm/Google Tensor backends intentionally use the
 * engine's sampler defaults because those delegates do not expose the
 * conversation sampler controls in the current LiteRT-LM release.
 */
internal data class LiteRtSamplerValues(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int
) {
    fun toSdkConfig(): LiteRtSamplerConfig = LiteRtSamplerConfig(
        topK = topK,
        topP = topP,
        temperature = temperature,
        seed = seed
    )
}

internal fun liteRtSamplerValuesFor(
    params: GenerationParams,
    backend: String
): LiteRtSamplerValues? {
    val normalizedBackend = backend.trim().lowercase().replace('-', '_')
    if (normalizedBackend == "npu" || normalizedBackend == "google_tensor") return null

    val topP = params.topP
        .takeIf { it.isFinite() }
        ?.toDouble()
        ?.coerceIn(0.0, 1.0)
        ?: 0.95
    val temperature = params.temperature
        .takeIf { it.isFinite() }
        ?.toDouble()
        ?.coerceAtLeast(0.0)
        ?: 0.6
    return LiteRtSamplerValues(
        topK = params.topK.coerceAtLeast(1),
        topP = topP,
        temperature = temperature,
        seed = params.seed ?: 0
    )
}

internal fun requireSuccessfulGenieXSdkInit(reportedFailure: String?) {
    val failure = reportedFailure?.trim().orEmpty()
    if (failure.isNotEmpty()) throw IllegalStateException(failure)
}

/**
 * GenieX exposes a process-wide SDK singleton.  Keep its one-time init state
 * outside individual runner instances so switching between the llama.cpp and
 * QAIRT runners cannot register the same native plugin more than once.
 */
private object GenieXProcessInitState {
    val lock = Any()
    var sdkInitAttempted = false
    var sdkInitError: String? = null
    var modelManagerInitAttempted = false
    var modelManagerInitError: String? = null
}

private val GENIEX_PROCESS_INIT = GenieXProcessInitState

internal class GenieXChatRunner(
    override val runtime: LocalChatRuntime,
    private val requestedRuntimeId: String,
    private val defaultComputeUnit: String,
    private val defaultBackendDevices: String,
    private val appContext: Context? = null
) : LocalChatRunner {
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
    @Volatile private var modelPath: String? = null
    @Volatile private var modelName: String = ""
    @Volatile private var loaded = false
    @Volatile private var lastError: String? = null
    @Volatile private var activeRuntimeId: String = requestedRuntimeId
    @Volatile private var activeComputeUnit: String = defaultComputeUnit
    @Volatile private var activeBackendDevices: String = defaultBackendDevices
    @Volatile private var activeGpuLayers: Int = 0
    @Volatile private var engine: Any? = null
    @Volatile private var handle: Long = 0L
    @Volatile private var activeModelType: ModelType = ModelType.LLM
    @Volatile private var activeMmprojPath: String? = null
    @Volatile private var activeVlmCapabilities: VlmCapabilities? = null
    @Volatile private var lastQairtBundleReadiness: QairtBundleReadiness? = null
    @Volatile private var generateThread: Thread? = null
    @Volatile private var lastPromptEndsInsideReasoning = false
    private val lifecycleLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>()
    private var params: GenerationParams = GenerationParams()
    private var loadParams: LoadParams = LoadParams()
    private var completionTokens = 0
    private var promptTokens = 0
    @Volatile private var prefillTotalTokens = 0
    private var loadMs = 0L
    private var startedAt = 0L
    private var firstTokenAt = 0L
    private var completedAt = 0L
    private var nativeProfileJson = JSONObject()
    private val isQairtRuntime: Boolean
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
        val started = System.currentTimeMillis()
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
                    chat_template_content = "",
                    max_tokens = 0,
                    enable_thinking = false,
                    verbose = false
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
                    chat_template_content = "",
                    max_tokens = 0,
                    enable_thinking = false,
                    verbose = false
                )
            }
            val runtimeId = managed.runtimeId.ifBlank { requestedRuntimeId }
            val engineKind = managed.modelType.name.lowercase()
            val createInput: Any
            val createInputClass: Class<*>
            val engineClassName: String
            if (managed.modelType == ModelType.VLM) {
                createInput = VlmCreateInput(
                    model_name = managed.modelName,
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
                    model_name = managed.modelName,
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
            lastError = null
            loadMs = System.currentTimeMillis() - started
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_load_ok",
                JSONObject()
                    .put("loadMs", loadMs)
                    .put("runtimeId", activeRuntimeId)
                    .put("computeUnit", activeComputeUnit)
                    .put("backendDevices", activeBackendDevices)
                    .put("modelType", activeModelType.name.lowercase())
                    .put("visionReady", activeModelType == ModelType.VLM && vlmCapabilities?.supportsVision != false)
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
            -3
        }
    }

    private fun prepareDirectGgufModel(input: File): PreparedGenieXModel {
        val searchRoot = input.takeIf(File::isDirectory) ?: input.parentFile
        val gguf = when {
            input.isFile &&
                input.extension.equals("gguf", ignoreCase = true) &&
                !input.isVisionProjectorFile() -> input
            input.isDirectory -> input.walkTopDown()
                .filter { candidate ->
                    candidate.isFile &&
                        candidate.extension.equals("gguf", ignoreCase = true) &&
                        !candidate.isVisionProjectorFile()
                }
                .maxByOrNull { it.length() }
                ?: error("No text GGUF model found under ${input.absolutePath}.")
            input.isFile && input.extension.equals("gguf", ignoreCase = true) -> searchRoot
                ?.walkTopDown()
                ?.maxDepth(2)
                ?.filter { candidate ->
                    candidate.isFile &&
                        candidate.extension.equals("gguf", ignoreCase = true) &&
                        !candidate.isVisionProjectorFile()
                }
                ?.maxByOrNull { it.length() }
                ?: error("No text GGUF model found beside ${input.absolutePath}.")
            else -> error("GenieX llama.cpp requires a GGUF file or directory: ${input.absolutePath}")
        }
        require(gguf.length() > 0L) { "GGUF model is empty: ${gguf.absolutePath}" }
        val explicitMmproj = loadParams.visionProjectorPath?.let { rawPath ->
            val rawFile = File(rawPath)
            val candidate = if (rawFile.isAbsolute) rawFile else File(searchRoot, rawPath)
            require(candidate.isFile && candidate.length() > 0L) {
                "Vision projector does not exist or is empty: ${candidate.absolutePath}"
            }
            candidate
        }
        val discoveredMmproj = searchRoot
            ?.takeIf(File::isDirectory)
            ?.walkTopDown()
            ?.maxDepth(3)
            ?.filter { candidate ->
                candidate.isFile &&
                    candidate.extension.equals("gguf", ignoreCase = true) &&
                    candidate.isVisionProjectorFile()
            }
            ?.maxByOrNull { it.length() }
        val mmproj = explicitMmproj ?: discoveredMmproj
        val modelType = detectGenieXModelType(input, mmproj?.absolutePath)
        return PreparedGenieXModel(
            modelName = gguf.nameWithoutExtension,
            modelPath = gguf.absolutePath,
            tokenizerPath = null,
            runtimeId = RuntimeIdValue.LLAMA_CPP.value.orEmpty(),
            modelType = modelType,
            mmprojPath = mmproj?.absolutePath
        )
    }

    private fun File.isVisionProjectorFile(): Boolean =
        name.contains("mmproj", ignoreCase = true) ||
            name.contains("projector", ignoreCase = true)

    private fun resolveLlamaCppComputeUnit(raw: String?): String = when (raw?.trim()?.lowercase()) {
        ComputeUnitValue.CPU.value -> ComputeUnitValue.CPU.value.orEmpty()
        ComputeUnitValue.GPU.value -> ComputeUnitValue.GPU.value.orEmpty()
        ComputeUnitValue.NPU.value -> ComputeUnitValue.NPU.value.orEmpty()
        ComputeUnitValue.HYBRID.value -> ComputeUnitValue.HYBRID.value.orEmpty()
        else -> defaultComputeUnit
    }

    private fun backendDevicesFor(computeUnit: String, modelType: ModelType): String {
        if (isQairtRuntime) {
            return if (modelType == ModelType.VLM) "QAIRT HTP / VLM" else defaultBackendDevices
        }
        return when (computeUnit) {
        ComputeUnitValue.CPU.value -> "CPU / GenieX llama.cpp"
        ComputeUnitValue.GPU.value -> "Adreno OpenCL / GenieX llama.cpp"
        ComputeUnitValue.NPU.value -> "骁龙 HTP0 / GenieX llama.cpp"
        ComputeUnitValue.HYBRID.value -> "骁龙 HTP + CPU / GenieX llama.cpp"
        else -> defaultBackendDevices
        }
    }

    private fun prepareManagedQairtModel(bundleDir: File): PreparedGenieXModel {
        val resolvedBundleDir = resolveQairtBundleRootForLoad(bundleDir)
            ?: error("GenieX QAIRT model root is ambiguous or incomplete: ${bundleDir.absolutePath}")
        val sourceReadiness = QairtBundleReadinessAnalyzer.analyze(resolvedBundleDir)
        lastQairtBundleReadiness = sourceReadiness
        if (!sourceReadiness.canLoad) {
            LocalChatRunnerDebug.emit(
                "qairt_bundle_preflight_failed",
                JSONObject()
                    .put("bundleDir", resolvedBundleDir.absolutePath)
                    .put("diagnostic", sourceReadiness.diagnosticSummary())
                    .put("missing", sourceReadiness.missingRequiredComponents.joinToString(","))
                    .put("invalid", sourceReadiness.invalidRequiredComponents.joinToString(","))
            )
            error("GenieX QAIRT model package is incomplete: ${sourceReadiness.diagnosticSummary()}")
        }
        LocalChatRunnerDebug.emit(
            "qairt_bundle_preflight_ok",
            JSONObject()
                .put("bundleDir", resolvedBundleDir.absolutePath)
                .put("modelId", sourceReadiness.modelId)
                .put("supportsVision", sourceReadiness.supportsVision)
                .put("rootBinCount", sourceReadiness.rootBinPaths.size)
        )
        val candidates = qairtModelNameCandidates(resolvedBundleDir)
        val importName = candidates.firstOrNull() ?: resolvedBundleDir.name
        val desiredModelType = detectGenieXModelType(resolvedBundleDir, loadParams.visionProjectorPath)
        return runBlocking {
            val manager = ModelManagerWrapper
            for (candidate in candidates) {
                val existing = runCatching { manager.getPaths(candidate) }.getOrNull() ?: continue
                val existingModel = existing.model_path
                    .takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                    ?: continue
                val existingReadiness = QairtBundleReadinessAnalyzer.analyze(
                    requireNotNull(existingModel.parentFile) {
                        "GenieX QAIRT cache model has no parent directory."
                    }
                )
                if (!existingReadiness.canLoad) {
                    val removeResult = runCatching { manager.remove(candidate) }.getOrNull()
                    LocalChatRunnerDebug.emit(
                        "qairt_existing_registration_invalidated",
                        JSONObject()
                            .put("candidate", candidate)
                            .put("modelPath", existingModel.absolutePath)
                            .put("removeResult", removeResult)
                            .put("diagnostic", existingReadiness.diagnosticSummary())
                    )
                    continue
                }
                val registeredName = existing.model_name.takeIf { it.isNotBlank() } ?: candidate
                LocalChatRunnerDebug.emit(
                    if (existing.model_type == desiredModelType) {
                        "qairt_existing_registration_reused"
                    } else {
                        "qairt_existing_registration_type_overridden"
                    },
                    JSONObject()
                        .put("candidate", candidate)
                        .put("registeredName", registeredName)
                        .put("registeredModelType", existing.model_type.name.lowercase())
                        .put("effectiveModelType", desiredModelType.name.lowercase())
                        .put("modelPath", existingModel.absolutePath)
                )
                return@runBlocking PreparedGenieXModel(
                    modelName = registeredName,
                    modelPath = existingModel.absolutePath,
                    tokenizerPath = existing.tokenizer_path?.takeIf { it.isNotBlank() },
                    runtimeId = existing.runtime_id.orEmpty().ifBlank { RuntimeIdValue.QAIRT.value.orEmpty() },
                    modelType = desiredModelType,
                    mmprojPath = existing.mmproj_path?.takeIf { it.isNotBlank() }
                )
            }
            LocalChatRunnerDebug.emit(
                "qairt_localfs_pull_start",
                JSONObject()
                    .put("importName", importName)
                    .put("bundleDir", resolvedBundleDir.absolutePath)
                    .put("modelType", desiredModelType.name.lowercase())
            )
            manager.pullFlow(
                ModelPullInput(
                    model_name = importName,
                    precision = "w4a16",
                    hub = HubSource.LOCALFS,
                    local_path = resolvedBundleDir.absolutePath,
                    hf_token = null,
                    chipset = null,
                    display_name = importName,
                    model_type = desiredModelType
                )
            ).collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Error -> {
                        LocalChatRunnerDebug.emit(
                            "qairt_localfs_pull_error",
                            JSONObject()
                                .put("code", event.code)
                                .put("message", event.message)
                        )
                        error("GenieX LOCALFS import failed (${event.code}): ${event.message}")
                    }
                    else -> Unit
                }
            }
            LocalChatRunnerDebug.emit("qairt_localfs_pull_done")

            var lastError: Throwable? = null
            for (candidate in candidates) {
                LocalChatRunnerDebug.emit(
                    "qairt_get_paths_start",
                    JSONObject().put("candidate", candidate)
                )
                val paths = runCatching { manager.getPaths(candidate) }
                    .onFailure {
                        lastError = it
                        LocalChatRunnerDebug.emit(
                            "qairt_get_paths_failed",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("error", it.message.orEmpty())
                        )
                    }
                    .getOrNull()
                val cachedModel = paths?.model_path
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.length() > 0L }
                if (paths != null && cachedModel != null) {
                    val cachedReadiness = QairtBundleReadinessAnalyzer.analyze(
                        requireNotNull(cachedModel.parentFile) {
                            "GenieX QAIRT cache model has no parent directory."
                        }
                    )
                    if (!cachedReadiness.canLoad) {
                        val removeResult = runCatching { manager.remove(candidate) }.getOrNull()
                        LocalChatRunnerDebug.emit(
                            "qairt_localfs_cache_incomplete",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("modelPath", cachedModel.absolutePath)
                                .put("removeResult", removeResult)
                                .put("diagnostic", cachedReadiness.diagnosticSummary())
                        )
                        error(
                            "GenieX LOCALFS import produced an incomplete QAIRT cache: " +
                                cachedReadiness.diagnosticSummary()
                        )
                    }
                    LocalChatRunnerDebug.emit(
                        "qairt_get_paths_ok",
                        JSONObject()
                            .put("candidate", candidate)
                            .put("modelName", paths.model_name.orEmpty())
                            .put("modelPath", cachedModel.absolutePath)
                            .put("tokenizerPath", paths.tokenizer_path.orEmpty())
                            .put("runtimeId", paths.runtime_id.orEmpty())
                            .put("modelType", paths.model_type.name.lowercase())
                            .put("mmprojPath", paths.mmproj_path.orEmpty())
                    )
                    if (paths.model_type != desiredModelType) {
                        LocalChatRunnerDebug.emit(
                            "qairt_imported_registration_type_overridden",
                            JSONObject()
                                .put("candidate", candidate)
                                .put("registeredModelType", paths.model_type.name.lowercase())
                                .put("effectiveModelType", desiredModelType.name.lowercase())
                        )
                    }
                    return@runBlocking PreparedGenieXModel(
                        modelName = paths.model_name.orEmpty().ifBlank { candidate },
                        modelPath = cachedModel.absolutePath,
                        tokenizerPath = paths.tokenizer_path?.takeIf { it.isNotBlank() },
                        runtimeId = paths.runtime_id.orEmpty().ifBlank { RuntimeIdValue.QAIRT.value.orEmpty() },
                        modelType = desiredModelType,
                        mmprojPath = paths.mmproj_path?.takeIf { it.isNotBlank() }
                    )
                }
            }
            error(
                "GenieX LOCALFS import did not expose model paths. Tried ${candidates.joinToString(", ")}" +
                    (lastError?.message?.let { ": $it" } ?: "")
            )
        }
    }

    private fun qairtModelNameCandidates(bundleDir: File): List<String> = buildList {
        val metadata = File(bundleDir, "metadata.json")
        val root = runCatching { JSONObject(metadata.readText()) }.getOrNull()
        root?.optString("model_name")?.takeIf { it.isNotBlank() }?.let(::add)
        root?.optString("model_id")?.takeIf { it.isNotBlank() }?.let(::add)
        add(bundleDir.name)
        bundleDir.name.substringBefore("-geniex").takeIf { it.isNotBlank() }?.let(::add)
    }.distinct()

    override fun unloadModel() = synchronized(lifecycleLock) {
        if (!stopAndJoinGenerationLocked()) return@synchronized
        val current = engine
        val nativeHandle = handle
        val engineKind = activeModelType.name.lowercase()
        // Detach first so repeated unloads and concurrent stop requests cannot reuse this handle.
        engine = null
        handle = 0L
        loaded = false
        activeModelType = ModelType.LLM
        activeMmprojPath = null
        activeVlmCapabilities = null
        if (current != null && nativeHandle != 0L) {
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_${engineKind}_destroy_start",
                JSONObject().put("handleNonZero", true)
            )
            runCatching {
                destroyGenieXHandle(current, nativeHandle)
            }.onSuccess { rc ->
                if (rc == 0) {
                    LocalChatRunnerDebug.emit(
                        "${stagePrefix}_${engineKind}_destroy_ok",
                        JSONObject().put("result", rc)
                    )
                } else {
                    lastError = "GenieX ${engineKind} destroy returned $rc."
                    LocalChatRunnerDebug.emit(
                        "${stagePrefix}_${engineKind}_destroy_failed",
                        JSONObject().put("result", rc).put("error", lastError)
                    )
                }
            }.onFailure { error ->
                lastError = "GenieX ${engineKind} destroy failed: ${error.describeForUser()}"
                LocalChatRunnerDebug.emit(
                    "${stagePrefix}_${engineKind}_destroy_failed",
                    JSONObject().put("error", lastError)
                )
            }
        }
        queue.clear()
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int = synchronized(lifecycleLock) {
        beginCompletionLocked(messagesJson, paramsJson)
    }

    private fun beginCompletionLocked(messagesJson: String, paramsJson: String): Int {
        val current = engine ?: return -4.also { lastError = "GenieX model is not loaded." }
        val nativeHandle = handle
        if (nativeHandle == 0L) return -4.also { lastError = "GenieX model handle is empty." }
        if (activeModelType != ModelType.VLM && messagesJson.contains("image_url", ignoreCase = true)) {
            lastError = "${runtime.label} text runner does not handle image input yet; use the VLM runner path."
            return -5
        }
        if (!stopAndJoinGenerationLocked()) return -7
        queue.clear()
        lastError = ""
        stopRequested.set(false)
        params = GenerationParams.fromJson(paramsJson)
        completionTokens = 0
        promptTokens = 0
        nativeProfileJson = JSONObject()
        startedAt = System.currentTimeMillis()
        firstTokenAt = 0L
        completedAt = 0L
        lastPromptEndsInsideReasoning = false

        val generationConfig = params.toGenieXGenerationConfig()
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
                    Boolean::class.javaPrimitiveType
                )
                val out = method.invoke(
                    current,
                    nativeHandle,
                    messages,
                    null,
                    params.reasoningMode != ReasoningMode.OFF
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
                    Boolean::class.javaPrimitiveType
                )
                val out = method.invoke(
                    current,
                    nativeHandle,
                    messages,
                    null,
                    params.reasoningMode != ReasoningMode.OFF,
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
                val generate = current.javaClass.getDeclaredMethod(
                    "generate",
                    Long::class.javaPrimitiveType,
                    String::class.java,
                    GenerationConfig::class.java,
                    LLMTokenCallback::class.java
                )

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
                            queue.put(headBuffer.toString())
                            headBuffer.setLength(0)
                        }
                    }

                    fun ingestVisibleChunk(chunk: String) {
                        if (chunk.isEmpty()) return
                        completionTokens += estimateTokens(chunk).coerceAtLeast(1)
                        if (live) {
                            queue.put(chunk)
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
                            if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
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
                            completedAt = System.currentTimeMillis()
                        }
                    }
                    val returned = generate.invoke(current, nativeHandle, prompt, config, callback)
                    if (!sawCallbackChunk) {
                        extractGenieXResultText(returned)?.let { raw ->
                            ingestVisibleChunk(stripGenieXPromptEcho(raw, prompt))
                        }
                    }
                    flushTextAssembler()
                    completedAt = System.currentTimeMillis()
                    lastProfile?.let { nativeProfileJson = it.toJson() }

                    // Already streaming a normal-sized answer: nothing to rescue.
                    if (live) return false
                    // User actively stopped: never auto-retry.
                    if (stopRequested.get()) {
                        if (headBuffer.isNotEmpty()) queue.put(headBuffer.toString())
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
                        firstTokenAt = 0L
                        return true
                    }
                    if (visible.isNotBlank()) queue.put(visible)
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
                completedAt = System.currentTimeMillis()
            }.onFailure { error ->
                lastError = error.describeForUser()
                queue.put(ERROR_PREFIX + lastError)
            }
            queue.put(DONE)
        }
        return 0
    }

    override fun generateNextChunk(): String? {
        while (true) {
            val item = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            if (item == DONE) return null
            if (item.startsWith(ERROR_PREFIX)) {
                throw IllegalStateException(item.removePrefix(ERROR_PREFIX).ifBlank {
                    "GenieX generation failed without a diagnostic."
                })
            }
            return item
        }
    }

    override fun requestStop() {
        synchronized(lifecycleLock) {
            requestStopLocked()
        }
    }

    private fun stopAndJoinGenerationLocked(): Boolean {
        requestStopLocked()
        val runningThread = generateThread ?: return true
        if (runningThread === Thread.currentThread()) {
            lastError = "GenieX generation cannot unload itself while its native call is active."
            return false
        }
        runningThread.join()
        if (generateThread === runningThread) {
            generateThread = null
        }
        return true
    }

    private fun requestStopLocked() {
        stopRequested.set(true)
        val current = engine
        val nativeHandle = handle
        if (current != null && nativeHandle != 0L) {
            runCatching {
                current.javaClass.getDeclaredMethod("stopStream", Long::class.javaPrimitiveType)
                    .invoke(current, nativeHandle)
            }
        }
    }

    override fun prefillProgress(): TokenProgress? {
        // GenieX is a closed SDK whose prefill produces no intermediate events.
        // Report the estimated total once the run starts; completed jumps to
        // total when the first generated token arrives.
        val total = prefillTotalTokens
        if (total <= 0) return null
        val completed = if (firstTokenAt > 0L) total else 0
        return TokenProgress(completedTokens = completed, totalTokens = total)
    }

    override fun getRuntimeStatsJson(): String = JSONObject()
        .put("backend", runtime.backendId)
        .put("loaded", loaded)
        .put("runnerReady", isAvailable)
        .put("modelPath", modelPath)
        .put("modelName", modelName)
        .put("modelType", activeModelType.name.lowercase())
        .put("promptEndsInsideReasoning", lastPromptEndsInsideReasoning)
        .put("mmprojPath", activeMmprojPath)
        .put("visionReady", loaded && activeModelType == ModelType.VLM && activeVlmCapabilities?.supportsVision != false)
        .put(
            "capabilities",
            JSONObject()
                .put("supportsVision", activeVlmCapabilities?.supportsVision ?: (activeModelType == ModelType.VLM))
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
        .put("ttftMs", if (firstTokenAt > 0L) firstTokenAt - startedAt else 0L)
        .put("prefillMs", nativeProfileJson.optDouble("promptTimeMs", 0.0))
        .put("decodeMs", nativeProfileJson.optDouble("decodeTimeMs", decodeElapsedMs().toDouble()))
        .put("decodeTps", nativeProfileJson.optDouble("decodingSpeed", decodeTps()))
        .put("e2eTps", e2eTps())
        .put("backendDevices", activeBackendDevices)
        .put(
            "chatRoleMode",
            if (activeModelType == ModelType.LLM) "native_roles_geniex_0_3_12_mca1" else "native_roles"
        )
        .put("nativeProfile", nativeProfileJson)
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
        .toString()

    override fun shutdown() {
        unloadModel()
    }

    private fun formattedTemplateText(output: Any): String =
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
    private fun extractGenieXResultText(value: Any?): String? {
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
    private fun stripGenieXPromptEcho(text: String, prompt: String): String {
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
    private fun ensureGenieXUserTurn(
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
    private fun isGenieXDegenerateGreedy(params: GenerationParams): Boolean =
        params.temperature <= 0f && params.topK <= 1 && params.topP >= 1f

    /**
     * Best-effort native session/KV reset (jni `Llm.reset(handle)`). The model
     * stays loaded; only the cached prefix/history is cleared so the soft-sampling
     * rescue decodes from a clean position instead of prefix-matching a turn that
     * already ended on EOS. Returns the native result code, or null when unavailable.
     */
    private fun resetGenieXNativeSession(current: Any, nativeHandle: Long): Int? = runCatching {
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
    private fun isGenieXPromptFragmentEcho(visible: String, referenceUserText: String): Boolean {
        if (visible.isBlank() || referenceUserText.isBlank()) return false
        if (visible.length > GENIEX_ECHO_FRAGMENT_MAX_CHARS) return false
        val compactAnswer = visible.filterNot { it.isWhitespace() }
        val compactReference = referenceUserText.filterNot { it.isWhitespace() }
        if (compactAnswer.isEmpty() || compactAnswer.length >= compactReference.length) return false
        return compactReference.contains(compactAnswer)
    }

    /** A 1-2 char punctuation-only fragment carries no visible answer. */
    private fun isGenieXMeaninglessShort(text: String): Boolean {
        if (text.isBlank()) return true
        if (text.length > 2) return false
        return text.all { ch ->
            ch.isWhitespace() || ch in "。.，,！!？?、：:；;\"'“”‘’()（）【】[]<>《》"
        }
    }

    private fun GenerationParams.toGenieXGenerationConfig(
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

    private fun ProfilingData.toJson(): JSONObject = JSONObject()
        .put("ttftMs", ttftMs)
        .put("promptTimeMs", promptTimeMs)
        .put("decodeTimeMs", decodeTimeMs)
        .put("promptTokens", promptTokens)
        .put("generatedTokens", generatedTokens)
        .put("prefillSpeed", prefillSpeed)
        .put("decodingSpeed", decodingSpeed)
        .put("stopReason", stopReason)

    private fun decodeElapsedMs(): Long =
        if (completedAt > 0L && firstTokenAt > 0L) (completedAt - firstTokenAt).coerceAtLeast(1L) else 0L

    private fun decodeTps(): Double {
        val elapsed = decodeElapsedMs()
        return if (elapsed > 0L) completionTokens * 1000.0 / elapsed else 0.0
    }

    private fun e2eTps(): Double {
        val end = completedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val elapsed = (end - startedAt).coerceAtLeast(1L)
        return completionTokens * 1000.0 / elapsed
    }

    private fun estimateTokens(text: String): Int =
        (text.length / 3).coerceAtLeast(if (text.isBlank()) 0 else 1)

    private fun Throwable.describeForUser(): String {
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

    companion object {
        private const val GENIEX_BOUNDARY_TAG = "MCA-GENIEX-BOUNDARY"
        private const val DONE = "\u0000MCA_GENIEX_DONE"
        private const val ERROR_PREFIX = "\u0000MCA_GENIEX_ERROR:"
        private const val GENIEX_ERROR_ALREADY_INITIALIZED = -100008

        // Buffer the head of a generation until it grows past this many
        // (estimated) tokens; a turn that never crosses this window is evaluated
        // for the one-shot rescue. Sized to retain the observed 3-5 token prompt
        // echo of a short Chinese question.
        private const val GENIEX_RESCUE_PROBE_TOKENS = 8
        // Native generated-token ceiling for a collapsed turn eligible for rescue.
        private const val GENIEX_RESCUE_MAX_GENERATED_TOKENS = 8
        // A collapsed echo fragment may not exceed this many visible characters.
        private const val GENIEX_ECHO_FRAGMENT_MAX_CHARS = 12
        // Gentle internal sampler used ONLY by the single rescue attempt.
        private const val GENIEX_RESCUE_TEMPERATURE = 0.3f
        private const val GENIEX_RESCUE_TOP_K = 20
        private const val GENIEX_RESCUE_TOP_P = 0.9f
    }
}

/**
 * Creates exactly one runner. Callers that isolate ordinary native runners can
 * therefore avoid loading unrelated JNI libraries in the product process.
 */
fun defaultLocalChatRunner(
    runtime: LocalChatRuntime,
    context: Context? = null
): LocalChatRunner = when (runtime) {
    LocalChatRuntime.MNN_CPU -> MnnCpuChatRunner()
    LocalChatRuntime.LLAMA_CPP -> LlamaCppChatRunner()
    LocalChatRuntime.GENIEX_LLAMA_CPP -> GenieXLlamaCppChatRunner(context)
    LocalChatRuntime.GENIEX_QAIRT -> GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_QAIRT,
        requestedRuntimeId = RuntimeIdValue.QAIRT.value.orEmpty(),
        defaultComputeUnit = ComputeUnitValue.NPU.value.orEmpty(),
        defaultBackendDevices = "QAIRT NPU",
        appContext = context
    )
    LocalChatRuntime.LITERT_LM -> LiteRtLmChatRunner()
}

fun defaultLocalChatRunners(context: Context? = null): Map<LocalChatRuntime, LocalChatRunner> =
    LocalChatRuntime.entries.associateWith { runtime -> defaultLocalChatRunner(runtime, context) }

internal fun unavailableStats(runtime: LocalChatRuntime, error: Throwable?): JSONObject =
    JSONObject()
        .put("backend", runtime.backendId)
        .put("loaded", false)
        .put("runnerReady", false)
        .put("nCtx", 0)
        .put("maxAllTokens", 0)
        .put("maxNewTokens", 0)
        .put(
            "lastError",
            buildString {
                append(runtime.label).append("不可用：当前 APK 尚未打包对应 native runner")
                val message = error?.message
                if (!message.isNullOrBlank()) append("；").append(message)
            }
        )

private const val UNAVAILABLE_RC = -100
