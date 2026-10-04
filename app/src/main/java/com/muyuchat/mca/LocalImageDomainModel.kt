package com.muyuchat.mca

import com.muyuchat.core.download.ImageEngineMinDeviceTier
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * The local-image domain model (P5/L5). Moved verbatim out of LocalImageProvider.kt: backend and
 * runtime enums, model-family/verification enums, bundle contract records, the model record, and
 * the generation/result/progress/options records. Same package and visibilities, so the provider,
 * model store, UI and tests are unchanged.
 */

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
