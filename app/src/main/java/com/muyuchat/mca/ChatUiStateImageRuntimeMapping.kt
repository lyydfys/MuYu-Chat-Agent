package com.muyuchat.mca

import com.muyuchat.feature.chat.FileAssetUiItem
import com.muyuchat.feature.chat.ImageGenerationUiJob
import com.muyuchat.feature.chat.ImageLibraryBackupUiState
import com.muyuchat.feature.chat.ImageLoraUiItem
import com.muyuchat.feature.chat.ImageTextualInversionUiItem
import com.muyuchat.feature.chat.ImageUpscalerUiItem
import com.muyuchat.mca.presentation.formatAssetBytes

/** 由 ChatUiStateMapping.kt 平移的纯映射块（同包拆分，无行为变化）。 */

internal fun imageLibraryBackupUiState(state: MainUiState): ImageLibraryBackupUiState =
    ImageLibraryBackupUiState(
        running = state.imageLibraryBackup.running,
        importing = state.imageLibraryBackup.importing,
        done = state.imageLibraryBackup.done,
        total = state.imageLibraryBackup.total,
        message = state.imageLibraryBackup.message,
        failed = state.imageLibraryBackup.failed
    )

internal fun buildImageLoraUiItems(state: MainUiState): List<ImageLoraUiItem> =
    state.localImageLoras.map { adapter ->
        ImageLoraUiItem(
            id = adapter.id,
            name = adapter.name,
            sizeText = formatAssetBytes(adapter.sizeBytes),
            sha256 = adapter.sha256,
            inUse = adapter.id in state.activeLocalImageLoraIds,
            isQwenImage21ViggleV021 =
                QwenImage21TurboSchedule.isOfficialV021Sha256(adapter.sha256)
        )
    }

internal fun buildImageTextualInversionUiItems(state: MainUiState): List<ImageTextualInversionUiItem> =
    state.localImageTextualInversions.map { artifact ->
        ImageTextualInversionUiItem(
            id = artifact.id,
            name = artifact.name,
            trigger = artifact.trigger,
            format = artifact.format.wireName,
            sizeText = formatAssetBytes(artifact.sizeBytes),
            sha256 = artifact.sha256,
            inUse = artifact.id in state.activeLocalImageTextualInversionIds ||
                artifact.id in state.deletingLocalImageTextualInversionIds
        )
    }

internal fun buildImageUpscalerUiItems(state: MainUiState): List<ImageUpscalerUiItem> =
    state.localImageUpscalers.map { upscaler ->
        ImageUpscalerUiItem(
            id = upscaler.id,
            name = upscaler.name,
            sizeText = formatAssetBytes(upscaler.sizeBytes),
            sha256 = upscaler.sha256,
            selected = upscaler.id == state.selectedLocalImageUpscalerId,
            inUse = upscaler.id == state.activeLocalImageUpscalerId,
            deleting = upscaler.id == state.localImageUpscalerDeletingId
        )
    }

internal fun buildFileAssetUiItems(
    state: MainUiState,
    timestampLocale: java.util.Locale
): List<FileAssetUiItem> =
    state.files.map { file ->
        FileAssetUiItem(
            id = file.id,
            name = file.name,
            mimeType = file.mimeType,
            preview = file.preview,
            createdAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
                .format(java.util.Date(file.createdAt)),
            sizeText = formatAssetBytes(file.sizeBytes),
            truncated = file.truncated
        )
    }

internal fun buildImageGenerationUiJobs(state: MainUiState): List<ImageGenerationUiJob> =
    state.imageJobs.map { job ->
        ImageGenerationUiJob(
            id = job.id,
            prompt = job.prompt,
            statusLabel = job.status.label,
            modelId = job.modelId,
            modelName = job.modelName,
            modelIsCloud = job.backend == ImageBackend.CLOUD,
            chatSessionId = job.spec?.chatSessionId,
            chatMessageId = job.spec?.chatMessageId,
            imageAssetId = job.imageAssetId,
            imageAssetIds = job.imageAssetIds,
            completedProgressCount = job.completedProgressCount,
            expectedOutputCount = job.spec?.options?.batchCount ?: 1,
            previewUriString = job.previewUriString,
            previewMode = job.previewMode,
            previewStep = job.previewStep,
            previewRevision = job.previewRevision,
            previewWidth = job.previewWidth,
            previewHeight = job.previewHeight,
            failed = job.status.failed,
            terminal = job.status.terminal,
            message = job.message,
            translationPreview = job.spec?.promptExecution
                ?.takeIf { execution ->
                    execution.method in setOf(
                        LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
                        LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
                    )
                }
                ?.let { execution ->
                    if (execution.originalPrompt.containsHanScript()) {
                        execution.effectivePrompt
                    } else {
                        execution.effectiveNegativePrompt
                    }
                },
            startedAtMillis = job.startedAtMillis,
            startedAtElapsedMs = job.startedAtElapsedMs
        )
    }
