package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatWorkspaceNavigationTest {
    @Test
    fun screenDshActionOpensDshWithoutInvokingOtherAgentModes() {
        val calls = mutableListOf<String>()
        val model = ChatViewModel(ChatUiState(), ChatRouteInputs(), actions(calls, "initial"))

        model.screenActions.shell.onOpenDshWorkspace()
        assertEquals(listOf("initial:dsh"), calls)

        model.screenActions.shell.onOpenAgentWorkspace()
        model.screenActions.shell.onOpenAgent()
        model.screenActions.shell.onOpenVideoWorkspace()
        assertEquals(
            listOf("initial:dsh", "initial:agent", "initial:tuning", "initial:video"),
            calls,
        )
    }

    @Test
    fun retainedDshActionUsesCurrentCapabilitiesAfterRouteUpdate() {
        val calls = mutableListOf<String>()
        val model = ChatViewModel(ChatUiState(), ChatRouteInputs(), actions(calls, "old"))
        val retainedAction = model.screenActions.shell.onOpenDshWorkspace

        model.update(ChatUiState(), ChatRouteInputs(), actions(calls, "new"))
        retainedAction()
        model.screenActions.shell.onOpenDshWorkspace()

        assertEquals(listOf("new:dsh", "new:dsh"), calls)
    }

    private fun actions(calls: MutableList<String>, owner: String) = ChatScreenActions(
        conversation = ChatConversationActions(
            onClearHistory = {},
            onDeleteConversation = {},
            onDeleteMessage = {},
            onExportConversation = {},
            onRenameConversation = { _, _ -> },
            onSelectConversation = {},
            onTogglePinConversation = {},
            onCreateConversationForAssistant = {},
            onRegenerate = {},
        ),
        generation = ChatGenerationActions(
            onInputChange = {},
            onSend = {},
            onSendImagePrompt = {},
            onStop = {},
            onReasoningModeChange = {},
            onUploadFile = {},
            releaseGenerationImageGrantsIfCoordinatorIdle = { false },
        ),
        content = ChatContentActions(),
        image = ChatImageActions(onImportImageModel = {}),
        shell = ChatShellActions(
            onOpenModels = {},
            onOpenRecommendedModels = {},
            onOpenSettings = {},
            onOpenAgent = { calls += "$owner:tuning" },
            onOpenAgentWorkspace = { calls += "$owner:agent" },
            onOpenDshWorkspace = { calls += "$owner:dsh" },
            onOpenVideoWorkspace = { calls += "$owner:video" },
            onOpenApi = {},
            onImportChatModel = {},
        ),
    )
}
