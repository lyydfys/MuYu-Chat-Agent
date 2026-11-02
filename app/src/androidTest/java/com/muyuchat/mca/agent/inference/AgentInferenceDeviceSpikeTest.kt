package com.muyuchat.mca.agent.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * K04 G0 device spike skeleton.
 *
 * NOT_RUN by design: the Lead has not provided the device + real MNN/GGUF/cloud model configuration
 * required for the G0 evidence (architecture 5.2/6.1). This test must be implemented and turned on
 * only with that configuration; it must never fabricate a real-model PASS from a fake executor.
 */
@RunWith(AndroidJUnit4::class)
class AgentInferenceDeviceSpikeTest {

    @Ignore("NOT_RUN: requires Lead-provided device + real MNN/GGUF model configuration")
    @Test
    fun localPromptedRoundTripAndScopedCancel() {
        // Intentionally empty: real G0 evidence is blocked on device/model configuration.
    }

    @Ignore("NOT_RUN: requires a controlled Responses endpoint with native tool calling")
    @Test
    fun responsesStrictTerminalRoundTrip() {
        // Intentionally empty: real cloud G0 evidence is blocked on endpoint configuration.
    }
}
