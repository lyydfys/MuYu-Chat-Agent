package com.muyuchat.mca

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// Job update commit and the user-visible failure text for local image generation.
//
// Moved here verbatim from MainViewModel during P2.
//
// The sentences here are what a user reads when local image generation fails or is
// cancelled, and they are the app's error semantics rather than decoration. Each one names a
// concrete condition and a next step -- not enough free memory, verify the model package and
// retry, wait a second and retry -- instead of a generic failure. A cancelleation caused only
// by prompt preparation is distinguished from a real native request. The task requires that
// cancellation and error semantics be preserved, and this file is where most of them live.
//
// withCommittedImageJobUpdate is here for the same reason: preview publication does file I/O
// before reaching this pure boundary, so callbacks may arrive after a newer revision or a
// terminal transition has already won. Keep the compare-and-commit in the same image-jobs map
// operation: stale or duplicate revisions preserve every committed preview field, while every
// terminal state clears the transient preview and cannot be revived by a late callback.
//
// Kept in com.muyuchat.mca: no import changes anywhere. The one private function here,
// imageExecutionContractFailureMessage, is used only inside this file and stays private.
//
// No message text, code, status or branch was edited.

/**
 * Commits a validated preview together with the surrounding job update.
 *
 * Preview publication does file I/O before reaching this pure boundary, so callbacks may arrive
 * after a newer revision or a terminal transition has already won. Keep the compare-and-commit in
 * the same image-jobs map operation: stale/duplicate revisions preserve every committed preview
 * field, while every terminal state clears the transient preview and cannot be revived by a late
 * non-terminal callback.
 */
internal fun ImageGenerationJobRecord.withCommittedImageJobUpdate(
    status: ImageGenerationStatusRecord,
    message: String,
    imageAssetId: String? = null,
    preview: PublishedLocalImagePreview? = null,
    imageAssetIds: List<String> = emptyList()
): ImageGenerationJobRecord {
    val lateNonTerminalAfterTerminal = this.status.terminal && !status.terminal
    // A cancellation request is deliberately non-terminal while the worker unwinds, but it must
    // be a one-way UI transition. A delayed progress callback must not revive its preview or
    // change it back to GENERATING.
    val lateProgressAfterCancellation =
        this.status == ImageGenerationStatusRecord.CANCEL_REQUESTED &&
            status == ImageGenerationStatusRecord.GENERATING
    val ignoredLateUpdate = lateNonTerminalAfterTerminal || lateProgressAfterCancellation
    val committedStatus = if (ignoredLateUpdate) {
        this.status
    } else {
        status
    }
    // 失败/取消的任务不保留任何图片资产引用：部分产物在失败清理时已被删除，若 job 仍带着
    // imageAssetId(s)，详情/重试/历史会继续指向 image_assets 下不存在的文件（ENOENT 循环）。
    val terminalWithoutAssets = committedStatus == ImageGenerationStatusRecord.FAILED ||
        committedStatus == ImageGenerationStatusRecord.CANCELLED
    val committedMessage = if (ignoredLateUpdate) this.message else message
    val committedImageAssetId = when {
        ignoredLateUpdate -> this.imageAssetId
        terminalWithoutAssets -> null
        else -> imageAssetId ?: this.imageAssetId
    }
    val committedImageAssetIds = when {
        ignoredLateUpdate -> this.imageAssetIds
        terminalWithoutAssets -> emptyList()
        imageAssetIds.isNotEmpty() -> imageAssetIds
        imageAssetId != null -> listOf(imageAssetId)
        else -> this.imageAssetIds
    }
    val previewVisible = committedStatus == ImageGenerationStatusRecord.GENERATING
    val committedPreview = preview?.takeIf { candidate ->
        previewVisible && candidate.revision > previewRevision
    }
    return copy(
        status = committedStatus,
        message = committedMessage,
        imageAssetId = committedImageAssetId,
        imageAssetIds = committedImageAssetIds,
        previewUriString = if (!previewVisible) null else {
            committedPreview?.uriString ?: previewUriString
        },
        previewMode = if (!previewVisible) "" else {
            committedPreview?.mode ?: previewMode
        },
        previewStep = if (!previewVisible) 0 else {
            committedPreview?.step ?: previewStep
        },
        previewRevision = if (!previewVisible) 0L else {
            committedPreview?.revision ?: previewRevision
        },
        previewWidth = if (!previewVisible) 0 else {
            committedPreview?.width ?: previewWidth
        },
        previewHeight = if (!previewVisible) 0 else {
            committedPreview?.height ?: previewHeight
        }
    )
}

internal const val LOCAL_IMAGE_PREVIEW_DEGRADED_MESSAGE =
    "实时预览已降级，最终图片继续生成"

internal fun appendLocalImagePreviewDegradationMessage(
    message: String,
    previewFailureCode: String
): String = if (previewFailureCode.isBlank()) {
    message
} else {
    "$message · $LOCAL_IMAGE_PREVIEW_DEGRADED_MESSAGE"
}

internal const val LOCAL_IMAGE_PROMPT_PREPARATION_FALLBACK_MESSAGE =
    "图片提示词准备失败，尚未启动图片生成，请重试。"
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_CODE =
    "image_prompt_translation_cleanup_unconfirmed"
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE =
    "Hy-MT2 worker 尚未确认退出，翻译未完成；图片生成尚未启动。请等待 worker 完全退出后重试。"
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_STATUS =
    "Hy-MT2 worker 正在安全回收；图片生成尚未启动"
internal const val LOCAL_IMAGE_PROMPT_TRANSLATION_CANCELLED_STATUS =
    "Hy-MT2 翻译已取消；worker 已释放，图片生成尚未启动"

internal fun localImagePromptPreparationFailureMessage(error: Exception): String =
    when ((error as? LocalImageProductContractException)?.code) {
        "image_textual_inversion_trigger_missing" ->
            error.message.orEmpty().ifBlank {
                "所选 Textual Inversion 的触发词缺失，尚未启动图片生成。"
            }
        "invalid_image_prompt" ->
            "图片提示词长度或内容无效，尚未启动图片生成。"
        "invalid_image_profile_prompt_language" ->
            "模型默认负向提示词语言不兼容，尚未启动图片生成。"
        "invalid_image_prompt_language_evidence" ->
            "原生中文文本编码器证据文件校验失败，尚未启动图片生成。"
        "image_prompt_unsupported_native_language" ->
            "当前原生多语文本编码器只支持中文汉字、已支持的中文标点和安全 ASCII 提示词语法，尚未启动图片生成。"
        "image_prompt_requires_canonical_english_tags" ->
            "当前模型只接受英文提示词。请将正向和负向提示词改写为英文后重试；本次尚未启动图片生成。"
        "execution_contract_unsupported" ->
            "当前生成设置与模型执行合同不兼容，尚未启动图片生成。"
        "image_prompt_translation_input_too_large" ->
            "离线中译英输入过长，尚未启动图片生成。"
        "image_prompt_translation_input_too_complex" ->
            "离线中译英输入结构过于复杂，尚未启动图片生成。"
        "image_prompt_translation_busy" ->
            "本地离线翻译运行时正忙，尚未启动图片生成，请稍后重试。"
        "image_prompt_translation_unavailable" ->
            "没有可核验的本地翻译模型，尚未启动图片生成。"
        "image_prompt_translation_timeout" ->
            "中文提示词转换超时，尚未启动图片生成。"
        "image_prompt_translation_invalid" ->
            "翻译结果未通过格式或语义一致性校验，尚未启动图片生成。"
        "image_prompt_translation_failed" ->
            "中文提示词转换失败，尚未启动图片生成。"
        LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_CODE ->
            LOCAL_IMAGE_PROMPT_TRANSLATION_RELEASE_UNCONFIRMED_MESSAGE
        else -> LOCAL_IMAGE_PROMPT_PREPARATION_FALLBACK_MESSAGE
    }

/**
 * Converts worker transport failures into an actionable product message. The low-level Binder
 * message is still kept in the worker journal/logs, but showing it verbatim leaves users with no
 * way to tell whether a model is incomplete or the previous disposable worker is still retiring.
 */
internal fun localImageGenerationFailureMessage(error: Throwable): String = when (error) {
    is LocalImageWorkerDisconnectedException -> {
        val detail = error.message.orEmpty()
        if (detail.contains("binding", ignoreCase = true) ||
            detail.contains("disconnected", ignoreCase = true) ||
            detail.contains("null binding", ignoreCase = true)
        ) {
            "本地图像生成 worker 正在退出或重新连接，本次请求未完成。请等待 1 秒后重试；" +
                "如果仍失败，请在模型管理中校验模型包并确认所有组件完整。"
        } else {
            "本地图像生成 worker 进程异常退出，本次请求未完成，可能是模型包不完整或可用内存不足。" +
                "请先释放其他已加载模型和后台应用，再到模型管理中校验模型包后重试；" +
                "校验失败时请重新导入完整模型包。"
        }
    }
    is ImageNativeExecutionContractException -> imageExecutionContractFailureMessage(error.message)
    is LocalImageWorkerRemoteException -> when (error.code.lowercase()) {
        IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID.lowercase(),
        EXECUTION_CONTRACT_MISMATCH.lowercase(),
        "execution_evidence_invalid", "execution_contract_invalid" ->
            imageExecutionContractFailureMessage(error.message)
        else -> {
            val detail = error.message.orEmpty().ifBlank { "未知错误" }
            "本地图像生成引擎返回错误：$detail。" + localImageNativeFailureNextStep(detail)
        }
    }
    is LocalImageWorkerException ->
        "本地图像生成 worker 返回了无效结果，本次请求未完成。下一步：先校验模型包和组件可读性，" +
            "然后重新尝试；仍失败时请重新导入完整模型包。"
    else -> error.message ?: "图片生成模型调用失败，请检查模型包后重试。"
}

private fun imageExecutionContractFailureMessage(detail: String?): String =
    "模型执行结果与应用配置不一致，本次图片未通过校验。" +
        "下一步：请更新应用后重试；仍失败时请反馈模型名称和此错误详情。" +
        "详情：${detail.orEmpty().ifBlank { "执行配置校验失败" }}"

/**
 * Actionable Chinese next step for recognisable QNN native failures (QNN context binary not
 * matching this device, CLIP tokenizer returning zero token ids).  The original English message
 * and its error code stay verbatim in the text above this suffix.  Display text only: this never
 * changes an error code, the worker journal, or a download/import/load/run decision.
 */
private fun localImageNativeFailureNextStep(detail: String): String {
    val normalized = detail.lowercase()
    return when {
        "contextcreatefrombinary failed" in normalized ->
            "该 QNN 模型包通常是为其他骁龙平台编译的（与当前设备的目标 SoC / NPU 运行环境不一致），" +
                "因此无法在本机加载。" +
                "下一步：请在模型页选择面向本机芯片导出的 QNN 包（或重新下载本机匹配的运行包），" +
                "也可以改用其他本地生图引擎（如 stable-diffusion.cpp、MNN）后重试；" +
                "仍失败时请反馈模型名称、应用版本和此原始错误详情（其中含原生错误码）。"
        "qnn clip tokenizer returned 0 ids" in normalized ->
            "QNN 文本编码器没有得到任何提示词 token，通常说明该模型包与本机运行环境不匹配，" +
                "或包内文本编码组件不完整。" +
                "下一步：请在模型页重新校验/重新导入完整模型包，或更换面向本机导出的模型包后重试；" +
                "仍失败时请反馈模型名称、应用版本和此原始错误详情。"
        else ->
            "下一步：重试一次；仍失败时请反馈模型名称、应用版本和此错误详情。"
    }
}

internal fun List<ImageGenerationJobRecord>.withLocalImagePromptPreparationFailureIfActive(
    activeJobId: String?,
    jobId: String,
    message: String
): List<ImageGenerationJobRecord> {
    val currentJob = firstOrNull { job -> job.id == jobId }
    if (activeJobId != jobId || currentJob?.status != ImageGenerationStatusRecord.GENERATING) {
        return this
    }
    return map { job ->
        if (job.id == jobId) {
            job.withCommittedImageJobUpdate(
                status = ImageGenerationStatusRecord.FAILED,
                message = message
            )
        } else {
            job
        }
    }
}
