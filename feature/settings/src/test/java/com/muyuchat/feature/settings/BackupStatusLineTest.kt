package com.muyuchat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The backup card may only echo status lines its own action produced; the app's global status
 * surface owns everything else (model errors, download notices, DSH failures).
 */
class BackupStatusLineTest {

    @Test
    fun `backup action statuses are shown on the card`() {
        listOf(
            "备份已导出：12 行 / 7 张表（不含聊天正文与凭据）",
            "备份导出失败：无法写入所选位置。",
            "备份校验失败：备份文件过大",
            "可恢复：7 张表 / 12 行；本次只做校验，未改动现有数据",
            "无法恢复：备份版本不受支持：备份版本 2，当前只支持 1"
        ).forEach { message ->
            assertEquals(message.trim(), backupStatusLine(message))
        }
    }

    @Test
    fun `unrelated statuses are not echoed by the backup card`() {
        listOf(
            null,
            "",
            "   ",
            "模型已加载",
            "诊断报告已导出",
            "本机 API 后台服务未就绪，请回到应用前台后重试。"
        ).forEach { message ->
            assertNull(message, backupStatusLine(message))
        }
    }

    @Test
    fun `surrounding whitespace does not hide a backup status`() {
        assertEquals("备份已导出：0 行 / 7 张表", backupStatusLine("  备份已导出：0 行 / 7 张表  "))
    }
}
