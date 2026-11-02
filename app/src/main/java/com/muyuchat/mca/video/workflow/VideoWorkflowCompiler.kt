package com.muyuchat.mca.video.workflow

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoInputBinding
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoResolvedParams
import com.muyuchat.core.video.contract.VideoRunSnapshot
import com.muyuchat.core.video.contract.VideoWorkflowCanon
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.validateVideoWorkflow
import com.muyuchat.core.video.contract.VideoWorkflowIssue
import com.muyuchat.mca.video.model.VideoModelManifest
import java.security.MessageDigest

/**
 * The production compiler from a validated MCA-native workflow DAG to one frozen
 * [VideoRunSnapshot] (proposal §10.4). Templates and hand-edited graphs go through THIS path —
 * the same one — so the node editor can never be decoration.
 *
 * Pure Kotlin: no Compose state reads, no IO, no native. The process owner calls it at submit
 * time, then persists the returned snapshot with the QUEUED run. The snapshot is what executes;
 * later draft edits cannot reach a running or historical run.
 *
 * Lowering for V1 is one fused pipeline: input nodes + model + conditioning + sampler + decode +
 * encode + save become a single native generate_video call with MP4 encode parameters. Node
 * semantics come from the pinned registry; the compiler refuses anything it cannot map exactly
 * (§6.5: reject-don't-ignore).
 */
object VideoWorkflowCompiler {

    /** How the seed in the draft becomes the frozen seed. */
    sealed class SeedPolicy {
        /** Use the draft's seed value verbatim. */
        data class Fixed(val seed: Long) : SeedPolicy()

        /** Draw once at freeze time (the caller supplies the drawn value; the compiler stays pure). */
        data class Randomized(val drawnSeed: Long) : SeedPolicy()
    }

    sealed class Result {
        data class Compiled(val snapshot: VideoRunSnapshot) : Result()

        data class Rejected(val issues: List<VideoWorkflowIssue>) : Result() {
            fun firstCode(): VideoErrorCode = issues.firstOrNull()?.code ?: VideoErrorCode.WORKFLOW_INVALID
        }
    }

    fun compile(
        document: VideoWorkflowDocument,
        manifest: VideoModelManifest,
        seedPolicy: SeedPolicy,
        resolvedImageRefs: Map<String, VideoInputBinding> = emptyMap(),
        backend: com.muyuchat.core.video.contract.VideoBackendKind =
            com.muyuchat.core.video.contract.VideoBackendKind.LOCAL_NATIVE,
        /**
         * Local execution engine chosen for this run ([com.muyuchat.core.video.contract.VideoLocalEngine]).
         * The default keeps the built-in MCA pipeline; the value is frozen into the snapshot and is
         * the ONLY place the native payload can learn about a non-default engine.
         */
        engine: String = com.muyuchat.core.video.contract.VideoLocalEngine.DEFAULT,
        /**
         * External-engine backend (`htp` | `cpu`), frozen with the engine so a retry re-runs the
         * same transport. It never reaches the native layer for the built-in engine.
         */
        externalBackend: String = com.muyuchat.core.video.contract.VideoLocalBackend.HTP,
        /**
         * Distillation LoRA weights applied by the external engine, frozen with the run so a retry
         * replays the same recipe. Rejected with the built-in engine instead of being dropped.
         */
        loraSpecs: List<com.muyuchat.core.video.contract.VideoLoraBinding> = emptyList(),
    ): Result {
        if (!com.muyuchat.core.video.contract.VideoLocalEngine.isSupported(engine)) {
            return Result.Rejected(
                listOf(
                    VideoWorkflowIssue(
                        code = VideoErrorCode.INPUT_INVALID,
                        path = "engine",
                        detail = "不支持的视频引擎：'$engine'",
                    ),
                ),
            )
        }
        if (!com.muyuchat.core.video.contract.VideoLocalBackend.isSupported(externalBackend)) {
            return Result.Rejected(
                listOf(
                    VideoWorkflowIssue(
                        code = VideoErrorCode.INPUT_INVALID,
                        path = "backend",
                        detail = "不支持的视频后端：'$externalBackend'",
                    ),
                ),
            )
        }
        if (loraSpecs.isNotEmpty() && engine != com.muyuchat.core.video.contract.VideoLocalEngine.SDCPP_EXTERNAL) {
            return Result.Rejected(
                listOf(
                    VideoWorkflowIssue(
                        code = VideoErrorCode.INPUT_INVALID,
                        path = "loras",
                        detail = "LoRA 权重只支持 external 引擎",
                    ),
                ),
            )
        }
        if (loraSpecs.size > com.muyuchat.core.video.contract.VideoLoraBinding.MAX_ENTRIES) {
            return Result.Rejected(
                listOf(
                    VideoWorkflowIssue(
                        code = VideoErrorCode.INPUT_INVALID,
                        path = "loras",
                        detail = "一次最多 ${com.muyuchat.core.video.contract.VideoLoraBinding.MAX_ENTRIES} 个 LoRA",
                    ),
                ),
            )
        }
        // 0. Materialize owner-resolved image bindings into the document BEFORE validation:
        //    the submitted executable graph is draft + resolved inputs as one frozen unit, and
        //    the shared validator stays the single authority (it must see the resolved literal).
        val materialized = if (resolvedImageRefs.isEmpty()) {
            document
        } else {
            document.copy(
                nodes = document.nodes.map { node ->
                    val binding = resolvedImageRefs[node.nodeId] ?: return@map node
                    node.copy(
                        params = node.params + (
                            "image" to VideoNodeValue.ImageAssetRefValue(
                                requireNotNull(binding.reference) { "resolved binding must carry a reference" },
                            )
                            ),
                    )
                },
            )
        }

        // 1. Full structural validation of the materialized graph; any issue blocks compilation.
        val issues = validateVideoWorkflow(materialized)
        if (issues.isNotEmpty()) return Result.Rejected(issues)

        // 2. Locate the typed chain from the single output root.
        val saveNode = materialized.nodes.first { typed(it) == VideoNodeTypeId.SAVE_ASSET }
        val encodeNode = soleProducerOf(materialized, saveNode, "mp4", VideoNodeTypeId.ENCODE_MP4)
            ?: return Result.Rejected(nodeIssue(saveNode, "输出根缺少 MP4 编码输入"))
        val decodeNode = soleProducerOf(materialized, encodeNode, "frames", VideoNodeTypeId.WAN_DECODE)
            ?: return Result.Rejected(nodeIssue(encodeNode, "编码节点缺少解码帧输入"))
        val samplerNode = soleProducerOf(materialized, decodeNode, "latent", VideoNodeTypeId.WAN_SAMPLER)
            ?: return Result.Rejected(nodeIssue(decodeNode, "解码节点缺少采样 latent 输入"))
        val conditioningNode = soleProducerOf(materialized, samplerNode, "conditioning", VideoNodeTypeId.WAN_CONDITIONING)
            ?: return Result.Rejected(nodeIssue(samplerNode, "采样节点缺少条件输入"))
        val textNode = soleProducerOf(materialized, conditioningNode, "prompt", VideoNodeTypeId.INPUT_TEXT)
            ?: return Result.Rejected(nodeIssue(conditioningNode, "条件节点缺少提示词输入"))
        val imageNode = producerOf(materialized, conditioningNode, "image", VideoNodeTypeId.INPUT_IMAGE)
        val modelNode = soleProducerOf(materialized, conditioningNode, "dit", VideoNodeTypeId.WAN22_TI2V_5B_MODEL)
            ?: return Result.Rejected(nodeIssue(conditioningNode, "条件节点缺少模型输入"))
        // The sampler's DiT and the decoder's VAE must come from the SAME model node.
        val samplerModelNode = soleProducerOf(materialized, samplerNode, "dit", VideoNodeTypeId.WAN22_TI2V_5B_MODEL)
            ?: return Result.Rejected(nodeIssue(samplerNode, "采样节点缺少模型输入"))
        val decodeModelNode = soleProducerOf(materialized, decodeNode, "vae", VideoNodeTypeId.WAN22_TI2V_5B_MODEL)
            ?: return Result.Rejected(nodeIssue(decodeNode, "解码节点缺少 VAE 输入"))
        if (modelNode.nodeId != samplerModelNode.nodeId || modelNode.nodeId != decodeModelNode.nodeId) {
            return Result.Rejected(
                nodeIssue(modelNode, "V1 要求 DiT 与 VAE 来自同一个模型节点（单 fused 管线）"),
            )
        }

        // 3. Model binding: the manifest id on the node must be the pinned baseline manifest.
        val manifestRef = (modelNode.params["manifest"] as? VideoNodeValue.ModelRefValue)?.modelId
            ?: return Result.Rejected(nodeIssue(modelNode, "模型节点缺少 manifest 引用"))
        if (manifestRef != manifest.modelId) {
            return Result.Rejected(
                listOf(
                    VideoWorkflowIssue(
                        code = VideoErrorCode.MODEL_ROLE_MISMATCH,
                        path = modelNode.nodeId,
                        nodeId = modelNode.nodeId,
                        detail = "工作流引用 '$manifestRef'，当前绑定清单为 '${manifest.modelId}'",
                    ),
                ),
            )
        }

        // 4. Extract and freeze parameters (validator guarantees the Wan rules already).
        fun longOf(node: VideoNode, name: String): Long =
            (node.params[name] as VideoNodeValue.LongValue).value

        fun decimalOf(node: VideoNode, name: String): VideoNodeValue.DecimalValue =
            node.params[name] as VideoNodeValue.DecimalValue

        fun textOf(node: VideoNode, name: String): String =
            (node.params[name] as? VideoNodeValue.TextValue)?.text.orEmpty()

        val width = longOf(conditioningNode, "width")
        val height = longOf(conditioningNode, "height")
        val frames = longOf(conditioningNode, "frames")
        val fps = longOf(encodeNode, "fps").let { value ->
            if (value < 1) return Result.Rejected(nodeIssue(encodeNode, "帧率必须为正整数"))
            value
        }
        val steps = longOf(samplerNode, "steps").let { value ->
            if (value < 1) return Result.Rejected(nodeIssue(samplerNode, "步数必须为正整数"))
            value
        }
        val sampler = textOf(samplerNode, "sampler")
        if (sampler.isBlank()) return Result.Rejected(nodeIssue(samplerNode, "采样器名称为空"))
        val guidance = decimalOf(samplerNode, "guidance")
        val flowShift = decimalOf(samplerNode, "flow_shift")
        val seed = when (seedPolicy) {
            is SeedPolicy.Fixed -> seedPolicy.seed
            is SeedPolicy.Randomized -> seedPolicy.drawnSeed
        }

        val prompt = textOf(textNode, "prompt")
        if (prompt.isBlank()) {
            return Result.Rejected(nodeIssue(textNode, "提示词为空"))
        }
        val negativePrompt = textOf(textNode, "negative_prompt").ifBlank { null }

        // 5. Image binding (image-to-video): the owner-resolved binding wins (it carries the
        // verified content digest); the on-node literal is the editor-time fallback.
        val imageRef: VideoInputBinding? = if (imageNode == null) {
            null
        } else {
            val resolved = resolvedImageRefs[imageNode.nodeId]
            val literal = imageNode.params["image"] as? VideoNodeValue.ImageAssetRefValue
            when {
                resolved != null -> resolved
                literal != null -> VideoInputBinding(
                    nodeId = imageNode.nodeId,
                    kind = com.muyuchat.core.video.contract.VideoPortKind.IMAGE_REF,
                    reference = literal.assetId,
                )

                else -> return Result.Rejected(nodeIssue(imageNode, "图生视频缺少已验证的输入图片"))
            }
        }

        // 6. Freeze the snapshot: one digest over the executable graph, prompts hashed (privacy:
        // snapshots and logs never persist raw prompt text), pins over the model chain.
        val params = VideoResolvedParams(
            width = width,
            height = height,
            frames = frames,
            fps = fps,
            steps = steps,
            sampler = sampler,
            guidance = guidance,
            flowShift = flowShift,
            seed = seed,
            promptSha256 = sha256Of(prompt),
            negativePromptSha256 = negativePrompt?.let(::sha256Of),
            imageRef = imageRef,
            engine = engine,
            externalBackend = externalBackend,
            loraSpecs = loraSpecs,
        )
        val snapshot = VideoRunSnapshot(
            workflowId = materialized.workflowId,
            revisionId = materialized.revisionId,
            canonicalSha256 = VideoWorkflowCanon.canonicalExecutableSha256(materialized),
            inputRefs = buildMap {
                textNode.params["prompt"]?.let { put("${textNode.nodeId}/prompt", VideoInputBinding(textNode.nodeId, com.muyuchat.core.video.contract.VideoPortKind.TEXT, "inline:prompt:${params.promptSha256}")) }
                negativePrompt?.let {
                    put("${textNode.nodeId}/negative", VideoInputBinding(textNode.nodeId, com.muyuchat.core.video.contract.VideoPortKind.TEXT, "inline:negative:${params.negativePromptSha256}"))
                }
                imageRef?.let { put(imageRef.nodeId, it) }
            },
            requestedParams = params,
            resolvedParams = params,
            nativeEffectiveParams = null,
            modelFingerprint = modelFingerprintOf(manifest),
            profileFingerprint = "wan22-ti2v-5b-baseline/${manifest.revision}",
            backend = backend,
        )
        return Result.Compiled(snapshot)
    }

    private fun typed(node: VideoNode): VideoNodeTypeId? = VideoNodeTypeId.fromTypeId(node.typeId)

    /** The unique producer feeding `toPort` of `consumer`; null when absent, first when duplicated. */
    private fun producerOf(
        document: VideoWorkflowDocument,
        consumer: VideoNode,
        toPort: String,
        expectedType: VideoNodeTypeId,
    ): VideoNode? {
        val edge = document.edges.firstOrNull { it.toNodeId == consumer.nodeId && it.toPort == toPort } ?: return null
        val node = document.node(edge.fromNodeId) ?: return null
        return if (typed(node) == expectedType) node else null
    }

    private fun soleProducerOf(
        document: VideoWorkflowDocument,
        consumer: VideoNode,
        toPort: String,
        expectedType: VideoNodeTypeId,
    ): VideoNode? = producerOf(document, consumer, toPort, expectedType)

    private fun nodeIssue(node: VideoNode, detail: String): List<VideoWorkflowIssue> = listOf(
        VideoWorkflowIssue(
            code = VideoErrorCode.WORKFLOW_INVALID,
            path = node.nodeId,
            nodeId = node.nodeId,
            detail = detail,
        ),
    )

    /** Stable fingerprint over the manifest's role/format/quant/size/sha pins. */
    fun modelFingerprintOf(manifest: VideoModelManifest): String {
        val canonical = buildString {
            append(manifest.modelId).append('|').append(manifest.revision)
            manifest.components.sortedBy { it.role.name }.forEach { component ->
                append('|').append(component.role.name)
                append('|').append(component.format)
                append('|').append(component.quantization)
                append('|').append(component.sizeBytes)
                append('|').append(component.sha256 ?: "pending")
            }
        }
        return sha256Of(canonical)
    }

    private fun sha256Of(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
