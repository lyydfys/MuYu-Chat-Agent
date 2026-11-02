package com.muyuchat.mca.agent.inference

import com.muyuchat.mca.CloudApiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-format native-tool declaration that the model choice mapper consumes.
 *
 * The table is the only place a format may claim native tools: Responses is implemented end to end
 * against an auditable controlled provider server, every other format is prompted-only or absent.
 * Anything that flips this table must also change this test, so a silent capability claim (or a
 * silent downgrade described as native) cannot pass review unnoticed.
 */
class AgentNativeToolCapabilityTableTest {

    @Test
    fun onlyResponsesDeclaresNativeToolSupport() {
        val formats = CloudApiFormat.values()
        assertTrue("the format enum must be covered by the table", formats.isNotEmpty())
        formats.forEach { format ->
            val capability = AgentNativeToolCapabilities.forFormat(format)
            assertTrue("${format.name} needs a note", capability.note.isNotBlank())
            assertTrue("${format.name} needs an evidence version", capability.evidenceVersion.isNotBlank())
            assertEquals(
                "${format.name} native support",
                format == CloudApiFormat.OPENAI_RESPONSES,
                capability.supported
            )
            assertEquals(
                "${format.name} controlled-server verification",
                format == CloudApiFormat.OPENAI_RESPONSES,
                capability.controlledVerification
            )
        }
    }

    @Test
    fun theResponsesNoteStillDeclaresTheProviderGap() {
        val responses = AgentNativeToolCapabilities.forFormat(CloudApiFormat.OPENAI_RESPONSES)
        assertTrue(
            "a controlled-server verification is not a provider round; the note must keep saying so",
            responses.note.contains("真实 provider 回合未验证")
        )
        assertTrue(
            "the note must state that failures stay typed instead of downgrading silently",
            responses.note.contains("不静默降级")
        )
    }

    @Test
    fun thePromptedOnlyFormatsSayWhatTheyActuallyCarry() {
        val compatible = AgentNativeToolCapabilities.forFormat(CloudApiFormat.OPENAI_COMPATIBLE)
        assertFalse(compatible.supported)
        assertTrue(
            "chat/completions must say it carries only the prompted envelope",
            compatible.note.contains("prompted")
        )
        assertFalse(
            "chat/completions must not advertise provider-side tools",
            compatible.note.contains("tools 声明")
        )

        val unsupported = AgentNativeToolCapabilities.forFormat(CloudApiFormat.ANTHROPIC)
        assertFalse(unsupported.supported)
        assertFalse(unsupported.controlledVerification)
        assertTrue(
            "a format with no wire must be described as such",
            unsupported.note.contains("尚无原生工具")
        )
    }
}
