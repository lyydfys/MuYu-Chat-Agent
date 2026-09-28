package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class QwenImage21GgufExecutionEvidenceTest {
    private val completedPhases = listOf(
        "qwenTwoStage",
        "qwenTextEncoderPhase",
        "qwenTextConditionEncoded",
        "qwenTextEncoderReleasedBeforeDiffusion",
        "qwenDiffusionVaePhase",
    )

    private fun completedResult(): JSONObject {
        val native = JSONObject()
            .put("actualModelVariant", "QWEN_IMAGE_21")
            .put("nativeScheduler", "flux")
        completedPhases.forEach { native.put(it, true) }
        return JSONObject().put("nativeEffective", native)
    }

    @Test
    fun acceptsCompletedTwoStageExecution() {
        requireQwenImage21GgufExecutionEvidence(completedResult())
    }

    @Test
    fun rejectsMissingOrFalseNativePhases() {
        completedPhases.forEach { phase ->
            val missing = completedResult().getJSONObject("nativeEffective")
            missing.remove(phase)
            assertThrows("missing $phase", IllegalArgumentException::class.java) {
                requireQwenImage21GgufExecutionEvidence(
                    JSONObject().put("nativeEffective", missing)
                )
            }

            val falsePhase = completedResult()
                .getJSONObject("nativeEffective")
                .put(phase, false)
            assertThrows("false $phase", IllegalArgumentException::class.java) {
                requireQwenImage21GgufExecutionEvidence(
                    JSONObject().put("nativeEffective", falsePhase)
                )
            }
        }
    }

    @Test
    fun rejectsWrongArchitectureOrScheduler() {
        listOf(
            "actualModelVariant" to "QWEN_IMAGE",
            "nativeScheduler" to "simple",
        ).forEach { (field, value) ->
            val native = completedResult().getJSONObject("nativeEffective").put(field, value)
            assertThrows("wrong $field", IllegalArgumentException::class.java) {
                requireQwenImage21GgufExecutionEvidence(
                    JSONObject().put("nativeEffective", native)
                )
            }
        }
    }

    @Test
    fun rejectsMissingNativeEvidence() {
        assertThrows(IllegalStateException::class.java) {
            requireQwenImage21GgufExecutionEvidence(JSONObject())
        }
    }
}
