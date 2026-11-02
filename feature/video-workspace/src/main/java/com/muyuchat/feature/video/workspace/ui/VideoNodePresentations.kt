package com.muyuchat.feature.video.workspace.ui

import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue

/**
 * Presentation mapping for draft nodes: stable Chinese labels, one-line parameter summaries and
 * the editor-facing field metadata used by the parameter sheet. Pure display projection — no
 * behavior, no validation (that lives in the shared contract).
 */
object VideoNodePresentations {

    fun labelOf(typeId: String): String = when (VideoNodeTypeId.fromTypeId(typeId)) {
        VideoNodeTypeId.INPUT_TEXT -> "文字输入"
        VideoNodeTypeId.INPUT_IMAGE -> "输入图片"
        VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> "Wan2.2 TI2V-5B 模型"
        VideoNodeTypeId.WAN_CONDITIONING -> "条件构建"
        VideoNodeTypeId.WAN_SAMPLER -> "采样器"
        VideoNodeTypeId.WAN_DECODE -> "视频解码"
        VideoNodeTypeId.ENCODE_MP4 -> "MP4 编码"
        VideoNodeTypeId.SAVE_ASSET -> "保存视频"
        null -> "未知节点"
    }

    fun summaryOf(node: VideoNode): String {
        fun long(name: String): Long? = (node.params[name] as? VideoNodeValue.LongValue)?.value
        fun text(name: String): String? = (node.params[name] as? VideoNodeValue.TextValue)?.text
        return when (VideoNodeTypeId.fromTypeId(node.typeId)) {
            VideoNodeTypeId.INPUT_TEXT -> {
                val prompt = text("prompt").orEmpty()
                if (prompt.isEmpty()) "提示词为空" else prompt
            }

            VideoNodeTypeId.INPUT_IMAGE -> {
                val ref = node.params["image"] as? VideoNodeValue.ImageAssetRefValue
                if (ref == null) "未选择图片" else "图片 ${ref.assetId.take(12)}"
            }

            VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> {
                val model = node.params["manifest"] as? VideoNodeValue.ModelRefValue
                model?.modelId ?: "未绑定模型"
            }

            VideoNodeTypeId.WAN_CONDITIONING -> {
                val w = long("width") ?: 0L
                val h = long("height") ?: 0L
                val f = long("frames") ?: 0L
                "$w×$h · $f 帧"
            }

            VideoNodeTypeId.WAN_SAMPLER -> {
                val steps = long("steps") ?: 0L
                val sampler = text("sampler").orEmpty()
                "$sampler · $steps 步"
            }

            VideoNodeTypeId.WAN_DECODE -> "Wan2.2 VAE"

            VideoNodeTypeId.ENCODE_MP4 -> {
                val fps = long("fps") ?: 0L
                "H.264 · $fps fps"
            }

            VideoNodeTypeId.SAVE_ASSET -> "发布到媒体库"

            null -> "不支持: ${node.typeId}"
        }
    }

    /** Fixed reading order of the simplified chain (by node type). */
    val simplifiedChainOrder: List<VideoNodeTypeId> = listOf(
        VideoNodeTypeId.INPUT_TEXT,
        VideoNodeTypeId.INPUT_IMAGE,
        VideoNodeTypeId.WAN22_TI2V_5B_MODEL,
        VideoNodeTypeId.WAN_CONDITIONING,
        VideoNodeTypeId.WAN_SAMPLER,
        VideoNodeTypeId.WAN_DECODE,
        VideoNodeTypeId.ENCODE_MP4,
        VideoNodeTypeId.SAVE_ASSET,
    )

    /** Editor-friendly parameter fields per node type (sheet renders these). */
    data class FieldSpec(
        val name: String,
        val label: String,
        val kind: FieldKind,
        val required: Boolean,
        /** Optional value hints, e.g. allowed frame counts. */
        val hint: String? = null,
    )

    enum class FieldKind { PROMPT_TEXT, NEGATIVE_TEXT, LONG, DECIMAL, SAMPLER_NAME, SEED }

    fun fieldsOf(typeId: String): List<FieldSpec> = when (VideoNodeTypeId.fromTypeId(typeId)) {
        VideoNodeTypeId.INPUT_TEXT -> listOf(
            FieldSpec("prompt", "提示词", FieldKind.PROMPT_TEXT, required = true),
            FieldSpec("negative_prompt", "负面提示词", FieldKind.NEGATIVE_TEXT, required = false),
        )

        VideoNodeTypeId.INPUT_IMAGE -> listOf(
            FieldSpec("image", "图片资产", FieldKind.LONG, required = true, hint = "图生视频在媒体选择器接入后可选图"),
        )

        VideoNodeTypeId.WAN22_TI2V_5B_MODEL -> listOf(
            FieldSpec("manifest", "模型清单", FieldKind.SAMPLER_NAME, required = true, hint = "固定 wan2.2-ti2v-5b"),
        )

        VideoNodeTypeId.WAN_CONDITIONING -> listOf(
            FieldSpec("width", "宽度", FieldKind.LONG, required = true, hint = "32 的倍数"),
            FieldSpec("height", "高度", FieldKind.LONG, required = true, hint = "32 的倍数"),
            FieldSpec("frames", "帧数", FieldKind.LONG, required = true, hint = "4k+1（5、9、13…81…）"),
            FieldSpec("seed", "种子", FieldKind.SEED, required = true, hint = "0 表示提交时随机"),
        )

        VideoNodeTypeId.WAN_SAMPLER -> listOf(
            FieldSpec("steps", "步数", FieldKind.LONG, required = true),
            FieldSpec("sampler", "采样器", FieldKind.SAMPLER_NAME, required = true, hint = "euler"),
            FieldSpec("guidance", "引导系数", FieldKind.DECIMAL, required = true, hint = "基线参考 6.0"),
            FieldSpec("flow_shift", "流偏移", FieldKind.DECIMAL, required = true, hint = "基线参考 3.0"),
        )

        VideoNodeTypeId.ENCODE_MP4 -> listOf(
            FieldSpec("fps", "帧率", FieldKind.LONG, required = true, hint = "正整数"),
        )

        VideoNodeTypeId.WAN_DECODE,
        VideoNodeTypeId.SAVE_ASSET,
        null,
        -> emptyList()
    }
}
