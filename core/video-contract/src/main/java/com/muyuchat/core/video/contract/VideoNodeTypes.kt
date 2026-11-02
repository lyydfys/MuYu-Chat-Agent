package com.muyuchat.core.video.contract

/**
 * Versioned node type registry for MCA-native video workflows (proposal §4.2).
 *
 * A node type is a frozen product contract: its typed input/output port declarations, its
 * literal parameter schema and its structural constraints. Adding a type or a port bumps the
 * type version; existing revisions keep validating against the version they were saved with.
 * Unsupported type ids are rejected by validation with WORKFLOW_UNSUPPORTED_NODE — never
 * silently dropped or substituted.
 *
 * The registry is deliberately a closed set for V1: the Wan2.2 TI2V-5B vertical pipeline only.
 * ComfyUI import (V2) maps onto these types; it cannot register arbitrary new ones.
 */
enum class VideoPortKind {
    /** Prompt text (positive or negative) flowing from an input node into conditioning. */
    TEXT,

    /** Reference to a validated local image asset owned by the media repository. */
    IMAGE_REF,

    /** Reference to a manifest-registered model component (DiT, VAE, text encoder). */
    MODEL_REF,

    /** Bundle of model conditioning inputs (text/image/size/frames/seed). */
    CONDITIONING,

    /** Sampled latent video produced by the sampler. */
    LATENT_VIDEO,

    /** Decoded RGB frame sequence. */
    RGB_FRAMES,

    /** Encoded, finalized MP4 staged for publishing. */
    ENCODED_VIDEO,

    /** Published media asset inside the MCA owned media root. */
    VIDEO_ASSET,
}

/** How a port of this kind may be fed. */
enum class VideoPortFeed {
    /** Only by an edge from another node's output port. */
    CONNECTED_ONLY,

    /** Only by a literal value stored on the node itself. */
    LITERAL_ONLY,

    /** Either form, but never both at once. */
    CONNECTED_OR_LITERAL,
}

/** One declared input port of a node type. */
data class VideoInputPortDecl(
    val name: String,
    val kind: VideoPortKind,
    val required: Boolean,
    val feed: VideoPortFeed,
)

/** One declared output port of a node type. */
data class VideoOutputPortDecl(
    val name: String,
    val kind: VideoPortKind,
)

/** One declared literal parameter of a node type, with its value kind and hard bounds. */
data class VideoParamDecl(
    val name: String,
    val kind: VideoParamKind,
    val required: Boolean,
    val minimum: Long? = null,
    val maximum: Long? = null,
)

enum class VideoParamKind {
    TEXT,
    LONG,
    DECIMAL,
    IMAGE_ASSET_REF,
    MODEL_REF_ID,
}

/**
 * The V1 native node types. [registry] is the single lookup table; validation and the workflow
 * compiler both read it and nowhere else.
 */
enum class VideoNodeTypeId(val typeId: String) {
    /** Literal prompt/negative-prompt source. */
    INPUT_TEXT("mca.video.input_text"),

    /** Literal validated image reference source (image-to-video). */
    INPUT_IMAGE("mca.video.input_image"),

    /** Wan2.2 TI2V-5B manifest reference; provides the DiT and the 5B VAE roles. */
    WAN22_TI2V_5B_MODEL("mca.video.wan22_ti2v_5b_model"),

    /** Text/image + geometry + seed → model conditioning. */
    WAN_CONDITIONING("mca.video.wan_conditioning"),

    /** Conditioned sampling; steps/sampler/guidance/flow shift. */
    WAN_SAMPLER("mca.video.wan_sampler"),

    /** Latent decode through the model's VAE role. */
    WAN_DECODE("mca.video.wan_decode"),

    /** RGB frames → staged MP4 (MediaCodec/MediaMuxer parameters). */
    ENCODE_MP4("mca.video.encode_mp4"),

    /** Publish staged MP4 into the owned media root. Execution output root of V1 graphs. */
    SAVE_ASSET("mca.video.save_asset");

    companion object {
        val byTypeId: Map<String, VideoNodeTypeId> = entries.associateBy { it.typeId }

        fun fromTypeId(typeId: String): VideoNodeTypeId? = byTypeId[typeId]
    }
}

/** Current version of each node type's port/parameter schema. */
object VideoNodeTypeRegistry {

    const val INPUT_TEXT_VERSION = 1
    const val INPUT_IMAGE_VERSION = 1
    const val WAN22_TI2V_5B_MODEL_VERSION = 1
    const val WAN_CONDITIONING_VERSION = 1
    const val WAN_SAMPLER_VERSION = 1
    const val WAN_DECODE_VERSION = 1
    const val ENCODE_MP4_VERSION = 1
    const val SAVE_ASSET_VERSION = 1

    fun currentVersion(type: VideoNodeTypeId): Int = when (type) {
        VideoNodeTypeId.INPUT_TEXT -> INPUT_TEXT_VERSION
        VideoNodeTypeId.INPUT_IMAGE -> INPUT_IMAGE_VERSION
        VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> WAN22_TI2V_5B_MODEL_VERSION
        VideoNodeTypeId.WAN_CONDITIONING -> WAN_CONDITIONING_VERSION
        VideoNodeTypeId.WAN_SAMPLER -> WAN_SAMPLER_VERSION
        VideoNodeTypeId.WAN_DECODE -> WAN_DECODE_VERSION
        VideoNodeTypeId.ENCODE_MP4 -> ENCODE_MP4_VERSION
        VideoNodeTypeId.SAVE_ASSET -> SAVE_ASSET_VERSION
    }

    fun inputs(type: VideoNodeTypeId, typeVersion: Int): List<VideoInputPortDecl> = when (type) {
        VideoNodeTypeId.INPUT_TEXT -> listOf(
            VideoInputPortDecl("prompt", VideoPortKind.TEXT, required = true, feed = VideoPortFeed.LITERAL_ONLY),
            VideoInputPortDecl("negative_prompt", VideoPortKind.TEXT, required = false, feed = VideoPortFeed.LITERAL_ONLY),
        )

        VideoNodeTypeId.INPUT_IMAGE -> listOf(
            VideoInputPortDecl("image", VideoPortKind.IMAGE_REF, required = true, feed = VideoPortFeed.LITERAL_ONLY),
        )

        VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> listOf(
            VideoInputPortDecl("manifest", VideoPortKind.MODEL_REF, required = true, feed = VideoPortFeed.LITERAL_ONLY),
        )

        VideoNodeTypeId.WAN_CONDITIONING -> listOf(
            VideoInputPortDecl("prompt", VideoPortKind.TEXT, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
            VideoInputPortDecl("negative_prompt", VideoPortKind.TEXT, required = false, feed = VideoPortFeed.CONNECTED_ONLY),
            VideoInputPortDecl("image", VideoPortKind.IMAGE_REF, required = false, feed = VideoPortFeed.CONNECTED_ONLY),
            VideoInputPortDecl("dit", VideoPortKind.MODEL_REF, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
        )

        VideoNodeTypeId.WAN_SAMPLER -> listOf(
            VideoInputPortDecl("conditioning", VideoPortKind.CONDITIONING, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
            VideoInputPortDecl("dit", VideoPortKind.MODEL_REF, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
        )

        VideoNodeTypeId.WAN_DECODE -> listOf(
            VideoInputPortDecl("latent", VideoPortKind.LATENT_VIDEO, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
            VideoInputPortDecl("vae", VideoPortKind.MODEL_REF, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
        )

        VideoNodeTypeId.ENCODE_MP4 -> listOf(
            VideoInputPortDecl("frames", VideoPortKind.RGB_FRAMES, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
        )

        VideoNodeTypeId.SAVE_ASSET -> listOf(
            VideoInputPortDecl("mp4", VideoPortKind.ENCODED_VIDEO, required = true, feed = VideoPortFeed.CONNECTED_ONLY),
        )
    }.also {
        require(typeVersion in 1..currentVersion(type)) {
            "unknown schema version $typeVersion for ${type.typeId}"
        }
    }

    fun outputs(type: VideoNodeTypeId, typeVersion: Int): List<VideoOutputPortDecl> = when (type) {
        VideoNodeTypeId.INPUT_TEXT -> listOf(
            VideoOutputPortDecl("prompt", VideoPortKind.TEXT),
            VideoOutputPortDecl("negative_prompt", VideoPortKind.TEXT),
        )

        VideoNodeTypeId.INPUT_IMAGE -> listOf(
            VideoOutputPortDecl("image", VideoPortKind.IMAGE_REF),
        )

        VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> listOf(
            VideoOutputPortDecl("dit", VideoPortKind.MODEL_REF),
            VideoOutputPortDecl("vae", VideoPortKind.MODEL_REF),
        )

        VideoNodeTypeId.WAN_CONDITIONING -> listOf(
            VideoOutputPortDecl("conditioning", VideoPortKind.CONDITIONING),
        )

        VideoNodeTypeId.WAN_SAMPLER -> listOf(
            VideoOutputPortDecl("latent", VideoPortKind.LATENT_VIDEO),
        )

        VideoNodeTypeId.WAN_DECODE -> listOf(
            VideoOutputPortDecl("frames", VideoPortKind.RGB_FRAMES),
        )

        VideoNodeTypeId.ENCODE_MP4 -> listOf(
            VideoOutputPortDecl("mp4", VideoPortKind.ENCODED_VIDEO),
        )

        VideoNodeTypeId.SAVE_ASSET -> listOf(
            VideoOutputPortDecl("asset", VideoPortKind.VIDEO_ASSET),
        )
    }.also {
        require(typeVersion in 1..currentVersion(type)) {
            "unknown schema version $typeVersion for ${type.typeId}"
        }
    }

    /**
     * Literal parameter schema per node type. Values are validated against these declarations;
     * unknown parameter names are REJECTED (never accepted-then-ignored, §6.5).
     */
    fun params(type: VideoNodeTypeId, typeVersion: Int): List<VideoParamDecl> = when (type) {
        VideoNodeTypeId.INPUT_TEXT,
        VideoNodeTypeId.INPUT_IMAGE,
        VideoNodeTypeId.WAN22_TI2V_5B_MODEL,
        VideoNodeTypeId.WAN_DECODE,
        VideoNodeTypeId.SAVE_ASSET,
        -> emptyList()

        VideoNodeTypeId.WAN_CONDITIONING -> listOf(
            VideoParamDecl("width", VideoParamKind.LONG, required = true, minimum = 32),
            VideoParamDecl("height", VideoParamKind.LONG, required = true, minimum = 32),
            VideoParamDecl("frames", VideoParamKind.LONG, required = true, minimum = 1),
            VideoParamDecl("seed", VideoParamKind.LONG, required = true),
        )

        VideoNodeTypeId.WAN_SAMPLER -> listOf(
            VideoParamDecl("steps", VideoParamKind.LONG, required = true, minimum = 1),
            VideoParamDecl("sampler", VideoParamKind.TEXT, required = true),
            VideoParamDecl("guidance", VideoParamKind.DECIMAL, required = true),
            VideoParamDecl("flow_shift", VideoParamKind.DECIMAL, required = true),
        )

        VideoNodeTypeId.ENCODE_MP4 -> listOf(
            VideoParamDecl("fps", VideoParamKind.LONG, required = true, minimum = 1),
        )
    }.also {
        require(typeVersion in 1..currentVersion(type)) {
            "unknown schema version $typeVersion for ${type.typeId}"
        }
    }
}
