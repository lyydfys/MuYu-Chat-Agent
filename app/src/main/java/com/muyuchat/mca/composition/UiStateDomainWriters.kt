package com.muyuchat.mca.composition

import com.muyuchat.mca.*

internal fun UiStateStore.generationLifecycleWriter(): UiStateWriter<GenerationLifecycleFields> = writer(
    project = { it.toGenerationLifecycleFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.generationExecutionWriter(): UiStateWriter<GenerationExecutionFields> = writer(
    project = { it.toGenerationExecutionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatPromptDraftWriter(): UiStateWriter<ChatPromptDraftFields> = writer(
    project = { it.toChatPromptDraftFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.generationStopWriter(): UiStateWriter<GenerationStopFields> = writer(
    project = { it.toGenerationStopFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.assistantProfileWriter(): UiStateWriter<AssistantProfileFields> = writer(
    project = { it.toAssistantProfileFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.assistantMemoryWriter(): UiStateWriter<AssistantMemoryFields> = writer(
    project = { it.toAssistantMemoryFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.knowledgeLibraryWriter(): UiStateWriter<KnowledgeLibraryFields> = writer(
    project = { it.toKnowledgeLibraryFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatAppearanceWriter(): UiStateWriter<ChatAppearanceFields> = writer(
    project = { it.toChatAppearanceFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.contentImportWriter(): UiStateWriter<ContentImportFields> = writer(
    project = { it.toContentImportFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.cacheSettingsWriter(): UiStateWriter<CacheSettingsFields> = writer(
    project = { it.toCacheSettingsFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.appUpdateWriter(): UiStateWriter<AppUpdateFields> = writer(
    project = { it.toAppUpdateFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.webSearchWriter(): UiStateWriter<WebSearchFields> = writer(
    project = { it.toWebSearchFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.browserTaskWriter(): UiStateWriter<BrowserTaskFields> = writer(
    project = { it.toBrowserTaskFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.settingsParameterWriter(): UiStateWriter<SettingsParameterFields> = writer(
    project = { it.toSettingsParameterFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.runtimeProfileWriter(): UiStateWriter<RuntimeProfileFields> = writer(
    project = { it.toRuntimeProfileFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatModelSelectionWriter(): UiStateWriter<ChatModelSelectionFields> = writer(
    project = { it.toChatModelSelectionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageJobCancellationWriter(): UiStateWriter<ImageJobCancellationFields> = writer(
    project = { it.toImageJobCancellationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.modelCatalogWriter(): UiStateWriter<ModelCatalogFields> = writer(
    project = { it.toModelCatalogFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.modelIdentityWriter(): UiStateWriter<ModelIdentityFields> = writer(
    project = { it.toModelIdentityFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.modelHubWriter(): UiStateWriter<ModelHubFields> = writer(
    project = { it.toModelHubFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.localApiSettingsWriter(): UiStateWriter<LocalApiSettingsFields> = writer(
    project = { it.toLocalApiSettingsFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.startupReconciliationWriter(): UiStateWriter<StartupReconciliationFields> = writer(
    project = { it.toStartupReconciliationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationSessionWriter(): UiStateWriter<ConversationSessionFields> = writer(
    project = { it.toConversationSessionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationSelectionWriter(): UiStateWriter<ConversationSelectionFields> = writer(
    project = { it.toConversationSelectionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationPersistenceWriter(): UiStateWriter<ConversationPersistenceFields> = writer(
    project = { it.toConversationPersistenceFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationSettlementWriter(): UiStateWriter<ConversationSettlementFields> = writer(
    project = { it.toConversationSettlementFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationWorkflowWriter(): UiStateWriter<ConversationWorkflowFields> = writer(
    project = { it.toConversationWorkflowFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatAttachmentWriter(): UiStateWriter<ChatAttachmentFields> = writer(
    project = { it.toChatAttachmentFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.contextCompressionWriter(): UiStateWriter<ContextCompressionFields> = writer(
    project = { it.toContextCompressionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationTraceWriter(): UiStateWriter<ConversationTraceFields> = writer(
    project = { it.toConversationTraceFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationMessageWriter(): UiStateWriter<ConversationMessageFields> = writer(
    project = { it.toConversationMessageFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationStatusWriter(): UiStateWriter<ConversationStatusFields> = writer(
    project = { it.toConversationStatusFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.assistantImageContinuationWriter(): UiStateWriter<AssistantImageContinuationFields> = writer(
    project = { it.toAssistantImageContinuationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationRegenerationWriter(): UiStateWriter<ConversationRegenerationFields> = writer(
    project = { it.toConversationRegenerationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.conversationResetWriter(): UiStateWriter<ConversationResetFields> = writer(
    project = { it.toConversationResetFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.settingsOperationWriter(): UiStateWriter<SettingsOperationFields> = writer(
    project = { it.toSettingsOperationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.navigationWriter(): UiStateWriter<NavigationFields> = writer(
    project = { it.toNavigationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.uiOperationFeedbackWriter(): UiStateWriter<UiOperationFeedbackFields> = writer(
    project = { it.toUiOperationFeedbackFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.hubInputWriter(): UiStateWriter<HubInputFields> = writer(
    project = { it.toHubInputFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatLoraSelectionWriter(): UiStateWriter<ChatLoraSelectionFields> = writer(
    project = { it.toChatLoraSelectionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageModelSelectionWriter(): UiStateWriter<ImageModelSelectionFields> = writer(
    project = { it.toImageModelSelectionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageAssetCreationWriter(): UiStateWriter<ImageAssetCreationFields> = writer(
    project = { it.toImageAssetCreationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageAssetDeletionWriter(): UiStateWriter<ImageAssetDeletionFields> = writer(
    project = { it.toImageAssetDeletionFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageLibraryCommitWriter(): UiStateWriter<ImageLibraryCommitFields> = writer(
    project = { it.toImageLibraryCommitFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageGenerationWriter(): UiStateWriter<ImageGenerationFields> = writer(
    project = { it.toImageGenerationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.localImageAdapterWriter(): UiStateWriter<LocalImageAdapterFields> = writer(
    project = { it.toLocalImageAdapterFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageUpscalerCatalogWriter(): UiStateWriter<ImageUpscalerCatalogFields> = writer(
    project = { it.toImageUpscalerCatalogFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageUpscaleWriter(): UiStateWriter<ImageUpscaleFields> = writer(
    project = { it.toImageUpscaleFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageTranslationWriter(): UiStateWriter<ImageTranslationFields> = writer(
    project = { it.toImageTranslationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.localApiImageWriter(): UiStateWriter<LocalApiImageFields> = writer(
    project = { it.toLocalApiImageFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageLibraryBackupWriter(): UiStateWriter<ImageLibraryBackupFields> = writer(
    project = { it.toImageLibraryBackupFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.assetReconciliationWriter(): UiStateWriter<AssetReconciliationFields> = writer(
    project = { it.toAssetReconciliationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.imageWorkspaceWriter(): UiStateWriter<ImageWorkspaceFields> = writer(
    project = { it.toImageWorkspaceFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatImageRequestWriter(): UiStateWriter<ChatImageRequestFields> = writer(
    project = { it.toChatImageRequestFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.sendChatImageWriter(): UiStateWriter<SendChatImageFields> = writer(
    project = { it.toSendChatImageFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.cloudModelConfigWriter(): UiStateWriter<CloudModelConfigFields> = writer(
    project = { it.toCloudModelConfigFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.diagnosticsWriter(): UiStateWriter<DiagnosticsFields> = writer(
    project = { it.toDiagnosticsFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.offlineTranslationWriter(): UiStateWriter<OfflineTranslationFields> = writer(
    project = { it.toOfflineTranslationFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.statusWriter(): UiStateWriter<StatusFields> = writer(
    project = { it.toStatusFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.visionAttachmentWriter(): UiStateWriter<VisionAttachmentFields> = writer(
    project = { it.toVisionAttachmentFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatImageApprovalWriter(): UiStateWriter<ChatImageApprovalFields> = writer(
    project = { it.toChatImageApprovalFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)

internal fun UiStateStore.chatStreamWriter(): UiStateWriter<ChatStreamFields> = writer(
    project = { it.toChatStreamFields() },
    merge = { current, fields -> fields.mergeInto(current) },
)
