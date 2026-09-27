package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ChatImageAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatImageAttachmentSubmissionTest {
    @Test
    fun differentContentUrisWithSameBytesCollapseBeforeMessageSubmission() {
        val input = """这是什么？

【上传图片：photo-a.jpg】
content://picker/images/a

【上传图片：photo-b.jpg】
content://picker/duplicate"""
        val normalized = deduplicateChatImageAttachmentMarkers(input) { uri ->
            when (uri) {
                "content://picker/images/a",
                "content://picker/duplicate" -> "file-bytes:same-image"
                else -> null
            }
        }

        assertEquals(1, chatImageAttachmentMarkerCount(normalized))
        assertTrue(normalized.contains("photo-a.jpg"))
        assertFalse(normalized.contains("photo-b.jpg"))
        assertTrue(normalized.startsWith("这是什么？"))
    }

    @Test
    fun bubbleDedupPreservesDifferentContentAndCollapsesSameBytes() {
        val attachments = listOf(
            ChatImageAttachment(name = "first.jpg", uriString = "content://picker/1"),
            ChatImageAttachment(name = "second.jpg", uriString = "content://picker/2"),
            ChatImageAttachment(name = "different.jpg", uriString = "content://picker/3")
        )
        val contentIdentity = mapOf(
            "content://picker/1" to "file-bytes:identical",
            "content://picker/2" to "file-bytes:identical",
            "content://picker/3" to "file-bytes:different"
        )

        assertEquals(
            listOf("first.jpg", "different.jpg"),
            deduplicateChatImageAttachments(attachments) { contentIdentity[it] }
                .map(ChatImageAttachment::name)
        )
    }

    @Test
    fun inaccessibleDifferentContentUrisAreKeptRatherThanRiskDroppingAnImage() {
        val input = """【上传图片：first.jpg】
content://picker/1
【上传图片：second.jpg】
content://picker/2"""

        val normalized = deduplicateChatImageAttachmentMarkers(input) { null }

        assertEquals(2, chatImageAttachmentMarkerCount(normalized))
        assertTrue(normalized.contains("first.jpg"))
        assertTrue(normalized.contains("second.jpg"))
    }

    @Test
    fun importedFileCopiesWithDifferentPathsCollapseBeforeMessageSubmission() {
        val input = """请识别图片

【上传图片：first.jpg】
file:///data/user/0/com.muyuchat/files/images/first.jpg

【上传图片：second.jpg】
file:///data/user/0/com.muyuchat/files/images/second.jpg"""

        val normalized = deduplicateChatImageAttachmentMarkers(input) { uri ->
            when {
                uri.endsWith("first.jpg") || uri.endsWith("second.jpg") ->
                    "file-bytes:identical-image"
                else -> null
            }
        }

        assertEquals(1, chatImageAttachmentMarkerCount(normalized))
        assertTrue(normalized.contains("first.jpg"))
        assertFalse(normalized.contains("second.jpg"))
    }
}
