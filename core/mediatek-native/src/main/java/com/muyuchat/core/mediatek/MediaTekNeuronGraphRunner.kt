package com.muyuchat.core.mediatek

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Tensor data types supported by LiteRT's Android TensorBuffer API. */
enum class MediaTekTensorType {
    FLOAT32,
    INT32,
    INT8,
}

/** Ordered tensor data for one graph input or output. Shape is the logical tensor shape. */
sealed class MediaTekTensorData(
    val name: String,
    val shape: List<Int>,
) {
    abstract val type: MediaTekTensorType
    abstract val elementCount: Int

    class Float32(name: String, shape: List<Int>, val values: FloatArray) : MediaTekTensorData(name, shape) {
        override val type = MediaTekTensorType.FLOAT32
        override val elementCount: Int get() = values.size
    }

    class Int32(name: String, shape: List<Int>, val values: IntArray) : MediaTekTensorData(name, shape) {
        override val type = MediaTekTensorType.INT32
        override val elementCount: Int get() = values.size
    }

    class Int8(name: String, shape: List<Int>, val values: ByteArray) : MediaTekTensorData(name, shape) {
        override val type = MediaTekTensorType.INT8
        override val elementCount: Int get() = values.size
    }

    internal fun validate(label: String, requireFinite: Boolean = true): String? {
        if (name.isBlank()) return "$label tensor name is empty"
        if (shape.isEmpty() || shape.size > MAX_RANK || shape.any { it <= 0 }) {
            return "$label tensor $name has an invalid shape"
        }
        val expected = try {
            shape.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension.toLong()) }
        } catch (_: ArithmeticException) {
            return "$label tensor $name shape overflows"
        }
        if (expected > MAX_TENSOR_ELEMENTS) return "$label tensor $name exceeds the element limit"
        if (elementCount.toLong() != expected) {
            return "$label tensor $name has $elementCount elements; expected $expected"
        }
        if (requireFinite && this is Float32 && values.any { !it.isFinite() }) {
            return "$label tensor $name contains a non-finite float"
        }
        return null
    }

    companion object {
        const val MAX_RANK = 8
        const val MAX_TENSOR_ELEMENTS = 16L * 1024L * 1024L
    }
}

/** Health statistics are returned for the generation owner to apply stage-specific drift limits. */
data class MediaTekFloatTensorStats(
    val count: Int,
    val mean: Double,
    val standardDeviation: Double,
    val maxAbs: Double,
)

fun MediaTekTensorData.Float32.stats(): MediaTekFloatTensorStats {
    if (values.isEmpty()) return MediaTekFloatTensorStats(0, 0.0, 0.0, 0.0)
    var sum = 0.0
    var maxAbs = 0.0
    values.forEach {
        sum += it.toDouble()
        maxAbs = maxOf(maxAbs, kotlin.math.abs(it.toDouble()))
    }
    val mean = sum / values.size
    var squaredDifference = 0.0
    values.forEach {
        val difference = it.toDouble() - mean
        squaredDifference += difference * difference
    }
    return MediaTekFloatTensorStats(
        count = values.size,
        mean = mean,
        standardDeviation = sqrt(squaredDifference / values.size),
        maxAbs = maxAbs,
    )
}

enum class MediaTekGraphFailureStage {
    CONTRACT,
    ARTIFACT,
    ENVIRONMENT,
    COMPILE,
    INPUTS,
    EXECUTE,
    OUTPUTS,
    CLOSED,
}

class MediaTekGraphExecutionException(
    val stage: MediaTekGraphFailureStage,
    message: String,
    cause: Throwable? = null,
    val outputDiagnostic: MediaTekGraphOutputDiagnostic? = null,
) : IllegalStateException("MediaTek ${stage.name.lowercase()} failed: $message", cause)

data class MediaTekGraphOutputDiagnostic(
    val graphFile: String,
    val tensorName: String,
    val stats: MediaTekAnimaDiagnosticStats,
)

internal fun validateMediaTekGraphOutput(graphFile: String, output: MediaTekTensorData) {
    output.validate("output", requireFinite = false)?.let { reason ->
        throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.OUTPUTS, reason)
    }
    if (output is MediaTekTensorData.Float32) {
        if (output.values.any { !it.isFinite() }) {
            val stats = output.diagnosticStats()
            throw MediaTekGraphExecutionException(
                stage = MediaTekGraphFailureStage.OUTPUTS,
                message = "$graphFile output tensor '${output.name}' contains non-finite float values " +
                    "(${stats.finiteCount}/${stats.count} finite)",
                outputDiagnostic = MediaTekGraphOutputDiagnostic(
                    graphFile = graphFile,
                    tensorName = output.name,
                    stats = stats,
                ),
            )
        }
    }
}

/**
 * Evidence for an individual graph call. LiteRT 2.3's Java API reports that an
 * NPU-targeted model ran, but does not expose the compiled partition count, so
 * this result never proves that the graph executed on Neuron rather than a
 * mixed/fallback partition.
 */
data class MediaTekGraphRunEvidence(
    val graphFile: String,
    val graphStage: MediaTekGraphStage,
    val requestedAccelerator: String,
    val graphCompiled: Boolean,
    val graphRan: Boolean,
    val npuPartitions: Int?,
    val cpuFallbackOps: Int?,
    val providerDeviceSupportedAdvisory: Boolean,
    val providerLibraryReadyAdvisory: Boolean,
    val availableAccelerators: Set<String>,
    val outputSha256: String,
    val elapsedMs: Long,
    val state: MediaTekRuntimeState,
    val message: String,
) {
    val provesNpuExecution: Boolean
        get() = state == MediaTekRuntimeState.READY &&
            requestedAccelerator == Accelerator.NPU.name &&
            npuPartitions != null && npuPartitions > 0 && graphCompiled && graphRan

    /** True only when a compiled, executed graph reported a positive NPU partition count. */
    val reportsNpuPartitions: Boolean
        get() = graphCompiled && graphRan && (npuPartitions ?: 0) > 0
}

data class MediaTekGraphRunResult(
    val outputs: List<MediaTekTensorData>,
    val evidence: MediaTekGraphRunEvidence,
)

/**
 * Loads a single verified graph using the public LiteRT MediaTek provider.
 * The provider's device-support probe is recorded as advisory information only;
 * the actual environment creation, NPU compile, and graph run decide whether
 * this graph can execute on the current device. No CPU fallback is attempted.
 */
class MediaTekNeuronGraphRunner(context: Context) {
    private val appContext = context.applicationContext

    fun openRuntime(): MediaTekNeuronGraphRuntime = MediaTekNeuronGraphRuntime(appContext, this)

    fun openGraph(
        bundleRoot: File,
        graph: MediaTekGraphContract,
    ): MediaTekNeuronGraphSession {
        val runtime = openRuntime()
        return try {
            runtime.openGraph(bundleRoot, graph, ownsRuntime = true)
        } catch (error: Throwable) {
            runCatching { runtime.close() }
            throw error
        }
    }

    internal fun validateAndResolveGraph(bundleRoot: File, graph: MediaTekGraphContract): File {
        val errors = graph.validate()
        if (errors.isNotEmpty()) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.CONTRACT,
                errors.joinToString("; "),
            )
        }
        if (!graph.delegate.contains("neuron", ignoreCase = true)) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.CONTRACT,
                "graph delegate must declare Neuron",
            )
        }
        val tensorMetadata = graph.inputTensors + graph.outputTensors
        tensorMetadata.forEach { tensor ->
            if (!validContractShape(tensor.shape)) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.CONTRACT,
                    "tensor ${tensor.name} has an invalid, unsupported dynamic, or oversized shape",
                )
            }
            if (tensor.dtype.normalizedType() == null) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.CONTRACT,
                    "tensor ${tensor.name} uses unsupported LiteRT dtype '${tensor.dtype}'",
                )
            }
        }
        return verifiedGraphFile(bundleRoot, graph)
    }

    private fun verifiedGraphFile(bundleRoot: File, graph: MediaTekGraphContract): File {
        val root = try {
            bundleRoot.canonicalFile
        } catch (error: Exception) {
            throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.ARTIFACT, "bundle root is invalid", error)
        }
        val relative = graph.file.replace('\\', '/')
        val candidate = try {
            File(root, relative).canonicalFile
        } catch (error: Exception) {
            throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.ARTIFACT, "graph path is invalid", error)
        }
        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!candidate.path.startsWith(rootPrefix) || !candidate.isFile || !candidate.canRead()) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.ARTIFACT,
                "graph file is missing, unreadable, or escapes the bundle root",
            )
        }
        if (candidate.length() != graph.sizeBytes) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.ARTIFACT,
                "graph size mismatch: expected ${graph.sizeBytes}, found ${candidate.length()}",
            )
        }
        val digest = candidate.inputStream().buffered().use { input ->
            val hasher = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hasher.update(buffer, 0, count)
            }
            hasher.digest().toHex()
        }
        if (!digest.equals(graph.sha256, ignoreCase = true)) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.ARTIFACT,
                "graph SHA-256 mismatch",
            )
        }
        return candidate
    }

    companion object {
        internal fun validShape(shape: List<Int>): Boolean {
            if (shape.isEmpty() || shape.size > MediaTekTensorData.MAX_RANK || shape.any { it <= 0 }) return false
            val elements = runCatching {
                shape.fold(1L) { product, dimension -> Math.multiplyExact(product, dimension.toLong()) }
            }.getOrNull() ?: return false
            return elements <= MediaTekTensorData.MAX_TENSOR_ELEMENTS
        }

        internal fun validContractShape(shape: List<Int>): Boolean {
            if (shape.isEmpty() || shape.size > MediaTekTensorData.MAX_RANK || shape.any { it == 0 || it < -1 }) {
                return false
            }
            if (shape.any { it == -1 }) return false
            val fixedElements = runCatching {
                shape.filter { it > 0 }.fold(1L) { product, dimension ->
                    Math.multiplyExact(product, dimension.toLong())
                }
            }.getOrNull() ?: return false
            return fixedElements <= MediaTekTensorData.MAX_TENSOR_ELEMENTS
        }

        internal fun matchesShape(contractShape: List<Int>, actualShape: List<Int>): Boolean =
            contractShape.size == actualShape.size && contractShape.indices.all { index ->
                contractShape[index] == -1 || contractShape[index] == actualShape[index]
            }
    }
}

/** One LiteRT provider/environment is shared by every graph compiled during a generation. */
class MediaTekNeuronGraphRuntime internal constructor(
    context: Context,
    private val graphRunner: MediaTekNeuronGraphRunner,
) : Closeable {
    private val lock = Any()
    private val provider = try {
        BuiltinNpuAcceleratorProvider(context.applicationContext)
    } catch (error: Exception) {
        throw MediaTekGraphExecutionException(
            MediaTekGraphFailureStage.ENVIRONMENT,
            "could not create LiteRT MediaTek NPU provider",
            error,
        )
    }
    private val environment = try {
        Environment.create(context.applicationContext, provider)
    } catch (error: Exception) {
        throw MediaTekGraphExecutionException(
            MediaTekGraphFailureStage.ENVIRONMENT,
            error.message ?: error.javaClass.simpleName,
            error,
        )
    }
    private val availableAccelerators = try {
        environment.getAvailableAccelerators().mapTo(linkedSetOf<String>()) { accelerator: Accelerator ->
            accelerator.name
        }
    } catch (_: Exception) {
        emptySet()
    }
    private val providerDeviceSupportedAdvisory = try {
        provider.isDeviceSupported()
    } catch (_: Exception) {
        false
    }
    private val providerLibraryReadyAdvisory = try {
        provider.isLibraryReady()
    } catch (_: Exception) {
        false
    }
    private val sessions = linkedSetOf<MediaTekNeuronGraphSession>()
    private var closed = false

    fun openGraph(
        bundleRoot: File,
        graph: MediaTekGraphContract,
        ownsRuntime: Boolean = false,
    ): MediaTekNeuronGraphSession = synchronized(lock) {
        checkOpen()
        val artifact = graphRunner.validateAndResolveGraph(bundleRoot, graph)
        val model = try {
            CompiledModel.create(
                artifact.absolutePath,
                CompiledModel.Options(setOf(Accelerator.NPU)),
                environment,
            )
        } catch (error: Exception) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.COMPILE,
                error.message ?: error.javaClass.simpleName,
                error,
            )
        }
        MediaTekNeuronGraphSession(
            model = model,
            graph = graph,
            runtime = this,
            ownsRuntime = ownsRuntime,
            providerDeviceSupportedAdvisory = providerDeviceSupportedAdvisory,
            providerLibraryReadyAdvisory = providerLibraryReadyAdvisory,
            availableAccelerators = availableAccelerators,
        ).also(sessions::add)
    }

    internal fun onSessionClosed(session: MediaTekNeuronGraphSession) = synchronized(lock) {
        sessions.remove(session)
        Unit
    }

    override fun close() {
        val ownedSessions = synchronized(lock) {
            if (closed) return
            closed = true
            sessions.toList().also { sessions.clear() }
        }
        ownedSessions.asReversed().forEach { session ->
            try {
                session.closeFromRuntime()
            } catch (_: Exception) {
                // Continue closing remaining sessions and the environment.
            }
        }
        try {
            environment.close()
        } catch (_: Exception) {
            // Teardown should continue even if the provider reports a close failure.
        }
    }

    private fun checkOpen() {
        if (closed) throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.CLOSED, "graph runtime is closed")
    }
}

/** A compiled graph is scoped to one generation and must be closed by its caller. */
class MediaTekNeuronGraphSession internal constructor(
    private val model: CompiledModel,
    private val graph: MediaTekGraphContract,
    private val runtime: MediaTekNeuronGraphRuntime,
    private val ownsRuntime: Boolean,
    private val providerDeviceSupportedAdvisory: Boolean,
    private val providerLibraryReadyAdvisory: Boolean,
    private val availableAccelerators: Set<String>,
) : Closeable {
    private val lock = Any()
    private var closed = false

    fun run(inputs: List<MediaTekTensorData>): MediaTekGraphRunResult = synchronized(lock) {
        checkOpen()
        val inputErrors = validateTensors(
            label = "input",
            tensors = inputs,
            expected = graph.inputTensors,
        )
        if (inputErrors.isNotEmpty()) {
            throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.INPUTS, inputErrors.joinToString("; "))
        }
        val inputBuffers = try {
            model.createInputBuffers()
        } catch (error: Exception) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.INPUTS,
                error.message ?: error.javaClass.simpleName,
                error,
            )
        }
        val outputBuffers = try {
            model.createOutputBuffers()
        } catch (error: Exception) {
            inputBuffers.closeAll()
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.OUTPUTS,
                error.message ?: error.javaClass.simpleName,
                error,
            )
        }
        try {
            if (inputBuffers.size != inputs.size || outputBuffers.size != graph.outputs.size) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.CONTRACT,
                    "LiteRT tensor counts do not match the manifest order",
                )
            }
            try {
                inputs.zip(inputBuffers).forEach { (value, buffer) -> value.writeInto(buffer) }
            } catch (error: Exception) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.INPUTS,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            val startNanos = System.nanoTime()
            try {
                model.run(inputBuffers, outputBuffers)
            } catch (error: Exception) {
                throw MediaTekGraphExecutionException(
                    MediaTekGraphFailureStage.EXECUTE,
                    error.message ?: error.javaClass.simpleName,
                    error,
                )
            }
            val elapsedMs = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
            val outputs = outputBuffers.mapIndexed { index, buffer ->
                readOutput(graph.file, graph.outputTensors[index], buffer)
            }
            val outputDigest = outputs.sha256()
            MediaTekGraphRunResult(
                outputs = outputs,
                evidence = MediaTekGraphRunEvidence(
                    graphFile = graph.file,
                    graphStage = graph.stage,
                    requestedAccelerator = Accelerator.NPU.name,
                    graphCompiled = true,
                    graphRan = true,
                    npuPartitions = null,
                    cpuFallbackOps = null,
                    providerDeviceSupportedAdvisory = providerDeviceSupportedAdvisory,
                    providerLibraryReadyAdvisory = providerLibraryReadyAdvisory,
                    availableAccelerators = availableAccelerators,
                    outputSha256 = outputDigest,
                    elapsedMs = elapsedMs,
                    state = MediaTekRuntimeState.UNVERIFIED,
                    message = "LiteRT NPU-targeted graph run returned; LiteRT 2.3 does not expose partition evidence.",
                ),
            )
        } catch (error: MediaTekGraphExecutionException) {
            throw error
        } catch (error: Exception) {
            throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.OUTPUTS,
                error.message ?: error.javaClass.simpleName,
                error,
            )
        } finally {
            inputBuffers.closeAll()
            outputBuffers.closeAll()
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        try {
            model.close()
        } finally {
            runtime.onSessionClosed(this)
            if (ownsRuntime) runtime.close()
        }
    }

    internal fun closeFromRuntime() {
        synchronized(lock) {
            if (closed) return
            closed = true
            model.close()
        }
    }

    private fun checkOpen() {
        if (closed) throw MediaTekGraphExecutionException(MediaTekGraphFailureStage.CLOSED, "graph session is closed")
    }

    private fun validateTensors(
        label: String,
        tensors: List<MediaTekTensorData>,
        expected: List<MediaTekTensorContract>,
    ): List<String> = buildList {
        if (tensors.size != expected.size) {
            add("$label tensor count ${tensors.size} does not match manifest count ${expected.size}")
            return@buildList
        }
        tensors.forEachIndexed { index, tensor ->
            val metadata = expected[index]
            tensor.validate(label)?.let(::add)
            if (tensor.type != metadata.dtype.normalizedType()) {
                add("$label tensor ${tensor.name} uses ${tensor.type}; manifest declares ${metadata.dtype}")
            }
            if (tensor.name != metadata.name) {
                add("$label tensor[$index] is ${tensor.name}; manifest order expects ${metadata.name}")
            }
            if (!MediaTekNeuronGraphRunner.matchesShape(metadata.shape, tensor.shape)) {
                add("$label tensor ${tensor.name} shape does not match manifest")
            }
        }
    }

    private fun readOutput(
        graphFile: String,
        metadata: MediaTekTensorContract,
        buffer: TensorBuffer,
    ): MediaTekTensorData {
        val output = when (metadata.dtype.normalizedType()) {
            MediaTekTensorType.FLOAT32 -> MediaTekTensorData.Float32(metadata.name, metadata.shape, buffer.readFloat())
            MediaTekTensorType.INT32 -> MediaTekTensorData.Int32(metadata.name, metadata.shape, buffer.readInt())
            MediaTekTensorType.INT8 -> MediaTekTensorData.Int8(metadata.name, metadata.shape, buffer.readInt8())
            null -> throw MediaTekGraphExecutionException(
                MediaTekGraphFailureStage.CONTRACT,
                "unsupported LiteRT dtype '${metadata.dtype}' for ${metadata.name}",
            )
        }
        validateMediaTekGraphOutput(graphFile, output)
        return output
    }
}

private fun MediaTekTensorData.writeInto(buffer: TensorBuffer) {
    when (this) {
        is MediaTekTensorData.Float32 -> buffer.writeFloat(values)
        is MediaTekTensorData.Int32 -> buffer.writeInt(values)
        is MediaTekTensorData.Int8 -> buffer.writeInt8(values)
    }
}

private fun String.normalizedType(): MediaTekTensorType? = when (trim().lowercase()) {
    "float32", "f32", "float" -> MediaTekTensorType.FLOAT32
    "int32", "i32" -> MediaTekTensorType.INT32
    "int8", "i8" -> MediaTekTensorType.INT8
    else -> null
}

private fun List<TensorBuffer>.closeAll() {
    forEach { buffer ->
        try {
            buffer.close()
        } catch (_: Exception) {
            // Close remaining buffers after a provider teardown failure.
        }
    }
}

private fun List<MediaTekTensorData>.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    forEach { tensor ->
        when (tensor) {
            is MediaTekTensorData.Float32 -> {
                tensor.values.forEach { value ->
                    if (bytes.remaining() < Float.SIZE_BYTES) {
                        digest.update(bytes.array(), 0, bytes.position())
                        bytes.clear()
                    }
                    bytes.putFloat(value)
                }
            }
            is MediaTekTensorData.Int32 -> {
                tensor.values.forEach { value ->
                    if (bytes.remaining() < Int.SIZE_BYTES) {
                        digest.update(bytes.array(), 0, bytes.position())
                        bytes.clear()
                    }
                    bytes.putInt(value)
                }
            }
            is MediaTekTensorData.Int8 -> digest.update(tensor.values)
        }
    }
    if (bytes.position() > 0) digest.update(bytes.array(), 0, bytes.position())
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
