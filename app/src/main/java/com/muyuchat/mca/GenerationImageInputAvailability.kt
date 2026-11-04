package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Locale

/**
 * 生图输入引用的提交前可用性判定（app 侧）。
 *
 * 与 feature:chat 的工作台体检互补：工作台在点击「生成」时检查并清掉失效引用；这里在唯一执行
 * owner 的准入处再做一次，覆盖工作台以外的入口（历史重试、Local API、助手工具续跑）。缺失的
 * 输入不再进入队列，从而不会留下一条「任务失败 + 悬空引用」的循环；提示词与模型选择保持不变。
 *
 * 纯函数只接收 `isReadable` 探针，JVM 测试不需要 Context。
 */

internal enum class GenerationInputAssetRole(val fieldLabel: String) {
    INPUT("输入图片"),
    MASK("蒙版"),
    CONTROL("控制图片"),
}

internal data class GenerationInputReference(
    val role: GenerationInputAssetRole,
    val reference: String?,
)

/** 所有非空且探针判定不可读的角色；空引用（该任务不需要这类输入）被忽略。 */
internal fun missingGenerationImageInputRoles(
    references: List<GenerationInputReference>,
    isReadable: (String) -> Boolean,
): List<GenerationInputAssetRole> = references
    .filter { (_, reference) -> !reference.isNullOrBlank() }
    .filterNot { (_, reference) -> isReadable(requireNotNull(reference)) }
    .map { (role, _) -> role }

/** 可操作的中文原因；空列表返回 null。 */
internal fun generationImageInputMissingMessage(
    roles: List<GenerationInputAssetRole>,
): String? {
    if (roles.isEmpty()) return null
    val labels = roles.joinToString("、") { it.fieldLabel }
    return "原任务的${labels}已丢失或无法读取；请重新选择图片后重新生成。"
}

/** 引用是否仍可被本进程读取：file/绝对路径看文件，content 看打开流，data URL 只校验结构。 */
internal fun generationImageReferenceReadable(
    context: Context,
    reference: String?,
): Boolean {
    val value = reference?.trim().orEmpty()
    if (value.isEmpty()) return false
    if (value.startsWith("data:image/", ignoreCase = true)) {
        return value.substringAfter("base64,", missingDelimiterValue = "").isNotBlank()
    }
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
    val appContext = context.applicationContext
    return when (uri.scheme?.lowercase(Locale.US)) {
        "content" -> runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                ?: appContext.contentResolver.openInputStream(uri)?.use { true }
                ?: false
        }.getOrDefault(false)
        "file" -> {
            val file = File(uri.path.orEmpty())
            file.isFile && file.canRead()
        }
        null, "" -> {
            val file = File(value)
            file.isAbsolute && file.isFile && file.canRead()
        }
        else -> false
    }
}

/** 一份 [LocalImageInputDraft] 的全部输入引用（角色顺序固定，便于提示文案稳定）。 */
internal fun localImageInputReferences(
    draft: LocalImageInputDraft,
): List<GenerationInputReference> = listOf(
    GenerationInputReference(GenerationInputAssetRole.INPUT, draft.inputImageReference),
    GenerationInputReference(GenerationInputAssetRole.MASK, draft.maskImageReference),
    GenerationInputReference(GenerationInputAssetRole.CONTROL, draft.controlImageReference),
)
