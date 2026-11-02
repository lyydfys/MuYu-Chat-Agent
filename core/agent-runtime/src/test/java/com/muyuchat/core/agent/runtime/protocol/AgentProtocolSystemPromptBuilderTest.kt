package com.muyuchat.core.agent.runtime.protocol

import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolSystemPromptBuilderTest {

    private val descriptor = AgentV1ToolSpecs.descriptor(
        AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        SupportedToolSchemaValidator()
    )

    @Test
    fun nativePromptForbidsJsonEnvelopesAndDescribesFunctionCalling() {
        val prompt = AgentNativeSystemPromptBuilder().build(
            listOf(descriptor),
            policyVersion = 1,
            budgetSummaryJson = "{}",
            protocolMode = AgentProtocolMode.NATIVE
        )
        assertTrue(prompt.contains("NATIVE tool calls"))
        assertTrue(prompt.contains("Never print JSON control envelopes"))
        assertFalse("the native prompt must not teach the prompted envelope", prompt.contains("mca.agent.v1"))
        assertTrue(prompt.contains(descriptor.toolKey))
    }

    @Test
    fun dispatcherSelectsPromptedForPromptedRuns() {
        val prompt = AgentProtocolSystemPromptBuilder().build(
            listOf(descriptor),
            policyVersion = 1,
            budgetSummaryJson = "{}",
            protocolMode = AgentProtocolMode.PROMPTED_V1
        )
        assertTrue(prompt.contains("mca.agent.v1"))
        assertFalse(prompt.contains("NATIVE tool calls"))
    }
}
