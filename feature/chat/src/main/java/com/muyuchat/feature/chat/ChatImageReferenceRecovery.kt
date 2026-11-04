package com.muyuchat.feature.chat

/**
 * 历史消息图片引用的降级与清理规则（纯函数，UI 与 app 会话加载共用）。
 *
 * 事故背景：一条纯文本消息下方出现红色「图片读取失败 … image_assets/... ENOENT」。根因不在这次
 * 输入，而是**历史消息里留有已删除图片的引用**，执行层把这条历史消息的图片一起内联/压缩时抛错，
 * 又把错误当成「本次操作失败」挂到了当前消息上。
 *
 * 规则：
 * 1. 只有消息自己声明过图片（`imageAttachments` 非空）才进入图片校验/加载路径；
 * 2. 历史消息缺失的引用直接剔除（不删消息），缺失本身不是错误；
 * 3. 只有「本轮用户消息自己声明的图片不可读」才是可操作错误；
 * 4. 缺失提示永远是一行浅色文字，不升级成红色错误。
 */

/** 一个图片引用的最小描述；与 core 的 ChatImageAttachment 解耦，便于纯 JVM 测试。 */
data class ChatImageReference(
    val uriString: String,
    /** 引用内联了 base64（data URL / vision payload），不依赖本地文件。 */
    val hasInlineData: Boolean = false,
)

/** 引用可用性探针结果。 */
enum class ChatImageReferenceState {
    READABLE,
    MISSING,
    UNREADABLE,
}

/** 剔除结果：保持原顺序，只移除确认缺失的引用。 */
data class ChatImageReferencePrune(
    val kept: List<ChatImageReference>,
    val dropped: List<ChatImageReference>,
) {
    val changed: Boolean get() = dropped.isNotEmpty()
}

/**
 * 剔除确认缺失的引用；内联数据与探针判定可读的引用原样保留。
 *
 * [exists] 只对没有内联数据的引用调用，因此纯文本消息（列表为空）不会触发任何校验。
 */
fun pruneMissingImageRefs(
    references: List<ChatImageReference>,
    exists: (String) -> Boolean,
): ChatImageReferencePrune {
    if (references.isEmpty()) return ChatImageReferencePrune(emptyList(), emptyList())
    val kept = ArrayList<ChatImageReference>(references.size)
    val dropped = ArrayList<ChatImageReference>()
    references.forEach { reference ->
        if (reference.hasInlineData || exists(reference.uriString)) {
            kept += reference
        } else {
            dropped += reference
        }
    }
    return ChatImageReferencePrune(kept = kept, dropped = dropped)
}

/**
 * 是否应该给用户一个可操作的图片恢复错误。
 *
 * 只有本轮消息自己声明过图片、且这张图片不可读时才成立；历史缺失图一律降级，不打断本轮。
 */
fun shouldOfferImageRecovery(
    hasDeclaredImage: Boolean,
    state: ChatImageReferenceState,
): Boolean = hasDeclaredImage && state != ChatImageReferenceState.READABLE

/**
 * 缺失图片的浅色提示；消息还有正文或其它图片时不显示（避免在正文下方重复一行脚注）。
 */
fun imageAbsenceHint(hasOtherContent: Boolean): String? =
    if (hasOtherContent) null else "图片已丢失"

/** 消息是否声明过图片输入；为 false 时调用方不得进入任何图片加载/校验路径。 */
fun declaresImageInput(references: List<ChatImageReference>): Boolean = references.isNotEmpty()
