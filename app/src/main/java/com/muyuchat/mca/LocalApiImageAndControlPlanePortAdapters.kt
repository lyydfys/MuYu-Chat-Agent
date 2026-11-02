package com.muyuchat.mca

import android.graphics.BitmapFactory
import android.util.Base64
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.presentation.LocalApiImageWorkerGateway
import com.muyuchat.mca.presentation.LocalApiImageLoraGateway
import com.muyuchat.mca.presentation.LocalApiImageBounds
import com.muyuchat.core.engine.EngineLifecycleLease
import kotlinx.coroutines.flow.StateFlow
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.LocalApiTuningJobCreateRequest
import com.muyuchat.api.local.LocalApiIdempotencyContext
import com.muyuchat.api.local.LocalApiControlResult

internal class LocalApiImageWorkerGatewayAdapter(
    private val clientProvider: () -> LocalImageWorkerClient,
) : LocalApiImageWorkerGateway {
    override suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        inputDraft: LocalImageInputDraft,
        requestId: String,
        batchLineage: ImageGenerationBatchLineage?
    ): LocalImageResult = clientProvider().generate(
        model = model,
        prompt = prompt,
        options = options,
        inputDraft = inputDraft,
        requestId = requestId,
        batchLineage = batchLineage
    )

    override suspend fun awaitOwnedWorkerRelease() {
        clientProvider().awaitOwnedWorkerRelease()
    }
}

internal class LocalApiImageLoraGatewayAdapter(
    private val storeProvider: () -> LocalImageLoraStore,
) : LocalApiImageLoraGateway {
    override fun load(): List<LocalImageLoraRecord> =
        storeProvider().load()
}

internal class LocalApiImageGenerationPortAdapter(
    private val store: UiStateWriter<LocalApiImageFields>,
    private val localImageLoraLifecycleLockRef: Any,
    private val localImageTextualInversionLifecycleLockRef: Any,
    private val getActiveLocalApiImageModelIdRef: () -> String?,
    private val setActiveLocalApiImageModelIdRef: (String?) -> Unit,
    override val localImageWorkerClient: LocalApiImageWorkerGateway,
    override val localImageLoraStore: LocalApiImageLoraGateway,
    private val tryAcquireObservedImageGenerationLeaseRef: (String) -> LocalImageGenerationCoordinator.Lease?,
    private val releaseObservedImageGenerationLeaseRef: (LocalImageGenerationCoordinator.Lease) -> Boolean,
    private val registerLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Unit,
    private val unregisterLocalApiImageGenerationOwnershipRef: (LocalApiImageGenerationOwnership) -> Boolean,
    private val cancelOwnedLocalApiImageWorkerRef: (LocalApiImageGenerationOwnership) -> Boolean,
    private val resolveCurrentLocalImageLorasRef1: (List<Pair<String, Double>>) -> List<LocalImagePreparedLora>,
    private val resolveCurrentLocalImageLorasRef2: (List<Pair<String, Double>>, List<LocalImageLoraRecord>) -> List<LocalImagePreparedLora>,
    private val resolveCurrentLocalImageTextualInversionIdsRef: (List<String>) -> List<String>,
    private val prepareLocalImagePromptExecutionRef: suspend (LocalImageModelRecord, String, LocalImageGenerationOptions, LocalImagePromptExecution?) -> LocalImagePromptExecution,
    private val prepareLocalImageRuntimeMemoryRef: suspend () -> EngineLifecycleLease?,
    private val executionWithQnnVerificationEvidenceRef: (LocalImageModelRecord, LocalImageResult, LocalImageGenerationOptions, String, String, QnnImageEvidenceEntryPoint) -> String,
) : LocalApiImageGenerationPort {

    override val uiState: StateFlow<MainUiState> get() = store.state

    override fun updateUiState(transform: (LocalApiImageFields) -> LocalApiImageFields) {
        store.update(transform)
    }

    override fun selectedLocalImageModel(): LocalImageModelRecord? =
        store.state.value.selectedLocalImageModel()

    override val localImageLoraLifecycleLock: Any
        get() = localImageLoraLifecycleLockRef

    override val localImageTextualInversionLifecycleLock: Any
        get() = localImageTextualInversionLifecycleLockRef

    override var activeLocalApiImageModelId: String?
        get() = getActiveLocalApiImageModelIdRef()
        set(value) { setActiveLocalApiImageModelIdRef(value) }

    override fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseRef(requestId)

    override fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean =
        releaseObservedImageGenerationLeaseRef(lease)

    override fun registerLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership) {
        registerLocalApiImageGenerationOwnershipRef(ownership)
    }

    override fun unregisterLocalApiImageGenerationOwnership(ownership: LocalApiImageGenerationOwnership): Boolean =
        unregisterLocalApiImageGenerationOwnershipRef(ownership)

    override fun cancelOwnedLocalApiImageWorker(ownership: LocalApiImageGenerationOwnership): Boolean =
        cancelOwnedLocalApiImageWorkerRef(ownership)

    override fun resolveCurrentLocalImageLoras(selections: List<Pair<String, Double>>): List<LocalImagePreparedLora> =
        resolveCurrentLocalImageLorasRef1(selections)

    override fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord>
    ): List<LocalImagePreparedLora> =
        resolveCurrentLocalImageLorasRef2(selections, records)

    override fun resolveCurrentLocalImageTextualInversionIds(ids: List<String>): List<String> =
        resolveCurrentLocalImageTextualInversionIdsRef(ids)

    override suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution =
        prepareLocalImagePromptExecutionRef(model, prompt, options, captured)

    override suspend fun prepareLocalImageRuntimeMemory(): EngineLifecycleLease? =
        prepareLocalImageRuntimeMemoryRef()

    override fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String =
        executionWithQnnVerificationEvidenceRef(model, result, options, requestId, attemptId, entryPoint)

    override fun decodeImageBounds(bytes: ByteArray): LocalApiImageBounds {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return LocalApiImageBounds(bounds.outWidth, bounds.outHeight)
    }

    override fun encodeBase64NoWrap(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)
}

internal class LocalApiControlPlaneAdapter(
    private val busyStateProvider: () -> LocalApiBusyState,
    private val preflightProvider: (LocalApiPreflightRequest) -> LocalApiPreflightResult,
    private val profileJsonProvider: () -> String,
    private val tuningJsonProvider: () -> String,
    private val tuningJobProvider: (String?) -> LocalApiControlResult,
    private val createTuningJobDelegate: (LocalApiTuningJobCreateRequest, String) -> LocalApiControlResult,
    private val pauseTuningJobDelegate: (String, String) -> LocalApiControlResult,
    private val resumeTuningJobDelegate: (String, String) -> LocalApiControlResult,
    private val cancelTuningJobDelegate: (String, String) -> LocalApiControlResult,
    private val applyTuningJobDelegate: (String, String) -> LocalApiControlResult,
    private val rollbackTuningDelegate: (String) -> LocalApiControlResult,
    private val runDurableLocalApiControl: (LocalApiIdempotencyContext, () -> LocalApiControlResult) -> LocalApiControlResult,
) : LocalApiControlPlane {
    override fun busyState(): LocalApiBusyState = busyStateProvider()

    override fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult =
        preflightProvider(request)

    override fun profileJson(): String = profileJsonProvider()

    override fun tuningJson(): String = tuningJsonProvider()

    override fun tuningJob(jobId: String?): LocalApiControlResult =
        tuningJobProvider(jobId)

    override fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotencyKey: String
    ): LocalApiControlResult = createTuningJobDelegate(request, idempotencyKey)

    override fun createTuningJob(
        request: LocalApiTuningJobCreateRequest,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        createTuningJob(request, idempotency.key)
    }

    override fun pauseTuningJob(
        jobId: String,
        idempotencyKey: String
    ): LocalApiControlResult = pauseTuningJobDelegate(jobId, idempotencyKey)

    override fun pauseTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        pauseTuningJob(jobId, idempotency.key)
    }

    override fun resumeTuningJob(
        jobId: String,
        idempotencyKey: String
    ): LocalApiControlResult = resumeTuningJobDelegate(jobId, idempotencyKey)

    override fun resumeTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        resumeTuningJob(jobId, idempotency.key)
    }

    override fun cancelTuningJob(
        jobId: String,
        idempotencyKey: String
    ): LocalApiControlResult = cancelTuningJobDelegate(jobId, idempotencyKey)

    override fun cancelTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        cancelTuningJob(jobId, idempotency.key)
    }

    override fun applyTuningJob(
        jobId: String,
        idempotencyKey: String
    ): LocalApiControlResult = applyTuningJobDelegate(jobId, idempotencyKey)

    override fun applyTuningJob(
        jobId: String,
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        applyTuningJob(jobId, idempotency.key)
    }

    override fun rollbackTuning(
        idempotencyKey: String
    ): LocalApiControlResult = rollbackTuningDelegate(idempotencyKey)

    override fun rollbackTuning(
        idempotency: LocalApiIdempotencyContext
    ): LocalApiControlResult = runDurableLocalApiControl(idempotency) {
        rollbackTuning(idempotency.key)
    }
}







