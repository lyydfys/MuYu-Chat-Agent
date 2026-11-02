package com.muyuchat.mca.video.workflow.importer

import com.muyuchat.core.video.contract.VideoEdge
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoImportPreview
import com.muyuchat.core.video.contract.VideoImportSeverity
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeUi
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoWorkflowCanon
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowId
import com.muyuchat.core.video.contract.VideoWorkflowSource
import com.muyuchat.core.video.contract.isWanAlignedFrameCount
import com.muyuchat.core.video.contract.resolveWanAlignedFrameCount
import com.muyuchat.core.video.contract.validateVideoWorkflow
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles

/**
 * Lowers a parsed Comfy API graph onto the frozen MCA V1 shape:
 * n_input, n_image (optional), n_model, n_cond, n_sampler, n_decode, n_encode, n_save.
 *
 * The result is always structurally valid (or Rejected). `runnable` is deliberately
 * conservative: exact sampler recipe, one fully bound model profile, valid geometry and no
 * unresolved image input are all required.
 */
object ComfyWorkflowLowerer {

    const val UNRESOLVED_IMAGE_REF_PREFIX = "comfy-unresolved:"

    private const val SHORT_DIGEST_LENGTH = 16

    fun lower(
        json: String,
        fileName: String? = null,
        rawSourceRef: String? = null,
    ): VideoImportPreview = when (val parsed = ComfyApiWorkflowParser.parse(json, fileName)) {
        is ComfyApiParseResult.Parsed -> lower(parsed.graph, rawSourceRef)
        is ComfyApiParseResult.Rejected -> VideoImportPreview.Rejected(parsed.code, parsed.diagnostics)
    }

    fun lower(
        graph: ComfyApiGraph,
        rawSourceRef: String? = null,
    ): VideoImportPreview {
        val state = LoweringState(graph)
        state.diagnostics += graph.warnings
        return try {
            val document = buildDocument(state, rawSourceRef)
            val issues = validateVideoWorkflow(document)
            if (issues.isNotEmpty()) {
                VideoImportPreview.Rejected(
                    code = issues.first().code,
                    diagnostics = state.diagnostics + issues.map {
                        VideoImportDiagnostic(
                            severity = VideoImportSeverity.ERROR,
                            nodeId = it.nodeId,
                            path = it.path,
                            message = it.detail,
                        )
                    },
                )
            } else {
                VideoImportPreview.Ready(
                    document = document,
                    runnable = state.runnable,
                    diagnostics = state.diagnostics.toList(),
                )
            }
        } catch (failure: ComfyValueFailure) {
            VideoImportPreview.Rejected(
                code = failure.code,
                diagnostics = state.diagnostics + VideoImportDiagnostic(
                    severity = VideoImportSeverity.ERROR,
                    nodeId = failure.nodeId,
                    path = failure.path,
                    message = failure.message,
                ),
            )
        }
    }

    private fun buildDocument(state: LoweringState, rawSourceRef: String?): VideoWorkflowDocument {
        val digest = requireSourceDigest(state)
        val save = uniqueOfClass(state, ComfyCompatibilityMatrix.SAVE_VIDEO)
        val create = resolveLink(state, save, "video", ComfyCompatibilityMatrix.CREATE_VIDEO)
        val decode = resolveLink(state, create, "images", ComfyCompatibilityMatrix.VAE_DECODE)
        val sampler = resolveLink(state, decode, "samples", ComfyCompatibilityMatrix.K_SAMPLER)

        val latentLink = sampler.linkInput("latent_image")
        val latent = state.node(latentLink.nodeId)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/latent_image",
                "latent_image 引用了不存在的节点 '${latentLink.nodeId}'",
            )
        if (latent.classType != ComfyCompatibilityMatrix.EMPTY_LATENT &&
            latent.classType != ComfyCompatibilityMatrix.WAN_IMAGE_TO_VIDEO
        ) {
            rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                sampler.id,
                "${sampler.id}/inputs/latent_image",
                "latent_image 只支持 EmptyHunyuanLatentVideo 或 WanImageToVideo，收到 '${latent.classType}'",
            )
        }
        if (latent.classType == ComfyCompatibilityMatrix.WAN_IMAGE_TO_VIDEO) {
            if (latentLink.outputIndex == 0) {
                state.warn(
                    latent.id,
                    "${latent.id}/latent",
                    "WanImageToVideo latent 引用使用了输出 0（条件输出），按兼容回退读取；执行前需重新绑定",
                )
            } else if (latentLink.outputIndex != 2) {
                rejectImport(
                    VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                    latent.id,
                    "${sampler.id}/inputs/latent_image",
                    "WanImageToVideo 的 latent 输出索引应为 2，收到 ${latentLink.outputIndex}",
                )
            }
        } else if (latentLink.outputIndex != 0) {
            rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                sampler.id,
                "${sampler.id}/inputs/latent_image",
                "EmptyHunyuanLatentVideo 只有输出 0，收到 ${latentLink.outputIndex}",
            )
        }

        val modelLink = sampler.linkInput("model")
        val modelSource = state.node(modelLink.nodeId)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/model",
                "model 引用了不存在的节点 '${modelLink.nodeId}'",
            )
        val modelSampling = when (modelSource.classType) {
            ComfyCompatibilityMatrix.MODEL_SAMPLING_SD3 -> modelSource
            ComfyCompatibilityMatrix.UNET_LOADER -> rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/model",
                "KSampler.model 直接连接 UNETLoader，缺少 ModelSamplingSD3 的 flow_shift，无法精确映射",
            )

            else -> rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                sampler.id,
                "${sampler.id}/inputs/model",
                "model 连接必须是 ModelSamplingSD3，收到 '${modelSource.classType}'",
            )
        }
        val unet = resolveLink(state, modelSampling, "model", ComfyCompatibilityMatrix.UNET_LOADER)
        val flowShift = modelSampling.decimalInput("shift")

        val positive = resolvePrompt(state, sampler, "positive")
        val negative = resolvePrompt(state, sampler, "negative")
        val clipForPositive = resolveLink(state, positive, "clip", ComfyCompatibilityMatrix.CLIP_LOADER)
        val clipForNegative = resolveLink(state, negative, "clip", ComfyCompatibilityMatrix.CLIP_LOADER)
        if (clipForPositive.id != clipForNegative.id) {
            rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                sampler.id,
                "${sampler.id}/inputs/positive",
                "正负提示词使用了不同的 CLIPLoader，无法映射为单一文本编码器角色",
            )
        }
        val clip = clipForPositive
        val vae = resolveLink(state, decode, "vae", ComfyCompatibilityMatrix.VAE_LOADER)

        val imageNode = if (latent.classType == ComfyCompatibilityMatrix.WAN_IMAGE_TO_VIDEO) {
            resolveLink(state, latent, "start_image", ComfyCompatibilityMatrix.LOAD_IMAGE)
        } else {
            null
        }
        val imageSource = imageNode?.stringInput("image")

        val consumedIds = buildSet {
            add(save.id)
            add(create.id)
            add(decode.id)
            add(sampler.id)
            add(latent.id)
            add(modelSampling.id)
            add(unet.id)
            add(positive.id)
            add(negative.id)
            add(clip.id)
            add(vae.id)
            imageNode?.let { add(it.id) }
        }
        val leftovers = state.graph.nodes.filterNot { it.id in consumedIds }
        if (leftovers.isNotEmpty()) {
            val first = leftovers.first()
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                first.id,
                first.id,
                "节点 '${first.id}' (${first.classType}) 不在受支持的映射链上；导入不会静默丢弃节点",
            )
        }
        val consumed = state.graph.nodes.filter { it.id in consumedIds }
        for (classType in listOf(
            ComfyCompatibilityMatrix.UNET_LOADER,
            ComfyCompatibilityMatrix.CLIP_LOADER,
            ComfyCompatibilityMatrix.VAE_LOADER,
        )) {
            val matches = consumed.filter { it.classType == classType }
            if (matches.size != 1) {
                rejectImport(
                    VideoErrorCode.WORKFLOW_INVALID,
                    matches.firstOrNull()?.id,
                    matches.firstOrNull()?.id ?: "$",
                    "class_type '$classType' 必须恰好出现一次，实际 ${matches.size} 次",
                )
            }
        }

        val unetName = unet.stringInput("unet_name")
        val weightDtype = unet.stringInput("weight_dtype")
        val clipName = clip.stringInput("clip_name")
        val clipType = clip.stringInput("type")
        val vaeName = vae.stringInput("vae_name")
        val prompt = positive.stringInput("text")
        val negativePrompt = negative.stringInput("text")
        val width = latent.longInput("width")
        val height = latent.longInput("height")
        val frames = latent.longInput("length")
        val batchSize = latent.longInput("batch_size")
        val seed = sampler.longInput("seed")
        val steps = sampler.longInput("steps")
        val cfg = sampler.decimalInput("cfg")
        val samplerName = sampler.stringInput("sampler_name")
        val scheduler = sampler.stringInput("scheduler")
        val denoise = sampler.decimalInput("denoise")
        val fps = create.longInput("fps")
        val saveFormat = save.optionalStringInput("format")
        val saveCodec = save.optionalStringInput("codec")
        val savePrefix = save.optionalStringInput("filename_prefix")

        requireGeometry(latent, "width", width)
        requireGeometry(latent, "height", height)
        if (!isWanAlignedFrameCount(frames)) {
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                latent.id,
                "${latent.id}/inputs/length",
                "帧数 $frames 不是 Wan 对齐的 4k+1；建议显式使用 ${resolveWanAlignedFrameCount(frames)}",
            )
        }
        if (steps < 1) {
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/steps",
                "steps 必须 >= 1，收到 $steps",
            )
        }
        if (fps < 1) {
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                create.id,
                "${create.id}/inputs/fps",
                "fps 必须 >= 1，收到 $fps",
            )
        }
        for (warning in ComfyCompatibilityMatrix.mappingWarnings(
            batchSize,
            clipType,
            samplerName,
            scheduler,
            denoise,
            saveFormat,
            saveCodec,
        )) {
            val target = when (warning.target) {
                ComfyWarningTarget.LATENT -> latent
                ComfyWarningTarget.CLIP -> clip
                ComfyWarningTarget.SAMPLER -> sampler
                ComfyWarningTarget.SAVE -> save
            }
            state.warn(target.id, "${target.id}/inputs/${warning.input}", warning.message)
        }
        if (prompt.isBlank()) {
            state.warn(
                positive.id,
                "${positive.id}/inputs/text",
                "正向提示词为空；文档可保存，但当前不可运行",
            )
        }
        if (imageSource != null && imageNode != null) {
            state.warn(
                imageNode.id,
                "${imageNode.id}/inputs/image",
                "LoadImage.image='$imageSource' 仅记录来源名，尚未解析为本地已验证资产；runnable=false",
            )
        }

        val modelBinding = when (
            val binding = ComfyCompatibilityMatrix.bindModelFiles(unetName, clipName, vaeName)
        ) {
            is ComfyModelBindingResult.Bound -> binding.profile
            is ComfyModelBindingResult.Unbound -> {
                for (issue in binding.issues) {
                    val (node, input) = anchorForRoles(issue.roles, unet, clip, vae)
                    state.warn(node.id, "${node.id}/inputs/$input", issue.message)
                }
                null
            }
        }
        val guidanceMilli = ComfyCompatibilityMatrix.toFixedScale(cfg)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/cfg",
                "cfg 超出可转换范围",
            )
        val flowShiftMilli = ComfyCompatibilityMatrix.toFixedScale(flowShift)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                modelSampling.id,
                "${modelSampling.id}/inputs/shift",
                "shift 超出可转换范围",
            )

        val inputParams = buildMap<String, VideoNodeValue> {
            put("prompt", VideoNodeValue.TextValue(prompt))
            if (negativePrompt.isNotEmpty()) {
                put("negative_prompt", VideoNodeValue.TextValue(negativePrompt))
            }
        }
        val nodes = mutableListOf(
            videoNode("n_input", VideoNodeTypeId.INPUT_TEXT, 60f, inputParams),
            videoNode(
                "n_model",
                VideoNodeTypeId.WAN22_TI2V_5B_MODEL,
                160f,
                mapOf("manifest" to VideoNodeValue.ModelRefValue(Wan22Ti2v5bProfiles.MODEL_ID)),
            ),
            videoNode(
                "n_cond",
                VideoNodeTypeId.WAN_CONDITIONING,
                260f,
                mapOf(
                    "width" to VideoNodeValue.LongValue(width),
                    "height" to VideoNodeValue.LongValue(height),
                    "frames" to VideoNodeValue.LongValue(frames),
                    "seed" to VideoNodeValue.LongValue(seed),
                ),
            ),
            videoNode(
                "n_sampler",
                VideoNodeTypeId.WAN_SAMPLER,
                360f,
                mapOf(
                    "steps" to VideoNodeValue.LongValue(steps),
                    "sampler" to VideoNodeValue.TextValue(samplerName),
                    // Fixed-point denominator 1000 => exactly 3 decimal digits.
                    "guidance" to VideoNodeValue.DecimalValue(guidanceMilli, 3),
                    "flow_shift" to VideoNodeValue.DecimalValue(flowShiftMilli, 3),
                ),
            ),
            videoNode("n_decode", VideoNodeTypeId.WAN_DECODE, 560f),
            videoNode(
                "n_encode",
                VideoNodeTypeId.ENCODE_MP4,
                660f,
                mapOf("fps" to VideoNodeValue.LongValue(fps)),
            ),
            videoNode("n_save", VideoNodeTypeId.SAVE_ASSET, 760f),
        )
        if (imageSource != null) {
            nodes += videoNode(
                "n_image",
                VideoNodeTypeId.INPUT_IMAGE,
                460f,
                mapOf("image" to VideoNodeValue.ImageAssetRefValue(UNRESOLVED_IMAGE_REF_PREFIX + imageSource)),
            )
        }

        val edges = baseEdges().toMutableList()
        if (imageSource != null) {
            edges += VideoEdge("n_image", "image", "n_cond", "image")
        }

        val metadata = buildMap {
            put(
                "comfy.sourceName",
                sanitizeName(state.graph.fileName?.let { ComfyCompatibilityMatrix.baseFileName(it) }) ?: "inline",
            )
            put("comfy.mappingVersion", ComfyCompatibilityMatrix.MAPPING_VERSION)
            put("comfy.sourceSha256", digest)
            put("comfy.classTypes", state.graph.classTypeCounts.entries.joinToString(",") { "${it.key}=${it.value}" })
            put("comfy.nodeCount", state.graph.nodes.size.toString())
            put("comfy.unetName", unetName)
            put("comfy.unetWeightDtype", weightDtype)
            put("comfy.clipName", clipName)
            put("comfy.clipType", clipType)
            put("comfy.vaeName", vaeName)
            put("comfy.saveFormat", saveFormat ?: "unspecified")
            put("comfy.saveCodec", saveCodec ?: "unspecified")
            sanitizeName(savePrefix)?.let { put("comfy.saveFilenamePrefix", it) }
            put(
                "comfy.modelBinding",
                modelBinding?.let { "profile:${it.profileRevision}" } ?: "unbound",
            )
            if (imageSource != null) {
                put("comfy.imageSource", imageSource)
            }
        }

        val workflowId = VideoWorkflowId("comfy-${digest.take(SHORT_DIGEST_LENGTH)}")
        val revisionId = VideoRevisionId("comfy-rev-${digest.take(SHORT_DIGEST_LENGTH)}")
        val base = VideoWorkflowDocument(
            workflowId = workflowId,
            revisionId = revisionId,
            source = VideoWorkflowSource.COMFY_API,
            nodes = nodes,
            edges = edges,
            metadata = metadata,
            rawSourceRef = rawSourceRef,
        )
        return base.copy(canonicalSha256 = VideoWorkflowCanon.canonicalExecutableSha256(base))
    }

    private fun videoNode(
        nodeId: String,
        type: VideoNodeTypeId,
        positionY: Float,
        params: Map<String, VideoNodeValue> = emptyMap(),
    ): VideoNode = VideoNode(
        nodeId = nodeId,
        typeId = type.typeId,
        typeVersion = 1,
        params = params,
        ui = VideoNodeUi(positionX = 40f, positionY = positionY),
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

    private fun resolvePrompt(
        state: LoweringState,
        sampler: ComfyApiNode,
        input: String,
    ): ComfyApiNode {
        val link = sampler.linkInput(input)
        val source = state.node(link.nodeId)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                sampler.id,
                "${sampler.id}/inputs/$input",
                "input '$input' 引用了不存在的节点 '${link.nodeId}'",
            )
        return when (source.classType) {
            ComfyCompatibilityMatrix.CLIP_TEXT_ENCODE -> source
            ComfyCompatibilityMatrix.WAN_IMAGE_TO_VIDEO -> {
                if (link.outputIndex !in 0..1) {
                    rejectImport(
                        VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                        sampler.id,
                        "${sampler.id}/inputs/$input",
                        "WanImageToVideo 只有输出 0/1 携带条件，收到 ${link.outputIndex}",
                    )
                }
                val inner = if (link.outputIndex == 1) "negative" else "positive"
                resolveLink(state, source, inner, ComfyCompatibilityMatrix.CLIP_TEXT_ENCODE)
            }

            else -> rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                sampler.id,
                "${sampler.id}/inputs/$input",
                "input '$input' 必须来自 CLIPTextEncode（或 WanImageToVideo 条件输出），收到 '${source.classType}'",
            )
        }
    }

    private fun anchorForRoles(
        roles: Set<ComfyModelRole>,
        unet: ComfyApiNode,
        clip: ComfyApiNode,
        vae: ComfyApiNode,
    ): Pair<ComfyApiNode, String> = when {
        ComfyModelRole.DIT in roles -> unet to "unet_name"
        ComfyModelRole.TEXT_ENCODER in roles -> clip to "clip_name"
        else -> vae to "vae_name"
    }

    private fun requireGeometry(node: ComfyApiNode, input: String, value: Long) {
        if (value < 32 || value % 32L != 0L) {
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                node.id,
                "${node.id}/inputs/$input",
                "$input=$value 必须是 >= 32 且为 32 的倍数",
            )
        }
    }

    private fun uniqueOfClass(state: LoweringState, classType: String): ComfyApiNode {
        val matches = state.graph.nodes.filter { it.classType == classType }
        if (matches.size != 1) {
            rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                matches.firstOrNull()?.id,
                matches.firstOrNull()?.id ?: "$",
                "图必须恰好包含一个 $classType 输出根，实际 ${matches.size} 个",
            )
        }
        return matches.single()
    }

    private fun resolveLink(
        state: LoweringState,
        node: ComfyApiNode,
        input: String,
        vararg expectedClassTypes: String,
    ): ComfyApiNode {
        val link = node.linkInput(input)
        val source = state.node(link.nodeId)
            ?: rejectImport(
                VideoErrorCode.WORKFLOW_INVALID,
                node.id,
                "${node.id}/inputs/$input",
                "input '$input' 引用了不存在的节点 '${link.nodeId}'",
            )
        if (source.classType !in expectedClassTypes) {
            rejectImport(
                VideoErrorCode.WORKFLOW_TYPE_MISMATCH,
                node.id,
                "${node.id}/inputs/$input",
                "input '$input' 期望 ${expectedClassTypes.toList()}，收到 '${source.classType}'",
            )
        }
        return source
    }

    private fun requireSourceDigest(state: LoweringState): String {
        val digest = state.graph.sourceSha256
        val valid = digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }
        if (!valid) {
            rejectImport(
                VideoErrorCode.INPUT_INVALID,
                null,
                "$",
                "源 JSON 摘要不是 64 位小写十六进制",
            )
        }
        return digest
    }

    private fun sanitizeName(value: String?): String? {
        val cleaned = value?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length <= 200) cleaned else cleaned.take(200)
    }

    private class LoweringState(val graph: ComfyApiGraph) {
        val diagnostics = mutableListOf<VideoImportDiagnostic>()
        var runnable: Boolean = true
            private set

        fun node(nodeId: String): ComfyApiNode? = graph.node(nodeId)

        fun warn(nodeId: String?, path: String?, message: String) {
            diagnostics += VideoImportDiagnostic(
                severity = VideoImportSeverity.WARNING,
                nodeId = nodeId,
                path = path,
                message = message,
            )
            runnable = false
        }
    }

    private fun rejectImport(
        code: VideoErrorCode,
        nodeId: String?,
        path: String,
        message: String,
    ): Nothing = throw ComfyValueFailure(code, nodeId, path, message)
}
