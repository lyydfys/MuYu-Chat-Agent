package com.muyuchat.feature.agent

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStaticStatusTagContractTest {

    @Test
    fun benchmarkStatsUseNonInteractiveLabelsWithoutEmptyClickHandlers() {
        val tuning = sourceFile("AgentTuningCards.kt")
        val debug = sourceFile("AgentDebugAndHardwareCards.kt")
        val emptyClick = Regex("""onClick\s*=\s*\{\s*\}""")

        listOf(tuning, debug).forEach { source ->
            assertFalse("Static status labels must not keep an empty onClick", emptyClick.containsMatchIn(source))
            assertFalse("Static status labels must not use a clickable AssistChip", source.contains("AssistChip"))
        }

        listOf(
            "StaticStatusTag(labels.speedText)",
            "StaticStatusTag(labels.firstTokenText)",
            "StaticStatusTag(labels.stabilityText)"
        ).forEach { expected ->
            assertTrue("Missing static benchmark label: $expected", tuning.contains(expected))
        }
        listOf(
            "StaticStatusTag(summary.labels.speedText)",
            "StaticStatusTag(summary.labels.ttftText)"
        ).forEach { expected ->
            assertTrue("Missing static debug label: $expected", debug.contains(expected))
        }
    }

    @Test
    fun staticTagHasNoClickEntryPointAndKeepsNeutralCompactPresentation() {
        val source = sourceFile("AgentTuningCards.kt")
        assertTrue(source.contains("fun StaticStatusTag(text: String, modifier: Modifier = Modifier)"))

        val tagBody = source
            .substringAfter("internal fun StaticStatusTag")
            .substringBefore("@Composable")
        assertFalse("StaticStatusTag must not accept an onClick", tagBody.contains("onClick"))
        assertFalse("StaticStatusTag must not add a card layer", tagBody.contains("Card("))
        assertFalse("StaticStatusTag must not add a shadow", tagBody.contains("shadowElevation"))
        assertFalse("StaticStatusTag must not introduce an accent color", tagBody.contains("primary"))
        assertFalse("StaticStatusTag must not introduce an accent color", tagBody.contains("secondary"))
        assertTrue(tagBody.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(tagBody.contains("heightIn(min = 32.dp)"))
        assertTrue(tagBody.contains("padding(horizontal = 16.dp"))
        assertFalse(tagBody.contains("heightIn(min = 48.dp)"))
        assertTrue(tagBody.contains("MaterialTheme.typography.labelLarge"))
    }

    @Test
    fun realSelectionChipsKeepTheirActions() {
        val source = sourceFile("AgentTuningCards.kt")

        assertTrue(source.contains("onClick = { onPreferenceChange(state.preference.copy(mode = mode)) }"))
        assertTrue(source.contains("onClick = { selectedMode = mode }"))
        assertTrue(source.contains("selected = state.preference.mode == mode"))
    }

    private fun sourceFile(fileName: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/agent/src/main/java/com/muyuchat/feature/agent/$fileName"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate agent source file: $fileName")
    }
}
