package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.composition.chatImageRequestWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal callbacks must revalidate job ownership inside the authoritative CAS attempt. */
class ChatImageRequestPersistenceTest {
    private class RecordingPort(initial: MainUiState) : ChatImageRequestPort {
        val store = UiStateStore(initial)
        private val writer = store.chatImageRequestWriter()
        val persisted = mutableListOf<List<ChatSessionRecord>>()
        var attempts = 0
        var beforeUpdate: (() -> Unit)? = null
        var afterReducer: (() -> Unit)? = null

        override fun uiState(): MainUiState = store.current

        override fun <Result> updateUiStateWithResult(
            transform: (ChatImageRequestFields) -> Pair<ChatImageRequestFields, Result>,
        ): Result {
            beforeUpdate?.let { hook -> beforeUpdate = null; hook() }
            return writer.updateWithResult { fields ->
                attempts++
                val outcome = transform(fields)
                // Test-only instrumentation: publish between reduction and the real store CAS.
                // The rejected attempt must then rerun through the production writer.
                afterReducer?.let { hook -> afterReducer = null; hook() }
                outcome
            }
        }

        override fun persistChatSessions(
            sessions: List<ChatSessionRecord>?,
            knowledgeBinding: Pair<String, Set<String>>?,
        ) {
            check(knowledgeBinding == null)
            persisted += requireNotNull(sessions)
        }

        fun publishRequest(request: ChatGeneratedImageRequest) {
            writer.update { fields ->
                fun replace(messages: List<ChatMessage>): List<ChatMessage> = messages.map { message ->
                    if (message.generatedImageRequest?.id == REQUEST_ID) {
                        message.copy(content = request.assistantTranscriptText(), generatedImageRequest = request)
                    } else message
                }
                fields.copy(
                    messages = replace(fields.messages),
                    chatSessions = fields.chatSessions.map { session ->
                        if (session.id == SESSION_ID) session.copy(messages = replace(session.messages)) else session
                    },
                )
            }
        }
    }

    @Test
    fun `matching terminal callbacks update and persist the same committed request`() {
        listOf(
            ImageGenerationStatusRecord.DONE to ChatGeneratedImageStatus.DONE,
            ImageGenerationStatusRecord.CANCELLED to ChatGeneratedImageStatus.CANCELLED,
            ImageGenerationStatusRecord.FAILED to ChatGeneratedImageStatus.FAILED,
        ).forEach { (jobStatus, expectedStatus) ->
            listOf(true, false).forEach { active ->
                val initial = initialState(jobStatus = jobStatus, active = active)
                val port = RecordingPort(initial)

                persistFinalChatImageRequest(port, OLD_JOB)

                val after = port.uiState()
                val request = after.request()
                assertEquals(expectedStatus, request.status)
                assertEquals(OLD_JOB, request.currentJobId)
                assertEquals(if (expectedStatus == ChatGeneratedImageStatus.DONE) listOf("image-1") else emptyList<String>(), request.imageAssetIds)
                assertEquals(request.assistantTranscriptText(), after.owner().messages.last().content)
                assertEquals(after.chatSessions, port.persisted.single())
                assertEquals(1, port.attempts)
                assertForeignStatePreserved(initial, after)
                if (active) assertEquals(after.owner().messages, after.messages)
                else assertSame(initial.messages, after.messages)
            }
        }
    }

    @Test
    fun `a retry published after the outer check prevents any old terminal write or persistence`() {
        val port = RecordingPort(initialState())
        val newer = request(NEW_JOB).copy(message = "new retry is queued")
        var published: MainUiState? = null
        port.beforeUpdate = {
            port.publishRequest(newer)
            port.store.showStatus("new admission")
            published = port.uiState()
        }

        persistFinalChatImageRequest(port, OLD_JOB)

        assertSame(published, port.uiState())
        assertEquals(newer, port.uiState().request())
        assertEquals(1, port.attempts)
        assertTrue(port.persisted.isEmpty())
    }

    @Test
    fun `a failed CAS retries ownership validation and cannot overwrite a new job`() {
        val port = RecordingPort(initialState())
        val newer = request(NEW_JOB).copy(message = "replacement owns the request")
        var published: MainUiState? = null
        port.afterReducer = {
            port.publishRequest(newer)
            port.store.showStatus("concurrent status")
            published = port.uiState()
        }

        persistFinalChatImageRequest(port, OLD_JOB)

        assertEquals("the first terminal attempt must lose CAS", 2, port.attempts)
        assertSame(published, port.uiState())
        assertEquals(newer, port.uiState().request())
        assertEquals("concurrent status", port.uiState().statusMessage)
        assertTrue("a discarded terminal attempt must not persist", port.persisted.isEmpty())
    }

    @Test
    fun `legacy requests with an empty job binding still accept their terminal callback`() {
        listOf<String?>(null, "").forEach { binding ->
            val initial = initialState(binding = binding)
            val port = RecordingPort(initial)

            persistFinalChatImageRequest(port, OLD_JOB)

            assertEquals(ChatGeneratedImageStatus.DONE, port.uiState().request().status)
            assertEquals(binding, port.uiState().request().currentJobId)
            assertEquals(port.uiState().chatSessions, port.persisted.single())
            assertForeignStatePreserved(initial, port.uiState())
        }
    }

    @Test
    fun `replacing the request identity during CAS cannot resurrect the removed request`() {
        val port = RecordingPort(initialState())
        val replacement = request(NEW_JOB).copy(id = "replacement-request", message = "replacement")
        var published: MainUiState? = null
        port.afterReducer = {
            port.publishRequest(replacement)
            published = port.uiState()
        }

        persistFinalChatImageRequest(port, OLD_JOB)

        assertEquals(2, port.attempts)
        assertSame(published, port.uiState())
        assertEquals(replacement, port.uiState().request())
        assertTrue(port.persisted.isEmpty())
    }

    @Test
    fun `a foreign update forces CAS retry without losing the valid terminal commit`() {
        val initial = initialState()
        val port = RecordingPort(initial)
        port.afterReducer = { port.store.showStatus("foreign update") }

        persistFinalChatImageRequest(port, OLD_JOB)

        assertEquals(2, port.attempts)
        assertEquals(ChatGeneratedImageStatus.DONE, port.uiState().request().status)
        assertEquals("foreign update", port.uiState().statusMessage)
        assertEquals(port.uiState().chatSessions, port.persisted.single())
        assertForeignStatePreserved(initial.copy(statusMessage = "foreign update"), port.uiState())
    }

    @Test
    fun `ordinary request replacement keeps the existing retry job rebind semantics`() {
        val initial = initialState()
        val port = RecordingPort(initial)
        val rebound = request(NEW_JOB).copy(message = "retry")

        applyChatGeneratedImageRequest(port, SESSION_ID, REQUEST_ID, rebound)

        assertEquals(rebound, port.uiState().request())
        assertEquals(port.uiState().chatSessions, port.persisted.single())
        assertForeignStatePreserved(initial, port.uiState())
    }

    private fun initialState(
        binding: String? = OLD_JOB,
        jobStatus: ImageGenerationStatusRecord = ImageGenerationStatusRecord.DONE,
        active: Boolean = true,
    ): MainUiState {
        val request = request(binding)
        val messages = listOf(
            ChatMessage(Role.USER, "keep the user message"),
            ChatMessage(Role.ASSISTANT, "keep the ordinary reply"),
            ChatMessage(Role.ASSISTANT, "queued", generatedImageRequest = request),
        )
        val owner = ChatSessionRecord(
            id = SESSION_ID,
            title = "image conversation",
            messages = messages,
            updatedAt = 1L,
            assistantId = "assistant-1",
            assistantSnapshot = AssistantConversationSnapshot(
                assistantId = "assistant-1", name = "assistant", systemPrompt = "Be helpful",
                memoryEnabled = false, webSearchEnabled = false, fileContextEnabled = true, capturedAt = 1L,
            ),
        )
        val foreignMessages = listOf(ChatMessage(Role.USER, "another conversation"))
        val foreign = ChatSessionRecord("foreign-session", "foreign", foreignMessages, updatedAt = 0L)
        val spec = ImageGenerationJobSpec(
            prompt = "a cat", backend = ImageBackend.CLOUD,
            cloudConfigSnapshot = CloudApiConfig(imageModel = "test-image-model"),
            modelId = "test-image-model", modelName = "test image model",
            inputDraft = LocalImageInputDraft(), options = LocalImageGenerationOptions(),
            chatSessionId = SESSION_ID, chatMessageId = REQUEST_ID,
        )
        val job = ImageGenerationJobRecord(
            id = OLD_JOB, prompt = spec.prompt, status = jobStatus, backend = spec.backend,
            modelId = spec.modelId, modelName = spec.modelName, spec = spec,
            imageAssetIds = listOf("image-1"), message = "terminal result", startedAtMillis = 1L,
        )
        return MainUiState(
            messages = if (active) messages else foreignMessages,
            activeChatSessionId = if (active) SESSION_ID else foreign.id,
            chatSessions = listOf(owner, foreign),
            imageJobs = listOf(job), input = "keep draft", hubPage = 7, apiEnabled = true,
            selectedAssistantId = requireNotNull(owner.assistantId), statusMessage = "keep status",
        )
    }

    private fun request(binding: String?) = ChatGeneratedImageRequest(
        id = REQUEST_ID, prompt = "a cat", status = ChatGeneratedImageStatus.QUEUED,
        message = "queued", currentJobId = binding,
    )

    private fun MainUiState.owner(): ChatSessionRecord = chatSessions.single { it.id == SESSION_ID }

    private fun MainUiState.request(): ChatGeneratedImageRequest =
        requireNotNull(owner().messages.last().generatedImageRequest)

    private fun assertForeignStatePreserved(before: MainUiState, after: MainUiState) {
        assertEquals(before.copy(messages = after.messages, chatSessions = after.chatSessions), after)
        assertSame(before.owner().messages[0], after.owner().messages[0])
        assertSame(before.owner().messages[1], after.owner().messages[1])
        assertSame(before.chatSessions.single { it.id == "foreign-session" }, after.chatSessions.single { it.id == "foreign-session" })
    }

    companion object {
        private const val SESSION_ID = "session-1"
        private const val REQUEST_ID = "request-1"
        private const val OLD_JOB = "old-job"
        private const val NEW_JOB = "new-job"
    }
}
