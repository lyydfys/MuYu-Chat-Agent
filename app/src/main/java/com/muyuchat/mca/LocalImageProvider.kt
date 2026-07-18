package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ImageBackend {
    LOCAL,
    CLOUD
}

enum class LocalImageRuntime(val label: String) {
    STABLE_DIFFUSION_CPP("stable-diffusion.cpp"),
    MNN_DIFFUSION("MNN Diffusion"),
    QNN_HTP("骁龙 NPU"),
    ONNX_RUNTIME("ONNX Runtime"),
    CUSTOM("自定义本地图像引擎");

    companion object {
        fun from(value: String?): LocalImageRuntime =
            entries.firstOrNull { it.name == value } ?: when (value) {
                "MEDIAPIPE", "NCNN", "DIFFUSERS" -> STABLE_DIFFUSION_CPP
                "MNN", "MNN_DIFFUSION", "MNN_DIFFUSION_ENGINE" -> MNN_DIFFUSION
                "QNN", "QNN_HTP", "QAIRT", "HTP" -> QNN_HTP
                "ONNX" -> ONNX_RUNTIME
                else -> CUSTOM
            }

        fun infer(fileName: String): LocalImageRuntime {
            val lower = fileName.lowercase()
            return when {
                "qnn" in lower || "qairt" in lower || "htp" in lower -> QNN_HTP
                lower.endsWith(".mnn") -> MNN_DIFFUSION
                lower.endsWith(".zip") && "mnn" in lower -> MNN_DIFFUSION
                lower.endsWith(".onnx") -> ONNX_RUNTIME
                lower.endsWith(".gguf") ||
                    lower.endsWith(".safetensors") ||
                    lower.endsWith(".ckpt") ||
                    lower.endsWith(".pth") ||
                    lower.endsWith(".pt") ||
                    lower.endsWith(".zip") -> STABLE_DIFFUSION_CPP
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageModelFamily(val label: String) {
    Z_IMAGE("Z-Image"),
    QWEN_IMAGE("Qwen-Image"),
    GLM_IMAGE("GLM-Image"),
    LONGCAT_IMAGE("LongCat-Image"),
    DREAMLITE("DreamLite"),
    SANA("Sana"),
    FLUX("Flux"),
    SD_TURBO("SD-Turbo"),
    SDXL("SDXL"),
    SD21("Stable Diffusion 2.1"),
    SD15("Stable Diffusion 1.5"),
    WAN("Wan"),
    CUSTOM("自定义");

    companion object {
        fun from(value: String?): LocalImageModelFamily =
            entries.firstOrNull { it.name == value } ?: CUSTOM

        fun infer(fileName: String): LocalImageModelFamily {
            val lower = fileName.lowercase()
            return when {
                "z-image" in lower || "z_image" in lower || "zimage" in lower -> Z_IMAGE
                "qwen-image" in lower || "qwen_image" in lower -> QWEN_IMAGE
                "glm-image" in lower || "glm_image" in lower -> GLM_IMAGE
                "longcat-image" in lower || "longcat_image" in lower -> LONGCAT_IMAGE
                "dreamlite" in lower -> DREAMLITE
                "sana" in lower -> SANA
                "flux" in lower -> FLUX
                "sd-turbo" in lower || "sd_turbo" in lower -> SD_TURBO
                "sdxl" in lower || "stable-diffusion-xl" in lower -> SDXL
                "sd-2.1" in lower || "sd2.1" in lower || "sd21" in lower || "v2-1" in lower || "stable-diffusion-2-1" in lower -> SD21
                "sd-1.5" in lower || "sd1.5" in lower || "sd15" in lower || "v1-5" in lower || "stable-diffusion-v1-5" in lower -> SD15
                "wan" in lower -> WAN
                else -> CUSTOM
            }
        }
    }
}

enum class LocalImageVerificationStatus {
    UNKNOWN,
    MNN_SMOKE_PASSED,
    QNN_IMAGE_SMOKE_PASSED,
    QNN_SMOKE_PASSED,
    QNN_PIPELINE_PROBE_PASSED,
    PASSED,
    FAILED;

    companion object {
        fun from(value: String?): LocalImageVerificationStatus =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

internal data class LocalImageBundleManifest(
    val id: String? = null,
    val displayName: String? = null,
    val task: String? = null,
    val runtime: LocalImageRuntime? = null,
    val family: LocalImageModelFamily? = null,
    val imageSize: String? = null,
    val minDeviceTier: ImageEngineMinDeviceTier = ImageEngineMinDeviceTier.ANY,
    val requiresQnnRuntime: Boolean = false,
    val requiredRuntimeProfile: LocalImageQnnRuntimeProfile? = null,
    val requiresSmokeTest: Boolean = true,
    val smokeWidth: Int = 0,
    val smokeHeight: Int = 0,
    val smokeSteps: Int = 0,
    val smokeTimeoutSeconds: Int = 0,
    val qnnSmokeSpec: QnnSmokeSpec = QnnSmokeSpec.Empty,
    val qnnSmokeSpecs: List<QnnSmokeSpec> = emptyList(),
    val primaryFile: File? = null,
    val componentCount: Int = 0
)

internal data class LocalImageQnnRuntimeProfile(
    val qnnSdk: String,
    val htpArch: Int,
    val completeBundleRuntime: Boolean
)

data class LocalImageModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtime: LocalImageRuntime,
    val family: LocalImageModelFamily = LocalImageModelFamily.CUSTOM,
    val imageSize: String = "512x512",
    val source: String = "local",
    val bundleRoot: String? = null,
    val componentCount: Int = 1,
    val verificationStatus: LocalImageVerificationStatus = LocalImageVerificationStatus.UNKNOWN,
    val verificationMessage: String = "",
    val verifiedAt: Long = 0L,
    val qnnVerificationStamp: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val configured: Boolean
        get() = path.isNotBlank() && File(path).exists() && bundleRoot?.let { File(it).exists() } != false

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("path", path)
        .put("fileName", fileName)
        .put("sizeBytes", sizeBytes)
        .put("sha256", sha256)
        .put("runtime", runtime.name)
        .put("family", family.name)
        .put("imageSize", imageSize)
        .put("source", source)
        .put("bundleRoot", bundleRoot)
        .put("componentCount", componentCount)
        .put("verificationStatus", verificationStatus.name)
        .put("verificationMessage", verificationMessage)
        .put("verifiedAt", verifiedAt)
        .put("qnnVerificationStamp", qnnVerificationStamp)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): LocalImageModelRecord =
            LocalImageModelRecord(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                displayName = json.optString("displayName"),
                path = json.optString("path"),
                fileName = json.optString("fileName"),
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
                runtime = LocalImageRuntime.from(json.optString("runtime")),
                family = LocalImageModelFamily.from(json.optString("family")),
                imageSize = json.optString("imageSize", "512x512"),
                source = json.optString("source", "local"),
                bundleRoot = json.optString("bundleRoot").takeIf { it.isNotBlank() && it != "null" },
                componentCount = json.optInt("componentCount", 1).coerceAtLeast(1),
                verificationStatus = LocalImageVerificationStatus.from(json.optString("verificationStatus")),
                verificationMessage = json.optString("verificationMessage"),
                verifiedAt = json.optLong("verifiedAt", 0L),
                qnnVerificationStamp = json.optString("qnnVerificationStamp"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
    }
}

data class LocalImageOutput(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val seed: Long? = null,
    val index: Int = 0
) {
    init {
        require(bytes.isNotEmpty()) { "Local image output must not be empty." }
        require(mimeType.startsWith("image/")) { "Local image output MIME type must use image/*." }
        require(index >= 0) { "Local image output index must be non-negative." }
    }
}

data class LocalImageResult(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    /** Native execution audit returned through the isolated product worker. */
    val executionMetadataJson: String = "",
    val seed: Long? = null,
    /** Ordered batch outputs. The legacy bytes/mimeType fields always mirror index 0. */
    val outputs: List<LocalImageOutput> = listOf(
        LocalImageOutput(bytes = bytes, mimeType = mimeType, seed = seed, index = 0)
    )
) {
    init {
        require(outputs.isNotEmpty()) { "Local image result must contain at least one output." }
        require(outputs.map(LocalImageOutput::index) == outputs.indices.toList()) {
            "Local image output indices must be contiguous and start at zero."
        }
        require(outputs.first().bytes.contentEquals(bytes) &&
            outputs.first().mimeType == mimeType && outputs.first().seed == seed
        ) {
            "Legacy local image result fields must mirror output index zero."
        }
    }
}

/**
 * Consumes the concrete files reported by stable-diffusion.cpp and always removes every
 * worker-private output after reading it. Older single-image runtimes may omit outputs[], but a
 * batch must prove its exact count, contiguous indices, deterministic seeds, MIME type, and files.
 */
internal fun consumeStableDiffusionOutputs(
    result: JSONObject,
    expectedCount: Int,
    expectedSeed: Long,
    legacyOutputFile: File
): List<LocalImageOutput> {
    require(expectedCount in 1..8) { "stable-diffusion.cpp output count must be between 1 and 8." }
    require(expectedSeed >= 0L) { "stable-diffusion.cpp output seed must be non-negative." }
    val outputRoot = requireNotNull(legacyOutputFile.parentFile).canonicalFile
    val cleanupCandidates = linkedSetOf(legacyOutputFile.canonicalFile)
    val hasOutputsField = result.has("outputs")
    val rawOutputs = result.opt("outputs")
    val outputArray = rawOutputs as? JSONArray

    fun rememberCleanupCandidate(path: String) {
        if (path.isBlank()) return
        runCatching { File(path).canonicalFile }
            .getOrNull()
            ?.takeIf { candidate ->
                candidate.path.startsWith(outputRoot.path + File.separator)
            }
            ?.let(cleanupCandidates::add)
    }

    (result.opt("path") as? String)?.let(::rememberCleanupCandidate)
    if (outputArray != null) {
        for (index in 0 until outputArray.length()) {
            val item = outputArray.optJSONObject(index) ?: continue
            (item.opt("path") as? String)?.let(::rememberCleanupCandidate)
        }
    } else if (rawOutputs is JSONObject) {
        (rawOutputs.opt("path") as? String)?.let(::rememberCleanupCandidate)
    }

    fun JSONObject.requiredOutputString(name: String): String {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is String && raw.isNotBlank()) {
            "stable-diffusion.cpp output $name must be a non-blank string."
        }
        return raw
    }

    fun JSONObject.requiredOutputLong(name: String): Long {
        require(has(name) && !isNull(name)) { "stable-diffusion.cpp output is missing $name." }
        val raw = get(name)
        require(raw is Byte || raw is Short || raw is Int || raw is Long) {
            "stable-diffusion.cpp output $name must be an integer."
        }
        return (raw as Number).toLong()
    }

    fun validatedMimeType(raw: String): String {
        val normalized = raw.trim().lowercase()
        require(normalized == "image/png") {
            "stable-diffusion.cpp output MIME type must be image/png."
        }
        return normalized
    }

    fun validatedOutputFile(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(outputRoot.path + File.separator)) {
            "stable-diffusion.cpp output path escaped its output directory."
        }
        require(file.isFile && file.length() > 0L) {
            "stable-diffusion.cpp output file is missing or empty: ${file.name}"
        }
        return file
    }

    return try {
        require(!hasOutputsField || outputArray != null) {
            "stable-diffusion.cpp outputs must be an array."
        }
        if (outputArray == null) {
            require(expectedCount == 1) {
                "stable-diffusion.cpp batch output is missing outputs[]."
            }
            val path = if (result.has("path")) {
                result.requiredOutputString("path")
            } else {
                legacyOutputFile.canonicalPath
            }
            val mimeType = if (result.has("mimeType")) {
                validatedMimeType(result.requiredOutputString("mimeType"))
            } else {
                "image/png"
            }
            val file = validatedOutputFile(path)
            listOf(
                LocalImageOutput(
                    bytes = file.readBytes(),
                    mimeType = mimeType,
                    seed = expectedSeed,
                    index = 0
                )
            )
        } else {
            require(outputArray.length() == expectedCount) {
                "stable-diffusion.cpp returned ${outputArray.length()} outputs; expected $expectedCount."
            }
            listOf("outputCount", "n").forEach { field ->
                if (result.has(field)) {
                    require(result.requiredOutputLong(field) == expectedCount.toLong()) {
                        "stable-diffusion.cpp $field does not match outputs[]."
                    }
                }
            }
            val descriptors = buildList {
                for (index in 0 until outputArray.length()) {
                    val item = outputArray.optJSONObject(index)
                        ?: error("stable-diffusion.cpp output item must be an object.")
                    require(item.requiredOutputLong("index") == index.toLong()) {
                        "stable-diffusion.cpp output indices must be contiguous and start at zero."
                    }
                    val expectedOutputSeed = Math.addExact(expectedSeed, index.toLong())
                    require(item.requiredOutputLong("seed") == expectedOutputSeed) {
                        "stable-diffusion.cpp output seed does not match index $index."
                    }
                    val mimeType = validatedMimeType(item.requiredOutputString("mimeType"))
                    val file = validatedOutputFile(item.requiredOutputString("path"))
                    add(Triple(file, mimeType, expectedOutputSeed))
                }
            }
            require(descriptors.map { it.first.canonicalPath }.distinct().size == descriptors.size) {
                "stable-diffusion.cpp returned duplicate output paths."
            }
            val legacyPath = validatedOutputFile(result.requiredOutputString("path"))
            val legacyMimeType = validatedMimeType(result.requiredOutputString("mimeType"))
            require(legacyPath.canonicalPath == descriptors.first().first.canonicalPath &&
                legacyMimeType == descriptors.first().second
            ) {
                "stable-diffusion.cpp legacy output fields must mirror output index zero."
            }
            descriptors.mapIndexed { index, (file, mimeType, outputSeed) ->
                LocalImageOutput(
                    bytes = file.readBytes(),
                    mimeType = mimeType,
                    seed = outputSeed,
                    index = index
                )
            }
        }
    } finally {
        cleanupCandidates.forEach { file -> runCatching { file.delete() } }
    }
}

data class LocalImageProgress(
    val phase: String,
    val message: String,
    val step: Int,
    val steps: Int,
    val elapsedMs: Long,
    val secondsPerStep: Double,
    val threads: Int,
    val width: Int,
    val height: Int,
    val cancelRequested: Boolean,
    /** Requested/effective controls for audit only; never native execution proof. */
    val requestOptionsJson: String = "",
    /** Actual stable-diffusion.cpp component paths selected by native execution. */
    val componentSelectionJson: String = "",
    /** Monotonic native stage evidence; useful even when a short stage is missed by polling. */
    val stageTrace: List<String> = emptyList(),
    /** App-private PNG written only by the native stable-diffusion.cpp preview callback. */
    val previewPath: String = "",
    val previewMimeType: String = "",
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val previewFrameCount: Int = 0,
    val previewNoisy: Boolean = false
)

/**
 * Explicit generation controls carried across the main-process/worker IPC
 * boundary.  Normal product calls leave every field null and retain the
 * model-family defaults; smoke and benchmark calls can require an exact,
 * auditable configuration instead of silently falling back to those defaults.
 */
data class LocalImageGenerationOptions(
    /** null = use profile default; empty string = explicitly disable it. */
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val threads: Int? = null,
    val seed: Int? = null,
    val cfgScale: Double? = null,
    val distilledGuidance: Double? = null,
    val flowShift: Double? = null,
    val sampleMethod: String? = null,
    val backendMode: String? = null,
    val tokenEmbeddingMode: String? = null,
    val memoryMode: Int? = null,
    val runner: String? = null,
    val useCfg: Boolean? = null,
    val taskMode: LocalImageTaskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
    val inputImage: LocalImagePreparedInput? = null,
    val maskImage: LocalImagePreparedInput? = null,
    val controlImage: LocalImagePreparedInput? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
    val clipSkip: Int? = null,
    val batchCount: Int = 1,
    val vaeTiling: LocalImageVaeTilingOptions? = null,
    val preview: LocalImagePreviewOptions? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        negativePrompt?.let { put("negativePrompt", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        steps?.let { put("steps", it) }
        threads?.let { put("threads", it) }
        seed?.let { put("seed", it) }
        cfgScale?.let { put("cfgScale", it) }
        distilledGuidance?.let { put("distilledGuidance", it) }
        flowShift?.let { put("flowShift", it) }
        sampleMethod?.let { put("sampleMethod", it) }
        backendMode?.let { put("backendMode", it) }
        tokenEmbeddingMode?.let { put("tokenEmbeddingMode", it) }
        memoryMode?.let { put("memoryMode", it) }
        runner?.let { put("runner", it) }
        useCfg?.let { put("useCfg", it) }
        put("taskMode", taskMode.wireName)
        inputImage?.let { put("inputImage", it.toJson()) }
        maskImage?.let { put("maskImage", it.toJson()) }
        controlImage?.let { put("controlImage", it.toJson()) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        put("batchCount", batchCount)
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalImageGenerationOptions {
            if (json == null) return LocalImageGenerationOptions()
            fun optionalInt(key: String): Int? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be an integer." }
                    val value = raw.toDouble()
                    require(value.isFinite() && value % 1.0 == 0.0 && value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
                        "$key must be a finite 32-bit integer."
                    }
                    value.toInt()
                } else null
            fun optionalDouble(key: String): Double? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Number) { "$key must be numeric." }
                    raw.toDouble().also { value -> require(value.isFinite()) { "$key must be finite." } }
                } else null
            fun optionalString(key: String, preserveBlank: Boolean = false): String? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is String) { "$key must be a string." }
                    raw.trim().let { value ->
                        if (preserveBlank || value.isNotBlank()) value else null
                    }
                } else {
                    null
                }
            fun optionalBoolean(key: String): Boolean? =
                if (json.has(key) && !json.isNull(key)) {
                    val raw = json.get(key)
                    require(raw is Boolean) { "$key must be a boolean." }
                    raw
                } else null
            fun optionalObject(key: String): JSONObject? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an object when specified." }
                    val raw = json.get(key)
                    require(raw is JSONObject) { "$key must be an object when specified." }
                    raw
                } else {
                    null
                }

            return LocalImageGenerationOptions(
                negativePrompt = optionalString("negativePrompt", preserveBlank = true)
                    ?: optionalString("negative_prompt", preserveBlank = true),
                width = optionalInt("width"),
                height = optionalInt("height"),
                steps = optionalInt("steps"),
                threads = optionalInt("threads"),
                seed = optionalInt("seed") ?: optionalInt("randomSeed"),
                cfgScale = optionalDouble("cfgScale"),
                distilledGuidance = optionalDouble("distilledGuidance"),
                flowShift = optionalDouble("flowShift"),
                sampleMethod = optionalString("sampleMethod", preserveBlank = true),
                // A present-but-empty execution control is invalid and must reach
                // the MNN resolver rather than becoming an implicit fallback.
                backendMode = optionalString("backendMode", preserveBlank = true),
                tokenEmbeddingMode = optionalString("tokenEmbeddingMode", preserveBlank = true),
                memoryMode = optionalInt("memoryMode"),
                runner = optionalString("runner", preserveBlank = true),
                useCfg = optionalBoolean("useCfg"),
                taskMode = if (json.has("taskMode")) {
                    require(!json.isNull("taskMode")) { "taskMode must be a string when specified." }
                    LocalImageTaskMode.fromWireName(optionalString("taskMode", preserveBlank = true))
                } else {
                    LocalImageTaskMode.TEXT_TO_IMAGE
                },
                inputImage = optionalObject("inputImage")?.let(LocalImagePreparedInput::fromJson),
                maskImage = optionalObject("maskImage")?.let(LocalImagePreparedInput::fromJson),
                controlImage = optionalObject("controlImage")?.let(LocalImagePreparedInput::fromJson),
                strength = optionalDouble("strength"),
                controlStrength = optionalDouble("controlStrength"),
                clipSkip = optionalInt("clipSkip"),
                batchCount = optionalInt("batchCount") ?: 1,
                vaeTiling = optionalObject("vaeTiling")?.let(LocalImageVaeTilingOptions::fromJson),
                preview = optionalObject("preview")?.let(LocalImagePreviewOptions::fromJson)
            ).also { options -> options.validateProductInputContract() }
        }
    }
}

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val bridge by lazy { NativeStableDiffusionBridge() }
    private val mnnDiffusionBridge by lazy { NativeMnnDiffusionBridge() }
    private val qnnBridge by lazy { NativeQnnBridge() }
    private val sdxlCoordinator by lazy { SdxlTwoPhaseCoordinator(appContext) }
    private val cancellationRequested = AtomicBoolean(false)

    @Volatile
    private var activeRuntime: LocalImageRuntime? = null

    fun begin(runtime: LocalImageRuntime) {
        activeRuntime = runtime
        cancellationRequested.set(false)
    }

    fun cancel(): Boolean {
        return when (activeRuntime ?: return false) {
            LocalImageRuntime.QNN_HTP -> {
                cancellationRequested.set(true)
                runCatching { sdxlCoordinator.cancel() }
                if (NativeQnnBridge.isAvailable) {
                    runCatching { qnnBridge.cancelImageGeneration() }
                }
                true
            }
            LocalImageRuntime.MNN_DIFFUSION -> {
                cancellationRequested.set(true)
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.STABLE_DIFFUSION_CPP -> {
                cancellationRequested.set(true)
                if (NativeStableDiffusionBridge.isAvailable) {
                    runCatching { bridge.cancel() }
                }
                true
            }
            else -> false
        }
    }

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        if (activeRuntime != model.runtime) begin(model.runtime)
        try {
            require(model.configured) { "本地图像生成模型文件不存在，请重新导入。" }
            require(prompt.isNotBlank()) { "请输入图片描述。" }
            require(!cancellationRequested.get()) { "本地生图已停止" }
            options.validateProductInputContract()
            validateLocalImageRuntimeProductOptions(model.runtime, options)
        if (model.runtime == LocalImageRuntime.QNN_HTP) {
            require(NativeQnnBridge.isAvailable) {
                val reason = NativeQnnBridge.loadError?.message.orEmpty()
                "Snapdragon NPU image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeQnnBridge.runnerReady) {
                JSONObject(qnnBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "Snapdragon NPU image runner is not packaged in this APK." }
            }
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN prompt encoder failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN prompt encoder is not packaged in this APK." }
            }
            model.localImageReadinessMessage()?.let { message -> error(message) }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf { it.isDirectory }
                ?: File(model.path).parentFile?.takeIf { it.isDirectory }
                ?: error("QNN image engine requires a complete QNN bundle directory.")
            val runtimeResolution = qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
            require(runtimeResolution.stagingError == null) {
                runtimeResolution.stagingError.orEmpty()
            }
            val qnnHealth = QnnHtpImageRunner(context = appContext).health(
                device = DeviceProfileReader(appContext).read(),
                bundleRoot = bundleRoot
            )
            require(qnnHealth.state == LocalImageQnnState.SMOKE_REQUIRED) {
                qnnHealth.message
            }
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot
            )
            val profile = profileResolution.profile
            val resolved = profileResolution.layers.resolved
            val effectiveNegativePrompt =
                effectiveOptions.negativePrompt ?: profile.defaults.defaultNegativePrompt.orEmpty()
            val effectiveFamily = profile.family
            val isSdxlQnn = effectiveFamily == LocalImageModelFamily.SDXL
            // Qualcomm's Gen5 archives include a native QNN CLIP graph.  Keep
            // legacy QNN archives on their existing MNN-embedding path, but
            // pass token IDs to the native graph when the archive explicitly
            // supplies text_encoder.bin.
            val usesQnnClipTokenIds = !isSdxlQnn &&
                qnnNativeTextEncoderContextPath(bundleRoot) != null
            val conditioningRoot = if (isSdxlQnn) {
                resolveSdxlQnnConditioningRoot(bundleRoot)
            } else {
                bundleRoot
            }
            val outputDir = File(
                appContext.cacheDir,
                if (isSdxlQnn) SDXL_TWO_PHASE_DIRECTORY else "local_image_outputs"
            ).apply { mkdirs() }
            val requestToken = "qnn-htp-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val outputFile = File(outputDir, "$requestToken.png")
            val embeddingFile = File(
                outputDir,
                if (isSdxlQnn) {
                    "${outputFile.nameWithoutExtension}.sdxl-conditioning.f32"
                } else if (usesQnnClipTokenIds) {
                    if (resolved.promptWeightingSupported) {
                        "${outputFile.nameWithoutExtension}.qnn-clip-conditioning.bin"
                    } else {
                        "${outputFile.nameWithoutExtension}.qnn-clip-token-ids.i32"
                    }
                } else {
                    "${outputFile.nameWithoutExtension}.sd15-embeddings.f32"
                }
            )
            val progressJournalFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.qnn-stage.json"
            )
            val latentFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.f32")
            val latentMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.latent.json")
            val unetJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.unet-stage.json")
            val vaeJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.vae-stage.json")
            val contract = resolveQnnImageGenerationContract(
                resolution = profileResolution,
                defaultThreads = defaultLocalImageThreads(),
                options = effectiveOptions
            )
            val width = contract.width
            val height = contract.height
            val steps = contract.steps
            val threads = contract.threads
            val startedAt = System.currentTimeMillis()
            fun progress(
                phase: String,
                message: String,
                step: Int = 0,
                totalSteps: Int = steps
            ) {
                onProgress(
                    LocalImageProgress(
                        phase = phase,
                        message = message,
                        step = step,
                        steps = totalSteps,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        secondsPerStep = 0.0,
                        threads = threads,
                        width = width,
                        height = height,
                        cancelRequested = false,
                        requestOptionsJson = contract.auditJson.toString()
                    )
                )
            }

            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                .put("prompt", prompt.trim())
                .put("negativePrompt", effectiveNegativePrompt)
                .put("family", effectiveFamily.name)
                .put("variant", profile.variant.name)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", contract.seed)
                .put("cfgScale", contract.cfgScale)
                .put("sampleMethod", contract.sampleMethod)
                .put("backendMode", contract.backendMode)
                .put("tokenEmbeddingMode", contract.tokenEmbeddingMode)
                .put("memoryMode", contract.memoryMode)
                .put("useCfg", contract.useCfg)
                .put("progressJournalPath", progressJournalFile.absolutePath)
                .putQnnSemanticDefaults(bundleRoot, profile)
            effectiveOptions.putProductInputNativeParams(params)
            if (isSdxlQnn) {
                params.put("conditioningFormat", "sdxl_qnn_conditioning")
            } else if (usesQnnClipTokenIds) {
                params.put(
                    "conditioningFormat",
                    if (resolved.promptWeightingSupported) {
                        "qnn_clip_token_ids_weights_v1"
                    } else {
                        "qnn_clip_token_ids_i32"
                    }
                )
            }

            try {
                progress(
                    "conditioning",
                    if (isSdxlQnn) {
                        "Encoding SDXL prompt conditioning for QNN image generation"
                    } else {
                        "Encoding prompt embeddings for QNN image generation"
                    }
                )
                val embeddingRaw = if (isSdxlQnn) {
                    mnnDiffusionBridge.encodeSdxlPromptConditioning(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        effectiveNegativePrompt,
                        embeddingFile.absolutePath,
                        width,
                        height,
                        contract.backendMode,
                        threads,
                        resolved.promptWeightingSupported
                    )
                } else if (usesQnnClipTokenIds) {
                    encodeQnnClipPromptTokenIds(
                        bridge = mnnDiffusionBridge,
                        bundleRoot = bundleRoot,
                        prompt = prompt.trim(),
                        outputFile = embeddingFile,
                        negativePrompt = effectiveNegativePrompt,
                        bosId = requireNotNull(profile.tokenizer.bosId) {
                            "Resolved tokenizer profile is missing BOS id."
                        },
                        eosId = requireNotNull(profile.tokenizer.eosId) {
                            "Resolved tokenizer profile is missing EOS id."
                        },
                        padId = requireNotNull(profile.tokenizer.padId) {
                            "Resolved tokenizer profile is missing PAD id."
                        },
                        maxTokens = profile.tokenizer.maxLength,
                        promptWeightingEnabled = resolved.promptWeightingSupported
                    )
                } else {
                    mnnDiffusionBridge.encodeSd15PromptEmbeddings(
                        bundleRoot.absolutePath,
                        prompt.trim(),
                        effectiveNegativePrompt,
                        embeddingFile.absolutePath,
                        contract.backendMode,
                        threads,
                        contract.tokenEmbeddingMode,
                        resolved.promptWeightingSupported
                    )
                }
                val embeddingJson = JSONObject(embeddingRaw)
                if (!embeddingJson.optBoolean("ok", false)) {
                    error(embeddingJson.optString("error").ifBlank { "Failed to encode QNN prompt embeddings." })
                }
                val actualConditioningFormat = embeddingJson
                    .optString("conditioningFormat")
                    .ifBlank { embeddingJson.optString("format") }
                    .trim()
                require(actualConditioningFormat.isNotEmpty()) {
                    "Prompt conditioning did not report its native file format."
                }
                val requestedConditioningFormat = params.optString("conditioningFormat").trim()
                if (requestedConditioningFormat.isEmpty()) {
                    params.put("conditioningFormat", actualConditioningFormat)
                } else {
                    require(requestedConditioningFormat == actualConditioningFormat) {
                        "Prompt conditioning format mismatch: resolved=$requestedConditioningFormat, " +
                            "encoder=$actualConditioningFormat."
                    }
                }
                ImageExecutionProfileNativeContract.nativeEvidenceOnlyFields.forEach { field ->
                    require(embeddingJson.has(field) && !embeddingJson.isNull(field)) {
                        "Prompt conditioning did not report native weighting evidence: $field"
                    }
                    params.put(field, embeddingJson.get(field))
                }
                require(embeddingFile.isFile && embeddingFile.length() > 0L) {
                    "Prompt conditioning did not produce a concrete artifact."
                }
                val conditioningArtifactSha256 = embeddingFile.sha256Contents()
                params.put("conditioningArtifactSha256", conditioningArtifactSha256)
                if (cancellationRequested.get()) {
                    error("本地生图已停止")
                }

                progress("sampling", "正在骁龙 NPU 上运行 QNN UNet 和 VAE")
                val raw = if (isSdxlQnn) {
                    sdxlCoordinator.generate(
                        requestId = requestToken,
                        bundleRoot = bundleRoot,
                        runtimeDirsJson = qnnRuntimeDirsJson(bundleRoot),
                        params = params,
                        embeddingsFile = embeddingFile,
                        latentFile = latentFile,
                        metadataFile = latentMetadataFile,
                        outputFile = outputFile,
                        unetJournal = unetJournalFile,
                        vaeJournal = vaeJournalFile,
                        onProgress = onProgress
                    )
                } else {
                    runCatching { progressJournalFile.delete() }
                    runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                    val progressPoller = launch(Dispatchers.Default) {
                        var lastJournalProgress: LocalImageProgress? = null
                        while (isActive) {
                            val observed = QnnImageStageJournal.readOrPrevious(
                                file = progressJournalFile,
                                previous = lastJournalProgress,
                                threads = threads,
                                width = width,
                                height = height
                            )
                            if (observed != null && observed != lastJournalProgress) {
                                lastJournalProgress = observed
                                onProgress(observed)
                            }
                            delay(250)
                        }
                    }
                    try {
                        qnnBridge.runImageSemanticGenerate(
                            bundleRoot.absolutePath,
                            qnnRuntimeDirsJson(bundleRoot),
                            params.toString(),
                            embeddingFile.absolutePath,
                            outputFile.absolutePath
                        )
                    } finally {
                        progressPoller.cancelAndJoin()
                        QnnImageStageJournal.readOrPrevious(
                            file = progressJournalFile,
                            previous = qnnBridge.currentImageProgressOrNull(threads, width, height),
                            threads = threads,
                            width = width,
                            height = height
                        )?.let(onProgress)
                    }
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    error(
                        if (json.optBoolean("cancelled", false)) {
                            "本地生图已停止"
                        } else {
                            json.optString("message").ifBlank { "Snapdragon NPU image generation failed." }
                        }
                    )
                }
                if (!isSdxlQnn) {
                    val nativeEffective = json.optJSONObject("nativeEffective")
                        ?: error("QNN image generation did not report nativeEffective evidence.")
                    require(json.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        nativeEffective.optString("conditioningArtifactSha256") == conditioningArtifactSha256
                    ) {
                        "QNN image generation did not consume the exact prepared conditioning artifact."
                    }
                }
                ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
                val inputExecutionAudit = when (effectiveOptions.taskMode) {
                    LocalImageTaskMode.CONTROL ->
                        verifyAndSanitizeQnnProductInput(json, effectiveOptions)
                    LocalImageTaskMode.TEXT_TO_IMAGE -> {
                        verifyAndSanitizeQnnTextToImagePrivatePaths(json, effectiveOptions)
                        null
                    }
                    else -> error(
                        "QNN native product input verification is unavailable for " +
                            effectiveOptions.taskMode.wireName
                    )
                }
                progress("completed", "QNN NPU image generation completed", step = steps)
                val generated = File(json.optString("outputPath", outputFile.absolutePath))
                require(generated.exists() && generated.length() > 0L) { "Snapdragon NPU did not output a valid image." }
                return@withContext LocalImageResult(
                    bytes = generated.readBytes(),
                    mimeType = json.optString("mimeType", "image/png"),
                    executionMetadataJson = qnnImageExecutionMetadata(
                        nativeRequestId = requestToken,
                        nativeResult = json,
                        outputBytes = generated.length(),
                        inputExecutionAudit = inputExecutionAudit
                    ).toString(),
                    seed = contract.seed.toLong()
                )
            } finally {
                runCatching { embeddingFile.delete() }
                runCatching { outputFile.delete() }
                runCatching { progressJournalFile.delete() }
                runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                runCatching { latentFile.delete() }
                runCatching { File(latentFile.absolutePath + ".part").delete() }
                runCatching { latentMetadataFile.delete() }
                runCatching { File(latentMetadataFile.absolutePath + ".part").delete() }
                runCatching { unetJournalFile.delete() }
                runCatching { File(unetJournalFile.absolutePath + ".tmp").delete() }
                runCatching { vaeJournalFile.delete() }
                runCatching { File(vaeJournalFile.absolutePath + ".tmp").delete() }
            }
        }
        require(model.runtime != LocalImageRuntime.QNN_HTP) {
            "QNN/QAIRT 本地生图 runner 尚未打包。该引擎包需要骁龙 NPU runtime、完整 QNN context/bin 组件，并通过 1-step smoke test 后才能生成。"
        }
        if (model.runtime == LocalImageRuntime.MNN_DIFFUSION) {
            require(NativeMnnDiffusionBridge.isAvailable) {
                val reason = NativeMnnDiffusionBridge.loadError?.message.orEmpty()
                "MNN-Diffusion image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeMnnDiffusionBridge.runnerReady) {
                JSONObject(mnnDiffusionBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank { "MNN-Diffusion native runner is not packaged in this APK." }
            }
            val bundleRoot = model.bundleRoot
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("MNN-Diffusion requires a complete model bundle directory.")
            prepareMnnDiffusionTokenizerIfPossible(bundleRoot)
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            val outputDir = File(appContext.cacheDir, "local_image_outputs").apply { mkdirs() }
            val outputFile = File(outputDir, "mnn-diffusion-${System.currentTimeMillis()}.png")
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot
            )
            val profile = profileResolution.profile
            val resolved = profileResolution.layers.resolved
            val effectiveNegativePrompt =
                effectiveOptions.negativePrompt ?: profile.defaults.defaultNegativePrompt.orEmpty()
            val runner = resolveMnnDiffusionProfileRunner(profile, effectiveOptions.runner)
            val (defaultWidth, defaultHeight) = resolved.width to resolved.height
            // The direct SD1.5 interpreter has a fixed 64x64 latent today.
            // Accepting a mismatched requested size would produce a misleading
            // result, so reject it rather than silently rendering another size.
            val (width, height) = resolveMnnDiffusionDimensions(
                defaultWidth = defaultWidth,
                defaultHeight = defaultHeight,
                requestedWidth = resolved.width,
                requestedHeight = resolved.height
            )
            val steps = resolved.steps
            val threads = resolveMnnDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
            val backendMode = resolveMnnDiffusionBackendMode(effectiveOptions.backendMode)
            val sampleMethod = imageSchedulerProductName(resolved.scheduler)
            require(effectiveOptions.tokenEmbeddingMode == null) {
                "MNN-Diffusion no longer accepts tokenEmbeddingMode; token table precision is " +
                    "determined from the package's exact byte contract."
            }
            val memoryMode = resolveMnnDiffusionMemoryMode(effectiveOptions.memoryMode)
            require(runner != "direct" || memoryMode == 0) {
                "MNN-Diffusion direct runner requires memoryMode=0."
            }
            val seed = resolved.seed.toInt()
            val cfgScale = resolved.cfgScale
            val useCfg = resolved.useCfg
            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
                .put("prompt", prompt.trim())
                .put("negativePrompt", effectiveNegativePrompt)
                .put("family", profile.family.name)
                .put("variant", profile.variant.name)
                .put("width", width)
                .put("height", height)
                .put("steps", steps)
                .put("threads", threads)
                .put("seed", seed)
                .put("randomSeed", seed)
                .put("cfgScale", cfgScale)
                .put("useCfg", useCfg)
                .put("sampleMethod", sampleMethod)
                .put("runner", runner)
                .put("backendMode", backendMode)
                .put("memoryMode", memoryMode)
            effectiveOptions.putProductInputNativeParams(params)
            onProgress(
                LocalImageProgress(
                    phase = "request_validated",
                    message = "MNN-Diffusion request controls validated; native execution has not started.",
                    step = 0,
                    steps = steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = threads,
                    width = width,
                    height = height,
                    cancelRequested = false,
                    requestOptionsJson = mnnDiffusionControlAuditJson(params).toString()
                )
            )
            val progressPoller = launch {
                while (isActive) {
                    mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            val raw = try {
                mnnDiffusionBridge.generate(
                    bundleRoot.absolutePath,
                    params.toString(),
                    outputFile.absolutePath
                )
            } finally {
                progressPoller.cancelAndJoin()
                mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
            }
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                error(json.optString("error").ifBlank { "MNN-Diffusion image generation failed." })
            }
            ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
            require(mnnDiffusionBackendMatches(backendMode, json.getString("backendMode"))) {
                "MNN-Diffusion did not execute on the resolved $backendMode backend."
            }
            require(json.getString("runner").trim().lowercase() == runner) {
                "MNN-Diffusion did not execute with the resolved $runner runner."
            }
            require(json.getString("sampleMethod") == sampleMethod) {
                "MNN-Diffusion did not execute the resolved scheduler."
            }
            require(json.getInt("memoryMode") == memoryMode) {
                "MNN-Diffusion did not execute the resolved memory mode."
            }
            val inputExecutionAudit = verifyAndSanitizeMnnProductInput(json, effectiveOptions)
            val generated = File(json.optString("path", outputFile.absolutePath))
            require(generated.exists() && generated.length() > 0L) { "MNN-Diffusion did not output a valid image." }
            return@withContext LocalImageResult(
                bytes = generated.readBytes(),
                mimeType = json.optString("mimeType", "image/png"),
                executionMetadataJson = json
                    .put("imageInput", inputExecutionAudit)
                    .toString(),
                seed = seed.toLong()
            )
        }
        require(model.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
        }
        require(NativeStableDiffusionBridge.isAvailable) {
            val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
            "stable-diffusion.cpp 本地后端加载失败${if (reason.isBlank()) "" else "：$reason"}"
        }
        model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
        val componentSelection = resolveStableDiffusionComponentSelection(model)
        val effectiveFamily = componentSelection.family
        val bundleRoot = File(componentSelection.bundleRoot).takeIf(File::isDirectory)
            ?: error("stable-diffusion.cpp requires a complete model bundle directory.")
        val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
        val inferredUseCfg = options.useCfg ?: options.cfgScale?.let { cfgScale ->
            kotlin.math.abs(cfgScale - 1.0) > 1e-12
        }
        val effectiveOptions = options.copy(
            seed = options.seed ?: fallbackSeed,
            useCfg = inferredUseCfg
        )
        val profileResolution = resolveLocalImageExecutionProfile(
            model = model,
            options = effectiveOptions,
            bundleRoot = bundleRoot,
            familyOverride = effectiveFamily
        )
        val profile = profileResolution.profile
        val nativeComponentSelection = componentSelection.withControlNetPath(
            resolveProfileControlNetPath(bundleRoot, profile)
        )
        val resolved = profileResolution.layers.resolved

        val outputDir = File(appContext.cacheDir, "local_image_outputs").apply { mkdirs() }
        val outputFile = File(outputDir, "sdcpp-${System.currentTimeMillis()}.png")
        val (width, height) = resolveStableDiffusionDimensions(
            defaultWidth = resolved.width,
            defaultHeight = resolved.height,
            requestedWidth = resolved.width,
            requestedHeight = resolved.height
        )
        val steps = resolved.steps
        val threads = resolveStableDiffusionThreads(effectiveOptions.threads, defaultLocalImageThreads())
        val seed = resolved.seed
        val cfgScale = resolved.cfgScale
        val distilledGuidance = resolveStableDiffusionFiniteControl(
            name = "distilledGuidance",
            requested = effectiveOptions.distilledGuidance,
            defaultValue = 3.5
        )
        val flowShift = resolveStableDiffusionFiniteControl(
            name = "flowShift",
            requested = effectiveOptions.flowShift,
            defaultValue = defaultStableDiffusionFlowShiftFor(profile)
        )
        val sampleMethod = imageSchedulerProductName(resolved.scheduler)
        val backendMode = resolveStableDiffusionBackendMode(effectiveOptions.backendMode)
        val effectiveNegativePrompt =
            effectiveOptions.negativePrompt ?: profile.defaults.defaultNegativePrompt.orEmpty()
        require(effectiveOptions.runner == null) {
            "stable-diffusion.cpp does not support the MNN runner option."
        }
        require(effectiveOptions.tokenEmbeddingMode == null) {
            "stable-diffusion.cpp no longer accepts tokenEmbeddingMode; its native tokenizer owns conditioning storage."
        }
        require(effectiveOptions.memoryMode == null || effectiveOptions.memoryMode == 0) {
            "stable-diffusion.cpp supports only memoryMode=0."
        }
        val params = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
            .put("prompt", prompt.trim())
            .put("negativePrompt", effectiveNegativePrompt)
            .put("family", effectiveFamily.name)
            .put("variant", profile.variant.name)
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("distilledGuidance", distilledGuidance)
            .put("distilledGuidanceSpecified", effectiveOptions.distilledGuidance != null)
            .put("flowShift", flowShift)
            .put("flowShiftSpecified", effectiveOptions.flowShift != null)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
        effectiveOptions.putProductInputNativeParams(params)
        nativeComponentSelection.putIntoNativeParams(params)

        val progressPoller = launch {
            while (isActive) {
                bridge.currentProgressOrNull()?.let(onProgress)
                delay(500)
            }
        }
        val raw = try {
            bridge.generate(
                componentSelection.primaryPath,
                componentSelection.bundleRoot,
                params.toString(),
                outputFile.absolutePath
            )
        } finally {
            progressPoller.cancelAndJoin()
            bridge.currentProgressOrNull()?.let(onProgress)
            // stable-diffusion.cpp keeps a process-global context for reuse.
            // The product worker is disposable and must return the model's
            // multi-gigabyte mappings after every terminal outcome instead.
            runCatching { bridge.shutdown() }
            stableDiffusionPreviewCandidates(outputFile).forEach { preview ->
                runCatching { preview.delete() }
            }
        }
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            val message = json.optString("error").ifBlank { "stable-diffusion.cpp 生成失败。" }
            error(message)
        }
        ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
        require(json.optInt("width", -1) == width && json.optInt("height", -1) == height) {
            "stable-diffusion.cpp did not execute the requested ${width}x${height} dimensions."
        }
        require(json.optInt("steps", -1) == steps) {
            "stable-diffusion.cpp did not execute the requested $steps steps."
        }
        require(json.optInt("threads", -1) == threads) {
            "stable-diffusion.cpp did not execute the requested $threads threads."
        }
        require(json.has("seed") && json.getLong("seed") == seed) {
            "stable-diffusion.cpp did not execute the requested seed."
        }
        verifyStableDiffusionResultControl(json, "cfgScale", cfgScale)
        verifyStableDiffusionDistilledGuidanceResult(
            result = json,
            requested = distilledGuidance,
            specified = effectiveOptions.distilledGuidance != null
        )
        verifyStableDiffusionFlowShiftResult(
            result = json,
            requested = flowShift,
            specified = effectiveOptions.flowShift != null,
            expectApplied = resolved.predictionType == ImagePredictionType.FLOW
        )
        require(stableDiffusionNativeSampleMethodMatches(resolved.scheduler, json.optString("sampleMethod"))) {
            "stable-diffusion.cpp did not execute the resolved ${resolved.scheduler} sampler."
        }
        require(
            json.has("negativePrompt") &&
                json.getString("negativePrompt") == effectiveNegativePrompt
        ) {
            "stable-diffusion.cpp did not execute the resolved negative prompt."
        }
        require(json.optString("backendMode") == backendMode) {
            "stable-diffusion.cpp did not execute on backendMode=$backendMode."
        }
        require(json.optBoolean("contextReleased", false)) {
            "stable-diffusion.cpp did not confirm native context release."
        }
        val componentSelectionAudit = nativeComponentSelection.verifyNativeEcho(json)
        val inputExecutionAudit = verifyAndSanitizeStableDiffusionProductInput(json, effectiveOptions)
        val executionMetadataJson = json
            .put("componentSelection", componentSelectionAudit)
            .put("imageInput", inputExecutionAudit)
            .toString()
        val outputs = consumeStableDiffusionOutputs(
            result = json,
            expectedCount = effectiveOptions.batchCount,
            expectedSeed = seed,
            legacyOutputFile = outputFile
        )
        val first = outputs.first()
        LocalImageResult(
            bytes = first.bytes,
            mimeType = first.mimeType,
            executionMetadataJson = executionMetadataJson,
            seed = first.seed,
            outputs = outputs
        )
        } finally {
            if (activeRuntime == model.runtime) {
                activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }

    private fun NativeStableDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
        runCatching {
            localImageProgressFromJson(JSONObject(getProgress()))
        }.getOrNull()

    private fun NativeMnnDiffusionBridge.currentProgressOrNull(): LocalImageProgress? =
        runCatching {
            localImageProgressFromJson(JSONObject(getProgress()))
        }.getOrNull()

    private fun NativeQnnBridge.currentImageProgressOrNull(
        threads: Int,
        width: Int,
        height: Int
    ): LocalImageProgress? = runCatching {
        val json = JSONObject(getImageGenerationProgressJson())
        if (!json.optBoolean("active") &&
            !json.optBoolean("cancelRequested") &&
            (json.optJSONArray("stageTrace")?.length() ?: 0) == 0
        ) {
            return@runCatching null
        }
        localImageProgressFromJson(json).copy(
            threads = threads,
            width = width,
            height = height
        )
    }.getOrNull()

    private fun localImageProgressFromJson(json: JSONObject): LocalImageProgress =
        LocalImageProgress(
            phase = json.optString("phase"),
            message = json.optString("message"),
            step = json.optInt("step"),
            steps = json.optInt("steps"),
            elapsedMs = json.optLong("elapsedMs"),
            secondsPerStep = json.optDouble("secondsPerStep"),
            threads = json.optInt("threads"),
            width = json.optInt("width"),
            height = json.optInt("height"),
            cancelRequested = json.optBoolean("cancelRequested"),
            componentSelectionJson = json.optJSONObject("componentSelection")?.toString().orEmpty(),
            previewPath = json.optString("previewPath"),
            previewMimeType = json.optString("previewMimeType"),
            previewMode = json.optString("previewMode"),
            previewStep = json.optInt("previewStep"),
            previewRevision = json.optLong("previewRevision"),
            previewWidth = json.optInt("previewWidth"),
            previewHeight = json.optInt("previewHeight"),
            previewFrameCount = json.optInt("previewFrameCount"),
            previewNoisy = json.optBoolean("previewNoisy"),
            stageTrace = json.optJSONArray("stageTrace")?.let { trace ->
                buildList {
                    for (index in 0 until trace.length()) {
                        trace.optString(index).takeIf(String::isNotBlank)?.let(::add)
                    }
                }
            }.orEmpty()
        )

    private fun defaultLocalImageThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return when {
            available >= 10 -> 5
            available >= 8 -> 4
            available >= 6 -> 4
            available >= 4 -> 3
            else -> 2
        }.coerceAtMost(available).coerceAtLeast(1)
    }

    private fun qnnRuntimeDirsJson(bundleRoot: File): String {
        return JSONArray(qnnRuntimeDirectoriesFor(appContext, bundleRoot)).toString()
    }

    private fun JSONObject.putQnnSemanticDefaults(
        bundleRoot: File,
        profile: ImageExecutionProfile
    ): JSONObject {
        val manifest = localImageBundleManifestFromRoot(bundleRoot)
        val smokes = manifest?.qnnSmokeSpecs.orEmpty()
        val unet = smokes.firstOrNull { spec ->
            val lower = spec.contextBinary.lowercase()
            "unet" in lower || "diffusion" in lower || spec.inputs.size >= 3
        } ?: smokes.firstOrNull()
        val vae = smokes.firstOrNull { spec ->
            val lower = spec.contextBinary.lowercase()
            "vae" in lower || "decoder" in lower || (spec.inputs.size == 1 && spec.outputs.any { it.shape.contains(3) })
        }
        val manifestBound = profile.provenance.primarySource == ImageProfileSource.MANIFEST
        fun resolveProfileArtifact(
            artifact: ImageGraphArtifactContract?,
            required: Boolean,
            vararg legacyNames: String
        ): String? {
            artifact?.relativePath?.trim()?.takeIf(String::isNotEmpty)?.let { relativePath ->
                require(!File(relativePath).isAbsolute && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(relativePath)) {
                    "Image execution graph path must stay inside the installed bundle."
                }
                val root = bundleRoot.canonicalFile
                val candidate = File(root, relativePath).canonicalFile
                require(candidate.path.startsWith(root.path + File.separator)) {
                    "Image execution graph path escapes the installed bundle."
                }
                if (candidate.isFile) return candidate.relativeTo(root).invariantSeparatorsPath
                if (manifestBound && required) {
                    error("Installed image execution profile is missing required graph: $relativePath")
                }
            }
            return qnnFirstContextPath(bundleRoot, *legacyNames)
        }
        val profileUnet = resolveProfileArtifact(profile.graph.unet, true, "unet.bin")
        val profileVae = resolveProfileArtifact(
            profile.graph.vae,
            true,
            "vae.bin",
            "vae_decoder.bin"
        )
        val textEncoderContext = resolveProfileArtifact(
            profile.graph.textEncoder?.takeIf { it.relativePath.endsWith(".bin", ignoreCase = true) },
            false,
            "text_encoder.bin"
        )
        val controlNetContext = resolveProfileArtifact(
            profile.graph.controlNet,
            profile.task == ImageTask.CONTROL_IMAGE,
            "controlnet.bin"
        )
        put(
            "unetContextBinary",
            profileUnet
                ?: unet?.contextBinary?.takeIf { it.isNotBlank() }
                ?: "unet.bin"
        )
        put(
            "vaeDecoderContextBinary",
            profileVae
                ?: vae?.contextBinary?.takeIf { it.isNotBlank() }
                ?: "vae_decoder.bin"
        )
        textEncoderContext?.let { put("textEncoderContextBinary", it) }
        controlNetContext?.let { put("controlNetContextBinary", it) }
        put("graphName", profile.graph.unet?.graphName?.takeIf { it.isNotBlank() }
            ?: unet?.graphName?.takeIf { it.isNotBlank() }
            ?: "model")
        profile.graph.textEncoder?.graphName?.takeIf { it.isNotBlank() }
            ?.let { put("textEncoderGraphName", it) }
        profile.graph.controlNet?.graphName?.takeIf { it.isNotBlank() }
            ?.let { put("controlNetGraphName", it) }
        return this
    }
}

/**
 * Returns the exact bundle-relative QNN text-encoder context path.  Presence
 * of this graph selects the token-ID contract; it is a package capability,
 * never a device admission rule.
 */
internal fun qnnNativeTextEncoderContextPath(bundleRoot: File): String? =
    qnnFirstContextPath(bundleRoot, "text_encoder.bin")

internal fun qnnFirstContextPath(bundleRoot: File, vararg names: String): String? {
    val expected = names.map(String::lowercase).toSet()
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file -> file.isFile && file.name.lowercase() in expected }
        ?.let { file ->
            runCatching { file.canonicalFile.relativeTo(root).invariantSeparatorsPath }.getOrNull()
        }
}

/** Locates the MNN tokenizer sidecar consumed by [MtokTokenizer]. */
internal fun qnnClipTokenizerRoot(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .filter { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.mtok", ignoreCase = true)
        }
        .mapNotNull(File::getParentFile)
        .firstOrNull()
        ?.let { parent -> runCatching { parent.canonicalFile }.getOrNull() }
}

/** Locates the complete tokenizer contract used by the standard CLIP backend. */
internal fun qnnClipTokenizerJsonFile(bundleRoot: File): File? {
    val root = runCatching { bundleRoot.canonicalFile }.getOrNull() ?: return null
    return root.walkTopDown()
        .firstOrNull { file ->
            file.isFile &&
                file.length() > 0L &&
                file.name.equals("tokenizer.json", ignoreCase = true)
        }
        ?.let { file ->
            runCatching { file.canonicalFile }
                .getOrNull()
                ?.takeIf { candidate ->
                    candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)
                }
        }
}

/** Writes raw token IDs or the versioned token+weight payload selected by the resolved profile. */
internal fun encodeQnnClipPromptTokenIds(
    bridge: NativeMnnDiffusionBridge,
    bundleRoot: File,
    prompt: String,
    outputFile: File,
    negativePrompt: String = "",
    bosId: Int = 49_406,
    eosId: Int = 49_407,
    padId: Int = 49_407,
    maxTokens: Int = 77,
    promptWeightingEnabled: Boolean = false
): String = runCatching {
    require(maxTokens in 1..4_096) { "CLIP tokenizer max length is invalid: $maxTokens." }
    val tokenizerJson = qnnClipTokenizerJsonFile(bundleRoot)
    val tokenizerRoot = qnnClipTokenizerRoot(bundleRoot)
    val tokenizerBackend: String
    outputFile.parentFile?.mkdirs()
    if (promptWeightingEnabled) {
        require(tokenizerJson != null) {
            "Prompt weighting requires tokenizer/tokenizer.json in the image bundle."
        }
        return@runCatching bridge.encodePromptTokenIdsWithWeightsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens,
            outputPath = outputFile.absolutePath
        )
    }
    val tokenIds = if (tokenizerJson != null) {
        tokenizerBackend = "tokenizers_cpp"
        bridge.tokenizePromptTokenIdsFromJson(
            tokenizerJsonPath = tokenizerJson.absolutePath,
            prompt = prompt,
            negativePrompt = negativePrompt,
            bosId = bosId,
            eosId = eosId,
            padId = padId,
            maxTokens = maxTokens
        )
    } else if (tokenizerRoot != null && negativePrompt.isEmpty()) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIdsWithConfig(
            bundleRoot = bundleRoot.absolutePath,
            prompt = prompt,
            tokenizerRoot = tokenizerRoot.absolutePath,
            bosId = bosId,
            eosId = eosId,
            maxTokens = maxTokens
        )
    } else if (negativePrompt.isEmpty() && bosId == 49_406 && eosId == 49_407 && padId == 49_407 && maxTokens == 77) {
        tokenizerBackend = "mnn_mtok"
        bridge.tokenizePromptTokenIds(bundleRoot.absolutePath, prompt)
    } else {
        error(
            "The image bundle does not contain tokenizer/tokenizer.json; " +
                "the requested negative prompt or tokenizer contract cannot be executed exactly."
        )
    }
    val expectedTokenCount = maxTokens * 2
    require(tokenIds.size == expectedTokenCount) {
        "QNN CLIP tokenizer returned ${tokenIds.size} IDs; expected $expectedTokenCount."
    }
    val bytes = java.nio.ByteBuffer
        .allocate(tokenIds.size * Int.SIZE_BYTES)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
    tokenIds.forEach(bytes::putInt)
    outputFile.writeBytes(bytes.array())
    JSONObject()
        .put("ok", true)
        .put("conditioningFormat", "qnn_clip_token_ids_i32")
        .put("tokenizerBackend", tokenizerBackend)
        .put("negativePromptSpecified", negativePrompt.isNotEmpty())
        .put("bosId", bosId)
        .put("eosId", eosId)
        .put("padId", padId)
        .put("tokenCount", tokenIds.size)
        .put("outputPath", outputFile.absolutePath)
        .toString()
}.getOrElse { error ->
    JSONObject()
        .put("ok", false)
        .put("error", error.message ?: "Failed to tokenize QNN CLIP prompt.")
        .toString()
}

internal fun qnnImageExecutionMetadata(
    nativeRequestId: String,
    nativeResult: JSONObject,
    outputBytes: Long,
    inputExecutionAudit: JSONObject? = null
): JSONObject = JSONObject().also { metadata ->
    ImageExecutionProfileNativeContract.requiredFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    val nativeEffective = requireNotNull(nativeResult.optJSONObject("nativeEffective")) {
        "Native QNN execution metadata is missing nativeEffective."
    }
    ImageExecutionProfileNativeContract.qnnNativeEffectiveFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing required field: $field"
        }
        require(nativeEffective.has(field) && !nativeEffective.isNull(field)) {
            "Native QNN nativeEffective metadata is missing required field: $field"
        }
        require(nativeResult.get(field) == nativeEffective.get(field)) {
            "Native QNN $field evidence conflicts with nativeEffective."
        }
        metadata.put(field, nativeResult.get(field))
    }
    val pixelRange = ImagePixelRange.entries.firstOrNull {
        it.name == nativeResult.getString("pixelRange")
    } ?: error("Native QNN execution reported an unknown pixelRange.")
    require(pixelRange != ImagePixelRange.RUNTIME_NATIVE) {
        "Native QNN execution must report an explicit pixelRange."
    }
    val expectedConversion =
        ImageExecutionProfileNativeContract.qnnPixelRangeConversionName(pixelRange)
    require(nativeResult.getString("pixelRangeConversion") == expectedConversion) {
        "Native QNN pixel-range conversion evidence does not match pixelRange."
    }
    fun requiredExactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN execution metadata field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN execution metadata field $field must be an exact integer."
        }
        return value
    }
    val valueCount = requiredExactLong("pixelRangeValueCount")
    val clampedValueCount = requiredExactLong("pixelRangeClampedValueCount")
    val expectedValueCount = Math.multiplyExact(
        Math.multiplyExact(nativeResult.getLong("width"), nativeResult.getLong("height")),
        3L
    )
    require(valueCount == expectedValueCount) {
        "Native QNN pixel-range value count does not match the generated RGB image."
    }
    require(clampedValueCount in 0L..valueCount) {
        "Native QNN pixel-range clamp count is invalid."
    }
    val observedMin = (nativeResult.opt("pixelRangeObservedMin") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMin evidence must be numeric.")
    val observedMax = (nativeResult.opt("pixelRangeObservedMax") as? Number)?.toDouble()
        ?: error("Native QNN pixelRangeObservedMax evidence must be numeric.")
    require(observedMin.isFinite() && observedMax.isFinite() && observedMin <= observedMax) {
        "Native QNN observed pixel range is invalid."
    }
    ImageExecutionProfileNativeContract.qnnPixelRangeEvidenceFields.forEach { field ->
        require(nativeResult.has(field) && !nativeResult.isNull(field)) {
            "Native QNN execution metadata is missing pixel-range evidence: $field"
        }
        metadata.put(field, nativeResult.get(field))
    }
    metadata.put("nativeEffective", nativeEffective)
    nativeResult.optJSONArray("timesteps")?.let { metadata.put("timesteps", it) }
    nativeResult.optJSONArray("sigmas")?.let { metadata.put("sigmas", it) }
    inputExecutionAudit?.let { metadata.put("imageInput", it) }
    listOf(
        "taskMode",
        "batchCount",
        "inputImageExecutionCount",
        "maskImageExecutionCount",
        "controlImageExecutionCount",
        "controlImageSha256",
        "controlImagePreprocessedSha256",
        "controlImagePreprocess",
        "controlImageSourceWidth",
        "controlImageSourceHeight",
        "controlImageSourceChannels",
        "controlImageOrientedWidth",
        "controlImageOrientedHeight",
        "controlImageExifOrientation",
        "controlImageTensorWidth",
        "controlImageTensorHeight",
        "controlImageTensorChannels",
        "controlImageTensorLayout",
        "controlImageEdgePixelCount",
        "controlImageTensorChecksum",
        "controlStrength",
        "controlStrengthApplied",
        "controlNetExecutionCount",
        "controlNetResidualTensorCount",
        "controlNetResidualWriteCount",
        "controlNetResidualUnetReuseCount",
        "controlNetConditioningBranch",
        "controlNetInputConsumed",
        "controlNetInputBufferSha256",
        "controlNetScaledResidualChecksum",
        "controlNetGraph",
        "conditioningArtifactSha256"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
    listOf(
        "initNoiseSigma",
        "scaleModelInput",
        "textEncoderExecutionCount",
        "vaeExecutionCount",
        "effectiveVaeHostScale",
        "vaeTileCount",
        "vaeTiled",
        "outputSha256"
    ).forEach { field ->
        if (nativeResult.has(field) && !nativeResult.isNull(field)) {
            metadata.put(field, nativeResult.get(field))
        }
    }
}.apply {
    put("nativeRequestId", nativeRequestId)
    .put("backend", nativeResult.optString("backend"))
    .put("executionStage", nativeResult.optString("executionStage"))
    .put("npuActive", nativeResult.optBoolean("npuActive", false))
    .put("qnnGraphExecution", nativeResult.optBoolean("qnnGraphExecution", false))
    .put("nativeExecution", nativeResult.optBoolean("nativeExecution", false))
    .put("fallback", nativeResult.optBoolean("fallback", true))
    .put("nativeGenerationSequence", nativeResult.optLong("nativeGenerationSequence"))
    .put("nativeStartedAtMonotonicMs", nativeResult.optLong("nativeStartedAtMonotonicMs"))
    .put("nativeStageMask", nativeResult.optLong("nativeStageMask"))
    .put("nativeDetailStageMask", nativeResult.optLong("nativeDetailStageMask"))
    .put("runtimeSessionMode", nativeResult.optString("runtimeSessionMode"))
    .put("conditioningFormat", nativeResult.optString("conditioningFormat"))
    .put("archiveContextHtpArch", nativeResult.optInt("archiveContextHtpArch"))
    .put("transportHtpArch", nativeResult.optInt("transportHtpArch"))
    .put("unetWorkerPid", nativeResult.optInt("unetWorkerPid"))
    .put("unetRuntimeProfile", nativeResult.optString("unetRuntimeProfile"))
    .put("unetProcessDeathConfirmed", nativeResult.optBoolean("unetProcessDeathConfirmed", false))
    .put("unetGraph", nativeResult.optString("unetGraph"))
    .put("vaeWorkerPid", nativeResult.optInt("vaeWorkerPid"))
    .put("vaeRuntimeProfile", nativeResult.optString("vaeRuntimeProfile"))
    .put("vaeTransportHtpArch", nativeResult.optInt("vaeTransportHtpArch"))
    .put("vaeProcessDeathConfirmed", nativeResult.optBoolean("vaeProcessDeathConfirmed", false))
    .put("vaeGraph", nativeResult.optString("vaeGraph"))
    .put("steps", nativeResult.optInt("steps"))
    .put("width", nativeResult.optInt("width"))
    .put("height", nativeResult.optInt("height"))
    .put("elapsedMs", nativeResult.optLong("elapsedMs"))
    .put("unetContextLoadMs", nativeResult.optLong("unetContextLoadMs"))
    .put("unetExecuteMsTotal", nativeResult.optLong("unetExecuteMsTotal"))
    .put("unetExecuteMsAvg", nativeResult.optLong("unetExecuteMsAvg"))
    .put("vaeContextLoadMs", nativeResult.optLong("vaeContextLoadMs"))
    .put("vaeExecuteMs", nativeResult.optLong("vaeExecuteMs"))
    .put("textEncoderGraph", nativeResult.optString("textEncoderGraph"))
    .put("textEncoderContextLoadMs", nativeResult.optLong("textEncoderContextLoadMs"))
    .put("textEncoderExecuteMsTotal", nativeResult.optLong("textEncoderExecuteMsTotal"))
    .put("textEncoderEmbeddingWidth", nativeResult.optLong("textEncoderEmbeddingWidth"))
    .put("latentSha256", nativeResult.optString("latentSha256"))
    .put("outputBytes", outputBytes)
    .also { metadata ->
        nativeResult.optJSONObject("runtime")?.let { runtime ->
            metadata
                .put("selectedHtpArch", runtime.optInt("htpArchVersion"))
                .put("runtimeLoadable", runtime.optBoolean("loadable", false))
                .put("qnnInterfacePresent", runtime.optBoolean("qnnInterfacePresent", false))
            runtime.optJSONObject("compile")?.let { compile ->
                metadata
                    .put("sdkHeadersPresent", compile.optBoolean("sdkHeadersPresent", false))
                    .put("typedGraphBindingsCompiled", compile.optBoolean("typedGraphBindingsCompiled", false))
            }
        }
    }
}

internal data class QnnImageGenerationContract(
    val width: Int,
    val height: Int,
    val steps: Int,
    val threads: Int,
    val seed: Int,
    val cfgScale: Double,
    val sampleMethod: String,
    val backendMode: String,
    val tokenEmbeddingMode: String,
    val memoryMode: Int,
    val useCfg: Boolean
) {
    val auditJson: JSONObject
        get() = JSONObject()
            .put("width", width)
            .put("height", height)
            .put("steps", steps)
            .put("threads", threads)
            .put("seed", seed)
            .put("cfgScale", cfgScale)
            .put("sampleMethod", sampleMethod)
            .put("backendMode", backendMode)
            .put("tokenEmbeddingMode", tokenEmbeddingMode)
            .put("memoryMode", memoryMode)
            .put("useCfg", useCfg)
}

internal fun resolveQnnImageGenerationContract(
    resolution: ImageExecutionProfileResolution,
    defaultThreads: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val resolved = resolution.layers.resolved
    val width = resolved.width
    val height = resolved.height
    require(width > 0 && height > 0 && width % 8 == 0 && height % 8 == 0) {
        "Resolved QNN image dimensions must be positive multiples of 8."
    }
    val steps = resolved.steps
    require(steps in resolution.profile.scheduler.minSteps..resolution.profile.scheduler.maxSteps) {
        "Resolved QNN steps are outside the execution profile bounds."
    }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = resolved.cfgScale
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = resolved.seed.toInt().also {
            require(resolved.seed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "QNN seed must fit the native 32-bit RNG contract."
            }
        },
        cfgScale = cfgScale,
        sampleMethod = imageSchedulerProductName(resolved.scheduler),
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = resolved.useCfg
    )
}

internal fun resolveQnnImageGenerationContract(
    family: LocalImageModelFamily,
    defaultWidth: Int,
    defaultHeight: Int,
    defaultThreads: Int,
    fallbackSeed: Int,
    options: LocalImageGenerationOptions
): QnnImageGenerationContract {
    require(options.distilledGuidance == null) {
        "QNN image graphs do not expose a distilled-guidance input."
    }
    require(options.flowShift == null) {
        "QNN image schedulers do not expose a writable flow-shift control."
    }
    val width = options.width ?: defaultWidth
    val height = options.height ?: defaultHeight
    require(width == defaultWidth && height == defaultHeight) {
        "QNN context 固定为 ${defaultWidth}x${defaultHeight}，不能执行 ${width}x${height}。"
    }
    val defaultSteps = if (family == LocalImageModelFamily.SDXL) 30 else defaultStepsFor(family).coerceIn(1, 100)
    val steps = options.steps ?: defaultSteps
    require(steps in 1..100) { "QNN steps 必须在 1..100。" }
    val threads = options.threads ?: defaultThreads
    require(threads in 1..16) { "QNN prompt encoder threads 必须在 1..16。" }
    val cfgScale = options.cfgScale ?: defaultCfgFor(family)
    require(cfgScale.isFinite() && cfgScale in 0.0..30.0) { "QNN CFG 必须是 0..30 的有限数值。" }
    val sampleMethod = options.sampleMethod?.trim()?.lowercase().orEmpty().ifBlank { "pndm" }
    imageSchedulerAlgorithmFromProductName(sampleMethod)
    val backendMode = options.backendMode?.trim()?.lowercase().orEmpty().ifBlank { "cpu" }
    require(backendMode == "cpu" || backendMode == "opencl") {
        "QNN prompt encoder backend 只支持 cpu 或 opencl。"
    }
    val tokenEmbeddingMode = options.tokenEmbeddingMode?.trim()?.lowercase().orEmpty().ifBlank { "auto" }
    require(tokenEmbeddingMode in setOf("auto", "module", "direct")) {
        "QNN token embedding mode 只支持 auto、module 或 direct。"
    }
    val memoryMode = options.memoryMode ?: 0
    require(memoryMode in 0..2) { "QNN memory mode 必须在 0..2。" }
    return QnnImageGenerationContract(
        width = width,
        height = height,
        steps = steps,
        threads = threads,
        seed = options.seed ?: fallbackSeed,
        cfgScale = cfgScale,
        sampleMethod = sampleMethod,
        backendMode = backendMode,
        tokenEmbeddingMode = tokenEmbeddingMode,
        memoryMode = memoryMode,
        useCfg = options.useCfg ?: true
    )
}

class LocalImageModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    private val managedDir: File by lazy {
        (appContext.getExternalFilesDir("image_models") ?: File(appContext.filesDir, "image_models")).also { it.mkdirs() }
    }

    fun loadModels(): List<LocalImageModelRecord> {
        val raw = prefs.getString(KEY_MODELS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                LocalImageModelRecord.fromJson(array.getJSONObject(index))
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun saveModels(models: List<LocalImageModelRecord>) {
        val array = JSONArray()
        models.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_MODELS, array.toString()).apply()
    }

    fun updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> {
        val now = System.currentTimeMillis()
        val models = loadModels().map { existing ->
            if (existing.id == record.id) record.copy(updatedAt = now) else existing
        }
        saveModels(models)
        return models
    }

    fun importFromUri(uri: Uri): LocalImageModelRecord {
        val fileName = queryDisplayName(uri) ?: "image-model.task"
        val extension = fileName.substringAfterLast('.', "").lowercase()
        require(extension in SUPPORTED_EXTENSIONS) {
            "请选择 .gguf、.safetensors、.ckpt、.pth、.pt、.onnx，或包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 .zip 图像生成引擎包。"
        }
        if (extension == "zip") {
            return importBundleFromUri(uri, fileName)
        }
        managedDir.mkdirs()
        val target = uniqueTarget(fileName)
        inputStreamFor(uri).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val record = LocalImageModelRecord(
            displayName = fileName.substringBeforeLast('.', fileName),
            path = target.absolutePath,
            fileName = target.name,
            sizeBytes = target.length(),
            sha256 = sha256(target),
            runtime = LocalImageRuntime.infer(fileName),
            family = LocalImageModelFamily.infer(fileName),
            imageSize = defaultImageSizeFor(fileName)
        )
        saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun registerDownloadedModel(
        file: File,
        remote: RemoteModelFile
    ): LocalImageModelRecord {
        require(file.exists()) { "下载完成的图像模型文件不存在：${file.absolutePath}" }
        if (file.extension.equals("zip", ignoreCase = true)) {
            return importBundleFromUri(Uri.fromFile(file), file.name).also {
                runCatching { file.delete() }
            }
        }
        val record = LocalImageModelRecord(
            displayName = file.name.substringBeforeLast('.', file.name),
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = sha256(file),
            runtime = LocalImageRuntime.infer(file.name),
            family = LocalImageModelFamily.infer("${remote.repoId}/${remote.path}/${file.name}"),
            imageSize = defaultImageSizeFor("${remote.repoId}/${file.name}"),
            source = "${remote.provider.name.lowercase()}:${remote.repoId}",
            updatedAt = System.currentTimeMillis()
        )
        saveModels(listOf(record) + loadModels().filterNot { it.sha256.equals(record.sha256, ignoreCase = true) })
        if (loadSelectedModelId() == null && record.isReadyForLocalImageGeneration()) saveSelectedModelId(record.id)
        return record
    }

    fun managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        return File(managedDir, "bundle-$safeName").also { it.mkdirs() }
    }

    fun managedBundleFileFor(bundleDir: File, fileName: String): File {
        val safeName = fileName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(bundleDir, safeName.ifBlank { "component-${System.currentTimeMillis()}" })
    }

    fun registerDownloadedBundle(
        displayName: String,
        bundleDir: File,
        primaryFile: File,
        primaryRemote: RemoteModelFile,
        componentCount: Int,
        runtimeOverride: LocalImageRuntime? = null,
        imageSizeOverride: String? = null,
        primarySha256: String? = null
    ): LocalImageModelRecord {
        require(bundleDir.isDirectory) { "本地生图引擎包目录不存在：${bundleDir.absolutePath}" }
        require(primaryFile.exists()) { "Local image engine bundle is missing a diffusion model: ${primaryFile.name}" }
        if (primaryFile.extension.equals("zip", ignoreCase = true)) {
            extractImageBundleZipIntoDirectory(primaryFile, bundleDir)
            runCatching { primaryFile.delete() }
        }
        prepareMnnDiffusionTokenizerIfPossible(bundleDir)
        val manifest = localImageBundleManifestFromRoot(bundleDir)
        val resolvedPrimary = manifest?.primaryFile ?: findPrimaryImageModel(bundleDir)
            ?: error("Local image engine bundle is missing a diffusion model.")
        val familyHint = "$displayName/${primaryRemote.repoId}/${primaryRemote.path}/${resolvedPrimary.name}"
        val record = LocalImageModelRecord(
            displayName = displayName,
            path = resolvedPrimary.absolutePath,
            fileName = resolvedPrimary.name,
            sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            sha256 = primarySha256
                ?.trim()
                ?.lowercase()
                ?.also { digest ->
                    require(digest.matches(Regex("^[0-9a-f]{64}$"))) {
                        "Downloaded image model SHA-256 is invalid."
                    }
                }
                ?: sha256(resolvedPrimary),
            runtime = runtimeOverride ?: manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleDir, resolvedPrimary),
            family = manifest?.family ?: LocalImageModelFamily.infer(familyHint),
            imageSize = imageSizeOverride ?: manifest?.imageSize ?: defaultImageSizeFor(familyHint),
            source = "${primaryRemote.provider.name.lowercase()}:${primaryRemote.repoId}",
            bundleRoot = bundleDir.absolutePath,
            componentCount = bundleDir.walkTopDown().count { it.isFile }
                .coerceAtLeast(componentCount)
                .coerceAtLeast(manifest?.componentCount ?: 0)
                .coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        record.localImageStructuralReadinessMessage()?.let { readiness ->
            error("图像生成引擎包不完整：$readiness")
        }
        saveModels(
            listOf(record) + loadModels().filterNot {
                it.bundleRoot == record.bundleRoot ||
                    (it.sha256.equals(record.sha256, ignoreCase = true) && it.imageSize == record.imageSize)
            }
        )
        if (record.isReadyForLocalImageGeneration()) {
            saveSelectedModelId(record.id)
            saveSelectedBackend(ImageBackend.LOCAL)
        }
        return record
    }

    fun managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun deleteModel(id: String): Boolean {
        val models = loadModels()
        val target = models.firstOrNull { it.id == id } ?: return false
        val targetFile = target.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?: File(target.path)
        val deleted = runCatching {
            if (!targetFile.exists()) {
                true
            } else if (targetFile.isDirectory) {
                targetFile.deleteRecursively() && !targetFile.exists()
            } else {
                targetFile.delete() && !targetFile.exists()
            }
        }.getOrDefault(false)
        if (!deleted || targetFile.exists()) return false
        val remaining = models.filterNot { it.id == id }
        saveModels(remaining)
        if (loadSelectedModelId() == id) saveSelectedModelId(remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id)
        return true
    }

    fun loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_MODEL_ID, modelId.orEmpty()).apply()
    }

    fun loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

    fun saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    private fun importBundleFromUri(uri: Uri, fileName: String): LocalImageModelRecord {
        val bundleDir = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
        bundleDir.mkdirs()
        val extracted = mutableListOf<File>()
        try {
            inputStreamFor(uri).use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(bundleDir, entry.name.replace('\\', '/'))
                        val canonicalRoot = bundleDir.canonicalFile
                        val canonicalTarget = target.canonicalFile
                        require(canonicalTarget.path.startsWith(canonicalRoot.path)) { "图像生成引擎包包含不安全路径。" }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                        if (target.extension.lowercase() in MODEL_FILE_EXTENSIONS) {
                            extracted += target
                        }
                        zip.closeEntry()
                    }
                }
            }
            prepareMnnDiffusionTokenizerIfPossible(bundleDir)
            val manifest = localImageBundleManifestFromRoot(bundleDir)
            val primary = manifest?.primaryFile ?: extracted.sortedWith(
                compareByDescending<File> { it.name.isPrimaryImageModelName() }
                    .thenByDescending { it.length() }
            ).firstOrNull() ?: run {
                bundleDir.deleteRecursively()
                error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX / MNN 模型文件。")
            }
            val family = manifest?.family
                ?: LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val displayName = manifest?.displayName ?: fileName.substringBeforeLast('.', fileName)
            val record = LocalImageModelRecord(
                displayName = displayName,
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = bundleDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                sha256 = sha256(primary),
                runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleDir, primary),
                family = family,
                imageSize = manifest?.imageSize
                    ?: defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = bundleDir.absolutePath,
                componentCount = bundleDir.walkTopDown().count { it.isFile }
                    .coerceAtLeast(manifest?.componentCount ?: 0)
                    .coerceAtLeast(1)
            )
            record.localImageStructuralReadinessMessage()?.let { readiness ->
                bundleDir.deleteRecursively()
                error("图像生成引擎包不完整：$readiness")
            }
            saveModels(listOf(record) + loadModels().filterNot { it.id == record.id })
            if (
                loadSelectedModelId() == null &&
                record.isReadyForLocalImageGeneration()
            ) {
                saveSelectedModelId(record.id)
            }
            return record
        } catch (error: Throwable) {
            if (bundleDir.exists()) runCatching { bundleDir.deleteRecursively() }
            throw error
        }
    }

    private fun inputStreamFor(uri: Uri): InputStream {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: error("无法读取图像生成引擎文件。")
            return File(path).inputStream()
        }
        return requireNotNull(appContext.contentResolver.openInputStream(uri)) {
            "无法读取图像生成引擎文件。"
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return appContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun uniqueTarget(fileName: String): File {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseName = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            val next = if (extension.isBlank()) "$baseName-$index" else "$baseName-$index.$extension"
            target = File(managedDir, next)
            index += 1
        }
        return target
    }

    private fun uniqueBundleDir(name: String): File {
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-bundle" }
        var target = File(managedDir, safeName)
        var index = 1
        while (target.exists()) {
            target = File(managedDir, "$safeName-$index")
            index += 1
        }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_MODELS = "local_image_models_json"
        private const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
        private const val KEY_SELECTED_BACKEND = "selected_image_backend"
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")
        private val SUPPORTED_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "mnn", "zip")
    }
}

internal fun extractImageBundleZipIntoDirectory(zipFile: File, bundleDir: File) {
    val canonicalRoot = bundleDir.canonicalFile
    val canonicalZip = zipFile.canonicalFile
    val mcaManifest = File(canonicalRoot, "manifest.json").canonicalFile
    zipFile.inputStream().use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val target = File(canonicalRoot, entry.name.replace('\\', '/')).canonicalFile
                require(target.toPath().startsWith(canonicalRoot.toPath()) && target != canonicalRoot) {
                    "Image engine bundle contains an unsafe path."
                }
                if (target == canonicalZip || (target == mcaManifest && mcaManifest.isMcaImageBundleManifest())) {
                    zip.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> zip.copyTo(output) }
                zip.closeEntry()
            }
        }
    }
}

internal fun findPrimaryImageModel(root: File): File? =
    root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .sortedWith(
            compareByDescending<File> { it.name.isPrimaryImageModelName() }
                .thenByDescending { it.length() }
        )
        .firstOrNull()

private fun File.isMcaImageBundleManifest(): Boolean =
    isFile && runCatching {
        JSONObject(readText(Charsets.UTF_8)).optString("schema") == "mca.image_engine.bundle.v1"
    }.getOrDefault(false)

internal fun prepareMnnDiffusionTokenizerIfPossible(root: File): Boolean {
    if (!root.isDirectory) return false
    val rootCanonical = root.canonicalFile
    val rootMtok = File(root, "tokenizer.mtok")
    val existingMtok = root.findDescendantFile("tokenizer.mtok")
    if (existingMtok != null) {
        if (existingMtok.parentFile?.canonicalFile != rootCanonical || !rootMtok.isFile) {
            existingMtok.copyTo(rootMtok, overwrite = true)
        }
        return true
    }
    val existingTxt = root.findDescendantFile("tokenizer.txt")
    if (existingTxt != null) {
        val rootTxt = File(root, "tokenizer.txt")
        val tokenizerTxt = if (existingTxt.parentFile?.canonicalFile != rootCanonical) {
            existingTxt.copyTo(rootTxt, overwrite = true)
            rootTxt
        } else {
            existingTxt
        }
        tokenizerTxt.copyTo(rootMtok, overwrite = true)
        return true
    }

    val vocabFile = root.findDescendantFile("vocab.json") ?: return false
    val mergesFile = root.findDescendantFile("merges.txt") ?: return false
    val vocabJson = JSONObject(vocabFile.readText(Charsets.UTF_8))
    val idToToken = mutableMapOf<Int, String>()
    var maxId = -1
    val keys = vocabJson.keys()
    while (keys.hasNext()) {
        val token = keys.next()
        val rawId = vocabJson.opt(token)
        val id = when (rawId) {
            is Number -> rawId.toInt()
            is String -> rawId.toIntOrNull()
            else -> null
        } ?: continue
        if (id >= 0) {
            idToToken[id] = token
            if (id > maxId) maxId = id
        }
    }
    require(maxId >= 0) { "MNN-Diffusion tokenizer vocab.json has no valid token ids." }
    val decoder = (0..maxId).map { id ->
        idToToken[id] ?: error("MNN-Diffusion tokenizer vocab.json is missing token id $id.")
    }
    val merges = mergesFile.readLines(Charsets.UTF_8)
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
    require(merges.isNotEmpty()) { "MNN-Diffusion tokenizer merges.txt has no merge rules." }

    val target = File(root, "tokenizer.txt")
    val temp = File(root, "tokenizer.txt.tmp")
    temp.bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.appendLine("430 3")
        writer.appendLine("0 0 0")
        writer.appendLine()
        writer.appendLine("${decoder.size} ${merges.size}")
        decoder.forEach { writer.appendLine(it) }
        merges.forEach { writer.appendLine(it) }
    }
    if (target.exists()) target.delete()
    require(temp.renameTo(target)) { "Failed to write MNN-Diffusion tokenizer.txt." }
    target.copyTo(rootMtok, overwrite = true)
    return true
}

internal fun qnnRuntimeSearchDirectories(
    bundleRoot: File?,
    existingDirectories: List<String>
): List<String> {
    val bundleRuntime = bundleRoot?.canonicalQnnRuntimeDirectoryOrNull()?.absolutePath
    return (listOfNotNull(bundleRuntime) + existingDirectories)
        .filter { it.isNotBlank() }
        .distinctBy { directory ->
            runCatching { File(directory).canonicalPath }
                .getOrElse { File(directory).absolutePath }
        }
}

/**
 * Resolves only linker-loadable QNN directories for an image bundle. A
 * complete runtime found beside a downloaded bundle is staged to private
 * code-cache storage first; it is never returned from external storage.
 */
internal data class QnnRuntimeDirectoryResolution(
    val directories: List<String>,
    val stagedRuntime: QnnImageStagedRuntime? = null,
    val stagingError: String? = null
)

internal fun qnnRuntimeDirectoryResolutionFor(
    context: Context,
    bundleRoot: File?
): QnnRuntimeDirectoryResolution {
    val appContext = context.applicationContext
    val requiredRuntimeProfile = bundleRoot
        ?.let { root -> runCatching { localImageBundleManifestFromRoot(root)?.requiredRuntimeProfile }.getOrNull() }
    val staged = stageQnnImageBundleRuntime(appContext, bundleRoot, requiredRuntimeProfile)
    if (staged.failed) {
        // Do not quietly fall back to an APK/GenieX host pair here. Mixing it
        // with the bundle's intended Skel/Stub profile can poison HTP state
        // and produce a misleading incompatible-context result.
        return QnnRuntimeDirectoryResolution(
            directories = emptyList(),
            stagingError = staged.error
        )
    }
    val fallback = listOf(
        File(appContext.filesDir, "qnnlibs").absolutePath,
        File(appContext.filesDir, "runtime_libs").absolutePath,
        appContext.applicationInfo.nativeLibraryDir,
        "/vendor/lib64",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/product/lib64"
    )
    val detectedRuntime = runCatching {
        // This runs after staging, so DeviceProfileReader can only see the
        // coherent private profile rather than the external bundle path.
        DeviceProfileReader(appContext).read().accelerationProfile.qnnRuntime
    }.getOrNull()
    val selectedHostDirectory = detectedRuntime?.qnnSystemLibraryPath
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    return QnnRuntimeDirectoryResolution(
        directories = qnnRuntimeSearchDirectories(
            bundleRoot = null,
            existingDirectories = listOfNotNull(staged.runtime?.directory?.absolutePath, selectedHostDirectory) +
                fallback + detectedRuntime?.searchDirectories.orEmpty()
        ),
        stagedRuntime = staged.runtime
    )
}

internal fun qnnRuntimeDirectoriesFor(context: Context, bundleRoot: File?): List<String> =
    qnnRuntimeDirectoryResolutionFor(context, bundleRoot).directories

/** Puts the device-selected coherent QNN host profile ahead of generic paths. */
internal fun qnnRuntimeDirectoriesFor(
    runtimeStatus: com.muyuchat.core.deviceprofile.QnnRuntimeStatus
): List<String> {
    val selectedHostDirectory = runtimeStatus.qnnSystemLibraryPath
        ?.let(::File)
        ?.parentFile
        ?.absolutePath
    return qnnRuntimeSearchDirectories(
        bundleRoot = null,
        existingDirectories = listOfNotNull(selectedHostDirectory) + runtimeStatus.searchDirectories
    )
}

private fun File.canonicalQnnRuntimeDirectoryOrNull(): File? {
    val root = runCatching { canonicalFile }.getOrNull()?.takeIf { it.isDirectory } ?: return null
    val runtime = runCatching { File(root, "runtime").canonicalFile }.getOrNull() ?: return null
    return runtime.takeIf {
        it.isDirectory && it.path.startsWith(root.path + File.separator)
    }
}

internal fun qnnImageVerificationStampFor(context: Context, bundleRoot: File): String {
    val appContext = context.applicationContext
    val bundle = QnnImageBundleIdentity.fromDirectory(bundleRoot)
    require(bundle.status == QnnImageBundleIdentityStatus.AVAILABLE) {
        "QNN image bundle is unavailable for verification."
    }
    return QnnImageVerificationStamp.create(
        device = currentQnnImageDeviceIdentity(appContext),
        runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
        bundleDirectory = bundleRoot
    ).toJsonString()
}

internal fun LocalImageModelRecord.hasCurrentQnnVerificationStamp(
    context: Context,
    bundleRoot: File
): Boolean {
    if (qnnVerificationStamp.isBlank()) return false
    val appContext = context.applicationContext
    return runCatching {
        QnnImageVerificationStamp.fromJson(qnnVerificationStamp).matchesCurrent(
            device = currentQnnImageDeviceIdentity(appContext),
            runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
            bundleDirectory = bundleRoot
        )
    }.getOrDefault(false)
}

private fun currentQnnImageDeviceIdentity(context: Context): QnnImageDeviceIdentity {
    val profile = DeviceProfileReader(context).read()
    return QnnImageDeviceIdentity(
        soc = profile.accelerationProfile.chipsetCode.ifBlank { profile.socModel.ifBlank { "unknown" } },
        abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },
        buildFingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "unknown" }
    )
}

private fun currentQnnImageRuntimeIdentity(
    context: Context,
    bundleRoot: File
): QnnImageRuntimeIdentity {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    val appIdentity = buildString {
        append(context.packageName)
        append('/')
        append(packageInfo.versionName.orEmpty().ifBlank { "unknown" })
        append('#')
        append(versionCode)
    }
    val nativeRuntime = runCatching {
        val runtimeDirsJson = JSONArray(qnnRuntimeDirectoriesFor(context, bundleRoot)).toString()
        qnnRuntimeIdentityJson(NativeQnnBridge().inspectRuntime(runtimeDirsJson))
    }.getOrDefault("unavailable")
    return QnnImageRuntimeIdentity(
        app = appIdentity,
        nativeRuntime = nativeRuntime
    )
}

internal fun qnnRuntimeIdentityJson(runtimeProbeJson: String): String {
    val probe = JSONObject(runtimeProbeJson)
    val identity = JSONObject()
        .put("schema", "mca.qnn.runtime.identity.v1")
        .put("ready", probe.optBoolean("ready", false))
        .put("loadable", probe.optBoolean("loadable", false))
        .put("qnnInterfacePresent", probe.optBoolean("qnnInterfacePresent", false))
        .put("qnnSystemInterfacePresent", probe.optBoolean("qnnSystemInterfacePresent", false))
        .put("compile", probe.optJSONObject("compile") ?: JSONObject())
    val libraries = JSONArray()
    QNN_RUNTIME_LIBRARY_KEYS.forEach { (role, key) ->
        libraries.put(qnnRuntimeFileIdentity(role, probe.optString(key)))
    }
    return identity.put("selectedLibraries", libraries).toString()
}

private fun qnnRuntimeFileIdentity(role: String, rawPath: String): JSONObject {
    val identity = JSONObject().put("role", role)
    if (rawPath.isBlank()) return identity.put("status", "not_selected")

    val file = File(rawPath)
    val canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    identity.put("path", canonicalPath)
    if (!file.exists()) return identity.put("status", "missing")
    if (!file.isFile) return identity.put("status", "not_file")

    identity
        .put("length", file.length())
        .put("lastModified", file.lastModified())
    val digest = runCatching { file.sha256Contents() }.getOrNull()
        ?: return identity.put("status", "unreadable")
    return identity
        .put("status", "available")
        .put("sha256", digest)
}

private fun File.sha256Contents(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}

private val QNN_RUNTIME_LIBRARY_KEYS = listOf(
    "qnn_system" to "qnnSystemLibraryPath",
    "qnn_htp" to "qnnHtpLibraryPath",
    "htp_skel" to "htpSkelLibraryPath",
    "htp_stub" to "htpStubLibraryPath",
    "cdsp_rpc" to "cdspRpcLibraryPath"
)

fun LocalImageModelRecord.localImageReadinessMessage(): String? {
    // Persisted verification is diagnostic evidence from an earlier attempt,
    // not a certificate.  A complete bundle must always be allowed to reach
    // the real native load/graph/generation path, including UNKNOWN and FAILED
    // records.  Concrete package/format/runtime structure remains the only
    // pre-execution admission check.
    return localImageStructuralReadinessMessage()
}

/**
 * Returns true only when FAILED is backed by a result recorded for the current
 * model record.  Legacy/stale FAILED bits must not be presented as a current
 * native failure and never participate in admission.
 */
internal fun LocalImageModelRecord.hasCurrentLocalImageExecutionFailure(): Boolean =
    verificationStatus == LocalImageVerificationStatus.FAILED &&
        verificationMessage.isNotBlank() &&
        verifiedAt > 0L &&
        localImageStructuralReadinessMessage() == null

/** Advisory text only; callers must never use this value to block selection or execution. */
fun LocalImageModelRecord.localImageVerificationDiagnosticMessage(): String? {
    if (localImageStructuralReadinessMessage() != null) return null
    val runtimeName = when (runtime) {
        LocalImageRuntime.MNN_DIFFUSION -> "MNN-Diffusion"
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
        else -> return null
    }
    return when (verificationStatus) {
        LocalImageVerificationStatus.UNKNOWN ->
            "$runtimeName 尚未记录真实运行结果，可直接尝试；本次 native 执行结果将作为诊断依据。"
        LocalImageVerificationStatus.FAILED -> if (hasCurrentLocalImageExecutionFailure()) {
            "$runtimeName 上次真实执行失败：${verificationMessage.trim()}；仍可直接重试，以本次执行结果为准。"
        } else {
            "$runtimeName 的历史失败状态没有当前执行证据，不会阻止使用；可直接重新尝试。"
        }
        LocalImageVerificationStatus.PASSED -> null
        LocalImageVerificationStatus.MNN_SMOKE_PASSED ->
            if (runtime == LocalImageRuntime.MNN_DIFFUSION) null else "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
        LocalImageVerificationStatus.QNN_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
        LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED ->
            "$runtimeName 可直接尝试，历史验证类型与当前引擎不匹配。"
    }
}

fun LocalImageModelRecord.localImageStructuralReadinessMessage(): String? {
    if (!configured) return "本地图像生成模型文件不存在，请重新导入。"
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return mnnDiffusionReadinessMessage()
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return qnnImageBundleReadinessMessage()
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return "当前仅支持 stable-diffusion.cpp 或 MNN-Diffusion 本地图像引擎。"
    }
    val componentSelection = runCatching { resolveStableDiffusionComponentSelection(this) }
        .getOrElse { error ->
            return error.message ?: "stable-diffusion.cpp image bundle component selection failed."
        }
    if (componentSelection.mode == STABLE_DIFFUSION_COMPONENT_MODE_MANIFEST) return null
    if (!family.requiresCompanionComponents()) return null
    val requirement = family.requiredCompanionComponentHint()
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: return "缺少组件包：${displayName} 只有 diffusion 主模型，还需要 $requirement。请在模型管理 > 文件中导入包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 zip 引擎包。"
    val primary = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val files = root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .filterNot { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == primary }
        .toList()
    val missing = buildList {
        if (files.none { it.isVaeComponentFile() }) add("VAE")
        if (files.none { it.isTextEncoderComponentFile() }) add("文本编码器/LLM")
    }
    return if (missing.isEmpty()) {
        null
    } else {
        "缺少组件：${missing.joinToString("、")}。${displayName} 需要 $requirement，不能只用单个 GGUF 生成图片。"
    }
}

fun LocalImageModelRecord.isReadyForLocalImageGeneration(): Boolean =
    localImageReadinessMessage() == null

/** Automatic selection is structural only; verification state is deliberately ignored. */
internal fun selectStructurallyReadyLocalImageModelId(
    models: List<LocalImageModelRecord>,
    preferredId: String?
): String? = preferredId
    ?.takeIf { id ->
        models.any { model -> model.id == id && model.localImageStructuralReadinessMessage() == null }
    }
    ?: models.firstOrNull { model -> model.localImageStructuralReadinessMessage() == null }?.id

fun LocalImageModelRecord.localImageReadinessLabel(): String {
    val structuralReady = localImageStructuralReadinessMessage() == null
    if (!structuralReady) return "缺少组件"
    if (runtime == LocalImageRuntime.MNN_DIFFUSION) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.UNKNOWN -> "未验证·可尝试"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.PASSED -> "可用"
            LocalImageVerificationStatus.UNKNOWN -> "未验证·可尝试"
            LocalImageVerificationStatus.FAILED ->
                if (hasCurrentLocalImageExecutionFailure()) "上次失败·可重试" else "可直接尝试"
            LocalImageVerificationStatus.MNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED,
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "可直接尝试"
        }
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        return when (verificationStatus) {
            LocalImageVerificationStatus.MNN_SMOKE_PASSED -> "MNN smoke"
            LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED -> "NPU 1-step smoke"
            LocalImageVerificationStatus.QNN_PIPELINE_PROBE_PASSED -> "NPU probe"
            LocalImageVerificationStatus.QNN_SMOKE_PASSED -> "NPU smoke"
            LocalImageVerificationStatus.UNKNOWN -> "NPU 待校验"
            LocalImageVerificationStatus.FAILED -> "NPU 校验失败"
            LocalImageVerificationStatus.PASSED -> "可用"
        }
    }
    return "可用"
}

private fun LocalImageModelRecord.qnnImageBundleReadinessMessage(): String? {
    val root = bundleRoot?.let(::File)?.takeIf { it.isDirectory }
        ?: File(path).parentFile?.takeIf { it.isDirectory }
        ?: return "QNN image engine requires a complete QNN bundle directory."
    val manifest = runCatching { localImageBundleManifestFromRoot(root) }.getOrNull()
    qnnRequiredBundleRuntimeReadinessMessage(root, manifest?.requiredRuntimeProfile)?.let { return it }
    val files = root.walkTopDown().filter { it.isFile }.toList()
    val names = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasAny(vararg tokens: String): Boolean = names.any { name -> tokens.any { it in name } }
    val requiresControlNet = manifest?.task == "CONTROL_IMAGE" ||
        manifest?.id.orEmpty().contains("controlnet", ignoreCase = true)
    val missing = buildList {
        if (!hasAny("qnn", "context", "unet", "diffusion", "transformer")) add("QNN diffusion/context")
        if (!hasAny("vae", "decoder", "ae")) add("VAE/AE decoder")
        if (!hasAny("text_encoder", "clip", "t5", "tokenizer", "qwen", "llm")) add("text encoder/tokenizer")
        if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
            add("controlnet.bin")
        }
    }
    return if (missing.isEmpty()) null else "QNN image bundle is incomplete: ${missing.joinToString(", ")}."
}

private fun LocalImageModelRecord.mnnDiffusionReadinessMessage(): String? {
    val effectiveFamily = resolvedMnnFamily()
    return LocalImageBundleContract.inspectMnnBundle(
        bundleRoot = bundleRoot?.let(::File),
        primaryFile = File(path),
        family = effectiveFamily
    ).readinessMessage(effectiveFamily)
}

private fun LocalImageModelRecord.resolvedMnnFamily(): LocalImageModelFamily {
    val primaryFile = File(path)
    val manifest = sequenceOf(
        bundleRoot?.takeIf { it.isNotBlank() }?.let(::File),
        primaryFile.parentFile
    )
        .filterNotNull()
        .distinctBy { root ->
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }
        .mapNotNull { root ->
            runCatching { localImageBundleManifestFromRoot(root) }.getOrNull()
        }
        .firstOrNull()
    return mnnVerificationRoute(family, manifest).family
}

private fun inferLocalImageRuntimeForBundle(root: File, primary: File): LocalImageRuntime =
    when {
        root.walkTopDown().any {
            it.isFile && listOf("qnn", "qairt", "htp").any { token -> token in it.invariantSeparatorsPath.lowercase() }
        } -> LocalImageRuntime.QNN_HTP
        primary.extension.equals("mnn", ignoreCase = true) -> LocalImageRuntime.MNN_DIFFUSION
        root.walkTopDown().any { it.isFile && it.extension.equals("mnn", ignoreCase = true) } -> LocalImageRuntime.MNN_DIFFUSION
        else -> LocalImageRuntime.infer(primary.name)
    }

internal fun localImageBundleManifestFromRoot(root: File): LocalImageBundleManifest? {
    if (!root.isDirectory) return null
    val manifestFile = root.findDescendantFile("manifest.json") ?: return null
    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    val runtime = manifest.optString("runtime").takeIf { it.isNotBlank() }?.let(LocalImageRuntime::from)
    val family = manifest.optString("family").takeIf { it.isNotBlank() }?.let(LocalImageModelFamily::from)
    val imageSize = manifest.manifestImageSize()
    val smoke = manifest.optJSONObject("smoke") ?: manifest.optJSONObject("smokeSpec")
    val qnnSmokeSpecs = manifest.optJSONArray("smokes")
        .takeIf { it != null && it.length() > 0 }
        ?.toQnnSmokeSpecs()
        ?: manifest.optJSONArray("smokeSpecs")
            .takeIf { it != null && it.length() > 0 }
            ?.toQnnSmokeSpecs()
        ?: smoke?.let { listOf(QnnSmokeSpec.fromSmokeJson(it)) }
        ?: emptyList()
    val components = manifest.optJSONArray("components")
    val requiredRuntimeProfile = manifest.optJSONObject("requiredRuntimeProfile")?.let { profile ->
        val qnnSdk = profile.optString("qnnSdk").trim()
        val htpArch = profile.optInt("htpArch", 0)
        if (qnnSdk.isNotBlank() && htpArch > 0) {
            LocalImageQnnRuntimeProfile(
                qnnSdk = qnnSdk,
                htpArch = htpArch,
                completeBundleRuntime = profile.optBoolean("completeBundleRuntime", true)
            )
        } else {
            null
        }
    }
    val primaryPath = components?.firstComponentPath("DIFFUSION")
        ?: components?.firstComponentPath("MODEL")
        ?: components?.firstComponentPath("UNET")
        ?: components?.firstComponentPath("TRANSFORMER")
        ?: manifest.optString("primary").takeIf { it.isNotBlank() }
        ?: manifest.optString("primaryFile").takeIf { it.isNotBlank() }
    val primaryFile = primaryPath
        ?.let { root.safeDescendantOrNull(it) }
        ?.takeIf { it.isFile }
    return LocalImageBundleManifest(
        id = manifest.optString("id").takeIf { it.isNotBlank() },
        displayName = manifest.optString("title").takeIf { it.isNotBlank() }
            ?: manifest.optString("displayName").takeIf { it.isNotBlank() }
            ?: manifest.optString("name").takeIf { it.isNotBlank() },
        task = manifest.optString("task")
            .trim()
            .uppercase()
            .takeIf(String::isNotBlank),
        runtime = runtime,
        family = family,
        imageSize = imageSize,
        minDeviceTier = manifest.optImageEngineMinDeviceTier(),
        requiresQnnRuntime = manifest.optBoolean("requiresQnnRuntime", runtime == LocalImageRuntime.QNN_HTP),
        requiredRuntimeProfile = requiredRuntimeProfile,
        requiresSmokeTest = manifest.optBoolean("requiresSmokeTest", true),
        smokeWidth = smoke?.optInt("width", 0) ?: 0,
        smokeHeight = smoke?.optInt("height", 0) ?: 0,
        smokeSteps = smoke?.optInt("steps", 0) ?: 0,
        smokeTimeoutSeconds = smoke?.optInt("timeoutSeconds", 0) ?: 0,
        qnnSmokeSpec = qnnSmokeSpecs.firstOrNull() ?: QnnSmokeSpec.Empty,
        qnnSmokeSpecs = qnnSmokeSpecs,
        primaryFile = primaryFile,
        componentCount = components?.length() ?: 0
    )
}

private fun JSONArray.toQnnSmokeSpecs(): List<QnnSmokeSpec> =
    buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(QnnSmokeSpec.fromSmokeJson(it)) }
        }
    }

private fun JSONObject.optImageEngineMinDeviceTier(): ImageEngineMinDeviceTier {
    val value = optString("minDeviceTier").takeIf { it.isNotBlank() } ?: return ImageEngineMinDeviceTier.ANY
    return runCatching { ImageEngineMinDeviceTier.valueOf(value) }.getOrDefault(ImageEngineMinDeviceTier.ANY)
}

private fun JSONObject.manifestImageSize(): String? {
    val direct = optString("imageSize").takeIf { it.isNotBlank() }
        ?: optString("size").takeIf { it.isNotBlank() }
    if (direct != null) return direct
    val smoke = optJSONObject("smoke") ?: optJSONObject("smokeSpec")
    val width = smoke?.optInt("width", 0)?.takeIf { it > 0 }
        ?: optInt("width", 0).takeIf { it > 0 }
    val height = smoke?.optInt("height", 0)?.takeIf { it > 0 }
        ?: optInt("height", 0).takeIf { it > 0 }
    return if (width != null && height != null) "${width}x${height}" else null
}

private fun JSONArray.firstComponentPath(role: String): String? {
    for (index in 0 until length()) {
        val component = optJSONObject(index) ?: continue
        if (component.optString("role").equals(role, ignoreCase = true)) {
            return component.optString("path").takeIf { it.isNotBlank() }
                ?: component.optString("fileName").takeIf { it.isNotBlank() }
        }
    }
    return null
}

private fun File.safeDescendantOrNull(relativePath: String): File? {
    val normalized = relativePath.replace('\\', '/').trim().trimStart('/')
    if (normalized.isBlank()) return null
    val rootCanonical = canonicalFile
    val candidate = File(rootCanonical, normalized).canonicalFile
    return candidate.takeIf { it.path == rootCanonical.path || it.path.startsWith(rootCanonical.path + File.separator) }
}

/**
 * Public SDXL archives commonly keep all prompt-conditioning assets in a
 * chipset-neutral nested directory. The manifest path is authoritative; a
 * bounded unique-directory fallback keeps older imported bundles usable.
 */
internal fun resolveSdxlQnnConditioningRoot(bundleRoot: File): File {
    val root = bundleRoot.canonicalFile
    require(root.isDirectory) { "SDXL QNN bundle directory is missing." }
    if (root.hasCompleteSdxlQnnConditioningAssets()) return root

    val manifestFile = File(root, "manifest.json").takeIf(File::isFile)
        ?: root.findDescendantFile("manifest.json")
    val manifestDirectories = manifestFile
        ?.let { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() }
        ?.optJSONArray("components")
        ?.let { components ->
            buildList {
                for (index in 0 until components.length()) {
                    val path = components.optJSONObject(index)
                        ?.optString("path")
                        ?.takeIf(String::isNotBlank)
                        ?: continue
                    val component = root.safeDescendantOrNull(path) ?: continue
                    if (component.name in SDXL_QNN_CONDITIONING_ASSET_NAMES) {
                        component.parentFile?.let(::add)
                    }
                }
            }
        }
        .orEmpty()

    val candidates = (manifestDirectories + root.walkTopDown()
        .maxDepth(5)
        .filter(File::isDirectory)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .toList())
        .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter { it.path.startsWith(root.path + File.separator) }
        .distinctBy(File::getPath)
        .filter(File::hasCompleteSdxlQnnConditioningAssets)
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

    return candidates.firstOrNull()
        ?: error(
            "SDXL QNN bundle requires clip.mnn, clip_2.mnn(+weight), tokenizer.json, " +
                "token_emb*.bin and pos_emb*.bin in one component directory."
        )
}

private fun File.hasCompleteSdxlQnnConditioningAssets(): Boolean =
    SDXL_QNN_CONDITIONING_ASSET_NAMES.all { name -> File(this, name).isFile }

private val SDXL_QNN_CONDITIONING_ASSET_NAMES = setOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight",
    "tokenizer.json",
    "token_emb.bin",
    "token_emb_2.bin",
    "pos_emb.bin",
    "pos_emb_2.bin"
)

private val READINESS_MODEL_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")

private fun LocalImageModelFamily.requiresCompanionComponents(): Boolean =
    when (this) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.WAN -> true
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> false
    }

private fun LocalImageModelFamily.requiredCompanionComponentHint(): String =
    when (this) {
        LocalImageModelFamily.FLUX -> "VAE/AE（如 flux2_ae、ae.sft 或 ae.safetensors）和 Qwen3 4B 文本编码器/LLM"
        LocalImageModelFamily.QWEN_IMAGE -> "Qwen-Image VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.Z_IMAGE -> "VAE/AE 和 Qwen3 文本编码器/LLM"
        LocalImageModelFamily.LONGCAT_IMAGE -> "FLUX VAE/AE 和 Qwen2.5-VL 文本编码器/LLM"
        LocalImageModelFamily.SANA -> "connector、projector、transformer、VAE decoder 和 Sana LLM"
        LocalImageModelFamily.SD_TURBO -> "SD-Turbo 完整 checkpoint"
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.WAN -> "VAE/AE 和文本编码器/LLM"
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> "VAE/AE 和文本编码器/LLM"
    }

private fun File.isVaeComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "vae" in lower ||
        lower.endsWith("/ae.sft") ||
        lower.endsWith("/ae.safetensors") ||
        lower.endsWith("_ae.safetensors") ||
        lower.endsWith("-ae.safetensors") ||
        lower.endsWith("_ae.gguf") ||
        lower.endsWith("-ae.gguf")
}

private fun File.isTextEncoderComponentFile(): Boolean {
    val lower = invariantSeparatorsPath.lowercase()
    return "text_encoder" in lower ||
        "text-encoder" in lower ||
        "text_encoders" in lower ||
        "t5xxl" in lower ||
        "t5-xxl" in lower ||
        "umt5" in lower ||
        "qwen2.5" in lower ||
        "qwen3" in lower ||
        "qwen_3" in lower ||
        "qwen-3" in lower ||
        "mistral" in lower ||
        "gemma" in lower ||
        "llm" in lower
}

private fun File.findDescendantFile(fileName: String): File? =
    walkTopDown().firstOrNull { it.isFile && it.name.equals(fileName, ignoreCase = true) }

private fun String.isPrimaryImageModelName(): Boolean {
    val lower = lowercase()
    val extension = substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in setOf("gguf", "safetensors", "sft", "ckpt", "pth", "pt", "onnx", "mnn", "bin", "ctx", "qnn") &&
        (
        lower == "unet.mnn" ||
                lower == "unet.ctx" ||
                lower == "unet.qnn" ||
                lower.endsWith("unet.bin") ||
                lower.endsWith("unet.ctx") ||
                lower.endsWith("unet.qnn") ||
                lower.endsWith("diffusion.bin") ||
                lower.endsWith("diffusion.ctx") ||
                lower.endsWith("diffusion.qnn") ||
                lower.endsWith("transformer.bin") ||
                lower.endsWith("transformer.ctx") ||
                lower.endsWith("transformer.qnn") ||
                lower.endsWith("context.bin") ||
                lower.endsWith("context.ctx") ||
                lower.endsWith("context.qnn") ||
                lower == "transformer.mnn" ||
                lower == "diffusion.mnn" ||
                "qnn" in lower ||
            "diffusion" in lower ||
                "z-image" in lower ||
                "z_image" in lower ||
                "qwen-image" in lower ||
                "qwen_image" in lower ||
                "glm-image" in lower ||
                "glm_image" in lower ||
                "longcat-image" in lower ||
                "longcat_image" in lower ||
                "dreamlite" in lower ||
                "flux" in lower ||
                "sd-turbo" in lower ||
                "sd_turbo" in lower
            ) &&
        "vae" !in lower &&
        "clip" !in lower &&
        "text" !in lower &&
        "tokenizer" !in lower &&
        "encoder" !in lower
}

private fun String.toImageDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val parts = lowercase().split("x", "×").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size >= 2) {
        return parts[0].coerceAtLeast(64) to parts[1].coerceAtLeast(64)
    }
    return when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL -> 1024 to 1024
        LocalImageModelFamily.WAN -> 832 to 480
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> 512 to 512
    }
}

internal fun Pair<Int, Int>.fastLocalDimensions(family: LocalImageModelFamily): Pair<Int, Int> {
    val maxDimension = when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> 512
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 384
    }
    val largest = maxOf(first, second)
    if (largest <= maxDimension) return first.alignImageDimension() to second.alignImageDimension()
    return ((first * maxDimension) / largest).alignImageDimension() to
        ((second * maxDimension) / largest).alignImageDimension()
}

private fun Int.alignImageDimension(): Int =
    (((this.coerceAtLeast(256) + 32) / 64) * 64).coerceIn(256, 1536)

internal fun resolveMnnDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?
): Pair<Int, Int> {
    requestedWidth?.let { width ->
        require(width == defaultWidth) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${width}x${requestedHeight ?: defaultHeight}。"
        }
    }
    requestedHeight?.let { height ->
        require(height == defaultHeight) {
            "MNN-Diffusion 当前模型只支持 ${defaultWidth}x${defaultHeight}，不能请求 ${requestedWidth ?: defaultWidth}x${height}。"
        }
    }
    return defaultWidth to defaultHeight
}

internal fun resolveStableDiffusionDimensions(
    defaultWidth: Int,
    defaultHeight: Int,
    requestedWidth: Int?,
    requestedHeight: Int?
): Pair<Int, Int> {
    val width = requestedWidth ?: defaultWidth
    val height = requestedHeight ?: defaultHeight
    require(width in 256..1536 && width % 64 == 0) {
        "stable-diffusion.cpp width must be a multiple of 64 between 256 and 1536."
    }
    require(height in 256..1536 && height % 64 == 0) {
        "stable-diffusion.cpp height must be a multiple of 64 between 256 and 1536."
    }
    return width to height
}

internal fun resolveStableDiffusionSteps(
    family: LocalImageModelFamily,
    requestedSteps: Int?
): Int {
    val steps = requestedSteps ?: defaultStepsFor(family)
    require(steps in 1..50) { "stable-diffusion.cpp steps must be between 1 and 50." }
    return steps
}

internal fun resolveStableDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "stable-diffusion.cpp threads must be between 1 and 64." }
    return threads
}

internal fun resolveStableDiffusionFiniteControl(
    name: String,
    requested: Double?,
    defaultValue: Double
): Double {
    val value = requested ?: defaultValue
    require(value.isFinite()) { "stable-diffusion.cpp $name must be finite." }
    when (name) {
        "distilledGuidance" -> require(value in 0.0..30.0) {
            "stable-diffusion.cpp distilledGuidance must be in [0, 30]."
        }
        "flowShift" -> require(value == -1.0 || value in 0.0..100.0) {
            "stable-diffusion.cpp flowShift must be -1 (model default) or in [0, 100]."
        }
    }
    return value
}

internal fun resolveStableDiffusionBackendMode(requestedBackendMode: String?): String {
    val backend = requestedBackendMode?.trim()?.lowercase() ?: "cpu"
    require(backend == "cpu") {
        "stable-diffusion.cpp Android backend currently supports only backendMode=cpu."
    }
    return backend
}

internal fun stableDiffusionPreviewCandidates(outputFile: File): List<File> = buildList {
    repeat(2) { slot ->
        val preview = File(outputFile.absolutePath + ".preview-$slot.png")
        add(preview)
        add(File(preview.absolutePath + ".part"))
    }
}

internal fun resolveStableDiffusionSampleMethod(requestedSampleMethod: String?): String {
    val method = requestedSampleMethod?.trim()?.lowercase() ?: "euler"
    require(method in STABLE_DIFFUSION_SAMPLE_METHODS) {
        "Unsupported stable-diffusion.cpp sample method '$method'."
    }
    return method
}

internal fun stableDiffusionNativeSampleMethodMatches(
    scheduler: ImageSchedulerAlgorithm,
    nativeSampleMethod: String
): Boolean {
    val actual = nativeSampleMethod.trim().lowercase()
    return actual in when (scheduler) {
        ImageSchedulerAlgorithm.EULER -> setOf("euler")
        ImageSchedulerAlgorithm.EULER_A -> setOf("euler_a")
        ImageSchedulerAlgorithm.DPMPP_2M -> setOf("dpmpp_2m", "dpm++2m")
        ImageSchedulerAlgorithm.DDIM -> setOf("ddim", "ddim_trailing")
        ImageSchedulerAlgorithm.LCM -> setOf("lcm")
        ImageSchedulerAlgorithm.FLOW_MATCH -> setOf("flow_match", "euler")
        ImageSchedulerAlgorithm.PNDM_PLMS -> emptySet()
    }
}

private val STABLE_DIFFUSION_SAMPLE_METHODS = setOf(
    "euler",
    "euler_a",
    "heun",
    "dpm2",
    "dpm++2s_a",
    "dpm++2m",
    "dpm++2mv2",
    "ipndm",
    "ipndm_v",
    "lcm",
    "ddim_trailing",
    "tcd",
    "res_multistep",
    "res_2s",
    "er_sde",
    "euler_cfg_pp",
    "euler_a_cfg_pp",
    "euler_ge"
)

internal fun resolveMnnDiffusionThreads(requestedThreads: Int?, defaultThreads: Int): Int {
    val threads = requestedThreads ?: defaultThreads
    require(threads in 1..64) { "MNN-Diffusion threads must be between 1 and 64." }
    return threads
}

internal fun resolveMnnDiffusionMemoryMode(requestedMemoryMode: Int?): Int {
    val memoryMode = requestedMemoryMode ?: 0
    require(memoryMode in 0..2) { "MNN-Diffusion memoryMode must be 0, 1, or 2." }
    return memoryMode
}

internal fun mnnDiffusionControlAuditJson(params: JSONObject): JSONObject = JSONObject().apply {
    listOf(
        "family",
        "width",
        "height",
        "steps",
        "threads",
        "seed",
        "cfgScale",
        "useCfg",
        "sampleMethod",
        "runner",
        "backendMode",
        "memoryMode"
    ).forEach { key ->
        if (params.has(key)) put(key, params.get(key))
    }
}

internal fun verifyStableDiffusionResultControl(
    result: JSONObject,
    key: String,
    expected: Double
) {
    require(result.has(key) && !result.isNull(key)) {
        "stable-diffusion.cpp native result did not report $key."
    }
    val actual = result.get(key)
    require(actual is Number && kotlin.math.abs(actual.toDouble() - expected) <= 1e-6) {
        "stable-diffusion.cpp native result reported $key=$actual, expected $expected."
    }
}

internal fun verifyStableDiffusionDistilledGuidanceResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedDistilledGuidance", requested)
    require(
        result.has("distilledGuidanceSpecified") &&
            result.get("distilledGuidanceSpecified") is Boolean &&
            result.getBoolean("distilledGuidanceSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether distilled guidance was explicitly requested."
    }
    require(
        result.has("distilledGuidanceApplied") &&
            result.get("distilledGuidanceApplied") is Boolean
    ) {
        "stable-diffusion.cpp did not report distilled-guidance applicability."
    }
    if (result.getBoolean("distilledGuidanceApplied")) {
        verifyStableDiffusionResultControl(result, "distilledGuidance", requested)
    } else {
        require(result.has("distilledGuidance") && result.isNull("distilledGuidance")) {
            "stable-diffusion.cpp claimed an inert distilled-guidance value as effective."
        }
    }
}

internal fun verifyStableDiffusionFlowShiftResult(
    result: JSONObject,
    requested: Double,
    specified: Boolean,
    expectApplied: Boolean
) {
    verifyStableDiffusionResultControl(result, "requestedFlowShift", requested)
    require(
        result.has("flowShiftSpecified") &&
            result.get("flowShiftSpecified") is Boolean &&
            result.getBoolean("flowShiftSpecified") == specified
    ) {
        "stable-diffusion.cpp did not preserve whether flow shift was explicitly requested."
    }
    require(result.has("flowShiftApplied") && result.get("flowShiftApplied") is Boolean) {
        "stable-diffusion.cpp did not report flow-shift applicability."
    }
    val applied = result.getBoolean("flowShiftApplied")
    require(applied == expectApplied) {
        "stable-diffusion.cpp flow-shift applicability conflicts with the resolved prediction mode."
    }
    require(result.has("dynamicFlowShift") && result.get("dynamicFlowShift") is Boolean) {
        "stable-diffusion.cpp did not report whether flow shift was dynamically resolved."
    }
    val dynamic = result.getBoolean("dynamicFlowShift")
    if (!applied) {
        require(!dynamic && result.has("flowShift") && result.isNull("flowShift")) {
            "stable-diffusion.cpp claimed an inert flow shift as effective."
        }
        return
    }
    require(result.has("flowShift") && !result.isNull("flowShift")) {
        "stable-diffusion.cpp did not report the effective flow shift."
    }
    val effective = result.get("flowShift")
    require(effective is Number && effective.toDouble().isFinite() && effective.toDouble() >= 0.0) {
        "stable-diffusion.cpp reported an invalid effective flow shift."
    }
    if (dynamic) {
        require(requested < 0.0) {
            "stable-diffusion.cpp dynamically replaced an explicit flow-shift override."
        }
    } else if (requested >= 0.0) {
        require(kotlin.math.abs(effective.toDouble() - requested) <= 1e-6) {
            "stable-diffusion.cpp effective flow shift differs from the configured value."
        }
    }
}

internal data class LocalImageSmokePixelQuality(
    val passed: Boolean,
    val message: String,
    val lumaDynamicRange: Int,
    val distinctLumaBins: Int,
    val meanHorizontalDelta: Double,
    val meanVerticalDelta: Double
)

/**
 * Cheap deterministic corruption gate for product smoke tests.  This proves
 * that a decoder returned a non-trivial image, not that the prompt semantics
 * are correct; semantic/default certification deliberately remains separate.
 */
internal fun evaluateLocalImageSmokePixels(
    width: Int,
    height: Int,
    pixels: IntArray
): LocalImageSmokePixelQuality {
    require(width > 1 && height > 1 && pixels.size == width * height) {
        "Image pixels do not match the declared dimensions."
    }
    val stride = (pixels.size / 65_536).coerceAtLeast(1)
    val lumas = ArrayList<Int>((pixels.size + stride - 1) / stride)
    val occupiedBins = BooleanArray(64)
    var index = 0
    while (index < pixels.size) {
        val pixel = pixels[index]
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val luma = (red * 54 + green * 183 + blue * 19) ushr 8
        lumas += luma
        occupiedBins[(luma ushr 2).coerceIn(0, occupiedBins.lastIndex)] = true
        index += stride
    }
    lumas.sort()
    val p02 = lumas[((lumas.size - 1) * 2) / 100]
    val p98 = lumas[((lumas.size - 1) * 98) / 100]
    val dynamicRange = p98 - p02
    val distinctBins = occupiedBins.count { it }

    fun luma(pixel: Int): Int {
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        return (red * 54 + green * 183 + blue * 19) ushr 8
    }

    var horizontalDelta = 0L
    var horizontalCount = 0L
    var verticalDelta = 0L
    var verticalCount = 0L
    for (y in 0 until height) {
        val row = y * width
        for (x in 1 until width) {
            horizontalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[row + x - 1]))
            horizontalCount += 1
        }
    }
    for (y in 1 until height) {
        val row = y * width
        val previous = row - width
        for (x in 0 until width) {
            verticalDelta += kotlin.math.abs(luma(pixels[row + x]) - luma(pixels[previous + x]))
            verticalCount += 1
        }
    }
    val meanHorizontal = horizontalDelta.toDouble() / horizontalCount.coerceAtLeast(1)
    val meanVertical = verticalDelta.toDouble() / verticalCount.coerceAtLeast(1)
    val reasons = buildList {
        if (dynamicRange < 24) add("luma dynamic range is too low ($dynamicRange)")
        if (distinctBins < 10) add("too few occupied luma bins ($distinctBins)")
        if (meanVertical > 12.0 && meanVertical > meanHorizontal * 3.5) {
            add("strong horizontal stripe pattern detected")
        }
    }
    return LocalImageSmokePixelQuality(
        passed = reasons.isEmpty(),
        message = if (reasons.isEmpty()) {
            "PNG pixel quality smoke passed."
        } else {
            reasons.joinToString("; ")
        },
        lumaDynamicRange = dynamicRange,
        distinctLumaBins = distinctBins,
        meanHorizontalDelta = meanHorizontal,
        meanVerticalDelta = meanVertical
    )
}

internal fun resolveMnnDiffusionBackendMode(requestedBackendMode: String?): String {
    if (requestedBackendMode == null) return "opencl"
    return when (requestedBackendMode.trim().lowercase()) {
        "opencl", "gpu" -> "opencl"
        "cpu" -> "cpu"
        else -> throw IllegalArgumentException(
            "Unsupported MNN-Diffusion backend '$requestedBackendMode'. Supported values are cpu and opencl (gpu is an alias for opencl)."
        )
    }
}

internal fun resolveMnnDiffusionRunner(
    family: LocalImageModelFamily,
    requestedRunner: String?
): String {
    val normalized = requestedRunner?.trim()?.lowercase()
    return if (family == LocalImageModelFamily.SANA) {
        when (normalized) {
            null, "sana", "sana_varp", "module" -> "sana_varp"
            else -> throw IllegalArgumentException(
                "Unsupported MNN Sana runner '$requestedRunner'. Supported values are sana_varp, sana, and module."
            )
        }
    } else {
        when (normalized) {
            null -> "direct"
            "direct", "module" -> normalized
            else -> throw IllegalArgumentException(
                "Unsupported MNN Stable Diffusion runner '$requestedRunner'. Supported values are direct and module."
            )
        }
    }
}

internal fun mnnDiffusionBackendMatches(requestedBackend: String, actualBackend: String): Boolean =
    when (requestedBackend) {
        "opencl" -> actualBackend.trim().lowercase() in setOf("opencl", "gpu")
        "cpu" -> actualBackend.trim().lowercase() == "cpu"
        else -> false
    }

private fun defaultStepsFor(family: LocalImageModelFamily): Int =
    when (family) {
        LocalImageModelFamily.Z_IMAGE -> 4
        LocalImageModelFamily.FLUX -> 4
        LocalImageModelFamily.SD_TURBO -> 1
        LocalImageModelFamily.QWEN_IMAGE -> 6
        LocalImageModelFamily.GLM_IMAGE -> 6
        LocalImageModelFamily.LONGCAT_IMAGE -> 6
        LocalImageModelFamily.DREAMLITE -> 6
        LocalImageModelFamily.SANA -> 5
        LocalImageModelFamily.SDXL -> 8
        LocalImageModelFamily.SD21 -> 8
        LocalImageModelFamily.SD15 -> 8
        LocalImageModelFamily.WAN -> 6
        LocalImageModelFamily.CUSTOM -> 6
    }

internal fun defaultCfgFor(family: LocalImageModelFamily): Double =
    when (family) {
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SD_TURBO -> 1.0
        LocalImageModelFamily.QWEN_IMAGE -> 2.5
        LocalImageModelFamily.SANA -> 4.5
        LocalImageModelFamily.LONGCAT_IMAGE -> 5.0
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> 7.0
    }

internal fun defaultStableDiffusionFlowShiftFor(profile: ImageExecutionProfile): Double {
    if (profile.scheduler.predictionType != ImagePredictionType.FLOW) return -1.0
    return when (profile.family) {
        LocalImageModelFamily.FLUX -> -1.0
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.WAN -> 3.0
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.CUSTOM -> -1.0
    }
}

private fun defaultImageSizeFor(fileName: String): String =
    when (LocalImageModelFamily.infer(fileName)) {
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.GLM_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE -> "768x768"
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.DREAMLITE,
        LocalImageModelFamily.SANA,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.SDXL -> "512x512"
        LocalImageModelFamily.SD_TURBO -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SD15 -> if ("384" in fileName) "384x384" else "512x512"
        LocalImageModelFamily.WAN,
        LocalImageModelFamily.CUSTOM -> "512x512"
    }
