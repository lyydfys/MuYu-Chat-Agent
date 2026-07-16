package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningProbeWorkerProtocolTest {
    @Test
    fun requestRoundTripContainsOnlyPersistedIdentityFields() {
        val request = request()

        val raw = TuningProbeWorkerProtocol.start(request)
        val json = JSONObject(raw)

        assertEquals(TuningProbeWorkerProtocol.requestKeys, json.keys().asSequence().toSet())
        listOf("prompt", "messages", "path", "sampling", "temperature", "userData").forEach { forbidden ->
            assertFalse(raw.contains(forbidden, ignoreCase = true))
        }
        assertEquals(request, TuningProbeWorkerProtocol.parseStart(raw))
    }

    @Test
    fun requestParserRejectsAnyCallerSuppliedPromptOrExtraField() {
        val raw = JSONObject(TuningProbeWorkerProtocol.start(request()))
            .put("prompt", "caller data must never cross this boundary")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            TuningProbeWorkerProtocol.parseStart(raw)
        }
    }

    @Test
    fun resultRoundTripPreservesIndependentSequenceSignatureAndMemoryEvidence() {
        val result = TuningProbeWorkerProtocol.Result(
            requestId = "request-1",
            transactionId = "probe-transaction-1",
            identityKey = "identity-1",
            modelId = "model-1",
            profileId = "profile-1",
            resolvedLoadSignature = "resolved-1",
            committedExecutionSignature = "committed-1",
            passed = true,
            signatureMatched = true,
            output = "fixed-canary-output",
            detail = "passed",
            runtimeStatsJson = JSONObject().put("decodeTps", 7.5).toString(),
            evidenceJson = JSONObject()
                .put("runs", JSONArray()
                    .put(JSONObject().put("requestId", "canary-1").put("generationSequenceAfter", 1))
                    .put(JSONObject().put("requestId", "canary-2").put("generationSequenceAfter", 2)))
                .put("signatures", JSONObject().put("matched", true))
                .toString(),
            startAvailableMemoryBytes = 5_000,
            startPssBytes = 1_000,
            startRssBytes = 2_000,
            endAvailableMemoryBytes = 4_000,
            endPssBytes = 1_500,
            endRssBytes = 2_500,
            lowMemoryTriggered = false,
            elapsedMs = 300
        )

        val restored = TuningProbeWorkerProtocol.parseComplete(
            TuningProbeWorkerProtocol.complete(result)
        )

        assertEquals(result, restored)
        val runs = JSONObject(restored.evidenceJson).getJSONArray("runs")
        assertEquals("canary-1", runs.getJSONObject(0).getString("requestId"))
        assertEquals(2L, runs.getJSONObject(1).getLong("generationSequenceAfter"))
        assertTrue(JSONObject(restored.evidenceJson).getJSONObject("signatures").getBoolean("matched"))
    }

    @Test
    fun clientTimeoutLeavesRoomForWorkerWatchdogBinderDeath() {
        assertTrue(
            TuningProbeWorkerProtocol.CLIENT_RUN_TIMEOUT_MS >
                TuningProbeWorkerProtocol.HARD_PROCESS_TIMEOUT_MS
        )
    }

    @Test
    fun binderStartTokenProtectsJsonParsingUntilInvalidRequestReleasesIt() {
        val lifecycle = TuningProbeProcessLifecycle()
        val parsing = lifecycle.beginStart()

        assertEquals(1, lifecycle.startInProgressCount)
        assertFalse(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = false))

        assertTrue(lifecycle.abandonStart(requireNotNull(parsing)))
        assertEquals(0, lifecycle.startInProgressCount)
        assertTrue(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = false))
    }

    @Test
    fun workerBusyReturnsItsParserTokenWithoutReleasingTheActiveRequest() {
        val lifecycle = TuningProbeProcessLifecycle()
        val first = requireNotNull(lifecycle.beginStart())
        val second = requireNotNull(lifecycle.beginStart())

        assertEquals(2, lifecycle.startInProgressCount)
        assertEquals(TuningProbeStartResolution.ACCEPTED, lifecycle.resolveStart(first))
        assertEquals(TuningProbeStartResolution.BUSY, lifecycle.resolveStart(second))
        assertEquals(0, lifecycle.startInProgressCount)
        assertTrue(lifecycle.hasActiveRequest)
        assertFalse(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = true))
    }

    @Test
    fun linkToDeathFailureReleasesTheAcceptedRequestForProcessExit() {
        val lifecycle = TuningProbeProcessLifecycle()
        val accepted = requireNotNull(lifecycle.beginStart())

        assertEquals(TuningProbeStartResolution.ACCEPTED, lifecycle.resolveStart(accepted))
        assertFalse(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = true))

        assertTrue(lifecycle.finishActive(accepted))
        assertFalse(lifecycle.hasActiveRequest)
        assertTrue(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = false))
        assertFalse(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 8, hasExternalActiveRequest = false))
    }

    @Test
    fun serviceDestructionInvalidatesParsersAndRejectsLaterStarts() {
        val lifecycle = TuningProbeProcessLifecycle()
        val active = requireNotNull(lifecycle.beginStart())
        assertEquals(TuningProbeStartResolution.ACCEPTED, lifecycle.resolveStart(active))
        val parsing = requireNotNull(lifecycle.beginStart())

        lifecycle.destroy()

        assertTrue(lifecycle.destroyed)
        assertEquals(0, lifecycle.startInProgressCount)
        assertFalse(lifecycle.hasActiveRequest)
        assertFalse(lifecycle.finishActive(active))
        assertFalse(lifecycle.abandonStart(parsing))
        assertNull(lifecycle.beginStart())
        assertFalse(lifecycle.shouldExit(scheduledEpoch = 7, currentEpoch = 7, hasExternalActiveRequest = false))
    }

    private fun request() = TuningProbeWorkerProtocol.Request(
        requestId = "request-1",
        transactionId = "probe-transaction-1",
        identityKey = "identity-1",
        modelId = "model-1",
        profileId = "profile-1",
        resolvedLoadSignature = "resolved-1",
        committedExecutionSignature = "committed-1"
    )
}
