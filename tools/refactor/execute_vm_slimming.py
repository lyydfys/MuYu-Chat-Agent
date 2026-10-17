#!/usr/bin/env python3
import re
from pathlib import Path

REPO_ROOT = Path("E:/MAC")
VM_PATH = REPO_ROOT / "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"
content = VM_PATH.read_text(encoding="utf-8")
lines = content.splitlines()

MUST_KEEP = {
    "onAppBackgrounded",
    "onAppForegrounded",
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
    "startGenerationFromCoordinator",
    "reserveUiGenerationStart",
    "cancelGenerationJob",
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
    "schedulePersistentPrefixCacheClear",
    "claimLocalApiProcessOwnership",
    "releaseLocalApiProcessOwnership",
    "retireLocalApiListener",
    "requestLocalApiState",
    "applyLocalApiState",
    "awaitLocalApiForegroundServiceReady",
    "ensureLocalApiForegroundServiceReady",
    "setLocalApiForegroundService",
    "startApiServer",
    "stopApiServer",
    "showStatus",
    "ensureLocalConversationContextInvalidated",
    "unregisterLocalApiImageGenerationOwnership",
    "cancelActiveLocalApiImageGeneration",
    "cancelOwnedLocalApiImageWorker",
    "generateLocalApiImage",
    "tryAcquireObservedImageGenerationLease",
    "releaseObservedImageGenerationLease",
    "releaseGenerationImageGrantsIfCoordinatorIdle",
    "updateGenerationImageGrantReleaseDefer",
    "startAfterConstruction",
    "publishManagedChatCatalogAfterValidation",
    "selectTab",
    "appendAssistant",
    "flushPendingAssistantOutput",
    "discardPendingAssistantOutput",
    "captureContentImportOwner",
    "isCurrent",
    "refreshPersistentPrefixCacheSummary",
    "markLocalConversationContextInvalid",
    "rejectWhileConversationMutationInProgress",
    "ensureLocalConversationContextInvalidated",
    "persistChatSessions",
    "hostPersistChatSessions",
    "persistConversationMutation",
    "persistKnowledgeBaseBindings",
    "persistFiles",
    "busy",
    "failDownload",
    "fail",
    "chatImageOptionsForCurrentSelection",
    "loadApiPreferences",
    "persistApiPreferences",
    "loadModelGenerationParams",
    "persistModelGenerationParams",
    "loadGenerationParams",
    "persistPersistentPrefixCacheEnabled",
    "apiUrl",
    "prepareChatInput",
    "mimeTypeForUri",
    "currentOpenApiAddress",
    "measureImagePromptTokens",
    "updateParams",
    "stageDirectParameterProfile",
    "finishSelectingNewAssistant",
    "restorePendingContentImportPreview",
    "stageContentImportPreview",
    "toggleApi",
    "toggleRest",
    "createUpscaledImageAsset",
    "reconcileModelIdentities",
    "launchModelOperation",
    "selectCloudChatModel",
    "sendMessage",
    "sendPreparedMessage",
    "invalidatePendingChatImagePreparation",
    "translateImagePromptPreview",
    "translateImageNegativePromptPreview",
    "sendChatImageRequestWithPromptBridge",
    "hostSendChatImageRequestWithPromptBridge",
    "chatImageNegativePromptSupported",
    "chatImageToolPermissionUiState",
    "continueAssistantImageToolTurn"
}

# The names of functions that have been moved to delegate files:
DELEGATED_NAMES = [
    # MainViewModelAppUpdateDelegates.kt
    "maybeCheckForAppUpdate",
    "checkForAppUpdate",
    "setAppUpdateAutoCheckEnabled",
    "downloadAppUpdate",
    "installAppUpdate",
    "openAppUpdateRelease",
    # MainViewModelIntentDelegates.kt
    "emitEffect",
    "processIntent",
    # MainViewModelChatSessionDelegates.kt
    "onInputChange",
    "clearStatusMessage",
    "startBrowserTask",
    "onBrowserTaskEvent",
    "requestContextCompression",
    "toggleMessagePinned",
    "undoContextSummary",
    "contextSummaryEvidenceForQuery",
    "setContextCompressionThreshold",
    "setContextSummaryModel",
    "selectChatSession",
    "branchMixedChatSession",
    "renameChatSession",
    "toggleChatSessionPinned",
    # MainViewModelImageDelegates.kt
    "upscaleImageAsset",
    "cancelImageUpscale",
    "attachFile",
    "useImageAsset",
    "useFileAsset",
    "deleteFileAsset",
    "clearFileLibrary",
    "importLocalImageLora",
    "deleteLocalImageLora",
    "importLocalImageTextualInversion",
    "deleteLocalImageTextualInversion",
    "downloadOfficialViggleLora",
    "reportMissingLocalImageLoraSelection",
    "reportMissingLocalImageTextualInversionSelection",
    "importLocalImageUpscaler",
    "selectLocalImageUpscaler",
    "deleteLocalImageUpscaler",
    "generateImageAsset",
    "recreateImageAsset",
    "selectLocalImageModel",
    "cancelImageGeneration",
    "cancelImageGenerationForJob",
    "deleteImageAsset",
    "deleteImageAssets",
    "setImageAssetFavorite",
    "clearImageLibrary",
    "exportImageLibraryBackup",
    "importImageLibraryBackup",
    "cancelImageLibraryBackup",
    "importLocalImageModel",
    "verifyLocalImageModel",
    "removeLocalImageModelRecord",
    "onRepoInputChange",
    "onHubQueryChange",
    "buildChatImageRetrySnapshot",
    # MainViewModelAssistantAndKnowledgeDelegates.kt
    "saveAssistantProfile",
    "upsertAssistantMemory",
    "deleteAssistantMemory",
    "createChatForAssistant",
    "importChatBackground",
    "setChatAppearance",
    "deleteAssistant",
    "importAssistantCard",
    "cancelContentImportPreview",
    "confirmContentImportPreview",
    "importAssistantCardFile",
    "importWorldBook",
    "importWorldBookFile",
    "deleteWorldBook",
    "createKnowledgeBase",
    "importKnowledgeDocument",
    "setKnowledgeBaseSelected",
    "deleteKnowledgeBase",
    "updateReasoningMode",
    "showCloudReasoningModeLocked",
    "saveWebSearchConfig",
    "testWebSearchConfig",
    "preflightWebSearchConfig",
    "testWebSearchTurn",
    "toggleWebSearchForNextTurn",
    "cycleWebSearchResearchModeForNextTurn",
    "selectWebSearchResearchModeForNextTurn",
    "clearWebSearchDiagnostics",
    "exportDiagnosticReport",
    "chatSessionExportFileName",
    "exportChatSession",
    "refreshLogs",
    "refreshDiagnostics",
    # MainViewModelCloudAndHubDelegates.kt
    "deleteCloudModel",
    "updateCloudApiEnabled",
    "updateCloudApiFormat",
    "updateCloudImageApiFormat",
    "applyCloudProviderPreset",
    "beginAddCloudModel",
    "editCloudModel",
    "updateCloudBaseUrl",
    "updateCloudApiKey",
    "updateCloudChatModel",
    "updateCloudSupportsVision",
    "updateModelBackendPreference",
    "generationParamsForChatModel",
    "updateCloudSupportsTools",
    "updateCloudImageModel",
    "updateCloudResponsesReasoning",
    "updateCloudImageSize",
    "updateCloudImageEndpointPath",
    "updateCloudDisplayName",
    "saveCloudApiConfig",
    "saveCloudChatModel",
    "saveCloudImageModel",
    "testCloudApiConfig",
    "selectCloudImageModel",
    "fetchRemoteFiles",
    "fetchRecommendedFiles",
    "downloadRecommended",
    "searchHubModels",
    "fetchHubModelFiles",
    "openModelScopePage",
    "download",
    # MainViewModelModelDelegates.kt
    "refreshLocalModels",
    "importModel",
    "importModelDirectory",
    "pauseManagedImport",
    "resumeManagedImport",
    "pauseManagedDownloads",
    "resumeManagedDownloads",
    "enqueueManagedDownload",
    "attachVisionProjector",
    "selectChatModel",
    "loadModel",
    "verifyModel",
    "unloadModel",
    "deleteModel",
    # MainViewModelPromptAndImageTurnDelegates.kt
    "confirmChatImageTranslationDraft",
    "resolveChatImageActionDraft",
    "importOfflinePromptTranslationBundle",
    "cancelOfflinePromptTranslationInstall",
    "downloadOfflinePromptTranslationBundle",
    "cancelOfflinePromptTranslationDownload",
    "cancelChatImageTranslationDraft",
    "sendChatImagePrompt",
    "setAssistantImageToolAutoApproval",
    "retryChatImageGeneration",
    "approveChatImageRequest",
    "rejectChatImageRequest",
    "continueAssistantImageTurn",
    "cancelChatImageGeneration",
    "updateChatImageLoraSelection",
    # MainViewModelAgentDelegates.kt
    "scanAgent",
    "applyAgentRecommendation",
    "rollbackAgentParams",
    "loadAgentRecommendedModel",
    "runAgentBenchmark",
    "runAgentStabilitySmoke",
    "startAgentTuning",
    "pauseAgentTuning",
    "resumeAgentTuning",
    "cancelAgentTuning",
    "queryAgentTuningJob",
    "applyPendingAgentProfile",
    "discardPendingAgentProfile",
    "rollbackAgentProfile",
    "runAgentQuickDebug",
    "runAgentStandardDebug",
    "runAgentDeepDebug",
    "runAgentPowerDebug",
    "showAgentDebugExplanation",
    "currentDeviceProfile",
    "buildRecommendation"
]

# Ensure no collision with MUST_KEEP
for name in DELEGATED_NAMES:
    assert name not in MUST_KEEP, f"CRITICAL CONFLICT: {name} is in MUST_KEEP!"

# Find range for each function declaration in MainViewModel.kt
func_decl_pattern = re.compile(r'^\s*(?:override\s+|internal\s+|private\s+|public\s+)?(?:suspend\s+)?fun\s+([a-zA-Z0-9_]+)\b')

def get_decl_range(lines, start_idx):
    # start_idx is the line with fun ...
    # Look back for comments
    s = start_idx
    k = s - 1
    while k >= 0:
        stripped = lines[k].strip()
        if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or stripped.startswith("*/"):
            s = k
            k -= 1
        elif stripped.startswith("@"):
            s = k
            k -= 1
        else:
            break
    
    # Now find end
    curr = start_idx
    while curr < len(lines):
        if "{" in lines[curr] or "=" in lines[curr]:
            break
        curr += 1
    if curr >= len(lines):
        return s, start_idx
    
    if "{" in lines[curr]:
        depth = 0
        for end in range(curr, len(lines)):
            depth += lines[end].count("{") - lines[end].count("}")
            if depth == 0:
                return s, end
        return s, len(lines) - 1
    else:
        # Expression body
        indent = len(lines[start_idx]) - len(lines[start_idx].lstrip())
        for end in range(start_idx + 1, len(lines)):
            line_str = lines[end].strip()
            if not line_str:
                return s, end
            k_indent = len(lines[end]) - len(lines[end].lstrip())
            if k_indent <= indent:
                if any(line_str.startswith(w) for w in [
                    "fun ", "internal fun ", "private fun ", "override ", "public fun ",
                    "val ", "internal val ", "private val ", "var ", "internal var ", "private var ",
                    "class ", "init ", "/*", "//"
                ]):
                    return s, end - 1
        return s, len(lines) - 1

delegated_set = set(DELEGATED_NAMES)
removed_ranges = []
i = 0
while i < len(lines):
    line = lines[i]
    m = func_decl_pattern.match(line)
    if m:
        name = m.group(1)
        if name in delegated_set and name not in MUST_KEEP:
            s, e = get_decl_range(lines, i)
            removed_ranges.append((s, e, name))
            i = e + 1
            continue
    i += 1

print(f"Total function occurrences removed: {len(removed_ranges)}")
# Merge ranges
removed_indices = set()
for s, e, name in removed_ranges:
    for idx in range(s, e + 1):
        removed_indices.add(idx)

new_lines = []
for idx, line in enumerate(lines):
    if idx not in removed_indices:
        new_lines.append(line)

# Clean excessive blank lines (more than 1 blank line in a row)
cleaned = []
for line in new_lines:
    if line.strip() == "" and cleaned and cleaned[-1].strip() == "":
        continue
    cleaned.append(line)

new_text = "\n".join(cleaned) + "\n"
print(f"Original lines: {len(lines)}")
print(f"New lines: {len(cleaned)}")

# Verify all MUST_KEEP methods are still present
for req in MUST_KEEP:
    assert req in new_text, f"CRITICAL: {req} missing from MainViewModel.kt!"

assert "this@MainViewModel" not in new_text, "Found this@MainViewModel!"
assert "_uiState.update" not in new_text, "Found _uiState.update!"

VM_PATH.write_text(new_text, encoding="utf-8")
print("SUCCESS: MainViewModel.kt updated cleanly!")
