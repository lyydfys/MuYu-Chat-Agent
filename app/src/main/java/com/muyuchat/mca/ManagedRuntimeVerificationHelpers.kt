package com.muyuchat.mca
import java.io.File

import android.content.Context
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.engine.QairtExecutionVerificationStore
import com.muyuchat.core.engine.qairtRuntimeIdentityFor
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentTuningJobState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale


/**
 * Verification and readiness helpers that used to live on `MainViewModel`.
 *
 * Every function here is either **pure** or needs only a `Context`; none reads `_uiState`, launches a
 * coroutine, or touches a lock, a job or the engine. That is exactly why they could leave the
 * ViewModel with **no port and no forwarder**: they keep their original names, the call sites inside
 * `MainViewModel` resolve to these top-level functions unchanged, and only the two that read
 * `getApplication()` needed a `context` parameter added.
 *
 * The QAIRT/QNN helpers read *advisory evidence*: whether a local model's verification stamp still
 * matches its current SHA, and whether a QNN bundle's stamp is current. They never gate a load --
 * structural readiness and the real native load decide that. `selectedReadyLocalImageModelId` keeps
 * the same rule it always had: the verification map is accepted for bookkeeping and is deliberately
 * **not** used as automatic-selection admission.
 */

internal fun verifiedQairtRecommendationIds(
        models: List<ModelManifest>,
        verifiedLocalModelIds: Set<String>,
        recommendations: List<ModelScopeRecommendedModel>
    ): Set<String> {
        val verifiedRepoIds = models.asSequence()
            .filter { it.id in verifiedLocalModelIds }
            .mapNotNull(ModelManifest::repoId)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return recommendations.asSequence()
            .filter { it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT }
            .filter { it.repoId.trim() in verifiedRepoIds }
            .map(ModelScopeRecommendedModel::id)
            .toSet()
    }

internal fun selectedReadyLocalImageModelId(
        models: List<LocalImageModelRecord>,
        qnnVerificationCurrentByModelId: Map<String, Boolean>,
        preferredId: String?
    ): String? {
        // Keep the verification map in the call shape for UI refresh bookkeeping, but never use
        // verification/certification as automatic-selection admission.
        @Suppress("UNUSED_VARIABLE")
        val advisoryVerification = qnnVerificationCurrentByModelId
        val preferred = models.firstOrNull { it.id == preferredId }
        // Debug smoke records share the app UID and may survive an interrupted run. They are
        // valid test fixtures, but must not silently become the product's default when a real
        // imported/downloaded engine is available. Manual selection remains unaffected.
        val preferredForProduct = preferredId?.takeUnless {
            preferred?.source.equals("local-test", ignoreCase = true) &&
                models.any {
                    !it.source.equals("local-test", ignoreCase = true) &&
                        it.localImageStructuralReadinessMessage() == null
                }
        }
        return selectStructurallyReadyLocalImageModelId(models, preferredForProduct)
    }

internal fun currentQnnImageVerificationByModelId(
    context: Context,
        models: List<LocalImageModelRecord>
    ): Map<String, Boolean> {
        val context = context
        return models
            .asSequence()
            .filter { it.runtime == LocalImageRuntime.QNN_HTP }
            .associate { model ->
                model.id to (model.qnnBundleRootForVerification()
                    ?.let { root -> model.hasCurrentQnnVerificationStamp(context, root) }
                    ?: false)
            }
    }

internal fun currentQairtVerifiedLocalModelIds(
    context: Context,
        models: List<ModelManifest>
    ): Set<String> {
        val context = context
        val verifiedIdentities = QairtExecutionVerificationStore
            .forContext(context)
            .verifiedIdentities()
        return models.asSequence()
            .filter { it.runtime == ChatModelRuntime.GENIEX_QAIRT }
            .filter { model ->
                isExactQairtExecutionVerified(
                    identity = qairtRuntimeIdentityFor(context, model.sha256),
                    verifiedIdentities = verifiedIdentities
                )
            }
            .map(ModelManifest::id)
            .toSet()
    }

internal fun agentJobState(job: TuningJobEntity): AgentTuningJobState = when {
        job.cancellationRequested -> AgentTuningJobState.CANCELING
        else -> when (job.state.uppercase(Locale.ROOT)) {
            PersistedTuningJobState.QUEUED.name -> AgentTuningJobState.QUEUED
            PersistedTuningJobState.RUNNING.name -> AgentTuningJobState.RUNNING
            PersistedTuningJobState.PAUSED.name -> AgentTuningJobState.PAUSED
            PersistedTuningJobState.CANCELING.name -> AgentTuningJobState.CANCELING
            PersistedTuningJobState.VALIDATING.name -> AgentTuningJobState.VALIDATING
            PersistedTuningJobState.SUCCEEDED.name -> AgentTuningJobState.SUCCEEDED
            PersistedTuningJobState.RECOVERING.name -> AgentTuningJobState.RECOVERING
            else -> AgentTuningJobState.FAILED
        }
    }

internal fun detectLanIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    val host = address.hostAddress.orEmpty()
                    !address.isLoopbackAddress && !host.startsWith("127.")
                }
                ?.hostAddress
        }.getOrNull()
    }

internal fun agentVerification(value: String): AgentProfileVerification = when {
        value.equals(PersistedProfileVerificationLevel.DEVICE_VERIFIED.name, ignoreCase = true) ->
            AgentProfileVerification.DEVICE_VERIFIED
        value.equals(PersistedProfileVerificationLevel.COMPATIBLE.name, ignoreCase = true) ->
            AgentProfileVerification.COMPATIBLE
        value.equals(PersistedProfileVerificationLevel.SAFE.name, ignoreCase = true) ->
            AgentProfileVerification.SAFE
        else -> AgentProfileVerification.UNKNOWN
    }

internal fun LocalImageModelRecord.qnnBundleRootForVerification(): File? =
        bundleRoot
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: File(path).parentFile?.takeIf { it.isDirectory }

    /** Must be called from an IO coroutine. */
