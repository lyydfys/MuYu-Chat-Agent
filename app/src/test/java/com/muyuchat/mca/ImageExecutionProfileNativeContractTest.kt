package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageExecutionProfileNativeContractTest {
    @Test
    fun `resolved request serializes supported capability but never native-only weighting proof`() {
        val resolution = resolution()
        val resolved = resolution.layers.resolved
        val json = ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)

        assertTrue(
            json.keys().asSequence().toSet()
                .containsAll(ImageExecutionProfileNativeContract.resolvedRequestFields)
        )
        assertTrue(
            ImageExecutionProfileNativeContract.nativeEvidenceOnlyFields.none(json::has)
        )
        assertEquals(resolved.profileId, json.getString("profileId"))
        assertEquals(resolved.profileRevision, json.getInt("profileRevision"))
        assertEquals(resolved.modelFingerprint, json.getString("modelFingerprint"))
        assertEquals(resolved.runtime.name, json.getString("runtime"))
        assertEquals(resolved.scheduler.name, json.getString("scheduler"))
        assertEquals(resolved.predictionType.name, json.getString("predictionType"))
        assertEquals(resolved.steps, json.getInt("steps"))
        assertEquals(resolved.timetableCount, json.getInt("timetableCount"))
        assertEquals(resolved.unetExecutionCount, json.getInt("unetExecutionCount"))
        assertEquals(resolved.cfgScale, json.getDouble("cfgScale"), 0.0)
        assertEquals(resolved.useCfg, json.getBoolean("useCfg"))
        assertEquals(resolved.unconditionalBranch, json.getBoolean("unconditionalBranch"))
        assertEquals(resolved.tokenizerBackend.name, json.getString("tokenizerBackend"))
        assertEquals(resolved.tokenCount, json.getInt("tokenCount"))
        assertEquals(
            resolved.promptWeightingSupported,
            json.getBoolean("promptWeightingSupported")
        )
        assertEquals(resolved.embeddingDiskDataType.name, json.getString("embeddingDiskDataType"))
        assertEquals(resolved.vaeScalingLocation.name, json.getString("vaeScalingLocation"))
        assertEquals(resolved.vaeScalingFactor, json.getDouble("vaeScalingFactor"), 0.0)
        assertEquals(resolved.width, json.getInt("width"))
        assertEquals(resolved.height, json.getInt("height"))
        assertEquals(resolved.seed, json.getLong("seed"))
        assertEquals(resolved.graphName, json.getString("graphName"))
        assertEquals(resolved.fallback, json.getBoolean("fallback"))
        assertEquals(
            resolution.profile.vae.outputRange.name,
            json.getString("pixelRange")
        )
        assertEquals(resolution.profile.scheduler.numTrainTimesteps, json.getInt("numTrainTimesteps"))
        assertEquals(resolved.timetableCount, json.getInt("expectedTimetableCount"))
        assertEquals(resolved.unetExecutionCount, json.getInt("expectedUnetExecutionCount"))
    }

    @Test
    fun `complete native echo parses and validates`() {
        val resolution = resolution()
        val raw = nativeEcho(resolution).toString()

        val result = ImageExecutionProfileNativeContract.parseAndValidate(resolution, raw)

        assertTrue(result.validation.valid)
        assertEquals(resolution.layers.resolved.profileId, result.nativeEffective.profileId)
        assertEquals(resolution.layers.resolved.seed, result.nativeEffective.seed)
        assertEquals(resolution.layers.resolved.unetExecutionCount, result.nativeEffective.unetExecutionCount)
        assertEquals(false, result.nativeEffective.promptWeightingApplied)
        assertEquals("c".repeat(64), result.nativeEffective.promptWeightFingerprint)
        assertEquals(resolution.profile.vae.outputRange, result.pixelRange)
    }

    @Test
    fun `qnn pixel range is strict while other runtimes keep the shared contract unchanged`() {
        val resolution = resolution()
        val missing = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").remove("pixelRange")
        }
        assertEquals("pixelRange", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, missing)
        }.field)

        val unknown = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("pixelRange", "AUTO_FROM_MIN_MAX")
        }
        assertEquals("pixelRange", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, unknown)
        }.field)

        val mismatch = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("pixelRange", ImagePixelRange.ZERO_TO_ONE.name)
        }
        val mismatchError = expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, mismatch)
        }
        assertEquals(EXECUTION_CONTRACT_MISMATCH, mismatchError.code)
        assertEquals("pixelRange", mismatchError.field)

        listOf(
            LocalImageRuntime.MNN_DIFFUSION,
            LocalImageRuntime.STABLE_DIFFUSION_CPP
        ).forEach { runtime ->
            val nonQnn = resolutionForRuntime(runtime)
            val params = ImageExecutionProfileNativeContract.toNativeParamsJson(nonQnn)
            assertTrue(!params.has("pixelRange"))
            val parsed = ImageExecutionProfileNativeContract.parseAndValidate(
                nonQnn,
                nativeEcho(nonQnn)
            )
            assertEquals(null, parsed.pixelRange)
        }
    }

    @Test
    fun `every required native field fails when missing`() {
        val resolution = resolution()
        ImageExecutionProfileNativeContract.requiredFields.forEach { field ->
            val json = nativeEcho(resolution).apply {
                getJSONObject("nativeEffective").remove(field)
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `unknown native enum values fail instead of falling back`() {
        val resolution = resolution()
        listOf(
            "runtime",
            "scheduler",
            "predictionType",
            "tokenizerBackend",
            "embeddingDiskDataType",
            "vaeScalingLocation"
        ).forEach { field ->
            val json = nativeEcho(resolution).apply {
                getJSONObject("nativeEffective").put(field, "UNKNOWN_VALUE")
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
            assertEquals(field, error.field)
        }
    }

    @Test
    fun `all covered execution mismatches fail validator`() {
        val resolution = resolution()
        val mutations = linkedMapOf<String, (JSONObject) -> Unit>(
            "profileId" to { it.put("profileId", "generic.compat.changed") },
            "profileRevision" to { it.put("profileRevision", it.getInt("profileRevision") + 1) },
            "modelFingerprint" to { it.put("modelFingerprint", "b".repeat(64)) },
            "runtime" to { it.put("runtime", LocalImageRuntime.MNN_DIFFUSION.name) },
            "scheduler" to { it.put("scheduler", ImageSchedulerAlgorithm.EULER.name) },
            "predictionType" to { it.put("predictionType", ImagePredictionType.V_PREDICTION.name) },
            "steps" to { it.put("steps", it.getInt("steps") + 1) },
            "timetableCount" to { it.put("timetableCount", it.getInt("timetableCount") + 1) },
            "unetExecutionCount" to { it.put("unetExecutionCount", it.getInt("unetExecutionCount") + 1) },
            "cfgScale" to { it.put("cfgScale", it.getDouble("cfgScale") + 0.5) },
            "useCfg" to { it.put("useCfg", !it.getBoolean("useCfg")) },
            "unconditionalBranch" to {
                it.put("unconditionalBranch", !it.getBoolean("unconditionalBranch"))
            },
            "tokenizerBackend" to { it.put("tokenizerBackend", ImageTokenizerBackend.TOKENIZERS_CPP.name) },
            "tokenCount" to { it.put("tokenCount", it.getInt("tokenCount") + 1) },
            "promptWeightingSupported" to {
                it.put("promptWeightingSupported", !it.getBoolean("promptWeightingSupported"))
            },
            "embeddingDiskDataType" to {
                it.put("embeddingDiskDataType", ImageEmbeddingDiskDataType.FP16.name)
            },
            "vaeScalingLocation" to {
                it.put("vaeScalingLocation", ImageVaeScalingLocation.HOST_BEFORE_GRAPH.name)
            },
            "vaeScalingFactor" to {
                it.put("vaeScalingFactor", it.getDouble("vaeScalingFactor") + 0.5)
            },
            "width" to { it.put("width", it.getInt("width") + 8) },
            "height" to { it.put("height", it.getInt("height") + 8) },
            "seed" to { it.put("seed", it.getLong("seed") + 1L) },
            "graphName" to { it.put("graphName", "different-graph") },
            "fallback" to { it.put("fallback", !it.getBoolean("fallback")) }
        )

        assertEquals(ImageExecutionProfileNativeContract.resolvedRequestFields, mutations.keys)
        mutations.forEach { (field, mutate) ->
            val json = nativeEcho(resolution).apply {
                mutate(getJSONObject("nativeEffective"))
            }
            val error = expectFailure {
                ImageExecutionProfileNativeContract.parseAndValidate(resolution, json)
            }

            assertEquals(EXECUTION_CONTRACT_MISMATCH, error.code)
            assertEquals(field, error.field)
            assertEquals(field, error.mismatches.single().field)
        }
    }

    @Test
    fun `fractional and non numeric native numbers fail strict parsing`() {
        val resolution = resolution()
        val fractional = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("steps", 20.5)
        }
        val nonNumeric = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("cfgScale", "7.0")
        }

        assertEquals("steps", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, fractional)
        }.field)
        assertEquals("cfgScale", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, nonNumeric)
        }.field)
    }

    @Test
    fun `flat top-level echo cannot replace native effective evidence`() {
        val resolution = resolution()
        val flat = ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)

        val error = expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, flat)
        }

        assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
        assertEquals("nativeEffective", error.field)
    }

    @Test
    fun `wrapping resolved request cannot forge native prompt weighting evidence`() {
        val resolution = resolution()
        val wrappedRequest = JSONObject().put(
            "nativeEffective",
            ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)
        )

        val error = expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, wrappedRequest)
        }

        assertEquals(IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID, error.code)
        assertEquals("promptWeightingApplied", error.field)
    }

    @Test
    fun `native weighting evidence is internally coherent and fingerprinted`() {
        val resolution = weightingResolution()
        val applied = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective")
                .put("promptWeightingApplied", true)
                .put("positiveWeightedTokenCount", 2)
                .put("negativeWeightedTokenCount", 1)
                .put("promptWeightFingerprint", "d".repeat(64))
        }

        val parsed = ImageExecutionProfileNativeContract.parseAndValidate(resolution, applied)
        assertEquals(true, parsed.nativeEffective.promptWeightingApplied)
        assertEquals(2, parsed.nativeEffective.positiveWeightedTokenCount)
        assertEquals(1, parsed.nativeEffective.negativeWeightedTokenCount)
        assertEquals("d".repeat(64), parsed.nativeEffective.promptWeightFingerprint)

        val appliedWithoutTokens = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("promptWeightingApplied", true)
        }
        assertEquals("promptWeightingApplied", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, appliedWithoutTokens)
        }.field)

        val countsWithoutApplied = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("positiveWeightedTokenCount", 1)
        }
        assertEquals("promptWeightingApplied", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, countsWithoutApplied)
        }.field)

        val invalidFingerprint = nativeEcho(resolution).apply {
            getJSONObject("nativeEffective").put("promptWeightFingerprint", "request-echo")
        }
        assertEquals("promptWeightFingerprint", expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(resolution, invalidFingerprint)
        }.field)
    }

    @Test
    fun `unsupported profile rejects native weighting claims through validator`() {
        val supported = weightingResolution()
        val unsupported = supported.copy(
            profile = supported.profile.copy(
                tokenizer = supported.profile.tokenizer.copy(supportsPromptWeighting = false),
                capabilities = supported.profile.capabilities.copy(supportsPromptWeighting = false)
            ),
            layers = supported.layers.copy(
                resolved = supported.layers.resolved.copy(promptWeightingSupported = false)
            )
        )
        val forged = nativeEcho(unsupported).apply {
            getJSONObject("nativeEffective")
                .put("promptWeightingSupported", false)
                .put("promptWeightingApplied", true)
                .put("positiveWeightedTokenCount", 1)
        }

        val error = expectFailure {
            ImageExecutionProfileNativeContract.parseAndValidate(unsupported, forged)
        }

        assertEquals(EXECUTION_CONTRACT_MISMATCH, error.code)
        assertTrue(error.mismatches.any { it.field == "promptWeightingApplied" })
        assertTrue(error.mismatches.any { it.field == "positiveWeightedTokenCount" })
    }

    private fun nativeEcho(resolution: ImageExecutionProfileResolution): JSONObject =
        JSONObject().put(
            "nativeEffective",
            ImageExecutionProfileNativeContract.toNativeParamsJson(resolution)
                .put("promptWeightingApplied", false)
                .put("positiveWeightedTokenCount", 0)
                .put("negativeWeightedTokenCount", 0)
                .put("promptWeightFingerprint", "c".repeat(64))
        )

    private fun resolution(): ImageExecutionProfileResolution =
        ImageExecutionProfileResolver.resolve(
            ImageExecutionProfileResolverInput(
                modelFingerprint = "a".repeat(64),
                runtime = LocalImageRuntime.QNN_HTP,
                family = LocalImageModelFamily.CUSTOM,
                recommendationId = null,
                deviceHints = ImageDeviceExecutionHints(localProfileKnown = false)
            )
        )

    private fun weightingResolution(): ImageExecutionProfileResolution {
        val base = resolution()
        return base.copy(
            profile = base.profile.copy(
                tokenizer = base.profile.tokenizer.copy(supportsPromptWeighting = true),
                capabilities = base.profile.capabilities.copy(supportsPromptWeighting = true)
            ),
            layers = base.layers.copy(
                resolved = base.layers.resolved.copy(promptWeightingSupported = true)
            )
        )
    }

    private fun resolutionForRuntime(runtime: LocalImageRuntime): ImageExecutionProfileResolution {
        val base = resolution()
        return base.copy(
            profile = base.profile.copy(runtime = runtime),
            layers = base.layers.copy(
                resolved = base.layers.resolved.copy(runtime = runtime)
            )
        )
    }

    private fun expectFailure(block: () -> Unit): ImageNativeExecutionContractException = try {
        block()
        fail("Expected native execution contract failure.")
        throw AssertionError("unreachable")
    } catch (error: ImageNativeExecutionContractException) {
        error
    }
}
