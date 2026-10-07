package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.deduplicateVisionAttachmentsForDisplay
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import java.io.File

internal class ChatInputAndAttachmentCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateStore,
    private val container: com.muyuchat.mca.composition.McaAppContainer,
    private val showStatus: (String?) -> Unit,
    private val fail: (String) -> Unit,
    private val invalidatePendingChatImagePreparation: () -> Unit,
    private val awaitImageLibraryStartupReconciliation: suspend () -> Unit,
    private val imageAssetWriteMutex: Mutex,
    private val imageAssetDirectory: File,
    private val imageLibraryCommitPort: () -> ImageLibraryCommitPort,
    private val imagePromptTokenMeasurementHost: ImagePromptTokenMeasurementHost,
    private val visionAttachmentPort: VisionAttachmentPort,
    private val chatSessionStore: ChatSessionStore
) {
    fun onInputChange(value: String) {
        if (value != store.current.input &&
            (container.runtimeAdmission.pendingImageTranslation != null || container.runtimeAdmission.pendingChatImageAction != null)
        ) invalidatePendingChatImagePreparation()
        val bounded = value.takeUtf8Prefix(MainViewModel.MAX_DIRECT_COMPOSER_UTF8_BYTES)
        store.update {
            it.copy(
                input = bounded.text,
                statusMessage = if (bounded.truncated) {
                    "输入超过 256 KiB，已保留前半部分；较长资料请导入知识库或作为文件使用。"
                } else {
                    it.statusMessage
                }
            )
        }
    }

    fun clearStatusMessage() {
        showStatus(null)
    }

    fun attachFile(uriString: String) =
        attachFileFor(
            store = store,
            scope = scope,
            contentResolver = application.contentResolver,
            imageAssetWriteMutex = imageAssetWriteMutex,
            imageAssetDirectory = imageAssetDirectory,
            imageLibraryCommitPort = imageLibraryCommitPort(),
            awaitImageLibraryStartupReconciliation = awaitImageLibraryStartupReconciliation,
            prepareChatInput = { prepareChatInput(it) },
            persistFiles = { persistFiles(it) },
            showStatus = showStatus,
            fail = fail,
            uriString = uriString
        )

    fun persistFiles(files: List<FileAssetRecord> = store.current.files) =
        persistFilesFor(chatSessionStore, store, scope, files)

    fun prepareChatInput(state: MainUiState): PreparedChatInput {
        val matches = imageAttachmentRegex.findAll(state.input).toList()
        val attachments = matches.mapNotNull { match ->
            val name = match.groupValues.getOrNull(1).orEmpty().trim()
            val uriString = match.groupValues.getOrNull(2).orEmpty().trim()
            if (uriString.isBlank()) return@mapNotNull null
            val asset = state.images.firstOrNull { it.uriString == uriString || it.name == name }
            asset?.toChatAttachment(mimeTypeForUri(uriString))
                ?: ChatImageAttachment(
                    name = name,
                    uriString = uriString,
                    mimeType = mimeTypeForUri(uriString)
                )
        }
        val textWithoutAttachments = imageAttachmentRegex
            .replace(state.input, "")
            .replace(oldImagePlaceholderRegex, "")
            .trim()
        return PreparedChatInput(
            text = textWithoutAttachments,
            imageAttachments = attachments.deduplicateVisionAttachmentsForDisplay()
        )
    }

    fun mimeTypeForUri(uriString: String): String =
        visionMimeTypeForUri(visionAttachmentPort, uriString)

    suspend fun measureImagePromptTokens(
        modelChoiceId: String,
        prompt: String,
    ): ImagePromptTokenMeasurement? = com.muyuchat.mca.measureImagePromptTokens(
        host = imagePromptTokenMeasurementHost,
        modelChoiceId = modelChoiceId,
        prompt = prompt
    )
}
