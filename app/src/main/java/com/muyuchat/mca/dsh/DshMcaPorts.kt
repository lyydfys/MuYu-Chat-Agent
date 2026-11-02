package com.muyuchat.mca.dsh

import android.util.Log
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.CloudApiConfig
import com.muyuchat.mca.CloudApiStore
import com.muyuchat.mca.CloudModelRecord
import com.muyuchat.mca.OpenAiCompatibleChatProvider
import com.muyuchat.mca.facade.ChatExecutionFacade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Production adapters for the DSH backend ports.
 *
 * These are thin, typed bindings to objects the app composition already owns:
 * the single `McaInferenceService`, the single `ChatExecutionFacade`, the existing `CloudApiStore`
 * and the existing `OpenAiCompatibleChatProvider`. No engine, store, HTTP client or credential
 * store is created here; wiring them is the composition root's responsibility.
 */

internal class EngineDshLocalModelAccess(
    private val engine: McaInferenceService,
    /**
     * Optional authoritative display-name source (for example the composition-owned model
     * catalog). When it returns null - or is not wired at all - the adapter falls back to the
     * read-only [LocalApiLoadedModelNameSource], then to a readable path/native name, then to
     * [DshLocalModelDisplayName.FALLBACK]. The resolved label is cached per loaded-model identity,
     * so a hash-named MNN bundle never exposes the manifest UUID in the picker.
     */
    private val nameSource: DshLocalModelNameSource? = null
) : DshLocalModelAccess {
    private val nameLock = Any()
    private var cachedNameIdentity: String? = null
    private var cachedDisplayName: String? = null

    override fun activeModel(): DshLocalModel? {
        // `committedProfile` survives an explicit unload in the engine, so availability must be
        // gated by the live loaded flag and the published active signature as well.
        val stats = engine.stats.value
        val loaded = stats.loaded
        val profile = engine.activeExecutionProfile()
        val signatures = runCatching { engine.parameterSignatureSnapshot() }.getOrNull()
        val activeSignature = signatures?.active
        if (!loaded || profile == null || activeSignature == null) {
            Log.i(
                "McaDshLocalGate",
                "activeModel=null loaded=$loaded profile=${profile?.profileId ?: "-"} " +
                    "signatureActive=${activeSignature != null}"
            )
            return null
        }
        val values = profile.resolvedLoadBoundValues
            .plus(profile.hotExecutionValues)
            .plus(profile.modelBehaviorValues)
            .toJsonObject()
        val contextWindow = (profile.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
            ?: (profile.resolvedLoadBoundValues.value("max_num_tokens") as? Number)?.toInt()
        val outputBudget = contextWindow
            ?.let { (it / 4).coerceIn(256, 4096) }
            ?: 4096
        val modelPath = engine.stats.value.modelPath?.trim()?.takeIf { it.isNotEmpty() }
        val envelopeStyle = DshGgufToolStyle.of(modelPath)
        Log.i(
            "McaDshLocalGate",
            "activeModel=${profile.modelId} loaded=true profile=${profile.profileId} " +
                "revision=${profile.revision} signature=${activeSignature.digest.take(12)} " +
                "nCtx=${contextWindow ?: "-"} maxOutput=$outputBudget " +
                "style=$envelopeStyle model=${modelPath?.substringAfterLast('/') ?: "-"} " +
                "workerLoaded=${stats.loaded} workerRssKb=${stats.processRssKb}"
        )
        return DshLocalModel(
            modelId = profile.modelId,
            displayName = displayNameFor(profile.modelId, modelPath),
            contextWindow = contextWindow?.takeIf { it > 0 },
            maxOutputTokens = outputBudget,
            runtimeValuesJson = values.toString(),
            toolEnvelopeStyle = envelopeStyle
        )
    }

    /**
     * Resolves the user-facing label and caches it per loaded-model identity.
     *
     * The identity is the manifest UUID, so it must never be shown. The manifest display name (if a
     * source is wired) is authoritative; otherwise a readable path/native name is used, and the
     * final fallback is a generic "MCA local model" rather than an opaque id.
     */
    private fun displayNameFor(modelId: String, modelPath: String?): String {
        val identity = modelId + '\u0000' + modelPath.orEmpty()
        synchronized(nameLock) {
            if (identity == cachedNameIdentity) cachedDisplayName?.let { return it }
        }
        val manifestName = nameSource?.let { source ->
            runCatching { source.displayName(modelId, modelPath) }.getOrNull()
        } ?: LocalApiLoadedModelNameSource.displayName(modelId, modelPath)
        val pathName = DshLocalModelDisplayName.fromPath(modelPath)
        val nativeName = if (
            DshLocalModelDisplayName.readable(manifestName) == null && pathName == null
        ) {
            runCatching {
                JSONObject(engine.nativeStatsJson()).optString("modelName").takeIf { it.isNotBlank() }
            }.getOrNull()
        } else {
            null
        }
        val resolved = DshLocalModelDisplayName.resolve(manifestName, modelPath, nativeName, modelId)
        if (resolved != DshLocalModelDisplayName.FALLBACK) {
            synchronized(nameLock) {
                cachedNameIdentity = identity
                cachedDisplayName = resolved
            }
        }
        return resolved
    }
}

/**
 * Read-only compatibility source for the human local-model name.
 *
 * The app composition already publishes the loaded-model projection through
 * [LocalApiRuntime.loadedModelJsonProvider] (the same document the public Local API returns:
 * `id`, `name`, `displayName` of the loaded manifest). Reading that projection here keeps the DSH
 * picker label correct for hash-named MNN bundle directories - the engine's runtime identity uses
 * the manifest UUID, and the bundle directory is a SHA-256 hash, so neither carries a name.
 *
 * Safety: this only reads; it never loads, unloads, switches or stops a model, and it is the exact
 * read path the Local API HTTP handlers already call from background threads. The projection is
 * matched by model id, so a stale document after a model switch can never label another model, and
 * any failure is ignored by the caller (a derived label is used instead). A composition-wired
 * [DshLocalModelNameSource] takes priority over this fallback.
 */
internal object LocalApiLoadedModelNameSource : DshLocalModelNameSource {
    override fun displayName(modelId: String, modelPath: String?): String? = runCatching {
        DshLocalModelDisplayName.loadedModelNameFromJson(
            LocalApiRuntime.loadedModelJsonProvider(),
            modelId
        )
    }.getOrNull()
}

internal class FacadeDshLocalChatPort(
    private val facade: ChatExecutionFacade
) : DshLocalChatPort {
    override fun stream(request: ChatRequest, requestId: String): Flow<GenerateEvent> =
        facade.streamChat(
            request,
            LocalChatExecutionContext(
                requestId = requestId,
                // DSH supplies its own time/system context; adding the MCA clock context would
                // silently change the DSH prompt on every turn.
                includeDeviceClockContext = false
            )
        )

    /**
     * Two steps on purpose, both exact-request:
     *
     * 1. [ChatExecutionFacade.cancelChat] marks that attempt on the shared supervisor (the previous
     *    behavior). On its own it cannot interrupt a native prefill: nothing polls the supervisor
     *    from inside a blocked `beginCompletion` binder call (device evidence 2026-10-26: binder
     *    code 4 blocked 245 232 ms after a cancel).
     * 2. [ChatExecutionFacade.stopGenerationIfActive] is the only path that dispatches
     *    `runner.requestStop()` out-of-band. It re-checks the engine-owned
     *    `GenerationStopToken.requestId` before signaling, so it can only ever stop this exact
     *    request; a late call for a settled id is a no-op and a different id is refused.
     *
     * The stop dispatch runs under [NonCancellable] because the structural-cancellation unwind
     * calls [cancel] from an already-cancelled collector: dropping the stop signal there would
     * recreate the exact failure this path exists to fix. The block is bounded by the engine's own
     * 500 ms stop window and never waits for native settle evidence.
     */
    override suspend fun cancel(requestId: String): Boolean {
        val cancelled = facade.cancelChat(requestId)
        val stopped = withContext(NonCancellable) {
            facade.stopGenerationIfActive(requestId)
        }
        return cancelled || stopped
    }
}

internal class StoreDshCloudModelAccess(
    private val store: CloudApiStore
) : DshCloudModelAccess {
    override suspend fun chatModels(): List<CloudModelRecord> = withContext(Dispatchers.IO) {
        store.loadModels()
    }
}

internal class ProviderDshCloudChatPort(
    private val provider: OpenAiCompatibleChatProvider
) : DshCloudChatPort {
    override fun stream(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent> =
        provider.streamChat(config, request)
}

/**
 * Builds the app-owned DSH inference backend once per DSH gateway/runtime generation.
 *
 * Threading contract for the caller:
 *  - [DshGatewayBackend.catalog] is a suspend function safe to call from the gateway's IO
 *    coroutine; the only blocking part (cloud model store read) is dispatched to `Dispatchers.IO`
 *    inside [StoreDshCloudModelAccess], and the engine reads are in-memory snapshots.
 *  - [DshGatewayBackend.stream] returns a cold flow. Collect it in the gateway's per-call IO
 *    coroutine; collection may suspend while the engine admits the request. Backend-local
 *    admission timeout (default 60s) turns that wait into a retryable `dsh_resource_busy`.
 *  - [DshGatewayBackend.cancel] is a bounded suspend dispatch: it marks the exact request on the
 *    supervisor and dispatches the exact engine stop (`runner.requestStop()`), then returns. It
 *    never waits for native settle evidence and is safe to await from the gateway's call coroutine.
 *
 * The instance must NOT be cached across DSH host generations that need a fresh catalog revision:
 * the local model snapshot and cloud records are read live, so a stale instance is still honest,
 * but the composition should recreate it with the gateway generation (see handoff notes).
 *
 * Local model picker label: the loaded model identity is the MCA manifest UUID and is never a
 * display name. By default the label comes from the process-global loaded-model projection the app
 * already publishes for the Local API (`id`-matched manifest `displayName`), so a hash-named MNN
 * bundle still shows `Qwen3.5 0.8B MNN CPU`. [localModelNameSource] can optionally override that
 * source when a composition-owned catalog is preferred. The resolver is called at most once per
 * loaded-model identity and the result is cached; if nothing is available the adapter falls back to
 * a readable model path/native name and finally to `MCA local model`, never a UUID. The gateway's
 * `[PROMPTED_TOOL_V1]` honesty suffix is unaffected by the label source.
 */
internal fun createMcaDshGatewayBackend(
    engine: McaInferenceService,
    facade: ChatExecutionFacade,
    cloudStore: CloudApiStore,
    cloudProvider: OpenAiCompatibleChatProvider,
    limits: DshAdapterLimits = DshAdapterLimits.Default,
    localModelNameSource: DshLocalModelNameSource? = null,
    installedLocalModels: () -> List<DshLocalModel> = { emptyList() }
): DshGatewayBackend = McaDshGatewayBackend(
    localModels = EngineDshLocalModelAccess(engine, localModelNameSource),
    installedLocalModels = installedLocalModels,
    localChat = FacadeDshLocalChatPort(facade),
    cloudModels = StoreDshCloudModelAccess(cloudStore),
    cloudChat = ProviderDshCloudChatPort(cloudProvider),
    limits = limits
)
