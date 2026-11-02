package com.muyuchat.core.video.contract

/** Shared fixture: one fully valid Wan TI2V-5B text-to-video graph, plus mutation helpers. */

fun textToVideoDocument(
    workflowId: String = "wf-test",
    revisionId: String = "rev-test-1",
    prompt: String = "a cat walking on a beach",
    negativePrompt: String? = "blurry",
    seed: Long = 42L,
    width: Long = 832L,
    height: Long = 480L,
    frames: Long = 81L,
    steps: Long = 20L,
    fps: Long = 16L,
    guidance: VideoNodeValue.DecimalValue = VideoNodeValue.DecimalValue(50, 1),
): VideoWorkflowDocument {
    val inputParams = buildMap {
        put("prompt", VideoNodeValue.TextValue(prompt))
        if (negativePrompt != null) put("negative_prompt", VideoNodeValue.TextValue(negativePrompt))
    }
    val nodes = listOf(
        VideoNode(
            nodeId = "n_input",
            typeId = VideoNodeTypeId.INPUT_TEXT.typeId,
            typeVersion = 1,
            params = inputParams,
        ),
        VideoNode(
            nodeId = "n_model",
            typeId = VideoNodeTypeId.WAN22_TI2V_5B_MODEL.typeId,
            typeVersion = 1,
            params = mapOf("manifest" to VideoNodeValue.ModelRefValue("wan2.2-ti2v-5b")),
        ),
        VideoNode(
            nodeId = "n_cond",
            typeId = VideoNodeTypeId.WAN_CONDITIONING.typeId,
            typeVersion = 1,
            params = mapOf(
                "width" to VideoNodeValue.LongValue(width),
                "height" to VideoNodeValue.LongValue(height),
                "frames" to VideoNodeValue.LongValue(frames),
                "seed" to VideoNodeValue.LongValue(seed),
            ),
        ),
        VideoNode(
            nodeId = "n_sampler",
            typeId = VideoNodeTypeId.WAN_SAMPLER.typeId,
            typeVersion = 1,
            params = mapOf(
                "steps" to VideoNodeValue.LongValue(steps),
                "sampler" to VideoNodeValue.TextValue("euler"),
                "guidance" to guidance,
                "flow_shift" to VideoNodeValue.DecimalValue(30, 1),
            ),
        ),
        VideoNode(
            nodeId = "n_decode",
            typeId = VideoNodeTypeId.WAN_DECODE.typeId,
            typeVersion = 1,
        ),
        VideoNode(
            nodeId = "n_encode",
            typeId = VideoNodeTypeId.ENCODE_MP4.typeId,
            typeVersion = 1,
            params = mapOf("fps" to VideoNodeValue.LongValue(fps)),
        ),
        VideoNode(
            nodeId = "n_save",
            typeId = VideoNodeTypeId.SAVE_ASSET.typeId,
            typeVersion = 1,
        ),
    )
    val edges = listOf(
        VideoEdge("n_input", "prompt", "n_cond", "prompt"),
        VideoEdge("n_model", "dit", "n_cond", "dit"),
        VideoEdge("n_cond", "conditioning", "n_sampler", "conditioning"),
        VideoEdge("n_model", "dit", "n_sampler", "dit"),
        VideoEdge("n_sampler", "latent", "n_decode", "latent"),
        VideoEdge("n_model", "vae", "n_decode", "vae"),
        VideoEdge("n_decode", "frames", "n_encode", "frames"),
        VideoEdge("n_encode", "mp4", "n_save", "mp4"),
    )
    return VideoWorkflowDocument(
        workflowId = VideoWorkflowId(workflowId),
        revisionId = VideoRevisionId(revisionId),
        nodes = nodes,
        edges = edges,
    )
}

fun imageToVideoDocument(): VideoWorkflowDocument {
    val base = textToVideoDocument(workflowId = "wf-i2v", revisionId = "rev-i2v-1")
    val imageNode = VideoNode(
        nodeId = "n_image",
        typeId = VideoNodeTypeId.INPUT_IMAGE.typeId,
        typeVersion = 1,
        params = mapOf("image" to VideoNodeValue.ImageAssetRefValue("img-asset-1")),
    )
    return base.copy(
        nodes = base.nodes + imageNode,
        edges = base.edges + VideoEdge("n_image", "image", "n_cond", "image"),
    )
}

/** A minimal node of the given type with no params/edges, for targeted negative tests. */
fun bareNode(nodeId: String, type: VideoNodeTypeId): VideoNode =
    VideoNode(nodeId = nodeId, typeId = type.typeId, typeVersion = 1)

fun issuesByCode(issues: List<VideoWorkflowIssue>): Map<VideoErrorCode, List<VideoWorkflowIssue>> =
    issues.groupBy({ it.code })
