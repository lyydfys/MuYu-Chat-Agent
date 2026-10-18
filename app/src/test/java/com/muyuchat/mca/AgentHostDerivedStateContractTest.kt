package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins host-side history parsing outside the hot recomposition and AgentScreen call site. */
class AgentHostDerivedStateContractTest {
    @Test
    fun `agent history formatting is cached by source list and locale`() {
        val source = sourceFile()
        assertTrue(source.contains("remember(state.benchmarkHistory, timestampLocale)"))
        assertTrue(source.contains("remember(state.agentLogs, timestampLocale)"))
        assertTrue(source.contains("remember(state.benchmark) {"))
        assertTrue(source.contains("benchmarkHistory = agentBenchmarkHistory"))
        assertTrue(source.contains("agentDecisionHistory = agentDecisionHistory"))
    }

    @Test
    fun `agent screen construction no longer parses json inline`() {
        val source = sourceFile()
        val callStart = source.indexOf("AgentScreen(")
        val callEnd = source.indexOf("onScan = viewModel::scanAgent", callStart)
        assertTrue(callStart >= 0 && callEnd > callStart)
        assertTrue(source.substring(callStart, callEnd).contains("state = AgentUiState("))
        val call = source.substring(callStart, callEnd)
        assertFalse(call.contains("JSONObject("))
        assertFalse(call.contains("SimpleDateFormat("))
    }

    private fun sourceFile(): String = File(projectRoot(), SOURCE_PATH).readText()

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SOURCE_PATH = "app/src/main/java/com/muyuchat/mca/MainActivity.kt"
    }
}
