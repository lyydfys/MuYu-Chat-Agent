package com.muyuchat.mca

import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.feature.settings.AgentApprovalPresetUi
import com.muyuchat.mca.agent.security.ToolApprovalMode
import com.muyuchat.mca.agent.security.ToolApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the three approval tiers the settings card offers.
 *
 * The card itself needs Compose, but the part that can actually be wrong — "does choosing a chip
 * produce the policy the description promises?" — is pure. Each case therefore asserts the tier's
 * *behaviour* through [ToolApprovalPolicy.decide], not just the object identity, so a future preset
 * edit that contradicts its own sentence fails here.
 */
class AgentApprovalPolicyPresetsTest {

    @Test
    fun `the cautious tier asks for every tool that has something to approve`() {
        val policy = policyFor(AgentApprovalPresetUi.CAUTIOUS)

        assertEquals(ToolApprovalMode.ASK, policy.decide("device.screen", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT))
        assertEquals(ToolApprovalMode.ASK, policy.decide("http.get", AgentToolSideEffect.NETWORK))
        assertEquals(ToolApprovalMode.ASK, policy.decide("workspace.write_text", AgentToolSideEffect.WORKSPACE_WRITE))
        // The documented exception: a read has no action to approve.
        assertEquals(ToolApprovalMode.ALLOW, policy.decide("workspace.read_text", AgentToolSideEffect.READ_ONLY))
        // Unknown tools must never be implicitly allowed.
        assertEquals(ToolApprovalMode.ASK, policy.decide("mystery.tool", AgentToolSideEffect.WORKSPACE_WRITE))
    }

    @Test
    fun `the money-only tier still confirms screen network and terminal`() {
        val policy = policyFor(AgentApprovalPresetUi.ASK_MONEY_ONLY)

        assertEquals(ToolApprovalMode.ASK, policy.decide("device.screen", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT))
        assertEquals(ToolApprovalMode.ASK, policy.decide("http.get", AgentToolSideEffect.NETWORK))
        assertEquals(ToolApprovalMode.ASK, policy.decide("terminal.exec", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT))
        // A tool the preset has never heard of is caught by its side-effect class.
        assertEquals(ToolApprovalMode.ASK, policy.decide("brand.new.tool", AgentToolSideEffect.NETWORK))
        assertEquals(ToolApprovalMode.ASK, policy.decide("brand.new.tool", AgentToolSideEffect.EXTERNAL_SIDE_EFFECT))
        // Everything else runs.
        assertEquals(ToolApprovalMode.ALLOW, policy.decide("workspace.read_text", AgentToolSideEffect.READ_ONLY))
        assertEquals(ToolApprovalMode.ALLOW, policy.decide("workspace.write_text", AgentToolSideEffect.WORKSPACE_WRITE))
    }

    @Test
    fun `the autonomous tier never asks`() {
        val policy = policyFor(AgentApprovalPresetUi.AUTONOMOUS)

        AgentToolSideEffect.entries.forEach { sideEffect ->
            assertEquals(
                "autonomous must run $sideEffect without asking",
                ToolApprovalMode.ALLOW,
                policy.decide("device.screen", sideEffect)
            )
        }
        assertTrue(policy.askOnSideEffects.isEmpty())
        assertTrue(policy.perToolOverrides.isEmpty())
    }

    @Test
    fun `the three presets are distinct policies and all three are offered`() {
        val policies = AgentApprovalPresetUi.entries.map(::policyFor)

        assertEquals(3, AgentApprovalPresetUi.entries.size)
        assertEquals(3, policies.distinct().size)
        assertEquals(AgentApprovalPresetUi.entries, AgentApprovalPolicyPresets.all.map { it.first })
    }

    @Test
    fun `every preset resolves back to its own tier`() {
        AgentApprovalPresetUi.entries.forEach { preset ->
            val policy = policyFor(preset)
            assertTrue("$preset must be recognised as a preset", AgentApprovalPolicyPresets.isPreset(policy))
            assertEquals(preset, AgentApprovalPolicyPresets.presetFor(policy))
        }
    }

    @Test
    fun `a hand-edited policy is not reported as one of the presets`() {
        val edited = ToolApprovalPolicy.PRESET_CAUTIOUS.withOverride("http.get", ToolApprovalMode.FORBID)

        assertFalse(AgentApprovalPolicyPresets.isPreset(edited))
        // The safe reading: the card shows the default tier instead of claiming a preset the user
        // no longer has.
        assertEquals(AgentApprovalPresetUi.DEFAULT, AgentApprovalPolicyPresets.presetFor(edited))
    }

    @Test
    fun `stored tier ids are stable wire values shared with the feature module`() {
        assertEquals("cautious", AgentApprovalPresetUi.CAUTIOUS.id)
        assertEquals("ask_money_only", AgentApprovalPresetUi.ASK_MONEY_ONLY.id)
        assertEquals("autonomous", AgentApprovalPresetUi.AUTONOMOUS.id)
        // Unknown/absent ids fall back to the default tier instead of throwing.
        assertEquals(AgentApprovalPresetUi.DEFAULT, AgentApprovalPresetUi.of(null))
        assertEquals(AgentApprovalPresetUi.DEFAULT, AgentApprovalPresetUi.of("legacy"))
        AgentApprovalPresetUi.entries.forEach { preset ->
            assertEquals(preset, AgentApprovalPresetUi.of(preset.id))
        }
    }

    @Test
    fun `each tier carries a non-empty title and one-line explanation`() {
        AgentApprovalPresetUi.entries.forEach { preset ->
            assertTrue("$preset title", preset.title.isNotBlank())
            assertTrue("$preset description", preset.description.isNotBlank())
        }
        // The card shows titles as chips, so they must stay short enough to fit a flow row.
        AgentApprovalPresetUi.entries.forEach { preset ->
            assertTrue("$preset title is too long: ${preset.title}", preset.title.length <= 12)
        }
        assertNotEquals(
            AgentApprovalPresetUi.CAUTIOUS.description,
            AgentApprovalPresetUi.AUTONOMOUS.description
        )
    }

    @Test
    fun `mode labels cover every approval mode`() {
        ToolApprovalMode.entries.forEach { mode ->
            assertTrue(AgentApprovalPolicyPresets.modeLabel(mode).isNotBlank())
        }
    }

    private fun policyFor(preset: AgentApprovalPresetUi): ToolApprovalPolicy =
        AgentApprovalPolicyPresets.policyFor(preset)
}
