package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud editor's streaming switch must change only the streaming field of the pending config;
 * everything else (protocol, base URL, credentials) stays untouched until the explicit save.
 */
class CloudModelStreamingToggleTest {

    @Test
    fun `streaming toggle updates only the streaming field`() {
        val port = FakePort(
            MainUiState(
                cloudApiConfig = CloudApiConfig(
                    apiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
                    baseUrl = "https://o-ai.me/v1",
                    chatModel = "gpt-6.1-sol",
                    streaming = true
                )
            )
        )

        applyCloudStreaming(port, false)

        assertFalse(port.state.cloudApiConfig.streaming)
        assertTrue(port.state.cloudApiConfig.baseUrl.endsWith("/v1"))
        assertTrue(port.state.cloudApiConfig.chatModel == "gpt-6.1-sol")
        assertTrue(port.state.cloudApiConfig.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE)
    }

    private class FakePort(var state: MainUiState) : CloudModelConfigPort {
        override fun uiState() = state
        override fun updateUiState(transform: (CloudModelConfigFields) -> CloudModelConfigFields): CloudModelConfigFields {
            val committed = transform(state.toCloudModelConfigFields())
            state = committed.mergeInto(state)
            return committed
        }

        override val cloudApiStore: CloudApiStore get() = TODO("not used")
        override val cloudChatProvider: OpenAiCompatibleChatProvider get() = TODO("not used")
        override val localImageModelStore: LocalImageModelStore get() = TODO("not used")

        override fun loadModelGenerationParams(
            backend: ChatBackend,
            modelId: String?,
            defaults: GenerationParams
        ): GenerationParams = defaults

        override fun persistChatSessions(
            sessions: List<ChatSessionRecord>?,
            knowledgeBinding: Pair<String, Set<String>>?
        ) = Unit

        override fun persistGenerationParams(params: GenerationParams) = Unit
        override fun showStatus(message: String?) = Unit
        override fun busy(message: String) = Unit
        override fun fail(message: String) = Unit
    }
}
