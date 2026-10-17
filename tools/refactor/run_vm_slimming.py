#!/usr/bin/env python3
import re
import sys
from pathlib import Path

REPO_ROOT = Path("E:/MAC")
VM_PATH = REPO_ROOT / "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"

content = VM_PATH.read_text(encoding="utf-8")
lines = content.splitlines()

# Contract-required functions that MUST NEVER be removed from MainViewModel.kt:
MUST_KEEP = {
    "onAppBackgrounded",
    "regenerateLastResponse",
    "onCleared",
    "selectAssistant",
    "newChatForAssistant",
    "deleteMessageAt",
    "deleteLastConversationTurn",
    "stopGeneration",
    "deleteChatSession",
    "clearChatHistory",
    "clearChat",
    "newChat",
    "startGeneration",
    "updateAgentPreference",
    "observeManagedDownloads",
    "deleteLocalImageModel",
    "selectImageGenerationModel",
    "settleLocalImageSelection",
    "rejectImageModelSwitchWhileGenerationIsActive",
    "publishLocalImagePreview",
    "retryImageGeneration",
    "loadPersistentPrefixCacheEnabled",
    "setPersistentPrefixCacheEnabled",
    "clearPersistentPrefixCache",
    "claimLocalApiProcessOwnership",
    "showStatus",
    "resetNativeConversationContextForGeneration",
    "claimLocalApiRuntimeOwnership",
    "relinquishLocalApiRuntimeOwnership",
    "registerLocalApiImageGenerationOwnership",
    "unregisterLocalApiImageGenerationOwnership",
    "cancelOwnedLocalApiImageWorker",
    "generateLocalApiImage",
    "cancelLocalApiImageGeneration",
    "queryLocalApiImageGeneration",
    "activeLocalApiImageGenerationOwnership"
}

# Signatures to remove:
REMOVE_SIGNATURES = [
    # AppUpdate
    "fun maybeCheckForAppUpdate() =",
    "fun checkForAppUpdate() =",
    "fun setAppUpdateAutoCheckEnabled(enabled: Boolean) =",
    "fun downloadAppUpdate() =",
    "fun installAppUpdate() =",
    "fun openAppUpdateRelease() =",
    # Upscale
    "fun upscaleImageAsset(imageId: String, targetScale: Int) =",
    "fun cancelImageUpscale() =",
    # Cloud
    "fun deleteCloudModel(modelId: String) =",
    "fun confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) =",
    "fun buildChatImageRetrySnapshot(",
    # Intent
    "fun emitEffect(effect: com.muyuchat.mca.presentation.MainUiEffect)",
    "fun processIntent(intent: com.muyuchat.mca.presentation.MainUiIntent)",
    # Chat inputs, compression
    "fun onInputChange(value: String) =",
    "fun clearStatusMessage() =",
    "fun startBrowserTask(request: com.muyuchat.feature.chat.BrowserTaskLaunch) =",
    "fun onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) =",
    "fun requestContextCompression() =",
    "fun toggleMessagePinned(messageId: String) =",
    "fun undoContextSummary() =",
    "fun contextSummaryEvidenceForQuery(query: String): List<com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem> =",
    "fun setContextCompressionThreshold(percent: Int) =",
    "fun setContextSummaryModel(selection: String) =",
    # Attachments & image assets
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
    "fun recreateImageAsset(imageId: String) =",
    "fun selectLocalImageModel(modelId: String) =",
    "fun cancelImageGeneration() =",
    "internal fun cancelImageGenerationForJob(expectedJobId: String?) =",
    "fun deleteImageAsset(imageId: String) =",
    "fun deleteImageAssets(imageIds: List<String>) =",
    "fun setImageAssetFavorite(imageId: String, favorite: Boolean) =",
    "fun clearImageLibrary() =",
    "fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =",
    "fun importImageLibraryBackup(uriString: String) =",
    "fun cancelImageLibraryBackup() =",
    "fun importLocalImageModel(uri: Uri) =",
    "fun verifyLocalImageModel(modelId: String) =",
    "fun removeLocalImageModelRecord(modelId: String) =",
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
    "fun cancelContentImportPreview(id: String) =",
    "fun confirmContentImportPreview(id: String) =",
    "fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =",
    "fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) =",
    "fun importWorldBookFile(",
    "fun deleteWorldBook(worldBookId: String) =",
    "fun createKnowledgeBase(name: String, description: String = \"\") =",
    "fun importKnowledgeDocument(",
    "fun importKnowledgeDocument(uriString: String) =",
    "fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =",
    "fun deleteKnowledgeBase(knowledgeBaseId: String) =",
    "fun updateReasoningMode(mode: ReasoningMode) =",
    "fun showCloudReasoningModeLocked() =",
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
    "internal fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =",
    "fun selectChatModel(choiceId: String) =",
    "fun loadModel(requestedModel: ModelManifest) =",
    "fun verifyModel(model: ModelManifest) =",
    "fun unloadModel(model: ModelManifest) =",
    "fun deleteModel(model: ModelManifest) =",
    "fun resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String) =",
    "fun importOfflinePromptTranslationBundle(uri: Uri) =",
    "fun cancelOfflinePromptTranslationInstall() =",
    "fun downloadOfflinePromptTranslationBundle() =",
    "fun cancelOfflinePromptTranslationDownload() =",
    "fun cancelChatImageTranslationDraft(id: String) =",
    "fun sendChatImagePrompt() =",
    "fun setAssistantImageToolAutoApproval(enabled: Boolean) =",
    "fun retryChatImageGeneration(requestId: String) =",
    "fun approveChatImageRequest(requestId: String) =",
    "fun rejectChatImageRequest(requestId: String) =",
    "fun continueAssistantImageTurn(requestId: String) =",
    "fun cancelChatImageGeneration(requestId: String) =",
    "fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?)",
    "fun selectChatSession(sessionId: String) =",
    "fun branchMixedChatSession(sessionId: String, assistantId: String) =",
    "fun renameChatSession(sessionId: String, title: String) =",
    "fun toggleChatSessionPinned(sessionId: String) =",
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

# Ensure no signature in REMOVE_SIGNATURES touches MUST_KEEP
for sig in REMOVE_SIGNATURES:
    m = re.search(r'\b(fun|val|var)\s+([a-zA-Z0-9_]+)\b', sig)
    if m:
        fn_name = m.group(2)
        assert fn_name not in MUST_KEEP, f"CRITICAL: {fn_name} is in MUST_KEEP!"

def find_range(lines, sig):
    for i, line in enumerate(lines):
        if sig in line:
            start = i
            # Check for preceding comments/kdoc
            k = start - 1
            while k >= 0 and (lines[k].strip().startswith("//") or lines[k].strip().startswith("/*") or lines[k].strip().startswith("*") or lines[k].strip().startswith("*/")):
                start = k
                k -= 1
            
            # Find end
            curr = i
            while curr < len(lines):
                if "{" in lines[curr] or "=" in lines[curr]:
                    break
                curr += 1
            if curr >= len(lines):
                return start, i
            if "{" in lines[curr]:
                depth = 0
                for end in range(curr, len(lines)):
                    depth += lines[end].count("{") - lines[end].count("}")
                    if depth == 0:
                        return start, end
                return start, len(lines) - 1
            else:
                # Expression body
                indent = len(lines[i]) - len(lines[i].lstrip())
                for end in range(i + 1, len(lines)):
                    s = lines[end].strip()
                    if not s:
                        return start, end
                    k_indent = len(lines[end]) - len(lines[end].lstrip())
                    if k_indent <= indent and any(s.startswith(w) for w in ["fun ", "internal fun ", "private fun ", "val ", "internal val ", "private val ", "override ", "class "]):
                        return start, end - 1
                return start, len(lines) - 1
    return None

ranges_to_remove = []
for sig in REMOVE_SIGNATURES:
    r = find_range(lines, sig)
    if r is None:
        print(f"NOT FOUND: {sig}")
    else:
        ranges_to_remove.append(r)

print(f"Found {len(ranges_to_remove)} ranges to remove.")
# Sort ranges descending by start index
ranges_to_remove.sort(key=lambda x: x[0], reverse=True)

# Merge overlapping ranges
merged = []
for r in ranges_to_remove:
    if not merged:
        merged.append(r)
    else:
        prev = merged[-1]
        if r[1] >= prev[0]:
            merged[-1] = (min(r[0], prev[0]), max(r[1], prev[1]))
        else:
            merged.append(r)

print(f"Merged into {len(merged)} disjoint ranges.")

lines_to_keep = []
remove_indices = set()
for s, e in merged:
    for idx in range(s, e + 1):
        remove_indices.add(idx)

for idx, line in enumerate(lines):
    if idx not in remove_indices:
        lines_to_keep.append(line)

# Remove consecutive blank lines
cleaned_lines = []
for line in lines_to_keep:
    if line.strip() == "" and cleaned_lines and cleaned_lines[-1].strip() == "":
        continue
    cleaned_lines.append(line)

new_content = "\n".join(cleaned_lines) + "\n"
print(f"Original line count: {len(lines)}")
print(f"New line count: {len(cleaned_lines)}")

# Verify assertions
for req in MUST_KEEP:
    assert req in new_content, f"CRITICAL: {req} missing from new content!"

assert "this@MainViewModel" not in new_content
assert "_uiState.update" not in new_content

VM_PATH.write_text(new_content, encoding="utf-8")
print("Successfully wrote slimmed MainViewModel.kt!")
