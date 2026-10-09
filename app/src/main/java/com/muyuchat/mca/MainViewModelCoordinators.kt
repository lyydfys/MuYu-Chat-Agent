package com.muyuchat.mca

import com.muyuchat.mca.composition.MainCoordinatorAssembly

/**
 * Extension properties for coordinators owned by [MainCoordinatorAssembly].
 * Keeps MainViewModel concise while preserving zero-overhead access for delegates and callers.
 */
internal val MainViewModel.modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator
    get() = coordinatorAssembly.modelImportAndDownloadCoordinator

internal val MainViewModel.localImageLibraryAndModelCoordinator: LocalImageLibraryAndModelCoordinator
    get() = coordinatorAssembly.localImageLibraryAndModelCoordinator

internal val MainViewModel.assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator
    get() = coordinatorAssembly.assistantImageContinuationCoordinator

internal val MainViewModel.offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator
    get() = coordinatorAssembly.offlinePromptTranslationAndChatImageCoordinator

internal val MainViewModel.agentTuningCoordinator: AgentTuningCoordinator
    get() = coordinatorAssembly.agentTuningCoordinator

internal val MainViewModel.chatModelCoordinator: ChatModelCoordinator
    get() = coordinatorAssembly.chatModelCoordinator

internal val MainViewModel.localApiControlPlaneCoordinator: LocalApiControlPlaneCoordinator
    get() = coordinatorAssembly.localApiControlPlaneCoordinator

internal val MainViewModel.modelScopeHubCoordinator: ModelScopeHubCoordinator
    get() = coordinatorAssembly.modelScopeHubCoordinator

internal val MainViewModel.assistantProfileCoordinator: AssistantProfileCoordinator
    get() = coordinatorAssembly.assistantProfileCoordinator

internal val MainViewModel.contextCompressionCoordinator: ContextCompressionCoordinator
    get() = coordinatorAssembly.contextCompressionCoordinator

internal val MainViewModel.managedRuntimeReadinessPort: ManagedRuntimeReadinessPort
    get() = coordinatorAssembly.managedRuntimeReadinessPort

internal val MainViewModel.contentImportPreviewCoordinator: ContentImportPreviewCoordinator
    get() = coordinatorAssembly.contentImportPreviewCoordinator

internal val MainViewModel.mainPostStartupCoordinator: MainPostStartupCoordinator
    get() = coordinatorAssembly.mainPostStartupCoordinator

internal val MainViewModel.chatSessionManagementCoordinator: ChatSessionManagementCoordinator
    get() = coordinatorAssembly.chatSessionManagementCoordinator

internal val MainViewModel.chatInputAndAttachmentCoordinator: ChatInputAndAttachmentCoordinator
    get() = coordinatorAssembly.chatInputAndAttachmentCoordinator
