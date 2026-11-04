package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级回归：图片缺失的“分级”必须落在真实链路上，而不是只留在纯函数里。
 *
 * 事故：历史消息悬空图片让每次纯文本发送都追加红色「图片读取失败」。纯函数测试覆盖判定规则，
 * 这里固定三个真实接线点：执行层按“本轮/历史”分级、会话加载剔除缺失引用、消息气泡降级提示。
 */
class ChatImageFailureGradingContractTest {

    @Test
    fun `execution grades image failures instead of failing the whole text turn`() {
        val execution = source("app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt")
        assertTrue(
            "execution must audit the admitted messages",
            execution.contains("auditChatImageAttachments(admittedRequestMessages)"),
        )
        assertTrue(
            "only the current turn's own unreadable image may be an actionable error",
            execution.contains("imageAudit.currentTurnNeedsRecovery"),
        )
        assertTrue(
            "the sanitized list must drive the image path",
            execution.contains("val imageSafeAdmittedMessages = imageAudit.messages") &&
                execution.contains("imageSafeAdmittedMessages.any { it.imageAttachments.isNotEmpty() }"),
        )
        assertTrue(
            "cloud/local conversion must use the sanitized turn messages",
            execution.contains("var turnMessages = imageSafeAdmittedMessages"),
        )
        assertTrue(
            "history-only image trouble must degrade to a text turn",
            execution.contains("if (!imageAudit.currentTurnDeclaresImage)") &&
                execution.contains("messagesWithoutImageAttachments(turnMessages)"),
        )
        assertFalse(
            "the old non-graded error text must not come back",
            execution.contains("，或检查文件权限。"),
        )
    }

    @Test
    fun `session loading prunes dangling image references without deleting messages`() {
        val store = source("app/src/main/java/com/muyuchat/mca/ChatSessionStore.kt")
        assertTrue(
            "history load must prune unreadable attachments",
            store.contains("pruneStoredChatImageAttachments("),
        )
        assertTrue(
            "non-DONE generated image ids are pruned against the library",
            store.contains("pruneMissingGeneratedImageAssetIds("),
        )
        assertTrue(
            "only changed history is rewritten",
            store.contains("if (boundedRecords != ownedRecords)") &&
                store.contains("database.chatSessionDao().replaceAll(boundedRecords)"),
        )
    }

    @Test
    fun `message bubbles degrade a missing image to a quiet hint`() {
        val bubble = source(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMessageBubbleScreens.kt"
        )
        assertTrue(
            "the failed attachment must show the quiet absence hint",
            bubble.contains("imageAbsenceHint("),
        )
        assertFalse(
            "the bubble must not render a red image-read error",
            bubble.contains("图片读取失败"),
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("source not found from ${System.getProperty("user.dir")}: $relative")
    }
}
