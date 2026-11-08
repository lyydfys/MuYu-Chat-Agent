package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelChoice
import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the one decision "agent mode" makes about which model executes a chat turn.
 *
 * The requirement is that both transports work — a loaded local engine and a configured cloud chat
 * model — so most cases are about which one wins and what happens when neither is usable.
 */
class ChatAgentModelBindingTest {

    private fun local(
        modelId: String = "qwen3-4b",
        loader: AgentModelLoadState = AgentModelLoadState.LOADED
    ) = AgentModelChoice(
        providerId = AGENT_LOCAL_PROVIDER_ID,
        modelId = modelId,
        displayName = "Qwen3 4B",
        runtime = "qnn",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        assetFingerprint = "sha256-local",
        availableOffline = true,
        loadState = loader
    )

    private fun cloud(id: String = "rec-1", protocol: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1) =
        AgentModelChoice(
            providerId = "cloud:$id",
            modelId = "gpt-4o-mini",
            displayName = "GPT-4o mini",
            runtime = "cloud",
            protocolMode = protocol,
            assetFingerprint = "sha256-cloud",
            availableOffline = false
        )

    @Test
    fun `a loaded local engine is used when no cloud model is selected`() {
        val binding = ChatAgentModelBinding.resolve(listOf(local()), selectedCloudChatModelId = null)
        assertNotNull(binding)
        assertEquals(AGENT_LOCAL_PROVIDER_ID, binding!!.providerId)
        assertEquals("qwen3-4b", binding.modelId)
        assertEquals("qnn", binding.runtime)
        assertEquals("sha256-local", binding.assetFingerprint)
    }

    @Test
    fun `a configured cloud chat model is used when it is selected`() {
        val binding = ChatAgentModelBinding.resolve(
            listOf(local(), cloud()),
            selectedCloudChatModelId = "rec-1"
        )
        assertNotNull(binding)
        assertEquals("cloud:rec-1", binding!!.providerId)
        assertEquals("gpt-4o-mini", binding.modelId)
        assertEquals("cloud", binding.runtime)
    }

    @Test
    fun `an explicit cloud selection wins over a loaded local engine`() {
        // A user who picked a cloud model must not silently get the local engine just because one is
        // still loaded in the background.
        val binding = ChatAgentModelBinding.resolve(
            listOf(local(), cloud()),
            selectedCloudChatModelId = "rec-1"
        )
        assertEquals("cloud:rec-1", binding?.providerId)
    }

    @Test
    fun `a stale cloud selection falls back to the local engine`() {
        val binding = ChatAgentModelBinding.resolve(
            listOf(local()),
            selectedCloudChatModelId = "deleted-record"
        )
        assertNotNull(binding)
        assertEquals(AGENT_LOCAL_PROVIDER_ID, binding!!.providerId)
    }

    @Test
    fun `an unloaded local model is never used`() {
        // An installed-but-unloaded model has a fingerprint without engine identity; running it would
        // fail inside the run instead of here.
        val binding = ChatAgentModelBinding.resolve(
            listOf(local(loader = AgentModelLoadState.UNLOADED)),
            selectedCloudChatModelId = null
        )
        assertNull(binding)
    }

    @Test
    fun `nothing usable resolves to null so the caller can ask the user`() {
        assertNull(ChatAgentModelBinding.resolve(emptyList(), selectedCloudChatModelId = null))
    }

    @Test
    fun `a model with no speakable protocol is rejected`() {
        val binding = ChatAgentModelBinding.resolve(
            listOf(cloud(protocol = AgentProtocolMode.NONE)),
            selectedCloudChatModelId = "rec-1"
        )
        assertNull(binding)
    }

    @Test
    fun `a blank model id is rejected`() {
        val binding = ChatAgentModelBinding.resolve(
            listOf(local(modelId = "")),
            selectedCloudChatModelId = null
        )
        assertNull(binding)
    }

    @Test
    fun `the protocol mode is carried through unchanged`() {
        // The picker already decided native vs prompted from real evidence; this layer must not
        // re-derive or upgrade it.
        val binding = ChatAgentModelBinding.resolve(
            listOf(cloud(protocol = AgentProtocolMode.NATIVE)),
            selectedCloudChatModelId = "rec-1"
        )
        assertEquals(AgentProtocolMode.NATIVE, binding?.protocolMode)
    }

    @Test
    fun `a local binding keeps the engine identity the run will verify`() {
        val binding = ChatAgentModelBinding.resolve(listOf(local()), selectedCloudChatModelId = null)
        assertEquals("sha256-local", binding?.assetFingerprint)
        assertEquals("Qwen3 4B", binding?.modelDisplayName)
    }
}
