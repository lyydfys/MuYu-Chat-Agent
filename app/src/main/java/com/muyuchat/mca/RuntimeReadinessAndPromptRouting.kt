package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

// Runtime readiness refresh gating, verified-model selection, and the local image prompt
// transformation decision.
//
// Moved here verbatim from MainViewModel during P2, with two exceptions noted below.
//
// ManagedRuntimeReadinessRefreshGate is the monotonic publication gate for the asynchronous
// managed-model readiness refresh. Its own doc, kept here, says why it exists: a refresh may
// hash large model packages for several seconds, and any newer refresh or catalog mutation
// invalidates its token, so a late result cannot replace a newer UI or catalog projection.
//
// verifiedModelIdsWithUnchangedSha and shouldSurfaceManagedRuntimeRefreshFailure decide which
// models may keep a verified mark and when a refresh failure is worth showing. Neither is an
// admission gate: they rank and report.
//
// Two declarations widened from private to internal: ManagedChatCatalogSnapshot and
// LocalApiImageGenerationOwnership. Both are small carriers the ViewModel class body uses, so a
// top-level private would not have crossed files. internal keeps them inside this module.
//
// Kept in com.muyuchat.mca: no import changes anywhere else, and no test changed. Two test
// files name these declarations and every use is either a direct call or a source-text
// assertion on a call site that stayed in the ViewModel.
//
// No field, threshold, branch or rule was edited.

/**
 * Monotonic publication gate for the asynchronous managed-model readiness refresh.
 *
 * A refresh may hash large model packages for several seconds. Any newer refresh or catalog
 * mutation invalidates its token so a late result cannot replace a newer UI/catalog projection.
 */
internal class ManagedRuntimeReadinessRefreshGate {
    private val epoch = AtomicLong(0L)

    fun begin(): Long = epoch.incrementAndGet()

    fun invalidate() {
        epoch.incrementAndGet()
    }

    fun isCurrent(token: Long): Boolean = epoch.get() == token
}

/** Keeps advisory verification only while the exact persisted package identity is unchanged. */
internal fun verifiedModelIdsWithUnchangedSha(
    verifiedShaByModelId: Map<String, String>,
    currentShaByModelId: Map<String, String>
): Set<String> = currentShaByModelId
    .asSequence()
    .filter { (modelId, sha256) ->
        sha256.isNotBlank() && verifiedShaByModelId[modelId] == sha256
    }
    .map { it.key }
    .toSet()

internal fun shouldSurfaceManagedRuntimeRefreshFailure(
    refreshCurrent: Boolean,
    busy: Boolean,
    isGenerating: Boolean,
    imageLibraryBackupRunning: Boolean,
    imageLibraryBackupJobActive: Boolean,
    generationImageGrantReleaseDeferred: Boolean,
    activeImageGeneration: Boolean,
    activeImageUpscale: Boolean,
    activeLocalApiImageGeneration: Boolean,
    coordinatorActive: Boolean
): Boolean = refreshCurrent &&
    !busy &&
    !isGenerating &&
    !imageLibraryBackupRunning &&
    !imageLibraryBackupJobActive &&
    !generationImageGrantReleaseDeferred &&
    !activeImageGeneration &&
    !activeImageUpscale &&
    !activeLocalApiImageGeneration &&
    !coordinatorActive

internal data class ManagedChatCatalogSnapshot(
    val models: List<ModelManifest>,
    val qairtVerifiedLocalModelIds: Set<String>
)

internal data class LocalApiImageGenerationOwnership(
    val requestId: String,
    val requestJob: Job
)

internal fun requiredLocalImagePromptTransformationMethod(
    containsChinese: Boolean,
    languageCapability: LocalImageTextEncoderLanguageCapability
): LocalImagePromptTransformationMethod = when {
    !containsChinese -> LocalImagePromptTransformationMethod.DIRECT
    languageCapability == LocalImageTextEncoderLanguageCapability.NATIVE_MULTILINGUAL ->
        LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL
    else -> LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
}

internal fun offlineImagePromptTranslationRequest(
    prompt: String,
    finalNegativePrompt: String
): OfflinePromptTranslationRequest {
    val promptHasHan = prompt.containsHanScript()
    val negativeHasHan = finalNegativePrompt.containsHanScript()
    require(promptHasHan || negativeHasHan)
    return if (promptHasHan) {
        OfflinePromptTranslationRequest(
            sourceText = prompt,
            negativePrompt = finalNegativePrompt.takeIf { negativeHasHan }.orEmpty()
        )
    } else {
        OfflinePromptTranslationRequest(sourceText = finalNegativePrompt)
    }
}
