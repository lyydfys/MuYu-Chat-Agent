package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.EngineLifecycleLease
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * C2/C0-a：生图准入与执行的唯一 owner。enqueueImageGeneration 的 673 行体自 MainViewModel 移入
 * （体逐字；VM 私有面经本 host 注入：状态/写入/scope/application/运行态字段/锁/资产与 worker 协作）。
 * 三处端口实现与两个 VM 直连调用点收敛到同一 ImageGenerationOwner 实例。
 *
 * ⚠ 过渡 adapter（用户 2026-10-06 插队裁定）：本 host 的 VM 侧实现为匿名 `object : ImageGenerationHost`
 * 反向捕获 `this@MainViewModel`，属方案 §五 禁止清单的过渡形态。退出条件 = ImageGenerationOwner 改为
 * `McaAppContainer` 工厂创建、构造注入真实依赖（UiStateStore/scope/engine/资产与 worker 协作者），
 * VM 仅从容器取出 owner 并保留 intent 委托；随之删除本 host 接口与 VM 内匿名装配。
 * 期限 = C2 序列内、C3 开工前（蓝图样板为 C2-b TuningOwner，落地后即回改本片）。
 */
internal interface ImageGenerationHost {
    val scope: CoroutineScope

    val application: Application

    fun currentState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun showStatus(message: String?)

    val imageGenerationJob: Job?

    fun setImageGenerationJob(job: Job?)

    var activeImageGenerationJobId: String?

    var activeImageGenerationBackend: ImageBackend?

    var activeImageGenerationModelId: String?

    val localImageTextualInversionLifecycleLock: Any

    val localImageLoraLifecycleLock: Any

    val localImageLoraStore: LocalImageLoraStore

    val localImageWorkerClient: LocalImageWorkerClient

    val imageAssetDirectory: File

    val imageLibraryCommitPort: ImageLibraryCommitPort

    val chatImageRequestPort: ChatImageRequestPort

    val chatImageCommitJournal: ChatImageCommitJournal

    suspend fun awaitImageLibraryStartupReconciliation()

    fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease?

    fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean

    fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora>

    fun resolveCurrentLocalImageTextualInversionIds(
        ids: List<String>,
        records: List<TextualInversionArtifact>
    ): List<String>

    suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution

    suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease

    fun startLocalImageGenerationWatchdog(jobId: String)

    fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview?

    fun cleanupLocalImagePreviews(jobId: String)

    suspend fun createCloudGeneratedImageAssets(
        request: CloudImageRequest,
        config: CloudApiConfig,
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata
    ): List<ImageAssetRecord>

    suspend fun createLocalGeneratedImageAsset(
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata,
        batchLineage: ImageGenerationBatchLineage? = null,
        outputLineages: List<ImageGenerationBatchLineage>,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): List<ImageAssetRecord>

    fun completeAssistantImageToolTurnForJob(jobId: String)
}
