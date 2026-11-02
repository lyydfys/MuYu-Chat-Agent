package com.muyuchat.core.video.contract

/**
 * The immutable execution snapshot a run binds at submit time (proposal §4.3/§4.3.1).
 *
 * The owner freezes this BEFORE inserting the QUEUED run row: revision digest, resolved input
 * references, the randomly selected seed, resolved parameters and the model/profile
 * fingerprints. The running pipeline reads ONLY this snapshot — never the editable draft, never
 * "current" model state. Retries reference the same snapshot unless a new one is explicitly
 * built (a new seed requires a new snapshot, recorded as such).
 */
data class VideoRunSnapshot(
    val workflowId: VideoWorkflowId,
    val revisionId: VideoRevisionId,
    val canonicalSha256: String,
    /** Input bindings resolved at freeze time: node id → validated asset/image reference. */
    val inputRefs: Map<String, VideoInputBinding>,
    /** Requested (user-visible) values as stored in the revision. */
    val requestedParams: VideoResolvedParams,
    /** Resolved values actually permitted by rules (e.g. Wan frame alignment). */
    val resolvedParams: VideoResolvedParams,
    /** What the native layer echoes back after the run (filled at completion; null before). */
    val nativeEffectiveParams: VideoResolvedParams? = null,
    val modelFingerprint: String,
    val profileFingerprint: String,
    val backend: VideoBackendKind,
)

/** One validated input reference frozen into a snapshot. */
data class VideoInputBinding(
    val nodeId: String,
    val kind: VideoPortKind,
    /** Repository-owned content reference (never a raw filesystem path). */
    val reference: String,
    val sha256: String? = null,
)

/**
 * Selectable local video execution engines, mirroring the pinned native request contract.
 *
 * [DEFAULT] is the built-in MCA pipeline and is represented by an ABSENT `engine` field in the
 * native request body, so a run that does not choose an engine produces exactly the same bytes as
 * before the field existed. Every other value names a concrete alternate backend and is written
 * into the request body verbatim.
 */
object VideoLocalEngine {
    /** Built-in MCA engine; the native request omits the `engine` field entirely. */
    const val DEFAULT: String = ""

    /** External stable-diffusion.cpp backend loaded by the native video bridge. */
    const val SDCPP_EXTERNAL: String = "sdcpp_external"

    /** Values the native bridge accepts; anything else is rejected before dispatch. */
    val supported: Set<String> = setOf(DEFAULT, SDCPP_EXTERNAL)

    fun isSupported(value: String): Boolean = value in supported
}

/**
 * Backend selection for the external stable-diffusion.cpp video engine.
 *
 * Only meaningful together with [VideoLocalEngine.SDCPP_EXTERNAL]; the built-in MCA engine never
 * receives the field. [HTP] is the production default; [CPU] is the compatibility fallback for
 * platforms that cannot load an unsigned HTP PD.
 */
object VideoLocalBackend {
    const val HTP: String = "htp"
    const val CPU: String = "cpu"

    val supported: Set<String> = setOf(HTP, CPU)

    fun isSupported(value: String): Boolean = value in supported
}

/**
 * The parameter projection that must stay identical between request, snapshot, native call and
 * persisted record. Mirrors the Wan TI2V-5B surface of the pinned native ABI (§6.5):
 * `4k+1` frame counts, 32-pixel geometry multiples, integer fps.
 */
data class VideoResolvedParams(
    val width: Long,
    val height: Long,
    val frames: Long,
    val fps: Long,
    val steps: Long,
    val sampler: String,
    /** Guidance as decimal (unscaled, scale) — never a float. */
    val guidance: VideoNodeValue.DecimalValue,
    val flowShift: VideoNodeValue.DecimalValue,
    val seed: Long,
    val promptSha256: String,
    val negativePromptSha256: String?,
    val imageRef: VideoInputBinding?,
    /** [VideoLocalEngine.DEFAULT] keeps the built-in engine; see [VideoLocalEngine]. */
    val engine: String = VideoLocalEngine.DEFAULT,
    /** External-engine backend (see [VideoLocalBackend]); frozen per run for reproducibility. */
    val externalBackend: String = VideoLocalBackend.HTP,
    /**
     * Distillation LoRA weights frozen with the run. Only the external engine consumes them; the
     * list is empty for every built-in-engine run, so its native request stays byte-identical.
     */
    val loraSpecs: List<VideoLoraBinding> = emptyList(),
) {
    init {
        require(width > 0 && width % 32 == 0L) { "width must be a positive multiple of 32" }
        require(height > 0 && height % 32 == 0L) { "height must be a positive multiple of 32" }
        require(isWanAlignedFrameCount(frames)) { "frames must be Wan-aligned (4k+1)" }
        require(fps > 0) { "fps must be a positive integer" }
        require(steps > 0) { "steps must be positive" }
        require(VideoLocalEngine.isSupported(engine)) { "unsupported video engine '$engine'" }
        require(VideoLocalBackend.isSupported(externalBackend)) {
            "unsupported video backend '$externalBackend'"
        }
        require(loraSpecs.size <= VideoLoraBinding.MAX_ENTRIES) {
            "at most ${VideoLoraBinding.MAX_ENTRIES} LoRAs per run"
        }
        require(loraSpecs.isEmpty() || engine != VideoLocalEngine.DEFAULT) {
            "LoRA weights require the external engine"
        }
    }
}

/**
 * One LoRA weight file applied to the diffusion model, as frozen into the run snapshot.
 *
 * The path must already be a staged, canonical child of the model root: the native layer repeats
 * that check and rejects anything else with `INPUT_INVALID` rather than loading it.
 */
data class VideoLoraBinding(
    val path: String,
    val weight: Double = 1.0,
) {
    init {
        require(path.isNotBlank()) { "LoRA path must not be blank" }
        require(weight.isFinite() && weight > 0.0 && weight <= MAX_WEIGHT) {
            "LoRA weight must be in (0, $MAX_WEIGHT]"
        }
    }

    companion object {
        const val MAX_ENTRIES = 16
        const val MAX_WEIGHT = 8.0
    }
}

/** Execution backend of a run. Local native and remote ComfyUI are explicitly separate. */
enum class VideoBackendKind {
    /** MCA local pipeline: sd-native generate_video + MediaCodec. */
    LOCAL_NATIVE,

    /** Remote ComfyUI server, explicitly selected by the user (V3). */
    REMOTE_COMFY,

    /** Fake/test adapter. Development and tests only; never a production capability claim. */
    FAKE,
}
