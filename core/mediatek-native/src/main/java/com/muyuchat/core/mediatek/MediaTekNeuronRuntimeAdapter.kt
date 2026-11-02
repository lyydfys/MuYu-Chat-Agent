package com.muyuchat.core.mediatek

import java.io.Closeable
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal interface MediaTekNeuronNativeBridge {
    fun nativeProbe(): String

    fun nativeRestore(
        dlaPath: String,
        inputCount: Int,
        outputCount: Int,
        inputRanks: IntArray,
    ): String

    fun nativeRun(inputs: Array<ByteArray>): Array<ByteArray>

    fun nativeClose()

    fun nativeLastError(): String
}

internal object MediaTekNeuronJniBridge : MediaTekNeuronNativeBridge {
    init {
        System.loadLibrary("mca_mediatek_neuron")
    }

    external override fun nativeProbe(): String

    external override fun nativeRestore(
        dlaPath: String,
        inputCount: Int,
        outputCount: Int,
        inputRanks: IntArray,
    ): String

    external override fun nativeRun(inputs: Array<ByteArray>): Array<ByteArray>

    external override fun nativeClose()

    external override fun nativeLastError(): String
}

data class MediaTekNeuronRuntimeInfo(
    val status: String,
    val libraryPath: String?,
    val version: String?,
    val devices: List<String>,
    val message: String?,
)

data class MediaTekNeuronCompiledNetworkInfo(
    val status: String,
    val libraryPath: String,
    val version: String?,
    val inputCount: Int,
    val outputCount: Int,
    val inputPaddedBytes: List<Long>,
    val outputPaddedBytes: List<Long>,
    val inputDims: List<List<Int>>,
    val outputDims: List<List<Int>>,
    val restoreElapsedMs: Long,
    val message: String?,
) {
    val executable: Boolean get() = status == "OPEN"
}

/**
 * Narrow owner of one restored Neuron compiled network. It exposes only
 * probe/open/run/close; it does not select a backend, retry, or fall back to
 * JIT. A restored stub without the matching DLA must fail here.
 */
class MediaTekNeuronRuntimeAdapter internal constructor(
    private val bridge: MediaTekNeuronNativeBridge,
) : Closeable {
    constructor() : this(MediaTekNeuronJniBridge)
    enum class State {
        IDLE,
        OPEN,
        CLOSED,
    }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var networkInfo: MediaTekNeuronCompiledNetworkInfo? = null
        private set

    fun probe(): Result<MediaTekNeuronRuntimeInfo> = runCatching {
        parseRuntime(bridge.nativeProbe())
    }

    fun open(
        dla: File,
        inputCount: Int,
        outputCount: Int,
        inputRanks: IntArray,
    ): Result<MediaTekNeuronCompiledNetworkInfo> = runCatching {
        check(state != State.OPEN) { "Neuron adapter is already open" }
        require(dla.isFile && dla.canRead() && dla.length() > 0L) {
            "DLA file is missing, empty, or unreadable: ${dla.name}"
        }
        require(inputCount > 0) { "inputCount must be positive" }
        require(outputCount > 0) { "outputCount must be positive" }
        require(inputRanks.size == inputCount) { "inputRanks must contain one rank per input" }
        require(inputRanks.all { it in 1..8 }) { "input ranks must be within 1..8" }
        val text = bridge.nativeRestore(dla.absolutePath, inputCount, outputCount, inputRanks)
        val info = try {
            parseNetwork(text)
        } catch (error: Throwable) {
            bridge.nativeClose()
            throw error
        }
        if (!info.executable) {
            bridge.nativeClose()
        }
        check(info.executable) { info.message ?: "Neuron restore failed (${info.status})" }
        state = State.OPEN
        networkInfo = info
        info
    }

    /**
     * Inputs must already match the runtime-reported padded sizes. The caller
     * owns padding; this adapter never trims or pads silently.
     */
    fun run(inputs: List<ByteArray>, logicalOutputBytes: Int? = null): Result<List<ByteArray>> = runCatching {
        val info = checkNotNull(networkInfo) { "Neuron adapter is not open" }
        check(state == State.OPEN) { "Neuron adapter is not open" }
        require(inputs.size == info.inputCount) {
            "expected ${info.inputCount} inputs, got ${inputs.size}"
        }
        inputs.forEachIndexed { index, bytes ->
            val expected = info.inputPaddedBytes.getOrNull(index)
                ?: error("runtime did not report a padded size for input $index")
            require(bytes.size.toLong() == expected) {
                "input $index has ${bytes.size} bytes; runtime expects $expected"
            }
        }
        if (logicalOutputBytes != null) {
            require(logicalOutputBytes >= 0) { "logicalOutputBytes must not be negative" }
        }
        val outputs = bridge.nativeRun(inputs.toTypedArray()).toList()
        require(outputs.size == info.outputCount) {
            "expected ${info.outputCount} outputs, got ${outputs.size}"
        }
        if (logicalOutputBytes == null) {
            outputs
        } else {
            outputs.map { bytes -> bytes.copyOf(minOf(bytes.size, logicalOutputBytes)) }
        }
    }

    override fun close() {
        if (state == State.OPEN) {
            bridge.nativeClose()
        }
        state = State.CLOSED
        networkInfo = null
    }

    private fun parseRuntime(text: String): MediaTekNeuronRuntimeInfo {
        val root = json.parseToJsonElement(text).jsonObject
        return MediaTekNeuronRuntimeInfo(
            status = root.string("status") ?: "BLOCKED",
            libraryPath = root.string("libraryPath"),
            version = root.string("version"),
            devices = root.array("devices").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            message = root.string("message"),
        )
    }

    private fun parseNetwork(text: String): MediaTekNeuronCompiledNetworkInfo {
        val root = json.parseToJsonElement(text).jsonObject
        return MediaTekNeuronCompiledNetworkInfo(
            status = root.string("status") ?: "FAILED",
            libraryPath = root.string("libraryPath").orEmpty(),
            version = root.string("version"),
            inputCount = root.int("inputCount") ?: 0,
            outputCount = root.int("outputCount") ?: 0,
            inputPaddedBytes = root.longArray("inputPaddedBytes"),
            outputPaddedBytes = root.longArray("outputPaddedBytes"),
            inputDims = root.dims("inputDims"),
            outputDims = root.dims("outputDims"),
            restoreElapsedMs = root.long("restoreElapsedMs") ?: 0L,
            message = root.string("message"),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.longOrNull

    private fun JsonObject.array(name: String): List<JsonElement> =
        (this[name] as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()

    private fun JsonObject.longArray(name: String): List<Long> =
        array(name).mapNotNull { (it as? JsonPrimitive)?.longOrNull }

    private fun JsonObject.dims(name: String): List<List<Int>> =
        array(name).map { element ->
            (element as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                .orEmpty()
        }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }
    }
}
