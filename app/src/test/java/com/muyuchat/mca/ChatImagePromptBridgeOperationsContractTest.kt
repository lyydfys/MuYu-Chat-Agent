package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the P3 step-2 extraction of the chat image prompt bridge into
 * `ChatImagePromptBridgeOperations.kt`.
 *
 * The bridge orchestration is UI-bound: it launches on the ViewModel scope, reads the live
 * `MainUiState` between suspensions, and reaches every collaborator (composer, cloud/local chat
 * runtime, offline translator, image request entry point) through field access. Those are exactly
 * the seams that used to make the flow untestable from the outside, so the extraction keeps them
 * behind `ChatImagePromptBridgePort` and these checks pin the properties that must survive the
 * move:
 *
 * - the moved body has no direct ViewModel field access (all IO through the port);
 * - the ViewModel keeps only a verbatim-signature delegate plus the port implementation;
 * - the invalidation token is bumped and the previous job cancelled before the replacement is
 *   launched;
 * - every suspension is followed by an ownership re-check before publishing or enqueueing.
 */
class ChatImagePromptBridgeOperationsContractTest {

    @Test
    fun movedBodyReachesEveryCollaboratorThroughThePort() {
        val ops = operationsSource()
        listOf(
            "port.currentState()",
            "port.updateUiState {",
            "port.currentBridgeGeneration()",
            "port.incrementBridgeGeneration()",
            "port.cancelBridgeJob()",
            "port.setBridgeJob(port.bridgeScope().launch {",
            "port.setPendingChatImageAction(",
            "port.setPendingImageTranslation(",
            "port.showStatus(",
            "port.chatImageOptionsForCurrentSelection(",
            "port.composeChatContext(",
            "port.streamChatCloud(",
            "port.streamChatLocal(",
            "port.translateOffline(",
            "port.sendChatImageRequest(",
            "port.negativePromptSupported(",
            "port.canWriteToActiveAssistantSession(",
            "port.selectedChatCloudConfig(",
            "port.selectedImageCloudConfig(",
            "port.selectedLocalImageModel(",
            "port.activeAssistantSnapshot(",
            "port.selectedAssistant("
        ).forEach { fragment ->
            assertTrue("Moved body must call $fragment", ops.contains(fragment))
        }
    }

    @Test
    fun movedBodyHasNoDirectViewModelFieldAccess() {
        val ops = operationsSource()
        listOf(
            "_uiState",
            "viewModelScope",
            "chatContextComposer",
            "cloudChatProvider",
            "LocalApiRuntime",
            "offlinePromptTranslationService",
            "verifiedOfflineTranslationBundle",
            "chatImagePromptBridgeJob",
            "pendingChatImageAction =",
            "pendingImageTranslation ="
        ).forEach { field ->
            assertFalse("Moved body must not touch $field directly", ops.contains(field))
        }
    }

    @Test
    fun invalidationHappensBeforeTheReplacementJobLaunches() {
        val ops = operationsSource()
        val increment = ops.indexOf("port.incrementBridgeGeneration()")
        val cancel = ops.indexOf("port.cancelBridgeJob()")
        val launch = ops.indexOf("port.setBridgeJob(port.bridgeScope().launch {")
        assertTrue("Missing increment", increment >= 0)
        assertTrue("Missing cancel", cancel >= 0)
        assertTrue("Missing setBridgeJob launch", launch >= 0)
        assertTrue("Token must be bumped before the old job is cancelled", increment < cancel)
        assertTrue("The old job must be cancelled before the replacement launches", cancel < launch)
    }

    @Test
    fun everySuspensionIsFollowedByAnOwnershipRecheck() {
        val ops = operationsSource()
        assertEquals(
            "Launch-scoped ownership rechecks must survive the move",
            6,
            countOccurrences(ops, "if (!preparationStillOwned()) return@launch")
        )
        assertEquals(
            "The cloud-consent helper keeps its non-launch recheck",
            1,
            countOccurrences(ops, "if (!preparationStillOwned()) return\n")
        )
        assertEquals(
            "Cancellation checkpoints must survive the move",
            5,
            countOccurrences(ops, "currentCoroutineContext().ensureActive()")
        )
    }

    @Test
    fun modelPassRemainsMandatoryForEveryExplicitImageRequest() {
        val ops = operationsSource()
        assertEquals(
            "The full request body must run through exactly one primary model pass",
            1,
            countOccurrences(ops, "val translatedResult = translateOne(original)")
        )
        assertTrue(
            "The recovery path must stay non-native so the model pass is not bypassed",
            ops.contains("ChatImagePromptBridge.chatImagePromptRecovery(original, nativeMultilingual = false)")
        )
        assertTrue(
            "The no-fast-path invariant comment must move with the body",
            ops.contains("Do not add a fast path here")
        )
    }

    @Test
    fun viewModelKeepsOnlyTheVerbatimDelegateAndPortImplementation() {
        val viewModel = mainViewModelSource()
        // The wrapper has an expression body, so the region is delimited by the next declaration
        // instead of by brace matching.
        val delegateStart = viewModel.indexOf("private fun sendChatImageRequestWithPromptBridge(")
        assertTrue("Missing ViewModel wrapper", delegateStart >= 0)
        val delegateEnd = viewModel.indexOf("private fun chatImageNegativePromptSupported(", delegateStart)
        assertTrue("Missing neighbour declaration", delegateEnd > delegateStart)
        val delegate = viewModel.substring(delegateStart, delegateEnd)
        assertTrue(
            "The ViewModel wrapper must delegate to the moved function",
            delegate.contains("runChatImagePromptBridge(")
        )
        assertTrue(
            "The wrapper must pass the bridge port",
            delegate.contains("port = chatImagePromptBridgePort")
        )
        assertFalse(
            "The moved orchestration must not remain in the ViewModel",
            delegate.contains("chatContextComposer")
        )
        assertFalse(
            "The moved orchestration must not remain in the ViewModel",
            delegate.contains("viewModelScope.launch")
        )
        assertFalse(
            "The moved status string must not remain in the ViewModel",
            delegate.contains("正在准备图片描述")
        )

        assertTrue(
            "The ViewModel must keep the port implementation",
            viewModel.contains("private val chatImagePromptBridgePort: ChatImagePromptBridgePort =")
        )
        listOf(
            "override fun currentState(): MainUiState",
            "override fun updateUiState(transform: (MainUiState) -> MainUiState)",
            "override fun currentBridgeGeneration(): Long",
            "override fun incrementBridgeGeneration(): Long",
            "override fun cancelBridgeJob()",
            "override fun setBridgeJob(job: Job)",
            "override fun bridgeScope(): CoroutineScope",
            "override fun setPendingChatImageAction(",
            "override fun setPendingImageTranslation(translation: PendingImageTranslation)",
            "override fun showStatus(message: String?)",
            "override fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions",
            "override fun composeChatContext(",
            "override fun streamChatCloud(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent>",
            "override fun streamChatLocal(",
            "override suspend fun translateOffline(request: OfflinePromptTranslationRequest): OfflinePromptTranslationResolution",
            "override fun sendChatImageRequest(",
            "override fun negativePromptSupported(state: MainUiState): Boolean",
            "override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean",
            "override fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig?",
            "override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig?",
            "override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord?",
            "override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot?",
            "override fun selectedAssistant(state: MainUiState): AssistantRecord?"
        ).forEach { member ->
            assertTrue("Port implementation must declare $member", viewModel.contains(member))
        }

        // The negative-branch capability probe stays a ViewModel member: the assistant image-turn
        // port consumes it too, so the bridge port only delegates to it.
        assertTrue(
            "Negative-prompt capability must stay owned by the ViewModel",
            viewModel.contains("private fun chatImageNegativePromptSupported(state: MainUiState): Boolean")
        )
        assertFalse(
            "The moved file must not re-implement the capability probe",
            operationsSource().contains("supportsNegativePrompt")
        )
    }

    private fun countOccurrences(source: String, fragment: String): Int {
        var count = 0
        var index = source.indexOf(fragment)
        while (index >= 0) {
            count++
            index = source.indexOf(fragment, index + fragment.length)
        }
        return count
    }

    private fun operationsSource(): String = projectFile(
        "src/main/java/com/muyuchat/mca/ChatImagePromptBridgeOperations.kt",
        "app/src/main/java/com/muyuchat/mca/ChatImagePromptBridgeOperations.kt"
    )

    private fun mainViewModelSource(): String = projectFile(
        "src/main/java/com/muyuchat/mca/MainViewModel.kt",
        "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"
    )

    private fun projectFile(vararg candidates: String): String {
        val root = File(System.getProperty("user.dir"))
        return candidates.asSequence()
            .map { File(root, it) }
            .firstOrNull(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?: error("None of ${candidates.toList()} found under $root")
    }
}
