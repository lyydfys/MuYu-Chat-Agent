package com.muyuchat.mca

import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.modelstore.ModelStoreRepository
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

/**
 * Extension properties for container-backed state and collaborators of [MainViewModel].
 */
internal val MainViewModel.modelStore: ModelStoreRepository get() = container.modelStore
internal val MainViewModel.runtimeProfileStore: ModelRuntimeProfileStore get() = container.runtimeProfileStore
internal val MainViewModel.chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore get() = container.chatImageToolAuthorizationStore
internal val MainViewModel.worldBookStore: WorldBookStore get() = container.worldBookStore
internal val MainViewModel.knowledgeBaseStore: KnowledgeBaseStore get() = container.knowledgeBaseStore
internal val MainViewModel.assistantStore: AssistantStore get() = container.assistantStore
internal val MainViewModel.cloudApiStore: CloudApiStore get() = container.cloudApiStore
internal val MainViewModel.cloudChatProvider: OpenAiCompatibleChatProvider get() = container.cloudChatProvider
internal val MainViewModel.cloudImageProvider: CloudImageProvider get() = container.cloudImageProvider
internal val MainViewModel.localImageModelStore: LocalImageModelStore get() = container.localImageModelStore
internal val MainViewModel.localImageLoraStore: LocalImageLoraStore get() = container.localImageLoraStore
internal val MainViewModel.imageUpscalerPreferences: android.content.SharedPreferences get() = container.imageUpscalerPreferences
internal val MainViewModel.manualContextCompressionRequests: ContextCompressionRequestStore get() = container.manualContextCompressionRequests
internal val MainViewModel.localImageWorkerClient: LocalImageWorkerClient get() = container.localImageWorkerClient
internal val MainViewModel.deviceProfileReader: DeviceProfileReader get() = container.deviceProfileReader
internal val MainViewModel.benchmarkHistoryLogger: BenchmarkHistoryLogger get() = container.benchmarkHistoryLogger
internal val MainViewModel.runtimeUserOverrideFields: MutableSet<String> get() = container.runtimeAdmission.runtimeUserOverrideFields
internal val MainViewModel.chatStreamAccumulator: ChatStreamAccumulator get() = container.chatStreamAccumulator

internal var MainViewModel.localConversationContextNeedsInvalidation: Boolean
    get() = container.runtimeAdmission.localConversationContextNeedsInvalidation
    set(value) { container.runtimeAdmission.localConversationContextNeedsInvalidation = value }

internal val MainViewModel.localConversationContextInvalidationSequence: AtomicLong
    get() = container.runtimeAdmission.localConversationContextInvalidationSequence

internal var MainViewModel.localConversationContextInvalidationJob: Job?
    get() = container.runtimeAdmission.localConversationContextInvalidationJob
    set(value) { container.runtimeAdmission.localConversationContextInvalidationJob = value }

internal val MainViewModel.persistentPrefixCacheOperationSequence: AtomicLong
    get() = cacheManagementCoordinator.persistentPrefixCacheOperationSequence

internal var MainViewModel.conversationMutationBarrier: Job?
    get() = container.runtimeAdmission.conversationMutationBarrier
    set(value) { container.runtimeAdmission.conversationMutationBarrier = value }

internal var MainViewModel.activeImageGenerationJobId: String?
    get() = container.runtimeAdmission.activeImageGenerationJobId
    set(value) { container.runtimeAdmission.activeImageGenerationJobId = value }

internal var MainViewModel.activeImageGenerationBackend: ImageBackend?
    get() = container.runtimeAdmission.activeImageGenerationBackend
    set(value) { container.runtimeAdmission.activeImageGenerationBackend = value }

internal var MainViewModel.activeImageGenerationModelId: String?
    get() = container.runtimeAdmission.activeImageGenerationModelId
    set(value) { container.runtimeAdmission.activeImageGenerationModelId = value }

internal var MainViewModel.activeLocalApiImageModelId: String?
    get() = container.runtimeAdmission.activeLocalApiImageModelId
    set(value) { container.runtimeAdmission.activeLocalApiImageModelId = value }

internal val MainViewModel.localImageGenerationCoordinator: LocalImageGenerationCoordinator
    get() = container.localImageGenerationCoordinator

internal val MainViewModel.localImageGenerationCoordinatorObservationLock: Any
    get() = container.localImageGenerationCoordinatorObservationLock

internal val MainViewModel.imageLibraryBackup: ImageLibraryBackup
    get() = container.imageLibraryBackup

internal val MainViewModel.imageAssetDirectory: File
    get() = container.imageAssetDirectory

internal val MainViewModel.chatImageCommitJournal: ChatImageCommitJournal
    get() = container.chatImageCommitJournal
