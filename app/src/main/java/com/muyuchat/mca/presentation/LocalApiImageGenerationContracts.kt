package com.muyuchat.mca.presentation

import com.muyuchat.mca.*
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.mca.ImageGenerationBatchLineage
import com.muyuchat.mca.LocalApiImageGenerationOwnership
import com.muyuchat.mca.LocalImageGenerationCoordinator
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.LocalImageInputDraft
import com.muyuchat.mca.LocalImageLoraRecord
import com.muyuchat.mca.LocalImageModelRecord
import com.muyuchat.mca.LocalImagePreparedLora
import com.muyuchat.mca.LocalImagePromptExecution
import com.muyuchat.mca.LocalImageResult
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.QnnImageEvidenceEntryPoint
import kotlinx.coroutines.flow.StateFlow

/** Local API image generation contracts: worker/LoRA gateways, bounds and the
 *  owner-backed execution port.
 */

/**
 * 该操作唯一需要的 worker 交互面。
 *
 * 刻意不暴露 `LocalImageWorkerClient` 本身：它需要 `Application` 才能构造，暴露它会让这个函数
 * 在普通 JVM 单测里无法替身化。`generate` 的参数与原调用点使用的具名实参一一对应。
 */
internal interface LocalApiImageWorkerGateway {
    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        inputDraft: LocalImageInputDraft,
        requestId: String,
        batchLineage: ImageGenerationBatchLineage?
    ): LocalImageResult

    suspend fun awaitOwnedWorkerRelease()
}

/** 该操作唯一需要的 LoRA 存储读取面（原 `localImageLoraStore.load()`）。 */
internal interface LocalApiImageLoraGateway {
    fun load(): List<LocalImageLoraRecord>
}

/**
 * [LocalApiImageGenerationPort.decodeImageBounds] 的结果。
 *
 * 字段名与 `BitmapFactory.Options` 保持一致（`outWidth` / `outHeight`），这样函数体里
 * `bounds.outWidth` / `bounds.outHeight` 的用法与断言 `bounds.outWidth > 0` 都是逐字保留的。
 */
internal data class LocalApiImageBounds(val outWidth: Int, val outHeight: Int)

/** Owner 在 Local API 请求进入执行区后，为该请求绑定当前模型身份。 */
internal interface LocalApiImageOperationBinding {
    fun bindModel(modelId: String)

    fun isCancellationRequested(): Boolean
}

/**
 * Local API 协议操作需要读取的宿主能力。
 *
 * 租约、取消所有权、worker release barrier 与终态清理都不在这里：这些能力由唯一的
 * [com.muyuchat.mca.ImageGenerationOwner] 通过 [OwnerBackedLocalApiImageGenerationPort]
 * 统一持有。这里只保留协议解析、批量执行与响应编码真正需要的只读/纯协作面。
 */
internal interface LocalApiImageGenerationBasePort {
    /** 读方向：原 `_uiState.value`。 */
    val uiState: StateFlow<MainUiState>

    /** 写方向：原 `_uiState.update { }`。宿主机必须委托给 `MutableStateFlow.update`。 */
    fun updateUiState(transform: (LocalApiImageFields) -> LocalApiImageFields)

    /** 原 `private fun MainUiState.selectedLocalImageModel()`。 */
    fun selectedLocalImageModel(): LocalImageModelRecord?

    val localImageLoraLifecycleLock: Any
    val localImageTextualInversionLifecycleLock: Any

    val localImageWorkerClient: LocalApiImageWorkerGateway
    val localImageLoraStore: LocalApiImageLoraGateway

    fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>
    ): List<LocalImagePreparedLora>

    fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora>

    fun resolveCurrentLocalImageTextualInversionIds(ids: List<String>): List<String>

    suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution

    /**
     * 原 `private suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease`。
     *
     * 返回类型可空是**刻意的**：`EngineLifecycleLease` 的构造函数是 `core:engine` 的 `internal`，
     * app 的单测无法构造它。生产实现返回非空值，因此 `chatRuntimeLease?.release()` 的行为与
     * 原来完全一致；替身返回 `null` 时该释放变成 no-op。
     */
    suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease?

    fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String

    /** 协议响应编码缝合点：等价于 `BitmapFactory.Options(inJustDecodeBounds = true)` + `decodeByteArray`。 */
    fun decodeImageBounds(bytes: ByteArray): LocalApiImageBounds

    /** 协议响应编码缝合点：等价于 `Base64.encodeToString(bytes, Base64.NO_WRAP)`。 */
    fun encodeBase64NoWrap(bytes: ByteArray): String
}

/**
 * Local API 生图进入唯一 owner 的入口。
 *
 * [ImageGenerationOwner] 在执行 block 前完成活动 UI 任务冲突检查、共享租约、Local API
 * cancellation ownership 注册与模型身份绑定；无论正常、异常还是取消，都在 worker release
 * barrier 之后按身份释放所有权和租约。
 */
internal interface OwnerBackedLocalApiImageGenerationPort : LocalApiImageGenerationBasePort {
    suspend fun <T> runLocalApiImageOperation(
        requestId: String,
        block: suspend (LocalApiImageOperationBinding) -> T
    ): T
}

/**
 * 过渡兼容接口：既有具名 adapter 仍按旧构造面装配，但生产 Local API 执行路径只使用
 * [OwnerBackedLocalApiImageGenerationPort]，不会再调用这里的租约或取消回调。
 */
internal interface LegacyLocalApiImageGenerationPort : LocalApiImageGenerationBasePort {
    var activeLocalApiImageModelId: String?

    fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease?
    fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean

    fun registerLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership)
    fun unregisterLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership): Boolean
    fun cancelOwnedLocalApiImageWorker(ownership: LocalApiImageGenerationOwnership): Boolean
}

/** 兼容旧 adapter 的源码名称；新的操作函数不接受这个接口。 */
internal typealias LocalApiImageGenerationPort = LegacyLocalApiImageGenerationPort
