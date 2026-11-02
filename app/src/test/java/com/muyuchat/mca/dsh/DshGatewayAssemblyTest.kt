package com.muyuchat.mca.dsh

import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.api.dsh.gateway.DshGatewayLogger
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.runtime.state.DshApplyDecision
import com.muyuchat.core.dsh.runtime.state.DshApplyPolicy
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshHostConfig
import com.muyuchat.core.dsh.runtime.state.DshInFlightChannel
import com.muyuchat.core.dsh.runtime.state.DshInFlightEvidence
import com.muyuchat.mca.composition.DshCatalogChange
import com.muyuchat.mca.composition.DshGatewayAssembly
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the production composition and real authenticated HTTP listener before any model exists. */
class DshGatewayAssemblyTest {

    @Test
    fun emptyCatalogStartsAnAuthenticatedGatewayWithoutInventingAModel() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            val endpoint = assembly.endpointProvider.endpoint()
            assertNotNull("An empty model catalog must not block opening DSH", endpoint)
            endpoint!!

            val unauthorized = request(endpoint, authenticated = false)
            assertEquals(401, unauthorized.first)
            val models = request(endpoint)
            assertEquals(200, models.first)
            assertEquals(0, models.second.getJSONArray("data").length())

            val missingModel = request(
                endpoint,
                path = "/chat/completions",
                body = """{"model":"missing-model","messages":[{"role":"user","content":"hello"}],"stream":false}"""
            )
            assertEquals(404, missingModel.first)
            assertEquals("model_not_found", missingModel.second.getJSONObject("error").getString("code"))
            assertEquals(0, backend.streamCalls.get())

            val attached = assembly.endpointProvider.endpoint()!!
            assertEquals(endpoint.baseUrl, attached.baseUrl)
            assertEquals(endpoint.bearerToken, attached.bearerToken)
        } finally {
            assembly.stop()
        }
    }

    @Test
    fun modelsLoadedAfterStartupAppearOnTheSameGatewayGeneration() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            val endpoint = assembly.endpointProvider.endpoint()!!
            assertEquals(0, request(endpoint).second.getJSONArray("data").length())

            backend.models = listOf(
                DshGatewayModel(
                    id = "mca-local:new-model", displayName = "Loaded Model",
                    contextWindow = 4_096, maxOutputTokens = null,
                    supportsTools = false, nativeToolCalling = false
                )
            )
            val updated = request(endpoint)
            assertEquals(200, updated.first)
            val models = updated.second.getJSONArray("data")
            assertEquals(1, models.length())
            assertEquals("mca-local:new-model", models.getJSONObject(0).getString("id"))
            val attached = assembly.endpointProvider.endpoint()!!
            assertEquals(endpoint.baseUrl, attached.baseUrl)
            assertEquals(endpoint.bearerToken, attached.bearerToken)

            backend.models = emptyList()
            assertEquals(0, request(endpoint).second.getJSONArray("data").length())
            assertEquals(0, backend.streamCalls.get())
        } finally {
            assembly.stop()
        }
    }

    @Test
    fun activityObservationIsTriStateAndNeverInventsAZero() = runBlocking {
        val backend = MutableBackend()
        // The tool-execution channel is not wired in this build: it must be reported as uncovered.
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            // No gateway generation is listening yet: the model channel is unobservable, not zero.
            val beforeStart = assembly.observeActivity()
            assertNull(beforeStart.observedCalls)
            assertTrue(beforeStart.unobservable)
            assertEquals(
                listOf(DshInFlightChannel.MODEL_GATEWAY),
                beforeStart.unobservableChannels
            )
            assertTrue(beforeStart.detail.contains("model-gateway=unobservable"))
            assertTrue(beforeStart.uncovered.contains(DshInFlightChannel.TOOL_EXECUTION))
            assertTrue(beforeStart.uncovered.contains(DshInFlightChannel.ACCOUNT_DIRECT))
            assertNull(assembly.inFlightObservation.inFlightCalls())

            // A running gateway reports a real zero, and the uncovered channels stay reported.
            assembly.endpointProvider.endpoint()
            val running = assembly.observeActivity()
            assertEquals(0, running.observedCalls)
            assertEquals(listOf(DshInFlightChannel.MODEL_GATEWAY), running.observedChannels)
            assertTrue(running.uncovered.contains(DshInFlightChannel.TOOL_EXECUTION))
            assertTrue(running.uncovered.contains(DshInFlightChannel.ACCOUNT_DIRECT))
            assertEquals(0, assembly.inFlightObservation.inFlightCalls())
        } finally {
            assembly.stop()
        }
    }

    @Test
    fun wiredToolExecutionEvidenceIsSummedWithTheModelChannel() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(
            backend,
            DshGatewayLogger { _, _ -> },
            toolEvidence = { DshInFlightEvidence.Observed(DshInFlightChannel.TOOL_EXECUTION, 2) }
        )
        try {
            assembly.endpointProvider.endpoint()
            val observation = assembly.observeActivity()
            assertEquals(2, observation.observedCalls)
            assertEquals(
                listOf(DshInFlightChannel.MODEL_GATEWAY, DshInFlightChannel.TOOL_EXECUTION),
                observation.observedChannels
            )
            // Account-direct turns cannot be seen by MCA even when both evidencing channels answer.
            assertEquals(listOf(DshInFlightChannel.ACCOUNT_DIRECT), observation.uncovered)
            assertEquals(2, assembly.inFlightObservation.inFlightCalls())
        } finally {
            assembly.stop()
        }
    }

    @Test
    fun anUnobservableToolChannelMakesTheWholeObservationUnobservable() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(
            backend,
            DshGatewayLogger { _, _ -> },
            toolEvidence = {
                DshInFlightEvidence.Unobservable(
                    DshInFlightChannel.TOOL_EXECUTION,
                    "bridge ledger is closing"
                )
            }
        )
        try {
            assembly.endpointProvider.endpoint()
            val observation = assembly.observeActivity()
            // A partial sum would read as "idle" while the tool channel is busy-but-unseen.
            assertNull(observation.observedCalls)
            assertTrue(observation.unobservable)
            assertNull(assembly.inFlightObservation.inFlightCalls())
        } finally {
            assembly.stop()
        }
    }

    /**
     * MODEL-03: the app-side catalog revision moves exactly when the advertised model list moves,
     * and the running generation keeps its port and bearer token across that change.
     */
    @Test
    fun catalogRevisionChangesExactlyWhenTheModelListChanges() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            val started = assembly.endpointProvider.endpoint()!!
            assertTrue("An empty catalog still has a revision", started.catalogRevision.isNotEmpty())
            val empty = assembly.catalogState
            assertEquals(0, empty.modelCount)
            assertTrue(empty.active)
            assertFalse(empty.bound)
            assertEquals(0, assembly.catalogSize)

            // Reading the same list again is not a change, so it cannot request a host reload.
            assertEquals(DshCatalogChange.Unchanged(empty), assembly.observeCatalogChange())

            backend.models = listOf(localModel("qwen-test"))
            val loaded = when (val change = assembly.observeCatalogChange()) {
                is DshCatalogChange.Changed -> change
                else -> error("a loaded model must be a catalog change, was $change")
            }
            assertEquals(empty, loaded.previous)
            assertEquals(1, loaded.current.modelCount)
            assertTrue(loaded.current.revision != empty.revision)
            assertTrue(loaded.current.bound)
            assertEquals(loaded.current, assembly.catalogState)

            // The live endpoint advertises the new revision with the SAME port and token.
            val attached = assembly.endpointProvider.endpoint()!!
            assertEquals(started.baseUrl, attached.baseUrl)
            assertEquals(started.bearerToken, attached.bearerToken)
            assertEquals(loaded.current.revision, attached.catalogRevision)
            assertEquals(1, assembly.catalogSize)

            // Unloading is a real revision change too, and never invents a model.
            backend.models = emptyList()
            val unloaded = when (val change = assembly.observeCatalogChange()) {
                is DshCatalogChange.Changed -> change
                else -> error("unloading the model must be a catalog change, was $change")
            }
            assertEquals(loaded.current, unloaded.previous)
            assertEquals(0, unloaded.current.modelCount)
            assertEquals(empty.revision, unloaded.current.revision)
            assertEquals(0, assembly.catalogSize)
        } finally {
            assembly.stop()
        }
    }

    /** An empty catalog is a valid, bound-free signal — never a failure and never a fake model. */
    @Test
    fun anEmptyCatalogIsAValidSignalAndNotAFailure() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            val endpoint = requireNotNull(assembly.endpointProvider.endpoint()) {
                "An empty model catalog must not block opening DSH"
            }
            val state = assembly.catalogState
            assertTrue(state.active)
            assertFalse(state.bound)
            assertEquals(0, state.modelCount)
            assertEquals(endpoint.catalogRevision, state.revision)
            assertEquals(200, request(endpoint).first)
            assertEquals(0, request(endpoint).second.getJSONArray("data").length())
        } finally {
            assembly.stop()
        }
    }

    /**
     * A failed catalog read keeps the previous revision. It is not a model change, and it is not
     * an empty catalog: the endpoint must keep advertising the last good snapshot.
     */
    @Test
    fun aFailedCatalogReadKeepsThePreviousRevisionInsteadOfAnEmptyCatalog() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            assembly.endpointProvider.endpoint()!!
            backend.models = listOf(localModel("qwen-test"))
            assertTrue(assembly.observeCatalogChange() is DshCatalogChange.Changed)
            val good = assembly.catalogState
            assertEquals(1, good.modelCount)

            backend.failCatalog = true
            val failed = when (val change = assembly.observeCatalogChange()) {
                is DshCatalogChange.Unreadable -> change
                else -> error("a failed catalog read must be unreadable, was $change")
            }
            assertEquals(good, failed.retained)
            assertEquals(good, assembly.catalogState)
            assertEquals(1, assembly.catalogSize)

            // The attach path keeps the last good revision instead of publishing an empty catalog.
            val attached = requireNotNull(assembly.endpointProvider.endpoint()) {
                "a running generation must keep advertising the last good catalog"
            }
            assertEquals(good.revision, attached.catalogRevision)

            // A later successful read recovers without inventing a change.
            backend.failCatalog = false
            assertEquals(DshCatalogChange.Unchanged(good), assembly.observeCatalogChange())
        } finally {
            assembly.stop()
        }
    }

    /**
     * MODEL-04 app-side half: an unchanged catalog must not ask the host for a new generation.
     *
     * The runtime owner answers UNCHANGED when the desired binding id equals the applied one, and
     * that id is derived from the gateway coordinates (URL, token, catalog revision). This test
     * proves the unchanged catalog reproduces the same id — so no restart can be requested — while
     * a changed catalog does produce a reload request.
     */
    @Test
    fun anUnchangedCatalogNeverRequestsAHostRestart() = runBlocking {
        val backend = MutableBackend()
        val assembly = DshGatewayAssembly(backend, DshGatewayLogger { _, _ -> })
        try {
            val first = assembly.endpointProvider.endpoint()!!
            val second = assembly.endpointProvider.endpoint()!!
            assertEquals(first.catalogRevision, second.catalogRevision)
            assertEquals(first.baseUrl, second.baseUrl)
            assertEquals(first.bearerToken, second.bearerToken)

            val applied = DshHostConfig(gateway = first)
            val desired = DshHostConfig(gateway = second)
            assertEquals(applied.id.value, desired.id.value)
            val decision = DshApplyPolicy.decide(
                snapshot = readySnapshot(),
                hostAlive = true,
                applied = applied,
                desired = desired,
                request = DshApplyRequest(inFlightCalls = 0, source = "model-catalog")
            )
            assertTrue(decision is DshApplyDecision.Unchanged)

            // Only a changed catalog yields a different binding id, i.e. a reload request.
            backend.models = listOf(localModel("qwen-test"))
            val changedEndpoint = assembly.endpointProvider.endpoint()!!
            val changed = DshHostConfig(gateway = changedEndpoint)
            assertTrue(applied.id.value != changed.id.value)
            val reload = when (
                val decision = DshApplyPolicy.decide(
                    snapshot = readySnapshot(),
                    hostAlive = true,
                    applied = applied,
                    desired = changed,
                    request = DshApplyRequest(inFlightCalls = 0, source = "model-catalog")
                )
            ) {
                is DshApplyDecision.Apply -> decision
                else -> error("a changed catalog must request the new binding, was $decision")
            }
            assertTrue(reload.reload)
        } finally {
            assembly.stop()
        }
    }

    private fun readySnapshot() = DshRuntimeSnapshot(
        phase = DshRuntimePhase.READY,
        epoch = 1L,
        changedAtMillis = 1L
    )

    private fun localModel(id: String) = DshGatewayModel(
        id = "mca-local:$id",
        displayName = "Qwen Test",
        contextWindow = 16_384,
        maxOutputTokens = null,
        supportsTools = true,
        nativeToolCalling = false
    )

    private class MutableBackend : DshGatewayBackend {
        @Volatile var models: List<DshGatewayModel> = emptyList()

        /** Simulates a catalog read failure (cloud store unavailable, engine snapshot missing). */
        @Volatile var failCatalog: Boolean = false

        val streamCalls = AtomicInteger()

        override suspend fun catalog(): List<DshGatewayModel> {
            if (failCatalog) throw IllegalStateException("the model catalog is unavailable")
            return models
        }

        override fun stream(request: DshModelRequest): Flow<DshModelEvent> {
            streamCalls.incrementAndGet()
            return emptyFlow()
        }
    }

    private fun request(
        endpoint: DshGatewayEndpoint,
        path: String = "/models",
        authenticated: Boolean = true,
        body: String? = null
    ): Pair<Int, JSONObject> {
        val connection = URI(endpoint.baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer ${endpoint.bearerToken}")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val response = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return status to JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
