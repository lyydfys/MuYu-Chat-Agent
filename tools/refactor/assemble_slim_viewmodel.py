#!/usr/bin/env python3
import re
from pathlib import Path

REPO_ROOT = Path("E:/MAC")
VM_PATH = REPO_ROOT / "app/src/main/java/com/muyuchat/mca/MainViewModel.kt"
content = VM_PATH.read_text(encoding="utf-8")
lines = content.splitlines()

# Targets to remove by finding exact boundaries:
def remove_function_range(source_lines, signature):
    idx = -1
    for i, line in enumerate(source_lines):
        if signature in line:
            idx = i
            break
    if idx == -1:
        return None
    # Find start (including preceding kdoc/comments)
    start = idx
    while start > 0 and source_lines[start - 1].strip().startswith(("//", "/*", "*", "*/")):
        start -= 1
    
    # Check if single line expression body
    line = source_lines[idx].strip()
    if "=" in line and "{" not in line:
        # Check if continuation lines
        indent = len(source_lines[idx]) - len(source_lines[idx].lstrip())
        end = idx
        while end + 1 < len(source_lines):
            next_line = source_lines[end + 1]
            if not next_line.strip():
                break
            next_indent = len(next_line) - len(next_line.lstrip())
            if next_indent <= indent and re.match(r'^\s*(fun |internal |private |val |var |class |override )', next_line):
                break
            end += 1
        return start, end
    
    # Block body
    curr = idx
    while curr < len(source_lines) and "{" not in source_lines[curr]:
        curr += 1
    if curr >= len(source_lines):
        return start, idx
    depth = 0
    for end in range(curr, len(source_lines)):
        depth += source_lines[end].count("{") - source_lines[end].count("}")
        if depth == 0:
            return start, end
    return start, len(source_lines) - 1

SIGNATURES_TO_REMOVE = [
    # Lines 281-301
    "internal fun appendAssistant(",
    "internal fun flushPendingAssistantOutput(",
    "internal fun discardPendingAssistantOutput(",
    # Lines 338-345
    "internal fun captureContentImportOwner(): ContentImportOwner",
    "internal fun ContentImportOwner.isCurrent(",
    # Lines 634-645
    "fun selectTab(",
    # Lines 692-698
    "suspend fun measureImagePromptTokens(",
    # Lines 712-717
    "suspend fun createUpscaledImageAsset(",
    # Lines 754-797
    "fun updateParams(",
    "private fun stageDirectParameterProfile(",
    # Lines 822-826
    "private fun finishSelectingNewAssistant(",
    "internal fun restorePendingContentImportPreview(",
    # Lines 830-831
    "private suspend fun reconcileModelIdentities(",
    # Lines 835-840
    "internal fun sendPreparedMessage(",
    # Lines 842-849
    "private fun invalidatePendingChatImagePreparation(",
    "suspend fun translateImagePromptPreview(",
    "suspend fun translateImageNegativePromptPreview(",
    # Lines 865-875
    "internal fun hostSendChatImageRequestWithPromptBridge(",
    # Lines 886-890
    "internal fun chatImageToolPermissionUiState(",
    "private fun continueAssistantImageToolTurn(",
    # Lines 967-986
    "internal fun startGenerationFromCoordinator(",
    # Lines 1096-1101
    "internal fun schedulePersistentPrefixCacheClear(",
    # Lines 1346-1348
    "private fun refreshPersistentPrefixCacheSummary(",
    # Lines 1391-1395
    "internal fun hostPersistChatSessions(",
    # Lines 1407-1412
    "internal fun persistKnowledgeBaseBindings(",
    # Lines 1414-1434
    "internal fun persistFiles(",
    "internal fun busy(",
    "internal fun failDownload(",
    "internal fun fail(",
    "internal fun chatImageOptionsForCurrentSelection(",
    # Lines 1380-1390
    "internal fun loadModelGenerationParams(",
    "internal fun persistModelGenerationParams(state: MainUiState",
    "internal fun persistModelGenerationParams(backend: ChatBackend",
    "private fun loadGenerationParams(",
    # Lines 1400-1405
    "internal fun prepareChatInput(",
    "private fun mimeTypeForUri(",
]

# Signatures that MUST NOT be removed under any circumstances:
MUST_PRESERVE = [
    "fun onAppBackgrounded()",
    "fun onAppForegrounded()",
    "fun regenerateLastResponse()",
    "override fun onCleared()",
    "fun selectAssistant(",
    "internal fun newChatForAssistant(",
    "fun deleteMessageAt(index: Int)",
    "fun deleteLastConversationTurn()",
    "fun stopGeneration()",
    "fun deleteChatSession(",
    "fun clearChatHistory()",
    "fun clearChat()",
    "fun newChat()",
    "internal fun startGeneration(",
    "fun updateAgentPreference(",
    "internal fun observeManagedDownloads()",
    "fun deleteLocalImageModel(",
    "fun selectImageGenerationModel(",
    "private fun settleLocalImageSelection(",
    "private fun rejectImageModelSwitchWhileGenerationIsActive()",
    "private fun publishLocalImagePreview(",
    "fun retryImageGeneration(",
    "fun setPersistentPrefixCacheEnabled(",
    "fun clearPersistentPrefixCache()",
    "private fun loadPersistentPrefixCacheEnabled(",
    "private fun persistPersistentPrefixCacheEnabled(",
    "private fun claimLocalApiProcessOwnership()",
    "internal fun showStatus(",
    "internal fun tryAcquireObservedImageGenerationLease(",
    "internal fun releaseObservedImageGenerationLease(",
    "fun releaseGenerationImageGrantsIfCoordinatorIdle(",
    "private fun registerLocalApiImageGenerationOwnership(",
    "private fun unregisterLocalApiImageGenerationOwnership(",
    "private fun cancelActiveLocalApiImageGeneration(",
    "private fun cancelOwnedLocalApiImageWorker(",
    "private fun updateGenerationImageGrantReleaseDefer(",
    "internal fun startAfterConstruction()",
    "internal fun publishManagedChatCatalogAfterValidation()",
    "internal fun sendChatImageRequestWithPromptBridge(",
    "internal fun chatImageNegativePromptSupported(",
    "fun deleteImageAsset(",
    "fun deleteImageAssets(",
    "fun setImageAssetFavorite(",
    "fun clearImageLibrary()",
    "fun updateChatImageLoraSelection(",
    "fun reportMissingLocalImageLoraSelection()",
    "internal fun markLocalConversationContextInvalid()",
    "internal fun rejectWhileConversationMutationInProgress()",
    "internal suspend fun ensureLocalConversationContextInvalidated()",
    "internal fun persistChatSessions(",
    "private fun persistConversationMutation(",
    "private suspend fun generateLocalApiImage(",
    "internal fun persistApiPreferences(",
    "private fun loadApiPreferences(",
    "internal fun apiUrl(",
    "internal fun currentOpenApiAddress()",
]

ranges_to_remove = []
for sig in SIGNATURES_TO_REMOVE:
    r = remove_function_range(lines, sig)
    if r is None:
        print(f"WARNING: signature not found: {sig}")
    else:
        # Check against must preserve
        found_preserve = False
        for p in MUST_PRESERVE:
            if any(p in lines[k] for k in range(r[0], r[1] + 1)):
                print(f"CRITICAL ERROR: {p} would be removed by {sig} ({r})")
                found_preserve = True
        if not found_preserve:
            ranges_to_remove.append(r)

print(f"Found {len(ranges_to_remove)} ranges to remove.")
# Also remove unused properties:
# Lines 152-158: apiLifecycleMutex etc.
# Lines 321-337: durableChatSessions etc.
# Lines 347-362: imageLibraryBackupJob etc.
property_sigs = [
    "private val apiLifecycleMutex get() = localApiServerCoordinator.apiLifecycleMutex",
    "private val apiLifecycleSequence get() = localApiServerCoordinator.apiLifecycleSequence",
    "private val apiLifecycleClosed get() = localApiServerCoordinator.apiLifecycleClosed",
    "private var apiLifecycleRequestJob: Job?",
    "private var durableChatSessions: List<ChatSessionRecord>",
    "private val chatSessionPersistenceMutex: Mutex get() = container.chatSessionPersistenceState.persistenceMutex",
    "private val chatSessionPersistenceSequence: AtomicLong get() = container.chatSessionPersistenceState.persistenceSequence",
    "private val chatSessionPersistenceStateLock: Any get() = container.chatSessionPersistenceState.stateLock",
    "private val pendingKnowledgeBindings: MutableMap<String, Set<String>> get() = container.chatSessionPersistenceState.pendingKnowledgeBindings",
    "private val pendingWorldBookCleanupOwners: MutableMap<WorldBookScope, MutableSet<String>> get() = container.chatSessionPersistenceState.pendingWorldBookCleanupOwners",
    "private var directParameterStageJob: Job?",
    "private val directParameterStageMutex: Mutex get() = container.runtimeAdmission.directParameterStageMutex",
    "private val directParameterStageGeneration: AtomicLong get() = container.runtimeAdmission.directParameterStageGeneration",
    "private val imageAssetWriteMutex: Mutex get() = container.imageAssetWriteMutex",
    "private val imageLibraryMutationMutex: Mutex get() = container.imageLibraryMutationMutex",
    "private val contentImportSelectionRevision: AtomicLong get() = container.runtimeAdmission.contentImportSelectionRevision",
    "private var imageLibraryBackupJob: Job?",
    "private var activeRuntimeIdentity: ModelRuntimeIdentity?",
    "private var activeAdaptiveRecommendation: AdaptiveTuningRecommendation?",
    "private var pendingProfileTransactionId: String?",
    "private var activeModelForRuntimeProfile: ModelManifest?",
]

for ps in property_sigs:
    r = remove_function_range(lines, ps)
    if r:
        ranges_to_remove.append(r)

# Sort descending
ranges_to_remove.sort(key=lambda x: x[0], reverse=True)

# Merge overlapping
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

remove_set = set()
for s, e in merged:
    for idx in range(s, e + 1):
        remove_set.add(idx)

new_lines = []
for i, line in enumerate(lines):
    if i not in remove_set:
        new_lines.append(line)

# Clean consecutive blank lines
final_lines = []
for line in new_lines:
    if not line.strip() and final_lines and not final_lines[-1].strip():
        continue
    final_lines.append(line)

print(f"Original line count: {len(lines)}")
print(f"New line count: {len(final_lines)}")

new_text = "\n".join(final_lines) + "\n"

# Verify all must_preserve:
for p in MUST_PRESERVE:
    assert p in new_text, f"VERIFICATION FAILED: {p} missing from new_text!"

print("All MUST_PRESERVE verified!")
