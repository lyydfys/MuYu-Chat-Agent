package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextualInversionQnnMnnEvidenceTest {
    @Test
    fun qnnPromotionRequiresTheExactNativeConsumedConditioningArtifact() {
        val selection = selection(TextualInversionRuntime.QNN_HTP)
        val encoded = encodedEvidence(selection, clipG = false)
        val result = qnnResult(CONDITIONING_SHA256, split = false)

        val verified = promoteConsumedQnnTextualInversionEvidence(
            result = result,
            encoded = encoded,
            selection = selection,
            expectedConditioningSha256 = CONDITIONING_SHA256,
            splitWorkers = false
        )

        assertEquals(selection.bindingFingerprint, verified.bindingFingerprint)
        assertEquals("conditioning_consumed", verified.bindingStage)
        assertEquals(
            "conditioning_consumed",
            result.getJSONObject("textualInversionEvidence").getString("bindingStage")
        )
        assertThrows(IllegalArgumentException::class.java) {
            promoteConsumedQnnTextualInversionEvidence(
                result = qnnResult("d".repeat(64), split = false),
                encoded = encoded,
                selection = selection,
                expectedConditioningSha256 = CONDITIONING_SHA256,
                splitWorkers = false
            )
        }
    }

    @Test
    fun splitQnnRequiresACompletedUnetPhaseProofBeforePromotion() {
        val selection = selection(TextualInversionRuntime.QNN_HTP)
        val encoded = encodedEvidence(selection, clipG = true)

        promoteConsumedQnnTextualInversionEvidence(
            result = qnnResult(CONDITIONING_SHA256, split = true),
            encoded = encoded,
            selection = selection,
            expectedConditioningSha256 = CONDITIONING_SHA256,
            splitWorkers = true
        )

        assertThrows(IllegalArgumentException::class.java) {
            val missingProof = qnnResult(CONDITIONING_SHA256, split = true)
            missingProof.remove("sdxlPhaseProof")
            promoteConsumedQnnTextualInversionEvidence(
                result = missingProof,
                encoded = encoded,
                selection = selection,
                expectedConditioningSha256 = CONDITIONING_SHA256,
                splitWorkers = true
            )
        }
    }

    @Test
    fun mnnFinalEvidenceMustReportNativeConditioningConsumption() {
        val selection = selection(TextualInversionRuntime.MNN_DIFFUSION)
        val consumed = encodedEvidence(selection, clipG = false).apply {
            getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 1)
                .put("consumedMask", 1)
                .put("bindingStage", "conditioning_consumed")
        }
        val result = JSONObject(consumed.toString())
            .put("nativeEffective", JSONObject(consumed.toString()))

        val verified = verifyStableDiffusionTextualInversionEvidence(
            result = result,
            selection = selection,
            expectedNativeMode = TextualInversionRuntime.MNN_DIFFUSION.nativeMode
        )

        assertEquals(selection.bindingFingerprint, verified.bindingFingerprint)
        assertEquals("conditioning_consumed", verified.bindingStage)
    }

    @Test
    fun stableFinalEvidenceAllowsConsumptionInBothCfgPromptsButRequiresLayerAgreement() {
        val selection = selection(TextualInversionRuntime.STABLE_DIFFUSION_CPP)
        val consumed = encodedEvidence(selection, clipG = false).apply {
            getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 2)
                .put("consumedMask", 1)
                .put("bindingStage", "conditioning_consumed")
        }
        val result = JSONObject(consumed.toString())
            .put("nativeEffective", JSONObject(consumed.toString()))

        val verified = verifyStableDiffusionTextualInversionEvidence(result, selection)

        assertEquals(2L, verified.conditioningConsumptionCount)
        val mismatched = JSONObject(result.toString()).apply {
            getJSONObject("nativeEffective")
                .getJSONObject("textualInversionEvidence")
                .put("conditioningConsumptionCount", 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            verifyStableDiffusionTextualInversionEvidence(mismatched, selection)
        }
    }

    private fun selection(runtime: TextualInversionRuntime): TextualInversionSelection {
        val id = "00000000-0000-0000-0000-000000000001"
        val artifact = TextualInversionArtifact(
            id = id,
            name = "runtime style",
            trigger = "<runtime-style>",
            fileName = "$id.safetensors",
            path = "/data/user/0/com.muyuchat.mca/no_backup/textual_inversions/$id.safetensors",
            sha256 = "a".repeat(64),
            sizeBytes = 16,
            format = TextualInversionFormat.SAFETENSORS
        )
        return TextualInversionSelection(
            listOf(
                artifact.bind(
                    modelFingerprint = "b".repeat(64),
                    tokenizerFingerprint = "c".repeat(64),
                    profileId = "host-clip-test",
                    profileRevision = 4,
                    runtime = runtime
                )
            )
        )
    }

    private fun encodedEvidence(
        selection: TextualInversionSelection,
        clipG: Boolean
    ): JSONObject {
        val binding = selection.bindings.single()
        val requiredMask = if (clipG) 1 else 0
        return JSONObject()
            .put(
                "textualInversions",
                JSONArray().put(
                    JSONObject()
                        .put("id", binding.artifact.id)
                        .put("trigger", binding.artifact.trigger)
                        .put("sha256", binding.artifact.sha256)
                        .put("sizeBytes", binding.artifact.sizeBytes)
                        .put("format", binding.artifact.format.wireName)
                        .put("modelFingerprint", binding.modelFingerprint)
                        .put("tokenizerFingerprint", binding.tokenizerFingerprint)
                        .put("profileId", binding.profileId)
                        .put("profileRevision", binding.profileRevision)
                        .put("runtime", binding.runtime.wireName)
                        .put("bindingFingerprint", binding.bindingFingerprint)
                )
            )
            .put(
                "textualInversionEvidence",
                JSONObject()
                    .put("requestedCount", 1)
                    .put("validatedCount", 1)
                    .put("loadAttemptCount", 1)
                    .put("loadedCount", 1)
                    .put("tokenizerMatchCount", 1)
                    .put("appliedCount", 1)
                    .put("appliedVectorCount", 2)
                    .put("conditioningConsumptionCount", 0)
                    .put("clipLAppliedCount", 1)
                    .put("clipGAppliedCount", requiredMask)
                    .put("requestedMask", 1)
                    .put("loadedMask", 1)
                    .put("tokenizerMatchMask", 1)
                    .put("appliedMask", 1)
                    .put("consumedMask", 0)
                    .put("clipLMask", 1)
                    .put("clipGMask", requiredMask)
                    .put("clipGRequiredMask", requiredMask)
                    .put("bindingFingerprint", selection.bindingFingerprint)
                    .put("nativeMode", selection.bindings.single().runtime.nativeMode)
                    .put("bindingStage", "conditioning_encoded")
                    .put("failureCode", "none")
            )
    }

    private fun qnnResult(conditioningSha256: String, split: Boolean): JSONObject {
        fun layer(): JSONObject = JSONObject()
            .put("conditioningArtifactSha256", conditioningSha256)
            .put("conditioningArtifactConsumed", !split)
            .put("unetExecutionCount", if (split) 2 else 1)
            .apply { if (split) put("sdxlPhaseProof", JSONObject().put("unetExecutionCount", 2)) }

        val inner = layer()
        return layer().put("nativeEffective", inner)
    }

    private companion object {
        val CONDITIONING_SHA256: String = "e".repeat(64)
    }
}
