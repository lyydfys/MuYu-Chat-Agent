package com.muyuchat.core.designsystem

/**
 * Shared accessibility action vocabulary.
 *
 * The chat workspace and the shared design-system components describe the same action in several
 * places (icon buttons, overlay scrims, menu rows). Keeping the verbs in one place stops the same
 * action from being announced as "关闭" in one surface and "关闭菜单" in another purely by drift.
 *
 * These are accessible labels, not visible text: changing a constant changes what TalkBack says,
 * not what is drawn, so the frozen visual contract is untouched. Context-specific names are still
 * allowed ("关闭研究模式选择", "复制代码"); they must start from the canonical verb below.
 */
public object McaActionLabels {
    /** Close/dismiss a layer that has no more specific subject. */
    public const val CLOSE: String = "关闭"

    /** Cancel a transient dialog or a pending non-destructive action. */
    public const val CANCEL: String = "取消"

    /** Retry a failed load or render (not "重新生成", which repeats a model turn). */
    public const val RETRY: String = "重试"

    /** Copy the current subject to the clipboard. */
    public const val COPY: String = "复制"

    /** Ask the model for another answer to the same turn. */
    public const val REGENERATE: String = "重新生成"

    /** Start a normal send. */
    public const val SEND: String = "发送"

    /** Stop the in-flight generation. */
    public const val STOP: String = "停止"

    /** Open the overflow menu of the current surface. */
    public const val OPEN_MORE: String = "打开更多操作"
}
