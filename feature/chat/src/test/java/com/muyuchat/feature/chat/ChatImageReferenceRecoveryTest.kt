package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史图片引用的降级规则（纯函数合同）。
 *
 * 事故：纯文本消息下方出现红色「图片读取失败 … image_assets/... open failed: ENOENT」。
 * 修复要求：没有声明图片的消息不进入校验；历史缺失引用静默剔除；缺失只给浅色提示；
 * 只有“本轮用户自己选的图不可读”才值得可操作错误。
 */
class ChatImageReferenceRecoveryTest {

    @Test
    fun `a message without image references never runs the probe`() {
        var probes = 0
        val prune = pruneMissingImageRefs(emptyList()) {
            probes += 1
            true
        }
        assertFalse(declaresImageInput(emptyList()))
        assertFalse(prune.changed)
        assertTrue(prune.kept.isEmpty())
        assertEquals(0, probes)
    }

    @Test
    fun `a missing historical reference is dropped without an actionable error`() {
        val prune = pruneMissingImageRefs(
            listOf(ChatImageReference("file:///data/files/image_assets/gone.png")),
            exists = { false },
        )
        assertEquals(1, prune.dropped.size)
        assertTrue(prune.kept.isEmpty())
        assertTrue(prune.changed)
        // 历史消息不是“本轮声明”，因此即使引用缺失也不升级成可操作错误。
        assertFalse(
            shouldOfferImageRecovery(
                hasDeclaredImage = false,
                state = ChatImageReferenceState.MISSING,
            ),
        )
        assertEquals("图片已丢失", imageAbsenceHint(hasOtherContent = false))
    }

    @Test
    fun `a readable reference is kept untouched`() {
        val ref = ChatImageReference("file:///data/files/image_assets/ok.png")
        val prune = pruneMissingImageRefs(listOf(ref)) { true }
        assertEquals(listOf(ref), prune.kept)
        assertTrue(prune.dropped.isEmpty())
        assertFalse(prune.changed)
    }

    @Test
    fun `partial loss drops only the missing references and keeps order`() {
        val first = ChatImageReference("content://provider/first")
        val gone = ChatImageReference("content://provider/gone")
        val last = ChatImageReference("content://provider/last")
        val prune = pruneMissingImageRefs(listOf(first, gone, last)) { it != gone.uriString }
        assertEquals(listOf(first, last), prune.kept)
        assertEquals(listOf(gone), prune.dropped)
    }

    @Test
    fun `pruning is idempotent and a second pass is a no-op`() {
        val gone = ChatImageReference("file:///gone.png")
        val kept = ChatImageReference("file:///kept.png")
        val first = pruneMissingImageRefs(listOf(gone, kept)) { it == kept.uriString }
        val second = pruneMissingImageRefs(first.kept) { it == kept.uriString }

        assertTrue(first.changed)
        assertFalse(second.changed)
        assertTrue(second.dropped.isEmpty())
        assertEquals(first.kept, second.kept)
    }

    @Test
    fun `inline image data survives even without a readable local path`() {
        val inline = ChatImageReference("", hasInlineData = true)
        var probes = 0
        val prune = pruneMissingImageRefs(listOf(inline)) {
            probes += 1
            false
        }
        assertEquals(listOf(inline), prune.kept)
        assertEquals(0, probes)
    }

    @Test
    fun `absence hint is hidden when the message still has text or another image`() {
        assertNull(imageAbsenceHint(hasOtherContent = true))
        assertEquals("图片已丢失", imageAbsenceHint(hasOtherContent = false))
    }

    @Test
    fun `recovery is offered only for the current turn's own unreadable image`() {
        assertFalse(shouldOfferImageRecovery(false, ChatImageReferenceState.UNREADABLE))
        assertFalse(shouldOfferImageRecovery(true, ChatImageReferenceState.READABLE))
        assertTrue(shouldOfferImageRecovery(true, ChatImageReferenceState.UNREADABLE))
        assertTrue(shouldOfferImageRecovery(true, ChatImageReferenceState.MISSING))
    }

    @Test
    fun `declared image input is reported only when references exist`() {
        assertTrue(declaresImageInput(listOf(ChatImageReference("file:///a.png"))))
        assertFalse(declaresImageInput(emptyList()))
    }
}
