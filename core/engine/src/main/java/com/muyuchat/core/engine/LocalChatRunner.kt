package com.muyuchat.core.engine

import android.content.Context
import android.util.Log
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
import com.muyuchat.core.nativebridge.NativeLlamaBridge
import com.muyuchat.core.nativebridge.NativeMnnBridge
import java.io.File
import java.lang.reflect.InvocationTargetException
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
    GENIEX_QAIRT("geniex_qairt", "GenieX QAIRT NPU")
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

interface LocalChatRunner {
    val runtime: LocalChatRuntime
    val isAvailable: Boolean
    val loadError: Throwable?

    fun initBackends(nativeLibDir: String)
    fun loadModel(modelPath: String, paramsJson: String): Int
    fun unloadModel()
    fun beginCompletion(messagesJson: String, paramsJson: String): Int
    fun generateNextChunk(): String?
    fun requestStop()
    fun requestStopIfActive(): Boolean = false
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
    override fun generateNextChunk(): String? = bridge.generateNextChunk()
    override fun requestStop() = bridge.requestStop()
    override fun requestStopIfActive(): Boolean = bridge.requestStopIfActive()
    override fun getRuntimeStatsJson(): String = bridge.getRuntimeStatsJson()
    override fun shutdown() = bridge.shutdown()
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
    @Volatile private var generateThread: Thread? = null
    private val lifecycleLock = Any()
    private val stopRequested = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>()
    private var params: GenerationParams = GenerationParams()
    private var loadParams: LoadParams = LoadParams()
    private var completionTokens = 0
    private var promptTokens = 0
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
        val context = appContext
        if (sdkInitAttempted || context == null || !classesPresent) return
        sdkInitAttempted = true
        val errors = StringBuilder()
        LocalChatRunnerDebug.emit(
            "${stagePrefix}_sdk_init_start",
            JSONObject()
                .put("nativeLibDir", nativeLibDir)
                .put("filesDir", context.filesDir.absolutePath)
        )
        runCatching {
            val sdk = GenieXSdk.getInstance()
            var initFailure: String? = null
            sdk.init(context, object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit

                override fun onFailure(reason: String) {
                    initFailure = reason
                }
            })
            initFailure?.let { errors.append(it).append('\n') }
            if (isQairtRuntime) {
                val dataDir = File(context.filesDir, "geniex").apply { mkdirs() }
                runBlocking { ModelManagerWrapper.init(dataDir.absolutePath).getOrThrow() }
            }
        }.onFailure { error ->
            errors.append(error.message ?: error::class.java.simpleName)
        }
        if (errors.isNotBlank()) {
            sdkInitError = IllegalStateException(errors.toString().trim())
            LocalChatRunnerDebug.emit(
                "${stagePrefix}_sdk_init_failed",
                JSONObject().put("error", sdkInitError?.message.orEmpty())
            )
        } else {
            LocalChatRunnerDebug.emit("${stagePrefix}_sdk_init_ok")
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
        initBackends(context.applicationInfo.nativeLibraryDir)
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
                    nCtx = loadParams.nCtx.coerceAtLeast(512),
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
            lastError = error.describeForUser()
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
                if (paths?.model_path?.isNotBlank() == true) {
                    LocalChatRunnerDebug.emit(
                        "qairt_get_paths_ok",
                        JSONObject()
                            .put("candidate", candidate)
                            .put("modelName", paths.model_name.orEmpty())
                            .put("modelPath", paths.model_path.orEmpty())
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
                        modelPath = paths.model_path.orEmpty(),
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

        val generationConfig = params.toGenieXGenerationConfig()
        val prompt = if (activeModelType == ModelType.VLM) {
            val messages = genieXVlmMessagesFromJson(messagesJson)
            if (messages.isEmpty()) {
                lastError = "GenieX VLM received no chat messages."
                return -6
            }
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
                requireNonBlankGenieXTemplate(
                    formattedTemplateText(requireNotNull(out) { "GenieX LLM returned no chat template output." })
                )
            }.getOrElse { error ->
                lastError = "GenieX LLM chat template failed: ${error.describeForUser()}"
                return -6
            }
        }
        promptTokens = estimateTokens(prompt)

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
                val textAssembler = GenieXUtf8ChunkAssembler()
                fun flushTextAssembler() {
                    textAssembler.finish()
                        .takeIf { it.isNotEmpty() }
                        ?.let(queue::put)
                }
                val callback = object : LLMTokenCallback {
                    override fun onToken(token: String): Boolean {
                        if (firstTokenAt == 0L) firstTokenAt = System.currentTimeMillis()
                        completionTokens += estimateTokens(token).coerceAtLeast(1)
                        textAssembler.append(token)
                            .takeIf { it.isNotEmpty() }
                            ?.let(queue::put)
                        return !stopRequested.get()
                    }

                    override fun onComplete(result: LlmGenerateResult) {
                        flushTextAssembler()
                        completedAt = System.currentTimeMillis()
                        nativeProfileJson = result.profileData.toJson()
                    }
                }
                generate.invoke(current, nativeHandle, prompt, generationConfig, callback)
                flushTextAssembler()
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

    override fun getRuntimeStatsJson(): String = JSONObject()
        .put("backend", runtime.backendId)
        .put("loaded", loaded)
        .put("runnerReady", isAvailable)
        .put("modelPath", modelPath)
        .put("modelName", modelName)
        .put("modelType", activeModelType.name.lowercase())
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

    private fun GenerationParams.toGenieXGenerationConfig(): GenerationConfig =
        GenerationConfig(
            maxTokens = effectiveNPredict(),
            stopWords = stopWords.takeIf { it.isNotEmpty() }?.toTypedArray(),
            stopCount = stopWords.size,
            samplerConfig = SamplerConfig(
                temperature = temperature,
                topP = topP,
                topK = topK,
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
    }
}

fun defaultLocalChatRunners(context: Context? = null): Map<LocalChatRuntime, LocalChatRunner> = mapOf(
    LocalChatRuntime.MNN_CPU to MnnCpuChatRunner(),
    LocalChatRuntime.LLAMA_CPP to LlamaCppChatRunner(),
    LocalChatRuntime.GENIEX_LLAMA_CPP to GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_LLAMA_CPP,
        requestedRuntimeId = RuntimeIdValue.LLAMA_CPP.value.orEmpty(),
        defaultComputeUnit = ComputeUnitValue.HYBRID.value.orEmpty(),
        defaultBackendDevices = "骁龙 HTP + CPU / GenieX llama.cpp",
        appContext = context
    ),
    LocalChatRuntime.GENIEX_QAIRT to GenieXChatRunner(
        runtime = LocalChatRuntime.GENIEX_QAIRT,
        requestedRuntimeId = RuntimeIdValue.QAIRT.value.orEmpty(),
        defaultComputeUnit = ComputeUnitValue.NPU.value.orEmpty(),
        defaultBackendDevices = "QAIRT NPU",
        appContext = context
    )
)

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
