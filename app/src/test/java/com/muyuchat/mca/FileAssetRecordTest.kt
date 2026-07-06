package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileAssetRecordTest {
    @Test
    fun fileAssetPreviewUsesCompactReadableText() {
        val asset = FileAssetRecord(
            name = "notes.md",
            text = "\n\n# MCA\n\n  本地聊天 + 文件上下文  \n\n第二段内容"
        )

        assertEquals("# MCA 本地聊天 + 文件上下文 第二段内容", asset.preview)
    }

    @Test
    fun fileAssetInputAttachmentKeepsTruncatedHint() {
        val asset = FileAssetRecord(
            name = "large.json",
            text = """{"hello":"world"}""",
            truncated = true
        )

        val attachment = asset.toInputAttachment()

        assertTrue(attachment.startsWith("【上传文件：large.json】"))
        assertTrue(attachment.contains("""{"hello":"world"}"""))
        assertTrue(attachment.contains("已截取前 64KB"))
    }
}
