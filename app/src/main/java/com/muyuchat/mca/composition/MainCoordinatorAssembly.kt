package com.muyuchat.mca.composition

import com.muyuchat.mca.*

/**
 * Already constructed coordinator results. This object owns no VM callback or construction logic.
 */
internal class MainCoordinatorAssembly(
    val modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator,
    val localImageLibraryAndModelCoordinator: LocalImageLibraryAndModelCoordinator,
    val assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
    val offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    val agentTuningCoordinator: AgentTuningCoordinator,
    val chatModelCoordinator: ChatModelCoordinator,
    val localApiControlPlaneCoordinator: LocalApiControlPlaneCoordinator,
    val modelScopeHubCoordinator: ModelScopeHubCoordinator,
    val assistantProfileCoordinator: AssistantProfileCoordinator,
    val contextCompressionCoordinator: ContextCompressionCoordinator,
    val managedRuntimeReadinessPort: ManagedRuntimeReadinessPort,
    val contentImportPreviewCoordinator: ContentImportPreviewCoordinator,
    val mainPostStartupCoordinator: MainPostStartupCoordinator,
    val chatSessionManagementCoordinator: ChatSessionManagementCoordinator,
    val chatInputAndAttachmentCoordinator: ChatInputAndAttachmentCoordinator,
    val chatExecutionPort: ChatGenerationExecutionPort,
)
