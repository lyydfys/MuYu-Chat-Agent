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
        TuningProbeWorkerProtocol.ProbeKind.entries.forEach { probeKind ->
            val request = request(probeKind)

            val raw = TuningProbeWorkerProtocol.start(request)
            val json = JSONObject(raw)

            assertEquals(TuningProbeWorkerProtocol.requestKeys, json.keys().asSequence().toSet())
            assertEquals(2, json.getInt("version"))
            listOf("prompt", "messages", "path", "device", "chipset", "sampling", "temperature", "userData")
                .forEach { forbidden ->
                    assertFalse(raw.contains(forbidden, ignoreCase = true))
                }
            assertEquals(probeKind.name, json.getString("probeKind"))
            assertEquals(request, TuningProbeWorkerProtocol.parseStart(raw))
        }
    }

    @Test
    fun requestParserRejectsMissingProbeKind() {
        val raw = JSONObject(TuningProbeWorkerProtocol.start(request()))
            .apply { remove("probeKind") }
            .toString()

        assertThrows(IllegalStateException::class.java) {
            TuningProbeWorkerProtocol.parseStart(raw)
        }
    }

    @Test
    fun requestParserRejectsUnknownProbeKind() {
        val raw = JSONObject(TuningProbeWorkerProtocol.start(request()))
            .put("probeKind", "UNKNOWN_PROBE")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            TuningProbeWorkerProtocol.parseStart(raw)
        }
    }

    @Test
    fun requestParserRejectsCallerSuppliedPromptPathDeviceAndChipset() {
        listOf("prompt", "path", "device", "chipset").forEach { forbidden ->
            val raw = JSONObject(TuningProbeWorkerProtocol.start(request()))
                .put(forbidden, "caller data must never cross this boundary")
                .toString()

            assertThrows(IllegalArgumentException::class.java) {
                TuningProbeWorkerProtocol.parseStart(raw)
            }
        }
    }

    @Test
    fun resultRoundTripPreservesIndependentSequenceSignatureAndMemoryEvidence() {
        val result = result(TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD).copy(
            runtimeStatsJson = JSONObject().put("decodeTps", 7.5).toString(),
            evidenceJson = JSONObject()
                .put("runs", JSONArray()
                    .put(JSONObject().put("requestId", "canary-1").put("generationSequenceAfter", 1))
                    .put(JSONObject().put("requestId", "canary-2").put("generationSequenceAfter", 2)))
                .put("signatures", JSONObject().put("matched", true))
                .toString()
        )

        val raw = TuningProbeWorkerProtocol.complete(result)
        val json = JSONObject(raw)
        val restored = TuningProbeWorkerProtocol.parseComplete(raw)

        assertEquals(2, json.getInt("version"))
        assertEquals(result.probeKind.name, json.getString("probeKind"))
        assertEquals(result, restored)
        val runs = JSONObject(restored.evidenceJson).getJSONArray("runs")
        assertEquals("canary-1", runs.getJSONObject(0).getString("requestId"))
        assertEquals(2L, runs.getJSONObject(1).getLong("generationSequenceAfter"))
        assertTrue(JSONObject(restored.evidenceJson).getJSONObject("signatures").getBoolean("matched"))
    }

    @Test
    fun resultParserRejectsMissingOrUnknownProbeKind() {
        val encoded = JSONObject(TuningProbeWorkerProtocol.complete(result()))
        val missing = JSONObject(encoded.toString()).apply { remove("probeKind") }.toString()
        val unknown = JSONObject(encoded.toString()).put("probeKind", "UNKNOWN_PROBE").toString()

        assertThrows(IllegalStateException::class.java) {
            TuningProbeWorkerProtocol.parseComplete(missing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TuningProbeWorkerProtocol.parseComplete(unknown)
        }
    }

    @Test
    fun resultParserRejectsPreviousProtocolVersion() {
        val raw = JSONObject(TuningProbeWorkerProtocol.complete(result()))
            .put("version", 1)
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            TuningProbeWorkerProtocol.parseComplete(raw)
        }
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

    private fun request(
        probeKind: TuningProbeWorkerProtocol.ProbeKind = TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE
    ) = TuningProbeWorkerProtocol.Request(
        requestId = "request-1",
        probeKind = probeKind,
        transactionId = "probe-transaction-1",
        identityKey = "identity-1",
        modelId = "model-1",
        profileId = "profile-1",
        resolvedLoadSignature = "resolved-1",
        committedExecutionSignature = "committed-1"
    )

    private fun result(
        probeKind: TuningProbeWorkerProtocol.ProbeKind = TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE
    ) = TuningProbeWorkerProtocol.Result(
        requestId = "request-1",
        probeKind = probeKind,
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
        runtimeStatsJson = "{}",
        evidenceJson = "{}",
        startAvailableMemoryBytes = 5_000,
        startPssBytes = 1_000,
        startRssBytes = 2_000,
        endAvailableMemoryBytes = 4_000,
        endPssBytes = 1_500,
        endRssBytes = 2_500,
        lowMemoryTriggered = false,
        elapsedMs = 300
    )
}
