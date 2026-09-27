package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtVisionUiReadinessTest {
    @Test
    fun explicitAbsenceOfVisualComponentsKeepsTheUiUnavailable() {
        val stats = JSONObject()
            .put("loaded", true)
            .put("runnerReady", true)
            .put("visionInputTransportReady", true)
            .put("visionModelVisualComponentsPresent", false)
            .put("visionReady", false)

        assertFalse(liteRtVisionInputReadyForUi(stats))
        assertFalse(liteRtVisionVerifiedReadyForUi(stats))
    }

    @Test
    fun visualPackageCanOfferImageInputBeforeItsFirstSuccessfulImageTurn() {
        val stats = JSONObject()
            .put("loaded", true)
            .put("runnerReady", true)
            .put("visionInputTransportReady", true)
            .put("visionModelVisualComponentsPresent", true)
            .put("visionReady", false)

        assertTrue(liteRtVisionInputReadyForUi(stats))
        assertFalse(liteRtVisionVerifiedReadyForUi(stats))
    }

    @Test
    fun legacyStatsStillUseTheRunnerReadyCompatibilityFallback() {
        val stats = JSONObject()
            .put("loaded", true)
            .put("runnerReady", true)
            .put("visionReady", true)

        assertTrue(liteRtVisionInputReadyForUi(stats))
        assertTrue(liteRtVisionVerifiedReadyForUi(stats))
    }
}
