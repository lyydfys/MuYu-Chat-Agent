package com.muyuchat.mca

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.muyuchat.api.local.imagePromptExecutionSha256
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.nativebridge.NativeQnnBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_UPSCALE_SOURCE_SIDE = 2_048
private const val MAX_UPSCALE_SOURCE_PIXELS = 4_000_000L
private const val MAX_UPSCALE_NATIVE_OUTPUT_SIDE = 8_192
private const val MAX_UPSCALE_NATIVE_OUTPUT_PIXELS = 64_000_000L
private const val MAX_UPSCALE_NATIVE_PNG_BYTES = 256L * 1_024L * 1_024L
private const val MAX_UPSCALE_OUTPUT_SIDE = 4_096
private const val MAX_UPSCALE_OUTPUT_PIXELS = 16_000_000L
private const val MAX_UPSCALE_PNG_BYTES = 64L * 1_024L * 1_024L
internal const val STABLE_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
internal const val MNN_DIFFUSION_OUTPUT_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val QWEN_IMAGE_21_RECOMMENDATION_ID = "qwen_image_21_mnn_opencl"
internal data class LocalImageUpscalePublicationDimensions(
    val width: Int,
    val height: Int,
    val pixels: Long
)

internal fun validatedLocalImageUpscalePublicationDimensions(
    sourceWidth: Int,
    sourceHeight: Int,
    targetScale: Int
): LocalImageUpscalePublicationDimensions {
    require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
    val sourcePixels = Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
    require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
        sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS
    ) { "Upscale source exceeds the 2048-pixel side or 4-megapixel execution limit." }
    val width = Math.multiplyExact(sourceWidth, targetScale)
    val height = Math.multiplyExact(sourceHeight, targetScale)
    val pixels = Math.multiplyExact(width.toLong(), height.toLong())
    require(width <= MAX_UPSCALE_OUTPUT_SIDE &&
        height <= MAX_UPSCALE_OUTPUT_SIDE &&
        pixels in 1L..MAX_UPSCALE_OUTPUT_PIXELS
    ) { "Requested upscale output exceeds the bounded Android publication limit." }
    return LocalImageUpscalePublicationDimensions(width, height, pixels)
}

internal data class VerifiedQnnImageOutput(
    val bytes: ByteArray,
    val mimeType: String,
    val outputBytes: Long
)

private enum class SplitSdxlRequestTerminalState {
    PENDING,
    CANCELLED,
    TRANSFERRED
}

private class SplitSdxlRequestTerminalArbiter {
    private val state = AtomicReference(SplitSdxlRequestTerminalState.PENDING)

    fun cancel(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.CANCELLED
    )

    fun transfer(): Boolean = state.compareAndSet(
        SplitSdxlRequestTerminalState.PENDING,
        SplitSdxlRequestTerminalState.TRANSFERRED
    )

    fun current(): SplitSdxlRequestTerminalState = state.get()
}

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
        fun from(value: String?): LocalImageModelFamily {
            val normalized = value?.trim()?.uppercase()
            return when (normalized) {
                "QWEN_IMAGE_21", "QWEN-IMAGE-2.1", "QWEN_IMAGE_2_1" -> QWEN_IMAGE
                else -> entries.firstOrNull { it.name == normalized } ?: CUSTOM
            }
        }

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

internal data class LocalImageBundleComponentContract(
    val role: String,
    val relativePath: String,
    val required: Boolean
)

internal data class LocalImageBundleManifest(
    val id: String? = null,
    val recommendationId: String? = null,
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
    /** The authoritative diffusion path remains available when its file is missing. */
    val declaredPrimaryPath: String? = null,
    val components: List<LocalImageBundleComponentContract> = emptyList(),
    val executionProfileRequiredPaths: List<String> = emptyList(),
    val executionProfileGraphPaths: Map<String, String> = emptyMap(),
    val executionProfileQnnSdk: String? = null,
    val executionProfileDeclared: Boolean = false,
    val componentCount: Int = 0
)

internal sealed interface LocalImageBundleManifestInspection {
    data object Undeclared : LocalImageBundleManifestInspection
    data class Ready(val manifest: LocalImageBundleManifest) : LocalImageBundleManifestInspection
    data class Invalid(val message: String) : LocalImageBundleManifestInspection
}

internal sealed interface LocalImageRuntimeComponentContract {
    data object UndeclaredLegacy : LocalImageRuntimeComponentContract
    data class Ready(
        val requiredPaths: List<String>,
        val missingPaths: List<String>
    ) : LocalImageRuntimeComponentContract
    data class Invalid(val message: String) : LocalImageRuntimeComponentContract
}

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
    /** Stable catalog identity used to connect a downloaded bundle to its recommendation card. */
    val recommendationId: String? = null,
    val componentCount: Int = 1,
    val verificationStatus: LocalImageVerificationStatus = LocalImageVerificationStatus.UNKNOWN,
    val verificationMessage: String = "",
    val verifiedAt: Long = 0L,
    val qnnVerificationStamp: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Retired duplicate ids retained for selections, history and Local API references. */
    val aliases: List<String> = emptyList(),
    /** Complete component identity; never inferred from a primary-only/source hash. */
    val contentFingerprint: String = "",
    val componentSnapshots: List<LocalImageComponentSnapshot> = emptyList(),
    val physicalCopies: List<LocalImageModelPhysicalCopy> = emptyList(),
    /** Content observed when this path actually passed native execution. */
    val verifiedContentFingerprint: String = ""
) {
    fun matchesCatalogId(candidateId: String?): Boolean =
        candidateId != null && (id == candidateId || candidateId in aliases)

    val configured: Boolean
        get() {
            val primary = File(path)
            val root = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
            return path.isNotBlank() &&
                primary.isFile &&
                primary.canRead() &&
                primary.length() > 0L &&
                (root == null || (root.isDirectory && root.canRead()))
        }

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
        .put("recommendationId", recommendationId)
        .put("componentCount", componentCount)
        .put("verificationStatus", verificationStatus.name)
        .put("verificationMessage", verificationMessage)
        .put("verifiedAt", verifiedAt)
        .put("qnnVerificationStamp", qnnVerificationStamp)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("aliases", JSONArray().apply { aliases.distinct().forEach { put(it) } })
        .put("contentFingerprint", contentFingerprint)
        .put("componentSnapshots", JSONArray().apply { componentSnapshots.forEach { put(it.toJson()) } })
        .put("physicalCopies", JSONArray().apply { physicalCopies.forEach { put(it.toJson()) } })
        .put("verifiedContentFingerprint", verifiedContentFingerprint)

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
                recommendationId = json.optString("recommendationId").takeIf { it.isNotBlank() && it != "null" },
                componentCount = json.optInt("componentCount", 1).coerceAtLeast(1),
                verificationStatus = LocalImageVerificationStatus.from(json.optString("verificationStatus")),
                verificationMessage = json.optString("verificationMessage"),
                verifiedAt = json.optLong("verifiedAt", 0L),
                qnnVerificationStamp = json.optString("qnnVerificationStamp"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                aliases = json.optJSONArray("aliases")?.let { values ->
                    (0 until values.length()).mapNotNull { index ->
                        values.optString(index).takeIf { it.isNotBlank() && it != "null" }
                    }
                }.orEmpty(),
                contentFingerprint = json.optString("contentFingerprint"),
                componentSnapshots = json.optJSONArray("componentSnapshots").imageComponentSnapshots(),
                physicalCopies = json.optJSONArray("physicalCopies")?.let { copies ->
                    (0 until copies.length()).mapNotNull { index ->
                        copies.optJSONObject(index)?.let(LocalImageModelPhysicalCopy::fromJson)
                            ?.takeIf { it.path.isNotBlank() && it.fileName.isNotBlank() }
                    }
                }.orEmpty(),
                verifiedContentFingerprint = json.optString("verifiedContentFingerprint")
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
    /** App-private PNG written by a native preview publisher and valid only for this request. */
    val previewPath: String = "",
    val previewMimeType: String = "",
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val previewFrameCount: Int = 0,
    val previewNoisy: Boolean = false,
    /** Shared-QNN preview audit; these counters never satisfy final VAE execution evidence. */
    val previewVaeExecutionAttemptCount: Int = 0,
    val previewVaeExecutionCount: Int = 0,
    val previewVaeExecutionMsTotal: Long = 0L,
    val previewPublicationCount: Int = 0,
    val previewLastStep: Int = 0,
    val previewLastRevision: Long = 0L,
    val previewFailureCode: String = ""
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
    val loras: List<LocalImagePreparedLora> = emptyList(),
    val vaeTiling: LocalImageVaeTilingOptions? = null,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: LocalImageUltraFixOptions? = null,
    val preview: LocalImagePreviewOptions? = null,
    /** Debug-only marker for the semantic smoke harness; never accepted from public JSON. */
    internal val allowLowStepSmoke: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        // Preserve null here so normal product requests can still resolve the
        // model's default negative prompt. Concrete native execution params are
        // canonicalised to a string immediately before crossing the JNI boundary.
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
        if (loras.isNotEmpty()) {
            put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        }
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        if (textualInversionIds.isNotEmpty()) put("textualInversionIds", JSONArray(textualInversionIds))
        ultraFix?.let { put("ultraFix", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = true)

        /** History intentionally omits prepared paths; execution validation happens after restaging. */
        internal fun fromHistoryJson(json: JSONObject?): LocalImageGenerationOptions =
            parseJson(json, validateForExecution = false)

        private fun parseJson(
            json: JSONObject?,
            validateForExecution: Boolean
        ): LocalImageGenerationOptions {
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
            fun optionalArray(key: String): JSONArray? =
                if (json.has(key)) {
                    require(!json.isNull(key)) { "$key must be an array when specified." }
                    val raw = json.get(key)
                    require(raw is JSONArray) { "$key must be an array when specified." }
                    raw
                } else {
                    null
                }

            val parsed = LocalImageGenerationOptions(
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
                loras = optionalArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT) {
                        "Too many LoRA adapters."
                    }
                    buildList {
                        for (index in 0 until array.length()) {
                            add(LocalImagePreparedLora.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                vaeTiling = optionalObject("vaeTiling")?.let(LocalImageVaeTilingOptions::fromJson),
                textualInversionIds = optionalArray("textualInversionIds")?.let { values ->
                    require(values.length() <= 8) { "Too many textual inversion identifiers." }
                    buildList {
                        for (index in 0 until values.length()) {
                            val value = values.get(index) as? String
                                ?: error("textualInversionIds[$index] must be a UUID.")
                            add(UUID.fromString(value.trim()).toString())
                        }
                    }.also { ids -> require(ids.distinct().size == ids.size) { "Textual inversion ids must be unique." } }
                }.orEmpty(),
                ultraFix = optionalObject("ultraFix")?.let(LocalImageUltraFixOptions::fromJson),
                preview = optionalObject("preview")?.let(LocalImagePreviewOptions::fromJson)
            )
            if (!validateForExecution) {
                require(parsed.inputImage == null && parsed.maskImage == null &&
                    parsed.controlImage == null && parsed.loras.isEmpty() && parsed.preview == null
                ) { "Image history options must not contain transient execution artifacts." }
                return parsed
            }
            return parsed.withCanonicalUltraFixControls().also { options ->
                options.validateProductInputContract()
            }
        }
    }
}

data class LocalImageUltraFixOptions(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(targetWidth in 64..8192 && targetWidth % 8 == 0)
        require(targetHeight in 64..8192 && targetHeight % 8 == 0)
        require(targetWidth.toLong() * targetHeight.toLong() <= 64L * 1024L * 1024L)
        require(strength > 0.0 && strength <= 1.0)
        require(inversionSteps in 1..100 && refinementSteps in 1..100)
        require(inversionSteps == localImageDenoisingTailStepCount(refinementSteps, strength))
        require(tileSize in 128..2048 && tileSize % 8 == 0 &&
            tileSize <= minOf(targetWidth, targetHeight))
        require(overlap in 0.0..0.5)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("targetWidth", targetWidth)
        .put("targetHeight", targetHeight)
        .put("strength", strength)
        .put("inversionSteps", inversionSteps)
        .put("refinementSteps", refinementSteps)
        .put("tileSize", tileSize)
        .put("overlap", overlap)

    companion object {
        fun fromJson(json: JSONObject): LocalImageUltraFixOptions {
            val fields = setOf("targetWidth", "targetHeight", "strength", "inversionSteps", "refinementSteps", "tileSize", "overlap")
            require(json.keys().asSequence().toSet() == fields) { "UltraFix options must contain only request fields." }
            fun int(name: String): Int = (json.get(name) as? Number)?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()
                ?: error("UltraFix $name must be an integer.")
            fun double(name: String): Double = (json.get(name) as? Number)?.toDouble()?.takeIf(Double::isFinite)
                ?: error("UltraFix $name must be finite.")
            return LocalImageUltraFixOptions(
                int("targetWidth"), int("targetHeight"), double("strength"), int("inversionSteps"),
                int("refinementSteps"), int("tileSize"), double("overlap")
            )
        }
    }
}

/** Preserves a concrete native product error instead of collapsing it into generation_failed. */
internal fun throwLocalImageNativeFailure(
    result: JSONObject,
    fallbackMessage: String
): Nothing {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    if (code.isNotEmpty()) {
        throw LocalImageProductContractException(code.lowercase(), message)
    }
    error(message)
}

/** Logs a native failure at the Android boundary without coupling pure error mapping to Log. */
private fun logLocalImageNativeFailure(result: JSONObject, fallbackMessage: String) {
    val message = sequenceOf(
        result.optString("error"),
        result.optString("message"),
        fallbackMessage
    ).map { it.trim() }.firstOrNull(String::isNotEmpty).orEmpty()
    val code = result.optString("errorCode").trim()
    Log.e(
        "MCA-LocalImage",
        "native_failure code=${code.ifBlank { "unknown" }} " +
            "backend=${result.optString("backend").take(64)} " +
            "stage=${result.optString("executionStage").take(64)} " +
            "message=${message.take(512)}"
    )
}

class LocalImageProvider(context: Context) {
    private val appContext = context.applicationContext
    private val textualInversionStore by lazy { TextualInversionStore(appContext) }
    private val bridge by lazy { NativeStableDiffusionBridge() }
    private val mnnDiffusionBridge by lazy { NativeMnnDiffusionBridge() }
    private val qnnBridge by lazy { NativeQnnBridge() }
    private val qwenImage21Worker by lazy { QwenImage21WorkerClient(appContext) }
    private val qwenImage21RuntimeResident = AtomicBoolean(false)
    private val activeQwenImage21RequestId = AtomicReference<String?>(null)
    private val sdxlCoordinator by lazy { SdxlTwoPhaseCoordinator(appContext) }
    private val cancellationRequested = AtomicBoolean(false)
    private val activeSplitSdxlTerminal =
        AtomicReference<SplitSdxlRequestTerminalArbiter?>(null)

    @Volatile
    private var activeRuntime: LocalImageRuntime? = null

    fun begin(runtime: LocalImageRuntime) {
        cancellationRequested.set(false)
        if (runtime in setOf(LocalImageRuntime.QNN_HTP, LocalImageRuntime.MNN_DIFFUSION) &&
            NativeMnnDiffusionBridge.isAvailable
        ) {
            runCatching { mnnDiffusionBridge.resetImageCancellation() }
        }
        if (runtime == LocalImageRuntime.QNN_HTP) {
            sdxlCoordinator.begin()
        }
        activeRuntime = runtime
    }

    fun cancel(): Boolean {
        return when (activeRuntime ?: return false) {
            LocalImageRuntime.QNN_HTP -> {
                // The split request state is the linearization point against final output transfer.
                activeSplitSdxlTerminal.get()?.cancel()
                cancellationRequested.set(true)
                activeSplitSdxlTerminal.get()?.cancel()
                runCatching { sdxlCoordinator.cancel() }
                if (NativeQnnBridge.isAvailable) {
                    runCatching { qnnBridge.cancelImageGeneration() }
                }
                if (NativeMnnDiffusionBridge.isAvailable) {
                    runCatching { mnnDiffusionBridge.cancel() }
                }
                true
            }
            LocalImageRuntime.MNN_DIFFUSION -> {
                cancellationRequested.set(true)
                activeQwenImage21RequestId.get()?.let { requestId ->
                    runCatching { qwenImage21Worker.cancelImmediately(requestId) }
                    qwenImage21RuntimeResident.set(false)
                    return true
                }
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

    private suspend fun generateQwenImage21(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        onProgress: (LocalImageProgress) -> Unit
    ): LocalImageResult {
        val generationContext = coroutineContext
        val bundleRoot = model.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?.canonicalFile
            ?: File(model.path).canonicalFile.parentFile
            ?: error("Qwen-Image-2.1 requires its complete MNN model bundle directory.")
        val primary = File(model.path).canonicalFile
        val expectedPrimary = File(bundleRoot, "dit.mnn").canonicalFile
        require(primary == expectedPrimary) {
            "Qwen-Image-2.1 primary model must be dit.mnn directly inside its installed bundle."
        }
        LocalImageBundleContract.inspectQwenImage21Bundle(bundleRoot, primary)
            .readinessMessage()
            ?.let(::error)

        val seed = options.seed ?: (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
        val effectiveOptions = options.copy(seed = seed)
        val profileResolution = resolveLocalImageExecutionProfile(
            model = model,
            options = effectiveOptions,
            bundleRoot = bundleRoot
        )
        val profile = profileResolution.profile
        require(profile.variant == ImageModelVariant.QWEN_IMAGE_21 &&
            profile.profileId == "mnn.qwen-image-2.1.opencl"
        ) { "The selected bundle did not resolve to the Qwen-Image-2.1 MNN profile." }
        validateLocalImageProfileProductOptions(profile, effectiveOptions)
        require(options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
            options.inputImage == null && options.maskImage == null && options.controlImage == null
        ) { "Qwen-Image-2.1 currently supports text-to-image requests only." }
        require(options.negativePrompt.isNullOrBlank()) {
            "Qwen-Image-2.1 does not support a separate negative prompt. Clear it and retry."
        }
        require(options.batchCount == 1 && options.loras.isEmpty() &&
            options.textualInversionIds.isEmpty() && options.vaeTiling == null &&
            options.ultraFix == null && options.preview == null
        ) { "Qwen-Image-2.1 currently supports one image per request without LoRA or editing controls." }
        require(options.cfgScale == null || kotlin.math.abs(options.cfgScale - 1.0) <= 1e-9) {
            "Qwen-Image-2.1 uses its model-native guidance value; reset CFG to 1.0 and retry."
        }
        require(options.useCfg == null || !options.useCfg) {
            "Qwen-Image-2.1 does not support classifier-free guidance. Disable CFG and retry."
        }
        require(options.distilledGuidance == null && options.flowShift == null) {
            "Qwen-Image-2.1 uses its model-native flow schedule; clear the custom guidance/flow shift and retry."
        }
        val width = options.width ?: profile.defaults.width
        val height = options.height ?: profile.defaults.height
        val steps = options.steps ?: profile.scheduler.defaultSteps
        require(QwenImage21SizeContract.isSupported(width, height)) {
            QwenImage21SizeContract.unsupportedSizeMessage(width, height)
        }
        require(steps in profile.scheduler.minSteps..profile.scheduler.maxSteps) {
            "Qwen-Image-2.1 steps must be between ${profile.scheduler.minSteps} and ${profile.scheduler.maxSteps}."
        }
        require(profile.scheduler.algorithm == ImageSchedulerAlgorithm.FLOW_MATCH) {
            "Qwen-Image-2.1 requires its built-in flow-matching sampler."
        }
        requireLocalImagePromptLanguageAdmission(
            profile = profile,
            prompt = prompt.trim(),
            executedNegativePrompt = ""
        )

        val requestedBackend = options.backendMode?.trim()?.lowercase().orEmpty()
        val backend = when (requestedBackend) {
            "", "auto", "fastest", "gpu", "opencl", "mnn_opencl" -> QwenImage21Backend.OPENCL
            "cpu", "mnn_cpu" -> QwenImage21Backend.CPU
            // QNN is an explicit opt-in for the experimental MNN DiT path. The
            // text encoder and VAE remain CPU, matching the native contract.
            "qnn", "mnn_qnn", "qnn_htp", "npu", "htp" -> QwenImage21Backend.QNN
            else -> error(
                "Qwen-Image-2.1 supports MNN OpenCL, MNN QNN/HTP, or CPU fallback."
            )
        }
        val useGpu = backend.useGpu
        val qnnRuntimePath = if (backend == QwenImage21Backend.QNN) {
            qwenQnnRuntimeDirectory(bundleRoot)
        } else {
            null
        }
        val threads = options.threads
            ?: Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        require(threads in 1..16) { "Qwen-Image-2.1 CPU thread count must be between 1 and 16." }
        val backendLabel = when (backend) {
            QwenImage21Backend.CPU -> "MNN CPU"
            QwenImage21Backend.OPENCL -> "MNN OpenCL"
            QwenImage21Backend.QNN -> "MNN QNN/HTP"
        }
        val operationStartedAt = SystemRuntimeMonotonicClock.nowMs()
        fun publishProgress(phase: String, message: String, step: Int) {
            val elapsed = (SystemRuntimeMonotonicClock.nowMs() - operationStartedAt).coerceAtLeast(0L)
            runCatching {
                onProgress(
                    LocalImageProgress(
                        phase = phase,
                        message = message,
                        step = step.coerceIn(0, steps),
                        steps = steps,
                        elapsedMs = elapsed,
                        secondsPerStep = if (step > 0) elapsed / 1_000.0 / step else 0.0,
                        threads = threads,
                        width = width,
                        height = height,
                        cancelRequested = cancellationRequested.get(),
                        requestOptionsJson = effectiveOptions.toJson().toString()
                    )
                )
            }
        }

        val outputDirectory = canonicalLocalImageOutputDirectory(
            appContext.cacheDir,
            "qwen_image_21_outputs"
        )
        pruneStaleQwenImage21Outputs(outputDirectory)
        val outputFile = File(outputDirectory, "qwen-image-2.1-${UUID.randomUUID()}.png").canonicalFile
        require(outputFile.parentFile == outputDirectory) {
            "Qwen-Image-2.1 output path must remain in the app cache directory."
        }
        var activeRequestId: String? = null
        try {
            val loadRequestId = UUID.randomUUID().toString()
            activeRequestId = loadRequestId
            activeQwenImage21RequestId.set(loadRequestId)
            publishProgress("model_loading", "正在校验并加载 Qwen-Image-2.1（${backendLabel}）", 0)
            val loaded = qwenImage21Worker.load(
                bundleRoot = bundleRoot.absolutePath,
                useGpu = useGpu,
                backend = backend,
                qnnRuntimePath = qnnRuntimePath,
                threads = threads,
                bundleFingerprint = profile.bindingFingerprint,
                requestId = loadRequestId,
                onProgress = { progress ->
                    val percent = progress.progressPercent ?: 0
                    publishProgress(
                        "model_loading",
                        "正在加载 Qwen-Image-2.1（${backendLabel}，${percent}%）",
                        0
                    )
                }
            )
            require(loaded.loaded && loaded.state == "READY") {
                "Qwen-Image-2.1 worker did not confirm that the model is ready."
            }
            require(loaded.backendConfigured == backend.nativeAuditName) {
                "Qwen-Image-2.1 worker did not configure the requested ${backendLabel} backend."
            }
            require(loaded.textEncoderOnCpu && loaded.vaeOnCpu) {
                "Qwen-Image-2.1 did not keep its text encoder and VAE on CPU as required by this profile."
            }
            qwenImage21RuntimeResident.set(true)
            generationContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()

            val generationRequestId = UUID.randomUUID().toString()
            activeRequestId = generationRequestId
            activeQwenImage21RequestId.set(generationRequestId)
            publishProgress("denoising", "正在生成图片（0%）", 0)
            val generated = qwenImage21Worker.generate(
                requestId = generationRequestId,
                bundleRoot = bundleRoot.absolutePath,
                prompt = prompt.trim(),
                inputImagePath = null,
                outputPath = outputFile.absolutePath,
                width = width,
                height = height,
                steps = steps,
                seed = seed,
                threads = threads,
                useGpu = useGpu,
                backend = backend,
                qnnRuntimePath = qnnRuntimePath,
                onProgress = { percent ->
                    publishProgress(
                        "denoising",
                        "正在生成图片（${percent}%）",
                        (steps * percent / 100.0).toInt()
                    )
                },
                negativePrompt = "",
                bundleFingerprint = profile.bindingFingerprint
            )
            generationContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            require(generated.requestId == generationRequestId &&
                generated.width == width && generated.height == height &&
                generated.steps == steps && generated.seed == seed
            ) { "Qwen-Image-2.1 worker result does not match the submitted generation request." }
            require(File(generated.outputPath).canonicalFile == outputFile &&
                generated.outputBytes == outputFile.length() &&
                generated.outputBytes in MIN_QNN_OUTPUT_PNG_BYTES..MAX_QNN_OUTPUT_PNG_BYTES
            ) { "Qwen-Image-2.1 returned an invalid or out-of-bounds PNG path/size." }
            val outputSha256 = outputFile.sha256Contents()
            val verifiedOutput = verifyAndReadQwenImageOutput(
                nativeResult = JSONObject()
                    .put("width", width)
                    .put("height", height)
                    .put("mimeType", "image/png")
                    .put("outputPath", outputFile.absolutePath)
                    .put("outputBytes", generated.outputBytes)
                    .put("outputSha256", outputSha256),
                expectedOutputFile = outputFile,
                expectedWidth = width,
                expectedHeight = height,
                checkCancelled = {
                    generationContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
            )
            val executionMetadata = bindQwenImage21ExecutionEvidence(
                execution = JSONObject(generated.executionAudit.toString())
                .put("bundleId", model.recommendationId ?: QWEN_IMAGE_21_RECOMMENDATION_ID)
                .put("profileId", profile.profileId)
                .put("profileBindingFingerprint", profile.bindingFingerprint)
                .put("backendConfigured", loaded.backendConfigured)
                .put(
                    "backendExecutionConfirmedByNative",
                    generated.executionAudit.optBoolean("backendExecutionConfirmedByNative", false)
                )
                .put("textEncoderOnCpu", loaded.textEncoderOnCpu)
                .put("vaeOnCpu", loaded.vaeOnCpu)
                .put("outputSha256", outputSha256)
                .putPromptExecutionBinding(
                    profile = profile,
                    prompt = prompt.trim(),
                    negativePrompt = ""
                ),
                profileId = profile.profileId,
                profileRevision = profile.profileRevision,
                modelFingerprint = profile.modelFingerprint,
                profileBindingFingerprint = profile.bindingFingerprint,
                promptLanguageBindingFingerprint = profile.promptLanguageBindingFingerprint,
                runtime = profile.runtime.name,
                width = width,
                height = height,
                steps = steps,
                seed = seed,
                outputBytes = verifiedOutput.outputBytes,
                outputSha256 = outputSha256
            ).toString()
            verifyQwenNativeBackendExecutionEvidence(
                audit = generated.executionAudit,
                expectedBackend = loaded.backendConfigured
            )
            val sanitizedExecutionMetadata = sanitizeNativeExecutionJson(executionMetadata)
                .takeIf(String::isNotBlank)
                ?: error("Qwen-Image-2.1 execution evidence could not be sanitized.")
            generationContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            publishProgress("complete", "图片生成完成（${backendLabel}）", steps)
            return LocalImageResult(
                bytes = verifiedOutput.bytes,
                mimeType = verifiedOutput.mimeType,
                executionMetadataJson = sanitizedExecutionMetadata,
                seed = seed.toLong()
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            qwenImage21RuntimeResident.set(false)
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { qwenImage21Worker.disconnect() }
            }
            throw cancelled
        } catch (error: Throwable) {
            if (error is LocalImageWorkerCancelledException || cancellationRequested.get()) {
                qwenImage21RuntimeResident.set(false)
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { qwenImage21Worker.disconnect() }
                }
                throw LocalImageWorkerCancelledException()
            }
            if (error is QwenImage21WorkerException || error is OutOfMemoryError) {
                qwenImage21RuntimeResident.set(false)
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { qwenImage21Worker.unload() }
                        .onFailure { runCatching { qwenImage21Worker.disconnect() } }
                }
            }
            throw error
        } finally {
            activeRequestId?.let { requestId ->
                activeQwenImage21RequestId.compareAndSet(requestId, null)
            }
            // Qwen-Image-2.1 keeps nearly 10 GB of native state resident after a successful
            // generation. Release it before the disposable image worker returns so the chat
            // runtime can be loaded without waiting for a later image request.
            val releaseResidentRuntime = qwenImage21RuntimeResident.compareAndSet(true, false)
            withContext(NonCancellable + Dispatchers.IO) {
                if (releaseResidentRuntime) {
                    runCatching { qwenImage21Worker.unload() }
                        .onFailure { runCatching { qwenImage21Worker.disconnect() } }
                } else {
                    // A failed load can still leave the Binder connection alive even though no
                    // resident handle was published; always drop that connection at the boundary.
                    runCatching { qwenImage21Worker.disconnect() }
                }
            }
            runCatching { outputFile.delete() }
        }
    }

    /**
     * Returns one coherent app-private or platform QNN host directory for the
     * Qwen worker. Runtime discovery is advisory: an empty result is passed as
     * null so the native loader can use its normal platform search path.
     */
    private fun qwenQnnRuntimeDirectory(bundleRoot: File): String? {
        val resolution = runCatching {
            qnnRuntimeDirectoryResolutionFor(appContext, bundleRoot)
        }.getOrNull()
        val discovered = resolution?.directories.orEmpty().asSequence()
            .map { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .filterNotNull()
            .firstOrNull { directory ->
                directory.isDirectory &&
                    File(directory, "libQnnSystem.so").isFile &&
                    File(directory, "libQnnHtp.so").isFile
            }
            ?.absolutePath
        if (discovered != null) return discovered

        // Qwen's MNN bundle predates the image-bundle QNN manifest, so it may
        // have no bundle-specific runtime to stage. Reuse the APK's coherent
        // Qualcomm runtime as an advisory candidate when it is packaged; an
        // unknown device simply returns null and native loading remains the
        // compatibility authority.
        return runCatching {
            LiteRtQualcommRuntimeStager.stage(appContext)?.directory?.canonicalPath
        }.getOrNull()
    }

    fun nativeConfig(): JSONObject? =
        if (NativeStableDiffusionBridge.isAvailable) {
            runCatching { JSONObject(bridge.getNativeConfig()) }.getOrNull()
        } else {
            null
        }

    suspend fun upscale(
        input: LocalImagePreparedInput,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = defaultLocalImageThreads(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        if (activeRuntime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            begin(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        }
        val nativeOutput = File(
            appContext.cacheDir,
            "local-image-upscale-native-${UUID.randomUUID()}.png"
        ).canonicalFile
        val postprocessedOutput = File(
            appContext.cacheDir,
            "local-image-upscale-final-${UUID.randomUUID()}.png"
        ).canonicalFile
        require(nativeOutput.parentFile == appContext.cacheDir.canonicalFile &&
            postprocessedOutput.parentFile == appContext.cacheDir.canonicalFile
        ) {
            "Upscale output escaped the app cache directory."
        }
        try {
            coroutineContext.ensureActive()
            require(NativeStableDiffusionBridge.isAvailable) {
                val reason = NativeStableDiffusionBridge.loadError?.message.orEmpty()
                "stable-diffusion.cpp upscaler failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(targetScale in setOf(2, 3, 4)) { "Upscale target scale must be 2, 3, or 4." }
            validatedLocalImageUpscalePublicationDimensions(input.width, input.height, targetScale)
            require(tileSize in 32..1_024 && tileSize % 8 == 0) {
                "Upscale tile size must be a multiple of 8 in 32-1024."
            }
            require(threads in 1..64) { "Upscale thread count must be in 1-64." }
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val upscalerRoot = File(upscaler.path).canonicalFile.parentFile
                ?: error("Upscaler model has no app-private root.")
            val params = JSONObject()
                .put("upscalerId", upscaler.id)
                .put("upscalerSha256", upscaler.sha256)
                .put("upscalerSizeBytes", upscaler.sizeBytes)
                .put("inputImageSha256", input.sha256)
                .put("targetScale", targetScale)
                .put("tileSize", tileSize)
                .put("threads", threads)
            val progressPoller = launch {
                while (isActive) {
                    bridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            val raw = try {
                bridge.upscale(
                    upscalerPath = upscaler.path,
                    upscalerRoot = upscalerRoot.canonicalPath,
                    inputPath = input.path,
                    paramsJson = params.toString(),
                    outputPath = nativeOutput.path
                )
            } finally {
                progressPoller.cancelAndJoin()
                bridge.currentProgressOrNull()?.let(onProgress)
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false)) throw LocalImageWorkerCancelledException()
                logLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
                throwLocalImageNativeFailure(json, "stable-diffusion.cpp ESRGAN upscale failed.")
            }
            val nativeEffective = json.optJSONObject("nativeEffective")
                ?: error("ESRGAN upscale did not report nativeEffective execution evidence.")
            require(json.optBoolean("nativeExecution", false) &&
                !nativeEffective.optBoolean("fallback", true) &&
                nativeEffective.optString("operation") == "ESRGAN_UPSCALE" &&
                nativeEffective.optString("runtime") == LocalImageRuntime.STABLE_DIFFUSION_CPP.name &&
                nativeEffective.optString("backendMode") == "cpu"
            ) { "ESRGAN upscale did not prove direct stable-diffusion.cpp execution." }
            require(nativeEffective.optString("upscalerId") == upscaler.id &&
                nativeEffective.optString("upscalerFileName") == File(upscaler.path).name &&
                nativeEffective.optString("upscalerSha256").lowercase() == upscaler.sha256 &&
                nativeEffective.optLong("upscalerSizeBytes", -1L) == upscaler.sizeBytes &&
                nativeEffective.optBoolean("modelHashVerified", false) &&
                nativeEffective.optBoolean("modelFileIdentityStable", false) &&
                nativeEffective.optString("inputImageSha256").lowercase() == input.sha256
            ) { "ESRGAN upscale identity or input digest evidence does not match the request." }
            val sourceWidth = nativeEffective.optInt("sourceWidth", -1)
            val sourceHeight = nativeEffective.optInt("sourceHeight", -1)
            val nativeScale = nativeEffective.optInt("nativeScale", -1)
            val nativeWidth = nativeEffective.optInt("width", -1)
            val nativeHeight = nativeEffective.optInt("height", -1)
            val nativePixels = runCatching {
                Math.multiplyExact(nativeWidth.toLong(), nativeHeight.toLong())
            }.getOrDefault(-1L)
            val sourcePixels = runCatching {
                Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
            }.getOrDefault(-1L)
            require(sourceWidth in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourceHeight in 1..MAX_UPSCALE_SOURCE_SIDE &&
                sourcePixels in 1L..MAX_UPSCALE_SOURCE_PIXELS &&
                nativeScale in 2..8 &&
                nativeScale >= targetScale &&
                nativeWidth.toLong() == sourceWidth.toLong() * nativeScale.toLong() &&
                nativeHeight.toLong() == sourceHeight.toLong() * nativeScale.toLong() &&
                nativeWidth <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativeHeight <= MAX_UPSCALE_NATIVE_OUTPUT_SIDE &&
                nativePixels in 1L..MAX_UPSCALE_NATIVE_OUTPUT_PIXELS &&
                nativeEffective.optInt("requestedTargetScale", -1) == targetScale &&
                nativeEffective.optInt("tileSize", -1) == tileSize &&
                nativeEffective.optInt("threads", -1) == threads
            ) { "ESRGAN upscale dimensions or controls do not match the request." }
            val physicalComputeCount = nativeEffective.optLong("physicalComputeCount", -1L)
            val physicalComputeSuccessCount =
                nativeEffective.optLong("physicalComputeSuccessCount", -1L)
            val physicalTileComputeCount =
                nativeEffective.optLong("physicalTileComputeCount", -1L)
            val physicalTileComputeSuccessCount =
                nativeEffective.optLong("physicalTileComputeSuccessCount", -1L)
            val expectedTiled = sourceWidth > tileSize || sourceHeight > tileSize
            require(nativeEffective.optBoolean("executionCompleted", false) &&
                physicalComputeCount > 0L &&
                physicalComputeSuccessCount == physicalComputeCount &&
                nativeEffective.optBoolean("tiledExecution", false) == expectedTiled &&
                (if (expectedTiled) {
                    physicalTileComputeCount == physicalComputeCount &&
                        physicalTileComputeSuccessCount == physicalComputeSuccessCount
                } else {
                    physicalComputeCount == 1L &&
                        physicalTileComputeCount == 0L &&
                        physicalTileComputeSuccessCount == 0L
                })
            ) { "ESRGAN upscale did not prove its physical model compute invocations." }
            require(json.optBoolean("contextReleased", false) &&
                json.optLong("nativeGenerationSequence", 0L) > 0L
            ) { "ESRGAN upscale did not release its native context or publish a sequence." }
            val requiredStages = 255L
            listOf("nativeStageMask", "nativeDetailStageMask").forEach { field ->
                val mask = json.optLong(field, -1L)
                require(mask >= 0L && mask and requiredStages == requiredStages) {
                    "ESRGAN upscale $field is missing required execution stages."
                }
            }
            require(nativeOutput.isFile &&
                nativeOutput.length() in 1L..MAX_UPSCALE_NATIVE_PNG_BYTES
            ) {
                "ESRGAN upscale did not publish a bounded non-empty PNG."
            }
            val nativeBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(nativeOutput.path, nativeBounds)
            require(nativeBounds.outWidth == nativeWidth && nativeBounds.outHeight == nativeHeight) {
                "ESRGAN native PNG dimensions differ from native execution evidence."
            }
            val publicationDimensions = validatedLocalImageUpscalePublicationDimensions(
                sourceWidth,
                sourceHeight,
                targetScale
            )
            val finalWidth = publicationDimensions.width
            val finalHeight = publicationDimensions.height
            var decodeSampleSize = 1
            val finalFile = if (targetScale == nativeScale) {
                nativeOutput
            } else {
                coroutineContext.ensureActive()
                decodeSampleSize = (nativeScale / targetScale).takeIf { ratio ->
                    nativeScale % targetScale == 0 && ratio >= 2 &&
                        (ratio and (ratio - 1)) == 0
                } ?: 1
                val nativeBitmap = BitmapFactory.decodeFile(
                    nativeOutput.path,
                    BitmapFactory.Options().apply { inSampleSize = decodeSampleSize }
                )
                    ?: error("ESRGAN native PNG could not be decoded for target-scale resize.")
                try {
                    require(nativeBitmap.width > 0 && nativeBitmap.height > 0 &&
                        nativeBitmap.width <= nativeWidth && nativeBitmap.height <= nativeHeight
                    ) {
                        "ESRGAN native PNG downsample could not be decoded safely."
                    }
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    val resized = Bitmap.createScaledBitmap(nativeBitmap, finalWidth, finalHeight, true)
                    try {
                        coroutineContext.ensureActive()
                        FileOutputStream(postprocessedOutput).use { output ->
                            check(resized.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "Unable to encode target-scale upscale output."
                            }
                            output.fd.sync()
                        }
                    } finally {
                        if (resized !== nativeBitmap) resized.recycle()
                    }
                } finally {
                    nativeBitmap.recycle()
                }
                postprocessedOutput
            }
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            require(finalFile.isFile && finalFile.length() in 1L..MAX_UPSCALE_PNG_BYTES) {
                "Final upscale PNG exceeds the bounded worker publication limit."
            }
            val finalBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(finalFile.path, finalBounds)
            require(finalBounds.outWidth == finalWidth && finalBounds.outHeight == finalHeight) {
                "Final upscale output dimensions do not match the requested scale."
            }
            coroutineContext.ensureActive()
            val finalBytes = finalFile.readBytes()
            val executionMetadata = json
                .put(
                    "productOutput",
                    JSONObject()
                        .put("targetScale", targetScale)
                        .put("nativeFixedScale", nativeScale)
                        .put("postResizeApplied", targetScale != nativeScale)
                        .put(
                            "postResizeMethod",
                            if (targetScale == nativeScale) "none" else "android_bitmap_filtered"
                        )
                        .put("sourceWidth", sourceWidth)
                        .put("sourceHeight", sourceHeight)
                        .put("nativeWidth", nativeWidth)
                        .put("nativeHeight", nativeHeight)
                        .put("decodeSampleSize", decodeSampleSize)
                        .put("width", finalWidth)
                        .put("height", finalHeight)
                        .put("mimeType", "image/png")
                )
                .toString()
            LocalImageResult(
                bytes = finalBytes,
                mimeType = "image/png",
                executionMetadataJson = executionMetadata,
                seed = null
            )
        } finally {
            runCatching { nativeOutput.delete() }
            runCatching { postprocessedOutput.delete() }
            runCatching { bridge.shutdown() }
            if (activeRuntime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
                activeRuntime = null
                cancellationRequested.set(false)
            }
        }
    }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult = withContext(Dispatchers.IO) {
        val options = options.withCanonicalUltraFixControls()
        var textualInversionLease: TextualInversionSelectionLease? = null
        try {
            model.localImageStructuralReadinessMessage()?.let { message -> error(message) }
            require(prompt.isNotBlank()) { "请输入图片描述。" }
            require(!cancellationRequested.get()) { "本地生图已停止" }
            options.validateProductInputContract()
            validateLocalImageRuntimeProductOptions(model.runtime, options)
            if (activeRuntime != model.runtime) begin(model.runtime)
            if (model.isQwenImage21MnnModel()) {
                return@withContext generateQwenImage21(
                    model = model,
                    prompt = prompt,
                    options = options,
                    onProgress = onProgress
                )
            }
            if (qwenImage21RuntimeResident.get()) {
                // The Qwen model is close to 10 GB. Release its isolated MNN context before
                // another image backend is allowed to allocate its own working set.
                try {
                    qwenImage21Worker.unload()
                } finally {
                    qwenImage21RuntimeResident.set(false)
                }
            }
            if (model.runtime == LocalImageRuntime.QNN_HTP) {
            require(NativeQnnBridge.isAvailable) {
                val reason = NativeQnnBridge.loadError?.message.orEmpty()
                "Snapdragon NPU image backend failed to load${if (reason.isBlank()) "" else ": $reason"}"
            }
            require(NativeQnnBridge.runnerReady) {
                JSONObject(qnnBridge.getRuntimeStatsJson()).optString("lastError")
                    .ifBlank {
                        "QNN typed graph bindings are unavailable in this APK; rebuild with the QAIRT/QNN SDK headers."
                    }
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
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            ).withQnnProductSchedule(effectiveOptions)
                .withQnnInpaintProductSchedule(effectiveOptions)
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved QNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "QNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.QNN_HTP,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.QNN_HTP.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val qnnTextualInversionJson = textualInversionLease
                ?.let { lease -> lease.selection.toNativeJson(lease.rootPath).toString() }
                .orEmpty()
            val resolved = profileResolution.layers.resolved
            val conditioningOrder = if (resolved.useCfg) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val resolvedFinalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            )
            val effectiveFamily = profile.family
            val isSdxlQnn = effectiveFamily == LocalImageModelFamily.SDXL
            val usesSplitQnnWorkers =
                profile.graph.workerStrategy == ImageWorkerStrategy.SPLIT_UNET_VAE
            // Only isolated SDXL workers serialize a conditional-only payload.
            // Shared QNN topologies retain the fixed dual-branch layout.
            val useCfgForSdxlConditioning = !usesSplitQnnWorkers || resolved.useCfg
            val sdxlConditioningOrder = if (useCfgForSdxlConditioning) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
            val expectedSdxlConditioningBranches = if (useCfgForSdxlConditioning) 2 else 1
            val expectedSdxlConditioningExecutions = if (useCfgForSdxlConditioning) 4 else 2
            require(!usesSplitQnnWorkers || effectiveOptions.preview == null) {
                "Split-SDXL uses isolated workers and does not support live preview."
            }
            val usesQnnImg2Img =
                effectiveOptions.taskMode == LocalImageTaskMode.IMG2IMG &&
                    profile.hasExecutableQnnImg2ImgTopology()
            val usesSharedQnnImg2Img = usesQnnImg2Img && profile.hasSharedQnnImg2ImgTopology()
            val usesQnnInpaint =
                effectiveOptions.taskMode == LocalImageTaskMode.INPAINT &&
                    profile.hasExecutableQnnInpaintTopology()
            // Qualcomm's Gen5 archives include a native QNN CLIP graph. Keep
            // legacy QNN archives on their existing MNN-embedding path, while
            // shared-text topologies bind the exact declared .bin artifact.
            val qnnTextEncoderContextPath = when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE ->
                    qnnDeclaredTextEncoderContextPath(bundleRoot, profile.graph.textEncoder)
                else -> qnnNativeTextEncoderContextPath(bundleRoot)
            }
            val usesQnnClipTokenIds = !isSdxlQnn && qnnTextEncoderContextPath != null
            when (profile.graph.workerStrategy) {
                ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    if (!isSdxlQnn) {
                        require(!usesQnnClipTokenIds &&
                            profile.graph.textEncoder
                                ?.relativePath
                                ?.substringAfterLast('/')
                                ?.substringAfterLast('\\') == "clip_v2.mnn"
                        ) {
                            "Resolved shared QNN UNet/VAE profile must use the installed MNN clip_v2.mnn conditioner."
                        }
                    }
                }
                ImageWorkerStrategy.SHARED_TEXT_UNET_VAE -> {
                    require(usesQnnClipTokenIds &&
                        profile.graph.textEncoder?.relativePath?.endsWith(".bin", ignoreCase = true) == true
                    ) {
                        "Resolved shared QNN text/UNet/VAE profile requires a real text-encoder context."
                    }
                }
                else -> Unit
            }
            val conditioningRoot = when {
                isSdxlQnn -> resolveSdxlQnnConditioningRoot(bundleRoot)
                profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE -> {
                    val relativePath = requireNotNull(profile.graph.textEncoder?.relativePath) {
                        "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path."
                    }
                    requireNotNull(
                        bundleRoot.safeDescendantOrNull(relativePath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                            ?.parentFile
                    ) { "MNN conditioning graph is missing or empty: $relativePath" }
                }
                else -> bundleRoot
            }
            val outputDir = canonicalLocalImageOutputDirectory(
                appContext.cacheDir,
                if (usesSplitQnnWorkers) SDXL_TWO_PHASE_DIRECTORY else "local_image_outputs"
            )
            if (!usesSplitQnnWorkers) {
                runCatching { QnnImageStageJournal.sweepStalePreviewDirectories(outputDir) }
                runCatching { QnnInputImageArtifact.cleanupStaleSharedArtifacts(appContext.cacheDir) }
                runCatching { QnnInpaintInputArtifact.cleanupStaleArtifacts(appContext.cacheDir) }
            }
            val requestToken = "qnn-htp-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val outputFile = File(outputDir, "$requestToken.png").canonicalFile
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
            val inputTensorFile = File(outputDir, "${outputFile.nameWithoutExtension}.input-rgb-nchw.f32")
            val inpaintMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-latent.f32"
            )
            val inpaintFullMaskTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-mask-full.f32"
            )
            val inpaintMaskedInputTensorFile = File(
                outputDir,
                "${outputFile.nameWithoutExtension}.inpaint-masked-rgb-nchw.f32"
            )
            val encoderLatentFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.f32")
            val encoderMetadataFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-latent.json")
            val encoderJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.encoder-stage.json")
            val unetJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.unet-stage.json")
            val vaeJournalFile = File(outputDir, "${outputFile.nameWithoutExtension}.vae-stage.json")
            val contract = resolveQnnImageGenerationContract(
                resolution = profileResolution,
                defaultThreads = defaultLocalImageThreads(),
                options = effectiveOptions
            )
            val finalNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolvedFinalNegativePrompt,
                useCfg = contract.useCfg
            )
            val executedNegativePrompt = resolveQnnExecutedNegativePrompt(
                useCfg = contract.useCfg,
                effectiveNegativePrompt = finalNegativePrompt.value
            )
            // QNN may discard a model-default negative prompt when CFG is disabled. Admit the
            // final native pair, not the request's pre-resolution negative-prompt value.
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = executedNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val width = contract.width
            val height = contract.height
            val steps = contract.steps
            val threads = contract.threads
            val startedAt = SystemRuntimeMonotonicClock.nowMs()
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
                        elapsedMs = (SystemRuntimeMonotonicClock.nowMs() - startedAt).coerceAtLeast(0L),
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
                .put("negativePrompt", executedNegativePrompt)
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
                .put("workerStrategy", profile.graph.workerStrategy.name)
                .put("progressJournalPath", progressJournalFile.absolutePath)
                .putQnnSemanticDefaults(
                    bundleRoot = bundleRoot,
                    profile = profile,
                    includeVaeEncoderEvidence = usesQnnImg2Img || usesQnnInpaint
                )
                .putQnnNativeMultilingualTextEncoderEvidence(
                    bundleRoot = bundleRoot,
                    profile = profile
                )
            if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                params.put("conditioningContractMode", "shared_unet_vae")
            }
            effectiveOptions.putProductInputNativeParams(params)
            if (usesSplitQnnWorkers) {
                require(!params.has("preview")) {
                    "Split-SDXL params must not carry a live-preview request."
                }
            }
            val nativeMultilingualTextEncoderEvidence = profile
                .textEncoderLanguage
                ?.evidence
                ?.takeIf { profile.hasVerifiedNativeSimplifiedChineseTextEncoder() }
            val requiresQnnVersionedTokenPayload =
                resolved.promptWeightingSupported || nativeMultilingualTextEncoderEvidence != null
            if (isSdxlQnn) {
                params.put("conditioningFormat", "sdxl_qnn_conditioning")
            } else if (usesQnnClipTokenIds) {
                params.put(
                    "conditioningFormat",
                    if (requiresQnnVersionedTokenPayload) {
                        "qnn_clip_token_ids_weights_v1"
                    } else {
                        "qnn_clip_token_ids_i32"
                    }
                )
            }
            val splitRequestLease = if (usesSplitQnnWorkers) {
                sdxlCoordinator.prepareLease(
                    requestId = requestToken,
                    params = params,
                    embeddingsFile = embeddingFile,
                    latentFile = latentFile,
                    metadataFile = latentMetadataFile,
                    inputTensorFile = inputTensorFile,
                    encoderLatentFile = encoderLatentFile,
                    encoderMetadataFile = encoderMetadataFile,
                    outputFile = outputFile,
                    encoderJournal = encoderJournalFile,
                    unetJournal = unetJournalFile,
                    vaeJournal = vaeJournalFile,
                    maskTensorFile = inpaintMaskTensorFile.takeIf { usesQnnInpaint },
                    fullMaskTensorFile = inpaintFullMaskTensorFile.takeIf { usesQnnInpaint }
                )
            } else {
                null
            }
            val splitRequestTerminal = if (usesSplitQnnWorkers) {
                SplitSdxlRequestTerminalArbiter().also { terminal ->
                    activeSplitSdxlTerminal.set(terminal)
                    if (cancellationRequested.get()) terminal.cancel()
                }
            } else {
                null
            }
            var splitOutputTransferred = false
            var splitRequestError: Throwable? = null

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
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        width,
                        height,
                        contract.backendMode,
                        threads,
                        useCfgForSdxlConditioning,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                } else if (usesQnnClipTokenIds) {
                    encodeQnnClipPromptTokenIds(
                        bridge = mnnDiffusionBridge,
                        bundleRoot = bundleRoot,
                        prompt = prompt.trim(),
                        outputFile = embeddingFile,
                        negativePrompt = executedNegativePrompt,
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
                        promptWeightingEnabled = resolved.promptWeightingSupported,
                        nativeMultilingualEvidence = nativeMultilingualTextEncoderEvidence
                    )
                } else {
                    mnnDiffusionBridge.encodeSd15PromptEmbeddings(
                        conditioningRoot.absolutePath,
                        prompt.trim(),
                        executedNegativePrompt,
                        embeddingFile.absolutePath,
                        contract.backendMode,
                        threads,
                        conditioningOrder,
                        resolved.promptWeightingSupported,
                        qnnTextualInversionJson
                    )
                }
                val embeddingJson = JSONObject(embeddingRaw)
                if (!embeddingJson.optBoolean("ok", false)) {
                    if (embeddingJson.optBoolean("cancelled", false) || cancellationRequested.get()) {
                        throw LocalImageWorkerCancelledException()
                    }
                    logLocalImageNativeFailure(embeddingJson, "Failed to encode QNN prompt embeddings.")
                    throwLocalImageNativeFailure(
                        embeddingJson,
                        "Failed to encode QNN prompt embeddings."
                    )
                }
                val nativeMultilingualPromptHandoff = nativeMultilingualTextEncoderEvidence
                    ?.let { evidence ->
                        verifyQnnNativeMultilingualPromptHandoff(
                            bundleRoot = bundleRoot,
                            evidence = evidence,
                            encoded = embeddingJson
                        )
                    }
                nativeMultilingualPromptHandoff?.let { handoff ->
                    // This opaque capability can only be issued after MNN commits the exact token
                    // payload. QNN atomically consumes it while matching prompt, payload,
                    // tokenizer identity, and the signed prompt-to-encoder closure.
                    params.put("mnnPromptHandoff", handoff.opaqueHandle)
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
                requireNativePromptEncodingEvidence(
                    source = embeddingJson,
                    prompt = prompt.trim(),
                    negativePrompt = executedNegativePrompt
                )
                ImageExecutionProfileNativeContract.qnnPromptConditioningHandoffFields.forEach { field ->
                    params.put(field, embeddingJson.get(field))
                }
                require(embeddingFile.isFile && embeddingFile.length() > 0L) {
                    "Prompt conditioning did not produce a concrete artifact."
                }
                val conditioningArtifactSha256 = embeddingFile.sha256Contents()
                textualInversionLease?.let { lease ->
                    verifyEncodedMnnTextualInversionEvidence(
                        encoded = embeddingJson,
                        selection = lease.selection,
                        runtime = TextualInversionRuntime.QNN_HTP
                    )
                }
                if (isSdxlQnn) {
                    require(
                        embeddingJson.optString("conditioningExecutionMode") ==
                            "external_mnn_sdxl_embeddings" &&
                            embeddingJson.optString("conditioningBackend") == "MNN" &&
                            embeddingJson.optString("conditioningGraph") ==
                            SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE &&
                            embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                            expectedSdxlConditioningExecutions &&
                            embeddingJson.optString("conditioningOrder") ==
                            sdxlConditioningOrder &&
                            embeddingJson.optJSONArray("hiddenShape")?.let { shape ->
                                shape.length() == 3 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 77 &&
                                    shape.optInt(2, -1) == 2_048
                            } == true &&
                            embeddingJson.optJSONArray("pooledShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == expectedSdxlConditioningBranches &&
                                    shape.optInt(1, -1) == 1_280
                            } == true &&
                            embeddingJson.optJSONArray("timeIdsShape")?.let { shape ->
                                shape.length() == 2 &&
                                    shape.optInt(0, -1) == 1 && shape.optInt(1, -1) == 6
                            } == true &&
                            embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                            conditioningArtifactSha256 &&
                            embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                            conditioningArtifactSha256
                    ) {
                        "MNN SDXL conditioning did not publish complete graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_sdxl_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE)
                    params.put(
                        "conditioningGraphSha256",
                        sdxlQnnConditioningGraphSha256(conditioningRoot)
                    )
                    params.put("conditioningEncoderExecutionCount", expectedSdxlConditioningExecutions)
                    params.put("conditioningOrder", sdxlConditioningOrder)
                } else if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_UNET_VAE) {
                    val expectedConditioningExecutions = if (resolved.useCfg) 2 else 1
                    val conditioningGraphPath = requireNotNull(
                        profile.graph.textEncoder?.relativePath
                    ) { "Shared QNN UNet/VAE profile is missing its MNN conditioning graph path." }
                    val conditioningGraphFile = requireNotNull(
                        bundleRoot.safeDescendantOrNull(conditioningGraphPath)
                            ?.takeIf { file -> file.isFile && file.length() > 0L }
                    ) { "MNN conditioning graph is missing or empty: $conditioningGraphPath" }
                    val conditioningGraphSha256 = conditioningGraphFile.sha256Contents()
                    val conditioningShape = embeddingJson.optJSONArray("shape")
                    require(embeddingJson.optString("conditioningExecutionMode") ==
                        "external_mnn_embeddings" &&
                        embeddingJson.optString("conditioningBackend") == "MNN" &&
                        embeddingJson.optString("conditioningGraph") == "clip_v2.mnn" &&
                        embeddingJson.optInt("conditioningEncoderExecutionCount", -1) ==
                        expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningOrder") == conditioningOrder &&
                        conditioningShape?.optInt(0, -1) == expectedConditioningExecutions &&
                        embeddingJson.optString("conditioningArtifactSha256").lowercase() ==
                        conditioningArtifactSha256 &&
                        embeddingJson.optString("promptWeightFingerprint").lowercase() ==
                        conditioningArtifactSha256
                    ) {
                        "MNN clip_v2.mnn conditioning did not publish complete native graph and artifact evidence."
                    }
                    params.put("conditioningExecutionMode", "external_mnn_embeddings")
                    params.put("conditioningBackend", "MNN")
                    params.put("conditioningGraph", "clip_v2.mnn")
                    params.put("conditioningGraphSha256", conditioningGraphSha256)
                    params.put("conditioningEncoderExecutionCount", expectedConditioningExecutions)
                    params.put("conditioningOrder", conditioningOrder)
                }
                params.put("conditioningArtifactSha256", conditioningArtifactSha256)
                val preparedQnnInput = if (usesQnnImg2Img) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN img2img requires a prepared input image."
                    }
                    progress("input_preprocess", "Preparing the bounded QNN VAE-encoder input tensor")
                    val prepared = if (usesSplitQnnWorkers && effectiveOptions.ultraFix == null) {
                        SdxlInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    } else {
                        // UltraFix preprocesses the complete target canvas before the isolated
                        // encoder worker executes its fixed-size graph once per planned tile.
                        QnnInputImageArtifact.prepare(
                            input = input,
                            tensorFile = inputTensorFile,
                            targetWidth = width,
                            targetHeight = height,
                            isCancelled = cancellationRequested::get
                        )
                    }
                    prepared.also { preparedInput ->
                        preparedInput.putNativeParams(params)
                        val schedule = resolveQnnImg2ImgSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params.put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                    }
                } else {
                    null
                }
                val preparedQnnInpaint = if (usesQnnInpaint) {
                    val input = requireNotNull(effectiveOptions.inputImage) {
                        "QNN inpaint requires a prepared source image."
                    }
                    val mask = requireNotNull(effectiveOptions.maskImage) {
                        "QNN inpaint requires a prepared mask image."
                    }
                    progress("input_preprocess", "Preparing bounded QNN inpaint tensors")
                    QnnInpaintInputArtifact.prepare(
                        input = input,
                        mask = mask,
                        sourceTensorFile = inputTensorFile,
                        maskTensorFile = inpaintMaskTensorFile,
                        fullMaskTensorFile = inpaintFullMaskTensorFile,
                        maskedInputTensorFile = inpaintMaskedInputTensorFile,
                        topology = profile.inspectQnnInpaintTopology(),
                        targetWidth = width,
                        targetHeight = height,
                        isCancelled = cancellationRequested::get
                    ).also { prepared ->
                        prepared.putNativeParams(params)
                        val schedule = resolveQnnInpaintSchedule(
                            steps = steps,
                            fullTimetableCount = steps,
                            strength = effectiveOptions.strength ?: 1.0
                        )
                        params
                            .put("fullTimetableCount", schedule.fullTimetableCount)
                            .put("img2imgBeginIndex", schedule.beginIndex)
                            .put("effectiveDenoiseSteps", schedule.effectiveSteps)
                    }
                } else {
                    null
                }
                if (cancellationRequested.get()) {
                    error("本地生图已停止")
                }

                progress("sampling", "正在骁龙 NPU 上运行 QNN UNet 和 VAE")
                var sharedQnnLastJournalProgress: LocalImageProgress? = null
                var sharedQnnNativeCompletedSuccessfully = false
                val raw = if (usesSplitQnnWorkers) {
                    sdxlCoordinator.generate(
                        lease = requireNotNull(splitRequestLease),
                        requestId = requestToken,
                        bundleRoot = bundleRoot,
                        runtimeDirsJson = qnnRuntimeDirsJson(bundleRoot),
                        params = params,
                        embeddingsFile = embeddingFile,
                        latentFile = latentFile,
                        metadataFile = latentMetadataFile,
                        preparedInput = preparedQnnInput,
                        preparedInpaint = preparedQnnInpaint,
                        inputTensorFile = inputTensorFile,
                        encoderLatentFile = encoderLatentFile,
                        encoderMetadataFile = encoderMetadataFile,
                        outputFile = outputFile,
                        encoderJournal = encoderJournalFile,
                        unetJournal = unetJournalFile,
                        vaeJournal = vaeJournalFile,
                        onProgress = onProgress
                    )
                } else {
                    runCatching { progressJournalFile.delete() }
                    runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                    val progressPoller = launch(Dispatchers.Default) {
                        while (isActive) {
                            val observed = QnnImageStageJournal.readOrPrevious(
                                file = progressJournalFile,
                                previous = sharedQnnLastJournalProgress,
                                threads = threads,
                                width = width,
                                height = height
                            )
                            if (observed != null && observed != sharedQnnLastJournalProgress) {
                                sharedQnnLastJournalProgress = observed
                                onProgress(observed)
                            }
                            delay(250)
                        }
                    }
                    var qnnRawResult: String? = null
                    try {
                        qnnBridge.runImageSemanticGenerate(
                            bundleRoot.absolutePath,
                            qnnRuntimeDirsJson(bundleRoot),
                            params.toString(),
                            embeddingFile.canonicalPath,
                            outputFile.canonicalPath
                        ).also { qnnRawResult = it }
                    } finally {
                        // The parent provider coroutine may already be cancelled here. Join in a
                        // non-cancellable cleanup region so no preview source is deleted while
                        // the poller is still reading it or completing a synchronous callback.
                        withContext(NonCancellable) {
                            progressPoller.cancelAndJoin()
                        }
                        sharedQnnNativeCompletedSuccessfully = qnnRawResult?.let { rawResult ->
                            runCatching {
                                val result = JSONObject(rawResult)
                                result.optBoolean("ok", false) &&
                                    !result.optBoolean("cancelled", false)
                            }.getOrDefault(false)
                        } == true
                    }
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) {
                    json.optString("errorCode")
                        .trim()
                        .takeIf(String::isNotEmpty)
                        ?.let { code ->
                            throw LocalImageProductContractException(
                                code.lowercase(),
                                json.optString("error")
                                    .ifBlank { json.optString("message") }
                                    .ifBlank { "Snapdragon NPU image generation failed." }
                            )
                        }
                    error(
                        if (json.optBoolean("cancelled", false)) {
                            "本地生图已停止"
                        } else {
                            json.optString("message").ifBlank { "Snapdragon NPU image generation failed." }
                        }
                    )
                }
                if (!usesSplitQnnWorkers) {
                    val nativeEffective = json.optJSONObject("nativeEffective")
                        ?: error("QNN image generation did not report nativeEffective evidence.")
                    require(json.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        nativeEffective.optString("conditioningArtifactSha256") == conditioningArtifactSha256 &&
                        json.optBoolean("conditioningArtifactConsumed", false) &&
                        nativeEffective.optBoolean("conditioningArtifactConsumed", false) &&
                        (profile.graph.workerStrategy != ImageWorkerStrategy.SHARED_UNET_VAE ||
                            nativeEffective.optString("conditioningGraphSha256") ==
                            params.optString("conditioningGraphSha256"))
                    ) {
                        "QNN image generation did not consume the exact prepared conditioning artifact."
                    }
                }
                val qnnTextualInversionEvidence = textualInversionLease?.let { lease ->
                    promoteConsumedQnnTextualInversionEvidence(
                        result = json,
                        encoded = embeddingJson,
                        selection = lease.selection,
                        expectedConditioningSha256 = conditioningArtifactSha256,
                        splitWorkers = usesSplitQnnWorkers
                    )
                }
                ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
                val inputExecutionAudit = when (effectiveOptions.taskMode) {
                    LocalImageTaskMode.CONTROL ->
                        verifyAndSanitizeQnnProductInput(json, effectiveOptions)
                    LocalImageTaskMode.IMG2IMG -> if (effectiveOptions.ultraFix != null) {
                        verifyAndSanitizeQnnUltraFixProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            usesSplitWorkers = usesSplitQnnWorkers,
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    } else if (usesSplitQnnWorkers) {
                        verifyAndSanitizeQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase()
                        )
                    } else {
                        require(usesSharedQnnImg2Img) {
                            "Resolved QNN img2img execution is neither a split nor coherent shared topology."
                        }
                        verifyAndSanitizeSharedQnnImg2ImgProductInput(
                            result = json,
                            options = effectiveOptions,
                            prepared = requireNotNull(preparedQnnInput),
                            profile = profile,
                            expectedVaeEncoderContextSha256 = params
                                .getString("vaeEncoderContextSha256")
                                .lowercase(),
                            expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                        )
                    }
                    LocalImageTaskMode.INPAINT -> {
                        require(usesQnnInpaint) {
                            "Resolved QNN inpaint execution has no executable topology."
                        }
                        if (usesSplitQnnWorkers) {
                            verifyAndSanitizeSplitQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase()
                            )
                        } else {
                            verifyAndSanitizeSharedQnnInpaintProductInput(
                                result = json,
                                options = effectiveOptions,
                                prepared = requireNotNull(preparedQnnInpaint),
                                profile = profile,
                                expectedVaeEncoderContextSha256 = params
                                    .getString("vaeEncoderContextSha256")
                                    .lowercase(),
                                expectedVaeEncoderGraphName = params.getString("vaeEncoderGraphName")
                            )
                        }
                    }
                    LocalImageTaskMode.TEXT_TO_IMAGE -> {
                        verifyAndSanitizeQnnTextToImagePrivatePaths(json, effectiveOptions)
                        null
                    }
                    else -> error(
                        "QNN native product input verification is unavailable for " +
                            effectiveOptions.taskMode.wireName
                    )
                }
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val verifiedOutput = verifyAndReadQnnImageOutput(
                    nativeResult = json,
                    expectedOutputFile = outputFile,
                    expectedWidth = width,
                    expectedHeight = height,
                    checkCancelled = {
                        coroutineContext.ensureActive()
                        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                    }
                )
                verifyQnnUltraFixEvidence(
                    result = json,
                    request = effectiveOptions.ultraFix,
                    inputImage = effectiveOptions.inputImage,
                    requestedUseCfg = profileResolution.layers.resolved.useCfg,
                    outputBytes = verifiedOutput.bytes,
                    outputMimeType = verifiedOutput.mimeType
                )
                if (!usesSplitQnnWorkers) {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
                val completedStepCount = if (usesSharedQnnImg2Img || usesQnnInpaint) {
                    profileResolution.layers.resolved.timetableCount
                } else {
                    steps
                }
                val qnnExecutionMetadata = sanitizeNativeExecutionJson(
                    qnnImageExecutionMetadata(
                        nativeRequestId = requestToken,
                        nativeResult = json,
                        outputBytes = verifiedOutput.outputBytes,
                        inputExecutionAudit = inputExecutionAudit,
                        requestedPreview = effectiveOptions.preview,
                        stagedRuntime = runtimeResolution.stagedRuntime
                    ).toString()
                ).takeIf(String::isNotBlank)
                    ?: error("QNN execution evidence could not be sanitized.")
                val result = LocalImageResult(
                    bytes = verifiedOutput.bytes,
                    mimeType = verifiedOutput.mimeType,
                    executionMetadataJson = JSONObject(qnnExecutionMetadata).putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = executedNegativePrompt
                    ).toString(),
                    seed = contract.seed.toLong()
                )
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                if (qnnTextualInversionEvidence != null) {
                    val lease = requireNotNull(textualInversionLease)
                    lease.verifyUnchanged()
                    lease.close()
                    textualInversionLease = null
                    textualInversionStore.commitSuccessfulBindings(
                        selection = lease.selection,
                        nativeBindingFingerprint =
                            qnnTextualInversionEvidence.bindingFingerprint,
                        nativeBindingStage = qnnTextualInversionEvidence.bindingStage
                    )
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                // The native worker can leave one committed frame between the last poll and its
                // return. Drain it only after every Java-side output/evidence/TI validation has
                // succeeded, so a product-contract failure cannot publish a new stale preview.
                if (!usesSplitQnnWorkers && sharedQnnNativeCompletedSuccessfully) {
                    QnnImageStageJournal.readOrPrevious(
                        file = progressJournalFile,
                        previous = sharedQnnLastJournalProgress,
                        threads = threads,
                        width = width,
                        height = height
                    )?.let { terminalProgress ->
                        if (terminalProgress != sharedQnnLastJournalProgress) {
                            sharedQnnLastJournalProgress = terminalProgress
                            onProgress(terminalProgress)
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                progress(
                    "completed",
                    "QNN NPU image generation completed",
                    step = completedStepCount,
                    totalSteps = completedStepCount
                )
                coroutineContext.ensureActive()
                if (usesSplitQnnWorkers) {
                    val terminal = requireNotNull(splitRequestTerminal)
                    if (cancellationRequested.get()) terminal.cancel()
                    if (!terminal.transfer()) {
                        check(terminal.current() == SplitSdxlRequestTerminalState.CANCELLED) {
                            "Split-SDXL output transfer was resolved more than once."
                        }
                        throw LocalImageWorkerCancelledException()
                    }
                } else if (cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                splitOutputTransferred = true
                return@withContext result
            } catch (error: Throwable) {
                splitRequestError = error
                throw error
            } finally {
                runCatching {
                    QnnImageStageJournal.cleanupRequestPreviewDirectory(progressJournalFile)
                }
                runCatching { progressJournalFile.delete() }
                runCatching { File(progressJournalFile.absolutePath + ".tmp").delete() }
                if (!usesSplitQnnWorkers) {
                    runCatching { embeddingFile.delete() }
                    runCatching { File(embeddingFile.absolutePath + ".part").delete() }
                    runCatching { outputFile.delete() }
                    runCatching { File(outputFile.absolutePath + ".part").delete() }
                    runCatching { latentFile.delete() }
                    runCatching { File(latentFile.absolutePath + ".part").delete() }
                    runCatching { latentMetadataFile.delete() }
                    runCatching { File(latentMetadataFile.absolutePath + ".part").delete() }
                    runCatching { inputTensorFile.delete() }
                    runCatching { File(inputTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskTensorFile.delete() }
                    runCatching { File(inpaintMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintFullMaskTensorFile.delete() }
                    runCatching { File(inpaintFullMaskTensorFile.absolutePath + ".part").delete() }
                    runCatching { inpaintMaskedInputTensorFile.delete() }
                    runCatching { File(inpaintMaskedInputTensorFile.absolutePath + ".part").delete() }
                    runCatching { encoderLatentFile.delete() }
                    runCatching { File(encoderLatentFile.absolutePath + ".part").delete() }
                    runCatching { encoderMetadataFile.delete() }
                    runCatching { File(encoderMetadataFile.absolutePath + ".part").delete() }
                    runCatching { encoderJournalFile.delete() }
                    runCatching { File(encoderJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { unetJournalFile.delete() }
                    runCatching { File(unetJournalFile.absolutePath + ".tmp").delete() }
                    runCatching { vaeJournalFile.delete() }
                    runCatching { File(vaeJournalFile.absolutePath + ".tmp").delete() }
                }
                val splitTerminalState = splitRequestTerminal?.let { terminal ->
                    if (cancellationRequested.get()) terminal.cancel()
                    terminal.current()
                }
                try {
                    splitRequestLease?.awaitFinishAfterProviderCleanup(
                        succeeded = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.TRANSFERRED
                        } else {
                            splitOutputTransferred
                        },
                        cancelled = if (usesSplitQnnWorkers) {
                            splitTerminalState == SplitSdxlRequestTerminalState.CANCELLED
                        } else {
                            cancellationRequested.get()
                        },
                        error = splitRequestError
                    )
                } finally {
                    splitRequestTerminal?.let { terminal ->
                        activeSplitSdxlTerminal.compareAndSet(terminal, null)
                    }
                }
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
            val outputDir = canonicalLocalImageOutputDirectory(
                appContext.cacheDir,
                "local_image_outputs"
            )
            pruneStaleMnnDiffusionOutputs(outputDir)
            val outputFile = File(
                outputDir,
                "mnn-diffusion-${UUID.randomUUID()}.png"
            ).canonicalFile
            try {
            val fallbackSeed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
            val effectiveOptions = if (options.seed == null) options.copy(seed = fallbackSeed) else options
            val profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                captureTextualInversionExecutionAssets = true
            )
            val profile = profileResolution.profile
            validateLocalImageProfileProductOptions(profile, effectiveOptions)
            textualInversionLease = effectiveOptions.textualInversionIds
                .takeIf(List<String>::isNotEmpty)
                ?.let { ids ->
                    require(profile.capabilities.supportsTextualInversion &&
                        profile.tokenizer.supportsTextualInversion &&
                        profile.hasHostWritableClipTextualInversionTopology()
                    ) {
                        "The resolved MNN profile has no host-writable CLIP textual-inversion topology."
                    }
                    val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                        "MNN textual inversion is missing its exact execution-asset snapshot."
                    }
                    textualInversionStore.acquireSelectionLease(
                        ids = ids,
                        modelFingerprint = profile.modelFingerprint,
                        tokenizerFingerprint = executionAssets.compositeSha256,
                        profileId = profile.profileId,
                        profileRevision = profile.profileRevision,
                        runtime = TextualInversionRuntime.MNN_DIFFUSION,
                        executionAssetBinding = executionAssets
                    ).also { lease ->
                        TextualInversionContract.validateNativeCapability(
                            runtime = LocalImageRuntime.MNN_DIFFUSION.name,
                            graphSupportsTextualInversion =
                                profile.hasHostWritableClipTextualInversionTopology(),
                            selection = lease.selection
                        )
                    }
                }
            val resolved = profileResolution.layers.resolved
            val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
                finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                    userNegativePrompt = effectiveOptions.negativePrompt,
                    modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
                ),
                useCfg = resolved.useCfg
            ).value
            requireLocalImagePromptLanguageAdmission(
                profile = profile,
                prompt = prompt.trim(),
                executedNegativePrompt = effectiveNegativePrompt
            )
            requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
            val nativeTextEncoderBinding = captureMnnNativeTextEncoderBinding(bundleRoot, profile)
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
            // Keep the distinction between an omitted backend (automatic compatibility
            // selection) and an explicit user choice.  The shipped SD1.5 MNN graph is
            // OpenCL-capable but its fused UNet operators are not registered by our CPU
            // build, so only the automatic path may retry on OpenCL after a proven CPU
            // NOT_SUPPORT result.
            val requestedBackendMode = requestedMnnDiffusionBackendMode(effectiveOptions.backendMode)
            var backendMode = resolveMnnDiffusionBackendMode(effectiveOptions.backendMode)
            val backendAttempts = mutableListOf<MnnDiffusionBackendAttempt>()
            var backendFallbackApplied = false
            var backendFallbackReason: String? = null
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
            // Rebuilt per attempt so an OpenCL failure can retry once on CPU.
            fun mnnNativeParams(backend: String): JSONObject {
                val p = ImageExecutionProfileNativeContract.toNativeParamsJson(profileResolution)
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
                    .put("backendMode", backend)
                    // MNN's direct VAE writer needs the profile-declared
                    // output range just like QNN. Keep this field local to
                    // the MNN execution payload so legacy shared contract
                    // consumers remain backward compatible.
                    .put("pixelRange", profile.vae.outputRange.name)
                    .put("memoryMode", memoryMode)
                effectiveOptions.putProductInputNativeParams(p)
                nativeTextEncoderBinding?.let { binding ->
                    p.put("nativeTextEncoderEvidence", binding.toNativeJson())
                }
                textualInversionLease?.let { lease ->
                    val nativeSelection = lease.selection.toNativeJson(lease.rootPath)
                    nativeSelection.keys().forEach { key ->
                        p.put(key, nativeSelection.get(key))
                    }
                }
                return p
            }
            val initialParams = mnnNativeParams(backendMode)
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
                    requestOptionsJson = mnnDiffusionControlAuditJson(initialParams).toString()
                )
            )
            val progressPoller = launch {
                while (isActive) {
                    mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
                    delay(500)
                }
            }
            fun mnnGenerate(backend: String): JSONObject {
                val result = JSONObject(
                    mnnDiffusionBridge.generate(
                        bundleRoot.absolutePath,
                        mnnNativeParams(backend).toString(),
                        outputFile.canonicalPath
                    )
                )
                backendAttempts += MnnDiffusionBackendAttempt(
                    backend = backend,
                    ok = result.optBoolean("ok", false),
                    errorCode = result.optString("errorCode").trim().ifBlank { null },
                    error = result.optString("error").trim().ifBlank { null }
                )
                return result
            }
            var json: JSONObject
            try {
                json = mnnGenerate(backendMode)
                if (!json.optBoolean("ok", false) &&
                    !json.optBoolean("cancelled", false) &&
                    !cancellationRequested.get() &&
                    requestedBackendMode == "auto" &&
                    backendMode == "cpu" &&
                    isMnnCpuBackendUnsupported(json)
                ) {
                    // This is a graph-proven compatibility fallback, not a chipset
                    // allowlist.  Preserve the complete CPU failure in backendAttempts.
                    backendFallbackApplied = true
                    backendFallbackReason = json.optString("error").trim().ifBlank {
                        json.optString("errorCode").trim().ifBlank {
                            "CPU UNet returned NOT_SUPPORT during graph initialization."
                        }
                    }
                    backendMode = "opencl"
                    json = mnnGenerate(backendMode)
                }
                // Keep a narrow automatic fallback for a future package whose preferred
                // backend is OpenCL but whose device reports an OpenCL execution failure.
                // Explicit OpenCL requests remain explicit and never silently change backend.
                if (!json.optBoolean("ok", false) &&
                    !json.optBoolean("cancelled", false) &&
                    !cancellationRequested.get() &&
                    requestedBackendMode == "auto" &&
                    backendMode == "opencl" &&
                    backendAttempts.none { it.backend == "cpu" }
                ) {
                    val openClFailure = json
                    backendFallbackApplied = true
                    backendFallbackReason = openClFailure.optString("error").trim().ifBlank {
                        openClFailure.optString("errorCode").trim().ifBlank {
                            "OpenCL execution failed."
                        }
                    }
                    backendMode = "cpu"
                    json = mnnGenerate(backendMode)
                }
            } finally {
                progressPoller.cancelAndJoin()
                mnnDiffusionBridge.currentProgressOrNull()?.let(onProgress)
            }
            if (!json.optBoolean("ok", false)) {
                if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                    throw LocalImageWorkerCancelledException()
                }
                if (requestedBackendMode == "cpu" && isMnnCpuBackendUnsupported(json)) {
                    json.put("errorCode", MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE)
                    json.put(
                        "error",
                        "MNN SD1.5 CPU backend does not support the fused UNet operators " +
                            "in this bundle (GroupNorm/FmhaV2/SplitGeLU). " +
                            json.optString("error").trim()
                    )
                }
                json.withMnnBackendAudit(
                    requested = requestedBackendMode,
                    effective = backendMode,
                    attempts = backendAttempts,
                    fallback = backendFallbackApplied,
                    fallbackReason = backendFallbackReason
                )
                logLocalImageNativeFailure(json, "MNN-Diffusion image generation failed.")
                throwLocalImageNativeFailure(json, "MNN-Diffusion image generation failed.")
            }
            json.withMnnBackendAudit(
                requested = requestedBackendMode,
                effective = backendMode,
                attempts = backendAttempts,
                fallback = backendFallbackApplied,
                fallbackReason = backendFallbackReason
            )
            ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
            nativeTextEncoderBinding?.verifyNativeReceipt(json)
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
            val verifiedOutput = verifyAndReadQnnImageOutput(
                nativeResult = json,
                expectedOutputFile = outputFile,
                expectedWidth = width,
                expectedHeight = height,
                checkCancelled = {
                    coroutineContext.ensureActive()
                    if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
                }
            )
            verifyMnnPublishedOutputEvidence(
                nativeResult = json,
                expectedOutputFile = outputFile,
                verifiedOutput = verifiedOutput
            )
            val textualInversionEvidence = textualInversionLease?.let { lease ->
                verifyStableDiffusionTextualInversionEvidence(
                    result = json,
                    selection = lease.selection,
                    expectedNativeMode = TextualInversionRuntime.MNN_DIFFUSION.nativeMode
                )
            }
            val executionMetadataJson = sanitizeNativeExecutionJson(
                json
                    .put("imageInput", inputExecutionAudit)
                    .putPromptExecutionBinding(
                        profile = profile,
                        prompt = prompt.trim(),
                        negativePrompt = effectiveNegativePrompt
                    )
                    .toString()
            ).takeIf(String::isNotBlank)
                ?: error("MNN-Diffusion execution evidence could not be sanitized.")
            val result = LocalImageResult(
                bytes = verifiedOutput.bytes,
                mimeType = verifiedOutput.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = seed.toLong()
            )
            coroutineContext.ensureActive()
            if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
            if (textualInversionEvidence != null) {
                val lease = requireNotNull(textualInversionLease)
                lease.verifyUnchanged()
                lease.close()
                textualInversionLease = null
                textualInversionStore.commitSuccessfulBindings(
                    selection = lease.selection,
                    nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                    nativeBindingStage = textualInversionEvidence.bindingStage
                )
            }
            return@withContext result
            } finally {
                cleanupMnnDiffusionRequestOutputs(outputFile)
            }
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
        var effectiveOptions = options.copy(seed = options.seed ?: fallbackSeed)
            .normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
        var profileResolution = resolveLocalImageExecutionProfile(
            model = model,
            options = effectiveOptions,
            bundleRoot = bundleRoot,
            familyOverride = effectiveFamily,
            captureTextualInversionExecutionAssets = true
        )
        // Only the resolved 2.1 GGUF graph may use Viggle's distilled timetable. The
        // optional adapter changes recommended defaults, never an explicit step choice.
        val selectedViggle = profileResolution.profile.profileId == "sdcpp.qwen-image-2.1" &&
            options.loras.size == 1 &&
            QwenImage21TurboSchedule.isOfficialV021(options.loras.single())
        val viggleSteps = if (selectedViggle) options.steps ?: 6 else options.steps
        val useViggleTimetable = selectedViggle &&
            viggleSteps?.let { it in 4..8 } == true &&
            QwenImage21TurboSchedule.acceptsControls(options)
        if (useViggleTimetable) {
            effectiveOptions = options.copy(
                seed = effectiveOptions.seed,
                steps = viggleSteps,
                cfgScale = options.cfgScale ?: 1.0,
                useCfg = options.useCfg ?: false,
                negativePrompt = options.negativePrompt ?: ""
            ).normalizedForPromptExecutionProfile(LocalImageRuntime.STABLE_DIFFUSION_CPP)
            profileResolution = resolveLocalImageExecutionProfile(
                model = model,
                options = effectiveOptions,
                bundleRoot = bundleRoot,
                familyOverride = effectiveFamily,
                captureTextualInversionExecutionAssets = true
            )
        }
        val profile = profileResolution.profile
        validateLocalImageProfileProductOptions(profile, effectiveOptions)
        textualInversionLease = effectiveOptions.textualInversionIds
            .takeIf(List<String>::isNotEmpty)
            ?.let { ids ->
                require(profile.capabilities.supportsTextualInversion &&
                    profile.tokenizer.supportsTextualInversion
                ) {
                    "The resolved stable-diffusion.cpp profile does not expose textual inversion."
                }
                val executionAssets = requireNotNull(profile.textualInversionExecutionAssets) {
                    "stable-diffusion.cpp textual inversion is missing its exact execution-asset snapshot."
                }
                textualInversionStore.acquireSelectionLease(
                    ids = ids,
                    modelFingerprint = profile.modelFingerprint,
                    tokenizerFingerprint = executionAssets.compositeSha256,
                    profileId = profile.profileId,
                    profileRevision = profile.profileRevision,
                    executionAssetBinding = executionAssets
                ).also { lease ->
                    TextualInversionContract.validateNativeCapability(
                        runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP.name,
                        graphSupportsTextualInversion =
                            profile.capabilities.supportsTextualInversion &&
                                profile.tokenizer.supportsTextualInversion,
                        selection = lease.selection
                    )
                }
            }
        val nativeComponentSelection = componentSelection
            .withControlNetPath(resolveProfileControlNetPath(bundleRoot, profile))
            .let { selection ->
                if (profile.textEncoderLanguageCapability() ==
                    LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL
                ) {
                    selection.requireTextEncoderPath(
                        requireNotNull(resolveProfileTextEncoderPath(bundleRoot, profile)) {
                            "Native multilingual profile is missing its evidence-bound text encoder."
                        }
                    )
                } else {
                    selection
                }
            }
        val resolved = profileResolution.layers.resolved

        val outputDir = canonicalLocalImageOutputDirectory(
            appContext.cacheDir,
            "local_image_outputs"
        )
        pruneStaleStableDiffusionOutputs(outputDir)
        val outputFile = File(
            outputDir,
            "sdcpp-${UUID.randomUUID()}.png"
        ).canonicalFile
        try {
        val (width, height) = resolveStableDiffusionDimensions(
            defaultWidth = resolved.width,
            defaultHeight = resolved.height,
            requestedWidth = resolved.width,
            requestedHeight = resolved.height,
            dimensionMultiple = if (profile.variant == ImageModelVariant.QWEN_IMAGE_21) 32 else 64
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
        val effectiveNegativePrompt = resolveLocalImageFinalNegativePromptForExecution(
            finalNegativePrompt = resolveLocalImageFinalNegativePrompt(
                userNegativePrompt = effectiveOptions.negativePrompt,
                modelDefaultNegativePrompt = profile.defaults.defaultNegativePrompt
            ),
            useCfg = resolved.useCfg
        ).value
        requireLocalImagePromptLanguageAdmission(
            profile = profile,
            prompt = prompt.trim(),
            executedNegativePrompt = effectiveNegativePrompt
        )
        requireNativeMultilingualTextEncoderEvidenceAsset(bundleRoot, profile)
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
        val qwenTurboSchedule = if (useViggleTimetable) {
            QwenImage21TurboSchedule.selectedFor(profile, effectiveOptions.loras, steps)
        } else {
            null
        }
        qwenTurboSchedule?.let { params.put("qwenTurboSchedule", it) }
        textualInversionLease?.let { lease ->
            val nativeTextualInversionParams = TextualInversionContract.run {
                lease.selection.toNativeJson(rootPath = lease.rootPath)
            }
            nativeTextualInversionParams.keys().forEach { key ->
                params.put(key, nativeTextualInversionParams.get(key))
            }
        }
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
                outputFile.canonicalPath
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
            if (json.optBoolean("cancelled", false) || cancellationRequested.get()) {
                throw LocalImageWorkerCancelledException()
            }
            logLocalImageNativeFailure(json, "stable-diffusion.cpp image generation failed.")
            throwLocalImageNativeFailure(json, "stable-diffusion.cpp image generation failed.")
        }
        ImageExecutionProfileNativeContract.parseAndValidate(profileResolution, json)
        if (profile.variant == ImageModelVariant.QWEN_IMAGE_21) {
            requireQwenImage21GgufExecutionEvidence(json)
            QwenImage21TurboSchedule.verifyNativeEcho(json, qwenTurboSchedule)
        }
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
        val textualInversionEvidence = verifyStableDiffusionTextualInversionEvidence(
            result = json,
            selection = textualInversionLease?.selection
        )
        val componentSelectionAudit = nativeComponentSelection.verifyNativeEcho(json)
        val inputExecutionAudit = verifyAndSanitizeStableDiffusionProductInput(json, effectiveOptions)
        val outputs = consumeStableDiffusionOutputs(
            result = json,
            expectedCount = effectiveOptions.batchCount,
            expectedSeed = seed,
            legacyOutputFile = outputFile,
            requireCommittedEvidence = effectiveOptions.ultraFix != null
        )
        verifyStableDiffusionUltraFixEvidence(
            result = json,
            request = effectiveOptions.ultraFix,
            inputImage = effectiveOptions.inputImage,
            requestedUseCfg = resolved.useCfg,
            outputs = outputs
        )
        val executionMetadataJson = sanitizeNativeExecutionJson(
            json
                .put("componentSelection", componentSelectionAudit)
                .put("imageInput", inputExecutionAudit)
                .putPromptExecutionBinding(
                    profile = profile,
                    prompt = prompt.trim(),
                    negativePrompt = effectiveNegativePrompt
                )
                .toString()
        ).takeIf(String::isNotBlank)
            ?: error("stable-diffusion.cpp execution evidence could not be sanitized.")
        val first = outputs.first()
        val result = LocalImageResult(
            bytes = first.bytes,
            mimeType = first.mimeType,
            executionMetadataJson = executionMetadataJson,
            seed = first.seed,
            outputs = outputs
        )
        coroutineContext.ensureActive()
        if (cancellationRequested.get()) throw LocalImageWorkerCancelledException()
        textualInversionLease?.let { lease ->
            lease.verifyUnchanged()
            lease.close()
            textualInversionLease = null
            textualInversionStore.commitSuccessfulBindings(
                selection = lease.selection,
                nativeBindingFingerprint = textualInversionEvidence.bindingFingerprint,
                nativeBindingStage = textualInversionEvidence.bindingStage
            )
        }
        result
        } finally {
            cleanupStableDiffusionRequestOutputs(outputFile)
        }
        } finally {
            textualInversionLease?.close()
            textualInversionLease = null
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
            previewVaeExecutionAttemptCount = json.optInt("previewVaeExecutionAttemptCount"),
            previewVaeExecutionCount = json.optInt("previewVaeExecutionCount"),
            previewVaeExecutionMsTotal = json.optLong("previewVaeExecutionMsTotal"),
            previewPublicationCount = json.optInt("previewPublicationCount"),
            previewLastStep = json.optInt("previewLastStep"),
            previewLastRevision = json.optLong("previewLastRevision"),
            previewFailureCode = json.optString("previewFailureCode"),
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
        profile: ImageExecutionProfile,
        includeVaeEncoderEvidence: Boolean
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
        val vaeEncoderContext = if (includeVaeEncoderEvidence) {
            requireNotNull(
                resolveProfileArtifact(
                    profile.graph.vaeEncoder,
                    true,
                    "vae_encoder.bin"
                )
            ) { "Resolved img2img profile is missing its required VAE encoder context." }
        } else {
            null
        }
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
        vaeEncoderContext?.let { relativePath ->
            val root = bundleRoot.canonicalFile
            val encoderContext = File(root, relativePath).canonicalFile
            require(
                encoderContext.path.startsWith(root.path + File.separator) &&
                    encoderContext.isFile && encoderContext.length() > 0L
            ) { "Resolved VAE encoder context is missing or escapes the installed bundle." }
            put("vaeEncoderContextBinary", relativePath)
            put("vaeEncoderContextSha256", encoderContext.sha256Contents())
            val normalizedEncoderPath = relativePath.replace('\\', '/').trimStart('/')
            val smokeGraphName = smokes.firstOrNull { spec ->
                spec.contextBinary.replace('\\', '/').trimStart('/')
                    .equals(normalizedEncoderPath, ignoreCase = true)
            }?.graphName?.trim()?.takeIf(String::isNotEmpty)
            put(
                "vaeEncoderGraphName",
                profile.graph.vaeEncoder?.graphName?.trim()?.takeIf(String::isNotEmpty)
                    ?: smokeGraphName
                    ?: "model"
            )
        }
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

    /**
     * Enables the QNN bridge's strict text-encoder path only for a profile that has already
     * passed the generic topology and evidence checks. The bridge receives bundle-relative paths
     * exclusively and re-hashes the file before mmap, after mmap, and after graph execution.
     */
    private fun JSONObject.putQnnNativeMultilingualTextEncoderEvidence(
        bundleRoot: File,
        profile: ImageExecutionProfile
    ): JSONObject {
        if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return this
        require(profile.runtime == LocalImageRuntime.QNN_HTP &&
            profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) {
            "Direct Chinese QNN prompts require a shared native text-encoder graph."
        }
        val evidence = requireNotNull(profile.textEncoderLanguage?.evidence) {
            "Direct Chinese QNN prompts require a pinned text-encoder evidence asset."
        }
        val tokenizerEvidence = evidence.promptToEncoderAssets.singleOrNull { entry ->
            entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
        }?.asset ?: error(
            "Direct Chinese QNN prompts require one pinned tokenizer JSON evidence asset."
        )
        val expectedGraphName = requireNotNull(profile.graph.textEncoder)
            .graphName
            .trim()
            .takeIf(String::isNotEmpty)
            ?: error("Direct Chinese QNN prompts require an explicit text-encoder graph name.")
        val expectedRelativePath = evidence.textEncoderAsset.relativePath
            .replace('\\', '/')
            .trim()
        val expectedSizeBytes = requireNotNull(evidence.textEncoderAsset.sizeBytes) {
            "Direct Chinese QNN prompts require a size-pinned text-encoder evidence asset."
        }
        val requestedRelativePath = optString("textEncoderContextBinary")
            .replace('\\', '/')
            .trim()
        require(requestedRelativePath == expectedRelativePath) {
            "QNN semantic defaults selected a text encoder different from the language evidence asset."
        }
        val root = bundleRoot.canonicalFile
        val encoder = File(root, requestedRelativePath).canonicalFile
        require(encoder.path.startsWith(root.path + File.separator) &&
            encoder.isFile &&
            encoder.length() == expectedSizeBytes
        ) {
            "Pinned QNN text-encoder evidence asset is unavailable or has an unexpected size."
        }
        val actualSha256 = encoder.sha256Contents()
        require(actualSha256.equals(evidence.textEncoderAsset.fingerprint, ignoreCase = true)) {
            "Pinned QNN text-encoder evidence asset SHA-256 differs from the resolved profile."
        }
        val tokenizer = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
        val expectedTokenizerSizeBytes = requireNotNull(tokenizerEvidence.sizeBytes) {
            "Direct Chinese QNN prompts require a size-pinned tokenizer JSON evidence asset."
        }
        val actualTokenizerSha256 = tokenizer.sha256Contents()
        require(actualTokenizerSha256.equals(tokenizerEvidence.fingerprint, ignoreCase = true) &&
            tokenizer.length() == expectedTokenizerSizeBytes
        ) {
            "Pinned QNN tokenizer JSON evidence asset differs from the resolved profile."
        }
        val tokenizerRelativePath = tokenizer.relativeTo(root).invariantSeparatorsPath
        require(tokenizerRelativePath == tokenizerEvidence.relativePath.replace('\\', '/').trim()) {
            "Pinned QNN tokenizer JSON evidence path differs from the resolved profile."
        }
        val proofSha256 = requireNotNull(
            profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
        ) { "Direct Chinese QNN prompts require a verified signed semantic proof." }
        require(optString("textEncoderGraphName").trim() == expectedGraphName) {
            "QNN semantic defaults selected a text-encoder graph different from the signed language proof."
        }
        return put("textEncoderPath", requestedRelativePath)
            .put("textEncoderSha256", actualSha256.lowercase())
            .put("textEncoderSizeBytes", encoder.length())
            .put("textEncoderGraphName", expectedGraphName)
            .put("tokenizerJsonPath", tokenizerRelativePath)
            .put("tokenizerJsonSha256", actualTokenizerSha256.lowercase())
            .put("tokenizerJsonSizeBytes", tokenizer.length())
            .put("promptToEncoderClosureSha256", evidence.promptToEncoderClosureSha256())
            .put("languageProofSha256", proofSha256)
    }
}

/**
 * The token-ID conditioner is implemented by the MNN bridge even when the text encoder runs on
 * QNN. The serializable fields below are checked only as diagnostics; authorization comes from
 * the one-time native handoff that QNN consumes against the observed artifact and closure.
 */
private data class QnnNativeMultilingualPromptHandoff(
    val opaqueHandle: String
)

private fun verifyQnnNativeMultilingualPromptHandoff(
    bundleRoot: File,
    evidence: ImageTextEncoderLanguageEvidence,
    encoded: JSONObject
): QnnNativeMultilingualPromptHandoff {
    val tokenizerAsset = evidence.promptToEncoderAssets.singleOrNull { entry ->
        entry.role == ImagePromptToEncoderAssetRole.TOKENIZER_JSON
    }?.asset ?: error(
        "Verified native-multilingual QNN execution requires exactly one TOKENIZER_JSON asset."
    )
    val expectedFile = qnnPinnedNativeMultilingualTokenizerJsonFile(bundleRoot, evidence)
    val expectedPath = expectedFile.canonicalPath
    val expectedSha256 = tokenizerAsset.fingerprint.lowercase()
    val expectedSizeBytes = requireNotNull(tokenizerAsset.sizeBytes) {
        "Verified native-multilingual QNN execution requires a size-pinned tokenizer JSON asset."
    }
    require(expectedFile.length() == expectedSizeBytes) {
        "Pinned native-multilingual tokenizer asset size changed before native conditioning."
    }
    require(expectedFile.sha256Contents().equals(expectedSha256, ignoreCase = true)) {
        "Pinned native-multilingual tokenizer asset digest changed before native conditioning."
    }

    val actualPath = (encoded.opt("tokenizerAssetPath") as? String)
        ?.takeIf(String::isNotBlank)
        ?: error("Native tokenizer receipt is missing tokenizerAssetPath.")
    val actualSha256 = (encoded.opt("tokenizerAssetSha256") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing tokenizerAssetSha256.")
    val actualSizeValue = encoded.opt("tokenizerAssetSizeBytes")
    val actualSizeBytes = actualSizeValue
        .takeIf { value ->
            value is Byte || value is Short || value is Int || value is Long
        }
        ?.let { value -> (value as Number).toLong() }
        ?: error("Native tokenizer receipt is missing an exact tokenizerAssetSizeBytes value.")
    val bindingStage = (encoded.opt("tokenizerAssetBindingStage") as? String)
        ?: error("Native tokenizer receipt is missing tokenizerAssetBindingStage.")
    val receiptFile = runCatching { File(actualPath).canonicalFile }.getOrNull()
        ?: error("Native tokenizer receipt path is invalid.")
    require(
        receiptFile.path == expectedPath &&
            actualSha256 == expectedSha256 &&
            actualSizeBytes == expectedSizeBytes &&
            bindingStage == "tokenizer_consumed" &&
            Regex("^[0-9a-f]{64}$").matches(actualSha256)
    ) {
        "Native tokenizer receipt differs from the signed QNN prompt-to-encoder closure."
    }
    val opaqueHandle = (encoded.opt("mnnPromptHandoff") as? String)
        ?.lowercase()
        ?: error("Native tokenizer receipt is missing its one-time MNN-to-QNN handoff.")
    require(Regex("^[0-9a-f]{64}$").matches(opaqueHandle)) {
        "Native tokenizer handoff must be a 256-bit lowercase opaque handle."
    }
    return QnnNativeMultilingualPromptHandoff(opaqueHandle = opaqueHandle)
}

private val imageModelCatalogLock = Any()

private fun canonicalImageModelPath(path: String): String =
    runCatching { File(path).canonicalPath }.getOrDefault(path)

private fun remoteImageModelSource(remote: RemoteModelFile): String =
    "${remote.provider.name.lowercase()}:${remote.repoId}@${remote.revision}/${remote.path}"

internal data class LocalImageCatalogSnapshot(
    val models: String?, val selectedModelId: String?, val selectedBackend: String?, val suppressedPaths: Set<String>
)

class LocalImageModelStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("mca_local_image_models", Context.MODE_PRIVATE)
    private val managedDir: File by lazy {
        (appContext.getExternalFilesDir("image_models") ?: File(appContext.filesDir, "image_models")).also { it.mkdirs() }
    }

    /** Serialize promotion/registration with scans; restore only this catalog's keys on failure. */
    internal fun <T> withCatalogTransaction(block: () -> T): T = synchronized(imageModelCatalogLock) {
        val snapshot = captureCatalogSnapshot()
        try { block() } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(snapshot) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    internal fun captureCatalogSnapshot(): LocalImageCatalogSnapshot = synchronized(imageModelCatalogLock) {
        LocalImageCatalogSnapshot(prefs.getString(KEY_MODELS, null), prefs.getString(KEY_SELECTED_MODEL_ID, null),
            prefs.getString(KEY_SELECTED_BACKEND, null), suppressedImageModelPaths().toSet())
    }

    internal fun restoreCatalogSnapshot(snapshot: LocalImageCatalogSnapshot) = synchronized(imageModelCatalogLock) {
        check(prefs.edit().putString(KEY_MODELS, snapshot.models).putString(KEY_SELECTED_MODEL_ID, snapshot.selectedModelId)
            .putString(KEY_SELECTED_BACKEND, snapshot.selectedBackend).putStringSet(KEY_SUPPRESSED_PATHS, snapshot.suppressedPaths)
            .commit()) { "无法恢复模型目录登记状态，请释放存储空间后刷新。" }
    }

    fun loadModels(discover: Boolean = true): List<LocalImageModelRecord> = synchronized(imageModelCatalogLock) {
        val raw = prefs.getString(KEY_MODELS, null).orEmpty()
        var persisted = runCatching { parseImageCatalogRecords(raw) }.getOrElse {
            // Preserve the original catalog for diagnosis/recovery before writing discoveries.
            if (!prefs.contains("catalog_recovery_raw")) prefs.edit().putString("catalog_recovery_raw", raw).apply()
            emptyList()
        }
        if (raw.isNotBlank() && runCatching { JSONArray(raw).length() }.getOrDefault(0) > persisted.size &&
            !prefs.contains("catalog_recovery_raw")) {
            prefs.edit().putString("catalog_recovery_raw", raw).apply()
        }
        if (!discover) {
            val normalized = normalizeLocalImageModelRecords(persisted, loadSelectedModelId())
            if (normalized != persisted) persistModels(normalized)
            return@synchronized normalized
        }
        val storedRecords = persisted
        persisted = persisted.flatMap { it.expandPhysicalRecords() }
        // Re-observe every companion before retaining execution proof. The digest cache
        // is process-local and stat/ctime based; persisted metadata is never a hash source.
        persisted = persisted.filterNot { model ->
            model.source == "local:discovered" && imageDirectoryIsOwnedComponent(File(model.bundleRoot ?: model.path).let {
                if (it.isDirectory) it else it.parentFile
            }, File(model.path))
        }.map { model ->
            runCatching { model.withObservedContent() }.getOrElse {
                model.copy(verificationStatus = LocalImageVerificationStatus.UNKNOWN,
                    verificationMessage = "", verifiedAt = 0L, qnnVerificationStamp = "", verifiedContentFingerprint = "")
            }
        }
        if (!ManagedModelDownloadWorker.isInstalling) recoverInterruptedImageBundlePromotions(managedDir) { candidate ->
            when (val inspection = inspectLocalImageBundleManifestFromRoot(candidate)) {
                is LocalImageBundleManifestInspection.Ready -> {
                    val manifest = inspection.manifest
                    val primaryReady = manifest.primaryFile?.let { it.isFile && it.length() > 0L } == true
                    val requiredPaths = manifest.components.filter { it.required }.map { it.relativePath } +
                        manifest.executionProfileRequiredPaths
                    primaryReady && requiredPaths.all { relative -> candidate.safeDescendantOrNull(relative)
                        ?.let { it.isFile && it.canRead() && it.length() > 0L } == true }
                }
                else -> false
            }
        }
        // Models downloaded by the runtime/model smoke tools live below the
        // application-owned `files/models` directory, while the UI's import
        // flow historically persisted records under `image_models`.  Discover
        // complete, manifest-declared image bundles from both locations so a
        // valid QNN bundle is visible without requiring a second manual import.
        // Discovery is structural only: it never filters on chipset/profile;
        // the native load/graph path remains the execution authority.
        val suppressed = suppressedImageModelPaths()
        val discovered = discoverInstalledImageBundles(persisted)
            .filterNot { candidate ->
                localImagePhysicalIdentity(candidate) in suppressed ||
                    canonicalImageModelPath(candidate.path) in suppressed // Legacy path-only tombstones.
            }
        if (discovered.isEmpty()) {
            val normalized = normalizeLocalImageModelRecords(persisted, loadSelectedModelId())
            if (normalized != storedRecords) {
                runCatching { saveModels(normalized) }
            }
            return@synchronized normalized
        }
        val merged = normalizeLocalImageModelRecords(persisted + discovered, loadSelectedModelId())
            .sortedByDescending { it.updatedAt }
        if (merged != storedRecords) {
            // Best-effort persistence makes the next refresh stable, but a
            // read-only discovery failure must never hide the in-memory result.
            runCatching { saveModels(merged) }
        }
        return@synchronized merged
    }

    private fun discoverInstalledImageBundles(persisted: List<LocalImageModelRecord>): List<LocalImageModelRecord> {
        val roots = listOfNotNull(
            managedDir,
            appContext.getExternalFilesDir("models"),
            File(appContext.filesDir, "models")
        ).distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
        // Materialize every candidate directory first. A container such as files/models is
        // itself walked at depth 0; without the descendant-ownership check below it would
        // recursively adopt a grandchild bundle's manifest/primary and be persisted as a
        // bogus "parent directory" record beside the real extracted bundle (the source of
        // the duplicate cyberrealistic entry / ":2" alias).
        val candidateDirs = roots
            .filter { it.isDirectory }
            .flatMap { root -> imageDiscoveryWalk(root).filter(File::isDirectory).toList() }
            .map { directory -> runCatching { directory.canonicalFile }.getOrDefault(directory) }
            .distinctBy { it.path }
        val candidatePaths = candidateDirs.map { it.path }.toSet()
        return candidateDirs.mapNotNull { bundleRoot ->
            runCatching {
                // Discovery must not require a manifest as an admission gate. Older
                // QNN/MNN archives shipped only graph files; infer the runtime and
                // primary artifact from the concrete directory, then let readiness and
                // native load decide whether the bundle is executable.
                // A stale/malformed optional manifest must not make an
                // otherwise discoverable downloaded bundle disappear
                // from the model list.  Treat it as advisory metadata;
                // structural files remain discoverable and native load
                // will report a concrete format error when selected.
                // Only a manifest that is a DIRECT child may authorize this directory's
                // identity; a descendant bundle's manifest belongs to that descendant.
                val hasDirectManifest = bundleRoot.listFiles()
                    ?.any { it.isFile && it.name.equals("manifest.json", ignoreCase = true) } == true
                val manifest = if (hasDirectManifest) {
                    runCatching {
                        when (val inspection = inspectLocalImageBundleManifestFromRoot(bundleRoot)) {
                            is LocalImageBundleManifestInspection.Ready -> inspection.manifest
                            else -> null
                        }
                    }.getOrNull()
                } else {
                    null
                }
                // A declared diffusion primary remains authoritative when missing. Falling
                // back to the VAE/encoder in that directory creates a second broken model.
                // Persisted rows keep the missing-file diagnosis; a complete retained copy
                // can take over during normalization without inheriting the old native proof.
                val declaredPrimary = manifest?.declaredPrimaryPath
                    ?.let(bundleRoot::safeDescendantOrNull) ?: manifest?.primaryFile
                if (declaredPrimary != null && (!declaredPrimary.isFile || declaredPrimary.length() <= 0L)) {
                    return@runCatching null
                }
                val primary = declaredPrimary
                    ?: imageDiscoveryWalk(bundleRoot).filter { it.isFile && it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
                        .maxWithOrNull(compareBy<File> { it.name.isPrimaryImageModelName() }.thenBy { it.length() })
                    ?: imageDiscoveryWalk(bundleRoot).firstOrNull {
                        it.isFile && it.length() > 0L &&
                            (it.name.endsWith(".bin", true) ||
                                it.name.endsWith(".mnn", true) ||
                                it.name.endsWith(".safetensors", true) ||
                                it.name.endsWith(".gguf", true))
                    }
                    ?: return@runCatching null
                // A container directory without its own manifest must not adopt a primary
                // that lives inside a different candidate bundle directory; the deeper
                // directory owns that artifact and emits the record instead.
                val primaryCanonical = runCatching { primary.canonicalFile }.getOrDefault(primary)
                if (!hasDirectManifest && imageDirectoryIsOwnedComponent(bundleRoot, primaryCanonical)) {
                    return@runCatching null
                }
                if (!hasDirectManifest && persisted.any { registered -> registered.allPhysicalCopies().any { physical ->
                    runCatching { File(physical.path).canonicalFile == primaryCanonical }.getOrDefault(false) &&
                        (physical.bundleRoot.isNullOrBlank() || !sameCanonicalPath(physical.bundleRoot, bundleRoot.path))
                } }) return@runCatching null
                val ownedByDescendantBundle = candidatePaths.any { otherPath ->
                    otherPath.startsWith(bundleRoot.path + File.separator) &&
                        primaryCanonical.path.startsWith(otherPath + File.separator)
                }
                if (!hasDirectManifest && ownedByDescendantBundle) return@runCatching null
                val runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(bundleRoot, primary, discovery = true)
                if (runtime != LocalImageRuntime.QNN_HTP && runtime != LocalImageRuntime.MNN_DIFFUSION &&
                    runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP
                ) return@runCatching null
                LocalImageModelRecord(
                    id = manifest?.id ?: bundleRoot.name,
                    displayName = manifest?.displayName ?: bundleRoot.name,
                    path = primary.absolutePath,
                    fileName = primary.name,
                    sizeBytes = imageDiscoveryWalk(bundleRoot).filter { it.isFile }.sumOf { it.length() },
                    sha256 = observedLocalImageFileSha256(primary),
                    runtime = runtime,
                    family = manifest?.family ?: LocalImageModelFamily.infer(bundleRoot.name),
                    imageSize = manifest?.imageSize ?: defaultImageSizeFor(bundleRoot.name),
                    source = "local:discovered",
                    bundleRoot = bundleRoot.absolutePath,
                    componentCount = imageDiscoveryWalk(bundleRoot).count { it.isFile }.coerceAtLeast(1),
                    updatedAt = bundleRoot.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                ).withObservedContent().let { observed ->
                    val existing = persisted.firstOrNull { localImagePhysicalIdentity(it) == localImagePhysicalIdentity(observed) &&
                        localImageSameCompleteContents(it, observed) }
                    observed.copy(updatedAt = existing?.updatedAt ?: (bundleRoot.lastModified().takeIf { it > 0L } ?: observed.updatedAt))
                }
            }.getOrNull()
        }
            .distinctBy { runCatching { it.bundleRoot?.let(::File)?.canonicalPath ?: it.path }.getOrDefault(it.path) }
    }

    private fun suppressedImageModelPaths(): Set<String> =
        prefs.getStringSet(KEY_SUPPRESSED_PATHS, emptySet()).orEmpty()

    private fun persistModels(
        models: List<LocalImageModelRecord>, suppressedPaths: Set<String>? = null,
        selectedModelId: String? = loadSelectedModelId(), selectedBackend: ImageBackend? = null
    ) {
        val normalized = normalizeLocalImageModelRecords(models, selectedModelId)
        val array = JSONArray()
        normalized.sortedByDescending { it.updatedAt }.forEach { array.put(it.toJson()) }
        val canonicalSelection = normalized.firstOrNull { it.matchesCatalogId(selectedModelId) }?.id ?: selectedModelId
        val editor = prefs.edit().putString(KEY_MODELS, array.toString())
            .putString(KEY_SELECTED_MODEL_ID, canonicalSelection.orEmpty())
        suppressedPaths?.let { editor.putStringSet(KEY_SUPPRESSED_PATHS, it) }
        selectedBackend?.let { editor.putString(KEY_SELECTED_BACKEND, it.name) }
        check(editor.commit()) {
            "无法保存模型列表。请释放存储空间后刷新或重试导入，模型文件已保留。"
        }
    }

    /** The complete record, selection and backend are registered in one preferences commit. */
    private fun registerRecord(
        record: LocalImageModelRecord, selectWhenNew: Boolean = false,
        backendOnSelection: ImageBackend? = null
    ): LocalImageModelRecord {
        val current = loadModels(discover = false)
        val selected = loadSelectedModelId()
        val wasRegistered = current.any { localImagePhysicalIdentity(record) in it.catalogPhysicalKeys() }
        val normalized = normalizeLocalImageModelRecords(current + record, selected)
        val owner = normalized.first { localImagePhysicalIdentity(record) in it.catalogPhysicalKeys() }
        val select = owner.isReadyForLocalImageGeneration() && (selected == null || (selectWhenNew && !wasRegistered))
        val visibleKeys = normalized.flatMapTo(mutableSetOf()) { model ->
            model.allPhysicalCopies().flatMap { copy ->
                listOf(localImagePhysicalIdentity(model.runtime, copy.path), canonicalImageModelPath(copy.path))
            }
        }
        persistModels(normalized, suppressedImageModelPaths() - visibleKeys,
            if (select) owner.id else selected, if (select) backendOnSelection else null)
        return owner
    }

    fun saveModels(models: List<LocalImageModelRecord>) = synchronized(imageModelCatalogLock) {
        // Tombstones now include the runtime. Saving another runtime that shares
        // this primary must not resurrect the removed row on the next scan.
        val visibleKeys = models.flatMapTo(mutableSetOf()) {
            it.allPhysicalCopies().flatMap { copy ->
                listOf(localImagePhysicalIdentity(it.runtime, copy.path), canonicalImageModelPath(copy.path))
            }
        }
        persistModels(models, suppressedImageModelPaths() - visibleKeys)
    }

    fun removeModelRecord(id: String): Boolean = synchronized(imageModelCatalogLock) {
        val models = loadModels()
        val target = models.firstOrNull { it.matchesCatalogId(id) } ?: return@synchronized false
        val remaining = models.filterNot { it.id == target.id }
        val selected = if (target.matchesCatalogId(loadSelectedModelId()))
            remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id else loadSelectedModelId()
        persistModels(remaining, suppressedImageModelPaths() + target.catalogPhysicalKeys(), selected)
        true
    }

    fun updateModel(record: LocalImageModelRecord): List<LocalImageModelRecord> = synchronized(imageModelCatalogLock) {
        val now = System.currentTimeMillis()
        val current = loadModels()
        val index = current.indexOfFirst { existing ->
            existing.matchesCatalogId(record.id) &&
                localImagePhysicalIdentity(existing) == localImagePhysicalIdentity(record)
        }
        require(index >= 0) { "Image model catalog record no longer exists at this path." }
        val owner = current[index]
        val models = current.toMutableList().also {
            val observed = record.withObservedContent()
            // updateModel is the native-result persistence boundary. A changed snapshot
            // must not be certified by a result produced for its previous components.
            val sameRequestContent = localImageSameCompleteContents(record, observed)
            it[index] = observed.copy(
                id = owner.id, aliases = (owner.aliases + record.aliases).distinct(), updatedAt = now,
                physicalCopies = owner.physicalCopies,
                verificationStatus = if (sameRequestContent) record.verificationStatus else LocalImageVerificationStatus.UNKNOWN,
                verificationMessage = if (sameRequestContent) record.verificationMessage else "",
                verifiedAt = if (sameRequestContent) record.verifiedAt else 0L,
                qnnVerificationStamp = if (sameRequestContent) record.qnnVerificationStamp else "",
                verifiedContentFingerprint = if (sameRequestContent && record.verificationStatus != LocalImageVerificationStatus.UNKNOWN)
                    observed.contentFingerprint else ""
            )
        }
        saveModels(models)
        return@synchronized models
    }

    fun importFromUri(uri: Uri, checkCancelled: () -> Unit = {}): LocalImageModelRecord =
        importFromSource(queryDisplayName(uri) ?: "image-model.task", { inputStreamFor(uri) }, checkCancelled)

    /** Production import entry also usable by file fixtures, without an Android URI provider. */
    internal fun importFromSource(
        fileName: String, openSource: () -> InputStream, checkCancelled: () -> Unit = {},
        source: String = "local"
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        checkCancelled()
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension in SUPPORTED_EXTENSIONS) {
            "请选择 .gguf、.safetensors、.ckpt、.pth、.pt、.onnx、.mnn，或包含 diffusion 主模型、VAE/AE、文本编码器/LLM 的 .zip 图像生成引擎包。"
        }
        if (extension == "zip") return@synchronized importBundleFromSource(fileName, openSource, checkCancelled, source)
        val catalogBefore = captureCatalogSnapshot()
        val workspace = File(managedDir, ".image-import-" + UUID.randomUUID())
        check(workspace.mkdirs()) { "无法创建图像模型导入临时目录。" }
        var promoted: File? = null
        try {
            val staged = File(workspace, "model." + extension)
            openSource().use { input -> staged.outputStream().use { output ->
                copyImageImportInput(input, output, checkCancelled)
            } }
            val draft = LocalImageModelRecord(
                displayName = fileName.substringBeforeLast('.', fileName), path = staged.absolutePath,
                fileName = fileName, sizeBytes = staged.length(), sha256 = "",
                runtime = LocalImageRuntime.infer(fileName), family = LocalImageModelFamily.infer(fileName),
                imageSize = defaultImageSizeFor(fileName), source = source
            ).withObservedContent(checkCancelled)
            checkCancelled()
            val target = uniqueTarget(fileName)
            require(!target.exists() && staged.renameTo(target)) { "图像模型导入提交失败，源文件仍可重新导入。" }
            promoted = target
            checkCancelled()
            return@synchronized registerRecord(draft.copy(path = target.absolutePath, fileName = target.name))
        } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(catalogBefore) }.exceptionOrNull()?.let(failure::addSuppressed)
            promoted?.let { file -> if (!runCatching { imageFileSharesCatalogOwnership(file, loadModels(discover = false)) }.getOrDefault(true))
                runCatching { if (file.exists()) file.delete() } }
            throw failure
        } finally {
            if (workspace.exists()) runCatching { workspace.deleteRecursively() }
        }
    }

    fun registerDownloadedModel(
        file: File,
        remote: RemoteModelFile
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        require(file.exists()) { "下载完成的图像模型文件不存在：${file.absolutePath}" }
        if (file.extension.equals("zip", ignoreCase = true)) {
            return@synchronized importFromSource(file.name, { file.inputStream() }, source = remoteImageModelSource(remote)).also {
                deleteManagedDuplicate(file)
            }
        }
        val digest = sha256(file)
        val runtime = LocalImageRuntime.infer(file.name)
        val family = LocalImageModelFamily.infer("${remote.repoId}/${remote.path}/${file.name}")
        val source = remoteImageModelSource(remote)
        val record = LocalImageModelRecord(
            displayName = file.name.substringBeforeLast('.', file.name),
            path = file.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            sha256 = digest,
            runtime = runtime,
            family = family,
            imageSize = defaultImageSizeFor("${remote.repoId}/${file.name}"),
            source = source,
            updatedAt = System.currentTimeMillis()
        )
        return@synchronized registerDownloadedRecord(record)
    }

    fun managedBundleDirFor(bundleId: String): File {
        val safeName = bundleId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image-engine" }
        // The bundle installer writes into a hidden staging directory and atomically
        // promotes it only after verification. Materializing the public destination
        // here leaves a misleading empty folder when Android stops the process during
        // a large download or while post-download validation is still running.
        return File(managedDir, "bundle-$safeName")
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
        primarySha256: String? = null,
        recordTransform: (LocalImageModelRecord) -> LocalImageModelRecord = { it }
    ): LocalImageModelRecord = synchronized(imageModelCatalogLock) {
        require(bundleDir.isDirectory) { "本地生图引擎包目录不存在：${bundleDir.absolutePath}" }
        require(primaryFile.exists()) { "Local image engine bundle is missing a diffusion model: ${primaryFile.name}" }
        if (primaryFile.extension.equals("zip", ignoreCase = true)) {
            extractImageBundleZipIntoDirectory(primaryFile, bundleDir)
            runCatching { primaryFile.delete() }
        }
        prepareMnnDiffusionTokenizerIfPossible(bundleDir)
        val manifest = localImageBundleManifestFromRoot(bundleDir)
        val resolvedPrimary = manifest?.primaryFile
            ?: primaryFile.takeIf { it.isFile && !it.extension.equals("zip", ignoreCase = true) }
            ?: findPrimaryImageModel(bundleDir)
            ?: error("Local image engine bundle is missing a diffusion model.")
        val canonicalRoot = bundleDir.canonicalFile
        val canonicalPrimary = resolvedPrimary.canonicalFile
        require(canonicalPrimary.isFile && canonicalPrimary.length() > 0L &&
            canonicalPrimary.path.startsWith(canonicalRoot.path + File.separator)) {
            "生图引擎包主模型为空、不可读或位于包目录之外：${resolvedPrimary.name}"
        }
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
            source = remoteImageModelSource(primaryRemote),
            bundleRoot = bundleDir.absolutePath,
            recommendationId = manifest?.recommendationId ?: manifest?.id,
            componentCount = bundleDir.walkTopDown().count { it.isFile }
                .coerceAtLeast(componentCount)
                .coerceAtLeast(manifest?.componentCount ?: 0)
                .coerceAtLeast(1),
            updatedAt = System.currentTimeMillis()
        )
        val observed = record.withObservedContent()
        observed.localImageStructuralReadinessMessage()?.let { readiness ->
            error("图像生成引擎包不完整：$readiness")
        }
        val transformed = recordTransform(observed)
        val prepared = transformed.withObservedContent()
        require(prepared.id == observed.id && localImagePhysicalIdentity(prepared) == localImagePhysicalIdentity(observed) &&
            sameCanonicalPath(prepared.bundleRoot, observed.bundleRoot) && prepared.contentFingerprint == observed.contentFingerprint &&
            prepared.componentSnapshots == observed.componentSnapshots) {
            "模型登记诊断不允许改变已经校验的文件或运行时身份。"
        }
        return@synchronized registerRecord(prepared, selectWhenNew = true, backendOnSelection = ImageBackend.LOCAL)
    }

    /** Complete equal bundles share a catalog owner while retaining every physical copy. */
    private fun registerDownloadedRecord(record: LocalImageModelRecord): LocalImageModelRecord =
        registerRecord(record.withObservedContent())

    /** Optional archive cleanup must not erase a primary or a shared bundle component. */
    internal fun deleteManagedDuplicate(file: File): Boolean = synchronized(imageModelCatalogLock) {
        val root = runCatching { managedDir.canonicalFile }.getOrNull() ?: return@synchronized false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return@synchronized false
        if (candidate.path == root.path || !candidate.path.startsWith(root.path + File.separator)) {
            return@synchronized false
        }
        if (imageFileSharesCatalogOwnership(candidate, loadModels(discover = false))) return@synchronized false
        runCatching {
            if (!candidate.exists()) true
            else if (candidate.isDirectory) candidate.deleteRecursively() && !candidate.exists()
            else candidate.delete() && !candidate.exists()
        }.getOrDefault(false)
    }

    private fun imageFileSharesCatalogOwnership(candidate: File, records: List<LocalImageModelRecord>): Boolean =
        localImageFileSharesCatalogOwnership(candidate, records)

    private fun sameCanonicalPath(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        return runCatching { File(first).canonicalPath == File(second).canonicalPath }.getOrDefault(false)
    }

    fun managedFileFor(fileName: String): File {
        managedDir.mkdirs()
        return uniqueTarget(fileName)
    }

    fun deleteModel(id: String): Boolean = synchronized(imageModelCatalogLock) {
        val models = loadModels()
        val target = models.firstOrNull { it.matchesCatalogId(id) } ?: return@synchronized false
        val remaining = models.filterNot { it.id == target.id }
        val files = target.allPhysicalCopies().map { physical ->
            physical.bundleRoot?.takeIf(String::isNotBlank)?.let(::File) ?: File(physical.path)
        }.distinctBy { it.canonicalPath }
        for (file in files) {
            if (imageFileSharesCatalogOwnership(file, remaining)) continue
            val deleted = runCatching {
                if (!file.exists()) true else if (file.isDirectory) file.deleteRecursively() && !file.exists()
                else file.delete() && !file.exists()
            }.getOrDefault(false)
            if (!deleted) return@synchronized false
        }
        val selected = if (target.matchesCatalogId(loadSelectedModelId()))
            remaining.firstOrNull { it.isReadyForLocalImageGeneration() }?.id else loadSelectedModelId()
        persistModels(remaining, suppressedImageModelPaths() + target.catalogPhysicalKeys(), selected)
        return@synchronized true
    }

    fun loadSelectedModelId(): String? =
        prefs.getString(KEY_SELECTED_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedModelId(modelId: String?) = synchronized(imageModelCatalogLock) {
        val persisted = parseImageCatalogRecords(prefs.getString(KEY_MODELS, null).orEmpty())
        val normalized = normalizeLocalImageModelRecords(persisted, modelId)
        val canonicalId = normalized.firstOrNull { it.matchesCatalogId(modelId) }?.id ?: modelId
        val editor = prefs.edit().putString(KEY_SELECTED_MODEL_ID, canonicalId.orEmpty())
        if (normalized != persisted) {
            val rows = JSONArray()
            normalized.sortedByDescending { it.updatedAt }.forEach { rows.put(it.toJson()) }
            editor.putString(KEY_MODELS, rows.toString())
        }
        check(editor.commit()) { "无法保存所选模型。请释放存储空间后重试。" }
    }

    fun loadSelectedBackend(): ImageBackend =
        runCatching { ImageBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ImageBackend.CLOUD.name).orEmpty()) }
            .getOrDefault(ImageBackend.CLOUD)

    fun saveSelectedBackend(backend: ImageBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    private fun importBundleFromSource(
        fileName: String, openSource: () -> InputStream, checkCancelled: () -> Unit, source: String
    ): LocalImageModelRecord {
        val catalogBefore = captureCatalogSnapshot()
        val workspace = File(managedDir, ".image-import-" + UUID.randomUUID())
        val stagedRoot = File(workspace, "content")
        check(stagedRoot.mkdirs()) { "无法创建图像引擎包导入临时目录。" }
        var promoted: File? = null
        try {
            val extraction = openSource().use { input ->
                extractBoundedImageBundleZip(input, stagedRoot, checkCancelled = checkCancelled)
            }
            checkCancelled()
            prepareMnnDiffusionTokenizerIfPossible(stagedRoot, checkCancelled)
            val manifest = localImageBundleManifestFromRoot(stagedRoot)
            val primary = manifest?.primaryFile ?: extraction.extractedFiles
                .filter { it.extension.lowercase(Locale.ROOT) in MODEL_FILE_EXTENSIONS }
                .sortedWith(compareByDescending<File> { it.name.isPrimaryImageModelName() }.thenByDescending { it.length() })
                .firstOrNull() ?: error("引擎包内没有找到可识别的 GGUF / safetensors / ckpt / ONNX / MNN 模型文件。")
            require(primary.canonicalFile.toPath().startsWith(stagedRoot.canonicalFile.toPath())) {
                "图像引擎包主模型位于导入目录之外。"
            }
            val family = manifest?.family
                ?: LocalImageModelFamily.infer(fileName).takeIf { it != LocalImageModelFamily.CUSTOM }
                ?: LocalImageModelFamily.infer(primary.name)
            val draft = LocalImageModelRecord(
                displayName = manifest?.displayName ?: fileName.substringBeforeLast('.', fileName),
                path = primary.absolutePath, fileName = primary.name, sizeBytes = 0L, sha256 = "",
                runtime = manifest?.runtime ?: inferLocalImageRuntimeForBundle(stagedRoot, primary), family = family,
                imageSize = manifest?.imageSize ?: defaultImageSizeFor(if (family != LocalImageModelFamily.CUSTOM) family.name else primary.name),
                bundleRoot = stagedRoot.absolutePath, source = source,
                recommendationId = manifest?.recommendationId ?: manifest?.id
            ).withObservedContent(checkCancelled)
            draft.localImageStructuralReadinessMessage()?.let { readiness -> error("图像生成引擎包不完整：$readiness") }
            checkCancelled()
            val target = uniqueBundleDir(fileName.substringBeforeLast('.', fileName))
            require(!target.exists() && stagedRoot.renameTo(target)) { "图像引擎包导入提交失败，源文件仍可重新导入。" }
            promoted = target
            val finalPrimary = File(target, primary.relativeTo(stagedRoot).path)
            checkCancelled()
            return registerRecord(draft.copy(path = finalPrimary.absolutePath, bundleRoot = target.absolutePath))
        } catch (failure: Throwable) {
            runCatching { restoreCatalogSnapshot(catalogBefore) }.exceptionOrNull()?.let(failure::addSuppressed)
            promoted?.let { file -> if (!runCatching { imageFileSharesCatalogOwnership(file, loadModels(discover = false)) }.getOrDefault(true))
                runCatching { if (file.exists()) file.deleteRecursively() } }
            throw failure
        } finally {
            if (workspace.exists()) runCatching { workspace.deleteRecursively() }
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
        if (uri.scheme.equals("file", ignoreCase = true)) return uri.path?.let { File(it).name }
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
        private const val KEY_SUPPRESSED_PATHS = "suppressed_local_image_model_paths"
        private const val KEY_SELECTED_MODEL_ID = "selected_local_image_model_id"
        private const val KEY_SELECTED_BACKEND = "selected_image_backend"
        private val MODEL_FILE_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "sft", "mnn", "bin", "ctx", "qnn")
        private val SUPPORTED_EXTENSIONS = setOf("gguf", "safetensors", "ckpt", "pth", "pt", "onnx", "mnn", "zip")
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
 * Builds the native QNN search path around one coherent private runtime. Once
 * a bundle or the APK has been staged, other app-private discovery results are
 * deliberately excluded: the native resolver must not combine a host library
 * from one QNN profile with a Skel/Stub pair from another. OEM platform paths
 * remain as a fallback for device transport dependencies such as libcdsprpc.
 */
internal fun qnnRuntimeDirectoriesForResolvedRuntime(
    privateRuntimeDirectory: String?,
    packagedNativeDirectory: String,
    fallbackDirectories: List<String>,
    detectedSearchDirectories: List<String>
): List<String> {
    val hasPrivateRuntime = !privateRuntimeDirectory.isNullOrBlank()
    val fallback = if (hasPrivateRuntime) {
        fallbackDirectories.filter(::isQnnPlatformRuntimeDirectory)
    } else {
        fallbackDirectories
    }
    val detected = if (hasPrivateRuntime) emptyList() else detectedSearchDirectories
    return qnnRuntimeSearchDirectories(
        bundleRoot = null,
        existingDirectories = listOfNotNull(
            privateRuntimeDirectory,
            packagedNativeDirectory.takeIf { !hasPrivateRuntime }
        ) + fallback + detected
    )
}

private fun isQnnPlatformRuntimeDirectory(path: String): Boolean {
    val normalized = path.trim().replace('\\', '/')
    return normalized.startsWith("/vendor/") ||
        normalized.startsWith("/odm/") ||
        normalized.startsWith("/system/") ||
        normalized.startsWith("/system_ext/") ||
        normalized.startsWith("/product/")
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

internal fun qnnSdkForPackagedImageRuntime(manifest: LocalImageBundleManifest?): String? =
    manifest?.requiredRuntimeProfile?.qnnSdk ?: manifest?.executionProfileQnnSdk

internal fun qnnRuntimeDirectoryResolutionFor(
    context: Context,
    bundleRoot: File?
): QnnRuntimeDirectoryResolution {
    val appContext = context.applicationContext
    val manifest = bundleRoot?.let(::localImageBundleManifestFromRoot)
    val requiredRuntimeProfile = manifest?.requiredRuntimeProfile
    val staged = stageQnnImageBundleRuntime(appContext, bundleRoot, requiredRuntimeProfile)
    // Bundle-specific staging is an optimization, not an admission gate. A
    // device may still expose a coherent OEM/system QNN runtime, so preserve
    // the diagnostic but continue through generic runtime discovery and let
    // the real native graph load decide compatibility.
    // A downloaded image bundle may contain only graph/model artifacts. In
    // that case use the APK's pinned Qualcomm asset set, staged into the
    // app-private code cache. This is important on devices where the APK also
    // carries another HTP profile in nativeLibraryDir: passing that directory
    // alongside the selected profile lets the native resolver pick the wrong
    // (usually newer) Skel/Stub pair and hang while opening the context.
    val packagedQualcommRuntime = runCatching {
        LiteRtQualcommRuntimeStager.stageForImageBundle(
            context = appContext,
            bundleRuntimeAlreadyStaged = staged.runtime != null,
            qnnSdk = qnnSdkForPackagedImageRuntime(manifest)
        )
    }.getOrNull()
    val privateRuntimeDirectory = staged.runtime?.directory?.absolutePath
        ?: packagedQualcommRuntime?.directory?.absolutePath
    val fallback = listOf(
        File(appContext.filesDir, "qnnlibs").absolutePath,
        File(appContext.filesDir, "runtime_libs").absolutePath,
        "/vendor/lib64",
        "/vendor/lib/rfsa/adsp",
        "/odm/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/product/lib64"
    )
    val detectedRuntime = if (privateRuntimeDirectory == null) {
        runCatching { DeviceProfileReader(appContext).read().accelerationProfile.qnnRuntime }
            .getOrNull()
    } else {
        // Do not probe another host library after a coherent stage has been
        // selected. QNN keeps process-level state after dlopen, so probing a
        // stale app/OEM profile here could poison the exact staged graph load.
        null
    }
    val packagedNativeDirectory = appContext.applicationInfo.nativeLibraryDir
        .let(::File)
        .canonicalPath
    val detectedSearchDirectories = detectedRuntime?.searchDirectories.orEmpty().filterNot { path ->
        runCatching { File(path).canonicalPath == packagedNativeDirectory }.getOrDefault(false)
    }
    return QnnRuntimeDirectoryResolution(
        directories = qnnRuntimeDirectoriesForResolvedRuntime(
            privateRuntimeDirectory = privateRuntimeDirectory,
            packagedNativeDirectory = appContext.applicationInfo.nativeLibraryDir,
            fallbackDirectories = fallback,
            detectedSearchDirectories = detectedSearchDirectories
        ),
        stagedRuntime = staged.runtime,
        stagingError = staged.error
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

internal fun qnnImageVerificationStampFor(
    context: Context,
    bundleRoot: File,
    execution: QnnImageExecutionEvidence? = null
): String {
    val appContext = context.applicationContext
    val bundle = QnnImageBundleIdentity.fromDirectory(bundleRoot)
    require(bundle.status == QnnImageBundleIdentityStatus.AVAILABLE) {
        "QNN image bundle is unavailable for verification."
    }
    return QnnImageVerificationStamp.create(
        device = currentQnnImageDeviceIdentity(appContext),
        runtime = currentQnnImageRuntimeIdentity(appContext, bundleRoot),
        bundleDirectory = bundleRoot,
        execution = execution
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
        qnnImageNativeRuntimeFingerprint(qnnRuntimeIdentityJson(NativeQnnBridge().inspectRuntime(runtimeDirsJson)))
    }.getOrDefault("unavailable")
    return QnnImageRuntimeIdentity(
        app = appIdentity,
        nativeRuntime = nativeRuntime,
        apkSha256 = runCatching { File(context.applicationInfo.sourceDir).sha256Contents() }.getOrNull()
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

internal fun sdxlQnnConditioningGraphSha256(conditioningRoot: File): String {
    val executionAssetNames = buildList {
        add(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.first())
        File(conditioningRoot, SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME)
            .takeIf { file -> file.isFile && file.length() > 0L }
            ?.let { add(SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME) }
        addAll(SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES.drop(1))
    }
    val payload = executionAssetNames.joinToString(
        separator = "\n",
        postfix = "\n"
    ) { name ->
        val asset = File(conditioningRoot, name)
        require(asset.isFile && asset.length() > 0L) {
            "MNN SDXL conditioning execution asset is missing or empty: ${asset.absolutePath}"
        }
        "$name=${asset.sha256Contents()}"
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte ->
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
        !hasLegacyQwenMemoryEstimateFailure() &&
        localImageStructuralReadinessMessage() == null

/** Old APKs persisted this preflight estimate as FAILED before calling the native graph. */
private fun LocalImageModelRecord.hasLegacyQwenMemoryEstimateFailure(): Boolean =
    runtime == LocalImageRuntime.MNN_DIFFUSION &&
        verificationMessage.trim().startsWith("Qwen-Image-2.1 预计需要约 ") &&
        verificationMessage.contains("可用内存，但当前只有约")

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
        LocalImageVerificationStatus.FAILED -> if (hasLegacyQwenMemoryEstimateFailure()) {
            "旧版本曾因内存估算中止准备，这不是真实推理失败。现已允许按所选尺寸重新运行，以本次实际分配和执行结果为准。"
        } else if (hasCurrentLocalImageExecutionFailure()) {
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
    val primary = File(path)
    if (path.isBlank() || !primary.isFile) {
        return "本地图像生成模型主文件不存在，请重新下载或重新导入。"
    }
    if (!primary.canRead() || primary.length() <= 0L) {
        return "本地图像生成模型主文件为空或不可读，请重新下载或重新导入。"
    }
    val declaredRoot = bundleRoot?.takeIf(String::isNotBlank)?.let(::File)
    if (declaredRoot != null && (!declaredRoot.isDirectory || !declaredRoot.canRead())) {
        return "本地图像生成模型组件目录不可读或不存在，请重新下载或重新导入完整模型包。"
    }
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
    val primaryCanonicalPath = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val files = root.walkTopDown()
        .filter { it.isFile }
        .filter { it.extension.lowercase() in READINESS_MODEL_EXTENSIONS }
        .filterNot {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == primaryCanonicalPath
        }
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
            LocalImageVerificationStatus.UNKNOWN -> "已安装·待首次运行"
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
            LocalImageVerificationStatus.UNKNOWN -> "已安装·待首次运行"
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
    val manifest = when (val inspection = inspectLocalImageBundleManifestFromRoot(root)) {
        LocalImageBundleManifestInspection.Undeclared -> null
        is LocalImageBundleManifestInspection.Ready -> inspection.manifest
        is LocalImageBundleManifestInspection.Invalid ->
            return "QNN image bundle manifest is invalid: ${inspection.message}"
    }
    qnnRequiredBundleRuntimeReadinessMessage(root, manifest?.requiredRuntimeProfile)?.let { return it }
    val requiresControlNet = manifest?.task == "CONTROL_IMAGE" ||
        manifest?.id.orEmpty().contains("controlnet", ignoreCase = true)
    if (manifest != null) {
        when (val contract = manifest.resolveRuntimeComponentContract(root)) {
            is LocalImageRuntimeComponentContract.Invalid ->
                return "QNN image bundle runtime contract is invalid: ${contract.message}"
            is LocalImageRuntimeComponentContract.Ready -> {
                if (contract.missingPaths.isNotEmpty()) {
                    return "QNN image bundle manifest requires missing or empty components: " +
                        contract.missingPaths.joinToString(", ") + "."
                }
                return if (requiresControlNet && root.nonEmptyQnnContextPath("controlnet.bin") == null) {
                    "QNN image bundle is incomplete: controlnet.bin."
                } else {
                    null
                }
            }
            LocalImageRuntimeComponentContract.UndeclaredLegacy -> Unit
        }
    }
    val files = root.walkTopDown().filter { it.isFile }.toList()
    val names = files.map { it.invariantSeparatorsPath.lowercase() }
    fun hasAny(vararg tokens: String): Boolean = names.any { name -> tokens.any { it in name } }
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
    if (isQwenImage21MnnModel()) {
        val primary = File(path)
        return LocalImageBundleContract.inspectQwenImage21Bundle(
            bundleRoot = bundleRoot?.let(::File),
            primaryFile = primary
        ).readinessMessage()
    }
    val effectiveFamily = resolvedMnnFamily()
    return LocalImageBundleContract.inspectMnnBundle(
        bundleRoot = bundleRoot?.let(::File),
        primaryFile = File(path),
        family = effectiveFamily
    ).readinessMessage(effectiveFamily)
}

private fun LocalImageModelRecord.isQwenImage21MnnModel(): Boolean {
    if (runtime != LocalImageRuntime.MNN_DIFFUSION || family != LocalImageModelFamily.QWEN_IMAGE) return false
    if (recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID) return true
    val roots = listOfNotNull(bundleRoot?.takeIf(String::isNotBlank)?.let(::File), File(path).parentFile)
        .distinctBy { root -> runCatching { root.canonicalPath }.getOrDefault(root.absolutePath) }
    return roots.any { root ->
        runCatching { localImageBundleManifestFromRoot(root) }
            .getOrNull()
            ?.let { manifest ->
                manifest.id == QWEN_IMAGE_21_RECOMMENDATION_ID ||
                    manifest.recommendationId == QWEN_IMAGE_21_RECOMMENDATION_ID
            } == true
    }
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

private fun inferLocalImageRuntimeForBundle(root: File, primary: File, discovery: Boolean = false): LocalImageRuntime =
    when {
        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any {
            it.isFile && (
                listOf("qnn", "qairt", "htp").any { token -> token in it.invariantSeparatorsPath.lowercase() } ||
                    it.name.endsWith(".ctx", ignoreCase = true) ||
                    it.name.equals("unet.bin", ignoreCase = true) &&
                        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any { sibling -> sibling.name.contains("clip", true) }
            )
        } -> LocalImageRuntime.QNN_HTP
        primary.extension.equals("mnn", ignoreCase = true) -> LocalImageRuntime.MNN_DIFFUSION
        (if (discovery) imageDiscoveryWalk(root) else root.walkTopDown()).any { it.isFile && it.extension.equals("mnn", ignoreCase = true) } -> LocalImageRuntime.MNN_DIFFUSION
        else -> LocalImageRuntime.infer(primary.name)
    }

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

/**
 * Returns whether an imported bundle contains the complete dual-CLIP SDXL
 * conditioning contract in one safe directory. This is intentionally a
 * package-layout check, not a device capability check.
 */
internal fun hasCompleteSdxlQnnConditioningBundle(bundleRoot: File): Boolean =
    runCatching { resolveSdxlQnnConditioningRoot(bundleRoot) }.isSuccess

private fun File.hasCompleteSdxlQnnConditioningAssets(): Boolean {
    val directory = runCatching { canonicalFile }.getOrNull() ?: return false
    return SDXL_QNN_CONDITIONING_ASSET_NAMES.all { name ->
        val asset = runCatching { File(directory, name).canonicalFile }.getOrNull()
        asset != null &&
            asset.parentFile == directory &&
            asset.isFile &&
            asset.length() > 0L
    }
}

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

private const val SDXL_QNN_CONDITIONING_GRAPH_EVIDENCE = "clip.mnn+clip_2.mnn"
private val SDXL_QNN_CONDITIONING_REQUIRED_EXECUTION_ASSET_NAMES = listOf(
    "clip.mnn",
    "clip_2.mnn",
    "clip_2.mnn.weight"
)
private const val SDXL_QNN_OPTIONAL_CLIP1_WEIGHT_NAME = "clip.mnn.weight"

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
