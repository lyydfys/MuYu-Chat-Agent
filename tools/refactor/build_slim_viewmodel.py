#!/usr/bin/env python3
import re
from pathlib import Path

REPO_ROOT = Path("E:/MAC")
VM_PATH = REPO_ROOT / "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"

content = VM_PATH.read_text(encoding="utf-8")
lines = content.splitlines()

# We will define a list of method signatures that have been moved to delegate files
# and remove them from lines.

methods_to_remove = [
    # AppUpdate
    "fun maybeCheckForAppUpdate() = appUpdateOwner.maybeCheckForAppUpdate()",
    "fun checkForAppUpdate() = appUpdateOwner.checkForAppUpdate()",
    "fun setAppUpdateAutoCheckEnabled(enabled: Boolean) = appUpdateOwner.setAppUpdateAutoCheckEnabled(enabled)",
    "fun downloadAppUpdate() = appUpdateOwner.downloadAppUpdate()",
    "fun installAppUpdate() = appUpdateOwner.installAppUpdate()",
    "fun openAppUpdateRelease() = appUpdateOwner.openAppUpdateRelease()",
    # Upscale
    "fun upscaleImageAsset(imageId: String, targetScale: Int) =",
    "fun cancelImageUpscale() =",
    # CloudModel delete
    "fun deleteCloudModel(modelId: String) =",
    # Offline prompt translation draft
    "fun confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) =",
    # buildChatImageRetrySnapshot
    "fun buildChatImageRetrySnapshot(",
    # emitEffect & processIntent
    "fun emitEffect(effect: com.muyuchat.mca.presentation.MainUiEffect)",
    "fun processIntent(intent: com.muyuchat.mca.presentation.MainUiIntent)",
    # Chat inputs, compression, assets
    "fun onInputChange(value: String) =",
    "fun clearStatusMessage() =",
    "fun startBrowserTask(request: com.muyuchat.feature.chat.BrowserTaskLaunch) =",
    "fun onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) =",
    "fun requestContextCompression() =",
    "fun toggleMessagePinned(messageId: String) =",
    "fun undoContextSummary() =",
    "fun contextSummaryEvidenceForQuery(query: String): List<com.muyuchat.feature.chat.ContextSummaryEvidenceItem> =",
    "fun setContextCompressionThreshold(percent: Int) =",
    "fun setContextSummaryModel(selection: String) =",
    "fun attachFile(uriString: String) =",
    "fun useImageAsset(imageId: String) =",
    "fun useFileAsset(fileId: String) =",
    "fun deleteFileAsset(fileId: String) =",
    "fun clearFileLibrary() =",
    "fun importLocalImageLora(uriString: String) =",
    "fun deleteLocalImageLora(id: String) =",
    "fun importLocalImageTextualInversion(uriString: String, trigger: String) =",
    "fun deleteLocalImageTextualInversion(id: String) =",
    "fun downloadOfficialViggleLora() =",
    "fun reportMissingLocalImageLoraSelection()",
    "fun reportMissingLocalImageTextualInversionSelection() =",
    "fun importLocalImageUpscaler(uriString: String) =",
    "fun selectLocalImageUpscaler(id: String) =",
    "fun deleteLocalImageUpscaler(id: String) =",
    "fun generateImageAsset(",
    # Image library & models
    "fun recreateImageAsset(imageId: String) =",
    "fun selectLocalImageModel(modelId: String) =",
    "fun cancelImageGeneration() =",
    "fun cancelImageGenerationForJob(jobId: String?) =",
    "fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress) =",
    "fun deleteImageAsset(imageId: String) =",
    "fun deleteImageAssets(imageIds: List<String>) =",
    "fun setImageAssetFavorite(imageId: String, favorite: Boolean) =",
    "fun clearImageLibrary() =",
    "fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =",
    "fun importImageLibraryBackup(uriString: String) =",
    "fun cancelImageLibraryBackup() =",
    "fun importLocalImageModel(uri: Uri) =",
    "fun rejectImageModelSwitchWhileGenerationIsActive() =",
    "fun verifyLocalImageModel(modelId: String) =",
    "fun settleLocalImageSelection(image: DownloadedModelRegistration.LocalImage): LocalImageAutomaticSelection =",
    "fun onRepoInputChange(value: String) =",
    "fun onHubQueryChange(value: String) =",
    # Assistant Profile & WebSearch
    "fun saveAssistantProfile(",
    "fun upsertAssistantMemory(assistantId: String, id: String?, scope: String, content: String) =",
    "fun deleteAssistantMemory(assistantId: String, id: String) =",
    "fun saveWebSearchConfig(",
    "fun testWebSearchConfig(query: String) =",
    "fun testWebSearchConfig(",
    "fun preflightWebSearchConfig(",
    "fun testWebSearchTurn(",
    "fun toggleWebSearchForNextTurn() =",
    "fun cycleWebSearchResearchModeForNextTurn() =",
    "fun selectWebSearchResearchModeForNextTurn(mode: String) =",
    "fun clearWebSearchDiagnostics() =",
    "fun createChatForAssistant(assistantId: String)",
    "fun importChatBackground(scope: ChatAppearanceScope, uriString: String) =",
    "fun setChatAppearance(scope: ChatAppearanceScope, appearance: ChatAppearance?) =",
    "fun deleteAssistant(assistantId: String) =",
    "fun importAssistantCard(rawJson: String) =",
    "fun removeLocalImageModelRecord(modelId: String) =",
    "fun cancelContentImportPreview(id: String) =",
    "fun confirmContentImportPreview(id: String) =",
    "fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =",
    "fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) =",
    "fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?)",
    "fun importWorldBookFile(",
    "fun deleteWorldBook(worldBookId: String) =",
    "fun createKnowledgeBase(name: String, description: String = \"\") =",
    "fun importKnowledgeDocument(",
    "fun importKnowledgeDocument(uriString: String) =",
    "fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =",
    "fun deleteKnowledgeBase(knowledgeBaseId: String) =",
    "fun updateReasoningMode(mode: ReasoningMode) =",
    "fun showCloudReasoningModeLocked() =",
    # Cloud API methods
    "fun updateCloudApiEnabled(enabled: Boolean) =",
    "fun updateCloudApiFormat(value: String) =",
    "fun updateCloudImageApiFormat(value: String) =",
    "fun applyCloudProviderPreset(value: String) =",
    "fun beginAddCloudModel(kind: String) =",
    "fun editCloudModel(modelId: String) =",
    "fun updateCloudBaseUrl(value: String) =",
    "fun updateCloudApiKey(value: String) =",
    "fun updateCloudChatModel(value: String) =",
    "fun updateCloudSupportsVision(value: Boolean) =",
    "fun updateModelBackendPreference(modelId: String, backendId: String) =",
    "fun generationParamsForChatModel(modelId: String): GenerationParams =",
    "fun updateCloudSupportsTools(value: Boolean) =",
    "fun updateCloudImageModel(value: String) =",
    "fun updateCloudResponsesReasoning(value: Boolean) =",
    "fun updateCloudImageSize(value: String) =",
    "fun updateCloudImageEndpointPath(value: String) =",
    "fun updateCloudDisplayName(value: String) =",
    "fun saveCloudApiConfig() =",
    "fun saveCloudChatModel() =",
    "fun saveCloudImageModel() =",
    "fun testCloudApiConfig() =",
    "fun selectCloudImageModel(modelId: String) =",
    # Model Hub & Imports
    "fun refreshLocalModels() =",
    "fun importModel(uri: Uri) =",
    "fun importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) =",
    "fun importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) =",
    "fun pauseManagedImport(requestId: String) =",
    "fun resumeManagedImport(requestId: String) =",
    "fun fetchRemoteFiles() =",
    "fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) =",
    "fun downloadRecommended(model: ModelScopeRecommendedModel) =",
    "fun downloadRecommended(model: ModelScopeRecommendedModel, useAfterDownload: Boolean) =",
    "fun attachVisionProjector(modelId: String, uri: Uri) =",
    "fun searchHubModels(reset: Boolean = true) =",
    "fun fetchHubModelFiles(model: ModelScopeHubModel) =",
    "fun openModelScopePage(url: String) =",
    "fun download(remote: RemoteModelFile) =",
    "fun pauseManagedDownloads() =",
    "fun resumeManagedDownloads() =",
    "internal fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean = false) =",
    "internal fun launchModelOperation(",
    "fun selectChatModel(choiceId: String) =",
    "internal fun selectCloudChatModel(modelId: String?) =",
    "fun loadModel(requestedModel: ModelManifest) =",
    "fun verifyModel(model: ModelManifest) =",
    "fun unloadModel(model: ModelManifest) =",
    "fun deleteModel(model: ModelManifest) =",
    # Offline prompt translation
    "fun resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String? = null) =",
    "fun importOfflinePromptTranslationBundle(uri: Uri) =",
    "fun cancelOfflinePromptTranslationInstall() =",
    "fun downloadOfflinePromptTranslationBundle() =",
    "fun cancelOfflinePromptTranslationDownload() =",
    "fun cancelChatImageTranslationDraft(id: String) =",
    "fun sendChatImagePrompt() =",
    # Assistant image tool turns
    "fun setAssistantImageToolAutoApproval(enabled: Boolean) =",
    "fun continueAssistantImageToolTurn(requestId: String, draft: String) =",
    "fun retryChatImageGeneration(requestId: String) =",
    "fun approveChatImageRequest(requestId: String) =",
    "fun rejectChatImageRequest(requestId: String) =",
    "fun continueAssistantImageTurn(requestId: String) =",
    "fun cancelChatImageGeneration(requestId: String) =",
    # Sessions
    "fun newChat()",
    "fun selectChatSession(sessionId: String) =",
    "fun branchMixedChatSession(sessionId: String, assistantId: String) =",
    "fun renameChatSession(sessionId: String, title: String) =",
    "fun toggleChatSessionPinned(sessionId: String) =",
    "fun clearChat()",
    # Diagnostics & Agent tuning
    "fun refreshLogs() =",
    "fun refreshDiagnostics() =",
    "fun exportDiagnosticReport(uri: Uri) =",
    "fun chatSessionExportFileName(sessionId: String): String =",
    "fun exportChatSession(sessionId: String, uri: Uri) =",
    "fun scanAgent() =",
    "fun applyAgentRecommendation() =",
    "fun rollbackAgentParams() =",
    "fun loadAgentRecommendedModel() =",
    "fun runAgentBenchmark() =",
    "fun runAgentStabilitySmoke() =",
    "fun startAgentTuning() =",
    "fun startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) =",
    "fun pauseAgentTuning() =",
    "fun resumeAgentTuning() =",
    "fun cancelAgentTuning() =",
    "fun queryAgentTuningJob() =",
    "fun applyPendingAgentProfile() =",
    "fun discardPendingAgentProfile() =",
    "fun rollbackAgentProfile() =",
    "fun runAgentQuickDebug() =",
    "fun runAgentStandardDebug() =",
    "fun runAgentDeepDebug() =",
    "fun runAgentPowerDebug() =",
    "fun showAgentDebugExplanation() =",
    "fun currentDeviceProfile(): DeviceProfile =",
    "fun buildRecommendation("
]

print(f"Total method signatures to remove: {len(methods_to_remove)}")

def find_function_range(lines, sig):
    for i, line in enumerate(lines):
        if sig in line:
            # Check if it's an expression body
            # i.e. contains '=' before '{' or no '{' on that statement
            # Note: could be multi-line expression body or multi-line signature
            # Let's check:
            start = i
            # Find the line with '=' or '{'
            curr = i
            full_sig = ""
            while curr < len(lines):
                full_sig += " " + lines[curr].strip()
                if "{" in lines[curr] or "=" in lines[curr]:
                    break
                curr += 1
            
            if "{" in lines[curr]:
                # block body: find matching brace
                depth = 0
                for k in range(curr, len(lines)):
                    depth += lines[k].count("{") - lines[k].count("}")
                    if depth == 0:
                        return start, k
                raise ValueError(f"Unmatched brace for {sig}")
            else:
                # expression body: ends when expression finishes (often single line or multi-line call)
                # An expression body ends at the next statement (fun, val, var, private, override, internal, etc.) or blank line
                # Let's inspect the indentation
                indent = len(lines[start]) - len(lines[start].lstrip())
                for k in range(start + 1, len(lines)):
                    s = lines[k].strip()
                    if not s:
                        return start, k
                    k_indent = len(lines[k]) - len(lines[k].lstrip())
                    if k_indent <= indent and any(s.startswith(w) for w in ["fun ", "val ", "var ", "private ", "internal ", "override ", "init ", "class "]):
                        return start, k - 1
                return start, len(lines) - 1
    return None

found_ranges = []
for sig in methods_to_remove:
    r = find_function_range(lines, sig)
    if r is None:
        print(f"Warning: NOT found: {sig}")
    else:
        found_ranges.append((r[0], r[1], sig))

print(f"Successfully located {len(found_ranges)} / {len(methods_to_remove)} method ranges.")
