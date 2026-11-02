package com.muyuchat.core.video.contract

/**
 * Value-level checks for node ports and parameters: literal kinds against port kinds, and
 * declared parameter schemas plus the Wan baseline geometry/frame rules
 * ([VideoWanBaselineRules]). Split from [VideoWorkflowValidation] so the structural graph walk
 * and the value rules stay independently readable; called only by that validator.
 */

/** Literal values fed to declared input ports must match the port's data kind. */
internal fun validatePortLiteralKind(
    node: VideoNode,
    decl: VideoInputPortDecl,
    value: VideoNodeValue,
    issues: MutableList<VideoWorkflowIssue>,
) {
    val kindMatches = when (decl.kind) {
        VideoPortKind.TEXT -> value is VideoNodeValue.TextValue
        VideoPortKind.IMAGE_REF -> value is VideoNodeValue.ImageAssetRefValue
        VideoPortKind.MODEL_REF -> value is VideoNodeValue.ModelRefValue
        // These kinds are always CONNECTED_ONLY in V1; a literal would already have been
        // rejected by the feed check in the structural validator.
        VideoPortKind.CONDITIONING,
        VideoPortKind.LATENT_VIDEO,
        VideoPortKind.RGB_FRAMES,
        VideoPortKind.ENCODED_VIDEO,
        VideoPortKind.VIDEO_ASSET,
        -> false
    }
    if (!kindMatches) {
        issues += VideoWorkflowIssue(
            code = VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
            path = "${node.nodeId}/${decl.name}",
            nodeId = node.nodeId,
            port = decl.name,
            detail = "port '${decl.name}' expects a ${decl.kind} literal but holds " +
                value::class.simpleName,
        )
    }
}

/** Checks one declared parameter value against its schema, bounds and Wan baseline rules. */
internal fun validateParamValue(
    node: VideoNode,
    decl: VideoParamDecl,
    value: VideoNodeValue,
    issues: MutableList<VideoWorkflowIssue>,
) {
    fun reject(detail: String) {
        issues += VideoWorkflowIssue(
            code = VideoErrorCode.WORKFLOW_INVALID,
            path = "${node.nodeId}/${decl.name}",
            nodeId = node.nodeId,
            port = decl.name,
            detail = detail,
        )
    }

    when (decl.kind) {
        VideoParamKind.TEXT ->
            if (value !is VideoNodeValue.TextValue) reject("parameter '${decl.name}' expects text")

        VideoParamKind.LONG -> {
            if (value !is VideoNodeValue.LongValue) {
                reject("parameter '${decl.name}' expects an integer")
                return
            }
            if (decl.minimum != null && value.value < decl.minimum) {
                reject("parameter '${decl.name}' must be >= ${decl.minimum}")
            }
            if (decl.maximum != null && value.value > decl.maximum) {
                reject("parameter '${decl.name}' must be <= ${decl.maximum}")
            }
            // Geometry and frame-count rules of the pinned Wan TI2V-5B baseline. Sampler
            // kinds/limits live in the model profile; these two are structural native rules.
            if (node.typeId == VideoNodeTypeId.WAN_CONDITIONING.typeId && decl.name in setOf("width", "height")) {
                if (value.value % WAN_GEOMETRY_MULTIPLE != 0L) {
                    reject("parameter '${decl.name}' must be a multiple of $WAN_GEOMETRY_MULTIPLE")
                }
            }
            if (node.typeId == VideoNodeTypeId.WAN_CONDITIONING.typeId && decl.name == "frames") {
                if (!isWanAlignedFrameCount(value.value)) {
                    val resolved = resolveWanAlignedFrameCount(value.value)
                    reject(
                        "frame count ${value.value} is not Wan-aligned (4k+1); native would round it " +
                            "down to $resolved — set the resolved value explicitly",
                    )
                }
            }
        }

        VideoParamKind.DECIMAL ->
            if (value !is VideoNodeValue.DecimalValue) {
                reject("parameter '${decl.name}' expects a decimal")
            } else if (value.scale < 0 || value.scale > 9) {
                reject("parameter '${decl.name}' decimal scale out of range")
            }

        VideoParamKind.IMAGE_ASSET_REF ->
            if (value !is VideoNodeValue.ImageAssetRefValue) {
                reject("parameter '${decl.name}' expects an image asset reference")
            }

        VideoParamKind.MODEL_REF_ID ->
            if (value !is VideoNodeValue.ModelRefValue) {
                reject("parameter '${decl.name}' expects a manifest model id")
            }
    }
}
