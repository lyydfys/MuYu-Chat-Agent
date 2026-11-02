package com.muyuchat.mca

import android.app.Application
import kotlinx.coroutines.CancellationException
import com.muyuchat.core.modelstore.ModelManifest

/**
 * The owner seam for the model-catalog readiness refresh.
 *
 * Fifteen members, and **not one of them is a lock, a job or a scope**: the four "is something active"
 * questions are answered as booleans, and the refresh gate is reached through three functions rather
 * than by handing out the gate object. The two verification helpers it uses
 * ([currentQnnImageVerificationByModelId], [currentQairtVerifiedLocalModelIds]) are already top-level,
 * so they need no port member at all -- they take the context directly.
 */
internal interface ManagedRuntimeReadinessPort {

    val context: Application

    fun uiState(): MainUiState

    fun updateUiState(transform: (ModelCatalogFields) -> ModelCatalogFields): ModelCatalogFields

    fun listLocalModels(): List<ModelManifest>

    fun loadLocalImageModels(): List<LocalImageModelRecord>

    fun loadPersistedSelectedImageModelId(): String?

    fun saveSelectedImageModelId(id: String?)

    fun beginReadinessRefresh(): Long

    fun invalidateReadinessRefresh()

    fun isReadinessRefreshCurrent(token: Long): Boolean

    fun imageLibraryBackupJobActive(): Boolean

    fun hasActiveImageGeneration(): Boolean

    fun hasActiveImageUpscale(): Boolean

    fun hasActiveLocalApiImageGeneration(): Boolean

    fun hasActiveImageGenerationRequest(): Boolean
}

internal fun publishChatCatalogSnapshot(
    port: ManagedRuntimeReadinessPort,): ManagedChatCatalogSnapshot {
        port.invalidateReadinessRefresh()
        val models = port.listLocalModels()
        val qairtVerifiedLocalModelIds = currentQairtVerifiedLocalModelIds(port.context, models)
        port.updateUiState { state ->
            state.copy(
                models = models,
                qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds,
                qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                    models = models,
                    verifiedLocalModelIds = qairtVerifiedLocalModelIds,
                    recommendations = state.recommendedRemoteModels
                )
            )
        }
        return ManagedChatCatalogSnapshot(
            models = models,
            qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds
        )
    }

internal fun refreshRuntimeReadiness(
    port: ManagedRuntimeReadinessPort,) {
        val refreshToken = port.beginReadinessRefresh()
        try {
            // Publish the inexpensive catalog projection before hashing QNN/QAIRT packages. This
            // keeps cold start responsive while the readiness evidence is still being collected.
            val localModels = port.listLocalModels()
            port.updateUiState { state ->
                if (port.isReadinessRefreshCurrent(refreshToken)) {
                    val previouslyVerifiedShaByModelId = state.models
                        .asSequence()
                        .filter { it.id in state.qairtVerifiedLocalModelIds }
                        .associate { it.id to it.sha256 }
                    val stillVerifiedIds = verifiedModelIdsWithUnchangedSha(
                        verifiedShaByModelId = previouslyVerifiedShaByModelId,
                        currentShaByModelId = localModels.associate { it.id to it.sha256 }
                    )
                    state.copy(
                        models = localModels,
                        qairtVerifiedLocalModelIds = stillVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = localModels,
                            verifiedLocalModelIds = stillVerifiedIds,
                            recommendations = state.recommendedRemoteModels
                        )
                    )
                } else {
                    state
                }
            }
            if (!port.isReadinessRefreshCurrent(refreshToken)) return

            val localImageModels = port.loadLocalImageModels()
            val persistedImageModelId = port.loadPersistedSelectedImageModelId()
            // QNN verification is advisory telemetry.  A staging/runtime probe failure must
            // never discard the structurally discovered image catalog from the product UI.
            // Keep the models visible and let the real load/graph path report the concrete
            // runtime failure when the user selects one.
            val qnnVerificationCurrentByModelId = runCatching {
                currentQnnImageVerificationByModelId(port.context, localImageModels)
            }.getOrElse { emptyMap() }
            val qairtVerifiedLocalModelIds = currentQairtVerifiedLocalModelIds(port.context, localModels)
            if (!port.isReadinessRefreshCurrent(refreshToken)) return

            val committed = port.updateUiState { latest ->
                if (!port.isReadinessRefreshCurrent(refreshToken)) {
                    latest
                } else {
                    // Selection is user-owned state. Merge against the latest StateFlow value at
                    // publication time and never write a captured background value back to prefs.
                    val preferredImageModelId = latest.selectedLocalImageModelId
                        ?.takeIf { selectedId -> localImageModels.any { it.id == selectedId } }
                        ?: persistedImageModelId
                    val selectedImageModelId = selectedReadyLocalImageModelId(
                        models = localImageModels,
                        qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                        preferredId = preferredImageModelId
                    )
                    val selectedImageBackend = if (
                        selectedImageModelId == null && latest.selectedImageBackend == ImageBackend.LOCAL
                    ) {
                        ImageBackend.CLOUD
                    } else {
                        latest.selectedImageBackend
                    }
                    val verifiedQairtShaByModelId = localModels
                        .asSequence()
                        .filter { it.id in qairtVerifiedLocalModelIds }
                        .associate { it.id to it.sha256 }
                    val currentQairtVerifiedIds = verifiedModelIdsWithUnchangedSha(
                        verifiedShaByModelId = verifiedQairtShaByModelId,
                        currentShaByModelId = latest.models.associate { it.id to it.sha256 }
                    )
                    latest.copy(
                        localImageModels = localImageModels,
                        qnnImageVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                        qairtVerifiedLocalModelIds = currentQairtVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = latest.models,
                            verifiedLocalModelIds = currentQairtVerifiedIds,
                            recommendations = latest.recommendedRemoteModels
                        ),
                        selectedLocalImageModelId = selectedImageModelId,
                        selectedImageBackend = selectedImageBackend
                    )
                }
            }
            // Persist only the successful CAS result; the reducer must remain replay-pure.
            // A newer selection or invalidated refresh owns persistence from that point onward.
            val selectedId = committed.selectedLocalImageModelId
            if (port.isReadinessRefreshCurrent(refreshToken) &&
                port.uiState().selectedLocalImageModelId == selectedId &&
                selectedId != persistedImageModelId
            ) {
                port.saveSelectedImageModelId(selectedId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            port.updateUiState { state ->
                if (shouldSurfaceManagedRuntimeRefreshFailure(
                        refreshCurrent = port.isReadinessRefreshCurrent(refreshToken),
                        busy = state.busy,
                        isGenerating = state.isGenerating,
                        imageLibraryBackupRunning = state.imageLibraryBackup.running,
                        imageLibraryBackupJobActive = port.imageLibraryBackupJobActive(),
                        generationImageGrantReleaseDeferred = state.deferGenerationImageGrantRelease,
                        activeImageGeneration = port.hasActiveImageGeneration(),
                        activeImageUpscale = port.hasActiveImageUpscale(),
                        activeLocalApiImageGeneration = port.hasActiveLocalApiImageGeneration(),
                        coordinatorActive = port.hasActiveImageGenerationRequest()
                    )) {
                    state.copy(
                        statusMessage = "模型目录后台刷新失败，已保留当前列表：" +
                            (error.message ?: "请稍后重试")
                    )
                } else {
                    state
                }
            }
        }
    }
