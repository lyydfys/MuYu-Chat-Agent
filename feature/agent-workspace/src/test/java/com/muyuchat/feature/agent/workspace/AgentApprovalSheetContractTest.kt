package com.muyuchat.feature.agent.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STATIC source contract for the approval sheet.
 *
 * This is not a rendering or interaction proof. It guards the safety-critical text/controls that
 * the UI must keep: the existing test tag, the one-shot allow/deny decisions, the bounded source
 * and bound-identity rows, and the absence of any persistent/automatic approval affordance.
 */
class AgentApprovalSheetContractTest {

    @Test
    fun `sheet keeps its existing tag and one-shot action tags`() {
        val sheet = source(SHEET)

        assertTrue(sheet.contains("testTag(\"agent.approval.sheet\")"))
        assertTrue(sheet.contains("testTag(\"agent.approval.source\")"))
        assertTrue(sheet.contains("testTag(\"agent.approval.binding\")"))
        assertTrue(sheet.contains("testTag(\"agent.approval.deny\")"))
        assertTrue(sheet.contains("testTag(\"agent.approval.allowOnce\")"))
        assertEquals(2, occurrences(sheet, "onDecision("))
    }

    @Test
    fun `sheet offers only allow-once and deny`() {
        val sheet = source(SHEET)

        assertTrue(sheet.contains("AgentApprovalDecision.ALLOW_ONCE"))
        assertTrue(sheet.contains("AgentApprovalDecision.DENY"))
        assertTrue(sheet.contains("Text(\"允许一次\")"))
        assertTrue(sheet.contains("Text(\"拒绝\")"))
        assertFalse(sheet.contains("ALLOW_ALWAYS"))
        assertFalse(sheet.lowercase().contains("always allow"))
        assertFalse(sheet.contains("始终允许"))
        assertFalse(sheet.contains("总是允许"))
        assertFalse(sheet.contains("永久允许"))
        assertFalse(sheet.contains("全部允许"))
        assertFalse(sheet.contains("自动允许"))
    }

    @Test
    fun `sheet shows tool action source expiry and bound identity`() {
        val sheet = source(SHEET)

        assertTrue(sheet.contains("approval.toolLabel"))
        assertTrue(sheet.contains("approval.actionLabel"))
        assertTrue(sheet.contains("approval.source.label"))
        assertTrue(sheet.contains("approval.expiresText"))
        assertTrue(sheet.contains("approval.principal"))
        assertTrue(sheet.contains("approval.approvalIdShort"))
        assertTrue(sheet.contains("approval.invocationIdShort"))
        assertTrue(sheet.contains("approval.runEpoch"))
        assertTrue(sheet.contains("approval.policyVersion"))
    }

    @Test
    fun `expired or consumed approval is never actionable`() {
        val sheet = source(SHEET)

        assertEquals(2, occurrences(sheet, "enabled = !inFlight && approval.actionable"))
        assertTrue(sheet.contains("if (approval.expired)"))
    }

    @Test
    fun `timeline row renders tool state and bounded source`() {
        val row = source(TIMELINE_ROW)

        assertTrue(row.contains("row.toolState?.label"))
        assertTrue(row.contains("row.source?.label"))
        assertTrue(row.contains("maxLines = 1"))
    }

    @Test
    fun `inline card shows the same bounded source targets and identity`() {
        val card = source(INLINE_CARD)

        assertTrue(card.contains("testTag(\"agent.approval.inline\")"))
        assertTrue(card.contains("approval.toolLabel"))
        assertTrue(card.contains("approval.actionLabel"))
        assertTrue(card.contains("approval.source.label"))
        assertTrue(card.contains("approval.readTargets"))
        assertTrue(card.contains("approval.writeTargets"))
        assertTrue(card.contains("approval.networkTargets"))
        assertTrue(card.contains("approval.expiresText"))
        assertTrue(card.contains("approval.principal"))
        assertFalse(card.lowercase().contains("always allow"))
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun occurrences(source: String, needle: String): Int = source.split(needle).size - 1

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SHEET =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentApprovalSheet.kt"
        const val INLINE_CARD =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentApprovalCard.kt"
        const val TIMELINE_ROW =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentTimelineRowItem.kt"
    }
}
