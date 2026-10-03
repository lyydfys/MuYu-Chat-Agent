package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON payloads the Local API serves about models and tuning.
 *
 * These are **projections**: they read state and render a document. None of them decides anything,
 * owns a job or takes a lock, which is why they can leave the ViewModel without moving ownership with
 * them. The one control path that *does* write state and block (`runTuningControl`) deliberately stays
 * behind: it is an orchestrator, and putting it here would drag a state writer into a projection file.
 *
 * The wire shapes are frozen by the Local API contract and are reproduced field for field, including
 * the three spellings of the display name and the two spellings of `vision_validated` that
 * OpenAI-compatible clients disagree about.
 */
internal interface LocalApiPayloadPort {

    fun uiState(): MainUiState

    /** The tuning job the API reports, if any. */
    val activeTuningJobId: String?

    /** Whether a cancel has been requested for the running adaptive tuning job. */
    fun tuningCancelRequested(): Boolean

    /** Whether a pause has been requested for the running adaptive tuning job. */
    fun tuningPauseRequested(): Boolean

    /** The committed runtime profile document, as the runtime endpoint renders it. */
    fun runtimeProfileJson(): String

    /** The engine's raw native stats document; empty when the engine cannot answer. */
    fun nativeStatsJson(): String

    /** Whether the native runtime currently reports a loaded model. */
    fun runtimeLoaded(): Boolean

    /**
     * Persisted runtime summaries for the given model ids.
     *
     * Suspending, and the caller does the blocking hop: this file must not decide how the store is
     * entered, only what the resulting document looks like.
     */
    suspend fun modelRuntimeSummaries(modelIds: Collection<String>): List<PersistedModelRuntimeSummary>
}

/**
 * `/v1/tuning` state: the job, its phase, ETA, progress counters, the cancel/pause requests and the
 * committed runtime profile.
 *
 * `cancelRequested` / `pauseRequested` are read through the port rather than passed in as booleans so
 * the read happens at render time, exactly as it did when this read the atomics directly.
 */
internal fun localApiTuningStateJson(port: LocalApiPayloadPort): String {
    val state = port.uiState()
    return JSONObject()
        .put("jobId", port.activeTuningJobId)
        .put("state", state.tuningJobState.name.lowercase())
        .put("phase", state.tuningPhase)
        .put("etaSeconds", state.tuningEtaSeconds)
        .put("autoTuningInProgress", state.autoTuningInProgress)
        .put("cancelRequested", port.tuningCancelRequested())
        .put("pauseRequested", port.tuningPauseRequested())
        .put("progress", JSONObject()
            .put("completed", state.tuningCandidateProgress.completed)
            .put("total", state.tuningCandidateProgress.total)
            .put("currentCandidate", state.tuningCandidateProgress.currentCandidate)
            .put("passed", state.tuningCandidateProgress.passed)
            .put("rejected", state.tuningCandidateProgress.rejected))
        .put("profile", JSONObject(port.runtimeProfileJson()))
        .toString()
}

/**
 * The loaded-model document.
 *
 * `vision_ready` and `vision_validated` are deliberately different facts: a model that loaded an
 * image input is **not** evidence that an image inference ever passed, so `visionValidated` requires
 * `visionVerified` from the native runner.
 */
internal fun localApiLoadedModelJson(port: LocalApiPayloadPort): String {
    val state = port.uiState()
    val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
    val nativeStats = runCatching { JSONObject(port.nativeStatsJson()) }.getOrElse { JSONObject() }
    val runtimeLoaded = state.loadedModelId != null && port.runtimeLoaded()
    val visionReady = runtimeLoaded && nativeStats.optBoolean("visionReady", false)
    // A loaded image input is not evidence that an image inference passed.
    val visionValidated = runtimeLoaded && nativeStats.optBoolean("visionVerified", false)
    return JSONObject()
        .put("id", state.loadedModelId)
        .put("name", state.loadedModelName)
        .put("displayName", state.loadedModelName)
        .put("runtime", loadedModel?.runtime?.storageValue)
        .put("vision_ready", visionReady)
        .put("vision_validated", visionValidated)
        .put("visionValidated", visionValidated)
        .put("vision_projector", loadedModel?.visionProjectorFileName)
        .put("stats", nativeStats)
        .toString()
}

/**
 * The OpenAI-compatible model list, followed by the local image-model catalogue entries.
 *
 * The stable manifest id stays in `id`; the user-facing name is published under all three keys that
 * clients disagree about, and is **never** derived from the id.
 */
internal fun localApiModelsJson(port: LocalApiPayloadPort): String {
    val array = JSONArray()
    val state = port.uiState()
    val nativeStats = runCatching { JSONObject(port.nativeStatsJson()) }.getOrElse { JSONObject() }
    state.models.forEach { model ->
        val selected = port.runtimeLoaded() && model.id == state.loadedModelId
        val visionReady = selected && nativeStats.optBoolean("visionReady", false)
        val visionValidated = selected && nativeStats.optBoolean("visionVerified", false)
        array.put(
            JSONObject()
                .put("id", model.id)
                .put("object", "model")
                .put("owned_by", "local")
                // OpenAI-compatible clients do not agree on the display-name key. Keep
                // the stable manifest id while publishing aliases from the real manifest
                // display name; never derive a user-facing name from the id.
                .put("name", model.displayName)
                .put("display_name", model.displayName)
                .put("displayName", model.displayName)
                .put("runtime", model.runtime.storageValue)
                .put("vision_projector", model.visionProjectorFileName)
                .put("vision_ready", visionReady)
                .put("vision_validated", visionValidated)
        )
    }
    localImageApiCatalogEntries(
        chatModelIds = state.models.map(ModelManifest::id),
        imageModels = state.localImageModels
    ).forEach { entry -> array.put(entry.payload) }
    return JSONObject().put("object", "list").put("data", array).toString()
}

/**
 * Per-model runtime state keyed by model id: the active profile, its verification level, whether a
 * reload is pending, and the engine lifecycle for the model that is currently loaded.
 *
 * A model that is not the loaded one reports `unloaded` rather than the engine's lifecycle, because
 * the engine lifecycle describes the process, not each entry in the map.
 */
internal suspend fun localApiModelRuntimeStatesJson(port: LocalApiPayloadPort): String {
    val models = port.uiState().models
    val states = port.modelRuntimeSummaries(models.map { it.id })
    val root = JSONObject()
    states.forEach { runtimeState ->
        val modelId = runtimeState.modelId
        val profile = runtimeState.activeProfile
        val activeJob = runtimeState.activeJob
        val isLoaded = modelId == port.uiState().loadedModelId
        root.put(
            modelId,
            JSONObject()
                .put("profileId", profile?.profileId)
                .put("profileRecordState", profile?.recordState?.lowercase(java.util.Locale.ROOT) ?: "none")
                .put(
                    "profileVerificationLevel",
                    profile?.verificationLevel?.lowercase(java.util.Locale.ROOT) ?: "unknown"
                )
                .put("reloadRequired", runtimeState.pendingProfile != null)
                .put(
                    "engineLifecycle",
                    if (isLoaded) {
                        port.uiState().engineLifecycle.name.lowercase(java.util.Locale.ROOT)
                    } else {
                        "unloaded"
                    }
                )
                .put("tuningJobState", activeJob?.state?.lowercase(java.util.Locale.ROOT) ?: "idle")
        )
    }
    return root.toString()
}
