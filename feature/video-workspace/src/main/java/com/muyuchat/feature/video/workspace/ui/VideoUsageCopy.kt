package com.muyuchat.feature.video.workspace.ui

/**
 * Onboarding copy of the video workspace: the usage guide and the example prompts.
 *
 * Text lives here instead of inside the composables because it is the part that changes with a
 * model or template revision. The page renders exactly this list, and the JVM test
 * `VideoUsageCopyTest` pins the count, uniqueness and the model-appropriate wording, so a copy
 * edit cannot silently document a resolution, duration or modality the shipped template cannot run.
 *
 * The examples describe content only (single shot, short action). They deliberately avoid
 * parameters such as resolution or frame count: those are edited in the draft, and the shipped
 * template is 480x832 / 4 steps / Wan-aligned frames.
 */
internal object VideoUsageCopy {

    data class GuideStep(
        /** Short imperative name of the step. */
        val title: String,
        /** One sentence that names the real control of the page. */
        val detail: String,
    )

    data class PromptExample(
        /** One-line name of the example. */
        val title: String,
        /** The exact sentence written into the draft's text node when the row is tapped. */
        val prompt: String,
    )

    /** One-line hint under the "示范提示词" title: what the shipped template can and cannot do. */
    const val PROMPT_HINT: String =
        "当前模板 Wan2.2 TI2V-5B：480×832、4 步；图生视频需先选择一张输入图片，示例均为单镜头短动作。"

    /** Five steps: model -> prompt -> geometry -> run -> retry/export. */
    val guideSteps: List<GuideStep> = listOf(
        GuideStep(
            title = "准备模型",
            detail = "在「本地模型组件」下载并校验 Wan2.2 TI2V-5B；已有 ComfyUI 工作流可点「导入 ComfyUI（API 格式）」。",
        ),
        GuideStep(
            title = "填写提示词",
            detail = "点开流程里的「文字输入」写一条单镜头提示词；也可以先点下面的示范提示词，再按需修改。",
        ),
        GuideStep(
            title = "设置分辨率与帧数",
            detail = "在「条件构建」中设置宽高（32 的倍数）和帧数（4k+1，如 5、33、81）；图生视频先选择一张输入图片。",
        ),
        GuideStep(
            title = "运行并查看进度",
            detail = "点底部「生成」提交当前草稿，在「输出预览」查看真实阶段与进度；执行未接入时会明确拒绝，不伪造进度。",
        ),
        GuideStep(
            title = "重试或导出",
            detail = "完成后在「作品」中播放、分享或另存；失败或中断的任务可在「输出预览」按原参数重试。",
        ),
    )

    /**
     * Four prompts a Wan2.2 TI2V-5B text-to-video / image-to-video template can actually attempt:
     * one subject, one shot, a short motion. No audio, subtitles, multi-shot cuts or 4K claims.
     */
    val promptExamples: List<PromptExample> = listOf(
        PromptExample(
            title = "雨夜霓虹",
            prompt = "雨夜的旧城街道，霓虹招牌在积水里晃动，一把红伞缓缓穿过画面，镜头保持不动。",
        ),
        PromptExample(
            title = "清晨微距",
            prompt = "清晨窗台上的柠檬与玻璃杯，阳光缓慢移动，细小尘埃在光束里漂浮，浅景深特写。",
        ),
        PromptExample(
            title = "海岸黄昏",
            prompt = "黄昏的海岸线，云层缓慢流动，海浪一层层推上礁石，海面泛着金色反光。",
        ),
        PromptExample(
            title = "雪原旅人",
            prompt = "穿白色风衣的旅人站在雪原中央，围巾被风轻轻吹起，镜头从远处缓慢推近。",
        ),
    )
}
