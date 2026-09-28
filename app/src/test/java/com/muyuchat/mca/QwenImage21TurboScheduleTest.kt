package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImage21TurboScheduleTest {
    private fun adapter(sha256: String, multiplier: Double = 1.0) = LocalImagePreparedLora(
        id = "00000000-0000-0000-0000-000000000001",
        name = "Viggle Turbo",
        path = "adapter.safetensors",
        sha256 = sha256,
        sizeBytes = 679_604_800L,
        multiplier = multiplier,
    )

    @Test
    fun recognizesOnlyThePublishedV021AdapterBytes() {
        assertTrue(QwenImage21TurboSchedule.isOfficialV021(adapter(
            "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b"
        )))
        assertTrue(QwenImage21TurboSchedule.isOfficialV021(adapter(
            "2a0148f5c73abbed5f97da5ea356e439318aadb281d01fce4af39cdf43728803"
        )))
        assertFalse(QwenImage21TurboSchedule.isOfficialV021(adapter(
            "5c494dce662a95898d0988831af733b203ceced9116a82aa099c997f96ea1a3a"
        )))
        assertFalse(QwenImage21TurboSchedule.isOfficialV021(adapter(
            "bafb91d0047df3f9b8a5a850b0c967f051164314d8aad778dfa34d9c24ec345b",
            multiplier = 0.5,
        )))
    }

    @Test
    fun turboControlsPreserveExplicitNativeSettings() {
        val defaults = LocalImageGenerationOptions()
        assertTrue(QwenImage21TurboSchedule.acceptsControls(defaults))
        assertTrue(QwenImage21TurboSchedule.acceptsControls(defaults.copy(cfgScale = 1.0)))
        assertFalse(QwenImage21TurboSchedule.acceptsControls(defaults.copy(cfgScale = 6.0)))
        assertFalse(QwenImage21TurboSchedule.acceptsControls(defaults.copy(useCfg = true)))
        assertFalse(QwenImage21TurboSchedule.acceptsControls(defaults.copy(negativePrompt = "blur")))
        assertFalse(QwenImage21TurboSchedule.acceptsControls(defaults.copy(flowShift = 0.5)))
        assertFalse(QwenImage21TurboSchedule.acceptsControls(defaults.copy(distilledGuidance = 3.5)))
    }

    private fun result(schedule: String, steps: Int): JSONObject {
        val raw = JSONArray().apply { repeat(steps) { put(1.0 - it.toDouble() / steps) } }
        val shifted = JSONArray().apply {
            repeat(steps) { put(1.0 - it.toDouble() / steps) }
            put(0.0)
        }
        return JSONObject().put("nativeEffective", JSONObject()
            .put("qwenTurboSchedule", schedule)
            .put("qwenRawSigmas", raw)
            .put("qwenShiftedSigmas", shifted))
    }

    @Test
    fun acceptsFourAndSixStepNativeEvidence() {
        QwenImage21TurboSchedule.verifyNativeEcho(result("viggle_v021_4", 4), "viggle_v021_4")
        QwenImage21TurboSchedule.verifyNativeEcho(result("viggle_v021_6", 6), "viggle_v021_6")
    }

    @Test
    fun rejectsMissingOrIncorrectNativeSchedule() {
        assertThrows(IllegalArgumentException::class.java) {
            QwenImage21TurboSchedule.verifyNativeEcho(result("viggle_v021_6", 6), "viggle_v021_4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            val native = result("viggle_v021_4", 4).getJSONObject("nativeEffective")
            native.getJSONArray("qwenShiftedSigmas").put(4, 0.25)
            QwenImage21TurboSchedule.verifyNativeEcho(JSONObject().put("nativeEffective", native), "viggle_v021_4")
        }
        assertThrows(IllegalArgumentException::class.java) {
            QwenImage21TurboSchedule.verifyNativeEcho(result("viggle_v021_4", 4), null)
        }
    }
}
