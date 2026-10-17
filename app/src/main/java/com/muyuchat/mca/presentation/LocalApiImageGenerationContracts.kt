package com.muyuchat.mca.presentation

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
 *  host port. Moved verbatim out of LocalApiImageGenerationOperations.kt.
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

/**
 * `generateLocalApiImage` 在 `MainViewModel` 里触碰的全部宿主能力。
 *
 * 端口面是照着函数体里出现的 **VM 成员访问**逐个列出来的，没有「捕获 VM」的兜底。按方向分组：
 *
 * ### 读快照（`_uiState.value`，6 处）
 * [uiState] 暴露只读的 `StateFlow`；`uiState.value` 与原 `_uiState.value` 读取时机完全一致。
 *
 * ### 发布变更（`_uiState.update { }`，3 处）
 * [updateUiState] 必须由宿主机委托给真正的 `MutableStateFlow.update`，以保留「读-改-写」的 CAS
 * 原子性。3 处调用中有 2 处位于 `synchronized` 块内，原子性与加锁范围不变。
 *
 * ### 独立快照查询
 * [selectedLocalImageModel] 对应原 `private fun MainUiState.selectedLocalImageModel()`：那是
 * `MainViewModel` 的私有扩展，跨包不可见，因此单独成为端口能力，而不是让函数体自己筛
 * `localImageModels`（那会改变默认模型的选择规则）。
 *
 * ### 锁与跨请求状态
 * [localImageLoraLifecycleLock]、[localImageTextualInversionLifecycleLock] 以对象身份暴露，
 * 因此 `synchronized(...)` 的**锁粒度与原有互斥关系不变**；[activeLocalApiImageModelId] 是
 * 跨请求可见的 `@Volatile` 字段，只是换了一处存放。
 *
 * ### 协作方
 * [localImageWorkerClient]、[localImageLoraStore] 是窄化的协作方面，不是原对象。
 *
 * ### 租约与取消归属
 * [tryAcquireObservedImageGenerationLease] / [releaseObservedImageGenerationLease] /
 * [registerLocalApiImageGenerationOwnership] / [unregisterLocalApiImageGenerationOwnership] /
 * [cancelOwnedLocalApiImageWorker] 保持原有的成对顺序与返回值语义（`check(...)` 依旧校验返回值）。
 *
 * ### 需要 VM 上下文的纯逻辑
 * [resolveCurrentLocalImageLoras]（含读取默认记录的 1 参重载）、
 * [resolveCurrentLocalImageTextualInversionIds]（1 参重载，内部读快照）、
 * [prepareLocalImagePromptExecution]、[executionWithQnnVerificationEvidence] 原本是 VM 私有成员，
 * 其中两个的默认实参本身就要读 `_uiState.value`，所以在端口上保留 1 参/2 参两个重载，语义不变。
 *
 * ### 协议响应编码缝合点
 * [decodeImageBounds] / [encodeBase64NoWrap] 是 `android.graphics.BitmapFactory` 与
 * `android.util.Base64` 的替身位。**生产实现必须就是原来那两行**：
 * `BitmapFactory.Options().apply { inJustDecodeBounds = true }` +
 * `BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)`，以及
 * `Base64.encodeToString(bytes, Base64.NO_WRAP)`。这样端上产出的 JSON 字节不变；而普通 JVM 单测
 * 里 Android stub 会抛 `RuntimeException("Stub!")`，没有这个缝合点就无法断言成功响应的 JSON。
 */
internal interface LocalApiImageGenerationPort {
    /** 读方向：原 `_uiState.value`。 */
    val uiState: StateFlow<MainUiState>

    /** 写方向：原 `_uiState.update { }`。宿主机必须委托给 `MutableStateFlow.update`。 */
    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** 原 `private fun MainUiState.selectedLocalImageModel()`。 */
    fun selectedLocalImageModel(): LocalImageModelRecord?

    val localImageLoraLifecycleLock: Any
    val localImageTextualInversionLifecycleLock: Any
    var activeLocalApiImageModelId: String?

    val localImageWorkerClient: LocalApiImageWorkerGateway
    val localImageLoraStore: LocalApiImageLoraGateway

    fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease?
    fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean

    fun registerLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership)
    fun unregisterLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership): Boolean
    fun cancelOwnedLocalApiImageWorker(ownership: LocalApiImageGenerationOwnership): Boolean

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
