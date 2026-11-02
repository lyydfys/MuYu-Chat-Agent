package com.muyuchat.api.local

import com.muyuchat.core.video.contract.VideoLocalBackend
import com.muyuchat.core.video.contract.VideoLocalEngine
import com.muyuchat.core.video.contract.isWanAlignedFrameCount
import org.json.JSONObject

/**
 * The authenticated `POST /v1/video` request contract.
 *
 * Strict by construction: unknown fields are rejected instead of ignored, `engine` accepts only
 * the values the native bridge implements, and geometry/frames follow the same Wan rules the
 * workflow compiler enforces. The handler parses once for early rejection; the app-owned provider
 * parses the same raw body again so its own contract tests never depend on the socket layer.
 */
object VideoGenerationApiContract {

    /** Prompts longer than this are rejected before any graph is built. */
    const val MAX_PROMPT_CHARS = 4_096

    private const val MIN_DIMENSION = 32L
    private const val MAX_DIMENSION = 4_096L
    private const val MAX_FRAMES = 1_024L
    private const val MIN_FPS = 1L
    private const val MAX_FPS = 120L
    private const val MIN_STEPS = 1L
    private const val MAX_STEPS = 200L

    internal val REQUEST_FIELDS = setOf(
        "prompt",
        "width",
        "height",
        "frames",
        "fps",
        "steps",
        "seed",
        "guidance",
        "engine",
        "backend",
        "ditPath",
        "vaePath",
        "tePath",
        "loras",
    )

    fun parseRequest(rawBody: String): VideoGenerationApiRequest {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            throw VideoGenerationContractException(
                "invalid_json",
                "Request body must be a JSON object.",
            )
        }
        rejectUnknownFields(root)

        val prompt = requiredString(root, "prompt")
            .trim()
        if (prompt.isEmpty()) {
            throw VideoGenerationContractException("invalid_prompt", "prompt is required.")
        }
        if (prompt.length > MAX_PROMPT_CHARS) {
            throw VideoGenerationContractException(
                "prompt_too_long",
                "prompt must be at most $MAX_PROMPT_CHARS characters.",
                httpStatus = 422,
            )
        }
        val width = requiredLong(root, "width", MIN_DIMENSION, MAX_DIMENSION, "invalid_size")
        val height = requiredLong(root, "height", MIN_DIMENSION, MAX_DIMENSION, "invalid_size")
        if (width % 32L != 0L || height % 32L != 0L) {
            throw VideoGenerationContractException(
                "invalid_size",
                "width and height must be multiples of 32.",
                httpStatus = 422,
            )
        }
        val frames = requiredLong(root, "frames", 1L, MAX_FRAMES, "invalid_frames")
        if (!isWanAlignedFrameCount(frames)) {
            throw VideoGenerationContractException(
                "invalid_frames",
                "frames must be a Wan-aligned frame count (4k+1, for example 5 or 33).",
                httpStatus = 422,
            )
        }
        val fps = requiredLong(root, "fps", MIN_FPS, MAX_FPS, "invalid_fps")
        val steps = requiredLong(root, "steps", MIN_STEPS, MAX_STEPS, "invalid_steps")
        // Request-scoped sampler override. Absent keeps the model profile's frozen recipe.
        val guidance = optionalDouble(root, "guidance", 0.1, 20.0, "invalid_guidance")
        val seed = optionalLong(root, "seed") ?: 0L
        if (seed < 0L) {
            throw VideoGenerationContractException(
                "invalid_seed",
                "seed must be a non-negative integer.",
                httpStatus = 422,
            )
        }

        // Absent engine means the built-in MCA pipeline; it is never written into the native body.
        val engine = if (root.has("engine") && !root.isNull("engine")) {
            val raw = root.get("engine")
            raw as? String
                ?: throw VideoGenerationContractException(
                    "unsupported_video_engine",
                    "engine must be a string.",
                )
        } else {
            VideoLocalEngine.DEFAULT
        }.trim()
        if (!VideoLocalEngine.isSupported(engine)) {
            throw VideoGenerationContractException(
                "unsupported_video_engine",
                "engine must be \"\" (built-in MCA engine) or \"${VideoLocalEngine.SDCPP_EXTERNAL}\" " +
                    "(external stable-diffusion.cpp backend).",
            )
        }

        // External-engine backend. "htp" is the production default; "cpu" is the compatibility
        // path. The field is rejected without the external engine instead of being ignored.
        val backendSpecified = root.has("backend") && !root.isNull("backend")
        val backend = if (backendSpecified) {
            val raw = root.get("backend")
            (raw as? String)?.trim()
                ?: throw VideoGenerationContractException(
                    "unsupported_video_backend",
                    "backend must be a string.",
                )
        } else {
            VideoLocalBackend.HTP
        }
        if (!VideoLocalBackend.isSupported(backend)) {
            throw VideoGenerationContractException(
                "unsupported_video_backend",
                "backend must be \"${VideoLocalBackend.HTP}\" (default) or " +
                    "\"${VideoLocalBackend.CPU}\".",
            )
        }
        if (backendSpecified && engine != VideoLocalEngine.SDCPP_EXTERNAL) {
            throw VideoGenerationContractException(
                "backend_requires_external_engine",
                "backend is only meaningful with engine=\"${VideoLocalEngine.SDCPP_EXTERNAL}\"; " +
                    "the built-in MCA engine does not accept a backend selection.",
            )
        }

        val loraSpecs = parseLoraSpecs(root, engine)

        return VideoGenerationApiRequest(
            rawBody = rawBody,
            prompt = prompt,
            width = width,
            height = height,
            frames = frames,
            fps = fps,
            steps = steps,
            seed = seed,
            engine = engine,
            backend = backend,
            ditPath = optionalNonBlankString(root, "ditPath"),
            vaePath = optionalNonBlankString(root, "vaePath"),
            tePath = optionalNonBlankString(root, "tePath"),
            loraSpecs = loraSpecs,
            guidance = guidance,
        )
    }

    /**
     * Parses the optional `loras` array.
     *
     * Every field is required per entry (no silent weight of 1.0) and the array is only accepted
     * with the external engine, because the built-in engine has no LoRA application stage.
     */
    private fun parseLoraSpecs(
        root: JSONObject,
        engine: String,
    ): List<com.muyuchat.core.video.contract.VideoLoraBinding> {
        if (!root.has("loras") || root.isNull("loras")) return emptyList()
        val array = root.get("loras") as? org.json.JSONArray
            ?: throw VideoGenerationContractException("invalid_loras", "loras must be an array.")
        val maxEntries = com.muyuchat.core.video.contract.VideoLoraBinding.MAX_ENTRIES
        if (array.length() > maxEntries) {
            throw VideoGenerationContractException(
                "invalid_loras",
                "loras must contain at most $maxEntries entries.",
            )
        }
        val specs = ArrayList<com.muyuchat.core.video.contract.VideoLoraBinding>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i)
                ?: throw VideoGenerationContractException(
                    "invalid_loras",
                    "each loras entry must be an object.",
                )
            val path = entry.optString("path").trim()
            if (path.isEmpty()) {
                throw VideoGenerationContractException("invalid_loras", "a loras entry has no path.")
            }
            val maxWeight = com.muyuchat.core.video.contract.VideoLoraBinding.MAX_WEIGHT
            val weight = entry.optDouble("weight", Double.NaN)
            if (!weight.isFinite() || weight <= 0.0 || weight > maxWeight) {
                throw VideoGenerationContractException(
                    "invalid_loras",
                    "a loras entry weight must be in (0, $maxWeight].",
                )
            }
            specs.add(com.muyuchat.core.video.contract.VideoLoraBinding(path, weight))
        }
        if (specs.isNotEmpty() && engine != VideoLocalEngine.SDCPP_EXTERNAL) {
            throw VideoGenerationContractException(
                "loras_requires_external_engine",
                "loras is only meaningful with engine=\"${VideoLocalEngine.SDCPP_EXTERNAL}\"; " +
                    "the built-in MCA engine does not apply LoRA weights.",
            )
        }
        return specs
    }

    private fun rejectUnknownFields(root: JSONObject) {
        val unknown = root.keys().asSequence().filterNot(REQUEST_FIELDS::contains).toList()
        if (unknown.isNotEmpty()) {
            throw VideoGenerationContractException(
                "unsupported_parameter",
                "Unsupported request field(s): ${unknown.sorted().joinToString(", ")}. " +
                    "Accepted fields: ${REQUEST_FIELDS.sorted().joinToString(", ")}.",
            )
        }
    }

    private fun requiredString(root: JSONObject, name: String): String {
        if (!root.has(name) || root.isNull(name)) {
            throw VideoGenerationContractException(
                "invalid_prompt",
                "$name is required.",
            )
        }
        return root.get(name) as? String
            ?: throw VideoGenerationContractException(
                "invalid_prompt",
                "$name must be a string.",
            )
    }

    private fun optionalNonBlankString(root: JSONObject, name: String): String? {
        if (!root.has(name) || root.isNull(name)) return null
        val value = root.get(name) as? String
            ?: throw VideoGenerationContractException(
                "invalid_request",
                "$name must be a string when provided.",
            )
        if (value.isBlank()) {
            throw VideoGenerationContractException(
                "invalid_request",
                "$name must not be blank when provided.",
            )
        }
        return value
    }

    private fun requiredLong(root: JSONObject, name: String, min: Long, max: Long, code: String): Long {
        val value = optionalLong(root, name)
            ?: throw VideoGenerationContractException(code, "$name is required.", httpStatus = 422)
        if (value !in min..max) {
            throw VideoGenerationContractException(
                code,
                "$name must be between $min and $max.",
                httpStatus = 422,
            )
        }
        return value
    }

    private fun optionalDouble(root: JSONObject, name: String, min: Double, max: Double, code: String): Double? {
        if (!root.has(name) || root.isNull(name)) return null
        val value = (root.get(name) as? Number)?.toDouble()
            ?: throw VideoGenerationContractException(
                "invalid_request",
                "$name must be a number.",
            )
        if (!value.isFinite() || value < min || value > max) {
            throw VideoGenerationContractException(
                code,
                "$name must be between $min and $max.",
                httpStatus = 422,
            )
        }
        return value
    }

    private fun optionalLong(root: JSONObject, name: String): Long? {
        if (!root.has(name) || root.isNull(name)) return null
        val raw = root.get(name)
        val number = raw as? Number
            ?: throw VideoGenerationContractException(
                "invalid_request",
                "$name must be an integer.",
            )
        val value = number.toDouble()
        if (!value.isFinite() || value != Math.floor(value) || value < Long.MIN_VALUE.toDouble() ||
            value > Long.MAX_VALUE.toDouble()
        ) {
            throw VideoGenerationContractException(
                "invalid_request",
                "$name must be an integer.",
            )
        }
        return value.toLong()
    }
}

/** Parsed `POST /v1/video` request, mirroring the accepted wire fields. */
data class VideoGenerationApiRequest(
    val rawBody: String,
    val prompt: String,
    val width: Long,
    val height: Long,
    val frames: Long,
    val fps: Long,
    val steps: Long,
    val seed: Long,
    val engine: String,
    /** External-engine backend; [VideoLocalBackend.HTP] unless the caller selected `cpu`. */
    val backend: String,
    val ditPath: String?,
    val vaePath: String?,
    val tePath: String?,
    /** Distillation LoRA weights; empty unless the caller asked for them with the external engine. */
    val loraSpecs: List<com.muyuchat.core.video.contract.VideoLoraBinding> = emptyList(),
    /** Optional request-scoped guidance override; null keeps the model profile's frozen recipe. */
    val guidance: Double? = null,
)

/** Structured 4xx raised while parsing `/v1/video`; never a silent default. */
class VideoGenerationContractException(
    val code: String,
    override val message: String,
    val httpStatus: Int = 400,
) : IllegalArgumentException(message) {
    init {
        require(httpStatus in 400..499) { "video request rejections must use a 4xx status" }
    }
}

/** Structured failure raised by the app-owned video provider after the request parsed. */
class VideoGenerationProviderException(
    val code: String,
    val httpStatus: Int,
    override val message: String,
    val detailsJson: String = "{}",
    val retryAfterMs: Long = 0L,
) : IllegalStateException(message) {
    init {
        require(httpStatus in 400..599) { "video provider failures must use an HTTP error status" }
    }
}
