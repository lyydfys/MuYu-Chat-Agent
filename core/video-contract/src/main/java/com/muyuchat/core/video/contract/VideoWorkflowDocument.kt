package com.muyuchat.core.video.contract

/**
 * The workflow document (proposal §4.1). A saved revision is immutable: editing produces a new
 * [VideoRevisionId]. A run always binds one immutable revision snapshot; the editable draft
 * never feeds an in-flight run.
 *
 * Structural rules enforced by [validateVideoWorkflow]:
 * - node ids unique, edge endpoints exist, no self-loops, no cycles (DAG),
 * - every connected input port exists on both endpoints and the port kinds match,
 * - literal parameter values match the declaring node type's schema and bounds.
 *
 * UI-only state ([VideoNodeUi]) is excluded from the executable digest ([VideoWorkflowCanon]);
 * seed, geometry, sampler values and input references are always part of it.
 */
const val VIDEO_WORKFLOW_SCHEMA_VERSION = 1

/** Where a document came from. MCA-native graphs are authored in-app; Comfy graphs are imported. */
enum class VideoWorkflowSource {
    MCA_NATIVE,
    COMFY_API,
}

/** Literal parameter value stored on a node. The node type's schema decides which names accept which kind. */
sealed class VideoNodeValue {
    data class TextValue(val text: String) : VideoNodeValue()

    /** Integer-valued parameters (width/height/frames/seed/steps/fps). */
    data class LongValue(val value: Long) : VideoNodeValue()

    /** Decimal parameters (guidance/flow shift). Serialized canonically; never floats. */
    data class DecimalValue(val unscaled: Long, val scale: Int) : VideoNodeValue()

    /** Reference to a validated image asset owned by the media repository. */
    data class ImageAssetRefValue(val assetId: String) : VideoNodeValue()

    /** Manifest-registered model identity, e.g. `wan2.2-ti2v-5b`. Never a bare file path. */
    data class ModelRefValue(val modelId: String) : VideoNodeValue()
}

/** Non-executable canvas state. Deliberately excluded from the canonical executable digest. */
data class VideoNodeUi(
    val positionX: Float? = null,
    val positionY: Float? = null,
    val collapsed: Boolean = false,
    val label: String? = null,
)

data class VideoNode(
    val nodeId: String,
    val typeId: String,
    val typeVersion: Int,
    val params: Map<String, VideoNodeValue> = emptyMap(),
    val ui: VideoNodeUi = VideoNodeUi(),
) {
    init {
        require(nodeId.isNotEmpty()) { "node id must not be empty" }
        require(typeId.isNotEmpty()) { "node type id must not be empty" }
        require(typeVersion >= 1) { "node type version must be >= 1" }
    }
}

data class VideoEdge(
    val fromNodeId: String,
    val fromPort: String,
    val toNodeId: String,
    val toPort: String,
) {
    init {
        require(fromNodeId.isNotEmpty() && toNodeId.isNotEmpty()) { "edge endpoints must not be empty" }
        require(fromPort.isNotEmpty() && toPort.isNotEmpty()) { "edge ports must not be empty" }
    }
}

data class VideoWorkflowDocument(
    val workflowId: VideoWorkflowId,
    val revisionId: VideoRevisionId,
    val schemaVersion: Int = VIDEO_WORKFLOW_SCHEMA_VERSION,
    val source: VideoWorkflowSource = VideoWorkflowSource.MCA_NATIVE,
    val nodes: List<VideoNode>,
    val edges: List<VideoEdge> = emptyList(),
    /** Non-executable display metadata (title, notes). Not part of the digest. */
    val metadata: Map<String, String> = emptyMap(),
    /**
     * Optional opaque reference to the raw imported source (SAF/document id or repository key).
     * Never a filesystem path and never executable; Comfy API imports keep their original JSON
     * here. The reference does not enter the digest — the canonical graph does.
     */
    val rawSourceRef: String? = null,
    val canonicalSha256: String? = null,
) {
    init {
        require(schemaVersion in 1..VIDEO_WORKFLOW_SCHEMA_VERSION) {
            "unsupported workflow schema version $schemaVersion"
        }
    }

    fun node(nodeId: String): VideoNode? = nodes.firstOrNull { it.nodeId == nodeId }
}
