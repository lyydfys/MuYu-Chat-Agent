package com.muyuchat.feature.chat

/**
 * 生图提交的结算规则（提示词清空 / 失败输入清理）。
 *
 * 产品要求与聊天发送一致但更保守：**成功**才清空提示词输入框，失败/取消保留原文以便重试；译文
 * 草稿只对同一原文可见，原文被清空或改写后旧译文不能“复活”。这些判断全是纯函数，Composable /
 * 效果层只负责在合适的时机调用，不在组合里直接改 app state。
 */

/**
 * 一次工作台提交的身份。没有 jobId：提交回调只返回 Boolean，而 Compose 闭包里的 `state` 可能还是
 * 上一帧，因此用“提示词 + 提交时刻”在新发布的 job 列表里定位本次任务。
 */
internal data class PendingImagePromptJob(
    val prompt: String,
    val submittedAtMillis: Long,
)

/**
 * 成功（terminal 且非失败且真的产出了图片）才允许清空提示词。
 *
 * 取消/中断即使 terminal 也不会产图，必须保留原文；失败必须保留。
 */
internal fun shouldClearImagePromptAfterJob(
    terminal: Boolean,
    failed: Boolean,
    imageAssetCount: Int,
): Boolean = terminal && !failed && imageAssetCount > 0

/**
 * 结算后的输入框正文：只清空“仍然是本次提交那条提示词”的输入。
 *
 * 用户在生成期间改了提示词时保留新内容，避免把用户正在编辑的文本冲掉。
 */
internal fun imagePromptAfterJobSettlement(
    currentPrompt: String,
    submittedPrompt: String,
    clearPrompt: Boolean,
): String = if (clearPrompt && currentPrompt == submittedPrompt) "" else currentPrompt

/** 译文草稿只对同一原文可见：原文一变（含被清空），旧译文/旧错误必须隐藏。 */
internal fun imagePromptTranslationVisibleFor(
    draftSource: String,
    currentSource: String,
): Boolean = draftSource == currentSource

/** 失败后需要从工作台清掉的输入角色（缺文件/不可读/格式坏）。 */
internal fun staleGenerationInputRoles(
    references: Map<ImageInputRole, String?>,
    reasonOf: (String) -> ImageAssetPreflightReason,
): List<ImageInputRole> = references.entries
    .filter { (_, reference) -> !reference.isNullOrBlank() }
    .filter { (_, reference) -> reasonOf(requireNotNull(reference)) != ImageAssetPreflightReason.OK }
    .map { (role, _) -> role }

/** 把失败的输入角色拼成一句可操作提示；空列表返回 null。 */
internal fun staleGenerationInputWarning(roles: List<ImageInputRole>): String? {
    if (roles.isEmpty()) return null
    val labels = roles.joinToString("、") { it.fieldLabel }
    return "已清除失效的$labels，请重新选择后再生成。"
}
