package com.muyuchat.mca

import android.content.Context
import android.net.Uri
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ChatImageReference
import com.muyuchat.feature.chat.ChatImageReferenceState
import com.muyuchat.feature.chat.pruneMissingImageRefs
import com.muyuchat.feature.chat.shouldOfferImageRecovery
import java.io.File
import java.util.Locale

/**
 * 消息图片引用的执行前审计：区分「本轮用户主动选的图不可读」（可操作错误）与「历史消息里的图
 * 已丢失」（静默降级）。纯逻辑接收探针，JVM 可测；Android 部分只负责把 URI 换成探针结果。
 *
 * 典型事故：会话里某条旧消息还引用 `files/image_assets/...png`，该文件已被图片库删除；之后每次
 * 发送纯文本消息都会把历史图片一起内联，抛 ENOENT，再把红色错误追加到当前消息下面。
 */

internal data class ChatImageAuditResult(
    val messages: List<ChatMessage>,
    val currentTurnDeclaresImage: Boolean,
    val currentTurnState: ChatImageReferenceState,
    /** 历史消息中被静默剔除的引用数（不包含本轮的可操作失败）。 */
    val droppedHistoryReferences: Int,
) {
    /** 只有本轮自己声明的图片不可读时才应显示可操作错误。 */
    val currentTurnNeedsRecovery: Boolean
        get() = shouldOfferImageRecovery(currentTurnDeclaresImage, currentTurnState)
}

/**
 * 审计一轮 request messages：最后一条用户消息视为「本轮」。
 *
 * - 没有任何消息声明图片 → 不调用探针、不产生提示；
 * - 历史消息缺失的附件被移除（消息本身保留）；
 * - 本轮消息的附件即使不可读也保留在结果里（由调用方决定报错），失败状态通过
 *   [ChatImageAuditResult.currentTurnState] 返回。
 */
internal fun auditChatImageAttachments(
    messages: List<ChatMessage>,
    probe: (ChatImageAttachment) -> ChatImageReferenceState,
): ChatImageAuditResult {
    if (messages.none { it.imageAttachments.isNotEmpty() }) {
        return ChatImageAuditResult(
            messages = messages,
            currentTurnDeclaresImage = false,
            currentTurnState = ChatImageReferenceState.READABLE,
            droppedHistoryReferences = 0,
        )
    }
    val currentTurnIndex = messages.indexOfLast { it.role == Role.USER }
    var currentTurnDeclaresImage = false
    var currentTurnState = ChatImageReferenceState.READABLE
    var droppedHistoryReferences = 0
    val audited = messages.mapIndexed { index, message ->
        if (message.imageAttachments.isEmpty()) return@mapIndexed message
        val isCurrentTurn = index == currentTurnIndex
        if (isCurrentTurn) currentTurnDeclaresImage = true
        val kept = ArrayList<ChatImageAttachment>(message.imageAttachments.size)
        message.imageAttachments.forEach { attachment ->
            val state = if (attachment.hasInlineData) {
                ChatImageReferenceState.READABLE
            } else {
                probe(attachment)
            }
            if (state == ChatImageReferenceState.READABLE) {
                kept += attachment
            } else if (isCurrentTurn) {
                // 本轮附件保留在结果中，让调用方按需报可操作错误；不会被悄悄吞掉。
                kept += attachment
                if (currentTurnState == ChatImageReferenceState.READABLE) {
                    currentTurnState = state
                }
            } else {
                droppedHistoryReferences += 1
            }
        }
        if (kept.size == message.imageAttachments.size) {
            message
        } else {
            message.copy(imageAttachments = kept)
        }
    }
    return ChatImageAuditResult(
        messages = audited,
        currentTurnDeclaresImage = currentTurnDeclaresImage,
        currentTurnState = currentTurnState,
        droppedHistoryReferences = droppedHistoryReferences,
    )
}

/** Android 侧引用探针：只回答可读/缺失/存在但不可读，不抛异常、不读整图。 */
internal fun chatImageAttachmentReadState(
    context: Context,
    attachment: ChatImageAttachment,
): ChatImageReferenceState {
    if (attachment.hasInlineData) return ChatImageReferenceState.READABLE
    return chatImageReferenceReadState(context, attachment.uriString)
}

internal fun chatImageReferenceReadState(
    context: Context,
    reference: String?,
): ChatImageReferenceState {
    val value = reference?.trim().orEmpty()
    if (value.isEmpty()) return ChatImageReferenceState.MISSING
    val uri = runCatching { Uri.parse(value) }.getOrNull()
        ?: return ChatImageReferenceState.MISSING
    val appContext = context.applicationContext
    return when (uri.scheme?.lowercase(Locale.US)) {
        "content" -> runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
                ?: appContext.contentResolver.openInputStream(uri)?.use { true }
                ?: false
        }.fold(
            onSuccess = { readable ->
                if (readable) ChatImageReferenceState.READABLE else ChatImageReferenceState.MISSING
            },
            onFailure = { failure ->
                if (failure is SecurityException) {
                    ChatImageReferenceState.UNREADABLE
                } else {
                    ChatImageReferenceState.MISSING
                }
            },
        )
        "file" -> fileImageReferenceReadState(File(uri.path.orEmpty()))
        null, "" -> fileImageReferenceReadState(File(value))
        // 未知 scheme 不猜测、不剔除：可能是外部 provider 的合法引用。
        else -> ChatImageReferenceState.READABLE
    }
}

private fun fileImageReferenceReadState(file: File): ChatImageReferenceState = when {
    !file.exists() -> ChatImageReferenceState.MISSING
    !file.isFile || !file.canRead() -> ChatImageReferenceState.UNREADABLE
    else -> ChatImageReferenceState.READABLE
}

/** 会话加载/保存前剔除本地文件已丢失的附件；内联数据与未知 scheme 原样保留。 */
internal fun pruneStoredChatImageAttachments(
    context: Context,
    attachments: List<ChatImageAttachment>,
): List<ChatImageAttachment> {
    if (attachments.isEmpty()) return attachments
    val prune = pruneMissingImageRefs(
        references = attachments.map { attachment ->
            ChatImageReference(
                uriString = attachment.uriString,
                hasInlineData = attachment.hasInlineData,
            )
        },
        exists = { uriString ->
            chatImageReferenceReadState(context, uriString) == ChatImageReferenceState.READABLE
        },
    )
    if (!prune.changed) return attachments
    val droppedUris = prune.dropped.mapTo(HashSet(), ChatImageReference::uriString)
    return attachments.filterNot { attachment ->
        !attachment.hasInlineData && attachment.uriString in droppedUris
    }
}

/**
 * 生图请求里引用的图片资产已不在图片库时剔除对应 id；状态与消息正文不动。
 *
 * 历史 DONE 记录可能来自旧版本（失败/取消未清 id），渲染层按 id 找不到资产时不应继续保留悬空 id。
 */
internal fun pruneMissingGeneratedImageAssetIds(
    request: ChatGeneratedImageRequest,
    existingImageIds: Set<String>,
): ChatGeneratedImageRequest {
    if (request.imageAssetIds.isEmpty()) return request
    val kept = request.imageAssetIds.filter { it in existingImageIds }
    return if (kept.size == request.imageAssetIds.size) {
        request
    } else {
        request.copy(imageAssetIds = kept)
    }
}
