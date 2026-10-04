package com.muyuchat.mca

import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentTuningJobState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the Local API payload projections: field names, defaults, ordering and the
 * facts each document must (and must not) publish.
 *
 * [LocalApiPayloadPort] is an interface, so a hand-written port supplies the state and records the
 * model ids the document asks for. Every assertion is on the rendered JSON, which is what an API
 * client sees.
 */
class LocalApiPayloadProjectionsContractTest {

    @Test
    fun `tuning document renders the job phase progress and both live request flags`() {
        val port = FakePayloadPort(
            state = MainUiState(
                tuningJobState = AgentTuningJobState.RUNNING,
                tuningPhase = "measure",
                tuningEtaSeconds = 42L,
                autoTuningInProgress = true,
                tuningCandidateProgress = AgentCandidateProgress(
                    completed = 3,
                    total = 5,
                    currentCandidate = "candidate-3",
                    passed = 2,
                    rejected = 1
                )
            ),
            activeTuningJobId = "job-1",
            cancelRequested = true,
            pauseRequested = false,
            runtimeProfileJson = """{"profileId":"p1","revision":2}"""
        )

        val json = JSONObject(localApiTuningStateJson(port))

        assertEquals("job-1", json.getString("jobId"))
        assertEquals("running", json.getString("state"))
        assertEquals("measure", json.getString("phase"))
        assertEquals(42L, json.getLong("etaSeconds"))
        assertTrue(json.getBoolean("autoTuningInProgress"))
        assertTrue(json.getBoolean("cancelRequested"))
        assertFalse(json.getBoolean("pauseRequested"))
        val progress = json.getJSONObject("progress")
        assertEquals(3, progress.getInt("completed"))
        assertEquals(5, progress.getInt("total"))
        assertEquals("candidate-3", progress.getString("currentCandidate"))
        assertEquals(2, progress.getInt("passed"))
        assertEquals(1, progress.getInt("rejected"))
        assertEquals("p1", json.getJSONObject("profile").getString("profileId"))
    }

    @Test
    fun `tuning document with no job publishes an idle state and no profile keys`() {
        val port = FakePayloadPort(state = MainUiState(), runtimeProfileJson = "{}")

        val json = JSONObject(localApiTuningStateJson(port))

        assertTrue(json.isNull("jobId"))
        assertEquals("idle", json.getString("state"))
        assertFalse(json.has("phase"))
        assertTrue(json.isNull("etaSeconds"))
        assertFalse(json.getBoolean("autoTuningInProgress"))
        assertEquals(0, json.getJSONObject("progress").getInt("total"))
        assertEquals(0, json.getJSONObject("profile").length())
    }

    @Test
    fun `loaded model publishes the display name under three keys and vision only when validated`() {
        val manifest = manifest("m1", "Local Qwen")
        val state = MainUiState(
            models = listOf(manifest),
            loadedModelId = "m1",
            loadedModelName = "Local Qwen"
        )

        val readyOnly = JSONObject(
            localApiLoadedModelJson(
                FakePayloadPort(
                    state = state,
                    nativeStatsJson = """{"visionReady":true}""",
                    runtimeLoaded = true
                )
            )
        )
        assertEquals("m1", readyOnly.getString("id"))
        assertEquals("Local Qwen", readyOnly.getString("name"))
        assertEquals("Local Qwen", readyOnly.getString("displayName"))
        assertEquals(manifest.runtime.storageValue, readyOnly.getString("runtime"))
        assertTrue(readyOnly.getBoolean("vision_ready"))
        // A loaded image input is not evidence that an image inference ever passed.
        assertFalse(readyOnly.getBoolean("vision_validated"))
        assertFalse(readyOnly.getBoolean("visionValidated"))

        val validated = JSONObject(
            localApiLoadedModelJson(
                FakePayloadPort(
                    state = state,
                    nativeStatsJson = """{"visionReady":true,"visionVerified":true}""",
                    runtimeLoaded = true
                )
            )
        )
        assertTrue(validated.getBoolean("vision_ready"))
        assertTrue(validated.getBoolean("vision_validated"))
        assertTrue(validated.getBoolean("visionValidated"))
    }

    @Test
    fun `vision flags stay false when no model is loaded or the engine cannot answer`() {
        val manifest = manifest("m1", "Local Qwen")
        val state = MainUiState(
            models = listOf(manifest),
            loadedModelId = "m1",
            loadedModelName = "Local Qwen"
        )

        val engineNotLoaded = JSONObject(
            localApiLoadedModelJson(
                FakePayloadPort(
                    state = state,
                    nativeStatsJson = """{"visionReady":true,"visionVerified":true}""",
                    runtimeLoaded = false
                )
            )
        )
        assertFalse(engineNotLoaded.getBoolean("vision_ready"))
        assertFalse(engineNotLoaded.getBoolean("vision_validated"))

        val unparsableStats = JSONObject(
            localApiLoadedModelJson(
                FakePayloadPort(
                    state = state,
                    nativeStatsJson = "not a json document",
                    runtimeLoaded = true
                )
            )
        )
        assertFalse(unparsableStats.getBoolean("vision_ready"))
        assertFalse(unparsableStats.getBoolean("vision_validated"))

        val nothingLoaded = JSONObject(
            localApiLoadedModelJson(
                FakePayloadPort(
                    state = MainUiState(models = listOf(manifest)),
                    nativeStatsJson = """{"visionReady":true,"visionVerified":true}""",
                    runtimeLoaded = true
                )
            )
        )
        assertTrue(nothingLoaded.isNull("id"))
        assertFalse(nothingLoaded.getBoolean("vision_ready"))
        assertFalse(nothingLoaded.getBoolean("vision_validated"))
    }

    @Test
    fun `model list keeps the manifest id while naming the user-facing model three ways`() {
        val loaded = manifest("m1", "Display One")
        val other = manifest("m2", "Display Two")
        val state = MainUiState(
            models = listOf(loaded, other),
            loadedModelId = "m1",
            loadedModelName = "Display One"
        )
        val port = FakePayloadPort(
            state = state,
            nativeStatsJson = """{"visionReady":true,"visionVerified":true}""",
            runtimeLoaded = true
        )

        val root = JSONObject(localApiModelsJson(port))

        assertEquals("list", root.getString("object"))
        val data = root.getJSONArray("data")
        assertEquals(2, data.length())

        val first = data.getJSONObject(0)
        assertEquals("m1", first.getString("id"))
        assertEquals("model", first.getString("object"))
        assertEquals("local", first.getString("owned_by"))
        assertEquals("Display One", first.getString("name"))
        assertEquals("Display One", first.getString("display_name"))
        assertEquals("Display One", first.getString("displayName"))
        assertEquals(loaded.runtime.storageValue, first.getString("runtime"))
        assertTrue(first.getBoolean("vision_ready"))
        assertTrue(first.getBoolean("vision_validated"))

        val second = data.getJSONObject(1)
        assertEquals("m2", second.getString("id"))
        assertEquals("Display Two", second.getString("displayName"))
        assertNotEquals(second.getString("id"), second.getString("displayName"))
        // Vision evidence belongs to the model that is actually loaded.
        assertFalse(second.getBoolean("vision_ready"))
        assertFalse(second.getBoolean("vision_validated"))
    }

    @Test
    fun `model list appends configured image models after chat models without publishing bundle secrets`() = runBlocking {
        withTemporaryBundle { root ->
            val primary = root.resolve("model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val digest = "f".repeat(64)
            val imageModel = LocalImageModelRecord(
                id = "image-model-1",
                displayName = "Local SD 1.5",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = digest,
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD15,
                bundleRoot = root.absolutePath
            )
            val port = FakePayloadPort(
                state = MainUiState(
                    models = listOf(manifest("chat-1", "Chat One")),
                    localImageModels = listOf(imageModel)
                )
            )

            val data = JSONObject(localApiModelsJson(port)).getJSONArray("data")

            assertEquals(2, data.length())
            assertEquals("chat-1", data.getJSONObject(0).getString("id"))
            val entry = data.getJSONObject(1)
            assertEquals("image:image-model-1", entry.getString("id"))
            assertEquals("model", entry.getString("object"))
            assertEquals("local", entry.getString("owned_by"))
            assertEquals("Local SD 1.5", entry.getString("display_name"))
            assertEquals("image_generation", entry.getString("type"))
            assertTrue(entry.getBoolean("configured"))
            val published = entry.toString()
            assertFalse(published.contains(primary.absolutePath))
            assertFalse(published.contains(root.absolutePath))
            assertFalse(published.contains(digest))
        }
    }

    @Test
    fun `duplicate image model ids are published under distinct api aliases`() = runBlocking {
        withTemporaryBundle { root ->
            val primary = root.resolve("model.gguf").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fun duplicate() = LocalImageModelRecord(
                id = "shared-id",
                displayName = "Shared",
                path = primary.absolutePath,
                fileName = primary.name,
                sizeBytes = primary.length(),
                sha256 = "a".repeat(64),
                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                family = LocalImageModelFamily.SD15,
                bundleRoot = root.absolutePath
            )
            val port = FakePayloadPort(
                state = MainUiState(localImageModels = listOf(duplicate(), duplicate()))
            )

            val data = JSONObject(localApiModelsJson(port)).getJSONArray("data")

            assertEquals(2, data.length())
            assertEquals("image:shared-id", data.getJSONObject(0).getString("id"))
            assertEquals("image:shared-id:2", data.getJSONObject(1).getString("id"))
        }
    }

    @Test
    fun `unconfigured image models are not advertised`() {
        val missing = LocalImageModelRecord(
            id = "gone",
            displayName = "Gone",
            path = File("does-not-exist-anywhere.gguf").absolutePath,
            fileName = "does-not-exist-anywhere.gguf",
            sizeBytes = 0L,
            sha256 = "b".repeat(64),
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            family = LocalImageModelFamily.SD15
        )
        val port = FakePayloadPort(state = MainUiState(localImageModels = listOf(missing)))

        val data = JSONObject(localApiModelsJson(port)).getJSONArray("data")

        assertEquals(0, data.length())
    }

    @Test
    fun `runtime states are keyed by model id and every other model reports unloaded`() = runBlocking {
        val m1 = manifest("m1", "One")
        val m2 = manifest("m2", "Two")
        val state = MainUiState(
            models = listOf(m1, m2),
            loadedModelId = "m1",
            engineLifecycle = AgentEngineLifecycle.READY
        )
        val port = FakePayloadPort(
            state = state,
            summaries = listOf(
                PersistedModelRuntimeSummary(
                    modelId = "m1",
                    identityKey = "k1",
                    pointers = null,
                    activeProfile = executionProfile("p-active", "COMMITTED", "DEVICE_VERIFIED"),
                    pendingProfile = executionProfile("p-pending", "STAGED", "SAFE"),
                    activeJob = tuningJob("job-1", "RUNNING")
                ),
                PersistedModelRuntimeSummary(
                    modelId = "m2",
                    identityKey = "k2",
                    pointers = null,
                    activeProfile = null,
                    pendingProfile = null,
                    activeJob = null
                )
            )
        )

        val json = JSONObject(localApiModelRuntimeStatesJson(port))

        assertEquals(listOf("m1", "m2"), port.requestedModelIds)
        val first = json.getJSONObject("m1")
        assertEquals("p-active", first.getString("profileId"))
        assertEquals("committed", first.getString("profileRecordState"))
        assertEquals("device_verified", first.getString("profileVerificationLevel"))
        assertTrue(first.getBoolean("reloadRequired"))
        assertEquals("ready", first.getString("engineLifecycle"))
        assertEquals("running", first.getString("tuningJobState"))

        val second = json.getJSONObject("m2")
        assertTrue(second.isNull("profileId"))
        assertEquals("none", second.getString("profileRecordState"))
        assertEquals("unknown", second.getString("profileVerificationLevel"))
        assertFalse(second.getBoolean("reloadRequired"))
        // The engine lifecycle describes the process, not every entry in the map.
        assertEquals("unloaded", second.getString("engineLifecycle"))
        assertEquals("idle", second.getString("tuningJobState"))
    }

    @Test
    fun `state enums keep their documented defaults and labels`() {
        assertEquals(AgentTuningJobState.IDLE, MainUiState().tuningJobState)
        assertEquals(AgentEngineLifecycle.UNLOADED, MainUiState().engineLifecycle)
        assertEquals(AgentProfileRecordState.NONE, MainUiState().profileRecordState)
        assertEquals(AgentProfileVerification.UNKNOWN, MainUiState().verification)
        assertNull(MainUiState().loadedModelId)
        assertEquals("{}", MainUiState().nativeStatsJson)
    }

    private class FakePayloadPort(
        private val state: MainUiState,
        override val activeTuningJobId: String? = null,
        private val cancelRequested: Boolean = false,
        private val pauseRequested: Boolean = false,
        private val runtimeProfileJson: String = "{}",
        private val nativeStatsJson: String = "{}",
        private val runtimeLoaded: Boolean = false,
        private val summaries: List<PersistedModelRuntimeSummary> = emptyList()
    ) : LocalApiPayloadPort {
        var requestedModelIds: List<String> = emptyList()
            private set

        override fun uiState(): MainUiState = state

        override fun tuningCancelRequested(): Boolean = cancelRequested

        override fun tuningPauseRequested(): Boolean = pauseRequested

        override fun runtimeProfileJson(): String = runtimeProfileJson

        override fun nativeStatsJson(): String = nativeStatsJson

        override fun runtimeLoaded(): Boolean = runtimeLoaded

        override suspend fun modelRuntimeSummaries(modelIds: Collection<String>): List<PersistedModelRuntimeSummary> {
            requestedModelIds = modelIds.toList()
            return summaries
        }
    }

    private fun manifest(id: String, displayName: String): ModelManifest = ModelManifest(
        id = id,
        displayName = displayName,
        path = "C:/models/$id.gguf",
        source = ModelSource.entries.first(),
        fileName = "$id.gguf",
        sizeBytes = 1L,
        sha256 = "0".repeat(64)
    )

    private fun executionProfile(
        profileId: String,
        recordState: String,
        verificationLevel: String
    ): ExecutionProfileEntity = ExecutionProfileEntity(
        profileId = profileId,
        identityKey = "identity",
        revision = 1L,
        parentCommittedProfileId = null,
        recordState = recordState,
        verificationLevel = verificationLevel,
        desiredProfileSignature = "",
        resolvedLoadSignature = "",
        activeLoadedSignature = "",
        committedExecutionSignature = "",
        runtimeOverrideSignature = "",
        effectiveExecutionSignature = "",
        resolvedLoadJson = "{}",
        committedExecutionJson = "{}",
        quarantinedOverridesJson = "{}",
        sourceSummaryJson = "{}",
        profileJson = "{}",
        failureStage = null,
        failureCode = null,
        failureSummary = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun tuningJob(jobId: String, state: String): TuningJobEntity = TuningJobEntity(
        jobId = jobId,
        identityKey = "identity",
        state = state,
        candidateProfileId = null,
        phase = "measure",
        autoApplyLoadChanges = false,
        cancellationRequested = false,
        failureCode = null,
        failureSummary = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastHeartbeatAt = 0L
    )

    private suspend fun <T> withTemporaryBundle(block: suspend (File) -> T): T {
        val root = Files.createTempDirectory("local-api-payload").toFile().canonicalFile
        try {
            return block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
