package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ChatImageReferenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息级图片审计合同：纯文本消息零校验；历史缺失图静默剔除；本轮自己选的图不可读才可操作。
 *
 * 事故复现：旧会话里某条用户消息的图片文件已被删除，之后发送纯文本消息时，执行层把历史图片
 * 一起内联/压缩 → ENOENT → 红色错误挂到当前消息。审计必须先区分“历史”和“本轮”。
 */
class ChatImageAttachmentAuditTest {

    @Test
    fun `pure text messages never probe and never request recovery`() {
        var probes = 0
        val messages = listOf(
            message(Role.USER, "第一句纯文本"),
            message(Role.ASSISTANT, "回答"),
            message(Role.USER, "再问一句"),
        )
        val result = auditChatImageAttachments(messages) {
            probes += 1
            ChatImageReferenceState.MISSING
        }
        assertEquals(0, probes)
        assertFalse(result.currentTurnDeclaresImage)
        assertFalse(result.currentTurnNeedsRecovery)
        assertEquals(0, result.droppedHistoryReferences)
        assertEquals(messages, result.messages)
    }

    @Test
    fun `a missing history image is dropped without a red error`() {
        val historyImage = attachment("file:///data/files/image_assets/gone.png")
        val messages = listOf(
            message(Role.USER, "看图", listOf(historyImage)),
            message(Role.ASSISTANT, "看到了"),
            message(Role.USER, "这条只是纯文本"),
        )
        val result = auditChatImageAttachments(messages) {
            if (it.uriString == historyImage.uriString) {
                ChatImageReferenceState.MISSING
            } else {
                ChatImageReferenceState.READABLE
            }
        }
        assertTrue(result.messages[0].imageAttachments.isEmpty())
        assertEquals(1, result.droppedHistoryReferences)
        assertFalse(result.currentTurnDeclaresImage)
        assertFalse(result.currentTurnNeedsRecovery)
        // 当前纯文本消息保持原样，不会被挂上任何图片错误。
        assertEquals("这条只是纯文本", result.messages[2].content)
    }

    @Test
    fun `a readable history image is preserved`() {
        val image = attachment("content://provider/photo")
        val messages = listOf(
            message(Role.USER, "看图", listOf(image)),
            message(Role.USER, "继续"),
        )
        val result = auditChatImageAttachments(messages) { ChatImageReferenceState.READABLE }
        assertEquals(listOf(image), result.messages[0].imageAttachments)
        assertEquals(0, result.droppedHistoryReferences)
    }

    @Test
    fun `partial loss removes only missing history images and keeps order`() {
        val first = attachment("content://provider/first")
        val gone = attachment("content://provider/gone")
        val last = attachment("content://provider/last")
        val messages = listOf(
            message(Role.USER, "三张图", listOf(first, gone, last)),
            message(Role.USER, "纯文本"),
        )
        val result = auditChatImageAttachments(messages) {
            if (it.uriString == gone.uriString) ChatImageReferenceState.MISSING
            else ChatImageReferenceState.READABLE
        }
        assertEquals(listOf(first, last), result.messages[0].imageAttachments)
        assertEquals(1, result.droppedHistoryReferences)
    }

    @Test
    fun `the current turn's missing image is actionable and kept for the error path`() {
        val missing = attachment("file:///data/files/image_assets/new-gone.png")
        val messages = listOf(
            message(Role.ASSISTANT, "你好"),
            message(Role.USER, "这张图", listOf(missing)),
        )
        val result = auditChatImageAttachments(messages) { ChatImageReferenceState.MISSING }
        assertTrue(result.currentTurnDeclaresImage)
        assertTrue(result.currentTurnNeedsRecovery)
        assertEquals(0, result.droppedHistoryReferences)
        // 本轮失败附件保留在返回的消息里，调用方据此报可操作错误而不是静默吞掉。
        assertEquals(listOf(missing), result.messages[1].imageAttachments)
    }

    @Test
    fun `the current turn's readable image passes with no recovery`() {
        val image = attachment("content://provider/current")
        val messages = listOf(message(Role.USER, "这张图", listOf(image)))
        val result = auditChatImageAttachments(messages) { ChatImageReferenceState.READABLE }
        assertTrue(result.currentTurnDeclaresImage)
        assertFalse(result.currentTurnNeedsRecovery)
        assertEquals(listOf(image), result.messages[0].imageAttachments)
    }

    @Test
    fun `inline image data never probes the file system`() {
        var probes = 0
        val inline = ChatImageAttachment(
            name = "inline.png",
            uriString = "",
            dataBase64 = "QUJD",
        )
        val messages = listOf(message(Role.USER, "data url", listOf(inline)))
        val result = auditChatImageAttachments(messages) {
            probes += 1
            ChatImageReferenceState.MISSING
        }
        assertEquals(0, probes)
        assertFalse(result.currentTurnNeedsRecovery)
        assertEquals(listOf(inline), result.messages[0].imageAttachments)
    }

    @Test
    fun `auditing already pruned history is a no-op`() {
        val image = attachment("file:///data/files/image_assets/gone.png")
        val messages = listOf(
            message(Role.USER, "看图", listOf(image)),
            message(Role.USER, "纯文本"),
        )
        val first = auditChatImageAttachments(messages) { ChatImageReferenceState.MISSING }
        val second = auditChatImageAttachments(first.messages) { ChatImageReferenceState.MISSING }
        assertTrue(first.droppedHistoryReferences == 1)
        assertEquals(0, second.droppedHistoryReferences)
        assertEquals(first.messages, second.messages)
    }

    @Test
    fun `generated image ids are pruned against the library without touching status`() {
        val request = ChatGeneratedImageRequest(
            id = "req",
            prompt = "一只猫",
            status = ChatGeneratedImageStatus.FAILED,
            imageAssetIds = listOf("kept", "gone"),
        )
        val pruned = pruneMissingGeneratedImageAssetIds(request, setOf("kept"))
        assertEquals(listOf("kept"), pruned.imageAssetIds)
        assertEquals(ChatGeneratedImageStatus.FAILED, pruned.status)
        // 已经干净时原样返回（幂等，不触发写库）。
        assertTrue(pruneMissingGeneratedImageAssetIds(pruned, setOf("kept")) === pruned)
    }

    private fun message(
        role: Role,
        content: String,
        attachments: List<ChatImageAttachment> = emptyList(),
    ): ChatMessage = ChatMessage(
        role = role,
        content = content,
        imageAttachments = attachments,
    )

    private fun attachment(uri: String): ChatImageAttachment = ChatImageAttachment(
        name = uri.substringAfterLast('/'),
        uriString = uri,
        mimeType = "image/png",
    )
}
