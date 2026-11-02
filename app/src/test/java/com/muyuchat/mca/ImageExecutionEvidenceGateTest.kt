package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExecutionEvidenceGateTest {
    @Test
    fun mediaTekEvidenceUsesItsOwnBackendRuntimeAndDigest() {
        requireNativeImageExecutionEvidence(
            JSONObject()
                .put("backend", "MEDIATEK_NEURON")
                .put("runtime", "MEDIATEK_NEURON")
                .put("outputSha256", "a".repeat(64)),
            LocalImageRuntime.MEDIATEK_NEURON,
        )
    }

    @Test
    fun mediaTekEvidenceFailsClosedWithoutADigestOrWithAFallback() {
        assertRejected("output digest") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("backend", "MEDIATEK_NEURON")
                    .put("runtime", "MEDIATEK_NEURON"),
                LocalImageRuntime.MEDIATEK_NEURON,
            )
        }
        assertRejected("must not report a fallback") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("backend", "MEDIATEK_NEURON")
                    .put("runtime", "MEDIATEK_NEURON")
                    .put("outputSha256", "b".repeat(64))
                    .put("fallback", true),
                LocalImageRuntime.MEDIATEK_NEURON,
            )
        }
        assertRejected("did not identify the MediaTek Neuron backend") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("backend", "QNN_HTP")
                    .put("runtime", "QNN_HTP")
                    .put("outputSha256", "c".repeat(64)),
                LocalImageRuntime.MEDIATEK_NEURON,
            )
        }
    }

    @Test
    fun qnnKeepsItsNativeEffectiveAndGraphEvidenceContract() {
        requireNativeImageExecutionEvidence(
            JSONObject()
                .put("nativeExecution", true)
                .put("fallback", false)
                .put("nativeGenerationSequence", 3)
                .put("npuActive", true)
                .put("qnnGraphExecution", true)
                .put("nativeEffective", JSONObject().put("runtime", "QNN_HTP")),
            LocalImageRuntime.QNN_HTP,
        )
        assertRejected("nativeEffective execution evidence") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("nativeExecution", true)
                    .put("fallback", false)
                    .put("nativeGenerationSequence", 3)
                    .put("npuActive", true)
                    .put("qnnGraphExecution", true),
                LocalImageRuntime.QNN_HTP,
            )
        }
        assertRejected("positive native generation sequence") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("nativeExecution", true)
                    .put("fallback", false)
                    .put("npuActive", true)
                    .put("qnnGraphExecution", true)
                    .put("nativeEffective", JSONObject().put("runtime", "QNN_HTP")),
                LocalImageRuntime.QNN_HTP,
            )
        }
        assertRejected("did not prove native QNN graph execution") {
            requireNativeImageExecutionEvidence(
                JSONObject()
                    .put("nativeExecution", true)
                    .put("fallback", false)
                    .put("nativeGenerationSequence", 1)
                    .put("nativeEffective", JSONObject().put("runtime", "QNN_HTP")),
                LocalImageRuntime.QNN_HTP,
            )
        }
    }

    private fun assertRejected(expected: String, action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("expected '${error.message}' to contain '$expected'", error.message.orEmpty().contains(expected))
            return
        }
        throw AssertionError("expected an IllegalArgumentException containing '$expected'")
    }
}
