package com.muyuchat.feature.agent.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Names stay on the existing callback-owned switches; runtime TalkBack verification is separate. */
class AgentSwitchAccessibilityContractTest {
    @Test
    fun `model network switch keeps its callback and gains its visible action name`() {
        val source = source("AgentModelPickerSheet.kt")
        assertTrue(
            Regex(
                """McaSwitch\(\s*checked = models\.networkEnabled,\s*""" +
                    """onCheckedChange = onNetworkEnabledChange,\s*contentDescription = "允许联网"\s*\)"""
            ).containsMatchIn(source)
        )
    }

    @Test
    fun `local server switch keeps busy gating and names the existing action node`() {
        val source = source("AgentMcpScreen.kt")
        assertTrue(
            Regex(
                """McaSwitch\(\s*checked = draft\.allowLocal,\s*onCheckedChange = onAllowLocalChange,\s*""" +
                    """enabled = !busy,\s*contentDescription = "本地服务器","""
            ).containsMatchIn(source)
        )
        assertTrue(source.contains("testTag(\"mcp.add.local\")"))
    }

    private fun source(fileName: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val file = File(
                directory,
                "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/$fileName"
            )
            if (file.isFile) return file.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate Agent switch source: $fileName")
    }
}
