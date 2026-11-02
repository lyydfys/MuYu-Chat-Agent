package com.muyuchat.feature.video.workspace

import com.muyuchat.core.video.contract.VideoEdge
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeUi
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowId
import com.muyuchat.core.video.contract.VideoWorkflowSource
import com.muyuchat.core.video.contract.isWanAlignedFrameCount

/**
 * Template builders for the two V1 workflows (proposal §4.2/§9.2). Templates are ordinary
 * documents — the same types, the same validation, the same compiler input as hand-edited
 * graphs; nothing about a template is static-decoration.
 *
 * Defaults are the shipping product recipe: euler, guidance 6.0, flow shift 3.0, 4 steps,
 * 24 fps, 5 frames (Wan-aligned smoke length), 480x832. The sampler and flow-shift values come from the pinned sd.cpp Wan
 * baseline; the step count and frame rate follow the product target. Geometry/frame rules are
 * enforced by the shared contract validator; template values satisfy them by construction.
 */
object VideoWorkflowTemplates {

    const val DEFAULT_WIDTH = 480L
    const val DEFAULT_HEIGHT = 832L
    const val DEFAULT_FRAMES = 5L
    const val DEFAULT_FPS = 24L
    const val DEFAULT_STEPS = 4L
    const val DEFAULT_GUIDANCE_UNSCALED = 60L
    const val DEFAULT_GUIDANCE_SCALE = 1
    const val DEFAULT_FLOW_SHIFT_UNSCALED = 30L
    const val DEFAULT_FLOW_SHIFT_SCALE = 1

    fun textToVideoTemplate(
        workflowId: String = "wf-native-t2v",
        revisionId: String = "rev-native-t2v-1",
        prompt: String = "",
        negativePrompt: String = "",
        width: Long = DEFAULT_WIDTH,
        height: Long = DEFAULT_HEIGHT,
        frames: Long = DEFAULT_FRAMES,
        seed: Long = 0L,
    ): VideoWorkflowDocument {
        require(isWanAlignedFrameCount(frames)) { "template frames must be Wan-aligned" }
        val nodes = listOf(
            inputTextNode("n_input", prompt, negativePrompt),
            modelNode("n_model"),
            conditioningNode("n_cond", width, height, frames, seed),
            samplerNode("n_sampler"),
            decodeNode("n_decode"),
            encodeNode("n_encode"),
            saveNode("n_save"),
        )
        return documentOf(workflowId, revisionId, nodes, baseEdges())
    }

    fun imageToVideoTemplate(
        workflowId: String = "wf-native-i2v",
        revisionId: String = "rev-native-i2v-1",
        prompt: String = "",
        negativePrompt: String = "",
        imageAssetId: String? = null,
        width: Long = DEFAULT_WIDTH,
        height: Long = DEFAULT_HEIGHT,
        frames: Long = DEFAULT_FRAMES,
        seed: Long = 0L,
    ): VideoWorkflowDocument {
        val base = textToVideoTemplate(workflowId, revisionId, prompt, negativePrompt, width, height, frames, seed)
        val imageNode = VideoNode(
            nodeId = "n_image",
            typeId = VideoNodeTypeId.INPUT_IMAGE.typeId,
            typeVersion = 1,
            params = if (imageAssetId != null) {
                mapOf("image" to VideoNodeValue.ImageAssetRefValue(imageAssetId))
            } else {
                emptyMap()
            },
            ui = VideoNodeUi(positionX = 40f, positionY = 300f),
        )
        return base.copy(
            nodes = base.nodes + imageNode,
            edges = base.edges + VideoEdge("n_image", "image", "n_cond", "image"),
        )
    }

    fun inputTextNode(nodeId: String, prompt: String, negativePrompt: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.INPUT_TEXT.typeId,
        typeVersion = 1,
        params = buildMap {
            put("prompt", VideoNodeValue.TextValue(prompt))
            if (negativePrompt.isNotEmpty()) {
                put("negative_prompt", VideoNodeValue.TextValue(negativePrompt))
            }
        },
        ui = VideoNodeUi(positionX = 40f, positionY = 60f),
    )

    fun modelNode(nodeId: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.WAN22_TI2V_5B_MODEL.typeId,
        typeVersion = 1,
        params = mapOf("manifest" to VideoNodeValue.ModelRefValue("wan2.2-ti2v-5b")),
        ui = VideoNodeUi(positionX = 330f, positionY = 160f),
    )

    fun conditioningNode(nodeId: String, width: Long, height: Long, frames: Long, seed: Long): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.WAN_CONDITIONING.typeId,
        typeVersion = 1,
        params = mapOf(
            "width" to VideoNodeValue.LongValue(width),
            "height" to VideoNodeValue.LongValue(height),
            "frames" to VideoNodeValue.LongValue(frames),
            "seed" to VideoNodeValue.LongValue(seed),
        ),
        ui = VideoNodeUi(positionX = 620f, positionY = 60f),
    )

    fun samplerNode(nodeId: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.WAN_SAMPLER.typeId,
        typeVersion = 1,
        params = mapOf(
            "steps" to VideoNodeValue.LongValue(DEFAULT_STEPS),
            "sampler" to VideoNodeValue.TextValue("euler"),
            "guidance" to VideoNodeValue.DecimalValue(DEFAULT_GUIDANCE_UNSCALED, DEFAULT_GUIDANCE_SCALE),
            "flow_shift" to VideoNodeValue.DecimalValue(DEFAULT_FLOW_SHIFT_UNSCALED, DEFAULT_FLOW_SHIFT_SCALE),
        ),
        ui = VideoNodeUi(positionX = 910f, positionY = 60f),
    )

    fun decodeNode(nodeId: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.WAN_DECODE.typeId,
        typeVersion = 1,
        ui = VideoNodeUi(positionX = 1200f, positionY = 60f),
    )

    fun encodeNode(nodeId: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.ENCODE_MP4.typeId,
        typeVersion = 1,
        params = mapOf("fps" to VideoNodeValue.LongValue(DEFAULT_FPS)),
        ui = VideoNodeUi(positionX = 1490f, positionY = 60f),
    )

    fun saveNode(nodeId: String): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = VideoNodeTypeId.SAVE_ASSET.typeId,
        typeVersion = 1,
        ui = VideoNodeUi(positionX = 1780f, positionY = 60f),
    )

    private fun baseEdges(): List<VideoEdge> = listOf(
        VideoEdge("n_input", "prompt", "n_cond", "prompt"),
        VideoEdge("n_model", "dit", "n_cond", "dit"),
        VideoEdge("n_cond", "conditioning", "n_sampler", "conditioning"),
        VideoEdge("n_model", "dit", "n_sampler", "dit"),
        VideoEdge("n_sampler", "latent", "n_decode", "latent"),
        VideoEdge("n_model", "vae", "n_decode", "vae"),
        VideoEdge("n_decode", "frames", "n_encode", "frames"),
        VideoEdge("n_encode", "mp4", "n_save", "mp4"),
    )

    private fun documentOf(
        workflowId: String,
        revisionId: String,
        nodes: List<VideoNode>,
        edges: List<VideoEdge>,
    ): VideoWorkflowDocument = VideoWorkflowDocument(
        workflowId = VideoWorkflowId(workflowId),
        revisionId = VideoRevisionId(revisionId),
        source = VideoWorkflowSource.MCA_NATIVE,
        nodes = nodes,
        edges = edges,
    )
}
