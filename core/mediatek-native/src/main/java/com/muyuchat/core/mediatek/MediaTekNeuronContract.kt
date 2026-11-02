package com.muyuchat.core.mediatek

import java.io.File

/** Stable execution names used by the image engine bundle manifest. */
enum class MediaTekGraphStage {
    TEXT_ENCODER,
    DIFFUSION,
    VAE,
}

enum class MediaTekCompileMode {
    ONLINE,
    OFFLINE,
}

enum class MediaTekRuntimeState {
    READY,
    UNVERIFIED,
    BLOCKED,
    INVALID,
}

enum class MediaTekPrecision {
    FLOAT32,
    FLOAT16,
    BFLOAT16,
    INT8,
}

/** Per-tensor metadata; graph-level fields below remain as compatibility summaries. */
data class MediaTekTensorContract(
    val name: String,
    val dtype: String,
    val layout: String,
    val shape: List<Int>,
) {
    fun validate(label: String): List<String> = buildList {
        if (name.isBlank()) add("$label: tensor name is empty")
        if (dtype.lowercase() !in SUPPORTED_DTYPES) add("$label: unsupported dtype '$dtype'")
        if (layout.isBlank()) add("$label: layout is empty")
        if (shape.isEmpty() || shape.any { it == 0 || it < -1 } || shape.size > MAX_RANK) {
            add("$label: shape must contain 1..$MAX_RANK dimensions; -1 may mark a dynamic axis")
        }
        if (shape.all { it == -1 }) add("$label: shape cannot be fully dynamic")
    }

    private companion object {
        const val MAX_RANK = 8
        val SUPPORTED_DTYPES = setOf("float32", "int8", "int32")
    }
}

/**
 * A vendor-neutral description of one graph produced by the NeuroPilot
 * conversion pipeline. Tensor names and shapes are intentionally data, not
 * assumptions in this module; the converter must write them into the manifest.
 */
data class MediaTekGraphContract(
    val stage: MediaTekGraphStage,
    val file: String,
    val sha256: String,
    val sizeBytes: Long,
    val dtype: String,
    val layout: String,
    val shape: List<Int>,
    val inputs: List<String>,
    val outputs: List<String>,
    val delegate: String,
    val precision: MediaTekPrecision = MediaTekPrecision.FLOAT32,
    val inputTensors: List<MediaTekTensorContract> = inputs.map {
        MediaTekTensorContract(it, dtype, layout, shape)
    },
    val outputTensors: List<MediaTekTensorContract> = outputs.map {
        MediaTekTensorContract(it, dtype, layout, shape)
    },
) {
    fun validate(): List<String> = buildList {
        val normalizedFile = file.replace('\\', '/')
        if (!isSafeGraphPath(normalizedFile)) {
            add("${stage.name}: graph file must be a relative graphs/ path")
        }
        if (!sha256.matches(SHA256_PATTERN)) add("${stage.name}: graph sha256 is invalid")
        if (sizeBytes <= 0) add("${stage.name}: graph sizeBytes must be positive")
        if (dtype.lowercase() !in SUPPORTED_DTYPES) add("${stage.name}: unsupported graph dtype '$dtype'")
        if (layout.isBlank()) add("${stage.name}: layout is empty")
        if (shape.isEmpty() || shape.any { it == 0 || it < -1 } || shape.size > MAX_RANK) {
            add("${stage.name}: shape must contain 1..$MAX_RANK dimensions; -1 may mark a dynamic axis")
        }
        if (inputs.isEmpty()) add("${stage.name}: graph has no inputs")
        if (outputs.isEmpty()) add("${stage.name}: graph has no outputs")
        if (delegate.isBlank()) add("${stage.name}: delegate is empty")
        if (inputs.size != inputs.toSet().size) add("${stage.name}: duplicate graph input name")
        if (outputs.size != outputs.toSet().size) add("${stage.name}: duplicate graph output name")
        if (inputTensors.map { it.name } != inputs) add("${stage.name}: input tensor metadata must match input names and order")
        if (outputTensors.map { it.name } != outputs) add("${stage.name}: output tensor metadata must match output names and order")
        inputTensors.flatMapTo(this) { it.validate("${stage.name} input '${it.name}'") }
        outputTensors.flatMapTo(this) { it.validate("${stage.name} output '${it.name}'") }
    }

    fun inputTensor(name: String): MediaTekTensorContract? = inputTensors.firstOrNull { it.name == name }

    fun outputTensor(name: String): MediaTekTensorContract? = outputTensors.firstOrNull { it.name == name }

    private companion object {
        const val MAX_RANK = 8
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        val SUPPORTED_DTYPES = setOf("float32", "float16", "bfloat16", "int8", "int32", "int64", "bool")

        fun isSafeGraphPath(path: String): Boolean = isSafeRelativeBundlePath(path, "graphs")
    }
}

/** Evidence is written only by a real runner; construction alone is not proof. */
data class MediaTekExecutionEvidence(
    val state: MediaTekRuntimeState,
    val requestedRuntime: String,
    val actualDelegate: String?,
    val actualRuntimeVersion: String?,
    /** Null means the runtime API does not expose the counter; zero is an observed zero. */
    val npuPartitions: Int?,
    /** Null means the runtime API does not expose the counter; zero is an observed zero. */
    val cpuFallbackOps: Int?,
    val stages: Set<MediaTekGraphStage>,
    val semanticGeneration: Boolean,
    val outputMime: String?,
    val outputSha256: String?,
    val message: String,
) {
    val provesNpuExecution: Boolean
        get() = state == MediaTekRuntimeState.READY &&
            requestedRuntime == MediaTekAnimaManifest.RUNTIME &&
            actualDelegate?.contains("neuron", ignoreCase = true) == true &&
            !actualRuntimeVersion.isNullOrBlank() &&
            npuPartitions?.let { it > 0 } == true &&
            stages.containsAll(MediaTekGraphStage.entries) &&
            semanticGeneration &&
            outputMime.equals("image/png", ignoreCase = true) &&
            outputSha256?.matches(Regex("[0-9a-fA-F]{64}")) == true

    companion object {
        fun blocked(reason: String): MediaTekExecutionEvidence = MediaTekExecutionEvidence(
            state = MediaTekRuntimeState.BLOCKED,
            requestedRuntime = MediaTekAnimaManifest.RUNTIME,
            actualDelegate = null,
            actualRuntimeVersion = null,
            npuPartitions = null,
            cpuFallbackOps = null,
            stages = emptySet(),
            semanticGeneration = false,
            outputMime = null,
            outputSha256 = null,
            message = reason,
        )
    }
}

data class MediaTekSdkProbe(
    val state: MediaTekRuntimeState,
    val root: String?,
    val version: String?,
    val missingPaths: List<String>,
    val message: String,
)

data class MediaTekPreflightResult(
    val state: MediaTekRuntimeState,
    val reasons: List<String>,
    val evidence: MediaTekExecutionEvidence,
)

/**
 * Performs the checks that can be made before loading a vendor delegate. It
 * deliberately returns evidence with provesNpuExecution=false; only the real
 * native runner may populate execution counters and output metadata.
 */
object MediaTekNeuronPreflight {
    fun check(manifest: MediaTekAnimaManifest, sdk: MediaTekSdkProbe): MediaTekPreflightResult {
        val manifestErrors = manifest.validate()
        if (manifestErrors.isNotEmpty()) {
            return MediaTekPreflightResult(
                state = MediaTekRuntimeState.INVALID,
                reasons = manifestErrors,
                evidence = MediaTekExecutionEvidence.blocked("Invalid MediaTek Anima manifest."),
            )
        }
        if (sdk.state != MediaTekRuntimeState.READY) {
            return MediaTekPreflightResult(
                state = sdk.state,
                reasons = listOf(sdk.message) + sdk.missingPaths,
                evidence = MediaTekExecutionEvidence.blocked(sdk.message),
            )
        }
        return MediaTekPreflightResult(
            state = MediaTekRuntimeState.UNVERIFIED,
            reasons = listOf("Manifest and SDK marker passed; native graph execution is unverified."),
            evidence = MediaTekExecutionEvidence(
                state = MediaTekRuntimeState.UNVERIFIED,
                requestedRuntime = MediaTekAnimaManifest.RUNTIME,
                actualDelegate = null,
                actualRuntimeVersion = null,
                npuPartitions = null,
                cpuFallbackOps = null,
                stages = emptySet(),
                semanticGeneration = false,
                outputMime = null,
                outputSha256 = null,
                message = "Preflight only; run the native delegate smoke before claiming NPU execution.",
            ),
        )
    }
}

/**
 * Resolves provisioning only. It never searches the device for vendor
 * libraries and never treats an unknown device as unsupported.
 */
object MediaTekSdkResolver {
    const val SDK_ROOT_ENV = "MCA_MEDIATEK_SDK_ROOT"
    const val SDK_ROOT_PROPERTY = "mcaMediatekSdkRoot"

    fun probe(root: File?): MediaTekSdkProbe {
        if (root == null) {
            return MediaTekSdkProbe(
                state = MediaTekRuntimeState.BLOCKED,
                root = null,
                version = null,
                missingPaths = listOf("SDK root"),
                message = "MediaTek NeuroPilot SDK is not provisioned; NPU path is blocked.",
            )
        }
        if (!root.isDirectory) {
            return MediaTekSdkProbe(
                state = MediaTekRuntimeState.INVALID,
                root = root.absolutePath,
                version = null,
                missingPaths = listOf(root.absolutePath),
                message = "MediaTek SDK root is not a directory.",
            )
        }
        val markerFiles = sequenceOf(
            root.resolve("neuron-sdk.properties"),
            root.resolve("VERSION"),
            root.resolve("version.txt"),
            root.resolve("sdk.version"),
            root.resolve("include/NeuroPilot/Neuron.h"),
            root.resolve("include/neuron/neuron.h"),
        )
        val hasHeaderOrPropertiesMarker = markerFiles.any(File::isFile)
        val hasNeuronLibrary = root.resolve("lib").listFiles().orEmpty().any {
            it.isFile && it.name.startsWith("libneuron", ignoreCase = true)
        }
        val hasAar = root.walkTopDown().maxDepth(3).any {
            it.isFile && it.extension.equals("aar", ignoreCase = true)
        }
        if (!hasHeaderOrPropertiesMarker && !hasNeuronLibrary && !hasAar) {
            return MediaTekSdkProbe(
                state = MediaTekRuntimeState.BLOCKED,
                root = root.absolutePath,
                version = null,
                missingPaths = listOf("NeuroPilot SDK marker (header, neuron-sdk.properties, libneuron*.so, or AAR)"),
                message = "Directory is not recognisable as a provisioned MediaTek NeuroPilot SDK.",
            )
        }
        val versionFile = sequenceOf("VERSION", "version.txt", "sdk.version", "neuron-sdk.properties")
            .map(root::resolve)
            .firstOrNull(File::isFile)
        val version = versionFile?.readText()?.trim()?.takeIf(String::isNotBlank)
        return MediaTekSdkProbe(
            state = MediaTekRuntimeState.READY,
            root = root.absolutePath,
            version = version,
            missingPaths = emptyList(),
            message = "SDK root is provisioned; delegate and graph execution remain unverified.",
        )
    }
}
